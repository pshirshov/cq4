package cq.host

import cq.api.GitCommit
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import scala.util.Using

final case class MergeInputs(directory: Path, common: Path, base: GitCommit, candidate: GitCommit) {
  require(List(directory, common).forall(path => path.isAbsolute && path.normalize() == path && !path.toString.exists(_.isControl)),
    "Merge paths must be absolute and normalized without control characters")
  require(List(base, candidate).forall(commit => commit.value.matches("[0-9a-f]{40}|[0-9a-f]{64}")), "Merge inputs require full object IDs")
  require(base != candidate, "Combination inputs must be distinct commits")
}

final class MergePreparation(guardian: Path) {
  private val MaxDiagnosticBytes = 64 * 1024
  require(guardian.isAbsolute && guardian.normalize() == guardian, "Merge capture requires an absolute guardian path")

  def wrap(native: HarnessLaunch, assets: Path, inputs: MergeInputs): HarnessLaunch = {
    require(native.arguments.nonEmpty && assets.isAbsolute && assets.normalize() == assets, "Invalid merge launch")
    val gitVariables = native.environment.keys.filter(_.startsWith("GIT_")).toList.sorted
    require(gitVariables.forall(_.matches("[A-Z][A-Z0-9_]{0,99}")), "Invalid inherited Git environment name")
    val script = Using.resource(getClass.getResourceAsStream("/cq/prepare-merge.sh")) { stream =>
      require(stream != null, "CQ merge preparation resource is missing")
      new String(stream.readAllBytes(), UTF_8)
    }
    val files = List(HarnessAsset("prepare-merge.sh", script), HarnessAsset("merge.log", ""),
      HarnessAsset("merge-status", ""), HarnessAsset("merge-ready", ""))
    require(!native.assets.exists(asset => files.exists(_.name == asset.name)), "Merge preparation assets already present")
    native.copy(arguments = List("sh", assets.resolve("prepare-merge.sh").toString, guardian.toString, assets.toString,
      inputs.directory.toString, inputs.common.toString, inputs.base.value, inputs.candidate.value,
      gitVariables.mkString(" "), MaxDiagnosticBytes.toString) ++ native.arguments, assets = native.assets ++ files)
  }
}
