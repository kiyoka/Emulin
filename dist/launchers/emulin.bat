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
        echo [emulin] Launching in Windows Terminal ^(set EMULIN_NO_WT=1 to disable^)...
        wt.exe -- cmd /c "%~f0"
        exit /b 0
    )
)

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

if "%~1"=="" (
    java %JVMOPT% -jar "%JAR%" "%ROOTFS%" -CJ /bin/busybox ash -i
) else (
    java %JVMOPT% -jar "%JAR%" "%ROOTFS%" -CJ /bin/busybox %*
)

endlocal
