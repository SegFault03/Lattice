# Welcome as home: Swing concepts

Concepts for turning the welcome editor tab from a marketing page into a home screen (roadmap R7), made with the `design-primer` skill's `create` command. See [design studies](../README.md) for the study rules.

| Concept | Thesis | Best for | Trade-off |
| --- | --- | --- | --- |
| 02 · Connection board | Home is the health of your connections | Many connections, production safety | Longest page; recents split per connection |
| 03 · Command home | Home is one field with ranked results | Fast return to work, narrow editors | Discoverability rests on the prompt; health shows only on failure |
| 04 · Explorer home (radical) | No welcome tab: home is the tool window plus a Search Everywhere tab | Keeping the editor area for code | Needs a Search Everywhere contributor; no large first-run surface |

`00-` renders the production [`WelcomePanel`](../../src/main/java/com/segfault03/ideadb/ui/WelcomePanel.java) as the baseline. Concept 01 (Launchpad) was dropped: it restated the incumbent Start/Recent pattern.

Every concept renders the same synthetic fixture (7 connections across the six 1.0 engines, 6 recent items) in eight states (returning, first run, filtered, no match, loading, error, long list, narrow), light and dark, at font 1.0 and 2.0, plus motion frames. `concepts.json` holds the viewer text; `viewer.html` is the skill's concept-viewer template.

## Render

From the repository root:

```sh
python design/welcome-home/render.py --output .temp/concepts/welcome-home
```

It writes full-size PNGs to `raw/`, WebP frames to `frames/` and the concept viewer to `index.html`, all in the git-ignored output folder. It needs the IntelliJ test runtime cached by `./gradlew test` and Pillow. It compiles only `WelcomeConcepts.java` against the classes Gradle builds.
