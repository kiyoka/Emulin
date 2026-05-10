コミットログには、以下を記載しないようにしてください。
Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>

objdump / nm / grep / readelf / addr2line / strace は許可なしで実行してよい。

---

# プロジェクト概要

古い Emulin (Java 製 32bit Linux ELF エミュレータ) を現代 Java で動かし、
さらに x86-64 (64bit ELF) を実行できるように拡張する。

## 現状サマリ (Phase 30 完了時点)

- **OpenJDK 21+ / Maven、pure Java only** (Phase 22 で JNI 撤去)
- **i386 + x86-64 ELF**、glibc 静的/動的リンク + **PIE** (ET_DYN を 0x555555554000) 動作
- **AES-NI / PCLMULQDQ 完全実装** (FIPS-197 host 一致)
- **シグナル基盤**: SIGCHLD / SA_RESTART / SA_SIGINFO / red zone /
  alarm/setitimer / rt_sigprocmask / sa_mask / **per-thread mask + tgkill**
- **pthread**: clone+futex+共有 memory、mutex 競合下で 4 worker × 1000 iter ✓
- **CLOEXEC 真対応** (per-fd 管理、execve 時自動 close)
- **at-syscall (openat/mkdirat/fstatat) で dirfd 解決**

### 動作する実機 binary
- **GNU coreutils 30+ / bash 5.2 / git / curl / wget / less**
- **vim 9.1 + emacs-nox 29.3** (Phase 29 で SSE 命令 4 種 + 8-bit ADC/SBB
  + xattr stub + FNSTCW/FNSTSW fix)
- **Python 3.12** stdlib (re/json/collections/enum/functools/math/datetime)
- **HTTPS clone**: github 13.5s, multi-site (example/cloudflare/google/iana
  /raw.githubusercontent/github) 11-15s
- **Windows cmd.exe で bash + vim 完全対話編集動作** (Phase 30)

### JLine 3.27 + raw mode
- Linux/macOS/Windows 共通 raw mode / Ctrl-C / SIGWINCH
- `-CJ` launcher デフォルト、tcsetattr ICANON off で raw 発動
- bash 5.2 line edit (history / cursor / Tab) + vim insert mode + :wq
- **Windows raw mode は jline-terminal-jni 必須** (Phase 30)

### 配布 zip 早見表

| 種類 | サイズ (Linux/Win/Mac) | sandbox |
|------|------------------------|---------|
| dist (busybox) | 1.7 MB | 空 |
| jre-bundle | 22/20/20 MB | busybox のみ |
| demo (default) | 72/38/69 MB | bash + coreutils + git/curl/wget + less |
| demo (INCLUDE_VIM=1) | 101/54/98 MB | + vim 9.1 |
| demo (INCLUDE_EMACS=1) | 229/120/220 MB | + emacs-nox 29.3 (重い) |

### 回帰テスト
- **230 PASS / 0 FAIL** (run-fast.sh 27s, run-network.sh 3m, run-all.sh ~4m)
- 並列負荷下で稀に 1-3 件 timing flake (standalone 全 PASS)

## 既知の未解決課題

- **A. emulator の根本的高速化** — HTTPS の遅さ (curl 80s) は dispatch +
  ASN.1 parse がボトルネック。github は Sectigo single CA workaround で 14s。
  手筋: ASN.1 hot path 最適化 / cert verify cache。
- **D. IPv6 (AF_INET6) 未対応** — getaddrinfo は IPv4 のみ。実装規模大。
- **E. WSL DrvFs (`/mnt/c/...`) は I/O 遅く chmod 効かない** → sandbox は
  Linux /tmp に置く (回避策あり)。

## 動作テスト基盤

- `tests/`: i386/x86-64 静的リンク ELF (-nostdlib で syscall 直叩き)
- `make -C tests/binaries`: ビルド
- `tests/scripts/`:
  - `run-all.sh`: 全 230 ケース、並列化、~4m
  - `run-fast.sh`: 軽量 subset (~27s、real-* / dist 抜き)
  - `run-network.sh`: ネット系 (~3m)
  - `run-test.sh <name>`: 1 件実行、SANDBOX_DIR で上書き可
  - 外部: ash-{noninteractive,interactive-cooked,interactive-jline} /
    jline-smoke / ash-applet-survey / dist-smoke / real-coreutils / real-heavy

