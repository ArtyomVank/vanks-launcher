#!/usr/bin/env bash
# Сборка Vanks Launcher без Gradle: aapt2 → javac → d8 → zipalign → apksigner.
# Нужны build-tools и platform из Android SDK (ANDROID_HOME, по умолчанию ~/Android/Sdk).
# Подпись — отладочным ключом keystore/debug.jks (создаётся сам).
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/36.1.0"
JAR="$SDK/platforms/android-36/android.jar"
B=build
rm -rf "$B"; mkdir -p "$B/res" "$B/gen" "$B/classes" "$B/dex"

"$BT/aapt2" compile --dir res -o "$B/res/"
"$BT/aapt2" link -o "$B/base.apk" -I "$JAR" --manifest AndroidManifest.xml \
    --java "$B/gen" --min-sdk-version 28 --target-sdk-version 36 \
    --version-code 1 --version-name 0.1 "$B"/res/*.flat

javac --release 17 -encoding UTF-8 -Xlint:-options -nowarn -classpath "$JAR" -d "$B/classes" \
    $(find src "$B/gen" -name '*.java')
"$BT/d8" --release --min-api 28 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')

cp "$B/base.apk" "$B/unsigned.apk"
python3 -I -c "import sys,zipfile; zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED).write(sys.argv[2],'classes.dex')" "$B/unsigned.apk" "$B/dex/classes.dex"
"$BT/zipalign" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"

mkdir -p keystore
[ -f keystore/debug.jks ] || keytool -genkeypair -keystore keystore/debug.jks -storepass android -keypass android \
    -alias vanks -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Vanks Launcher debug" >/dev/null 2>&1
"$BT/apksigner" sign --ks keystore/debug.jks --ks-pass pass:android --key-pass pass:android \
    --out "$B/vanks-launcher.apk" "$B/aligned.apk"
"$BT/apksigner" verify "$B/vanks-launcher.apk"
ls -la "$B/vanks-launcher.apk"
