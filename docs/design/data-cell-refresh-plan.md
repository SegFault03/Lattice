# Data cell refresh implementation checklist

Scope: findings from the adversarial behavior, design and fine-detail reviews of
`codex-cloud/data-cell-ui-refresh` at `e44125a`. Ordered by impact. Checkpoints are
committed and pushed to the same branch after each completed phase.

## Phase 1 — edit safety and feedback (highest priority)

- [x] Preserve untouched SQL NULL and database-default values; distinguish empty strings.
- [x] Stage changes only through an explicit action; make navigation, X and Escape safe and consistent.
- [x] Keep an in-progress draft available when selection changes; make Commit's relationship to the draft explicit.
- [x] Update draft character counts and raw-editing feedback; show pending status locally.
- [x] Put validation beside the value with recognizable error treatment.
- [x] Add behavioral regression coverage for sentinel preservation and draft transitions.

## Phase 2 — predictable inspector interaction and accessibility

- [x] Add an explicit inspector toggle and retain the closed state during ordinary selection.
- [x] Remember the divider position and show stable primary-key row context.
- [x] Give presentation choices mutually exclusive selection semantics, visible keyboard focus and a recognizable Wrap toggle.
- [x] Show formatting, wrapping and line-number controls when relevant to the value.
- [x] Put secondary table-structure actions in column details/contextual actions.

## Phase 3 — responsive layout and visual finish

- [x] Prevent presentation/count overlap; preserve usable edit actions at compact widths/heights.
- [x] Wrap/select long identifiers, expressions and metadata; stack narrow metadata rows.
- [x] Use a smaller scalar editor, compact gutter and restrained type display.
- [x] Consolidate typography and spacing; align title/subtitle, header controls and action baselines.
- [x] Unify button shape, target heights, icon family, icon/text gaps and painted-edge spacing.
- [x] Add quiet-action hover/focus and distinct pressed feedback; retain readable contrast.
- [x] Add helper/action spacing and consistent section insets, borders and selected cues.
- [x] Reduce competing key-header/toolbar emphasis while retaining identifiable states.

## Phase 4 — verification and handoff

- [x] Run the ordinary Java suite and focused production previews in both themes.
- [x] Exercise scalar, JSON, NULL/default, long metadata, invalid, pinned, editing and disabled states.
- [x] Check compact/wide layouts, larger fonts and scaled layouts; inspect saved renders.
- [x] Update preview/native-review expectations and UI documentation for the final interaction model.
- [x] Build the plugin and report remaining native desktop/platform limitations precisely.

## Progress

- Planning checkpoint: collected the overlapping findings above into one implementation scope.
- Evidence from the initial reviews remains under ignored `build/adversarial-ui-review/`.
  Generated screenshots and local probe sources are not committed.

- Phase 1: focused CellDetailsPanel/CellValuePresentation tests passed (6 tests);
  light/dark production inspector previews passed. Untouched sentinels stay unchanged,
  navigation retains the current draft, X/Escape cancel it, and tab disposal stores
  an unconfirmed draft separately for recovery.

- Phase 2: expanded focused regressions passed (8 tests); both preview themes
  verified closed-state retention and divider restoration. Inspect/Alt+Enter now
  explicitly opens the panel; scalar values hide irrelevant presentation controls,
  column metadata is expandable, and NULL/empty/default have explicit actions.

- Phase 3: ordinary Java suite, plugin build and native UI scenario compilation
  passed. Expanded light/dark previews cover compact JSON, long identifiers/defaults,
  local validation and larger fonts (22 renders). Controls use native geometry,
  consistent targets/icon gaps, selectable wrapping metadata and compact scalar values.

- Verification checkpoint: 200 ordinary tests, plugin ZIP/archive validation,
  13 preview/review tooling checks and the complete 124-render gallery passed.
  The live IntelliJ 2025.1 inspector flow passed, including pinning, editing,
  closed-state retention, explicit reopening and a native dark/light theme round trip.
  Inspection of its screenshots found a secondary-toggle opaque/foreground issue
  after theme switching; that treatment is corrected for the scaled native rerun.

- Scaled verification: the native flow passed at 125% after converting the
  source-picker width/icon assertions to logical pixels. Added a regression
  requiring readable metadata contrast even when the native info color is faint,
  and added equivalent live dark/light assertions. All 201 ordinary tests,
  native scenario compilation, refreshed focused previews and archive validation
  pass; the final native run includes those contrast assertions.

## Final verification

- 201 ordinary Java tests passed with no failures, errors or skips.
- Plugin build and release-archive validation passed; native UI scenarios compile.
- 13 preview/review tooling tests passed.
- The complete light/dark gallery passed (124 renders); five tracked README
  images were refreshed. Focused inspector checks passed (22 renders), including
  760px/960px compact layouts and 150% UI fonts.
- Live IntelliJ IDEA 2025.1 / Linux flows passed at 100% and 125% UI scale,
  including an HSQLDB fixture, pinning, editing, explicit close/reopen, both
  cell/column views and a dark/light/dark theme round trip. The final 125% run
  also requires at least 4.5:1 contrast for metadata labels in both native themes.
- Final saved native renders were inspected; the secondary-toggle theme artifact
  is resolved. Capture output: `build/inspector-native-final/`; focused gallery:
  `build/inspector-final/`; complete gallery: `build/ui-preview-final/`.

Remaining coverage limits: native Windows/macOS, other IDE versions/themes,
screen readers, and physical display scaling beyond the exercised Linux setup.
These checks do not establish full accessibility compliance. The optional bottom
inspector direction was unnecessary for the corrected compact side layout.
