コミットログには、以下を記載しないようにしてください。
Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>

objdump / nm / grep / readelf / addr2line / strace は許可なしで実行してよい。

---

# プロジェクト概要

古い Emulin (Java 製 32bit Linux ELF エミュレータ) を現代 Java で動かし、
さらに x86-64 (64bit ELF) を実行できるように拡張する。

## 現状サマリ (Phase 28-3 完了時点)

- 現代 Java (OpenJDK 21+) / Maven、**pure Java only** (Phase 22 で JNI 撤去)
- 32bit ELF (i386) と 64bit ELF (x86-64) 両方を実行可能
- glibc 静的リンク + 動的リンク + **PIE** (ET_DYN を 0x555555554000) 動作
- 動的リンク + 複雑な libc 機能: printf 各種 (NaN/Inf/bignum)、malloc/free、
  signal/raise (tgkill)、setjmp/longjmp、fopen/fgets、strftime、sscanf、qsort、
  getopt、POSIX regex、dlopen/dlsym、wchar、C++ STL、fork+pipe IPC、zlib、
  socketpair (双方向)、mmap of file、GCC ネスト関数
- **実機 GNU coreutils 30+ 種類 + bash 5.2 + git/curl/wget** 動作 (Phase 28-3g
  で full sandbox を coreutils 中心に再構成、demo zip 80→72 MB)
- **AES-NI / PCLMULQDQ 命令を完全実装** (FIPS-197 host 一致)
- **wget HTTP/HTTPS、curl HTTP/HTTPS、wget DNS が動作**。wget HTTPS で iana
  (RSA cert + TLS 1.3) 確実動作
- **git clone 多経路で動作** (Phase 28-3 で本格対応):
  - `git://...`: pack 受信 + checkout 完走 (step 40)
  - `--no-hardlinks file://...`: 完走 (step 39 CLOEXEC + 28-3 sh + Pipeinfo)
  - `--no-hardlinks -c protocol.version=0 file://...`: 安定 (28-3i)
  - **`https://github.com/...` (cert verify あり)**: **13.5 秒で完走**
    (28-3c の amd64_mremap 新設で segfault 解消 + Sectigo Root single CA)
  - **bash → fork+exec git clone**: file:// + https:// 両方動作 (28-3a-i)
- busybox 静的リンクで **88 applet** 動作。**対話 busybox ash** 動作 (Phase 22)
- **JLine 3 採用** で Linux/macOS/Windows 共通 raw mode / Ctrl-C / SIGWINCH
- **対話 bash 5.2 + line edit** (history / cursor / Tab) — 28-3a で `-CJ` を
  launcher default、JLine raw mode が tcsetattr ICANON off で発動する既存
  chain でコード変更ゼロ
- **配布 zip 7 種類** (dist 1.7 MB / jre 22 MB × 3 platform / demo 38-72 MB ×
  3)。Linux で cross-build (jlink) で Windows / macOS 用 JRE bundle 生成
- シグナル基盤: SIGCHLD、SA_RESTART、SA_SIGINFO、red zone 尊重、alarm/setitimer、
  rt_sigprocmask、sa_mask + SA_NODEFER、**per-thread signal mask + tgkill**
  (step 34-35)
- **pthread 動作**: pthread_create/join + 共有 memory + **mutex 競合下** で
  4 worker × 1000 iter counter++ → 4000。**curl AsynchDNS (HTTP) 動作**
- **CLOEXEC 真対応** (step 39): pipe2/SOCK_CLOEXEC/dup3/fcntl(F_SETFD) すべて
  per-fd 管理。execve 時 cloexec fd 自動 close
- **at-syscall (openat/mkdirat/fstatat) で dirfd 解決** (Phase 28-3i): find /
  cp -r が dirfd 経由 subdir 走査で動作
- **Pipeinfo thread-safe** (Phase 28-3e): synchronized + wait/notifyAll で
  pipe IPC race fix。並列負荷下の "bad line length" 等を完全解消
- **多サイト HTTPS 対応** (Phase 28-3k): example.com / cloudflare / google /
  iana / raw.githubusercontent / github の 6 サイトが 11-15 秒で動作。
  `emulin-roots.pem` (7 root concat) を sslCAInfo に設定
- **git clone --hardlinks (direct path)** (Phase 28-3l): `Inode.get_uniq_no`
  を Java NIO fileKey() に変更で host inode 反映、hardlink で同値
- **回帰テスト 223 PASS / 0 FAIL** (Phase 28-3 で 211 → 223、bash 6 +
  git-clone 6)。`run-fast.sh` 27 秒、`run-network.sh` 3 分、フル
  `run-all.sh` 約 1m41s (step 30-33c で baseline 3m51s から **56% 短縮**)
- **Phase 29-A 最適化** (JFR profile-driven): github HTTPS clone 14.4s →
  **10.2s (-29%)**。
  29-A1: `Segment.peekbs` を `System.arraycopy` で bulk copy 化 (top hot
  spot 解消、ossl 31% / clone 18% を 0% に)。
  29-A2: `Memory.load8_slow` に `lastSegment` fast path 追加で segment
  線形 scan を skip

## 既知の未解決課題

### A. emulator の根本的な高速化 (TLS 系の真因)
HTTPS 系の遅さ (curl 80s、cert verify 14s) は emulator の dispatch + ASN.1
parse がボトルネック。github は Sectigo single CA workaround で 14s 動作。
手筋: ASN.1 hot path の更なる最適化 / cert verify cache / 1 cert ファイル固定。

### B. 多サイトの HTTPS 動作確認 ✅ 解決済 (Phase 28-3k)
example.com / cloudflare / google / iana / raw.githubusercontent / github の
6 サイトを `emulin-roots.pem` (curated 7-root bundle) で 11-15 秒で動作。
build-sandbox.sh が自動で生成、curl の default bundle も上書き。

### C. file system / fork 系 ✅ 解決済 (Phase 28-3l)
`git clone --hardlinks file://` (= direct path 経路、hardlinks default) が
動作。`Inode.get_uniq_no` を Java NIO `BasicFileAttributes.fileKey()` に
変更し、host の実 inode (UnixFileKey = dev+ino) を反映。hardlink で同値、
異 file は path.hashCode() 同等の衝突確率を維持。

