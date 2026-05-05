コミットログには、以下を記載しないようにしてください。
Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>

objdump / nm / grep / readelf / addr2line / strace は許可なしで実行してよい。

---

# プロジェクト概要

古い Emulin (Java 製 32bit Linux ELF エミュレータ) を現代 Java で動かし、
さらに x86-64 (64bit ELF) を実行できるように拡張する。

## 現状サマリ (Phase 27 step 34 完了時点)

- 現代 Java (OpenJDK 21+) / Maven、**pure Java only** (Phase 22 で JNI 撤去)
- 32bit ELF (i386) と 64bit ELF (x86-64) の両方を実行可能
- glibc 静的リンク + 動的リンク (gcc -no-pie) + **PIE** (ET_DYN を
  0x555555554000 にロード) すべて動作
- 動的リンク + 複雑な libc 機能が動作 — printf 各種 (NaN/Inf/bignum %g/%e/%a)、
  malloc/free、signal/raise (tgkill)、setjmp/longjmp、fopen/fgets、strftime、
  sscanf、qsort、getopt、POSIX regex、dlopen/dlsym、wchar、C++ STL、
  fork+pipe IPC、zlib、socketpair (双方向)、mmap of file、GCC ネスト関数
- **実機 GNU coreutils 24 種類 + bash** (ls/cat/wc/grep/sed/sort/awk/find/...)
- **実機 Python 3.12 + OpenSSL 3.0** (print/import/json/hashlib/openssl)
- **AES-NI / PCLMULQDQ 命令を完全実装** (FIPS-197 host 一致)
- **wget HTTP / curl HTTP / wget DNS / wget HTTPS が動作**
  (AF_INET TCP+UDP socket、sendmmsg/recvmmsg、FIONREAD、POSIX timer、
  AES-NI、PSRLQ imm、INC/DEC CF 保存、EPIPE タイポ修正)
- **wget HTTPS で実 HTML 取得**: iana (RSA cert + TLS 1.3) は確実に動作。
  ECDSA cert の server (Cloudflare 系) は handshake 後 server FIN — step 20 課題
- busybox (静的リンク) で **88 applet** が動作。**対話 busybox ash** が
  プロンプト + 行入力で動作 (Phase 22)
- **JLine 3 採用** で Linux/macOS/Windows 共通の raw mode / Ctrl-C / SIGWINCH
- **ディストリビューション zip** (約 1.7 MB) を解凍して `emulin.sh` /
  `emulin.bat` 一発で busybox ash が起動 (Windows cmd.exe からも完全対応)
- シグナル基盤: SIGCHLD 自動配信、SA_RESTART で syscall 自動再開、
  SA_SIGINFO で 3 引数ハンドラ対応、レジスタ完全保存 + red zone 尊重、
  alarm/setitimer (ITIMER_REAL) → SIGALRM 自動配信、rt_sigprocmask、
  sigaction.sa_mask + SA_NODEFER 完全対応、**per-thread signal mask**
  (pthread_sigmask 互換、step 34)
- パイプライン・fork/exec/wait4 (exit code 伝搬・ECHILD 適切返却・exec
  race 解消)・getdents64・連続リダイレクト・O_APPEND 対応済み
- **実機 git** が emulator 内で動作 (init / add / commit / status / log /
  diff)。Phase 27 step 25 で sys_access バグ修正 + /dev/* router 修正 +
  link(#86) 実装で完全な workflow が成立。HTTPS が必要な clone/push は不可
- **pthread が動作**: pthread_create + worker + pthread_join + 共有
  memory + **pthread_mutex 競合下** で 4 worker × 1000 iter の counter++
  が正しく 4000 になる (Phase 27 step 28-29、Thread64 + FutexManager +
  CMPXCHG/XCHG/XADD の synchronized RMW)。**curl AsynchDNS (HTTP) も動作**
  (curl http://example.com/ で完全な HTML 取得)
- **回帰テスト 211 本 PASS / 0 FAIL / 0 SKIP**、xargs -P / wait -n で
  並列化、5 連続安定。`run-fast.sh` (real-* / dist 抜き) で 27 秒、
  `run-network.sh` (network のみ filter) で 3 分、フル `run-all.sh` 約 1m41s
  (step 30-33c で baseline 3m51s から **56% 短縮**)。
- **wget HTTPS (iana)**: 50s → **10s** (5x 高速化、step 30-33c の総合効果)

## 既知の未解決課題

- **ECDSA cert + TLS 1.3 で server が Client Finished 直後に FIN**
  (Phase 27 step 19 + step 20 調査) — example.com / cloudflare.com /
  google.com が AppData 送信前に server FIN される。
  - **判明したこと (step 20)**:
    - ClientHello は host と byte-identical (385 byte、cipher suite 75 件
      とも host wget と一致。step 19 の "400 vs 308" は計測誤りだった)
    - server の encrypted handshake (3825-3827 byte) は decrypt 成功 →
      cert chain / CertificateVerify / server Finished まで全部 parse OK
    - 我々の CCS + Client Finished (80 byte) を write 後、server が
      即 FIN (read returns 0)、Alert なし
    - iana (RSA cert + TLS 1.3 + AES_256_GCM_SHA384) は動く →
      ★ **cipher 共通 / cert type だけ違う場合に発火** = ECDSA 固有
    - ECDSA P-256 SHA256 verify (`openssl dgst -verify`) は単体で動作
    - cipher を AES_128_GCM_SHA256 に絞っても同じ症状
  - **次の手筋 (step 20+)**: tcpdump で host vs emu の Client Finished
    暗号文を bytes 単位で比較 / Wireshark で server 応答を decrypt /
    `c_handshake_traffic_secret` の HKDF-Expand-Label 出力を host と diff /
    custom python TLS client で minimal 再現
- **IPv6 (AF_INET6) は未対応** — getaddrinfo は IPv4 のみ返る経路で動作
- **WSL DrvFs (`/mnt/c/...`) は I/O 遅く chmod も効かない** — テストの
  sandbox は `mktemp -d -t emulin-sb.XXXXXX` で Linux 側 /tmp に置く運用

## 動作テスト基盤

- `tests/` に i386/x86-64 静的リンク ELF (-nostdlib で int 0x80 / syscall
  直叩き) と回帰ハーネス
- `make -C tests/binaries`: テストバイナリビルド
- `tests/scripts/run-all.sh`: 全テスト実行 (200 ケース、~3m45s)。**並列化済**:
  binary は xargs -P で nproc 並列、ext scripts は & + wait で 5 本同時、
  applet-survey は内部で wait -n 制御の 6 並列。各テストは Linux 側 /tmp
  上の per-test sandbox。JOBS=1 で逐次に戻せる
- `tests/scripts/run-fast.sh`: 軽量 subset (139 ケース、**~27s**)。
  real-coreutils / real-heavy / dist-smoke を skip。開発時の素早い検証用
- `tests/scripts/run-network.sh`: ネットワーク関連だけ (11 ケース、~3m)。
  binary の sockpair_*、real-coreutils の wget-*/curl-*、real-heavy の
  ssl-* を CASE_FILTER で絞って実行
- `tests/scripts/run-test.sh`: 1 件実行。SANDBOX_DIR で sandbox 上書き可。
  java 起動時に `-XX:-UsePerfData` で hsperfdata ロック競合警告を防ぐ。
  `_dyn` 名は host の ld-linux と libc.so.6 を sandbox にコピー
