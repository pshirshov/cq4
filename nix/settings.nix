{ lib, ... }:
let
  inherit (lib) mkOption types;
  absolute = types.strMatching "/.*";
  positive = types.ints.positive;
in {
  options = {
    stateRoot = mkOption { type = absolute; description = "Private supervisor state root outside consumer repositories."; };
    harnesses = mkOption {
      type = types.listOf (types.submodule {
        options = {
          harness = mkOption { type = types.enum [ "Claude" "Codex" "Pi" ]; };
          executable = mkOption { type = absolute; };
          model = mkOption { type = types.nonEmptyStr; };
          provider = mkOption { type = types.nonEmptyStr; };
          version = mkOption { type = types.nonEmptyStr; description = "An installed harness version verified by this CQ package."; };
          providerExtensions = mkOption { type = types.listOf absolute; default = []; };
          providerEnvironment = mkOption { type = types.listOf types.nonEmptyStr; default = []; description = "Environment variable names; never credential values."; };
        };
      });
    };
    limits = mkOption {
      type = types.submodule {
        options = builtins.listToAttrs (map (name: { inherit name; value = mkOption { type = positive; }; })
          [ "startupMillis" "heartbeatMillis" "graceMillis" "killMillis" "retainedOutputBytes" ]);
      };
    };
    checks = mkOption {
      type = types.listOf (types.submodule {
        options = {
          name = mkOption { type = types.strMatching "[a-z][a-z0-9-]{0,49}"; };
          command = mkOption { type = types.listOf types.nonEmptyStr; };
          executionMillis = mkOption { type = positive; };
          retainedOutputBytes = mkOption { type = positive; };
          attempts = mkOption { type = types.ints.between 1 3; };
          revalidations = mkOption { type = types.ints.between 0 3; };
        };
      });
      default = [];
    };
    integrationTarget = mkOption { type = types.nullOr types.nonEmptyStr; default = null; };
  };
}
