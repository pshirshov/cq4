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
    val limits = HostLimits(3000, 1000, 300, 2000, 262144)
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

    "I18: merge a reviewed candidate onto an advanced head with ordered parents, leaving the governing checkout untouched" in { (local: LocalWorkspaceFixture) =>
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
        head <- makeCandidate("left.txt")
        reviewed <- makeCandidate("right.txt")
        _ <- ZIO.attemptBlocking {
          local.git(local.source, "branch", "integration", head.value)
          Files.writeString(local.source.resolve("staged.txt"), "staged governing change\n")
          local.git(local.source, "add", "staged.txt")
          Files.writeString(local.source.resolve("tracked.txt"), "unstaged governing change\n")
          Files.writeString(local.source.resolve("untracked.log"), "operator notes\n")
          def governing = (local.git(local.source, "rev-parse", "HEAD"), local.git(local.source, "symbolic-ref", "HEAD"),
            Files.readAllBytes(local.source.resolve(".git/index")).toList, local.git(local.source, "status", "--porcelain=v1", "--untracked-files=all"),
            Files.readString(local.source.resolve("tracked.txt")))
          val before = governing
          val id = IntegrationId(uuid)
          val message = s"Rebase reviewed candidate\n\n${CandidateMessage.IntegrationTrailer}: ${id.value}\n"
          val result = candidates.rebase(head, reviewed, id, message)
          val merged = result match { case HostRebase.Merged(commit) => commit; case other => fail(s"Clean rebase was not merged: $other") }
          println(s"Host rebase: head=${head.value.take(7)} reviewed=${reviewed.value.take(7)} merged=${merged.value.take(7)} " +
            s"parents=${local.git(local.source, "rev-list", "--parents", "-n", "1", merged.value)}")
          assert(local.git(local.source, "rev-list", "--parents", "-n", "1", merged.value).split(" ").toList == List(merged.value, head.value, reviewed.value))
          assert(local.git(local.source, "show-ref", "--verify", "--hash", "refs/cq/candidates/" + id.value) == merged.value)
          assert(local.git(local.source, "ls-tree", "-r", "--name-only", merged.value).linesIterator.toSet == Set("left.txt", "right.txt", "tracked.txt"))
          assert(local.git(local.source, "show", merged.value + ":tracked.txt") == "committed")
          assert(local.git(local.source, "log", "-1", "--format=%B", merged.value) == message.trim)
          assert(local.git(local.source, "log", "-1", "--format=%an <%ae>", merged.value) == "CQ host <cq@localhost>")
          assert(governing == before, "Host rebase changed the governing HEAD, index or files")
          // The rebased commit is a host-captured candidate, so checks may start from it; its identity cannot be reused.
          candidates.verifyBase(merged)
          intercept[IllegalArgumentException](candidates.rebase(head, reviewed, id, message))
        }
      } yield ()
    }

    "I18: report textual and directory-rename conflicts without a ref, whatever the checkout's attributes or merge settings, and refuse merge drivers" in { (local: LocalWorkspaceFixture) =>
      val settings = configuration(local)
      val candidates = new CandidateWorkspace(settings)
      val fixture = local.fixture
      def makeCandidate(base: GitCommit)(change: Path => Unit) = for {
        workspace <- fixture.service.prepare(settings.owner, fixture.spec(settings.owner).copy(base = base))
        commit <- ZIO.attemptBlocking { change(Path.of(workspace.directory)); candidates.capture(workspace, None, "Candidate\n") }
      } yield commit
      def refs: String = local.git(local.source, "for-each-ref", "--format=%(refname)", "refs/cq/candidates/")
      for {
        nested <- ZIO.attemptBlocking {
          Files.createDirectory(local.source.resolve("before"))
          Files.writeString(local.source.resolve("before/one.txt"), "one\n")
          local.git(local.source, "add", "before/one.txt")
          local.git(local.source, "-c", "user.name=CQ test", "-c", "user.email=test@localhost", "commit", "-q", "-m", "Add a directory")
          local.git(local.source, "branch", "integration")
          GitCommit(local.git(local.source, "rev-parse", "HEAD"))
        }
        textHead <- makeCandidate(nested)(tree => Files.writeString(tree.resolve("tracked.txt"), "target line\n"))
        textCandidate <- makeCandidate(nested)(tree => Files.writeString(tree.resolve("tracked.txt"), "candidate line\n"))
        renamedHead <- makeCandidate(nested)(tree => Files.move(tree.resolve("before"), tree.resolve("after")))
        addingCandidate <- makeCandidate(nested)(tree => Files.writeString(tree.resolve("before/two.txt"), "two\n"))
        cleanCandidate <- makeCandidate(nested)(tree => Files.writeString(tree.resolve("clean.txt"), "clean\n"))
        _ <- ZIO.attemptBlocking {
          val captured = refs
          def conflicted(head: GitCommit, candidate: GitCommit): Unit = {
            val result = candidates.rebase(head, candidate, IntegrationId(uuid), "Rebase\n")
            assert(result == HostRebase.Conflicted && refs == captured, s"Conflicting rebase gave $result")
          }
          conflicted(textHead, textCandidate)
          conflicted(renamedHead, addingCandidate)
          // An untracked attribute file in the governing checkout would select the union driver and hide the textual conflict.
          Files.writeString(local.source.resolve(".gitattributes"), "tracked.txt merge=union\n")
          conflicted(textHead, textCandidate)
          Files.delete(local.source.resolve(".gitattributes"))
          // Repository configuration that moves a file added to a renamed directory would hide the directory-rename conflict.
          local.git(local.source, "config", "merge.directoryRenames", "true")
          conflicted(renamedHead, addingCandidate)
          local.git(local.source, "config", "merge.fixture.driver", "true")
          val refused = candidates.rebase(textHead, cleanCandidate, IntegrationId(uuid), "Rebase\n")
          println(s"Host rebase with a configured merge driver: $refused")
          assert(refused == HostRebase.Refused("Host rebase refuses repository-defined merge drivers") && refs == captured)
          local.git(local.source, "config", "--unset", "merge.fixture.driver")
          assert(candidates.rebase(textHead, cleanCandidate, IntegrationId(uuid), "Rebase\n").isInstanceOf[HostRebase.Merged])
        }
      } yield ()
    }

    "report a textual conflict, or refuse, whichever attribute or configuration source outside the target's tree selects the union driver" in { (local: LocalWorkspaceFixture) =>
      val settings = configuration(local)
      val fixture = local.fixture
      val Union = "tracked.txt merge=union\n"
      def makeCandidate(text: String) = for {
        workspace <- fixture.service.prepare(settings.owner, fixture.spec(settings.owner))
        commit <- ZIO.attemptBlocking {
          Files.writeString(Path.of(workspace.directory).resolve("tracked.txt"), text)
          new CandidateWorkspace(settings).capture(workspace, None, "Candidate\n")
        }
      } yield commit
      def rebase(environment: Map[String, String], head: GitCommit, candidate: GitCommit): HostRebase =
        new CandidateWorkspace(settings.copy(environment = environment)).rebase(head, candidate, IntegrationId(uuid), "Rebase\n")
      for {
        head <- makeCandidate("target line\n")
        candidate <- makeCandidate("candidate line\n")
        _ <- ZIO.attemptBlocking {
          local.git(local.source, "branch", "integration", local.base.value)
          val captured = local.git(local.source, "for-each-ref", "--format=%(refname)", "refs/cq/candidates/")
          var wrong = List.empty[String]
          def outcome(source: String, environment: Map[String, String], expected: HostRebase): Unit = {
            val result = rebase(environment, head, candidate)
            println(s"Host rebase with merge=union from $source: $result")
            if (result != expected) wrong = wrong :+ s"$source gave $result"
          }
          // The operator's own attributes file must not decide the other cases.
          val clean = sys.env.updated("HOME", Files.createDirectory(local.directory.resolve("empty-home")).toString)
          assert(rebase(clean, head, candidate) == HostRebase.Conflicted)

          val attributes = Path.of(local.git(local.source, "rev-parse", "--path-format=absolute", "--git-path", "info/attributes"))
          Files.createDirectories(attributes.getParent)
          Files.writeString(attributes, Union)
          outcome("$GIT_DIR/info/attributes", clean, HostRebase.Refused("Host rebase refuses a repository with info/attributes"))
          Files.delete(attributes)

          val home = Files.createDirectories(local.directory.resolve("home/.config/git")).getParent.getParent
          Files.writeString(home.resolve(".config/git/attributes"), Union)
          outcome("$HOME/.config/git/attributes", clean.updated("HOME", home.toString), HostRebase.Conflicted)

          val custom = Files.writeString(local.directory.resolve("custom-attributes"), Union)
          local.git(local.source, "config", "core.attributesFile", custom.toString)
          outcome("repository core.attributesFile", clean, HostRebase.Refused("Host rebase refuses repository-defined merge behaviour: core.attributesfile"))
          local.git(local.source, "config", "--unset", "core.attributesFile")

          local.git(local.source, "config", "merge.default", "union")
          outcome("repository merge.default", clean, HostRebase.Refused("Host rebase refuses repository-defined merge behaviour: merge.default"))
          local.git(local.source, "config", "--unset", "merge.default")

          // The system attributes file has a fixed path outside the fixture. Where bubblewrap exists Git runs in a mount namespace
          // that provides it; elsewhere only the variable that makes Git 2.55 skip that file is asserted.
          val system = Path.of(new BoundedHostCommand(sys.env.filterNot(_._1.startsWith("GIT_")), java.time.Duration.ofSeconds(10), 4096)
            .run(local.source, List("git", "var", "GIT_ATTR_SYSTEM")).text.trim)
          val path = sys.env("PATH").split(":").toList.map(Path.of(_))
          val real = path.map(_.resolve("git")).find(Files.isExecutable(_)).get.toRealPath()
          val contents = Files.writeString(local.directory.resolve("system-attributes"), Union)
          val native = CandidateWorkspace.command(clean)
          path.map(_.resolve("bwrap")).find(Files.isExecutable(_)) match {
            case Some(sandbox) =>
              val namespaced: HostCommand = (directory, arguments) => native.run(directory, List(sandbox.toString, "--dev-bind", "/", "/",
                "--tmpfs", system.getParent.toString, "--ro-bind", contents.toString, system.toString, real.toString) ++ arguments.tail)
              val result = new CandidateWorkspace(settings, namespaced).rebase(head, candidate, IntegrationId(uuid), "Rebase\n")
              println(s"Host rebase with merge=union from the system file $system: $result")
              if (result != HostRebase.Conflicted) wrong = wrong :+ s"the system file $system gave $result"
            case None =>
              println(s"Host rebase with merge=union from the system file $system: NOT EXERCISED, bubblewrap is unavailable")
              if (!GitEnvironment.isolated(clean).get("GIT_ATTR_NOSYSTEM").contains("1")) wrong = wrong :+ "the merge environment reads the system attributes file"
          }
          assert(wrong.isEmpty && local.git(local.source, "for-each-ref", "--format=%(refname)", "refs/cq/candidates/") == captured, wrong.mkString("; "))
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
