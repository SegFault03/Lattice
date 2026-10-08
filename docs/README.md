# Documentation

All commands in these guides run from the repository root unless stated otherwise.

| Guide | Contents |
|---|---|
| [Contributing](CONTRIBUTING.md) | Build requirements, tests and development practices |
| [Scripts](SCRIPTS.md) | Portable test, deployment and release helpers |
| [UI previews](UI_PREVIEW.md) | Render the side panel, connection dialog, table editor/viewer and SQL console outside the IDE; refresh README screenshots |
| [IDE UI review](IDE_UI_REVIEW.md) | Repeat real IntelliJ captures across all bundled themes; visual findings and coverage limits |
| [Releasing](RELEASING.md) | Versioning, tags, validation and publication |
| [Changelog](CHANGELOG.md) | Versioned release notes |
| [Issue reporting](ISSUE.md) | Bug reports and feature requests |
| [Security reporting](SECURITY.md) | Private vulnerability reporting |
| [Compatibility](compatibility.md) | Supported IDE/database combinations and limits |
| [Final review](final-review.md) | Historical publication review and validation evidence |
| [Third-party notices](THIRD_PARTY_NOTICES.md) | Bundled dependencies, licenses and source distribution |

GitHub discovers contribution and security guidance in this folder. Issue forms, the pull request template and workflows remain under `.github/` so GitHub can discover them. README and LICENSE remain at the root; agent instructions stay there for automatic discovery, and the Gradle wrapper launchers keep their conventional root locations.

Portable helpers live in `scripts/`, with their regression tests in `scripts/tests/`. Workstation-only scripts and documentation can live in `scripts/local/` and `docs/local/`; these directories are excluded locally through `.git/info/exclude` and must not be force-added to Git. SDKs, database binaries and private caches stay outside the repository.

Generated build output and retained validation reports live under ignored `build/`. Development dependency caches are also ignored. Clean disposable outputs only after confirming that their processes have stopped; retain the current distribution and any reports needed to support a release or review.
