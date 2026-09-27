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
    val attempt = Attempt(AttemptId(uuid), assignment.id, None, SessionId(uuid), Role.Governor, Harness.Codex, "fixture", "fixture", "fixture", 1000)
    val profile = HarnessSetting(Harness.Codex, "/unused/codex", "fixture", "fixture", "0.156.1", Nil, Set.empty)
    val limits = HostLimits(3000, 10000, 1000, 300, 2000, 262144)
    val settings = SupervisorSettings(local.directory.toString, "/unused/guardian", List(profile), limits, Nil, None, Some("refs/heads/integration"))
    val run = SupervisorRun(project, assignment, attempt, profile.version, local.source.toString, local.base)
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
          val result = Try(candidates.capture(workspace, None))
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

    "capture both ordered immutable parents and reject changed HEAD or merge inputs" in { (local: LocalWorkspaceFixture) =>
      val settings = configuration(local)
      val candidates = new CandidateWorkspace(settings)
      val fixture = local.fixture
      def makeCandidate(name: String) = for {
        workspace <- fixture.service.prepare(settings.owner, fixture.spec(settings.owner))
        commit <- ZIO.attemptBlocking {
          Files.writeString(Path.of(workspace.directory).resolve(name), name + "\n")
          candidates.capture(workspace, None)
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
              val commit = candidates.capture(workspace, Some(plan))
              assert(local.git(tree, "rev-list", "--parents", "-n", "1", commit.value).split(" ").toList == List(commit.value, target.value, original.value))
              List("left.txt", "right.txt").foreach(name => assert(local.git(tree, "show", commit.value + ":" + name) == name))
            } else intercept[IllegalArgumentException](candidates.capture(workspace, Some(plan)))
            assert(Files.readAllBytes(local.source.resolve(".git/index")).toList == before)
          }
        } yield () }
      } yield ()
    }
  }
}
