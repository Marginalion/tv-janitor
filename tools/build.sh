#!/usr/bin/env bash
# Build TV Janitor. No binaries live in this repo: the bundled launcher is built from CleanYourTV's
# source at a pinned commit, with patches/cleanyourtv-launcher.patch, then packed into the app; the full edition
# also carries LeanKey, a light remote-friendly keyboard, built from its source at a pinned commit.
# Needs JDK 17+ and the Android SDK (platforms 34 and 35, build-tools 34 and 35) in ANDROID_HOME.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
UPSTREAM=https://github.com/Gathin23/cleanyourtv.git
PIN=86a69372f9886ab6392ad2c3cf95f9a0e1c54644
LEANKEY=https://github.com/yuliskov/LeanKeyKeyboard.git
LEANKEY_PIN=abe39bd25889ea9006402273b36db9bdf38c05e4
BT="${ANDROID_HOME:?set ANDROID_HOME}/build-tools/35.0.0"
NOLINT="-x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease"

# Bundled APKs are signed with the release key when keystore.properties is here, so a build from any machine
# installs over the one already on the TV (a debug key differs from machine to machine).
sign() {
    local in=$1 out=$2
    if [ -f keystore.properties ]; then
        local store pass alias kpass
        store=$(sed -n 's/^storeFile=//p' keystore.properties); [ -f "app/$store" ] && store="app/$store"
        pass=$(sed -n 's/^storePassword=//p' keystore.properties)
        alias=$(sed -n 's/^keyAlias=//p' keystore.properties)
        kpass=$(sed -n 's/^keyPassword=//p' keystore.properties)
        "$BT/zipalign" -f -p 4 "$in" "$out.tmp"
        "$BT/apksigner" sign --ks "$store" --ks-pass "pass:$pass" --ks-key-alias "$alias" --key-pass "pass:$kpass" --out "$out" "$out.tmp"
        rm -f "$out.tmp" "$out.idsig"
    else
        cp "$in" "$out"
    fi
}

# Maven Central sometimes answers "too many requests": try a few times.
gradle() { local dir=$1; shift; for i in 1 2 3 4 5; do (cd "$dir" && bash ./gradlew "$@" -q --console=plain) && return 0; sleep 30; done; return 1; }

WORK=build/cleanyourtv
if [ ! -d "$WORK/.git" ]; then git clone --filter=blob:none "$UPSTREAM" "$WORK"; fi
git -C "$WORK" fetch -q origin "$PIN" || true
git -C "$WORK" checkout -q -f "$PIN"
git -C "$WORK" apply "$ROOT/patches/cleanyourtv-launcher.patch"
echo "sdk.dir=$ANDROID_HOME" > "$WORK/launcher/local.properties"
gradle "$WORK/launcher" assembleRelease $NOLINT
sign "$WORK/launcher/app/build/outputs/apk/release/app-release.apk" app/src/main/assets/tvhome.apk

KEY=build/leankey
if [ ! -d "$KEY/.git" ]; then git clone --filter=blob:none "$LEANKEY" "$KEY"; fi
git -C "$KEY" fetch -q origin "$LEANKEY_PIN" || true
git -C "$KEY" checkout -q -f "$LEANKEY_PIN"
echo "sdk.dir=$ANDROID_HOME" > "$KEY/local.properties"
gradle "$KEY" assembleOriginRelease -x lintVitalAnalyzeOriginRelease -x lintVitalReportOriginRelease -x lintVitalOriginRelease
mkdir -p app/src/full/assets
sign "$KEY"/leankeykeyboard/build/outputs/apk/origin/release/LeanKeyboard_*_origin_r.apk app/src/full/assets/leankey.apk

echo "sdk.dir=$ANDROID_HOME" > local.properties
gradle . assembleRelease
echo "Built: app/build/outputs/apk/full/release/app-full-release.apk"
echo "       app/build/outputs/apk/lite/release/app-lite-release.apk"
