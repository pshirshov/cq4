package cq.server

import cq.api.*
import cq.host.*
import io.circe.Json
import java.net.URI
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec

final class HarnessAdapterLocal extends AnyWordSpec {
  private def profile(harness: Harness): HarnessProfile = HarnessProfile(harness, Path.of("/test/harness"), "selected-model", if (harness == Harness.Claude) "anthropic" else "selected-provider",
    HarnessUsage.version(harness), Nil, Set("PROVIDER_TOKEN"))
  private def invocation(role: Role, root: Path): HarnessInvocation = HarnessInvocation(role, AttemptId(UUID.randomUUID()), "Host role instructions",
    Json.obj("type" -> Json.fromString("object")), List(
      HarnessMcp(McpTarget.Domain, URI.create("http://127.0.0.1:1234/mcp"), AccessToken("scoped-domain-token", 2000)),
      HarnessMcp(McpTarget.Local, URI.create("http://127.0.0.1:1235/mcp"), AccessToken("scoped-local-token", 2000))), root)
  private def adapters: List[HarnessAdapter] = List(new ClaudeAdapter, new CodexAdapter, new PiAdapter)
  private def environment: Map[String, String] = Map("HOME" -> "/test/home", "PATH" -> "/test/bin", "CQ_TOKEN" -> "operator-root-secret",
    "CQ_LOCAL_TOKEN" -> "parent-controller-secret", "CLAUDECODE" -> "parent-session", "CLAUDE_CODE_SAFE_MODE" -> "1",
    "PROVIDER_TOKEN" -> "configured-provider-secret", "UNRELATED_SECRET" -> "unrelated-secret")

  "Harness launch boundaries (Behavioral Active Blackbox; Group / filesystem Communication)" should {
    "isolate credentials and select explicit models without inherited harness control" in {
      adapters.foreach { adapter =>
        val launch = adapter.launch(profile(adapter.harness), invocation(Role.Worker, Path.of("/test/assets")), environment)
        assert(launch.environment("PROVIDER_TOKEN") == "configured-provider-secret")
        assert(!launch.environment.values.exists(value => Set("operator-root-secret", "parent-controller-secret", "parent-session", "unrelated-secret")(value)))
        assert(!launch.arguments.exists(value => value.contains("secret") || value.contains("scoped-domain-token") || value.contains("scoped-local-token")))
        assert(launch.arguments.contains("selected-model") && !launch.arguments.contains("--fallback-model"))
      }
      intercept[IllegalArgumentException](profile(Harness.Codex).copy(providerEnvironment = Set("CQ_TOKEN")))
      intercept[IllegalArgumentException](new PiAdapter().launch(profile(Harness.Pi), invocation(Role.Worker, Path.of("/test/assets")), environment - "PROVIDER_TOKEN"))
      intercept[IllegalArgumentException](new ClaudeAdapter().launch(profile(Harness.Claude).copy(provider = "unconfigured-provider"),
        invocation(Role.Worker, Path.of("/test/assets")), environment))
    }

    "give ledger mutation and dispatch only to governing profiles and native edits only to workers" in {
      val root = Path.of("/test/assets")
      val governor = invocation(Role.Governor, root)
      assert(governor.tools(McpTarget.Domain).contains("change") && governor.tools(McpTarget.Local) == List("dispatch") && !governor.edits)
      List(Role.Explorer, Role.Planner, Role.Worker, Role.Reviewer).foreach { role =>
        val child = invocation(role, root)
        assert(child.tools(McpTarget.Domain) == List("search", "read", "usage") && child.tools(McpTarget.Local) == List("workspace"))
        assert(child.edits == (role == Role.Worker))
        adapters.foreach { adapter =>
          val launch = adapter.launch(profile(adapter.harness), child, environment)
          assert(!launch.arguments.mkString(" ").contains("mcp__cq__change"))
          assert(!launch.assets.exists(asset => asset.body.contains("operator-root-secret")))
        }
      }
      intercept[IllegalArgumentException](invocation(Role.Human, root))
      intercept[IllegalArgumentException](invocation(Role.Collector, root))
    }

    "materialize private immutable assets and reject divergent retries or symbolic replacements" in {
      val root = Files.createTempDirectory("cq-harness-assets-").toAbsolutePath
      val launch = new PiAdapter().launch(profile(Harness.Pi), invocation(Role.Reviewer, root), environment)
      launch.install(root)
      launch.install(root)
      assert(Files.getPosixFilePermissions(root) == PosixFilePermissions.fromString("rwx------"))
      launch.assets.foreach { asset =>
        assert(Files.readString(root.resolve(asset.name)) == asset.body)
        assert(Files.getPosixFilePermissions(root.resolve(asset.name)) == PosixFilePermissions.fromString("rw-------"))
      }
      val changed = launch.copy(assets = List(HarnessAsset("pi-mcp.json", "different")))
      intercept[IllegalArgumentException](changed.install(root))
      val link = root.resolve("replacement")
      Files.createSymbolicLink(link, root.resolve("pi-mcp.json"))
      intercept[IllegalArgumentException](launch.copy(assets = List(HarnessAsset("replacement", "different"))).install(root))
    }
  }
}
