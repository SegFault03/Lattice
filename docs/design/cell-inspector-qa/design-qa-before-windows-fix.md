Historical report before the Windows font, hover and compact-spacing corrections. The current report is [design-qa.md](../../../design-qa.md).

# Cell inspector design QA

Final result: passed

No actionable P0/P1/P2 differences remain after the final native comparison. Explicit user changes to copy, icon sizing and control placement are intentional deviations from the generated reference.

## Visual target and normalization

- Source: `docs/design/cell-inspector-proposal-light.png` and `docs/design/cell-inspector-proposal-dark.png` (1487 × 1058 generated design images).
- Scope: app-owned inspector, not generated IDE chrome. Source crop `(1002, 145, 485, 913)` normalized from approximately 125% density to 388 × 730 logical pixels. No content is stretched independently.
- State: WARMUP_ORDERS / ORDER_DATE, Row 2 · Column 4, 2026-09-18, pinned, DATE, non-nullable, no default.
- Explicit user changes override the original mock: remove ID from subtitle; move pin/close toward the right edge; round the buttons; enlarge all inspector icons.
- Implementation: production Swing panel, IntelliJ IDEA 2026.2.3 build 262.10968.63, Islands Light/Dark on Linux. Native 100% UI/device density; inspector viewport 388 × 730. No browser/frontend replica is involved.

## Comparison history

1. `docs/design/cell-inspector-qa/iteration-1-light.png`: P2 text/icons too small, value area starts early, edit-table section and footer sit low. Increased type/icon sizes, adjusted header padding, footer height and action dimensions.
2. `docs/design/cell-inspector-qa/iteration-4-light.png`: user identified inset header controls and square button corners. Moved the fixed control rail to a 16-pixel close-button edge inset and replaced platform-dependent inspector surfaces with consistent 5-pixel corner radii. Kept Swing button models, keyboard actions, focus and accessibility.
3. User requested larger icons. Increased action/table/column icons to 24 logical pixels, close icon to 28, and the standard filled-pin artwork to a 32-pixel canvas. The visible glyph remains centered in its 32-pixel control. Selected pin and surface stay neutral gray in both themes.
4. Narrow 320 × 650 review: P2 structure button was too close to the footer. Reserved 24 pixels below that group; value canvas adapts to preserve visible metadata and controls. Wrapped helper copy at narrow widths.
5. `docs/design/cell-inspector-qa/iteration-5-native-light.png` and `iteration-5-native-dark.png`: exact-content native comparisons exposed P2 vertical drift after enlarging icons (3 pixels above the value canvas; about 9 pixels by metadata). Reduced the Value bottom gap by 3 pixels and section heading padding by 7; balanced the section divider by 1 pixel. The final native recapture below confirms aligned section rhythm without pushing the metadata down.

## Fidelity surfaces

- Typography: native IDE label font plus 2 points for body text; 22-point title at default 13-point IDE font; 17-point Value heading; 16-point monospace value. Identifiers and metadata remain selectable and wrap. Native OS font rasterization is expected to differ from the generated Windows reference.
- Spacing: 24-pixel outer content inset; fixed 32-pixel header targets with 10-pixel gap; 164-pixel scalar canvas at the approved size, shrinking only when needed; 36-pixel action targets; aligned metadata columns; structure action attached to metadata; fixed footer.
- Colors: light panel #F7F8FA / dark #25262A; white / #1E1F22 value canvas; explicit readable secondary text #657185 / #ABB3BE; gray selected pin; blue primary action. Theme changes refresh the complete component tree.
- Assets: existing plugin/platform vector artwork. The filled pin is the original Apache-licensed JetBrains toolbar pin, tinted gray, rather than a hand-drawn substitute. No rasterized UI text or screenshot backgrounds.
- Copy: only row and column in subtitle; visible Column metadata; direct applicable value-state actions; compact Edit table below the structure hint; no More or collapse control.

## Verification

- 212 ordinary tests passed after the final spacing correction, with zero failures/skips. Plugin build and archive validation passed again after packaging the icon license; both the pin asset and its Apache license are present in the implementation JAR.
- 28 focused production Swing renders/assertion states passed, including both themes, pin/header pin, double-click edit, draft behavior, narrow layouts, long identifiers, validation and larger fonts.
- Earlier native light/dark runs passed cell/header pin persistence, close/reopen behavior, double-click editing and opposite-theme switching without stale colors.
- Actual Windows execution is unavailable in this Linux workspace. The button surfaces are independent of Windows LAF geometry; narrow layout and larger-font checks supplement native Linux verification.

## Final capture and visual review

- Full real IDE renders: [light](../cell-inspector-native-light.png), [dark](../cell-inspector-native-dark.png). Each is a 1487 × 879 capture of the complete IDE window; black desktop margins were cropped, with no altered UI pixels. The original virtual desktop captures are 1920 × 1080 under `build/ui-native-matched/Islands Dark/`.
- Native panel bounds in the original captures: `(1234, 173, 388, 730)`. This equals the normalized source viewport at 100% UI/device density. This is native Swing, so CSS dimensions do not apply.
- Full panel comparisons were opened together: [light](../cell-inspector-qa/final-light-comparison.png), [dark](../cell-inspector-qa/final-dark-comparison.png).
- Focused comparisons were also opened and inspected: [header](../cell-inspector-qa/final-light-header.png), [actions and metadata](../cell-inspector-qa/final-dark-actions.png). Both themes have corresponding focused files in that directory. Two-times focused crops use nearest-neighbor magnification solely to inspect existing pixels.
- Responsive evidence: [320 × 650 light](../cell-inspector-qa/narrow-light.png), [dark](../cell-inspector-qa/narrow-dark.png). Metadata stays visible, the structure action remains compact, and the footer retains breathing room.
- [Runtime evidence](../cell-inspector-native-evidence.txt). The final native scenario passed with zero failures/errors; it exercised pin persistence for both cell/header, double-click edit, close/reopen/closed-state retention, and Dark → Light → Dark plus the pinned date fixture in both themes. Theme captures show the value canvas, metadata and foregrounds updating together.

## Expected differences and P3 polish

- Native Linux font rasterization and the generated Windows reference differ slightly in glyph shapes/weight. The production implementation inherits the IDE font and user font scaling. Actual Windows font rendering remains unverified.
- The generated reference has a subtle glow/gradient and approximately 2-pixel editor corner rounding. The production editor uses a flat native canvas with a 1-pixel border. Primary/secondary action buttons have the requested 5-pixel rounding.
- Enlarged icons deliberately exceed the original mock size following the user's explicit request. Fixed header targets, margins and aligned metadata preserve the approved hierarchy.

## Implementation checklist

- [x] Remove ID; retain only row and column coordinates.
- [x] Fix header rail, filled gray pinned state, larger icons, rounded buttons.
- [x] Match the value, action, metadata and structure-action spacing through native comparisons.
- [x] Keep Inspect and always-visible metadata; expose applicable value-state actions directly.
- [x] Preserve unstaged drafts, validation, explicit staging, NULL/default/empty-string semantics and double-click editing.
- [x] Pass component, behavioral, native theme and responsive checks; save full renders and evidence.

final result: passed
