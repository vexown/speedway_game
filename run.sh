#!/usr/bin/env bash
# Launch Broadside Speedway (libGDX desktop). Uses the JDK at ~/.local/jdk if java isn't on PATH.
set -e
cd "$(dirname "$0")"
if [ -z "$JAVA_HOME" ]; then
  if ! command -v java >/dev/null 2>&1; then
    JAVA_HOME="$(ls -d "$HOME"/.local/jdk/jdk-21* 2>/dev/null | head -1)"
    [ -n "$JAVA_HOME" ] || { echo "No Java found. Install JDK 17+ (e.g. sudo apt install openjdk-21-jdk)."; exit 1; }
    export JAVA_HOME
  fi
fi
exec ./gradlew -q run "$@"
