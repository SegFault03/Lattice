# Data cell refresh implementation checklist

Scope: findings from the adversarial behavior, design and fine-detail reviews of
`codex-cloud/data-cell-ui-refresh` at `e44125a`. Ordered by impact. Checkpoints are
committed and pushed to the same branch after each completed phase.

## Phase 1 — edit safety and feedback (highest priority)

- [ ] Preserve untouched SQL NULL and database-default values; distinguish empty strings.
- [ ] Stage changes only through an explicit action; make navigation, X and Escape safe and consistent.
- [ ] Keep an in-progress draft available when selection changes; make Commit's relationship to the draft explicit.
- [ ] Update draft character counts and raw-editing feedback; show pending status locally.
- [ ] Put validation beside the value with recognizable error treatment.
- [ ] Add behavioral regression coverage for sentinel preservation and draft transitions.

## Phase 2 — predictable inspector interaction and accessibility

- [ ] Add an explicit inspector toggle and retain the closed state during ordinary selection.
- [ ] Remember the divider position and show stable primary-key row context.
- [ ] Give presentation choices mutually exclusive selection semantics, visible keyboard focus and a recognizable Wrap toggle.
- [ ] Show formatting, wrapping and line-number controls when relevant to the value.
- [ ] Put secondary table-structure actions in column details/contextual actions.

## Phase 3 — responsive layout and visual finish

- [ ] Prevent presentation/count overlap; preserve usable edit actions at compact widths/heights.
- [ ] Wrap/select long identifiers, expressions and metadata; stack narrow metadata rows.
- [ ] Use a smaller scalar editor, compact gutter and restrained type display.
- [ ] Consolidate typography and spacing; align title/subtitle, header controls and action baselines.
- [ ] Unify button shape, target heights, icon family, icon/text gaps and painted-edge spacing.
- [ ] Add quiet-action hover/focus and distinct pressed feedback; retain readable contrast.
- [ ] Add helper/action spacing and consistent section insets, borders and selected cues.
- [ ] Reduce competing key-header/toolbar emphasis while retaining identifiable states.

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
