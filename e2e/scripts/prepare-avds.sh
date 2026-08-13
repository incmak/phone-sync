#!/usr/bin/env bash
set -Eeuo pipefail

ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
IMAGE="system-images;android-35;google_apis;x86_64"
AVD_A=${E2E_AVD_A:-twinotify-api35-a}
AVD_B=${E2E_AVD_B:-twinotify-api35-b}

die() { echo "prepare-avds: $*" >&2; exit 1; }
command -v sdkmanager >/dev/null 2>&1 || die "sdkmanager is required"
command -v avdmanager >/dev/null 2>&1 || die "avdmanager is required"
[[ -n "$ANDROID_SDK_ROOT" && -d "$ANDROID_SDK_ROOT" ]] || die "ANDROID_SDK_ROOT must point to an Android SDK"

sdkmanager "platform-tools" "emulator" "platforms;android-35" "$IMAGE"
printf 'no\n' | avdmanager create avd --force --name "$AVD_A" --package "$IMAGE" --device pixel_8
printf 'no\n' | avdmanager create avd --force --name "$AVD_B" --package "$IMAGE" --device pixel_8
avdmanager list avd | grep -Fq "Name: $AVD_A" || die "created AVD $AVD_A was not listed"
avdmanager list avd | grep -Fq "Name: $AVD_B" || die "created AVD $AVD_B was not listed"
echo "prepare-avds: ready $AVD_A and $AVD_B"
