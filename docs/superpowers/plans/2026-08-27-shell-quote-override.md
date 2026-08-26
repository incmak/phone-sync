# Pin the Patched Shell Parser Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the known `shell-quote@1.8.3` advisory from the mobile production dependency graph without changing Expo or React Native versions.

**Architecture:** Keep the existing `react-native@0.81.5 -> react-devtools-core@6.1.5 -> shell-quote@^1.6.1` graph, but use npm's root override to resolve the transitive parser to `1.10.0`. The affected devtools path is guarded by `__DEV__` and absent from the standalone APK, so this is a dependency-hygiene change rather than an app-runtime repair.

**Tech Stack:** npm 11 lockfile v3, Expo SDK 54, React Native 0.81.5, Android Gradle build.

## Global Constraints

- Work in the primary checkout only; do not create a worktree or push.
- Do not run `npm audit fix`, upgrade Expo/React Native, or modify unrelated dependencies.
- The committed diff must contain only `mobile/package.json` and `mobile/package-lock.json`.
- Reuse the existing standalone release APK scan to prove devtools code remains absent from the bundled app.

---

### Task 1: Override the vulnerable transitive parser

**Files:**
- Modify: `mobile/package.json`
- Modify: `mobile/package-lock.json`

**Interfaces:**
- Consumes: `react-devtools-core@6.1.5` dependency range `shell-quote@^1.6.1`
- Produces: one resolved `shell-quote@1.10.0` node and a clean npm audit for this advisory

- [ ] **Step 1: Record the failing dependency audit**

Run from `mobile/`:

```bash
npm ls shell-quote react-devtools-core react-native --all --omit=dev
npm audit --omit=dev --json
```

Expected: the tree resolves `shell-quote@1.8.3`, and the audit reports its known high/critical advisory.

- [ ] **Step 2: Add the exact root override**

Add this top-level key to `mobile/package.json`:

```json
"overrides": {
  "shell-quote": "1.10.0"
}
```

Regenerate the lockfile with npm 11 without changing declared package versions:

```bash
npm install --package-lock-only --ignore-scripts
```

- [ ] **Step 3: Verify dependency resolution and audit**

Run:

```bash
npm ci
npm ls shell-quote react-devtools-core react-native --all --omit=dev
npm audit --omit=dev --json
```

Expected: the only `shell-quote` node is `1.10.0`; the prior advisory is absent; `npm ci` succeeds.

- [ ] **Step 4: Verify mobile and Android compatibility**

Run:

```bash
npm run typecheck
npm test -- --runInBand
npm run lint
npx --yes expo-doctor
cd android && ./gradlew --no-daemon :twinotify-core:testDebugUnitTest :twinotify-core:compileDebugAndroidTestKotlin :twinotify-core:lintDebug :app:assembleDebug :app:assembleRelease
```

Expected: typecheck and Jest pass; lint has zero errors; Expo Doctor passes; Gradle JVM tests, Android-test compilation, lint, debug build, and bundled release build pass.

- [ ] **Step 5: Prove release scope and review the exact diff**

Inspect the release APK and require no matches for `shell-quote`, `react-devtools-core`, `connectToDevTools`, or `setUpReactDevTools`. Run `git diff --check` and verify only the two declared files changed. Request an independent dependency/security review.

- [ ] **Step 6: Commit**

```bash
git add mobile/package.json mobile/package-lock.json
git commit -m "fix(mobile): pin patched shell parser"
```

Do not push.
