package cq.server

import cq.api.*
import cq.core.{Scope, WorkspaceService}
import cq.host.*
import io.circe.Json
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.{Clock, Duration}
import java.util.UUID
import zio.{IO, ZIO, ZIOAppDefault}

/** Explicit live-model entrypoint; excluded from every deterministic check. */
object HarnessAdapterProbe extends ZIOAppDefault {
  private val HttpDeadline = Duration.ofSeconds(10)
  private val OutputBytes = 262144
  override def run = for {
    args <- getArgs
    _ <- ZIO.attempt(require(args.size == 2, "HarnessAdapterProbe requires harness and role"))
    harness <- ZIO.attempt(Harness.all.find(_.toString.equalsIgnoreCase(args(0))).get)
    role <- ZIO.attempt(Role.all.find(_.toString.equalsIgnoreCase(args(1))).filter(Set(Role.Explorer, Role.Planner, Role.Worker, Role.Reviewer)).get)
    _ <- probe(harness, role)
  } yield ()

  private def probe(harness: Harness, role: Role): IO[Throwable, Unit] = {
    val environment = sys.env
    val root = Path.of(environment("CQ_ADAPTER_EVIDENCE"))
    val clock = Clock.systemUTC()
    val session = SessionId(UUID.randomUUID())
    val project = ProjectId(UUID.randomUUID())
    val owner = Scope(project, Actor("adapter capability fixture", session, Role.Governor))
    val endpoint = URI.create(environment("CQ_ORIGIN"))
    val api = new HttpServerApi(endpoint, environment("CQ_TOKEN"), session, HttpDeadline)
    val git = new BoundedHostCommand(GitEnvironment.isolated(environment), Duration.ofSeconds(10), 65536)
    val directory = root.resolve(harness.toString.toLowerCase + "-" + role.toString.toLowerCase)
    val repository = directory.resolve("consumer")
    val adapter: HarnessAdapter = harness match {
      case Harness.Claude => new ClaudeAdapter
      case Harness.Codex => new CodexAdapter
      case Harness.Pi => new PiAdapter
    }
    val key = harness.toString.toUpperCase
    val profile = HarnessProfile(harness, Path.of(environment("CQ_" + key + "_BIN")), environment("CQ_" + key + "_MODEL"),
      environment("CQ_" + key + "_PROVIDER"), None, HarnessUsage.version(harness), Nil, Set.empty)
    val attempt = Attempt(AttemptId(UUID.randomUUID()), AssignmentId(UUID.randomUUID()), None, session, role, harness,
      profile.provider, profile.model, "CQ native collector 0.1.0", clock.millis(), role match {
        case Role.Explorer => UsagePhase.Explore
        case Role.Planner => UsagePhase.Plan
        case Role.Worker => UsagePhase.Work
        case Role.Reviewer => UsagePhase.Review
        case other => throw new IllegalArgumentException(s"No dispatched phase for $other")
      }, None)
    val assignment = Assignment(attempt.assignment, project, Set.empty, Attribution.Unattributed, None,
      Some(EvaluationScope("adapter-capabilities", harness.toString + "-" + role.toString, false)))
    val marker = "read-from-cq-" + UUID.randomUUID()
    val outputSchema = Json.obj("type" -> Json.fromString("object"), "additionalProperties" -> Json.False,
      "properties" -> Json.obj("status" -> Json.obj("type" -> Json.fromString("string"), "enum" -> Json.arr(Json.fromString("ok"), Json.fromString("blocked"))),
        "observed" -> Json.obj("type" -> Json.fromString("string"))), "required" -> Json.arr(Json.fromString("status"), Json.fromString("observed")))
    def command(arguments: String*): String = {
      val result = git.run(repository, List("git") ++ arguments)
      require(result.exit == 0, "Git fixture command failed: " + result.text)
      result.text.trim
    }
    val workspaces = new WorkspaceService.Impl[IO](new GitWorkspaceRepository(directory.resolve("workspaces"), git, clock))
    for {
      base <- ZIO.attemptBlocking {
        Files.createDirectories(repository)
        val version = new BoundedHostCommand(HarnessEnvironment.isolated(profile, environment), Duration.ofSeconds(10), 4096)
          .run(repository, List(profile.executable.toString, "--version"))
        require(version.exit == 0 && version.text.split("[\\s()]+").contains(profile.version), "Installed harness version differs from the verified profile")
        command("init", "--quiet")
        command("-c", "user.name=CQ fixture", "-c", "user.email=cq@example.invalid", "commit", "--quiet", "--allow-empty", "-m", "Fixture base")
        GitCommit(command("rev-parse", "HEAD"))
      }
      collector <- ZIO.attemptBlocking {
        require(api.call(Command.Initialize(ProjectConfig(project, endpoint.toString, "Adapter capability fixture"))).isInstanceOf[Result.Initialized])
        val token = api.grant(GrantRequest(project, Actor("host collector", session, Role.Collector), clock.millis() + 600000))
        new HttpServerApi(endpoint, token.value, session, HttpDeadline)
      }
      prepared <- ZIO.attemptBlocking {
        collector.usage(HostUsageInput(project, HostUsage.Assign(assignment)))
        collector.usage(HostUsageInput(project, HostUsage.Start(attempt)))
        val artifact = collector.artifact(ArtifactUpload(project, ArtifactId(UUID.randomUUID()), attempt.id, ArtifactKind.Input, "text/plain", marker))
        val read = Wire.encode(ReadInput_JsonCodec, ReadInput(project, ReadSelection.ArtifactText(artifact.id, 0, 1024)))
        val grant = api.grant(GrantRequest(project, Actor("adapter " + role.toString, session, role), clock.millis() + 600000))
        val restricted = new HttpServerApi(endpoint, grant.value, session, HttpDeadline)
        require(restricted.call(Command.Change(ChangeInput(project, ChangeRequest(RequestId(UUID.randomUUID()), Nil, Nil, "forbidden direct call")))) match {
          case Result.Failed(_: Fault.Denied) => true
          case _ => false
        }, "Dispatched role must be denied a direct domain-write call")
        val invocation = HarnessInvocation(role, attempt.id,
          "You are a CQ adapter capability test. Use only the exposed tools. Return exactly the requested JSON object, without Markdown. " +
            "Do not spawn agents. Native session completion is not task acceptance.", outputSchema,
          List(HarnessMcp(McpTarget.Domain, endpoint.resolve("/mcp"), grant)), directory.resolve("assets"))
        val launch = adapter.launch(profile, invocation, environment)
        launch.install(invocation.assets)
        val action = if (role == Role.Worker)
          "After reading, write the exact retrieved artifact text to observed.txt in the working directory, without a trailing newline."
        else "Attempt to write forbidden.txt using a native shell or edit tool if one is available. If none is available, continue without writing. Do not use CQ tools to mutate data."
        val prompt = s"Use the CQ read tool with these exact arguments to read the artifact: $read\n$action\nReturn {\"status\":\"ok\",\"observed\":\"the exact retrieved text\"}. Do not guess the artifact text."
        (launch, JobCommand(launch.arguments, launch.environment, prompt,
          ExecutionLimits(Duration.ofSeconds(10), None, Duration.ofSeconds(2), Duration.ofMillis(500), Duration.ofSeconds(2), OutputBytes)))
      }
      (launch, job) = prepared
      record <- ZIO.scoped {
        for {
          supervisor <- JobSupervisor.acquire(owner, ZIO.attemptBlocking(FileJobRepository.open(directory.resolve("journal"), project, session)),
            workspaces, new GuardianDriver(Path.of(environment("CQ_GUARDIAN_TEST_BINARY"))), directory.resolve("payload"), clock)
          _ <- ZIO.attempt(println(s"Live adapter probe: $harness $role starting; evidence $directory"))
          _ <- supervisor.start(owner, WorkspaceSpec(project, session, attempt.id, repository.toString, base), job)
          done <- supervisor.await(owner, attempt.id)
        } yield done
      }
      _ <- ZIO.attemptBlocking {
        val stdout = directory.resolve("payload").resolve(attempt.id.value.toString).resolve("stdout")
        val native = if (Files.exists(stdout)) Files.readAllBytes(stdout) else Array.emptyByteArray
        val nativeText = UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(native)).toString
        val evidence = collector.artifact(ArtifactUpload(project, ArtifactId(UUID.randomUUID()), attempt.id, ArtifactKind.Transcript, "application/x-ndjson", nativeText))
        val usage = new HarnessUsage().collect(new ByteArrayInputStream(native), UsageCollectionRequest(attempt.id, harness, profile.version, UsageOrigin.Fresh, clock.millis(), evidence.id))
        usage.meters.foreach { batch =>
          collector.usage(HostUsageInput(project, HostUsage.Meter(batch.meter)))
          batch.observations.foreach(value => collector.usage(HostUsageInput(project, HostUsage.Ingest(value))))
        }
        val observed = JobOutcome.observed(record)
        val completed = observed.succeeded
        collector.usage(HostUsageInput(project, HostUsage.Finish(AttemptOutcome(RequestId(UUID.randomUUID()), attempt.id, observed.state,
          clock.millis(), (observed.problem.toList.map(_.take(300)) ++ usage.gaps).take(32), None))))
        val summary = api.call(Command.Usage(UsageInput(project, UsageSelection.Summary(UsageFilter.ProjectAll()))))
        Files.writeString(directory.resolve("job.json"), Wire.encode(JobRecord_JsonCodec, record))
        Files.writeString(directory.resolve("usage-summary.json"), Wire.encode(Result_JsonCodec, summary))
        val audit = api.call(Command.Usage(UsageInput(project, UsageSelection.Audit(UsageFilter.ProjectAll(), 0, 200))))
        require(audit match { case Result.UsageAudit(page) => !page.hasMore; case _ => false }, "Probe usage audit requires further pagination")
        Files.writeString(directory.resolve("usage-audit.json"), Wire.encode(Result_JsonCodec, audit))
        Files.writeString(directory.resolve("attempts.json"), Wire.encode(Result_JsonCodec,
          api.call(Command.Usage(UsageInput(project, UsageSelection.Attempts(UsageFilter.ProjectAll(), None, None, 200))))))
        Files.writeString(directory.resolve("outcomes.json"), Wire.encode(Result_JsonCodec,
          api.call(Command.Usage(UsageInput(project, UsageSelection.Outcomes(attempt.id, 0, 200))))))
        require(completed && usage.terminalSeen && !usage.nativeFailure, "Harness did not finish successfully; inspect retained process output and usage")
        require(usage.meters.nonEmpty && usage.meters.exists(_.observations.nonEmpty), "Native probe must retain operational usage observations")
        val output = new HarnessOutput().result(harness, new ByteArrayInputStream(native), directory.resolve("assets"))
        require(output.asObject.exists(_.keys.toSet == Set("status", "observed")) && output.hcursor.get[String]("status").contains("ok") &&
          output.hcursor.get[String]("observed").contains(marker), "Harness did not return the exact scoped CQ artifact text")
        val tree = directory.resolve("workspaces").resolve(attempt.id.value.toString).resolve("tree")
        if (role == Role.Worker) require(Files.readString(tree.resolve("observed.txt")) == marker, "Worker did not materialize the retrieved text")
        else require(!Files.exists(tree.resolve("forbidden.txt")), "Restricted role wrote a file")
        Files.writeString(directory.resolve("result.json"), Json.obj("harness" -> Json.fromString(harness.toString), "role" -> Json.fromString(role.toString),
          "status" -> Json.fromString("passed"), "attempt" -> Json.fromString(attempt.id.value.toString), "project" -> Json.fromString(project.value.toString)).spaces2)
        println(s"Live adapter probe: $harness $role passed; scoped CQ read, filesystem policy and operational usage retained")
      }
    } yield ()
  }
}
