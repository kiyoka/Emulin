# Backend Abstraction Layer

> **status**: #231/#232/#233 + #238 + #239 + 3a(#240) + 3b(#241) + 3c(★syscall trap=機構 GO、#242) + 3d-1(NativeMemoryBackend、#243) + 3d-2a(Syscall.mem widen seam、#244) + 3d-2(★hello64 native KVM 実行 + software byte 一致 oracle=Phase 0 山場、#245) + 3d-2c-1(hello64 ring 3、sysretq 往復、#246) + 3d-2c-2(初期 stack、#247) + 3d-2c-3(SSE+GPR、#248) + 3d-2c-4(初の実 glibc 静的 binary、#249) + 3d-2c-5(oracle 拡張、#250) + 3d-2c-6(性能実測 215.7x→compute-bound GO、#251) + 3d-2c-7(anonymous mmap、#252) + 3d-2c-8(★★★非 identity MMU リワーク、#253) merged。**step 3d-2c-9 (file-backed mmap + CPUID passthrough、本 PR)**。ld.so が file-backed mmap で libc.so.6 を完全ロード + glibc の CPU ISA level check を CPUID passthrough で通過 → libc startup まで到達。全 12 oracle PASS / run-fast 223。★hello_dyn64 は libc startup の futex(WAIT,val=2) で発散 hang (software は futex を呼ばない)。次は **futex 発散の調査 → 動的リンク完走 (busybox) → 3f (bare-metal latency) → WHP 移植**。go/no-go = conditional GO (機構実証済、compute-bound 勝ち筋、§4.4c)。
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