### D. ネットワーク
**IPv6 (AF_INET6) 未対応** — getaddrinfo は IPv4 のみ。glibc/libcurl の v6
fallback パスがあり影響範囲広い。実装規模大。

### E. 環境固有
WSL DrvFs (`/mnt/c/...`) は I/O 遅く chmod 効かない → sandbox は Linux /tmp
に置く (回避策あり)。

### F. 副次的に蓄積された debug 基盤 (再利用可)
step 48-57 で実装した address aligned / auxv aligned / multi-thread fix /
trace hooks 多数 は libtasn1 解決には直接効かなかったが将来の追跡に再利用可:
`Memory.alloc()` synchronized、`globalStoreEpoch`、Segment buf 256MB
pre-allocate、mmap top-down + memory_top = 0x7ffff7fbf000、auxv 17/19 host
一致、`EMULIN_TRACE_RIP_DUMP{1-4}` / `WATCH_STORE_VAL` / `DUMP_AT_RIP` /
最小再現 binary `tests/repro_pthread_calloc{,_loop}.c`。

## 動作テスト基盤

- `tests/` に i386/x86-64 静的リンク ELF (-nostdlib で int 0x80/syscall 直叩き)
- `make -C tests/binaries`: テストバイナリビルド
- `tests/scripts/run-all.sh`: 全 221 ケース、~1m41s。並列化済 (binary は
  xargs -P で nproc 並列、ext scripts は & + wait で 5 本同時)
- `run-fast.sh`: 軽量 subset (~27s、real-* / dist 抜き)
- `run-network.sh`: ネットワーク関連だけ (~3m)
- `run-test.sh`: 1 件実行。SANDBOX_DIR で sandbox 上書き可
- 外部スクリプト (CASE_FILTER env で regex 絞り込み):
  ash-noninteractive / ash-interactive-{cooked,jline} / jline-smoke /
  ash-applet-survey / dist-smoke / real-coreutils / real-heavy

## 主要なディレクトリ・ファイル

- `src/main/java/emulin/`
  - `Cpu.java` (i386), `Cpu64.java` (x86-64), `AbstractCpu.java`
  - `Syscall.java`, `SyscallI386.java`, `SyscallAmd64.java`
  - `Elf.java`, `Segment.java`, `Section.java`, `Memory.java`
  - `Process.java`, `Kernel.java`, `Thread64.java`, `FutexManager.java`
  - `PipeManager.java` (Phase 28-3e で synchronized 化)
  - `device/Console.java` / `StdConsole.java` / `JLineConsole.java`
- `dist/build-{dist,sandbox,jre-bundle,demo-bundle}.sh` — 配布スクリプト
- `tests/binaries/src/sys_*64.c` / `*_dyn64.c` / `aesni_static64.c`

---

# Phase 別 作業記録 (要約)

詳細は各 phase ブランチの commit にある。

## Phase 0-9: 基盤整備
i386 ELF 回帰一式と `run-test.sh`、現代 Java 化、64bit リファクタ、ELF64 ロード、
x86-64 CPU + SyscallAmd64、glibc 静的リンク (4 致命: brk 揃え、SHL マスク、
起動時 GPR ゼロクリア、AH/CH/DH/BH エンコ)、シグナル配信 (SIG_IGN/DFL/カスタム)、
Syscall シグネチャ全 long 化。

## Phase 10-21: busybox + bug 発掘
busybox / busybox sh で XORPS, PSUBD, PADDD, INC/DEC r/m8 等。8bit ALU の
AH/CH/DH/BH 統一 (`readReg8`/`writeReg8`)。`sys_dup2` が `sys_fcntl` を引数順誤り
で呼んでいた。x87 最小スタブ + SSE2 double + BMI1 + SHRD。**DIV r64 致命**:
(RDX:RAX)/64 を Long.divideUnsigned で実装していた → BigInteger で再実装。
getdents64 の `.`/`..` 補完。busybox applet サーベイ — wait4 exit code、
F_DUPFD、O_APPEND、Syscall int 残党、XADD、utimensat、0x66 prefix 漏れ。
Phase 21: 0x66 prefix 全面監査 — ALU 16 命令、IMUL imm、TEST、XCHG、Grp2
shift/rotate、NOT/NEG、INC/DEC、CMPXCHG、BSF/BSR。

## Phase 22: 対話 ash + JLine + ディストリビューション化
TIOCGPGRP は `*addr` に getpgrp、poll は即時 ready スタブ。**JLine 3.27.1**
raw/cooked 切替、SIGINT 共通化、SIGWINCH 配信。NativeConsole / emu_con.c
(JNI) 完全削除 (831 行削除)。maven-shade-plugin で fat jar、
`dist/build-dist.sh` で zip 出力。後修正: emulin.bat 文字化け、JVM Ctrl-C、
シグナル配信時 GPR + flags 保存復元、SIGRETURN_TRAMPOLINE で着地検知。

## Phase 23: シグナル拡張 + テスト基盤強化
SIGCHLD 自動配信 + **x86-64 ABI red zone (rsp-128) 尊重**。SA_RESTART (rip を
syscall 命令の手前に戻して再実行)。SA_SIGINFO 3 引数 (siginfo_t + ucontext_t)。
amd64_wait4 ECHILD、exec_replacing スキップ。並列化で 4.4x 高速化、SKIP ゼロ化、
ash-applet-survey 43 ケース。

## Phase 24: 動的リンク対応 (gcc -no-pie)
PT_INTERP 検出、interp 別 base load、auxv 拡充、SHUFPD、**PT_LOAD 以外 map
しない**、amd64_pread64、**`mem.syscall = syscall` 同期**、CPUID baseline、
0F AE 系 (FXSAVE/FXRSTOR/LDMXCSR/STMXCSR/CLFLUSH/FENCE)。

## Phase 25: 動的リンク binary 使い込み + 致命バグ
小物: tgkill / MOVMSKPD / JRCXZ / PMAXUB / futex no-op / PINSRW / socketpair
片方向 / mmap of file / clone3 → ENOSYS / GCC ネスト関数。**PF (parity flag)
追跡** — `evalCond` JP/JNP が hardcode false/true。glibc `__printf_fp` の
`ucomisd; setp al` で NaN 判定壊れ。**ADC/SBB が CF を更新していなかった
bignum 致命**: 0x11/0x13 (ADC), 0x19/0x1B (SBB), Grp1 imm case 2/3。
`printf("%g", 1e96)` → `9.:e+95`。helper `adc{16,32,64}/sbb{16,32,64}` 新設。

