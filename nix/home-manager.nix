{ config, lib, pkgs, ... }:
let
  cfg = config.programs.cq;
  inherit (lib) mkOption mkEnableOption mkIf types;
  settingsFile = name: project: pkgs.writeText "cq-${name}-settings.json" (builtins.toJSON
    (project.settings // { guardian = "${cfg.package}/bin/cq-guardian"; evaluation = null; }));
  exported = name: project: pkgs.runCommand "cq-${name}-assets" {} ''
    mkdir -p "$out"
    ${lib.concatMapStringsSep "\n" (harness: ''
      ${cfg.package}/bin/cq assets export ${lib.toLower harness} --directory "$out" \
        --project-directory ${lib.escapeShellArg project.directory} \
        --settings ${settingsFile name project} --executable ${cfg.package}/bin/cq --json
    '') project.harnesses}
  '';
  projectFiles = name: project:
    let
      assets = exported name project;
      relative = lib.removePrefix (config.home.homeDirectory + "/") project.directory;
      roots = lib.unique (lib.concatMap (harness:
        if harness == "Claude" then [ ".mcp.json" ".claude/commands/cq" ".claude/settings.local.json" ]
        else if harness == "Codex" then [ ".codex/config.toml" ".codex/hooks.json" ".agents/skills" ]
        else [ ".pi/extensions" ".pi/prompts" ]) project.harnesses);
    in builtins.listToAttrs (map (root: {
      name = "${relative}/${root}";
      value = { source = "${assets}/${root}"; recursive = true; };
    }) roots);
in {
  options.programs.cq = {
    enable = mkEnableOption "CQ CLI and declarative project integrations";
    package = mkOption { type = types.package; description = "Native package built with lib.mkNativePackage."; };
    tokenFile = mkOption { type = types.nullOr types.str; default = null; description = "Optional absolute runtime operator-token file; only its name enters session variables."; };
    projects = mkOption {
      default = {};
      type = types.attrsOf (types.submodule {
        options = {
          directory = mkOption { type = types.strMatching "/.*"; description = "Consumer project directory under home.homeDirectory."; };
          harnesses = mkOption { type = types.listOf (types.enum [ "Claude" "Codex" "Pi" ]); description = "Harness integrations owned by home-manager in this project."; };
          settings = mkOption { type = types.submodule (import ./settings.nix); };
        };
      });
    };
  };
  config = mkIf cfg.enable {
    home.packages = [ cfg.package ];
    home.sessionVariables = lib.optionalAttrs (cfg.tokenFile != null) { CQ_TOKEN_FILE = cfg.tokenFile; };
    assertions = [ {
      assertion = let directories = map (project: project.directory) (builtins.attrValues cfg.projects);
        in lib.unique directories == directories;
      message = "CQ project directories must be distinct.";
    } {
      assertion = cfg.tokenFile == null || lib.hasPrefix "/" cfg.tokenFile && !lib.hasPrefix "/nix/store/" cfg.tokenFile;
      message = "CQ tokenFile must name an absolute runtime file outside /nix/store; do not use a Nix path literal.";
    } ] ++ lib.mapAttrsToList (name: project: {
      assertion = lib.hasPrefix (config.home.homeDirectory + "/") project.directory
        && project.harnesses != [] && lib.unique project.harnesses == project.harnesses
        && lib.all (harness: lib.any (route: route.harness == harness) project.settings.harnesses) project.harnesses;
      message = "CQ project ${name} must be under the home directory and have distinct integrations with configured routes.";
    }) cfg.projects;
    home.file = lib.foldl' lib.recursiveUpdate {} (lib.mapAttrsToList projectFiles cfg.projects);
  };
}
