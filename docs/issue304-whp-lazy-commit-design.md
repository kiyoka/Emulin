# issue #304 — WHP guest RAM lazy commit 設計

issue #221 Phase 1+ フォローアップ (#304) の残件「WHP の lazy commit」の設計ドキュメント。

## 背景・問題の実体

WHP backend の guest RAM は現在、起動時に pool 全体 (`POOL_SIZE`、launcher default 2GB) を
`VirtualAlloc(MEM_COMMIT|MEM_RESERVE)` で一括 commit し、`WHvMapGpaRange` で一括 map する
(`HvVm.allocGuestRam` WHP 分岐 + `NativeCpuBackend.setupKvm` の `WhpVm.mapGuestRam`)。

**重要な事実 (Microsoft Learn + Simpleator 実装で確認)**:
`VirtualAlloc(MEM_COMMIT)` した pool でも、**物理 RAM は guest/host が触った page 分しか
使われない** (Windows kernel の demand-zero、透過的・WHP exit 無し)。一括 commit が消費するのは
**commit charge (pagefile / system commit limit の予約)** であって物理 RAM ではない。

したがって本 issue の lazy commit は **commit charge を guest 実使用量に比例させる最適化**である
(物理 RAM は既に遅延)。動機は fork 連鎖時の `N × 2GB` の commit charge が system commit limit を
圧迫する点。現行 eager commit は whp-oracle 51 件 PASS 済で **正しさの問題ではない**。

## 設計案の比較

| 案 | 概要 | 評価 |
|----|------|------|
| A. 現状維持 | eager commit + 全域 full-map | 最単純・実証済。commit charge が `POOL_SIZE/process` 固定 (問題そのもの) |
| B. CPU fault-on-access | `MEM_RESERVE` + `WHvRunVpExitReasonMemoryAccess` で chunk を commit+map | **emulin に不適合**。emulin は host から pool へ直接書込 (page table / ELF copy / syscall copyin-out) するため、reserve-only page は WHP exit でなく JVM access violation で破綻 |
| **C. allocator 駆動 commit-on-map (推奨)** | `MEM_RESERVE` 起点、`allocPt/allocData` で chunk を commit+`WHvMapGpaRange` | host/guest 双方の access が常に backing。eval/WhpVcpu/exit 経路不変。KVM は hook 未 attach で byte-identical |
| D. C+B | C に MemoryAccess exit の安全網 | C の invariant 上 fault は起きず不要。複雑化のみ → 非推奨 |

### なぜ B が不可か
emulin は `physSet64` (page table 書込) / `bulkStoreToMem` (ELF copy) / syscall の
`copyIn/copyOut/bulkZero` を JVM から `poolSeg` (= VirtualAlloc 領域) へ**直接 host-write** する。
`MEM_RESERVE` だけの page への host 書込は WHP の memory-access exit ではなく
**JVM プロセス内の access violation** になる。よって「guest が触った時に commit」では host 側 touch を
捕捉できない。

### なぜ C が成立するか
`NativeMemoryBackend` が PTE を張る瞬間 (`allocPt`/`allocData`/`mapPage`) = そのページを
emulin が使い始める瞬間。この時点で chunk 単位に `VirtualAlloc(MEM_COMMIT)` + `WHvMapGpaRange`
すれば、**host (JVM 直書き) も guest (vCPU access) も常に committed+mapped に当たる**。
`WHvMapGpaRange` の source は committed+page-aligned 必須という制約も満たす。
emulin が PTE に載せた GPA は必ず先に backing されるので、guest からの未 map fault は従来通り
「本物の segfault → triple fault → EXIT_OTHER」のみで、MemoryAccess exit 分岐は不要。

## 実装計画 (案 C)

