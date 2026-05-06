コミットログには、以下を記載しないようにしてください。
Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>

objdump / nm / grep / readelf / addr2line / strace は許可なしで実行してよい。

---

# プロジェクト概要

古い Emulin (Java 製 32bit Linux ELF エミュレータ) を現代 Java で動かし、
さらに x86-64 (64bit ELF) を実行できるように拡張する。

## 現状サマリ (Phase 27 step 51 完了時点)

- 現代 Java (OpenJDK 21+) / Maven、**pure Java only** (Phase 22 で JNI 撤去)
- 32bit ELF (i386) と 64bit ELF (x86-64) 両方を実行可能
- glibc 静的リンク + 動的リンク + **PIE** (ET_DYN を 0x555555554000) 動作
- 動的リンク + 複雑な libc 機能: printf 各種 (NaN/Inf/bignum)、malloc/free、
  signal/raise (tgkill)、setjmp/longjmp、fopen/fgets、strftime、sscanf、qsort、
  getopt、POSIX regex、dlopen/dlsym、wchar、C++ STL、fork+pipe IPC、zlib、
  socketpair (双方向)、mmap of file、GCC ネスト関数
- **実機 GNU coreutils 24 種類 + bash + Python 3.12 + OpenSSL 3.0** 動作
- **AES-NI / PCLMULQDQ 命令を完全実装** (FIPS-197 host 一致)
- **wget HTTP/HTTPS、curl HTTP/HTTPS、wget DNS が動作**。wget HTTPS で iana
  (RSA cert + TLS 1.3) は確実に動作。curl HTTPS は libssl 経由で TLS
  handshake 自体は完璧成功 (Client Finished まで)
- **git clone 多経路で動作**:
  - `git clone git://...` (kernel.org dtc.git): 346 obj / 38 deltas / 335
    ファイル checkout 完走 (step 40)
  - `git clone --no-hardlinks file://...`: 完走 (step 39 で CLOEXEC fix)
  - `git -c http.sslVerify=false clone https://github.com/...`: 完走 (step 41)
  - `git clone https://...` (cert verify あり) のみ libtasn1 内で segfault 残存
- busybox 静的リンクで **88 applet** 動作。**対話 busybox ash** 動作 (Phase 22)
- **JLine 3 採用** で Linux/macOS/Windows 共通の raw mode / Ctrl-C / SIGWINCH
- **ディストリビューション zip** (約 1.7 MB)、Windows cmd.exe からも完全対応
- シグナル基盤: SIGCHLD 自動配信、SA_RESTART で syscall 自動再開、
  SA_SIGINFO 3 引数、レジスタ完全保存 + red zone 尊重、alarm/setitimer →
  SIGALRM、rt_sigprocmask、sigaction.sa_mask + SA_NODEFER、
  **per-thread signal mask + tgkill thread-targeted pending** (step 34-35)
- **実機 git** が emulator 内で動作 (init/add/commit/status/log/diff)
- **pthread 動作**: pthread_create/join + 共有 memory + **mutex 競合下** で
  4 worker × 1000 iter counter++ → 4000 (Thread64 + FutexManager + atomic
  CMPXCHG/XCHG/XADD)。**curl AsynchDNS (HTTP) 動作**
- **CLOEXEC 真対応** (step 39): pipe2(O_CLOEXEC) / SOCK_CLOEXEC / dup3 /
  fcntl(F_SETFD) すべて per-fd 管理。execve 時 cloexec fd を自動 close
- **回帰テスト 211 PASS / 0 FAIL / 0 SKIP**、5 連続安定。`run-fast.sh` 27 秒、
  `run-network.sh` 3 分、フル `run-all.sh` 約 1m41s (step 30-33c で
  baseline 3m51s から **56% 短縮**)。wget HTTPS (iana): 50s → **10s**

## 既知の未解決課題

- **git clone HTTPS (cert verify あり) で libtasn1 内 segfault**
  (step 48 で更に絞り込み):
  - 旧 step 46 の「libc free に rdi=0x1440」segfault は再現せず、現在は
    libtasn1+0x4220 (`cmp 0x68(%rbx), %rax`) で fault address 0xab00000068
    に進化。最近の MOVSX r16 + RIP-rel imm8 fix の副作用と推定
  - 詳細追跡で判明:
    (a) Debian の libtasn1.so.6 は **ASN1_SMALL_VALUE_SIZE=40** で build
        (source は 16)。struct size = **0xb0 byte** (source は 0x98)。
        offset 0x78 = small_value[40], 0xa0 = tmp_ival, 0xa4 = start, 0xa8 = end
    (b) bad rbx = 0x555555b78f00 は実は real node 0x555555b78ed0 + 0x30 を
        指す **node 中身へのポインタ** (name 配列の途中)
    (c) `*(rbx+0x70)` を読むと real node の offset 0xa0..0xa7 (4 byte
        reserved + start=0xab) が返り、64-bit LE では 0xab00000000
    (d) 0xab は libtasn1 が直前に `mov %r12d, 0xa4(%r13)` で書いた `start`
        field の counter 値 = 171 (DER 位置)。libtasn1 自身の挙動は正しい
  - 真因 (未特定): どこかで「`node + 0x30`」というポインタが tree (down/left)
    として保存されている。0x30 = 48 byte は struct の field offset と一致
    しないため、emulator の CPU 命令 bug (32-bit 切り詰め、misaligned
    arithmetic) が pointer 計算を歪めている可能性が高い
  - 計測ツール: `EMULIN_TRACE_RIP_DUMP` (RIP 到達時 register/memory dump)、
    `EMULIN_TRACE_RIP_DUMP2` (r13 中心の dump)、`EMULIN_WATCH_STORE_VAL`
    (特定 64-bit 値の store64 を捕捉)、`EMULIN_WATCH_STORE_ADDR` (特定
    アドレスへの全 byte 書き込みを捕捉) を Cpu64.java/Memory.java に追加。
    今後の memory 系 bug 追跡に再利用可
  Workaround: `git -c http.sslVerify=false clone https://...` で動作
- **ECDSA cert + TLS 1.3 で server FIN** (step 19-20):
  example.com / cloudflare.com / google.com で Client Finished (80 byte) write
  直後 server が silent FIN (Alert なし)。iana (RSA cert) は同 cipher で動く →
  ECDSA 経路固有のバグ。ClientHello は host と byte-identical、server response
  decrypt も成功 (cert chain/CertificateVerify/Finished 全部 parse OK)、
  AES-256-GCM/HMAC-SHA384/ECDSA P-256 verify 単体 OK。原因不明。
  次の手筋: tcpdump で Client Finished 暗号文を host と bytes diff /
  Wireshark で server 応答を decrypt / `c_handshake_traffic_secret` HKDF 出力 diff
- **curl HTTPS は timeout** (step 36): TLS handshake 自体は完全成功するが
  emulator が遅く (80s) server idle timeout を超えて FIN される。wget HTTPS
  (10s) は同 server で動く。emulator の根本的な高速化が必要
- **git clone --hardlinks file://** が hardlink 同一性検証で失敗:
  `link()` 後の stat で「dest と src の inode が同じ」検証で落ちる。
  `--no-hardlinks` 指定で動作
- **IPv6 (AF_INET6) 未対応** — getaddrinfo は IPv4 のみ
- **WSL DrvFs (`/mnt/c/...`) は I/O 遅く chmod 効かない** — sandbox は
  `mktemp -d -t emulin-sb.XXXXXX` で Linux /tmp に置く

## 動作テスト基盤

- `tests/` に i386/x86-64 静的リンク ELF (-nostdlib で int 0x80/syscall 直叩き)
- `make -C tests/binaries`: テストバイナリビルド
- `tests/scripts/run-all.sh`: 全テスト (211 ケース、~1m41s)。並列化済 (binary
  は xargs -P で nproc 並列、ext scripts は & + wait で 5 本同時)。
  各テストは Linux 側 /tmp 上の per-test sandbox。`JOBS=1` で逐次に戻せる
- `run-fast.sh`: 軽量 subset (~27s、real-* / dist 抜き)
- `run-network.sh`: ネットワーク関連だけ (~3m)
- `run-test.sh`: 1 件実行。SANDBOX_DIR で sandbox 上書き可。`_dyn` 名は
  host の ld-linux と libc.so.6 を sandbox にコピー
- 外部スクリプト形式 (CASE_FILTER env で個別ケース regex 絞り込み可):
  ash-noninteractive / ash-interactive-cooked / ash-interactive-jline /
  jline-smoke / ash-applet-survey / dist-smoke / real-coreutils / real-heavy

## 主要なディレクトリ・ファイル

- `src/main/java/emulin/`
  - `Cpu.java` (i386), `Cpu64.java` (x86-64), `AbstractCpu.java`
  - `Syscall.java` (long 化済), `SyscallI386.java`, `SyscallAmd64.java`
  - `Elf.java`, `Segment.java`, `Section.java`, `Memory.java`
  - `Process.java`, `Kernel.java`, `Thread64.java`, `FutexManager.java`
  - `device/Console.java` / `StdConsole.java` / `JLineConsole.java`
- `dist/build-dist.sh` — fat jar + busybox + ランチャを zip 化
- `tests/binaries/src/sys_*64.c` — AMD64 syscall 単体テスト
- `tests/binaries/src/*_dyn64.c` — 動的リンク回帰
- `tests/binaries/src/aesni_static64.c` — AES-NI / PCLMULQDQ FIPS-197

---

# Phase 別 作業記録 (要約)

詳細は各 phase ブランチの commit にある。

