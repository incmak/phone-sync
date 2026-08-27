# Plan 028: Close or fail closed on high mobile dependency advisories

> **Executor instructions**: Work in the primary checkout only. Read
> `AGENTS.md` fully. Use TDD for the verifier contract and obtain explicit
> operator approval before any online `npm audit`, because it sends the
> dependency inventory to the configured registry. Do not use `npm audit fix`,
> force a framework-major upgrade, or weaken an advisory severity. Update
> `advisor-plans/README.md` only after independent review.
>
> **Drift check (run first)**:
> `git diff --stat 1576e33..HEAD -- mobile/package.json mobile/package-lock.json Makefile .github/workflows/android-release.yml scripts/verify-mobile-dependencies.sh scripts/verify-mobile-dependencies_test.sh scripts/verify-android-release-workflow.sh scripts/verify-android-release_test.sh advisor-plans/README.md`

## Status

- **Status**: TODO
- **Priority**: P1
- **Effort**: M
- **Risk**: MED
- **Depends on**: Plan 024
- **Category**: dependencies, security
- **Planned at**: commit `1576e33`, 2026-08-27

## Why this matters

The current locked install reports 13 high advisories but exits successfully,
and the protected Android producer has no audit-level failure gate. Several
affected versions can be refreshed inside their parents' declared semver ranges.
Two build-pipeline packages may require an upstream Expo/Metro update; those
must block protected release rather than be papered over with an unverified
major override.

This is not evidence of a currently exploitable notification or call-sync bug.
The high `nanoid` advisory is on the shipped navigation dependency, but every
current React Navigation/Expo Router caller invokes `nanoid()` with its normal
positive default size. The remaining high paths are Node-based Metro, dev-server,
lint, config, or asset-processing code. The goal is still to remove the known
risk and make future protected builds fail closed.

## Current state

- `mobile/package-lock.json:10956-10957` resolves `nanoid@3.3.11`; current
  advisories require at least `3.3.18`. React Navigation and Expo Router declare
  compatible `^3.3.x` ranges and call only `nanoid()`/`nanoid/non-secure` with no
  user-controlled size.
- `mobile/package-lock.json:3102-3103`, `12443-12444`, and `14653-14654`
  resolve `ws@6.2.3` and `7.5.10`; their parents declare `^6.2.3` and `^7`, so
  patched `6.2.4` and `7.5.11` are range-compatible.
- `mobile/package-lock.json:4702-4703` and `3655-3656` resolve vulnerable
  `brace-expansion@1.1.14` and `5.0.5`; parent ranges admit patched `1.1.18` and
  `5.0.9`.
- `mobile/package-lock.json:9834-9835` and the nested Istanbul path resolve
  `js-yaml@4.1.1` and `3.14.2`; their parents use compatible-major caret ranges,
  but the exact fixing releases must be verified against the authorized live
  advisory and registry response.
- `mobile/package-lock.json:11730-11731` resolves `postcss@8.4.49` under
  `@expo/metro-config`, whose declared `~8.4.32` range does not admit the fixed
  8.5 line.
- `mobile/package-lock.json:8084-8085` resolves `image-size@1.2.1` under Metro,
  whose declared `^1.0.2` range does not admit a fixed 2.x release.
- `Makefile:42-45` runs install, typecheck, and Jest without an audit failure
  gate. `.github/workflows/android-release.yml:30-33` relies on that host gate
  before protected work.
- `mobile/package.json:62-64` already uses a narrow override for `shell-quote`;
  this is precedent for a targeted override only when compatibility is proven.
- `scripts/verify-android-release-workflow.sh` and its self-test are the existing
  fail-closed structural contract for protected release. Match their bounded
  parser/mutation style.

