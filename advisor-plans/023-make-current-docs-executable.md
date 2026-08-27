# Plan 023: Make contributor and release documentation match the live product

> **Executor instructions**: Work in the primary checkout only. Read `AGENTS.md`
> fully. This is a documentation-contract change: preserve every truthful
> physical/release `PENDING` marker. Use test-first shell fixtures, do not use a
> worktree, device, network, EAS credentials, or push. Update
> `advisor-plans/README.md` when complete.
>
> **Drift check (run first)**:
> `git diff --stat f119224..HEAD -- README.md mobile/README.md proto/README.md docs/release-evidence/README.md Makefile scripts/verify-project-docs.sh scripts/verify-project-docs_test.sh scripts/verify-host-workflows.sh scripts/verify-host-workflows_test.sh`

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: docs
- **Planned at**: commit `f119224`, 2026-08-27

## Why this matters

The mobile README is untouched Expo starter text that recommends `npm install`,
Expo Go, and deleting the app with `reset-project`, none of which is a valid
Twinotify workflow. The protocol README claims v1 plus a nonexistent Desktop
Rust client, and the release manifest example omits the now-mandatory
`PHY-CALL-01`. A contributor or release operator following these files can take
an unsupported path or construct evidence the verifier correctly rejects.

## Current state

- There is no root `README.md`.
- `mobile/README.md` begins “Welcome to your Expo app”, recommends
  `npm install`, `npx expo start`, Expo Go, and `npm run reset-project`.
- `proto/README.md` is titled “Phone-Sync Protocol (v1)”, names a Desktop Rust
  client, and describes v2 message classes as future work.
- `docs/release-evidence/README.md` documents all seven physical scenarios in
  prose, but its `manifest.json` example lists only six and omits
  `PHY-CALL-01`.
- `scripts/verify-release-evidence.sh` already requires exactly the seven current
  scenario IDs; do not weaken it.
- Canonical commands are in `AGENTS.md` and `Makefile`: `make host-verify`,
  `make verify`, `make e2e-emulator`, and `make release-audit`.

## Scope

**In scope**:

- create `README.md`
- replace `mobile/README.md`
- update `proto/README.md`
- correct `docs/release-evidence/README.md`
- add `scripts/verify-project-docs.sh` and its self-test
- wire that check into `host-verify` and its existing workflow-contract verifier

**Out of scope**: changing protocol schemas, source behavior, physical scenario
status, EAS project linkage, credentials, creating evidence, or claiming a local
APK is a release candidate.

## Git workflow

- Primary checkout only.
- Commit after review as `docs: align contributor and release guidance`.
- Do not push.

## Steps

### 1. Capture stale guidance in a fail-closed docs check

Write a shell self-test first. The production verifier must reject fixtures when:

- the mobile README contains Expo Go, `npm install`, or `reset-project`;
- the protocol README names Desktop Rust or describes only v1;
- the release manifest example lacks any required physical scenario;
- the root README omits the custom native-module/dev-build requirement or the
  canonical host/native/release commands;
- a pending physical or protected-release item is rewritten as passed.

The checker must parse the fenced manifest JSON example with `jq`; do not rely on
substring count alone.

**Verify**: `./scripts/verify-project-docs_test.sh` fails before the docs are fixed.

### 2. Write repository-accurate onboarding

Create a terse root README covering architecture, prerequisites, primary-checkout
rule, `make host-verify`, native prebuild/device requirements, relay startup, and
links to threat model, test scenarios, and release evidence.

Replace the mobile README with `npm ci`, typecheck/lint/Jest, prebuild/dev-build,
API-level/device requirements, and the warning that Expo Go cannot load the
custom Kotlin module. Do not suggest reset-project.

Update the protocol README to explain coexistence of v1 online compatibility and
v2 authenticated durable delivery, Android and Go consumers, generated relay
schema copy, schema `$id` prefix invariant, and `make sync-proto`/protocol tests.

### 3. Correct the release example without changing truth

Add `PHY-CALL-01` to the manifest example. Keep protected signing/provenance and
all physical two-phone scenarios explicitly pending until real evidence exists.
Do not add placeholder pass artifacts.

### 4. Make documentation drift a host gate

Add `./scripts/verify-project-docs.sh` to `host-verify`, update the exact recipe
contract in `verify-host-workflows.sh`, and add mutation cases to
`verify-host-workflows_test.sh`. Keep Makefile recipe tabs.

**Verify**:

```bash
./scripts/verify-project-docs_test.sh
./scripts/verify-project-docs.sh
./scripts/verify-release-evidence.sh --self-test
./scripts/verify-host-workflows.sh
./scripts/verify-host-workflows_test.sh
make host-verify
git diff --check
```

Expected: every command exits 0. Network escalation is allowed only if the
operator approves a required `npm ci`; never claim a sandbox DNS failure passed.

## Test plan

- Self-test each forbidden stale phrase and each missing required scenario.
- Parse the exact JSON example and compare its scenario key set to the release
  verifier contract.
- Mutation-test removal of the new host recipe entry.
- Run the existing release-evidence self-test unchanged.

## Done criteria

- [ ] Root and mobile quickstarts describe only supported Twinotify workflows.
- [ ] Protocol README describes current v1+v2 Android/relay reality.
- [ ] Release manifest example contains all seven required scenarios including
      `PHY-CALL-01`.
- [ ] Physical and protected-release claims remain pending.
- [ ] Docs checker, self-test, host workflow checks, `make host-verify`, and diff
      check pass.

## STOP conditions

- Any source/schema behavior must change to make the docs true.
- A physical scenario or protected artifact would be marked passed without exact
  evidence.
- The checker would need private keys, tokens, device IDs, or network data.
- An in-scope document has drifted and contradicts the live code.

## Maintenance notes

The docs checker protects only load-bearing commands and release truth. It should
not enforce prose formatting or make harmless editorial changes brittle.
