# Physical release evidence

Physical-device evidence is private release material. Keep the evidence
directory outside the repository, or in an ignored location, and do not paste
device identifiers, notification text, keys, tokens, contacts, or raw logcat
into a report or issue.

## Protected Android candidate

The `protected-android-release` workflow is the only repository workflow that
produces a release candidate. It runs only from an `android-v*` tag or a manual
dispatch and requires approval for the `android-release` environment. Configure
that environment with these protected values:

- `EAS_TOKEN`: an Expo token authorized to build this project;
- `ANDROID_RELEASE_CERT_SHA256`: the expected certificate fingerprint for the
  stable `com.twinotify.app` signing identity, as 64 hex characters;
- `RELEASE_ATTESTATION_PRIVATE_KEY`: the PEM private key matching the committed
  `attestation-public.pem` trust root.

The workflow builds `release-apk` and `production` from the same checkout. The
first is an internally distributed, non-development APK with its JavaScript
bundle embedded, so it starts without Metro. The second is the store AAB. Both
profiles use the EAS `production` environment and local app-version source.
EAS-managed or protected external Android credentials must preserve the
existing signing identity. The workflow never submits either artifact.

Before approving a run, confirm that the tag points to the intended full commit
and that the repository host gates pass. The committed Expo config must already
be linked to the intended EAS project, and the local version in `app.json` must
be advanced deliberately before a new store release. Do not let a protected run
create or relink the project because that would change the build input outside
the attested commit. After the run:

1. Download the `twinotify-android-<commit>` artifact into a fresh private
   directory. Keep `app-release.apk`, `app-release.aab`,
   `app-provenance.json`, `app-attestation.json`, and
   `app-attestation.sig` together.
2. Verify the APK again using the protected certificate value and exact commit:

   ```bash
   ./scripts/verify-standalone-android.sh \
     --apk /private/candidate/app-release.apk \
     --provenance /private/candidate/app-provenance.json \
     --expected-cert-sha256 "$ANDROID_RELEASE_CERT_SHA256" \
     --expected-commit "$(git rev-parse HEAD)"
   ```

3. Install that APK on both approved release devices with Metro stopped. Do not
   use the AAB for direct device installation.
4. Collect the private physical scenarios described below. Copy only the
   allowlisted sanitized artifacts into a new evidence directory. Place the
   protected candidate files at the manifest paths, including
   `artifacts/app-release.apk`, `artifacts/app-provenance.json`,
   `artifacts/app-attestation.json`, and `artifacts/app-attestation.sig`.
5. From the exact candidate commit, run:

   ```bash
   make release-audit RELEASE_EVIDENCE_DIR=/private/path/to/evidence
   ```

The protected build creates a candidate, not a physical test result. The audit
still rejects absent `all-correctness`, stress, capacity, battery, OEM, network,
or other physical evidence.

Host-only release contract checks are safe to run without credentials:

```bash
./scripts/verify-android-release_test.sh
./scripts/verify-release-evidence.sh --self-test
```

When an authorized operator resolves the release profiles, use the pinned
ephemeral CLI rather than adding EAS CLI to this project:

```bash
cd mobile
npx --yes eas-cli@22.0.0 config --platform android --profile release-apk --non-interactive
npx --yes eas-cli@22.0.0 config --platform android --profile production --non-interactive
```

Resolving EAS configuration itself requires an Expo account. In CI, the
protected environment supplies that authority. The current checkout is not yet
linked to an EAS project, so an authorized owner must establish and commit that
link before the first approved build. Never add a credential bypass, commit a
keystore, or copy a protected fingerprint from the artifact being verified.

## Gate

Run the verifier against one candidate directory:

```bash
make release-audit RELEASE_EVIDENCE_DIR=/private/path/twinotify-release-2026-08-14
```

The gate is intentionally strict. It requires a manifest, an APK and a
sanitized E2E result from the exact current commit. The result must record
passing `all-correctness`, `burst-1000`, and `offline-capacity` runs. It also
requires one Android 14+ Pixel and one Android 14+ Samsung device, all six
physical scenario IDs, both batterystats captures, a sanitized timeline, and
operator notes. The verifier compares the APK and E2E result SHA-256 values
itself; hashes copied into a manifest are not trusted.

Run the verifier's fixture tests without physical devices:

```bash
./scripts/verify-release-evidence.sh --self-test
```