- 外部スクリプト形式の回帰 (各 ext script は `CASE_FILTER` env で
  個別ケースを regex 絞り込みできる、real-coreutils と real-heavy 対応):
  - `ash-noninteractive.sh` — busybox ash -c '<script>' 11 ケース
  - `ash-interactive-cooked.sh` / `ash-interactive-jline.sh` — 対話 ash
  - `jline-smoke.sh` — JLine Terminal / signal API
  - `ash-applet-survey.sh` — find/sort/awk/sed/grep/tar/xargs 等 43 ケース
  - `dist-smoke.sh` — zip 生成 → 解凍 → 起動
  - `real-coreutils.sh` — 実機 GNU coreutils + bash + git + curl 51 ケース
  - `real-heavy.sh` — Python 3.12 / OpenSSL 7 ケース (timeout 120)

## 主要なディレクトリ・ファイル

- `src/main/java/emulin/`
  - `Cpu.java` (i386), `Cpu64.java` (x86-64), `AbstractCpu.java`
  - `Syscall.java` (long 化済), `SyscallI386.java`, `SyscallAmd64.java`
  - `Elf.java`, `Segment.java`, `Section.java`, `Memory.java`
  - `Process.java`, `Kernel.java`
  - `device/Console.java` / `StdConsole.java` / `JLineConsole.java`
- `dist/build-dist.sh` — fat jar + busybox + ランチャを zip 化
- `tests/binaries/src/sys_*64.c` — AMD64 syscall 単体テスト
- `tests/binaries/src/*_dyn64.c` — 動的リンク回帰
- `tests/binaries/src/aesni_static64.c` — AES-NI / PCLMULQDQ FIPS-197

---

# Phase 別 作業記録 (要約)

詳細は各 phase ブランチの commit にある。

## Phase 0-9: 基盤整備
- Phase 0: i386 ELF (-nostdlib) 回帰一式と `run-test.sh` ハーネス
- Phase 1: 現代 Java 化 (UTF-8、`--release 11`、Maven、Thread.interrupt)
- Phase 2: 64bit 化リファクタ (アドレス int → long、AbstractCpu 抽出)
- Phase 3: ELF64 ロード (auxv 8byte 単位)
- Phase 4-5: x86-64 CPU + 命令拡張、SyscallAmd64 新設、hello64 PASS
- Phase 6-7: glibc 静的リンク (4 致命バグ: brk 揃え、SHL マスク、起動時
  GPR ゼロクリア、AH/CH/DH/BH エンコ)。syscall 単体 26 本 + 8 バグ
- Phase 8: シグナル配信 (SIG_IGN/SIG_DFL/カスタム)
- Phase 9: Syscall 全 sys_* メソッドのシグネチャを int → long 化

## Phase 10-21: busybox + bug 発掘
- Phase 10-11: busybox / busybox sh — XORPS, PSUBD, PADDD, INC/DEC r/m8 等
- Phase 12-14: 8bit ALU で AH/CH/DH/BH を取れていなかった (`readReg8`/
  `writeReg8` に統一)。acc, imm 短形式の 0x66/REX.W 対応
- Phase 15: `sys_dup2` が `sys_fcntl` を引数順誤りで呼んで全く効いてなかった
- Phase 16: x87 最小スタブ + SSE2 double + BMI1 (TZCNT/LZCNT) + SHRD
- Phase 17: **DIV r64 の致命バグ** — (RDX:RAX) 128bit / 64bit を Long.
  divideUnsigned で実装していた。BigInteger で実装し直し
- Phase 18: getdents64 で "." が 2 回出る (`.`/`..` 補完ロジック)
- Phase 19-20: busybox applet サーベイ — wait4 exit code 形式、F_DUPFD
  系、O_APPEND、Syscall の int 残党、XADD、utimensat、0x66 prefix 漏れ
  2 件 (`0x89/0x8B` MOV、`0xF7 /0` TEST)
- Phase 21: 0x66 prefix 全面監査 — ALU 16 命令、IMUL imm、TEST、XCHG、
  Grp2 shift/rotate、NOT/NEG、INC/DEC、CMPXCHG、BSF/BSR

## Phase 22: 対話 ash + JLine + ディストリビューション化
- 8 段階で「Windows ユーザが zip 解凍して即起動」可能に
- TIOCGPGRP は `*addr` に getpgrp を書くスタブ、poll は即時 ready スタブ
- **JLine 3.27.1** 採用、raw/cooked 切替、SIGINT 共通化、SIGWINCH 配信
- NativeConsole / emu_con.c (JNI) を完全削除 (831 行削除)
- maven-shade-plugin で fat jar、`dist/build-dist.sh` で zip 出力
- **後修正 (commit 032f5ac)**: emulin.bat の文字化け (CP932)、JVM Ctrl-C
  (Signals.register で sun.misc.Signal 握る)、シグナル配信時のレジスタ
  破壊 (GPR 16 本 + flags 保存復元、SIGRETURN_TRAMPOLINE で着地検知)

## Phase 23: シグナル拡張 + テスト基盤強化
- SIGCHLD 自動配信 + **x86-64 ABI red zone (rsp-128) 尊重**
- SA_RESTART (rip を syscall 命令の手前に戻して再実行)
- SA_SIGINFO 3 引数ハンドラ (siginfo_t + ucontext_t)
- amd64_wait4 ECHILD (is_child_exited 0/-1 区別)、exec_replacing スキップ
- 並列化で 4.4x 高速化、SKIP ゼロ化、ash-applet-survey 43 ケース

## Phase 24: 動的リンク対応 (gcc -no-pie hello)
- PT_INTERP 検出、interp を別 base にロード、auxv 拡充
- SHUFPD 実装、**PT_LOAD 以外を memory map しない**
- amd64_pread64、**`mem.syscall = syscall` の同期** (Process ctor 後の
  syscall 差し替え時に mem 内参照が古いまま残る罠)
- **CPUID baseline** (x86-64-baseline 相当、glibc dl-prop.h ISA レベル)
- 0F AE 系 (FXSAVE/FXRSTOR/LDMXCSR/STMXCSR/CLFLUSH/FENCE)

## Phase 25: 動的リンク binary の使い込み + 致命バグ
- 小物: tgkill / MOVMSKPD / JRCXZ / PMAXUB / futex no-op / PINSRW /
  socketpair (片方向、pipe 流用 — step 21 で双方向化) / mmap of file / clone3 → ENOSYS /
  GCC ネスト関数 (実行可能スタックそのまま)
- **PF (parity flag) を追跡** — `evalCond` の case 10/11 (JP/JNP) が
  hardcode false/true。glibc `__printf_fp` の `ucomisd; setp al` で NaN
  判定が壊れて bignum 桁変換に突入していた
- **ADC/SBB が CF を更新していなかった bignum 致命バグ** — 0x11/0x13
  (ADC), 0x19/0x1B (SBB) と Grp1 imm case 2/3 が CF を読むだけで結果
  CF/ZF/SF/OF を一切更新してなかった。`printf("%g", 1e96)` → `9.:e+95`、
  DBL_MAX → `1.673e+307`。helper `adc{16,32,64}/sbb{16,32,64}` を新設

## Phase 26: PIE (位置独立実行ファイル)
- ET_DYN を弾かずロード、load_bias、lea rip-relative も bias 付き
- **真因: `Syscall.sys_brk` 自身の 3 重 32-bit 切り詰め** (ローカル変数
  ret が int + `(long)bx & 0xFFFFFFFFL` mask + `(int)mem.get_curbrk()`
  + dispatch 側 mask)。全部除去して pie_base を Linux 典型 0x555555554000

## Phase 27: 実機バイナリを動かす

### step 1-7: 実機 coreutils + python + git + bash
- step 1: 実機 /bin/ls — `statfs/fstatfs/statx → ENOSYS`、`socket/connect
  /bind → EAFNOSUPPORT` で通る
- step 2: GNU coreutils 9 種類 — `fadvise64 → 0`, `mincore → 0` (一旦)
- step 3: grep / sed
  - **mincore → 0 で grep が 278k 回 busy loop** → ENOSYS で諦めさせる
  - **LEA r32 zero-extend バグ** (operand-size 無視で常に 64-bit 書き)
  - 副次: sigaltstack、CVTSI2SS / CVTTSS2SI / CVTSS2SI / UCOMISS / COMISS
