#!/usr/bin/env bash
# Build TV Janitor. No binaries live in this repo: the bundled launcher is built from CleanYourTV's
# source at a pinned commit, with patches/cleanyourtv-launcher.patch, then packed into the app.
# Needs JDK 17+ and the Android SDK (platform 34, build-tools 34) in ANDROID_HOME.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
UPSTREAM=https://github.com/Gathin23/cleanyourtv.git
PIN=86a69372f9886ab6392ad2c3cf95f9a0e1c54644
WORK=build/cleanyourtv
if [ ! -d "$WORK/.git" ]; then git clone --filter=blob:none "$UPSTREAM" "$WORK"; fi
git -C "$WORK" fetch -q origin "$PIN" || true
git -C "$WORK" checkout -q -f "$PIN"
git -C "$WORK" apply "$ROOT/patches/cleanyourtv-launcher.patch"
echo "sdk.dir=${ANDROID_HOME:?set ANDROID_HOME}" > "$WORK/launcher/local.properties"
# Maven Central sometimes answers "too many requests": try a few times.
for i in 1 2 3; do (cd "$WORK/launcher" && ./gradlew assembleRelease -q --console=plain) && break; [ $i = 3 ] && exit 1; sleep 30; done
cp "$WORK/launcher/app/build/outputs/apk/release/app-release.apk" app/src/main/assets/tvhome.apk
echo "sdk.dir=$ANDROID_HOME" > local.properties
for i in 1 2 3; do ./gradlew assembleRelease -q --console=plain && break; [ $i = 3 ] && exit 1; sleep 30; done
echo "Built: app/build/outputs/apk/full/release/app-full-release.apk"
echo "       app/build/outputs/apk/lite/release/app-lite-release.apk"
