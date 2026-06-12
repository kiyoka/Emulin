# ----------------------------------------
#  whp-oracle.ps1 — issue #221 step 3e-whp-5: WHP (Windows) 版 native-oracle
#
#  Copyright (C) 1998-2026  Kiyoka Nishiyama
# ----------------------------------------
#
# native-oracle.sh (Linux/KVM) の Windows/WHP 対応版。hermetic テスト binary (tests/binaries) を
# software backend と native (WHP) backend の両方で実行し、stdout が byte 一致することを検証する。
#
#   実行 (Windows、Hyper-V「Windows ハイパーバイザー プラットフォーム」有効 + JDK 22+):
#     powershell -ExecutionPolicy Bypass -File whp-oracle.ps1 [-Jar emulin-all.jar] [-Sandbox sandbox]
#
#   WHP では実行できないテストは SKIP する:
#     - fork 系 (sys_fork64 等): WHP は 1 process につき 1 partition しか guest memory を map
#       できない設計制限 (Microsoft Q&A #320005、docs §4.4rr) のため fork = 2 つ目 partition が不可。
#     - async_signal_dyn64: async kick (host signal で vCPU を割込む #288 の機構) が Linux 固有
#       (tgkill + KVM_RUN EINTR)。WHP は syscall 境界配信のみで、syscall-free spin する worker に
#       signal を届けられず hang する (WHvCancelRunVirtualProcessor 対応は follow-up)。
#
#   終了コード: 0=PASS (全 ok/SKIP)、1=FAIL あり、2=環境不備 (java 無し等)。
param(
  [string]$Jar     = "emulin-all.jar",
  [string]$Sandbox = "sandbox",
  [int]$TimeoutSec = 180
)
$ErrorActionPreference = "Stop"

# ---- 環境確認 ----
if( -not (Get-Command java -ErrorAction SilentlyContinue) ) {
  Write-Host "SKIP whp-oracle : java not found on PATH (JDK 22+ required)"; exit 2
}
$Jar     = (Resolve-Path $Jar).Path
$Sandbox = (Resolve-Path $Sandbox).Path
# mmap_dyn64 / dirlist_dyn64 が /tmp を使う → sandbox/tmp を保証 (zip は空 dir を保存しない)
New-Item -ItemType Directory -Force -Path (Join-Path $Sandbox "tmp") | Out-Null
# stdin の EOF 用 (fgetc_static64 等)。空ファイルを redirect する。
$EmptyIn = Join-Path $env:TEMP "whp-oracle-empty-stdin.txt"
Set-Content -Path $EmptyIn -Value "" -NoNewline

$JFlags = @("--enable-native-access=ALL-UNNAMED", "-XX:-UsePerfData", "-Xmx4g")

