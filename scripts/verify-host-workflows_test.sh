#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
VERIFY="$ROOT_DIR/scripts/verify-host-workflows.sh"

[[ -x "$VERIFY" ]] || {
  echo "host workflow verifier is missing or not executable: $VERIFY" >&2
  exit 1
}

"$VERIFY"

tmp=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-host-workflows.XXXXXX")
trap 'rm -rf -- "$tmp"' EXIT

copy_workflows() {
  rm -rf -- "$tmp/.github"
  mkdir -p "$tmp/.github/workflows"
  cp "$ROOT_DIR/.github/workflows/mobile.yml" "$tmp/.github/workflows/mobile.yml"
  cp "$ROOT_DIR/.github/workflows/e2e-host.yml" "$tmp/.github/workflows/e2e-host.yml"
  cp "$ROOT_DIR/Makefile" "$tmp/Makefile"
}

missing_rejections=0
expect_rejection() {
  local label=$1
  if TWINOTIFY_HOST_WORKFLOW_ROOT="$tmp" "$VERIFY" >/dev/null 2>"$tmp/error"; then
    echo "self-test expected rejection: $label" >&2
    missing_rejections=$((missing_rejections + 1))
  fi
}

copy_workflows
sed -i.bak '/npm test -- --runInBand/d' "$tmp/.github/workflows/mobile.yml"
expect_rejection 'missing mobile Jest'

copy_workflows
awk '/^[[:space:]]*- run: npm test -- --runInBand$/ { print "      # - run: npm test -- --runInBand"; next } { print }' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'commented mobile Jest cannot satisfy typecheck job'

copy_workflows
awk '
  /^[[:space:]]*- run: npm test -- --runInBand$/ { print "      # - run: npm test -- --runInBand"; next }
  $0 == "  native-android:" { in_native = 1 }
  in_native && $0 == "      - run: npm ci" { print; print "      - run: npm test -- --runInBand"; in_native = 0; next }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'Jest in another job cannot satisfy typecheck job'

copy_workflows
awk '
  $0 == "      - run: npx tsc --noEmit" { typecheck = $0; next }
  $0 == "      - run: npm test -- --runInBand" { print; print typecheck; next }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'typecheck must precede Jest in mobile typecheck job'

copy_workflows
awk '/^[[:space:]]*- run: npm test -- --runInBand$/ { print; print; next } { print }' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'mobile typecheck job must not duplicate Jest'

copy_workflows
sed -i.bak 's/e2e\/\*\*/not-e2e\/\*\*/g' "$tmp/.github/workflows/mobile.yml"
expect_rejection 'mobile paths omit E2E changes'

copy_workflows
sed -i.bak 's#\.github/workflows/mobile\.yml#.github/workflows/not-mobile.yml#g' "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'E2E host paths omit mobile workflow changes'

copy_workflows
sed -i.bak 's/-race -count=1/-short/' "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'missing E2E race test'

copy_workflows
sed -i.bak 's/go vet \.\/\.\./go vet .\/internal/' "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'missing E2E vet'

copy_workflows
sed -i.bak 's/verify-release-evidence\.sh --self-test/verify-release-evidence.sh --not-a-self-test/' "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'missing release evidence self-test'

copy_workflows
sed -i.bak '/verify-android-release_test\.sh/d' "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'missing protected Android release contract test'

copy_workflows
sed -i.bak "s#\.github/workflows/android-release\.yml#.github/workflows/not-android-release.yml#g" "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'E2E host paths omit protected Android release workflow changes'

copy_workflows
awk '/^  pull_request:$/ { print; print "    paths:"; print "      - mobile/eas.json"; next } { print }' "$tmp/.github/workflows/e2e-host.yml" > "$tmp/e2e-host.yml"
mv "$tmp/e2e-host.yml" "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'pull request guard must be unconditional'

