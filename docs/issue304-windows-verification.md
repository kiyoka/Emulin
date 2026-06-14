# PR #320 (WHP guest RAM lazy commit) — Windows 実機検証 runbook

対象: `issue-304-whp-lazy-commit` (PR #320, commit `83d8da6`)
変更点: WHP の guest RAM pool を起動時 full `MEM_COMMIT` から **`MEM_RESERVE` + chunk(既定 2MB) 単位の commit-on-map** に変更。狙いは fork 連鎖時の commit charge (pagefile / system commit limit 予約) を guest 実使用量に比例させること。

Linux(KVM) の byte-identical 非回帰は dev host で実測済 (`native-oracle.sh` = 100 ok / mismatch 0)。**この runbook は KVM では検証不能な WHP 固有項目だけ**を Windows 実機で確認する:
1. whp-oracle.ps1 51 件 非回帰 (lazy 経路でも software と byte 一致)
2. **commit charge 削減の実測** (main=eager commit vs PR#320=lazy commit を 2GB pool / git clone で A/B)
3. 並行 fork 下で `0xC0370008` / partition 破損が出ないこと
4. 対話 binary (bash / vim / emacs-nw)
5. デバッグ観測点 (`EMULIN_WHP_DEBUG` / `EMULIN_WHP_COMMIT_CHUNK_MB`)

> 注記 (正直な前提依存):
> - **WHP は WSL2 からは検証できない** (Hyper-V API は Windows ホスト側のみ)。本 runbook の「Windows」ブロックは実機 PowerShell/cmd で実行する。
> - eager 版が実際に commit limit に当たって**失敗するか**は、user の物理 RAM + ページファイルサイズに依存する。失敗しなくても「commit charge の数値差」で削減効果は確認できる。
> - git clone は network 依存 (HTTPS)。non-deterministic なので oracle (項目 3) とは分離して測る。

---

## 凡例

- 🐧 = WSL2 (Debian) の bash で実行 (build/copy)。`$EMULIN` = あなたの Linux 側 checkout = `/home/kiyoka/GitHub/Emulin`。
- 🪟 = Windows 実機の **PowerShell** (特記時のみ cmd)。作業ディレクトリは `C:\emulin-verify` (ASCII path、日本語/OneDrive path を避ける)。

---

## (0) 前提

### 0-1. Hyper-V「Windows Hypervisor Platform」を有効化 🪟 (管理者 PowerShell)

```powershell
# 現状確認
Get-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform | Select FeatureName,State
# 無効なら有効化 (再起動が要る)
Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All -NoRestart
# 必要なら Hyper-V 本体も
Enable-WindowsOptionalFeature -Online -FeatureName Microsoft-Hyper-V-All -All -NoRestart
Restart-Computer
```

期待結果: 再起動後 `Get-WindowsOptionalFeature ... HypervisorPlatform` の `State` が **Enabled**。
(GUI でやる場合: コントロール パネル → プログラム → Windows の機能の有効化または無効化 → 「Windows ハイパーバイザー プラットフォーム」にチェック。)

### 0-2. JDK 22+ を PATH に置く 🪟

WHP backend は FFM API を使うので **JDK 22 以上が必須** (JDK 21 以下は不可)。

```powershell
java -version
```

期待結果: `openjdk version "22..."` 以上 (あなたの環境は JDK 25 のはず)。
注記: **demo bundle (項目 4-6) は同梱 JRE = JDK 25 で自走する**ので launcher 自体は別 JDK 不要。ただし `whp-oracle.ps1`/`whp-oracle-full.ps1` は PATH の `java` を使うので、PATH の `java` が 22+ である必要がある。22+ が PATH に無ければ demo bundle の同梱 JRE を一時的に前置:
```powershell
$env:Path = "C:\emulin-verify\emulin-demo\jre\bin;" + $env:Path
java -version   # 25 になることを確認
```

### 0-3. Windows 作業ディレクトリを作る 🪟

```powershell
New-Item -ItemType Directory -Force C:\emulin-verify | Out-Null
```

---

## (1) PR #320 のコードを Windows へ deploy

WHP の eager(現行 main) と lazy(PR#320) は **コード差分のみ**で、env での切替フラグは無い。よって **fat jar を 2 本** (main=eager / PR#320=lazy) ビルドして同じ demo bundle に差し替えながら比較する。

### 1-1. lazy(PR#320) と eager(main) の fat jar を 2 本ビルド 🐧

```bash
# lazy = 現在のブランチ (issue-304-whp-lazy-commit)
cd /home/kiyoka/GitHub/Emulin
git branch --show-current        # → issue-304-whp-lazy-commit であること
mvn -q clean package -DskipTests
cp target/emulin-0.5.0-all.jar /mnt/c/emulin-verify/emulin-lazy.jar

# eager = main を worktree で取り出してビルド (作業ツリーを汚さない)
git worktree add /tmp/emulin-eager main
( cd /tmp/emulin-eager && mvn -q clean package -DskipTests )
cp /tmp/emulin-eager/target/emulin-0.5.0-all.jar /mnt/c/emulin-verify/emulin-eager.jar
git worktree remove /tmp/emulin-eager --force
```

期待結果: `/mnt/c/emulin-verify/` に `emulin-lazy.jar` と `emulin-eager.jar` (各 ~1.1 MB)。
注記: `main` ブランチは PR がまだ未マージ前提。マージ済なら eager 比較は `git worktree add /tmp/emulin-eager <PR の親 commit>` (= `git rev-parse 83d8da6^`) を使う。

### 1-2. Windows demo bundle (git/bash/vim/emacs 入り) をビルド 🐧

> 既に 0.5.0 release bundle を持っているなら 1-2 はスキップして、その bundle の `lib\` に jar を差し替えてもよい (項目 4 で差し替える)。新規に作る場合のみ実行:

```bash
cd /home/kiyoka/GitHub/Emulin
PLATFORMS="windows-x64" dist/build-release.sh        # 数分 + 初回は MS OpenJDK 25 を download
cp target/emulin-demo-0.5.0-windows-x64.zip /mnt/c/emulin-verify/
```

期待結果: `/mnt/c/emulin-verify/emulin-demo-0.5.0-windows-x64.zip` (~150 MB)。同梱 JRE は JDK 25。
注記: build-release.sh は Linux/WSL2 host 必須。staging は `/mnt/c` (NTFS) の case-collision 回避で自動的に `/tmp` に逃げる。

### 1-3. oracle bundle (hermetic、項目 3 用) をビルド 🐧

```bash
cd /home/kiyoka/GitHub/Emulin
mvn -q package -DskipTests                                   # fat jar が target に無ければ
tests/scripts/build-native-oracle-full-bundle.sh
cp target/native-oracle-full-bundle.zip /mnt/c/emulin-verify/
# 51 件版 whp-oracle.ps1 も bundle に同梱されないので別途コピー
cp tests/scripts/whp-oracle.ps1 /mnt/c/emulin-verify/
```

期待結果: `native-oracle-full-bundle.zip` + `whp-oracle.ps1` が `/mnt/c/emulin-verify/` に出来る。
注記: この bundle の jar (`emulin-all.jar`) は **lazy(現ブランチ) のビルド**になる (項目 3 は lazy 経路の非回帰確認なのでこれで正しい)。

### 1-4. Windows 側で展開 🪟

```powershell
cd C:\emulin-verify
Expand-Archive -Force .\emulin-demo-0.5.0-windows-x64.zip      .\
Rename-Item .\emulin-demo-0.5.0-windows .\emulin-demo -ErrorAction SilentlyContinue
Expand-Archive -Force .\native-oracle-full-bundle.zip          .\
# whp-oracle.ps1 (51件版) を oracle sandbox 同階層へ
Copy-Item .\whp-oracle.ps1 .\native-oracle-full-bundle\ -Force
```

期待結果:
- `C:\emulin-verify\emulin-demo\` に `emulin.bat` / `jre\` / `rootfs\` / `lib\emulin-0.5.0-all.jar` (★native 起動は `emulin.bat` に `EMULIN_BACKEND=native` を与える。`emulin-native.bat` という別 launcher は**存在しない**)。
- `C:\emulin-verify\native-oracle-full-bundle\` に `emulin-all.jar` / `whp-oracle-full.ps1` / **`run-whp-oracle-full.bat`** (全 binary 版 oracle のダブルクリック起動) / `sandbox\bin\*64` / `expected\` (+ 項目 1-3 で別途コピーした 51 件版 `whp-oracle.ps1`)。

> zip 名/展開後フォルダ名は build 版により若干変わる。`Get-ChildItem` で実名を確認して読み替えること。

---

## (2) native(WHP) で起動することの確認 [backend=native]

🪟 (cmd でも PowerShell でも可)

```powershell
cd C:\emulin-verify\emulin-demo
$env:EMULIN_NO_WT = "1"          # Windows Terminal 自動再起動を抑止 (この窓で観測する)
$env:EMULIN_BACKEND = "native"   # WHP を強制
.\emulin.bat bash -c "echo hi-from-whp; uname -m" 2>&1
```

期待結果 (pass 条件):
- 出力に `[backend=native (WHP detected)]` (stderr。`2>&1` で見える)。
- 続けて `hi-from-whp` と `x86_64`。

確認 (software との対比、任意):
```powershell
$env:EMULIN_BACKEND = "software"
.\emulin.bat bash -c "echo hi" 2>&1     # → [backend=software]
$env:EMULIN_BACKEND = "native"
```

注記:
- banner は **launcher ではなく Java アプリ**が stderr に出す (`Emulin.java`)。`2>&1` しないと cmd の窓では見落としやすい。
- `EMULIN_BACKEND=auto` (既定) でも WHP があれば native になり banner は `[backend=native (auto, WHP detected)]`。検証では `native` 明示が確実。
- `[backend=native (... not available)]` 等が出たら 0-1 (Hyper-V) を見直す。

---

## (3) whp-oracle.ps1 で 51 件 非回帰 (lazy 経路 == software byte 一致)

🪟 PowerShell

```powershell
cd C:\emulin-verify\native-oracle-full-bundle
# PATH の java が 22+ であること (0-2)。不足なら demo の同梱 JRE を前置:
#   $env:Path = "C:\emulin-verify\emulin-demo\jre\bin;" + $env:Path
powershell -ExecutionPolicy Bypass -File .\whp-oracle.ps1 -Jar .\emulin-all.jar -Sandbox .\sandbox
echo "exit=$LASTEXITCODE"
```

期待結果 (pass 条件):
- 各テストが `  ok <name> : native(WHP)==software (byte 一致、'...')`。
- 末尾 `PASS whp-oracle : ok=51 SKIP=0 — native(WHP)==software byte 一致`、`exit=0`。
- **FAIL が 1 件も無いこと** (lazy commit でも全 binary が software と byte 一致 = chunk commit/map が data・page table 両アクセス経路で漏れ無く張れている証拠)。

(任意) 全 binary 版でカバレッジ拡大:
```powershell
powershell -ExecutionPolicy Bypass -File .\whp-oracle-full.ps1 -Jar .\emulin-all.jar -Sandbox .\sandbox -Expected .\expected
echo "exit=$LASTEXITCODE"
```
期待結果: `exit=0` (FAIL 0)。`sys_inet|sys_socket|sys_udp|sys_dns|_net_|env_probe` は network 非決定で自動 SKIP されるので SKIP は許容、FAIL のみ NG。

注記: `SKIP whp-oracle : java not found ... exit 2` が出たら PATH の java が 22 未満 / 不在。0-2 を再確認。

---

## (4) ★ commit charge の before/after 実測 (eager main vs lazy PR#320)

**これが PR #320 の本丸**。同じ demo bundle・同じ 2GB pool・同じ git clone を、jar だけ eager↔lazy で差し替えて commit charge を比較する。

計測指標:
- **java プロセスの Private Bytes (`PrivateMemorySize64`)** = そのプロセスの private commit charge。`MEM_RESERVE` は数えず `MEM_COMMIT` だけ数えるので、eager(pool 全 commit) と lazy(触れた chunk のみ commit) が綺麗に分かれる。
- 併せて system 全体の `\Memory\Committed Bytes` も補助的に取る。

### 4-1. 計測スクリプトを保存 🪟

`C:\emulin-verify\measure-clone.ps1`:

```powershell
param([string]$Tag = "run")
$ErrorActionPreference = "Stop"
Set-Location C:\emulin-verify\emulin-demo
$env:EMULIN_NO_WT     = "1"
$env:EMULIN_BACKEND   = "native"     # WHP 強制
# EMULIN_NATIVE_POOL_MB は launcher 既定 2048 (=2GB pool) のまま

# クリーンな clone 先
$dst = "/root/clonetest"
& .\emulin.bat bash -c "rm -rf $dst" 2>$null | Out-Null

$base = (Get-Counter '\Memory\Committed Bytes').CounterSamples[0].CookedValue
$peakSys = $base; $peakProc = 0

# clone を起動 (args 付きなので WT 再起動はしない)。中程度の repo を浅く clone。
$p = Start-Process -FilePath ".\emulin.bat" `
       -ArgumentList @("git","clone","--depth","1","https://github.com/git/git.git",$dst) `
       -PassThru -NoNewWindow
while (-not $p.HasExited) {
  Start-Sleep -Milliseconds 200
  $s = (Get-Counter '\Memory\Committed Bytes').CounterSamples[0].CookedValue
  if ($s -gt $peakSys) { $peakSys = $s }
  $jp = (Get-Process java -ErrorAction SilentlyContinue | Measure-Object PrivateMemorySize64 -Sum).Sum
  if ($jp -and $jp -gt $peakProc) { $peakProc = $jp }
}
"[$Tag] clone exit            = $($p.ExitCode)"
"[$Tag] baseline sys committed = {0:N0} MB" -f ($base/1MB)
"[$Tag] PEAK sys committed     = {0:N0} MB  (delta {1:N0} MB)" -f ($peakSys/1MB), (($peakSys-$base)/1MB)
"[$Tag] PEAK java PrivateBytes = {0:N0} MB" -f ($peakProc/1MB)
```

> 計測前に他の Java アプリ (IDE 等) を閉じる。`Get-Process java` は全 java を合算するため。

### 4-2. eager(main) で計測 🪟

```powershell
cd C:\emulin-verify
Copy-Item .\emulin-eager.jar .\emulin-demo\lib\emulin-0.5.0-all.jar -Force
powershell -ExecutionPolicy Bypass -File .\measure-clone.ps1 -Tag EAGER
```

### 4-3. lazy(PR#320) で計測 🪟

```powershell
cd C:\emulin-verify
Copy-Item .\emulin-lazy.jar .\emulin-demo\lib\emulin-0.5.0-all.jar -Force
powershell -ExecutionPolicy Bypass -File .\measure-clone.ps1 -Tag LAZY
```

期待結果 (pass 条件):
- 両方とも `clone exit = 0` (clone が完走)。
- **`PEAK java PrivateBytes` が LAZY < EAGER** で明確に小さい。
  - EAGER: git の fork 連鎖 (git → git-remote-https → index-pack 等) で **同時 live process 数 × 2GB** が commit される (例: 数 GB〜十数 GB)。
  - LAZY: 触れた chunk だけ commit されるので実使用量に比例 (pack サイズ依存だが EAGER より大幅に小さい)。
- `PEAK sys committed delta` も LAZY < EAGER で同傾向。

注記 (正直な前提依存):
- EAGER が user 機の commit limit を超えると、clone が `cannot allocate memory` 系で **失敗 (exit≠0)** することがある。これはまさに PR が解消する症状で、その場合「EAGER=失敗 / LAZY=成功」が最強の pass 証跡になる。逆に RAM+ページファイルが潤沢だと EAGER も成功するが、その時は **PrivateBytes の数値差**で削減を判定する。
- repo はサイズ調整可。fork 圧力をもっと掛けたい/速くしたいなら別 repo に変えてよい (`--depth 1` 推奨)。clone が遅い/落ちる場合は software では完走しない重さなので native のままで。
- 数値はラン毎に揺れる。各 2〜3 回取り、傾向 (LAZY < EAGER) を見る。

---

## (5) 並行 fork stress — 0xC0370008 / partition 破損が無いこと

JVM 共有の単一 partition + GPA slot 設計 (step 3e-whp-7) なので `0xC0370008` (1-partition-per-process 制限) は **本来発生しない**。lazy commit で map/unmap が「process 1 回」→「chunk 単位の継続 stream」に増え並行度が上がるため、ここを実機で叩く。

🪟 PowerShell (jar は lazy のまま = 4-3 の状態)

```powershell
cd C:\emulin-verify\emulin-demo
$env:EMULIN_NO_WT   = "1"
$env:EMULIN_BACKEND = "native"
$env:EMULIN_WHP_DEBUG = "1"           # map/unmap と 0xC0370008 retry を stderr に出す

# (a) bash pipeline (短命 fork の連鎖 → slot alloc/release 再利用を叩く)
.\emulin.bat bash -c "for i in 1 2 3 4 5 6 7 8; do echo step$i | tr a-z A-Z; done" 2>&1 |
  Tee-Object C:\emulin-verify\stress-pipeline.log

# (b) git clone full (重い fork 連鎖) — 5-1 の clone を再利用してログ採取
.\emulin.bat bash -c "rm -rf /root/s2" 2>$null | Out-Null
.\emulin.bat git clone --depth 1 https://github.com/git/git.git /root/s2 2>&1 |
  Tee-Object C:\emulin-verify\stress-clone.log
```

期待結果 (pass 条件):
- (a)(b) とも正常終了 (pipeline 出力が出る / clone が完走)。
- ログに **`HRESULT=0xc0370008` の最終 throw が無い** こと:
```powershell
Select-String -Path C:\emulin-verify\stress-*.log -Pattern "0xc0370008|WHvMapGpaRange HRESULT|WHvUnmapGpaRange 失敗|GPA 空間枯渇|VirtualAlloc\(MEM_COMMIT\) failed" 
```
  → **ヒット 0 件** が pass。
- (`EMULIN_WHP_DEBUG=1` の `[whp] mapGuestRam 0xc0370008 → ... retry` が一瞬出ても、最終的に成功して clone/pipeline が完走していれば許容。retry を使い切って `IllegalStateException ... 0xc0370008` で **fail** したら NG = partition leak / 別 partition 併用を疑う。)
- `[WhpGpaBacking] WHvUnmapGpaRange 失敗 chunk=...` が出ていないこと (出ると slot 再利用時に遅れて map 失敗として表面化する)。

注記: `EMULIN_WHP_DEBUG=1` は map/unmap を全部出すのでログが大きい。grep 確認が済んだら unset する (`$env:EMULIN_WHP_DEBUG=$null`)。

---

## (6) 対話 binary (bash / vim / emacs-nw)

lazy commit でも対話 (pty / SIGWINCH / 画面描画) が崩れないことを目視確認。Windows Terminal 推奨 (cmd/conhost は Ctrl-A/Ctrl-F を奪う)。jar は lazy のまま。

🪟 Windows Terminal で

```powershell
cd C:\emulin-verify\emulin-demo
# 対話シェル (native 強制)。EMULIN_NO_WT は外してよい (WT 内なら再起動しない)
$env:EMULIN_BACKEND = "native"
.\emulin.bat
```

`emulin.bat` は引数無しで対話 shell を起動する (busybox ash 既定。bash 同梱なら起動後 `bash` で切替)。`EMULIN_BACKEND=native` で WHP 経由になる。起動後、guest シェルで:

```sh
echo $UID; uname -m; pwd            # 0 / x86_64 / /root
vim /root/t.txt                     # i で入力 → Esc → :wq
emacs-nw /root/e.txt                # C-x C-s で保存 → C-x C-c で終了
exit
```

期待結果 (pass 条件):
- 起動時 stderr に `[backend=native (WHP detected)]`。
- bash の行編集 (←/→/履歴) が効く。
- vim で挿入・保存・終了ができ、保存内容が正しい。
- emacs-nw が画面描画され、保存・終了ができる (端末リサイズで再描画が追従)。
- いずれもクラッシュ無し・文字化け無し。

注記: cmd.exe で動かす場合は一部 Ctrl 系キーが console に奪われる (Windows 仕様)。キー不達は PR#320 とは無関係なので Windows Terminal で確認すること。

---

## (7) 観測点 (EMULIN_WHP_DEBUG / EMULIN_WHP_COMMIT_CHUNK_MB)

### 7-1. lazy commit の chunk map/unmap を直接観察 🪟

```powershell
cd C:\emulin-verify\emulin-demo
$env:EMULIN_NO_WT     = "1"
$env:EMULIN_BACKEND   = "native"
$env:EMULIN_WHP_DEBUG = "1"
.\emulin.bat bash -c "echo hi" 2>&1 | Select-String "\[whp\]" | Select-Object -First 40
$env:EMULIN_WHP_DEBUG = $null
```

期待結果 (pass 条件): 次の系列が見える =
- `[whp] CreatePartition OK partition=0x...` / `[whp] SetupPartition OK` (partition 起動)
- `[whp] mapGuestRam partition=0x... gpa=0x... host=0x... size=0x...` が **複数回** (= pool 全域一括ではなく chunk 単位で逐次 map されている = lazy が効いている証拠)。`size` は既定 `0x200000` (2MB) 単位が中心。
- 各 `mapGuestRam -> HRESULT=0x0` (成功)。
- 終了時 `[whp] unmapGuestRam gpa=0x... size=0x...` が map した chunk 分だけ出る。
- `0xc0370008` の最終 throw が無い。

### 7-2. chunk 粒度を変えても byte 一致が崩れないこと 🪟

```powershell
cd C:\emulin-verify\native-oracle-full-bundle
# PATH の java が 22+ であること
$env:EMULIN_WHP_COMMIT_CHUNK_MB = "1"     # 最小粒度 1MB (map 回数最大)
powershell -ExecutionPolicy Bypass -File .\whp-oracle.ps1 -Jar .\emulin-all.jar -Sandbox .\sandbox
echo "chunk=1MB exit=$LASTEXITCODE"

$env:EMULIN_WHP_COMMIT_CHUNK_MB = "16"    # 粗い粒度 16MB
powershell -ExecutionPolicy Bypass -File .\whp-oracle.ps1 -Jar .\emulin-all.jar -Sandbox .\sandbox
echo "chunk=16MB exit=$LASTEXITCODE"

$env:EMULIN_WHP_COMMIT_CHUNK_MB = $null
```

期待結果 (pass 条件): どちらの粒度でも `PASS whp-oracle : ok=51 SKIP=0` / `exit=0`。
意味: chunk 境界をまたぐ alloc (`ensure(off,len)` が複数 chunk を張る) が粒度に依らず正しいこと = chunk index 計算 (`chunkOf`/`lastChunkOf`/`chunkLen`) の境界バグが無いことの実機確認。

注記:
- `EMULIN_WHP_DEBUG` / `EMULIN_WHP_COMMIT_CHUNK_MB` は **emulin プロセス側**の env なので、`emulin.bat`/`whp-oracle.ps1` を起動する**前**にセットする (whp-oracle.ps1 は child java に env を継承する)。
- stderr のリダイレクトが効かない窓では `2>&1 | Tee-Object log` で採取し `Select-String` で grep する。

---

## 合否まとめ (この runbook の pass 条件)

| 項目 | pass 条件 |
|---|---|
| (0) 前提 | HypervisorPlatform=Enabled、`java -version`≥22 |
| (2) backend | `[backend=native (WHP detected)]` が出る |
| (3) oracle 51件 | `PASS whp-oracle : ok=51 SKIP=0` / exit 0 (FAIL 0) |
| (4) commit charge ★ | clone 完走 (両 jar) かつ **LAZY の PEAK java PrivateBytes < EAGER**。EAGER が commit limit で失敗するなら「EAGER fail / LAZY pass」 |
| (5) fork stress | pipeline/clone 完走、ログに `0xc0370008` 最終 throw・`WHvUnmapGpaRange 失敗`・`GPA 空間枯渇` が **0 件** |
| (6) 対話 | bash/vim/emacs-nw が native で正常動作・クラッシュ無し |
| (7) 観測 | chunk 単位 map/unmap が観測でき、chunk=1MB/16MB どちらでも oracle exit 0 |

最重要は **(4)**: eager→lazy で java プロセス commit charge が明確に下がる (または eager だけ commit limit で落ちる) ことが PR #320 の効果の直接証拠。
