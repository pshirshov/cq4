#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ $# != 2 ]]; then
  echo "Usage: $0 RELEASE_DIRECTORY STATE_DIRECTORY" >&2
  echo "Optional ports: CQ_LOCAL_PORT=8080 CQ_LOCAL_DB_PORT=55432" >&2
  echo "Optional browser address: CQ_ORIGIN=http://server-address:8080" >&2
  exit 2
fi
for command in realpath flock initdb pg_ctl python3 curl; do
  command -v "$command" >/dev/null || { echo "Missing command: $command (use nix develop)" >&2; exit 1; }
done
release=$(realpath -e -- "$1")
state=$(realpath -m -- "$2")
port=${CQ_LOCAL_PORT:-8080}
db_port=${CQ_LOCAL_DB_PORT:-55432}
for value in "$port" "$db_port"; do
  [[ $value =~ ^[0-9]{1,5}$ ]] && (( 10#$value > 0 && 10#$value < 65536 )) || { echo "Invalid port: $value" >&2; exit 2; }
done
[[ $port != "$db_port" ]] || { echo "Server and database ports must differ" >&2; exit 2; }
[[ -x "$release/bin/cq" && -x "$release/bin/cq-guardian" ]] || { echo "Missing CQ distribution executables" >&2; exit 1; }
[[ $(id -u) != 0 ]] || { echo "Run as your ordinary user; initdb does not run as root" >&2; exit 1; }

if [[ ! -d $state ]]; then
  mkdir -m 700 -- "$state"
  touch "$state/.cq-local"
fi
[[ -f "$state/.cq-local" && -O $state && ! -L $state ]] || { echo "State must be a new directory or an owned CQ local directory: $state" >&2; exit 1; }
chmod 700 "$state"
exec 9>"$state/launcher.lock"
flock -n 9 || { echo "A launcher already owns $state" >&2; exit 1; }
mkdir -p "$state/logs" "$state/sessions"
for secret in token database-password; do
  if [[ ! -f $state/$secret ]]; then
    python3 -c 'import secrets; print(secrets.token_hex(32))' > "$state/$secret"
  fi
done
export CQ_TOKEN="$(cat "$state/token")"
export CQ_DATABASE_PASSWORD="$(cat "$state/database-password")"
export CQ_DATABASE_USER=cq
export CQ_DATABASE_URL="jdbc:postgresql://127.0.0.1:$db_port/postgres"
health_origin="http://127.0.0.1:$port"
export CQ_HOST=0.0.0.0 CQ_PORT="$port" CQ_ORIGIN="${CQ_ORIGIN:-$health_origin}"
export CQ_BIN="$release/bin/cq" CQ_LOCAL_STATE="$state"
{
  printf 'export CQ_TOKEN=%q\n' "$CQ_TOKEN"
  printf 'export CQ_ORIGIN=%q\n' "$CQ_ORIGIN"
  printf 'export CQ_BIN=%q\n' "$CQ_BIN"
  printf 'export CQ_LOCAL_STATE=%q\n' "$state"
  printf 'export PATH=%q:"$PATH"\n' "$release/bin"
} > "$state/client.env"

data="$state/postgres"
if [[ ! -f $data/PG_VERSION ]]; then
  initdb -D "$data" -U cq --auth-local=trust --auth-host=scram-sha-256 \
    --pwfile="$state/database-password" --encoding=UTF8 --no-locale > "$state/logs/initdb.log" 2>&1
fi
if pg_ctl -D "$data" status >/dev/null 2>&1; then
  echo "PostgreSQL is already running for this state; inspect it before relaunching: $data" >&2
  exit 1
fi
server_pid=
database_owned=0
readonly SERVER_GRACE_SECONDS=10 SERVER_KILL_SECONDS=5
await_server_exit() {
  local deadline=$((SECONDS + $1))
  while kill -0 "$server_pid" 2>/dev/null; do
    (( SECONDS < deadline )) || return 1
    sleep 0.1
  done
  return 0
}
cleanup() {
  result=$?
  trap - EXIT INT TERM
  if [[ -n $server_pid ]]; then
    kill -TERM "$server_pid" 2>/dev/null || true
    if ! await_server_exit "$SERVER_GRACE_SECONDS"; then
      echo "CQ did not exit after SIGTERM; sending SIGKILL to the owned server" >&2
      kill -KILL "$server_pid" 2>/dev/null || true
      if ! await_server_exit "$SERVER_KILL_SECONDS"; then
        echo "CQ shutdown unresolved; PostgreSQL retained. Inspect $state/logs before restarting" >&2
        exit 75
      fi
    fi
    wait "$server_pid" 2>/dev/null || true
  fi
  if [[ $database_owned == 1 ]] && pg_ctl -D "$data" status >/dev/null 2>&1; then
    pg_ctl -D "$data" -m fast -w -t 30 stop >> "$state/logs/postgres-control.log" 2>&1 || {
      echo "PostgreSQL shutdown unresolved; inspect $state/logs/postgres-control.log" >&2
      result=1
    }
  fi
  echo "CQ stopped. Data and logs retained in $state"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Disable Unix sockets so the only connection path uses loopback password authentication.
database_owned=1
pg_ctl -D "$data" -l "$state/logs/postgres.log" \
  -o "-h 127.0.0.1 -p $db_port -c unix_socket_directories=''" -w -t 30 start >> "$state/logs/postgres-control.log" 2>&1
"$CQ_BIN" serve >> "$state/logs/cq-server.log" 2>&1 9>&- &
server_pid=$!
ready=0
for ((attempt=0; attempt<60; attempt++)); do
  kill -0 "$server_pid" 2>/dev/null || break
  if curl --silent --fail --max-time 1 \
    -H "Authorization: Bearer $CQ_TOKEN" -H 'CQ-Session: 00000000-0000-0000-0000-000000000001' \
    "$health_origin/api/hello" > "$state/hello.json"; then
    ready=1
    break
  fi
  sleep 0.5
done
[[ $ready == 1 ]] || { echo "CQ startup failed; inspect $state/logs/cq-server.log" >&2; exit 1; }
python3 -c 'import json,sys; assert json.load(open(sys.argv[1])) == {"version":"0.1.0","supported":["0.1.0"]}' "$state/hello.json"
printf '\nCQ ready: %s\nBrowser login token: cat %q\nSecond terminal: source %q\n' "$CQ_ORIGIN" "$state/token" "$state/client.env"
printf 'Listening on %s:%s; browser origin must match CQ_ORIGIN.\n' "$CQ_HOST" "$CQ_PORT"
echo 'Leave this terminal open. Stop consumer runs before pressing Ctrl-C. Reuse the same command to restart.'
wait "$server_pid"
server_pid=
