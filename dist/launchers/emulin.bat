@echo off
rem --------------------------------------------------------------------
rem  Emulin launcher (Windows)
rem
rem  Usage:
rem    emulin.bat                  : busybox ash -i (interactive shell)
rem    emulin.bat ls /             : run busybox ls / once
rem    emulin.bat ash -c "echo hi"
rem
rem  Requires: java (JRE 11+) on PATH
rem --------------------------------------------------------------------
setlocal

rem issue #59: sandbox 内 root user (uid=0) の home directory として
rem /root を露出。bash の `cd` (引数なし)、ssh の ~/.ssh/、vim の
rem ~/.vimrc 等が動作する。setlocal scope なので Windows 側 HOME
rem (= C:\Users\...) には影響しない。
set "HOME=/root"

rem Provide a UTF-8 locale so emacs and other programs handle UTF-8 text.
rem   C.UTF-8 is glibc's built-in UTF-8 locale (needs no locale files).
rem   Windows provides no LANG, so without this emacs falls back to ASCII
rem   and turns Japanese/Chinese into "?" (mojibake) in shell-mode etc.
rem   (Respect an LANG already set by the user.)
if not defined LANG set "LANG=C.UTF-8"

rem issue #216: Windows sets no TERM, so default to xterm-256color when unset.
rem   With vt100 emacs cannot decode modified keys (Shift+Arrow = \e[1;2C etc.),
rem   so shift-select (region select -> M-w/C-w copy/paste) breaks. Windows
rem   Terminal is xterm-compatible. Respect a TERM the user already set.
if not defined TERM set "TERM=xterm-256color"

rem issue #212: pass the host OS environment variables through to the guest.
rem   emulin-essential vars (PATH/HOME/USER/...) are overridden by emulin
rem   itself, so the host's Windows values cannot break the guest. Set
rem   EMULIN_INHERIT_ENV=0 beforehand to keep the old (whitelist-only) behavior.
if not defined EMULIN_INHERIT_ENV set "EMULIN_INHERIT_ENV=1"
rem issue #221 C-1: HW virtualization (Hyper-V WHP / KVM) present -> run guest on a real
rem   vCPU via the native backend (compute ~200x; HTTPS/git clone that software cannot
rem   finish complete in seconds). Falls back to software automatically when unavailable.
rem   Set EMULIN_BACKEND explicitly to override (CI/tests keep software).
if not defined EMULIN_BACKEND set "EMULIN_BACKEND=auto"
rem issue #221 C-3: native backend physical pool. The 512MB default is exhausted by a
rem   large git clone (index-pack mmaps the whole pack). WHP commits the whole pool but
rem   real RAM is only the touched part. CI/tests keep the 512MB default (env unset).
if not defined EMULIN_NATIVE_POOL_MB set "EMULIN_NATIVE_POOL_MB=2048"

set "HERE=%~dp0"
if "%HERE:~-1%"=="\" set "HERE=%HERE:~0,-1%"
set "ROOTFS=%HERE%\rootfs"

set "JAR="
for %%i in ("%HERE%\lib\emulin-*-all.jar") do set "JAR=%%i"

if not defined JAR (
    echo emulin.bat: error: lib\emulin-*-all.jar not found under %HERE% 1>&2
    exit /b 2
)
if not exist "%ROOTFS%\bin\busybox" (
    echo emulin.bat: error: %ROOTFS%\bin\busybox not found 1>&2
    exit /b 2
)
where java >nul 2>nul
if errorlevel 1 (
    echo emulin.bat: error: java not found on PATH ^(install JRE 11+^) 1>&2
    exit /b 2
)

rem issue #121: if Windows Terminal (wt.exe) is installed, relaunch the
rem   interactive shell inside it for full keyboard passthrough.
rem   Conditions: not already in WT (WT_SESSION undefined) AND no args
rem   (= interactive shell) AND wt.exe present AND EMULIN_NO_WT unset.
rem   After relaunch, WT sets WT_SESSION so it does not loop. With wt
rem   absent / args present / EMULIN_NO_WT=1, keep the old cmd behavior.
rem   (ASCII-only here: cmd.exe reads .bat in the OEM codepage.)
if not defined WT_SESSION if not defined EMULIN_NO_WT if "%~1"=="" (
    where wt >nul 2>nul
    if not errorlevel 1 (
        if not defined EMULIN_NO_WT_SETUP if exist "%HERE%\wt-setup.ps1" powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%\wt-setup.ps1"
        echo [emulin] Launching in Windows Terminal ^(set EMULIN_NO_WT=1 to disable^)...
        wt.exe -- cmd /c "%~f0"
        exit /b 0
    )
)

rem If we are already inside Windows Terminal, ensure the emacs/vim Ctrl+V
rem   passthrough keybinding exists (WT hot-reloads settings.json on save).
if not defined EMULIN_NO_WT_SETUP if defined WT_SESSION if exist "%HERE%\wt-setup.ps1" powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%\wt-setup.ps1"

cd /d "%ROOTFS%"

rem issue #3-#3: cmd.exe / conhost intercept Ctrl-A / Ctrl-F before
rem JLine raw mode can pass them through. Windows Terminal recommended.
rem When WT_SESSION env is absent, assume conhost and show one-time hint.
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

rem JDK 24+ (JEP 472) warns on JLine's System.load (JNI native access):
rem   "WARNING: A restricted method in java.lang.System has been called".
rem   --enable-native-access=ALL-UNNAMED silences it, but that option only
rem   exists on JDK 17+. Feature-detect via -version and add it only when
rem   supported, so older JDKs (which lack the option) still start.
set "NATIVE_ACCESS="
java --enable-native-access=ALL-UNNAMED -version >nul 2>nul
if not errorlevel 1 set "NATIVE_ACCESS=--enable-native-access=ALL-UNNAMED"

rem Phase 27 step 64: -XX:-DontCompileHugeMethods lets HotSpot C2 compile
rem Cpu64::decode_and_exec (20K+ bytecode); about 22% speedup on real binaries.
set "JVMOPT=-XX:-DontCompileHugeMethods %NATIVE_ACCESS%"

rem issue #401: TLS-MITM (EMULIN_EGRESS_MITM) needs add-exports for EmulinCA
rem   (sun.security.x509 pure-Java cert generation, zero added deps).
if defined EMULIN_EGRESS_MITM set "JVMOPT=%JVMOPT% --add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.util=ALL-UNNAMED --add-exports java.base/sun.security.tools.keytool=ALL-UNNAMED"

if /i "%~1"=="sshd" goto emulin_sshd
if /i "%~1"=="setcred" goto emulin_setcred
if "%~1"=="" (
    java %JVMOPT% -jar "%JAR%" "%ROOTFS%" -CJ /bin/busybox ash -i
) else (
    java %JVMOPT% -jar "%JAR%" "%ROOTFS%" -CJ /bin/busybox %*
)
goto emulin_end

rem issue #219: `emulin.bat sshd [port]` で OpenSSH sshd を SSH サーバ起動。
rem   Tera Term 等から接続すれば端末が Ctrl+Space=NUL / 修飾キーを正しく送る
rem   ので Windows console の制約 (issue #216) を回避できる。
:emulin_sshd
set "SSHD_PORT=%~2"
if "%SSHD_PORT%"=="" set "SSHD_PORT=2222"
if not exist "%ROOTFS%\usr\sbin\sshd" (
    echo emulin: sshd not bundled ^(need a bundle built with INCLUDE_SSHD=1^) 1>&2
    exit /b 2
)
if not exist "%ROOTFS%\root\.ssh\authorized_keys" echo [emulin sshd] WARNING: add your SSH client's public key to %ROOTFS%\root\.ssh\authorized_keys
echo [emulin sshd] OpenSSH sshd on 127.0.0.1:%SSHD_PORT% ^(user=root, publickey^) - Ctrl-C to stop
echo [emulin sshd]   connect: ssh -p %SSHD_PORT% root@127.0.0.1
echo [emulin sshd]   Tera Term: Host=localhost / TCP port=%SSHD_PORT% / User=root / publickey
java %JVMOPT% -jar "%JAR%" "%ROOTFS%" /bin/busybox chmod 600 /etc/ssh/ssh_host_ed25519_key >nul 2>nul
java %JVMOPT% -jar "%JAR%" "%ROOTFS%" /usr/sbin/sshd -D -e -p %SSHD_PORT% -f /etc/ssh/sshd_config
goto emulin_end

rem issue #763: 'emulin.bat setcred' sets up ~/.emulin/credentials interactively
rem   (shows how to get a Pro/Max token via 'claude setup-token', tests it against
rem   api.anthropic.com, then saves it). Host-side only; java.base only (no add-exports).
:emulin_setcred
java -cp "%JAR%" emulin.SetCred
goto emulin_end

:emulin_end

endlocal
