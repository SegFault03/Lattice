# CI test groups and release gates

The CI workflow has two tiers. A push to any branch, a PR to any branch, or a
manual CI run executes embedded functional tests and builds/validates the plugin
on Linux, Windows and macOS. The remaining suites run only on `pull_request`
events whose base branch is exactly `main`. PRs to `staging` receive the commit
tier. PR validation checks GitHub's prospective merge commit.

## Before changing CI

Read this guide and [Contributing](CONTRIBUTING.md) before editing a workflow,
CI test task, runner or workflow-policy test. Read the relevant
[script reference](SCRIPTS.md) sections before choosing or changing commands.
This guide defines CI policy and suite ownership; the workflow-policy tests in
`scripts/tests/test_ci_workflow.py` check that the YAML implements it.

Read the additional guides that apply to the change:

| Change | Required reading |
|---|---|
| Release triggers, tag guards, versioning or publication | [Releasing](RELEASING.md) |
| Signing, credentials, release assets or Marketplace upload | [Marketplace](MARKETPLACE.md) and [Releasing](RELEASING.md) |
| Swing previews, real IDE scenarios or UI artifacts | [UI review](UI_PREVIEW.md) |
| Java, IDE, JDBC or server matrices | [Compatibility](compatibility.md) |
| H2 fixtures, drivers or scenarios | [H2 validation](H2_TESTING.md) |
| Windows commands or portability | [Windows scripts](WINDOWS_SCRIPTS.md) |

Use these conventions for every CI change:

- Preserve the event policy above: commit checks are functional/build only;
  full validation belongs to main-targeted PRs; publication belongs to new
  version tags reachable from main. Change policy only when explicitly requested,
  and update this guide and its policy tests together.
- Put a check in its existing suite group and confirm it does not repeat another
  tier or a nested runner's work. Keep functional/regression selection disjoint.
- Use documented portable runners and the committed Gradle wrapper. Reuse the
  build SDK/cache and verify the exact built artifact instead of rebuilding it.
- Pin Actions to commit SHAs with version comments. Keep permissions scoped to
  the job that needs them and credentials scoped to the consuming step.
- Use owned, disposable fixtures and preserve their cleanup. Retain diagnostics
  on failure with unique artifact names. Independent matrix cases must not hide
  one another's evidence; required failures or unexpected skips must fail the
  main PR gate.
- Keep workflow comments as short pointers or explanations of a specific guard.
  Keep the complete policy here so comments and guides do not diverge.

For every workflow change, run the workflow-policy and documentation checks:

```bash
python -m unittest discover -s scripts/tests -p 'test_ci_workflow.py' -v
python -m unittest discover -s scripts/tests -p 'test_layout.py' -v
actionlint .github/workflows/ci.yml .github/workflows/release.yml
```

When commands, platforms or fixtures change, also run the tooling suite and the
affected backend/UI/compatibility checks from the inventory below. For comments
or documentation alone, the focused checks and workflow lint are sufficient.
Update the affected guides in the same commit and describe validation and limits
in the PR. Repository agent instructions are in [AGENTS.md](../AGENTS.md).

## Suite inventory

| Group | Coverage | Hosts / scope |
|---|---|---|
| `functional-build` | Provider registration, URLs, defaults, driver selection, embedded H2/HSQLDB schema and table operations, CRUD, exports and key metadata; plugin ZIP drivers, licenses and bytecode | Every commit; Linux, Windows, macOS |
| `tooling-tests` | Python runners, fixture ownership, Windows launcher execution, archive/release tooling, workflow event routing and release ancestry | Main PR only; Linux, Windows, macOS |
| `backend-tests` | Remaining model/service/Swing regressions; transactions, validation, cancellation and recovery; H2 file/TCP/TLS and isolated drivers; owned MySQL/HSQLDB/PostgreSQL integration on Linux | Main PR only; three hosts, live external-engine fixtures on Linux |
| `ui-tests` | Production Swing previews, HSQLDB dark full flow, focused light styling, dark/light explorer search, PostgreSQL dark full flow, H2 dark/light full flows | Main PR only; independent Linux matrix jobs |
| `ide-compatibility` | Plugin Verifier against IDEA 2025.1, 2025.2 and 2025.3 using the exact ZIP from `functional-build` | Main PR only; one Linux job per IDE |
| `database-compatibility` | MySQL, HSQLDB, PostgreSQL and H2 server/JDBC matrices with real Java 8/21 probes and explicit unsupported-runtime checks | Main PR only; Linux |
| `full-validation` | Requires all preceding groups to succeed; failures, cancellations and unexpected skipped groups fail the gate | Main PR only |

