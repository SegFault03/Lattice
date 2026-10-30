#!/usr/bin/env bash
# Cloud environment setup script. Keep it under about five minutes so the environment cache can build.
# Secrets must not appear here: environment setup scripts are visible to everyone using the environment.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

# 1. X11 tools for the real-IDE capture (not in the base image).
apt-get update -qq
apt-get install -y -qq --no-install-recommends x11-utils openbox

# 2. Mirror init script, read by every Gradle build in the session.
mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/mirror.gradle.kts" <<'EOF'
val centralMirror = "https://maven-central.storage-download.googleapis.com/maven2"
val jetbrainsDeps = "https://cache-redirector.jetbrains.com/intellij-dependencies"

fun org.gradle.api.artifacts.dsl.RepositoryHandler.prependMirror() {
    val deps = maven { name = "jetbrainsDepsMirror"; url = uri(jetbrainsDeps) }
    remove(deps)
    add(0, deps)
    val mirror = maven { name = "centralMirror"; url = uri(centralMirror) }
    remove(mirror)
    add(0, mirror)
}

beforeSettings {
    pluginManagement.repositories.prependMirror()
    pluginManagement.repositories.gradlePluginPortal()
}

beforeProject {
    buildscript.repositories.prependMirror()
    repositories.prependMirror()
}
EOF

# 3. UI fixture JDBC jars, fetched from the mirror (same paths as scripts/linux/review-intellij-ui.sh).
mirror_base=https://maven-central.storage-download.googleapis.com/maven2
fixture_repo="$repo_root/build/ui-test-maven/repository"
for artifact in \
    com/mysql/mysql-connector-j/9.0.0/mysql-connector-j-9.0.0.jar \
    com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar \
    com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar \
    org/hsqldb/hsqldb/2.7.2/hsqldb-2.7.2.jar \
    org/hsqldb/hsqldb/2.7.3/hsqldb-2.7.3-jdk8.jar \
    org/hsqldb/hsqldb/2.6.1/hsqldb-2.6.1-jdk8.jar \
    org/hsqldb/hsqldb/2.4.1/hsqldb-2.4.1.jar; do
    destination="$fixture_repo/$artifact"
    if [[ ! -s "$destination" ]]; then
        mkdir -p "$(dirname "$destination")"
        curl -sS --fail --retry 2 -L "$mirror_base/$artifact" -o "$destination.part"
        mv "$destination.part" "$destination"
    fi
done

# 4. Warm the Gradle cache. Keep this last: it is the slowest step.
for attempt in 1 2; do
    ./gradlew --no-daemon compileJava compileKotlin compileUiTestKotlin && break
    if [[ $attempt -eq 2 ]]; then
        exit 1
    fi
    sleep 120
done