| ファイル | 変更 |
|---|---|
| `HvVm.java` | WHP `allocGuestRam` を `MEM_RESERVE` のみに (MEM_COMMIT 除去)。起動時 commit charge ~0。KVM 分岐 (mmap MAP_ANON) と `freeGuestRam` (VirtualFree MEM_RELEASE) は不変 |
| `NativeMemoryBackend.java` | commit/map hook を追加 (`interface GpaBacking { void ensure(long poolOffset, long len); }`、既定 null = KVM no-op)。`allocPt()/allocData()` で割当ページの chunk を ensure。ctor/`enableMmu` で chunk0 (page0+PML4) を ensure。`duplicate()` で `MemorySegment.copy` の前に child の `[0,dataNext)` を ensure し child backend にも hook 配線。**数値計算・PTE 内容・bump pointer は一切不変** (hook は副作用のみ) |
| `WhpGpaBacking.java` (新規) | `GpaBacking` 実装。committed/mapped chunk を `BitSet` で追跡。`ensure(off,len)` で各 chunk を `VirtualAlloc(poolSeg+off, chunkLen, MEM_COMMIT)` → vm 配線済なら `WhpVm.mapGuestRam(gpaBase+off, host+off, chunkLen)`。vm 未配線 (boot 中) は commit のみ記録し `flushMaps()` で後追い map。chunk-range 計算は純粋 helper に分離し **Linux で単体テスト可能**に。並行アクセスは lock で直列化 |
| `WhpVm.java` | `mapGuestRam` を chunk 呼び出しで再利用 (既存 retry/0xC0370008 ロジック流用)。`unmapGuestRam` を mapped chunk を 1 つずつ `WHvUnmapGpaRange` する形に |
| `NativeCpuBackend.java` | `connect_devices/connect_fork` で `!IS_KVM` のとき `guestMem` に `WhpGpaBacking` を attach。`setupKvm` WHP 分岐で full-pool map の代わりに `backing.bindVm(vm)+flushMaps()` を `setupVcpu` (CR3) の前に。teardown を chunk 単位 unmap に。KVM 分岐は不変 |

chunk 粒度は **2MB 既定** (env で可変)。`WHvRunVirtualProcessor` round-trip ~5.8µs を踏まえ、
過小だと VirtualAlloc/WHvMapGpaRange 回数増、過大だと commit over-provision。

## 不変条件 (回帰防止)

- **software backend は不変** (`Memory.java` の独立実装、`NativeMemoryBackend` を使わない、canonical oracle)。
- **KVM backend は byte-identical**: hook を attach しない (null/no-op)。`allocPt/allocData/mapPage/duplicate`
  の返値・PTE 内容・`ptNext/dataNext` は一切変わらず、page table walk 結果も不変。KVM は元から
  mmap demand-paged で lazy なので本変更の対象外。
- 変更は **WHP 固有経路に限定** (`HvVm` WHP 分岐 / `WhpVm` map/unmap / 新規 `WhpGpaBacking` /
  `NativeCpuBackend` の `!IS_KVM` 分岐)。

## リスク (Windows 実機まで未検証)

- `WHvUnmapGpaRange` の chunk 単位 unmap の成否 (slot 全域 1 回 unmap が gap で失敗するのを回避する目的)。
- reserved 領域の sub-range への繰り返し `VirtualAlloc(MEM_COMMIT)`、committed sub-range を
  `WHvMapGpaRange` source にする動作 (仕様上 OK だが WSL2 では実行不可で未検証)。
- chunk 粒度 sweet spot (2MB 既定、実機計測要)。
- multi-vCPU (worker) と main/fork が同一 `guestMem` hook 共有 → chunk bitmap 更新と
  commit/map を lock で直列化 (二重 commit/map race 防止)。
- boot 中 commit した chunk の `WHvMapGpaRange` を `flushMaps` で後追いする順序契約を
  `setupVcpu` (CR3) の前に必ず実行 (誤ると初回 fetch が triple fault)。
- `duplicate` の `MemorySegment.copy` 前に child の `[0,dataNext)` commit 必須 (忘れると fork 子が
  JVM access violation で即死)。

## 検証範囲

- **Linux で検証可**: KVM 経路は hook 未 attach なので既存 native oracle (KVM 版) で byte-identical
  非回帰を検証 (※ 本 dev host は kvm group 未加入で kvm 不可 → CI / 別 Linux 実機 or user 環境)。
  chunk tracker の純ロジックは WHP API 非依存 helper に分離して Linux 単体テスト可能。`mvn compile` は
  Linux で通る (`WhpBindings` は `probe()=false` で未呼出、`WhpGpaBacking` も WHP 経路でのみ instantiate)。
