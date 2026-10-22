# Cell inspector: table-matched controls

final result: blocked

Implementation and component checks are complete. Native IDEA light/dark comparison is running; final sign-off awaits its geometry assertions and captured evidence.

## Requested correction

The user flagged the inspector icons and controls as larger than those in the table viewer. This supersedes the earlier enlarged-icon request. The table viewer is the sizing reference: standard 16-logical-pixel icons and 28-logical-pixel minimum controls, with four-pixel vertical/six-pixel horizontal padding and a four-pixel native icon-to-text gap.

## Changes and current verification

- Removed the inspector's 1.5x Edit/Copy/table/column, 1.75x close and 2x selected-pin enlargement. Both pin states use the standard canvas and retain a filled gray selected pin.
- Shared the table toolbar's actual padding, margin, icon gap and preferred-size calculation in `DatabaseUi`. Inspector surfaces retain rounded painting and native button semantics.
- Header pin/close targets now use the same compact dimensions as table icon actions, with four logical pixels between them. Both remain right-aligned inside the header's paint bounds.
- Reduced metadata indentation to align with the now-standard column icon and label. Existing content margins, typography, visible metadata, editing and draft behavior are preserved.
- All 215 ordinary tests pass, including equality against actual table toolbar buttons at 100%, 125% and 150% UI scale, both pin states and ancestor clipping checks. The pinned glyph is compared to its original artwork, since core icons can remain fixed-size placeholders in headless tests; native checks compare actual controls directly.
- All 30 production Swing preview states pass; before/after comparisons opened together in light and dark. Plugin build and archive validation pass.

## Opened evidence

- Same 388 × 730 viewport/state, 100% UI/device scale, ORDER_DATE / Row 2 · Column 4 / pinned / close hovered: [light comparison](docs/design/cell-inspector-qa/standard-actions-light-comparison.png), [dark comparison](docs/design/cell-inspector-qa/standard-actions-dark-comparison.png).
- Previous Windows typography, hover and compact-spacing review is archived in [the earlier report](docs/design/cell-inspector-qa/design-qa-before-standard-actions.md).

## Native iteration

The first live run passed icon-size and control-height comparisons, then caught a one-physical-pixel icon-to-text gap mismatch at 125% IDE zoom. IDEA's Darcula button UI explicitly resets the table action gap to four logical pixels when installing a theme; the previous factory used five. Both table and inspector now use IDEA's four-pixel standard directly. The exact native gap assertion remains in place for both themes.

## Native check pending

The native scenario uses IDEA 2026.2.3 build 262.10968.63 with JBR 25.0.4+1-b508.27 at 1920 × 1080, 125% IDE geometry scale and 100% device scale. It compares Edit/Copy/Edit table/pin/close icon sizes, action heights and icon-to-text gaps directly to the table's Inspect control in Light and Islands Dark. It also checks full header visibility and native font inheritance.

Actual Windows execution is unavailable; the same IDE/JBR build runs here on Linux. Windows device scaling and font rasterization remain platform-specific.

final result: blocked
