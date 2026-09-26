{
  description = "CQ development and native build environment";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/4975466d324710c576dc11ad614684e6bd8cad8e";
  outputs = { nixpkgs, ... }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "aarch64-darwin" ];
      forSystems = nixpkgs.lib.genAttrs systems;
    in {
      devShells = forSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
          java = pkgs.graalvmPackages.graalvm-ce;
        in {
          default = pkgs.mkShell {
            packages = [
              java
              (pkgs.sbt.override { jre = java; })
              pkgs.nodejs_24
              pkgs.postgresql_18
              pkgs.python3
              pkgs.curl
              pkgs.gcc
              pkgs.zlib
              pkgs.pkg-config
            ];
            JAVA_HOME = java;
          };
        });
    };
}
