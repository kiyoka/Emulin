コミットログには、以下を記載しないようにしてください。
Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>

objdump / nm / grep / readelf / addr2line / strace は許可なしで実行してよい。

---

# プロジェクト概要

古い Emulin (Java 製 32bit Linux ELF エミュレータ) を現代 Java で動かし、
さらに x86-64 (64bit ELF) を実行できるように拡張する。

## 現状サマリ (Phase 27 step 21 完了時点)

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
  SA_SIGINFO で 3 引数ハンドラ対応、レジスタ完全保存 + red zone 尊重
- パイプライン・fork/exec/wait4 (exit code 伝搬・ECHILD 適切返却・exec
  race 解消)・getdents64・連続リダイレクト・O_APPEND 対応済み
- **回帰テスト 200 本 PASS / 0 FAIL / 0 SKIP**、xargs -P / wait -n で
  並列化、逐次 128s → 並列 27-30s で約 4.4x 高速化、5 連続安定。

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
- **curl の DNS は不可** — AsynchDNS が pthread 必須で同期 fallback なし。
  `clone(CLONE_VM|CLONE_THREAD)` を -EAGAIN で reject する我々の方針では
  "getaddrinfo() thread failed to start" で諦める。wget は同期 getaddrinfo
  なので動く。pthread の真実装が必要 (将来課題)
- **IPv6 (AF_INET6) は未対応** — getaddrinfo は IPv4 のみ返る経路で動作
- **WSL DrvFs (`/mnt/c/...`) は I/O 遅く chmod も効かない** — テストの
  sandbox は `mktemp -d -t emulin-sb.XXXXXX` で Linux 側 /tmp に置く運用

## 動作テスト基盤

- `tests/` に i386/x86-64 静的リンク ELF (-nostdlib で int 0x80 / syscall
  直叩き) と回帰ハーネス
- `make -C tests/binaries`: テストバイナリビルド
- `tests/scripts/run-all.sh`: 全テスト実行。**並列化済**: binary は xargs
  -P で nproc 並列、ext scripts は & + wait で 5 本同時、applet-survey は
  内部で wait -n 制御の 6 並列。各テストは Linux 側 /tmp 上の per-test
  sandbox。JOBS=1 で逐次に戻せる
- `tests/scripts/run-test.sh`: 1 件実行。SANDBOX_DIR で sandbox 上書き可。
  java 起動時に `-XX:-UsePerfData` で hsperfdata ロック競合警告を防ぐ。
  `_dyn` 名は host の ld-linux と libc.so.6 を sandbox にコピー
- 外部スクリプト形式の回帰:
  - `ash-noninteractive.sh` — busybox ash -c '<script>' 11 ケース
  - `ash-interactive-cooked.sh` / `ash-interactive-jline.sh` — 対話 ash
  - `jline-smoke.sh` — JLine Terminal / signal API
  - `ash-applet-survey.sh` — find/sort/awk/sed/grep/tar/xargs 等 43 ケース
  - `dist-smoke.sh` — zip 生成 → 解凍 → 起動
  - `real-coreutils.sh` — 実機 GNU coreutils + bash + git + curl 47 ケース
  - `real-heavy.sh` — Python 3.12 / OpenSSL 5 ケース (timeout 120)

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

---

# Phase 27 続き 候補

1. pthread (clone3 真対応 + futex + TLS 拡張) — 工数最大、curl AsynchDNS
   や OpenSSL の最近の経路が解放
2. 性能改善: Decoder 命令キャッシュ復活 / テーブル駆動化
3. stat 系レイアウトの再確認 (st_atime_nsec の順序など)
4. シグナル更なる拡張: SIGALRM 自動配信 (alarm/setitimer)、sigprocmask、
   sa_mask 中ハンドラの再入抑止
5. dist の充実: jlink で JRE 同梱の self-contained zip、Win/Linux/macOS
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