## Phase 26: PIE
ET_DYN を弾かずロード、load_bias、lea rip-relative も bias 付き。
**真因: `Syscall.sys_brk` 自身の 3 重 32-bit 切り詰め**。除去して pie_base を
Linux 典型 0x555555554000。

## Phase 27: 実機バイナリを動かす (要約版)

### step 1-7: 実機 coreutils + python + git + bash の基礎
- statfs/fstatfs/statx/socket → ENOSYS/EAFNOSUPPORT スタブで coreutils 24 種類
  + bash + python3 + make/file が起動 (sysinfo, sched_getaffinity, unlinkat,
  vfork, faccessat2, REP RET, PACKUSWB, CVTSD2SS, CMPSD scalar, SHUFPS, MINSD,
  PEXTRW, getitimer/setitimer → 0 等)
- **★ LEA r32 zero-extend バグ** (operand-size 無視で常に 64-bit 書き)
- **★ sys_rename/sys_mount/sys_pipe の (int) キャスト** — 64-bit ptr 破壊
- **stub の `return 0` は busy loop 誘発** — mincore→0 で grep 278k 回ループ。
  本当に成功と言える syscall 以外は ENOSYS

### step 8-18: AES + curl/wget HTTPS 動作まで
- **AES-NI / PCLMULQDQ 7 命令完全実装** (3-byte opcode escape `66 0F 38/3A`、
  FIPS-197 S-box、CPUID leaf 1 ECX で AES-NI(bit25) + PCLMUL(bit1))
- **★ Inode.get_uniq_no の弱いハッシュで st_ino 衝突** → ld.so が
  `(st_dev, st_ino)` 同一視で silent skip。`pathname.hashCode()` に置換
- **AF_INET TCP socket** 実装で wget HTTP 動作 (socket/connect/sendto/recvfrom
  MSG_PEEK/sendmsg/recvmsg/bind/listen/getsockname/getpeername/setsockopt/
  getsockopt/clock_getres/clock_nanosleep/pselect6)。MSG_PEEK は Fileinfo
  peekBuf。AF_UNIX/AF_INET UDP は EAFNOSUPPORT で `/etc/hosts` fallback
- 実 DNS で wget 動作: UDP socket、connect(udp) で dest 記憶、sendmmsg/recvmmsg、
  poll で DatagramSocket、FIONREAD ioctl
- TLS handshake 到達: PSHUFB/PALIGNR/PINSRD、/dev/urandom S_IFCHR、openssl.cnf
  二重パス、/dev/urandom dd 1MB
- **★ PSRLQ imm 未実装が "OpenSSL keylen=bits" の真因** — gcc -O2 が
  `kbits/8` を `psrlq $3, xmm` に最適化。Group 12/13/14 (`66 0F 71/72/73`)
  per-element shift 網羅
- **★ INC/DEC が CF 破壊バグ** — Intel SDM「INC/DEC は CF を変えない」違反。
  Group 5 で saved_cf で囲い込み。openssl ECDSA P-256 動作
- wget HTTPS で実 HTML — **★ amd64_write の `-EPIPE` タイポ** (EPIPE 既に
  -32 なのに `-EPIPE` で +32)。pselect6/poll/Fileinfo.Read TCP non-blocking
  を setSoTimeout(1) + peek 統一。F6 /4-/7 (MUL/IMUL/DIV/IDIV r/m8) 実装

### step 19-22: socketpair 双方向 + stat 64-bit + 調査
- step 19-20 (調査のみ): example.com / cloudflare / google で server FIN。
  実は cert load の遅さが真因 (step 59 で判明)
- step 21: socketpair 双方向化 (Fileinfo に `pipe_write_no` + `set_pipe_pair`、
  amd64_socketpair で 2 pipe を `connect_pipe()` で対称配置)
- step 22: stat レイアウトの type truncation 修正 (st_size & 0xFFFFFFFFL で
  >2GB truncate、atime/mtime/ctime の Y2038 wrap、blocks 常に 0、nsec 常に
  0)。Inode の 関連 field を long 化 + nsec 追加

### step 23-29: signal 拡張 + git workflow + pthread
- step 23: SIGALRM 自動配信 + sigprocmask + sa_mask 中再入抑止 (alarm(N)
  Java background thread → kernel.kill、setitimer ITIMER_REAL、rt_sigprocmask
  SIG_BLOCK/UNBLOCK/SETMASK + oldset、Cpu64.check_pending_signal で進入時
  mask 保存)。**Kernel.kill の pid index 1 ズレ** — pid は 1-based、ptable
  は 0-based。`find_process(pid)` 線形探索に修正
- step 25 ★: emulator 内 git workflow が動作 (`sys_access` 2 重バグ:
  `mode &= F_OK` (= mode &= 0) で R_OK/W_OK dead code 化、失敗時 -1 を
  -ENOENT/-EACCES に振り分け; `/dev/*` device router の sandbox file 無視
  fix; `sys_open` の FileOpen 失敗時 errno を parent dir 有無で分岐;
  `link(#86)` / `linkat(#265)` 実装)
- step 27: sigaction.sa_mask 完全対応 (Siginfo に sa_mask field + SA_NODEFER、
  amd64_rt_sigaction で `act_addr+24` から sa_mask 8 byte 読取り)
