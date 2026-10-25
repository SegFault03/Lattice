# Signing and Marketplace publishing

Before changing signing or publication automation, read the
[CI maintenance conventions](CI_TESTING.md#before-changing-ci),
[Releasing](RELEASING.md) and the relevant sections below. Keep workflow guards,
credential handling, artifact validation and their tests consistent with those
guides.

## Signing setup

Tagged GitHub releases require author signing. Ordinary local builds and local dry runs remain unsigned and need no secrets. The build signs the ZIP with the pinned Marketplace ZIP Signer 0.1.24, verifies its signature, then checks its contents and computes checksums. Publication consumes that exact signed ZIP; PR compatibility jobs check the unsigned PR build before merge. Missing signing secrets or a failed signature check block publication.

The Marketplace publishing token authenticates uploads; it is separate from your signing key. Generate an encrypted RSA key and a self-signed certificate once, outside this repository. Git for Windows includes OpenSSL at `C:/Program Files/Git/usr/bin/openssl.exe`; Linux/macOS can use `openssl` directly. For example, in PowerShell:

```powershell
$signingDir = Join-Path $env:USERPROFILE '.lattice-signing'
New-Item -ItemType Directory -Force -Path $signingDir | Out-Null
$openssl = 'C:/Program Files/Git/usr/bin/openssl.exe'
# Use a new directory or filenames if these files already exist; preserve the original key.
& $openssl genrsa -traditional -aes256 -out "$signingDir/private.pem" 4096
& $openssl req -new -x509 -sha256 -days 365 -key "$signingDir/private.pem" -out "$signingDir/chain.crt" -subj '/CN=Lattice Plugin Signing'
```

OpenSSL prompts for the encryption password. Back up the key and password securely, keep them out of Git, and track the certificate expiry date. Renew the certificate before expiration. Do not regenerate the key on every build.

In **GitHub repository Settings → Secrets and variables → Actions**, add repository secrets:

| Secret | Value |
|---|---|
| `LATTICE_SIGNING_PRIVATE_KEY` | Complete contents of `private.pem`, including PEM header/footer and line breaks |
| `LATTICE_SIGNING_CERTIFICATE_CHAIN` | Complete contents of `chain.crt` |
| `LATTICE_SIGNING_PRIVATE_KEY_PASSWORD` | Password chosen when generating the encrypted key |
| `JETBRAINS_MARKETPLACE_TOKEN` | Existing Marketplace publishing token; used by the Marketplace upload job |

The signing secrets are scoped to the signing step; the token is scoped to the upload step. Do not paste them into issues, workflow inputs or logs. Local signing uses environment variables with the same `LATTICE_SIGNING_*` names and the committed wrapper:

```text
./gradlew signPlugin verifyPluginSignature -Plattice.requireSigning=true
```

On Windows use `gradlew.bat`, Java 21 and your shared Gradle cache; quote `-P` arguments containing dots in PowerShell. Local signing produces `build/distributions/Lattice-VERSION-signed.zip`. The hosted workflow renames the verified signed ZIP to the ordinary release filename before calculating checksums. Never overwrite a signed ZIP with a later unsigned build.

Author signing and Marketplace signing are distinct. Marketplace adds its own signature during delivery. A self-signed ZIP installed directly from GitHub may require trusting `chain.crt` under **Settings → Plugins → Manage Plugin Certificates**. See [JetBrains signing documentation](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).

## Local preparation

The hosted release workflow has no manual trigger or hidden-channel upload. Use the [local dry-run commands](RELEASING.md#local-dry-run) to review the ZIP, rendered patch notes, source and checksums without publishing. `release.py --marketplace-smoke` remains a local notes/version preparation helper; it does not submit an update.

Screenshots are maintained separately in Marketplace's Media section. Change notes are part of the submitted archive and cannot be edited in place after submission. Finalize notes before tagging.

## Public releases

Create and push an annotated release tag only when the release is approved by the maintainer. A tag such as `v0.1.0-alpha` runs functional/package checks and signing, publishes GitHub assets and notes, then submits the exact same ZIP to Marketplace's public Stable channel (`channel` empty, `isHidden=false`). The remaining validation suites run on the preceding main-targeted PR, as defined in [CI policy](CI_TESTING.md). Alpha/RC versions remain GitHub prereleases but are deliberately available to ordinary Marketplace users after JetBrains approval. No tag or release is created by pushing preparation commits to main.

The Marketplace job requires successful GitHub publication for tag runs. A failed upload leaves the GitHub Release available and can be retried independently after checking for an existing Marketplace version. Never rerun an upload that Marketplace already accepted. Only new version tags whose commits are reachable from `main` can publish; branch pushes and manual CI checks publish nothing. See [Releasing](RELEASING.md).
