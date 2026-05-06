#!/usr/bin/env bash
# --------------------------------------------------------------------
#  dist/build-demo-bundle.sh
#
#  実機 binary 同梱 demo zip を作る。
#  build-jre-bundle.sh + full sandbox (git/curl/openssl 等) を組み合わせて、
#  解凍直後から git clone HTTPS 等が動く即席 demo 配布版。
#
#  使い方:
#    dist/build-demo-bundle.sh
#
#  生成物:
#    target/emulin-demo-<version>-<platform>.zip
#
#  注意:
#    Debian/Ubuntu host (glibc 2.39 系) でビルドしたバイナリ・library が
#    そのまま同梱される。互換性は host と同じ系統の Linux でのみ保証。
#
#  cross-platform 対応 (Phase 28-2b):
#    rootfs (= sandbox) は Linux ELF binary 群なので host に依存しない。
#    JRE のみ host 別 (linux/windows/macos)。GitHub Actions matrix で
#    Linux runner が rootfs を build → 各 platform runner が JRE bundle。
#
#  環境変数:
#    PREBUILT_ROOTFS  事前構築された sandbox dir を指定 (build-sandbox.sh
#                     を skip。windows/macos runner で必須)。
# --------------------------------------------------------------------
set -eu

HERE=$(cd "$(dirname "$0")" && pwd -P)
PROJECT=$(cd "$HERE/.." && pwd -P)
PREBUILT_ROOTFS=${PREBUILT_ROOTFS:-}

if ! command -v jlink >/dev/null 2>&1; then
    echo "build-demo-bundle: error: jlink not found (need JDK 11+)" >&2
    exit 1
fi
if [ -z "$PREBUILT_ROOTFS" ] && ! command -v ldd >/dev/null 2>&1; then
    echo "build-demo-bundle: error: ldd not found (Linux host required, または PREBUILT_ROOTFS=... を指定)" >&2
    exit 1
fi

# 移植性のある zip 作成 (build-jre-bundle.sh と同じロジック)
make_zip() {
    local zip_path=$1 dir_name=$2
    if command -v zip >/dev/null 2>&1; then
        zip -qr "$zip_path" "$dir_name"
    elif command -v 7z >/dev/null 2>&1; then
        7z a -r -tzip "$zip_path" "$dir_name" >/dev/null
    elif command -v jar >/dev/null 2>&1; then
        ( cd "$(dirname "$zip_path")" && jar -cMf "$(basename "$zip_path")" "$dir_name" )
    elif command -v python3 >/dev/null 2>&1; then
        python3 -c "import shutil; shutil.make_archive('${zip_path%.zip}', 'zip', '.', '$dir_name')"
    else
        echo "make_zip: error: zip / 7z / jar / python3 が見つからない" >&2
        return 1
    fi
}

# 1. fat jar
echo "[build-demo] mvn package..."
( cd "$PROJECT" && mvn -q package -DskipTests )
JAR=$(ls "$PROJECT"/target/emulin-*-all.jar | head -1)
VERSION=$(basename "$JAR" | sed 's/^emulin-//; s/-all\.jar$//')

# 2. platform 判別
case "$(uname -s)" in
    Linux*)  PLATFORM=linux ;;
    Darwin*) PLATFORM=macos ;;
    MINGW*|CYGWIN*|MSYS*) PLATFORM=windows ;;
    *) PLATFORM=$(uname -s | tr A-Z a-z) ;;
esac
echo "[build-demo] platform=$PLATFORM version=$VERSION"

DIST_NAME=emulin-demo-$VERSION-$PLATFORM
DIST_DIR=$PROJECT/target/$DIST_NAME
rm -rf "$DIST_DIR"

# 3. jlink JRE
mkdir -p "$DIST_DIR"
echo "[build-demo] jlink → $DIST_DIR/jre ..."
jlink \
    --add-modules java.base,java.logging \
    --output "$DIST_DIR/jre" \
    --no-header-files --no-man-pages --strip-debug --compress=zip-6

# 4. fat jar + scripts
mkdir -p "$DIST_DIR/lib"
cp "$JAR"                   "$DIST_DIR/lib/"
cp "$HERE/README.txt"       "$DIST_DIR/"
cp "$HERE/NOTICE.txt"       "$DIST_DIR/"
cp "$PROJECT/COPYING"       "$DIST_DIR/" 2>/dev/null || true
cp "$HERE/build-sandbox.sh" "$DIST_DIR/"

