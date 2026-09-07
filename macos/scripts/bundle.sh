#!/usr/bin/env bash
set -Eeuo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SIGNING_IDENTITY=""
E2E=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --sign) [[ $# -ge 2 && -n $2 ]] || { echo 'Signing identity required' >&2; exit 2; }; SIGNING_IDENTITY=$2; shift 2 ;;
    --e2e) E2E=true; shift ;;
    *) echo 'Usage: bundle.sh [--sign IDENTITY] [--e2e]' >&2; exit 2 ;;
  esac
done
[[ $(uname -s) == Darwin && $(uname -m) == arm64 ]] || { echo 'macOS arm64 is required' >&2; exit 2; }
python3 "$ROOT/macos/scripts/sync-schemas.py"
BUILD_ARGS=(--package-path "$ROOT/macos" -c release --arch arm64 --disable-automatic-resolution)
BUNDLE_NAME=Twinotify
BUNDLE_ID=co.twinotify.mac
if $E2E; then
  BUILD_ARGS+=(--scratch-path "$ROOT/macos/.build-e2e" -Xswiftc -DTWINOTIFY_E2E)
  BUNDLE_NAME=TwinotifyE2E
  BUNDLE_ID=co.twinotify.mac.e2e
fi
swift build "${BUILD_ARGS[@]}"
BIN=$(swift build "${BUILD_ARGS[@]}" --show-bin-path)
mkdir -p "$ROOT/macos/dist"
STAGING=$(mktemp -d "$ROOT/macos/dist/.bundle.XXXXXX")
APP="$STAGING/$BUNDLE_NAME.app"
ICONSET=""
trap 'rm -rf -- "$STAGING"; [[ -z $ICONSET ]] || rm -rf -- "$ICONSET"' EXIT
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN/TwinotifyMac" "$APP/Contents/MacOS/TwinotifyMac"
ICONSET=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-icon.XXXXXX")
mkdir -p "$ICONSET/AppIcon.iconset"
for size in 16 32 128 256 512; do
  sips -z "$size" "$size" "$ROOT/mobile/assets/brand/icon.png" --out "$ICONSET/AppIcon.iconset/icon_${size}x${size}.png" >/dev/null
  double=$((size * 2))
  sips -z "$double" "$double" "$ROOT/mobile/assets/brand/icon.png" --out "$ICONSET/AppIcon.iconset/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET/AppIcon.iconset" -o "$APP/Contents/Resources/AppIcon.icns"
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>co.twinotify.mac</string>
<key>CFBundleName</key><string>Twinotify</string>
<key>CFBundleDisplayName</key><string>Twinotify</string>
<key>CFBundleExecutable</key><string>TwinotifyMac</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>0.1.0</string>
<key>CFBundleVersion</key><string>1</string>
<key>CFBundleIconFile</key><string>AppIcon</string>
<key>LSMinimumSystemVersion</key><string>26.0</string>
<key>LSUIElement</key><true/>
<key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST
plutil -replace CFBundleIdentifier -string "$BUNDLE_ID" "$APP/Contents/Info.plist"
plutil -replace CFBundleName -string "$BUNDLE_NAME" "$APP/Contents/Info.plist"
plutil -replace CFBundleDisplayName -string "$BUNDLE_NAME" "$APP/Contents/Info.plist"
plutil -lint "$APP/Contents/Info.plist"
if [[ -n $SIGNING_IDENTITY ]]; then
  codesign --force --options runtime --timestamp=none --sign "$SIGNING_IDENTITY" "$APP"
  codesign --verify --strict "$APP"
else
  echo 'Bundle built without a selected signing identity. Signed notification acceptance remains pending.'
fi
# Replace only the generated bundle after a successful build/signature check.
rm -rf -- "$ROOT/macos/dist/$BUNDLE_NAME.app"
mv "$APP" "$ROOT/macos/dist/$BUNDLE_NAME.app"
printf '%s\n' "$ROOT/macos/dist/$BUNDLE_NAME.app"