copy_workflows
awk '/^  pull_request:$/ { print; print "    paths-ignore:"; print "      - docs/**"; next } { print }' "$tmp/.github/workflows/e2e-host.yml" > "$tmp/e2e-host.yml"
mv "$tmp/e2e-host.yml" "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'pull request guard must reject paths-ignore'

for release_input in mobile/eas.json mobile/package.json mobile/package-lock.json; do
  copy_workflows
  sed -i.bak "\\#$release_input#d" "$tmp/.github/workflows/e2e-host.yml"
  expect_rejection "E2E host push paths omit $release_input"
done

copy_workflows
sed -i.bak 's/contents: read/contents: write/' "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'write permission'

copy_workflows
awk '/contents: read/ { print; print "  actions: write"; next } { print }' "$tmp/.github/workflows/e2e-host.yml" > "$tmp/e2e-host.yml"
mv "$tmp/e2e-host.yml" "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'additional root permission'

copy_workflows
awk '/contents: read/ { print; print "  id-token: write"; next } { print }' "$tmp/.github/workflows/e2e-host.yml" > "$tmp/e2e-host.yml"
mv "$tmp/e2e-host.yml" "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'id-token write permission'

copy_workflows
awk '/contents: read/ { print; print "  packages: write"; next } { print }' "$tmp/.github/workflows/e2e-host.yml" > "$tmp/e2e-host.yml"
mv "$tmp/e2e-host.yml" "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'packages write permission'

copy_workflows
printf '\n  rogue-job:\n    permissions:\n      contents: read\n' >> "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'nested permission block'

copy_workflows
sed -i.bak 's#actions/checkout@[0-9a-f]\{40\}#actions/checkout@v4#' "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'unpinned action'

copy_workflows
printf '\n      - run: adb devices\n' >> "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'device command'

copy_workflows
printf '\n      - run: |\n          adb devices\n' >> "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'multiline device command'

copy_workflows
printf '\n      - run: |\n          printf "$(adb)"\n' >> "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'subshell device command'

copy_workflows
printf "\n      - run: |\n          \$('a'db) devices\n" >> "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'quoted-shell device command'

copy_workflows
printf '\n      - run: printf safe\n' >> "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'additional benign-looking run command'

expect_acceptance() {
  local label=$1
  if ! TWINOTIFY_HOST_WORKFLOW_ROOT="$tmp" "$VERIFY" >/dev/null 2>"$tmp/error"; then
    echo "self-test expected acceptance: $label" >&2
    cat "$tmp/error" >&2
    exit 1
  fi
}

copy_workflows
sed -i.bak 's/Run E2E Go race tests/ADB wording is not a command/' "$tmp/.github/workflows/e2e-host.yml"
printf '\n      # adb devices is intentionally mentioned in a comment\n' >> "$tmp/.github/workflows/e2e-host.yml"
expect_acceptance 'comments and labels may mention prohibited tools'

copy_workflows
sed -i.bak '/verify-host-workflows_test\.sh/d' "$tmp/Makefile"
expect_rejection 'host Make target omits verifier self-test'

copy_workflows
sed -i.bak '/lan_product_target_test\.sh/d' "$tmp/Makefile"
expect_rejection 'host Make target omits LAN product contract test'

copy_workflows
sed -i.bak '/lan_product_target_test\.sh/d' "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'E2E host workflow omits LAN product contract test'

copy_workflows
awk '/^[[:space:]]*\.\/e2e\/scripts\/lan_product_target_test\.sh$/ { print; print; next } { print }' "$tmp/Makefile" > "$tmp/Makefile.mutated"
mv "$tmp/Makefile.mutated" "$tmp/Makefile"
expect_rejection 'host LAN product contract test must not be duplicated'

copy_workflows
awk '/^[[:space:]]*\.\/e2e\/scripts\/lan_product_target_test\.sh$/ { print "\t# ./e2e/scripts/lan_product_target_test.sh"; next } { print }' "$tmp/Makefile" > "$tmp/Makefile.mutated"
mv "$tmp/Makefile.mutated" "$tmp/Makefile"
expect_rejection 'commented LAN product contract test cannot satisfy host gate'

