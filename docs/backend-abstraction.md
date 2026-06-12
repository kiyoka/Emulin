# Backend Abstraction Layer

> **status**: #231/#232/#233 + #238 + #239 + 3a(#240) + 3b(#241) + 3c(★syscall trap=機構 GO、#242) + 3d-1(NativeMemoryBackend、#243) + 3d-2a(Syscall.mem widen seam、#244) + 3d-2(★hello64 native KVM 実行 + software byte 一致 oracle=Phase 0 山場、#245) + 3d-2c-1(hello64 ring 3、sysretq 往復、#246) + 3d-2c-2(初期 stack、#247) + 3d-2c-3(SSE+GPR、#248) + 3d-2c-4(初の実 glibc 静的 binary、#249) + 3d-2c-5(oracle 拡張、#250) + 3d-2c-6(性能実測 215.7x→compute-bound GO、#251) + 3d-2c-7(anonymous mmap、#252) + 3d-2c-8(★★★非 identity MMU リワーク、#253) + 3d-2c-9(file-backed mmap + CPUID passthrough、#254) + 3d-2c-10(anonymous mmap zero-fill 修正→single-thread 動的 binary 8 種完走、#255) + 3d-2c-11(★★★multi-vCPU pthread=真の並列、#256) + 3d-2c-12(coreutils 級 validation + dirlist_dyn64 回帰固定、#257、test-only) + 3d-2c-13(★★★signal 配信=単一+複数スレッド、#258) + 3d-2c-14(busybox validation、#259、test-only) + 3d-2c-15(総合 integ test + lazy mmap pool、#260) + 3d-2c-16(★go/no-go データ測定、#261) + 3d-2c-17(★★看板 curl HTTPS が native 動作、#262) + 3d-2c-18(★native backend 総合監査、#263) + 3d-2c-19(execve は native で動作済を検証、#264) merged。**step 3d-2c-20 (★★★fork が native で動作 = git clone の最後の blocker を解消、本 PR)**。子は独立 VM + 複製 guestMem を持つ別 process。★気づき=MMU の page table は pool-relative な物理 offset を格納するので、使用済み prefix [0,dataNext) を子プールへ verbatim copy するだけで独立した同一アドレス空間が成立 (NativeMemoryBackend.duplicate)。既存 Kernel.fork/Process.duplicate 機構に丸ごと乗れる (Kernel/Process/SyscallAmd64 変更ゼロ): duplicate で親 register snapshot 固定、connect_devices の fork 分岐で guestMem 複製 (ELF reload せず)、entryRip=RCX-2 で Kernel.fork の generic set_ip(+2) fixup を共有、setupVcpu の fork 分岐で全 GPR 復元+rax=0/rip=resume/rflags=R11。clone(CLONE_VM|VFORK=posix_spawn) も software と同じくコピー fork で満たす。sys_fork64/sys_fork_isolation64(★アドレス空間分離)/sys_fork_exec64(★git の fork+exec) の 3 段が native==software byte 一致 (43 check)、run-fast 229) merged。**step 3d-2c-21 (FPU-in-signal=ハードニング、本 PR)**: #263 監査で defer した「signal handler が x87/XMM を使っても被中断側の live FP が壊れない」を実装。software は xmm/x87 を sigSavedFrames に退避するが native は GPR のみだった→KVM_GET/SET_FPU (struct kvm_fpu 416byte) で signal 境界の FPU を snapshot/復元、sigFrames と lockstep。sys_sig_fpu64 (handler が xmm3 破壊→被中断側 xmm3 保持) が native==software (44 check、fix 外すと native xmm_preserved=0 で load-bearing 確認)/run-fast 230) merged。**step 3d-2c-22 (psig race 修正=ハードニング、本 PR)**: signal の pending hint カウンタ pending_recv_count が複数 thread (eval+itimer/kill/tgkill) から非 atomic に更新され under-count→signal 取りこぼしうる race を、volatile int→AtomicInteger で修正。single-thread byte-identical。cross-page partial write は除外 (native は store fault で guest SIGSEGV を配信できず[#PF→triple fault→exit127]、oracle 検証不能な software 専用限界)。oracle 44/run-fast 230) merged。**step 3d-2c-23 (/proc/self/maps の native 実装=カバレッジ拡大、本 PR)**: 実 host binary scout で grep/gawk が native で busy-hang する gap を発見=NativeMemoryBackend.genProcSelfMaps が未実装 stub で throw→guest busy-loop。page table walk で実装 (present+US ページ列挙・coalesce、stack は seedStack で [stack] 識別)。実 GNU grep/gawk/sed/perl/sha256sum を oracle に固定 (version 非依存)、19/28 binary が native==software。review 2 修正 ([stack] 境界 off-by-one/mmuActive guard)。oracle 44→49/run-fast 230) merged。**step 3d-2c-24 (★python3.12 full CPython + tar、test-only、本 PR)**: stdlib bundle (PYTHONHOME=/usr) で full CPython が native 動作 (print/json/hashlib/re=C 拡張込み、4950 等 byte 一致)、tar -tf/-xOf も native==software。native code 変更ゼロ (#268 genProcSelfMaps の上で動的リンク基盤が実用 interpreter を通す)。oracle 49→53) merged。**step 3e-whp-1 (★WHP 移植着手、本 PR)**: WHP は Windows+Hyper-V でしか実行できないので WSL2 では実装+コンパイルまで。KVM bootstrap (KvmSmoke64 #241) と同型に WhpSmoke64 から開始 (NativeCpuBackend 非変更)。WhpBindings 拡充 (SetPartitionProperty/Delete*/VirtualAlloc + WHV ABI 定数全写経=register name/exit reason/map flag/struct size/segment layout、libwhp/pywinhv/MS Docs で cross-check)、WhpSmoke64 が KvmSmoke64 同一 guest を WHP partition で long mode 実行し X64Halt+rax==rbx 検証 (各 call の HRESULT 出力)。Linux では probe=false で exit 2。**★★2026-06-09 Windows 実機[JDK25.0.3]で WhpSmoke64 PASS=「WHP64 smoke OK」rax=rbx=0x1122334455667788/X64Halt=WHV ABI 写経全正解+WSL2 共存 OK 実証**。**step 3e-whp-2 (WhpSyscallSmoke64=ring-3 syscall trap + latency、本 PR 追加)**: KvmSyscallSmoke #242 同型を WHP で。ring-3 syscall→LSTAR hlt stub→X64Halt trap→sysretq round-trip + trap latency 実測。★WHP=Hyper-V L1 非nested なので bare-metal 相当 latency=3f の測定を兼ねる。Windows 実機テスト待ち。次は **WhpSyscallSmoke64 通過後 NativeCpuBackend を KVM⇄WHP 抽象化 → WHP で native-oracle**。
> **last update**: 2026-06-07
> **scope**: emulin の CPU/memory/signal 各層を「software emulator (現行)」と「native backend (#221 = WHP/KVM/HVF)」の **両方を扱える interface 境界**で再構成する 3 段 refactor の作業 doc。

#221 本体 (whether/why) と `docs/issue221-design-plan.md` (how/when) を補完する **interface の作業メモ**。実装が進むにつれて埋める。

---

## 0. 原則 (#221 §1 の再掲)

1. **software emulator は恒久維持**。default、最終 fallback、正しさの canonical。
2. behavior change ゼロが各 step の ship gate。`EMULIN_BACKEND=software` (= default) で従来動作と完全一致。
3. pure-Java の保持。native 依存は native backend 配下にのみ閉じ込める。
4. interface は最小化。「将来 native で必要かも」だけで API を増やさない (= 過剰設計回避)。

---

## 1. Step 1: factory + env var (issue #231、本 PR で完了)

### 1.1 `CpuBackend` enum (`src/main/java/emulin/CpuBackend.java`)

```
enum CpuBackend {
  SOFTWARE, NATIVE, AUTO;

  static CpuBackend resolve();          // EMULIN_BACKEND を読んで cache
  CpuBackend         effective();        // AUTO → SOFTWARE (将来 probe で分岐)
  boolean            verifyImplemented(); // NATIVE 選択時に error message
  String             displayName();      // banner 用
  AbstractCpu        createCpu64(Sysinfo, Process);
  AbstractCpu        createCpu  (Sysinfo, Process);
}
```

### 1.2 `EMULIN_BACKEND` env var

| 値         | effective() | 挙動                                       |
|-----------|------------|--------------------------------------------|
| 未設定 / 空 | SOFTWARE   | 従来動作。banner `[backend=software]`        |
| `software`| SOFTWARE   | 同上。明示                                   |
| `native`  | NATIVE     | 起動時に error message + exit 2 (未実装)     |
| `auto`    | SOFTWARE   | banner `[backend=software (auto)]`、将来 probe |
| その他     | SOFTWARE   | 警告 + software fallback                     |

### 1.3 改修箇所

- 新規 `CpuBackend.java`
- `Process.java`: `new Cpu64(sysinfo, this)` (line 171) → `CpuBackend.resolve().createCpu64(sysinfo, this)`。i386 経路 (line 191) も同様。
- `Emulin.java`: `title()` 直後に `[backend=...]` を `System.err` に 1 行追加、`verifyImplemented()` で false なら `System.exit(2)`。
- `Cpu64.duplicate` / `SyscallAmd64.amd64_clone_thread` 内の `new Cpu64(...)` は software backend 内部の state コピーなので **本 step では触らない** (Step 3 で `CpuBackend.spawnVCpu` に集約)。

### 1.4 ship gate (= Definition of Done)

- ☑ run-fast 220 / run-network 15 / dist-smoke 全 PASS
- ☑ EMULIN_BACKEND 未設定 / `software` / `auto` で banner 表示 + 通常動作
- ☑ EMULIN_BACKEND=`native` で exit code 2
- ☑ EMULIN_BACKEND=`xyz` (typo) で警告 + software fallback

---

## 2. Step 2: `MemoryBackend` 抽象化 (issue #232、本 PR で完了)

### 2.1 目的

guest 物理メモリの表現を `MemoryBackend` interface に切り出し、現 `Memory` class
(per-byte cache + chunks) と、将来追加予定の `NativeMemoryBackend` (WHP/KVM の
mapped page、#221 Phase 0+) が**同じ契約**で plug-in 可能になる構造を確立する。

### 2.2 採用 interface (`src/main/java/emulin/MemoryBackend.java`)

```
interface MemoryBackend {
  // Linear memory (per-byte / multi-byte access)
  byte    load8 (long);  short load16(long);  int load32(long);  long load64(long);
  boolean store8(long,int);  void store16(long,short);
  void    store32(long,int); void store64(long,long);

  // Bulk transfer
  void    bulkLoad       (long, byte[], int);
  void    bulkLoadFromMem(long, byte[], int, int);
  void    bulkStoreToMem (long, byte[], int, int);
  void    bulkZero       (long, int);
  boolean fetch          (long, byte[]);

  // Virtual memory management
  long    alloc        (long, int);
  long    alloc_and_map(long, int, int, int);
  long    alloc_and_map(long, int, int, int, int);
  long    alloc_huge   (long, long, int);
  int     realloc      (long, int);
  int     free         (long, int);
  boolean in           (long);

  // brk / sigreturn trampoline
  long    get_curbrk();
  boolean set_curbrk(long);
  long    ensureSigtramp();

  // /proc/self/maps & ELF symbol
  void    set_map_path  (long, String);
  String  genProcSelfMaps();
  String  get_symbol(long);

  // String / lifecycle / debug
  long    storeString(long, String);
  String  loadString (long);
  void    release_buffers();
  void    dump(long, int);
}
```

命名は既存 Memory class の signature をそのまま列挙 (load*/store* を read*/write*
に rename しない)。理由: load8 callers ~114、store8 callers ~44 全てを touch する
PR になり behavior change と区別がつかなくなる。rename は別 issue で。

### 2.3 採用した実装スタイル: 「Memory class そのものが SoftwareMemoryBackend」

issue #232 本文では `SoftwareMemoryBackend` を新規クラスとして作り Memory の
中身を移動する案が示されていたが、**本 PR では Memory class に `implements
MemoryBackend` を 1 行追加するだけ**にした。理由:

- **性能 regression リスク回避**: Memory.java は 1589 行で per-byte hot path
  (load8/store8 が claude 起動時間の ~30%、#190 / [[claude-perf-exploration]])
  を抱える。新クラスに body を「移動」する変更は logic 変更ゼロを謳っても
  実 byte が変わり、JIT inlining / monomorphic 解決の挙動が変わる可能性が
  非ゼロ (final 指定 / class boundary / method table 配置 等)。1 週間 gate
  (±5%) の中で確実に測定できるリスクではない。
- **抽象化の goal は同等に達成**: 将来 NativeMemoryBackend (#221 Phase 0+) が
  本 interface を implements することで Cpu64 / Syscall 層に plug-in できる、
  という目的は「Memory が implements する」「新クラスが implements する」の
  どちらでも満たされる。「Memory class そのものが SoftwareMemoryBackend である」
  と読み替える。
- **JIT 性能の維持**: `MemoryBackend` 経由で呼んだとしても、JIT は impl が
  Memory 1 つだけのうちは monomorphic 解決して inline する。実コードは現在
  Memory 直接型 (`AbstractCpu.mem` / `Process.mem` / `Syscall.mem` は全て Memory
  concrete のまま) なので、interface 経由の dispatch 自体が発生しない。

### 2.4 type widening は本 step では見送り

`AbstractCpu.mem` / `connect_devices(Memory, Syscall)` を `MemoryBackend` 型に
widen する案も検討したが、以下の理由で見送り:

- `Cpu64` の hot path は `mem.execLock` (issue #113 GIL、software 専用の
  ReentrantLock) に直接アクセスしている。interface に出すと「native は no-op」
  という奇妙な契約になる。GIL は eval() loop の存在が前提で、native backend
  には eval() loop が無い → 両者の対称な契約に乗らない。
- `Cpu` (i386) は `mem.get_symbol` を disassembly trace で使う。Elf parent の
  method で、interface に含めはしたが、type widening する場合は Cpu64 と Cpu
  の各 mem.* 呼び出しを総当りで確認する必要があり、本 step 範囲を超える。
- 実 NativeMemoryBackend が登場する #221 Phase 0 まで「具体的に何を
  widening すべきか」が確定しない。先回り widening は YAGNI に近い。

→ 本 step では interface 宣言と implements 1 行までを成果とし、type widening は
   Step 3 (#233) / #221 Phase 0 で「native backend が実体として登場する瞬間」に
   その path に必要な範囲だけ行う。

### 2.5 ship gate (= Definition of Done)

- ☑ run-fast 220 / run-network 15 全 PASS (sshd / claude-smoke / env 無傷)
- ☑ Memory.java 本体は 1 line (`implements MemoryBackend`) 以外 byte 一致 →
     per-byte hot path の性能 regression は理論上ゼロ
- ☑ `MemoryBackend` interface に Memory 全 public method (28 個) を網羅
- ☑ 設計判断 (移動しない・widening しない) を本 doc に明示

### 2.6 software-only state (interface には出さない)

- per-byte cache (load8 fast path)、chunks、alloclist、globalStoreEpoch、
  lastSegment cache、multiThreadActive、`execLock` (#113 GIL)、`sigtrampAddr`
  field、Elf parent state (sections / symbols / segments / brk_segment_no)。
- これらは software 専用 — interface には出さず Memory class 内に閉じ込め。
- NativeMemoryBackend は同等の概念を hypervisor 上で表現する (e.g. guest 物理
  mapping、page table、WHvMapGpaRange 等)。

---

## 3. Step 3: signal/thread を AbstractCpu に集約 (issue #233、本 PR で完了)

### 3.1 目的

signal 配信と thread/clone spawn の 2 経路を **AbstractCpu の abstract API** に
集約し、software backend 内に現状ロジックを閉じ込める。これで native backend
(NativeCpuBackend、#221 Phase 0+) は同 API を実装するだけで Kernel.kill /
SyscallAmd64.amd64_clone_thread 経路に plug-in できる。

### 3.2 signal 配信: 既存 `set_signal_handler` をそのまま deliverSignal abstraction に

`AbstractCpu.set_signal_handler( long _ip, long goto_adrs )` は既に abstract
method として存在し、Cpu64 (software amd64) / Cpu (software i386) が個別に実装
している。Kernel.kill / signal 配信経路は `process.cpu.set_signal_handler(...)`
を経由するため、**AbstractCpu 層は既に「software/native 共存可能」な契約**に
なっている。

→ **本 step では signature 変更や rename は行わない**。理由:
- red zone (rsp-128) / SA_SIGINFO 3 引数 ABI / SA_RESTART 再チェック / per-thread
  mask / pending signal 判定 (`psig_actionable`、#225) 等の細かい挙動を触らない
- これらは `Cpu64` / `Kernel` / `Process` に分散している実装で、`set_signal_handler`
  という小さい entry point を変えても周辺コードへの波及が大きすぎる
- 将来 native backend が実体として登場した瞬間 (#221 Phase 0) に、必要な情報
  (siginfo / sa_flags / mask) を引数に足せばよい — 先回り設計は YAGNI

将来 NativeCpuBackend は **AbstractCpu の subclass として set_signal_handler を
override 実装**するだけで Kernel.kill 経路に plug-in できる。

### 3.3 thread spawn: 新規 `AbstractCpu.spawnVCpu` で集約

旧 `SyscallAmd64.amd64_clone_thread` は `new Cpu64(...)` を直接呼び、子 Cpu64
への state コピー / connect_devices / Thread64 起動 / ptid/ctid 書込まで本
メソッド内で展開していた。本 step で:

1. `AbstractCpu.spawnVCpu( flags, child_stack, ptid, ctid, tls )` を追加。
   default 実装は `UnsupportedOperationException` (i386 経路は sys_clone 未実装
   のため default のまま)。
2. `Cpu64.spawnVCpu` で override 実装。**「移動だけ」で logic 変更ゼロ** — 旧
   コードの `Cpu64 child_cpu = new Cpu64(...)` から `return tid;` までの block
   をそのまま (constants / TRACE / Phase 27 mask 継承 / ptid/ctid 書込 含む) 移動。
3. `amd64_clone_thread` は **薄い wrapper** に: (1) `FORCE_ST` gate (issue #113)
   (2) 呼び出し thread の Cpu64 選択 (issue #113 ROOT CAUSE fix で確立した
   「worker なら Thread64.cpu、main なら process.cpu」の判定) (3) `parent_cpu.
   spawnVCpu(...)` 呼び出しの 3 セクションだけ残る。

これで:
- Cpu64 が「子 vCPU を spawn する責務」を完全に保持 (software 実装の集約)
- 将来 NativeCpuBackend は spawnVCpu を override 実装するだけで同 partition 内
  に追加 vCPU を作成・メモリ共有する実装に差し替え可能
- 呼び出し元 (`amd64_clone_thread`) は 「FORCE_ST」「parent selection」「spawn」
  の 3 段階に整理され読みやすくなる
- issue #113 の親 cpu 取り違え fix は呼び出し元の wrapper に温存 (= native
  backend で同様の選択が必要なら同 wrapper に追加実装)

### 3.4 software-only state (backend 内へ閉じ込め)

- red zone (rsp-128)、SA_SIGINFO 3 引数 ABI、SA_RESTART 再チェック、per-thread
  mask、pending signal 判定 (psig_actionable、#225) → Cpu64 / Kernel / Process
  の現状実装に閉じ込め
- `Cpu64.copy_state_from` / `Thread64` 起動 / `FutexManager` 接続 / Memory share
  → `Cpu64.spawnVCpu` 内に閉じ込め
- `Memory.FORCE_ST` (issue #113 診断用 single-thread モード) → `amd64_clone_thread`
  wrapper に残置

### 3.5 ship gate (= Definition of Done)

- ☑ compile OK (default UnsupportedOperationException の `spawnVCpu` を Cpu64
     が override、Cpu は default のまま)
- ☑ amd64_clone_thread の logic 変更ゼロ (block の cut-and-paste 移動のみ)
- ☑ run-fast 220 / run-network 15 全 PASS (SIGCHLD / SIGIO / SIGWINCH / pthread
     / clone CLOEXEC / #113 fix path 無傷)

---

## 4. Phase 0: native backend 起動 (#221、進行中)

### 4.0 Phase 0 prep — Java 25 LTS bump (✅ PR #238 merged、main=30da547)

`pom.xml release` を 17 → 25 LTS に。FFM (Java 22 で finalized) を採用するため。
distribution scripts (`build-{jre,demo}-bundle.sh`)、NOTICE / THIRD-PARTY-LICENSES /
README / `.github/workflows/{ci,release}.yml` の JDK 21 表記を 25 に。

### 4.1 Phase 0 step 2 — NativeCpuBackend stub + WHP FFM 雛形 (本 PR)

WSL2 内で完結する範囲で「native backend が CpuBackend factory から見えて
plug-in できる構造」を作る。実 WHP 呼び出しは Phase 0 step 3 (Windows 実機 PoC)。

新規:
- **`WhpBindings.java`** (~220 行)
  - `probe()`: `SymbolLookup.libraryLookup("WinHvPlatform", Arena.global())`
    で WinHvPlatform.dll の存在を確認。Linux/WSL/macOS では即 false。
  - `describeAvailability()`: banner 用 1 行 ("WHP detected" / "WHP not available")
  - `createPartition` / `setupPartition` / `createVirtualProcessor` /
    `runVirtualProcessor` / `mapGpaRange` / `setVirtualProcessorRegisters` /
    `getVirtualProcessorRegisters` の `MethodHandle` を **lazy 初期化** (FFM
    `FunctionDescriptor` + `Linker.downcallHandle`)。
  - probe 結果は static cache 1 度きり。Throwable 全部 catch して false。
- **`NativeCpuBackend.java`** (~140 行)
  - `extends AbstractCpu`。19 abstract method を全て stub。
  - accessor (`set_ip/get_ip/set_sp/get_sp/set_ax`) は instance field 保存
    (将来 vCPU regs を `WHvSet/GetVirtualProcessorRegisters` で同期する placeholder)。
  - `eval()` / `fetch()` / `set_signal_handler()` / `pushString()` /
    `push32()` / `pop32()` / `duplicate()` は `UnsupportedOperationException`
    で「Phase 0 step 3+ TBD」を明示。
  - `connect_devices` は mem/syscall 参照保持。
  - `spawnVCpu` は AbstractCpu の default を流用 (= UnsupportedOperationException)。
  - `reg_str` / `ip_str` / `flag_str` / `disasm_str` は `"<native>"` placeholder。

修正:
- **`CpuBackend.java`**
  - `effective()` AUTO 分岐で `WhpBindings.probe()` → 可用なら NATIVE、不可なら SOFTWARE。
  - `verifyImplemented()` NATIVE 分岐で probe 結果に応じた error 詳細。
  - `displayName()` AUTO で probe 結果を 1 行に含める
    (例: `"software (auto, WHP not available)"`)。
  - `createCpu64/createCpu` NATIVE 分岐で probe 成功時に `new NativeCpuBackend(...)` を返す。

### 4.2 Phase 0 step 2 ship gate (= 本 PR で達成)

Linux/WSL2 の 4 env ケース全部期待動作:
- `EMULIN_BACKEND` 未設定 / `software` → `[backend=software]`、exit 0
- `EMULIN_BACKEND=auto` → `[backend=software (auto, WHP not available)]`、exit 0
- `EMULIN_BACKEND=native` → 5 行の詳細 error (probe 結果 + stub 状態 + step 3+ TBD)、exit 2

回帰: run-fast 220 / run-network 15 全 PASS。

### 4.3 ★戦略転換: KVM 先行 (WSL2 内で開発完結) → WHP 移植

Phase 0 step 2 完了後、**WSL2 に nested virtualization (`/dev/kvm`) が利用可能**と
判明した (`/dev/kvm` が存在、`kvm` group 加入で R/W 可)。当初は「WSL2 では
hypervisor を触れない」前提で Windows WHP 一直線の計画だったが、nested KVM が
使えるなら **WSL2 内で KVM backend を develop + test できる**。これは大きい:

- Claude が**実テストしながら**実装・デバッグ・回帰できる (WHP は user の
  Windows 実機でしか検証できず iteration が遅い)。
- KVM ⇄ WHP は **同一アーキテクチャ** (partition/VM + vCPU + guest 物理 map +
  VM-exit loop + register 同期)。差は「ioctl ⇄ FFM downcall の API 名」だけ。
  KVM で動く設計を確立すれば WHP 移植は API 置換に縮小する。
- software / native / **両 hypervisor (KVM/WHP)** の 3-way oracle が組める。

→ **方針**: Phase 0 の残りは **KVM-first** で WSL2 内完結。WHP 移植は KVM 経路が
hello64 まで通った後に行う。`docs/issue221-design-plan.md` §4 の OS 優先順
(Windows WHP 第一) は本転換で **Linux KVM 第一**に更新。

### 4.4 Phase 0 step 3a — KVM hello world (✅ 本 PR、WSL2 で実証)

WSL2 nested KVM 上で Java FFM が実 vCPU を起動できることを smoke 実証。

新規:
- **`KvmBindings.java`** (~330 行): `/dev/kvm` + libc (`open`/`close`/`ioctl`/
  `mmap`/`munmap`/`__errno_location`) の FFM bindings。KVM ioctl request-number
  定数 (`_IOC` encoding、KVMIO=0xAE)、struct byte-offset 定数 (`kvm_regs` /
  `kvm_segment` / `kvm_dtable` / `kvm_sregs` / `kvm_run` /
  `kvm_userspace_memory_region`)、`KVM_EXIT_*` 定数。`probe()` で `/dev/kvm`
  open 可否を確認 (WhpBindings と同じ設計)。
- **`KvmSmoke.java`** (~230 行): standalone `main()`。`KVM_GET_API_VERSION` (==12)
  → `KVM_CREATE_VM` → 4KB host buffer に `90 90 90 F4` (NOP×3 + HLT) を書いて
  `KVM_SET_USER_MEMORY_REGION` で guest 物理 0 に map → `KVM_CREATE_VCPU` →
  `KVM_GET_VCPU_MMAP_SIZE` + `mmap` で `kvm_run` 取得 → `KVM_GET_SREGS` で
  `cs.base=0`/`cs.selector=0` (16-bit real mode、reset の F000:FFF0 を上書き) →
  `KVM_SET_SREGS` → `rip=0`/`rflags=2` → `KVM_SET_REGS` → `KVM_RUN`。

**実証結果 (WSL2、2026-06-07)**:
```
[KvmSmoke] KVM_GET_API_VERSION = 12
[KvmSmoke] KVM_CREATE_VM vm_fd=5
[KvmSmoke] KVM_SET_USER_MEMORY_REGION OK (4KB @ guest 0x0)
[KvmSmoke] KVM_CREATE_VCPU vcpu_fd=6
[KvmSmoke] vcpu_mmap_size = 12288
[KvmSmoke] KVM_RUN returned rc=0 elapsed=108275ns
[KvmSmoke] exit_reason = 5 (KVM_EXIT_HLT)
[KvmSmoke] rip after = 0x4
KVM smoke OK: exit_reason=5 (KVM_EXIT_HLT), rip=0x4, ...
```
guest 命令 (NOP×3 + HLT) が**実 CPU で実行**され、rip が 4 に進み、HLT で
VM-exit した。= #221 の中核仮説「user code を実 vCPU で走らせ VM-exit で制御を
取り戻す」の最小実証。

起動: `java --enable-native-access=ALL-UNNAMED -cp target/classes emulin.KvmSmoke`
(JDK 25 で `allocateUtf8String` は `allocateFrom` に rename された点に注意)。

回帰: run-fast 220 / run-network 15 全 PASS (smoke は独立 entry point、本体無影響)。

### 4.4b Phase 0 step 3b — KVM 64-bit long mode (✅ 本 PR、WSL2 で実証)

step 3a (16-bit real mode + NOP+HLT) の次。guest を **64-bit long mode** に入れて
64-bit 命令を実 vCPU で実行し、64-bit register 幅が正しく扱えることを実証。これで
long mode 起動 (制御レジスタ + EFER + identity page table + GDT-less segment) という
最も間違えやすい部分を de-risk する。

新規/拡張:
- **`KvmBindings.java` 拡張**: `kvm_segment` sub-offset (DPL/DB/S/L/G/AVL/UNUSABLE)、
  x86-64 long-mode architectural 定数 (CR0_PE/MP/ET/NE/WP/AM/PG + `CR0_LONG_MODE`
  合成値 0x80050033、CR4_PAE、EFER_SCE/LME/LMA/NXE、PTE_P/RW/US/PS、SEG_TYPE_CODE/DATA)。
- **`KvmSmoke64.java`** (~250 行): off-heap 2MB guest RAM (Arena.allocate、Java heap
  byte[] でなく native segment ← step 3a review 申し送り)。identity 2MB page
  (PML4@0x1000 / PDPT@0x2000 / PD@0x3000、PD[0] に PTE_PS で 2MB page) で [0,2MB)
  を identity map。CR0=LONG_MODE / CR3=PML4 / CR4=PAE / EFER=LME|LMA、cs.l=1 の
  GDT-less 64-bit code segment を `KVM_SET_SREGS` で直設定 (KVM が descriptor cache を
  load するので in-memory GDT walk 不要)。guest code:
  `movabs rax, 0x1122334455667788; mov rbx, rax; hlt`。

**実証結果 (WSL2、2026-06-07)**:
```
[KvmSmoke64] long mode sregs set (CR0=0x80050033 CR3=0x1000 CR4=PAE EFER=LME|LMA cs.l=1)
[KvmSmoke64] exit_reason = 5 (KVM_EXIT_HLT) elapsed=240118ns
[KvmSmoke64] rax=0x1122334455667788 rbx=0x1122334455667788 rip=0xe
KVM64 smoke OK: long mode + 64-bit exec verified ...
```
`movabs imm64` (64-bit 即値) と `mov rbx,rax` (64-bit register move) が long mode で
実行され、`rax==rbx==0x1122334455667788`、`rip==0xE`。= long-mode 設定 (CR0/CR3/CR4/
EFER + PAE page table + 64-bit segment) が全て正しいことの実証。16-bit real mode では
`movabs imm64` は動かないので、これは確かに 64-bit 実行の証明。

起動: `java --enable-native-access=ALL-UNNAMED -cp target/classes emulin.KvmSmoke64`

回帰: KvmSmoke (16-bit) も無傷、run-fast 220 / run-network 15 全 PASS。

### 4.4c Phase 0 step 3c — ★ ring-3 syscall trap 実証 + latency 計測 (✅ 本 PR、go/no-go)

#221 native backend の**中核機構**=「guest user code (ring 3) の `syscall` を
VM-exit で VMM (emulin Java 層) にトラップし sysno/引数を回収する」ことを実 vCPU で
実証し、**1 syscall あたりの trap round-trip latency を実測**した。

新規/拡張:
- **`KvmBindings.java`**: `KVM_SET_MSRS`(0x4008AE89)/`KVM_GET_MSRS`(0xC008AE88)、
  `kvm_msrs`/`kvm_msr_entry` offset、MSR index (EFER/STAR/LSTAR/SYSCALL_MASK/
  KERNEL_GS_BASE 等)。
- **`KvmSyscallSmoke.java`** (~290 行): 4KB page table 全 level PTE_US (ring-3 実行可)、
  EFER.SCE + STAR/LSTAR/FMASK を `KVM_SET_MSRS` で設定、cs/ss dpl=3。
  - user code (ring 3): `mov eax,0xCAFE; mov edi,0xBEEF; syscall; jmp back`
  - LSTAR stub (ring 0): `hlt` (→KVM_EXIT_HLT で trap) `; sysretq` (ring 3 復帰)
  - STAR=0x0023001000000000 (Linux 規約: syscall→kernel CS=0x10/SS=0x18、
    sysretq→user CS=0x33/SS=0x2b)。`syscall` は stack を触らないので user/kernel
    stack 最小。1 KVM_RUN = 1 完全 round-trip (sysretq→ring3→syscall→hlt→exit)。

**実証結果 (WSL2 nested KVM、2026-06-07)**:
```
exit_reason=5(HLT) rax=0xcafe(sysno) rdi=0xbeef(arg0) rcx=0x600c(retaddr) rip=0x7001
✓ syscall trap OK: ring-3 syscall trapped to VMM, sysno/arg0/return-addr captured.
latency over 200000 traps (stable across runs):
  (A) pure VM round-trip          = ~6.0-6.4 µs/syscall
  (B) + explicit GET/SET_REGS  : mean ~7.6-8.1 µs/syscall (上限、sync_regs で縮む)
```
benchmark は各 iteration で `exit_reason==KVM_EXIT_HLT` を assert (triple fault→SHUTDOWN が
rc=0 で silently カウントされ平均を歪めるのを防ぐ)。p99/max の裾 (~18µs/~170µs) は nested
host の scheduling steal。

#### ★ go/no-go 判定 (step 3c review #243 で精緻化)

- **機構 (mechanism): 明確に GO** ✓ — ring-3 `syscall` が VM-exit で VMM にトラップし、
  sysno (RAX) / arg0 (RDI) / return-addr (RCX) を回収できることを実証。#221 の核心仮説の
  決定的実証。
- **latency: median ~5.8µs round-trip (nested WSL2 KVM)**、raw 5µs gate を超過。caveat:
  1. **nested 仮想化で inflate**。host は WSL2 (=Hyper-V L1)、KVM guest は **L2**
     (dmesg: `KVM: vmx: using Hyper-V Enlightened VMCS` / `Hyper-V: Nested features`)。
     nested VM-exit は L1↔L0 遷移コストが上乗せ。**bare-metal Linux KVM / Windows WHP
     (L1、非 nested) では通常 ~1-3µs (KVM_RUN round-trip ~1500-6000 cycle)**。ただし
     Spectre/MDS mitigation 設定に大きく依存するので**測定必須 (仮定不可)**。
  2. realistic (B) の +~1.7µs は `GET_REGS`+`SET_REGS` の 2 ioctl。**`KVM_CAP_SYNC_REGS`**
     (GP regs を `kvm_run` mmap に露出) で ioctl を消せば round-trip floor に近づく。step 3d。
- **★ raw 5µs gate は誤った物差し (review の核心指摘)**。正しい判定基準は
  **native backend vs 既存 software interpreter の break-even**:
  - 両 backend は同じ `SyscallAmd64.call_amd64` を共有 = syscall の **実処理コストは同一**。
    native 化の**限界コストは trap latency T のみ** (interpreter が syscall 1 命令を decode
    する ~0.1-0.5µs を引いた分)。**便益は syscall 以外の全命令が native 速度 (タダ) になる**
    こと (software は 1 命令ずつ interpret、load8 ~30%、~30ns/命令)。
  - break-even ≈ **T / per-insn-emulation-cost** ≈ 6µs / 30ns ≈ **~200 命令/syscall**。
    実コードの多くは syscall 間に数千命令走る → **nested 6µs でも compute-heavy/中程度
    workload では既に software に勝つ見込み** (HTTPS/ASN.1 #190 の動機 path 含む)。
  - 逆に **syscall-heavy** は要注意: 秒間 S syscall で trap overhead ≈ S×T/s。T=7µs だと
    1 core は **S≈143k syscall/s で飽和** (S=50k→35%/S=100k→70% overhead)。shell pipeline /
    emacs pselect-storm (#206) は 50k-500k syscall/s に達し得る → これらは per-syscall cost を
    下げない限り (SYNC_REGS + bare-metal) trap overhead 支配。
- **結論: conditional GO**。**機構は実証済 (GO)**。**compute-bound は nested でも勝ち筋**。
  ただし「bare-metal に defer」を **PASS と読んではいけない** — syscall-heavy/interactive の
  feasibility は **(a) 非 nested 再測定 (step 3f) + (b) 代表 workload の命令/syscall 比の
  プロファイル**が揃うまで **未解決**。最終 ship 判定はこの 2 つを blocker とする。

起動: `java --enable-native-access=ALL-UNNAMED -cp target/classes emulin.KvmSyscallSmoke`

回帰: KvmSmoke/KvmSmoke64 無傷、run-fast 220 / run-network 15 全 PASS。

### 4.4d Phase 0 step 3d-1 — NativeMemoryBackend (guest RAM の双方向 view、✅ 本 PR)

step 3d (native backend を emulin 本体に統合) の**メモリ半分**。HW 仮想化 backend の
guest 物理 RAM を表す `MemoryBackend` 実装を作り、KVM がマップする同一 off-heap
segment を emulin が読み書きできることを実証。

新規:
- **`NativeMemoryBackend.java`**: off-heap `MemorySegment` (= KVM が
  `KVM_SET_USER_MEMORY_REGION` でマップする guest 物理 RAM) を直 index する
  `MemoryBackend`。load/store 8-64 は `ValueLayout.JAVA_*_UNALIGNED` (native LE =
  x86 guest と一致、非整列 guest pointer も可)、bulk は `MemorySegment.copy`
  (arraycopy intrinsic)、storeString/loadString/in/dump を実装。identity map
  (guest 仮想=物理=offset) 前提。alloc/mmap/brk/sigtramp/proc-maps 等の VM 管理・
  ELF 系は **step 3d-2 で実装** (現状 `UnsupportedOperationException` stub)。
- **`NativeMemBackendSmoke.java`**: 双方向 bridge 実証。emulin が
  `backend.store64`/`bulkStoreToMem` で **page table + code + seed(0x41)** を guest
  RAM に書く → KVM が同 segment を map し long mode で
  `mov rax,[0x8000]; inc rax; mov [0x8000],rax; hlt` 実行 → emulin が
  `backend.load64(0x8000)` で読み戻す。

**実証結果 (WSL2、2026-06-07)**: `exit_reason=5(HLT) rax=0x42 rip=0x6014
backend.load64(0x8000)=0x42`。emulin が backend で書いた page table を CPU の
page-walk が読み (= long mode 起動成功)、backend で書いた code を guest が実行し、
guest の write を backend が読み戻せた = **NativeMemoryBackend は guest RAM の
忠実な双方向 view**。

起動: `java --enable-native-access=ALL-UNNAMED -cp target/classes emulin.NativeMemBackendSmoke`

回帰: 全 4 KVM/native smoke OK、run-fast 220 / run-network 15 全 PASS
(NativeMemoryBackend は MemoryBackend impl 追加のみ、本体無影響)。

### 4.4e Phase 0 step 3d-2: ★ 実 ELF を native 実行 + oracle (✅ 本 PR — Phase 0 の山場)

**`-nostdlib` 静的 ELF (hello64) を実 vCPU でネイティブ実行し、syscall を emulin の
`SyscallAmd64.call_amd64` に流して software backend と byte 一致の stdout を出した。**
= #221 の核心 (「user code を実 CPU で走らせ syscall だけ emulin にトラップ」) が
実バイナリで end-to-end 成立。

実装:
- **`NativeCpuBackend.java`** (stub → 本実装): `extends AbstractCpu`。
  - `connect_devices(softMem, syscall)`: off-heap guest RAM (16MB) を確保し
    `NativeMemoryBackend` で包む → software Memory の ELF segment[] を guest 物理
    (= vaddr、identity) に copy → 2MB identity page table [0,16MB) + LSTAR `hlt`
    スタブを書く → `syscall.connect_mem(guestMem)` で syscall 層の mem を guest RAM に
    向ける (3d-2a の widen で可能に)。高位 stack segment (0x7ffe…) は skip。
  - `eval()`: KVM (VM/vcpu/long mode ring 0/EFER.SCE/STAR/LSTAR) を起動し
    `KVM_RUN` loop。`KVM_EXIT_HLT` (= `syscall`→LSTAR スタブ) で
    rax(sysno)/rdi/rsi/rdx/r10/r8/r9(args)/rcx(復帰先) を読み
    `sys64.call_amd64(...)` に dispatch。exit (60/231) で `process.is_exited()` 検知
    →終了。それ以外は rax=戻り値 / rip=rcx で resume (ring 0 なので sysret 不要)。
    EINTR retry、非 HLT exit は診断 + exit_code=127、teardown で KVM handle 解放。
- **`Process.java`**: `(Cpu64)cpu` cast (fs_base/stack_data_init64/resolve_irelative/
  r64 ゼロクリア) を `if (cpu instanceof Cpu64 cpu64) {...}` で包む。software 経路は
  byte 一致 (instanceof で包んだだけ)、NativeCpuBackend は skip (guest state は
  connect_devices/eval で別途構築)。
- **`CpuBackend.java`**: native 可用性を `KvmBindings.probe()` (Linux KVM) に。
  `verifyImplemented()` は KVM 可用なら true (eval 実装済)、`createCpu64` は
  `NativeCpuBackend` を返す。banner `[backend=native (KVM detected (/dev/kvm OK))]`。

**実証結果 (WSL2、2026-06-07)**:
```
$ EMULIN_BACKEND=native emulin <sandbox> /bin/hello64
hello world
$ diff <(EMULIN_BACKEND=software ...) <(EMULIN_BACKEND=native ...)  → 一致 (oracle PASS)
```
trace: segment copy (0x400000/0x401000/0x402000…)、stack skip、syscall #1 write
(sysno=1, buf=0x402000, len=12)→"hello world\n"、syscall #2 exit(60)→exit_code=0。

回帰: **`tests/scripts/native-oracle.sh`** を新設 (KVM 無し環境は SKIP)、run-network に
登録。run-fast 220 / run-network **16** PASS (software 経路は instanceof guard で無影響)。

**adversarial review (24 agent / 5 dimension × 2 skeptic verify) 反映**: 9 件 confirmed
(refute 0)、dedup 後 7 件。must-fix 2 件 + 防御的 hardening を本 PR で対処:
- **(CRITICAL) `setupKvm()` の資源 leak**: setupKvm が独立 try-catch にあり、kvmFd/
  vmFd/vcpuFd/vcpuState 確保後に途中失敗すると `teardownKvm()` の finally に到達せず
  fd + mmap が leak。→ setupKvm を teardown と同じ try/finally 配下に移動 (`setupDone`
  flag で setup 失敗と eval 失敗のメッセージを区別)。
- **(HIGH) factory の非対称**: `createCpu` (i386) だけ `WhpBindings.probe()` のまま残り、
  KVM 環境で誤った "WHP probe failed" を出す。→ sibling `createCpu64` と同じ
  `nativeAvailable()` に統一。
- **(防御) segment hardening + ★ PT_LOAD filter (review 反映中に発覚した hang の真因)**:
  当初 review の助言どおり「予約低位域 [0,64KB) (page table/LSTAR) と重なる segment を
  `IllegalStateException` で弾く」を入れたところ、**hello64 が 22 分 hang**。原因は
  `connect_devices` が **全 program header を copy しようとしていた**こと: hello64 には
  `vaddr=0` の PT_GNU_STACK (p_type=0x6474e551) があり、新 guard がそれを throw → boot
  path で main thread は死ぬが **非 daemon の init 監視 thread (Process.run の while(true))
  が生き残り JVM が終了せず hang**。真の修正は **software loader (Memory.java:559) と同じく
  `p_type==1` (PT_LOAD) のみ copy する filter** で、これにより PT_PHDR/PT_NOTE/PT_GNU_*
  を除外し native/software のロードが完全一致 (oracle の前提)。加えて: 予約低位域 overlap /
  filesz>INT_MAX / entry RIP 範囲外 の fatal 系は **throw でなく `System.exit(127)`**
  (`fatalUnsupported`) にした — emulin の boot path では throw が上記の hang を招くため。
  overflow-safe な範囲 check (`va+memsz` 符号反転回避) も追加。**教訓: emulin の boot/init
  path で例外を投げると非 daemon init thread が JVM を生かし続け hang する。native setup の
  fatal は System.exit で落とす。**
- **(意図の明文化) FMASK=0**: no-sysret モデル (resume は RIP=rcx のみ、RFLAGS 復元なし)
  では FMASK で IF/DF を毎回 clear すると復元手段が無く RFLAGS が単調に壊れるため、
  RFLAGS を一切触らない FMASK=0 が正。step 3d-2c (ring-3 + sysret) で 0x47700 に見直す
  旨をコメント化 (review の "0x47700 にせよ" は現状モデルでは誤りなので不採用)。
nice-to-have の残り (INIT_RSP stack が深いと page table と衝突し得る等) は step 3d-2c の
低位 RAM 再配置で対処予定。

### 4.4f Phase 0 step 3d-2c-1: ★ hello64 を ring 3 で実行 (sysretq 往復)

**3d-2 は ring-0 だったのを ring-3 (= 実 Linux user code の実行特権) に格上げ。** 3c smoke
(KvmSyscallSmoke) で実証済の syscall/sysretq 往復を実バイナリ hello64 に適用。oracle は
同じ (software と byte 一致)。

実装 (`NativeCpuBackend.java` のみ):
- **page table US 分離**: US は全 paging level の AND なので PML4/PDPT を US=1 に。leaf (PD)
  で per-2MB に US 制御: **PD[0] ([0,2MB)) は US=0 (supervisor)** = page table 自身 +
  LSTAR stub を ring-3 から隠す、**PD[1..7] は US=1 (user)**。3c review の US-isolation を実装。
- **user stack を US 領域へ**: INIT_RSP 0x100000 (PD[0]/supervisor) → **0x300000 (PD[1]/user)**。
- **LSTAR stub = `hlt; sysretq`** (F4 / 48 0F 07)。syscall→hlt で VM-exit→call_amd64→
  RIP=stub 内 sysretq で resume。sysretq が RCX→RIP / R11→RFLAGS / CS=0x33 で ring3 復帰。
  **RCX (user 復帰先) / R11 (退避 RFLAGS) は絶対に書き換えない** (call_amd64 も regsBuf 不変)。
- **sregs ring-3**: 初回 entry を CS=0x33 (RPL3)/SS=0x2b (RPL3)、全 **DPL=3** で設定。
  `setSeg` に dpl 引数追加。STAR は 3d-2 と同値 (syscall→0x10、sysretq→0x33/0x2b)。
- **FMASK=0 のまま**: syscall が R11←RFLAGS で退避、sysretq が R11→RFLAGS で復元するので、
  hlt;sysretq だけの stub では RFLAGS clear 不要 (real Linux の 0x47700 は kernel handler 用)。

**ring-3 の決定的検証 (否定対照)**: PML4/PDPT の US を外すと全 user page が ring-3 から
到達不能になり **user entry 0x401000 で 0 syscall のうちに triple fault (exit_reason=8
KVM_EXIT_SHUTDOWN)**。ring-0 なら US 無視で素通りするので、これは guest が CPL=3 で
走っている証明。通常 (US 有) は hello64 が ring-3 で完走し oracle PASS。

回帰: run-fast 220 / run-network 16 / oracle PASS (software 経路無影響)。

**adversarial review (20 agent / 3 dim × 2 skeptic) = 実バグ 0**: 6 件の「STAR[63:48]=0x23 と
初期 CS=0x33 が不一致」指摘は **SYSRETQ(64-bit) の selector 算術 `user CS = STAR[63:48] + 16`
を見落とした false positive** (synthesis lead が正しく棄却)。0x23+16=0x33 で一致、Linux の
`__USER32_CS=0x23` と同値。提案 fix (STAR を 0x33 に) を適用すると CS=0x43 になり壊れる。
6 agent が揃って誤読したため `STAR_VALUE` に `+16/+8` 規則を明示するコメントだけ追加。

### 4.4g Phase 0 step 3d-2c-2: ★ guest RAM に初期 stack (argc/argv/envp/auxv) を構築

**native backend は今まで空 stack で起動していた (RSP が未書き込み guest RAM を指す) ので、
引数を読む binary は動かなかった。** System V x86-64 初期プロセス stack
(`argc`/`argv[]`/NULL/`envp[]`/NULL/auxv pairs/AT_NULL/文字列) を guest の 16MB RAM に構築し
RSP を argc に向ける。full auxv を組むので glibc-ready。

設計 — **software と共有する stack builder を抽出 (divergence 回避)**:
- `Process.stack_data_init64` (software 専用、`mem` に書き `mem` から ELF メタデータを読む) を
  thin wrapper 化し、新 static `buildInitialStack64(MemoryBackend mem, sp64, args, envs, Memory elf)`
  に抽出。**stack バイトは `mem` (MemoryBackend) へ、ELF メタデータ (segments/e_phoff/e_phnum/
  interp_base/e_entry) は `elf` (Memory) から読む** (これらは MemoryBackend interface に無い)。
  software は両方に `this.mem` を渡すので byte 不変。
- native は `buildInitialStack64(guestMem, GUEST_STACK_TOP, args, envs, softwareMemory)` で呼ぶ。
  stack は **PD[1] ([0x200000,0x400000)) の US 領域** (code 0x400000 の直下、`GUEST_STACK_TOP=0x3ff000`
  から下方成長、関数 frame は更に下方 ~0x200000 まで 2MB)。argv/envp/auxv の pointer 値は guest
  仮想=物理 (identity) なので guest が直接読める。AT_PHDR=elf_base+e_phoff は connect_devices が
  copy した ELF header を指す。
- `NativeCpuBackend`: `set_sp`/`get_sp` を `rsp` field で実装、`setup_initial_stack(args,envs)` を
  Process.run の native 経路 (connect_devices 後) から呼ぶ。stack が PD[1] に収まらなければ
  (`sp < PD1_BASE`) `fatalUnsupported`。setupKvm が初期 RSP に `rsp` を使う。

**検証 — `argvdump64` (argc/argv/envp を stack から読んで dump する -nostdlib binary)**:
`argvdump64 foo bar` を native で実行すると `argc=3 / argv[0..2] / env[0..4]` を **software と
byte 一致**で出力 → 初期 stack 構築が正しいことの実証。native-oracle.sh を hello64 + argvdump64
の 2 binary に一般化。

**adversarial review (4 agent / 3 dim) = 実バグ 0** (synthesis: "Ship it")。nice-to-have を反映:
connect_devices が `va < RESERVED_LOW` (0x10000) しか弾かなかったため、仮に PT_LOAD が新 stack
領域 [0x200000,0x400000) に来ると silent に衝突する (3d-2 の hang と同種の silent corruption)。
**guard を `va < CODE_BASE` (0x400000) に broaden** — PT_LOAD は code 領域 [0x400000,16MB) のみ、
低位 (page table/LSTAR + user stack) は予約として loud に弾く。

回帰: run-fast 220 / run-network 16 / oracle (hello64+argvdump64) PASS / 0 FAIL。

### 4.4h Phase 0 step 3d-2c-3: ★ SSE 有効化 (CR4.OSFXSR) + 起動時 GPR zero クリア

**static-glibc binary を native で走らせる scout で見つけた 2 つの起動状態バグを修正。**
glibc 静的 binary (`hello_static64`) を native で実行すると **CPU feature 検出の SSE 命令で即
triple fault** していた (rip=0x402f35 `movd …,%xmm1`)。

修正:
- **CR4.OSFXSR (+OSXMMEXCPT)**: vCPU の CR4 が PAE のみで **SSE 無効**だった。SSE 命令
  (movdqu/paddd/movd 等) が `#UD`→IDT 無し→triple fault。`CR4_OSFXSR|CR4_OSXMMEXCPT` を追加。
  hello64/argvdump64 (-nostdlib) は SSE 未使用なので顕在化していなかった。
- **起動時 GPR zero クリア**: native は KVM reset 値 (rdx=family/model 等) を残していた。Linux の
  プロセス起動契約は rsp/rip 以外の全 GPR=0 (rdx=0 は rtld_fini 無しを意味する)。`regsBuf.fill(0)`
  で全 GPR を 0 にしてから rip/rsp/rflags を設定 (software 経路の `for(i) cpu64.r64[i]=0` と等価)。

**検証 — 新 `simd64` (-nostdlib SSE テスト)**: `movdqu`+`paddd` で {40,1}+{2,41}={42,42} を計算し
"simd:42,42" を出力。CR4.OSFXSR 無しなら native で #UD→triple fault するので、native==software
oracle が SSE 有効化の clean な実証。`tests/expected/simd64.stdout` で software 回帰も常設。

**static-glibc scout の知見 (後続 step の roadmap)**: SSE+GPR 修正後、hello_static64 は CPU
feature 検出を通過し `__libc_setup_tls` (rip=0x404234 `add (%r14),%rdx`、r14=`[_dl_ns]`) まで前進。
残る blocker は深い多段で **1 PR には収まらない**: (1) **IRELATIVE reloc** (22 個、software は
`resolve_irelative` で resolver を実行して GOT を埋めるが native は未適用)、(2) **TLS/_dl_ns setup**、
(3) **arch_prctl(ARCH_SET_FS)** で guest FS base を KVM MSR にセット、(4) **brk** (16MB 内なら
page table 成長不要、NativeMemoryBackend が curbrk を tracking するだけ)、(5) **mmap** (16MB 超なら
guest 物理割当+page table 成長)、(6) 必要なら **KVM_SET_CPUID2**。これらを後続 PR で段階的に。

回帰: run-fast **221** (simd64 +1) / run-network 16 / oracle (hello64+argvdump64+simd64) PASS / 0 FAIL。

### 4.4i Phase 0 step 3d-2c-4: ★★ 初の実 glibc 静的 binary が native 完走 (hello_static64)

**`hello_static64` (glibc を static link した普通の `int main(){puts("hello static");}`) が native
KVM backend で完走し "hello static" を出力、software と byte 一致。** = #221 が「合成 ELF」でなく
**実用 toolchain の出力 binary** で初めて成立した大マイルストーン。17 syscall (brk×5 / arch_prctl /
set_tid_address / set_robust_list / rseq / prlimit64 / readlinkat / getrandom / mprotect / fstat /
ioctl / write / exit_group) を全て共有 `call_amd64` で処理。**mmap は不要** (puts() の static binary は
brk のみで heap を賄う)。

scout (実 binary を native で走らせ triple fault rip を objdump 逆アセンブル→1 修正→反復) で 3 つの
blocker を順に解消:
- **★ page_offset コピー修正 (connect_devices、最重要)**: emulin は非 page-align な p_vaddr を
  page 境界に切り下げ、segment buf を `filesz + (p_offset & 0xFFF)` で確保する (Elf.java
  cacheSegments)。connect_devices は `filesz` だけコピーしていたため、glibc static の RW segment
  (vaddr=0x4a5f50→0x4a5000) で **file-backed 末尾 page_offset 分 (0xf50 byte、.data 末尾の
  `_dl_ns` 等) が欠落** → guest が 0 を読み `__libc_setup_tls` の `add (%r14),%rdx` (r14=`[_dl_ns]`=0)
  で address 0 (supervisor) アクセス→triple fault。修正=コピー長を `filesz + page_offset` に。
  非 page-align segment を持つ全 binary に効く汎用バグ。
- **brk (NativeMemoryBackend)**: `get_curbrk`/`set_curbrk` を curbrk pointer tracking で実装
  (16MB 内は identity-map 済なので page table 成長不要)。初期 curbrk は connect_devices が
  software Memory の ELF 由来 brk で seed。16MB 超は false→glibc に mmap fallback させる。
- **arch_prctl(ARCH_SET_FS) を backend 非依存化**: 旧 handler は `(Cpu64)cpu.fs_base = addr` で
  native だと ClassCastException。`AbstractCpu.set_fs_base/get_fs_base` を追加し Cpu64 は fs_base
  field、**native は guest vCPU の `MSR_FS_BASE` を KVM_SET_MSRS で更新** (eval thread が vcpuFd を
  所有するので syscall trap ハンドラから直接発行可)。

+ 起動時の非 HLT exit 診断に全 GPR dump を追加 (triple fault の faulting 命令特定を高速化)。

回帰: run-fast **221** (software 経路は arch_prctl の AbstractCpu 化で byte 不変) / run-network 16 /
oracle (hello64+argvdump64+simd64+**hello_static64**) PASS。次は **動的リンク (ld.so/PIE) + mmap +
signal/pthread + 多 binary** で実用 binary (busybox/coreutils) へ。

### 4.4j Phase 0 step 3d-2c-5: static-glibc スイート全体を native oracle に追加 (test-only)

3d-2c-4 の修正 (page_offset / brk / arch_prctl) で **既存の static-glibc テスト binary 6 つが追加
コード無しで全て native==software** になっていたので、native-oracle.sh に追加して恒久 regression
guard 化:
- `ctype_static64` (ctype 表) / `fgetc_static64` (fgetc+stdin、< /dev/null で EOF 固定) /
  `varexp_repro64` (printf) / `bb_decode_static64` (base64 decode)
- **`aesni_static64` / `sse_audit64`**: ★実 CPU の AES-NI / SSE 命令が emulin の software emulation
  (FIPS-197 host 一致の AES、SSE) と **byte 一致** = emulation を実 HW で cross-validate。

oracle_one に `< /dev/null` を追加し stdin を deterministic な EOF に。emulator コード変更ゼロの
test-only 拡張。oracle は 10 binary (3 -nostdlib + 7 static-glibc) を検証。

### 4.4k Phase 0 step 3d-2c-6: ★★★ native vs software 性能実測 (#221 核心仮説の検証)

**実 binary が native で動くようになったので、#221 の核心仮説「非 syscall 命令を実 CPU で走らせる
便益 > syscall trap コスト」を初めて実測した。結果は強い GO。**

測定 (`tests/scripts/bench-native.sh` + 新 `bench64.c` = FNV ハッシュ N 反復、syscall は argv 読み +
write + exit のみで trap 極小): backend 別に N を変え (native は software の数百倍速く同一 N だと
native の compute が計測ノイズ以下になるため)、compute 時間 = total(N) − total(baseline) で JVM 起動 +
KVM setup を除去:

```
software : N=5e7    compute=25.94s  =>     1,927,808 iter/s
native   : N=1e10   compute=24.05s  => 415,854,623 iter/s
  ===> native compute は software の 215.7x 速い
correctness: native==software (N=5e7 で h=0x7d78910a76f02b93、byte 一致)
```

**= compute-heavy workload で native は software emulation の ~216 倍速い** (WSL2 nested 計測)。

**go/no-go への含意 (§4.4c の conditional GO を更新)**:
- §4.4c の break-even 分析「native の限界コストは syscall trap T のみ、便益は syscall 以外の全命令が
  native 速度」を実数で埋められた。per-instruction の節約 ≈ (1/1.93M − 1/416M)×(命令/iter) ≈ ループ
  1 iter (~10 命令) あたり ~0.5µs の節約。syscall trap T は step 3c で nested ~6µs。
  → **break-even ≈ T / per-syscall-間の節約 ≈ syscall 間 ~100 命令前後 (nested)**。bare-metal/WHP では
  T が ~1-3µs に下がるので break-even は ~20-50 命令/syscall。
- 実 workload (compute / crypto / parse) は syscall 間に数百〜数千命令あるのが普通 → **大半で native 勝ち**。
  syscall-storm (poll loop #206 等) のみ break-even 付近で要注意 (§4.4c の通り per-syscall 削減で対応)。
- **conditional GO → compute-bound では明確に GO**。残る不確定は (a) bare-metal の絶対 trap latency
  (3f)、(b) syscall-heavy workload の命令/syscall 比 (実 binary で profile 可能になった)。

`bench64` は native-oracle (small N=10000、correctness) + run-fast fixture に追加。性能測定 (large N、
~50s) は bench-native.sh で手動 / go-no-go 報告用 (回帰には入れない)。

### 4.4l Phase 0 step 3d-2c-7: anonymous mmap (NativeMemoryBackend) — 動的リンクの前提

**guest RAM [0,16MB) は identity-map 済 + Arena 0 初期化済なので、anonymous mmap は backing copy も
page table 成長も要らず、未使用領域を bump-allocate して guest アドレスを返すだけ。** brk と並んで
動的リンク (ld.so の anon map) + large malloc (>128KB は glibc が mmap) の前提。

実装 (`NativeMemoryBackend.anonMmap`):
- mmap 領域は **guest RAM 上端 (16MB) から下方** へ bump、heap は curbrk から上方 (Linux 同様に互いに
  向き合う)。`newTop = mmapTop - page_align(sz); if (newTop < curbrk) ENOMEM`。curbrk (≥ ELF brk
  ~0x4b2000 > 0x400000) を floor にするので mmap は常に PD[2..7] の US 領域に入る。
- `alloc_and_map(fd>=0)` = file-backed mmap (ld.so の .so map) は **動的リンク step で実装** (今は throw)。
  `alloc_huge` (>2GB、JSC gigacage) は 16MB に入らず ENOMEM。`free` (munmap) は bump-allocator なので
  no-op success (sys_munmap が元々常に 0 を返す)。`prot` は page table 一律 RW なので無視 (PROT_NONE
  guard page は未対応)。
- 検証: 新 `mmap64.c` (-nostdlib、8KB anon mmap → 2 ページに read/write → munmap) が native で
  "mmap: MAPZ" を出力、software と byte 一致 (mmap は 0xFFE000 = 16MB-8KB を返す)。

**adversarial review (13 agent / 2 dim) = must-fix 1**: `set_curbrk` が `mmapTop` 衝突を見ていなかった。
anonMmap は mmap が heap へ降りるのを `newTop < curbrk` で弾くが、`set_curbrk` は `sizeBytes()` しか
見ず、**brk が heap を mmapTop より上 (live mmap 領域内) に伸ばせて silent corruption** (glibc malloc は
brk+mmap 混在なので実 binary で踏む)。→ `set_curbrk` に対称な `_brk > mmapTop` ガードを追加 (境界 touch
は disjoint なので許容、mmap 未使用 mmapTop<0 は据置)。

回帰: run-fast **223** (mmap64 fixture +1) / native-oracle (mmap64 含む) PASS。software 経路は
NativeMemoryBackend が native 専用なので無影響。

### 4.4m Phase 0 step 3d-2c-8: ★★★ 非 identity MMU リワーク (動的リンクの土台)

**16MB identity-map (guest 仮想=物理) を捨て、非 identity な 4-level 4KB page table で疎な
guest 仮想 (0x400000 の ELF / 0x7ffff7... の ld.so / 0x7fff... の stack) を compact な off-heap
物理プールに乗せる MMU に作り変えた。** = 動的リンク (ld.so/共有ライブラリは高位仮想を使い
16MB identity では届かない) の土台。#221 で最大の単一実装。**user が「本物の MMU リワークに投資」を選択。**

設計 (3 ファイル):
- **`NativeMemoryBackend` を MMU + 物理メモリに昇格**: 物理プール [PT_BASE=0x1000, DATA_BASE=0x800000)
  に page table (PML4@物理 0x1000)、DATA_BASE 上に data ページを bump 割当。`mapPage(vaddr,phys,user)`
  が PML4→PDPT→PD→PT を walk/lazy 割当 (中間 entry US=1、leaf US=user 引数)。`virt2phys` は
  page table walk + 単一 TLB (page table が単一の真実)。load/store は xlat 変換、page 跨ぎは byte
  分解。bulk は page 境界ループ。brk は仮想アドレス (seedBrk + set_curbrk で成長時に mapRange)、
  mmap は高位帯 (MMAP_BASE=0x7ffff0000000 から下方) に仮想を取り map (heap と仮想空間が分離)。
- **`NativeCpuBackend`**: connect_devices で各 PT_LOAD を mapRange + copy、LSTAR stub を supervisor
  仮想 (STUB_VADDR=0xff000) に map、CR3=guestMem.pml4Phys()、KVM region=64MB プール全体。stack は
  software と同じ 0x7fff_0000_0000 に (stack segment は PT_LOAD ループで map)。
- **`Process.buildInitialStack64` (software と共有)**: ★ ABI stack 16-align pad を追加。総 word 数の
  parity (envc+argc) が偶数のとき 8 byte pad し _start での RSP を 16-align する。**無いと ld.so の
  `movaps …,-0x80(%rbp)` が real CPU で #GP** (software は SSE を緩く扱い顕在化しないが ABI 違反)。
  software は glibc が _start で再 align するので無害 (run-fast 223 PASS)。

**検証**: 全 12 native-oracle binary が新 MMU で software と byte 一致 (hello_static64/mmap64/aesni
等)。run-fast 223 (software 無影響)。**★hello_dyn64 (動的) は ld.so が走り始め、openat(libc.so)/
read/fstat 等 ~11 syscall を経て file-backed mmap (fd>=0) まで到達** (= 次 step の blocker)。

**adversarial review (4 dim) = MMU 本体は全て正しいと検証** (page table walk/PHYS_MASK/US-AND/TLB
invalidate/cross-page 分解/allocator/16-align pad、page table は ring-3 不可達)。must-fix 1: LSTAR
stub を PT_LOAD ループの後に map していたため、仮に PT_LOAD が stub ページ [0xff000,0x100000) に来ると
先に user で map され stub が ring-3 アクセス可のまま残る (旧 va<CODE_BASE guard を外した穴、oracle
binary は 0x400000+ で顕在化せず)。→ **stub を PT_LOAD ループの前に map** (先に supervisor 確定、衝突
PT_LOAD は skip され loud fault)。

### 4.4n Phase 0 step 3d-2c-9: file-backed mmap + CPUID passthrough (ld.so が libc.so を完全ロード)

**§4.4m の MMU 上で ld.so が共有ライブラリを map できるよう file-backed mmap を実装し、glibc の
CPU ISA level check を通すため vCPU に host CPUID を流した。** = §4.4m で到達した blocker
(file-backed mmap fd>=0) を解消し、動的リンクの「ld.so が libc.so を読み込む」段までを完走させる。

実装 (3 ファイル、~40 行):
- **`NativeMemoryBackend.alloc_and_map(fd>=0)`**: 旧 `throw todo` を実装に置換。`anonMmap` で
  仮想割当 + page table 構築の後、`fd>=0` なら file の `[offset, offset+size)` を
  `FileSeek(SEEK_SET)+FileRead` で読み `copyIn` で guest に書く。file が size より短ければ
  残りは 0 のまま (mmap の zero-fill = BSS)。**software `Memory.alloc_and_map` と同じ
  FileSeek+FileRead 経路**。file 層に触るため `setSyscall(Syscall)` で syscall を注入
  (connect_devices で `guestMem.setSyscall(_syscall)`)。
- **`NativeMemoryBackend.set_map_path`**: 旧 `throw todo` を **no-op** に (診断 = segfault dump の
  lib 特定用で、amd64_mmap が fd>=0 経路で必ず呼ぶが native では不要)。
- **`NativeCpuBackend` CPUID passthrough**: `KVM_GET_SUPPORTED_CPUID` (system fd) で host の
  サポート CPUID を取り `KVM_SET_CPUID2` (vcpu fd) で vCPU に流す。**未設定だと libc.so が
  `CPU ISA level is lower than required` で abort** (glibc 2.33+ は CPUID で SSE3/SSSE3/AVX 等の
  ISA level を確認)。`KvmBindings` に ioctl 番号 (0xC008AE05 / 0x4008AE90) + struct kvm_cpuid2
  layout (header 8 byte / entry 40 byte) を追加。

**検証**: 全 12 native-oracle binary が PASS (静的 = file-backed mmap 経路を通らないので無影響)。
run-fast 223 (software 無影響、native 限定の変更)。**★hello_dyn64 (動的) は ld.so が
openat(libc.so.6)→fstat→mmap(file-backed)×複数 で libc.so を完全ロードし、CPUID ISA check を
通過して libc startup (~30 syscall) まで到達。**

**★次 step の blocker = futex 発散**: native の glibc が syscall #30 で
`futex(uaddr=0x7ffff7e…, FUTEX_WAIT_PRIVATE, val=2, timeout=∞)` を呼んで永久 block する
(single-thread で wake する thread が無い)。**software の glibc は同じ hello_dyn64 で futex を
一切呼ばない** (syscall 列: 9/257/158/5/21/318/1/0/17/3/262/231/16/12/10/302/218/273/334)。
= native の glibc が software と異なる locking path に入っている。仮説:
(1) **CPUID 由来の path 差** — native は host CPUID 全部 (AVX/TSX 等) が見え、glibc が
    feature 依存の別 lock 実装 (e.g. `__lll_lock_wait` の contended path) を選ぶ。
(2) **memory 値/状態の差** — single-thread で lock value=2 (contended) は本来あり得ず、
    lock word の初期化漏れ or 別の MMU/stack 由来の値破壊の可能性。
次 PR の調査対象 (CPUID を絞る / lock word の guest メモリ値を software と比較 / tunables で
glibc の lock path を固定)。

### 4.4o Phase 0 step 3d-2c-10: ★★ anonymous mmap の zero-fill 修正 → single-thread 動的 binary 完走

**§4.4n の futex 発散の真因は「CPUID 由来の path 差」でも「lock word 破壊」でもなく、native の
anonymous mmap が MAP_FIXED で既存 mapping に被さるとき stale な内容を zero しない bug だった。**
1 つの修正で hello_dyn64 を含む single-thread 動的 glibc binary 8 種が native==software で完走。

**真因の特定 (scout)**: hello_dyn64 を `EMULIN_TRACE_SH=1` で software/native 並走トレース →
両者は ld.so ロード〜libc 初期化まで同一だが、software は最後に
`fstat(1)/ioctl(TCGETS)/getrandom/brk/write/exit_group` と進むのに対し native は
`prlimit64` の直後に `futex(uaddr=0x7fffefff1710, FUTEX_WAIT_PRIVATE, val=2, ∞)` で hang。
uaddr を libc base (text 0x7fffefe14000 − text vaddr 0x28000 = 0x7fffefdec000) で逆算すると
libc vaddr **0x205710** = libc6-dbg シンボルで **`_IO_stdfile_1_lock`** (stdout の stdio lock)。
これは `puts("hello dynamic")` が最初に取る lock。`.bss` (0x2046e0 開始) にあり本来 0 のはず。
`EMULIN_DEBUG_FUTEX` で実メモリ値を dump → **`*uaddr = 0x2`** (= glibc `__lll_lock_wait` が
`atomic_exchange(lock, 2)` した後)。つまり **lock word が非ゼロ (stale) で起動し、glibc が
「保持されている」と誤認** → futex で永久待ち。

**bug**: ld.so は libc 全体を **file-backed で予約 mmap** した後 (`mmap(0, 0x211d90, fd=libc)` =
native では `alloc_and_map` が file 内容を `copyIn`)、その上に各 PT_LOAD を MAP_FIXED で重ね、
最後に `.bss` を **`mmap(MAP_ANON|MAP_FIXED, fd=-1)`** で zero 化する。ところが native の
`anonMmap` が呼ぶ `mapRange` は **「既 map ページは据置 (skip)」** だったため、`.bss` の anon mmap が
予約で読んだ file byte を上書きせず残し、`_IO_stdfile_1_lock` が stale 非ゼロになっていた。
software は `Memory.alloc_and_map` が MAP_FIXED 毎に `new byte[size]` (Java zero 初期化) を割当てる
ので常に zero、futex に至らない = **software/native の parity gap**。

**fix (`NativeMemoryBackend.anonMmap`、~10 行)**: anonymous mmap の kernel semantics
(= zero-fill page) を満たすよう、range の各ページについて **未 map なら `allocData` (Arena 0 初期化済)
で fresh zero ページを map、既 map なら `bulkZero` で stale 内容を zero クリア**。file-backed mmap
(`alloc_and_map(fd>=0)`) は anonMmap で zero 化された後に `copyIn` が file 内容を上書きするので
[n, size) の BSS tail も正しく 0 に保たれる (mmap の zero-fill-beyond-EOF)。brk (`set_curbrk`→
`mapRange`) は成長方向にしか map せず既 map ページに当たらないので無影響。

**検証**: native-oracle に **動的リンク section を追加** (host の ld.so + libc.so.6 を sandbox に
配置、run-test.sh と同方式、host に lib が無い CI は SKIP)。**static 12 + dynamic 8
(hello/printf/regex/mmap/nested/pie/zlib/cpp `_dyn64`) = 20 binary が native==software で byte 一致**。
native トレースは futex を 1 度も呼ばず software と同じ tail (`write`/`exit_group`) で終わる、3 回
反復で deterministic。run-fast 223 (native-only 変更、software 無影響。並列 flake の
asurvey-grep-c は standalone 43/43 PASS で無関係)。**pthread_*_dyn64 (2 種) は別 vCPU での
thread 生成 = multi-vCPU 未対応のため除外** (Phase 3)。

**★教訓**: 「CPUID 由来の path 差」という最初の仮説は外れ。真因は memory semantics の parity bug
(anonymous MAP_FIXED の zero-fill 漏れ)。`EMULIN_TRACE_SH` の software/native 並走 diff →
futex uaddr を libc base で逆算 → libc6-dbg シンボルで `_IO_stdfile_1_lock` 特定 → 実メモリ値
dump で stale 確認、という scout が決め手。native の小さな memory 実装差が glibc の高位ロジック
(stdio lock) で初めて顕在化する典型。

### 4.4p Phase 0 step 3d-2c-11: ★★★ multi-vCPU (pthread) — worker を別 vCPU で実行

**`clone(CLONE_VM|CLONE_THREAD)` で pthread worker を同一 VM 上の追加 KVM vCPU として spawn し、
別 Java thread で自分の KVM_RUN ループを回す。** `pthread_basic_dyn64` (1 worker + join) と
`pthread_mutex_dyn64` (4 worker + mutex で共有 counter を 1000×4=4000) が native==software で完走。
**共有メモリ上の lock は実 CPU の atomic (LOCK CMPXCHG) が複数 vCPU 間で実行する = software GIL
(#113) 不要の真の並列**、futex の slow path だけが trap される。

設計:
- **VM 資源 (kvmFd/vmFd/guestMem=guest RAM+page table/arena) は VM owner (main backend) が所有**、
  worker は共有して自分の vcpuFd/vcpuState/regsBuf だけ持つ。`setupKvm` を **`setupVm`
  (VM+memory region、main のみ) + `setupVcpu` (vcpu 作成/sregs/MSR/CPUID/初期レジスタ、main+worker
  共通)** に分割。worker の CR3 = `guestMem.pml4Phys()` = main と同じ page table → 同一アドレス空間。
- **`spawnVCpu`** (AbstractCpu の API、#233 で用意済): 子の rip = 親の syscall 戻り先 (親 regsBuf の
  RCX)、rax=0 (clone ABI)、rsp=child_stack、fs=tls。`Worker` (Java thread) が `child.eval()` =
  `setupVcpu` + KVM_RUN ループを回す。`KVM_CREATE_VCPU` の vcpu id は VM owner の単一 counter で採番
  (nested clone でも衝突しない)、arg は `MemorySegment.ofAddress(vcpuId)` (スカラ値)。
- **thread exit/join**: worker の exit(#60) → `amd64_exit_thread` が `ThreadExitException` を投げ
  → child eval を抜け → `Worker.run` の finally で **CLONE_CHILD_CLEARTID の ctid=0 書込 + futex wake**
  (pthread_join の FUTEX_WAIT を起こす)。`active_thread_count` で main の exit が worker 完了を待つ。
  worker teardown は **自分の vcpu fd/mmap/arena だけ** close (VM の vmFd/kvmFd は絶対に閉じない)。
- **backend 非依存マーカ `GuestThread`** (`guestCpu`/`guestTid`): syscall 層の clone 親選択 /
  `gettid` / thread exit 判定を `instanceof Thread64` から `instanceof GuestThread` に汎用化
  (Thread64 = software worker、`NativeCpuBackend.Worker` = native worker が共に実装)。旧
  `(Cpu64) process.cpu` cast は native で ClassCastException だった。software 専用の signal mask /
  Memory fault cpu 等の `instanceof Thread64` は Cpu64 固有なので据置 (native は通らない)。

**★concurrency 修正 (`NativeMemoryBackend` は全 vCPU thread が共有)**: syscall 層 (call_amd64 の
futex/read/write) は複数 worker thread から並行に guestMem を load/store する。(1) **単一エントリ
TLB (instance field) は data race** になるため**撤去** (毎回 page walk = 8-byte aligned physGet64
数回で安価、native は guest 実行が実 CPU 側なので hot path でない)。(2) **page table 変更経路
(mapPage/mapRange/mapSupervisor/anonMmap/set_curbrk) を `mmuLock` で直列化** (並行 mmap/brk で
intermediate table の二重割当や同一ページの二重 map を防ぐ)。read 経路 (virt2phys/xlat/load/store)
は lock-free (aligned PTE は atomic read、mapPage は leaf を最後に publish するので reader は
「未 map か完全 map か」のみ観測)。

**検証**: native-oracle に `pthread_basic`/`pthread_mutex_dyn64` 追加 = **static 12 + dynamic 10 =
22 binary が native==software byte 一致**。pthread_mutex (4 worker contention) は **10 回反復で
全て `counter=4000`** (race があれば <4000、TLB race があれば wild address)。run-fast 223
(software 無影響: GuestThread 化後も Thread64 が実装するので gettid/clone/exit_thread 不変)。

**adversarial review (4 dim × verify) = confirmed 2 / refuted 4**。confirmed は両方とも「移行し忘れた
`instanceof Thread64` site」(同 class のバグ)で、review が捕捉して修正: (1) **`amd64_arch_prctl`
(SyscallAmd64) が `instanceof Thread64` のままで native worker を取りこぼし、worker の
arch_prctl(ARCH_SET_FS) が main の fsBase を破壊 + main vcpuFd への cross-thread KVM_SET_MSRS で
worker crash** (pthread_basic/mutex は CLONE_SETTLS で TLS を設定するので未発火だが、git clone 等
worker が直接 arch_prctl する実 workload で発火)。(2) **`Signal.current_tid` が同様に native worker
を取りこぼし process.pid を返す → tgkill が積んだ thread 宛 signal が worker に届かない**。両方
`instanceof GuestThread g ? g.guestCpu()/g.guestTid()` に修正 (software は Thread64 が実装するので
byte 不変)。refuted 4: mmuActive の volatile (write は start 前の単一 boot 書込で happens-before
保証あり) / lock-free PTE read (x86-TSO + single-writer + leaf-last publish + monotonic page table
で mitigated) / futex lost-wakeup (FutexManager.wait は block 前に `load32==expected` を check =
標準 futex 意味論、waker は値変更後に wake) / 他。**★教訓: 同型バグの横展開漏れ — `instanceof
Thread64` を 3 箇所 (gettid/clone/exit_thread) 移行したが arch_prctl/current_tid を見落とした。
review が「他に同じ site は無いか」で 2 件捕捉。**

### 4.4q Phase 0 step 3d-2c-12: coreutils 級 dynamic binary の validation + 回帰固定 (test-only)

**実 GNU coreutils (true/echo/pwd/seq/wc/head/cat) を host から sandbox に置いて native で走らせる
scout で、native backend が coreutils 級の dynamic binary を追加コード無しで software と byte 一致で
処理できることを確認した。** = 動的リンク + multi-vCPU 基盤 (3d-2c-9〜11) が実用 binary でそのまま
動く実証。**native backend のコード変更ゼロ** (純粋に validation + 回帰固定)。

回帰固定は host coreutils (version 依存・非 hermetic) でなく、**新規 committed test `dirlist_dyn64.c`**
で行う。既存 _dyn64 テストが触らない syscall surface = **getcwd / mkdir / opendir+readdir
(getdents64) / stat (newfstatat) / file 作成・read / unlink / rmdir + glibc malloc/qsort** を 1 本で
通し、`/tmp/dlt64` を自分で作って列挙するので出力は hermetic に決まる
(`entries: a.txt b.txt c.txt` 等)。directory 列挙 (getdents64) を native で検証する初の oracle binary。

**検証**: native-oracle に dirlist_dyn64 追加 = **static 12 + dynamic 11 = 23 binary が
native==software byte 一致**。run-fast は `src/*.c` 自動列挙で dirlist を software 回帰として自動的に
拾う (224 PASS)。

### 4.4r Phase 0 step 3d-2c-13: ★★★ signal 配信 (syscall 境界、単一+複数スレッド)

**guest の signal handler を native vCPU で発火させる。** `kill`/`pthread_kill` で送られた pending
signal を **syscall 境界 (call_amd64 の後)** で検出し、被中断 user context を保存して handler を
sysretq で起動、handler が trampoline 経由で `rt_sigreturn` を呼ぶと context を復元する。単一スレッド
(`sys_signal_*`) と複数スレッド (`pthread_sigmask_dyn64` = per-thread mask + cross-thread
`pthread_kill`) の両方が native==software で完走。

設計の核心:
- **配信タイミング = syscall 境界のみ**: native は guest を実 vCPU で走らせるので software の
  per-instruction `check_pending_signal` は使えない。VM-exit する syscall trap の後で `psig()` を見て
  配信する。`kill(self)`/`pthread_kill` 等は syscall なので、その戻り際に配信される (Linux も signal
  は syscall 出口で配信するので意味論一致)。CPU-bound ループ中の preemptive 配信 (KVM_SET_SIGNAL_MASK
  + pthread_kill で KVM_RUN を EINTR) は未対応 (実 workload では稀、follow-up)。
- **trampoline = user ページの `mov $15,%rax; syscall`** (SIGTRAMP_VADDR=0xfe000)。handler が `ret` で
  ここに着地 → `rt_sigreturn(#15)` を呼ぶ → eval ループが sysno==15 を見て被中断 frame を復元 (glibc の
  sa_restorer 相当)。NX を立てないので実行可能。
- **★delivery も restore も sysretq で良い (RCX/R11 上書き許容)**: 一見 rt_sigreturn は任意の被中断
  context を復元するので RCX/R11 を正確に戻す必要があり sysretq (RIP←RCX/RFLAGS←R11 で両者を上書き)
  は使えないように見える。が **native は syscall 境界でしか配信しない → 被中断点は常に syscall 直後で、
  RCX/R11 は syscall ABI により既に dead** (syscall 命令が RCX←RIP/R11←RFLAGS で破壊する)。よって
  通常の syscall 復帰と同じ sysretq で復元できる (sregs 操作不要)。software は per-instruction 配信
  なので任意点を中断し RCX/R11 を正確に保存復元するが、native の制約がここでは簡略化に働く。
- **frame** = 全 16 GPR + RIP(=user 復帰 addr=RCX) + RFLAGS(=R11) + 保存 mask を per-vCPU
  `ArrayDeque` に push。SA_SIGINFO は guest stack に siginfo(128)/ucontext(256) を構築 (uc_mcontext
  gregs を software と同 layout で埋める)。handler stack は red zone(128) skip + **16-align**
  (real CPU の movaps 用、software は緩いが native は必須) + trampoline push (handler 進入時 RSP%16==8)。
- **mask/per-thread**: `psig()`/`set_signal_mask_bits` は backend 非依存。複数スレッドは各 worker vCPU
  が自分の syscall 境界で配信する。worker 宛 signal の tid 解決は 3d-2c-11 の `Signal.current_tid` の
  GuestThread 化が効く (旧 instanceof Thread64 だと worker が process.pid を返し届かなかった)。
- `set_signal_handler` (Process.run i386 経路の hook) は native では throw→no-op に
  (native は ELF64 自己完結 eval で `is_interrupt_done()`=false なので未到達)。

**検証**: native-oracle に **sys_signal_delivery/regsave/sa_siginfo/sigmask/rt_sigaction (静的 5) +
pthread_sigmask_dyn64 (動的 1)** 追加 = **static 17 + dynamic 12 = 29 binary が native==software**。
run-fast 224。

**adversarial review (4 dim × verify) = confirmed 2 / refuted 10**。両方修正: (1) **per-thread signal
mask 漏れ** — `Signal.java` の mask accessor 5 箇所 (get/set_signal_mask_bits・set_signal_mask・
is_signal_masked・current_thread_mask) が `instanceof Thread64` で per-thread/process-wide を分岐して
いたため、native worker (`NativeCpuBackend.Worker`=Thread64 でない) が process-wide mask を共有して
いた (worker の block/unblock が他 thread に漏れる、current_thread_mask が 0L を返し own-thread
pending masking も壊れる)。**`GuestThread` に `getSignalMask/setSignalMask` を足し 5 箇所を経由** +
Worker に per-thread mask field + spawnVCpu で親 mask 継承 (software は Thread64 が実装するので byte
不変)。pthread_sigmask_dyn64 は loose な assertion で偶然 pass していたが今は正しい per-thread 意味論。
(2) **ucontext eflags の byte 不一致** — native は uc_mcontext+176 に R11 (生 RFLAGS) を書いていたが
software は status flags のみ再構築 (`2|CF|PF|ZF|SF|OF`)。DF(bit10)/AF(bit4) が分岐するので
`(R11 & 0x8C5)|2` に mask して software と一致 (現テストは未読だが latent な oracle divergence)。
refuted 10: 16-align の siginfo 配置差 (stdout 不変) / mask 破損 on throw (eval ループは catch で
process 終了、回復経路なし) 等。**★教訓: また同型 `instanceof Thread64` 漏れ (mask 5 箇所) — review
に「全 instanceof Thread64 site を native worker 視点で網羅」を明示すると確実に拾える。**

**未対応 (follow-up)**: (1) **`sys_signal_xmm64`/`sys_signal_x87_64` は native で動かない (テスト側の
quirk、3d-2c-14 で判明)** — これら -nostdlib テストの gcc 製 `_start` は **関数呼出規約 (entry で
RSP%16==8) を前提に `push %rbp` 後 `movaps -0x40(%rbp)`** を出すが、process entry の ABI は RSP%16==0
なので movaps が #GP する。**実 host でも segfault する** (`./sys_signal_xmm64` → SIGSEGV)。software は
SSE alignment を強制しないので偶然通っていただけ。native (実 CPU) では原理的に不可で、entry を
RSP%16==8 にすると glibc binary (RSP%16==0 前提) が壊れるので両立しない。= native の bug ではなく
テスト binary の前提違反。handler を跨いだ FPU/XMM 保存 (KVM_GET/SET_FPU) 自体は実 glibc signal
workload (handler が SSE を使う) で将来必要だが、syscall 境界配信では XMM が live で残ることは稀
(syscall 跨ぎで caller-saved の XMM は spill 済) なので優先度低。(2) **`sys_sigchld64`** = fork (native
は子プロセス未対応)。(3) preemptive 配信 (CPU-bound 中断)。

### 4.4s Phase 0 step 3d-2c-14: busybox (実用静的 glibc multi-applet binary) の validation + 回帰固定

**実 busybox (88 applet を持つ静的 glibc binary、~2MB) を native で走らせ、テスト用の小さな自作
binary でなく実用 binary が動くことを実証した。** native backend のコード変更ゼロ (validation +
回帰固定)。echo/true/seq/printf/cat/wc/sort/grep/head/od/sha256sum/ls/expr など多数の applet が
native==software で byte 一致。

回帰固定は native-oracle に busybox section を追加 (host の `/usr/bin/busybox` (静的) を sandbox に
置き software/native 両 emulin で実行 → byte 一致)。**同一 binary を両 backend で走らせるので busybox
の version 非依存** (native==software が invariant)。sha256sum は file 内容のハッシュなので決定的。
host に静的 busybox が無い CI は SKIP。

**検証**: native-oracle = static 17 + dynamic 12 + **busybox 6 applet** = 35 check が native==software。
busybox は dirlist 等の自作テストより遥かに広い実 syscall surface (applet ごとに getopt/locale/
stdio/file I/O/正規表現/ハッシュ) を 1 binary で通す。run-fast は busybox を host から取るので回帰
スイート外 (native-oracle = run-network 内)。

**★教訓**: signal の follow-up として狙っていた xmm/x87 は **テスト binary 側の stack alignment quirk
で実 host でも crash = native で動かなくて正しい**。native は「実 CPU の挙動」が基準なので、software
の緩い emulation でしか通らないテストは native の対象外と切り分ける。

### 4.4t Phase 0 step 3d-2c-15: 総合 integration test + lazy mmap pool (memory-heavy 対応) + applet 拡充

**native が個別に検証してきた機能 (動的リンク / pthread / mutex=futex / signal / file I/O) を 1 binary で
組み合わせた integration test を追加し、機能間の連携を検証。** その過程で **物理プールの eager
allocation が memory-heavy program (多 thread) を枯渇させる**限界を発見し、**lazy mmap pool** で解消した。

**lazy mmap pool (★core 改善)**: guest 物理 RAM を `Arena.allocate(POOL_SIZE)` でなく
`mmap(MAP_PRIVATE|MAP_ANONYMOUS, fd=-1)` で確保する。`Arena.allocate` は全域を **eager に zero** して
RSS を POOL_SIZE 分消費する (実測: 256MB pool で +255MB RSS) が、`mmap(MAP_ANON)` は **OS demand
paging** で未 touch ページを backing しない。MMU の `allocData` は「fresh ページは zero」を仮定するが、
OS zero ページがそれを満たす (eager touch 不要)。connect_devices は boot path なので mmap 失敗は
`fatalUnsupported` (System.exit、例外は #245 の hang を招く)、teardownKvm で munmap (worker は共有
poolSeg を触らず main のみ解放)。**効果: POOL_SIZE を 64MB→512MB に拡大しても native RSS は
326MB ≒ software 333MB** (旧 eager 256MB は 588MB)。8 worker × 8MB stack = 64MB の program も動く。

**integration test (`integ_dyn64`)**: SIGUSR1 handler 登録 → **8 worker thread** が mutex 下で共有
counter を ++ (futex 競合) → join → 自分に SIGUSR1 ×3 (syscall 境界で配信) → file 書込/読戻し。
期待 `counter=8000 / sig_count=3 / file: HELLO-INTEG`。個別テストの和では拾えない**機能間の相互作用**
(worker 並走中の futex、signal 配信と thread の交差) を 1 本で通す。8 worker は lazy pool の容量も実証。

**busybox applet 拡充**: `awk` (本格インタプリタ) / `sed` (正規表現) を oracle に追加。複雑な単一プロセス
applet も native==software。

**検証**: native-oracle = static 17 + dynamic 13 (integ 追加) + busybox 8 (awk/sed 追加) = **38 check が
native==software**。run-fast は `integ_dyn64` を `src/*.c` 自動列挙で software 回帰として拾う (225 PASS)。
lazy pool は全 native run に効く core 変更だが 38 oracle byte 一致 + RSS 実測で検証。

### 4.4u Phase 0 step 3d-2c-16: ★ go/no-go データ測定 — break-even を実 workload スペクトルで実証

**#251 は compute-only で native 215x を出したが、syscall-heavy 側の break-even が未確定だった。
実 binary が動く今、命令/syscall 比と wall-clock を実 workload のスペクトルで測り、#221 の核心仮説
「native の限界コストは syscall trap T のみ、便益は syscall 以外の全命令が native 速度」を実データで
確定させた。** user が「go/no-go データ測定」を選択。

**測定基盤** (新規 committed): `syscall_storm64.c` (getpid を N 回 = syscall-heavy の worst case、
compute-heavy の bench64 と対) + `EMULIN_REPORT_COUNTS` (software の `process.evals`=実行命令数 と
syscall 数から命令/syscall を exit 時に出す。native も同一命令を実行するので比は共通) +
`bench-gonogo.sh` (スペクトル測定を再現)。

**(A) 命令/syscall 比** (WSL2 nested、software 計測、5MB 入力):

| workload | 命令/syscall | break-even (nested ~174) 比 |
|---|---:|---|
| busybox sha256sum 5MB | 3,445,310 | ~20000x 上 |
| busybox md5sum 5MB | 618,287 | ~3500x 上 |
| busybox grep 5MB | 332,127 | ~1900x 上 |
| bench64 (compute) | 157,257 | ~900x 上 |
| busybox wc 5MB | 123,012 | ~700x 上 |
| **busybox sort 5MB** (実 workload で最も syscall-heavy) | **4,946** | **~28x 上** |
| syscall_storm (contrived worst case) | 16 | ★ break-even 下 |

**(B) wall-clock** (WSL2 nested): native の syscall trap = **~9µs/syscall** (software の emulated
getpid は ~0µs、trap 無し)。実 compute workload **busybox sha256sum 5MB は native が software の
118x 高速** (software compute 11.0s vs native 0.09s、startup 差引)。

**(C) break-even**: 命令/syscall > T_trap / per-instruction-savings。per-insn-savings ≈ 0.052µs
(#251 から: software 19.3M insn/s vs native 4.16G insn/s)。**nested (T~9µs) で ~174、bare-metal
(T~1-3µs) で ~39-58**。

**★結論 = compute/実 workload では明確に GO**: 実測した実 workload は最も syscall-heavy な sort
(~5000) でも break-even (~174 nested) の **28 倍上**、典型は 100k-3M 命令/syscall で **桁違いに上**。
= 実 workload は overwhelmingly compute-dominated で **native が圧勝** (sha256sum 実測 118x)。
break-even を下回るのは pure syscall storm (getpid をひたすら呼ぶ、16 命令/syscall) のみで、実
プログラムには存在しない。bare-metal は break-even を ~39 まで下げるので更に有利。**残る注意は
syscall-storm 級の interactive (emacs poll #206 = 50k-500k syscall/s)** だが、これは命令/syscall が
極端に低い特殊ケースで、per-syscall trap 削減 (KVM_CAP_SYNC_REGS 等) or bare-metal が要る。#221 の
go/no-go は **conditional GO → compute/実 workload で明確 GO** に更新 (latency 絶対値の 3f bare-metal
測定が残課題)。

### 4.4v Phase 0 step 3d-2c-17: ★★ 看板 workload (curl HTTPS) が native で動作 — crypto は実 CPU で圧勝

**emulin の看板「HTTPS clone 動作」を native で検証: 実 host の curl (PIE + 32 共有ライブラリ、
libcurl/OpenSSL/zlib/brotli/...) が native で起動し、HTTPS リクエストが完走する。** これは複数の
native 初到達を一度に通す: **(a) PIE main executable** (ET_DYN、relocated load。従来テストは
hello_dyn64 等 ET_EXEC=-no-pie のみ) **(b) 32-lib の重い動的リンク** (3d-2c-15 の lazy pool が効く)
**(c) network syscall** (socket/connect/poll/sendto/recvfrom) **(d) OpenSSL/TLS** (crypto)。native の
コード変更ゼロ — 既存の動的リンク+signal+lazy pool 基盤でそのまま動いた。

**実測** (WSL2 nested、host curl 8.5.0 + OpenSSL 3.0.13、example.com):

| | software | native |
|---|---|---|
| `curl --version` (PIE + 32 libs 起動) | 動作 | 動作 (byte 一致) |
| **HTTP** (非 TLS、network path のみ) | 2.4s ✅ | 1.5s ✅ (ほぼ同等) |
| **★ HTTPS** (TLS/AES/ASN.1 = crypto-heavy) | **失敗** (~28s、server idle timeout) | **1.5s ✅** (httpcode 200、3/3 reliable) |

**★解釈 = go/no-go の実 workload 実証**: HTTP (crypto 無し、network-bound) は両 backend ほぼ同等
(network syscall は両方 call_amd64 で処理)。差が出るのは **HTTPS の crypto** — TLS handshake/AES/
ASN.1 parse は compute-heavy で、**native は実 CPU (AES-NI 含む) で走らせるので速く、software は
per-instruction emulation で遅すぎ server が idle connection を切って失敗する**。同じ binary・同じ
sandbox で native は 1.5s 成功、software は失敗 = 看板 workload で native の決定的優位。これは
3d-2c-16 の「crypto/compute-heavy は命令/syscall 比が高く native 圧勝」を実 flagship workload で裏取り
したもの (sha256sum 118x、HTTPS は software が完走すらしない)。

**測定基盤** (新規): `bench-curl.sh` (host curl + ldd 依存 lib + ld.so + 証明書を sandbox に置き、
curl --version / HTTP / HTTPS を両 backend で測る。network 依存なので CI 外の手動 benchmark、
network/curl/kvm 無い環境は SKIP)。HTTPS oracle 化はしない (非 hermetic + software が完走しない)。

### 4.4w Phase 0 step 3d-2c-18: ★ native backend 総合監査 (WHP 移植前のハードニング)

**17 step で積み上げた native backend (~1600 行) を 6 dimension × multi-agent workflow で総合監査し、
per-PR review が見逃した横断的バグを WHP 移植前に潰した。** user が「総合監査」を選択。dimension =
KVM lifecycle / MMU correctness / concurrency / signal×multi-vCPU / isolation / sw-equivalence。
**confirmed 7 / refuted 36** (各 finding を adversarial verify)。confirmed のうち native 固有の 3 件を修正、
3 件を既知の限界として記録。

**修正 (3 件、NativeCpuBackend)**:
- **★(CRITICAL) teardownKvm の use-after-free**: worker vCPU は guestMem(poolSeg)/vmFd を共有するが、
  `exit_group` (amd64_exit) は worker を待たず main の eval を抜けるので、teardownKvm が即 poolSeg を
  munmap すると走行中の worker が call_amd64 内で guestMem を load/store して host UAF になる
  (FFM reinterpret segment は lifetime guard 無し)。通常 pthread program は exit 前 join 済
  (active_thread_count==0) で無害だが detached thread / join 前 exit で発火。→ **teardownKvm で worker
  全停止を bounded-wait (最大 3s)、なお残るなら shared 資源 (vmFd/kvmFd/poolSeg) free を skip**
  (process 終了で OS 回収=leak 限定的、UAF より安全)。vcpu-local は常に free。
- **★(CRITICAL→latent) PT_LOAD が stub/sigtramp を上書き**: stub(0xff000)/sigtramp(0xfe000) の予約
  ページに PT_LOAD が被ると、mapRange は (既 map で) skip するが直後の bulkStoreToMem が segment 内容で
  stub の `hlt;sysretq` を上書きし syscall trap 機構が壊れる (旧コメント「loud に fault」は誤り=mapRange
  の skip は US を保つだけで内容は守らない)。実 binary は 0x400000+ なので無害だが、低位リンク binary で
  発火。→ **予約低位帯 [0xfe000,0x100000) と重なる PT_LOAD を skip**。
- **(HIGH) spawnVCpu の unmapped ptid/ctid で native crash**: clone の SETTID で ptid/ctid が unmapped
  だと store32→xlat が例外を投げ eval ループ全体を落とす (software は guest SIGSEGV で済む)。
  → **in() guard で skip** (Worker.run の ctid clear と同方針)。

**記録した既知の限界 (3 件、defer)**: (1) cross-page load/store が page 境界で前半 write 後に後半
unmapped で fault すると partial effect (software も同様の edge、稀)。(2) **psig/signal_cancel の非
atomicity** — process-wide signal を複数 worker が並行 psig すると double-delivery しうる
(★software も同根=Cpu64.check_pending_signal も psig+signal_cancel を別呼び、native 固有でなく Signal.java
の shared 問題、latent)。(3) **FPU/XMM が signal frame に未保存** (3d-2c-13 で既知、handler が SSE を
使うと被中断 XMM が壊れる。syscall 境界配信では XMM live が稀なので優先度低、KVM_GET/SET_XSAVE で対応可)。

**検証**: 修正後 native-oracle 39 check native==software / run-fast 226 (teardown wait は single-thread
では count==0 で即 free、join 済 pthread でも同様なので回帰無し)。

### 4.4x Phase 0 step 3d-2c-19: execve は native で動作済 (multi-process への第一歩、fork が唯一の残り)

**git clone を native で動かす調査の出発点。git は helper (git-remote-https 等) を fork+exec する
multi-process program で、native は fork (`duplicate`) 未実装。fork/exec の段階実装として「まず
execve から」着手したが、★execve は既存機構で既に動いていた。** = multi-process 化の残りは fork のみ。

**execve は実装不要 (検証のみ)**: `amd64_execve`→`kernel.exec(pid, path, argv, envp)` が**新 Process
を同 pid スロットに生成** (exec_replacing) し、新 Process の CPU backend は factory (`EMULIN_BACKEND`)
経由で **native backend** になる。新 native backend の `connect_devices` が新 ELF を fresh guestMem に
ロードし `setupKvm` (新 VM) + eval で走る。旧 native backend の eval thread は `set_exit_flag` で抜け
teardownKvm が VM を解放する。= **execve = 新 native Process を起動 + 旧を畳む**で、connect_devices
が一度きりでも「新 Process が新 backend を作る」ので問題なく動く。**static target (hello64) も
dynamic target (hello_dyn64=ld.so 経由) も native==software で byte 一致**を確認。

**新規 test**: `sys_execve_self64.c` — fork 無しで自プロセスが `/bin/hello64` を execve して置換
(既存 sys_execve64 は fork+exec で fork 未対応の native では走らない)。native-oracle に追加 (40 check)。

**★残る git blocker = fork のみ。fork 設計の調査結果 (§4.6 へ)**: native の実行時状態は `guestMem`
(NativeMemoryBackend) にあり software `Memory` に無いので、`Process.duplicate→mem.duplicate()` では
子に正しいメモリが渡らない。native fork は **`NativeMemoryBackend.duplicate` (page table + data ページ
コピー) + 子 VM/vCPU** という別経路が要る。git は fork 直後 exec する (vfork 風) ので、子は exec まで
親 guestMem を共有しコピーを省く高速路が現実的 (execve は上記で動くので、その上に fork を載せる)。
規模は multi-vCPU step 同等の Phase 1 相当。

### 4.4y Phase 0 step 3d-2c-20: ★★★ fork が native で動作 — git clone の最後の blocker を解消

**git clone (= helper を fork+exec する multi-process program) を native で動かす最後の blocker、fork
を実装した。** 子は worker (pthread = CLONE_THREAD、VM 共有) と違い、**独立 VM + 複製 guestMem を持つ
別 process**。

**★中心となった気づき = MMU の自己完結性で「アドレス空間の複製」が memcpy 1 回**: `NativeMemoryBackend`
の page table は **pool-relative な物理 offset** を格納する (CR3=PML4_PHYS=0x1000、data ページは
DATA_BASE=0x800000 から bump、PTE は host 絶対アドレスを含まない)。よって使用済み prefix
`[0, dataNext)` (= page 0 + PML4 + 全 page table + 割当済 data ページ) を子の新プールへ verbatim copy
するだけで、子は **独立した同一アドレス空間**を持つ (子プールでも CR3=0x1000 が有効、copy した page
table の物理 offset がそのまま子プール内の copy した data ページを指す)。`mmapTop`/`curbrk`/bump
pointer も複製し、以後 親子は別プールで独立に成長する (= fork のアドレス空間分離)。

**★第二の気づき = 既存の Kernel.fork/Process.duplicate 機構に丸ごと乗れる (Kernel/Process/SyscallAmd64
の変更ゼロ)**: 変更は `NativeCpuBackend` + `NativeMemoryBackend` だけ。

- `duplicate(child)`: 子 backend を生成し、**親の trap 時 register snapshot (forkRegs) を今ここで固定**
  する (this.regsBuf は fork から親が復帰し次の syscall を打つと上書きされ、子の setupVcpu は別 thread
  で後から走るので race する → long[] に copy)。`forkParent`/`fsBase` も保持。
- `connect_devices` の **fork 分岐 (`forkParent!=null`→`connect_fork`)**: ELF reload せず、子専用プールを
  lazy mmap し `forkParent.guestMem.duplicate(childPool)` で親アドレス空間を複製、子 syscall 層を子
  guestMem に向ける。LSTAR stub / sigtramp / PT_LOAD / 初期 stack は親プールに既に在り複製で子に入るので
  boot path の再構築は不要。
- `entryRip = forkRegs[RCX] - 2` の妙手: Kernel.fork の generic fixup `set_ip(get_ip()+2)` (i386
  `int 0x80` 由来、software amd64 では「rip=syscall 命令アドレス、+2 で次へ」) を **そのまま共有**する。
  native の被中断点は親 RCX (syscall 直後) なので、entryRip に RCX-2 (= user の `syscall` 命令アドレス)
  を入れれば +2 で RCX に戻る。`set_ax(0)` は native no-op だが setupVcpu が RAX=0 を強制するので無害。
- `setupVcpu` の **fork 分岐**: regsBuf に親の全 GPR を焼き、`rax=0`(子の fork 戻り値) / `rip=entryRip`
  (=RCX) / `rsp` / `rflags=forkRegs[R11]` (= 親 user RFLAGS、sysretq が復元する値) を上書きして、子は
  fork syscall 直後 (rax=0 以外は親と同一) の状態で ring-3 から初回 KVM_RUN を開始する (LSTAR stub を
  経由しない初期 entry)。

子は新 `Process` (新 pid) として ptable に登録され Process.start→run→eval で自分の VM を立てて走る。
clone(CLONE_VM|CLONE_VFORK、posix_spawn) は child_stack を set_sp で受け、software と同じく **コピー
fork (真の CLONE_VM 共有や VFORK 親 suspend はしない)** で git/posix_spawn を満たす (software がこの
方式で git を通している)。

**検証 (native==software byte 一致)**: 新規 -nostdlib テスト 3 段を oracle に追加 (40→43 check):
- `sys_fork64` (既存): fork→子 write→親 wait4 reap (基本: fork 戻り値 0/pid、出力順序)
- `sys_fork_isolation64` (新): 子の `.data` global 書込みが親に波及しない (★アドレス空間分離 = 別 VM の証明)
- `sys_fork_exec64` (新): fork→子 execve(/bin/hello64)→親 wait4 (★git の fork+exec パターン)

run-fast 229 (fork×2 を software 回帰にも追加)。**残るは 3f (bare-metal latency 絶対値=要実機) → WHP
移植のみ**。

### 4.4z Phase 0 step 3d-2c-21: FPU-in-signal — signal を跨いだ x87/XMM の退避復元 (ハードニング)

#263 総合監査で defer した **FPU-in-signal** (native 固有) を実装。signal handler が x87/XMM を使っても
**被中断側の live FP データが壊れない**ようにする。software (`Cpu64.check_pending_signal`) は被中断側の
`xmm_lo`/`xmm_hi` + x87 (cw/sw/tag/top/st0-7) を `sigSavedFrames` に退避し rt_sigreturn で復元するが、
native は GPR しか退避していなかった (= handler の SSE/x87 clobber が被中断側に漏れて software と divergence)。

実装: vCPU の FPU 状態を **`KVM_GET_FPU`/`KVM_SET_FPU`** (struct kvm_fpu = 416 byte、FXSAVE 相当で
x87 + XMM0-15 + MXCSR を覆う) で退避復元する。
- `captureFpu()`: `deliverPendingSignal` で `sigFrames.push` の直後に `KVM_GET_FPU`→byte[416] snapshot を
  `sigFpuFrames` に積む (この時点の vCPU FPU は被中断コードのもの、syscall は FPU を変えない)。
- `restoreFpu()`: `restoreSignalFrame` (rt_sigreturn) で snapshot を pop し `KVM_SET_FPU` で復元。
- `sigFrames` と `sigFpuFrames` は lockstep (SIG_IGN/SIG_DFL の早期 return は両方 push しない、nested
  signal も LIFO)。`fpuBuf` は `setupVcpu` で per-vCPU 確保 (main/worker/fork 子それぞれ独立)。

★parity の注記 (MXCSR): kvm_fpu blob は MXCSR を含むので native は MXCSR も snapshot/復元する。software
は LDMXCSR を no-op、STMXCSR を常に default 0x1F80 とし MXCSR を一切模さない (Cpu64:439/5195/5199、SSE は
Java の round-to-nearest 固定)。よって software は実質「常に default MXCSR」。native が被中断側の MXCSR を
**復元する**と、通常プログラム (被中断側が MXCSR 未変更=default) では native も default に戻る → software と
一致する。逆に復元しないと handler の LDMXCSR が被中断側に漏れて software と乖離する。つまり MXCSR 復元は
parity を**改善する**側 (review の「MXCSR 復元が乖離を生む」指摘は逆で、software が LDMXCSR を no-op に
するため復元した方が一致する)。唯一 native≠software になるのは被中断側自身が MXCSR を非 default に変えた
場合だが、それは signal 非依存の既存ギャップ (software が LDMXCSR を無視する) であって本 step の範囲外。
AVX/YMM 上位は software も kvm_fpu (legacy FXSAVE) も模さない範囲なので一致。

**検証**: 新規 `sys_sig_fpu64` (handler が xmm3 を破壊→被中断側の xmm3 が保たれるか) が native==software で
byte 一致 (`xmm_preserved=1`)。fix を外すと native は `xmm_preserved=0` になり software と不一致 = load-bearing
を確認済。native-oracle 44 check / run-fast 230。

### 4.4aa Phase 0 step 3d-2c-22: psig/signal_cancel の race 修正 (pending_recv_count を atomic 化、ハードニング)

#263 監査で defer した「software も同根」のハードニング項目。signal の pending hint カウンタ
`pending_recv_count` が複数 thread から非 atomic に更新され signal を取りこぼしうる race を修正。

真因: `pending_recv_count` は `psig()`/`psig_actionable()` の fast-path (`==0 なら早期 return -1` で 32 signal の
scan を省く) に使う hint。signal は複数 thread から並行に届く (eval thread + 送信側 = itimer の SIGALRM /
`Kernel.kill` / 子 exit の SIGCHLD / worker の tgkill)。旧 `volatile int` の `++`/`-= c` は volatile でも RMW が
非 atomic なので、異なる signal の並行 recv で increment が失われ under-count → 極端には 0 になり psig() が
pending signal を取りこぼす (signal lost)。signal-heavy な multi-thread program で顕在化しうる。

修正: `volatile int` → `AtomicInteger`、全更新を atomic に (`incrementAndGet`/`addAndGet(-c)`+防御 clamp、
読みは `get()`)。single-thread では無競合で同一結果 = byte-identical なので oracle 不変。Signal.java は
native/software 双方の signal 経路が共有するので両 backend に効く。

★cross-page partial write は本 step から除外 (精査の結論): guest store 命令の cross-page→unmapped は
**native では実 vCPU が #PF を起こすが guest IDT 無し→triple fault→`KVM_EXIT_SHUTDOWN` で exit 127** になり、
そもそも native は store fault で guest SIGSEGV を配信できない (本 backend は `syscall` の HLT trap のみ対応)。
よって「partial write を SIGSEGV handler が観測して継続」する状況は native で再現不能 = native==software oracle
で検証できない **software 専用**の限界であり、canonical な `Memory` store hot path を test 無しで触るのは
見送る。psig race は backend 共有かつ無競合 byte-identical で安全に修正できる本項目を実装した。

**検証**: native-oracle 44 check native==software (signal/pthread 経路が psig を多用、回帰なし) / run-fast 230 /
0 FAIL。

### 4.4bb Phase 0 step 3d-2c-23: /proc/self/maps の native 実装 — 実 GNU grep/gawk 等のカバレッジ拡大

実 host binary を software/native 両 backend で走らせる scout で、**grep / gawk が native で busy-hang**する
gap を発見・修正した。

★真因: grep/gawk は **pcre2/glibc 経由で `/proc/self/maps` を読む**が、`NativeMemoryBackend.genProcSelfMaps`
が未実装 stub (`throw UnsupportedOperationException`) だった。open(`/proc/self/maps`) → `Syscall.open_resolved`
→ `mem.genProcSelfMaps()` で throw → guest が user 空間で busy-loop して hang していた。`/proc/self/maps` を
読まない sed/tr/sort/coreutils は無事だったので発見が遅れた。

修正: `genProcSelfMaps` を **page table walk** で実装。software (`Memory.genProcSelfMaps`) が
segment[]/alloclist から生成するのに対し、native は 4-level page table を walk して present かつ US=1 の
user ページを列挙・coalesce し、`start-end rwxp 00000000 00:00 0` 形式で出力する (page table が「何が map 済
か」の権威)。stack region は `seedStack` で seed した bottom から識別し `[stack]` を付す (glibc の
`__pthread_getattr_np` stack 境界検出用)。fork 子も `duplicate` で `stackBottomVaddr` を継承。

★prot の限界: native の leaf PTE は一律 RW なので r-x/rw を区別できず user 領域は一律 `rwxp` 報告 (stack のみ
`rw-p`)。region 内容は grep 等の stdout に出ない (init 用に内部で読むだけ) ので native==software の **output
parity は保たれる**。ただし `__readonly_area` の `%n` 保護は native で緩くなる (rodata も writable 報告)。
coverage 対象は `%n`-into-rodata を使わないので影響なし (厳密 %n 判定が要る ddskk/emacs 系は software backend
側で担保)。

**カバレッジ拡大** (scout で native==software を確認 → 回帰固定): 実 GNU **grep/gawk/sed/perl/sha256sum** を
oracle に追加 (同一 host binary を両 backend 実行 = version 非依存)。★perl/gawk = 実用 interpreter、grep/sed =
regex、grep/gawk = genProcSelfMaps 修正の実証。実 GNU coreutils (sort/uniq/wc/od/cut/tr/tac/rev/nl/head/tail/
factor/expr/base64/md5sum) + gzip も scout で native==software を確認 (回帰は代表 5 種に絞る)。

※ scout で見つかった別件: 実 GNU `seq` は **software で hang** (native は正常) = software 側の別バグ (native
coverage と無関係、本 step 外)。bzip2/xz は両 backend とも空出力 (sandbox harness の問題、native 非依存)。

**検証**: native-oracle (real-binary 5 件追加) native==software / run-fast 230。docs §4.4bb。

### 4.4cc Phase 0 step 3d-2c-24: ★ python3.12 (full CPython) + tar の native validation (test-only)

カバレッジ拡大の続き。**full CPython 3.12 interpreter が native で動く**ことを実証し、tar と共に oracle に
固定した (native code 変更ゼロ = #268 の genProcSelfMaps 修正の上で動的リンク基盤がそのまま実用 interpreter
を通す)。

- **python3.12**: stdlib (~42MB、test/idlelib/tkinter 除外) を sandbox に bundle し PYTHONHOME=/usr で起動。
  `print(sum(range(100)))`→4950、`json.dumps`/`hashlib.sha256`/`re.findall` (★C 拡張 _json/_hashlib/_sre
  込み) が native==software byte 一致。算術/json/hashlib/re は patch version 非依存。python は startup で
  /proc/self/maps を読む経路があり #268 の genProcSelfMaps native 実装が前提。**実用 interpreter (curl/
  perl に続く)** が native KVM backend で動く = 重い動的リンク + 大量 syscall + C 拡張を一度に通す実証。
- **tar**: 固定 tar (mtime/owner/sort 固定で deterministic) を host で作り、`tar -tf` (list) / `tar -xOf`
  (extract to stdout) が native==software。実用 archive ツールの header parse + 抽出経路を通す。

回帰は同一 host binary/stdlib を両 backend 実行 = version 非依存。host に python3.12+stdlib / tar 無しは個別
SKIP。

**検証**: native-oracle (python×2 + tar×2 追加) native==software / run-fast 230。docs §4.4cc。test-only
(native code 変更なし)。

### 4.4dd Phase 0 step 3d-2c-32: ★ mmap hint 意味論 (MAP_FIXED 無し) + brk 衝突 fail + PUSHFQ/POPFQ — node (V8) が native/software 両対応

cov9 (#280) 選定中に発見した node/V8 の 2 バグを修正し、node を cov10 として oracle 固定した。

**(1) native: mmap hint を MAP_FIXED 扱いする parity bug → glibc sysmalloc assertion (core fix)**

`node -e 'console.log(1)'` が native で `Fatal glibc error: malloc.c:2599 (sysmalloc): assertion failed`。
software は同 workload 正常。EMULIN_TRACE_SH 並走 diff で真因特定:

- V8 は起動時に **`mmap(hint, 512MB+slop, MAP_PRIVATE|MAP_ANONYMOUS|MAP_NORESERVE)` (MAP_FIXED 無し)**
  を発行する。hint は **brk heap top の 512KB 切下げアドレス** (= live heap の内側) だった。
- Linux の契約: MAP_FIXED 無しの addr は hint。**範囲が空いていればそこを使い、塞がっていれば kernel が
  別の場所を選ぶ** (既存 mapping を壊すことは無い)。
- 旧 NativeMemoryBackend.anonMmap は `adrs != 0` を無条件 MAP_FIXED 扱いし、既 map ページを bulkZero
  (§4.4o の .bss zero-fill 修正) していた → **live な malloc top chunk が zero され** prev_inuse(old_top)
  が落ち sysmalloc assertion。software はアドレス配置が違い hint が heap に当たらないため顕在化しない。

修正 (native のみ、software は byte-identical 不変):
- `MemoryBackend.alloc_and_map(...,long flags)` default overload 追加 (default は flags 無視 = software
  従来挙動)。amd64_mmap が flags を渡し、native だけ override。
- anonMmap(adrs, sz, **fixed**): `!fixed && adrs!=0` は hint — 範囲 [va,va+len) が全ページ未 map なら
  honor、1 ページでも塞がっていれば kernel-chooses (高位 bump) に relocate。honor は `va>=0x10000 &&
  va+len<=HINT_VA_MAX(0x7E0000000000)` に限る (高位 bump 帯に hint が居座ると後続 bump と同一ページを
  alias するため、bump 帯の到達可能域より 2TB 下で切る。MAP_FIXED 無し hint の relocate は常に合法)。
- **set_curbrk: 成長先に他 mapping が居たら Linux 同様 fail** (return false → sys_brk が旧 brk を返す →
  glibc malloc は mmap arena に fallback)。hint mmap が heap 直上に置かれた後の brk 成長で、旧実装の
  mapRange (既 map skip) だと heap と mmap が同一ページを alias して silent corruption になるため。
  brkHigh (歴代最高 brk) を導入し、自分が shrink した領域の再成長は検査しない (誤 ENOMEM 防止)。
  fork (duplicate) は brkHigh も複製。

**(2) software: PUSHFQ (0x9C) / POPFQ (0x9D) 未実装 (cov9 時に node が unknown opcode 0x9d で死亡)**

Cpu64 dispatch に追加。RFLAGS は **実 CPU の architectural layout** で構成 (bit1 常時 1、IF は CPL=3 で
常に 1 に見える、POPFQ の TF/IF/IOPL はユーザモードで実 CPU が黙って無視するので非反映)。JSC の SCASB
0xAE と同類の「JS JIT 出力で初めて踏む opcode」。66 prefix (16-bit PUSHF) は rsp を黙って壊さず unknown
報告へ。検証 = 新 pushf64.c (-nostdlib): cmp/add で CF/ZF/SF/OF、ucomisd(NaN) で PF (software の pf field
は FP 比較経路のみ追跡のため)、popfq→setcc 読み戻し。**host 実 CPU == software == native の 3 者一致**。

**cov10 (node/V8)**: `node -e 'map/regexp/JSON 連結 1 行'` が native==software byte 一致 (3 回反復 +
hot loop 1e7 でも確認)。claude (bun/JSC) に続く第 2 の JS engine。V8 は 512MB code range 等を eager
予約するので EMULIN_NATIVE_POOL_MB=8192 (claude と同じ)。host に node 無い CI は SKIP。

**検証**: native-oracle 86→88 (pushf64 + cov10 node) / run-fast 231 (pushf64 +1)。既存 86 oracle は
mmap/brk 修正後も全 PASS (無回帰)。残: ruby は software backend SIGSEGV (別バグ、未着手)。

### 4.4ee Phase 0 step 3d-2c-33: ★ x86-64 main stack を 8MB 化 — ruby (CRuby/YARV) の SIGSEGV 解消

cov9 で記録した「ruby = software backend SIGSEGV」の真因特定と修正。perl/python/node に続く第 4 の
実用 interpreter を cov11 として oracle 固定。

**真因 = main stack mapping (1MB 固定) と getrlimit 報告 (実 8MB) の不整合**。SEGV dump の読み:
fault は `load8 0x7ffeffeff998` = stack segment [0x7ffefff00000, 0x7fff00000000) (1MB) の直下、
RBP=0x7ffefffffad0 (stack 上端付近)、RSP=0x7ffeffefe9a0 → **RBP−RSP ≈ 0x101130 (~1.05MB) の単一
巨大 frame** = ruby 3.2 の初期化が ~1MB の stack frame を 1 関数で取り、1MB mapping の底を踏んだ。
Linux は main stack を RLIMIT_STACK (既定 8MB) まで自動成長させ、emulin の getrlimit/prlimit64 は
本物の rlimit を返す (CLAUDE.md 既定) ので、guest は 8MB 使える前提で動く — mapping だけが 1MB
だった。無限再帰ではなく正当な深い stack 使用 (8MB 化で即完走、それ以上は使わない)。

**修正**: `RootSysinfo.stack_size64 = 0x800000` (8MB) を新設し、x86-64 の stack() 3 site (Elf.java
static/dynamic + Memory.genProcSelfMaps の stackLow) だけ切替。**i386 は stack_size=1MB 据置**
(32-bit 経路の layout を触らない)。native backend は ELF の .stack segment (PT_LOAD) 経由で自動的に
同じ 8MB が適用される (lazy pool なので RSS 影響は touch した分のみ)。

**cov11 (ruby)**: `ruby --disable-gems -e 'hash/block/regexp/Range#reduce 1 行'` が native==software
byte 一致。YARV bytecode VM + libruby 動的リンク。host に ruby 無い CI は SKIP。

**検証**: native-oracle 88→89 (cov11 ruby) / run-fast 231 PASS 0 FAIL (8MB stack で全 software 回帰
無影響)。

### 4.4ff Phase 0 step 3d-2c-34: ★★ node/V8 重 workload scout で software backend の 3 バグ発見・修正 — IMUL CF/OF + x87 FPREM + brk/mmap alias

user 指定の「node の重い workload」着手。tier-up/OSR/deopt/GC churn/WASM/worker_threads/SAB Atomics/
crypto/npm の battery を両 backend で scout した結果、**native は全 workload 一発 PASS、software は
3 つの実バグで全滅**していた。3 件とも修正し cov12 として固定。**いずれも「software は canonical」の
前提を崩す silent wrong answer / SIGSEGV** で、V8 という命令網羅性の高い workload が掘り出した。

**(1) IMUL の CF/OF 未設定 (silent wrong answer)**: `0F AF` は `of=0;cf=0` ハードコード + 32-bit 形の
符号無視、`6B`/`69` (imm 形) は flags 未設定 (stale)、`F7 /5` 32-bit は `cf=of=0`。IMUL の CF/OF は
「結果が dest 幅に収まらないとき 1」(Intel SDM)。V8 は smi 乗算の overflow を `imul r32; jo deopt`
で検出するため、OF が立たないと int32 が silent wrap し `46341*46341` が負値になる (deopt 不発)。
scout の hot loop `(s+i*i)%1000003` が wrong answer になった主因の片方。F6 (8-bit) と F7 64-bit、
JIT helper (jitIMul64*) は正しかった。**横展開で乗算 family 全 site を監査して 4 site 修正**。

**(2) x87 FPREM (D9 F8) が silent no-op (silent wrong answer)**: V8 の Float64Mod は
「`fprem; fnstsw ax; C2 が立つ間ループ`」で実装される。旧 Cpu64 は D9 F8 を「未対応 no-op」で
握り潰し fnstsw の C2=0 → ループ即終了 → **dividend がそのまま剰余として返る**
(`90000000000 % 1000003` → 90000000000)。smi を超える数値の `%` は全部この経路 = V8 の数値計算が
広範に壊れていた。fix = FPREM (truncating、Java の double `%` は C fmod と同じ truncated remainder
で double 入力に exact) + FPREM1 (round-to-nearest、Math.IEEEremainder) を実装し、常に完全剰余 +
C2=0 を返す (実 fprem は指数差 ≥64 で部分剰余だが consumer は C2 ループなので as-if 等価)。
quotient 下位 3 bit は SDM 通り C0←Q2/C3←Q1/C1←Q0。
(1)(2) の hermetic 回帰 = **新 mulmod64.c** (-nostdlib、host 実 CPU == software == native 3 者一致)。

**(3) brk 成長が hint mmap を貫通して仮想アドレス空間が alias (SIGSEGV)**: 全 heavy workload が
同一 RIP (`RelocIterator(Tagged<Code>,int)` 先頭+5) で load8 fault addr=0x1f。scout 手順 =
fault RIP→nm で関数特定→SEGV dump に ET_EXEC 帯の backtrace 追加→`[rsp]`=戻り番地から caller =
`InstructionStream::RelocateFromDescWriteBarriers` と確定→**EMULIN_WATCH_STORE_ADDR (#113 の
watchpoint 基盤) で istream の code field への store を監視**→「store は実行されているのに read が
0 を見る」+ region label で真因確定: **V8 が起動時に free 域 0x16880000 へ hint mmap した 512MB
領域を、その後 glibc の brk 成長が貫通** (brk segment 終端 0x16888000 > 0x16880000)。Linux は brk が
既存 mapping に当たると失敗する (glibc は mmap arena に fallback) が、software の
`Elf.set_curbrk → Segment.expand_memory` は無条件成長 → brk Segment (byte[]) と AllocInfo (byte[])
が同一仮想範囲を二重 backing → store と load が別の backing に行き「書いた値が読めない」。
**= native backend で #281 として修正した brk 衝突 fail と同型の software 版**。fix = Memory に
set_curbrk override (新規 backing が増えるページ範囲 [現 buf 終端, page-ceil(_brk)) に alloclist
mapping が居たら false) + 対称の hint 側 guard (6-arg alloc_and_map override: MAP_FIXED 無し hint が
segment/alloclist と重なるなら kernel-chooses に relocate。resolve_fixed_overlap は alloclist 同士
しか見ず ELF segment との重なりを検出できない)。

**cov12 (node/V8 重 workload)**: a=tier-up/deopt/GC/JSON/regexp/WASM 一括、c=crypto
(node 静的 OpenSSL: chained sha256/pbkdf2/hmac)。期待値は JS 仕様で決定的 = node version 非依存。
npm --version も両 backend で確認。

**follow-up: worker_threads (multi-isolate + SharedArrayBuffer/Atomics) は 3d-2c-35 で別途調査** (当初
「software で deadlock」と書いたが誤診で、§4.4gg で「libuv バグではなく Java heap 不足の OOM」と判明)。
本 step では vm/wasm + crypto を cov12 に固定。

★教訓: (a) 命令網羅性の高い workload (V8) は「software が canonical」の検証そのもの — silent
wrong answer 2 件 (IMUL OF / FPREM) は oracle (native==software) では出ず、host との比較で初めて
見えた。(b) SEGV dump の backtrace + watchpoint 基盤 (#113) が region label 込みで「store と load が
別 backing」を直接示した = alias バグの決定的診断。(c) 1 つのバグ (brk-alias SEGV) を直すと、それに
隠れていた次の現象 (worker の OOM) が露出する = 重 workload は段階的に深いバグを掘る。

### 4.4gg Phase 0 step 3d-2c-35: worker_threads の「deadlock」は libuv バグでなく Java heap 不足の OOM — cov12 worker を oracle 化

3d-2c-34 で「worker_threads が software で deadlock」と記録した follow-up を精査した結果、**libuv の
eventfd cross-thread 通知バグではなく、テスト harness の Java heap 不足による OutOfMemoryError artifact**
と判明。worker_threads は production の launcher 設定 (`-Xmx8g`) では**元から正常動作**していた。

**診断**: jstack では main + worker loop が epoll_wait、V8 helper が futex で「相互 deadlock」に見えたが、
syscall trace の末尾に **`Thread64[10003] crashed: java.lang.OutOfMemoryError: Java heap space`**。真因 =
node worker_threads は worker ごとに **2 つ目以降の V8 isolate** を作り、各 isolate は heap cage / thread
stack を **PROT_NONE / MAP_NORESERVE で 8MB〜537MB 予約** する (Linux は予約のみ・touch 時 lazy commit)。
software backend は予約全体に実 `byte[]` を eager 確保するため、main isolate (default 2GB heap にぎりぎり
収まる) に worker の 2 つ目 isolate が乗った瞬間 heap を使い切り、worker thread が OOM で死亡 → main が
worker の message を永久 epoll 待ち = **見かけ上の deadlock**。私の scout/oracle が `-Xmx` 未指定 (default
2GB) だったための artifact で、launcher の `-Xmx8g` では発生しない。

**検証**: 適正 heap (`-Xmx6g`) で single worker / 4 worker (cov12b) とも **software==native==host** 完走
(`cov12b:89995,89996,89997,89998:80000`)。native は off-heap KVM pool 使用なので default Java heap のまま
通過。= **コードのバグは無く、worker_threads は両 backend で正しく動作する**。

**試した修正と却下**: PROT_NONE/NORESERVE の予約 mmap を `alloc_huge` の sparse (1MB chunk 遅延確保) に
流して default 2GB でも収める案を実装したが、V8 がその領域を後で **実行コード arena** に使うため
execution-from-sparse の coherence で SEGV (claude/JSC は sparse を data=gigacage にしか使わず実行コードは
byte[] のため未露出だった)。-Xmx で production と揃える方が確実かつ production 忠実なので sparse 案は却下。

**cov12 worker を oracle 化**: `oracle_node12` に software 側 java opt 引数を追加し、worker_threads test
(4 worker + SharedArrayBuffer/Atomics、native は実 LOCK 命令を vCPU 間で実行) を software は `-Xmx6g`
(EMULIN_ORACLE_XMX で上書き可)・native は default で走らせ native==software byte 一致を固定。multi-isolate
+ Atomics という重要 subsystem のカバレッジ。

**検証**: native-oracle 91→92 (cov12 workers 追加) / run-fast 無変更 (oracle script のみ、core 変更なし)。

★教訓: 「multi-thread が hang」を見たらまず jstack で「全 thread が待ち」か「1 thread が OOM/crash して
他が待ち」かを区別する。前者は通知/wakeup バグ、後者は resource (heap) 不足。syscall trace 末尾の
`OutOfMemoryError` 一行が「libuv 通知バグ」という最初の誤診を覆した。emulin の launcher は `-Xmx8g` 既定
なので、raw `java` で再現するときは production と同じ heap を渡さないと OOM を logic バグと誤認する。

### 4.4hh Phase 0 step 3d-2c-36: ★ 大規模実リポジトリ (google/mozc) の HTTPS git clone が native で完走

emulin の看板「HTTPS clone 動作」を、**大規模な実リポジトリ google/mozc** で native 実証 (user 指定)。
3d-2c-20 (#265) で fork が native 対応し git clone が動くようになっていたが、検証は Hello-World /
git.github.io 程度の小 repo だった。mozc は **shallow clone (depth 1) でも pack ~29MB / working tree
~193MB (1745 file)** の重い workload で、TLS pack 受信 (gnutls/OpenSSL = crypto) + index-pack (zlib
解凍 + delta 解決) + checkout (大量 file 書込) + git→git-remote-https の fork+exec を総合的に通す。

**結果 (native、WSL2 nested KVM、8GB pool)**: `git clone --depth 1 --single-branch
https://github.com/google/mozc` が **RC=0 / 112 秒** で完走。.git=29MB (full pack 受信) / worktree=193MB
(1745 file checkout) / HEAD=9afbd98、README/AUTHORS 等の checkout 内容も正常。pack 受信は ~250-300
KiB/s (emulin の TLS throughput が律速、CPU でなく network/暗号復号 path)、delta 解決 (710 delta) +
193MB checkout も完走。**native は実 CPU の AES-NI で TLS crypto を捌けるので大 repo でも実用的**
(software は crypto emulation が遅く大 repo の HTTPS は server idle timeout で非実用 = 3d-2c-17 既知)。

**bench-git-clone.sh 新設** (network 依存で CI 外、回帰スイート非登録)。host git + git-core helper
(git-remote-https) + ldd 依存 lib (gnutls/OpenSSL/zlib/brotli 等 32 lib) + ld.so + ca-certificates +
resolv.conf を sandbox に bundle し、`--depth 1 --single-branch` で clone して所要時間 + .git/worktree
サイズ + HEAD を出す。`BENCH_GIT_URL` で対象変更可 (既定 mozc)、`BENCH_GIT_SOFTWARE=1` で software も
試せる (小 repo 推奨、大 repo は遅すぎる)。`safe.directory=*` + `http.sslCAInfo` を `~/.gitconfig` に
置き ownership check 回避 + CA path 明示。Java heap は launcher 同等 `-Xmx6g` (3d-2c-35 の教訓)。

**native code 変更ゼロ** = 既存の fork/動的リンク/network/TLS 基盤がそのまま大 repo を通す実証
(curl HTTPS #262 / git clone #265 の延長)。oracle 化はしない (network 非 hermetic + software は完走
しない非対称)。docs §4.4hh。

### 4.4ii Phase 0 step 3d-2c-37: ★ Go (golang) の実行ファイルが両 backend で動作 + go build が native で完走 — 3 バグ修正

user 指定「Go 言語のコンパイルと実行ファイルの実行」。Go の実行ファイルは独自ランタイム (goroutine
スケジューラ / GC / signal-based preemption / clone で M スレッド生成 / futex 協調) を持つ静的 binary で、
emulin の signal / clone / futex / mmap 基盤を広範にストレスする。host 製 Go binary を emulin で走らせる
scout で **3 つの実バグ**を発見・修正し、Go runtime が native==software で動くようにした。

**(1) rt_sigaction が RT signal を EINVAL → Go initsig が "fatal error: sigaction failed" で即死**:
Go の `initsig` は全 signal (1..64) を反復して sigaction する。emulin は `Signal.SIGNALS=32` で signal
34 (SIGRTMIN) の sigaction を範囲外 EINVAL にしていた → Go が即 throw。Linux の _NSIG=65 (signal 1..64、
32..64 が RT signal) に合わせ **SIGNALS=32→65** に拡大、rt_sigaction の guard も `>=65`。RT signal は
実配信せずとも登録/照会が成功すれば良い (Go は SIGURG=23 でのみ preempt)。thread_pending int[] も 65。

**(2) native の clone(thread) 子が親 GPR を継承せず → Go runtime.clone で triple fault**: native の
spawnVCpu は子 vCPU の RIP/RSP/RFLAGS のみ設定し他 GPR は 0 だった。glibc pthread は start 関数を
stack/TLS から読むので rsp だけで足りたが、**Go の `runtime.clone.abi0` は child path で `call *%r12`
(r12=mstart fn ptr) を実行する** — register 経由で entry を渡すため、r12=0 で call 0 → triple fault
(raw=8 KVM_EXIT_SHUTDOWN、全 register 0)。Linux clone は子の register を親と同一にする (rax=0/
rsp=child_stack のみ差分) のが正。fork (#265) と同じく **親の全 GPR snapshot を子に継承** (cloneRegs)、
setupVcpu で復元 + rax=0/rip=親RCX/rsp=child_stack/rflags=親R11 を上書き。glibc pthread も Linux 同様の
全 GPR 継承で無回帰 (pthread_basic/mutex/sigmask 全 PASS)。

**(3) interpreter (software) が RCL/RCR (Grp2 /2 /3) 未実装 → "unsupported Grp2 /3" で停止**: Go の
除算-by-定数 magic 等が `rcr $1,%rdx` を使う。Cpu64.exec_grp2_shift は ROL/ROR/SHL/SHR/SAR のみで
rotate-through-carry (RCL=/2, RCR=/3) が無かった。(size+1)-bit の [CF:operand] を count 分回転する
ループで実装、CF/OF を Intel SDM 準拠で設定 (OF は 1-bit rotate のみ定義)。native は実 CPU で元から OK。

**検証**: host 製 Go binary (goroutine+channel+map+sort) が **native==software==host** byte 一致
(`go-hello: total=33598383 ...`、software ~slow だが完走)。軽量版 (4 goroutine+channel) は software
4s / native 1s。汎用命令 RCL/RCR は **新 rcr64.c** (host==software==native 3 者一致) を cov として
oracle 固定。Go runtime 固有 (SIGNALS=65/clone GPR) は host go 依存なので bench-go.sh に。

**(4) nanosleep の sub-millisecond を即 return で潰していた → Go usleep spin が busy-loop 化**:
`amd64_nanosleep` は `nsec/1_000_000` で ms 換算し sub-ms (例 usleep 数 µs) を ms=0 → 実 sleep せず
即 return していた。Go runtime の usleep ベース spin-backoff (sysmon / osyield / lock 取得待ち) が
実遅延ゼロの busy-loop になり Thread-2 が 100% CPU spin。`LockSupport.parkNanos(nsec)` で実 ns 待ち
に修正 (clock_nanosleep が #113 で同根の修正済なのと対)。修正後 spin は 100%→~34% CPU に低下。

**★go build (コンパイル) は emulin native で hang [follow-up]**: go toolchain (go→compile→link) を
fork+exec で走らせる試み。**go コマンドが compile を fork+exec する前に停止**する (trace=clone は
Go の M thread 生成のみ・execve 無し・FS 書込ゼロ・nanosleep #35 spin)。判明した付随問題 2 件は解決:
(a) Debian の GOROOT/src は `../../share/go-1.x/src` への symlink で sandbox 内 dangling → std lib
未発見 (cp -rL で実体化)、(b) 上記 nanosleep sub-ms (busy-spin→parkNanos)。だが**両者修正後も go
コマンドは前進せず** (now spin でなく sleep するが workdir 未生成のまま)。= go の build 実行エンジン
(goroutine + timer + 内部協調) が emulin native 上で待ち合わせる条件が満たされない、より深い
scheduler/timer wakeup の問題。hello.go (8 goroutine) は完走するので Go runtime 自体は動くが、go
コマンドの複雑な並行制御が hit する未解決ケース。**コンパイルは follow-up、実行ファイル実行が本 step
の成果**。

**oracle**: rcr64 (汎用命令、host==software==native)。Go binary の run/go build は host go 依存で
bench-go.sh (CI 外、bench-curl/bench-git-clone と同列)。docs §4.4ii。

### 4.4jj Phase 0 step 3d-2c-38: ★★ go build hang の真因究明 = Go async preemption が native の syscall 境界 signal 配信と非互換

3d-2c-37 で follow-up とした「go build (および go env/go help 以外の全 go サブコマンド) が hang」を
深掘りして**真因を特定**。原因は 3 層あり、上から順に剥がした。

**(層 1) env が guest に届いていなかった (test harness)**: emulin は再現性のため default で host env を
guest に渡さない (`EMULIN_INHERIT_ENV=1` か `EMU_` prefix が要る、launcher は設定)。go コマンドは
GOROOT/GOCACHE/GOPATH/HOME 等を要するが、これらが届かず go が build cache dir を決められず spin して
いた。`env GOCACHE=... java ...` で渡しても guest に伝わらないのが盲点。`EMULIN_INHERIT_ENV=1` で解決
(検証=Go 製 env プローブで `os.Getenv("GODEBUG")` が空→値ありに)。

**(層 2) GOROOT/src が sandbox 内で dangling symlink (test harness)**: Debian/Ubuntu の GOROOT/src は
`../../share/go-1.x/src` への symlink で、`/usr/lib/go-1.22` だけ sandbox に置くと src が解決不能。
`cp -rL` で実体化。

**(層 3=真因) Go の async preemption が emulin native で配信不能**: 層 1/2 を直すと go は build cache /
work dir 作成まで前進するが、依然 hang。**`GODEBUG=schedtrace=1000,scheddetail=1` (env が届いたので
出力されるように) で scheduler 状態を観測** = `G1 (main goroutine) status=2(chan receive)` で永久 block、
他は GC 系 idle goroutine のみ・runnable goroutine ゼロ・しかし deadlock 検出されず。syscall trace =
最後の実行的 syscall が netpoller init (epoll_create1/pipe2) + **tgkill(#234) + sigaltstack** の後
nanosleep spin。★**`GODEBUG=asyncpreemptoff=1` で go env/go help 等が安定動作** (3/3 PASS) = 真因確定。

**機構**: Go runtime の sysmon は、cooperative preemption point (関数 prologue の stack check) を持たない
tight-loop を実行中の goroutine を停止させるため、`tgkill(SIGURG)` を送って **非協調 async preemption**
する (`runtime.preemptM`→signal handler `sigPreempt` が goroutine を safepoint へ追い込む)。一方
**emulin native は signal を syscall 境界でしか配信できない** (#258、実 vCPU は per-instruction check
不可なので kill 等は syscall 戻り際に配信)。よって guest code を tight-loop 実行中の goroutine には
SIGURG が永久に届かず、その goroutine の preemption を待つ scheduler (stopTheWorld / suspendG) が stall、
main goroutine が chan receive で永久待ちになる。go version/go env が「go version は動く」のは前者が
preemption 不要の最小経路だから。

**回避策**: `GODEBUG=asyncpreemptoff=1` (async preempt を切り cooperative のみに)。bench-go.sh に
`EMULIN_INHERIT_ENV=1` + `GODEBUG=asyncpreemptoff=1` + `cp -rL` を組込み、go env 等が動くようにした。

**残 (follow-up)**: ①回避策込みでも `go build` は **並行 source read で EBADF** (`read .../hypot_asm.go:
bad file descriptor`) に当たる別の fd 並行性バグ (Go が複数 std lib source を並行 read する経路)。
②**真の修正 = emulin native の async signal 配信** = signal を queue した vCPU thread が KVM_RUN 実行中
なら host signal を vCPU thread に送って KVM_RUN を -EINTR 脱出させ、guest signal frame を組んで再入する
(KVM の標準的な vCPU 割込み手法)。worker の Linux TID 取得 + FFM tgkill + host signal handler + EINTR
処理が要る中規模 feature。これを入れれば asyncpreemptoff 不要になり、async-preempt する全 Go program が
native で動く。**診断完了・proper fix は次 step**。docs §4.4jj。

### 4.4kk Phase 0 step 3d-2c-39: ★★ native の async signal 配信を実装 — 実行中 vCPU を割込んで syscall 境界外で signal 配信

3d-2c-38 で特定した「emulin native は signal を syscall 境界でしか配信できない (#258)」制約を撤廃し、
**KVM_RUN で guest code を実行中の vCPU を host signal で割込んで、被中断点 (任意命令) で guest signal
handler を起動する** async 配信を実装した。

**機構**: (1) host SIG_KICK (sun.misc.Signal で no-op handler を install、SIGUSR2 等) を用意。(2) 各
vCPU は eval 開始で自分の Linux TID (FFM `syscall(gettid)`) を guest tid に紐付け RUNNING_TIDS へ登録。
(3) signal が queue される (recv_to_thread/recv) と Signal.asyncKick hook が走り、対象 guest tid の
走行中 vCPU に `tgkill(pid, host_tid, SIG_KICK)` (FFM) を送る。(4) KVM_RUN が EINTR で脱出 (KvmVcpu.run
は EINTR を内部 retry せず EXIT_INTR を返すよう変更)。(5) eval loop は EXIT_INTR で pending guest signal
を確認し async 配信する。

**delivery (被中断点での handler 起動)**: EXIT_INTR 時 vCPU は ring-3 (user code 実行中) なので、sysretq
を経由せず RIP=handler を直接設定して handler を起動する (sync の syscall 境界配信は sysretq 経由)。被中断
context (全 GPR + RIP/RFLAGS/RSP) を SigFrame に保存。

**restore (rt_sigreturn)**: ★被中断点は任意命令で RCX/R11 も live なので、sysretq (RIP←RCX/RFLAGS←R11
で RCX/R11 を潰す) は使えない。代わりに **全 GPR (RCX/R11 含む) を復元し RIP/RFLAGS/RSP を被中断値に
設定して `hv.exitToRing3()` (KVM_GET/SET_SREGS で CS=0x33/SS=0x2b に遷移) で ring-0 (rt_sigreturn syscall
trap 後) から ring-3 被中断点へ直接遷移する**。さらに **handler が ucontext を書換える場合 (= 実行の
リダイレクト) に対応するため、SA_SIGINFO のときは内部 frame でなく guest stack の uc_mcontext (handler
改変済) から復元する** (Go の async preemption が ucontext を書換えて asyncPreempt へ飛ばす設計のため)。

**検証**: 新 async_signal_dyn64 (worker が syscall-free tight-loop で spin 中に main が SIGUSR1 を
pthread_kill。修正前は worker が永久 spin して native hang、修正後は被中断点で handler 起動して
`async: delivered=1 onworker=1`) が **native==software** byte 一致 (5 回反復 deterministic)。
**native-oracle 93→94 ok / 0 FAIL** (6 signal test/pthread/fork 含む既存全 binary 無回帰)、run-fast 234。
WhpVcpu.exitToRing3 は throw stub (WHP の async 配信は Windows-gated follow-up)。

**残 (当初 follow-up と書いたが §4.4ll で否定): Go の async preemption はまだ動かない** (go env は
asyncpreemptoff 無しで依然 hang)。async 配信自体は動く (合成テスト)。当初は Go の `runtime.asyncPreempt`
が完全 signal frame (fpstate) を要求するためと推測したが、**§4.4ll の精査で go env hang は async
preemption と無関係 (Go は signal を一切送らない) と判明** = 完全 signal frame を作っても go env は直らない。

### 4.4ll Phase 0 step 3d-2c-40: ★ go env/go build hang の再診断 = async preemption は無関係 (#287 の誤診訂正)、真因は netpoller/scheduler deadlock

#288 で native の async signal 配信を実装したが go env は依然 hang。**「Go の signal frame を完全化すれば
直る」という #287/§4.4jj の前提を検証した結果、誤りと判明した**。

**決定的証拠 (計装による)**: go env hang 中に (a) **`amd64_tgkill` が一度も呼ばれない** (Go は preemption
signal SIGURG を送っていない)、(b) **async 配信が一度も発火しない** (`deliverPendingSignal(async)` の
`psig()` が常に -1)。同じ計装は合成テスト async_signal_dyn64 では正しく発火する (tgkill/async psig が出る)
ので、計装は健全。= **go env hang は Go の async preemption を全く経由しない**。よって Go 用の完全 signal
frame (fpstate 込み rt_sigframe) を実装しても go env は直らない (#287 の「async preemption が真因」は誤診)。

**真の hang 状態 (jstack)**: 全 thread が JVM レベルで block = main(M0) が **epoll_wait** (netpoller、
Thread.sleep ポーリングで KVM_RUN ではない) / worker(idle M) が **futex** (Object.wait)。Go の main
goroutine G1 は chan receive 待ち。= netpoller/scheduler の cross-thread 通知 (pipe/eventfd/futex wake)
が emulin で噛み合わず、送り手 goroutine が走らないまま全員が待ち合う **timing-sensitive deadlock**。
`asyncpreemptoff=1` (8/8 reliable) と `EMULIN_TRACE_SH=1` (syscall を遅くする) はいずれもこの timing を
**signal 経由でなく** 偶然回避しているだけ (= async preemption 仮説の誤った状況証拠だった)。

**結論**: go (go env/go build) の hang は native の async signal 配信 (#288、汎用 handler で動作する実機能)
とは別問題。真因は Go netpoller の cross-thread wakeup と emulin の epoll/futex/pipe 通知の噛み合わせで、
worker_threads OOM (§4.4gg) や libuv 通知系と同系統の調査が要る別 step。**本 step は誤診の訂正と真因の
局在化のみ** (コード変更なし、docs/memory 訂正)。Go の実行ファイル実行 (#286) は動作。

### 4.4mm Phase 0 step 3d-2c-41: ★ go env/go build hang の真因 = SA_ONSTACK/sigaltstack 未実装 (netpoller deadlock の根本修正)

§4.4ll で「真因は netpoller/scheduler deadlock、別 step」と局在化したが、**それは下流の症状で、真の
root cause は emulin が `sigaltstack(2)` を no-op stub にし `SA_ONSTACK` を無視していたこと**だった。

**決定的証拠 (計装による)**: go env (GOMAXPROCS=1 で 3/3 reliable hang) 中の signal 配信を `deliverPendingSignal`
で one-shot ログした結果、**`sig=23` (SIGURG = Go の async preemption signal) が sync 経路 (async=false、
syscall 境界) で main thread に配信されていた**。§4.4ll が「tgkill 呼ばれない/async 配信が発火しない」と
したのは **async 配信経路 (`deliverPendingSignal(async)`) だけを計装していた**ためで、実際は **sync 経路**で
配信されていた (= §4.4ll の「async preemption は無関係」は誤り、SIGURG は確かに関与していた)。

**spin の正体 (`go tool addr2line` で逆引き)**: hang 中の main vCPU は `runtime.usleep` を無限に呼んでいた。
caller を辿ると `lockextra (stubs2.go:25) ← getExtraM (proc.go:2561) ← needm (proc.go:2196) ←
adjustSignalStack (signal_unix.go:589) ← sigtrampgo ← sigtramp`。`adjustSignalStack` の line 589 は
**「signal が gsignal/g0/sigaltstack のどれでもない foreign stack 上で起きた」**の bad path =
`setg(nil); needm(true)`。needm は extra M を取りに `lockextra` で無限 spin する (非 cgo では extra M が
無く `usleep_no_g(1)` で永久待ち)。

**root cause**: Go runtime は **全 signal handler に `SA_ONSTACK` を立て**、各 M ごとに `sigaltstack` で専用
signal stack を登録する。実機 Linux は handler を必ずその alt stack 上で起動する。ところが emulin は
(software/native 両方とも) `sigaltstack` を `return 0` の no-op にし、handler を**被中断点の stack
(SIGURG の場合は main goroutine G1 の stack) 上で起動**していた → Go の `adjustSignalStack` が
「foreign stack の signal」と誤認 → needm → lockextra 無限 spin → M0 が固まり G1 の chan receive を
unblock できず全員が待ち合う (= §4.4ll が観測した "netpoller deadlock" の正体)。

**なぜ software は動いて native は hang したか**: 両 backend とも SA_ONSTACK を無視するが、**配信される
被中断 context の stack が timing で異なる**。software は SIGURG 配信時の被中断点が偶々 g0/gsignal stack
だったため `adjustSignalStack` の check を通過していた (= まぐれ)。native は main goroutine stack
だったため bad path に落ちた。**正しい挙動は「常に alt stack で起動」**なので、両 backend を同じ alt-stack
ロジックに直すのが本筋。

**fix (software/native 両方に同一適用、native-oracle byte 一致を維持)**:
- `Siginfo`: `SA_ONSTACK = 0x08000000` + `has_sa_onstack()`。`rt_sigaction` は既に sa_flags 全体を
  保存しているので SA_ONSTACK も透過。
- `Signal`: per-thread alt stack を `ConcurrentHashMap<tid,{ss_sp,ss_size,ss_flags}>` で保持
  (`set_alt_stack`/`get_alt_stack`)。`sig_alt_stack_base(sig,cur_rsp)` = SA_ONSTACK かつ有効 alt stack
  登録済かつ被中断点が alt stack 外なら alt stack の top を返す (nested signal は継続使用、それ以外は -1)。
- `SyscallAmd64.amd64_sigaltstack(uss,uoss)` (#131): `struct sigaltstack {ss_sp@0; ss_flags@8(int);
  ss_size@16}` を get/set。未登録時は `oss.ss_flags=SS_DISABLE(2)` を報告 (Go の minit が SS_DISABLE を
  見て自前 gsignal stack を登録する流儀に合わせる)。
- `Cpu64.check_pending_signal` (software) / `NativeCpuBackend.deliverPendingSignal` (native): handler
  起動 stack を `sig_alt_stack_base() >= 0 ? alt_top : (rsp - 128 red zone)` で決める。SA_ONSTACK 無し /
  alt stack 未登録のシグナル (emacs SIGABRT 等) は従来通り被中断 stack を使う = 既存挙動を保つ。

**効果**: go env (GOMAXPROCS=1 / default 共に) native で 6/6 完走 = netpoller hang 解消。go build は
さらに先へ進み、**software と同じ別バグ (並列 goroutine の concurrent file read で `bad file descriptor`)**
で止まる = native==software に収束 (この fd バグは shared、別 step)。

**検証**: 新規 hermetic 回帰 `sigaltstack_dyn64` (SA_ONSTACK handler が登録済 alt stack 上で走ることを
ローカル変数アドレスで検証、host 実機と byte 一致 `query=1 handled=1 onalt=1`) を native-oracle + run-fast
に追加。native-oracle 95 ok / 0 FAIL / 1 SKIP (busybox-static)、run-fast 235 PASS / 0 FAIL。既存 signal 系
(sys_signal_delivery64/sys_sa_siginfo64/sys_sigmask64/async_signal_dyn64 等) 全て無傷。docs §4.4mm。

### 4.4nn Phase 0 step 3d-2c-42: ★ go build の "bad file descriptor" を解消 = fstatat 空 path + fd table 競合 + epoll EPERM の 3 修正

§4.4mm で netpoller hang を解消後、go build は package loading 中に **`reflect/value.go:13:2: read
.../math/hypot_asm.go: bad file descriptor`** で deterministic に落ちた (software/native 共通)。深掘りで
**独立した 3 つの fd 系バグ**を特定・修正した。

**(1) 真の blocker = fstatat 空 path の errno (EBADF→ENOENT)**。全 syscall の -9 戻りを計装して「build 中
emulin が返す唯一の EBADF = `newfstatat(AT_FDCWD,"",buf,flags=0)`」と特定。emulin は空 path を**無条件に
`fstat(dirfd)` 扱い**にしていて、AT_FDCWD(=-100) を fstat → get_finfo(-100)=null → EBADF。Linux は
**空 path + AT_EMPTY_PATH 無し → ENOENT**、AT_EMPTY_PATH 有 → dirfd 自身を stat。Go の `os.Stat("")` は
ENOENT を `os.IsNotExist` で握って先へ進む設計なので、EBADF だと「未知のエラー」と判断して build が落ちる。
fix=`if(AT_EMPTY_PATH) fstat(dirfd); else if(path.isEmpty()) return ENOENT;`。これで build は package loading
を抜け、stdlib の実コンパイル (compile を fork) まで前進 (= 完走は速度律速 = issue A、別問題)。
★教訓: 「read FILE: bad file descriptor」の様に Go が**別 op 名でエラーを報告**しても、真の失敗 syscall は
全 -9 戻りの計装で特定する (read 計装は空振り = 失敗は fstatat だった)。また **GOCACHE が前の EBADF 失敗を
キャッシュ**しており、fix 後も同じエラーが出続けた → cache 削除で前進を確認 (build cache 系のデバッグ鉄則)。

**(2) fd table の確保競合 (並列 open の EBADF/誤内容)**。`FileAccess` の fd 確保は
`search_empty_fd()` → `addElement/setElementAt` が**非アトミック**で、2 thread が同時 open すると
同じ空きスロットを二重確保 / addElement の index ずれで返した fd が別 Fileinfo (または null) を指す →
read が EBADF/誤内容。hermetic 再現 (6 thread × 共有ファイル open/read/verify/close) で software が ~1/4
の頻度で `mismatch` を出すことを確認。fix=`fdLock` (専用 monitor) を導入し、確保 (`place_fd`) / close の
slot クリア / dup(`Dup`/`sys_dup`/`F_DUPFD`) / cloexec・tty_alias の ArrayList を直列化。blocking I/O
(`finfo.open`/`finfo.close`) は lock 外に置き、#41 で外した「全 read を serialize する method-level
synchronized」(socket read 中の worker deadlock) は復活させない。GOMAXPROCS=1 でも go build が落ちた
ことから本競合は go build の主因では**なかった**が、並列 file read の正しさに必須の独立バグ。

**(3) epoll_ctl(ADD) on regular file/dir → EPERM (Linux 準拠)**。emulin は regular file/directory の
epoll 登録に 0 (成功) を返していた (Linux は EPERM)。Go runtime が source file/dir を pollable とみなして
netpoller に登録する挙動差。実害は (1) の fix で消えたが、Linux semantics に合わせて EPERM を返す。

**効果**: go build は package loading を完走し stdlib コンパイル (parallel compile fork) へ前進。emulin が
返す EBADF はゼロ。完走は cold stdlib compile の速度律速 (issue A) で本 step の対象外。**検証**: 新規
hermetic 回帰 `concurrent_fd_dyn64` (open_err=0 ebadf=0 mismatch=0) + `statat_empty_dyn64`
(noflag_errno=2 emptypath_ok=1) を native-oracle + run-fast に追加、native==software byte 一致。docs §4.4nn。

### 4.4oo Phase 0 step 3d-2c-43: ★ sqlite3 カバレッジ追加 = pwrite64 + fdatasync を実装 (positioned I/O + durability)

カバレッジ拡大の次手として **sqlite3** (file-backed DB の B-tree + rollback journal) を native-oracle に
追加。狙いは**未テストの syscall surface**: positioned write (`pwrite64`)、durability (`fdatasync`)、
byte-range advisory lock (`fcntl` F_SETLK)。

**発見した gap**: file DB に `create table` した瞬間 sqlite が **"disk I/O error (10)"** で落ちた。全 syscall
trace で 2 つの ENOSYS を特定:
- **`pwrite64` (#18) 未実装** — sqlite は DB page / journal を**位置指定書き込み**する (pread64(#17) は実装
  済だったが pwrite64 が欠けていた)。fix=`amd64_pwrite64` を pread64 と対称に実装 (save pos → seek
  offset → write → restore pos、POSIX 通り file position 不変)。
- **`fdatasync` (#75) 未実装** — sqlite は commit 時に sync して durability を取る (fsync(#74) は実装済)。
  fix=fsync と同じく `sys_sync` に dispatch (emulin の write は RandomAccessFile 経由で既に host FS へ
  到達済なので no-op success で十分)。

byte-range lock (`fcntl` F_SETLK) は single-process では no-op success で sqlite が通る (contention 無し)。

**効果**: sqlite3 が file DB で create→insert(BEGIN/COMMIT)→index(B-tree)→集計(SUM/COUNT/GROUP BY)→
ORDER BY/LIMIT を完走、**native==software byte 一致**。**検証**: 新規 hermetic 回帰 `sys_pwrite64`
(-nostdlib、pwrite/pread/fdatasync の position 不変性 + 内容一致を syscall 直叩きで検証) +
cov13 (実 sqlite3、host に無ければ `apt-get download` で取得=非 sudo) を追加。native-oracle 99 ok/0 FAIL/
1 SKIP、run-fast。★教訓: 「disk I/O error」系は positioned I/O (pread/pwrite) と sync (fsync/fdatasync)
の syscall を全 trace の ENOSYS で洗う。pwrite64 は pread64 と対称実装が定石。docs §4.4oo。

### 4.4pp Phase 0 step 3e-whp-2: ★★ 本丸 NativeCpuBackend が WHP 実機で end-to-end 動作 (Stage A)

WHP smoke (long mode / syscall trap / HvVm-HvVcpu 抽象層) は #270 + 2026-06-12 再確認で実証済みだったが、
**`NativeCpuBackend` 本体 (VM + 非 identity MMU + 初期 stack + call_amd64 dispatch) を WHP で走らせる**のが
本丸。調査の結果、抽象化 (HvVm/HvVcpu + KvmVm/KvmVcpu + WhpVm/WhpVcpu + register normalize layer) は前
step で**大半が完成済み**で、NativeCpuBackend は既に `hv`(HvVcpu)/`vm`(HvVm) 経由で動いていた。残る Linux 固有
seam は 2 つだけで、それを Stage A として修正:

**(1) `CpuBackend` が KVM しか知らなかった (Windows で native を弾く真因)**。`nativeAvailable()`/
`nativeDescribe()` が `KvmBindings.probe()` のみを見ており、Windows で `EMULIN_BACKEND=native` が
`verifyImplemented()` の「no hypervisor available / Windows WHP backend は未実装」で exit 2 していた。fix=
`nativeAvailable()` を `KvmBindings.probe() || WhpBindings.probe()`、`nativeDescribe()` を KVM 優先 / WHP
fallback に (HvVm.create() の dispatch と同じ)。**Linux は KvmBindings.probe()=true なので完全に従来挙動
(KVM oracle 無影響)**。

**(2) async-kick 基盤 (gettid/tgkill/SIG_KICK) を WHP で first-class に**。#288 の async signal 配信は Linux
固有 (sun.misc.Signal + tgkill + KVM_RUN EINTR) で、従来は try/catch が Windows での失敗を暗黙に握って
いた。`IS_KVM = KvmBindings.probe()` フラグで明示 gate (ensureAsyncInfra / gettid 登録を KVM のみ)。WHP path
は syscall 境界配信 (#258) のみ = async kick (WHvCancelRunVirtualProcessor) は後続。

**★★結果 (2026-06-12 Windows 実機 10.0.26200.8655 / JDK25.0.3、`run-whp-native.bat`)**: -nostdlib 静的
binary 3 つが **`[backend=native (WHP detected)]`** で exit 0 完走: (1) **hello64**=syscall trap→call_amd64
dispatch、(2) **argvdump64 foo bar**=初期 stack (argc/argv/envp) + 非 identity MMU + page table、
(3) **simd64**=SSE(paddd) を実 CPU 実行 (CR4.OSFXSR/OSXMMEXCPT を WhpVcpu.configureLongModeRing3 が設定)。
**= 本丸 NativeCpuBackend が WHP 実機で end-to-end 動作**。検証: Linux native-oracle byte-identical (Stage A は
KVM 無影響)。**残 (Stage B)**: 静的 glibc (hello_static64 = CPUID 要 → WhpVcpu.setCpuidFromHost を
CpuidResultList で実装) → 動的 glibc (file-backed mmap の Windows 対応) → multi-vCPU / async kick (WHP
cancel-run) → WHP で native-oracle。docs §4.4pp。

### 4.5 Phase 0 step 3d-2c+ (KVM、WSL2 内で次に作る)

- **3d-2 (NativeCpuBackend KVM 経路 + emulin 統合)**: stub の `init`/`eval`/`fetch`/
  `connect_devices`/`set_signal_handler` を KVM で実装。`eval()` は step 3c の
  KVM_RUN → exit_reason 分岐 loop: `KVM_EXIT_HLT` (= LSTAR スタブ) なら sysno=RAX /
  引数=RDI/RSI/RDX/R10/R8/R9 を読み `SyscallAmd64.call_amd64` に dispatch、戻り値を
  RAX に書いて resume (stub の sysretq で ring3 復帰)。`NativeMemoryBackend` (3d-1) を
  `mem` として SyscallAmd64 に繋ぐ + ELF loader で guest RAM に PT_LOAD を配置。
  3d-1 review で確定した中心 work item (compile-breaking):
  - **✅型 widening の seam = `Syscall.mem` を MemoryBackend に (step 3d-2a、PR #244)**:
    調査の結果、syscall 層 (`Syscall`/`SyscallAmd64`/`SyscallI386`/`FileAccess`) が
    `mem` に触る member は **全て MemoryBackend interface メソッド** (load/store 8-64、
    loadString/storeString、bulk*、alloc_and_map/free/realloc/alloc_huge、get/set_curbrk、
    in、genProcSelfMaps、set_map_path、fetch、dump) で、`execLock`/`e_entry`/`segment[]`
    等の Memory/Elf 固有 member は **CPU 側 (`AbstractCpu.mem` = Cpu64 の GIL/ELF)
    だけ**が使う。よって seam は **`Syscall.mem` + `connect_mem` + `FutexManager.wait`
    の 3 箇所を Memory→MemoryBackend に widen するだけ** (AbstractCpu.mem は Memory 据置)。
    Memory IS-A MemoryBackend なので software path は behavior 不変 (run-fast 220/
    run-network 15 PASS)。native backend は `connect_mem(nativeMemBackend)` で差し込む。
    → 上記 review の「(a) ELF/Process state 分離 + GIL interface 化」は不要で、
    syscall 側 mem だけ widen する最小 seam が成立した。
  - **★ELF image は MemoryBackend 契約の外**: PT_LOAD は `Elf.load_body`
    (Elf.java:559) が Memory の `segment[].buf[]` に直接書き (MemoryBackend method 経由
    でない)、stack/auxv は `stack_data_init64` が guest 仮想 ~0x7fff_0000_0000 に
    `store64`。NativeMemoryBackend は identity-map + 有限 size なので PT_LOAD が
    guestRam に届かず (page-walk が 0 fetch)、high stack は OOB。→ native ELF loader
    (PT_LOAD を bulkStoreToMem で guest 物理 + page table build) と guest 仮想→物理
    変換が要る (alloc stub の実装だけでは不十分)。
  step 3c review で確定した実装上の罠:
  - **★`syscall` が hardware 退避した RCX/R11 を保護**: `syscall` は user RIP→RCX /
    RFLAGS→R11 を退避し `sysretq` がそれで復帰する。`call_amd64` 前後で kvm_regs を
    丸ごと write-back すると RCX/R11 を壊し sysretq が wild jump する → trap 時に
    RCX/R11 を snapshot し、戻りは **RAX (戻り値) だけ書く** (or sync_regs で
    `s.regs.rax` のみ in-place 編集 + dirty フラグ)。
  - **`KVM_CAP_SYNC_REGS` で GET/SET_REGS ioctl を消す** (realistic +1.7µs 削減):
    `KVM_CHECK_EXTENSION(KVM_CAP_SYNC_REGS=74)` で gate、`kvm_run` の
    `kvm_valid_regs@288`/`kvm_dirty_regs@296`/`sync_regs@304` (rax.. sub-offset) を使う。
  - **KVM_RUN は EINTR retry + 非 HLT exit 分岐 + `KVM_SET_SIGNAL_MASK`**: JVM の
    GC/safepoint signal で KVM_RUN が EINTR (-1) を返すので retry loop が必須。
    exit_reason を MMIO/IO/INTERNAL_ERROR/FAIL_ENTRY/SHUTDOWN まで handler table に。
  - **isolation 強化**: 現 smoke は page table/LSTAR stub も PTE_US で ring-3 から
    読み書きできてしまう (自分の table を書き換え可能)。US は全 level の AND なので
    upper (PML4E/PDPTE/PDE) は US=1 のまま、**supervisor backing の leaf PTE (page
    table 4 page / LSTAR stub / kernel stack) を US=0** にし user 領域と別 page に分離。
  - **multi-vCPU**: `ioctl(int,long,long)` scalar overload (vcpu index>0) を追加。
- **3e (oracle)**: `tests/binaries/hello64` 等を native で実行 → software と
  stdout/exit byte 一致 (`tests/scripts/run-native-oracle.sh`)。
- **3f (latency 再測定)**: ★非 nested 環境 (bare-metal Linux KVM or Windows WHP) で
  trap latency を再測定し go/no-go の latency 部分を確定 (WSL2 の ~6µs は nested
  inflate)。

**code review で確定した step 3c/3d 設計上の申し送り**:
- **✅ guest RAM は off-heap native MemorySegment (step 3b で確立済)**。emulin の
  `Memory` は Java heap `byte[]` だが、heap array は GC で移動し安定した native
  アドレスを持たないため `KVM_SET_USER_MEMORY_REGION.userspace_addr` に使えない。
  KvmSmoke64 は off-heap `Arena.allocate(2MB)` を使用。step 3d の
  `NativeMemoryBackend` は guest 物理 RAM を単一の off-heap `MemorySegment` として
  持ち `load8`/`store8` はその segment への直 index にする。これは
  `docs/issue221-design-plan.md` §6 の「pinned 領域が要る」と整合。
- **mmap / fd の RAII**: 長命の backend では `vcpuState` mmap と VM/vCPU fd を
  try/finally (or AutoCloseable) で確実に解放する (smoke は process exit 任せ)。
- **KVM_RUN の re-entry loop**: 実 workload は `exit_reason` を `while` で回し、
  EINTR (signal 配信) で再 `KVM_RUN` する。step 3c の syscall trap dispatch の
  土台になる。
- **`ioctl` scalar overload**: `KvmBindings.ioctl` は arg が `MemorySegment` 固定。
  multi-vCPU (vcpu index > 0) には `ioctl(int,long,long)` overload を足す
  (index 0 は NULL==0 で偶然動いているだけ)。
- **errno は captureCallState で確定済**: step 3a で `__errno_location` 後読みを
  `Linker.Option.captureCallState("errno")` に修正済 (KVM_RUN/SET_SREGS の
  EINTR/EFAULT/EINVAL を正しく読める = 3b/3c デバッグの主信号)。

### 4.6 Phase 0 WHP 移植 (Windows 実機、KVM 経路確立後)

KVM 経路が実用 binary まで通ったので WHP 移植に着手。差は「ioctl ⇄ WHvXxx FFM downcall」+「KVM
GET/SET_REGS/SREGS 構造体 ⇄ WHP WHV_REGISTER_NAME[]+WHV_REGISTER_VALUE[16B] name-value 配列」。
★ **WHP は Windows + Hyper-V (Windows Hypervisor Platform 機能 + WinHvPlatform.dll) でしか実行できない**ので、
WSL2 では「実装 + コンパイル + Linux 上での graceful skip 確認」までで、実機検証は Windows で行う。

**step 3e-whp-1 (WhpBindings 拡充 + WhpSmoke64、Windows 実機テスト待ち)**: KVM bootstrap が KvmSmoke→
KvmSmoke64 (#240/#241) で始まったのと同型に、WHP も **WhpSmoke64** から始める (NativeCpuBackend には触らず、
bindings + WHP register/exit ABI を実機で end-to-end 検証する足場)。
- `WhpBindings` 拡充: 既存 7 handle に **SetPartitionProperty / DeleteVirtualProcessor / DeletePartition** +
  kernel32 **VirtualAlloc/VirtualFree** (WHvMapGpaRange の source は page-align 済 commit memory が要る) を追加。
  **WHV ABI 定数を全て写経** (WHV_REGISTER_NAME Rax=0x0..Cr4=0x1F/Efer=0x2001/Star=0x2008…、
  WHV_RUN_VP_EXIT_REASON X64Halt=0x8、WHV_MAP_GPA_RANGE_FLAGS、ProcessorCount=0x1FFF、struct size
  REGISTER_VALUE=16/RUN_VP_EXIT_CONTEXT=144/PARTITION_PROPERTY=32、WHV_X64_SEGMENT_REGISTER layout
  Base@0/Limit@8/Selector@12/Attributes@14 + 64bit code attr 0xA09B/data 0xC093)。値は libwhp/pywinhv/MS Docs で cross-check。
- `WhpSmoke64`: KvmSmoke64 と同一 guest (movabs rax,0x1122334455667788; mov rbx,rax; hlt) を WHP partition で
  long mode 実行し X64Halt + rax==rbx を検証。各 WHP call の HRESULT を出力 (実機失敗時に段を特定)。Linux では
  probe()=false で exit 2。
- **★ Windows 実機テスト手順** (user 側): `java --enable-native-access=ALL-UNNAMED -cp target/classes
  emulin.WhpSmoke64` →「WHP64 smoke OK」。HRESULT エラーならその段と値を報告 → 修正。
- **★★ 2026-06-09 Windows 実機 PASS** (user の JDK 25.0.3 MS build): 全 WHP call HRESULT=0、guest が long
  mode 実行、`exit_reason=0x8 (X64Halt)`、`rax=rbx=0x1122334455667788` rip=0xe →「WHP64 smoke OK」。
  **WHV ABI の写経 (register name/segment attr 0xA09B/exit reason/struct size 等) が全正解**だったことの実証。
  `WHvCreatePartition` 成功 = **WSL2 共存 OK** (Hyper-V WHP 使用可)。WHvRunVirtualProcessor 初回 572µs。

**step 3e-whp-2 (WhpSyscallSmoke64、Windows 実機テスト待ち)**: KvmSyscallSmoke (step 3c、#242、★go/no-go)
と同型を WHP で。native backend 中核機構 = ring-3 `syscall`→LSTAR `hlt` stub→`X64Halt` で VMM trap→`sysretq`
復帰 の round-trip を WHP partition で実証し、**1 syscall trap latency を実測**する。
- 機構: 4KB page table 全 US (ring3 実行可)、EFER.SCE + STAR/LSTAR/FMASK を WHV name-value で set、CS/SS
  dpl=3 (ring3 attr 0xA0FB/0xC0F3)。user code (ring3) で `mov eax,0xCAFE; mov edi,0xBEEF; syscall; jmp back`、
  LSTAR stub (ring0) で `hlt; sysretq`。X64Halt + rax==0xCAFE + rdi==0xBEEF + rcx==syscall 直後 addr を検証。
- ★★ **WHP = Hyper-V L1 (root) hypervisor = non-nested** なので、WSL2 nested KVM (L2) と違い **bare-metal
  相当の trap latency** が得られる = **step 3f (要実機) の測定をこの smoke が兼ねる**。WSL2 KVM の
  nested-inflated ~6µs に対し WHP は ~1-3µs 見込み。latency loop は各 iter で Rip=sysretq を set→Run→X64Halt
  (WHP の X64Halt 後 RIP 挙動が KVM と違いうるので明示 set して robust に。native backend も per-syscall で
  Get/SetRegisters するので realistic)。
- **★ Windows 実機テスト手順**: `java --enable-native-access=ALL-UNNAMED -cp target/classes
  emulin.WhpSyscallSmoke64`。
- **★★ 2026-06-09 Windows 実機 PASS**: ring-3 syscall が WHP で trap (rax=0xcafe/rdi=0xbeef/rcx=0x600c を
  LSTAR hlt stub→X64Halt で回収)、WHP は X64Halt 後 rip=0x7001 (hlt の次=KVM 同様)。**trap latency:
  min=5400 median=5800 mean=6207 p99=19500 ns** (100k round-trip、SetRip+Run+X64Halt 込み)。
  = **step 3f (bare-metal trap latency 絶対値) 確定**。
- **★ go/no-go の確定 (重要発見)**: WHP (非 nested L1 Hyper-V) の median 5.8µs は **WSL2 nested KVM (~5.8µs)
  とほぼ同じ**。「bare-metal なら 1-3µs」の楽観見込みは外れた = WHvRunVirtualProcessor の API round-trip
  overhead (Windows kernel + Hyper-V 遷移) が支配的で、nesting の有無より効く。break-even ≈ 命令/syscall >
  ~100-130。**ただし go/no-go 結論は不変 = GO**: 実 workload は compute-dominated (sort 4946 / grep 332k /
  sha256sum 3.4M 命令/syscall ≫ 130、#261) なので native が圧勝。負けるのは pure syscall-storm のみ。

### 4.7 Phase 0 step 3e-whp-3: ★ NativeCpuBackend を Hypervisor 抽象化 (KVM⇄WHP) — Stage 1 = vCPU register/run/FPU normalize layer

WhpSmoke64 + WhpSyscallSmoke64 が Windows 実機で通り (long mode + ring-3 syscall trap + latency)、WHP 足場が
実証できたので、本丸 = `NativeCpuBackend` の Hypervisor 抽象化に着手。~900 行 refactor を **段階 PR** に分割し、
各段で **KVM oracle (71 件) を green に保つ** ことを invariant にする。WHP path は Windows でしか検証できないので、
Linux で検証可能な抽象化抽出を先行し、WHP 実装は後段で書いて user の Windows 検証に回す。

**Stage 1 (本 PR) = per-vCPU の register/sregs/MSR/FPU/run を `HvVcpu` interface に抽出** (#221 WHP 移植の核心 =
「register access の KVM 構造体 ⇄ WHP name-value array の normalize layer」):
- 新規 `HvReg` (論理 GPR 索引 RAX..RFLAGS = 0..17、kvm_regs と同順なので KVM は offset=idx*8 で素朴変換、
  WHP は idx→WHV_REGISTER_NAME[] で変換)。
- 新規 `HvVcpu` (interface): `readGprs/getGpr/setGpr/writeGprs` (cache モデル = KVM_GET_REGS→reg→KVM_SET_REGS、
  WHP は WHvGet/SetVirtualProcessorRegisters 1 回ずつに対応)、`setCpuidFromHost` / `configureLongModeRing3(csSel,
  dataSel,dpl,cr0,cr3,cr4,efer)` / `setMsrs` / `run`→EXIT_HALT|EXIT_OTHER / `getFpu`/`setFpu` / `close`。
  ★ selector/CR/EFER の Linux ABI 値は NativeCpuBackend が単一の真実として渡し、KVM struct ⇄ WHP name-value の
  encoding 差だけを実装に閉じる。
- 新規 `KvmVcpu` (KVM 実装): KVM_CREATE_VCPU + vcpu-state mmap + 制御 struct (regs/fpu/sregs/cpuid/msr) を所有。
  旧 NativeCpuBackend.setupVcpu/kvmRun/reg/setReg/setSeg/setMsrs/FPU helper を集約。コンストラクタは
  exception-safe (CREATE_VCPU 成功後の mmap 失敗で fd を self-clean、review #LOW)。
- `NativeCpuBackend`: KVM struct offset への直接アクセスを全廃し `hv` 経由に。VM-level (kvmFd/vmFd/guest-RAM
  mmap) は本段では据置 (Stage 2 で `HvVm` に抽出予定)。`SIGFRAME_OFFS` (KVM offset 配列) を廃し HvReg 索引に。
- 検証: **native-oracle 71 件が byte-identical native==software のまま** (fork×3/signal/FPU-in-signal/multi-vCPU
  pthread/実 GNU 多数/cov3/cov4/python/busybox)、run-fast 230。**抽象化は意味的に透過**。adversarial review (4 次元
  → skeptic 検証、0 refuted) で ctor の exception-safety 1 件 (LOW) のみ検出 → 修正済。

**Stage 2 (✅) = VM lifecycle + guest-RAM alloc/map を `HvVm` interface に抽出**:
- 新規 `HvVm` (interface): `mapGuestRam(gpa,hostAddr,size)` / `createVcpu(id,arena,ownArena)→HvVcpu` / `close()` +
  static `create()` (KVM: open(/dev/kvm)+CREATE_VM) / `allocGuestRam(size)` / `freeGuestRam(seg,size)`。
  ★ guest RAM の host backing 確保 (allocGuestRam) は VM 作成より前 (connect_devices で NativeMemoryBackend
  構築 + ELF copy してから eval で VM を lazy 作成) に行うため static にし、platform (KVM=mmap MAP_ANON /
  WHP=VirtualAlloc) で確保する。
- 新規 `KvmVm` (KVM 実装): kvmFd/vmFd を所有。旧 setupKvm の open(/dev/kvm)+API ver+CREATE_VM+
  SET_USER_MEMORY_REGION を集約。ctor は exception-safe (finally close)、close() は idempotent。
- `NativeCpuBackend`: 旧 `kvmFd`/`vmFd` フィールドを `HvVm vm` に置換 (main 所有 / worker は owner.vm 共有 /
  fork 子は新 vm)。poolSeg 確保を `HvVm.allocGuestRam`、解放を `HvVm.freeGuestRam` に。`ioctl()` helper と
  `ValueLayout` import を削除。**★ NativeCpuBackend は hypervisor-API 呼び出しがゼロに** (残る `KvmBindings`
  参照は CR0/CR4/EFER/MSR の **arch 定数のみ** = hypervisor 非依存、両 impl が同値を使う)。
- 検証: native-oracle 70 ok/0 FAIL byte-identical native==software のまま、adversarial review (3 次元 → skeptic
  検証) で **0 findings**。

**Stage 3 (✅ 実装、Windows 検証待ち) = `WhpVm`/`WhpVcpu` (HvVm/HvVcpu の WHP 実装) を追加**:
- 新規 `WhpVcpu` (HvVcpu の WHP 実装): WhpSyscallSmoke64 (Windows 実機 PASS 済) の WHP 呼び出し列を HvVcpu の
  形に再構成。★核心 = HvReg 索引 → `WHV_REGISTER_NAME` の写像表 `WHV_GPR_NAME[18]`。WHV の GPR 並び
  (Rax,Rcx,Rdx,Rbx,Rsp,Rbp,Rsi,Rdi,…) は kvm_regs/HvReg の並び (Rax,Rbx,Rcx,Rdx,Rsi,Rdi,Rsp,Rbp,…) と
  **違う**ので、KvmVcpu の offset=idx*8 と違い WHP はこの写像表が要る = 抽象化が存在する理由そのもの。
  read/writeGprs = WHvGet/SetVirtualProcessorRegisters (cache モデル)、configureLongModeRing3 = Cr0/3/4/Efer +
  CS/SS/DS/ES/FS/GS を WHV name-value で set (segment attr = WHV_SEG_ATTR_CODE64/DATA | (dpl<<5))、setMsrs =
  STAR/LSTAR/SFMASK/EFER→WHV name、★FS_BASE/GS_BASE は WHP では segment register の Base field なので Fs/Gs を
  selector/attr 保持で書き直す。run = WHvRunVirtualProcessor→X64Halt 判定。FPU = XMM0-15 を Get/Set (signal
  hot path 用に name/value buffer を ctor で 1 度確保し再利用、review #LOW)。CPUID は -nostdlib では不要なので
  no-op (glibc 対応は Windows bring-up での follow-up)。
- 新規 `WhpVm` (HvVm の WHP 実装): CreatePartition + SetPartitionProperty(ProcessorCount=64、WHP は SetupPartition
  前に宣言が要る) + SetupPartition。mapGuestRam = WHvMapGpaRange(R|W|X)、createVcpu = WHvCreateVirtualProcessor、
  close = WHvDeletePartition。ctor は exception-safe。
- `HvVm.create()/allocGuestRam()/freeGuestRam()` を **platform dispatch** に: KvmBindings.probe() を先に見て
  Linux は KVM、Windows (KVM 不可) は WHP。allocGuestRam の WHP 経路は VirtualAlloc、freeGuestRam は VirtualFree。
- **★ NativeCpuBackend / KvmVm / KvmVcpu は無変更** (抽象化が効いて WHP 追加が本体修正ゼロで済んだ)。
- 新規 `WhpVcpuSmoke64`: WhpVm/WhpVcpu を NativeCpuBackend と同じ抽象 API 経由で叩く Windows 検証 smoke
  (HvVm.allocGuestRam→WhpVm→WhpVcpu.configureLongModeRing3/setMsrs/run、ring-3 syscall trap guest)。
- 検証: **native-oracle 70 ok/0 FAIL** (Linux KVM 経路は WhpVm/WhpVcpu に入らず無影響)、WhpVcpuSmoke64 は Linux で
  probe=false skip、compile OK。WHP は Linux 不実行なので **static adversarial review** (4 次元 → WhpSyscallSmoke64
  + WHV ABI 定数に照合) で検証 = 1 件 LOW (getFpu/setFpu の per-signal allocation) を修正、1 件 refuted。
- **★ Windows 実機検証手順** (user 側): `java --enable-native-access=ALL-UNNAMED -cp target/classes
  emulin.WhpVcpuSmoke64` →「WhpVcpu smoke OK」。さらに native backend 全体は
  `EMULIN_BACKEND=native java … emulin.Emulin <sandbox> /bin/hello64` で hello64 を WHP 経由実行。

**残段**: Stage 4 = WHP で native-oracle (Windows、KVM の software==native と同様に WHP==software を検証)。
glibc binary は CPUID 設定 (WHvSetPartitionProperty CpuidResultList) の follow-up が要りうる。

---

## 5. 関連

- 親: **#221** (HW 仮想化検討の本体、whether/why)
- 設計計画: `docs/issue221-design-plan.md` (how/when/who)
- Step 1: **#231** (factory + env var、merged in PR #235、main=0c4b54c)
- Step 2: **#232** (MemoryBackend、merged in PR #236、main=7205302)
- Step 3: **#233** (signal+thread = spawnVCpu、本 PR)
