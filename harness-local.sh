#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 [--profile YOLO_PROFILE] <claude|codex|pi> [harness arguments...]" >&2
  echo "The yolo profile is 'work' unless --profile or CQ_LOCAL_PROFILE names another." >&2
  echo "Optional environment: CQ_ORIGIN, CQ_LOCAL_STATE, CQ_LOCAL_PORT, CQ_LOCAL_PROFILE" >&2
  exit 2
}
profile="${CQ_LOCAL_PROFILE:-work}"
if [[ ${1:-} == --profile ]]; then
  [[ $# -ge 2 && -n $2 ]] || usage
  profile=$2
  shift 2
fi
[[ $# -ge 1 ]] || usage
harness=$1
shift
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cq="$repo/.local/release/bin/cq"
settings="$repo/.local/interactive/settings.json"
token="${CQ_LOCAL_STATE:-/srv/nvme/tmp/cq4-playground}/token"
origin="${CQ_ORIGIN:-http://vm.home.7mind.io:${CQ_LOCAL_PORT:-8080}}"

# The file whose presence shows that cq configure has run for the harness, and the arguments its launch needs.
case "$harness" in
  claude) configured="$repo/.mcp.json"; arguments=(--setting-sources project,local) ;;
  codex) configured="$repo/.codex/config.toml"; arguments=() ;;
  pi) configured="$repo/.pi/extensions/cq-host.js"; arguments=(--no-prompt-templates --prompt-template .pi/prompts) ;;
  *) usage ;;
esac

[[ -x "$cq" ]] || { echo "Local package is missing: $cq; run ./update-local.sh" >&2; exit 1; }
[[ -r "$token" ]] || { echo "Operator token is missing: $token; start ./run-local.sh once" >&2; exit 1; }
[[ -e "$configured" ]] || {
  echo "The $harness integration is not installed in this checkout; run:" >&2
  echo "  $cq configure $harness --settings $settings" >&2
  exit 1
}
curl --silent --output /dev/null --max-time 5 "$origin/api/hello" ||
  echo "Warning: no CQ server answers at $origin; start ./run-local.sh in another terminal" >&2

cd "$repo"
exec yolo --profile "$profile" --env "CQ_TOKEN_FILE=$token" "$harness" "${arguments[@]}" "$@"
