package cq.server

import cq.api.*
import cq.host.{HarnessOutput, HostFiles}
import java.nio.file.{Files, Path}

object HarnessOutputReplay {
  def main(args: Array[String]): Unit = {
    require(args.nonEmpty && args.length <= 10, "Pass retained successful adapter-probe directories")
    args.foreach { value =>
      val root = Path.of(value)
      val fixture = io.circe.parser.parse(HostFiles.text(root.resolve("result.json"), 65536)).fold(throw _, identity).hcursor
      require(fixture.get[String]("status").contains("passed"), "Replay requires a previously successful live probe")
      val name = fixture.get[String]("harness").fold(throw _, identity)
      val role = fixture.get[String]("role").fold(throw _, identity)
      val harness = Harness.all.find(_.toString.equalsIgnoreCase(name)).get
      val directory = root.resolve(name + "-" + role.toLowerCase)
      val job = HostFiles.read(directory.resolve("job.json"), JobRecord_JsonCodec, 65536)
      val bytes = HostFiles.bytes(directory.resolve("payload").resolve(job.workspace.attempt.value.toString).resolve("stdout"), 32 * 1024 * 1024)
      val result = new HarnessOutput().result(harness, bytes, directory.resolve("assets"))
      require(result.hcursor.get[String]("status").contains("ok"))
      val observed = result.hcursor.get[String]("observed").fold(throw _, identity)
      require(observed.startsWith("read-from-cq-"))
      if (role == "Worker") require(Files.readString(directory.resolve("workspaces").resolve(job.workspace.attempt.value.toString).resolve("tree/observed.txt")) == observed)
      println(s"Retained live result accepted by production parser: $harness $role")
    }
  }
}

object HarnessOutputBounds {
  def main(args: Array[String]): Unit = {
    try {
      new HarnessOutput().result(Harness.Pi, Array.fill[Byte](4 * 1024 * 1024)(10), Path.of("/unused"))
      throw new IllegalStateException("Native event limit was not enforced")
    } catch {
      case _: IllegalArgumentException => println("BOUNDED_NATIVE_EVENT_REJECTION")
      case failure: OutOfMemoryError =>
        System.err.println("UNBOUNDED_NATIVE_EVENT_ALLOCATION")
        throw failure
    }
  }
}
