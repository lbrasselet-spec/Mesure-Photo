#!/usr/bin/env bash
# Compilation de l'APK « Mesure Photo », sans Android Studio ni Gradle.
# Outils (Ubuntu / Debian) :
#   apt install aapt android-sdk-platform-23 dalvik-exchange zipalign apksigner default-jdk python3-pil
# Usage : ./build.sh  ->  MesurePhoto-v<version>.apk dans ce dossier
# Variables facultatives (utilisées par la compilation automatique GitHub) :
#   PAGE=chemin/index.html  VERSION_CODE=12  VERSION_NAME=1.0.11  APK_NAME=Mesure-Photo.apk
set -euo pipefail
cd "$(dirname "$0")"

SDK=/usr/lib/android-sdk/platforms/android-23/android.jar   # bibliothèque Android de compilation
KS=keystore/mesurephoto.p12                                 # clé de signature : TOUJOURS la même pour les mises à jour
KS_PASS=mesurephoto
VERSION=${VERSION_NAME:-$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)}
PAGE=${PAGE:-assets/index.html}                             # page de l'appli (dépôt GitHub : ../index.html)
OUT=build
APK=${APK_NAME:-MesurePhoto-v${VERSION}.apk}

rm -rf "$OUT"; mkdir -p "$OUT/stubs" "$OUT/classes" "$OUT/dex" "$OUT/assets"
cp "$PAGE" "$OUT/assets/index.html"
sed -i "s/const VERSION = '[^']*';/const VERSION = '${VERSION}';/" "$OUT/assets/index.html"   # n° affiché à l'accueil

# 1) icônes
python3 tools/make_icons.py res

# 2) clé de signature (créée une seule fois, à conserver)
if [ ! -f "$KS" ]; then
  mkdir -p keystore
  keytool -genkeypair -keystore "$KS" -storetype PKCS12 -storepass "$KS_PASS" -alias mesurephoto \
    -keyalg RSA -keysize 2048 -validity 12000 -dname "CN=Mesure Photo, O=Laurent Brasselet, C=FR"
fi

# 3) manifeste + ressources + page (assets) ; resources.arsc non compressé (exigé depuis Android 11)
VER=()
if [ -n "${VERSION_CODE:-}" ]; then VER=(--version-code "$VERSION_CODE" --version-name "$VERSION" --replace-version); fi
aapt package -f -M AndroidManifest.xml -S res -A "$OUT/assets" -I "$SDK" -F "$OUT/app.unsigned.apk" -0 arsc "${VER[@]}"

# 4) Java -> classes (le talon RenderProcessGoneDetail ne sert qu'à compiler, il n'entre pas dans l'APK)
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$SDK" -d "$OUT/stubs" stubs/android/webkit/*.java
javac -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$SDK" -classpath "$OUT/stubs" \
  -d "$OUT/classes" src/fr/brasselet/mesurephoto/*.java

# 5) classes -> classes.dex, ajouté à l'APK
dalvik-exchange --dex --output="$OUT/dex/classes.dex" "$OUT/classes"
(cd "$OUT/dex" && aapt add -f ../app.unsigned.apk classes.dex > /dev/null)

# 6) alignement puis signature (schémas v2 et v3)
zipalign -f -p 4 "$OUT/app.unsigned.apk" "$OUT/app.aligned.apk"
apksigner sign --v2-signing-enabled true --v3-signing-enabled true --ks "$KS" --ks-pass "pass:$KS_PASS" --ks-key-alias mesurephoto --out "$APK" "$OUT/app.aligned.apk"
apksigner verify "$APK"
echo "APK prêt : $APK ($(du -h "$APK" | cut -f1))"
