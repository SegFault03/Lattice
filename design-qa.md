# Cell inspector: Windows text and hover correction

final result: passed

The font, hover, compact-control and spacing corrections pass the component and native checks. No actionable P0/P1/P2 findings remain for this scope. Actual Windows execution remains unverified.

## Findings and changes

- **P1 — inspector typography exceeded the surrounding IDE.** The Windows 11 screenshot at 1920 × 1080 showed larger inspector body/button/value text than the grid and toolbar. `bodyFont()` unconditionally added two points to the IDE label font; the value added another point. Body and value text now inherit `Label.font`, and action labels inherit `Button.font`. The title retains a six-point hierarchy and Value a one-point hierarchy, respecting the IDE's current scale and configured font size.
- **P2 — close hover background clipped at the right edge.** The previous header used a negative right inset to place its controls beyond the header. The paint clip truncated the hover surface. Each panel region now owns its padding: the header has a real 16-logical-pixel right inset, body/footer retain 24, and both complete 32-pixel controls lie within every ancestor. Icon sizes and spacing remain as requested.

- **P2 — oversized actions and inconsistent component spacing.** User review of the native renders found that a 36-logical-pixel action minimum and generous button borders still dominated the panel. Edit, Copy and Edit table now use a 28-pixel minimum, two-pixel vertical padding and 8–10-pixel horizontal padding. The editor uses a 12-pixel inset, its action row has one eight-pixel gap, group spacing follows 8/16/24 increments, metadata rows use four-pixel vertical padding, and the footer uses symmetric 12-pixel padding. Existing icon sizes are retained.

## Source and comparison contract

- Design context: `docs/design/cell-inspector-proposal-light.png` and `docs/design/cell-inspector-proposal-dark.png`.
- Latest truth: the user's Windows 11 screenshots in this conversation, including IDEA 2026.2.3 build 262.10968.63, JBR 25.0.4+1-b508.27, oversized inspector text and clipped close hover. These screenshots contain personal build/license information and are not copied into the repository.
- The 40-pixel hover target and 30-pixel padding indicate approximately 125% UI scale. The native reproduction uses 1920 × 1080 with `ide.ui.scale=1.25`, while device scaling remains 100% for exact screenshot/Driver coordinates. Actual Windows execution is unavailable; native reproduction runs on Linux with the same IDE and JBR build.
- Scoped overrides: use IDE-native body/button/value typography and keep the complete hover background visible. The approved layout, rounded actions, larger icons, gray pin and row/column-only subtitle are retained.
- Component comparison: production Swing before/after at the identical 388 × 730 viewport and 100% UI/device scale, WARMUP_ORDERS / ORDER_DATE / 2026-09-18 / Row 2 · Column 4 / pinned / close hovered. This compares the defect and correction without a viewport or density mismatch.

## Evidence and iteration

1. Captured the existing production panel with close hovered. The right part of the background is visibly cut off.
2. Added regressions for native label/button/value sizes and the full header target's intersection with every ancestor, at 100%, 125% and 150% UI scale, including narrow layouts. The old code produced a 15-point subtitle for a native 13-point label; the existing hover capture reproduces the clip.
3. Replaced the negative inset with real header layout space and removed the font inflation. The revised component comparison was opened with before and after together; typography matches the native UI and the hover surface is complete.
4. Native 125% light/dark captures passed. The IDE and inspector both report 13 pt for native label/value text and button labels; both complete header targets are visible. This Linux run uses 125% IDE geometry zoom with 100% device scale. It is not an exact Windows DPI/font rasterization capture. Unit checks additionally inject scaled native fonts (13, 16.25 and 19.5 pt), confirming the inspector follows them without adding points.

5. The user flagged action size and padding after seeing the first native font/hover correction. Opened the revised component before/after comparisons at the same viewport. Added a compact-height regression relative to a native JButton and a live assertion relative to the existing Inspect toolbar control. The final native comparison confirms a 125 × 36 physical-pixel Edit action next to the 35-pixel Inspect toolbar control at 125% IDE geometry zoom. Both themes pass the compact-height assertion.

## Fidelity surfaces

- **Fonts:** use IDE label and button font families/sizes directly; monospace value at the native label size. User font/zoom preferences continue to apply. Title and Value retain modest hierarchy. OS glyph rasterization can differ.
- **Spacing:** content retains 24-pixel logical margins; header controls have 16-pixel right clearance, a 10-pixel gap and complete 32-pixel hover targets. Actions have a compact 28-pixel logical minimum and consistent two-pixel vertical padding. Value/editor/action/metadata/footer gaps use the balanced spacing described above, and no negative insets remain.
- **Colors:** existing light/dark palette, readable secondary text, blue primary action and neutral gray selected pin are retained.
- **Images/icons:** existing plugin/JetBrains vector artwork; enlarged icons stay at their approved sizes. No raster UI or replacement drawings.
- **Copy/content:** column name, only row/column in subtitle, visible metadata, compact Edit table and direct applicable value actions are retained.