## Phase 0-9: 基盤整備
- i386 ELF 回帰一式と `run-test.sh`、現代 Java 化、64bit リファクタ、ELF64 ロード、
  x86-64 CPU + SyscallAmd64、glibc 静的リンク (4 致命: brk 揃え、SHL マスク、
  起動時 GPR ゼロクリア、AH/CH/DH/BH エンコ)、シグナル配信 (SIG_IGN/DFL/カスタム)、
  Syscall シグネチャ全 long 化

## Phase 10-21: busybox + bug 発掘
- busybox / busybox sh で XORPS, PSUBD, PADDD, INC/DEC r/m8 等
- 8bit ALU の AH/CH/DH/BH 統一 (`readReg8`/`writeReg8`)
- `sys_dup2` が `sys_fcntl` を引数順誤りで呼んでいた
- x87 最小スタブ + SSE2 double + BMI1 + SHRD
- **DIV r64 致命**: (RDX:RAX) 128bit / 64bit を Long.divideUnsigned で実装
  していた。BigInteger で再実装
- getdents64 で "." 重複 (`.`/`..` 補完)
- busybox applet サーベイ — wait4 exit code、F_DUPFD、O_APPEND、Syscall int
  残党、XADD、utimensat、0x66 prefix 漏れ
- Phase 21: 0x66 prefix 全面監査 — ALU 16 命令、IMUL imm、TEST、XCHG、
  Grp2 shift/rotate、NOT/NEG、INC/DEC、CMPXCHG、BSF/BSR

## Phase 22: 対話 ash + JLine + ディストリビューション化
- TIOCGPGRP は `*addr` に getpgrp、poll は即時 ready スタブ
- **JLine 3.27.1** raw/cooked 切替、SIGINT 共通化、SIGWINCH 配信
- NativeConsole / emu_con.c (JNI) を完全削除 (831 行削除)
- maven-shade-plugin で fat jar、`dist/build-dist.sh` で zip 出力
- 後修正: emulin.bat 文字化け、JVM Ctrl-C、シグナル配信時 GPR + flags 保存復元、
  SIGRETURN_TRAMPOLINE で着地検知

## Phase 23: シグナル拡張 + テスト基盤強化
- SIGCHLD 自動配信 + **x86-64 ABI red zone (rsp-128) 尊重**
- SA_RESTART (rip を syscall 命令の手前に戻して再実行)
- SA_SIGINFO 3 引数 (siginfo_t + ucontext_t)
- amd64_wait4 ECHILD、exec_replacing スキップ
- 並列化で 4.4x 高速化、SKIP ゼロ化、ash-applet-survey 43 ケース

## Phase 24: 動的リンク対応 (gcc -no-pie)
- PT_INTERP 検出、interp 別 base load、auxv 拡充、SHUFPD、
  **PT_LOAD 以外 map しない**、amd64_pread64、**`mem.syscall = syscall` 同期**、
  CPUID baseline、0F AE 系 (FXSAVE/FXRSTOR/LDMXCSR/STMXCSR/CLFLUSH/FENCE)

## Phase 25: 動的リンク binary 使い込み + 致命バグ
- 小物: tgkill / MOVMSKPD / JRCXZ / PMAXUB / futex no-op / PINSRW /
  socketpair (片方向、step 21 で双方向化) / mmap of file / clone3 → ENOSYS /
  GCC ネスト関数
- **PF (parity flag) 追跡** — `evalCond` JP/JNP が hardcode false/true。
  glibc `__printf_fp` の `ucomisd; setp al` で NaN 判定壊れ
- **ADC/SBB が CF を更新していなかった bignum 致命**: 0x11/0x13 (ADC),
  0x19/0x1B (SBB), Grp1 imm case 2/3。`printf("%g", 1e96)` → `9.:e+95`、
  helper `adc{16,32,64}/sbb{16,32,64}` 新設

## Phase 26: PIE
- ET_DYN を弾かずロード、load_bias、lea rip-relative も bias 付き
- **真因: `Syscall.sys_brk` 自身の 3 重 32-bit 切り詰め**。除去して
  pie_base を Linux 典型 0x555555554000

## Phase 27: 実機バイナリを動かす

### step 1-7: 実機 coreutils + python + git + bash
- 実機 /bin/ls (statfs/fstatfs/statx → ENOSYS、socket → EAFNOSUPPORT)
- coreutils 9 種類 (fadvise64 → 0、mincore は ENOSYS。step 3 で grep が
  mincore→0 で 278k 回 busy loop と判明)
- **LEA r32 zero-extend バグ** (operand-size 無視で常に 64-bit 書き)
- coreutils 24 種類 + bash (getpeername/getsockname → ENOTSOCK、sysinfo、
  sched_getaffinity、unlinkat、vfork、faccessat2、REP RET、PACKUSWB、
  CVTSD2SS、CMPSD scalar)
- Python 3.12 + OpenSSL (getrandom 実バイト書き、FIOCLEX/FIONCLEX no-op、
  SHUFPS/MINSD)。`real-heavy.sh` (timeout 120) に分離
- make / git --version / file (getitimer/setitimer → 0、PEXTRW、env passthrough)
- **sys_rename / sys_mount / sys_pipe の (int) キャスト** — 64-bit ptr 破壊

### step 8-10: AES + curl --version
- step 8: OpenSSL DRBG bug、深追いせず保留 → step 16 で真因
- **AES-NI / PCLMULQDQ 7 命令完全実装** — 3-byte opcode escape
  (66 0F 38/3A)、FIPS-197 S-box、CPUID leaf 1 ECX で AES-NI(bit25) +
  PCLMUL(bit1)。aesni_static64 host 一致
- curl --version (TLS) — **`Inode.get_uniq_no` の弱いハッシュで st_ino
  衝突** → ld.so が `(st_dev, st_ino)` 同一視で silent skip。
  `pathname.hashCode()` に置換。MAP_FIXED 後の mark_address は前進のみ

### step 11-14: HTTP / DNS
- **AF_INET TCP socket** — wget HTTP 動作。socket/connect/sendto/recvfrom
  (MSG_PEEK)/sendmsg/recvmsg/bind/listen/getsockname/getpeername/setsockopt/
  getsockopt/clock_getres/clock_nanosleep/pselect6。MSG_PEEK は Fileinfo
  peekBuf。amd64 では SubProcess spawn しない。AF_UNIX/AF_INET UDP は
  EAFNOSUPPORT で `/etc/hosts` fallback
- wget hang 解消: socketEof/nonBlock、fcntl(F_SETFL) 反映、pselect6 実 fd_set、
  clock_gettime 実時計
- curl HTTP: timer_create/timer_settime (POSIX timer + SIGALRM)、rt_sigsuspend、
  Pipeinfo 非 blocking、poll の fd 別判定、non-blocking connect で -EINPROGRESS、
  getsockname IP byte-order、getsockopt は optlen NULL でも optval 書き、
  EMULIN_TRACE_RIP / 戻り値 trace 追加
- 実 DNS で wget 動作: UDP socket、connect(udp) で dest 記憶、sendmmsg/recvmmsg、
  poll で DatagramSocket、FIONREAD ioctl、recvfrom/recvmsg src_addr byte-order、
  pthread 系 clone は -EAGAIN reject

### step 15-18: HTTPS
- TLS handshake 到達 — PSHUFB/PALIGNR/PINSRD、/dev/urandom S_IFCHR、
  サンドボックス整備 (openssl.cnf 二重パス、/dev/urandom dd 1MB、/etc/passwd)
- **PSRLQ imm 未実装が "OpenSSL keylen=bits" の真因** — gcc -O2 が
  `kbits/8` を `psrlq $3, xmm` に最適化。Group 12/13/14 (`66 0F 71/72/73`)
  per-element shift 網羅
- **INC/DEC が CF 破壊バグ** — Intel SDM「INC/DEC は CF を変えない」違反。
  Group 5 で saved_cf で囲い込み。openssl ECDSA P-256 動作。副次:
  PREFETCH (`0F 18`) と多バイト NOP (`0F 1F`) を no-op
- wget HTTPS で実 HTML — **`amd64_write` の `-EPIPE` タイポ** (EPIPE 既に
  -32 なのに `-EPIPE` で +32 を返し partial-write retry で req 破壊)。
  pselect6/poll/Fileinfo.Read TCP non-blocking を setSoTimeout(1) + peek 統一。
  F6 /4-/7 (MUL/IMUL/DIV/IDIV r/m8) 実装

### step 19-20: ECDSA cert TLS 1.3 で server FIN (調査のみ)
- 動く: iana (RSA + TLS 1.3 + AES_256_GCM_SHA384)
- 動かない: example.com / cloudflare.com / google.com (ECDSA + TLS 1.3)
- ★ iana と example.com は cipher 同一、cert type だけ違う → ECDSA 固有
- ClientHello は host と byte-identical (385 byte)。server encrypted handshake
  (3825-3827 byte) は decrypt 成功、cert chain 4 段、CertificateVerify
  (ecdsa_secp256r1_sha256)、server Finished (verify_data 48 byte) 全 parse OK
- 我々の CCS + Client Finished (80 byte) write 後 server 即 FIN (Alert なし)
- AES-256-GCM/HMAC-SHA384/ECDSA P-256 単体 OK
- 推定原因: Client Finished 暗号文が server 視点で invalid (decrypt or
  HMAC verify 失敗) → silent FIN。OpenSSL 内部の cert sig algo 依存分岐?
  または TCP MSS 境界での断片化?
- 残された手筋: tcpdump bytes diff / Wireshark decrypt / HKDF 出力 diff /
  custom python TLS client / iana ECDSA CDN 探し

