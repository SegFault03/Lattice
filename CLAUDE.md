# Lattice — agent guide

Lattice is an IntelliJ IDEA plugin (database client for MySQL, PostgreSQL, HSQLDB and H2). Plugin ID `com.segfault03.lattice`; source under `src/main/java`, Kotlin under `src/main/kotlin` and `src/uiTest/kotlin`.

Read `AGENTS.md` before changing CI, workflows, `build.gradle.kts` test tasks, or test runners. For CI, follow `docs/CI_TESTING.md#before-changing-ci`.

## Build and test

Requirements: JDK 21 (`JAVA_HOME` or on PATH), Python 3.11+ for helper scripts. Gradle downloads the IntelliJ 2025.1 SDK on first use.

Code is compiled and tested against two IDE targets: the oldest supported, IntelliJ 2025.1, which also builds the release ZIP, and the newest stable, IntelliJ 2026.3. Select the newest with `-Plattice.ide.home=/path/to/idea-2026.3`; see `docs/CI_TESTING.md#ide-targets`.

```sh
./gradlew test buildPlugin          # unit tests + plugin ZIP (build/distributions/)
./gradlew buildPlugin -x test       # packaging only
python -m unittest discover -s scripts/tests -v   # tooling regression tests
python scripts/ui-preview.py        # headless Swing screenshots (light, nine README images)
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
- Screenshots and preview output may hold private content: keep them in git-ignored `.temp/` or `build/`, never commit or upload them. Never enter credentials or sign in for the owner (ask them to), and never print secrets.
- Working style: use a subagent only for bulky read-only work whose raw output is not needed later; search before reading and cap long output; report only checks that actually ran, with their result.
- For any UI/UX work, read DESIGN.md, then .claude/skills/design-primer/SKILL.md, and follow them.
- Never push to a remote without the user's request or explicit approval. Commit locally and ask first.
- Branches: do not create or push a new branch on your own. Ask the user which branch to work on. If the user names no branch, use `main`. Do not push to any branch other than the one the user chose.
- Permissions and approvals the user gives during a session take precedence over the pre-defined rules above, for the scope the user stated (for example, standing permission to push a named branch at checkpoints). Outside that scope, the rules still apply.
- Documentation is current-state. Do not add dated validation narratives or progress logs to `docs/`.
- While a plan or task is in progress, keep hand-off notes in `HANDOFF.md` at the repository root: update it as the work moves, and thoroughly at each checkpoint (goal, decisions, what is done, what remains, open questions), so a later session can continue without losing context. Commit it with the work. Delete it, and any other progress, plan or note files, before a release; only guides and docs remain. Before tagging, confirm the repo holds only source, guides and lasting docs (no scratch files, task logs or findings); `docs/RELEASING.md` covers signing and the Marketplace upload that `release.yml` performs.
- Add a test only when it guards behavior that could realistically break. Do not add tautological tests, tests that assert what the code trivially does, or tests that check very little. When a check is needed only to verify a change, write it, run it and remove it. Trim redundant or low-value tests whenever you touch a suite.

## Key locations

| Path | Contents |
|---|---|
| `src/main/java/com/segfault03/ideadb/` | Plugin implementation: `provider`, `service`, `model`, `ui`, `editor`, `dialog`, `sql`, `state`, `release` |
| `src/test/java/` | Unit and UI-component tests |
| `src/uiTest/kotlin/` | Real-IDE screenshot tests (`uiScreenshotTest`) |
| `scripts/` | Python helpers; `scripts/linux/` and `scripts/windows/` launchers |
| `scripts/test-suites.json` | Single registry of CI and local suites |
| `docs/` | Guides (index in `docs/README.md`) |

Full documentation index: `docs/README.md`.

## Agent skills

### Issue tracker

Issues are tracked in GitHub Issues for `SegFault03/Lattice`, using the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses the default labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` and `docs/adr/` at the repo root, created lazily. See `docs/agents/domain.md`.