- step 4: GNU coreutils 24 種類 + bash — getpeername/getsockname → ENOTSOCK、
  sysinfo、sched_getaffinity、unlinkat、vfork、faccessat2、REP RET、
  PACKUSWB、CVTSD2SS、CMPSD scalar
- step 5: Python 3.12 + OpenSSL 3.0 — getrandom 実バイト書き込み (ENOSYS
  だと _Py_HashRandomization_Init で fatal)、FIOCLEX / FIONCLEX no-op、
  SHUFPS / MINSD scalar。`real-heavy.sh` (timeout 120) に分離
- step 6: GNU make / git --version / file — getitimer/setitimer → 0、
  PEXTRW、env passthrough 拡張 (OPENSSL_*, PYTHONHASHSEED, LANG, TZ 等)
- step 7: **sys_rename / sys_mount / sys_pipe の (int) キャスト** — name
  pointer を `int _p = (int)bx` で受けて 64-bit ptr が壊れていた

### step 8-10: AES + curl --version
- step 8: OpenSSL DRBG の AES_set_encrypt_key で bits=2048 (期待 256)
  と判明。深追いせず保留 → step 16 で真因特定
- step 9: **AES-NI / PCLMULQDQ 7 命令を完全実装** — 3-byte opcode escape
  (66 0F 38 / 66 0F 3A) のサポート、FIPS-197 S-box / 逆 S-box、CPUID
  leaf 1 ECX に AES-NI(bit25) と PCLMUL(bit1) を立てる。aesni_static64
  回帰で host と完全一致
- step 10: curl --version (TLS) — **`Inode.get_uniq_no` の弱いハッシュで
  st_ino 衝突** → ld.so が `(st_dev, st_ino)` 同一視で重複ロードと判断
  silently skip。`pathname.hashCode()` (Java 31-進多項式) に置換。
  副次: MAP_FIXED 後の mark_address は前進のみ

### step 11-14: HTTP / DNS
- step 11: **AF_INET TCP socket** — wget で HTTP ダウンロード成功。
  socket / connect / sendto / recvfrom (MSG_PEEK) / sendmsg / recvmsg /
  bind / listen / getsockname / getpeername / setsockopt / getsockopt /
  clock_getres / clock_nanosleep / pselect6。MSG_PEEK は Fileinfo の
  peekBuf で実現。amd64 では SubProcess を spawn しない (i386 経路と
  違う)。AF_UNIX / AF_INET UDP は EAFNOSUPPORT で `/etc/hosts` fallback
- step 12: wget の hang 解消 — Fileinfo に socketEof / nonBlock、
  fcntl(F_SETFL) で O_NONBLOCK 反映、pselect6 を実 fd_set ベースに、
  clock_gettime を実時計に
- step 13: curl HTTP — pthread でなく fork で動くと判明。timer_create /
  timer_settime (POSIX timer + SIGALRM)、rt_sigsuspend ループ、Pipeinfo
  非 blocking、poll の fd 別 readable 判定 (socket / pipe / regular)、
  non-blocking connect で -EINPROGRESS、getsockname IP byte-order 修正
  (i386 の `Util.swap32` 二重掛けに揃える)、getsockopt は optlen NULL
  でも optval 書き込み、EMULIN_TRACE_RIP / 戻り値 trace を追加
- step 14: 実 DNS で wget が動作 — UDP socket 許可、connect(udp) で
  dest 記憶、sendmmsg / recvmmsg、sendmsg/recvmsg を UDP 対応、poll で
  DatagramSocket を扱う (setSoTimeout で試行 receive、cachedDatagram に
  キャッシュ)、FIONREAD ioctl、recvfrom/recvmsg の src_addr byte-order、
  pthread 系 clone は -EAGAIN で reject

### step 15-18: HTTPS
- step 15: TLS handshake まで到達 — PSHUFB / PALIGNR / PINSRD 実装、
  /dev/urandom を S_IFCHR 申告、サンドボックス整備 (openssl.cnf 二重
  パス、/dev/urandom の dd 済 1MB file、/etc/passwd)。decrypt は失敗
  (step 16 で解決)
- step 16: **PSRLQ imm 未実装が "OpenSSL keylen=bits" バグの真因**
  — gcc -O2 が `kbits/8` と `ivbits/8` を `psrlq $3, xmm` に最適化、
  我々が Group 14 で /3 PSRLDQ と /7 PSLLDQ しか実装してなかった。
  Group 12/13/14 (`66 0F 71/72/73`) の per-element shift を網羅実装。
  `openssl rand` / `openssl enc` が動作。HASH-DRBG workaround 不要に
- step 17: **INC/DEC が CF を破壊するバグ** — Intel SDM「INC/DEC は CF
  を変えない」に違反していた。Group 5 (`0xFF /0` INC, `/1` DEC) で
  saved_cf で囲い込み。Mont 乗算 / EC 公開鍵解析が動作 (`openssl x509
  -pubkey` ECDSA、`openssl ecparam -name prime256v1` OK)。副次: PREFETCH
  系 (`0F 18 /n`) と多バイト NOP (`0F 1F /n`) を no-op で実装
- step 18: wget HTTPS で実 HTML 取得 — **`amd64_write` の `-EPIPE`
  タイポ** (EPIPE 既に -32 なのに `-EPIPE` で +32 を返していた、wget が
  「32 byte 書けた」と誤解して partial-write retry で HTTP req 破壊)。
  pselect6 / poll / Fileinfo.Read の TCP non-blocking を setSoTimeout(1)
  + peek に統一。F6 /4-/7 (MUL/IMUL/DIV/IDIV r/m8) 実装。iana / kernel /
  python / github 成功

### step 19-20: ECDSA cert TLS 1.3 で server FIN (調査のみ・修正なし)
- **動く**: iana (RSA cert + TLS 1.3 + AES_256_GCM_SHA384)
- **動かない**: example.com / cloudflare.com / google.com (ECDSA cert
  + TLS 1.3 + AES_X_GCM_SHA_X)
- ★ **iana と example.com は cipher が同じ TLS_AES_256_GCM_SHA384 だが
  cert type だけ違う。よって cipher / KDF 共通の経路はバグでない、
  ECDSA 固有の経路にバグ**

#### step 20 で確認したこと (= バグでない)
  - **ClientHello は host と byte-identical** (両方 385 byte、cipher suite
    75 件、extensions 構成も全部同じ。step 19 の "我々 400 vs host 308" は
    計測誤りだった)。よって TLS fingerprinting の仮説は破棄
  - server からの encrypted handshake (3825-3827 byte 可変) は decrypt
    成功 → cert chain 4 段、CertificateVerify (ecdsa_secp256r1_sha256)、
    server Finished (verify_data 48 byte) すべて parse できる
  - CCS + Client Finished record (80 byte) は write 成功
  - 直後の read で 0 (EOF) が返る = server が FIN している
  - server は Alert を送らずに silently FIN (Cloudflare の anti-leak 仕様)
  - SHA384 transcript hash (長さ 0〜7900 で host と完全一致)
  - AES-256-GCM ラウンドトリップ (各種長)、HMAC-SHA384、AES-NI 7 命令
  - ECDSA P-256 SHA256 verify (`openssl dgst -sha256 -verify`) 単体動作
  - cipher を `--ciphersuites=TLS_AES_128_GCM_SHA256` に絞っても症状同じ
  - `--secure-protocol=TLSv1_2` に落としても症状同じ (Cloudflare は server
    側で TLS 1.3 にネゴしてくる、または TLS 1.2 でも同症状)

