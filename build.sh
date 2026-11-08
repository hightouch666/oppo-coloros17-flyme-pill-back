#!/usr/bin/env bash
# PillBack 构建脚本（无 Gradle：aapt2 + javac + d8 + apksigner）
#
# 依赖（都通过环境变量指定，脚本不假设任何本地目录）：
#   JAVA_HOME    JDK 17+
#   ANDROID_SDK  Android SDK 根目录（需 build-tools 34+ 与 platforms/android-34+）
#   XPOSED_API   Xposed API jar 路径，默认取 ./lib/api-82.jar
#                下载：https://repo1.maven.org/maven2/de/robv/android/xposed/api/82/api-82.jar
#
# 用法：JAVA_HOME=/path/to/jdk ANDROID_SDK=/path/to/sdk ./build.sh
set -e

ROOT=$(cd "$(dirname "$0")" && pwd)

if [ -z "$JAVA_HOME" ]; then
    echo "!! 请设置 JAVA_HOME（需要 JDK 17 或更高）"; exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

SDK="${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
if [ -z "$SDK" ]; then
    echo "!! 请设置 ANDROID_SDK（Android SDK 根目录）"; exit 1
fi

# 从最新往旧挑，但要挑"完整"的那一个（有些新版 build-tools 是残缺的）
BT=""
for d in $(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V -r); do
    d=${d%/}
    if { [ -f "$d/aapt2.exe" ] || [ -f "$d/aapt2" ]; } \
       && { [ -f "$d/zipalign.exe" ] || [ -f "$d/zipalign" ]; } \
       && [ -f "$d/lib/d8.jar" ] && [ -f "$d/lib/apksigner.jar" ]; then
        BT="$d"; break
    fi
done

PLATFORM=""
for d in $(ls -d "$SDK"/platforms/android-*/ 2>/dev/null | sort -V -r); do
    d=${d%/}
    if [ -f "$d/android.jar" ]; then PLATFORM="$d"; break; fi
done

AJAR="$PLATFORM/android.jar"

AAPT2="$BT/aapt2";       [ -f "$AAPT2.exe" ]     && AAPT2="$AAPT2.exe"
ZIPALIGN="$BT/zipalign"; [ -f "$ZIPALIGN.exe" ] && ZIPALIGN="$ZIPALIGN.exe"

XPOSED="${XPOSED_API:-$ROOT/lib/api-82.jar}"

OUT="$ROOT/build"
APK_NAME="PillBack.apk"

echo "== 0/6 环境检查 =="
MISSING=0
for f in "$AAPT2" "$ZIPALIGN" "$BT/lib/apksigner.jar" "$BT/lib/d8.jar" "$AJAR" "$XPOSED"; do
    [ -f "$f" ] || { echo "   缺失: $f"; MISSING=1; }
done
[ "$MISSING" = 0 ] || { echo "!! 依赖不全，先按脚本头部说明准备"; exit 1; }
echo "   OK  build-tools=$(basename "$BT")  platform=$(basename "$PLATFORM")"

rm -rf "$OUT"
mkdir -p "$OUT/cls" "$OUT/dex"

echo "== 1/6 aapt2 link =="
"$AAPT2" link \
    -o "$OUT/base.apk" \
    -I "$AJAR" \
    --manifest "$ROOT/AndroidManifest.xml" \
    -A "$ROOT/assets" \
    --min-sdk-version 31 \
    --target-sdk-version 36

echo "== 2/6 javac =="
SRCS=$(find "$ROOT/src" -name "*.java")
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
    -bootclasspath "$AJAR" \
    -classpath "$XPOSED" \
    -d "$OUT/cls" \
    $SRCS

if [ -z "$(find "$OUT/cls" -name '*.class' 2>/dev/null)" ]; then
    echo "!! 没有生成任何 class"; exit 1
fi
echo "   class 数: $(find "$OUT/cls" -name '*.class' | wc -l)"

echo "== 3/6 d8（Xposed API 只在编译期使用，不打进 dex）=="
CLS=$(find "$OUT/cls" -name "*.class")
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --min-api 31 \
    --lib "$AJAR" \
    --output "$OUT/dex" \
    $CLS

echo "== 4/6 打包 dex =="
cp "$OUT/base.apk" "$OUT/withdex.apk"
if command -v zip >/dev/null 2>&1; then
    ( cd "$OUT" && zip -q -j withdex.apk dex/classes.dex )
else
    "${PYTHON:-python}" -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED); z.write(sys.argv[2],'classes.dex'); z.close()" \
        "$OUT/withdex.apk" "$OUT/dex/classes.dex"
fi
echo "   dex $(wc -c < "$OUT/dex/classes.dex") bytes"

echo "== 5/6 zipalign =="
"$ZIPALIGN" -f -p 4 "$OUT/withdex.apk" "$OUT/aligned.apk"

echo "== 6/6 签名 =="
# 第一次构建会自动生成签名密钥；请自己备份，后续升级必须用同一个 key
KS="${PILLBACK_KEYSTORE:-$ROOT/pillback.keystore}"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias pillback -storepass android \
        -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=PillBack, O=Laotian, C=CN" -storetype JKS >/dev/null 2>&1
    echo "   已生成签名密钥: $KS（请妥善备份）"
fi
java -jar "$BT/lib/apksigner.jar" sign \
    --ks "$KS" --ks-key-alias pillback \
    --ks-pass pass:android --key-pass pass:android \
    --out "$ROOT/$APK_NAME" \
    "$OUT/aligned.apk"

ls -lh "$ROOT/$APK_NAME"
echo "== 完成: $ROOT/$APK_NAME =="
