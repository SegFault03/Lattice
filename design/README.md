# Design studies

A design study compares layout options for a screen using real Swing painting, without changing production code. Use one when a screen has several reasonable layouts and they are easier to judge side by side than to describe.

## Study contents

Each study has its own folder under `design/`:

| Path | Contents |
| --- | --- |
| `<Screen>Concepts.java` | Presentation-only concept layouts, compiled separately from the plugin |
| `render.py` | Compiles the plugin and the concepts, then paints every concept in light and dark |
| `renders/` | One PNG per concept and theme, a production baseline, and overview sheets |
| `index.html` | Gallery for switching between concepts and themes |
| `final/` | Desktop captures of the production dialog running in IntelliJ |
| `.gitignore` | Re-allows the study's PNGs, which the repository otherwise ignores |

## Rules

- **Keep concepts isolated.** They stay out of the plugin source set, and only `render.py` compiles them.
- **Reuse production parts.** Build controls, sections, toolbars, key markers and icons from `DatabaseInputs`, `DatabaseUi`, `DatabaseTable` and `Icons`, so a concept looks like the product.
- **Add no behavior.** Concepts have no action listeners, SQL generators, validation, database connections or execution callbacks. Controls are real Swing widgets showing fixed values.
- **Use one fixture.** Every concept shows the same data, so the comparison is about layout only.
- **Include a baseline.** Render the production screen with that fixture as the `00-` image.
- **Describe trade-offs neutrally.** For each concept, give its layout, what it suits and what it costs.
- **Label renders accurately.** Renders are Swing painting at 2× density with FlatLaf approximating the IDE themes. They are not live IDE captures.

## Add a study

1. Copy an existing study folder to `design/<screen>/`.
2. Rename the concepts class and paint each layout, numbering concepts from `01`.
3. In `render.py`, update the build directory (`build/<screen>-pocs/`), the concepts source file and the main class.
4. Update the concept list and text in `index.html`, and the study's `README.md`.
5. Render the study and open `index.html` to compare the concepts.

## Render

From the repository root:

```sh
python design/<screen>/render.py --java-home /path/to/jdk-21 --gradle-user-home /path/to/gradle-cache
```

The renderer also accepts `--ide-home`, `--flatlaf-jar`, `--output` and the shared dependency options from `scripts/ui-preview.py`. It needs the IntelliJ test runtime in the Gradle cache; see [UI previews](../docs/UI_PREVIEW.md). Build scratch goes to ignored `build/<screen>-pocs/` and PNGs go to `renders/`. The tracked README screenshots are not touched.

## Native captures

Once a screen exists in production, capture it with a focused real-IDE review from [UI previews](../docs/UI_PREVIEW.md). For each theme, copy the dialog crop and the full-IDE capture to `final/` as `<theme>-dialog.png` and `<theme>-intellij.png`.