copy_workflows
sed -i.bak '/verify-host-workflows_test\.sh/d' "$tmp/.github/workflows/e2e-host.yml"
expect_rejection 'E2E host workflow omits verifier self-test'

copy_workflows
awk '/^[[:space:]]*\.\/scripts\/verify-host-workflows_test\.sh$/ { print "\t# ./scripts/verify-host-workflows_test.sh"; next } { print }' "$tmp/Makefile" > "$tmp/Makefile.mutated"
mv "$tmp/Makefile.mutated" "$tmp/Makefile"
expect_rejection 'tabbed Make comment cannot satisfy verifier self-test command'

copy_workflows
awk '
  $0 == "\tcd mobile && npm run typecheck" { typecheck = $0; next }
  $0 == "\tcd mobile && npm test -- --runInBand" { print; print typecheck; next }
  { print }
' "$tmp/Makefile" > "$tmp/Makefile.mutated"
mv "$tmp/Makefile.mutated" "$tmp/Makefile"
expect_rejection 'host verification commands must retain fail-fast order'

copy_workflows
awk '/^[[:space:]]*\.\/scripts\/verify-host-workflows\.sh$/ { print; print; next } { print }' "$tmp/Makefile" > "$tmp/Makefile.mutated"
mv "$tmp/Makefile.mutated" "$tmp/Makefile"
expect_rejection 'host verification command must not be duplicated'

copy_workflows
sed -i.bak '/cd mobile && npm test -- --runInBand/d' "$tmp/Makefile"
expect_rejection 'host verification must include mobile Jest'

copy_workflows
awk '
  $0 == "\tcd mobile && npm run typecheck" { typecheck = $0; next }
  $0 == "\tcd mobile && npm test -- --runInBand" { print; print typecheck; next }
  { print }
' "$tmp/Makefile" > "$tmp/Makefile.mutated"
mv "$tmp/Makefile.mutated" "$tmp/Makefile"
expect_rejection 'mobile-verify must run Jest after typecheck'

copy_workflows
sed -i.bak 's/ compileDebugAndroidTestKotlin//' "$tmp/Makefile"
expect_rejection 'mobile-verify must compile Android instrumentation sources'

copy_workflows
sed -i.bak 's/compileDebugAndroidTestKotlin assembleDebug/assembleDebug compileDebugAndroidTestKotlin/' "$tmp/Makefile"
expect_rejection 'mobile-verify instrumentation compilation must precede APK assembly'

copy_workflows
awk '
  /compileDebugAndroidTestKotlin assembleDebug$/ {
    sub(/ compileDebugAndroidTestKotlin assembleDebug$/, " assembleDebug")
    print
    print "\tcd mobile/android && ./gradlew --no-daemon compileDebugAndroidTestKotlin"
    next
  }
  { print }
' "$tmp/Makefile" > "$tmp/Makefile.mutated"
mv "$tmp/Makefile.mutated" "$tmp/Makefile"
expect_rejection 'mobile-verify must use one canonical native Gradle command'

copy_workflows
sed -i.bak 's/ compileDebugAndroidTestKotlin//' "$tmp/.github/workflows/mobile.yml"
expect_rejection 'native Android CI must compile instrumentation sources'

copy_workflows
awk '
  /compileDebugAndroidTestKotlin assembleDebug$/ {
    sub(/ compileDebugAndroidTestKotlin/, "")
    print
    print "      # compileDebugAndroidTestKotlin"
    next
  }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'commented instrumentation task cannot satisfy native Android CI'

copy_workflows
sed -i.bak 's/ compileDebugAndroidTestKotlin//' "$tmp/.github/workflows/mobile.yml"
cat >> "$tmp/.github/workflows/mobile.yml" <<'EOF'

  instrumentation-decoy:
    runs-on: ubuntu-latest
    steps:
      - run: ./gradlew --no-daemon lintDebug testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug
