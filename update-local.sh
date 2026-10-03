#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 0 ]]; then
  echo "Usage: $0 (optional CQ_LOCAL_STATE)" >&2
  exit 2
fi
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
exec nix develop "$repo" -c python3 "$repo/dev/update-local.py"