#### 推定原因 (現時点)
  - 我々の **Client Finished 暗号文が server 視点で invalid**
  - Server が decrypt or HMAC verify に失敗 → silent FIN
  - iana が動くのは server の error 処理が違う、または「decrypt 失敗を
    見逃す」/ "ECDSA cert 経路だけ別の secret derivation 経路に入る"
    可能性 (TLS 1.3 仕様上は cert type と key schedule は無関係なはず
    だが OpenSSL 内部実装で cert sig algo に依存した分岐が…?)
  - 別の可能性: TCP 層の MSS / segment 境界で `c_handshake_traffic_key`
    でのストリーム断片化 (Cloudflare の TLS proxy 実装が断片化に厳しい)

#### 残された手筋 (step 22+)
  1. tcpdump で host vs emu の Client Finished 暗号文を bytes 単位で比較
  2. Wireshark で SSLKEYLOGFILE 経由で server 応答を decrypt
  3. `c_handshake_traffic_secret` の HKDF-Expand-Label 出力を host と diff
     (printf debug を openssl 内部に注入か、自作 C で再現)
  4. custom python TLS client で host の OpenSSL を完全に bypass して
     emulator 上で直接 cloudflare に handshake を組み立てる
  5. iana を ECDSA cert にする CDN (Cloudfront など) を見つけて isolation

### step 21: socketpair 双方向化
Phase 25 で「片方向」と保留にしていた socketpair を真の双方向に。

- Fileinfo に `pipe_write_no` 追加 (read 用 pipe_no と別)
- `set_pipe_pair(read, write)` で両方向フラグを立てる
- `FileWrite` は `pipe_write_no >= 0` なら write 用 pipe を使う
- `amd64_socketpair`: 2 つの pipe (順方向 / 逆方向) を `connect_pipe()`
  で作って各 fd に対称に持たせる
- 通常 pipe (`sys_pipe`) は pipe_write_no=-1 のままで従来動作 (片方向)

新規回帰 `sockpair_bidir_dyn64`: forward (fds[0] write → fds[1] read) と
backward (fds[1] write → fds[0] read) の両方を確認。**回帰 200 PASS /
0 FAIL / 0 SKIP** (199 → 200)。

### step 22: stat レイアウトの type truncation を修正
旧 AMD64 stat 実装は 144 byte レイアウト自体は Linux x86-64 ABI と一致
していたが、フィールド書き出し時に 32-bit mask が入っていてデータ損失
あり:
- `st_size & 0xFFFFFFFFL` — >2GB ファイルが truncate
- `st_atime/mtime/ctime & 0xFFFFFFFFL` — Y2038 wrap 可能性
- `st_blocks` が常に 0 — du / cp -a が混乱
- `st_atime_nsec / mtime_nsec / ctime_nsec` が常に 0 — make / ninja /
  sccache が変更検知で秒精度に落ちる

修正: Inode の st_size, st_atime, st_mtime, st_ctime, st_blocks を
long 化、nsec 系フィールド追加 (Java の File.lastModified() の ms
からナノ秒部を導出)、st_blocks を `(size + 511) / 512` で計算、
`_set_file_stat64` の 32-bit mask を除去。i386 stat は仕様上 32-bit
なので `(int)` キャストを追加して維持。

新規回帰 `sys_stat_layout64`: ftruncate(12345) → fstat で size, blocks,
mtime > 2024 epoch, nsec が範囲内であることを確認。**回帰 201 PASS /
0 FAIL / 0 SKIP** (200 → 201)。

注記: stat レイアウトの「順序」自体は元から正しかった (CLAUDE.md の
「st_atime_nsec の順序など」候補メモは記憶違いだった)。実問題は
type truncation の方だった。

### step 23: SIGALRM 自動配信 + sigprocmask + sa_mask 中再入抑止
シグナル基盤を 4 系統で拡張。bash trap、make timeout、glibc internal の
sigprocmask 経路が動作するようになる。

