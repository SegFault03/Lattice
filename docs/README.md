# Documentation

All commands run from the repository root unless a guide says otherwise.

| Guide | Contents |
|---|---|
| [Database providers](DATABASE_PROVIDERS.md) | Architecture, extension steps, connection ownership and validation |
| [Contributing](CONTRIBUTING.md) | Build requirements, tests and development practices |
| [Cloud builds](CLOUD_BUILDS.md) | Known failures in cloud sessions (rate limits, mirrors, UI fixtures) and their fixes |
| [CI test groups](CI_TESTING.md) | Required reading before CI edits, maintenance conventions, suite ownership and release gates |
| [Scripts](SCRIPTS.md) | Test, UI, deployment and release helpers |
| [Windows scripts](WINDOWS_SCRIPTS.md) | Every local Windows command and its complete options |
| [UI previews](UI_PREVIEW.md) | Render production Swing screens and refresh the seven README images |
| [Releasing](RELEASING.md) | Versioning, tags, validation and publication |
| [Marketplace](MARKETPLACE.md) | Signing, tag-only public releases and local preparation |
| [Changelog](CHANGELOG.md) | Versioned release notes |
| [Issue reporting](ISSUE.md) | Bug reports and feature requests |
| [Security reporting](SECURITY.md) | Private vulnerability reporting |
| [Compatibility](compatibility.md) | Supported IDE/database combinations and limits |
| [Third-party notices](THIRD_PARTY_NOTICES.md) | Bundled dependencies, licenses and source distribution |

GitHub discovers contribution and security guidance here. Issue forms, the pull request template and workflows live under `.github/`; agent instructions and Gradle wrapper launchers stay at the repository root.

Portable Python helpers live in `scripts/`; platform entry points live in `scripts/windows/` and `scripts/linux/`, and regression tests live in `scripts/tests/`. Keep SDKs, database binaries, private caches and generated validation reports under ignored `build/`. The seven product screenshots live under `screenshots/`; the `design/` concept galleries retain their rendered design references.

- [H2 validation](H2_TESTING.md): functional, regression, TLS, driver/runtime compatibility and full real-IDE checks.
