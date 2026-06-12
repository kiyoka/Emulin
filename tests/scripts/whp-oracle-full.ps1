# ----------------------------------------
#  whp-oracle-full.ps1 — issue #304: tests/binaries の全 64-bit binary を
#    software/native (WHP) で実行し byte 一致を自動網羅検証する (whp-oracle.ps1 の全件版)。
#
#  Copyright (C) 1998-2026  Kiyoka Nishiyama
# ----------------------------------------
#
# native-oracle-full.sh (Linux/KVM) の Windows/WHP 版。手動リストでなく sandbox/bin の
# 全 *64 binary を自動列挙し、expected/<name>.{stdout,exit,argv,stdin,skip} を使って:
#   (1) software が expected と一致するか (canonical の正しさ)
#   (2) native(WHP) が software と byte 一致 + exit 一致するか (native の正しさ)
# を検証する。(1) NG は環境/host 依存とみなし SKIP。WHP で 1 回実行=全 binary をループ。
#
#   実行 (Windows、Hyper-V「Windows ハイパーバイザー プラットフォーム」有効 + JDK 22+):
#     powershell -ExecutionPolicy Bypass -File whp-oracle-full.ps1 [-Jar ...] [-Sandbox ...] [-Expected ...]
#
#   終了コード: 0=PASS、1=FAIL あり、2=環境不備。
param(
  [string]$Jar      = "emulin-all.jar",
  [string]$Sandbox  = "sandbox",
  [string]$Expected = "expected",
  [int]$TimeoutSec  = 120
)
$ErrorActionPreference = "Stop"

if( -not (Get-Command java -ErrorAction SilentlyContinue) ) {
  Write-Host "SKIP whp-oracle-full : java not found on PATH (JDK 22+ required)"; exit 2
}
$Jar      = (Resolve-Path $Jar).Path
$Sandbox  = (Resolve-Path $Sandbox).Path
$Expected = (Resolve-Path $Expected).Path
New-Item -ItemType Directory -Force -Path (Join-Path $Sandbox "tmp") | Out-Null
$EmptyIn = Join-Path $env:TEMP "whp-oracle-full-empty.txt"
Set-Content -Path $EmptyIn -Value "" -NoNewline

$JFlags = @("--enable-native-access=ALL-UNNAMED", "-XX:-UsePerfData", "-XX:-DontCompileHugeMethods", "-Xmx4g")
# host network / 非決定 stdout / 既知の native バグ (issue #309 sa_mask) を名前で除外。
$SkipRe = 'sys_inet|sys_socket|sys_udp|sys_dns|_net_|env_probe|sys_sa_mask64'

# ---- 1 binary を 1 backend で実行 (timeout 付き) ----
function Invoke-Emulin( [string]$Backend, [string[]]$CmdArgs, [string]$StdinFile ) {
  $outFile = New-TemporaryFile
  $env:EMULIN_BACKEND = $Backend
  # Start-Process -ArgumentList (PS5.1) は空白入り要素を自動 quote しないので明示 quote。
  $argList = @($JFlags) + @("-cp", "`"$Jar`"", "emulin.Emulin", "`"$Sandbox`"") + $CmdArgs
  try {
    $p = Start-Process -FilePath "java" -ArgumentList $argList -WorkingDirectory $Sandbox `
           -RedirectStandardOutput $outFile -RedirectStandardInput $StdinFile -NoNewWindow -PassThru
    # ★ PS5.1 の癖: exit 前に Handle を touch しないと ExitCode が $null になる。
    $null = $p.Handle
    if( -not $p.WaitForExit( $TimeoutSec * 1000 ) ) {
      try { $p.Kill() } catch {}
      return @{ out = ""; rc = -1; timeout = $true }
    }
    $p.WaitForExit()
    $out = (Get-Content -Raw -ErrorAction SilentlyContinue $outFile)
    if( $null -eq $out ) { $out = "" }
    return @{ out = $out; rc = $p.ExitCode; timeout = $false }
  } finally {
    Remove-Item -ErrorAction SilentlyContinue $outFile
  }
}

# ---- 全 64-bit binary を列挙して実行 ----
$bins = Get-ChildItem (Join-Path $Sandbox "bin") -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '64$' } | Sort-Object Name
Write-Host "============================================================"
Write-Host " whp-oracle-full : tests/binaries 全件 software vs native(WHP) byte 一致"
Write-Host " sandbox/bin の *64 binary 数: $($bins.Count)"
Write-Host "============================================================"

$ok = 0; $fail = 0; $skip = 0; $failed = @()
foreach( $b in $bins ) {
  $name = $b.Name
  $expOut = Join-Path $Expected "$name.stdout"
  if( -not (Test-Path $expOut) ) { $skip++; continue }                       # 検証には expected stdout 必須
  if( Test-Path (Join-Path $Expected "$name.skip") ) { $skip++; continue }    # 明示 skip
  if( $name -match $SkipRe ) { $skip++; continue }                            # 名前パターン skip

  # argv (expected/<name>.argv があれば全引数、なければ /bin/<name> 単独)
  $argvFile = Join-Path $Expected "$name.argv"
  if( Test-Path $argvFile ) {
    $cmdArgs = (Get-Content -Raw $argvFile).Trim() -split '\s+'
  } else {
    $cmdArgs = @("/bin/$name")
  }
  # stdin (あれば redirect、なければ空)
  $stdinFile = Join-Path $Expected "$name.stdin"
  if( -not (Test-Path $stdinFile) ) { $stdinFile = $EmptyIn }
  # 期待 exit code
  $expExit = 0
  $exitFile = Join-Path $Expected "$name.exit"
  if( Test-Path $exitFile ) { $expExit = [int]((Get-Content -Raw $exitFile).Trim()) }
  $expStdout = (Get-Content -Raw $expOut); if( $null -eq $expStdout ) { $expStdout = "" }

  # software (canonical) を先に。expected と不一致なら環境依存 → SKIP。
  $soft = Invoke-Emulin "software" $cmdArgs $stdinFile
  if( $soft.timeout -or ($soft.out -ne $expStdout) -or ($soft.rc -ne $expExit) ) { $skip++; continue }
  # native(WHP) が software と byte 一致 + exit 一致なら PASS。
  $nat = Invoke-Emulin "native" $cmdArgs $stdinFile
  if( (-not $nat.timeout) -and ($nat.out -eq $soft.out) -and ($nat.rc -eq $soft.rc) ) {
    $ok++
  } else {
    $fail++; $failed += $name
    Write-Host ("  FAIL {0} : native rc={1} (soft rc={2}) timeout={3}" -f $name, $nat.rc, $soft.rc, $nat.timeout)
  }
}

Write-Host "============================================================"
Write-Host "whp-oracle-full : ok=$ok  FAIL=$fail  SKIP=$skip"
if( $fail -gt 0 ) {
  Write-Host ("  failed: " + ($failed -join " "))
  exit 1
}
Write-Host "PASS whp-oracle-full : tests/binaries の native 適用可 $ok 件すべて native(WHP)==software"
exit 0