1. **`alarm(N)` (#37) 真対応**: 旧 stub return 0 を `Process.set_alarm()`
   で Java background thread spawn → N 秒 sleep → `kernel.kill(pid,
   SIGALRM)`。alarm(0) や次の alarm() で前 pending を cancel。
2. **`setitimer(ITIMER_REAL)` (#38) 真対応**: itimerval を読んで it_value
   (initial) と it_interval (周期) を解釈。`Process.set_itimer_real()` が
   initial sleep → SIGALRM → period sleep → SIGALRM の繰り返し。
   alarm() と timer は同じ ITIMER_REAL を共有 (POSIX)。
3. **`rt_sigprocmask` (#14) 真対応**: 旧 stub return 0 を、Siginfo.mask
   を syscall 経由で実 set/get できるようにした (psig() は元から
   isMask() をチェックしていた)。SIG_BLOCK/SIG_UNBLOCK/SIG_SETMASK と
   oldset 書き出しに対応。
4. **sa_mask 中の self-mask**: Cpu64.check_pending_signal でハンドラ進入
   時に現在の mask を sigSavedFrames に保存し、配信中の signal 自身を
   mask。SIGRETURN_TRAMPOLINE 着地時に mask 復元。POSIX sa_mask デフォ
   ルト (sa_mask 自身を読む拡張は将来課題)。

副次バグ修正:
- `Kernel.kill` が `ptable.elementAt(_pid)` で 1 つズレていた (pid は
  1-based、ptable は 0-based)。`find_process(_pid)` で線形探索に修正。
  curl の timer_settime は ptable 3+ 要素状態でしか発火しなかったので
  気付かれていなかった。
- `sys_pause` が Thread.sleep の無限ループで pending signal を全く
  チェックしないバグ。psig() != -1 で -EINTR を返す形に修正
  (alarm + pause が hang していた)。

新規回帰:
- `sys_alarm_deliver64`: alarm(1) → pause() → SIGALRM ハンドラ実行 → exit
- `sys_sigmask64`: SIGUSR1 を block → kill self → unblock で pending
  signal が配信されることを確認

**回帰 203 PASS / 0 FAIL / 0 SKIP** (201 → 203)。

### step 24: 性能改善 (easy wins) — Python 起動 9% 高速化
ホット bottleneck を 3 系統で削減:

1. **`psig()` fast-path**: 旧実装は **1 命令ごとに 32 signal を loop scan
   + 各 2 method calls (isMask/get_count) = 64 method calls/命令** だった。
   `pending_recv_count` を recv()/cancel() で incr/decr して、pending=0
   なら早期 return -1。大半のケースで 1 volatile read で済む。
2. **`EMULIN_TRACE_FP` / `_SH` の getenv ホイスト**: 旧実装は毎命令
   `System.getenv()` × 2 回 (HashMap lookup)。eval ループ進入時の 1 回読み
   に hoist して boolean に固定。
3. **`process.evals = executed` を 1024 命令ごとに batch**: segfault 診断は
   最大 1023 命令ずれるが許容範囲。

real-heavy (Python 起動 + OpenSSL) で **155s → 141s (9% 高速化)**。
注: real-coreutils と real-heavy は parallel に走るので、real-heavy
単体の改善は wall-clock の run-all 全体時間 (約 3m50s) には反映されない
(real-coreutils が bottleneck)。

deeper refactor (decode_and_exec 2100 行の opcode switch 化、basic-block
cache 等) はこの step の範囲外、step 候補リスト #2 に残置。

回帰 203 PASS / 0 FAIL / 0 SKIP のまま。

### step 25: emulator 内で git init + add + commit が動作 — 4 系統のバグ修正
最近の改修 (socketpair 双方向 / SIGALRM / sigprocmask / stat レイアウト)
の積み重ねで git の hard 経路が解放されたタイミングで、4 系統の修正で
emulator 内で **git の完全な workflow** が動作。

1. **`sys_access` の 2 重バグ**:
   - `mode &= F_OK` (= mode &= 0) でローカル mode を 0 上書きしていて
     R_OK / W_OK / 既存判定が全部 dead code 化
   - 失敗時に -1 (= -EPERM) を返していた。正しくは -ENOENT/-EACCES
   - git は EPERM を "Operation not permitted" として fatal abort して
     いたが、ENOENT を返すと parent dir mkdir + retry に入る
2. **`/dev/*` device router の sandbox file 無視**:
   - `is_device("/dev/urandom")` は true だが `is_exist_device` は
     /dev/null しか知らず -1 で諦め → sandbox/dev/urandom を user が
     dd で作っても open できなかった
   - 未認識の /dev/* は regular file として開きを試みる方針に変更
3. **`sys_open` の FileOpen 失敗時 errno**:
   - 旧実装は -1 (= -EPERM) 直返し
   - parent dir の有無で ENOENT (= -2) か EACCES (= -13) に振り分け。
     git は ENOENT で mkdir + retry する
4. **`link(#86)` / `linkat(#265)` 実装**:
   - 旧実装は ENOSYS。git の object commit は tmp_obj_xxx → 最終 hash
     名を link + unlink で atomic にする
   - Java NIO `Files.createLink` で実装

新規回帰: real-coreutils.sh に 4 ケース追加 (`git-init-emu` /
`git-add-emu` / `git-commit-emu` / `git-emu-log`)。emulator 内で
git init → add → commit → log の完全な workflow を検証。

副次: 既存の sys_access64 / sys_rename64 / sys_unlink64 テストは旧 buggy
な戻り値 (-1 = EPERM) を期待していたので正しい -2 = ENOENT に更新。

**回帰 207 PASS / 0 FAIL / 0 SKIP** (203 → 207)。

未対応: HTTPS が必要な clone / push / fetch (Cloudflare TLS の step 19-20
ECDSA 経路と同根、別途解決必要)、PGP signed commit (gpg subprocess)。

### step 26: 性能改善 (深掘り) — 失敗、no-op コミット
Memory に cachedSeg (Segment 参照キャッシュ) を導入し load8/16/32/64 +
store8/32/64 に fast-path を追加する試み。機能は維持できたが Python
起動 (real-heavy) で **142s → 175s と 23% 遅くなった**ため revert。

原因: 旧 per-byte cache (cache_size=8) の HIT パスは「配列 1 アクセス」
のみ。新 fast-path は field load + null check + 2 比較 + 配列 = 5 ops で
HIT パスがむしろ重い。命令 fetch は連続 byte でヒット率 99%、JIT が旧
コードを十分最適化していた。

学び: 当てずっぽうの最適化は逆効果。真の wins には profiler 必須で、
decode_and_exec 2100 行の switch 化や basic-block cache 等の deeper
refactor が必要 (multi-hour 別 session)。

回帰 207 PASS / 0 FAIL / 0 SKIP のまま (コード変更なし)。

### step 27: sigaction.sa_mask 完全対応
step 23 で配信中の signal 自身を self-mask するところまでは対応していた
が、sigaction 構造体の `sa_mask` フィールド (handler 進入時に追加で
block する signal の bitmap) を完全に無視していた。

修正:
- Siginfo に `sa_mask` field 追加 + SA_NODEFER 定数追加
- amd64_rt_sigaction で `act_addr+24` から sa_mask 8 byte を読み取り保存。
  oldact 書き戻しも sa_handler / sa_flags / sa_restorer / sa_mask の全
  32 byte に拡張
- Cpu64.check_pending_signal で handler 進入時に
  `saved_mask | sa_mask | (1L << (sig-1))` を新 mask に設定
  (SA_NODEFER 時は sig 自身は加えない POSIX 仕様)。SIGRETURN_TRAMPOLINE
  着地時に saved_mask を復元 (既存)

新規回帰 `sys_sa_mask64`: SIGUSR1 handler に sa_mask=SIGUSR2 で install。
handler 中に kill self SIGUSR2 → handler 終了まで配信されないこと、
handler 終了後 (mask 復元) に SIGUSR2 が配信されることを検証。

**回帰 208 PASS / 0 FAIL / 0 SKIP** (207 → 208)。

### step 28: pthread の基礎動作 — curl AsynchDNS が解放された
長らく EAGAIN で諦めさせていた pthread を真対応。pthread_create + worker
+ pthread_join + 共有 memory が動作。当初の motivation だった
**curl AsynchDNS (HTTP) が動作する**ようになった。

新規追加クラス:
- **`Thread64`**: 各 pthread = Java Thread + 独立 Cpu64 + 共有 Memory/Syscall。
  CLONE_CHILD_CLEARTID 対応で thread exit 時に *ctid_addr=0 + futex wake
  (= pthread_join を起こす glibc の慣例)
- **`FutexManager`**: アドレス毎の Object monitor で wait/notify。
  FUTEX_WAIT (0/9) と FUTEX_WAKE (1/10) の最小実装

amd64 syscall 拡張:
- **`clone (#56)`**: CLONE_VM|CLONE_THREAD なら `amd64_clone_thread` で
  Thread64 を spawn。子は親のレジスタを継承し rax=0、rsp=child_stack、
  fs_base=tls (CLONE_SETTLS)、rip=`r64[R_RCX]` (= syscall return address) で開始
- **`futex (#202)`**: 旧 stub return 0 を真対応
- **`gettid (#186)`**: Thread64 経由なら Thread64.tid、メイン thread なら pid
- **`exit (#60)` vs `exit_group (#231)`**: pthread thread の exit は
  ThreadExitException で eval を抜ける、exit_group は process 全体 exit

副次修正:
- Memory の per-byte cache (`cache_address` + `cache[8]`) を **ThreadLocal** に。
  共有すると複数 thread のリフィル race で `index out of bounds` で crash する。
- `Cpu64.copy_state_from / copy_state_into` 新設 (xmm 含む全 register state を
  thread spawn 時に親→子コピー)
- `Kernel.next_tid`: pthread の TID 採番 (10000 から開始)

新規回帰: `pthread_basic_dyn64` — pthread_create で worker → 共有 int に 42 →
pthread_join で待機 → 値読み出し。**回帰 209 PASS / 0 FAIL / 0 SKIP** (208 → 209)。

副次的成果: **curl AsynchDNS が動作** (curl http://example.com/ で完全な
HTML 取得成功)。Phase 27 step 14 で「pthread 必須なので諦める」と書いた
経路が解放された。

未対応 (将来課題): 複数 worker thread の安定性、mutex 競合下の futex 動作、
per-thread signal mask、set_tid_address (#218) 真対応、TLS の細部、
CLONE_FILES 等の他 flags の semantics 追従。

### step 29: pthread mutex 競合下の動作確認 — 4 worker + 共有 counter
step 28 の基礎は単一 worker のみ。複数 worker + mutex 競合下を新規回帰
`pthread_mutex_dyn64` (4 thread × 1000 iter で counter++ → 期待 4000)
で検証 → 当初 SIGABRT で fail。3 系統の修正で動作。

修正:
1. **`FutexManager.wake` の wake count 嘘**: 旧実装は actual waker 数を
   返さず常に `max` を返していた → glibc が `pthread_mutex_lock` で
   "futex_wait_simple returned a wrong value" → abort。WaitNode に
   `waiters` / `wakers` の counter を持たせ、wake は実数を返す
2. **CMPXCHG (`0F B0/B1`) の atomic 化**: pthread mutex は cmpxchg で
   atomic に lock 取得するので thread 間で原子性が必要。`mem` を共通
   monitor として synchronized で serialize
3. **XCHG (`86/87`) と XADD (`0F C0/C1`) の atomic 化**: x86 ABI で XCHG
   は LOCK prefix なしでも implicit atomic、XADD は spinlock で頻繁に
   使われる。同様に synchronized 化

**回帰 210 PASS / 0 FAIL / 0 SKIP** (209 → 210)。

未対応: curl HTTPS は依然 timeout (TLS handshake 中の thread 同期 or
別問題、別途調査必要)。

### step 30: 性能改善 — 2 系統 (init busy-loop + alloclist scan)
curl HTTPS の hang 調査で `jstack` を使い 2 つの bottleneck を発見・修正。
real-heavy で **142s → 105s (26% 高速化)**、run-all wall で **3m51s →
3m21s (30 秒短縮、13%)**、特に sys time は 5m53s → 0m24s (24倍改善)。

修正 1: **init process の busy yield ループ**
  `jstack` で Thread-1 (init) が CPU 100% pegged で `Thread.yield()` 連発。
  worker thread と CPU 競合して全体的に遅くなる元。Thread.sleep(50ms)
  に変更 (Ctrl-C / SIGWINCH 検知遅延は目視で問題なし)
修正 2: **Memory alloclist scan を reverse iteration + 早期 break**
  `jstack` で curl HTTPS が `Memory.load8` → alloclist 線形 scan で 100%
  CPU。旧コードは forward + no-break で「最後の match が勝つ」semantics
  (MAP_FIXED の overlap で新エントリ優先の Linux 仕様)。reverse + break
  で同 semantics を **O(N) → O(K)** (K = 末尾からの距離) に高速化。
  最近の mmap entry が末尾に積まれるので active な hot region は K ≈ 1。
  Phase 27 step 26 で同じ高速化を試みたが lastAlloc cache 方式は
  MAP_FIXED overlap で stale データ問題で revert。今回は LAST-match
  semantics を保ったまま reverse iteration で正攻法に高速化。

回帰 210 PASS / 0 FAIL / 0 SKIP のまま。

未対応: curl HTTPS は依然 hang。`jstack` では Thread-2 が Memory.load8
を回し続けているが progress しない (10M inst / 25s = 400K inst/s)。
alloclist が膨大 (推定 1000+ entries) で TLS handshake 中に hot region
が頻繁に切り替わると reverse + break でも遅い可能性。alloclist を
address sorted な構造 (TreeMap や interval tree) にすれば O(log N) に
なるが別 step。

### step 31: Memory.alloclist を TreeMap に — alloc lookup O(log N)
step 30 の reverse + break をさらに進めて、`alloclist` を `Vector` から
`java.util.TreeMap<Long, AllocInfo>` (start address でソート) に置き換え。
すべての lookup が `floorEntry` で **O(log N)** になる。

real-heavy: **105s → 92s** (12% 改善、累計 35% from baseline 142s)
run-all wall: **3m21s → 2m08s** (36% 改善、**累計 45% from baseline 3m51s**)

変更:
- `Vector alloclist` → `TreeMap<Long, AllocInfo>`
- `alloc / realloc / free`: O(log N) 直接 lookup
- `in()`: floorEntry で O(log N)
- `load8 / store8` の cache miss 時: floorEntry で O(log N) lookup
- `duplicate`: entrySet で iterate

注意: range overlap (異なる start で範囲交差) は handle せず。ld.so の
library segment は non-overlapping に並べるので実害なし。同 start address
は TreeMap.put で置換 (Linux MAP_FIXED の最小限互換)。完全な MAP_FIXED
互換は別途 (interval tree 化が必要)。

回帰 210 PASS / 0 FAIL / 0 SKIP のまま。

未対応: curl HTTPS は依然 timeout (2 min でも未完了)。alloclist 高速化は
効いているが TLS handshake の crypto 計算 + per-byte cache miss が依然
重い。次の手筋: cache_size を 8 → 64 に拡大 / load64 の cache 経由バイ
パス / decode_and_exec の opcode switch 化等。

### step 32: Memory.cache_size を 8 → 32 に拡大 — 計測ベースの最適化
cache_size を 4 候補で real-heavy を 3 回ずつ計測:

| cache_size | avg 時間 | 個別計測 |
|---|---|---|
| 8 (baseline) | 104s | 102, 105, 106 |
| 16 | 102s | 95, 100, 99 |
| **32** | **87s** | 87, 87, 87 ← 安定 |
| 64 | 87s | 87, 88, 87 |
| 128 | 88s | 87, 89, 88 |

cache_size=32 で **16% 高速化**、それ以上は plateau (cache hit 率が
飽和)。run-all wall: 2m08s → **1m55s** (10% 改善、**累計 50% from
baseline 3m51s**)。

boundary safety: 32-byte cache line は 4096-byte page boundary を跨がない
ので segment / allocinfo の境界で torn read は起きない。

回帰 210 PASS / 0 FAIL / 0 SKIP のまま。

curl HTTPS: 依然 TLS handshake が curl の SSL connection timeout (~120s)
を超える。CPU 計算量ベースで 30%+ 高速化したが TLS の crypto 計算が
genuinely 重い。次の手筋は decode_and_exec の opcode switch 化等の
deep optimization。

### step 33: opcode fast-path switch + alloclist を ConcurrentSkipListMap に
decode_and_exec の mid-chain hot opcodes を switch (b0) で先取り dispatch
+ alloclist を thread-safe な ConcurrentSkipListMap に変更。

real-heavy: **87s → 66s** (24% 改善、累計 53% from baseline 142s)
run-all wall: **1m55s → 1m44s** (10% 改善、**累計 55% from baseline 3m51s**)

修正:
1. `Cpu64.decode_and_exec` の hot path 16 opcode を `switch (b0)` で先取り:
   `0x89/0x8B/0x88/0x8A` MOV、`0x83` Grp1 imm8、`0x39/0x3B` CMP、
   `0x84/0x85` TEST、`0x01/0x03` ADD、`0x29/0x2B` SUB、`0x31/0x33` XOR、
   `0x21/0x23` AND、`0x09/0x0B` OR、`0x8D` LEA。`default` で既存 if/else
   cascade に fall-through (追加した case 以外は影響なし)。Java JIT が
   switch を tableswitch (jump table、O(1)) に compile する。
2. `Memory.alloclist`: `TreeMap` → `ConcurrentSkipListMap`。pthread で
   複数 thread が並列 access するため TreeMap は thread-safe でない。
   step 33 の switch dispatch 高速化で race が露見した
   (`pthread_mutex_dyn64` が 5 回中 2 回 fail)。ConcurrentSkipListMap も
   同 O(log N) で thread-safe。

回帰 210 PASS / 0 FAIL / 0 SKIP (5 連続安定確認)。

### step 33b: switch dispatch を 6 種類拡張 (FF/81/C7/B0-BF/C1D1D3)
step 33a の 16 opcode 先取り switch をさらに 6 種類拡張:

real-heavy: 66s → **62s** (6% 改善、**累計 56% from baseline 142s**)

追加 case:
- `0xFF` Group 5: INC/DEC/CALL/JMP/PUSH r/m (very common)
- `0x81` Grp1 r/m, imm32/imm16
- `0xC7` MOV r/m, imm32
- `0xB0-0xB7` MOV r8, imm8 (8 cases fall-through to one body)
- `0xB8-0xBF` MOV r32/r64, imm
- `0xC1/0xD1/0xD3` Grp2 shift/rotate (3 cases share body)

合計 hot path opcodes: 22 → ~30 (range opcodes 含む)。

curl HTTPS: SSL connection timeout だが 120s → **88s** に短縮。
TLS handshake が curl の SSL 内部 timeout (~90s) にギリギリ届かず。

回帰 210 PASS / 0 FAIL / 0 SKIP (5 連続安定確認、pthread_mutex も 5/5)。

### step 33c: switch dispatch を 8 種類拡張 (ADC/SBB/Grp1-8bit/MOV-imm8/IMUL/MOVSXD)
bignum 演算 (ADC/SBB) と crypto math (IMUL) を中心にさらに 8 opcode 追加:

real-heavy: 62s → 62s (横ばい、Python 起動には効かない)
**wget HTTPS: 50s → 10s** (5x 高速化、累計 step 30-33c の総合効果) ← 顕著
curl HTTPS: 88s → 84s (微小)
run-all wall: 1m43s → 1m41s

追加 case:
- `0x11/0x13` ADC r/m,r / r,r/m  (bignum carry chain)
- `0x19/0x1B` SBB r/m,r / r,r/m  (bignum borrow chain)
- `0x80`    Grp1 r/m8, imm8
- `0xC6`    MOV r/m8, imm8
- `0x69`    IMUL r, r/m, imm32 (crypto)
- `0x6B`    IMUL r, r/m, imm8
- `0x63`    MOVSXD r64, r/m32

合計 hot path opcodes: 30 → 38 (range 含む)。

curl HTTPS は依然 curl 内部の SSL connection timeout を超える。
emulator の計算量はさらに改善の余地ありだが、curl の特殊な timeout
仕様 (デフォルトで TLS handshake に hard timeout がある) によるところが
大きい。

**性能改善 step 30-33c 累計サマリ**:
- real-heavy: 142s → 62s (-56%)
- run-all wall: 3m51s → 1m41s (-56%)
- run-all sys: 5m53s → 0m13s (-96%)
- wget HTTPS (iana): 50s → 10s (-80%)

回帰 210 PASS / 0 FAIL / 0 SKIP のまま。

### step 34: per-thread signal mask (POSIX pthread_sigmask 互換)
step 27 で `sigaction.sa_mask` を実装したが、signal mask 自体は process
全体で共有 (Siginfo.mask の boolean を pthread でも参照) されていた。
POSIX 仕様では mask は呼び出し側 thread にだけ適用される
(`sigprocmask` / `pthread_sigmask`)。step 34 で per-thread mask に対応。

修正:
- `Thread64` に `signal_mask` field 追加 (long bitmap、bit i = signum (i+1))
- `Signal.psig() / is_signal_masked / set_signal_mask / get_signal_mask_bits /
  set_signal_mask_bits` を、`Thread.currentThread() instanceof Thread64`
  なら `Thread64.signal_mask`、それ以外 (main thread) は process-wide な
  `Siginfo.mask` を見るように変更。後方互換は維持
- `amd64_clone_thread`: 親の現 `signal_mask` を子 Thread64 に inherit
  (POSIX clone 仕様)

新規回帰: `pthread_sigmask_dyn64` — 親が SIGUSR1 を block → 子が
inherit した mask を確認 → 子だけ unblock → 子に kill で handler 動作。

**回帰 211 PASS / 0 FAIL / 0 SKIP** (210 → 211)。

未対応: thread-targeted signal (`tgkill` / `pthread_kill` 経由) の
per-thread pending tracking。現状は process-wide pending で、複数 thread
が unmasked だと先着順で picks up。`tgkill` のターゲット指定は無視。
完全対応には `Siginfo.count` を per-thread にする必要あり、別 step。

---

# Phase 27 続き 候補

1. pthread の使い込み: 複数 worker thread / mutex 競合下の安定性、
   per-thread signal mask、set_tid_address (#218) 真対応、curl HTTPS
   AsynchDNS 動作確認 (step 28 で HTTP は動作)
2. 性能改善 (深掘り): decode_and_exec 2100 行の opcode switch 化、
   basic-block cache、Memory.load64 を per-byte cache 経由でなく直接
   segment buf から読む fast-path 等 (step 24 で easy win は摘み済み)
3. dist の充実: jlink で JRE 同梱の self-contained zip、Win/Linux/macOS
   別の zip 出力

---

# 累計バグ修正・実装の傾向 (今後の Phase で参考に)

## アドレス幅 / 命令フラグ

- **アドレスの int 切り詰め** (Phase 9 / 26 / 27 step 7) — シグネチャは
  long 化済みでも個別 syscall に「ローカル変数が int」「`(long)bx
  & 0xFFFFFFFFL` でマスク」が残ると低位アドレスでは動き高位 (PIE) で
  segfault。新規 syscall 追加時は中身も全部 long で書き、`grep -nE
  "= \(int\)(bx|cx|dx|si|di)"` で監査
- **0x66 operand-size prefix の漏れ** (Phase 11/14/20/21) — imm を持つ
  命令で imm16/imm32 の分岐忘れ。新規 imm 命令追加時は必ず 0x66 ブランチ
- **AH/CH/DH/BH (REX 無 + rm=4-7)** (Phase 7/13) — 8bit 命令は必ず
  `readReg8`/`writeReg8` を経由
- **多レジスタ命令の上位読み忘れ** (Phase 17 DIV) — RDX:RAX のような
  上位レジスタを読み忘れる。MUL / IDIV も同種
- **operand-size 無視の dest 書き** (Phase 27 step 3 LEA) — 32-bit dest
  は上位 zero-extend、16-bit dest は上位 48-bit 保持を機械的に守る
- **PF (parity flag) を放置すると致命** (Phase 25) — glibc は `ucomisd;
  setp al` で NaN 判定。UCOMISD/COMISD は Intel SDM 通り ZF/PF/CF
- **ADC/SBB がフラグを更新しないと bignum 連鎖が壊れる** (Phase 25) —
  CF を読むだけで書かないと 1e96 以上の double を %g で出すと壊れる。
  ALU 命令は CF/ZF/SF/OF/PF を Intel SDM 通りに設定
- **INC/DEC は CF を変えない (Intel SDM 違反)** (Phase 27 step 17) —
  SBB chain の途中の DEC が borrow を消す。新規命令実装時は Intel SDM
  の "Flags Affected" を必ず読む
- **PSRLQ imm のような per-element shift の sub-opcode** (Phase 27
  step 16) — Group 12/13/14 (`66 0F 71/72/73`) は per-element 系と
  byte 系で sub-opcode が違う、全部の reg 値を実装すべし
- **PALIGNR の concat 順序** (Phase 27 step 15) — Intel SDM `((DEST
  << 128) OR SRC) >> imm*8` の通り (src が低位、dst が高位)。SSE
  shuffle 系は SDM の式を直接写経

## メモリ / セグメント / 動的リンク

- **動的リンクの最低ライン** (Phase 24) — PT_INTERP 認識 + interp を
  別 base に load + auxv (AT_BASE/AT_ENTRY/AT_PHDR/AT_PHNUM/AT_EXECFN)、
  PT_LOAD 以外を memory map しない (PT_PHDR が PT_LOAD と同じページに
  重なって未初期化バッファが先勝ちする)、CPUID baseline 申告、FXSAVE/
  FXRSTOR、pread64、Process ctor 後の syscall 差し替えに mem.syscall
  も同期
- **glibc 動的リンクの隙間は浅い syscall + 命令の継ぎ足しで埋まる**
  (Phase 25) — tgkill / pread64 / MOVMSKPD / SHUFPD / FXSAVE / 0F AE /
  JRCXZ / CPUID baseline 等で hello〜printf の大半が動く
- **Memory.peekb の線形走査は order 依存** — null buf を許容する
  Segment.in() で「該当しない」扱い
- **emulator 層のフィールド差し替えは要注意** — Process / Memory /
  Syscall は相互参照しており、片方を新インスタンスに差し替えると他方の
  参照が古いまま残る (Phase 24 の mem.syscall = syscall)
- **PIE では section.sh_addr / IRELATIVE relocation も load_bias で
  ずれる** (Phase 26)
- **PIE base は mark_address (0x40000000) と衝突しないよう選定** (Phase 26)
- **st_ino のハッシュは衝突に強くする** (Phase 27 step 10) — ld.so は
  `(st_dev, st_ino)` 対で「同一ファイル」判定。弱いハッシュで衝突すると
  silently skip され LD_DEBUG にも痕跡が残らない
- **MAP_FIXED 後の mark_address は前進のみ** (Phase 27 step 11) — 後退
  すると次の mmap(0) で既存領域を踏む

## syscall / glibc 流儀

- **新しい実機バイナリは ENOSYS / EAFNOSUPPORT スタブで通ることが多い**
  (Phase 27 step 1) — 「まず ENOSYS で返してみる」が最初の一手
- **stub の `return 0` は busy loop を誘発しうる** (Phase 27 step 3) —
  `mincore → 0` で grep が 278k 回ループ。本当に成功と言える syscall
  以外は ENOSYS の方が安全
- **対話プログラムの初期化ループ** (Phase 22) — `tcgetpgrp`/`tcsetpgrp`
  系 ioctl は値を return するだけでなく `*addr` に書き込むことが必須。
  失敗時は -ENOTTY を返すと caller が job control を諦める
- **clock_gettime のスタブは `return 0` でなく実時刻を書く** (Phase 27
  step 12) — caller (date / log) は ts ポインタに書かれることを期待
- **`fcntl(F_SETFL)` が return 0 だけは非 blocking 期待を裏切る**
  (Phase 27 step 12) — Fileinfo に nonBlock フラグ反映必須
- **POSIX timer (timer_create / timer_settime) を実装すると --max-time
  が効く** (Phase 27 step 13) — Java background thread で sleep →
  kernel.kill(pid, SIGALRM)
- **既に負の errno 定数に `-` を付けると符号逆転する** (Phase 27
  step 18) — `Syscall.EPIPE = -32` なのに `return -EPIPE` で +32 が
  返り caller が「成功」と誤解。`grep -nE "return -E[A-Z]+"` で監査

## シグナル / fork / wait

- **シグナル配信の最小実装は致命的** (Phase 22 後 / Phase 23) —
  (a) GPR 16 本 + flags の保存・復元、(b) **x86-64 ABI red zone (rsp-128)
  を尊重**、(c) SA_SIGINFO 時の 3 引数 (rsi=&siginfo, rdx=&ucontext) ABI、
  (d) syscall が EINTR を返す前に SA_RESTART チェックして再実行
- **wait4 / fork / exec の race** (Phase 23) — `is_child_exited` が
  「子がいない」(0) と「子が未終了」(-1) を区別する必要。exec 中の旧
  プロセスは exec_replacing でスキップ

## socket / 非 blocking

- **MSG_PEEK は libc/wget で必須** (Phase 27 step 11) — Java Socket に
  peek API が無いので Fileinfo に peekBuf。peek は実 read するので、
  その後の read は peekBuf から消費
- **Java Socket と背景スレッド読み出しのレース** (Phase 27 step 11) —
  amd64 では SubProcess を起動せず Fileinfo.client_socket() を直接呼ぶ
- **AF_UNIX / UDP は EAFNOSUPPORT で fallback を強制** (Phase 27
  step 11) — glibc が file 経路 (`/etc/hosts`) に fall back する
- **socket EOF 後の poll/select は ready=0 (timeout) を返す** (Phase 27
  step 12) — Fileinfo に socketEof フラグ。「全部死んだ socket だけ
  残った」場合は 0 を返して caller のポーリングループを抜けさせる
- **i386 (手書き) の syscall を amd64 移植時に `Util.swap32` を落とす罠**
  (Phase 27 step 13) — i386 sys_socketcall の getsockname は swap32 を
  二重に掛けている (BE int を store32 LE 書き出し前にもう一度 swap)。
  socket / IP 関連の挙動が違ったら i386 を正解として揃える
- **poll が「全 fd 常に ready」と返すと wakeup pipe で誤動作** (Phase 27
  step 13) — fd 種別を見て: socket は available/peekBuf、pipe は基本
  「データ無し」、通常 fd は読める扱い
- **non-blocking connect は -EINPROGRESS を返すべき** (Phase 27 step 13)
  — 即 0 だと curl が一部経路で abort
- **getsockopt は optlen が NULL でも *optval を埋める** (Phase 27
  step 13) — 書かないと caller がスタックゴミを「エラーコード」と誤読
- **Pipeinfo.read を非 blocking 化** (Phase 27 step 13) — Fileinfo.
  nonBlock を見て空 pipe + 接続中なら -2 (EAGAIN sentinel)
- **`Java Socket.InputStream.available()` は kernel buffer を見ない**
  (Phase 27 step 18) — `setSoTimeout(1)` + `read(byte[1])` 試行で peek
  して peekBuf にキャッシュ。SocketTimeoutException は EAGAIN
- **`pselect6` の timeout を honor しないと caller が無限ループ**
  (Phase 27 step 18) — wget は read_timeout でなく pselect6 timeout に
  依存。timespec を読み socket peek + sleep ループで wait

## DNS / UDP

- **glibc resolver は `FIONREAD` ioctl を recvmsg の前に呼ぶ** (Phase 27
  step 14) — UDP では cachedDatagram のサイズ、TCP は available()、pipe
  は peekLen を返す。`*addr` 書き込み必須
- **DatagramSocket には available() が無い** (Phase 27 step 14) — 短い
  setSoTimeout で receive を試行して SocketTimeoutException で判定。
  受信できたら Fileinfo にキャッシュ
- **glibc 2.34+ の DNS resolver は sendmmsg/recvmmsg を使う** (Phase 27
  step 14) — A と AAAA を 1 syscall で送る。各 mmsghdr は 64 byte
- **sendmsg/recvmsg は msg_name (UDP の dest/src sockaddr) を扱う**
  (Phase 27 step 14) — IP は getsockname と同じ swap32 二重掛け

## OpenSSL / TLS

- **OpenSSL は /dev/urandom を S_ISCHR でチェック** (Phase 27 step 15)
  — `_set_file_stat64` 後に path で分岐して S_IFCHR | 0666 で上書き
- **OpenSSL は openssl.cnf を `/usr/lib/ssl/openssl.cnf` から読む**
  (Phase 27 step 15) — Ubuntu の compile-time default。sandbox には
  両方 (or symlink) 必要
- **gcc -O2 は size_t / 8 を SIMD (PSRLQ) に最適化する** (Phase 27
  step 16) — shared library 境界をまたぐ関数呼び出しでだけ発火する
  微妙な bug。inlined だと compile-time に折りたたまれる
- **OpenSSL のバグ追跡は逆方向に narrow down** (Phase 27 step 16) —
  失敗箇所のソース行は上流の症状であって真因ではない。objdump + 自作
  C を shared lib に分離して isolation 再現
- **bn_mul_mont の num サイズ別経路の分岐** (Phase 27 step 17) — num が
  4 の倍数かつ ≥4 で AES-NI/MULX/AVX 系の高速 ASM 経路。Mont 乗算 bug
  追跡時は **必ず 4-limb (= P-256) でテスト**

## デバッグツール

- **CPU loop で stuck している原因を特定するには rip dump が必要**
  (Phase 27 step 13) — `EMULIN_TRACE_RIP=N` で N 命令ごとに rip と
  GPR を dump。syscall trace だけでは ld.so symbol 解決中などが見えない
- **syscall trace は戻り値も出力する** (Phase 27 step 13) — `EMULIN_
  TRACE_SH=1` で `DBG ret #n = 0xX` を出す。ENOSYS / EAGAIN /
  EINPROGRESS の混乱を即特定

# テスト戦略

- 「1 syscall 1 テスト」原則 (`sys_*64.c`)
- `-nostdlib` で int 0x80 / syscall 直叩き
- 期待値は実 Linux カーネルの仕様に合わせる
- 並列実行を前提に、テスト間で sandbox / /tmp を衝突させない
- 既知バグは `.skip` で残せるが、Phase 23 で全 .skip 解消済