EOF
expect_rejection 'instrumentation compilation in another job cannot satisfy native Android CI'

copy_workflows
awk '/^[[:space:]]*- run: .*gradlew --no-daemon lintDebug testDebugUnitTest/ { print; print; next } { print }' \
  "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'native Android CI must not duplicate the canonical Gradle command'

copy_workflows
awk '
  $0 == "  native-android:" { print; print "    if: false"; next }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'conditional native Android job cannot satisfy the compile gate'

copy_workflows
awk '
  $0 == "  native-android:" { print; print "    continue-on-error: true"; next }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'skippable native Android job cannot satisfy the compile gate'

copy_workflows
awk '
  $0 == "  native-android:" { print; print "    \"if\" : false"; next }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'quoted conditional native Android job cannot satisfy the compile gate'

copy_workflows
awk '
  $0 == "  native-android:" { print; print "    \047continue-on-error\047 : true"; next }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'quoted skippable native Android job cannot satisfy the compile gate'

copy_workflows
awk '
  /compileDebugAndroidTestKotlin assembleDebug$/ { print; print "        continue-on-error: true"; next }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'skippable native Android Gradle step cannot satisfy the compile gate'

copy_workflows
awk '
  /compileDebugAndroidTestKotlin assembleDebug$/ {
    command = $0
    sub(/^[[:space:]]*- run:[[:space:]]*/, "", command)
    print "      - if: false"
    print "        run: " command
    next
  }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'leading conditional key cannot make the native Gradle step optional'

copy_workflows
awk '
  /compileDebugAndroidTestKotlin assembleDebug$/ {
    command = $0
    sub(/^[[:space:]]*- run:[[:space:]]*/, "", command)
    print "      - continue-on-error: true"
    print "        run: " command
    next
  }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'leading continue-on-error key cannot make the native Gradle step optional'

copy_workflows
awk '
  /compileDebugAndroidTestKotlin assembleDebug$/ {
    command = $0
    sub(/^[[:space:]]*- run:[[:space:]]*/, "", command)
    print "      - \"if\" : false"
    print "        run: " command
    next
  }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'quoted leading conditional key cannot make the native Gradle step optional'

copy_workflows
awk '
  /compileDebugAndroidTestKotlin assembleDebug$/ {
    command = $0
    sub(/^[[:space:]]*- run:[[:space:]]*/, "", command)
    print "      - \047continue-on-error\047 : true"
    print "        run: " command
    next
  }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'quoted leading continue-on-error key cannot make the native Gradle step optional'

copy_workflows
awk '
  /compileDebugAndroidTestKotlin assembleDebug$/ { print; print "        \"if\" : false"; next }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'quoted subsequent conditional key cannot make the native Gradle step optional'

copy_workflows
awk '
  /compileDebugAndroidTestKotlin assembleDebug$/ { print; print "        \047continue-on-error\047 : true"; next }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'quoted subsequent continue-on-error key cannot make the native Gradle step optional'

copy_workflows
awk '
  /compileDebugAndroidTestKotlin assembleDebug$/ {
    print
    print "      - run: |"
    print "          printf extra-command"
    next
  }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'extra multiline native Android run command must be rejected'

copy_workflows
awk '
  /compileDebugAndroidTestKotlin assembleDebug$/ {
    print
    print "      - \"run\" : printf hidden-extra-command"
    next
  }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'quoted scalar run key cannot hide an extra native Android command'

copy_workflows
awk '
  /compileDebugAndroidTestKotlin assembleDebug$/ {
    print
    print "      - run : printf hidden-extra-command"
    next
  }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'spaced scalar run key cannot hide an extra native Android command'

copy_workflows
awk '
  /compileDebugAndroidTestKotlin assembleDebug$/ {
    print
    print "      - \"run\" : |"
    print "          printf hidden-extra-command"
    next
  }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'quoted block run key cannot hide an extra native Android command'

