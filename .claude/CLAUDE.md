コミットログには、以下を記載しないようにしてください。
Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>

objdump / nm / grep / readelf / addr2line / strace は許可なしで実行してよい。

---

# プロジェクト概要

古い Emulin (Java 製 32bit Linux ELF エミュレータ) を現代 Java で動かし、
さらに x86-64 (64bit ELF) を実行できるように拡張する。

## 現状サマリ (Phase 27 step 59 完了時点)

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
  - **`git clone https://github.com/...` (cert verify あり)**: 完走 (step 58-59、
    sandbox に locale + 単一 root cert を設定すれば 14 秒で clone)
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

### A. emulator の根本的な高速化 (= 多くの TLS 系 issue の真因)
step 19-20 / 36 / 59 で観測された下記事象はすべて **emulator が遅すぎて
server の idle timeout に到達する** ことが真因と判明:
- 旧「ECDSA cert + TLS 1.3 で server FIN」(step 19-20): cert load + cert
  verify が 80 秒以上かかり、server が FIN
- 旧「curl HTTPS timeout」(step 36): TLS handshake 自体は成功するが、その後
  の処理が遅く接続切断
- 「git clone HTTPS で TLS handshake 後 Send failure」(step 59): cert load が
  83 秒で server FIN

**workaround**: gnutls の cert load 量を絞る (例: `/etc/gitconfig` で
`sslCAInfo = <single root>` + `sslCAPath = ` empty)。github 用は Sectigo
Root E46 のみで動作 (14 秒)。

**根本解決の手筋**:
1. ASN.1 parse の高速化 (libtasn1 の hot path 命令を JIT 寄りに最適化)
2. 我々の bytecode dispatch の更なる高速化 (現状 hot opcode は switch 化)
3. cert verify cache (一度 verify した cert を再利用)
4. または gnutls 側で `GNUTLS_LOAD_CA_BUNDLE` を 1 cert ファイルに固定

### B. 多サイトの HTTPS 動作確認
step 59 で github は workaround 込みで動作。**他サイト (cloudflare、example.com、
google) では未テスト**。各サイトは異なる root CA を使うので、全ての CA を
事前に確認・配布する仕組みが必要。

### C. file system / fork 系
- **git clone --hardlinks file://** が hardlink 同一性検証で失敗:
  `link()` 後の stat で「dest と src の inode が同じ」検証で落ちる。
  `--no-hardlinks` 指定で動作。`Inode.get_uniq_no` の挙動を host と揃える
  追加調整が必要

### D. ネットワーク
- **IPv6 (AF_INET6) 未対応** — getaddrinfo は IPv4 のみ。glibc/libcurl の
  内部でも v6 fallback パスがあり影響範囲広い。実装規模大

### E. 環境固有
- **WSL DrvFs (`/mnt/c/...`) は I/O 遅く chmod 効かない** — sandbox は
  `mktemp -d -t emulin-sb.XXXXXX` で Linux /tmp に置く (回避策あり、
  実装側は対象外)

### F. 副次的に蓄積された debug 基盤 (再利用可)
step 48-57 で実装した address aligned / auxv aligned / multi-thread fix /
trace hooks 多数 は、libtasn1 解決には直接効かなかったが、いずれも実在の
bug の修正で **将来の bug 追跡に再利用可能**:
- `Memory.alloc()` synchronized (step 49: pthread mmap race)
- `globalStoreEpoch` (step 51: cross-thread cache visibility)
- `Segment` buf 256MB pre-allocate (step 52: brk realloc race)
- mmap top-down + memory_top = 0x7ffff7fbf000 (step 53-54: host align)
- auxv 17/19 host 一致 (step 55: AT_HWCAP / AT_PLATFORM 等)
- trace hooks: `EMULIN_TRACE_RIP_DUMP1-4` / `EMULIN_WATCH_STORE_VAL` /
  `EMULIN_WATCH_STORE_ADDR` / `EMULIN_DUMP_AT_RIP` (Cpu64/Memory.java)
