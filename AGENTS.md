# Agent guide

Lattice is a Java 21 IntelliJ Platform plugin for MySQL/HSQLDB browsing, SQL, table editing and schema tools; use the committed Gradle wrapper.

## Scripts
- [`test.py`](scripts/test.py) — run builds, Java tests and optional disposable database fixtures.
- [`one-shot-test.py`](scripts/one-shot-test.py) — run isolated Linux/Windows build, live and compatibility checks.
- [`ui-preview.py`](scripts/ui-preview.py) — render production Swing previews and refresh the five README images.
- [`capture-intellij-ui.sh`](scripts/linux/capture-intellij-ui.sh) / [`capture-intellij-ui.ps1`](scripts/windows/capture-intellij-ui.ps1) — capture the plugin in a real IntelliJ test IDE on Linux/X11 or a Windows desktop.
- [`review-intellij-ui.sh`](scripts/linux/review-intellij-ui.sh) / [`review-intellij-ui.ps1`](scripts/windows/review-intellij-ui.ps1) — exercise real-IDE UI flows across themes on Linux/X11 or Windows.
- [`dev-deploy.py`](scripts/dev-deploy.py) — validate and install the plugin into a local IDEA.
- [`verify-plugin.py`](scripts/verify-plugin.py) — run JetBrains Plugin Verifier on a plugin ZIP.
- [`check-release-archive.py`](scripts/check-release-archive.py) — validate a release ZIP and write checksums.
- [`prepare-source-asset.py`](scripts/prepare-source-asset.py) — fetch and verify the matching Connector/J source archive.
- [`release.py`](scripts/release.py) — validate release versions/tags and prepare release notes.
- [`probe-hsqldb-cancellation.py`](scripts/probe-hsqldb-cancellation.py) / [`.ps1`](scripts/windows/probe-hsqldb-cancellation.ps1) / [`.sh`](scripts/linux/probe-hsqldb-cancellation.sh) — isolate HSQLDB JDBC cancellation behavior.
- See [script reference](docs/SCRIPTS.md) for options and requirements.

## Tests and UI
- Test suites: [`src/test/java`](src/test/java), [`src/uiTest/kotlin`](src/uiTest/kotlin) and [`scripts/tests`](scripts/tests); run `./gradlew test` on Linux/macOS or `.\gradlew.bat test` on Windows, plus `python -m unittest discover -s scripts/tests -v`.
- Add or update focused tests when changing or fixing behavior; use `python scripts/ui-preview.py` for Swing UI changes and the platform-specific real-IDE review script (`./scripts/linux/review-intellij-ui.sh` on Linux/X11 or `.\scripts\windows\review-intellij-ui.ps1` on Windows).
- Run database integration checks with `python scripts/test.py --live --mysql --hsqldb`.
- See [contributing](docs/CONTRIBUTING.md), [UI preview guide](docs/UI_PREVIEW.md) and [compatibility](docs/compatibility.md) as needed.
