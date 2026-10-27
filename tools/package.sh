#!/usr/bin/env bash
# Builds Oriel, signs the apps and puts the installer folder together as dist/oriel-<version>.zip.
# Needs ANDROID_HOME, and ORIEL_KEYSTORE + ORIEL_KEYPASS_FILE pointing at the release key (never in this repo).
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
: "${ORIEL_KEYSTORE:?set ORIEL_KEYSTORE to the release keystore}"
: "${ORIEL_KEYPASS_FILE:?set ORIEL_KEYPASS_FILE to the file holding its password}"
abis_arg=()
if [ -n "${ORIEL_ABIS:-}" ]; then abis_arg=("-Poriel.abis=$ORIEL_ABIS"); fi

./gradlew --no-daemon --max-workers="${ORIEL_WORKERS:-2}" "${abis_arg[@]}" \
  :home:assembleStandardRelease :home:assembleHikeenRelease :mirror:assembleRelease

version=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' home/build.gradle.kts | head -1)
bt=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
out="dist/oriel-$version"
rm -rf "$out" "$out.zip"
mkdir -p "$out"

sign() {
  "$bt/zipalign" -f -P 16 4 "$1" "$out/.aligned.apk"
  "$bt/apksigner" sign --ks "$ORIEL_KEYSTORE" --ks-pass "file:$ORIEL_KEYPASS_FILE" --out "$out/$2" "$out/.aligned.apk"
  rm -f "$out/.aligned.apk" "$out/$2.idsig"
  "$bt/apksigner" verify "$out/$2"
}
sign home/build/outputs/apk/standard/release/home-standard-release-unsigned.apk oriel-home.apk
sign home/build/outputs/apk/hikeen/release/home-hikeen-release-unsigned.apk oriel-home-hikeen.apk
sign mirror/build/outputs/apk/release/mirror-release-unsigned.apk oriel-mirror.apk

# Boot screens: drawn from the Geist font the home app ships (needs python3 with Pillow, and ffmpeg).
python3 installer/boot/make_boot.py home/src/main/res/font/geist_semibold.ttf "$out/.boot"
mv "$out/.boot/bootanimation.zip" "$out/oriel-bootanimation.zip"
mv "$out/.boot/bootvideo.mp4" "$out/oriel-bootvideo.mp4"
mv "$out/.boot/logo.jpg" "$out/oriel-logo.jpg"
rm -rf "$out/.boot"

# Files for Install Oriel System: the boot watchdog and helpers, Oriel's system permissions, and the overlay
# with Geist that gives Android's own dialogs Oriel's look.
mkdir -p "$out/system"
cp installer/system/oriel-watchdog.rc installer/system/oriel-watchdog.sh installer/system/oriel-remove.sh \
  installer/system/oriel-add.sh installer/system/oriel-factory.sh installer/system/privapp-permissions-oriel.xml "$out/system/"
android_jar=$(ls -d "$ANDROID_HOME"/platforms/android-* | sort -V | tail -1)/android.jar
"$bt/aapt2" compile --dir system-overlay/res -o "$out/.overlay-res.zip"
"$bt/aapt2" link -o "$out/.overlay.apk" --manifest system-overlay/AndroidManifest.xml -I "$android_jar" \
  --min-sdk-version 31 --target-sdk-version 31 "$out/.overlay-res.zip"
rm -f "$out/.overlay-res.zip"
sign "$out/.overlay.apk" system/OrielFramework.apk
rm -f "$out/.overlay.apk"
cp system-overlay/fonts_customization.xml "$out/system/"
cp home/src/main/res/font/geist_regular.ttf "$out/system/Geist-Regular.ttf"
cp home/src/main/res/font/geist_medium.ttf "$out/system/Geist-Medium.ttf"
cp home/src/main/res/font/geist_semibold.ttf "$out/system/Geist-SemiBold.ttf"
cp home/src/main/res/font/geist_bold.ttf "$out/system/Geist-Bold.ttf"

cp installer/oriel.ps1 installer/tvkeeper.sh "installer/Install Oriel.bat" "installer/Remove Oriel.bat" \
  "installer/Install Oriel System.bat" "installer/Restore Factory.bat" LICENSE "$out/"
(cd dist && zip -qr "oriel-$version.zip" "oriel-$version")
echo "Built dist/oriel-$version.zip"