### step 21: socketpair 双方向化
- Fileinfo に `pipe_write_no` 追加 (read pipe_no と別)、`set_pipe_pair`
- amd64_socketpair: 2 pipe (順方向/逆方向) を `connect_pipe()` で対称配置
- 通常 sys_pipe は pipe_write_no=-1 で従来動作
- 新規回帰 `sockpair_bidir_dyn64`。**200 PASS** (199 → 200)

### step 22: stat レイアウトの type truncation 修正
- 旧 144 byte レイアウト自体は ABI と一致だが書き出しに 32-bit mask 残存:
  `st_size & 0xFFFFFFFFL` で >2GB truncate、`st_atime/mtime/ctime & 0xFFFFFFFFL`
  で Y2038 wrap、`st_blocks` 常に 0、`st_*time_nsec` 常に 0
- Inode の st_size/atime/mtime/ctime/blocks を long 化、nsec 追加
  (File.lastModified() ms から導出)、blocks=`(size+511)/512`、
  `_set_file_stat64` の mask 除去。i386 は仕様上 32-bit なので `(int)` で維持
- 新規回帰 `sys_stat_layout64`。**201 PASS**

### step 23: SIGALRM 自動配信 + sigprocmask + sa_mask 中再入抑止
- alarm(N) #37 真対応: Java background thread → kernel.kill(pid, SIGALRM)
- setitimer(ITIMER_REAL) #38 真対応: it_value initial + it_interval 周期。
  alarm() と timer は同 ITIMER_REAL 共有
- rt_sigprocmask #14 真対応: SIG_BLOCK/UNBLOCK/SETMASK + oldset 書き
- sa_mask 中の self-mask: Cpu64.check_pending_signal で進入時 mask 保存、
  配信 signal 自身を mask、SIGRETURN で復元
- 副次: `Kernel.kill` の `ptable.elementAt(_pid)` 1 ズレ (pid 1-based vs
  ptable 0-based)。`find_process(_pid)` 線形探索に修正。
  `sys_pause` が pending signal チェックせず → psig() != -1 で -EINTR
- 新規回帰 `sys_alarm_deliver64` / `sys_sigmask64`。**203 PASS**

### step 24: 性能改善 (easy wins) — 9% 高速化
1. `psig()` fast-path: 旧は 1 命令ごと 32 signal scan + 64 method calls。
   `pending_recv_count` で early return。1 volatile read で済む
2. `EMULIN_TRACE_*` getenv ホイスト: 旧は毎命令 HashMap lookup × 2。
   eval ループ進入時 1 回読み
3. `process.evals = executed` を 1024 命令ごとに batch
- real-heavy: 155s → 141s

### step 25: emulator 内 git workflow が動作 — 4 系統
1. **`sys_access` 2 重バグ**: `mode &= F_OK` (= mode &= 0) で R_OK/W_OK
   dead code 化。失敗時 -1 (=-EPERM) を -ENOENT/-EACCES に。git は EPERM
   を fatal abort、ENOENT なら mkdir + retry
