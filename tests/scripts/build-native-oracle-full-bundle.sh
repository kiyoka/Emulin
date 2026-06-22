#!/usr/bin/env bash
# ----------------------------------------
#  build-native-oracle-full-bundle.sh — issue #304: WHP で whp-oracle-full.ps1 を
#    1 回実行して tests/binaries の全 binary を native 検証できる bundle (zip) を作る。
#
#  Copyright (C) 1998-2026  Kiyoka Nishiyama
# ----------------------------------------
#
# bundle の中身:
#   emulin-all.jar              … fat jar (WHP backend 込み)
#   whp-oracle-full.ps1         … 全 binary 自動列挙の oracle スクリプト
#   run-whp-oracle-full.bat     … ps1 を起動する bat (ダブルクリック用)
#   sandbox/bin/*64             … tests/binaries の全 64-bit binary
#   sandbox/lib64, lib/...       … ld.so / libc.so.6 / libm / libstdc++ 等 (dynamic 用)
#   expected/<name>.{stdout,exit,argv,stdin,skip} … 期待値
#
# Windows での実行: bundle を展開し run-whp-oracle-full.bat をダブルクリック (要 Hyper-V WHP + JDK 22+)。
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
OUT=${1:-$PROJECT/target/native-oracle-full-bundle}
JAR=$(ls "$PROJECT/target/"emulin-*-all.jar 2>/dev/null | head -1)
if [ ! -f "$JAR" ]; then
    echo "ERROR: fat jar が無い ($PROJECT/target/emulin-*-all.jar)。先に 'mvn package -DskipTests' を実行。"; exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/sandbox/bin" "$OUT/sandbox/lib64" "$OUT/sandbox/lib/x86_64-linux-gnu" \
         "$OUT/sandbox/tmp" "$OUT/sandbox/etc" "$OUT/expected"
: > "$OUT/sandbox/etc/emulin.cnf"

# 全 64-bit binary (依存テスト用に全部入れる: sys_execve64 が /bin/hello64 を exec 等)。
cp "$ROOT/binaries/bin/"*64 "$OUT/sandbox/bin/" 2>/dev/null || true
NBIN=$(ls "$OUT/sandbox/bin/" 2>/dev/null | wc -l)

# dynamic glibc 用 ld.so + libc + 定番 lib。
cp /lib64/ld-linux-x86-64.so.2 "$OUT/sandbox/lib64/" 2>/dev/null || true
cp /lib/x86_64-linux-gnu/libc.so.6 "$OUT/sandbox/lib/x86_64-linux-gnu/" 2>/dev/null || true
for lib in libm.so.6 libstdc++.so.6 libgcc_s.so.1 libdl.so.2 libz.so.1 libpthread.so.0; do
    [ -f "/lib/x86_64-linux-gnu/$lib" ] && cp "/lib/x86_64-linux-gnu/$lib" "$OUT/sandbox/lib/x86_64-linux-gnu/"
done

# 期待値 (全 binary 分)。
for ext in stdout exit argv stdin skip; do
    cp "$ROOT/expected/"*.$ext "$OUT/expected/" 2>/dev/null || true
done

# jar + ps1。
cp "$JAR" "$OUT/emulin-all.jar"
cp "$ROOT/scripts/whp-oracle-full.ps1" "$OUT/"
# issue #392 4f: curated 版 whp-oracle.ps1 も同梱 (sys_pf_demand64 で戦略B demand paging を WHP 検証。
#   全件版 -full は EMULIN_NATIVE_PF を立てないので 4f の検証は curated 版でのみ可能)。
cp "$ROOT/scripts/whp-oracle.ps1" "$OUT/"

# run bat (CRLF)。
cat > "$OUT/run-whp-oracle-full.bat" <<'BAT'
@echo off
setlocal
cd /d "%~dp0"
echo ============================================================
echo  whp-oracle-full : all tests/binaries, software vs native(WHP)
echo  Requires: Windows + Hyper-V "Windows Hypervisor Platform" ON + JDK 22+
echo  (if WHvCreatePartition is access-denied, run as Administrator)
echo  NOTE: 2 JVM launches per binary; takes a few minutes.
echo ============================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0whp-oracle-full.ps1"
echo.
echo   ^>^> exit code: %errorlevel%   (0=PASS, 1=FAIL, 2=env)
pause
BAT
awk 'BEGIN{ORS="\r\n"} {sub(/\r$/,""); print}' "$OUT/run-whp-oracle-full.bat" > "$OUT/run-whp-oracle-full.bat.tmp"
mv "$OUT/run-whp-oracle-full.bat.tmp" "$OUT/run-whp-oracle-full.bat"

# issue #392 4f: curated whp-oracle.ps1 用の起動 bat (sys_pf_demand64 で WHP demand paging を検証)。
#   ★bat は ASCII-only + CRLF 必須 (cmd は .bat を OEM CP932 で読むため、下の awk で CRLF 化)。
cat > "$OUT/run-whp-oracle.bat" <<'BAT'
@echo off
setlocal
cd /d "%~dp0"
echo ============================================================
echo  whp-oracle : hermetic tests, software vs native(WHP)
echo  Incl. issue #392 4f: sys_pf_demand64 (EMULIN_NATIVE_PF demand paging)
echo  Requires: Windows + Hyper-V "Windows Hypervisor Platform" ON + JDK 22+
echo  (if WHvCreatePartition is access-denied, run as Administrator)
echo ============================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0whp-oracle.ps1"
echo.
echo   ^>^> exit code: %errorlevel%   (0=PASS, 1=FAIL, 2=env)
pause
BAT
awk 'BEGIN{ORS="\r\n"} {sub(/\r$/,""); print}' "$OUT/run-whp-oracle.bat" > "$OUT/run-whp-oracle.bat.tmp"
mv "$OUT/run-whp-oracle.bat.tmp" "$OUT/run-whp-oracle.bat"

# zip。
( cd "$(dirname "$OUT")" && rm -f "$(basename "$OUT").zip" && zip -rq "$(basename "$OUT").zip" "$(basename "$OUT")" )

echo "============================================================"
echo "bundle 作成: $OUT"
echo "  zip: $OUT.zip"
echo "  sandbox/bin の binary 数: $NBIN"
echo "  expected: $(ls "$OUT/expected/"*.stdout 2>/dev/null | wc -l) stdout"
echo "Windows で展開 → run-whp-oracle-full.bat をダブルクリック (Hyper-V WHP + JDK 22+)。"
echo "  issue #392 4f の確認は run-whp-oracle.bat (curated 版、sys_pf_demand64 が PASS すれば 4f OK)。"
