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

- [ ] Run the ordinary Java suite and focused production previews in both themes.
- [ ] Exercise scalar, JSON, NULL/default, long metadata, invalid, pinned, editing and disabled states.
- [ ] Check compact/wide layouts, larger fonts and scaled layouts; inspect saved renders.
- [ ] Update preview/native-review expectations and UI documentation for the final interaction model.
- [ ] Build the plugin and report remaining native desktop/platform limitations precisely.

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
