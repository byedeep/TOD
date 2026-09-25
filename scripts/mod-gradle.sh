#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="$project_dir/.tools/jdk8u504-b01"
export GRADLE_USER_HOME="$project_dir/.tools/gradle-home"
if [[ ! -x "$JAVA_HOME/bin/java" || ! -x "$project_dir/.tools/gradle-2.14.1/bin/gradle" ]]; then
  echo 'Run ./scripts/bootstrap.sh first.' >&2
  exit 1
fi
exec "$project_dir/.tools/gradle-2.14.1/bin/gradle" --no-daemon -p "$project_dir/mod" "$@"
