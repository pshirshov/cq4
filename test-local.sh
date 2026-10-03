#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 0 ]]; then
  echo "Usage: $0" >&2
  exit 2
fi
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
exec nix develop "$repo" -c bash -e -c 'cd "$1"; for mode in fast ui postgres native; do ./dev/check "$mode"; done' bash "$repo"
