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
# issue #59: staging dir は EMULIN_STAGE_DIR で override 可能。
#   project が /mnt/c (NTFS, case-insensitive) 上にある場合、rootfs の
#   terminfo (A vs a) / perl (sys vs Sys) 等の case-colliding entry を
#   staging できない (cp が "File exists" で失敗)。Linux fs (/tmp 等) を
#   staging に使い、最終 zip だけ $PROJECT/target に移すことで回避する。
STAGE_BASE=${EMULIN_STAGE_DIR:-$PROJECT/target}
mkdir -p "$STAGE_BASE"
DIST_DIR=$STAGE_BASE/$DIST_NAME
rm -rf "$DIST_DIR"

# 3. jlink JRE
mkdir -p "$DIST_DIR"
echo "[build-demo] jlink → $DIST_DIR/jre ..."
JLINK_ARGS=(
    # Phase 33-20: jdk.unsupported は sun.misc.Signal を含む。
    # Emulin.main で SIGINT handler 登録に使用するため必須。
    --add-modules java.base,java.logging,jdk.unsupported
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
cp "$HERE/README.txt"              "$DIST_DIR/"
cp "$HERE/NOTICE.txt"              "$DIST_DIR/"
# issue #63: 同梱 third-party の license inventory
cp "$HERE/THIRD-PARTY-LICENSES.md" "$DIST_DIR/"
cp "$PROJECT/COPYING"              "$DIST_DIR/" 2>/dev/null || true
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
    # issue #3-#4: PREBUILT_ROOTFS が WSL host で build されていた場合、
    # rootfs/etc/resolv.conf に "nameserver 10.255.255.254" (WSL2 DNS proxy)
    # が残っていると Windows native cmd.exe で github.com 等が resolve 不可。
    # cross-platform で確実に動く public DNS で必ず上書きする (build-sandbox.sh
    # 経路と同じ方針)。
    # 注: cp -a は symlink を保持するため、host の resolv.conf が
    # `/mnt/wsl/resolv.conf` 等への symlink だと rootfs/etc/resolv.conf 経由で
    # WSL system file への書き込みになって Permission denied で失敗する。
    # rm -f で symlink を解いてから新規 file として作成する。
    mkdir -p "$ROOTFS/etc"
    rm -f "$ROOTFS/etc/resolv.conf" "$ROOTFS/etc/hosts"
    cat > "$ROOTFS/etc/resolv.conf" <<'RESOLV_EOF'
# generic public DNS (cross-platform 用、emulin sandbox)
nameserver 1.1.1.1
nameserver 1.0.0.1
nameserver 8.8.8.8
RESOLV_EOF
    # /etc/hosts も同様に host (WSL 等) 由来の余計な entry が残っていると
    # 不安定なので、最小限の generic entry で上書きする (build-sandbox.sh 経路と同じ)。
    cat > "$ROOTFS/etc/hosts" <<'HOSTS_EOF'
127.0.0.1	localhost
::1		localhost ip6-localhost ip6-loopback
HOSTS_EOF
    echo "[build-demo] $ROOTFS/etc/{resolv.conf,hosts} を generic 内容で上書き完了"
else
    echo "[build-demo] sandbox (full) を構築中..."
    # issue #59: INCLUDE_* オプションを全て build-sandbox.sh に伝播。
    #   INCLUDE_EMACS=1: emacs-nox + lisp + native-comp + terminfo
    #     (+120 MB raw / +40-50 MB compressed、init は重い)
    #   INCLUDE_VIM=1:    vim + runtime + terminfo (+50 MB raw / +15 MB compressed)
    #   INCLUDE_SSH=1:    openssh client (ssh/scp/sftp/ssh-keygen 等、+8 MB)
    #   INCLUDE_SSHD=1:   openssh server (sshd、+1 MB)
    #   INCLUDE_TIG=1:    tig (git history browser、+400 KB)
    #   INCLUDE_PERL=1:   perl 5 + core modules (+50 MB)
    #   INCLUDE_PYTHON=1: python3 + stdlib (+72 MB)
    #   INCLUDE_MAKE=1:   GNU make (Makefile build、+0.25 MB)
    INCLUDE_EMACS=${INCLUDE_EMACS:-0} \
    INCLUDE_VIM=${INCLUDE_VIM:-0} \
    INCLUDE_SSH=${INCLUDE_SSH:-0} \
    INCLUDE_SSHD=${INCLUDE_SSHD:-0} \
    INCLUDE_TIG=${INCLUDE_TIG:-0} \
    INCLUDE_PERL=${INCLUDE_PERL:-0} \
    INCLUDE_PYTHON=${INCLUDE_PYTHON:-0} \
    INCLUDE_MAKE=${INCLUDE_MAKE:-0} \
    INCLUDE_SSHD_AUTHORIZED_KEY="${INCLUDE_SSHD_AUTHORIZED_KEY:-}" \
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
    # issue #59: NTFS は case-insensitive のため、terminfo の同名異 case
    # entry (例: 2/2621A vs 2/2621a、大文字始まり dir A/L/M/N/P/X/E/Q) が
    # 展開時に衝突する (cp / tar が "File exists" で失敗、または上書き)。
    # これら大文字始まり terminfo は古い HP/IBM 端末用で emulin の利用
    # 端末 (xterm/vt100/linux 等、小文字側) と無関係なので削除する。
    TI="$ROOTFS/usr/share/terminfo"
    if [ -d "$TI" ]; then
        # 大文字始まり 1 文字 dir (= 同名小文字 dir と NTFS で衝突)
        for d in A B C D E F G H I J K L M N O P Q R S T U V W X Y Z; do
            if [ -d "$TI/$d" ] && [ -d "$TI/$(echo "$d" | tr 'A-Z' 'a-z')" ]; then
                rm -rf "$TI/$d"
            fi
        done
        # 残った大文字 entry が小文字 entry と衝突する個別 file も sweep
        find "$TI" -type f -o -type l 2>/dev/null | \
          awk -F/ '{ k=tolower($0); if (seen[k]++) print $0 }' | \
          while read -r dup; do [ -n "$dup" ] && rm -f "$dup"; done
        echo "[build-demo] (windows) terminfo の case-collision entry を sanitize"
    fi
    # issue #68 Phase 3: rootfs 内の全 symlink を Cygwin 式マジックファイル
    # (!<symlink> cookie) に変換する。これにより:
    #   - rootfs に POSIX symlink が無くなる → tar.exe 展開で symlink を
    #     作らない → admin 権限 / Developer Mode 不要
    #   - ld-linux 等の動的リンカ symlink も emulin が Windows host
    #     (= CygSymlink.enabled()) で namei 解決して追従する
    #   - 旧 ld-linux inline hack は不要に (マジックファイル化で代替)
    echo "[build-demo] (windows) symlink を Cygwin マジックファイルに変換..."
    bash "$HERE/cyg-symlinkify.sh" "$ROOTFS"
    # rootfs に symlink が無いことを確認 (あれば admin が必要になってしまう)
    REMAIN=$(find "$ROOTFS" -type l | wc -l)
    if [ "$REMAIN" -ne 0 ]; then
        echo "[build-demo] warn: $REMAIN symlink が残存 (admin が必要になる)" >&2
    fi
    echo "[build-demo] (windows) packing rootfs as tar.gz (symlink 無し → admin 不要)..."
    ( cd "$DIST_DIR" && tar czf rootfs.tar.gz rootfs && rm -rf rootfs )
fi

# 6. 専用 launcher (bundled JRE + bundled rootfs)
cat > "$DIST_DIR/emulin.sh" <<'EOF'
#!/usr/bin/env bash
# Emulin demo bundle launcher (bundled JRE + full sandbox)
set -u
# issue #59: sandbox 内 root user の home を /root に (~/.ssh 等が解決)。
export HOME=/root
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

JVM_OPTS=( -Xmx8g -XX:-DontCompileHugeMethods )
# host の LESSCHARSET=japanese-sjis 等 legacy 設定が less を壊すので
# utf-8 に override (emulator の Kernel.java passthrough 経由)。
export LESSCHARSET=utf-8
# host TERM を emulator 内 bash にも反映 (terminfo lookup に必要)。
# TERM 未設定 host では Kernel.java が vt100 を fallback default にする。
# 注: git clone protocol.version は transport 別に好みが違う
#   https:// → default v2 で動作 (Phase 28-3 mremap fix 後)
#   file://  → v0 必須 (v2 は sideband demuxer "unexpected disconnect" で fail)
# 一括設定すると HTTPS が壊れるので、file:// 時のみユーザーが手動で:
#   git -c protocol.version=0 clone --no-hardlinks file:///path /dest
cd "$ROOTFS"
if [ $# -eq 0 ]; then
    # full sandbox には bash があり実機 binary 用に bash 必須。無ければ
    # minimal sandbox なので busybox ash にフォールバック。
    if [ -x "$ROOTFS/usr/bin/bash" ] || [ -x "$ROOTFS/bin/bash" ]; then
        exec "$JAVA" "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" -CJ /bin/bash -i
    else
        exec "$JAVA" "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" -CJ /bin/busybox ash -i
    fi
else
    # 第 1 引数が emulin native binary path なら直接、そうでなければ shell 経由
    # -CJ は常に付ける (raw mode を要求する binary だけが ICANON off を発動する)
    if [ -e "$ROOTFS/$1" ] || [ -e "$ROOTFS/usr/bin/$1" ] || [ -e "$ROOTFS/bin/$1" ]; then
        exec "$JAVA" "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" -CJ "$@"
    elif [ -x "$ROOTFS/usr/bin/bash" ] || [ -x "$ROOTFS/bin/bash" ]; then
        exec "$JAVA" "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" -CJ /bin/bash -c "$*"
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
rem issue #59: set HOME=/root so the sandbox root user's home resolves
rem (ssh ~/.ssh, vim ~/.vimrc, git ~/.gitconfig). setlocal scope keeps
rem the Windows-side HOME unchanged. (ASCII only: cmd.exe reads .bat in
rem the system codepage; non-ASCII comments break parsing on CP932.)
set "HOME=/root"
rem Provide a UTF-8 locale (Windows sets none) so emacs etc. handle UTF-8
rem text instead of turning Japanese/Chinese into "?". C.UTF-8 is glibc's
rem built-in UTF-8 locale (no locale files). Respect a user-set LANG.
if not defined LANG set "LANG=C.UTF-8"
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

rem issue #121: if Windows Terminal (wt.exe) is installed, relaunch the
rem   interactive shell inside it for full keyboard passthrough. WT sets
rem   WT_SESSION so the relaunched copy does not loop. issue #124: before
rem   launching, run wt-setup.ps1 so emacs/vim Ctrl+V reaches the app
rem   (WT otherwise eats Ctrl+V as paste). Both are opt-out:
rem   EMULIN_NO_WT=1 / EMULIN_NO_WT_SETUP=1. (ASCII only: cmd.exe codepage.)
if not defined WT_SESSION if not defined EMULIN_NO_WT if "%~1"=="" (
    where wt >nul 2>nul
    if not errorlevel 1 (
        if not defined EMULIN_NO_WT_SETUP if exist "%HERE%\wt-setup.ps1" powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%\wt-setup.ps1"
        echo [emulin] Launching in Windows Terminal ^(set EMULIN_NO_WT=1 to disable^)...
        wt.exe -- cmd /c "%~f0"
        exit /b 0
    )
)
rem If we are already inside Windows Terminal, ensure the Ctrl+V keybinding
rem   exists (WT hot-reloads settings.json on save).
if not defined EMULIN_NO_WT_SETUP if defined WT_SESSION if exist "%HERE%\wt-setup.ps1" powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%\wt-setup.ps1"

rem Windows demo bundle ships rootfs as rootfs.tar.gz.
rem issue #68 Phase 3: all symlinks in the rootfs are converted to Cygwin
rem magic files (regular files), so tar.exe extraction creates only regular
rem files + dirs and never calls CreateSymbolicLinkW. Hence NO admin /
rem Developer Mode is required. Emulin resolves the magic files as symlinks
rem via its own namei when running on a Windows host.
rem Sentinel rootfs\.extracted marks successful extraction so subsequent
rem runs skip re-extraction.
if not exist "%ROOTFS%\.extracted" (
    if exist "%HERE%\rootfs.tar.gz" (
        if exist "%ROOTFS%" (
            echo Removing incomplete rootfs from previous extraction...
            rmdir /s /q "%ROOTFS%"
        )
        echo Extracting bundled rootfs ^(this may take a minute^)...
        tar -xzf "%HERE%\rootfs.tar.gz" -C "%HERE%"
        if errorlevel 1 (
            echo emulin.bat: error: failed to extract rootfs.tar.gz 1>&2
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

rem issue #3-#3: cmd.exe / conhost intercept Ctrl-A (select-all),
rem Ctrl-F (find), etc. before JLine raw mode can pass them through,
rem so emacs/vim inside Emulin do not receive those keys.
rem Windows Terminal sets WT_SESSION env var; when absent we assume
rem the user launched from plain conhost and show a one-time hint
rem (set EMULIN_NO_TIP=1 to suppress).
if not defined WT_SESSION (
    if not defined EMULIN_NO_TIP (
        echo [emulin tip] Running in cmd.exe ^(conhost^).
        echo   - Ctrl-A / Ctrl-F are intercepted by the console and will not
        echo     reach emacs/vim inside Emulin.
        echo   - For full keyboard passthrough, run Emulin from
        echo     "Windows Terminal" ^(free in Microsoft Store^).
        echo   - Set EMULIN_NO_TIP=1 to suppress this hint.
        echo.
    )
)

rem JDK 24+ (JEP 472) warns on JLine's JNI System.load. Silence it with
rem   --enable-native-access when the bundled JRE supports it (JDK 17+);
rem   feature-detect so older JREs (without the option) still start.
set "NATIVE_ACCESS="
"%JAVA%" --enable-native-access=ALL-UNNAMED -version >nul 2>nul
if not errorlevel 1 set "NATIVE_ACCESS=--enable-native-access=ALL-UNNAMED"
set "JVMOPT=-Xmx8g -XX:-DontCompileHugeMethods %NATIVE_ACCESS%"
rem Avoid less "invalid charset name" via emulator (host LESSCHARSET override).
set "LESSCHARSET=utf-8"
rem Note: git clone protocol differs per transport
rem   https:// works with default v2 (after Phase 28-3 mremap fix)
rem   file:// needs v0 (v2 hits sideband demuxer disconnect)
rem User can opt in for file:// only:  git -c protocol.version=0 clone file:///...
cd /d "%ROOTFS%"
rem Default shell: bash (full sandbox includes /bin/bash). For minimal
rem sandbox without bash, fall back to busybox ash.
set "DEFAULT_SHELL=/bin/bash"
set "DEFAULT_SHELL_KIND=bash"
if not exist "%ROOTFS%\usr\bin\bash" if not exist "%ROOTFS%\bin\bash" (
    set "DEFAULT_SHELL=/bin/busybox"
    set "DEFAULT_SHELL_KIND=ash"
)
if "%~1"=="" (
    if "%DEFAULT_SHELL_KIND%"=="bash" (
        "%JAVA%" %JVMOPT% -jar "%JAR%" "%ROOTFS%" -CJ /bin/bash -i
    ) else (
        "%JAVA%" %JVMOPT% -jar "%JAR%" "%ROOTFS%" -CJ /bin/busybox ash -i
    )
    goto :end
)

rem If first arg is found under rootfs\, rootfs\usr\bin\, rootfs\bin\,
rem run it directly; otherwise route through default shell.
if exist "%ROOTFS%%~1" goto :direct
if exist "%ROOTFS%\usr\bin\%~1" goto :direct
if exist "%ROOTFS%\bin\%~1" goto :direct
if "%DEFAULT_SHELL_KIND%"=="bash" (
    "%JAVA%" %JVMOPT% -jar "%JAR%" "%ROOTFS%" -CJ /bin/bash -c "%*"
) else (
    "%JAVA%" %JVMOPT% -jar "%JAR%" "%ROOTFS%" -CJ /bin/busybox %*
)
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

# 6c. Windows Terminal keybinding helper (issue #124). emulin.bat invokes
#     wt-setup.ps1 to add a "ctrl+v -> unbound" entry to the user's WT
#     settings.json so emacs C-v / vim CTRL-V work (WT otherwise eats Ctrl+V
#     as paste). Ship the script + the manual fragment, CRLF for Windows.
cp "$HERE/launchers/wt-setup.ps1"                    "$DIST_DIR/"
cp "$HERE/launchers/windows-terminal-settings.jsonc" "$DIST_DIR/"
for f in wt-setup.ps1 windows-terminal-settings.jsonc; do
    awk 'BEGIN{ORS="\r\n"} {sub(/\r$/,""); print}' "$DIST_DIR/$f" > "$DIST_DIR/$f.tmp"
    mv "$DIST_DIR/$f.tmp" "$DIST_DIR/$f"
done

# 7. demo 用 README 追記
cat > "$DIST_DIR/QUICKSTART.txt" <<EOF
Emulin Demo Bundle (Linux x86-64, glibc 2.39 系)
========================================================

このパッケージは、実機 Linux binary 同梱の即席 demo です。
Java も別 sandbox も用意せず、解凍してすぐ動かせます。

クイックスタート:

  ./emulin.sh                                       # bash 対話シェル
  ./emulin.sh /usr/bin/git --version                # 実機 git
  ./emulin.sh /usr/bin/git clone --depth=1 \\
      https://github.com/octocat/Hello-World.git /tmp/cloned   # HTTPS clone

動作する binary (rootfs/usr/bin):
  git, curl, openssl, python3, wget, ...

Windows で emulin.bat を実行する場合:
  Windows Terminal があれば自動でそちらで起動します。emacs/vim で Ctrl+V が
  効くよう、WT 設定 (settings.json) に keybinding を一度だけ追記します
  (バックアップ付き・冪等)。無効化は EMULIN_NO_WT_SETUP=1、手動設定は同梱の
  windows-terminal-settings.jsonc 参照。

詳細は README.txt 参照。
EOF

# 8. zip
# staging dir で zip を作成し ($STAGE_BASE)、最終 zip を $PROJECT/target に置く。
# (EMULIN_STAGE_DIR が NTFS 回避で Linux fs を指している場合、zip 自体は
#  case-collision を内包できる ZIP container なので /mnt/c に移して OK。)
cd "$STAGE_BASE"
ZIP=$DIST_NAME.zip
rm -f "$ZIP"
echo "[build-demo] zipping..."
zip -qr "$ZIP" "$DIST_NAME"
SIZE=$(du -sh "$ZIP" | awk '{print $1}')
RAW=$(du -sh "$DIST_DIR" | awk '{print $1}')
mkdir -p "$PROJECT/target"
if [ "$STAGE_BASE" != "$PROJECT/target" ]; then
    mv -f "$ZIP" "$PROJECT/target/$ZIP"
fi
echo "[build-demo] $PROJECT/target/$ZIP ($SIZE compressed, $RAW raw)"
