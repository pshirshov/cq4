package cq.server

import cq.api.*
import com.sun.net.httpserver.HttpServer
import java.net.{InetSocketAddress, URI}
import java.nio.charset.StandardCharsets.UTF_8
import org.scalatest.wordspec.AnyWordSpec
import scala.util.Using

trait InstallationFixture extends AutoCloseable {
  def endpoint: URI
  def reader: InstallationReader
  def reply(value: InstallationInfo): Unit
  def fail(): Unit
}
abstract class InstallationDoctorContract extends AnyWordSpec {
  protected def fixture(): InstallationFixture
  protected def isolation: String
  private val token = "a" * 32
  private val source = SourceBuild.Clean(GitCommit("a" * 40))
  private val schema = SchemaIdentity.current()
  private def healthy = InstallationInfo(Command.baboonDomainVersion, schema.sha256, schema.sha256, 18, true, "on", true, source, 0, 0, 0)
  private def inspect(fixture: InstallationFixture, settled: Boolean) =
    new InstallationDoctor(fixture.reader, schema, source).server(fixture.endpoint, Map("CQ_TOKEN" -> token), settled)
  s"Installation doctor (Behavioral Active Blackbox $isolation)" should {
    "verify authenticated model, source, schema and PostgreSQL identity" in Using.resource(fixture()) { f =>
      f.reply(healthy); val report = inspect(f, true)
      assert(report.current && report.checks.map(_.name) == List("Credential", "Server", "Model", "Source", "Schema", "PostgreSQL", "Durability", "Unsettled work"))
    }
    "reject independent model, source, embedded schema, applied schema and database version mismatches" in Using.resource(fixture()) { f =>
      List("Model" -> healthy.copy(version = "other"), "Source" -> healthy.copy(source = SourceBuild.Clean(GitCommit("b" * 40))),
        "Schema" -> healthy.copy(schemaSha256 = "different"), "Schema" -> healthy.copy(appliedSchemaSha256 = "different"),
        "PostgreSQL" -> healthy.copy(postgresMajor = 17), "Durability" -> healthy.copy(fsync = false),
        "Durability" -> healthy.copy(synchronousCommit = "off"), "Durability" -> healthy.copy(fullPageWrites = false)).foreach { (name, value) =>
        f.reply(value); val report = inspect(f, true)
        assert(!report.current && report.checks.find(_.name == name).exists(_.state == InstallationState.Failed))
      }
    }
    "report modified source as unknown rather than matching the commit alone" in Using.resource(fixture()) { f =>
      f.reply(healthy.copy(source = SourceBuild.Modified(GitCommit("a" * 40))))
      assert(inspect(f, false).checks.find(_.name == "Source").exists(_.state == InstallationState.Unknown))
    }
    "report unsettled work and refuse it only when settlement is requested" in Using.resource(fixture()) { f =>
      List(healthy.copy(activeClaims = 1), healthy.copy(managedAttempts = 1), healthy.copy(pendingIntegrations = 1)).foreach { value =>
        f.reply(value); assert(inspect(f, false).current && !inspect(f, true).current)
      }
    }
    "report invalid credentials and server failures without exposing their contents" in Using.resource(fixture()) { f =>
      f.fail(); val report = inspect(f, false)
      assert(!report.current && report.checks.exists(check => check.name == "Server" && check.state == InstallationState.Failed))
      assert(!report.toString.contains(token) && !report.toString.contains("fixture-secret"))
      val missing = new InstallationDoctor(f.reader, schema, source).server(f.endpoint, Map.empty, false)
      assert(!missing.current && missing.checks.head.state == InstallationState.Failed)
    }
  }
}
final class InstallationDoctorDummy extends InstallationDoctorContract {
  override protected def isolation = "Atomic"
  override protected def fixture(): InstallationFixture = new InstallationFixture {
    private var value = Option.empty[InstallationInfo]
    override val endpoint = URI.create("http://127.0.0.1:12345")
    override val reader = new InstallationReader {
      override def read(endpoint: URI, token: String): InstallationInfo = value.getOrElse(throw new IllegalStateException("fixture-secret"))
    }
    override def reply(info: InstallationInfo): Unit = value = Some(info)
    override def fail(): Unit = value = None
    override def close(): Unit = ()
  }
}
final class InstallationDoctorLocal extends InstallationDoctorContract {
  override protected def isolation = "Good Communication HTTP"
  override protected def fixture(): InstallationFixture = new InstallationFixture {
    @volatile private var value = Option.empty[InstallationInfo]
    private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/installation", exchange => {
      val body = value.fold("fixture-secret")(info => Wire.encode(InstallationInfo_JsonCodec, info)).getBytes(UTF_8)
      exchange.sendResponseHeaders(if (value.isDefined) 200 else 401, body.length)
      Using.resource(exchange.getResponseBody)(_.write(body)); exchange.close()
    })
    server.start()
    override val endpoint = URI.create(s"http://127.0.0.1:${server.getAddress.getPort}")
    override val reader = new HttpInstallationReader
    override def reply(info: InstallationInfo): Unit = value = Some(info)
    override def fail(): Unit = value = None
    override def close(): Unit = server.stop(0)
  }
}