The plugin itself targets Java 21 / IntelliJ build 251. Java 8 probes validate
compatible JDBC drivers and server runtimes, not running the plugin on Java 8.
H2 2.3's expected Java 8 rejection is part of that matrix.

## Avoiding redundant execution

- `functionalTest` selects the `functional` tag and excludes `integration`.
  Five provider test classes define this small commit tier.
- `regressionTest` runs the remaining ordinary tests with the IntelliJ test
  runtime. It excludes both `functional` and `integration`, so it does not repeat
  the commit tier. `test` still runs all ordinary tests for local use.
- `integrationTest` excludes `h2integration`; `h2IntegrationTest` owns H2 file,
  TCP, TLS and authentication checks. The live runner requests both tasks once.
  Linux validates all packaged drivers with its live fixtures; Windows/macOS use
  the isolated H2-only driver check.
- Backend CI does not call the standalone H2 all-in-one runner, rebuild the
  plugin, or repeat the ordinary `test` task.
- UI scenarios are separate matrix jobs with `fail-fast: false`, unique
  artifacts and failure diagnostics. A shared JDBC service contract cannot
  replace real IDE interaction/painting checks. Conversely, only focused styling
  and search scenarios repeat the provider-independent UI checks in light mode.
  H2 retains its complete dark/light scenarios.
- The JDBC compatibility runner uses `--database-compatibility-only`; Plugin
  Verifier runs separately against the built ZIP. Compatibility cases reuse the
  production service contracts to detect driver/server differences.

Representative local commands:

```bash
# Commit tier, including ZIP packaging (also portable via windows/test.ps1).
python scripts/test.py --functional-only --build

# Remaining backend regression tier.
./gradlew regressionTest h2IntegrationTest h2FallbackDriverTest
python scripts/test.py --integration-only --live --mysql --hsqldb --postgres

# Full ordinary local suite, including the functional subset.
./gradlew test

# Tooling/workflow checks and compatibility.
python -m unittest discover -s scripts/tests -v
python scripts/one-shot-test.py --database-compatibility-only
```

The two backend examples can be used independently; CI chooses the isolated H2
fallback on Windows/macOS and the all-provider fallback on Linux. When combining
local commands, omit a repeated `h2IntegrationTest` task if the live runner has
already executed it. UI commands and evidence are described in
[UI review](UI_PREVIEW.md) and [H2 validation](H2_TESTING.md).

## Release scope

The release workflow accepts newly created `v*` tags only when their commits are
reachable from `origin/main`. Branch pushes, PRs, manual dispatches, tag updates
and tag deletions cannot publish. Tags outside main fail before downstream work.

Tag jobs run functional/package checks, derive the version and release notes,
prepare corresponding source, sign and verify the ZIP, validate checksums and
publish the same signed artifact to GitHub and Marketplace. Regression,
integration, UI and compatibility validation belongs to the main PR and is not
repeated on tags. The maintainer must merge the validated release commit to main
before tagging it. This workflow does not change repository branch-protection
settings.

## CI failures addressed in this change

The initial Windows failure was a stale assertion for nine launchers after the
H2 commands raised the inventory to eleven. That inventory now runs on every
host, with actual launcher execution on Windows. The subsequent failure was the
TLS cancellation test requesting cancellation after client attachment but before
server execution. Cancellation retries now tolerate a slower remote startup; a
unit regression simulates this delay. The H2 transport regression separately
checks pre-submission cancellation and observes server execution before testing
active cancellation and recovery, rather than relying on a CPU spin or three
identical races.
