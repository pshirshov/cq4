package cq.host

import cq.api.*
import cq.core.{DomainFailure, Scope, WorkspaceService}
import io.circe.Json
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Clock
import scala.util.Using
import zio.{IO, Promise, Ref, ZIO}

final case class JobCommand(arguments: List[String], environment: Map[String, String], input: String, limits: ExecutionLimits) {
  private val MaxInputBytes = 256 * 1024
  private val MaxLaunchBytes = 1024 * 1024
  require(arguments.nonEmpty && arguments.forall(v => !v.contains('\u0000')), "Invalid job arguments")
  require(UTF_8.newEncoder().canEncode(input) && input.getBytes(UTF_8).length <= MaxInputBytes, "Job input exceeds its Unicode/byte bounds")
  private val encoded = Json.arr(
    Json.arr(arguments.map(Json.fromString)*),
    Json.arr(environment.toList.sortBy(_._1).map { case (key, value) => Json.arr(Json.fromString(key), Json.fromString(value)) }*),
    Json.fromString(input),
    Json.arr(List(limits.startup, limits.execution, limits.heartbeat, limits.grace, limits.kill).map(d => Json.fromLong(d.toMillis))*),
    Json.fromInt(limits.outputBytes),
  ).noSpaces.getBytes(UTF_8)
  require(encoded.length <= MaxLaunchBytes, "Job launch exceeds its byte bound")
  val fingerprint: String = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded))
}

