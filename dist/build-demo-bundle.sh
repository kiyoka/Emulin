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
#    TARGET_PLATFORM  cross-compile 対象 (linux-x64 / windows-x64 /
#                     macos-x64 / macos-arm64)。build-jre-bundle.sh と同様、
#                     Adoptium から target JDK を download して jlink。
#    EMULIN_JDK_CACHE TARGET_PLATFORM 用 JDK の cache dir
#                     (default: $HOME/.cache/emulin/jdk)
# --------------------------------------------------------------------
set -eu

HERE=$(cd "$(dirname "$0")" && pwd -P)
PROJECT=$(cd "$HERE/.." && pwd -P)
PREBUILT_ROOTFS=${PREBUILT_ROOTFS:-}
TARGET=${TARGET_PLATFORM:-}

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

# 2. platform 判別 + cross-compile 用 JDK 取得
JLINK_MODULE_PATH=
if [ -n "$TARGET" ]; then
    case "$TARGET" in
        linux-x64)   PLATFORM=linux  ; AOPT_OS=linux  ; AOPT_ARCH=x64    ; ARC_EXT=tar.gz ;;
        windows-x64) PLATFORM=windows; AOPT_OS=windows; AOPT_ARCH=x64    ; ARC_EXT=zip    ;;
        macos-x64)   PLATFORM=macos  ; AOPT_OS=mac    ; AOPT_ARCH=x64    ; ARC_EXT=tar.gz ;;
        macos-arm64) PLATFORM=macos  ; AOPT_OS=mac    ; AOPT_ARCH=aarch64; ARC_EXT=tar.gz ;;
        *) echo "build-demo: error: unknown TARGET_PLATFORM=$TARGET" >&2; exit 1 ;;
    esac

    CACHE_DIR=${EMULIN_JDK_CACHE:-$HOME/.cache/emulin/jdk}
    mkdir -p "$CACHE_DIR"
    JDK_DIR=$CACHE_DIR/jdk-21-$TARGET
    if [ ! -d "$JDK_DIR/jmods" ]; then
        URL="https://api.adoptium.net/v3/binary/latest/21/ga/$AOPT_OS/$AOPT_ARCH/jdk/hotspot/normal/eclipse"
        ARC=$CACHE_DIR/jdk-21-$TARGET.$ARC_EXT
        echo "[build-demo] downloading Temurin JDK 21 ($TARGET) ..."
        curl -fsSL -o "$ARC" "$URL"
        rm -rf "$JDK_DIR"
        mkdir -p "$JDK_DIR"
        case "$ARC_EXT" in
            zip)    unzip -q "$ARC" -d "$JDK_DIR" ;;
            tar.gz) tar xzf "$ARC" -C "$JDK_DIR" ;;
        esac
        ACTUAL=$(find "$JDK_DIR" -maxdepth 5 -name jmods -type d | head -1)
        if [ -z "$ACTUAL" ]; then
            echo "build-demo: error: jmods not found in extracted JDK" >&2
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
echo "[build-demo] platform=$PLATFORM version=$VERSION target=${TARGET:-native}"

DIST_NAME=emulin-demo-$VERSION-$PLATFORM
DIST_DIR=$PROJECT/target/$DIST_NAME
rm -rf "$DIST_DIR"

# 3. jlink JRE
mkdir -p "$DIST_DIR"
echo "[build-demo] jlink → $DIST_DIR/jre ..."
JLINK_ARGS=(
    --add-modules java.base,java.logging
    --output "$DIST_DIR/jre"
    --no-header-files --no-man-pages --strip-debug --compress=zip-6
)
if [ -n "$JLINK_MODULE_PATH" ]; then
    JLINK_ARGS=( --module-path "$JLINK_MODULE_PATH" "${JLINK_ARGS[@]}" )
fi
jlink "${JLINK_ARGS[@]}"

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

# 5b. Windows 用は rootfs を tar.gz にして symlink を保持する。
#    Windows Explorer の標準 unzip は POSIX symlink を扱えないので
#    rootfs/ を展開済 dir で zip に入れると、340 個の symlink (例:
#    /lib64/ld-linux-x86-64.so.2 → ../lib/x86_64-linux-gnu/ld-linux...)
#    が 0-byte file 化して dynamic linker が見つからずに git 等が失敗する。
#    回避策: rootfs を tar.gz として格納し、emulin.bat が初回起動時に
#    Windows 10+ 標準の tar.exe (C:\Windows\System32\tar.exe) で展開する。
if [ "$PLATFORM" = "windows" ]; then
    # Windows Explorer の標準 unzip は POSIX symlink を扱えず、Windows
    # tar.exe (BSD libarchive) も symlink 作成に admin 権限が必要、Unicode
    # path での extraction 不安定など問題が多い。回避策として、build 側で
    # rootfs 内の symlink + hardlink をすべて実体ファイルに dereference して
    # から outer zip にそのまま入れる。これで Windows Explorer の「展開」
    # 一発で完全な rootfs が得られ、tar.exe の介在不要。
    echo "[build-demo] (windows) cleaning up broken / circular symlinks..."
    BROKEN=$(find "$ROOTFS" -type l ! -exec test -e {} \; -print 2>/dev/null || true)
    if [ -n "$BROKEN" ]; then
        echo "$BROKEN" | while read -r L; do
            [ -n "$L" ] && rm -f "$L" && echo "  removed: ${L#$ROOTFS/}"
        done
    fi
    echo "[build-demo] (windows) dereferencing all symlinks + hardlinks in rootfs/ ..."
    # tar roundtrip で in-place dereference (--hard-dereference + -h)。
    # こうすると rootfs/ 配下が全部 regular file になり、zip 側は単純展開で OK。
    DEREF_TAR=$DIST_DIR/.rootfs-deref.tar
    ( cd "$DIST_DIR" && tar --hard-dereference -chf "$DEREF_TAR" rootfs && rm -rf rootfs && tar -xf "$DEREF_TAR" && rm -f "$DEREF_TAR" )
    # 検証: dereference 後 symlink/hardlink が残っていないこと
    REMAIN=$(find "$ROOTFS" -type l 2>/dev/null | wc -l)
    if [ "$REMAIN" -ne 0 ]; then
        echo "build-demo: error: $REMAIN symlinks remain after dereference" >&2
        exit 1
    fi
    echo "[build-demo] (windows) rootfs/ now contains only regular files"
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

rem If first arg is found under rootfs\, rootfs\usr\bin\, rootfs\bin\,
rem run it directly; otherwise route through busybox.
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
# .bat は Windows cmd.exe が CRLF を要求するため LF を CRLF に変換
# (GNU/BSD sed の差異を避けて awk で portable に)
awk 'BEGIN{ORS="\r\n"} {sub(/\r$/,""); print}' "$DIST_DIR/emulin.bat" > "$DIST_DIR/emulin.bat.tmp"
mv "$DIST_DIR/emulin.bat.tmp" "$DIST_DIR/emulin.bat"

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
