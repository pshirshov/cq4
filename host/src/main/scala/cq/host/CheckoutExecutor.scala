package cq.host

import cq.api.*
import cq.core.IntegrationPolicy
import java.io.ByteArrayOutputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.{FutureTask, TimeUnit}
import scala.jdk.CollectionConverters.*
import scala.util.Using

object CheckoutRecords {
  val MaxBytes = IntegrationPolicy.MaxIntentBytes + 8192
  val MaxIndexBytes = 32 * 1024 * 1024
  def lockText(intent: IntegrationIntent, directory: Path): String = s"CQ checkout ${intent.id.value}\n$directory\n"
  def hash(path: Path): String = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(HostFiles.bytes(path, MaxIndexBytes)))
  def force(path: Path): Unit = Using.resource(FileChannel.open(path, StandardOpenOption.READ))(_.force(true))
  /** The file of a checkout's directory that its executor locks for its whole run. Whoever holds that lock knows that no executor of
    * the checkout is running, and what it then writes into the directory is read by every executor that starts later. */
  val Lock = "executor.lock"
  def lock(directory: Path): FileChannel = FileChannel.open(directory.resolve(Lock), java.util.Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
}

/** Executed only as a guardian-owned job; interrupted work retains the real index lock and journal. */
final class CheckoutExecutor(environment: Map[String, String]) {
  private val CommandDeadline = Duration.ofSeconds(30)
  private val OutputBytes = 1024 * 1024
  private val ProtocolBytes = 65536
  private val Git = List("git", "--no-replace-objects", "--no-pager", "-c", "core.hooksPath=/dev/null", "-c", "submodule.recurse=false",
    "-c", "core.fsmonitor=false", "-c", "core.fsync=committed,index", "-c", "core.fsyncMethod=fsync")
  private val isolated = GitEnvironment.isolated(environment) ++ Map("LC_ALL" -> "C", "GIT_OPTIONAL_LOCKS" -> "0")

  private final class RefTransaction(repository: Path, intent: IntegrationIntent, head: Option[String]) extends AutoCloseable {
    private val builder = new ProcessBuilder((Git ++ List("-c", "user.name=CQ host", "-c", "user.email=cq@localhost",
      "update-ref", "--no-deref", "--stdin", "-m", "CQ integration " + intent.id.value)).asJava).directory(repository.toFile)
    builder.environment().clear(); builder.environment().putAll(isolated.asJava)
    private val process = builder.start()
    var settled = false
    private val errors = new FutureTask[String](() => {
      val bytes = process.getErrorStream.readNBytes(ProtocolBytes + 1)
      require(bytes.length <= ProtocolBytes, "Git transaction diagnostics exceed the bound")
      new String(bytes, UTF_8)
    })
    Thread.ofVirtual().name("cq-checkout-ref-errors").start(errors)
    private def phase(input: String, expected: String): Unit = {
      process.getOutputStream.write(input.getBytes(UTF_8)); process.getOutputStream.flush()
      val reply = new FutureTask[String](() => {
        val bytes = new ByteArrayOutputStream()
        var next = process.getInputStream.read()
        while (next != -1 && next != '\n' && bytes.size() < ProtocolBytes) { bytes.write(next); next = process.getInputStream.read() }
        require(next == '\n', "Git transaction acknowledgement missing or oversized")
        bytes.toString(UTF_8)
      })
      Thread.ofVirtual().name("cq-checkout-ref-ack").start(reply)
      require(reply.get(CommandDeadline.toMillis, TimeUnit.MILLISECONDS) == expected, "Unexpected Git transaction acknowledgement")
    }
    def prepare(): Unit = {
      phase("start\n", "start: ok")
      val verifyHead = head.map(value => if (value.startsWith("refs/")) s"symref-verify HEAD $value\n" else s"verify HEAD $value\n").getOrElse("")
      phase(s"update ${intent.target} ${intent.candidate.value} ${intent.expected.value}\n${verifyHead}prepare\n", "prepare: ok")
    }
    def commit(): Unit = {
      phase("commit\n", "commit: ok")
      process.getOutputStream.close()
      require(process.waitFor(CommandDeadline.toMillis, TimeUnit.MILLISECONDS) && process.exitValue() == 0, "Git reference commit did not settle successfully")
      require(errors.get(CommandDeadline.toMillis, TimeUnit.MILLISECONDS).isEmpty, "Git reference commit reported diagnostics")
    }
    override def close(): Unit = {
      if (process.isAlive) process.getOutputStream.close()
      if (!process.waitFor(CommandDeadline.toMillis, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        process.waitFor(CommandDeadline.toMillis, TimeUnit.MILLISECONDS)
        throw new IllegalStateException("Git transaction did not abort cleanly; retain its locks for explicit recovery")
      }
      settled = true
      process.getInputStream.close(); process.getErrorStream.close(); process.getOutputStream.close()
    }
  }

