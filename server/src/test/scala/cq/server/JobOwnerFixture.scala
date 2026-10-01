package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.{Scope, WorkspaceService}
import cq.host.*
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import java.time.{Clock, Duration}
import zio.{IO, ZIO, ZIOAppDefault}

object JobOwnerFixture extends ZIOAppDefault {
  override def run = ZIO.scoped {
    for {
      args <- getArgs
      _ <- ZIO.attempt(require(args.length == 2))
      root = Path.of(args(0))
      binary = Path.of(args(1))
      workspace <- ZIO.attemptBlocking {
        parse(Files.readString(root.resolve("workspace.json"))).flatMap(WorkspaceSpec_JsonCodec.decode(BaboonCodecContext.Default, _)).fold(throw _, identity)
      }
      scope = Scope(workspace.project, Actor("job owner", workspace.owner, Role.Governor))
      repository = new GitWorkspaceRepository(root.resolve("workspaces"), new BoundedHostCommand(GitEnvironment.isolated(sys.env), Duration.ofSeconds(10), 65536), Clock.systemUTC())
      service = new WorkspaceService.Impl[IO](repository)
      supervisor <- JobSupervisor.acquire(scope, ZIO.attemptBlocking(FileJobRepository.open(root.resolve("journal"), workspace.project, workspace.owner)),
        service, new GuardianDriver(binary), root.resolve("payload"), Clock.systemUTC())
      script = """import os,signal,time
from pathlib import Path
child=os.fork()
if child==0:
    os.setsid()
    signal.signal(signal.SIGTERM,signal.SIG_IGN)
    Path('child.pid').write_text(str(os.getpid()))
else:
    Path('root.pid').write_text(str(os.getpid()))
time.sleep(30)
"""
      launch = JobCommand(List("python3", "-c", script), sys.env, "", ExecutionLimits(Duration.ofSeconds(2), None,
        Duration.ofMillis(900), Duration.ofMillis(100), Duration.ofSeconds(1), 262144))
      _ <- supervisor.start(scope, workspace, launch)
      tree = root.resolve("workspaces").resolve(workspace.attempt.value.toString).resolve("tree")
      _ <- (ZIO.sleep(zio.Duration.fromMillis(20)) *> ZIO.attemptBlocking(Files.exists(tree.resolve("root.pid")) && Files.exists(tree.resolve("child.pid"))))
        .repeatUntil(identity).timeoutFail(new IllegalStateException("Child fixture did not start"))(zio.Duration.fromSeconds(10))
      _ <- ZIO.attemptBlocking(Files.writeString(root.resolve("ready"), "ready"))
      _ <- ZIO.never
    } yield ()
  }
}
