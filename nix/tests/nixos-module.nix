# NixOS VM test of nixosModules.default; run through dev/nixos-module-check (impure: imports a native release by path).
{ release }:
let
  flake = builtins.getFlake (toString ../..);
  pkgs = import flake.inputs.nixpkgs { system = "x86_64-linux"; };
  cq = flake.lib.mkNativePackage { inherit pkgs; release = /. + release; };
  secrets = "/var/lib/cq-test-secrets";
  origin = "http://127.0.0.1:8080";
in pkgs.testers.runNixOSTest {
  name = "cq-nixos-module";
  nodes.machine = { pkgs, ... }: {
    imports = [ flake.nixosModules.default ];
    services.cq = {
      enable = true;
      package = cq;
      inherit origin;
      tokenFile = "${secrets}/token";
      database.passwordFile = "${secrets}/database-password";
    };
    # The secrets are generated inside the guest on its first boot, so no value exists at evaluation time.
    systemd.services.cq-test-secrets = {
      wantedBy = [ "multi-user.target" ];
      before = [ "cq-database-password.service" "cq.service" ];
      requiredBy = [ "cq-database-password.service" "cq.service" ];
      unitConfig.ConditionPathExists = "!${secrets}/token";
      serviceConfig = { Type = "oneshot"; RemainAfterExit = true; UMask = "0077"; };
      script = ''
        mkdir -p ${secrets}
        for name in token database-password; do
          ${pkgs.coreutils}/bin/od -An -tx1 -N32 /dev/urandom | ${pkgs.coreutils}/bin/tr -d ' \n' > ${secrets}/$name.new
          echo >> ${secrets}/$name.new
        done
        mv ${secrets}/database-password.new ${secrets}/database-password
        mv ${secrets}/token.new ${secrets}/token
      '';
    };
    environment.systemPackages = [ cq pkgs.git pkgs.curl ];
    virtualisation.memorySize = 2048;
  };
  testScript = ''
    import json
    import uuid

    ORIGIN = "${origin}"
    SECRETS = "${secrets}"
    CLIENT = f"cd /root/consumer && CQ_TOKEN_FILE={SECRETS}/token "
    session = str(uuid.uuid4())

    def ready():
        machine.wait_for_unit("cq.service")
        machine.wait_until_succeeds(
            f"curl --silent --fail --max-time 2 -H \"Authorization: Bearer $(cat {SECRETS}/token)\" -H 'CQ-Session: {session}' {ORIGIN}/api/hello")

    def doctor():
        report = json.loads(machine.succeed(CLIENT + f"cq doctor server --endpoint {ORIGIN} --require-settled --json"))
        states = {check["name"]: check["state"] for check in report["checks"]}
        assert report["current"] and states and set(states.values()) == {"Current"}, report
        return states

    def call(command):
        machine.succeed("cat > /root/command.json <<'JSON'\n" + json.dumps(command) + "\nJSON")
        reply = json.loads(machine.succeed(
            f"curl --silent --show-error --fail --max-time 30 -H \"Authorization: Bearer $(cat {SECRETS}/token)\" "
            f"-H 'CQ-Session: {session}' -H 'CQ-Protocol-Version: 0.1.0' -H 'Content-Type: application/json' "
            f"--data-binary @/root/command.json {ORIGIN}/api/call"))
        assert "Failed" not in reply, reply
        return reply

    def retained(project, title):
        view = call({"Read": {"input": {"project": project, "selection": {"ItemDetail": {"id": {"project": project, "ledger": "Tasks", "number": "1"}}}}}})["Detail"]["view"]
        assert view["item"]["draft"]["title"] == title, view
        found = machine.succeed(CLIENT + "cq query --query 'ledger:Tasks' --json")
        assert title in found, found

    def service_identity():
        assert machine.succeed("systemctl show cq.service --property=User --value").strip() == "cq"
        pid = machine.succeed("systemctl show cq.service --property=MainPID --value").strip()
        assert pid != "0" and machine.succeed(f"ps -o user= -p {pid}").strip() == "cq"
        return pid

    def secrets_confined(pid):
        for name in ["token", "database-password"]:
            assert machine.succeed(f"stat -c '%a %U' {SECRETS}/{name}").strip() == "600 root"
            status, output = machine.execute(f"runuser -u cq -- cat {SECRETS}/{name} 2>&1")
            assert status == 1 and "Permission denied" in output, (status, output)
            # Unit text, properties, process environment and every store path the units name must not hold the value.
            machine.succeed("systemctl cat cq.service cq-database-password.service > /root/units.txt")
            machine.succeed("systemctl show cq.service cq-database-password.service > /root/properties.txt")
            machine.succeed(f"tr '\\0' '\\n' < /proc/{pid}/environ > /root/environment.txt")
            machine.succeed("grep -oE '/nix/store/[a-z0-9]{32}-[^/\" :;]+' /root/units.txt /root/properties.txt -h | sort -u > /root/store-paths.txt")
            assert int(machine.succeed("wc -l < /root/store-paths.txt")) >= 3
            machine.succeed(f"test -s {SECRETS}/{name}")
            # Exit 1 is "searched everything, no match"; 2 would be an unreadable path.
            status, output = machine.execute(f"grep -rlFf {SECRETS}/{name} /root/units.txt /root/properties.txt /root/environment.txt /etc/systemd/system/cq.service /etc/systemd/system/cq-database-password.service $(cat /root/store-paths.txt) 2>&1")
            assert status == 1 and output == "", (status, output)

    machine.start()
    ready()
    machine.wait_for_unit("postgresql.service")
    machine.succeed("git init --quiet /root/consumer")
    print("doctor after first boot:", doctor())
    pid = service_identity()
    secrets_confined(pid)

    initialized = json.loads(machine.succeed(CLIENT + f"cq init --endpoint {ORIGIN} --name 'NixOS module check' --json"))
    print("initialized:", initialized)
    project = json.loads(machine.succeed("cat /root/consumer/.git/cq/project.json"))["project"]
    title = "Survives the service and the machine"
    call({"Change": {"input": {"project": project, "change": {"request": {"value": str(uuid.uuid4())}, "mutations": [{"Create": {"draft": {
        "title": title, "body": "NixOS module check", "labels": [], "archived": False, "citations": [],
        "content": {"Task": {"status": "Ready", "acceptance": ["Retained"], "result": None, "validation": []}}}}}],
        "fences": [], "reason": "NixOS module check"}}}})
    retained(project, title)

    machine.succeed("systemctl restart cq.service")
    ready()
    assert service_identity() != pid
    retained(project, title)
    print("doctor after service restart:", doctor())

    machine.shutdown()
    machine.start()
    ready()
    retained(project, title)
    print("doctor after reboot:", doctor())
    secrets_confined(service_identity())
    machine.succeed("systemctl stop cq.service")
    stopped = machine.succeed("systemctl show cq.service --property=ActiveState,Result").split()
    assert sorted(stopped) == ["ActiveState=inactive", "Result=success"], stopped
    machine.succeed("systemctl is-active postgresql.service")

    # The server does not outlive the database it requires: stopping PostgreSQL stops it, in order.
    machine.succeed("systemctl start cq.service")
    ready()
    machine.succeed("systemctl stop postgresql.service")
    machine.wait_until_succeeds("test \"$(systemctl show cq.service --property=ActiveState --value)\" = inactive", timeout=60)
    assert machine.succeed("systemctl show cq.service --property=Result --value").strip() == "success"
  '';
}
