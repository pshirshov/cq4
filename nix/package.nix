{ pkgs, release }:
let
  manifest = builtins.fromJSON (builtins.readFile (release + "/manifest.json"));
  source = builtins.path {
    path = release;
    name = "cq-native-release";
    filter = path: type:
      let relative = pkgs.lib.removePrefix (toString release + "/") path;
      in path == toString release || relative == "manifest.json" || relative == "bin"
        || builtins.elem relative [ "bin/cq" "bin/cq-guardian" ];
  };
in
assert pkgs.lib.assertMsg (manifest.modelVersion == "0.1.0") "CQ native package requires model 0.1.0";
assert pkgs.lib.assertMsg (manifest.platform == pkgs.stdenv.hostPlatform.system) "CQ release platform differs from the target system";
assert pkgs.lib.assertMsg (builtins.all (name: builtins.match "[0-9a-f]{64}" manifest.filesSha256.${name} != null)
  [ "bin/cq" "bin/cq-guardian" ]) "CQ manifest executable checksums must be SHA-256 hex digests";
pkgs.stdenv.mkDerivation {
  pname = "cq";
  version = manifest.modelVersion;
  src = source;
  nativeBuildInputs = [ pkgs.autoPatchelfHook pkgs.makeWrapper ];
  buildInputs = [ pkgs.stdenv.cc.cc.lib pkgs.zlib ];
  dontBuild = true;
  installPhase = ''
    runHook preInstall
    echo '${manifest.filesSha256."bin/cq"}  bin/cq' | sha256sum --check --status
    echo '${manifest.filesSha256."bin/cq-guardian"}  bin/cq-guardian' | sha256sum --check --status
    install -Dm755 bin/cq "$out/bin/cq"
    install -Dm755 bin/cq-guardian "$out/bin/cq-guardian"
    install -Dm444 manifest.json "$out/share/cq/manifest.json"
    install -Dm444 ${../dev/codex-hook-report.py} "$out/share/cq/codex-hook-report.py"
    makeWrapper ${pkgs.python3}/bin/python3 "$out/bin/cq-codex-hook-report" --add-flags "$out/share/cq/codex-hook-report.py"
    mkdir -p "$out/share/cq/doctor-home"
    chmod 555 "$out/share/cq/doctor-home"
    runHook postInstall
  '';
  meta = {
    description = "CQ native ledger server, CLI and guardian";
    platforms = [ "x86_64-linux" ];
    mainProgram = "cq";
  };
}