2. **`/dev/*` device router の sandbox file 無視**: 未認識 /dev/* は
   regular file として open 試行
3. **`sys_open` の FileOpen 失敗時 errno**: parent dir の有無で
   ENOENT/EACCES 振り分け。git は ENOENT で mkdir + retry
4. **`link(#86)` / `linkat(#265)` 実装**: git の object commit は tmp →
   最終 hash 名を link + unlink で atomic。Java NIO `Files.createLink`
- 新規回帰 4 ケース (git-init/add/commit/log)。**207 PASS**
- 副次: 既存 sys_access64/sys_rename64/sys_unlink64 の期待値を正しい
  -2 = ENOENT に更新

### step 26: 性能改善 (失敗・no-op コミット)
- Memory cachedSeg を試したが Python 起動 142s → 175s で revert
- 学び: 旧 per-byte cache の HIT パスは「配列 1 アクセス」のみ、JIT が
  既に十分最適化。当てずっぽうの最適化は逆効果

### step 27: sigaction.sa_mask 完全対応
- Siginfo に sa_mask field + SA_NODEFER 定数
- amd64_rt_sigaction で `act_addr+24` から sa_mask 8 byte 読み取り、
  oldact 書き戻しは全 32 byte
- check_pending_signal で `saved_mask | sa_mask | (1L << (sig-1))` を新 mask、
  SA_NODEFER 時は sig 自身を加えない
- 新規回帰 `sys_sa_mask64`。**208 PASS**

### step 28: pthread 基礎動作 — curl AsynchDNS 解放
新規クラス:
- **`Thread64`**: 各 pthread = Java Thread + 独立 Cpu64 + 共有 Memory/Syscall。
  CLONE_CHILD_CLEARTID で thread exit 時 *ctid_addr=0 + futex wake
- **`FutexManager`**: アドレス毎の Object monitor で wait/notify。
  FUTEX_WAIT (0/9) と FUTEX_WAKE (1/10) 最小実装

amd64 syscall:
- **clone (#56)**: CLONE_VM|CLONE_THREAD なら Thread64 spawn。子は親 register
  継承、rax=0、rsp=child_stack、fs_base=tls、rip=`r64[R_RCX]` (= syscall
  return address。親 cpu の get_ip() = syscall instr address ではない)
- futex (#202): 真対応
- gettid (#186): Thread64 経由なら Thread64.tid、main なら pid
- exit (#60) vs exit_group (#231): pthread thread exit は ThreadExitException

副次:
- Memory per-byte cache を **ThreadLocal** に (race で index out of bounds crash)
- Cpu64.copy_state_from/copy_state_into 新設 (xmm 含む全 register)
- Kernel.next_tid: pthread TID 採番 (10000 から)

新規回帰 `pthread_basic_dyn64`。**209 PASS**。**curl AsynchDNS HTTP 動作**

### step 29: pthread mutex 競合下 — 4 worker + 共有 counter
新規回帰 `pthread_mutex_dyn64` (4 thread × 1000 iter → 4000) で当初
SIGABRT。3 系統修正:
1. **`FutexManager.wake` の wake count 嘘**: 常に max を返していた →
   glibc が "futex_wait_simple returned a wrong value" abort。WaitNode
   に waiters/wakers counter、wake は実数返す
2. **CMPXCHG (`0F B0/B1`) atomic 化**: `mem` 共通 monitor で synchronized
3. **XCHG (`86/87`) と XADD (`0F C0/C1`) atomic 化**: 同様に synchronized
- **210 PASS**

### step 30-33c: 性能改善累計 — wall 3m51s → 1m41s (-56%)
| step | 改善内容 | real-heavy | run-all wall |
|---|---|---|---|
| 30 | init busy-yield → sleep(50ms)、alloclist reverse iteration + early break | 142s→105s | 3m51s→3m21s |
| 31 | alloclist Vector → TreeMap (O(log N)) | 105s→92s | 3m21s→2m08s |
| 32 | cache_size 8 → 32 (計測ベース、64/128 は plateau) | 92s→87s | 2m08s→1m55s |
| 33 | 16 hot opcode を switch (b0) で先取り (MOV/Grp1-imm8/CMP/TEST/ADD/SUB/XOR/AND/OR/LEA)、TreeMap → ConcurrentSkipListMap (pthread thread-safe) | 87s→66s | 1m55s→1m44s |
| 33b | switch 拡張 6 種 (FF/81/C7/B0-BF/C1D1D3) | 66s→62s | |
| 33c | switch 拡張 8 種 (ADC/SBB/Grp1-8bit/MOV-imm8/IMUL/MOVSXD) | 62s→62s | 1m43s→1m41s |

副次成果: wget HTTPS (iana) **50s → 10s** (5x)
教訓: step 33a の switch 高速化で TreeMap race 露見 (`pthread_mutex_dyn64`
5 回中 2 回 fail) → ConcurrentSkipListMap 必須

### step 34: per-thread signal mask (pthread_sigmask 互換)
- step 27 まで mask は process 全体共有 (Siginfo.mask)。POSIX は per-thread
- `Thread64` に `signal_mask` field (long bitmap)
- `Signal.psig()/is_signal_masked/set_signal_mask/get/set_signal_mask_bits`
  を `Thread.currentThread() instanceof Thread64` で分岐。後方互換維持
- amd64_clone_thread: 親の signal_mask を子に inherit
- 新規回帰 `pthread_sigmask_dyn64`。**211 PASS**

### step 35: thread-targeted pending signal (tgkill 真対応)
- step 34 まで pending は process 全体共有で複数 thread unmasked だと
  先着順で picks up。tgkill ターゲット指定が無視されていた
- `Signal` に `ConcurrentHashMap<Integer tid, int[] pending>` 追加。
  key = Thread64.tid または process.pid (main thread)
- recv_to_thread(target_tid, sig): 特定 thread の pending に enqueue
- psig() 優先順位: own thread pending → process-wide pending
- amd64_tgkill (#234): `process.recv_to_thread(tid, sig)` で specific
- 回帰拡張: `pthread_sigmask_dyn64` に parent_handler_fired check 追加。
  子から `pthread_kill(parent, SIGUSR1)` → parent pending に入る → 親 unblock
  で配信。step 34 の「子が parent 宛て signal を消費」バグを完全解決

### step 36: curl HTTPS timeout 追跡 (調査のみ)
- TLS handshake 自体は完全成功 (CCS + ClientFinished 送信まで)
- 直後 `OpenSSL SSL_connect: Broken pipe` で server FIN
- iana に対して wget HTTPS は 10s で成功、curl は 80s+ かかる
- pthread DNS 無関係 (`--resolve` でも同じ)
- 仮説: emulator TLS handshake が 80s → server idle timeout 超え → FIN
- curl/libcurl の processing time が遅すぎる。emulator の根本的高速化必要
- 代替: wget HTTPS は実用的 (10s)。openssl s_client + nc は試す価値あり

### step 37: git clone HTTPS hang 追跡 (調査のみ)
- git clone https://github.com/.../Hello-World.git で 3 分以上 "Cloning into..."
- emulator user CPU 2.7s しか使っていない (≠ CPU-bound)
- syscall trace で最後は read(fd=0, buf, 4096) で blocked
- git は fork + execve で git-remote-https spawn。子が stdin (parent からの
  pipe) を read 待ち。親が pipe に何も書いていない (or 子に届いていない)
- git --version/init/status (local) は 1〜2 秒
- HTTPS-fetch する subprocess との pipe IPC が壊れている。execve 越しに
  pipe fd を継承する経路に問題か
- curl HTTPS (step 36 のスピード問題) と git clone HTTPS (pipe deadlock)
  は **異なるバグ**

### step 38: FileAccess.FileRead の `synchronized` 撤去 — git:// で pack 取得完了
- `FileAccess.FileRead(int fd, byte[])` が **method-level `synchronized`**
  だった。pthread 後の世界では main thread が socket Read で block している
  間、他 thread が別 fd を read しようとすると monitor entry で待たされ
  deadlock 化。fd 単位の独立性は Fileinfo / Pipeinfo 内側で確保されているので
  外で囲む必要はない
- 効果: `git clone --depth=1 git://git.kernel.org/.../dtc.git` が
  pack 受信 + delta 解決まで完走 (346 obj / 274 KiB / 38 deltas done)
- 残バグ (step 38 では未着手):
  (a) post-pack で subprocess [2] が segfault (`RIP=0x1525e9` の
      `mov 0x20(%r9), %r14d` 系で r9 がガベージ)。`shallow.lock` が残る
  (b) file:// local clone は `git-upload-pack` fork 直後に silent fail
- 教訓: **method-level `synchronized` は pthread 環境で deadlock の温床**。
  fd 単位の保護は inner クラスで行い、外側 wrapper には付けない

### step 39: CLOEXEC 真対応 + main thread sys_exit semantics — file:// clone 動作
- **CLOEXEC 未実装が file:// silent fail の真因**: git の child notify pipe
  (CLOEXEC) が emulator では exec 越しに残り続け、親の `read(notify_pipe, 8)`
  が child execve 完了を検知できず hang。`Syscall.java:744` に「未追跡」と
  明記されていた dead path が露呈
- **per-fd 管理が必須**: 当初 Fileinfo に cloexec を持たせたら `Dup` で
  Fileinfo が共有されるため dup2 した old fd まで cloexec が消えて
  `pipe-tee` 回帰失敗。POSIX 上 FD_CLOEXEC は fd table のフラグ (open file
  description ではない)。FileAccess に `cloexec_fds: ArrayList<Boolean>` を
  flist と並列に持たせて per-fd で解決
- 反映箇所:
  (a) `sys_open(O_CLOEXEC=0x80000)` → set_cloexec(fd, true)
  (b) `pipe2(O_CLOEXEC)` → 両端 cloexec
  (c) `socket(SOCK_CLOEXEC=0x80000)` → cloexec
  (d) `socketpair(SOCK_CLOEXEC)` → 両端 cloexec
  (e) `dup3(oldfd, newfd, O_CLOEXEC)` → newfd のみ cloexec
  (f) `dup2(oldfd, newfd)` → POSIX 仕様で newfd の cloexec を **クリア**
      (oldfd の cloexec は per-fd 管理なので影響無し)
  (g) `fcntl(F_SETFD, FD_CLOEXEC=1)` / `F_GETFD` → 真対応
  (h) `fcntl(F_DUPFD_CLOEXEC)` → newfd cloexec
  (i) `Kernel.exec` → `close_cloexec_files()` で cloexec fd を全 close
- **main thread の sys_exit (#60) で worker thread を待つ**: 旧実装は
  「main thread sys_exit は process 全体 exit」だったが、Linux 仕様は
  「呼び出し thread だけ exit」。main の sys_exit で即 process tear down すると
  pthread worker (git の AsynchDNS 等) が壊れた状態で動き続け segfault。
  Process に `active_thread_count: AtomicInteger` を追加、Thread64
  spawn/finally で incr/decr。main の sys_exit は count==0 まで wait。
  glibc の pthread_exit が main の場合に sys_exit を直接呼ぶ慣例に整合
- 効果:
  - file:// local clone (`--no-hardlinks`) **完走** (working tree checkout 含む)
  - real-coreutils-git-init/add/commit/log 全 PASS 維持
  - 211 PASS / 0 FAIL 維持
- **未解決**: git:// (network) clone は pack 受信完走するが post-pack で
  pthread worker exit 後 main が RIP=0x401525e9 で segfault。RIP が
  segment 範囲外 → 上位 32-bit が消えた可能性 (LEA zero-extend バグ類似)。
  次 step で追跡

### step 51: per-thread cache の cross-thread visibility 修正
step 50 で「extnValue.down に 0x555555b78f00 を書いた rip は libc+0xab060」
と判明。disasm すると `movups %xmm0, 0x10(%rbx)` で **glibc malloc が chunk
を unsorted bin に追加する命令**:

```
ab040: mov 0x70(%r15), %rax         ; load bin head's bk pointer
ab044: lea 0x60(%r15), %rdx         ; rdx = &arena.top (aliased bin head)
ab048..ab052: pack rax+rdx into xmm0
ab056: cmp 0x18(%rax), %rdx         ; sanity check
ab060: movups %xmm0, 0x10(%rbx)     ; new_chunk->fd = old_last, ->bk = bin
```

つまり **別 chunk の fd/bk フィールドが extnValue.down と同じアドレスに
書かれている** = chunk overlap が依然存在 (pthread mmap fix では解消しない
別経路)。

可能性として per-thread cache の cross-thread visibility 問題を疑った:
- thread A の `store8` は自分の cache のみ invalidate するが、thread B の
  cache は古いまま → glibc malloc / mutex 等の atomic op が壊れる

修正:
- `Memory.globalStoreEpoch` (volatile long) を追加
- `store8` 毎に increment
- `load8` で cache_epoch と比較し、ズレていれば cache を refill

効果:
- run-all.sh 211 PASS / 0 FAIL 維持
- pthread test 5/5 OK 維持
- ただし git clone HTTPS の libtasn1 segfault は **依然同じ場所で fail**

cache visibility は real bug だったが、libtasn1 segfault の真因は別系統で、
追加調査が必要。考えられる残候補:
1. CPU 命令 bug が glibc malloc 内部 (LOCK CMPXCHG16B / SIMD atomic ops 等) で誤動作
2. Memory.alloc の brk 経由の race (mmap 以外の heap 確保)
3. Java 側 byte[] 共有 access の memory model 問題 (volatile barrier 不足)

### step 50: libtasn1 segfault 親 node 特定 + 追加 trace hook (調査のみ)
step 49 で pthread mmap race を fix したが git clone HTTPS の libtasn1
segfault は依然 fail。新たな手筋:

- `EMULIN_TRACE_RIP_DUMP4=<RIP>`: 指定 RIP で `mov 0x60(%rdi), %rbx` の
  rdi と `*(rdi+0x60)` を dump
- `EMULIN_DUMP_AT_RIP=<RIP>:<ADDR>`: 指定 RIP かつ rbx==ADDR の時、ADDR
  から 256 byte を text + hex で dump (mem.in() で安全 check 付き)
- `Memory.current_thread_rip()`: store64 watchpoint が main thread の rip
  ではなく、書いてる thread の rip を取るよう per-thread 化

判明:
- libtasn1+0x41fb (`mov 0x60(%rdi), %rbx`) で rdi=0x555555b79ff0 (name=
  "extnValue", X.509 extension Value) のとき、その `down` field が
  0x555555b78f00 になっている
- 0x555555b78f00 は real node 0x555555b78ed0 (small_value="2.5.4.10") の
  +0x30 を指す壊れたポインタ
- `EMULIN_WATCH_STORE_ADDR=555555b7a050` (= extnValue.down のアドレス)
  trace で 0x555555b78f00 を書いた rip が **0x40280060 だが、これは libc
  の text section 範囲外** (libc は [0x40022000, 0x40233d90) 範囲)
  - rip 検出ロジック自体に問題がある可能性 (decode_and_exec 中の rip 同期)
  - または別 process の memory layout が混在している可能性

depth が深くなり、各仮説の検証に追加の追跡作業が必要。次の手筋:
1. write watchpoint の rip 取得を decode_and_exec 進入時の正確な rip に
   修正 (process.cpu.get_ip() が間違った rip を返している疑い)
2. または segfault 直前の state dump を真の crash 時に行う hook を追加
3. または「pthread mmap race fix で git clone HTTPS の chunk overlap が
   解消したのに segfault する」事実から、libtasn1 bug は本当に独立で、
   別 root cause の可能性を再考

### step 49: pthread mmap race 修正 — 同時 mmap で同 address 取得バグ
最小再現 (4 thread + 1 calloc/free each) を CPU/syscall trace で詳細追跡:

- syscall trace で 2 つの mmap が同じ address (0x40a67000) を返している事実
  を発見: 1 つは 8 MB stack、もう 1 つは 128 MB PROT_NONE 領域
- `Memory.alloc()` (mmap の bump allocator) に **synchronized が無い** ため、
  thread A が `mark_address` を read → thread B も read → 両方が同じ address
  を返して、それぞれ違う `mark_address` を write する race condition
- 結果として alloclist に overlap entry が複数できて、`floorEntry` lookup が
  「最も近い entry」を返すが、それが target address を含まない場合 segfault
  扱いになる (より低い address の正しい entry が検索されない)

**修正**: `Memory.alloc()` 全体を `synchronized(alloclist)` で囲んで直列化。
read-modify-write (`mark_address` 更新 + `alloclist.put`) を atomic に。

効果:
- `tests/repro_pthread_calloc.c` (4 thread × 1 calloc/free): 5/5 → **5/5 OK**
- `tests/repro_pthread_calloc_loop.c` (4 thread × 5000 iter): fail/3 → **3/3 OK**
- run-all.sh **211 PASS / 0 FAIL** 維持

ただし git clone HTTPS の libtasn1 segfault (RIP=0x4133d220) は **同じ場所で
依然 fail**。これは pthread mmap race とは独立した別バグであることが判明。
step 48 の「chunk overlap が真因」仮説は誤りで、libtasn1 segfault の真因は
今後の追跡が必要。

### step 48: libtasn1 segfault 細分化 — Debian struct size と pointer corruption (調査のみ)
step 47 までの「UAF / overlap chunk 仮説」を更に絞り込んだ:
- 最近の MOVSX r16 / RIP-rel imm8 fix の影響で segfault 位置が変化
  - 旧: libc free + 0x25 (rdi=0x1440)
  - 新: libtasn1+0x4220 (`cmp 0x68(%rbx),%rax` で rbx=0x555555b78f00)
- emulator 内 trace hook を新設して詳細追跡:
  - `EMULIN_TRACE_FREE_BAD=<RIP>`: libc free entry で rdi が小さすぎる時に
    caller stack を 8 段 dump (今回は free に到達しなくなったので未発火)
  - `EMULIN_TRACE_RIP_DUMP=<RIP>`: 指定 RIP で rbx/r13/r12 と struct field
    を dump
  - `EMULIN_TRACE_RIP_DUMP2=<RIP>`: 指定 RIP で r13 中心の struct dump
  - `EMULIN_WATCH_STORE_VAL=<HEX>`: store64 が指定値と一致した瞬間の
    addr/rip を出力 (bogus pointer 出所追跡)
  - `EMULIN_WATCH_STORE_ADDR=<HEX>`: 指定アドレス +0..+7 への全 byte
    書き込みを出力
- 0xab 単一 byte 書き込みの犯人を特定: libtasn1+0xd265
  (`mov %r12d, 0xa4(%r13)`) で `r13->start = 0xab` (counter=171) を書く。
  libtasn1 自身の正常動作
- Debian struct layout 検証: `gcc + offsetof` で SMALL_VALUE_SIZE 値別に
  測定 → **40 で sizeof=0xb0, start=0xa4, end=0xa8** がぴったり一致。
  Debian shipped libtasn1.so.6 は ASN1_SMALL_VALUE_SIZE=40 で build されて
  いる (source 16 ではない)。これで「offset 0xa4/0xa8 への書き込み」は
  legitimate と確定
- bad rbx=0x555555b78f00 の正体: real node 0x555555b78ed0 + 0x30 (name
  配列の途中) を指すポインタ。`*(rbx+0x70)` が real node の offset 0xa0..0xa7
  (4 byte reserved + start=0xab) を読んで 0xab00000000 となる
- 真因推定: どこかで「`node + 0x30`」が tree pointer として保存されて
  いる。0x30 = 48 byte は struct field offset と一致しない (name は 0..0x40、
  下位 field は 0x44 以降)。emulator の CPU 命令 bug で pointer 計算が
  歪んでいる可能性が高い (32-bit 切り詰め、misaligned arithmetic 等)
- 残された手筋:
  (a) **実施済み**: store64 watchpoint で 0x555555b78f00 の出所を特定。
    libc malloc の `_int_malloc` split 処理 (libc+0xac4e8 `lea
    (%rdx,%rbx,1),%rcx; mov %rcx,0x60(%r12)`) が `arena.top` に書いていた。
    chunk@0x555555b78e40 を 0xc0 split した remainder が 0x555555b78f00 で
    main arena (r12=0x403d8ac0) の top に store された
  (a-cont) **重要発見**: 同 trace で chunk@0x555555b78ec0 (= real node 1
    の chunk addr) が **同じ main arena で別途 allocate されている** こと
    も判明。chunk@0x555555b78ec0 (size 0xc0 → ec0..f80) と chunk@0x555555b78f00
    (size 0xc0 → f00..fc0) が **overlap している**。これは正常な malloc では
    起こり得ず、**emulator 上で libc malloc の内部 chunk metadata が壊れて
    いる** ことを示す
  (a-cont) 0xab00000000 の正体: chunk@0x555555b78f00 を node ptr と誤解した
    libtasn1 が `*(0x555555b78f00 + 0x70) = *(0x555555b78f70)` を読む。
    これは chunk@0x555555b78ec0 の user data (= real node 1) の offset 0xa0..0xa7
    = (4 byte reserved 0) + (start = 0xab) → 64-bit LE で 0xab00000000
  (b) libtasn1+0x41d0 関数の rbx 設定全パス trace は未実施
  (c) ASN1_SMALL_VALUE_SIZE=40 で localbuild した libtasn1 で再現するかは
      未検証 (custom build では segfault せず通る、と step 47 で確認済み)
  (d) 真因深掘り: malloc の chunk overlap を引き起こした emulator instruction
      の特定。glibc malloc は CMPXCHG16B / MOVSXD / 32-bit conditional store
      など hot path で SIMD/atomic 多用するため、これらのどれかが微妙に
      壊れている可能性。要追跡
- 副次成果: Debian の libtasn1 が ASN1_SMALL_VALUE_SIZE=40 patch 入りで
  build されているという事実は今後の cert 系 debug で重要

**追加発見 (step 48 後半)**: 0x555555b78f00 の出所追跡で
**libc malloc が同じ arena で overlap chunk を allocate している** ことが
確定。chunk@0x555555b78ec0 (size 0xc0) と chunk@0x555555b78f00 (size 0xc0)
が完全に overlap。これは emulator 上で libc malloc の chunk metadata が
壊れている証拠。真因は emulator の CPU instruction (CMPXCHG16B / 32-bit
conditional store / SIMD のどれか) が glibc malloc 内部で誤動作している
可能性が高い。

`EMULIN_TRACE_RIP_DUMP3=<HEX>` (rdx/rbx/rcx/r12/r14 を 1 行 dump) を追加
新設し、glibc malloc の split 処理 (libc+0xac4e8) の victim/size/remainder
を可視化できるようにした。

**追加発見 (step 48 拡張トレース)**: `EMULIN_WATCH_STORE_ADDR=403d8b20` で
main arena の `top` field (offset 0x60) への全書き込みを捕捉した結果、
**7 つの異なる RIP** が top を更新していると判明:
- `0x402814f9` (libc+0xac4f9): 29861 回 — split top の主経路 (`_int_malloc`)
- `0x402800be` (libc+0xab0be): 1518 回 — chunk consolidation 経路
  (`_int_free` か `unlink` で adjacent chunk が top に merge される)
- `0x40281755` (libc+0xac755): 43 回
- `0x4027ed03` (libc+0xa9d03): 6 回
- `0x40282181` (libc+0xad181): 2 回
- `0x40281a18` / `0x4027ee6d`: 各 1 回

split path (0x402814f9) trace の line 29193 で「victim r14=0x555555b598d0
が prev top 0x555555b7a940 と一致しない」事象を観測。これは split top の
code path に入ったときに victim が top と異なる状態になっていることを示し、
**top が他経路 (consolidation 等) で書き換えられた直後に split path に入った
可能性** を示唆。複数 update path のどこかで誤った chunk address が top に
書かれて、後の split で overlap が起きていると推測

real-malloc bug の絞り込みには、
- 7 つの top writer 全てを同時 trace (rip + new_value)
- それぞれの write と関連する instruction の動作確認
- 特に rare path (40281a18, 4027ee6d 等) を最優先で正確性検証
が必要。現時点で深掘り保留 (相応の作業時間が必要)

**重大発見 (step 48 最小再現)**: 30 行 C プログラムでバグを再現分離:
- `pth_simple.c`: 4 thread が各々 1 回 calloc + free するだけ
  → 5/5 runs で segfault (RIP 種類は揺れるが必ず libc 内部)
- 1 thread だと 5/5 runs OK → **pthread 同時 calloc が必須条件**
- 2 thread でも 5/5 fail
- 失敗 RIP の例:
  - libc+0xae83d (`lock cmpxchg %edx,(%r12)`) — arena mutex 取得 (`__libc_calloc`
    内、glibc per-thread arena cache の lock)
  - libc+0x98d6f (`syscall` instruction with rax=0xca = futex)
  - libc+0xae5XX 各種
- 失敗 fault address: 0x44000030, 0x44000158 等。0x44xxxxxx 帯は thread arena
  の mmap 領域 (heap_info struct base)
- trace: thread 起動時 mmap → munmap → arena 確保のシーケンス中で fault
  - syscall #11 munmap で 56 MB / 10 MB の巨大領域を munmap している
    (glibc の thread arena 確保パターン: mmap(64MB, PROT_NONE) → mprotect
    → 端を munmap で align 調整)
  - 我々の mmap/munmap が この align 調整パターンに対応できていない可能性

これで **git/libtasn1 から完全分離した repro** が手に入った。今後の追跡:
1. pth_simple.c を回帰 binary に追加 (静的 link 版で確実に再現させる)
2. mmap (PROT_NONE) + 続く mprotect + munmap の連携を真対応にする
3. または lock cmpxchg / atomic 命令の per-thread fs_base 解釈を再確認

**追加発見 (step 48 同時トレース)**: 7 つの top writer 全てを同時 trace し
た結果、top の前進・後退は **全て glibc の正常な動作と整合する**:
- 0x402814f9 (split): top を victim+nb (前進方向) に更新
- 0x402800be / 0x4027ed03 (consolidation): top を merge した chunk start
  (後退方向) に更新 — 例えば top のすぐ手前の chunk が free されると、
  consolidation で top がその chunk start にドロップする
- これらの動作はそれぞれ glibc malloc の正常な仕様。bug は top の更新
  ロジックではなく、**chunk metadata (size field の PREV_INUSE flag や
  fastbin/unsorted bin の chunk address)** が emulator で誤って読み書きされて、
  consolidation で in-use chunk が誤って free 扱いされる可能性が高い

bug の絞り込みには、glibc malloc の `_int_free` で chunk の PREV_INUSE
判定 (`(size & 0x1) == 0` チェック) と consolidation logic の trace が必要。
あるいは、最小再現プログラム (calloc/free を loop で多数実行して chunk
overlap を検出) で git/libtasn1 と切り離して再現を試みるのが効果的

Workaround は依然 `git -c http.sslVerify=false clone https://...`

### step 46: TLS handshake segfault 真因再特定 (調査のみ)
step 45 で「ld.so の symbol resolution が wrong load_bias を使う」と判定
したが、その判定は誤りだった。詳細追跡で:

- 仮説検証: 最小 C 再現テスト (gnutls + dlsym + cert load) を作成。host と
  emulator で同じ動作 → ld.so の symbol resolution は実は正しい
- 真の load_bias 確認: mmap [0x401d5000, 0x403e7000) (size 2.07 MB) を
  libgnutls と仮定していたが実は **libc.so.6**。`__libc_free`/`free`/`cfree`
  symbol が同 vaddr 0xadd50 にあることを `readelf` 全 lib スキャンで確認
- 正しい解釈: GOT[free]=0x40282d50 = libc.l_addr (0x401d5000) + 0xadd50
  = **libc の free の正しいアドレス**。ld.so 解決は完璧
- 真因: free() の最初の load `mov -0x8(%rdi),%rax` (chunk metadata) で
  fault。`%rdi=0x1440`, `0x1440-8=0x1438` は unmapped → segfault
- 0x1440 の出所: watchpoint で `*(r14+0x50) = 0x1440` の書き込み元を特定
  → libc の malloc 内部 (rip=0x40280080 = libc+0xab080 付近、chunk
  metadata writer)。**malloc が書いた chunk size 0x1440 が、libtasn1 の
  構造体 field として誤って読まれている**
- 推定原因: libtasn1 の構造体 field (offset 0x50) が NULL 初期化されるべき
  だが uninitialized で残り、stale malloc metadata = 0x1440 を pointer と
  して使用 → free() に渡して crash
- 必要な追跡: libtasn1 のコードで構造体 init 経路をたどり、emulator のどの
  CPU 命令が初期化を skip しているか特定

副次成果: ld.so の `elf_machine_rela` 内 fb23/fb7b 詳細 trace、library
mapping の同オフセット偶然一致リスク、`mov -0x8(%rdi),%rax` 起点の libc
malloc chunk metadata access パターンの理解

### step 45: TLS handshake 内 ld.so symbol resolution 追跡 (調査のみ)
step 44 で CA load を完全に直したが、git clone HTTPS は TLS handshake 中の
別 segfault に進化 (RIP=0x40282d75)。詳細追跡で:

- 失敗位置: libtasn1 が `free()` を PLT 経由で呼び出す際 (`asn1_der_decoding2`
  の `call free@plt`)
- 真因: ld.so が `free` シンボル解決時、誤った DSO の load_bias を使用
  - libc の `free` symbol は vaddr 0xadd50
  - libgnutls の `gnutls_pkcs11_copy_x509_crt2+0x220` も偶然 vaddr 0xadd50
  - ld.so が `libgnutls.l_addr + 0xadd50` を計算 (本来 `libc.l_addr + 0xadd50`)
  - 結果 GOT[free] に libgnutls 内のランダムな関数中間アドレス
  - その後 `free()` 呼び出しで libgnutls 内に飛び segfault
- 原因コード位置: ld.so vaddr 0xfb7b 付近 `mov %r9, (%rax)` (`elf_machine_rela`)
- 仮説:
  - link_map のサーチ順または lookup 結果の解釈が誤り
  - DSO 識別 (l_name や hash table) の処理にバグ
  - 我々の memory 管理が link_map データを破壊している可能性
- 必要な追跡: ld.so 内部の relocation 関数 (`_dl_lookup_symbol_x` 等) を
  step trace。または gdb 風 backtrace の取得。深掘り作業なので次 step に保留

副次成果: GOT 書き込みウォッチポイント (`EMULIN_WP_ADDR`) で誤書き込み
の rip と書き込み内容を直接特定する手法を確立

### step 44: gnutls CA load 真因解決 — 2 つの致命的バグ
追跡を継続して **2 つの独立した CPU 命令バグ** を特定し fix:

**Bug 1: MOVSX/MOVZX r16 (0x66 prefix) の上位ビット破壊**
- `66 0F BE 14 11` (movsbw (%rcx,%rdx,1),%dx) で operand-size 16-bit のとき、
  本来は **下位 16 bit のみ書き上位 48 bit 保持** が POSIX
- 旧実装は 32-bit 書き → bits 16-31 を破壊
- nettle base64 decode の table lookup `signed char [256]` で発火。
  `mov %dl, low_byte_of_word` の後の処理で破壊された上位 16 bit が露出

**Bug 2: PINSRW / 0F 3A 系の RIP-relative で imm8 を未加算**
- x86-64 spec: RIP-relative addressing は **命令全体の末尾 RIP** を使う。
  `imm8` 持ち命令では disp32 後の RIP ではなく imm8 後の RIP
- 旧 fixEA は disp32 直後の `next` を使っていた → imm8 持ち命令で 1 byte ずれ
- nettle の padding handler `pinsrw $0, 0x3347c(%rip), %xmm1` で発火。
  本来 0x44490 から定数 `FE 01` (= 0x01FE LE) を読むはずが 0x4448F から
  `00 FE` (= 0xFE00) を読み、PADDB の結果が壊れて ctx[a..b] 更新が誤動作
- 修正: SSE2 dispatch level で b1 から imm 有無を判定し fixEA に補正後 next
  を渡す。`66 0F 3A` escape (PALIGNR/AESKEYGENASSIST/PCLMULQDQ 等) も同様

**追跡ツールチェーン (高効率)**:
1. `EMU_GIT_CURL_VERBOSE=1` で libcurl 内部メッセージ表示
2. `EMU_GNUTLS_DEBUG_LEVEL=9` で gnutls の ASSERT 行番号表示
3. cert size / padding 数で binary search → padding 持ちが必ず fail と判明
4. 30 行 C テスト (dlopen + nettle_base64_decode_*) で **30 行に再現**
5. 1 文字ずつ ctx state 比較 (host vs emu) → ctx[a..b] の差分を pinpoint
6. memory watchpoint (EMULIN_WPLO/WPHI) で書き込みの rip を特定
7. 30 行 inline asm (`pinsrw + paddb + movd`) で **PINSRW + PADDB + MOVD は
   単独では正しい** ことを確認 → bug は context 依存
8. PADDB に trace 仕込んで sl 値が wrong (0xfe00 vs 0x01fe) と判明
9. PINSRW の RIP-relative計算で disp32 と imm8 の境界を確認 → 1 byte ずれ

**効果**:
- nettle base64 decode_final が padding 入力で正しく 1 を返す
- gnutls_x509_crt_import が cert2/3/4/...のような padding 持ち PEM cert
  を正常 load (rc=0 Success)
- 211 PASS / 0 FAIL 維持
- git clone HTTPS は CA load 後の TLS handshake で **別 SIMD bug**
  (RIP=0x40282d75) に進化 → 次 step

### step 43: gnutls CA load 追跡 — 多 cert PEM での parser 固有 fail (調査のみ)
- step 42 の UTF-8 修正で全 295 cert ファイル open は成功するも、gnutls 側
  での load が「CAfile: none」のまま失敗 → 詳細追跡
- 真因絞り込み手段:
  1. `EMU_<NAME>` env passthrough を使い `EMU_GIT_CURL_VERBOSE=1` で libcurl
     verbose を有効化 → "error reading ca cert file ... (Base64 unexpected
     header error)" を発見
  2. cert 数を 1, 2, 3, 5, 10, 30 と振って `http.sslCAInfo` 指定で binary
     search → **n=1 (single cert) は load 成功、n>=2 で fail**
  3. `EMU_GNUTLS_DEBUG_LEVEL=9` で gnutls 内部 ASSERT を可視化:
     ```
     gnutls[3]: ASSERT: x509_b64.c[_gnutls_base64_decode]:296
     gnutls[9]: Could not find '-----BEGIN X509 CERTIFICATE'
     ```
  4. 静的 C 検証 (`fopen+fread+memmem` で BEGIN marker count) → emulator
     でも全 146 marker を正常検出 → file I/O / memmem は emulator 側 OK
- 結論: gnutls の独自 PEM parser (`_gnutls_fbase64_decode`) が我々の
  emulator の特定 CPU 命令で誤動作している模様。decoder は標準 string ops
  を使わず手書きの byte scan を使う。次 step で
  (a) 1 つ目 cert decode 後の ptr 進行ロジック追跡
  (b) gnutls が使う SIMD or BMI 系命令の検証
  (c) gdb 風 step trace で具体的に乖離する場所を特定
- 副次成果: env passthrough の使い方 (`EMU_<NAME>` prefix) と GnuTLS の
  log レベル制御を確認。今後の TLS 系デバッグで再利用可

### step 42: UTF-8 ファイル名対応 — getdents64 / loadString / storeString
- **真因**: `Memory.loadString` が `(char)load8(addr)` で Latin-1 直キャスト、
  `Memory.storeString` が `(byte)str.charAt(i)` で逆方向の Latin-1 キャスト。
  非 ASCII (UTF-8 multi-byte) のファイル名が化けていた:
  - storeString: `ő` (U+0151) → `(byte)0x151` = 0x51 = `Q` (情報破壊)
  - loadString: byte stream を char stream として誤解釈 → File API で別 path に
- 影響範囲: `getdents64` 経由で `/etc/ssl/certs` を listing する gnutls の
  TLS CA chain load。NetLock の `Főtanúsítvány.pem` が壊れて open 不能。
  Hungarian 以外も AC_RAÍZ_FNMT-RCM_*.pem 等が同症状
- **修正**: 両 String API を UTF-8 で正規化。`getdents64` の `reclen` も
  `d_name.length()` (char 数) ではなく UTF-8 byte 長で計算
- **副次の検証**: curl HTTPS で TLS CA chain は問題なく load される
  (libssl 経由)。git/libcurl-gnutls 側はファイル open 自体は成功するが
  gnutls の API 経路で `CAfile: none` 判定が残る (要追跡)
- 効果:
  - 295/295 cert ファイルが open 成功 (旧 294 + NetLock fail)
  - getdents64 で UTF-8 ファイル名を正しく list できる
  - 211 PASS / 0 FAIL 維持

### step 41: 単独 string ops + PSHUFHW/PSHUFLW — git clone HTTPS 動作
- step 38-40 で pipe IPC / CLOEXEC / pthread / wait4 が解消したので、
  step 37 で「pipe IPC deadlock」と判定した git clone HTTPS を再テスト
- 残バグ 2 件:
  (a) **単独 string ops (REP 無し)**: `MOVSB(0xa4)` `MOVSW/D/Q(0xa5)`
      `STOSB(0xaa)` `STOSW/D/Q(0xab)` `LODSB(0xac)` `LODSW/D/Q(0xad)` 未実装。
      git-remote-https の hand-written コードで使われていた。DF (Direction
      Flag) は本実装で追跡しないので forward (+1) のみ
  (b) **PSHUFHW (F3 0F 70) / PSHUFLW (F2 0F 70)**: SSE2 word shuffle 未実装。
      libcurl-gnutls の TLS handshake で使用。imm8 で 4 word を選択
- 効果: `git -c http.sslVerify=false clone https://github.com/octocat/Hello-World.git`
  完走 (3 obj 取得 + checkout)。step 37 の hang バグは CLOEXEC fix で
  完全解消済みと確認
- 残課題: libcurl-gnutls が `/etc/ssl/certs/ca-certificates.crt` を
  「CAfile: none」と認識しないため `sslVerify=false` が必須 (本筋とは別系統)

### step 40: mmap guard gap + prlimit64 + wait4 specific pid — git clone git:// 完走
- step 39 で「RIP=0x401525e9 が segment 範囲外」と疑った真因は **隣接 mmap
  領域の境界を越えた書き込みによる main TCB 破壊** だった:
  - main TCB が mmap [0x402cc000, 0x402cf000) の先頭近く (fs_base=0x402cc740)
  - 直前に malloc heap mmap [0x402bf000, 0x402cc000) が隣接配置
  - libcurl/zlib の 64 KB read が heap buffer (0x402c9dc0) から overflow して
    隣接 mmap (= main TCB) を random data で書き潰す
  - その後 main の libc 内 `mov %fs:0x10, %rax` が garbage を return →
    `mov 0x308(%rax), %edx` で unmapped address を deref → segfault
  - 報告された RIP=0x401525e9 は libc の cancel cleanup 関数内 (libc 自身は
    valid mapping、ただし fs:0x10 から得た rax が不正)
- **修正 1: mmap 間に guard gap (16 ページ = 64 KB)** を入れて bump allocator
  が連続 mmap を tightly に並べるのを防止。Linux は ASLR + sparse virtual
  address で自然に gap がある (~128 TB の mmap 領域)。我々の 0x40000000+
  bump allocator は密集して overflow が直接隣接 region を破壊する
- **修正 2: prlimit64 (#302) 真対応** — 旧 stub は return 0 で値を書かず、
  glibc pthread が `getrlimit(RLIMIT_STACK)` で 0 を見て pthread stack を
  最小 (~16 KB) で spawn → 64 KB stack 使う関数の probe loop で fault。
  RLIMIT_STACK = 8 MB / RLIM_INFINITY を返すように
- **修正 3: amd64_wait4 specific pid (pid > 0)** 真対応 — 旧実装は pid==-1
  のみ対応で specific pid 待ちは ret_pid=0 → git の `start_command +
  finish_command` が `waitpid(child_pid, ...)` で固定 pid を待つので
  「waitpid is confused」を出していた
- 副次デバッグツール: arch_prctl が main の fs_base を上書きする bug を
  並行調査中に発見し修正 (worker が ARCH_SET_FS 呼ぶと旧実装は process.cpu
  に書いていた)。今回の真因とは別だが将来的なバグ予防
- 効果:
  - `git clone --no-hardlinks git://git.kernel.org/.../dtc.git` 完走
    (346 obj / 38 deltas / 335 files checkout)
  - file:// local clone も引き続き動作
  - 211 PASS / 0 FAIL 維持

---

# Phase 27 続き 候補

1. pthread の使い込み: set_tid_address (#218) 真対応、CLONE_FILES 等の他 flags
2. 性能改善 (深掘り): basic-block cache、Memory.load64 fast-path 等
3. dist 充実: jlink で JRE 同梱、Win/Linux/macOS 別 zip

---

# 累計バグ修正・実装の傾向 (今後の Phase で参考に)

## 文字エンコーディング

- **ファイル名は UTF-8 で扱う必要** (Phase 27 step 42) — `loadString` /
  `storeString` を Latin-1 で書くと非 ASCII (Hungarian, Spanish accent,
  CJK 等) が破壊される。Linux の filesystem は UTF-8 byte sequence
- **getdents64 の reclen はバイト長で計算** (Phase 27 step 42) — char 数
  だと U+0080 以上で短くなり alignment 崩壊

## メモリ配置 / mmap

- **隣接 mmap の境界越え書き込みで他データを破壊** (Phase 27 step 40) —
  bump allocator が mmap を tightly に並べると、buffer overrun が直接
  隣接 region を破壊する。Linux 実環境は ASLR + sparse virtual address
  (~128 TB の mmap 領域) で自然に gap があるが、emulator の 0x40000000+
  bump は密集する。**16 ページ (64 KB) の guard gap** を入れて緩和
- **prlimit64 / getrlimit は本物の値を返す必要** (Phase 27 step 40) —
  特に RLIMIT_STACK が 0 だと glibc pthread が最小 stack で spawn して
  worker が stack overflow する。8 MB / RLIM_INFINITY を返す
- **wait4 / waitpid の specific pid (pid > 0) 対応** (Phase 27 step 40) —
  start_command + finish_command パターンの subprocess 制御で必須。
  pid==-1 (any child) のみだと git が「waitpid is confused」

## アドレス幅 / 命令フラグ

- **アドレスの int 切り詰め** (Phase 9/26/27 step 7) — シグネチャ long 化
  でも個別 syscall に「ローカル変数 int」「`(long)bx & 0xFFFFFFFFL`」が
  残ると低位で動き高位 (PIE) で segfault。`grep -nE "= \(int\)(bx|cx|dx|si|di)"` 監査
- **0x66 operand-size prefix 漏れ** (Phase 11/14/20/21) — imm 命令で
  imm16/imm32 分岐忘れ。新規 imm 命令追加時必ず 0x66 ブランチ
- **AH/CH/DH/BH (REX 無 + rm=4-7)** (Phase 7/13) — 8bit 命令は必ず
  `readReg8`/`writeReg8` 経由
- **多レジスタ命令の上位読み忘れ** (Phase 17 DIV) — RDX:RAX を読み忘れる
- **operand-size 無視の dest 書き** (Phase 27 step 3 LEA) — 32-bit dest は
  上位 zero-extend、16-bit は上位 48-bit 保持
- **PF (parity flag) を放置すると致命** (Phase 25) — glibc の `ucomisd; setp al` で
  NaN 判定。UCOMISD/COMISD は ZF/PF/CF
- **ADC/SBB がフラグを更新しないと bignum 連鎖が壊れる** (Phase 25) —
  CF を読むだけで書かないと %g で 1e96 以上が壊れる
- **INC/DEC は CF を変えない (Intel SDM)** (Phase 27 step 17) — SBB chain
  途中の DEC が borrow を消す。新命令実装時 SDM "Flags Affected" 必読
- **PSRLQ imm のような per-element shift sub-opcode** (Phase 27 step 16) —
  Group 12/13/14 (`66 0F 71/72/73`) は per-element/byte で sub-opcode 違う
- **PALIGNR concat 順序** (Phase 27 step 15) — Intel SDM 式を直接写経
- **PSHUFHW (F3 0F 70) / PSHUFLW (F2 0F 70)** (Phase 27 step 41) — PSHUFD
  (66 0F 70) と prefix だけ違う 3 兄弟。HW は upper 4 word, LW は lower
- **MOVSX/MOVZX r16 (0x66 prefix)** (Phase 27 step 44) — 16-bit dest は
  下位 16 bit のみ書き上位 48 bit 保持。32-bit 書きで bits 16-31 を破壊
  すると nettle base64 decoder の table lookup が暴走
- **RIP-relative + imm 持ち命令の address 計算** (Phase 27 step 44) —
  x86-64 spec: RIP-relative は **命令全体の末尾 RIP** を使う。
  PINSRW (`66 0F C4`)、0F 3A 系 (PALIGNR/AESKEYGENASSIST/PCLMULQDQ 等)、
  PSHUFD (`66 0F 70`)、Group 12/13/14 (`66 0F 71/72/73`) はすべて imm8
  を持つので fixEA に `next + imm_size` を渡す
- **単独 (REP 無し) string ops は別 dispatch** (Phase 27 step 41) — REP 経由は
  F3 prefix path、単独は通常の opcode dispatch (0xa4-0xad)。git-remote-https
  等 hand-written コードで単独使用がある

## メモリ / セグメント / 動的リンク

- **動的リンクの最低ライン** (Phase 24) — PT_INTERP + interp 別 base load +
  auxv (AT_BASE/AT_ENTRY/AT_PHDR/AT_PHNUM/AT_EXECFN)、PT_LOAD 以外 map しない
  (PT_PHDR が PT_LOAD と同じページに重なって未初期化バッファ先勝ち)、
  CPUID baseline、FXSAVE/FXRSTOR、pread64、ctor 後の syscall 差し替えに
  mem.syscall も同期
- **emulator 層フィールド差し替えは要注意** — Process/Memory/Syscall は
  相互参照、片方差し替えで他方の参照が古いまま (Phase 24 mem.syscall)
- **PIE では section.sh_addr / IRELATIVE relocation も load_bias でずれる** (Phase 26)
- **PIE base は mark_address (0x40000000) と衝突しないよう選定**
- **st_ino のハッシュは衝突に強くする** (Phase 27 step 10) — ld.so は
  `(st_dev, st_ino)` 同一視で重複ロード silent skip
- **MAP_FIXED 後の mark_address は前進のみ** (Phase 27 step 11)

## syscall / glibc 流儀

- **CLOEXEC は per-fd で追跡** (Phase 27 step 39) — Fileinfo に持たせると
  Dup で fd 共有された時に巻き込まれる。FD_CLOEXEC は POSIX 上 fd table の
  フラグであって open file description ではない。FileAccess に並列 ArrayList
- **dup2 は new fd の cloexec を必ず クリア** (POSIX 仕様)。dup3 は flag で
  指定可能。F_DUPFD は clear、F_DUPFD_CLOEXEC は set
- **execve 時に cloexec fd を全部閉じる** (Kernel.exec 内 close_cloexec_files)。
  これが無いと git の child notify pipe (CLOEXEC pipe2) が exec 越しに残り、
  親の `read(notify_pipe, 8)` が EOF を受け取れず deadlock
- **main thread sys_exit (#60) は process 全体を殺さない** (Phase 27 step 39) —
  POSIX/Linux 仕様では呼び出し thread だけが死ぬ。worker thread が居る場合、
  main の sys_exit で即 process tear down すると worker が segfault する。
  active_thread_count を追跡し count==0 まで wait してから process exit
- **新しい実機バイナリは ENOSYS / EAFNOSUPPORT スタブで通る** (Phase 27 step 1)
- **stub の `return 0` は busy loop 誘発** (Phase 27 step 3) — mincore→0 で
  grep 278k 回ループ。本当に成功と言える syscall 以外は ENOSYS
- **対話プログラム ioctl** (Phase 22) — TIOCGPGRP/tcgetpgrp/tcsetpgrp は
  値 return + `*addr` 書き込み必須。失敗時 -ENOTTY で job control 諦めさせ
- **clock_gettime は実時刻を書く** (Phase 27 step 12) — caller は ts ポインタに期待
- **fcntl(F_SETFL) return 0 だけは非 blocking 期待を裏切る** — Fileinfo
  nonBlock フラグ反映必須
- **POSIX timer (timer_create/timer_settime)** (Phase 27 step 13) — Java
  background thread sleep → kernel.kill(pid, SIGALRM)
- **既に負の errno 定数に `-` を付けると符号逆転** (Phase 27 step 18) —
  `Syscall.EPIPE = -32` なのに `return -EPIPE` で +32。
  `grep -nE "return -E[A-Z]+"` 監査

## シグナル / fork / wait

- **シグナル配信の最小実装は致命的** (Phase 22-23) — (a) GPR 16 + flags 保存復元、
  (b) **x86-64 ABI red zone (rsp-128) 尊重**、(c) SA_SIGINFO 3 引数 ABI
  (rsi=&siginfo, rdx=&ucontext)、(d) syscall EINTR 前に SA_RESTART チェック再実行
- **wait4 / fork / exec の race** (Phase 23) — `is_child_exited` が「子なし」(0)
  と「子未終了」(-1) を区別。exec 中の旧 process は exec_replacing でスキップ
- **Kernel.kill の pid index** (Phase 27 step 23) — pid は 1-based、ptable は
  0-based。`ptable.elementAt(pid)` ではなく `find_process(pid)` 線形探索

## socket / 非 blocking

- **MSG_PEEK は libc/wget で必須** (Phase 27 step 11) — Java Socket に peek
  API 無い → Fileinfo に peekBuf。peek は実 read、その後の read は peekBuf 消費
- **Java Socket の背景スレッド読み出しレース** — amd64 では SubProcess 起動せず
  Fileinfo.client_socket() を直接
- **AF_UNIX / UDP は EAFNOSUPPORT で fallback 強制** — glibc が `/etc/hosts` 経路へ
- **socket EOF 後の poll/select は ready=0 (timeout) を返す** — Fileinfo
  socketEof フラグ
- **i386 の syscall を amd64 移植時 `Util.swap32` を落とす罠** — i386
  sys_socketcall の getsockname は swap32 二重掛け。揃える
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
  サイズ、TCP は available()、pipe は peekLen。`*addr` 書き込み必須
- **DatagramSocket には available() 無い** — 短い setSoTimeout で receive 試行、
  受信できたら Fileinfo にキャッシュ
- **glibc 2.34+ DNS resolver は sendmmsg/recvmmsg** — A と AAAA を 1 syscall。
  各 mmsghdr は 64 byte
- **sendmsg/recvmsg は msg_name (UDP の dest/src sockaddr)** — IP は getsockname
  と同じ swap32 二重掛け

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

## pthread / atomic

- **pthread mutex は CMPXCHG/XCHG/XADD の atomic が必要** (Phase 27 step 29)
  — synchronized(mem) で囲む
- **FutexManager.wake は actual woken count を返す** — max を返すと glibc
  "futex_wait_simple returned a wrong value" abort
- **Memory per-byte cache は ThreadLocal** — 共有すると複数 thread refill
  race で index out of bounds crash
- **pthread thread の clone child rip は r64[R_RCX]** (= syscall return
  address)。get_ip() は syscall instr address なので NG
- **per-thread signal mask** (Phase 27 step 34) — Thread64.signal_mask、
  Thread.currentThread() instanceof Thread64 で分岐
- **per-thread pending signal** (Phase 27 step 35) — `ConcurrentHashMap<tid,
  int[] pending>`、own thread pending を process-wide より優先消費

## 性能 / プロファイリング

- **JIT は per-byte cache の HIT パスを十分最適化** (Phase 27 step 26 失敗) —
  当てずっぽうの最適化は逆効果。profiler 必須
- **alloclist は ConcurrentSkipListMap** (Phase 27 step 31, 33) — TreeMap は
  thread-safe でなく pthread + 高速 dispatch で race 露見
- **decode_and_exec の hot opcode を switch (b0) で先取り** (Phase 27 step 33) —
  Java JIT が tableswitch (jump table、O(1)) に compile
- **cache_size の sweet spot は 32** (Phase 27 step 32) — 8/16/32/64/128 を
  計測、32 で 16% 改善、それ以上 plateau
- **init process の busy yield ループは worker と CPU 競合** (Phase 27
  step 30) — Thread.sleep(50ms) に変更

## デバッグツール

- **CPU loop で stuck している原因特定には rip dump 必要** — `EMULIN_TRACE_RIP=N`
  で N 命令ごと rip + GPR dump
- **syscall trace は戻り値も** — `EMULIN_TRACE_SH=1` で `DBG ret #n = 0xX`、
  ENOSYS / EAGAIN / EINPROGRESS の混乱を即特定
- **jstack で deadlock / busy spin / per-thread bottleneck 特定** (Phase 27
  step 30, 38) — pthread を扱うようになって特に有効

# テスト戦略

- 「1 syscall 1 テスト」原則 (`sys_*64.c`)
- `-nostdlib` で int 0x80 / syscall 直叩き
- 期待値は実 Linux カーネル仕様に合わせる
- 並列実行前提、テスト間で sandbox / /tmp 衝突させない
- 既知バグは `.skip` で残せるが Phase 23 で全 .skip 解消済