The audit response observed during the 2026-08-27 locked install covered
`brace-expansion`, `js-yaml`, `ws`, `image-size`, `nanoid`, `postcss`, `uuid`,
and a low-severity `@babel/core` issue. Re-run an authorized online audit rather
than treating this snapshot as permanent truth.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Install | `cd mobile && npm ci` | exit 0 |
| Authorized audit | `cd mobile && npm audit --audit-level=high --json` | exit 0 and zero high/critical advisories across the full locked tree |
| Audit self-test | `./scripts/verify-mobile-dependencies_test.sh` | positive fixture passes; malformed/high/critical fixtures fail |
| Release contract | `./scripts/verify-android-release_test.sh` | all mutations pass |
| Mobile host | `cd mobile && npm run typecheck && npm test -- --runInBand && npm run lint` | exit 0; lint has no new warning |
| Native | `make mobile-verify` | exit 0 through AndroidTest compile and APK assemble |
| Exact host | `make host-verify` | exit 0 |
| Hygiene | `git diff --check` | exit 0 |

## Scope

**In scope**:

- `mobile/package.json`
- `mobile/package-lock.json`
- `Makefile`
- `.github/workflows/android-release.yml`
- create `scripts/verify-mobile-dependencies.sh`
- create `scripts/verify-mobile-dependencies_test.sh`
- `scripts/verify-android-release-workflow.sh`
- `scripts/verify-android-release_test.sh`
- `advisor-plans/README.md` status only

**Out of scope**:

- application, native Kotlin, relay, protocol, schema, UI, E2E scenario, or
  physical evidence changes;
- `npm audit fix` or `npm audit fix --force`;
- a React Native, Expo SDK, Metro, or Node major upgrade;
- broad overrides that force every copy of a multi-major dependency to one
  version;
- allowing an advisory exception, waiver, allowlist, or risk-acceptance record
  to make the dependency verifier or protected release pass;
- EAS execution, release credentials, device use, commit push, or public issue.

## Git workflow

- Primary checkout only; no worktree.
- Commit only after review, suggested message:
  `chore(mobile): fail closed on high dependency advisories`.
- Do not push.

## Steps

### 1. Capture a fresh, authorized RED audit

With explicit operator approval, run the online full-locked-tree audit and retain its
JSON only under ignored `.omo/evidence/plan-028/`. Record the npm version and
registry hostname, but no credentials or registry tokens. Prove the current
candidate returns nonzero for high advisories.

Do not use `--offline`: the current offline cache returns a misleading empty
advisory set even though the preceding online locked install reports highs.

**Verify**: current authorized audit exits nonzero and names at least one high
installed path. If it unexpectedly exits zero, STOP and reconcile the live
advisory set before changing dependencies.

### 2. Add a deterministic audit verifier first

Create a fail-closed shell verifier and mutation self-test. Normal mode should
run `npm audit --audit-level=high --json` in `mobile/`, fail on
network/registry error, malformed or empty reports, and any high/critical
vulnerability. A `--check-json <fixture>` mode may parse deterministic self-test
fixtures without network. Validate `auditReportVersion`, dependency totals, and
both metadata counts and per-package severities so an empty object cannot pass
by omission.

Audit the full tree installed by the protected job's ordinary `npm ci`, including
dev dependencies used by lint, Jest, Expo, prebuild, and native assembly. The
verifier must reject `--omit`, `--production`, or another dependency-set filter.

Self-tests must include: valid zero-high report; one high; one critical;
metadata/per-package disagreement; malformed JSON; zero dependency total; and
command/network failure. Do not log the full dependency tree in normal success
output.

**Verify**: new self-test is RED before the verifier exists, then green after
implementation.

### 3. Refresh only range-compatible transitive versions

From the authorized JSON, record every high advisory's vulnerable range and
`fixAvailable` result. For every proposed target, query the same registry for
that exact version and prove it exists before editing the lockfile. Update the
lockfile with npm's resolver, not hand edits.

The 2026-08-27 install response suggested these minimums, but they are audit
leads, not permission to guess a release:

- `nanoid >=3.3.18 <4`
- `ws >=6.2.4 <7` and `>=7.5.11 <8` (existing 8.x copies are already outside
  the cited vulnerable ranges)
- `brace-expansion >=1.1.18 <2` and `>=5.0.9 <6`
- `js-yaml` outside the fresh advisory's vulnerable 3.x and 4.x ranges. In
  particular, verify that any claimed `4.3.1` target exists and is the registry's
  fixing version before selecting it.

Prefer a package-lock-only targeted update. Add a version-scoped override only
if npm cannot resolve an already-admitted patched version, and explain why in
`mobile/package.json`. Never force a 1.x caller to 5.x or a 6.x `ws` caller to
8.x.