## Validation

- 214 ordinary tests passed; zero failures. The new tests restore global scale/font state after each run.
- 30 focused production Swing render/assertion states passed in light/dark, including close hover, narrow and larger-font cases.
- Plugin build and archive validation passed.
- Native IDEA 2026.2.3 build 262.10968.63 and JBR 25.0.4+1-b508.27 passed at 1920 × 1080 and 125% IDE UI scale, with both Light and Islands Dark checked. Both font-size equality and full header visible-rectangle assertions passed; the close-hover images show all four rounded corners.

## Opened visual evidence

- Full before/after comparisons (same 388 × 730 viewport, 100% UI/device scale): [light](docs/design/cell-inspector-qa/windows-fix-light-comparison.png), [dark](docs/design/cell-inspector-qa/windows-fix-dark-comparison.png). Both source and revised production UI are together in each image.
- Focused before/after header comparisons: [light](docs/design/cell-inspector-qa/windows-fix-light-header.png), [dark](docs/design/cell-inspector-qa/windows-fix-dark-header.png). The after view preserves the intended control position and shows the complete background.
- Full native captures with close hovered: [light](docs/design/cell-inspector-native-125-light-close-hover.png), [dark](docs/design/cell-inspector-native-125-dark-close-hover.png). These are complete 1900 × 1020 IDE window captures cropped only to remove the surrounding desktop; original 1920 × 1080 desktop images are under `build/ui-native-compact/ExperimentalLight/`.
- Native panel bounds: light `(1369, 152, 485, 847)` and dark `(1369, 153, 485, 844)`. Those heights reflect available desktop space and theme chrome; the component comparisons above use the same viewport on both sides. Native geometry UI scale is 1.25; device scale is 1.0; there are no CSS dimensions for Swing.
- Focused native hover inspection: [light](docs/design/cell-inspector-qa/windows-native-125-light-header.png), [dark](docs/design/cell-inspector-qa/windows-native-125-dark-header.png). Two-times nearest-neighbor crops expose the entire hover background without altered UI pixels.
- Font/visibility measurements: [light](docs/design/cell-inspector-qa/windows-native-125-light-metrics.txt), [dark](docs/design/cell-inspector-qa/windows-native-125-dark-metrics.txt). [IDE/JBR and interaction evidence](docs/design/cell-inspector-native-125-evidence.txt).

- Final compact-control native comparisons, opened together at identical viewport/state/density: [light](docs/design/cell-inspector-qa/compact-native-light-comparison.png), [dark](docs/design/cell-inspector-qa/compact-native-dark-comparison.png). The full before/after inspector crops are 485 × 847 in Light and 485 × 844 in Islands Dark, both at UI scale 1.25/device scale 1.0. Source is the first font/hover correction; revised implementation includes the user's subsequent compact-spacing request. Latest original desktop captures are under `build/ui-native-compact/ExperimentalLight/`.
- The focused native header crops include the right-side tooltip area so the tooltip itself is not cut by the evidence crop. The close hover surface is fully visible.
- Earlier 100% design review is archived separately; this review follows the user's Windows feedback and compact-spacing correction.

## Result and limits

The oversized-font rule and clipping layout are corrected, and the requested larger icons are retained. The compact sizing and spacing changes are confirmed in final native light/dark comparisons. The light/dark palette, rounded actions, value editing, pinning and layout remain functional. Windows applies its own device/font rendering; the capture here checks the same IDE/JBR build on Linux. The original mock's font sizes are superseded by the user's request for text consistent with their IDE. Prior design QA is retained as historical evidence in `docs/design/cell-inspector-qa/design-qa-before-windows-fix.md`.

- [x] Inherit native label/value and button typography, including custom sizes.
- [x] Keep the full pin and close hover targets inside all paint bounds.
- [x] Verify normal/narrow geometry at 100%, 125% and 150% scale.
- [x] Verify the same IDE/JBR at 1920 × 1080 with native light/dark hover captures.
- [x] Pass tests, build/archive checks and preserve visual evidence.
- [x] Confirm the final compact action height and complete spacing changes in native light/dark captures.

final result: passed