# ---- 1 テストを 1 backend で実行し stdout/exit を返す (timeout 付き) ----
function Invoke-Emulin( [string]$Backend, [string]$Bin, [string[]]$BinArgs ) {
  $outFile = New-TemporaryFile
  $errFile = New-TemporaryFile
  $env:EMULIN_BACKEND = $Backend
  # Start-Process -ArgumentList (PS5.1) は空白入り要素を自動 quote しないので明示 quote する
  $argList = @($JFlags) + @("-cp", "`"$Jar`"", "emulin.Emulin", "`"$Sandbox`"", $Bin) + $BinArgs
  try {
    $p = Start-Process -FilePath "java" -ArgumentList $argList -WorkingDirectory $Sandbox `
           -RedirectStandardOutput $outFile -RedirectStandardError $errFile `
           -RedirectStandardInput $EmptyIn -NoNewWindow -PassThru
    # ★ PS5.1 の既知の癖: exit 前に Handle を touch しておかないと ExitCode が $null になる
    $null = $p.Handle
    if( -not $p.WaitForExit( $TimeoutSec * 1000 ) ) {
      try { $p.Kill() } catch {}
      return @{ timeout = $true; out = ""; rc = -1 }
    }
    $p.WaitForExit()   # redirect stream の flush を保証 (引数なし版)
    $out = (Get-Content -Raw -ErrorAction SilentlyContinue $outFile)
    if( $null -eq $out ) { $out = "" }
    return @{ timeout = $false; out = $out; rc = $p.ExitCode }
  } finally {
    Remove-Item -ErrorAction SilentlyContinue $outFile, $errFile
  }
}

# ---- テスト定義 (native-oracle.sh の static + dynamic セクションと同一の binary/args/期待値) ----
#   skip = WHP で実行不可の理由 (null なら実行)。expect は stdout に含まれるべき部分文字列。
$Tests = @(
  # --- static -nostdlib / static glibc (oracle_one 相当) ---
  @{ name="hello64";              args=@();            expect="hello world" },
  @{ name="argvdump64";           args=@("foo","bar"); expect="argv[1]=foo" },
  @{ name="simd64";               args=@();            expect="simd:42,42" },
  @{ name="hello_static64";       args=@();            expect="hello static" },
  @{ name="ctype_static64";       args=@();            expect="alnum" },
  @{ name="fgetc_static64";       args=@();            expect="read 0 chars" },
  @{ name="varexp_repro64";       args=@();            expect="plain_loop" },
  @{ name="bb_decode_static64";   args=@();            expect="chars" },
  @{ name="aesni_static64";       args=@();            expect="state_in" },
  @{ name="sse_audit64";          args=@();            expect="in_a" },
  @{ name="bench64";              args=@();            expect="bench n=10000" },
  @{ name="syscall_storm64";      args=@();            expect="storm n=10000" },
  @{ name="pushf64";              args=@();            expect="popf:11000,00110" },
  @{ name="mmap64";               args=@();            expect="mmap: MAPZ" },
  @{ name="rcr64";                args=@();            expect="rcrcl:0xe00000000000000f,1" },
  @{ name="sys_execve_self64";    args=@();            expect="hello world" },
  @{ name="sys_fork64";           args=@();            expect="parent_saw_child=1";
     skip="fork = WHP 1-partition-per-process 制限 (docs §4.4rr)" },
  @{ name="sys_fork_isolation64"; args=@();            expect="parent:g=1";
     skip="fork = WHP 1-partition-per-process 制限" },
  @{ name="sys_fork_exec64";      args=@();            expect="parent_after_wait";
     skip="fork = WHP 1-partition-per-process 制限" },
  @{ name="sys_signal_delivery64";args=@();            expect="flag=1" },
  @{ name="sys_signal_regsave64"; args=@();            expect="rax=0" },
  @{ name="sys_sa_siginfo64";     args=@();            expect="ucontext_nonnull=1" },
  @{ name="sys_sigmask64";        args=@();            expect="handler sig=10" },
  @{ name="sys_rt_sigaction64";   args=@();            expect="ret=0" },
  @{ name="sys_sig_fpu64";        args=@();            expect="xmm_preserved=1" },
  @{ name="sys_pwrite64";         args=@();            expect="content=AAAXYZAAAAZZ" },
  # --- dynamic glibc (oracle_dyn 相当。ld.so + libc.so.6 等は sandbox に同梱済み) ---
  @{ name="hello_dyn64";          args=@();            expect="hello dynamic" },
  @{ name="printf_dyn64";         args=@();            expect="nan: -nan" },
  @{ name="regex_dyn64";          args=@();            expect="match num='123'" },
  @{ name="mmap_dyn64";           args=@();            expect="mapped: abcdefghij" },
  @{ name="nested_dyn64";         args=@();            expect="result=42" },
  @{ name="pie_dyn64";            args=@();            expect="hello pie" },
  @{ name="zlib_dyn64";           args=@();            expect="compress rc=0" },
  @{ name="cpp_dyn64";            args=@();            expect="apple" },
  @{ name="dirlist_dyn64";        args=@();            expect="entries: a.txt b.txt c.txt" },
  @{ name="pthread_basic_dyn64";  args=@();            expect="joined value=42" },
  @{ name="pthread_mutex_dyn64";  args=@();            expect="counter=4000" },
  @{ name="pthread_sigmask_dyn64";args=@();            expect="parent_handler_fired" },
  @{ name="integ_dyn64";          args=@();            expect="counter=8000" },
  @{ name="async_signal_dyn64";   args=@();            expect="async: delivered=1 onworker=1";
     skip="async kick (tgkill+EINTR) は Linux 固有。WHP は syscall 境界配信のみ = syscall-free spin に届かず hang (follow-up: WHvCancelRunVirtualProcessor)" },
  @{ name="sigaltstack_dyn64";    args=@();            expect="sigaltstack: query=1 handled=1 onalt=1" },
  @{ name="concurrent_fd_dyn64";  args=@();            expect="concurrent-fd: open_err=0 ebadf=0 mismatch=0" },
  @{ name="statat_empty_dyn64";   args=@();            expect="statat-empty: noflag_errno=2 emptypath_ok=1" }
)

# ---- 実行 ----
Write-Host "============================================================"
Write-Host " whp-oracle : software vs native(WHP) byte-identical check"
Write-Host " jar=$Jar"
Write-Host " sandbox=$Sandbox"
Write-Host "============================================================"
$nOk = 0; $nFail = 0; $nSkip = 0
$failNames = @()
foreach( $t in $Tests ) {
  $name = $t.name
  if( $t.skip ) {
    Write-Host ("SKIP {0} : {1}" -f $name, $t.skip)
    $nSkip++
    continue
  }
  $binPath = Join-Path (Join-Path $Sandbox "bin") $name
  if( -not (Test-Path $binPath) ) {
    Write-Host "SKIP $name : binary not bundled ($binPath)"
    $nSkip++
    continue
  }
  $soft = Invoke-Emulin "software" "/bin/$name" $t.args
  $nat  = Invoke-Emulin "native"   "/bin/$name" $t.args
  if( $soft.timeout ) { Write-Host "FAIL $name : software TIMEOUT (${TimeoutSec}s)"; $nFail++; $failNames += $name; continue }
  if( $nat.timeout )  { Write-Host "FAIL $name : native(WHP) TIMEOUT (${TimeoutSec}s)"; $nFail++; $failNames += $name; continue }
  if( $soft.rc -ne 0 ) { Write-Host "FAIL $name : software rc=$($soft.rc)"; $nFail++; $failNames += $name; continue }
  if( $nat.rc -ne 0 )  { Write-Host "FAIL $name : native(WHP) rc=$($nat.rc)"; $nFail++; $failNames += $name; continue }
  if( $soft.out -ne $nat.out ) {
    Write-Host "FAIL $name : native(WHP) stdout != software stdout"
    Write-Host "--- software ---"; Write-Host (($soft.out -split "`n" | Select-Object -First 8) -join "`n")
    Write-Host "--- native ---";   Write-Host (($nat.out  -split "`n" | Select-Object -First 8) -join "`n")
    $nFail++; $failNames += $name; continue
  }
  if( -not $nat.out.Contains( $t.expect ) ) {
    Write-Host "FAIL $name : output に '$($t.expect)' 無し"
    $nFail++; $failNames += $name; continue
  }
  Write-Host "  ok $name : native(WHP)==software (byte 一致、'$($t.expect)')"
  $nOk++
}

Write-Host "============================================================"
if( $nFail -gt 0 ) {
  Write-Host "FAIL whp-oracle : ok=$nOk FAIL=$nFail SKIP=$nSkip"
  Write-Host ("  failed: " + ($failNames -join " "))
  exit 1
}
Write-Host "PASS whp-oracle : ok=$nOk SKIP=$nSkip (fork/async-kick は WHP 制限で SKIP) — native(WHP)==software byte 一致"
exit 0
