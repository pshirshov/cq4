package cq.host

import cq.api.StopReason

final case class GuardianExit(code: Option[Int], signal: Option[Int], reason: StopReason,
  stdoutBytes: Long, stderrBytes: Long, settled: Boolean, hostFailure: Boolean)

final case class GuardianTranscript(root: Option[Long], stop: Option[StopReason], result: Option[GuardianExit]) {
  def append(line: String): GuardianTranscript = {
    require(result.isEmpty, "Guardian output after terminal record")
    def reason(text: String): StopReason = StopReason.parse(text).getOrElse(throw new IllegalArgumentException("Unknown guardian stop reason"))
    def number(text: String, minimum: Long, maximum: Long): Long = {
      require(text.matches("-?[0-9]+"), "Malformed guardian number")
      val value = text.toLong
      require(value >= minimum && value <= maximum, "Guardian number outside bounds")
      value
    }
    def flag(text: String): Boolean = number(text, 0, 1) == 1
    line.split(" ", -1).toList match {
      case "START" :: pid :: Nil =>
        require(root.isEmpty, "Repeated guardian start")
        copy(root = Some(number(pid, 2, Int.MaxValue)))
      case "STOP" :: cause :: Nil =>
        require(stop.isEmpty, "Repeated guardian stop")
        copy(stop = Some(reason(cause)))
      case "EXIT" :: code :: signal :: cause :: out :: err :: settled :: failure :: Nil =>
        val exitCode = number(code, -1, 255).toInt
        val exitSignal = number(signal, 0, 64).toInt
        val exited = reason(cause)
        require(stop.exists(s => s == exited || s == StopReason.Exited), "Guardian terminal reason contradicts stop")
        val confirmed = flag(settled)
        if (confirmed) require((exitCode >= 0 && exitSignal == 0) || (exitCode == -1 && exitSignal > 0), "Settled root has no consistent exit status")
        if (exited == StopReason.Exited) require(root.nonEmpty, "Normal completion without start acknowledgement")
        copy(result = Some(GuardianExit(Option.when(exitCode >= 0)(exitCode), Option.when(exitSignal > 0)(exitSignal), exited,
          number(out, 0, Long.MaxValue), number(err, 0, Long.MaxValue), confirmed, flag(failure))))
      case _ => throw new IllegalArgumentException("Malformed guardian lifecycle record")
    }
  }

  def complete(helperExit: Int): GuardianExit = {
    val value = result.getOrElse(throw new IllegalArgumentException("Guardian terminal record missing"))
    require(helperExit == (if (value.settled && !value.hostFailure) 0 else 3), "Helper exit contradicts terminal record")
    value
  }
}