copy_workflows
awk '
  /compileDebugAndroidTestKotlin assembleDebug$/ {
    print
    print "      - run : |"
    print "          printf hidden-extra-command"
    next
  }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'spaced block run key cannot hide an extra native Android command'

copy_workflows
awk '
  $0 == "        working-directory: mobile" {
    print
    print "        \"shell\" : bash {0}"
    next
  }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'native Android defaults must not override the shell'

copy_workflows
awk '
  /compileDebugAndroidTestKotlin assembleDebug$/ {
    print
    print "        \047shell\047 : bash {0}"
    next
  }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'required native Android steps must not override the shell'

copy_workflows
sed -i.bak 's/working-directory: mobile$/working-directory: fake-mobile/' "$tmp/.github/workflows/mobile.yml"
expect_rejection 'native Android default working directory must be mobile'

copy_workflows
awk '
  $0 == "        working-directory: mobile" {
    print
    print "        \"working-directory\" : fake-mobile"
    next
  }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'native Android default working directory must not be duplicated'

copy_workflows
sed -i.bak '/^[[:space:]]*working-directory: \.$/d' "$tmp/.github/workflows/mobile.yml"
expect_rejection 'native Android proto sync must run from the repository root'

copy_workflows
sed -i.bak 's/working-directory: \.$/"working-directory" : fake-root/' "$tmp/.github/workflows/mobile.yml"
expect_rejection 'native Android proto sync must reject a conflicting working directory'

copy_workflows
sed -i.bak 's/working-directory: mobile\/android$/working-directory : fake-android/' "$tmp/.github/workflows/mobile.yml"
expect_rejection 'native Android Gradle gate must run from mobile/android'

copy_workflows
awk '
  $0 == "        working-directory: mobile/android" {
    print
    print "        \047working-directory\047 : fake-android"
    next
  }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'native Android Gradle working directory must not be duplicated'

copy_workflows
awk '
  $0 == "        working-directory: mobile/android" {
    print
    print "      - { run: printf hidden-extra-command }"
    next
  }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'flow-style native Android steps must not bypass the run inventory'

copy_workflows
awk '
  $0 == "        working-directory: mobile/android" {
    print
    print "      - &hidden run: printf hidden-extra-command"
    next
  }
  { print }
' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
expect_rejection 'anchored native Android steps must not bypass the run inventory'

expect_native_continuation_rejection() {
  local mutation=$1
  local label=$2
  copy_workflows
  awk -v mutation="$mutation" '
    $0 == "        working-directory: mobile/android" {
      print
      print mutation
      next
    }
    { print }
  ' "$tmp/.github/workflows/mobile.yml" > "$tmp/mobile.yml"
  mv "$tmp/mobile.yml" "$tmp/.github/workflows/mobile.yml"
  expect_rejection "$label"
}

expect_native_continuation_rejection \
  '        &skip_native if: false' \
  'anchored continuation if cannot make the native Gradle step conditional'
expect_native_continuation_rejection \
  '        &skip_native continue-on-error: true' \
  'anchored continuation continue-on-error cannot make the native Gradle step skippable'
expect_native_continuation_rejection \
  '        &hidden shell: bash {0}' \
  'anchored continuation shell cannot mask native Gradle failure'
expect_native_continuation_rejection \
  '        &hidden working-directory: fake-android' \
  'anchored continuation working-directory cannot redirect native Gradle'
expect_native_continuation_rejection \
  '        &hidden run: printf hidden-extra-command' \
  'anchored continuation run cannot bypass the exact command inventory'
expect_native_continuation_rejection \
  '        !unsafe if: false' \
  'tagged continuation keys are outside the supported native-job YAML subset'

[[ "$missing_rejections" -eq 0 ]] || exit 1

echo "host workflow verifier self-test passed"
