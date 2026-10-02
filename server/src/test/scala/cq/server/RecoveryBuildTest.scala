package cq.server

import distage.Activation
import distage.StandardAxis.Repo
import io.circe.parser.parse
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import zio.ZIO

/**
 * D103, Q23: every marker startup recovery writes names the source revision of the build that wrote it. The marker is read as the
 * JSON on disk; the revision is taken from Git directly and from `dev/source-revision`, which the build compiles in.
 */
final class RecoveryBuildLocal extends SpecZIO with AssertZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(WorkspaceTestPlugin)), activation = Activation(Repo -> Repo.Prod))
  private val source = Path.of(Option(System.getProperty("cq.test.sourceRoot")).getOrElse(throw new IllegalStateException("-Dcq.test.sourceRoot is required")))

  private def run(directory: Path, command: String*): (Int, String) = {
    val builder = new ProcessBuilder(command.asJava).directory(directory.toFile).redirectErrorStream(true)
    builder.environment().keySet().removeIf(_.startsWith("GIT_"))
    val process = builder.start()
    val output = new String(process.getInputStream.readAllBytes(), UTF_8).trim
    require(process.waitFor(60, TimeUnit.SECONDS), s"${command.mkString(" ")} did not end")
    (process.exitValue(), output)
  }
  private def git(directory: Path, arguments: String*): String = {
    val (exit, output) = run(directory, ("git" +: arguments)*)
    require(exit == 0, s"git ${arguments.mkString(" ")}: $output")
    output
  }
  /** The mechanism the build uses: one line, `clean <commit>`, `modified <commit>` or `undetermined <reason>`. */
  private def revision(root: Path): String = {
    val (exit, output) = run(source, source.resolve("dev").resolve("source-revision").toString, root.toString)
    require(exit == 0, s"dev/source-revision exited $exit: $output")
    output
  }
  /** The `build` field of a marker as (variant, revision or reason). */
  private def build(marker: Path): Option[(String, String)] = {
    val field = parse(Files.readString(marker)).fold(throw _, identity).hcursor.downField("build")
    field.keys.flatMap(_.headOption).map { variant =>
      val value = field.downField(variant)
      variant -> value.downField("revision").get[String]("value").orElse(value.get[String]("reason")).fold(throw _, identity)
    }
  }

  "The producing build in session recovery markers (Behavioral Active Blackbox; local Git Communication)" should {
    "record in Recovered and Abandoned markers the revision this build was made from, and say so when the tree is modified" in { (local: LocalWorkspaceFixture) =>
      val state = RecoveryState(local)
      for {
        ended <- state.session(1)
        undecodable <- ZIO.attemptBlocking { ended.finish(); state.raw("{\"project\":".getBytes(UTF_8)) }
        receipt <- state.recover
        _ <- ZIO.attemptBlocking {
          val markers = List(ended.marker, undecodable.resolve("recovery.json")).map(marker => parse(Files.readString(marker)).fold(throw _, identity))
          val recorded = List(ended.marker, undecodable.resolve("recovery.json")).map(build)
          println(s"Producing build: markers=${markers.map(_.noSpaces)} receipt=${receipt.totals}")
          assert(markers.map(_.hcursor.get[String]("outcome")) == List(Right("Recovered"), Right("Abandoned")), markers.toString)
          assert(recorded.forall(_.nonEmpty), "recovery.json has no build field: " + markers.map(_.noSpaces))
          val line = revision(source)
          println(s"Producing build: dev/source-revision reports $line")
          // Git itself, not the mechanism under test, says what the build was made from.
          val expected = if (!Files.exists(source.resolve(".git"))) "Undetermined" -> line.stripPrefix("undetermined ")
            else (if (git(source, "status", "--porcelain", "--untracked-files=all").isEmpty) "Clean" else "Modified") -> git(source, "rev-parse", "HEAD")
          assert(recorded == List(Some(expected), Some(expected)), s"$recorded expected=$expected")
          assert(line == expected._1.toLowerCase + " " + expected._2, s"$line expected=$expected")
          // The executable is still named, apart from the build.
          assert(markers.forall(_.hcursor.get[String]("host").exists(_.startsWith("cq.api 0.1.0 "))), markers.toString)
        }
      } yield ()
    }

    "yield a different value for each revision, mark a modified tree and never report a revision it could not determine" in { (local: LocalWorkspaceFixture) =>
      ZIO.attemptBlocking {
        val repository = Files.createTempDirectory(local.directory, "revision-")
        def commit(content: String): String = {
          Files.writeString(repository.resolve("source.txt"), content)
          git(repository, "add", "source.txt")
          git(repository, "-c", "user.name=CQ test", "-c", "user.email=cq@example.invalid", "commit", "--quiet", "-m", content.trim)
          git(repository, "rev-parse", "HEAD")
        }
        git(repository, "init", "--quiet")
        val unborn = revision(repository)
        val first = commit("first\n")
        val atFirst = revision(repository)
        val second = commit("second\n")
        val atSecond = revision(repository)
        Files.writeString(repository.resolve("source.txt"), "edited\n")
        val edited = revision(repository)
        git(repository, "checkout", "--quiet", "--", "source.txt")
        Files.writeString(repository.resolve("untracked.txt"), "new\n")
        val untracked = revision(repository)
        Files.delete(repository.resolve("untracked.txt"))
        val restored = revision(repository)
        // A directory below a work tree is not that tree's revision; a directory outside any repository has none.
        val nested = revision(Files.createDirectory(repository.resolve("nested")))
        val outside = revision(Files.createTempDirectory("cq-no-repository-"))
        println(s"Source revisions: unborn=$unborn first=$atFirst second=$atSecond edited=$edited untracked=$untracked restored=$restored nested=$nested outside=$outside")
        assert(first != second && atFirst == "clean " + first && atSecond == "clean " + second && restored == atSecond)
        assert(edited == "modified " + second && untracked == "modified " + second)
        assert(List(unborn, nested, outside).forall(value => value.startsWith("undetermined ") && !value.contains(first) && !value.contains(second)), s"$unborn $nested $outside")
      }
    }
  }
}