final class JobSupervisor private (owner: Scope, repository: JobRepository, workspaces: WorkspaceService[IO],
  driver: ExecutionDriver, payloadRoot: Path, clock: Clock, state: Ref.Synchronized[JobSupervisor.State]) {
  import JobSupervisor.*
  private def authorized(scope: Scope): Unit = {
    if (scope.project != owner.project || scope.actor.session != owner.actor.session || !Set(Role.Governor, Role.Human)(scope.actor.role))
      throw DomainFailure(Fault.Denied("Job control requires its governing project/session"))
  }
  private def read(attempt: AttemptId): JobRecord = repository.records.find(_.workspace.attempt == attempt)
    .getOrElse(throw DomainFailure(Fault.Missing("Job is not registered")))
  private def update(attempt: AttemptId)(change: JobRecord => JobRecord): JobRecord = {
    val previous = read(attempt)
    val changed = change(previous)
    if (changed == previous) previous else {
      val next = changed.copy(revision = Math.addExact(previous.revision, 1), updatedAt = math.max(previous.updatedAt, clock.millis()))
      repository.replace(previous, next)
      next
    }
  }
  private def healthy(current: State): Unit = current.failure.foreach(failure => throw new IllegalStateException("Supervisor storage/quarantine failed; new work is disabled", failure))
  private def mutate[A](operation: State => IO[Throwable, (A, State)]): IO[Throwable, A] = state.modifyZIO { current =>
    operation(current).fold(
      failure => {
        val next = failure match {
          case _: DomainFailure | _: IllegalArgumentException => current
          case _ => current.copy(failure = Some(failure))
        }
        (Left(failure), next)
      },
      { case (value, next) => (Right(value), next) },
    )
  }.flatMap(ZIO.fromEither(_))

  def status(scope: Scope, attempt: AttemptId): IO[Throwable, JobRecord] = state.get.flatMap { current => ZIO.attemptBlocking {
    authorized(scope)
    healthy(current)
    read(attempt)
  } }

  def start(scope: Scope, workspace: WorkspaceSpec, command: JobCommand): IO[Throwable, JobRecord] = ZIO.uninterruptible {
    mutate { current =>
      for {
        reserved <- ZIO.attemptBlocking {
          authorized(scope)
          healthy(current)
          require(!current.closing, "Supervisor is closing")
          require(workspace.project == owner.project && workspace.owner == owner.actor.session, "Job workspace has another owner")
          repository.reserve(workspace, command.fingerprint, clock.millis())
        }
        (record, fresh) = reserved
        done <- Promise.make[Throwable, Unit]
        next <- if (!fresh) ZIO.succeed(current) else {
          workflow(workspace, command).exit.flatMap(done.done).forkDaemon.as(current.copy(jobs = current.jobs.updated(workspace.attempt, Live(None, done))))
        }
      } yield (record, next)
    }
  }

  def cancel(scope: Scope, attempt: AttemptId): IO[Throwable, JobRecord] = ZIO.uninterruptible {
    mutate { current =>
      ZIO.attempt(authorized(scope)) *> ZIO.attemptBlocking {
        val record = update(attempt)(previous => if (JobRecords.terminal(previous.phase)) previous else previous.copy(target = JobTarget.Stop))
        (record, current)
      }.ensuring(ZIO.succeed(current.jobs.get(attempt).flatMap(_.execution).foreach(_.cancel())))
    }
  }

  def await(scope: Scope, attempt: AttemptId): IO[Throwable, JobRecord] = for {
    _ <- ZIO.attempt(authorized(scope))
    current <- state.get
    _ <- ZIO.foreachDiscard(current.jobs.get(attempt))(_.done.await)
    result <- status(scope, attempt)
  } yield result

  private def recover: IO[Throwable, Unit] = for {
    records <- ZIO.attemptBlocking(repository.records)
    _ <- ZIO.foreachDiscard(records) { record =>
      if (record.phase == JobPhase.Settled) ZIO.unit
      else ZIO.attemptBlocking {
        update(record.workspace.attempt)(_.copy(target = JobTarget.Stop, phase = JobPhase.Uncertain,
          problem = Some("Previous supervisor ended without a confirmed job acknowledgement; no saved process identity was adopted")))
      } *> quarantine(record.workspace.attempt, "Supervisor restart: execution or acknowledgement is unconfirmed")
    }
  } yield ()

  private def quarantine(attempt: AttemptId, reason: String): IO[Throwable, Unit] =
    workspaces.get(owner, attempt).flatMap(_ => workspaces.quarantine(owner, attempt, reason).unit).catchSome {
      case DomainFailure(_: Fault.Missing) => ZIO.unit
    }

  private def workflow(workspace: WorkspaceSpec, command: JobCommand): IO[Throwable, Unit] = {
    val attempt = workspace.attempt
    val operation = for {
      initial <- ZIO.attemptBlocking(read(attempt))
      prepared <- if (initial.target == JobTarget.Stop) ZIO.succeed(None) else workspaces.prepare(owner, workspace).map(Some(_))
      execution <- state.modifyZIO { current => ZIO.attemptBlocking {
        val previous = read(attempt)
        if (previous.target == JobTarget.Stop || current.closing || current.failure.nonEmpty) {
          update(attempt)(_.copy(target = JobTarget.Stop, phase = JobPhase.Settled))
          (None, current)
        } else {
          val directory = payloadRoot.resolve(attempt.value.toString)
          Files.createDirectories(payloadRoot, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
          Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
          val input = directory.resolve("input")
          Files.createFile(input, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
          Files.writeString(input, command.input)
          Using.resource(FileChannel.open(input, StandardOpenOption.WRITE))(_.force(true))
          update(attempt)(_.copy(phase = JobPhase.Starting))
          val running = driver.start(ExecutionSpec(Path.of(prepared.get.directory), command.arguments, command.environment,
            input, directory.resolve("stdout"), directory.resolve("stderr"), command.limits))
          (Some(running), current.copy(jobs = current.jobs.updated(attempt, current.jobs(attempt).copy(execution = Some(running)))))
        }
      } }
      _ <- ZIO.foreachDiscard(execution)(running => monitor(attempt, running))
    } yield ()
    operation.catchAll { failure =>
      val diagnostic = "Local job failed: " + failure.getClass.getSimpleName
      val settle = for {
        current <- state.get
        _ <- ZIO.foreachDiscard(current.jobs.get(attempt).flatMap(_.execution))(running => ZIO.attemptBlocking(running.close()))
        _ <- ZIO.attemptBlocking(update(attempt)(_.copy(target = JobTarget.Stop, phase = JobPhase.Uncertain, problem = Some(diagnostic))))
        _ <- quarantine(attempt, diagnostic)
      } yield ()
      settle.catchAll { storage =>
        state.update(_.copy(failure = Some(storage))) *> quarantine(attempt, diagnostic).either *> ZIO.fail(storage)
      }
    }
  }

  private def monitor(attempt: AttemptId, running: ManagedExecution): IO[Throwable, Unit] = for {
    observed <- ZIO.attempt(running.status)
    saved <- state.modifyZIO { current => ZIO.attemptBlocking {
      val record = update(attempt) { previous =>
        val observedPhase = observed.phase match {
          case ProcessPhase.Starting => JobPhase.Starting
          case ProcessPhase.Running => JobPhase.Running
          case ProcessPhase.Stopping => JobPhase.Stopping
          case ProcessPhase.Settled => JobPhase.Settled
          case ProcessPhase.Uncertain => JobPhase.Uncertain
        }
        val phase = if (current.failure.nonEmpty) JobPhase.Uncertain else observedPhase
        previous.copy(phase = phase, target = if (phase == JobPhase.Uncertain) JobTarget.Stop else previous.target,
          exit = observed.result.map(r => JobExit(r.code, r.signal, r.reason, r.stdoutBytes, r.stderrBytes, r.settled, r.hostFailure)),
          problem = if (current.failure.nonEmpty) Some("Supervisor storage/quarantine failed; execution admission is uncertain") else observed.problem)
      }
      (record, current)
    } }
    _ <- if (saved.phase == JobPhase.Uncertain) ZIO.attemptBlocking(running.close()) *> quarantine(attempt, "Process hierarchy cleanup or output retention is unconfirmed")
      else if (saved.phase == JobPhase.Settled) ZIO.unit
      else ZIO.sleep(zio.Duration.fromMillis(PollMillis)) *> monitor(attempt, running)
  } yield ()

  private def shutdown: IO[Throwable, Unit] = ZIO.uninterruptible {
    for {
      current <- state.modify(previous => (previous, previous.copy(closing = true)))
      cancellations <- ZIO.foreach(current.jobs.toList) { case (attempt, live) =>
        cancel(owner, attempt).ensuring(ZIO.succeed(live.execution.foreach(_.cancel()))).exit
      }
      completions <- ZIO.foreach(current.jobs.values)(_.done.await.exit)
      _ <- ZIO.foreachDiscard(cancellations ++ completions)(ZIO.done(_))
    } yield ()
  }
}

object JobSupervisor {
  private val PollMillis = 20L
  private final case class Live(execution: Option[ManagedExecution], done: Promise[Throwable, Unit])
  private final case class State(closing: Boolean, failure: Option[Throwable], jobs: Map[AttemptId, Live])

  def acquire(owner: Scope, repository: IO[Throwable, JobRepository], workspaces: WorkspaceService[IO], driver: ExecutionDriver,
    payloadRoot: Path, clock: Clock): ZIO[zio.Scope, Throwable, JobSupervisor] = for {
    _ <- ZIO.attempt {
      require(Set(Role.Governor, Role.Human)(owner.actor.role), "Governing owner required")
      require(payloadRoot.isAbsolute && payloadRoot.normalize() == payloadRoot, "Absolute payload root required")
    }
    stored <- ZIO.acquireRelease(repository)(value => ZIO.attemptBlocking(value.close()).orDie)
    state <- Ref.Synchronized.make(State(false, None, Map.empty))
    supervisor <- ZIO.acquireRelease(ZIO.succeed(new JobSupervisor(owner, stored, workspaces, driver, payloadRoot, clock, state)))(_.shutdown.orDie)
    _ <- supervisor.recover
  } yield supervisor
}
