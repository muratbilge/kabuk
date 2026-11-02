#!/usr/bin/env bash
# Build the release APK, then align + sign it with the debug key and VERIFY the signature.
# (Signing explicitly with apksigner: an unsigned APK fails on the phone with "package error".)
#   scripts/release.sh   -> ~/apks/kabuk/kabuk-<version>.apk, and for the in-app updater (Settings -> Check for update):
#                           ~/apk-share/kabuk/kabuk-<versionCode>.apk + signed update.json, served by the relay server at
#                           http://192.168.178.11:8000/apk/kabuk/ . NOTES="..." sets the release notes (default: last
#                           commit subject). PUBLISH=0 skips the apk-share step. The APK itself is never handed out as a
#                           link: the app's own updater downloads and verifies it (key, SHA-256, signer).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
BT=${BUILD_TOOLS:-$HOME/Android/Sdk/build-tools/35.0.0}
cd "$ROOT"
./gradlew --console=plain :app:assembleRelease -q
IN=$(ls app/build/outputs/apk/release/kabuk-*-release.apk | head -1)
VER=$(basename "$IN" | sed -E 's/kabuk-(.*)-release.apk/\1/')
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
"$BT/zipalign" -f -p 4 "$IN" "$TMP/aligned.apk"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$TMP/kabuk.apk" "$TMP/aligned.apk"
"$BT/apksigner" verify "$TMP/kabuk.apk"
mkdir -p "$HOME/apks/kabuk"
cp "$TMP/kabuk.apk" "$HOME/apks/kabuk/kabuk-$VER.apk"
echo "OK: $HOME/apks/kabuk/kabuk-$VER.apk (signature verified)"

if [ "${PUBLISH:-1}" != 0 ]; then
  python3 "$ROOT/scripts/publish_update.py" "$TMP/kabuk.apk" "${APK_SHARE:-$HOME/apk-share}/kabuk" \
    --notes "${NOTES:-$(git -C "$ROOT" log -1 --format=%s)}"
  echo "Next: Kabuk -> Settings -> Check for update."
fi