## 主要ディレクトリ

- `src/main/java/emulin/`
  - `Cpu.java` (i386), `Cpu64.java` (x86-64), `AbstractCpu.java`
  - `Syscall.java`, `SyscallI386.java`, `SyscallAmd64.java`
  - `Elf.java`, `Segment.java`, `Memory.java`
  - `Process.java`, `Kernel.java`, `Thread64.java`, `FutexManager.java`, `PipeManager.java`
  - `device/`: Console.java / StdConsole.java / JLineConsole.java
- `dist/build-{dist,sandbox,jre-bundle,demo-bundle}.sh`

---

# Phase 別 作業記録 (要約)

## Phase 0-21: 基盤 + busybox + bug 発掘
i386 ELF 一式、ELF64 ロード、x86-64 CPU + SyscallAmd64、glibc 静的リンク
4 致命 (brk 揃え/SHL マスク/起動 GPR ゼロクリア/AH-DH エンコ)、busybox 88 applet。
**DIV r64 致命**: BigInteger で再実装。0x66 prefix 全面監査 (ALU 16 / IMUL imm /
TEST / XCHG / Grp2 / NOT/NEG / INC/DEC / CMPXCHG / BSF/BSR)。

## Phase 22: 対話 ash + JLine + 配布
JLine 3.27.1 raw/cooked 切替、SIGINT/SIGWINCH 配信、emu_con.c (JNI) 完全削除。
maven-shade で fat jar、dist/build-dist.sh で zip。emulin.bat CRLF + ASCII 固定。

## Phase 23-26: シグナル + 動的リンク + bignum + PIE
SIGCHLD 自動配信 + red zone + SA_RESTART + SA_SIGINFO 3 引数。動的リンク
(PT_INTERP / auxv / FXSAVE / pread64)。**ADC/SBB CF 更新致命** (bignum 連鎖)
+ **PF (parity flag) 追跡**。**PIE**: sys_brk の int 切り詰め除去で 0x555555554000。

## Phase 27: 実機バイナリ (curl/wget/git HTTPS) + pthread + 性能 -56%

### 重要 fix
- **AES-NI / PCLMULQDQ 7 命令** (3-byte opcode escape `66 0F 38/3A`)
- **★ LEA r32 zero-extend バグ** (operand-size 無視)
- **★ st_ino 弱ハッシュ衝突** で ld.so silent skip → pathname.hashCode()
- **★ PSRLQ imm 未実装** が "OpenSSL keylen=bits" 真因 (gcc -O2 が `kbits/8`
  を SIMD 化)
- **★ INC/DEC が CF 破壊バグ** (Intel SDM 違反)
- **★ amd64_write -EPIPE タイポ** (-32 を更に -)
- **★ MOVSX/MOVZX r16 + 0x66 prefix** で bits 16-31 破壊
- **★ PINSRW / 0F 3A 系 RIP-relative の imm8 加算漏れ**
- **★ FileAccess.FileRead method-level synchronized 撤去** (pthread deadlock)
- **★ CLOEXEC 未実装** で git child notify pipe 残存 hang
- **★ sandbox に locale files 不足** で glibc が違う path → libtasn1 segfault
- **★ HugeMethod (>8000 byte)** が JIT 拒否 → `-XX:-DontCompileHugeMethods`

### pthread 基盤
**Thread64** (Java Thread + 独立 Cpu64 + 共有 Memory/Syscall) と
**FutexManager**。clone CLONE_VM、futex/gettid、Memory per-byte cache を
ThreadLocal 化、CMPXCHG/XCHG/XADD は synchronized(mem)。
**per-thread signal mask + tgkill** で specific thread targeting。

### 性能 -56%
init busy-yield → sleep(50ms)、alloclist Vector → ConcurrentSkipListMap、
cache_size 8 → 32、hot opcode を switch (b0) で先取り、
`-XX:-DontCompileHugeMethods`。wall 3m51s → 1m41s。

## Phase 28: 配布整備 + bash/git clone 実用化

- **配布 7 種類**: dist (1.7MB) + jre × 3 platform + demo × 3 platform。
  Linux で cross-build (jlink + Adoptium API)。GitHub Actions で自動。