After `npm ci`, use `npm ls`/lockfile queries to prove every installed copy in
the vulnerable range is gone. Exercise navigation tests because `nanoid` is the
one bundled runtime path.

### 4. Resolve upstream-only build-pipeline advisories safely

Re-run the authorized audit. For `postcss`, `image-size`, or another remaining
high whose patched version is outside its parent's declared range:

1. First update to an Expo SDK 54/Metro-compatible patch that officially widens
   the range.
2. If no compatible upstream release exists, do not force the major. Leave the
   verifier blocking protected release, mark Plan 028 BLOCKED with the exact
   upstream package/advisory, and create a bounded follow-up migration plan.
3. A risk-acceptance note may document ownership, advisory ID, affected path,
   rationale, and review date for follow-up, but it is documentation only. It
   must not be read by the verifier, suppress an audit result, or unblock the
   protected producer. Any remaining high/critical advisory leaves this plan
   BLOCKED and protected release unavailable, including build-only paths and the
   runtime `nanoid` path.

Any package change beyond a range-compatible patch requires Expo Doctor,
prebuild, full native tests/AndroidTest compilation, and APK assembly before it
can be accepted.

### 5. Gate protected release before secrets

Invoke the dependency verifier in `.github/workflows/android-release.yml` after
the locked install/host verification and before any protected token, signing,
certificate, or attestation input is exposed. Extend the workflow verifier and
mutation self-tests to reject: missing audit; audit after secrets; `|| true` or
other failure suppression; lower severity; `--offline`; any `--omit` or
production-only filter; a substitute echoed/static JSON; or duplicate network
audits.

Do not add a second `npm ci`. Preserve Plan 024's one-install contract.

### 6. Run full gates and independent security/build review

Run every command in the command table. The reviewer must inspect every override
against each parent's declared semver/API, verify navigation still uses a safe
patched `nanoid`, verify no protected secret precedes the audit, and confirm no
high/critical result is waived silently.

## Test plan

- Deterministic JSON verifier fixtures for success, high, critical,
  disagreement, malformed, empty, and command failure.
- Release-workflow mutations for order, failure suppression, severity,
  production omission, offline mode, static output, and duplicate audits.
- Existing Home/route/navigation Jest suites after the `nanoid` refresh.
- Full typecheck/Jest/lint, Expo Doctor/prebuild/native gate, exact host gate.
- Authorized online audit at the end, with zero high/critical or an explicit
  terminal BLOCKED status before protected release.

## Done criteria

- [ ] Fresh authorized full-locked-tree audit evidence exists and is not an
      offline/cache-only or dependency-filtered result.
- [ ] All range-compatible vulnerable transitive versions are absent from the
      lockfile.
- [ ] The protected workflow runs exactly one fail-closed full-tree audit
      before any secret-bearing step and after the one locked install.
- [ ] High/critical, malformed, network failure, audit suppression, and any
      waiver/allowlist input or bypass attempt all fail.
- [ ] No broad or cross-major override was introduced.
- [ ] Final authorized full-locked-tree audit has zero high/critical advisories,
      or Plan 028 is honestly BLOCKED and protected release remains impossible.
- [ ] Full mobile/native/host gates and independent review pass.
- [ ] Only in-scope files changed; `git diff --check` exits 0.

## STOP conditions

- Online audit permission is unavailable or the registry cannot return a fresh
  report.
- A patched version lies outside its parent's range and no compatible upstream
  Expo SDK 54/Metro patch exists.
- The only proposed fix is a broad/cross-major override, `audit fix --force`, or
  a framework-major upgrade.
- Audit would run after protected credentials become available.
- Any proposed exception, waiver, allowlist, or risk-acceptance mechanism would
  affect verifier exit status or protected-release eligibility.
- `npm ci`, Expo Doctor, prebuild, native compilation/tests, or navigation tests
  regress after a dependency change.

## Maintenance notes

Npm severity and package ancestry change over time. Keep the normal verifier
online and fail closed; use deterministic JSON only for its self-test. A
risk-acceptance note may guide follow-up ownership but is never an executable
release exception. Delete it when upstream support lands. Do not treat a
zero-result offline cache as release evidence.
