# Documentation

All commands run from the repository root unless a guide says otherwise.

| Guide | Contents |
|---|---|
| [Contributing](CONTRIBUTING.md) | Build requirements, tests and development practices |
| [Scripts](SCRIPTS.md) | Test, UI, deployment and release helpers |
| [Windows scripts](WINDOWS_SCRIPTS.md) | Every local Windows command and its complete options |
| [Windows scripts](WINDOWS_SCRIPTS.md) | Every local Windows command and its complete options |
| [UI previews](UI_PREVIEW.md) | Render production Swing screens and refresh the five README images |
| [Releasing](RELEASING.md) | Versioning, tags, validation and publication |
| [Changelog](CHANGELOG.md) | Versioned release notes |
| [Issue reporting](ISSUE.md) | Bug reports and feature requests |
| [Security reporting](SECURITY.md) | Private vulnerability reporting |
| [Compatibility](compatibility.md) | Supported IDE/database combinations and limits |
| [Third-party notices](THIRD_PARTY_NOTICES.md) | Bundled dependencies, licenses and source distribution |

GitHub discovers contribution and security guidance here. Issue forms, the pull request template and workflows live under `.github/`; agent instructions and Gradle wrapper launchers stay at the repository root.

Portable Python helpers live in `scripts/`; platform entry points live in `scripts/windows/` and `scripts/linux/`, and regression tests live in `scripts/tests/`. Keep SDKs, database binaries, private caches and generated reports outside tracked source; build artifacts are written under ignored `build/`. The five README screenshots are the only tracked generated artifacts.
