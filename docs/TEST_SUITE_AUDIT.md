# Release test suite audit

The release gate should fail for a broken feature, unsafe write, lost draft,
incorrect export, incompatible runtime, or an operation users cannot reach.
Exact appearance and repeating the same contract on adjacent versions do not
justify separate release checks.

## Changes

| Scope | Before | After | Reason |
|---|---:|---:|---|
| Ordinary JUnit cases | 220 | 179 | Remove 41 appearance-only cases; retain distinct model, service and interaction failures |
| Live integration methods | 11 | 10 | Remove the older runner after preserving its unique connection checks |
| Python tooling cases | 134 | 130 | Remove four gallery appearance/variant checks; retain provisioning, fixture ownership, safe publishing and release guards |
| Native/headless UI CI jobs | 12 | 8 | One inspector case at 125% with both themes; one search and H2 theme; no styling-only job or full-gallery gate |
| JDBC positive combinations | 78 | 44 | Test floors, driver generations, current versions and SQL feature boundaries |

The functional/regression tag split is unchanged. Commit checks and packaging
still run on Linux, Windows and macOS; full suites still run only for main PRs.
All three supported 2025 IDE builds retain exact-artifact Plugin Verifier checks.

## Coverage owners

| Release failure | Retained checks |
|---|---|
| Plugin cannot load, packaged drivers missing or wrong bytecode | Three-host functional/package checks, archive checks and three IDE verifiers |
| Wrong server/driver selected, unsafe driver replacement or credential leakage | Provider/driver store tests, connection readiness, configuration/session lifecycle, credential migration/redaction and selected-artifact probes |
| Incorrect row identity, partial commit, lost generated/default values | Provider workflows, typed row/editor tests, failed-batch rollback and mutation cleanup regressions |
| DDL loses data or retries already committed steps | Alter/create model and panel tests, reviewed-SQL preflight, stale-schema rejection, partial-DDL recovery and native create/alter flows |
| Broken cancellation, session reuse or transaction boundaries | Execution-handle unit tests, real database cancellation and bundled H2 TCP/TLS regressions |
| Export changes values or loses schema constraints/sequence state | Provider-specific export/reimport and native CREATE replay contracts |
| Recovered draft disappears or is committed without consent | Typed persistence, cell-editor tests, headless production-constructor recovery including metadata failure, and native inspector close/reopen |
| Controls unavailable, stale metadata or unsafe duplicate actions | Native full provider flows, minimum-size/125% control reachability, busy-state disabling, mutation refresh and search |
| Previously reported header cursor regression | Boundary/interior mouse-event test across UI refreshes |

`PluginIntegrationTest` is removed. Its CRUD, pagination, metadata, export and DDL
checks overlapped `ProviderEndToEndTest` and `FunctionalRegressionTest`. Custom URL
connectivity/version detection now belongs to the live engine flow. The explicit
InnoDB requirement stays in the functional provider contract. Pure history,
read-only-result, draft and credential contracts run in the ordinary suite,
without repeating them inside the live runner.

H2 compatibility no longer runs both complete CRUD contracts plus all regressions
for each case. It retains selected-JAR identity and the provider workflow in every
case. Full H2 regressions run per embedded driver and compatibility mode; bundled
TCP/TLS owns transport-specific failure recovery. PostgreSQL uses one common CRUD
contract plus its engine-specific features; key metadata is included in that
common contract rather than requiring a second provider workflow.

## Deliberately removed coverage

- Pixel colors, rounded corners, exact icon sizes, font sizes, button gaps,
  baselines, cosmetic focus outlines and approved-prototype comparisons.
- Repeated edit/revert/filter scenarios at several window widths. One minimum-
  width visibility check remains; the normal native flow owns the mutations.
- Spinner class/pixel animation checks and simulated IDE balloon overlays.
  Visible operation status and disabled editing controls remain checked.
- A second 100% inspector run, repeated search/H2 theme jobs and the styling-only
  job. Inspector setup no longer replays the full driver-selection/download wizard. Theme changes remain exercised in the inspector case; design galleries
  remain available manually.
- Intermediate JDBC releases and middle-version cross-pairs that repeat the same
  production compatibility branch. The remaining 44 positive combinations and
  H2's expected Java 8 rejection are listed in [compatibility](compatibility.md)
  and [H2 validation](H2_TESTING.md).

Reduced sampling does not prove every intermediate server/driver combination.
A new production version branch or a failure unique to an omitted version should
add a focused regression at that boundary, rather than restore the full product
of versions or copy an entire workflow.

## Validation

Validation results and any platform/runtime limits for this change are recorded
in the PR. Historical dated results in the other guides describe their original
runs and have not been rewritten as evidence for this smaller suite.