The self-test proves that a missing Samsung battery capture and a mismatched
APK hash fail with actionable diagnostics, and that a complete fixture passes.
It is not physical-device evidence.

## Manifest contract

`manifest.json` has this shape (paths are relative to the evidence directory):

```json
{
  "app_sha256": "64 lowercase hex characters",
  "relay_git_commit": "40 hex characters",
  "e2e_result_sha256": "64 lowercase hex characters",
  "e2e_git_commit": "40 hex characters",
  "app_git_commit": "40 hex characters",
  "tested_at": "2026-08-14T12:00:00Z",
  "devices": [
    {"role":"pixel", "model":"...", "android":14, "build":"..."},
    {"role":"samsung", "model":"...", "android":14, "build":"..."}
  ],
  "scenarios": {
    "PHY-PAIR-01":"pass",
    "PHY-DOZE-01":"pass",
    "PHY-OEM-01":"pass",
    "PHY-NET-01":"pass",
    "PHY-BATTERY-01":"pass",
    "PHY-RELIABILITY-01":"pass"
  },
  "artifacts": {
    "app":"artifacts/app-release.apk",
    "app_provenance":"artifacts/app-provenance.json",
    "app_attestation":"artifacts/app-attestation.json",
    "app_attestation_signature":"artifacts/app-attestation.sig",
    "e2e_result":"artifacts/e2e-result.json",
    "timeline":"artifacts/timeline.json",
    "operator_notes":"artifacts/operator-notes.md",
    "batterystats": {
      "pixel":"artifacts/batterystats/pixel.txt",
      "samsung":"artifacts/batterystats/samsung.txt"
    }
  }
}
```

`app-provenance.json` must contain `git_commit` and `app_sha256`. The verifier
requires both values to match the manifest, the current release commit, and
the bytes of the APK. Artifact paths must remain inside the evidence
directory; symlinked files and symlink escapes are rejected.

The attestation JSON must contain `app_sha256`, `app_git_commit`,
`relay_git_commit`, and `e2e_git_commit`. It is verified with a detached
signature from the pinned public key in
[`attestation-public.pem`](attestation-public.pem). The corresponding private
key is held by the protected build/release system and is never stored in this
repository or evidence directory. There is no unsigned fallback. The normal
audit invocation uses the pinned key automatically; `RELEASE_ATTESTATION_PUBLIC_KEY`
is not accepted by a normal one-argument audit. The self-test uses the private
`--self-test-key` verifier mode with a temporary fixture key; that mode is not
used by `make release-audit`.

The E2E result JSON must include `e2e_git_commit` and a `scenarios` object
whose `all-correctness`, `burst-1000`, and `offline-capacity` values are
`pass`. Timelines must be JSON arrays containing only event IDs, states,
timestamps, and measurements. Do not include notification titles, text,
extras, payloads, ciphertext, nonces, keys, JWTs, phone numbers, contacts, or
unrelated device logs.

## Physical scenarios

The exact operator steps and evidence commands are in
[`docs/test-scenarios.md`](../test-scenarios.md). Every scenario records
durable state, Android-visible state, a timestamped sanitized timeline, and a
cleanup result:

- `PHY-PAIR-01`: pair two fresh devices by QR/fingerprint and verify restart
  recovery.
- `PHY-DOZE-01`: lock both devices, leave Doze active, introduce notifications,
  and verify delivery after wake.
- `PHY-OEM-01`: exercise Samsung background restrictions and notification
  listener rebind, including a reboot.
- `PHY-NET-01`: perform Wi-Fi/mobile handoff and relay restart while checking
  durable queue convergence.
- `PHY-BATTERY-01`: run the 24-hour battery protocol at approximately 100
  notifications per day and retain final batterystats for both devices.
- `PHY-RELIABILITY-01`: revoke/restore permissions, force-stop/restart, update,
  reboot, and verify no duplicate or lost state.
- `PHY-CALL-01`: exercise call permission denial, grant, revocation, and
  recovery plus real calls in both directions over direct LAN and relay. Verify
  the HIGH action-free call channel, stable identity, terminal removal,
  custody/receipt convergence, and no resurrection across screen-off and
  process restart.

The absence of a physical evidence directory is a pending release gate, not a
pass. Never manufacture a manifest or mark a scenario `pass` without the
corresponding device capture.
