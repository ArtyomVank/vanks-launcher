#!/usr/bin/env bash
# Выпустить новую версию «по воздуху»: собрать APK и выложить релизом на GitHub.
# Перед этим поднимите versionCode и versionName в AndroidManifest.xml.
#     tools/release.sh "Что нового: …"
# Телефоны с Vanks увидят релиз сами (раз в 12 часов) или по «Проверить
# обновления». APK должен быть подписан тем же ключом (keystore/debug.jks),
# что и стоящий у людей Vanks, — иначе Android не поставит его поверх.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=ArtyomVank/vanks-launcher
VER=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -1)
[ -n "$VER" ] || { echo "нет versionName в AndroidManifest.xml" >&2; exit 1; }
if gh release view "v$VER" --repo "$REPO" >/dev/null 2>&1; then
    echo "v$VER уже выпущена — поднимите версию в AndroidManifest.xml" >&2
    exit 1
fi
./build.sh
cp build/vanks-launcher.apk "build/vanks-launcher-$VER.apk"
gh release create "v$VER" "build/vanks-launcher-$VER.apk" --repo "$REPO" \
    --title "Vanks $VER" --notes "${1:-Vanks $VER}"
