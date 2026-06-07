# Backend Abstraction Layer

> **status**: #231/#232/#233 + #238 + #239 + step 3a (#240) + 3b (#241) + 3c (★ring-3 syscall trap 実証=機構 GO、conditional GO、#242) merged。**step 3d-1 (NativeMemoryBackend: off-heap guest RAM の MemoryBackend、双方向 bridge 実証、本 PR)**。次は **step 3d-2 (NativeCpuBackend KVM eval loop を emulin の SyscallAmd64 に統合 + ELF loader、★Syscall.mem の型 widening 必須) → 3e (hello64 oracle) → 3f (非 nested latency 再測定)**。go/no-go = conditional GO (機構実証済、compute-bound 勝ち筋、§4.4c)。
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

### 4.5 Phase 0 step 3d-2+ (KVM、WSL2 内で次に作る)

- **3d-2 (NativeCpuBackend KVM 経路 + emulin 統合)**: stub の `init`/`eval`/`fetch`/
  `connect_devices`/`set_signal_handler` を KVM で実装。`eval()` は step 3c の
  KVM_RUN → exit_reason 分岐 loop: `KVM_EXIT_HLT` (= LSTAR スタブ) なら sysno=RAX /
  引数=RDI/RSI/RDX/R10/R8/R9 を読み `SyscallAmd64.call_amd64` に dispatch、戻り値を
  RAX に書いて resume (stub の sysretq で ring3 復帰)。`NativeMemoryBackend` (3d-1) を
  `mem` として SyscallAmd64 に繋ぐ + ELF loader で guest RAM に PT_LOAD を配置。
  3d-1 review で確定した中心 work item (compile-breaking):
  - **★型 widening が central blocker**: `Syscall.mem` (Syscall.java:281) /
    `AbstractCpu.mem` (AbstractCpu.java:52) / `Process.mem` / `Thread64.mem` は全て
    `Memory` concrete 型、`connect_devices(Memory _mem,...)` も concrete。
    `NativeMemoryBackend` は `Memory` を extend しないので代入不可。さらに code は
    `mem.execLock`(#113 GIL ReentrantLock)/`e_ident`/`e_entry`/`segment[]`/
    `interp_base`/`duplicate` 等 **MemoryBackend に無い ~20 の Memory/Elf member** を
    触る → 単純 widening では compile しない。seam を決める: (a) `mem` を MemoryBackend
    に widen + ELF/Process state を別 object に分離 + GIL を interface method 化
    (native は no-op、coherency は KVM 持ち)、or (b) `mem` は concrete のまま
    NativeCpuBackend が Cpu64.eval を完全 bypass し SyscallAmd64 の MemoryBackend-subset
    呼び出しだけ native mem に向ける。#232 で見送った widening をここで必要範囲だけ。
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

KVM 経路が hello64 まで通った後、`WhpBindings` の MethodHandle を実装し
`NativeCpuBackend` に KVM/WHP 分岐を入れる。差は「ioctl ⇄ WHvXxx FFM downcall」
の置換。user の Windows 実機で:
- **WSL2 共存** (#221 §5): WSL2 distro 起動中に `WHvCreatePartition` が成功するか。
- WHP で hello64 oracle + trap latency。VBS/HVCI 干渉確認。

---

## 5. 関連

- 親: **#221** (HW 仮想化検討の本体、whether/why)
- 設計計画: `docs/issue221-design-plan.md` (how/when/who)
- Step 1: **#231** (factory + env var、merged in PR #235、main=0c4b54c)
- Step 2: **#232** (MemoryBackend、merged in PR #236、main=7205302)
- Step 3: **#233** (signal+thread = spawnVCpu、本 PR)
