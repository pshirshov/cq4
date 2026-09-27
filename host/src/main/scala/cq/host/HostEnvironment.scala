package cq.host

object HostEnvironment {
  private val Allowed = Set("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "SSL_CERT_FILE", "SSL_CERT_DIR", "NIX_SSL_CERT_FILE", "__NIXOS_SET_ENVIRONMENT_DONE")
  def runtime(source: Map[String, String]): Map[String, String] = source.filter((name, _) => Allowed(name))
}
