#!/usr/bin/env bash
# --------------------------------------------------------------------
#  dist/build-jre-bundle.sh
#
#  jlink で minimal JRE を bundle した配布 zip を作る。
#  Java を別途 install しなくても起動できる「standalone 配布版」。
#
#  使い方:
#    dist/build-jre-bundle.sh
#
#  生成物:
#    target/emulin-jre-<version>-<platform>.zip
#
#  注意:
#    jlink は実行中の JDK と同じ platform 用の JRE しか作れない。
#    Linux jlink → Linux JRE、Windows jlink → Windows JRE。
#    cross-platform JRE には JDK の jmod を別途用意して --module-path で渡す。
#    GitHub Actions の matrix で各 platform runner 上で実行することで
#    Linux / Windows / macOS 用 JRE bundle を生成できる (Phase 28-2b)。
#
#  環境変数:
#    HOST_BB          rootfs/bin/busybox にコピーする busybox バイナリ
#                     (Linux ELF; default: /usr/bin/busybox)
#    TARGET_PLATFORM  cross-compile 対象 (linux-x64 / windows-x64 /
#                     macos-x64 / macos-arm64)。指定すると Adoptium から
#                     対象 JDK の jmods を download して jlink --module-path
#                     経由で対象 platform 用 JRE を生成する。
#                     未指定なら uname で host platform を自動判別。
#    EMULIN_JDK_CACHE TARGET_PLATFORM 用 JDK の cache dir
#                     (default: $HOME/.cache/emulin/jdk)
# --------------------------------------------------------------------
set -eu

HERE=$(cd "$(dirname "$0")" && pwd -P)
PROJECT=$(cd "$HERE/.." && pwd -P)
HOST_BB=${HOST_BB:-/usr/bin/busybox}
TARGET=${TARGET_PLATFORM:-}

# 移植性のある zip 作成。Linux runner には zip、Windows runner には 7z や
# jar、macOS runner には zip がある。順に fallback する
make_zip() {
    local zip_path=$1 dir_name=$2
    if command -v zip >/dev/null 2>&1; then
        zip -qr "$zip_path" "$dir_name"
    elif command -v 7z >/dev/null 2>&1; then
        7z a -r -tzip "$zip_path" "$dir_name" >/dev/null
    elif command -v jar >/dev/null 2>&1; then
        # jar -cMf は manifest なしで通常の zip を生成
        ( cd "$(dirname "$zip_path")" && jar -cMf "$(basename "$zip_path")" "$dir_name" )
    elif command -v python3 >/dev/null 2>&1; then
        python3 -c "import shutil; shutil.make_archive('${zip_path%.zip}', 'zip', '.', '$dir_name')"
    else
        echo "make_zip: error: zip / 7z / jar / python3 が見つからない" >&2
        return 1
    fi
}

if ! command -v jlink >/dev/null 2>&1; then
    echo "build-jre-bundle: error: jlink not found (need JDK 11+)" >&2
    exit 1
fi
if ! command -v mvn >/dev/null 2>&1; then
    echo "build-jre-bundle: error: mvn not found on PATH" >&2
    exit 1
fi
if [ ! -f "$HOST_BB" ]; then
    echo "build-jre-bundle: error: busybox not at $HOST_BB" >&2
    exit 1
fi

# 1. fat jar
echo "[build-jre-bundle] mvn package..."
( cd "$PROJECT" && mvn -q package -DskipTests )
JAR=$(ls "$PROJECT"/target/emulin-*-all.jar | head -1)
VERSION=$(basename "$JAR" | sed 's/^emulin-//; s/-all\.jar$//')

