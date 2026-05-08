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
    # Windows Explorer の標準 unzip は POSIX symlink を扱えないので、
    # rootfs を tar.gz として bundle する。emulin.bat 初回起動時に
    # Windows 10+ 標準の tar.exe が展開し、symlink を作成する。
    # tar.exe での symlink 作成には admin 権限 OR Developer Mode が必要。
    # 事前に dangling/circular symlink (build-sandbox.sh の不完全 install
    # 由来) を削除しておく。
    echo "[build-demo] (windows) cleaning up broken / circular symlinks..."
    BROKEN=$(find "$ROOTFS" -type l ! -exec test -e {} \; -print 2>/dev/null || true)
    if [ -n "$BROKEN" ]; then
        echo "$BROKEN" | while read -r L; do
            [ -n "$L" ] && rm -f "$L" && echo "  removed: ${L#$ROOTFS/}"
        done
    fi
    echo "[build-demo] (windows) packing rootfs as tar.gz (symlinks preserved)..."
    ( cd "$DIST_DIR" && tar czf rootfs.tar.gz rootfs && rm -rf rootfs )
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
# 注: git clone protocol.version は transport 別に好みが違う
#   https:// → default v2 で動作 (Phase 28-3 mremap fix 後)
#   file://  → v0 必須 (v2 は sideband demuxer "unexpected disconnect" で fail)
# 一括設定すると HTTPS が壊れるので、file:// 時のみユーザーが手動で:
#   git -c protocol.version=0 clone --no-hardlinks file:///path /dest
cd "$ROOTFS"
if [ $# -eq 0 ]; then
    exec "$JAVA" "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" -CJ /bin/busybox ash -i
else
    # 第 1 引数が emulin native binary path なら直接、そうでなければ busybox 経由
    # -CJ は常に付ける (raw mode を要求する binary だけが ICANON off を発動する)
    if [ -e "$ROOTFS/$1" ] || [ -e "$ROOTFS/usr/bin/$1" ] || [ -e "$ROOTFS/bin/$1" ]; then
        exec "$JAVA" "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" -CJ "$@"
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
rem Windows demo bundle ships rootfs as rootfs.tar.gz (symlinks preserved).
rem On first run, extract using Windows 10+ built-in tar.exe.
rem tar.exe creates POSIX symlinks via CreateSymbolicLinkW which requires
rem either admin privileges OR Developer Mode (Windows 10 1703+).
rem Sentinel rootfs\.extracted marks successful extraction so subsequent
rem runs skip re-extraction.
if not exist "%ROOTFS%\.extracted" (
    if exist "%HERE%\rootfs.tar.gz" (
        rem Auto-elevate to admin if not already (UAC prompt on first run).
        net session >nul 2>&1
        if errorlevel 1 (
            echo First-run setup needs to extract the bundled rootfs ^(creates POSIX symlinks^).
            echo This requires administrator privileges. Re-launching with UAC elevation...
            echo.
            rem PowerShell の Start-Process は -ArgumentList が空文字列だと
            rem syntax error になるので、引数の有無で分岐する。
            if "%~1"=="" (
                powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
            ) else (
                powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -ArgumentList '%*' -Verb RunAs"
            )
            exit /b 0
        )
        if exist "%ROOTFS%" (
            echo Removing incomplete rootfs from previous extraction...
            rmdir /s /q "%ROOTFS%"
        )
        echo Extracting bundled rootfs ^(this may take a minute^)...
        tar -xzf "%HERE%\rootfs.tar.gz" -C "%HERE%"
        if errorlevel 1 (
            echo emulin.bat: error: failed to extract rootfs.tar.gz 1>&2
            echo. 1>&2
            echo If symlink creation failed, please enable Developer Mode: 1>&2
            echo   Settings -^> Update ^& Security -^> For developers -^> Developer Mode 1>&2
            echo Or run this .bat as administrator from an elevated cmd. 1>&2
            pause
            exit /b 2
        )
        rem mark extraction complete
        echo. > "%ROOTFS%\.extracted"
        echo Setup complete. Launching Emulin...
        echo.
    )
)
if not exist "%ROOTFS%" (
    echo emulin.bat: error: %ROOTFS% not found 1>&2
    exit /b 2
)

set "JVMOPT=-XX:-DontCompileHugeMethods"
rem Note: git clone protocol differs per transport
rem   https:// works with default v2 (after Phase 28-3 mremap fix)
rem   file:// needs v0 (v2 hits sideband demuxer disconnect)
rem User can opt in for file:// only:  git -c protocol.version=0 clone file:///...
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
"%JAVA%" %JVMOPT% -jar "%JAR%" "%ROOTFS%" -CJ %*

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
