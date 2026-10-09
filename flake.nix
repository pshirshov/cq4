{
  description = "CQ development and native build environment";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/4975466d324710c576dc11ad614684e6bd8cad8e";
  inputs.baboon.url = "github:7mind/baboon/c003b03a64b1e2ccdc060d20072c56f010beb111";
  inputs.ponygirls = {
    url = "github:7mind/ponygirls/7aa0caab568f82a90653f701e60e7f30aae027b6";
    flake = false;
  };
  outputs = { self, nixpkgs, baboon, ponygirls, ... }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ];
      forSystems = nixpkgs.lib.genAttrs systems;
    in {
      lib.mkNativePackage = import ./nix/package.nix;
      nixosModules.default = import ./nix/nixos.nix;
      homeManagerModules.default = import ./nix/home-manager.nix;
      packages = forSystems (system:
        let pkgs = import nixpkgs { inherit system; };
        in {
          ponygirls-subagents = pkgs.runCommand "ponygirls-subagents" { } ''
            cp -r ${ponygirls + "/nix/pkg/pi-extensions/ponygirls-subagents"} $out
          '';
        });
      devShells = forSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
          java = pkgs.graalvmPackages.graalvm-ce;
          compiler = baboon.packages.${system}.baboon-jvm;
          subagents = self.packages.${system}.ponygirls-subagents;
        in {
          default = pkgs.mkShell ({
            packages = [
              java
              compiler
              (pkgs.sbt.override { jre = java; })
              pkgs.nodejs_24
              pkgs.postgresql_18
              pkgs.python3
              pkgs.go_1_27
              pkgs.curl
              pkgs.gcc
              pkgs.zlib
              pkgs.pkg-config
            ];
            JAVA_HOME = java;
            CQ_BABOON = "${compiler}/bin/baboon-jvm";
            CQ_PONYGIRLS_SUBAGENTS = "${subagents}";
          } // pkgs.lib.optionalAttrs pkgs.stdenv.hostPlatform.isLinux {
            PLAYWRIGHT_BROWSERS_PATH = pkgs.playwright-driver.browsers;
            PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS = "true";
          });
        });
    };
}
