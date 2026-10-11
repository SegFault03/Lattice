# Swing UI previews

Run the preview helper from the repository root on Windows, Linux or macOS:

```sh
python scripts/ui-preview.py
```

It paints the plugin's production Swing screens with fixture data and a headless IntelliJ test application for platform services such as the Action System. It does not open an IDE window or recreate the screens in separate UI code. The default run uses a light look and feel and refreshes these five tracked README images:

- `screenshots/side-panel.png`
- `screenshots/connection-dialog.png`
- `screenshots/table-view.png`
- `screenshots/table-editing.png`
- `screenshots/sql-console.png`

It also writes `build/ui-preview/index.html` and a ZIP of the generated preview set. Open the gallery to inspect the captures before committing changed README images.

Explorer previews use the production native action toolbar. The helper waits for action expansion before painting and fails if a toolbar has no visible actions.

Use JDK 21 and run `./gradlew test` on Linux/macOS or `.\gradlew.bat test` on Windows once to cache the IntelliJ SDK and UI-test runtime dependencies. Missing inputs and checksum-verified FlatLaf are downloaded into the selected `--download-dir` (the OS development cache by default). In this headless Linux environment the preview selects an installed regular sans-serif font to avoid the host's italic logical-font fallback.

On Windows, pass the local Java 21 and IntelliJ SDK paths if they are not already discoverable. The renderer uses Java argument files so SDK and workspace paths with spaces work:

```powershell
python scripts/ui-preview.py --java-home 'C:/path/to/jdk-21' --ide-home 'C:/path/to/IntelliJ IDEA 2025.1'
```

Options:

```sh
python scripts/ui-preview.py --all-previews
python scripts/ui-preview.py --all-previews --output build/ui-after --compare-with build/ui-before
python scripts/ui-preview.py --java-home /path/to/jdk-21 --ide-home /path/to/intellij-sdk
```

`--all-previews` adds light and dark variants to the gallery; `--output` changes generated build output but still refreshes the five README images; `--compare-with` adds a before/after gallery. The helper requires the IntelliJ 2025.1 SDK and JUnit/Hamcrest runtime jars in the selected Gradle cache, or explicit JDK/SDK paths.

These previews show component layout and Swing painting with fixture data. For IDE chrome, live plugin loading, tool-window behavior and database-backed flows, Linux users can use [`capture-intellij-ui.sh`](../scripts/linux/capture-intellij-ui.sh) or [`review-intellij-ui.sh`](../scripts/linux/review-intellij-ui.sh). Windows users can use [`capture-intellij-ui.ps1`](../scripts/windows/capture-intellij-ui.ps1) or [`review-intellij-ui.ps1`](../scripts/windows/review-intellij-ui.ps1) from an interactive desktop session; captures use the primary display, which must be at least 1440×800. Windows capture automatically restores the newly launched `lattice-ui-test` IDE when another application takes the foreground, preserving focus in IDE dialogs and popups. Allow the capture script to use the desktop while it runs; a locked desktop still prevents capture. Their setup and output paths are documented in the [script reference](SCRIPTS.md).

Windows PowerShell examples:

The test applies 100% scaling directly to the IDE VM options as well as the Driver JVM, so Windows display scaling does not offset automated clicks. The fixture Maven project imports offline from its prepared JDBC repository.

For a focused palette review, pass `-StyleOnly -GradleArgs @('-Plattice.ui.lightThemes=true')` to Windows capture. It switches the running IDE through the bundled light themes (including Islands Light when available) and checks the SQL export submenu against the table palette, saving table and console captures for each theme.

```powershell
.\scripts\windows\capture-intellij-ui.ps1 -JavaHome 'C:/path/to/jdk-21' -IdeHome 'C:/path/to/IntelliJ IDEA 2025.1' -InputsOnly
.\scripts\windows\review-intellij-ui.ps1 -JavaHome 'C:/path/to/jdk-21' -IdeHome 'C:/path/to/IntelliJ IDEA 2025.1' -FixtureCache 'C:/path/to/jdbc-fixtures' -InputsOnly -Themes @('ExperimentalDark')
```

Dependency paths and cleanup are described in the [script reference](SCRIPTS.md#binary-paths-downloads-and-cleanup). Windows capture/review accepts `-DownloadDir`, `-BinariesDir`, `-GradleUserHome`, `-BuildIdeHome`, `-Cleanup` and `-NoCleanup`; missing JDK/SDK/JDBC fixtures are provisioned automatically. Standalone previews accept `--download-dir`, `--cleanup`, `--no-cleanup`, `--flatlaf-jar`, `--junit-jar` and `--hamcrest-jar`.
