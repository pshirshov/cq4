package cq.server

import cq.api.Harness
import cq.host.{DriverAssets, WorkflowAssets}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*
import scala.util.Using

trait CommandDoctorFixture extends AutoCloseable {
  def root: Path
  def reader: CommandAssetReader
  def put(path: Path, bytes: Array[Byte]): Unit
  def remove(path: Path): Unit
  def directory(path: Path): Unit
  def unreadable(path: Path): Unit
}

abstract class CommandDoctorContract extends AnyWordSpec {
  protected def fixture(): CommandDoctorFixture
  protected def isolation: String
  private val workflows = new WorkflowAssets
  private def installed(f: CommandDoctorFixture, harness: Harness): List[Path] = {
    val assets = workflows.commands(harness) ++ DriverAssets.commands(harness)
    assets.foreach(asset => f.put(f.root.resolve(asset.path), asset.body.getBytes(UTF_8)))
    assets.map(asset => f.root.resolve(asset.path))
  }
  private def inspect(f: CommandDoctorFixture, harness: Harness): CommandDoctorReport =
    new CommandDoctor(f.reader, workflows).inspect(harness, f.root)

  s"Command doctor (Behavioral Active Blackbox $isolation)" should {
    "verify the complete packaged command inventory of each harness" in {
      Using.resource(fixture()) { f =>
        Harness.all.foreach { harness =>
          val paths = installed(f, harness)
          val report = inspect(f, harness)
          assert(report.current && report.checks.size == (if (harness == Harness.Pi) 4 else 6))
          assert(report.checks.map(check => f.root.resolve(check.path)) == paths)
        }
      }
    }
    "report every missing file without creating an installation" in {
      Using.resource(fixture()) { f =>
        val report = inspect(f, Harness.Codex)
        assert(!report.current && report.checks.size == 6)
        assert(report.checks.forall(_.state == CommandAssetState.Missing))
      }
    }
    "detect stale workflow text and drive command text independently" in {
      Using.resource(fixture()) { f =>
        val paths = installed(f, Harness.Claude)
        f.put(paths.head, "old workflow".getBytes(UTF_8))
        f.put(paths.last, "old park".getBytes(UTF_8))
        val report = inspect(f, Harness.Claude)
        assert(!report.current)
        assert(report.checks.filter(_.state == CommandAssetState.Different).map(check => f.root.resolve(check.path)) == List(paths.head, paths.last))
        assert(report.checks.count(_.state == CommandAssetState.Current) == 4)
      }
    }
    "reject a matching prefix with extra bytes" in {
      Using.resource(fixture()) { f =>
        val paths = installed(f, Harness.Pi)
        val expected = workflows.commands(Harness.Pi).head.body.getBytes(UTF_8)
        f.put(paths.head, expected ++ "extra".getBytes(UTF_8))
        assert(inspect(f, Harness.Pi).checks.head.state == CommandAssetState.Different)
      }
    }
    "distinguish missing, nonregular and unreadable assets while checking the remaining files" in {
      Using.resource(fixture()) { f =>
        val paths = installed(f, Harness.Codex)
        paths.take(3).foreach(f.remove)
        f.directory(paths(1)); f.unreadable(paths(2))
        val report = inspect(f, Harness.Codex)
        assert(report.checks.map(_.state) == List(CommandAssetState.Missing, CommandAssetState.NotRegular, CommandAssetState.Unreadable,
          CommandAssetState.Current, CommandAssetState.Current, CommandAssetState.Current))
        assert(!report.current)
      }
    }
  }
}

final class CommandDoctorDummy extends CommandDoctorContract {
  override protected def isolation = "Atomic"
  override protected def fixture(): CommandDoctorFixture = new CommandDoctorFixture {
    override val root: Path = Path.of("/command-doctor-fixture")
    private var files = Map.empty[Path, CommandFile]
    override val reader: CommandAssetReader = new CommandAssetReader {
      override def read(path: Path, maximum: Int): CommandFile = files.getOrElse(path, CommandFile.Missing) match {
        case CommandFile.Content(bytes) => CommandFile.Content(bytes.take(maximum))
        case other => other
      }
    }
    override def put(path: Path, bytes: Array[Byte]): Unit = files = files.updated(path, CommandFile.Content(bytes.clone()))
    override def remove(path: Path): Unit = files = files.removed(path)
    override def directory(path: Path): Unit = files = files.updated(path, CommandFile.NotRegular)
    override def unreadable(path: Path): Unit = files = files.updated(path, CommandFile.Unreadable)
    override def close(): Unit = ()
  }
}

final class CommandDoctorLocal extends CommandDoctorContract {
  override protected def isolation = "Good Communication filesystem"
  override protected def fixture(): CommandDoctorFixture = new CommandDoctorFixture {
    override val root: Path = Files.createTempDirectory("cq-command-doctor-").toAbsolutePath
    override val reader: CommandAssetReader = new FileCommandAssetReader
    override def put(path: Path, bytes: Array[Byte]): Unit = {
      Files.createDirectories(path.getParent); Files.write(path, bytes); ()
    }
    override def remove(path: Path): Unit = { Files.delete(path); () }
    override def directory(path: Path): Unit = { Files.createDirectory(path); () }
    override def unreadable(path: Path): Unit = { Files.createSymbolicLink(path, path.getFileName); () }
    override def close(): Unit = Using.resource(Files.walk(root))(_.iterator().asScala.toList.reverse.foreach(Files.delete))
  }

  "Declarative command files (Behavioral Active Blackbox Good Communication filesystem)" should {
    "report a dangling symlink as missing and preserve the link" in {
      val root = Files.createTempDirectory("cq-doctor-dangling-").toAbsolutePath
      try {
        val path = root.resolve(new WorkflowAssets().commands(Harness.Pi).head.path)
        val target = root.resolve("absent-store-file")
        Files.createDirectories(path.getParent); Files.createSymbolicLink(path, target)
        val report = new CommandDoctor(new FileCommandAssetReader, new WorkflowAssets).inspect(Harness.Pi, root)
        assert(!report.current && report.checks.forall(_.state == CommandAssetState.Missing))
        assert(Files.isSymbolicLink(path) && Files.readSymbolicLink(path) == target)
      } finally Using.resource(Files.walk(root))(_.iterator().asScala.toList.reverse.foreach(Files.delete))
    }
    "verify symlinks to immutable command files without replacing or modifying them" in {
      val root = Files.createTempDirectory("cq-doctor-symlinks-").toAbsolutePath
      try {
        val store = Files.createDirectory(root.resolve("store"))
        val assets = new WorkflowAssets().commands(Harness.Codex) ++ DriverAssets.commands(Harness.Codex)
        val targets = assets.zipWithIndex.map { (asset, index) =>
          val target = Files.writeString(store.resolve(index.toString), asset.body)
          target.toFile.setWritable(false)
          val path = root.resolve(asset.path)
          Files.createDirectories(path.getParent); Files.createSymbolicLink(path, target)
          (path, target, Files.getLastModifiedTime(target), Files.readAllBytes(target).toList)
        }
        assert(new CommandDoctor(new FileCommandAssetReader, new WorkflowAssets).inspect(Harness.Codex, root).current)
        targets.foreach { (path, target, modified, bytes) =>
          assert(Files.isSymbolicLink(path) && Files.readSymbolicLink(path) == target)
          assert(Files.getLastModifiedTime(target) == modified && Files.readAllBytes(target).toList == bytes)
        }
        assert(!Files.exists(root.resolve(".cq")))
      } finally Using.resource(Files.walk(root))(_.iterator().asScala.toList.reverse.foreach(Files.delete))
    }
  }
}
