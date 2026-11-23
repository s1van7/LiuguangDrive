#!/usr/bin/env bash
# 无 Gradle 构建：aapt → javac → d8 → zipalign → apksigner
set -euo pipefail
cd "$(dirname "$0")"

VERSION="0.4.0"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then echo "请先设置 ANDROID_HOME 指向 Android SDK 目录。" >&2; exit 1; fi
TOOLS="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-28/android.jar"

for tool in aapt d8 zipalign apksigner; do
    if [ ! -x "$TOOLS/$tool" ]; then echo "缺少 build-tools;34.0.0 中的 $tool" >&2; exit 1; fi
done
if [ ! -f "$JAR" ]; then echo "请用 sdkmanager 安装 platforms;android-28" >&2; exit 1; fi

OUT="${BUILD_DIR:-build}"
rm -rf "$OUT/gen" "$OUT/classes" "$OUT/dex"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> 资源与清单"
"$TOOLS/aapt" package -f -m -J "$OUT/gen" -M AndroidManifest.xml -S res -I "$JAR" -F "$OUT/base.apk"

echo "==> 编译 Java"
find src "$OUT/gen" -name '*.java' -print > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
    -bootclasspath "$TOOLS/core-lambda-stubs.jar:$JAR" \
    -d "$OUT/classes" @"$OUT/sources.txt"

jar cf "$OUT/classes.jar" -C "$OUT/classes" .

echo "==> 生成 dex"
"$TOOLS/d8" --release --min-api 28 --lib "$JAR" --output "$OUT/dex" "$OUT/classes.jar"

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$(realpath "../unsigned.apk")" classes*.dex)
"$TOOLS/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# 默认用仓库里的发布签名。签名必须每次一致，否则用户装新版会被系统拒绝，
# 只能先卸载（设置和歌词缓存都会丢）。想换成自己的密钥就覆盖这两个变量：
#   SIGNING_KEYSTORE=/path/to.jks SIGNING_PASSWORD=*** bash build.sh
KEYSTORE="${SIGNING_KEYSTORE:-signing/liuguang-release.jks}"
KEY_ALIAS="${SIGNING_ALIAS:-liuguang}"
export STORE_PASS="${SIGNING_PASSWORD:-liuguang-local-build}"
if [ ! -f "$KEYSTORE" ]; then
    echo "==> 没找到 $KEYSTORE，生成本地临时签名（这次构建的 APK 无法覆盖安装到已有版本上）"
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
        -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 3650 \
        -dname "CN=Liuguang Local Development,O=Liuguang,C=CN"
fi

APK="$OUT/LiuguangDrive-$VERSION.apk"
"$TOOLS/apksigner" sign --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass env:STORE_PASS --out "$APK" "$OUT/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$APK" | head -6
echo "构建完成：$APK"
