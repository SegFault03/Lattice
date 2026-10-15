# Repository instructions

## Before changing CI or test automation

Before editing `.github/workflows/`, CI test tasks in `build.gradle.kts`, test
runners or workflow-policy tests, read:

1. `docs/CONTRIBUTING.md` for repository conventions.
2. `docs/CI_TESTING.md`, especially **Before changing CI**, for the authoritative
   CI policy, suite inventory and validation requirements.
3. The relevant sections of `docs/SCRIPTS.md` for runner interfaces, platform
   support, fixture ownership and cleanup.

Follow the additional reading in `docs/CI_TESTING.md` for release/signing, UI,
compatibility, H2 or Windows changes. Do not infer workflow policy from an old
run, an isolated YAML comment or a helper's default command.

## CI conventions

- Commit checks run only functional tests and plugin build/archive validation.
  Tooling, regression/integration, UI and Java/JDBC/IDE compatibility suites run
  only on PRs whose base is `main`; staging PRs use the commit tier.
- Publishing requires a newly created version tag reachable from `main`. Keep
  the ancestry guard ahead of signing and publication.
- Preserve grouped suites, disjoint functional/regression selection, owned
  fixtures, portable launchers, exact-artifact verification and failure reports.
  Avoid nested runners that repeat tests or packaging.
- Pin Actions to commit SHAs with version comments; retain least-privilege
  permissions and keep credentials out of source, arguments and logs.
- Keep workflow conditions, policy tests and affected documentation aligned in
  the same change. Follow the validation procedure in `docs/CI_TESTING.md` before
  committing; report what ran and any remaining limits.

Explicit task instructions take precedence over these repository conventions.
