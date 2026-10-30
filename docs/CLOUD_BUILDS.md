# Cloud builds: known failures and fixes

This guide covers building and testing Lattice in Claude cloud sessions (ephemeral Linux containers that reach the internet through an egress proxy). It describes the current state of the environment and the fix for each failure seen. It does not record history.

## Environment facts

- JDK 21 and Python 3.13 are preinstalled. Xvfb is installed; `xdpyinfo` and a window manager are not.
- Outbound HTTPS goes through `$HTTPS_PROXY`. The proxy's own status is at `curl -sS "$HTTPS_PROXY/__agentproxy/status"`. Never disable TLS verification or unset `HTTPS_PROXY`.
- Maven Central (`repo.maven.apache.org`, `repo1.maven.org`) is shared across all sessions and rate-limits aggressively. Expect HTTP 429 during dependency resolution.
- `plugins.gradle.org` resolves normally.
- JetBrains' own repositories (`packages.jetbrains.team`, `www.jetbrains.com/intellij-repository`) return 404 through the proxy for most paths. `cache-redirector.jetbrains.com/intellij-dependencies` serves some artifacts (for example JCEF).
- A Central mirror that works: `https://maven-central.storage-download.googleapis.com/maven2`. It does not host IntelliJ `bundledModule` artifacts or every JetBrains tooling jar.

## Failure: `Could not GET ... Received status code 429`

Gradle does not fall back to another repository on 429, so the first failing repository ends the build.

Fix: put a mirror ahead of `mavenCentral()` with a user-level init script. Keep it outside the repo.

Create `~/.gradle/init.d/mirror.gradle.kts`:

```kotlin
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
    pluginManagement.repositories.gradlePluginPortal() // required: prepending otherwise drops the portal
}

beforeProject {
    buildscript.repositories.prependMirror()
    repositories.prependMirror()
}
```

Notes:

- The import type is `org.gradle.api.artifacts.dsl.RepositoryHandler`. The `repositories` package name fails to compile.
- `pluginManagement` must keep `gradlePluginPortal()`, or the IntelliJ Platform plugin cannot be found.
- The file is per-container. Recreate it each session, or add it to the environment setup script.

If the build still returns 429 after the mirror is in place, wait several minutes and retry. Do not loop tightly; each attempt counts against the shared limit.

## Failure: `Could not HEAD ... bundledModule/...` or `ide-starter` 429

These are IntelliJ Platform test-runtime artifacts resolved through Central. The mirror may not carry them.

Fix: once an online build has populated `~/.gradle`, try `./gradlew --offline <task>`. Offline mode uses cached artifacts without network calls and reports the first artifact that was never fetched; that artifact then needs the JetBrains mirror above. Offline mode was verified for `compileUiTestKotlin` only up to the JCEF artifact, so treat it as a fallback, not a guarantee.

## Failure: `No cached version of org.jetbrains.intellij.deps.jcef:jcef`

JCEF is a JetBrains-hosted dependency that is not in Central under that coordinate.

Fix: the `jetbrainsDeps` repository in the init script above resolves it. Offline mode cannot, until it has been fetched once.

## Failure: `Expected the HSQLDB Maven fixture in .../ui-test-maven/repository`

`IntellijUiScreenshotTest` checks for prepared JDBC jars before it starts the IDE.

Fix: fetch the fixture jars into `build/ui-test-maven/repository` with the same paths the review script uses (`scripts/linux/review-intellij-ui.sh`): `org/hsqldb/hsqldb/{2.7.2/hsqldb-2.7.2.jar, 2.7.3/hsqldb-2.7.3-jdk8.jar, 2.6.1/hsqldb-2.6.1-jdk8.jar, 2.4.1/hsqldb-2.4.1.jar}` and the three `com/mysql/mysql-connector-j` versions. Use the Central mirror URL. Write to a `.part` file and rename on success.

## Failure: `Timed out waiting for MySQL driver download`

The plugin's `DriverCatalog` downloads from `https://repo.maven.apache.org/maven2/`, which is rate-limited.

Fix: set `LATTICE_UI_MAVEN_CENTRAL=https://maven-central.storage-download.googleapis.com/maven2/` (trailing slash required) when running `capture-intellij-ui.sh`. The launcher passes it to the IDE as `-Dlattice.maven.central`. The product default is unchanged.

## Failure: `Find JComboBoxUiComponent ... 'Available driver'`

The new-connection dialog collapses **Driver options**. The screenshot test expects that section to be open.

Fix: this is a UI-automation step in `src/uiTest`, not a build problem. Expand the section in the test flow before locating the combo.

## Failure: `xkbcomp` warnings or `Could not resolve keysym`

Harmless warnings from the X keymap compiler under Xvfb. Ignore them.

## Missing `xdpyinfo` or window manager

`capture-intellij-ui.sh` requires `xdpyinfo` and a window manager.

Fix:

```sh
apt-get update && apt-get install -y --no-install-recommends x11-utils openbox
```

## Flaky `DatabaseUiTest.cachedPopupAndSubmenuRefreshTheirPaletteOnEveryOpen`

The test fails in a full `./gradlew test` run with `Not disposed javax.swing.Timer ... PopupTimer`. It passes alone (`--tests com.segfault03.ideadb.ui.DatabaseUiTest`). The leaked timer comes from another test in the same JVM.

Fix: do not skip the test. Rerun it alone to confirm it passes. Report the full-run failure to the user, and investigate the timer leak before merging test-order changes.

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