# 2. platform 判別 + cross-compile 用 JDK 取得
JLINK_MODULE_PATH=
if [ -n "$TARGET" ]; then
    case "$TARGET" in
        linux-x64)   PLATFORM=linux  ; AOPT_OS=linux  ; AOPT_ARCH=x64    ; ARC_EXT=tar.gz ;;
        windows-x64) PLATFORM=windows; AOPT_OS=windows; AOPT_ARCH=x64    ; ARC_EXT=zip    ;;
        macos-x64)   PLATFORM=macos  ; AOPT_OS=mac    ; AOPT_ARCH=x64    ; ARC_EXT=tar.gz ;;
        macos-arm64) PLATFORM=macos  ; AOPT_OS=mac    ; AOPT_ARCH=aarch64; ARC_EXT=tar.gz ;;
        *) echo "build-jre-bundle: error: unknown TARGET_PLATFORM=$TARGET (linux-x64 | windows-x64 | macos-x64 | macos-arm64)" >&2; exit 1 ;;
    esac

    CACHE_DIR=${EMULIN_JDK_CACHE:-$HOME/.cache/emulin/jdk}
    mkdir -p "$CACHE_DIR"
    JDK_DIR=$CACHE_DIR/jdk-21-$TARGET
    if [ ! -d "$JDK_DIR/jmods" ]; then
        URL="https://api.adoptium.net/v3/binary/latest/21/ga/$AOPT_OS/$AOPT_ARCH/jdk/hotspot/normal/eclipse"
        ARC=$CACHE_DIR/jdk-21-$TARGET.$ARC_EXT
        echo "[build-jre-bundle] downloading Temurin JDK 21 ($TARGET) ..."
        curl -fsSL -o "$ARC" "$URL"
        rm -rf "$JDK_DIR"
        mkdir -p "$JDK_DIR"
        case "$ARC_EXT" in
            zip)    unzip -q "$ARC" -d "$JDK_DIR" ;;
            tar.gz) tar xzf "$ARC" -C "$JDK_DIR" ;;
        esac
        ACTUAL=$(find "$JDK_DIR" -maxdepth 5 -name jmods -type d | head -1)
        if [ -z "$ACTUAL" ]; then
            echo "build-jre-bundle: error: jmods not found in extracted JDK" >&2
            exit 1
        fi
        if [ "$ACTUAL" != "$JDK_DIR/jmods" ]; then
            ln -sfn "$ACTUAL" "$JDK_DIR/jmods"
        fi
    fi
    JLINK_MODULE_PATH=$JDK_DIR/jmods
else
    case "$(uname -s)" in
        Linux*)  PLATFORM=linux ;;
        Darwin*) PLATFORM=macos ;;
        MINGW*|CYGWIN*|MSYS*) PLATFORM=windows ;;
        *) PLATFORM=$(uname -s | tr A-Z a-z) ;;
    esac
fi
echo "[build-jre-bundle] platform=$PLATFORM version=$VERSION target=${TARGET:-native}"

# 3. jlink で minimal JRE を作る
DIST_NAME=emulin-jre-$VERSION-$PLATFORM
DIST_DIR=$PROJECT/target/$DIST_NAME
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

JRE_DIR=$DIST_DIR/jre
echo "[build-jre-bundle] jlink → $JRE_DIR ..."
JLINK_ARGS=(
    --add-modules java.base,java.logging
    --output "$JRE_DIR"
    --no-header-files
    --no-man-pages
    --strip-debug
    --compress=zip-6
)
if [ -n "$JLINK_MODULE_PATH" ]; then
    JLINK_ARGS=( --module-path "$JLINK_MODULE_PATH" "${JLINK_ARGS[@]}" )
fi
jlink "${JLINK_ARGS[@]}"

JRE_SIZE=$(du -sh "$JRE_DIR" | awk '{print $1}')
echo "[build-jre-bundle] JRE size: $JRE_SIZE"

# 4. dist tree
mkdir -p "$DIST_DIR/lib" \
         "$DIST_DIR/rootfs/bin" \
         "$DIST_DIR/rootfs/etc" \
         "$DIST_DIR/rootfs/tmp"

