package cq.core

import cq.api.*

/** Which process modes a project can be given in this release. The write path, restore and the mode catalog share it. */
object ProcessModePolicy {
  val Default: ProjectSetting.Mode = ProjectSetting.Mode(ProcessMode.Rigorous, false)

  private val YoloUnavailable = "The YOLO cross-cutting mode is not available in this release"

  /** Why `mode` cannot be selected, when it cannot. */
  def unavailable(mode: ProcessMode): Option[String] = mode match {
    case ProcessMode.Rigorous | ProcessMode.CrossCutting => None
    case ProcessMode.Yolo => Some(YoloUnavailable)
  }

  // A self-review exists only in the YOLO mode, so its exemption from configured checks is unavailable with it.
  def validate(setting: ProjectSetting.Mode): Unit = {
    unavailable(setting.value).foreach(reason => throw DomainFailure(Fault.Invalid(reason)))
    if (setting.selfReviewWithoutChecks) throw DomainFailure(Fault.Invalid(
      s"A self-review without configured checks belongs to the YOLO cross-cutting mode. $YoloUnavailable"))
  }
}