- **Windows + Hyper-V 必須 (Linux 不可)**: 実 commit charge 削減量、`VirtualAlloc` sub-range commit +
  committed sub-range を source にした `WHvMapGpaRange` 実動作、chunk 単位 `WHvUnmapGpaRange`、
  2GB pool に拡大する git clone 下での system commit limit 圧迫解消、whp-oracle.ps1 51 件 non-regression、
  対話 binary (bash/vim/emacs-nw)。

## 実装・検証 (2026-06-14)

実装ファイル: `HvVm.java` (WHP allocGuestRam を MEM_RESERVE 化) / `NativeMemoryBackend.java`
(`GpaBacking` hook + allocPt/allocData/enableMmu/duplicate に ensure) / `WhpGpaBacking.java` (新規、
chunk 単位 commit+map、chunk 算出は static 純関数) / `WhpVm.java` (map/unmap を **synchronized** 化) /
`NativeCpuBackend.java` (connect_devices/connect_fork で backing attach、setupKvm で bindVm[setupVcpu 前]、
teardownKvm で unmapAll)。test: `WhpGpaBackingSmoke.java` + `tests/scripts/whp-gpabacking-smoke.sh`
(run-fast 登録)。

### adversarial review (5 lens) で確認
- **KVM byte-identical = 確証**: hook は既定 null + null-guard no-op、生成は全て `!IS_KVM` gate、KVM 分岐
  (HvVm mmap / setupKvm mapGuestRam / teardown vm.close) は untouched、gpaBase=0 で全演算不変。
- **commit-on-map invariant = 全 pool アクセス経路で成立**: page table r/w (PML4=enableMmu、中間/leaf PT=
  allocPt が ensure 後に返す)、data load/store/bulk (allocData が ensure 後に leaf PTE publish)、
  fork duplicate (copy 前に child [0,dataNext) commit)、boot/guest 側 bindVm 順序 (setupVcpu の CR3 前)。
- **must-fix 反映**: `WhpVm.mapGuestRam/unmapGuestRam` を synchronized 化。JVM 共有の単一 partition を
  fork 子 (別 eval thread) が各自の WhpGpaBacking 経由で並行 mutate しうる (#304 で map/unmap が
  「process 1 回」→「chunk 単位 stream」に増加) ため、partition mutation を instance monitor で直列化。
  lock 順 WhpGpaBacking→WhpVm の一方向で deadlock 無し。

### 検証範囲 (実測)
- **KVM byte-identical = 実測 OK**: `native-oracle.sh` (KVM vs software byte 比較) を kvm 有効化した
  dev host で実行、**100 ok / native!=software 0** (= baseline と同一)。cov4-git-log は #304 と無関係の
  既存 env 件 (native==software、両 rc=128)。cov4-A-dash-pipe は dash 多プロセス pipeline の timing flake
  (再実行で ok)。
- **chunk 純ロジック = 単体テスト OK**: `whp-gpabacking-smoke.sh` PASS (chunk index 算出、末尾 partial、
  duplicate prefix range)。
- **mvn compile OK**。
- **WHP は Windows + Hyper-V でのみ検証可 (本 dev host では不可)**: 実 commit charge 削減、MEM_RESERVE
  sub-range への VirtualAlloc(MEM_COMMIT) + その committed sub-range を WHvMapGpaRange source にする動作、
  chunk 単位 WHvUnmapGpaRange + slot 再利用、並行 fork (git clone full / bash pipeline / fork×3) での
  partition 健全性、whp-oracle.ps1 51 件 非回帰、対話 binary。→ **user の Windows 実機で要確認**。

## 参考

- Microsoft Learn: WHvMapGpaRange / WHvRunVirtualProcessor / WHV_MEMORY_ACCESS_CONTEXT / WHvUnmapGpaRange
- ionescu007/Simpleator issue #2 (WHvMapGpaRange の source alignment + commit 要件)
- `docs/issue221-design-plan.md` / `docs/backend-abstraction.md`