  def run(input: Path): Unit = {
    val directory = input.getParent.toRealPath()
    HostFiles.directory(directory)
    // Taken before the retained evidence is read and kept until the process ends: a host that withdrew this checkout did so under
    // the same lock, and its refusal is then found below.
    Using.resource(CheckoutRecords.lock(directory))(channel => Using.resource(channel.lock())(_ => execute(input, directory)))
  }

  private def execute(input: Path, directory: Path): Unit = {
    val plan = HostFiles.read(input, CheckoutPlan_JsonCodec, CheckoutRecords.MaxBytes)
    val intent = plan.intent
    require(!List("started.json", "completed.json", "refused.json", "index-before").exists(name => Files.exists(directory.resolve(name))),
      "Checkout execution already has retained evidence; use reconcile-only recovery")
    val repository = Path.of(intent.repository)
    val command = new BoundedHostCommand(isolated, CommandDeadline, OutputBytes)
    def git(arguments: String*): CommandOutput = command.run(repository, Git ++ arguments)
    def required(arguments: String*): String = {
      val output = git(arguments*)
      require(output.exit == 0, "Git checkout inspection failed: " + output.text.take(1000))
      output.text.stripTrailing()
    }
    var effectsStarted = false
    var completed = false
    var ownedIndexLock: Option[Path] = None
    var reference: Option[RefTransaction] = None
    try {
      require(repository.isAbsolute && repository.toRealPath() == repository, "Checkout repository identity differs")
      require(Path.of(required("rev-parse", "--show-toplevel")).toRealPath() == repository, "Checkout worktree identity differs")
      require(intent.target.startsWith("refs/heads/") && git("check-ref-format", intent.target).exit == 0, "Invalid checkout target")
      require(List(intent.expected, intent.candidate).forall(_.value.matches("[0-9a-f]{40}|[0-9a-f]{64}")) && intent.expected != intent.candidate,
        "Distinct full checkout object IDs required")
      require(required("rev-parse", "--show-ref-format") == "files", "Checked-out integration currently requires Git's files reference format")
      require(git("merge-base", "--is-ancestor", intent.expected.value, intent.candidate.value).exit == 0, "Candidate must descend from expected target")
      val gitDirectory = Path.of(required("rev-parse", "--absolute-git-dir")).toRealPath()
      val index = Path.of(required("rev-parse", "--path-format=absolute", "--git-path", "index"))
      require(index.getParent.toRealPath() == gitDirectory && Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS), "Ordinary Git index required")
      val indexLock = index.resolveSibling("index.lock")
      Files.writeString(indexLock, CheckoutRecords.lockText(intent, directory), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
      ownedIndexLock = Some(indexLock)
      CheckoutRecords.force(indexLock); CheckoutRecords.force(gitDirectory)
      val head = git("symbolic-ref", "--quiet", "HEAD") match {
        case CommandOutput(0, text) => text.stripTrailing()
        case CommandOutput(1, _) => required("rev-parse", "HEAD")
        case _ => throw new IllegalStateException("Cannot inspect governing HEAD")
      }
      require((head == intent.target) == plan.attached, "Target checkout changed after scheduling")
      val transaction = new RefTransaction(repository, intent, if (plan.attached) None else Some(head))
      reference = Some(transaction)
      Using.resource(transaction) { transaction =>
        transaction.prepare()
        require(Files.exists(gitDirectory.resolve("HEAD.lock")), "Git did not lock the governing HEAD")
        val worktrees = required("worktree", "list", "--porcelain", "-z").split("\u0000\u0000", -1).filter(_.split("\u0000", -1).contains("branch " + intent.target))
        require(if (plan.attached) worktrees.length == 1 && worktrees.head.startsWith("worktree " + repository + "\u0000") else worktrees.isEmpty,
          "Target checkout occupancy changed or belongs to another worktree")
        if (!plan.attached) {
          val receipt = CheckoutReceipt(intent, gitDirectory.toString, CheckoutRecords.hash(index))
          HostFiles.immutable(directory.resolve("started.json"), HostFiles.encode(CheckoutReceipt_JsonCodec, receipt), CheckoutRecords.MaxBytes)
          effectsStarted = true
          transaction.commit()
          HostFiles.immutable(directory.resolve("completed.json"), HostFiles.encode(CheckoutReceipt_JsonCodec, receipt), CheckoutRecords.MaxBytes)
          completed = true
        } else {
          require(!List("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "REBASE_HEAD", "BISECT_START", "rebase-merge", "rebase-apply", "sequencer")
            .exists(name => Files.exists(Path.of(required("rev-parse", "--path-format=absolute", "--git-path", name)), LinkOption.NOFOLLOW_LINKS)),
            "Finish the existing Git operation before integrating")
          require(git("config", "--bool", "core.sparseCheckout").text.trim != "true" && git("config", "--bool", "core.splitIndex").text.trim != "true" &&
            required("rev-parse", "--shared-index-path").isEmpty, "Sparse and split indexes require an ordinary checkout before integrating")
          val stages = required("ls-files", "--stage", "-z").split("\u0000", -1).filter(_.nonEmpty)
          require(stages.forall(entry => entry.takeWhile(_ != '\t').endsWith(" 0") && !entry.startsWith("160000 ")), "Unmerged indexes and submodules require separate integration")
          require(!required("ls-tree", "-r", intent.candidate.value).linesIterator.exists(_.startsWith("160000 ")), "Submodule candidates require separate integration")
          require(required("ls-files", "-v", "-z").split("\u0000", -1).filter(_.nonEmpty).forall(_.startsWith("H ")),
            "Assume-unchanged and skip-worktree index entries require an ordinary checkout before integrating")
          val changes = required("diff-tree", "--no-commit-id", "--name-only", "-r", "-z", intent.expected.value, intent.candidate.value)
            .split("\u0000", -1).filter(_.nonEmpty).toList
          require(changes.forall(path => !path.contains('\ufffd') && !Path.of(path).isAbsolute && repository.resolve(path).normalize().startsWith(repository)), "Unsupported checkout pathname")
          val obstructingParents = changes.flatMap { name =>
            Iterator.iterate(repository.resolve(name).getParent)(_.getParent).takeWhile(path => path != null && path != repository)
              .filter(path => Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
              .map(repository.relativize(_).toString).toList
          }
          val collisionPaths = (changes ++ obstructingParents).distinct
          if (collisionPaths.nonEmpty) require(required((List("--literal-pathspecs", "ls-files", "--others", "-z", "--") ++ collisionPaths)*).isEmpty,
            "Checkout refused: candidate paths contain untracked or ignored local files")
          val oldIndex = directory.resolve("index-before")
          val privateIndex = directory.resolve("index-prepared")
          val original = HostFiles.bytes(index, CheckoutRecords.MaxIndexBytes)
          Files.write(oldIndex, original, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
          Files.write(privateIndex, original, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
          CheckoutRecords.force(oldIndex); CheckoutRecords.force(privateIndex); CheckoutRecords.force(directory)
          val checkout = new BoundedHostCommand(isolated.updated("GIT_INDEX_FILE", privateIndex.toString), CommandDeadline, OutputBytes)
          def readTree(dryRun: Boolean): Unit = {
            val arguments = Git ++ List("read-tree") ++ (if (dryRun) List("--dry-run") else Nil) ++ List("-m", "-u", intent.expected.value, intent.candidate.value)
            val result = checkout.run(repository, arguments)
            require(result.exit == 0, "Checkout refused or failed: " + result.text.take(1000))
          }
          require(required("symbolic-ref", "HEAD") == intent.target && required("rev-parse", "HEAD") == intent.expected.value,
            "Checked-out branch changed before the conditional transaction")
          readTree(true)
          HostFiles.immutable(directory.resolve("started.json"), HostFiles.encode(CheckoutReceipt_JsonCodec,
            CheckoutReceipt(intent, gitDirectory.toString, CheckoutRecords.hash(oldIndex))), CheckoutRecords.MaxBytes)
          effectsStarted = true
          readTree(false)
          val parents = scala.collection.mutable.Set[Path](repository)
          changes.foreach { name =>
            val path = repository.resolve(name)
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) CheckoutRecords.force(path)
            var parent = path.getParent
            while (parent != null && parent.startsWith(repository)) {
              if (Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) parents += parent
              parent = parent.getParent
            }
          }
          parents.toList.sortBy(path => -path.getNameCount).foreach(CheckoutRecords.force)
          CheckoutRecords.force(privateIndex)
          val publication = Files.createTempFile(gitDirectory, ".cq-index-", ".publish")
          try {
            Files.copy(privateIndex, publication, StandardCopyOption.REPLACE_EXISTING)
            CheckoutRecords.force(publication)
            Files.move(publication, index, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            CheckoutRecords.force(gitDirectory)
          } finally Files.deleteIfExists(publication)
          val receipt = CheckoutReceipt(intent, gitDirectory.toString, CheckoutRecords.hash(index))
          HostFiles.immutable(directory.resolve("published.json"), HostFiles.encode(CheckoutReceipt_JsonCodec, receipt), CheckoutRecords.MaxBytes)
          transaction.commit()
          require(required("symbolic-ref", "HEAD") == intent.target && required("rev-parse", "HEAD") == intent.candidate.value, "Git commit acknowledgement differs from checkout target")
          HostFiles.immutable(directory.resolve("completed.json"), HostFiles.encode(CheckoutReceipt_JsonCodec, receipt), CheckoutRecords.MaxBytes)
          completed = true
        }
      }
    } catch {
      case error: Throwable =>
        if (!effectsStarted && reference.forall(_.settled)) HostFiles.immutable(directory.resolve("refused.json"), HostFiles.encode(CheckoutRefusal_JsonCodec,
          CheckoutRefusal(intent, Option(error.getMessage).getOrElse(error.getClass.getSimpleName).take(2000))), CheckoutRecords.MaxBytes)
        throw error
    } finally {
      if ((!effectsStarted || completed) && reference.forall(_.settled)) ownedIndexLock.foreach { path =>
        require(Files.readString(path) == CheckoutRecords.lockText(intent, directory), "Checkout index lock ownership changed")
        Files.delete(path); CheckoutRecords.force(path.getParent)
      }
    }
  }
}