# 5. rootfs を準備 — 事前 build があれば copy、なければ Linux host で build
ROOTFS=$DIST_DIR/rootfs
if [ -n "$PREBUILT_ROOTFS" ]; then
    if [ ! -d "$PREBUILT_ROOTFS" ]; then
        echo "build-demo: error: PREBUILT_ROOTFS=$PREBUILT_ROOTFS が dir でない" >&2
        exit 1
    fi
    echo "[build-demo] PREBUILT_ROOTFS=$PREBUILT_ROOTFS を copy 中..."
    cp -a "$PREBUILT_ROOTFS" "$ROOTFS"
else
    echo "[build-demo] sandbox (full) を構築中..."
    "$HERE/build-sandbox.sh" "$ROOTFS" full > /dev/null
fi

# 6. 専用 launcher (bundled JRE + bundled rootfs)
cat > "$DIST_DIR/emulin.sh" <<'EOF'
#!/usr/bin/env bash
# Emulin demo bundle launcher (bundled JRE + full sandbox)
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
if [ ! -d "$ROOTFS" ]; then
    echo "emulin.sh: error: $ROOTFS not found" >&2
    exit 2
fi

JVM_OPTS=( -XX:-DontCompileHugeMethods )
cd "$ROOTFS"
if [ $# -eq 0 ]; then
    exec "$JAVA" "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" -CJ /bin/busybox ash -i
else
    # 第 1 引数が emulin native binary path なら直接、そうでなければ busybox 経由
    if [ -e "$ROOTFS/$1" ] || [ -e "$ROOTFS/usr/bin/$1" ] || [ -e "$ROOTFS/bin/$1" ]; then
        exec "$JAVA" "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" "$@"
    else
        exec "$JAVA" "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" -CJ /bin/busybox "$@"
    fi
fi
EOF
chmod +x "$DIST_DIR/emulin.sh"

# 6b. Windows 用 launcher (bundled JRE + bundled rootfs)
cat > "$DIST_DIR/emulin.bat" <<'EOF'
@echo off
rem Emulin demo bundle launcher (bundled JRE + full sandbox)
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
if not exist "%ROOTFS%" (
    echo emulin.bat: error: %ROOTFS% not found 1>&2
    exit /b 2
)

set "JVMOPT=-XX:-DontCompileHugeMethods"
cd /d "%ROOTFS%"
if "%~1"=="" (
    "%JAVA%" %JVMOPT% -jar "%JAR%" "%ROOTFS%" -CJ /bin/busybox ash -i
    goto :end
)

rem 第 1 引数が rootfs/, rootfs\usr\bin\, rootfs\bin\ にあれば直接 exec、
rem そうでなければ busybox 経由
if exist "%ROOTFS%%~1" goto :direct
if exist "%ROOTFS%\usr\bin\%~1" goto :direct
if exist "%ROOTFS%\bin\%~1" goto :direct
"%JAVA%" %JVMOPT% -jar "%JAR%" "%ROOTFS%" -CJ /bin/busybox %*
goto :end

:direct
"%JAVA%" %JVMOPT% -jar "%JAR%" "%ROOTFS%" %*

:end
endlocal
EOF

# 7. demo 用 README 追記
cat > "$DIST_DIR/QUICKSTART.txt" <<EOF
Emulin Demo Bundle (Linux x86-64, glibc 2.39 系)
========================================================

このパッケージは、実機 Linux binary 同梱の即席 demo です。
Java も別 sandbox も用意せず、解凍してすぐ動かせます。

クイックスタート:

  ./emulin.sh                                       # busybox ash 対話シェル
  ./emulin.sh /usr/bin/git --version                # 実機 git
  ./emulin.sh /usr/bin/openssl version              # 実機 openssl
  ./emulin.sh /usr/bin/git clone --depth=1 \\
      https://github.com/octocat/Hello-World.git /tmp/cloned   # HTTPS clone

動作する binary (rootfs/usr/bin):
  git, curl, openssl, python3, wget, ...

詳細は README.txt 参照。
EOF

# 8. zip
cd "$PROJECT/target"
ZIP=$DIST_NAME.zip
rm -f "$ZIP"
echo "[build-demo] zipping..."
zip -qr "$ZIP" "$DIST_NAME"
SIZE=$(du -sh "$ZIP" | awk '{print $1}')
RAW=$(du -sh "$DIST_DIR" | awk '{print $1}')
echo "[build-demo] $PROJECT/target/$ZIP ($SIZE compressed, $RAW raw)"