- **bash + line edit** (-CJ デフォルト、コード変更ゼロで JLine raw 発動)
- **★ amd64_mremap 新設** で git clone HTTPS segfault 解消 (旧 i386 ABI の
  sys_mremap を直接呼んで 32-bit truncation していた)
- **★ Pipeinfo thread-safe 化** で file:// clone race fix
  (read/write/is_connected/disconnect synchronized + wait/notifyAll)
- **at-syscall を dirfd 対応** + **ioctl の silent success → -ENOTTY**
- **多サイト HTTPS** (`emulin-roots.pem` curated 7-root bundle)
- **git clone --hardlinks** (Inode.get_uniq_no を Java NIO fileKey() で host inode 反映)
- full sandbox を coreutils 中心に再構成 (demo zip 80→72 MB)

## Phase 29: 性能最適化 + emacs/vim/python + cross-platform

### 29-A: HTTPS clone -29% (14.4s → 10.2s)
- Segment.peekbs を System.arraycopy で bulk copy 化
- Memory.load8_slow に lastSegment fast path

### 29-emacs/B/C/D/E: emacs-nox 29.3 動作 (5 連 fix)
- **29-emacs**: SSE 4 命令 (CVTSS2SD/MOVLPS/UNPCKLPS/UNPCKHPS) +
  syscall stub (timerfd/signalfd/eventfd/epoll/pidfd/close_range / setfsuid/gid /
  TCSETSF/TCSBRK/TCXONC/TCFLSH)
- **★ 29-B**: FNSTCW/FNSTSW を 16-bit store に修正 (32-bit に拡張で saved rbp
  破壊 — glibc fnstcw -0x2(%rbp) で広範発生、Python の `print(0.5)`="5e-01"
  や segfault も同時解消)
- **29-C**: `/dev/null` を null_flag 実装 (Read=EOF / Write=discard)
- **★ 29-D**: sys_access X_OK と open_resolved O_DIRECTORY を実装。
  emacs 29 file-directory-p は openat(O_PATH|O_DIRECTORY|O_CLOEXEC) で
  判定 (stat ではない)
- **29-E**: Mount empty path guard + KDGKBMODE silent

