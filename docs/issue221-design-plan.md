# issue #221 設計計画 — HW 仮想化 backend (Hyper-V/KVM/HVF) と software emulator の並立

> **status**: design plan (実装は #231 → #232 → #233 → #221 Phase 0 〜)
> **last update**: 2026-06-07
> **scope**: 「user code を実 vCPU でネイティブ実行 + syscall は emulin の Java 層へトラップ」する native backend を、現 software emulator と**並立する第一級モード**として導入するための実装計画

#221 本体 (issue body) は **whether / why** の検討、本 doc は **how / when / who** の実装計画。両者を分離して保守する。

---

## 0. 1 段落サマリ

emulin 最大のボトルネック (per-byte memory emulation、`load8` ~30%、#190) を抜本解決するため、x86-64 user code を **Hyper-V (WHP) / KVM / Hypervisor.framework** の vCPU でネイティブ実行し、`syscall` 命令だけを VM-exit させて **既存 `SyscallAmd64.call_amd64` に流す** 構造へ移行する。**software emulator は第一級の supported mode として恒久維持** (移植性 / デバッグ容易性 / 決定的回帰 / equivalence oracle)。実装は **3 段の refactor (#231/#232/#233) → 4 段の PoC (Phase 0〜3) → 実 binary 計測 (Phase 4)** で進める。各段で **software ⇄ native の等価性 oracle** を維持する。

---

## 1. 設計原則 (#221 §1 の確認)

1. **software emulator は恒久維持**: 削除しない。仮想化不可環境 (古い CPU / VBS制約 / CI / 権限不足 / nested不可) でも完全動作する唯一の保証。
2. **正しさの canonical は software**: 回帰テストは software backend で常時 PASS。native backend は software との出力一致で正しさを確認する (equivalence oracle)。
3. **default は software**: `EMULIN_BACKEND=auto` は仮想化可用時のみ native、不可なら software に自動 fallback。明示的に `=native` を選んだときのみ強制。
4. **pure-Java の保持**: software backend は FFM/JNI 不要のまま。native backend の native 依存を software path に**波及させない**。
5. **diff 0 で第一級**: refactor 3 段 (#231/#232/#233) は behavior change ゼロ。これが satisfied されない限り native PoC を始めない。

---

## 2. アーキテクチャ全体図

```
                ┌─────────────────────────────────────────────┐
                │           emulin Java application           │
                └─────────────┬───────────────────────────────┘
                              │
                              ▼
            ┌─────────────────────────────────────────────────┐
            │  CpuBackendFactory  (EMULIN_BACKEND で選択)     │
            └──────┬──────────────────────────┬───────────────┘
                   │                          │
        ┌──────────▼──────────┐    ┌──────────▼──────────────┐
        │  SoftwareCpuBackend │    │   NativeCpuBackend      │
        │  (現 Cpu64 を内包)   │    │   (WHP / KVM / HVF)     │
        │                     │    │                         │
        │  decode_and_exec    │    │  vCPU run → VM-exit     │
        │  per-byte cache     │    │  syscall trap (§5)      │
        │  Thread64           │    │  追加 vCPU = thread     │
        │  signal: regs 書換  │    │  signal: vCPU regs API  │
        └──────────┬──────────┘    └──────────┬──────────────┘
                   │                          │
                   ▼                          ▼
            ┌─────────────────────────────────────────────────┐
            │   SyscallAmd64.call_amd64(sysno, a1..a6)       │
            │   (両 backend 共通、既存コードを温存)            │
            └─────────────────────────────────────────────────┘
                              │
                              ▼
            ┌─────────────────────────────────────────────────┐
            │  既存 sandbox / Inode / FileAccess /             │
            │  PipeManager / PtyManager / FutexManager …       │
            └─────────────────────────────────────────────────┘
```

**捨てる (native path 時のみ非使用)**: `Cpu64.decode_and_exec`、`EMULIN_USE_JIT`、Memory の per-byte fast path、手実装 SSE/AES-NI/PCLMULQDQ/FPU、JIT correctness 計装。

**残す (両 backend で共有)**: `call_amd64` 以下の syscall 層、sandbox / 仮想 FS、`Thread64` の queue / FutexManager、ELF loader / auxv / PIE 配置。

---

## 3. 実装フェーズ (mile stones)

### Phase R: Refactor (前提、behavior change 0)

| # | issue | 内容 | 完了 gate |
|---|-------|------|----------|
| R1 | **#231** | backend factory + `EMULIN_BACKEND` env var | run-fast 220 / run-network 15 全 PASS、性能 ±2% |
| R2 | **#232** | `MemoryBackend` interface + `SoftwareMemoryBackend` | run-fast / run-network 全 PASS、claude起動 ±5%、sha256sum 5MB ±5% |
| R3 | **#233** | `CpuBackend.deliverSignal` / `spawnVCpu` | SIGCHLD/SIGIO/SIGWINCH/pthread/clone CLOEXEC 全無傷、emacs-pty-smoke PASS |

R1〜R3 完了で「native backend を後から差し込むだけの構造」が立つ。Phase 0 はそれが ship gate。

### Phase 0: feasibility / WSL2 共存 (#221 本体)

**目的**: 仮想化 backend が emulin で機能することの最小実証 + WSL2 環境で破綻しないかの確認。

タスク (1〜2 週間想定):

1. **0a: WHP partition + vCPU の最小起動** (Windows)
   - FFM (Java 22+) で `WHvCreatePartition` / `WHvSetupPartition` / `WHvCreateVirtualProcessor` / `WHvRunVirtualProcessor` を呼ぶ
   - `WHvMapGpaRange` で byte[] backing を guest 物理に map
   - long mode ring3 への初期 setup (CR0/CR3/EFER/STAR/LSTAR/CS/SS、最小 page table)
   - **NOP × N → HLT** を実行できることを確認
2. **0b: WSL2 共存テスト** (Windows、ユーザ環境)
   - WSL2 distro 起動中の host で `WHvCreatePartition` が成功するか
   - 同時実行で WSL2 / WHP partition の双方が安定動作するか
   - VBS / HVCI / Credential Guard 有効時の挙動
3. **0c: syscall trap の最小 PoC** (§5)
   - 候補 (a)/(b)/(c) のうち実装最小 (= (b) LSTAR に `vmcall`/`hlt` 配置) で 1 回 trap → VMM 復帰 → resume
   - VM-exit context から sysno 回収可能を確認
4. **0d: 等価性 oracle 雛形**
   - `tests/binaries/hello64` を native で 1 syscall (`write(1, "hi", 2)`) → software と stdout 一致
   - CI で hardware backend は **opt-in** (env: `EMULIN_NATIVE_OK=1`)

**Phase 0 ship gate**:
- ☑ WHP で 1 syscall trap が動作 (`hello64` PASS)
- ☑ WSL2 と共存可能 (実機確認)
- ☑ syscall trap オーバーヘッドの実測値 (≤ 2µs/syscall が目標、5µs 超えると syscall-heavy workload で勝てない可能性大)

**Phase 0 で go/no-go 判定**:
- syscall trap が ≥ 10µs だと PoC 続行は怪しい (emacs poll/pselect で詰む)。代替策 (Linux KVM + nested、外部 VMM IPC) を再評価
- WSL2 共存不可だと Windows native + WSL2 主ユース層に響く

### Phase 1: hello64 / sys_*64 全 PASS

- `-nostdlib` 静的 ELF (`tests/binaries/bin/*64`) を native backend で実行 → software と stdout 一致
- syscall は emulin の `call_amd64` に流す (refactor 完了済 → そのまま再利用)
- ターゲット: hello / sys_open_64 / sys_pipe_64 / sys_fork_64 / sys_clone_64 / sys_pty_*64 等 200+ 件
- 並行性が無いテストから始め、徐々に pthread / clone / signal へ拡大

**ship gate**: refactor 後 software backend と同じ 220 件 (うち native 適用可な non-WSL/non-host依存のもの) で **byte 一致**

### Phase 2: glibc 動的リンク

- ld.so / auxv / PIE / mmap / brk / TLS の native での動作
- 静的 glibc PASS → 動的 glibc (bash, coreutils 系) PASS
- MAP_FIXED / mremap / mprotect の guest 物理 ↔ host backing 整合

**ship gate**: `bash -c 'echo hi'` / `ls /tmp` / `cat /etc/passwd` が native で動作 (software と diff 0)

### Phase 3: pthread / signal / futex (concurrency)

- pthread = 同一 partition の追加 vCPU、メモリ共有
- signal = vCPU regs を hypervisor API で書換 (deliverSignal の native 実装)
- futex = VM-exit → 既存 `FutexManager` (refactor 済の Step 3 経路をそのまま使う)

**ship gate**:
- ☑ pthread hello (2 threads + mutex) PASS
- ☑ SIGCHLD/SIGIO/SIGWINCH 全動作 (sshd 越し emacs # 223/#225 の path)
- ☑ #113 系 (worker pthread crash) が native では発生しない (= 実 MMU/atomic で自然解消の検証)

### Phase 4: 実 binary + 性能計測

- bash / vim / emacs-nox / claude / curl HTTPS の動作確認
- **software vs native の性能比較**:
  - claude --version 起動時間 (現在 ~17 秒 → 期待 1〜2 秒)
  - HTTPS clone (現在 ~14 秒 → 期待 2〜3 秒)
  - emacs 起動 (issue #207、現在 ~5 分 → 期待 5〜10 秒)
- **syscall-heavy ケース** (#206 emacs pselect 連打、#207 getdents 連打、#190 ASN.1) で実効測定 — VM-exit コストが効くケース

**Phase 4 ship gate**:
- ☑ claude / emacs / curl / bash が native で操作可能 (software との diff は許容、ただし意味的同一)
- ☑ 性能 ≥ 5x で「投資の元が取れた」と判定

---

## 4. Technology stack の判断 (#221 §6)

| 候補 | pros | cons | 採否 |
|------|------|------|------|
| **FFM (Java 22+)** | pure-Java 精神に最も近い、JNI glue 不要 | Java bump (17→22+) 必要、配布 JRE jlink 影響 | **本命** (Step R1 完了後に bump 議論) |
| JNI / C shim | Java 17 のまま | pure-Java 方針に反する、Phase 22 撤去の逆行 | 不採用 |
| 別プロセス VMM + IPC | 疎結合、Rust製 VMM 等流用可 | syscall 毎 IPC コスト (≥ 数十µs) で 5µs/syscall 目標を超える可能性大 | back-pocket (FFM 不可時) |

**判断ルール**:
- Phase 0 (#231/#232/#233 完了後) で FFM の素振り (WHP 関数 1 つ呼べるか) を試す
- 通れば FFM で Phase 1+ 続行、Java bump PR を別建てで起票
- 通らなければ別プロセス VMM (Rust + UNIX socket IPC) の PoC

### Java bump 判断 ([[release-process]])

- 現状: `maven.compiler.release=17`、`-CJ` launcher、jlink で配布 JRE 構成 (linux/win/mac の 22-69MB)
- FFM 採用なら Java 22+ (LTS は 21 で finalized は 22)、できれば **Java 23 LTS 待ち** (2025-09 予定だが本 design 時点では確認要)
- jlink 構成 + JLine 3.27 (terminal-jni) との互換 検証必須

---

## 5. syscall trap 方式の比較 (#221 §3)

| 方式 | 仕組み | VM-exit コスト見込み | 実装難度 |
|------|--------|---------------------|----------|
| **(a) LSTAR 未マップ** | LSTAR を未 map の guest 物理に向け、fetch で EPT violation → VM-exit | 中 (fault 経路) | 中 (page table 操作) |
| **(b) LSTAR スタブ** | LSTAR に `vmcall` / `hlt` / 無効命令を 1 つ置き、確実に VM-exit させる | 低 (= VM-exit 直行) | 低 (page 1 つ用意) |
| **(c) MSR intercept** | hypervisor が syscall を直接 intercept | hypervisor 依存、低い可能性 | 高 (API 確認要) |

**Phase 0 で (b) → (c) → (a) の順で測定** (最も実装が軽い (b) で feasibility、(c) で本番性能、(a) は fallback)。

各方式の比較項目:
- VM-exit → guest resume の round-trip 時間 (`rdtsc` 計測、目標 ≤ 2µs)
- レジスタ退避 / 復元のコスト (`WHvGetVirtualProcessorRegisters` のオーバーヘッド)
- guest 側スタブが踏むメモリ (TLB / cache の影響)

---

## 6. Memory model (guest 物理 ↔ emulin Memory)

- 現 `Memory`: byte[] chunks (per-page LRU cache + globalStoreEpoch)
- native backend では:
  - chunks を WHP `WHvMapGpaRange` / KVM `KVM_SET_USER_MEMORY_REGION` で guest 物理に map
  - mmap / mprotect / mremap / MAP_FIXED は emulin が page table を管理 (最小 identity map から始める)
  - guard gap (16 page、#190) は guest 物理にも反映
- **ページテーブル**: emulin が用意 (guest 自身の page table を尊重 → user code は仮想 alias でアクセス、kernel 経路の syscall は emulin が解釈)
- **mmap/PIE**: 既存の PIE base 0x555555554000 / brk / stack 配置をそのまま guest 仮想に置く

**未解決**: native では byte[] のアドレスが host 仮想で確定し、それを guest 物理に map する → host 側 GC で動かれない pinned 領域が要る (FFM では Arena, ByteBuffer.allocateDirect 経由)。

---

## 7. 等価性 oracle (equivalence testing)

回帰戦略 (#221 §1 原則 2):

1. **software backend は CI で常時 PASS** (run-fast 220 / run-network 15)
2. **native backend は opt-in CI ジョブ** (`EMULIN_BACKEND=native` で同じ test を走らせる)
3. **両者の stdout / exit code が byte 一致** することを `diff -u` で確認
4. **不一致は即 fail**、software 側を canonical として native を直す
5. **timing 依存 test** (例: pselect timeout 系) は software/native で結果が異なりうる → 個別に分類

PoC 期間中の oracle:
- `tests/scripts/run-native-oracle.sh` を新設 (Phase 1 から)
- 各 test を software / native 両方で走らせ stdout / exit / fd dump を diff
- non-zero diff があれば fail、log を保存

---

## 8. 観測手段の刷新 (#221 §8 リスクへの対策)

software では `EMULIN_TRACE_RIP` / `EMULIN_TRACE_OPEN` / `EMULIN_PROFILE_SYS` 等で hot path を可視化していたが native では使えない。代替手段を Phase 0 から設計:

| 観測対象 | software | native | 代替案 |
|---------|----------|--------|--------|
| 命令トレース | TRACE_RIP | × | hypervisor の single-step (WHP `WHvSetVirtualProcessorRegisters`) を opt-in |
| syscall trace | TRACE_OPEN/EXEC | call_amd64 で捕捉 | 共通 (call_amd64 経路はそのまま) |
| 性能プロファイル | PROFILE_SYS / JFR | call_amd64 + VM-exit 計数 | exit reason histogram (新規) |
| 命令フリーズデバッグ | jstack | host gdb で hypervisor 自身 | host gdb + qemu-system-x86_64 比較 |

---

## 9. リスクと意思決定点 (decision tree)

```
Phase R (refactor) PASS?
  ├── No  → 修正、Phase 0 開始 NG
  └── Yes ↓

Phase 0a (WHP 起動) PASS?
  ├── No  → KVM/HVF backend に方向転換、または別プロセス VMM 検討
  └── Yes ↓

Phase 0b (WSL2 共存) PASS?
  ├── No  → Windows native のみ / WSL2 ユーザ向けは別 backend
  └── Yes ↓

Phase 0c (syscall trap < 5µs) PASS?
  ├── No  → 方式 (c) MSR intercept を粘る、ダメなら #221 再評価
  └── Yes ↓

Phase 0d (oracle hello64) PASS?
  ├── No  → bug fix、再試行
  └── Yes ↓

Phase 1〜4 順次、各 ship gate で go/no-go
```

**no-go パターンの撤退戦略**:
- Phase 0 で feasibility なし → #221 を「**software emulator を限界まで最適化**」(#190、#206/#207 系) に方針転換
- Phase 4 で 5x 未達 → native backend は「実験的 opt-in feature」として残す、default は software

---

## 10. スケジュール感 (rough)

| Phase | 想定期間 | dependency |
|-------|---------|------------|
| R1 (#231) | 1〜2 日 | - |
| R2 (#232) | 1 週間 (性能 gate あり) | R1 |
| R3 (#233) | 1 週間 (signal complexity) | R1, R2 |
| Phase 0 | 2 週間 (feasibility) | R1〜R3 完了 |
| Phase 1 | 2〜4 週間 | Phase 0 |
| Phase 2 | 4〜6 週間 | Phase 1 |
| Phase 3 | 4 週間 | Phase 2 |
| Phase 4 | 2〜4 週間 | Phase 3 |

合計 **3〜5 ヶ月想定** (single-person、業務時間外開発を想定)。Phase 0 の go/no-go 判定がもっとも重要。

---

## 11. 未解決の Open Questions (要追加調査)

1. **WHP の vCPU exit latency 実測値** (Phase 0c で測定、目標 ≤ 2µs)
2. **WSL2 共存** (#221 §5、Phase 0b で実機確認)
3. **Java FFM の Windows DLL 呼び出し overhead** (Phase 0a で測定)
4. **vDSO / `rdtsc` / `cpuid` の native での扱い** (VM-exit 頻度に直結、Phase 3 で詰める)
5. **self-modifying code** (emacs native-comp `.eln`) の native での挙動 (理論上 EPT 制御で問題ないはずだが Phase 2 で要検証)
6. **ARM Apple Silicon の x86 → ARM JIT 必要性** (Hypervisor.framework は ARM CPU を guest にする — x86 binary を ARM 上で動かすのは別問題、当面 macOS は software backend 維持)

---

## 12. 関連 issue / memory

- 親: **#221** (HW 仮想化検討の本体)
- refactor 3 段: **#231** / **#232** / **#233**
- 性能背景: **#190** (per-byte memory 限界)、[[claude-perf-exploration]]
- concurrency 背景: **#113** (worker pthread crash、native MMU で自然解消候補)
- signal 背景: **#219** 系 (#222/#224/#227 で signal path 複雑化)、[[issue219-sshd-server]]
- Phase 22 (JNI 撤去): pure Java 方針の根拠、FFM 採否の前例