cp "$JAR"                       "$DIST_DIR/lib/"
cp "$HOST_BB"                   "$DIST_DIR/rootfs/bin/busybox"
chmod +x                        "$DIST_DIR/rootfs/bin/busybox"
: > "$DIST_DIR/rootfs/etc/emulin.cnf"
cp "$HERE/README.txt"           "$DIST_DIR/"
cp "$HERE/NOTICE.txt"           "$DIST_DIR/"
cp "$PROJECT/COPYING"           "$DIST_DIR/" 2>/dev/null || true
cp "$HERE/build-sandbox.sh"     "$DIST_DIR/"
chmod +x                        "$DIST_DIR/build-sandbox.sh"

# 5. ランチャ — bundle した JRE を使うバージョン
cat > "$DIST_DIR/emulin.sh" <<'EOF'
#!/usr/bin/env bash
# Emulin standalone launcher (bundled JRE)
set -u
HERE=$(cd "$(dirname "$0")" && pwd -P)
JAVA=$HERE/jre/bin/java
JAR=$(ls "$HERE"/lib/emulin-*-all.jar 2>/dev/null | head -1)
ROOTFS=$HERE/rootfs

if [ ! -x "$JAVA" ]; then
    echo "emulin.sh: error: bundled JRE not at $JAVA" >&2
    exit 2
fi
if [ -z "${JAR:-}" ] || [ ! -f "$JAR" ]; then
    echo "emulin.sh: error: lib/emulin-*-all.jar not found" >&2
    exit 2
fi
if [ ! -x "$ROOTFS/bin/busybox" ]; then
    echo "emulin.sh: error: $ROOTFS/bin/busybox not found" >&2
    exit 2
fi

# Phase 27 step 64: 巨大 method (decode_and_exec) を JIT C2 で compile させる
JVM_OPTS=( -XX:-DontCompileHugeMethods )
cd "$ROOTFS"
if [ $# -eq 0 ]; then
    exec "$JAVA" "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" -CJ /bin/busybox ash -i
else
    exec "$JAVA" "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" -CJ /bin/busybox "$@"
fi
EOF
chmod +x "$DIST_DIR/emulin.sh"

cat > "$DIST_DIR/emulin.bat" <<'EOF'
@echo off
rem Emulin standalone launcher (bundled JRE)
setlocal
set "HERE=%~dp0"
if "%HERE:~-1%"=="\" set "HERE=%HERE:~0,-1%"
set "JAVA=%HERE%\jre\bin\java.exe"
set "ROOTFS=%HERE%\rootfs"
set "JAR="
for %%i in ("%HERE%\lib\emulin-*-all.jar") do set "JAR=%%i"

if not exist "%JAVA%" (
    echo emulin.bat: error: bundled JRE not at %JAVA% 1>&2
    exit /b 2
)
if not defined JAR (
    echo emulin.bat: error: lib\emulin-*-all.jar not found 1>&2
    exit /b 2
)
if not exist "%ROOTFS%\bin\busybox" (
    echo emulin.bat: error: %ROOTFS%\bin\busybox not found 1>&2
    exit /b 2
)

set "JVMOPT=-XX:-DontCompileHugeMethods"
cd /d "%ROOTFS%"
if "%~1"=="" (
    "%JAVA%" %JVMOPT% -jar "%JAR%" "%ROOTFS%" -CJ /bin/busybox ash -i
) else (
    "%JAVA%" %JVMOPT% -jar "%JAR%" "%ROOTFS%" -CJ /bin/busybox %*
)
endlocal
EOF
# .bat は Windows cmd.exe が CRLF を要求するため LF を CRLF に変換
# (GNU/BSD sed の差異を避けて awk で portable に)
awk 'BEGIN{ORS="\r\n"} {sub(/\r$/,""); print}' "$DIST_DIR/emulin.bat" > "$DIST_DIR/emulin.bat.tmp"
mv "$DIST_DIR/emulin.bat.tmp" "$DIST_DIR/emulin.bat"

# 6. zip
cd "$PROJECT/target"
ZIP=$DIST_NAME.zip
rm -f "$ZIP"
make_zip "$ZIP" "$DIST_NAME"
SIZE=$(du -sh "$ZIP" | awk '{print $1}')
echo "[build-jre-bundle] $PROJECT/target/$ZIP ($SIZE)"
