package cq.server

import cq.api.*
import cq.host.*
import distage.Activation
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.file.{Files, Path}
import java.util.UUID
import scala.util.Try
import zio.ZIO

final class CandidateWorkspaceLocal extends SpecZIO with AssertZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(WorkspaceTestPlugin)), activation = Activation(Repo -> Repo.Prod))
  private def uuid: UUID = UUID.randomUUID()
  private def configuration(local: LocalWorkspaceFixture): SupervisorConfig = {
    val project = ProjectConfig(ProjectId(uuid), "http://localhost", "Candidate capture")
    val assignment = Assignment(AssignmentId(uuid), project.project, Set.empty, Attribution.Unattributed, None, None)
    val attempt = Attempt(AttemptId(uuid), assignment.id, None, SessionId(uuid), Role.Governor, Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Govern)
    val profile = HarnessSetting(Harness.Codex, "/unused/codex", "fixture", "fixture", "0.156.1", Nil, Set.empty)
    val limits = HostLimits(3000, 10000, 1000, 300, 2000, 262144)
    val settings = SupervisorSettings(local.directory.toString, "/unused/guardian", List(profile), limits, Nil, None, Some("refs/heads/integration"))
    val run = SupervisorRun(project, assignment, attempt, profile.version, local.source.toString, local.base, SessionOwnership.Managed)
    SupervisorConfig(settings, project, SupervisorConfig.profile(profile), SupervisorConfig.limits(limits), run, local.directory, "fixture", None, sys.env)
  }

  "Candidate capture (Behavioral Active Blackbox; local Git Good Communication)" should {
    "reject a redirected Git directory before staging can change the governing index" in { (local: LocalWorkspaceFixture) =>
      val settings = configuration(local)
      val candidates = new CandidateWorkspace(settings)
      val fixture = local.fixture
      val spec = fixture.spec(settings.owner)
      for {
        before <- ZIO.attemptBlocking {
          Files.writeString(local.source.resolve("tracked.txt"), "staged governing change\n")
          local.git(local.source, "add", "tracked.txt")
          Files.readAllBytes(local.source.resolve(".git/index")).toList
        }
        workspace <- fixture.service.prepare(settings.owner, spec)
        _ <- ZIO.attemptBlocking {
          val tree = Path.of(workspace.directory)
          Files.writeString(tree.resolve("candidate.txt"), "candidate\n")
          Files.writeString(tree.resolve(".git"), "gitdir: " + local.source.resolve(".git") + "\n")
          val result = Try(candidates.capture(workspace, None, "Candidate\n"))
          val unchanged = Files.readAllBytes(local.source.resolve(".git/index")).toList == before
          println(s"Redirected Git directory: captureRejected=${result.isFailure}, governingIndexUnchanged=$unchanged")
          assert(result.isFailure && unchanged)
        }
        reuse <- fixture.service.prepare(settings.owner, spec).either
        _ <- assertIO(reuse.isLeft)
        retained <- fixture.service.get(settings.owner, spec.attempt)
        _ <- assertIO(retained.admission == WorkspaceAdmission.Quarantined && retained.observed == workspace.observed)
      } yield ()
    }

    "capture a candidate whose project ignore rules already list the worker's .work/ directory" in { (local: LocalWorkspaceFixture) =>
      val settings = configuration(local)
      val candidates = new CandidateWorkspace(settings)
      val fixture = local.fixture
      for {
        ignoring <- ZIO.attemptBlocking {
          Files.writeString(local.source.resolve(".gitignore"), ".work/\n")
          local.git(local.source, "add", ".gitignore")
          local.git(local.source, "-c", "user.name=CQ test", "-c", "user.email=test@localhost", "commit", "-q", "-m", "Ignore .work")
          GitCommit(local.git(local.source, "rev-parse", "HEAD"))
        }
        workspace <- fixture.service.prepare(settings.owner, fixture.spec(settings.owner).copy(base = ignoring))
        commit <- ZIO.attemptBlocking {
          Files.writeString(Path.of(workspace.directory).resolve("change.txt"), "change\n")
          val evidence = Path.of(workspace.directory).resolve(".work/evidence")
          Files.createDirectories(evidence)
          Files.writeString(evidence.resolve("run.log"), "worker log\n")
          candidates.capture(workspace, None, "Candidate\n")
        }
        _ <- ZIO.attemptBlocking {
          val files = local.git(local.source, "ls-tree", "-r", "--name-only", commit.value).linesIterator.toList
          assert(files.contains("change.txt") && !files.exists(_.startsWith(".work/")), files)
        }
      } yield ()
    }

    "start fresh work at the current target head, expect that head only while the candidate descends from it, and record the message" in { (local: LocalWorkspaceFixture) =>
      val settings = configuration(local)
      val candidates = new CandidateWorkspace(settings)
      val fixture = local.fixture
      val attempt = AttemptId(uuid)
      def makeCandidate(name: String, base: GitCommit, message: String) = for {
        workspace <- fixture.service.prepare(settings.owner, fixture.spec(settings.owner).copy(base = base))
        commit <- ZIO.attemptBlocking {
          Files.writeString(Path.of(workspace.directory).resolve(name), name + "\n")
          val evidence = Path.of(workspace.directory).resolve(".work/evidence")
          Files.createDirectories(evidence)
          Files.writeString(evidence.resolve("run.log"), "worker log\n")
          candidates.capture(workspace, None, message)
        }
      } yield commit
      val message = s"T1 Fix the base\n\nAssignment:\n- T1 Fix the base (derived from D77)\n\n${CandidateMessage.AttemptTrailer}: ${attempt.value}\n"
      for {
        _ <- ZIO.attemptBlocking { local.git(local.source, "branch", "integration", local.base.value); () }
        _ <- assertIO(candidates.fresh() == local.base)
        first <- makeCandidate("first.txt", local.base, message)
        second <- makeCandidate("second.txt", first, "Continuation\n")
        other <- makeCandidate("other.txt", local.base, "Competing\n")
        orphan <- ZIO.attemptBlocking {
          assert(local.git(local.source, "log", "-1", "--format=%B", first.value) == message.trim)
          assert(local.git(local.source, "log", "-1", s"--format=%(trailers:key=${CandidateMessage.AttemptTrailer},valueonly)", first.value) == attempt.value.toString)
          assert(local.git(local.source, "log", "-1", "--format=%an <%ae>", first.value) == "CQ host <cq@localhost>")
          // A continuation candidate whose ancestry still reaches the unmoved target head integrates against that head, not its worker base.
          val expectations = List(
            (first, second, local.base), (local.base, first, local.base))
          println(s"Target at base: expected=${expectations.map((base, candidate, _) => candidates.expected(base, candidate).value.take(7))} " +
            s"wanted=${expectations.map(_._3.value.take(7))}")
          expectations.foreach((base, candidate, wanted) => assert(candidates.expected(base, candidate) == wanted))
          local.git(local.source, "update-ref", "refs/heads/integration", first.value)
          println(s"Target at first: fresh=${candidates.fresh().value.take(7)} first=${first.value.take(7)}")
          assert(candidates.fresh() == first)
          assert(candidates.expected(first, second) == first)
          // An incorporated candidate keeps its worker base so inspection reports Incorporated instead of a same-commit update.
          assert(candidates.expected(local.base, first) == local.base)
          local.git(local.source, "update-ref", "refs/heads/integration", other.value)
          assert(candidates.fresh() == other)
          // The target advanced past the candidate's base: the worker base stays expected so the update is NotApplied and combinable.
          assert(candidates.expected(local.base, first) == local.base && candidates.expected(first, second) == first)
          val unconfigured = new CandidateWorkspace(settings.copy(settings = settings.settings.copy(integrationTarget = None)))
          assert(unconfigured.fresh() == local.base && unconfigured.expected(first, second) == first)
          // The worker's .work/ directory never enters the candidate, whatever the project's ignore rules say.
          assert(!local.git(local.source, "ls-tree", "-r", "--name-only", first.value).linesIterator.exists(_.startsWith(".work/")))
          // A target head that does not descend from the session base is still a valid fresh base; foreign commits still are not.
          val orphan = GitCommit(local.git(local.source, "-c", "user.name=CQ test", "-c", "user.email=test@localhost", "commit-tree",
            local.git(local.source, "rev-parse", local.base.value + "^{tree}"), "-m", "Orphan target"))
          local.git(local.source, "update-ref", "refs/heads/integration", orphan.value)
          assert(candidates.fresh() == orphan)
          candidates.verifyBase(orphan)
          orphan
        }
        onOrphan <- makeCandidate("orphan.txt", orphan, "On the orphan head\n")
        _ <- ZIO.attemptBlocking {
          // A host-captured candidate is a valid base for review and continuation even though it does not descend from the session base.
          candidates.verifyBase(onOrphan)
          local.git(local.source, "update-ref", "refs/heads/integration", other.value)
          candidates.verifyBase(onOrphan)
          // An uncaptured commit outside the target and the session base is refused.
          val foreign = GitCommit(local.git(local.source, "-c", "user.name=CQ test", "-c", "user.email=test@localhost", "commit-tree",
            local.git(local.source, "rev-parse", local.base.value + "^{tree}"), "-m", "Foreign commit"))
          assert(scala.util.Try(candidates.verifyBase(foreign)).isFailure)
        }
      } yield ()
    }

    "capture both ordered immutable parents and reject changed HEAD or merge inputs" in { (local: LocalWorkspaceFixture) =>
      val settings = configuration(local)
      val candidates = new CandidateWorkspace(settings)
      val fixture = local.fixture
      def makeCandidate(name: String) = for {
        workspace <- fixture.service.prepare(settings.owner, fixture.spec(settings.owner))
        commit <- ZIO.attemptBlocking {
          Files.writeString(Path.of(workspace.directory).resolve(name), name + "\n")
          candidates.capture(workspace, None, name + "\n")
        }
      } yield commit
      for {
        target <- makeCandidate("left.txt")
        original <- makeCandidate("right.txt")
        plan = CombinationPlan(CombinationTicket(RequestId(uuid), IntegrationId(uuid), Fence(ClaimId(uuid), 1)), settings.owner.project,
          settings.owner.actor, settings.run.attempt.id, local.source.toString, "refs/heads/integration", target, original, ArtifactId(uuid),
          List(ItemRevision(ItemId(settings.owner.project, Ledger.Tasks, 1), Revision(1))))
        before <- ZIO.attemptBlocking {
          local.git(local.source, "branch", "integration", target.value)
          Files.writeString(local.source.resolve("tracked.txt"), "staged governing change\n")
          local.git(local.source, "add", "tracked.txt")
          assert(candidates.observeTarget(original) == target)
          Files.readAllBytes(local.source.resolve(".git/index")).toList
        }
        _ <- ZIO.foreachDiscard(List("valid", "head", "merge-input", "git-directory")) { mode => for {
          workspace <- fixture.service.prepare(settings.owner, fixture.spec(settings.owner).copy(base = target))
          _ <- ZIO.attemptBlocking {
            val tree = Path.of(workspace.directory)
            val inputs = candidates.mergeInputs(plan, workspace.spec.attempt)
            assert(inputs.directory == tree && inputs.base == target && inputs.candidate == original)
            local.git(tree, "-c", "user.name=CQ test", "-c", "user.email=cq@localhost", "merge", "--no-commit", "--no-ff", "--no-edit", "--no-gpg-sign", original.value)
            mode match {
              case "head" => local.git(tree, "-c", "user.name=CQ test", "-c", "user.email=cq@localhost", "commit", "-m", "Unexpected worker commit")
              case "merge-input" => Files.writeString(Path.of(local.git(tree, "rev-parse", "--path-format=absolute", "--git-path", "MERGE_HEAD")), target.value + "\n")
              case "git-directory" => Files.writeString(tree.resolve(".git"), "gitdir: " + local.source.resolve(".git") + "\n")
              case "valid" => ()
            }
            if (mode == "valid") {
              val commit = candidates.capture(workspace, Some(plan), "Combined\n")
              assert(local.git(tree, "rev-list", "--parents", "-n", "1", commit.value).split(" ").toList == List(commit.value, target.value, original.value))
              List("left.txt", "right.txt").foreach(name => assert(local.git(tree, "show", commit.value + ":" + name) == name))
            } else intercept[IllegalArgumentException](candidates.capture(workspace, Some(plan), "Combined\n"))
            assert(Files.readAllBytes(local.source.resolve(".git/index")).toList == before)
          }
        } yield () }
      } yield ()
    }
  }
}
