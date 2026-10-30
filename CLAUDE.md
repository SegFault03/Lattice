# Lattice — agent guide

Lattice is an IntelliJ IDEA plugin (database client for MySQL, PostgreSQL, HSQLDB and H2). Plugin ID `com.segfault03.lattice`; source under `src/main/java`, Kotlin under `src/main/kotlin` and `src/uiTest/kotlin`.

Read `AGENTS.md` before changing CI, workflows, `build.gradle.kts` test tasks, or test runners. For CI, follow `docs/CI_TESTING.md#before-changing-ci`.

## Build and test

Requirements: JDK 21 (`JAVA_HOME` or on PATH), Python 3.11+ for helper scripts. Gradle downloads the IntelliJ 2025.1 SDK on first use.

```sh
./gradlew test buildPlugin          # unit tests + plugin ZIP (build/distributions/)
./gradlew buildPlugin -x test       # packaging only
python -m unittest discover -s scripts/tests -v   # tooling regression tests
python scripts/ui-preview.py        # headless Swing screenshots (light, seven README images)
```

Real-IDE UI screenshots need Xvfb, `xdpyinfo` and a window manager (`openbox` works):

```sh
LATTICE_UI_MAVEN_REPOSITORY="$PWD/build/ui-test-maven/repository" ./scripts/linux/capture-intellij-ui.sh
```

Cloud sessions frequently hit Maven Central HTTP 429 limits; see `docs/CLOUD_BUILDS.md`.

Live database suites need owned fixtures; see `docs/CONTRIBUTING.md`. Do not run tests against production data.

## Conventions

- Match the surrounding code's comment density, naming and idiom.
- Keep generated output, SDKs, native binaries and credentials under ignored `build/` or outside the repo. Never commit passwords or local connection state.
- Commit checks run functional tests and plugin build/archive validation only. Regression, integration, UI and compatibility suites run on PRs to `main`; register new suites in `scripts/test-suites.json`.
- Downloadable drivers are only the known Maven Central coordinates in `DriverCatalog`; the base URL can be overridden with `-Dlattice.maven.central` for isolated runs only.
- Pin GitHub Actions to commit SHAs with version comments, and keep permissions least-privilege.
- Do not create a pull request unless asked.
- Never push to a remote without the user's request or explicit approval. Commit locally and ask first.
- Branches: do not create or push a new branch on your own. Ask the user which branch to work on. If the user names no branch, use `main`. Do not push to any branch other than the one the user chose.
- Documentation is current-state. Do not add dated validation narratives or progress logs to `docs/`.

## Key locations

| Path | Contents |
|---|---|
| `src/main/java/com/segfault03/ideadb/` | Plugin implementation: `provider`, `service`, `model`, `ui`, `editor`, `dialog`, `state`, `release` |
| `src/test/java/` | Unit and UI-component tests |
| `src/uiTest/kotlin/` | Real-IDE screenshot tests (`uiScreenshotTest`) |
| `scripts/` | Python helpers; `scripts/linux/` and `scripts/windows/` launchers |
| `scripts/test-suites.json` | Single registry of CI and local suites |
| `docs/` | Guides (index in `docs/README.md`) |

Full documentation index: `docs/README.md`.
