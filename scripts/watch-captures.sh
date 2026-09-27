#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
capture_dir="${1:-${XDG_DATA_HOME:-$HOME/.local/share}/PrismLauncher/instances/TOD-Bed-Wars/.minecraft/bedwars-companion/captures}"
echo "Watching completed captures in $capture_dir (Ctrl+C to stop)."
exec python3 "$project_dir/scripts/capture_tool.py" review "$capture_dir" --watch
