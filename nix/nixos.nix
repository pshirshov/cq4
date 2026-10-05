{ config, lib, pkgs, ... }:
let
  cfg = config.services.cq;
  inherit (lib) mkOption mkEnableOption mkIf types;
  identifier = types.strMatching "[a-z_][a-z0-9_]{0,62}";
  runtimeFile = path: lib.hasPrefix "/" path && !lib.hasPrefix "/nix/store/" path;
  passwordSetup = pkgs.writeText "cq-postgres-password.py" ''
    import os, pathlib, subprocess
    with pathlib.Path(os.environ["CREDENTIALS_DIRECTORY"], "database-password").open("rb") as stream:
        value = stream.read(8193)
    if len(value) > 8192:
        raise SystemExit("CQ database credential exceeds 8192 bytes")
    if value.endswith(b"\r\n"):
        value = value[:-2]
    elif value.endswith(b"\n"):
        value = value[:-1]
    if not value or len(value) > 8192 or any(c in value for c in (b"\r", b"\n", b"\0")):
        raise SystemExit("CQ database credential must contain one bounded nonempty line")
    try:
        password = value.decode("utf-8").replace(chr(39), chr(39) * 2)
    except UnicodeError:
        raise SystemExit("CQ database credential must be UTF-8")
    sql = "SET standard_conforming_strings = on; ALTER ROLE ${cfg.database.user} PASSWORD '" + password + "';"
    environment = dict(os.environ, PGOPTIONS="-c log_statement=none -c log_min_error_statement=panic")
    result = subprocess.run(["${config.services.postgresql.package}/bin/psql", "--no-psqlrc", "--set=ON_ERROR_STOP=1", "--port=${toString cfg.database.port}", "postgres"], env=environment, input=sql.encode(), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    if result.returncode:
        raise SystemExit("CQ database password setup failed; SQL and credential contents are withheld")
  '';
in {
  options.services.cq = {
    enable = mkEnableOption "CQ ledger server";
    package = mkOption { type = types.package; description = "Native CQ package built with lib.mkNativePackage."; };
    origin = mkOption { type = types.str; description = "Public HTTP(S) origin used for browser authorization."; };
    listenAddress = mkOption { type = types.str; default = "127.0.0.1"; };
    port = mkOption { type = types.port; default = 8080; };
    stateDirectory = mkOption { type = types.strMatching "[a-zA-Z0-9_-]+"; default = "cq"; };
    tokenFile = mkOption { type = types.str; description = "Absolute runtime operator-token file, outside the Nix store."; };
    database = {
      managed = mkOption { type = types.bool; default = true; description = "Provision a CQ database and role in the NixOS PostgreSQL 18 service."; };
      host = mkOption { type = types.str; default = "127.0.0.1"; };
      port = mkOption { type = types.port; default = 5432; };
      name = mkOption { type = identifier; default = "cq"; };
      user = mkOption { type = identifier; default = "cq"; };
      passwordFile = mkOption { type = types.str; description = "Absolute runtime database-password file, outside the Nix store."; };
    };
  };
  config = mkIf cfg.enable {
    assertions = [
      { assertion = runtimeFile cfg.tokenFile && runtimeFile cfg.database.passwordFile; message = "CQ credentials require absolute runtime file names outside /nix/store; do not use Nix path literals."; }
      { assertion = builtins.match "https?://[^/?#@]+/?" cfg.origin != null; message = "services.cq.origin must be an HTTP(S) origin."; }
      { assertion = !cfg.database.managed || cfg.database.name == cfg.database.user; message = "Managed CQ database name must equal the role name for ensureDBOwnership."; }
      { assertion = !cfg.database.managed || cfg.database.host == "127.0.0.1"; message = "Managed CQ database uses IPv4 loopback."; }
      { assertion = !cfg.database.managed || lib.versions.major config.services.postgresql.package.version == "18"; message = "CQ managed database requires PostgreSQL 18."; }
      { assertion = !cfg.database.managed || lib.all (key: builtins.elem config.services.postgresql.settings.${key} [ true "on" ]) [ "fsync" "synchronous_commit" "full_page_writes" ]; message = "CQ managed PostgreSQL must retain fsync, synchronous_commit and full_page_writes."; }
    ];
    users.users.cq = { isSystemUser = true; group = "cq"; home = "/var/lib/${cfg.stateDirectory}"; };
    users.groups.cq = {};
    services.postgresql = mkIf cfg.database.managed {
      enable = true;
      package = lib.mkOverride 900 pkgs.postgresql_18;
      settings.port = cfg.database.port;
      settings.fsync = lib.mkOverride 900 "on";
      settings.synchronous_commit = lib.mkOverride 900 "on";
      settings.full_page_writes = lib.mkOverride 900 "on";
      ensureDatabases = [ cfg.database.name ];
      ensureUsers = [ { name = cfg.database.user; ensureDBOwnership = true; } ];
      authentication = lib.mkBefore "host ${cfg.database.name} ${cfg.database.user} 127.0.0.1/32 scram-sha-256\n";
    };
    systemd.services.cq-database-password = mkIf cfg.database.managed {
      description = "Set CQ database credential from a runtime systemd credential";
      requires = [ "postgresql.target" ];
      after = [ "postgresql.target" ];
      before = [ "cq.service" ];
      serviceConfig = {
        Type = "oneshot";
        User = "postgres";
        LoadCredential = [ "database-password:${cfg.database.passwordFile}" ];
        ExecStart = "${pkgs.python3}/bin/python3 ${passwordSetup}";
        UMask = "0077";
      };
    };
    systemd.services.cq = {
      description = "CQ ledger server";
      wantedBy = [ "multi-user.target" ];
      requires = lib.optionals cfg.database.managed [ "postgresql.target" "cq-database-password.service" ];
      after = [ "network.target" ] ++ lib.optionals cfg.database.managed [ "postgresql.target" "cq-database-password.service" ];
      environment = {
        CQ_ORIGIN = cfg.origin;
        CQ_HOST = cfg.listenAddress;
        CQ_PORT = toString cfg.port;
        CQ_DATABASE_URL = "jdbc:postgresql://${cfg.database.host}:${toString cfg.database.port}/${cfg.database.name}";
        CQ_DATABASE_USER = cfg.database.user;
        CQ_DATABASE_PASSWORD_FILE = "%d/database-password";
        CQ_TOKEN_FILE = "%d/token";
      };
      serviceConfig = {
        ExecStart = "${cfg.package}/bin/cq serve";
        User = "cq";
        Group = "cq";
        StateDirectory = cfg.stateDirectory;
        StateDirectoryMode = "0700";
        WorkingDirectory = "/var/lib/${cfg.stateDirectory}";
        LoadCredential = [ "token:${cfg.tokenFile}" "database-password:${cfg.database.passwordFile}" ];
        Restart = "on-failure";
        RestartSec = 5;
        TimeoutStopSec = 30;
        KillSignal = "SIGTERM";
        UMask = "0077";
        NoNewPrivileges = true;
        ProtectSystem = "strict";
        ProtectHome = true;
        PrivateTmp = true;
      };
    };
  };
}
