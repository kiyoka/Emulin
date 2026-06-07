# Backend Abstraction Layer

> **status**: Step 1 (factory + env var) merged via issue #231。Step 2 (MemoryBackend、#232) / Step 3 (signal+thread、#233) は未着手。
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

## 2. Step 2: `MemoryBackend` 抽象化 (issue #232、TBD)

### 2.1 目的

guest 物理メモリの表現を `MemoryBackend` interface に切り出し、現 `Memory` (per-byte cache + chunks) を `SoftwareMemoryBackend` として内包する。将来 native backend (WHP/KVM の mapped page) は同じ interface に乗せる。

### 2.2 候補 interface (Step 2 着手時に詰める)

```
interface MemoryBackend {
  int  read8 (long addr);
  int  read16(long addr);
  int  read32(long addr);
  long read64(long addr);
  void write8 (long addr, int  v);
  void write16(long addr, int  v);
  void write32(long addr, int  v);
  void write64(long addr, long v);
  void bulkLoad (long addr, byte[] dst, int off, int len);
  void bulkStore(long addr, byte[] src, int off, int len);
  long mmap   (long addr, long len, int prot, int flags, int fd, long off);
  int  munmap (long addr, long len);
  int  mprotect(long addr, long len, int prot);
  // ... brk, seglist, MAP_FIXED handling
}
```

### 2.3 性能注意

- `read8` / `write8` は claude 起動の ~30% を占める (#190、[[claude-perf-exploration]])。
- virtual dispatch が JIT で monomorphic 解決されないと致命傷。
- 対策: `SoftwareMemoryBackend` は `final` class、interface method は `abstract` (default 禁止)、`Memory` は facade として残し inline 委譲。
- gate: claude --version 起動時間 / `sha256sum 5MB` を interleaved A/B で ±5% 以内。

### 2.4 software-only state (backend 内へ閉じ込め)

- per-byte cache (load8 fast path)、chunks、alloclist、globalStoreEpoch、lastSegment cache、multiThreadActive。
- これらは software 専用。interface には出さない。

---

## 3. Step 3: signal/thread を `CpuBackend` 経由に (issue #233、TBD)

### 3.1 目的

signal 配信と thread/clone spawn の 2 経路を `CpuBackend` interface に集約。software backend 内に現状ロジックを閉じ込めれば、native backend で「vCPU regs を hypervisor API 経由で書き換える」実装に置き換え可能になる。

### 3.2 候補 interface

```
interface CpuBackend (extends 現 AbstractCpu か別 facade か検討) {
  void deliverSignal(Siginfo si, long handler_addr, int sa_flags, long mask);
  AbstractCpu spawnVCpu(AbstractCpu parent_ctx, long child_stack, int clone_flags);
}
```

### 3.3 software-only state (backend 内へ閉じ込め)

- red zone (rsp-128)、SA_SIGINFO 3 引数 ABI、SA_RESTART 再チェック、per-thread mask、pending signal 判定 (psig_actionable、#225)。
- `amd64_clone_thread` の親 cpu 取り違え修正 (#113) は backend 内の実装詳細に。
- Thread64 起動 / FutexManager 接続 / Memory share。

### 3.4 ship gate

- SIGCHLD / SIGIO (#223 sshd 越し emacs) / SIGWINCH (#225) hermetic 回帰 全 PASS。
- pthread / clone CLOEXEC (#113 fix path) 無傷。

---

## 4. native backend の差し込み点 (#221 Phase 0+、未着手)

Step 1〜3 完了時点で、native backend 追加は以下を新規実装するだけになる想定:

- `NativeCpuBackend implements (Step 3 で確定する CpuBackend interface)`
- `NativeMemoryBackend implements MemoryBackend` (WHP `WHvMapGpaRange` 等で byte[] backing を guest 物理に map)
- `CpuBackend.AUTO.effective()` の probe (HW 仮想化可用性チェック → 可用なら NATIVE、不可なら SOFTWARE)
- `CpuBackend.NATIVE.createCpu64` の throws を実装に置換

syscall trap 方式 (LSTAR スタブ / 未マップ / MSR intercept) は **native backend 内部の実装詳細**。interface には出さない。

---

## 5. 関連

- 親: **#221** (HW 仮想化検討の本体、whether/why)
- 設計計画: `docs/issue221-design-plan.md` (how/when/who)
- Step 1: **#231** (本 PR)
- Step 2: **#232** (MemoryBackend、TBD)
- Step 3: **#233** (signal+thread、TBD)
