#!/usr/bin/env bash
# Use a real JDBC jar; the database is isolated and in-memory.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
driver_jar="${1:-$repo_root/lib/hsqldb-2.7.4.jar}"
if [[ ! -f "$driver_jar" ]]; then
    echo "Driver jar does not exist: $driver_jar" >&2
    exit 2
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
    probe_java="$JAVA_HOME/bin/java"
elif [[ -x "$repo_root/../.jdk21/bin/java" ]]; then
    probe_java="$repo_root/../.jdk21/bin/java"
else
    probe_java=java
fi
# Bound a driver that ignores both cancel and query timeout; terminate only this diagnostic JVM.
timeout 45s "$probe_java" --class-path "$driver_jar" src/test/java/com/segfault03/ideadb/service/HsqlCancellationProbe.java
