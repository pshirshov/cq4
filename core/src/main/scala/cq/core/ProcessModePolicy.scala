package cq.core

import cq.api.*

/** Which process modes a project can be given. The write path, restore and the mode catalog share one policy. */
final class ProcessModePolicy(yoloAvailable: Boolean) {
  import ProcessModePolicy.*

  /** Why `mode` cannot be selected, when it cannot. */
  def unavailable(mode: ProcessMode): Option[String] = mode match {
    case ProcessMode.Yolo if !yoloAvailable => Some(YoloUnavailable)
    case _ => None
  }

  // A self-review exists only in the YOLO mode, so its exemption from configured checks is stored only with that mode.
  def validate(setting: ProjectSetting.Mode): Unit = {
    unavailable(setting.value).foreach(reason => throw DomainFailure(Fault.Invalid(reason)))
    if (setting.selfReviewWithoutChecks && setting.value != ProcessMode.Yolo) throw DomainFailure(Fault.Invalid(
      (s"Self-review without configured checks can be allowed only in the ${label(ProcessMode.Yolo)} mode; the requested mode is ${label(setting.value)}" ::
        unavailable(ProcessMode.Yolo).toList).mkString(". ")))
  }
}

object ProcessModePolicy {
  val Default: ProjectSetting.Mode = ProjectSetting.Mode(ProcessMode.Rigorous, false)

  /** Whether this release lets a project be given the YOLO mode and its exemption. */
  val YoloAvailable = false
  val Release: ProcessModePolicy = new ProcessModePolicy(YoloAvailable)

  private val YoloUnavailable = "The YOLO cross-cutting mode is not available in this release"

  def label(mode: ProcessMode): String = mode match {
    case ProcessMode.Rigorous => "Rigorous"
    case ProcessMode.CrossCutting => "Cross-cutting"
    case ProcessMode.Yolo => "YOLO cross-cutting"
  }

  /** The project's mode as stored now; a project without a stored document has the default. */
  def current(tx: LedgerTransaction): ProjectSetting.Mode = tx.setting(ProjectSettingKind.Mode).map(_.value) match {
    case Some(mode: ProjectSetting.Mode) => mode
    case Some(other) => throw new IllegalStateException(s"The process mode row holds a ${ProjectSettingKind.of(other)} document")
    case None => Default
  }
}
