# Build-time check of homeManagerModules.default; run through dev/home-manager-module-check (impure: imports a native release by path).
# Home Manager is the source of pkgs.home-manager in the nixpkgs this flake pins; no further input is fetched.
{ release }:
let
  flake = builtins.getFlake (toString ../..);
  pkgs = import flake.inputs.nixpkgs { system = "x86_64-linux"; };
  inherit (pkgs) lib;
  cq = flake.lib.mkNativePackage { inherit pkgs; release = /. + release; };
  homeManager = import (pkgs.home-manager.src + "/lib") { inherit lib; };
  # One version per harness that the package verifies; asset export refuses any other.
  routes = [
    { harness = "Claude"; provider = "anthropic"; version = "2.1.285"; }
    { harness = "Codex"; provider = "openai"; version = "0.159.2"; }
    { harness = "Pi"; provider = "openai-codex"; version = "0.99.1"; }
  ];
  directory = "/home/cq-fixture";
  tokenFile = "/run/user/1000/secrets/cq-token";
  configuration = token: homeManager.homeManagerConfiguration {
    inherit pkgs;
    modules = [ flake.homeManagerModules.default {
      home.username = "cq-fixture";
      home.homeDirectory = directory;
      home.stateVersion = "26.05";
      programs.cq = {
        enable = true;
        package = cq;
        tokenFile = token;
        projects.consumer = {
          directory = "${directory}/consumer";
          harnesses = map (route: route.harness) routes;
          settings = {
            stateRoot = "${directory}/state";
            # Asset export only records the executable; no harness is started by this check.
            harnesses = map (route: route // { executable = "${pkgs.coreutils}/bin/false"; model = "fixture"; }) routes;
            limits = { startupMillis = 10000; heartbeatMillis = 2000; graceMillis = 1000; killMillis = 3000; retainedOutputBytes = 1048576; };
          };
        };
      };
    } ];
  };
  home = configuration tokenFile;
  accepted = module: builtins.all (assertion: assertion.assertion) module.config.assertions;
  files = lib.filterAttrs (name: _: lib.hasPrefix "consumer/" name) home.config.home.file;
in
assert lib.assertMsg (accepted home) "The module's assertions reject the fixture configuration";
# Home Manager throws on a failed assertion as soon as the configuration is read.
assert lib.assertMsg (!(builtins.tryEval (accepted (configuration "/nix/store/token"))).success) "A token file in the Nix store was accepted";
assert lib.assertMsg (files != {} && builtins.all (file: file.recursive && !file.force) (builtins.attrValues files))
  "Project files must be recursive links that do not overwrite existing files";
pkgs.runCommand "cq-home-manager-module-check" { nativeBuildInputs = [ pkgs.jq ]; } ''
  generation=${home.activationPackage}
  test "$(readlink -f $generation/home-path/bin/cq)" = ${cq}/bin/cq
  grep -qxF 'export CQ_TOKEN_FILE="${tokenFile}"' $generation/home-path/etc/profile.d/hm-session-vars.sh
  mkdir -p $out project
  cp -rs $generation/home-files/consumer/. project/
  for harness in claude codex pi; do
    ${cq}/bin/cq doctor commands $harness --directory $PWD/project --json > $out/$harness.json
    jq -e '.current and (.checks | length > 0) and (.checks | all(.state == "Current"))' $out/$harness.json > /dev/null
  done
  test -z "$(find project -type f)"
''
