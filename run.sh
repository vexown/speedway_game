#!/usr/bin/env bash
# Launch Broadside Speedway (Linux/macOS). Windows: gradlew.bat lwjgl3:run
# Uses JAVA_HOME, else `java` on PATH, else a JDK unpacked under ~/.local/jdk.
set -e
cd "$(dirname "$0")"
if [ -z "$JAVA_HOME" ] && ! command -v java >/dev/null 2>&1; then
  JAVA_HOME="$(ls -d "$HOME"/.local/jdk/jdk-* 2>/dev/null | sort | tail -1)"
  if [ -z "$JAVA_HOME" ]; then
    echo "No Java found. Install a JDK 17-24 (21 recommended) - see README.md 'Requirements'." >&2
    exit 1
  fi
  export JAVA_HOME
fi
exec ./gradlew -q run "$@"
