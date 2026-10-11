# Signing and Marketplace smoke testing

## Signing setup

Tagged GitHub releases and manual Marketplace smoke submissions require author signing. Ordinary local builds and manual dry runs remain unsigned and need no secrets. The build signs the ZIP with the pinned Marketplace ZIP Signer 0.1.24, verifies its signature, then checks its contents and computes checksums. Compatibility jobs and publication consume that exact signed ZIP. Missing signing secrets or a failed signature check block publication.

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
| `JETBRAINS_MARKETPLACE_TOKEN` | Existing Marketplace publishing token; only the smoke upload job uses it |

The signing secrets are scoped to the signing step; the token is scoped to the upload step. Do not paste them into issues, workflow inputs or logs. Local signing uses environment variables with the same `LATTICE_SIGNING_*` names and the committed wrapper:

```text
./gradlew signPlugin verifyPluginSignature -Plattice.requireSigning=true
```

On Windows use `gradlew.bat`, Java 21 and your shared Gradle cache; quote `-P` arguments containing dots in PowerShell. Local signing produces `build/distributions/Lattice-VERSION-signed.zip`. The hosted workflow renames the verified signed ZIP to the ordinary release filename before calculating checksums. Never overwrite a signed ZIP with a later unsigned build.

Author signing and Marketplace signing are distinct. Marketplace adds its own signature during delivery. A self-signed ZIP installed directly from GitHub may require trusting `chain.crt` under **Settings → Plugins → Manage Plugin Certificates**. See [JetBrains signing documentation](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html).

## Hidden smoke test without a GitHub Release

1. Push the workflow and signing configuration to the default branch so the manual workflow controls are available. Configure the four secrets above before running it.
2. Choose an unused prerelease version, for example `0.0.2-smoke.1`. Add its patch notes to a matching section in `docs/CHANGELOG.md` (recommended), or populate Unreleased. `scripts/release.py` embeds those notes in the ZIP's `plugin.xml`; uploading the ZIP submits them to Marketplace along with the version. GitHub's longer release description and commit history are separate.
3. Open **Actions → Build, test and release → Run workflow**, select the intended branch, enter the version, and check **marketplace_smoke**. Do not create a tag. A missing version or stable version is rejected.
4. The normal release validation gates run, including Windows/macOS tests, live database checks, real-IDE UI checks, signature verification and three IDE compatibility checks. Only after every gate succeeds, the workflow submits the signed ZIP to XML ID `com.segfault03.lattice`, fixed channel `smoke`, with `isHidden=true`. The GitHub Release job is skipped. The `release-candidate` artifact contains the signed ZIP, notes, source archive and checksums; `marketplace-smoke-response` contains the upload API response. Both expire after seven days.
5. Inspect the submitted version, channel, hidden flag and change notes in your Marketplace listing's Versions administration view. JetBrains still reviews every update; upload success is not approval. After approval, open the hidden version's direct link, download its Marketplace-delivered ZIP and install it with **Settings → Plugins → Install Plugin from Disk**. Check the installed version and patch notes, and exercise your normal connection/query/table-editing smoke flows.

A custom channel alone is not hidden: channels are displayed on the Marketplace plugin page. This workflow adds the hidden flag to keep the test update from becoming publicly listed after approval. Hidden versions are accessible by direct link, so this is not private access control. Keep the version hidden. If you later deliberately unhide it, channel subscribers can use `https://plugins.jetbrains.com/plugins/smoke/list` as a custom IDE repository; ordinary Stable-channel users will not receive it. See [channels](https://plugins.jetbrains.com/docs/marketplace/custom-release-channels.html) and [hidden updates](https://plugins.jetbrains.com/docs/marketplace/hidden-plugin.html).

Screenshots are maintained separately in Marketplace's Media section. No screenshot changes or stable Marketplace uploads are made by this workflow. Change notes are part of the submitted archive and cannot be edited in place after submission; Marketplace allows compatibility-range edits only. Finalize notes before running. See [plugin updates](https://plugins.jetbrains.com/docs/marketplace/plugin-updates.html).

There is no automatic upload retry: a connection error can occur after Marketplace accepts the file. Before rerunning a failed upload job, check whether the version already exists. If accepted, do not resubmit it. If absent, rerun only the failed job while its candidate artifact is available. Use a new prerelease version for changed code or notes. Never reuse a smoke version for a different build.
