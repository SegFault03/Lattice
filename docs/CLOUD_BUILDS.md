# Cloud builds: known failures and fixes

This guide covers building and testing Lattice in Claude cloud sessions (ephemeral Linux containers that reach the internet through an egress proxy). It describes the current state of the environment and the fix for each failure seen. It does not record history.

## Environment facts

- JDK 21 and Python 3.13 are preinstalled. Xvfb, `xdpyinfo` (x11-utils) and openbox are installed; `scripts/linux/cloud-setup.sh` installs them if they are missing.
- Outbound HTTPS goes through `$HTTPS_PROXY`. The proxy's own status is at `curl -sS "$HTTPS_PROXY/__agentproxy/status"`. Never disable TLS verification or unset `HTTPS_PROXY`.
- Maven Central (`repo.maven.apache.org`, `repo1.maven.org`) is shared across all sessions and rate-limits aggressively. Expect HTTP 429 during dependency resolution.
- `plugins.gradle.org` resolves normally.
- `packages.jetbrains.team` returns 404 through the proxy for most paths. `cache-redirector.jetbrains.com/intellij-dependencies` serves some artifacts (for example JCEF).
- The IntelliJ release repository serves the IDE Starter test libraries (`com.jetbrains.intellij.tools:ide-starter`, `ide-performance-testing-commands`, `ide-util-common` and the rest of that group), which are not in Central or its mirror. Use it through the redirector: `https://cache-redirector.jetbrains.com/www.jetbrains.com/intellij-repository/releases`.
- A Central mirror that works: `https://maven-central.storage-download.googleapis.com/maven2`. It does not host IntelliJ `bundledModule` artifacts or every JetBrains tooling jar, and it lacks `maven-metadata.xml` for some groups, such as `com.jetbrains.intellij.platform:test-framework`.
- A second Central mirror: `https://repo.huaweicloud.com/repository/maven`. It carries the metadata the Google mirror lacks, for example `com/jetbrains/intellij/java/java-compiler-ant-tasks`.
- Each new session gets a fresh VM and a fresh clone. `~/.gradle`, the init script and anything installed mid-session do not carry over by default.
- The exception is the environment cache. If the environment's setup script finishes in roughly five minutes, Anthropic snapshots the filesystem and new sessions start from that snapshot, skipping the script. The snapshot rebuilds when the script or allowed hosts change, and expires after about seven days. A script that runs longer than five minutes is not cached.

## Setup script

The setup script lives at `scripts/linux/cloud-setup.sh`. It installs the X11 tools, writes the mirror init script, fetches the UI fixture jars, and warms the Gradle cache.

In the environment's settings (cloud environment menu in the session title bar, then Edit, then the **Setup script** row), enter:

```sh
bash scripts/linux/cloud-setup.sh
```

The script resolves the repository root itself. The repository is cloned before the setup script runs, so the file is present. Setup scripts are visible to everyone who uses the environment, so do not put secrets in them.

To test in a session before relying on the environment cache, run `bash -x scripts/linux/cloud-setup.sh` from anywhere in the repository.

Notes on the script:

- The whole script must finish in roughly five minutes, or the environment is not cached. If the Gradle warm-up (the last step of `scripts/linux/cloud-setup.sh`) alone takes longer, remove it from the script and run it from a SessionStart hook in the background. That hook runs on every session and does not benefit from the cache.
- The script can still hit Central 429s. Two attempts with a two-minute gap is the most that fits the time budget. If it fails, the session still starts, so check the build output before assuming the cache exists.
- Changing the script or the allowed hosts rebuilds the cache. An existing session picks up a change only when its VM is rebuilt, so start a new session to see it.
- The mirror hosts must be reachable. Add `maven-central.storage-download.googleapis.com` and `cache-redirector.jetbrains.com` under **Allowed domains**, with **Allow package managers** ticked. The redirector sends IntelliJ release-repository requests on to `www.jetbrains.com`, which serves them from `d2cico3c979uwg.cloudfront.net`; allow both as well. See [network access](https://code.claude.com/docs/en/cloud-environments#network-access).

Once the environment is cached, a new session starts with the mirror init script, the X11 tools and the fixture jars already on disk.

## Failure: `Could not GET ... Received status code 429`

Gradle does not fall back to another repository on 429, so the first failing repository ends the build.

Fix: put a mirror ahead of `mavenCentral()` with a user-level init script. Keep it outside the repo.

Create `~/.gradle/init.d/mirror.gradle.kts`:

```kotlin
val centralMirror = "https://maven-central.storage-download.googleapis.com/maven2"
val huaweiMirror = "https://repo.huaweicloud.com/repository/maven"
val jetbrainsDeps = "https://cache-redirector.jetbrains.com/intellij-dependencies"
val jetbrainsReleases = "https://cache-redirector.jetbrains.com/www.jetbrains.com/intellij-repository/releases"

fun org.gradle.api.artifacts.dsl.RepositoryHandler.prependMirror() {
    val deps = maven { name = "jetbrainsDepsMirror"; url = uri(jetbrainsDeps) }
    remove(deps)
    add(0, deps)
    val releases = maven { name = "jetbrainsReleasesMirror"; url = uri(jetbrainsReleases) }
    remove(releases)
    add(0, releases)
    val mirror = maven { name = "centralMirror"; url = uri(centralMirror) }
    remove(mirror)
    add(0, mirror)
    val huawei = maven { name = "huaweiMirror"; url = uri(huaweiMirror) }
    remove(huawei)
    add(1, huawei)
    // Dynamic versions query every repository for metadata, so one 429 from Central fails the build: drop Central.
    removeAll { it is org.gradle.api.artifacts.repositories.MavenArtifactRepository && it.url.host in setOf("repo.maven.apache.org", "repo1.maven.org") }
}

beforeSettings {
    pluginManagement.repositories.prependMirror()
    pluginManagement.repositories.gradlePluginPortal() // required: prepending otherwise drops the portal
}

beforeProject {
    buildscript.repositories.prependMirror()
    repositories.prependMirror()
}

// The build script declares Central after beforeProject has run, so drop it again once the project is evaluated.
afterProject {
    buildscript.repositories.removeAll { it is org.gradle.api.artifacts.repositories.MavenArtifactRepository && it.url.host in setOf("repo.maven.apache.org", "repo1.maven.org") }
    repositories.removeAll { it is org.gradle.api.artifacts.repositories.MavenArtifactRepository && it.url.host in setOf("repo.maven.apache.org", "repo1.maven.org") }
}
```

Notes:

- The resulting order is the Central mirror, the IntelliJ release repository, the JetBrains dependencies cache, then the build's own repositories. Each 404 moves Gradle on to the next one; only a 429 stops it, which is why Central must not come first.
- The import type is `org.gradle.api.artifacts.dsl.RepositoryHandler`. The `repositories` package name fails to compile.
- `pluginManagement` must keep `gradlePluginPortal()`, or the IntelliJ Platform plugin cannot be found.
- The file is per-container. Recreate it each session, or add it to the environment setup script.

If the build still returns 429 after the mirror is in place, wait several minutes and retry. Do not loop tightly; each attempt counts against the shared limit.

## Failure: `Could not GET .../maven-metadata.xml` (429) with the mirror already in place

Platform test tasks (`javaPlatformTest`, `kotlinPlatformTest`) resolve dynamic versions such as `test-framework:{strictly [251, 251.23774.435]}`. Gradle asks every repository for the metadata, and a 429 from Central fails the build even when a mirror answers first.

Fix: the init script above removes the Central repositories (`repo.maven.apache.org`, `repo1.maven.org`) after prepending the mirrors, and adds the Huawei mirror behind the Google one. `build.gradle.kts` declares `mavenCentral()` itself, after `beforeProject` has run, so the `afterProject` block removes Central again once the project is evaluated. Without it, `uiScreenshotTest` fails resolving the version range of `org.bouncycastle:bcutil-jdk18on` (required by `ide-starter-squashed`) with a 429 from `repo.maven.apache.org`. The IntelliJ release repository serves the `com.jetbrains.intellij.*` platform artifacts. Check a coordinate by hand with `curl -sL -o /dev/null -w '%{http_code}\n' <mirror>/<group path>/maven-metadata.xml`.

## Failure: `ide-starter`, `ide-performance-testing-commands` or `ide-util-common` not resolved

`uiScreenshotTest` needs the IDE Starter libraries (`com.jetbrains.intellij.tools:*`, version matching the SDK build, for example `251.23774.435`). They are not in Central or the Central mirror, and not in `intellij-dependencies`; Central answers 429 or 404. The IntelliJ release repository has them.

Fix: the `jetbrainsReleases` entry in the init script above. To check a coordinate by hand:

```sh
curl -sL -o /dev/null -w '%{http_code}\n' \
  https://cache-redirector.jetbrains.com/www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/tools/ide-starter/251.23774.435/ide-starter-251.23774.435.pom
```

`--offline` does not help until an online build has fetched these once.

## Failure: `Could not HEAD ... bundledModule/...`

These are IntelliJ Platform test-runtime artifacts resolved through Central. The mirror may not carry them.

Fix: once an online build has populated `~/.gradle`, try `./gradlew --offline <task>`. Offline mode uses cached artifacts without network calls and reports the first artifact that was never fetched; that artifact then needs one of the JetBrains mirrors above.

## Failure: `No cached version of org.jetbrains.intellij.deps.jcef:jcef`

JCEF is a JetBrains-hosted dependency that is not in Central under that coordinate.

Fix: the `jetbrainsDeps` repository in the init script above resolves it. Offline mode cannot, until it has been fetched once.

## Failure: `Expected the HSQLDB Maven fixture in .../ui-test-maven/repository`

`IntellijUiScreenshotTest` checks for prepared JDBC jars before it starts the IDE.

Fix: fetch the fixture jars into `build/ui-test-maven/repository` with the same paths the review script uses (`scripts/linux/review-intellij-ui.sh`): `org/hsqldb/hsqldb/{2.7.2/hsqldb-2.7.2.jar, 2.7.3/hsqldb-2.7.3-jdk8.jar, 2.6.1/hsqldb-2.6.1-jdk8.jar, 2.4.1/hsqldb-2.4.1.jar}` and the three `com/mysql/mysql-connector-j` versions. Use the Central mirror URL. Write to a `.part` file and rename on success.

## Failure: `Timed out waiting for MySQL driver download`

The plugin's `DriverCatalog` downloads from `https://repo.maven.apache.org/maven2/`, which is rate-limited.

Fix: set `LATTICE_UI_MAVEN_CENTRAL=https://maven-central.storage-download.googleapis.com/maven2/` when running `capture-intellij-ui.sh`. The launcher passes it to the IDE as `-Dlattice.maven.central`. The product default is unchanged. The download-progress step in the UI test gates responses from whichever host that property names, so the mirror works with it. Without the override, a 429 from `repo.maven.apache.org` shows as `Download failed` in the dialog and the step times out.

## Failure: `Find JComboBoxUiComponent ... 'Available driver'` or `Repeating click. Click was unsuccessful`

The screenshot test clicks **Driver options ▸** in the new-connection dialog, then waits for the **Available driver** combo. The click never reaches the link, the dialog stays collapsed and the wait times out. `idea.log` shows `SmoothRobot - Repeating click. Click was unsuccessful on ActionLink[... text=Driver options ▸]`.

Cause: under openbox with default window decorations, the IDE's XAWT peer reports a dialog's `getLocationOnScreen()` offset by the frame insets (about 1px left, 20px top). Robot clicks on dialogs land that far above their target and miss small targets such as links.

Fix: `scripts/linux/capture-intellij-ui.sh` starts openbox with a copy of `/etc/xdg/openbox/rc.xml` (written to `build/ui-openbox-rc.xml`) that sets `<decor>no</decor>` for every window. Swing and X11 coordinates then agree to within 1px. Screenshots taken this way have no window-manager title bars. Do not work around this with sleeps or retries in the test.

## Failure: `xkbcomp` warnings or `Could not resolve keysym`

Harmless warnings from the X keymap compiler under Xvfb. Ignore them.

## Missing `xdpyinfo` or window manager

`capture-intellij-ui.sh` requires `xdpyinfo` and a window manager.

Fix:

```sh
apt-get update && apt-get install -y --no-install-recommends x11-utils openbox
```

## Shell and background-process pitfalls

- Variables passed to a background script are not visible inside it. Define paths inside the script, or pass them as arguments.
- `pkill -f <pattern>` matches the calling shell's own command line when the pattern appears in it, and kills the tool call (exit 144). Use a bracketed pattern such as `pkill -f "[c]apture-intellij-ui"`.
- Shell safety checks block `rm` on an unguarded variable. Use `"${VAR:?}/path"` or a literal absolute path.
- Do not poll with `sleep` in the foreground. Start long builds in the background and wait for their completion notice.

## Validation order

1. `./gradlew --no-daemon compileJava compileKotlin` to confirm dependencies resolve.
2. `./gradlew --no-daemon buildPlugin -x test` for packaging-only checks when tests are blocked.
3. `./gradlew --no-daemon test` for the full suite; report any failing test by name.
4. `scripts/linux/capture-intellij-ui.sh` for real-IDE screenshots, after fixtures and the mirror are in place.

Report which of these ran, and what did not.
