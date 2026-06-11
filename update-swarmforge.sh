#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ ! -f "$SCRIPT_DIR/swarmforge/scripts/update-swarmforge.sh" ]]; then
  echo "swarmforge/scripts/ not found. Run ./swarm first to bootstrap the scripts directory." >&2
  exit 1
fi

exec "$SCRIPT_DIR/swarmforge/scripts/update-swarmforge.sh" "$@"
