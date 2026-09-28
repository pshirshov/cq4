#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 0 ]]; then
  echo "Usage: $0" >&2
  echo "Optional environment: CQ_ORIGIN, CQ_LOCAL_STATE, CQ_LOCAL_PORT, CQ_LOCAL_DB_PORT" >&2
  exit 2
fi
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
release="$repo/.local/release"
[[ -x "$release/bin/cq" ]] || { echo "Verified native package is missing: $release" >&2; exit 1; }
export CQ_ORIGIN="${CQ_ORIGIN:-http://vm.home.7mind.io:${CQ_LOCAL_PORT:-8080}}"
exec nix develop "$repo" -c bash "$repo/docs/examples/launch-local.sh" \
  "$release" "${CQ_LOCAL_STATE:-/srv/nvme/tmp/cq4-playground}"
