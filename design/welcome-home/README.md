# Welcome as home: Swing concepts

Concepts for turning the welcome editor tab from a marketing page into a home screen (roadmap R7). See [design studies](../README.md) for the rules.

Renders are not tracked (the repository ignores PNGs). Render them locally and open the gallery from the output folder.

| Concept | Layout | Best for | Trade-off |
| --- | --- | --- | --- |
| 01 · Launchpad | Start actions beside Recent items | Smallest change from today | Still a page of links; connection health not visible |
| 02 · Connection board | One row per connection with environment tag, status and last objects | Many connections, production safety | Recents are split per connection; longest page |
| 03 · Command home | One filter field, ranked recents and connections, Enter opens | Fast return to work, narrow editors | Discoverability relies on the field's prompt text |

Every concept shows the same synthetic fixture: four connections (HSQLDB, H2, MySQL, PostgreSQL) and four recent items. Each renders returning and first-run states and a 520 px narrow width, in light and dark. `00-` renders the production `WelcomePanel` as the baseline.

The production screen is [`WelcomePanel`](../../src/main/java/com/segfault03/ideadb/ui/WelcomePanel.java).

## Render

From the repository root:

```sh
python design/welcome-home/render.py --output .temp/concepts/welcome-home
```

Unlike the older studies, this renderer compiles only `WelcomeConcepts.java` and runs it against the classes Gradle builds, so the Java and Kotlin SQL injectors (which need language plugins) are never recompiled.