- step 28 ★: pthread 基礎動作 — **`Thread64`** (各 pthread = Java Thread +
  独立 Cpu64 + 共有 Memory/Syscall) と **`FutexManager`** 新設。
  clone (#56) CLONE_VM|CLONE_THREAD で Thread64 spawn (子の rip は
  `r64[R_RCX]` = syscall return address)、futex (#202)、gettid (#186)、
  exit (#60) は ThreadExitException。Memory per-byte cache を ThreadLocal 化
  (race で index out of bounds crash)。curl AsynchDNS HTTP 動作
- step 29: pthread mutex 競合下 (4 thread × 1000 iter → 4000)。
  **FutexManager.wake の wake count 嘘** (常に max を返していた → glibc
  abort)、CMPXCHG/XCHG/XADD を `synchronized(mem)` で atomic 化

### step 30-33c: 性能改善累計 — wall 3m51s → 1m41s (-56%)
| step | 改善内容 | real-heavy | run-all wall |
|---|---|---|---|
| 30 | init busy-yield → sleep(50ms)、alloclist reverse iteration + early break | 142s→105s | 3m51s→3m21s |
| 31 | alloclist Vector → TreeMap (O(log N)) | 105s→92s | 3m21s→2m08s |
| 32 | cache_size 8 → 32 | 92s→87s | 2m08s→1m55s |
| 33 | 16 hot opcode を switch (b0) で先取り (MOV/Grp1-imm8/CMP/TEST/ADD/SUB/XOR/AND/OR/LEA)、TreeMap → ConcurrentSkipListMap (pthread thread-safe) | 87s→66s | 1m55s→1m44s |
| 33b | switch 拡張 6 種 (FF/81/C7/B0-BF/C1D1D3) | 66s→62s | |
| 33c | switch 拡張 8 種 (ADC/SBB/Grp1-8bit/MOV-imm8/IMUL/MOVSXD) | 62s→62s | 1m43s→1m41s |

副次成果: wget HTTPS (iana) **50s → 10s**。教訓: switch 高速化で TreeMap
race 露見 (`pthread_mutex_dyn64` 5 回中 2 回 fail) → ConcurrentSkipListMap 必須。

### step 34-35: per-thread signal mask + tgkill thread-targeted
- step 34: `Thread64.signal_mask` (long bitmap)、`Signal` の各メソッドを
  `Thread.currentThread() instanceof Thread64` で分岐
- step 35: `Signal` に `ConcurrentHashMap<tid, int[] pending>` 追加。
  amd64_tgkill (#234) で specific thread targeting。psig() 優先順位:
  own thread pending → process-wide pending

### step 36-37: HTTPS hang 追跡 (調査のみ → 後で解決)
- curl HTTPS は emulator が遅く 80s で server idle timeout (step 36)
- git clone HTTPS は git-remote-https subprocess の pipe IPC で hang (step 37)
- → step 38-39 の synchronized 撤去 + CLOEXEC 真対応で解決

### step 38-39 ★: pipe synchronized 撤去 + CLOEXEC 真対応 → file:// clone 動作
- **★ FileAccess.FileRead の method-level `synchronized` 撤去** — pthread 後
  の世界で main が socket Read で block 中、他 thread が別 fd を read しよう
  とすると monitor entry で待たされ deadlock。fd 単位の保護は inner クラス
  で行い、外側 wrapper には付けない (教訓)
- **★ CLOEXEC 未実装が file:// silent fail の真因** — git の child notify
  pipe (CLOEXEC) が exec 越しに残り親が `read(notify_pipe, 8)` で hang
- **per-fd 管理が必須** — FD_CLOEXEC は POSIX 上 fd table のフラグ (open
  file description ではない)。FileAccess に `cloexec_fds: ArrayList<Boolean>`
  を flist と並列に持たせる。dup2 は new fd の cloexec を **クリア**、dup3 は
  flag 指定可、F_DUPFD は clear、F_DUPFD_CLOEXEC は set、execve 時に
  `close_cloexec_files()` で全 close
- **★ main thread の sys_exit (#60) で worker thread を待つ** — Linux 仕様は
  「呼び出し thread だけ exit」。main で即 process tear down すると worker
  segfault。`active_thread_count: AtomicInteger` で count==0 まで wait
- 効果: `git clone --no-hardlinks file://...` 完走

### step 40-41: mmap guard gap + 単独 string ops + PSHUF{HW,LW}
- 隣接 mmap の境界越え書き込みで main TCB 破壊 → **16 ページ (64 KB) の guard
  gap** 挿入。`prlimit64` (#302) 真対応 (RLIMIT_STACK=8MB)、`wait4` の
  specific pid 対応、`arch_prctl` が main の fs_base を上書きしていた fix
- 単独 (REP 無し) `MOVSB/MOVSW/STOSB/STOSW/LODSB/LODSW` (0xa4-0xad) 実装、
  `PSHUFHW (F3 0F 70)` / `PSHUFLW (F2 0F 70)` 実装
- 効果: `git clone --no-hardlinks git://...` 完走 +
  `git -c http.sslVerify=false clone https://github.com/...` 完走

### step 42-44: UTF-8 ファイル名 + gnutls CA load の 2 つの致命 CPU bug
- **UTF-8 ファイル名対応**: `Memory.loadString/storeString` を Latin-1 から
  UTF-8 に。`getdents64` reclen も byte 長で計算 (旧 char 数で multi-byte
  alignment 崩壊)
- **★ Bug 1: MOVSX/MOVZX r16 (0x66 prefix)** — 16-bit dest なのに 32-bit 書き
  で bits 16-31 を破壊。nettle base64 decode で発火
- **★ Bug 2: PINSRW / 0F 3A 系の RIP-relative で imm8 を未加算** — 1 byte
  ずれ。PADDB の operand が 1 byte ずれて nettle padding handler 暴走
- 効果: gnutls が 295/295 cert ファイルを正常 load

### step 45-57: TLS handshake / libtasn1 segfault 追跡 + debug 基盤蓄積
真因は step 58 で「sandbox の locale files 不備」と判明するが、過程で多数の
有用な debug 基盤を蓄積:
- `Memory.alloc()` synchronized (step 49: pthread mmap race)
- trace hooks `EMULIN_TRACE_RIP_DUMP{1-4}` / `WATCH_STORE_VAL` /
  `WATCH_STORE_ADDR` / `DUMP_AT_RIP` (step 50)
- `globalStoreEpoch` (step 51: per-thread cache cross-thread visibility fix)
- `Segment` brk buf 256 MB pre-allocate + volatile + synchronized (step 52)
- mmap layout を host と一致 (step 53-54: `memory_top = 0x7ffff7fbf000`、
  top-down alloc)
- auxv を host と一致 (step 55: 17/19 entries、AT_HWCAP / AT_PLATFORM 等)
- address aligned で host と並走比較で divergence point 特定 (step 56-57)

### step 58-59 ★: libtasn1 segfault + github HTTPS 完全動作
- **★ step 58 真因: sandbox に locale files 不足** — host は
  `/usr/lib/locale/C.utf8/LC_CTYPE` 等を mmap、emu は ENOENT で skip → 全く
  違う malloc/free pattern → chunk overlap → libtasn1 segfault。
  **教訓**: emulator core のバグと思いがちだが、まず sandbox の環境差で
  glibc が違う path を走っていないか疑う
- **★ step 59 真因: emulator が遅く CA cert load に 83 秒で server FIN**。
  workaround: `/etc/gitconfig` で `sslCAInfo = <Sectigo Root E46>` +
  `sslCAPath = ` empty で github 用 14 秒 clone

### step 60-64: 性能最適化 (JFR profile-driven)
- step 60: `Memory.globalStoreEpoch++` を multi-thread 時のみ
- step 61: `Memory.load8` / `store8` を fast/slow path 分離 (load8 698→65 byte
  で JIT inline 可)、`CacheState.lastSegment` で store fast path に segment
  cache
- step 62: Cpu64 に instruction byte buffer (16 byte prefetch)
- step 63: REX prefix を switch ループ外で先処理して dispatch skip
- **★ step 64**: `Cpu64::decode_and_exec` (= 20,476 byte) が
  `HugeMethodLimit` default 8000 byte で **JIT C2 から compile 拒否されている**
  と判明。`-XX:-DontCompileHugeMethods` flag を launcher に追加
- 効果: git clone HTTPS 14.4s → **10.4s (-28%)**

---

# Phase 28: ディストリビューション整備 + bash/git clone 実用化

## Phase 28-1〜5: 配布スクリプト + JRE 同梱 + CI

- **28-1** `dist/build-sandbox.sh`: minimal/base/full 3 段階 sandbox 構築
- **28-2** `dist/build-jre-bundle.sh`: jlink で minimal JRE (java.base +
  java.logging) 同梱、Java インストール不要
- **28-3** `dist/build-demo-bundle.sh`: bundled JRE + full sandbox の即実行 demo
- **28-4** GitHub Actions: ci.yml (push/PR で run-fast.sh) + release.yml
  (tag push で zip 自動 upload)
- **28-5** README 全面更新 (legacy JDK 1.1.6 / cygwin1.dll 参照を rewrite)

## Phase 28-2b: Windows / macOS JRE bundle を CI matrix で生成

- jlink は実行中 JDK と同 platform 用 JRE しか作れない制約に対応
- `release.yml` matrix [linux/windows/macos]、各 runner で jlink
- `build-{jre,demo}-bundle.sh` に `TARGET_PLATFORM` env var を追加 →
  Adoptium API から target JDK 取得 + `jlink --module-path` で cross-build
- 全 7 zip (dist + jre×3 + demo×3) が tag push で自動配布
- bundle 内 emulin.bat を CRLF + ASCII に固定 (LF だと cmd.exe parse 失敗)
- NOTICE.txt で Temurin OpenJDK の GPLv2+CPE ライセンスと source 入手先明記

## Phase 28-3 a-i: bash + git clone 実用化 + coreutils sandbox

**a. bash + line edit** — `build-sandbox.sh` の copy 対象に bash 追加 (libtinfo
依存自動解決)、`TIOCSWINSZ` (0x5414) を no-op stub。demo launcher に
**`-CJ` を常時付与** → bash readline が raw byte (ESC sequence) を受け取って
動く既存 chain を活用、コード変更ゼロで line edit (history / cursor / Tab)。
expect で `echo aaa<CR><Up><CR>` → readline が history から復元再実行を確認。

**b. bash → fork+exec git の経路修正 (3 段階)**
1. **`/bin/sh` symlink 必須** — git は file:// clone で `/bin/sh -c
   "git-upload-pack ..."` を fork+exec。sh が無いと親 git が read で hang。
   `/bin/sh → bash` symlink を生成
2. **`safe.directory = *` を /etc/gitconfig に** — host uid と emulator uid の
   不一致で git が "dubious ownership" で拒否。子 git-upload-pack は親の
   `-c` を継承しないので gitconfig 必須
3. **`protocol.version` は transport 別** — file:// は v0、https:// は v2 が
   必要。global 設定すると片方が壊れる。launcher で env var 強制は廃止、
   file:// 利用者が `git -c protocol.version=0 clone --no-hardlinks file://`
   と明示する想定

**c. ★`amd64_mremap` 新設 — git clone HTTPS の segfault 解消**
- 真因: `SyscallAmd64.amd64_call` が `syscall #25 (mremap)` で **i386 ABI
  専用の `sys_mremap`** を直接呼んでいた。amd64 では `bx = rdi = old_addr
  64-bit pointer`、`(int)bx = 0xee412000` に切り詰め → `mem.load32(...)`
  の int を long に sign-extend して `0xffffffffee412004` に access → segfault
- bisect 中、最初は libgnutls 内 RIP=0x7ffff7d5337f だと思っていたが、実は
  **libc の `0f 05` syscall 命令**だった (`rax=0x19` = syscall # 25 = mremap
  で確定)。bytes ダンプで仮定を覆した
- `amd64_mremap` 新設 (register 直接引数、in-place `mem.realloc` 試行 →
  失敗 + MREMAP_MAYMOVE flag set なら alloc + copy + free で relocate)
- 効果: bash → `git clone --depth=1 https://github.com/octocat/Hello-World.git`
  が **13.5 秒で完走**

**d. ★`Pipeinfo` thread-safe 化 — file:// clone race fix**
- 並列負荷下で `git clone --no-hardlinks file://` が散発的に多様な error
  (`bad line length character: 005?` / `early EOF` / `bad band #48` /
  `sideband demuxer disconnect` / `libgcc_s.so.1 must be installed for
  pthread_exit`) で失敗
- 真因: `Pipeinfo.read() / write()` が共有 mutable state (buf[]/used/wp/rp)
  を **synchronization なしで update**。pthread 後の世界では parent git ↔
  child git-upload-pack が別 Java thread から racing → data 破損
- 修正: read/write/is_connected/disconnect を `synchronized` 化、
  `sleep + yield` の spin を `wait()` / `notifyAll()` に置換。parallel
  4 連 clone 3/8 → 7/8 PASS、protocol error 完全消滅

**e. regression 4 ケース + bash 6 ケース追加 (`real-coreutils.sh`)**
- `git-clone-{file,content,bash,bash-content}` で bash → fork+exec → file://
  clone の総合回帰
- bash 6 ケース追加 (assoc array / `[[ =~ ]]` regex / substr / function /
  here-string)。**211 → 221 PASS**

**g. full sandbox を coreutils 中心に再構成**
- 旧: bash + git/curl/openssl/python3/wget (heavy stack、80 MB)
- 新: bash + GNU coreutils 30+ (ls/cat/cp/mv/rm/mkdir/rmdir/touch/ln/echo/
  true/false/dirname/basename/uname/pwd/sleep/date/dd/chmod/chown/chgrp/
  wc/head/tail/cut/tr/uniq/sort/printf/find/diff/yes/tee/stat/df/du) +
  grep/sed/awk/file/expr + git/curl/wget。openssl CLI / python3 削除
  (curl/git の HTTPS は libssl 経由なので影響なし)
- demo zip: 80 MB → 72 MB (linux)、51 → 38 MB (windows)、80 → 69 MB (macos)

**h. `amd64_mkdirat` (#258) 実装**
- cp -r が destination dir 作成に使う syscall。AT_FDCWD or 絶対パスは
  cwd 起点で sys_mkdir 同等、dirfd が実 fd + 相対パスは get_name(dirfd) で
  dir path 取得して結合

**i. ★at-syscall を dirfd 対応 + ioctl の silent success 撲滅**
- 旧 amd64_openat / fstatat は dirfd を無視して `process.get_curdir()` で
  resolve していた → find / cp -r が `openat(open_dir_fd, "subdir", ...)` で
  recursive 走査時に false ENOENT。共通 helper `resolve_at_path(dirfd, path)`
  を新設、openat / mkdirat / fstatat で使用
- `sys_open` のロジックを `open_resolved(name, flags)` helper に extract、
  amd64_openat と sys_open で共有
- **★ FICLONE ioctl (0x40049409)** が silent success (0) を返していたため
  cp が「reflink で複製したつもり」のまま fallback せず destination が空に
  なっていた。`-EOPNOTSUPP` (-95) を返して fallback 強制
- 未知 ioctl 一般を `-ENOTTY` (-25) に変更 (POSIX 標準、glibc/coreutils
  はこれを見て fallback)
- 効果: `find /tmp/src` が subdir 内 file まで列挙、`cp -r src dst/copied`
  完全コピー

## 配布 zip 早見表

| 種類 | サイズ (Linux/Win/Mac) | Java | sandbox |
|------|------------------------|------|---------|
| dist (busybox) | 1.7 MB | system | 空 |
| jre-bundle | 22 / 20 / 20 MB | bundled | busybox のみ |
| demo (28-3g coreutils) | 72 / 38 / 69 MB | bundled | bash + coreutils + git/curl/wget |

---

# 累計バグ修正・実装の傾向 (今後の Phase で参考に)

## アドレス幅 / int 切り詰め

- **アドレスの int 切り詰め** (Phase 9/26/27 step 7) — シグネチャ long 化
  でも個別 syscall に「ローカル変数 int」「`(long)bx & 0xFFFFFFFFL`」が
  残ると低位で動き高位 (PIE) で segfault。`grep -nE "= \(int\)(bx|cx|dx|si|di)"` 監査
- **★ syscall ABI 不整合 (i386 vs amd64) の罠** (Phase 28-3c) — `sys_*` は
  i386 ABI 専用 (stack 経由 args) なので amd64 から直接呼ぶと 32-bit
  truncation を起こす。`(int)bx` で上位 32-bit 切り捨て、`mem.load32()` の
  int 引数が long に **sign-extend** されて `0xffffffff____` で kernel
  address access → segfault。amd64 では必ず `amd64_<name>` を作って
  register 直接引数で書く
- **追跡時の罠**: 当初 RIP が libgnutls 内 instruction に見えていたが、実は
  libc の `0f 05` (syscall) 命令。bytes ダンプ + `rax` を syscall # と
  解釈すれば真因にたどり着く

## 命令フラグ / operand-size

- **0x66 operand-size prefix 漏れ** (Phase 11/14/20/21) — imm 命令で
  imm16/imm32 分岐忘れ。新規 imm 命令追加時必ず 0x66 ブランチ
- **AH/CH/DH/BH (REX 無 + rm=4-7)** (Phase 7/13) — 8bit 命令は必ず
  `readReg8`/`writeReg8` 経由
- **operand-size 無視の dest 書き** (Phase 27 step 3 LEA) — 32-bit dest は
  上位 zero-extend、16-bit は上位 48-bit 保持
- **MOVSX/MOVZX r16 (0x66 prefix)** (Phase 27 step 44) — 16-bit dest は下位
  16 bit のみ書き上位 48 bit 保持。32-bit 書きで bits 16-31 を破壊
- **PF (parity flag) を放置すると致命** (Phase 25) — glibc の `ucomisd; setp al`
  で NaN 判定。UCOMISD/COMISD は ZF/PF/CF
- **ADC/SBB がフラグを更新しないと bignum 連鎖が壊れる** (Phase 25) —
  CF を読むだけで書かないと %g で 1e96 以上が壊れる
- **INC/DEC は CF を変えない (Intel SDM)** (Phase 27 step 17) — SBB chain
  途中の DEC が borrow を消す。新命令実装時 SDM "Flags Affected" 必読
- **多レジスタ命令の上位読み忘れ** (Phase 17 DIV) — RDX:RAX を読み忘れる

## SIMD 命令の細部

- **PSRLQ imm 等の per-element shift sub-opcode** (Phase 27 step 16) —
  Group 12/13/14 (`66 0F 71/72/73`) は per-element/byte で sub-opcode 違う
- **PALIGNR concat 順序** (Phase 27 step 15) — Intel SDM 式を直接写経
- **PSHUFHW (F3 0F 70) / PSHUFLW (F2 0F 70)** (Phase 27 step 41) — PSHUFD
  (66 0F 70) と prefix だけ違う 3 兄弟
- **RIP-relative + imm 持ち命令の address 計算** (Phase 27 step 44) —
  x86-64 spec: target = **命令全体の末尾 RIP** + disp。
  PINSRW (`66 0F C4`)、0F 3A 系 (PALIGNR/AESKEYGENASSIST/PCLMULQDQ 等)、
  PSHUFD (`66 0F 70`)、Group 12/13/14 はすべて imm8 を持つので fixEA に
  `next + imm_size` を渡す
- **単独 (REP 無し) string ops は別 dispatch** (Phase 27 step 41) — REP 経由は
  F3 prefix path、単独は通常の opcode dispatch (0xa4-0xad)。git-remote-https
  等 hand-written コードで使用

## メモリ配置 / mmap / 動的リンク

- **隣接 mmap の境界越え書き込みで他データを破壊** (Phase 27 step 40) —
  bump allocator で密集すると buffer overrun が直接隣接 region を破壊。
  **16 ページ (64 KB) の guard gap** を入れる
- **prlimit64 / getrlimit は本物の値を返す必要** — RLIMIT_STACK が 0 だと
  glibc pthread が最小 stack で spawn して overflow。8 MB / RLIM_INFINITY
- **wait4 / waitpid の specific pid (pid > 0) 対応** — start_command +
  finish_command パターン。pid==-1 のみだと git "waitpid is confused"
- **動的リンクの最低ライン** (Phase 24) — PT_INTERP + interp 別 base load +
  auxv (AT_BASE/AT_ENTRY/AT_PHDR/AT_PHNUM/AT_EXECFN)、PT_LOAD 以外 map しない、
  CPUID baseline、FXSAVE/FXRSTOR、pread64
- **emulator 層フィールド差し替えは要注意** — Process/Memory/Syscall は相互
  参照、片方差し替えで他方の参照が古いまま (Phase 24 mem.syscall)
- **PIE では section.sh_addr / IRELATIVE relocation も load_bias でずれる**
- **st_ino のハッシュは衝突に強くする** (Phase 27 step 10) — ld.so は
  `(st_dev, st_ino)` 同一視で重複ロード silent skip
- **MAP_FIXED 後の mark_address は前進のみ** (Phase 27 step 11)

## syscall / glibc 流儀

- **CLOEXEC は per-fd で追跡** (Phase 27 step 39) — Fileinfo に持たせると
  Dup で fd 共有時に巻き込まれる。FD_CLOEXEC は POSIX 上 fd table のフラグ
  (open file description ではない)。FileAccess に並列 ArrayList。dup2 は
  new fd の cloexec を **クリア**、F_DUPFD は clear、F_DUPFD_CLOEXEC は set。
  execve 時に `close_cloexec_files()` で全 close
- **main thread sys_exit (#60) は process 全体を殺さない** (Phase 27 step 39)
  — 呼び出し thread だけ exit。worker thread が居る場合
  `active_thread_count` で count==0 まで wait
- **新しい実機バイナリは ENOSYS / EAFNOSUPPORT スタブで通る** (Phase 27 step 1)
- **stub の `return 0` は busy loop 誘発** — 本当に成功と言える syscall
  以外は ENOSYS
- **対話プログラム ioctl** (Phase 22) — TIOCGPGRP/tcgetpgrp/tcsetpgrp は
  値 return + `*addr` 書き込み必須。失敗時 -ENOTTY で job control 諦めさせ
- **clock_gettime は実時刻を書く** — caller は ts ポインタに期待
- **fcntl(F_SETFL) return 0 は非 blocking 期待を裏切る** — Fileinfo nonBlock
  反映必須
- **POSIX timer (timer_create/timer_settime)** — Java background thread sleep
  → kernel.kill(pid, SIGALRM)
- **既に負の errno 定数に `-` を付けると符号逆転** (Phase 27 step 18) —
  `Syscall.EPIPE = -32` なのに `return -EPIPE` で +32。
  `grep -nE "return -E[A-Z]+"` 監査
- **at-syscall は dirfd 解決必須** (Phase 28-3i) — openat / mkdirat /
  fstatat 等は AT_FDCWD or 絶対パスは cwd 起点、実 fd + 相対パスは
  `get_name(dirfd)` を起点に。共通 helper `resolve_at_path(dirfd, path)`
  でまとめる。dirfd 無視だと find の recursive 走査が false ENOENT
- **未知 ioctl は -ENOTTY を返す** (Phase 28-3i) — 0 (silent success) は
  cp の FICLONE (0x40049409) で「reflink 複製したつもり」のまま fallback
  せず destination 空になる致命。POSIX 標準 -ENOTTY で fallback 強制

## シグナル / fork / wait

- **シグナル配信の最小実装は致命** (Phase 22-23) — (a) GPR 16 + flags 保存復元、
  (b) **x86-64 ABI red zone (rsp-128) 尊重**、(c) SA_SIGINFO 3 引数 ABI
  (rsi=&siginfo, rdx=&ucontext)、(d) syscall EINTR 前に SA_RESTART チェック再実行
- **wait4 / fork / exec の race** (Phase 23) — `is_child_exited` が「子なし」(0)
  と「子未終了」(-1) を区別。exec 中の旧 process は exec_replacing でスキップ
- **Kernel.kill の pid index** (Phase 27 step 23) — pid は 1-based、ptable は
  0-based。`find_process(pid)` 線形探索

## socket / 非 blocking

- **MSG_PEEK は libc/wget で必須** — Java Socket に peek API 無い → Fileinfo
  に peekBuf。peek は実 read、その後の read は peekBuf 消費
- **AF_UNIX / UDP は EAFNOSUPPORT で fallback 強制** — glibc が `/etc/hosts`
- **socket EOF 後の poll/select は ready=0 (timeout) を返す** — Fileinfo
  socketEof フラグ
- **i386 の syscall を amd64 移植時 `Util.swap32` を落とす罠** — i386
  sys_socketcall の getsockname は swap32 二重掛け
- **poll が「全 fd 常に ready」と返すと wakeup pipe で誤動作** — fd 種別判定:
  socket は available/peekBuf、pipe は基本「データ無し」、通常 fd は読める
- **non-blocking connect は -EINPROGRESS** — 即 0 だと curl abort
- **getsockopt は optlen NULL でも *optval を埋める** — 書かないと caller が
  スタックゴミをエラーコード誤読
- **Pipeinfo.read を非 blocking 化** — Fileinfo.nonBlock を見て空 pipe + 接続中
  なら -EAGAIN
- **Java Socket.InputStream.available() は kernel buffer を見ない** —
  setSoTimeout(1) + read(byte[1]) で peek、SocketTimeoutException は EAGAIN
- **pselect6 timeout を honor しないと caller 無限ループ** — wget は
  read_timeout でなく pselect6 timeout 依存

## DNS / UDP

- **glibc resolver は FIONREAD を recvmsg 前に呼ぶ** — UDP は cachedDatagram
  サイズ、TCP は available()、pipe は peekLen
- **DatagramSocket には available() 無い** — 短い setSoTimeout で receive
  試行、受信できたら Fileinfo にキャッシュ
- **glibc 2.34+ DNS resolver は sendmmsg/recvmmsg** — A と AAAA を 1 syscall
- **sendmsg/recvmsg は msg_name (UDP の dest/src sockaddr)** — IP は
  getsockname と同じ swap32 二重掛け

## OpenSSL / TLS

- **OpenSSL は /dev/urandom を S_ISCHR でチェック** — `_set_file_stat64` 後に
  path 分岐で S_IFCHR | 0666 上書き
- **OpenSSL は openssl.cnf を `/usr/lib/ssl/openssl.cnf` から読む** (Ubuntu
  compile-time default) — sandbox に両方 (or symlink) 必要
- **gcc -O2 は size_t / 8 を SIMD (PSRLQ) に最適化** — shared library 境界
  またぐ関数呼び出しでだけ発火する微妙な bug
- **OpenSSL バグ追跡は逆方向に narrow down** — 失敗箇所のソース行は上流症状。
  objdump + 自作 C を shared lib に分離して isolation 再現
- **bn_mul_mont の num サイズ別経路分岐** — num が 4 倍数かつ ≥4 で
  AES-NI/MULX/AVX 系の高速 ASM 経路。Mont 乗算 bug 追跡時は **必ず 4-limb
  (= P-256) でテスト**
- **emulator が遅すぎて TLS server idle timeout** (step 19-20/36/59) — cert
  load + verify が 80s 以上で server FIN。github は Sectigo single CA
  workaround (sslCAInfo + sslCAPath empty) で 14s 動作

## pthread / atomic / 共有状態

- **pthread mutex は CMPXCHG/XCHG/XADD の atomic が必要** (Phase 27 step 29)
  — `synchronized(mem)` で囲む
- **FutexManager.wake は actual woken count を返す** — max を返すと glibc
  "futex_wait_simple returned a wrong value" abort
- **Memory per-byte cache は ThreadLocal** — 共有すると複数 thread refill
  race で index out of bounds crash
- **pthread thread の clone child rip は r64[R_RCX]** (= syscall return
  address)。get_ip() は syscall instr address なので NG
- **per-thread signal mask** (Phase 27 step 34) — `Thread64.signal_mask`、
  `Thread.currentThread() instanceof Thread64` で分岐
- **per-thread pending signal** (Phase 27 step 35) — `ConcurrentHashMap<tid,
  int[] pending>`、own thread pending を process-wide より優先消費
- **★ Pipeinfo は完全 synchronized + wait/notify** (Phase 28-3e) — read/write
  が共有 mutable state (buf[]/used/wp/rp) を持ち、parent process と child
  process が別 Java thread から read/write し合うと compound update が
  racing して `bad line length` / `early EOF` / `bad band #N` /
  `sideband demuxer disconnect` 等多様な data corruption。read/write/
  is_connected/disconnect を synchronized 化、sleep+yield spin を
  wait/notifyAll に置換
- **method-level synchronized は pthread 環境で deadlock の温床** (step 38)
  — fd 単位の保護は inner クラスで行い、外側 wrapper には付けない。
  `FileAccess.FileRead` の synchronized 撤去で git:// pack 取得が動いた

## 性能 / プロファイリング

- **JIT は per-byte cache の HIT パスを十分最適化** (Phase 27 step 26 失敗) —
  当てずっぽうの最適化は逆効果。profiler 必須
- **alloclist は ConcurrentSkipListMap** (Phase 27 step 31, 33) — TreeMap は
  thread-safe でなく pthread + 高速 dispatch で race 露見
- **decode_and_exec の hot opcode を switch で先取り** (Phase 27 step 33) —
  Java JIT が tableswitch (jump table、O(1)) に compile
- **cache_size の sweet spot は 32** (Phase 27 step 32) — 8/16/32/64/128 を
  計測、32 で 16% 改善、それ以上 plateau
- **init process の busy yield ループは worker と CPU 競合** — Thread.sleep(50ms)
- **★ HugeMethod (>8000 byte) は JIT 拒否** (Phase 27 step 64) — `Cpu64::
  decode_and_exec` (20K+ byte) が compile されず interpreter に。
  `-XX:-DontCompileHugeMethods` を launcher 必須

## デバッグツール / 追跡パターン

- **CPU loop で stuck している原因特定には rip dump 必要** — `EMULIN_TRACE_RIP=N`
  で N 命令ごと rip + GPR dump
- **syscall trace は戻り値も** — `EMULIN_TRACE_SH=1` で `DBG ret #n = 0xX`、
  ENOSYS / EAGAIN / EINPROGRESS の混乱を即特定
- **jstack で deadlock / busy spin / per-thread bottleneck 特定** — pthread を
  扱うようになって特に有効
- **bytes ダンプは命令位置の最終確認** — disassembly が正しい命令を示している
  と仮定せず、emulator が見ているメモリ上の byte 列を直接読む
  (Phase 28-3c の libgnutls→libc syscall 誤認識を発見)

## sandbox / 環境

- **sandbox 環境差は emulator core バグの fake 真因** (step 58) — locale /
  ssl certs / config の不足で glibc が違う path を走り、無関係 lib で
  segfault。core 修正前にまず host との syscall 並走比較
- **build-sandbox.sh full の中身** (Phase 28-3g) — bash + GNU coreutils 30+ +
  grep/sed/awk/file/expr + git/curl/wget。`/bin/sh → bash` symlink 必須

# テスト戦略

- 「1 syscall 1 テスト」原則 (`sys_*64.c`)
- `-nostdlib` で int 0x80 / syscall 直叩き
- 期待値は実 Linux カーネル仕様に合わせる
- 並列実行前提、テスト間で sandbox / /tmp 衝突させない
- 既知バグは `.skip` で残せるが Phase 23 で全 .skip 解消済
