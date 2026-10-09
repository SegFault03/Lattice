# Swing UI previews

Run the preview helper from the repository root:

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

Use JDK 21 and run `./gradlew test` once to cache the IntelliJ SDK and UI-test runtime dependencies. On the first preview run, the script downloads checksum-verified FlatLaf into ignored `build/ui-preview/deps/`. In this headless Linux environment the preview selects an installed regular sans-serif font to avoid the host's italic logical-font fallback.

Options:

```sh
python scripts/ui-preview.py --all-previews
python scripts/ui-preview.py --all-previews --output build/ui-after --compare-with build/ui-before
python scripts/ui-preview.py --java-home /path/to/jdk-21 --ide-home /path/to/intellij-sdk
```

`--all-previews` adds light and dark variants to the gallery; `--output` changes generated build output but still refreshes the five README images; `--compare-with` adds a before/after gallery. The helper requires the IntelliJ 2025.1 SDK and JUnit/Hamcrest runtime jars in the selected Gradle cache, or explicit JDK/SDK paths.

These previews show component layout and Swing painting with fixture data. For IDE chrome, live plugin loading, tool-window behavior and database-backed flows, use [`capture-intellij-ui.sh`](../scripts/capture-intellij-ui.sh) or [`review-intellij-ui.sh`](../scripts/review-intellij-ui.sh); their setup and output paths are documented in the [script reference](SCRIPTS.md).
