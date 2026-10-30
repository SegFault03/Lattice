# CI test groups and release gates

The CI workflow has two tiers. A push to any branch, a PR to any branch, or a
manual CI run executes embedded functional tests and builds/validates the plugin
on Linux, Windows and macOS. The remaining suites run only on `pull_request`
events whose base branch is exactly `main`. PRs to `staging` receive the commit
tier. PR validation checks GitHub's prospective merge commit.
The lightweight suite catalog/workflow contract is checked before that tier on
every event; it does not execute the extended test suites.

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
- Define executable suites in `scripts/test-suites.json`; both CI and local
  runners use that catalog. Keep test commands and UI/IDE/database matrix lists
  out of the workflow. Record a reason for every unsupported platform.
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
python scripts/one-shot-test.py --check-workflow
python -m unittest discover -s scripts/tests -p 'test_ci_workflow.py' -v
python -m unittest discover -s scripts/tests -p 'test_suite_parity.py' -v
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
| `suite-plan` | Validate catalog/workflow routing and emit shared UI, IDE and database matrices | Every event; metadata only |
| `functional-build` | Provider registration, URLs, defaults, driver selection, embedded H2/HSQLDB schema and table operations, CRUD, exports and key metadata; plugin ZIP drivers, licenses and bytecode | Every commit; Linux, Windows, macOS |
| `tooling-tests` | Python runners, fixture ownership, Windows launcher execution, archive/release tooling, workflow event routing and release ancestry | Main PR only; Linux, Windows, macOS |
| `backend-tests` | Remaining model/service/Swing regressions; transactions, validation, cancellation and recovery; H2 file/TCP/TLS and isolated drivers; SQL editor IDE fixtures (`platformTest`, `javaPlatformTest`, `kotlinPlatformTest`: language, completion, hover, injection, project discovery) on Linux; owned MySQL/HSQLDB/PostgreSQL integration on Linux | Main PR only; three hosts, live external-engine fixtures on Linux |
| `ui-tests` | Headless editor recovery assertions; HSQLDB full flow; mixed alter/recovery; create/mutation refresh at 125%; inspector recovery at 125% with both themes; one search, PostgreSQL and H2 flow | Main PR only; independent Linux matrix jobs |
| `ide-compatibility` | Plugin Verifier against IDEA 2025.1, 2025.2 and 2025.3 using the exact ZIP from `functional-build` | Main PR only; one Linux job per IDE |
| `database-compatibility` | MySQL, HSQLDB, PostgreSQL and H2 server/JDBC matrices with real Java 8/21 probes and explicit unsupported-runtime checks | Main PR only; independent Linux job per engine |
| `full-validation` | Requires all preceding groups to succeed; failures, cancellations and unexpected skipped groups fail the gate | Main PR only |

The plugin itself targets Java 21 / IntelliJ build 251. Java 8 probes validate
compatible JDBC drivers and server runtimes, not running the plugin on Java 8.
H2 2.3's expected Java 8 rejection is part of that matrix.
The local plan is platform-specific: macOS has functional, regression and IDE
verification adapters but no JDBC matrix adapters. Planner tests explicitly cover
Linux, Windows and macOS rather than inheriting the test runner's host.

## Shared CI and local suite contract

`scripts/test-suites.json` is the executable suite inventory. Each entry defines
its ID, CI group, local phase, execution adapter, supported platforms, CI hosts,
dependency requirements and selectors. `platforms` describes where the maintained
adapter can run; `ci_hosts` describes where CI currently pays to run it. Thus a
Linux CI suite can still enter the full Windows local plan when its adapter
supports Windows. Every excluded platform has an explicit reason.

`scripts/one-shot-test.py` executes these entries for both environments. CI uses
`--ci-group GROUP --in-place` for grouped checks and `--suite ID --in-place` for
matrix cases. UI themes/flags and IDE/database matrix entries come from the
catalog. The local aggregate discovers every compatible entry automatically;
there is no separate Windows allowlist. `--windows-ui` includes the interactive
portion; omitting it deliberately selects the headless profile.
Parity applies to the checked-out commit. Pull repository updates normally to
receive catalog/test changes before running locally; the runner does not modify
Git branches or fetch another revision during validation.

When adding or changing a suite:

1. Update its catalog entry, selecting an existing adapter or implementing a
   shared adapter when new fixture behavior is needed. New tests within an
   existing Gradle selector need no catalog change.
2. Declare Windows support and dependency requirements, or record the concrete
   unsupported-platform reason. CI host choice does not imply platform support.
3. Run the contract and parity tests above. A new compatible entry is discovered
   by the local runner and its CI group/matrix without another orchestration edit.
4. Update affected documentation and run the relevant real suite. Registering a
   platform cannot establish that a new binary or fixture works there.

Inspect a complete Windows plan or a focused CI plan without downloads:

```powershell
.\scripts\windows\one-shot-test.ps1 --list-suites --platform windows --windows-ui
.\scripts\windows\one-shot-test.ps1 --ci-group backend-tests --list-suites
.\scripts\windows\one-shot-test.ps1 --check-workflow
```

The guard rejects unregistered workflow jobs, manually copied matrices, bypass
test commands and conditional group dispatch. It runs on every event, including
staging PRs. Regression tests add synthetic suites and change selectors to check
that the actual CI/local execution paths pick them up. Actions syntax is checked
separately with actionlint.

Dependencies keep their existing owned-workspace and cache rules. Versioned
MySQL/HSQLDB matrix selections are passed from the catalog to the Java checks;
H2 and PostgreSQL share their existing matrix implementations on both entry paths.
With `--binaries-dir`, the local runner reuses native fixtures and IDEs and retains
JDBC/UI fixture caches. A new kind of binary still needs resolver support once.
Verifier matrix suites require the exact existing ZIP via `--archive`; CI never
rebuilds it in a verifier job.

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
- UI scenarios are eight independent jobs with `fail-fast: false`, unique
  artifacts and failure diagnostics. The headless job uses `--checks-only` for
  constructor-level draft recovery without screenshot generation. Each provider
  has one native database flow. Inspector recovery runs once at 125% and switches
  themes; search runs once in dark mode. Create/alter controls retain native
  reachability checks at 125%. Exact colors, icon dimensions, text baselines,
  rounded-corner pixels and approved-design captures are not release gates.
- JDBC compatibility runs as independent catalog matrix jobs with `fail-fast:
  false` and unique artifacts; `--database-compatibility-only` aggregates them
  locally. Plugin Verifier runs separately against the built ZIP. Compatibility
  cases retain artifact identity, Java-runtime and real SQL
  behavior checks at the boundaries listed in [compatibility](compatibility.md).
  A provider workflow is not rerun alongside another full CRUD contract.

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

For the full CI-supported Windows scope, use the Windows one-shot runner with
`--windows-ui` and the shared asset/cache options. It runs the functional and
regression tasks, tooling suite, packaging/archive validation, native MySQL and
HSQLDB plus H2 backend checks, IDEA/JDBC compatibility checks, and the five
interactive UI scenarios supported on Windows. The isolated H2 fallback check
also runs, matching Windows CI. Linux-only PostgreSQL and H2 UI
fixtures remain in Linux CI. `--binaries-dir` retains compatibility JARs in
`jdbc-cache/`; the UI fixture Maven repository is retained beside it.

```powershell
.\scripts\windows\one-shot-test.ps1 --windows-ui `
  --binaries-dir $assets --download-dir "$assets\downloads" `
  --gradle-user-home "$env:USERPROFILE\.gradle" --cleanup
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