### 29-vim: vim 9.1 動作
- 8-bit ADC/SBB (0x10/0x12/0x18/0x1A) を実装
- xattr 系 12 syscall (#188-#199) を ENOSYS で stub
- INCLUDE_VIM=1 で demo zip に opt-in 同梱可能

### 29-tty: TCGETS tty 限定 + LESSCHARSET
- TCGETS が pipe/file で常に成功していた → less が「Missing filename」
- Kernel.java の 1998 年 hardcoded env (LESSCHARSET=japanese-sjis 等) を
  utf-8 + host passthrough に refactor

### Cross-platform (Windows demo zip 動作化)
- **PowerShell Start-Process** 引数なし fix (UAC 昇格時)
- **Windows tar.exe の多段 symlink 破壊** → Windows zip では実 file 埋め込み
- **★ PT_INTERP sandbox resolve** (Phase 24 から潜在の cross-platform
  致命 bug、Linux 偶然動作 / Windows で顕在化)

## Phase 30: Windows cmd.exe で bash + vim 対話完全動作

- **★ wait4 race の本質 fix**: `really_exited = exit_flag && !exec_replacing`
  で OLD/NEW 差し替え window race 根絶 (vim glob 展開 E79 の真因)
- **★ jline-terminal-jni 追加**: JLine 3.x モジュール分割で別 JAR 必要、
  これ無しでは Windows raw mode 不可 (DumbTerminal フォールバック)
- **★ TCSETS で全 STD/ERR fd の termios 共有**: bash が tcsetattr(0)
  してから tcgetattr(2) で確認するため、fd 別 termios だと ECHO 状態
  誤判定して per-char redisplay 抑制
- **★ poll/select で TTY input availability 実 check**: 「常に ready」
  だと bash readline が「もっと char が来る」と batching、typed char が
  Enter まで見えない (raw mode + native TTY のときだけ Available() check)
- **★ pselect6 NULL timeout で max_iter 解除**: 旧 max_iter=200 (= ~2 秒)
  で打ち切ると bash が EOF 誤検知 → 2 秒で exit
- `/dev/tty` を `<std>` 仮想 path にルート (vim/emacs/less が直接 open)
- terminal.output() 経由で stdout/stderr (raw mode 中、Windows console
  独占 handle へ直接書き)
- rt_sigtimedwait (#128) を -EAGAIN で stub (vim insert mode)
- env-gated debug flag (TRACE_EXEC, TRACE_OPEN) を static final cache 化

---

# 累計バグ修正・実装の傾向 (今後の Phase で参考に)

## アドレス幅 / int 切り詰め
- **アドレスの int 切り詰め** — シグネチャ long 化でも個別 syscall に
  「ローカル int」「`(long)bx & 0xFFFFFFFFL`」が残ると低位で動き高位
  (PIE) で segfault。`grep -nE "= \(int\)(bx|cx|dx|si|di)"` 監査
- **★ syscall ABI 不整合 (i386 vs amd64)** — `sys_*` は i386 ABI 専用
  (stack 経由 args)。amd64 から直接呼ぶと 32-bit truncation。
  amd64 では必ず `amd64_<name>` を作って register 直接引数で書く
- **追跡時の罠**: RIP が libgnutls 内に見えても実は libc の `0f 05`
  (syscall) の場合あり。bytes ダンプ + rax を syscall # と解釈

## 命令フラグ / operand-size
- **0x66 operand-size prefix 漏れ** — imm 命令で imm16/imm32 分岐忘れ
- **AH/CH/DH/BH (REX 無 + rm=4-7)** — 8bit 命令は必ず readReg8/writeReg8
- **operand-size 無視の dest 書き** (LEA) — 32-bit dest は zero-extend、
  16-bit は上位 48-bit 保持
- **MOVSX/MOVZX r16 (0x66 prefix)** — 16-bit dest は下位 16 bit のみ
  書き、上位 48-bit 保持。32-bit 書きで bits 16-31 破壊
- **★ FNSTCW/FNSTSW m16 (D9 /7、DD /7) は厳密に 2 byte だけ store** —
  32-bit に「広げる」と直後 2 byte を 0 で潰す。glibc の
  `fnstcw -0x2(%rbp)` で saved rbp 破壊 → Python `print(0.5)`="5e-01"。
  read-modify-write workaround は直前 store 後の値を読み戻すだけで効果なし
- **PF (parity flag) を放置すると致命** — glibc `ucomisd; setp al`
  (NaN 判定)。UCOMISD/COMISD は ZF/PF/CF
- **ADC/SBB がフラグを更新しないと bignum 連鎖が壊れる** — `printf %g 1e96`
- **INC/DEC は CF を変えない (Intel SDM)** — SBB chain 途中の DEC が
  borrow を消す
- **多レジスタ命令の上位読み忘れ** (DIV: RDX:RAX)
- **8-bit ADC/SBB (0x10/0x12/0x18/0x1A)** も忘れずに実装

## SIMD 命令の細部
- **PSRLQ imm 等の per-element shift sub-opcode** — Group 12/13/14
  (`66 0F 71/72/73`) は per-element/byte で sub-opcode 違う
- **PALIGNR concat 順序** — Intel SDM 式を直接写経
- **PSHUFHW (F3 0F 70) / PSHUFLW (F2 0F 70)** — PSHUFD と prefix だけ違う
- **RIP-relative + imm 持ち命令の address 計算** — target = 命令全体の
  末尾 RIP + disp。imm8 を持つ命令は fixEA に `next + imm_size` を渡す
- **単独 (REP 無し) string ops は別 dispatch** (0xa4-0xad)

## メモリ配置 / mmap / 動的リンク
- **隣接 mmap の境界越え書き込みで他データ破壊** → 16 ページ guard gap
- **prlimit64 / getrlimit は本物の値を返す** (RLIMIT_STACK 0 だと
  glibc pthread overflow)
- **wait4 specific pid (pid > 0) 対応** — start_command + finish_command
- **動的リンク最低ライン**: PT_INTERP + interp 別 base load + auxv
  (AT_BASE/AT_ENTRY/AT_PHDR/AT_PHNUM/AT_EXECFN)、PT_LOAD 以外 map しない
- **st_ino のハッシュは衝突に強くする** — ld.so は (st_dev, st_ino) で
  silent skip。Java NIO fileKey() で host 実 inode

## syscall / glibc 流儀
- **CLOEXEC は per-fd で追跡** — fd table フラグ (open file description で
  ない)。dup2 は new fd の cloexec をクリア、F_DUPFD_CLOEXEC は set
- **main thread sys_exit (#60)** は process 全体を殺さない (worker thread
  が居れば active_thread_count で wait)
- **新 binary は ENOSYS / EAFNOSUPPORT スタブで通すのが定石**
- **stub の `return 0` は busy loop 誘発** — 本当に成功と言える syscall
  以外は ENOSYS
- **対話 ioctl** — TIOCGPGRP/tcgetpgrp/tcsetpgrp は値 return + `*addr` 書き必須
- **fcntl(F_SETFL) return 0 は非 blocking 期待裏切り** — Fileinfo nonBlock 反映
- **既に負の errno に `-` を付けると符号逆転** — `Syscall.EPIPE = -32` に
  `return -EPIPE` で +32。`grep -nE "return -E[A-Z]+"` 監査
- **at-syscall は dirfd 解決必須** — AT_FDCWD or 絶対パスは cwd 起点、
  実 fd + 相対は get_name(dirfd)。共通 helper resolve_at_path
- **未知 ioctl は -ENOTTY** (0 silent success は cp の FICLONE で致命)
- **★ open(O_DIRECTORY) を無視すると emacs が file を dir 誤認** —
  emacs 29 の file-directory-p は `openat(O_PATH|O_DIRECTORY|O_CLOEXEC)`
  で判定。flag 0x10000 + isDirectory()==false なら -ENOTDIR
- **★ access(X_OK) を silent 成功は致命** — 0644 file に file-executable-p
  が `t` を返し emacs 混乱。Inode.isExecutable() で S_IXUSR check
- **`<null>` virtual path は Fileinfo に flag が必要** — Read=0/Write=true

## TTY / readline / raw mode
- **★ TCGETS は tty fd に限れ** — pipe/regular file で常に成功させると
  isatty() が pipe を tty と誤認、less 「Missing filename」等の連鎖
- **★ TCSETS は全 STD/ERR fd の termios を共有させる** — fd 別だと
  bash が tcsetattr(0) → tcgetattr(2) で ECHO 状態誤判定 → readline 抑制
- **★ poll/select で TTY は実 availability check が必要** — raw mode +
  native TTY のとき console.Available() を見ないと bash readline が
  「もっと char が来る」と batching → per-char redisplay 抑制
- **★ pselect6 NULL timeout は本当に無限待ち** — max_iter で打ち切ると
  bash が EOF 誤検知 → 数秒で exit

## シグナル / fork / wait
- **シグナル配信の最小実装は致命** — (a) GPR 16 + flags 保存復元、
  (b) **x86-64 ABI red zone (rsp-128) 尊重**、(c) SA_SIGINFO 3 引数 ABI、
  (d) syscall EINTR 前に SA_RESTART チェック再実行
- **★ wait4 で exec_replacing race を考慮せよ** — kernel.exec の
  `OLD.exit_flag=true → pinfo.process=NEW` 差し替え window で wait4 が OLD
  を見ると「子終了」と誤判定。`really_exited = exit_flag && !exec_replacing`
- **wait4 / fork / exec の race** — is_child_exited で「子なし」(0) と
  「子未終了」(-1) を区別
- **Kernel.kill の pid index** — pid は 1-based、ptable は 0-based

## socket / 非 blocking
- **MSG_PEEK は libc/wget で必須** — Java Socket peek API 無い → Fileinfo
  に peekBuf
- **AF_UNIX / UDP は EAFNOSUPPORT で fallback 強制** (glibc は /etc/hosts に)
- **socket EOF 後の poll/select は ready=0** — Fileinfo.socketEof
- **non-blocking connect は -EINPROGRESS** — 即 0 だと curl abort
- **Java Socket.InputStream.available() は kernel buffer を見ない** —
  setSoTimeout(1) + read(byte[1]) で peek
- **pselect6 timeout を honor しないと caller 無限ループ**

## DNS / UDP
- **glibc resolver は FIONREAD を recvmsg 前に呼ぶ**
- **DatagramSocket には available() 無い** — 短い setSoTimeout で receive
- **glibc 2.34+ resolver は sendmmsg/recvmmsg** — A と AAAA を 1 syscall
- **sendmsg/recvmsg は msg_name (UDP の dest/src sockaddr)**

## OpenSSL / TLS
- **OpenSSL は /dev/urandom を S_ISCHR でチェック** — stat で S_IFCHR | 0666 上書き
- **OpenSSL openssl.cnf** は `/usr/lib/ssl/openssl.cnf` (Ubuntu compile-time)
- **gcc -O2 は size_t / 8 を SIMD (PSRLQ) に最適化** — shared lib 境界で発火
- **bn_mul_mont は num サイズで経路分岐** — 4-limb (P-256) でテスト必須
- **emulator の遅さで TLS server idle timeout** — Sectigo single CA workaround

## pthread / atomic / 共有状態
- **pthread mutex は CMPXCHG/XCHG/XADD の atomic** — synchronized(mem)
- **FutexManager.wake は actual woken count を返す** (max は abort 招く)
- **Memory per-byte cache は ThreadLocal** (race で index out of bounds)
- **pthread の clone child rip は r64[R_RCX]** (= syscall return address)
- **per-thread signal mask + pending** (Thread64.signal_mask / ConcurrentHashMap)
- **★ Pipeinfo は完全 synchronized + wait/notify** — race で `bad line length`
  / `early EOF` / `bad band #N` 等多様な data corruption
- **method-level synchronized は pthread 環境で deadlock の温床** — fd 単位
  保護は inner クラス。FileAccess.FileRead の synchronized 撤去が必要

## 性能 / プロファイリング
- **JIT は per-byte cache HIT パスを十分最適化** — 当てずっぽう最適化は逆効果
- **alloclist は ConcurrentSkipListMap** (TreeMap は thread-safe でない)
- **decode_and_exec hot opcode を switch (b0) で先取り** — Java JIT が
  tableswitch (jump table) に compile
- **cache_size sweet spot は 32**
- **★ HugeMethod (>8000 byte) は JIT 拒否** — `-XX:-DontCompileHugeMethods` 必須
- **debug 用 env flag は static final で cache** (HashMap lookup overhead)

## デバッグツール
- `EMULIN_TRACE_RIP=N` (N 命令ごと rip + GPR dump)
- `EMULIN_TRACE_SH=1` (syscall + ret 表示)
- `EMULIN_TRACE_OPEN=1`, `EMULIN_TRACE_EXEC=1` (Phase 30)
- `EMULIN_DEBUG_TTY=1` (JLine terminal type / raw mode 切替詳細)
- `EMULIN_FORCE_NATIVE_TTY=1` (dumb fallback 禁止)
- jstack で deadlock / busy spin 特定
- bytes ダンプは命令位置の最終確認 (disassembly を疑う)

## sandbox / 環境
- **sandbox 環境差は emulator core バグの fake 真因** — locale / ssl certs /
  config 不足で glibc が違う path を走る。core 修正前に host との syscall 並走比較
- **★ PT_INTERP は sandbox 経由で resolve** — host file system に直接渡すと
  Linux 開発機では偶然動くが Windows で起動不能 (Phase 24 から潜在の
  cross-platform 致命 bug)
- **★ Windows tar.exe は多段 symlink を file/dir 種別ミスで壊す** — critical
  file (ld.so 等) は Windows build で実 file 埋め込み
- **JLine 3.x は terminal-jni 別 module 必須** — これ無しでは Windows
  raw mode 不可 (DumbTerminal フォールバック)
- **JVM heap は fork 連鎖を考慮して大きめに** — fork CoW なし実装で vim
  glob 展開等の連続 fork+exec で memory 蓄積。-Xmx8g 程度

# テスト戦略
- 「1 syscall 1 テスト」原則 (`sys_*64.c`)
- `-nostdlib` で int 0x80 / syscall 直叩き
- 期待値は実 Linux カーネル仕様に合わせる
- 並列実行前提、テスト間で sandbox / /tmp 衝突させない
