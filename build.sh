#!/bin/bash
# Build manual dua APK karaoke tanpa Gradle.
# Gradle tidak bisa jalan di VM ini: sandbox memblokir koneksi TCP antar
# proses Java, dan daemon Gradle butuh koneksi itu. Pipeline di sini
# memakai tool Android langsung: aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -e
KARAOKE="$HOME/workspace/karaoke"
SDK="$HOME/.local/android-sdk"
JDK="$HOME/.local/jdk17"
AAPT2="$SDK/build-tools/34.0.0/aapt2"
D8="$SDK/build-tools/34.0.0/d8"
ZIPALIGN="$SDK/build-tools/34.0.0/zipalign"
APKSIGNER="$SDK/build-tools/34.0.0/apksigner"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
KEYSTORE="$KARAOKE/debug.keystore"
LIBS="$KARAOKE/libs"
export PATH="$JDK/bin:$PATH"
mkdir -p "$KARAOKE/apks"

if [ ! -f "$KEYSTORE" ]; then
    "$JDK/bin/keytool" -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=Android Debug, O=Android, C=US" > /dev/null 2>&1
    echo "keystore debug dibuat"
fi

build_app() {
    local app="$1"; shift
    local src="$KARAOKE/$app/src/main"
    local work="$KARAOKE/build-$app"
    rm -rf "$work"
    mkdir -p "$work/gen" "$work/classes" "$work/dex"

    echo "=== $app: aapt2 compile ==="
    "$AAPT2" compile --dir "$src/res" -o "$work/compiled.zip"

    echo "=== $app: aapt2 link ==="
    "$AAPT2" link -o "$work/app-unsigned.apk" --manifest "$src/AndroidManifest.xml" \
        -I "$ANDROID_JAR" -A "$src/assets" --java "$work/gen" --auto-add-overlay \
        -R "$work/compiled.zip"

    echo "=== $app: javac ==="
    local cp="$ANDROID_JAR"
    for j in "$@"; do cp="$cp:$j"; done
    find "$src/java" -name "*.java" > "$work/sources.txt"
    find "$work/gen" -name "*.java" >> "$work/sources.txt"
    "$JDK/bin/javac" -source 8 -target 8 -nowarn -cp "$cp" \
        -d "$work/classes" @"$work/sources.txt"

    echo "=== $app: d8 ==="
    (cd "$work/classes" && "$JDK/bin/jar" cf ../classes.jar .)
    "$D8" --release --output "$work/dex" --lib "$ANDROID_JAR" "$work/classes.jar" "$@"

    echo "=== $app: tambah dex, zipalign, sign ==="
    (cd "$work/dex" && zip -q ../app-unsigned.apk classes*.dex)
    "$ZIPALIGN" -f 4 "$work/app-unsigned.apk" "$work/app-aligned.apk"
    "$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
        --out "$KARAOKE/apks/karaoke-$app-debug.apk" "$work/app-aligned.apk"
    "$APKSIGNER" verify "$KARAOKE/apks/karaoke-$app-debug.apk"
    ls -la "$KARAOKE/apks/karaoke-$app-debug.apk"
}

# Tulis ulang version.json dari manifest, supaya cek update di dalam
# aplikasi selalu menunjuk ke versi yang baru di-build. Field "notes"
# dipertahankan kalau sudah ada. URUTAN RILIS (penting): build dulu,
# upload APK ke GitHub, BARU push source + version.json — supaya cek
# update tidak menunjuk ke APK yang belum naik.
write_version_json() {
    python3 - "$KARAOKE" <<'PY'
import json, re, sys
root = sys.argv[1]
man = open(root + '/tv/src/main/AndroidManifest.xml').read()
vn = re.search(r'android:versionName="([^"]+)"', man).group(1)
vc = int(re.search(r'android:versionCode="(\d+)"', man).group(1))
vj = root + '/version.json'
notes = ''
try:
    notes = json.load(open(vj)).get('notes', '')
except Exception:
    pass
data = {
    'version': vn,
    'code': vc,
    'tv': 'https://raw.githubusercontent.com/khaliefk/karaoke/main/apks/karaoke-tv-debug.apk',
    'remote': 'https://raw.githubusercontent.com/khaliefk/karaoke/main/apks/karaoke-remote-debug.apk',
    'notes': notes or 'Update di dalam aplikasi: cek versi tiap dibuka, download + pasang dari dalam.',
}
json.dump(data, open(vj, 'w'), indent=2, sort_keys=True)
print('version.json: ' + vn + ' (code ' + str(vc) + ')')
PY
}

write_version_json

build_app tv \
    "$LIBS/nanohttpd-2.3.1.jar" \
    "$LIBS/Java-WebSocket-1.5.4.jar" \
    "$LIBS/slf4j-api-1.7.36.jar" \
    "$LIBS/core-3.5.2.jar"

build_app remote \
    "$LIBS/core-3.5.2.jar" \
    "$LIBS/Java-WebSocket-1.5.4.jar" \
    "$LIBS/slf4j-api-1.7.36.jar"

echo "SELESAI"
