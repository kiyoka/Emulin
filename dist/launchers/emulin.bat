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

cd /d "%ROOTFS%"

rem issue #3-#3: cmd.exe / conhost.exe は Ctrl-A / Ctrl-F 等の console shortcut
rem を JLine raw mode より先に intercept する。Windows Terminal 推奨。
rem WT_SESSION env が無ければ conhost と判定して 1 度だけ案内 (EMULIN_NO_TIP=1 で抑止)。
if not defined WT_SESSION (
    if not defined EMULIN_NO_TIP (
        echo [emulin tip] cmd.exe では Ctrl-A / Ctrl-F が console に奪われ、
        echo  emacs / vim 等の editor まで届きません。Windows Terminal を推奨
        echo  ^(Microsoft Store から無料インストール可^)。
        echo  EMULIN_NO_TIP=1 で本案内を抑止できます。
        echo.
    )
)

rem Phase 27 step 64: -XX:-DontCompileHugeMethods で Cpu64::decode_and_exec
rem (20K+ bytecode) も JIT C2 コンパイルさせる。実機 binary で 22% 高速化。
set "JVMOPT=-XX:-DontCompileHugeMethods"

if "%~1"=="" (
    java %JVMOPT% -jar "%JAR%" "%ROOTFS%" -CJ /bin/busybox ash -i
) else (
    java %JVMOPT% -jar "%JAR%" "%ROOTFS%" -CJ /bin/busybox %*
)

endlocal