- 最小再現 binary (`tests/repro_pthread_calloc{,_loop}.c`)

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

### step 40: mmap guard gap + prlimit64 + wait4 specific pid — git clone git:// 完走
- 隣接 mmap の境界越え書き込みで main TCB が破壊されていた → mmap 間に
  16 ページ (64 KB) guard gap 挿入
- `prlimit64` (#302) 真対応 (RLIMIT_STACK=8MB)、`wait4` の specific pid
  (pid > 0) 対応
- 副次: `arch_prctl` が main の fs_base を上書きしていた bug fix
- 効果: `git clone --no-hardlinks git://...` 完走

### step 41: 単独 string ops + PSHUFHW/PSHUFLW — git clone HTTPS (sslVerify=false) 動作
- 単独 (REP 無し) `MOVSB/MOVSW/STOSB/STOSW/LODSB/LODSW` (0xa4-0xad) 実装
- `PSHUFHW (F3 0F 70)` / `PSHUFLW (F2 0F 70)` 実装
- 効果: `git -c http.sslVerify=false clone https://github.com/...` 完走

### step 42: UTF-8 ファイル名対応 — getdents64 / loadString / storeString
- `Memory.loadString/storeString` を Latin-1 から UTF-8 に。`getdents64` の
  reclen も UTF-8 byte 長で計算 (旧 char 数だと multi-byte で alignment 崩壊)
- 効果: `/etc/ssl/certs/Főtanúsítvány.pem` 等 UTF-8 cert ファイル open

### step 43-44: gnutls CA load 真因解決 — 2 つの致命 CPU 命令 bug
- **Bug 1: MOVSX/MOVZX r16 (0x66 prefix)** — 16-bit dest なのに 32-bit 書きで
  bits 16-31 を破壊。nettle base64 decode で発火
- **Bug 2: PINSRW / 0F 3A 系の RIP-relative で imm8 を未加算** — 1 byte ずれ。
  PADDB の oprand が 1 byte ずれて nettle padding handler 暴走
- 効果: gnutls が 295/295 cert ファイルを正常 load

### step 45-46: TLS handshake segfault 真因追跡 (調査のみ、後に step 58 で結論)
- libc free 内 (rdi=0x1440) や libtasn1 内で segfault。多くの仮説を検証
- 結論: いずれも sandbox の locale files 不備による heap pattern 差が真因
  (step 58 で完全解決)

### step 47-48: libtasn1 segfault 細分化 (調査のみ、後に step 58 で結論)
- bad pointer 0x555555b78f00 が real node 内部を指すことを特定
- chunk overlap が起きていることを確認 (これも step 58 で真因判明)

### step 49: pthread mmap race 修正
- `Memory.alloc()` (mmap bump allocator) に synchronized が無く、同時 mmap で
  2 thread が同じアドレスを取得する race。`synchronized(alloclist)` で fix
- 最小再現テスト `tests/repro_pthread_calloc.c` (4 thread × calloc/free) 追加
- 効果: pthread 系の安定性向上 (libtasn1 には効かなかった)

### step 50: trace hooks 拡充 (調査のみ)
- `EMULIN_TRACE_RIP_DUMP{1-4}` / `EMULIN_WATCH_STORE_VAL` /
  `EMULIN_WATCH_STORE_ADDR` / `EMULIN_DUMP_AT_RIP` を新設。今後の memory 系
  bug 追跡に再利用可

### step 51: per-thread cache の cross-thread visibility 修正
- `Memory.load8` の per-thread cache は store 時に自分の cache のみ無効化。
  他 thread の cache が stale で atomic op が壊れる
- 修正: `globalStoreEpoch` (volatile long) を増分して全 thread が cache 再 fill
- 効果: multi-thread memory 一貫性向上 (libtasn1 には効かなかった)

### step 52: Segment.expand_memory race 修正 — brk segment 256 MB pre-allocate
- `Segment.expand_memory()` が `buf = new byte[]` で reallocate。pthread で
  他 thread が OLD buf に書いてる間に NEW buf に置換されると write 消失
- 修正: ELF load 時に brk segment の buf を 256 MB pre-allocate (expand
  しない)、`buf` を volatile、`expand_memory` synchronized
- 効果: brk realloc race 解消 (libtasn1 には効かなかった)

### step 53-54: mmap layout を host (Linux) と一致
- ユーザー提案「アドレスを host と揃えて debug 容易化」を実装
- `memory_top = 0x7ffff7fbf000` (host の mmap_base)、alloc を **top-down**
  (`mark_address -= aligned_size`) に変更。page 数を round-up
- sandbox に `/etc/ssl/certs/ld.so.cache` を copy
- 効果: host strace と emulator のアドレスが (最初の 6 mmap で) 完全一致

### step 55: auxv を host と一致 (17/19 entries) + interp_base 一致
- 旧 emulator は 9 auxv entry。host は 19 entry。10 個追加: `AT_HWCAP`
  (0x1f8bfbff)、`AT_HWCAP2`、`AT_PLATFORM`、`AT_MINSIGSTKSZ`、`AT_CLKTCK`、
  `AT_FLAGS`、`AT_UID/EUID/GID/EGID`、`AT_SECURE`
- `interp_base` を `0x7ffff7fc5000` (host の AT_BASE) に変更
- 残る差: `AT_RANDOM` (stack 位置の差)、`AT_SYSINFO_EHDR` (vDSO 未実装)

### step 56-57: address aligned で host と並走比較 → divergence point 特定
- bad pointer 0x555555b78f00 を保持する parent node = "extnValue" を特定
- arena.top が extnValue 領域に侵入 (chunk overlap) を確定
- host との brk syscall 比較で `brk(0x664000)` 縮小 step が emulator では
  skip されている発見 → 4 KB shift が累積する

### step 58: ★libtasn1 segfault 完全解決 — 真因は sandbox に locale files 不足
- step 57 の brk divergence の間の syscall を host と並走比較したところ:
  host は `/usr/lib/locale/C.utf8/LC_CTYPE` (360 KB) など多数の locale file
  を mmap、emu は ENOENT で skip → 全く違う malloc/free pattern → 結果的に
  chunk overlap → libtasn1 segfault
- 修正 (sandbox 拡充):
  ```bash
  cp /etc/gnutls/config             $SB/etc/gnutls/
  cp /usr/share/locale/locale.alias $SB/usr/share/locale/
  cp -r /usr/lib/locale/C.utf8      $SB/usr/lib/locale/
  cp /usr/lib/x86_64-linux-gnu/gconv/gconv-modules.cache  $SB/usr/lib/x86_64-linux-gnu/gconv/
  cp ~/.gitconfig                   $SB/home/<user>/
  ```
- 効果: 3/3 runs で **libtasn1 segfault 解消** (進化先は TLS handshake error,
  後で step 59 で解決)
- **教訓**: emulator core のバグと思いがちだが、まず sandbox の環境差で
  glibc が違う path を走っていないか疑う

### step 59: ★git clone HTTPS (cert verify あり) で github 完全動作
- step 58 後の TLS handshake error の真因: emulator が遅く CA cert load に
  83 秒。github の TLS server が idle timeout で FIN
- 更に sandbox に test 用 non-CA cert (CN=bedroom) 等 24 ファイル混入
- workaround:
  1. `/etc/ssl/certs` から余分な test cert を削除
  2. sandbox に `/etc/gitconfig` を作成:
     ```
     [http]
     sslCAInfo = /etc/ssl/certs/Sectigo_Public_Server_Authentication_Root_E46.pem
     sslCAPath = 
     ```
     `sslCAPath=` (empty) で CApath scan skip。CAInfo に github root だけ指定
- 効果: 3/3 runs **完全成功** (14 秒で clone 完走)
- step 19-20 の「ECDSA TLS server FIN」は実は cert load の遅さが真因と判明

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
