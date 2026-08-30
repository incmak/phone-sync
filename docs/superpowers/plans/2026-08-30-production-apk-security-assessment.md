# Production APK and Security Assessment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce an installable non-development Android release APK using a stable EAS-managed signing identity, then perform an adversarial security review and bounded live penetration test before two-phone testing.

**Architecture:** Keep the existing `release-apk` EAS profile and `com.twinotify.app` identity. Establish and commit the owner-authorized EAS project link, let EAS create or reuse one Android signing identity, preserve the downloaded APK and build metadata outside Git, and assess the mobile, relay, protocol, deployment, and public endpoint without destructive load. The APK is a production-mode test candidate; only the protected GitHub workflow with certificate pinning and signed attestation may call an artifact a protected Play candidate.

**Tech Stack:** Expo SDK 57, EAS CLI 22.0.0, Gradle/Android APK tooling, Go 1.25.13, GitHub Actions, Docker/Caddy, OCI, shell-based security checks.

## Global Constraints

- Work in the primary checkout; do not create a Git worktree.
- Never commit Android keystores, tokens, private attestation keys, device identifiers, notification contents, or raw physical evidence.
- Do not uninstall or clear either phone during APK production; release installation requires separate destructive approval if the current signing identity differs.
- Keep `release-apk` non-development, internal-distribution, APK output with the embedded JavaScript bundle.
- Keep `production` as the Play AAB profile; do not submit anything to Google Play.
- Do not perform denial-of-service, credential guessing, persistence corruption, or destructive live-server testing.
- Report an internal adversarial review separately from a genuinely independent third-party audit.

---

### Task 1: Release-input and host gate

**Files:**
- Read: `mobile/app.json`
- Read: `mobile/eas.json`
- Read: `.github/workflows/android-release.yml`
- Read: `scripts/verify-android-release-workflow.sh`

**Interfaces:**
- Consumes: clean `main` checkout and authenticated Expo/GitHub sessions.
- Produces: a verified release-input commit and explicit list of external credential gaps.

- [ ] **Step 1: Verify the protected workflow contract**

Run:

```bash
./scripts/verify-android-release_test.sh
./scripts/verify-release-evidence.sh --self-test
```

Expected: both commands exit 0.

- [ ] **Step 2: Run the host verification gate**

Run:

```bash
make host-verify
```

Expected: exit 0 with protocol and host checks passing.

- [ ] **Step 3: Record the exact clean commit**

Run:

```bash
git diff --check
git status --short
git rev-parse HEAD
```

Expected: no unplanned changes before the EAS link commit.

### Task 2: Establish the owner-authorized EAS project link

**Files:**
- Modify: `mobile/app.json`
- Test: Expo resolved configuration

**Interfaces:**
- Consumes: authenticated Expo account `incmak`.
- Produces: committed `expo.extra.eas.projectId` and `expo.owner` linkage.

- [ ] **Step 1: Create and link the Twinotify EAS project**

Run:

```bash
cd mobile
npx --yes eas-cli@22.0.0 init --account incmak --non-interactive
```

Expected: an EAS project is created or linked and `app.json` receives its project ID.

- [ ] **Step 2: Resolve both release profiles**

Run:

```bash
npx --yes eas-cli@22.0.0 config --platform android --profile release-apk --non-interactive
npx --yes eas-cli@22.0.0 config --platform android --profile production --non-interactive
```

Expected: both resolve to `com.twinotify.app`, version `0.1.0`, and the intended EAS project.

- [ ] **Step 3: Commit only the project linkage**

Run:

```bash
git add mobile/app.json
git commit -m "chore(mobile): link Twinotify EAS project"
git push origin main
```

Expected: `main` contains the stable project link and no credentials.

### Task 3: Build and verify the production-mode test APK

**Files:**
- Produce outside Git: `/private/tmp/twinotify-release-${release_commit}/app-release.apk`
- Produce outside Git: `/private/tmp/twinotify-release-${release_commit}/build.json`
- Produce outside Git: `/private/tmp/twinotify-release-${release_commit}/verification.txt`

**Interfaces:**
- Consumes: committed EAS project link and EAS-managed Android credential.
- Produces: installable release APK, immutable build ID, SHA-256, package/version, debuggable status, and certificate fingerprint.

- [ ] **Step 1: Start the EAS release APK build**

Run:

```bash
cd mobile
release_commit=$(git rev-parse HEAD)
release_dir="/private/tmp/twinotify-release-$release_commit"
mkdir -p "$release_dir"
npx --yes eas-cli@22.0.0 build --platform android --profile release-apk --wait --json > "$release_dir/build.json"
```

Expected: successful EAS build using a stable Android signing credential.

- [ ] **Step 2: Download the exact APK over HTTPS**

Extract and download the exact build URL:

```bash
release_commit=$(git rev-parse HEAD)
release_dir="/private/tmp/twinotify-release-$release_commit"
build_url=$(jq -er 'if type == "array" then .[0] else . end | .artifacts.buildUrl // .artifacts.applicationArchiveUrl' "$release_dir/build.json")
curl --proto '=https' --tlsv1.2 --fail --location --output "$release_dir/app-release.apk" "$build_url"
```

Expected: an APK ZIP archive with a nonzero SHA-256.

- [ ] **Step 3: Verify release properties**

Run:

```bash
release_commit=$(git rev-parse HEAD)
release_dir="/private/tmp/twinotify-release-$release_commit"
apksigner verify --verbose --print-certs "$release_dir/app-release.apk"
aapt dump badging "$release_dir/app-release.apk"
apkanalyzer manifest debuggable "$release_dir/app-release.apk"
```

Expected: valid signature, package `com.twinotify.app`, version `0.1.0`, and `debuggable=false`.

- [ ] **Step 4: Confirm release-only attack surface**

Run:

```bash
release_commit=$(git rev-parse HEAD)
release_dir="/private/tmp/twinotify-release-$release_commit"
cert_sha=$(apksigner verify --print-certs "$release_dir/app-release.apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
apk_sha=$(shasum -a 256 "$release_dir/app-release.apk" | awk '{print $1}')
build_id=$(jq -er 'if type == "array" then .[0] else . end | .id' "$release_dir/build.json")
jq -n --arg commit "$release_commit" --arg apk "$apk_sha" --arg build "$build_id" \
  '{git_commit:$commit,app_sha256:$apk,release_apk_build_id:$build,release_apk_profile:"release-apk"}' \
  > "$release_dir/app-provenance.json"
./scripts/verify-standalone-android.sh \
  --apk "$release_dir/app-release.apk" \
  --provenance "$release_dir/app-provenance.json" \
  --expected-cert-sha256 "$cert_sha" \
  --expected-commit "$release_commit"
```

Expected: release manifest excludes debug control components and the APK matches its recorded commit and certificate. If protected provenance cannot be created locally, record that as a protected-candidate blocker rather than bypassing it.

### Task 4: Internal independent security review

**Files:**
- Review: `relay/`
- Review: `mobile/modules/twinotify-core/`
- Review: `proto/`
- Review: `deploy/`
- Produce outside Git: `/private/tmp/twinotify-security-review-2026-08-30.md`

**Interfaces:**
- Consumes: exact release commit and deployed relay version.
- Produces: severity-ranked findings with file/line evidence, exploit preconditions, and remediation recommendations.

- [ ] **Step 1: Run dependency and secret scanners available locally**

Run:

```bash
cd relay && govulncheck ./...
cd ../mobile && npm audit --omit=dev
cd .. && git grep -nE '(BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|AKIA[0-9A-Z]{16})'
```

Expected: no unresolved critical/high dependency vulnerability and no committed private credential.

- [ ] **Step 2: Audit authentication, authorization, cryptography, and key lifecycle**

Inspect device-bound JWT verification, JTI persistence, mutual pairing signatures, pair-scoped mailbox transactions, nonce allocation, Keystore wrapping, unpair rotation, downgrade prevention, and ciphertext validation. Every finding must cite an exact file and line.

- [ ] **Step 3: Audit Android release exposure**

Inspect merged release manifest, exported components, intent filters, backup/data-extraction policy, cleartext traffic, notification listener/service permissions, debug-only components, logs, WebView/deep-link input, and local data storage.

- [ ] **Step 4: Record the independent reviewer verdict**

Require a second read-only reviewer to report critical/high/medium findings without modifying files. Reconcile disagreements explicitly in the private report.

### Task 5: Bounded live penetration test

**Files:**
- Produce outside Git: `/private/tmp/twinotify-pentest-2026-08-30.md`

**Interfaces:**
- Consumes: `https://relay.twinotify.nuvaynlabs.com` and the exact deployed relay build.
- Produces: timestamped, non-destructive evidence for network/TLS, unauthenticated routes, malformed inputs, auth bypass attempts, rate limits, and WebSocket boundary behavior.

- [ ] **Step 1: Test TLS and public exposure**

Verify certificate hostname/chain, accepted TLS versions, security headers, public ports, hidden metrics, and absence of direct port 8080 exposure.

- [ ] **Step 2: Test unauthenticated and malformed requests**

Exercise missing/bad bearer tokens, `alg=none`, malformed JWT claims, invalid WebSocket upgrades, wrong content types, truncated JSON, bounded oversized bodies, unexpected methods, and path normalization. Use only a handful of requests per case.

- [ ] **Step 3: Test pairing boundary safely**

Verify expired/invalid pair tokens, missing responder signature rejection, invalid Ed25519 signatures, conflicting device keys, and bounded per-IP/token behavior without sustained load. Allow temporary pending records to expire naturally.

- [ ] **Step 4: Compare live results to repository tests**

Map every live behavior to a repository test or record a coverage gap. Do not claim protection from a test that only exercises a mock.

### Task 6: Release decision and phone-install handoff

**Files:**
- Read: `/private/tmp/twinotify-security-review-2026-08-30.md`
- Read: `/private/tmp/twinotify-pentest-2026-08-30.md`
- Read: APK verification outputs

**Interfaces:**
- Consumes: verified APK and completed security evidence.
- Produces: explicit test-candidate verdict, blockers, hashes, and safe installation instructions.

- [ ] **Step 1: Apply release blockers**

Block installation if the APK is debuggable, unsigned, signed by an unrecorded identity, contains debug-only components, fails host checks, or the review finds an unresolved critical/high vulnerability.

- [ ] **Step 2: Report signing compatibility before device mutation**

Compare the APK certificate with each installed `com.twinotify.app`. If different, stop and obtain explicit approval before uninstalling because uninstall clears pairing and app data.

- [ ] **Step 3: Hand off the candidate**

Provide the exact APK path, SHA-256, EAS build ID, commit, certificate fingerprint, review verdict, and remaining protected-release/third-party-audit limitations.
