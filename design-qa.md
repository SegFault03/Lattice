# Cell inspector: table-matched controls

final result: passed

The inspector now uses the table viewer's standard icon canvases, action heights, padding and icon-to-text spacing. The final native Light/Islands Dark comparisons and exact geometry assertions pass. No actionable P0/P1/P2 findings remain for this sizing correction.

## Reference and scope

The user's latest request makes the existing table viewer the sizing reference and supersedes the earlier enlarged-icon request. The approved inspector structure, rounded actions, gray selected pin, visible column metadata and row/column-only subtitle remain the layout reference. Native IDE typography continues to apply.

The comparison state is WARMUP_ORDERS / ORDER_DATE / 2026-09-18 / Row 2 · Column 4 / pinned. Before and after use identical viewport, content, theme, UI zoom and device scale. Hover checks use a separate capture of that same state with close hovered.

## Findings and changes

- **P2 — inspector icons exceeded table icons.** Removed 1.5x Edit/Copy/table/column, 1.75x close and 2x selected-pin enlargement. All use their original 16-logical-pixel canvases, including the existing JetBrains filled pin artwork. Both pin states retain a stable control footprint.
- **P2 — independent button padding made the inspector controls inconsistent.** Inspector and table actions now share `DatabaseUi` padding, margin, icon gap and preferred-size calculation: four logical pixels vertically, six horizontally, zero extra Swing margin, and a 28-logical-pixel minimum. Rounded painting, keyboard semantics and native fonts remain intact.
- **P2 — header targets exceeded table action sizing.** Pin and close use the same compact calculation as table icon actions, with four logical pixels between them. Both remain right-aligned within real header layout space; their full hover backgrounds fit every ancestor.
- **P2 — metadata alignment depended on enlarged icons.** Reduced metadata indentation from 32 to 24 logical pixels so its text aligns with the standard column icon and section label. Existing content margins and balanced section spacing remain.
- **Native iteration — theme installation changed the text gap.** The first native run passed Edit icon/height checks, then caught a one-physical-pixel gap mismatch at 125% IDE zoom. IDEA's Darcula button UI explicitly resets this gap to four logical pixels when installing a theme; the previous action factory used five. Table and inspector now use the native four-pixel standard directly. The exact gap assertion remains; the final run passes in both themes.

## Final native measurements

IDEA 2026.2.3 build 262.10968.63 with JBR 25.0.4+1-b508.27, 1920 × 1080 desktop, UI geometry scale 1.25 and device scale 1.0. These are physical screenshot pixels, measured from the running components in both themes:

| Control | Height | Icon canvas | Icon-to-text gap |
| --- | --- | --- | --- |
| Table Inspect reference | 35 | 20 × 20 | 5 |
| Edit value | 35 | 20 × 20 | 5 |
| Copy | 35 | 20 × 20 | 5 |
| Edit table | 35 | 20 × 20 | 5 |
| Selected pin | 35 | 20 × 20 | 5 |
| Close | 35 | 20 × 20 | 5 |

The pin/close targets measure 36 × 35 because each six-logical-pixel horizontal inset rounds separately at 125% zoom; the table icon actions use the same calculation. IDE and inspector label/value/button text all report 13 pt in this Linux capture. Tests also inject scaled native fonts and confirm inheritance without added points.

## Opened visual evidence

- Native before/after panels, identical viewport and state: [Light](docs/design/cell-inspector-qa/standard-native-light-comparison.png), [Islands Dark](docs/design/cell-inspector-qa/standard-native-dark-comparison.png). Both source and revised production UI were opened together; the oversized glyphs and action widths are corrected.
- Full native IDE captures showing inspector controls beside the table toolbar: [Light](docs/design/cell-inspector-native-125-light.png), [Islands Dark](docs/design/cell-inspector-native-125-dark.png). Each is the complete 1900 × 1020 IDE window, cropped only to remove surrounding desktop pixels.
- Full close-hover captures: [Light](docs/design/cell-inspector-native-125-light-close-hover.png), [Islands Dark](docs/design/cell-inspector-native-125-dark-close-hover.png).
- Focused hover evidence, enlarged two times with nearest-neighbor pixels: [Light](docs/design/cell-inspector-qa/standard-native-light-header.png), [Islands Dark](docs/design/cell-inspector-qa/standard-native-dark-header.png). The right-side tooltip area is included; the full close surface is visible.
- Native inspector bounds are `(1369,152,485,847)` in Light and `(1369,153,485,844)` in Islands Dark, identical before and after. Original desktop captures are under `build/ui-native-standard-actions-final/ExperimentalLight/`.
- Exact measurements: [Light](docs/design/cell-inspector-qa/standard-native-light-metrics.txt), [Islands Dark](docs/design/cell-inspector-qa/standard-native-dark-metrics.txt). [IDE/JBR and interaction evidence](docs/design/cell-inspector-native-125-evidence.txt).
- Component comparisons, same 388 × 730 viewport and 100% UI/device scale: [Light](docs/design/cell-inspector-qa/standard-actions-light-comparison.png), [Dark](docs/design/cell-inspector-qa/standard-actions-dark-comparison.png). These were opened before the native comparison.
- The preceding Windows typography, hover and compact-spacing review is preserved in [the historical report](docs/design/cell-inspector-qa/design-qa-before-standard-actions.md).

## Validation and limits

- 215 ordinary tests passed with zero failures, errors or skips. Geometry tests compare inspector actions against actual table button factories at 100%, 125% and 150% scale and restore global font/scale settings. The selected pin is compared to its original artwork because core icons can remain fixed-size placeholders in headless tests; the native checks compare real controls directly.
- 30 production Swing render/assertion states passed across light/dark, normal/narrow widths, larger fonts, editing, JSON, long metadata and validation.
- Final native scenario passed in both themes: exact action-height/icon-size/gap equality, native font inheritance, full header visibility, cell/header pinning, double-click editing, close/reopen behavior and theme switching.
- Plugin build, exact archive validation, documentation links and whitespace checks passed.

Actual Windows execution is unavailable. The native captures use Linux with the same IDE and JBR build shown in the user's Windows report; OS font rasterization and device scaling remain platform-specific. Sizing follows the same IDE fonts, icons and shared geometry so those settings apply consistently.

final result: passed
