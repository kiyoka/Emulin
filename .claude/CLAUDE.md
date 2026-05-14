# 指示

- コミットログに `Co-Authored-By: Claude ...` 行を含めない。
- ディスク容量が足りない場合は `/mnt/d/gitwork` を使用 (約 500GB 空き)。

---

# プロジェクト概要

古い Emulin (Java 製 32bit Linux ELF エミュレータ) を現代 Java で動かし、
x86-64 (64bit ELF) も実行できるよう拡張する。

- **OpenJDK 21+ / Maven、pure Java only** (Phase 22 で JNI 撤去)
- **i386 + x86-64 ELF**、glibc 静的/動的リンク + **PIE** (ET_DYN を 0x555555554000)
- **AES-NI / PCLMULQDQ 完全実装** (FIPS-197 host 一致)
- **シグナル基盤**: SIGCHLD / SA_RESTART / SA_SIGINFO / red zone /
  alarm/setitimer / rt_sigprocmask / per-thread mask + tgkill
- **pthread**: clone+futex+共有 memory、CLOEXEC 真対応 (per-fd)
- **at-syscall (openat 等) で dirfd 解決**

### 動作する実機 binary
GNU coreutils 30+ / bash 5.2 / git / curl / wget / less / vim 9.1 /
emacs-nox 29.3 / Python 3.12 stdlib。HTTPS clone 動作 (github ~14s)。
Windows native でも cmd.exe 上で bash/vim/emacs 対話編集 + git clone 完走。

### JLine 3.27 + raw mode
Linux/macOS/Windows 共通 raw mode / Ctrl-C / SIGWINCH。`-CJ` launcher
デフォルト、tcsetattr ICANON off で raw 発動。**Windows raw mode は
jline-terminal-jni 必須**。

### 配布 zip 早見表
| 種類 | サイズ (Linux/Win/Mac) | sandbox |
|------|------------------------|---------|
| dist (busybox) | 1.7 MB | 空 |
| jre-bundle | 22/20/20 MB | busybox のみ |
| demo (default) | 72/38/69 MB | bash + coreutils + git/curl/wget + less |
| demo (INCLUDE_VIM=1) | 101/54/98 MB | + vim 9.1 |
| demo (INCLUDE_EMACS=1) | 229/120/220 MB | + emacs-nox 29.3 |

INCLUDE_TIG / INCLUDE_PERL / INCLUDE_SSH で tig / Perl 5 / openssh-client も同梱可。

### 回帰テスト
230 PASS / 0 FAIL。run-fast.sh 27s / run-network.sh 3m / run-all.sh ~4m。
並列負荷下で稀に timing flake (standalone 全 PASS)。

## 既知の未解決課題
- **A. emulator の根本的高速化** — HTTPS の遅さは dispatch + ASN.1 parse が
  ボトルネック。手筋: ASN.1 hot path 最適化 / cert verify cache。
- **D. IPv6 (AF_INET6) 未対応** — getaddrinfo は IPv4 のみ。
- **E. WSL DrvFs (`/mnt/c/...`) は I/O 遅く chmod 効かない** → sandbox は Linux /tmp に。

## 動作テスト基盤
- `tests/`: i386/x86-64 静的リンク ELF (-nostdlib で syscall 直叩き)。
  `make -C tests/binaries` でビルド。
- `tests/scripts/`: `run-all.sh` (全 230) / `run-fast.sh` (軽量 ~27s) /
  `run-network.sh` (~3m) / `run-test.sh <name>` (1 件、SANDBOX_DIR で上書き可)。

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

- **Phase 0-21**: i386/x86-64 CPU + syscall 基盤、glibc 静的リンク、busybox
  88 applet。DIV r64 を BigInteger 再実装、0x66 prefix 全面監査。
- **Phase 22**: 対話 ash + JLine raw/cooked 切替、JNI 完全削除、fat jar 配布。
- **Phase 23-26**: SIGCHLD/red zone/SA_RESTART/SA_SIGINFO、動的リンク
  (PT_INTERP/auxv/FXSAVE)、ADC/SBB CF + PF 追跡、PIE 化。
- **Phase 27**: 実機 binary (curl/wget/git HTTPS)、pthread 基盤 (Thread64 +
  FutexManager)、性能 -56%。AES-NI 7 命令、LEA r32 zero-ext / PSRLQ imm /
  INC-DEC CF 破壊 / CLOEXEC 等の致命 fix 多数。
- **Phase 28**: 配布 7 種類、bash line edit、amd64_mremap 新設で git clone
  segfault 解消、多サイト HTTPS。
- **Phase 29**: HTTPS clone -29%、emacs-nox/vim 9.1/Python 動作 (SSE 命令 +
  FNSTCW/FNSTSW 16-bit fix + O_DIRECTORY/access X_OK)、cross-platform 化。
- **Phase 30**: Windows cmd.exe で bash+vim 対話完全動作 (wait4 race 本質 fix、
  jline-terminal-jni、TCSETS termios 共有、poll/select TTY availability check)。
- **Phase 31-32**: leak fix (release_buffers)、text/mmap の reference share で
  fork 軽量化 (vim 5x -43%)。
- **Phase 33**: Windows native で git clone / rm -rf / Ctrl-C 実用化。
  rename/unlink を NIO Files に、HTTPS clone 完走 (public DNS / HTTP/1.1 強制 /
  SO_RCVBUF 4MB)、lstat/newfstatat AT_SYMLINK_NOFOLLOW 完備、dangling symlink
  sweep、sun.misc.Signal で Ctrl-C panic 回避 + jdk.unsupported 同梱。
- **Phase 34-A2〜A5**: Cpu64.decode_and_exec を switch-only 化 (-85% 行数)、
  x86-64 → Java bytecode 翻訳 JIT (`EMULIN_USE_JIT=1`、default off)。
  basic-block 単位 + hot threshold + memory operand + SIMD 命令対応。
  汎用 binary では neutral、crypto/SIMD heavy (curl HTTPS / AES) で net -13〜14%。
- **Phase 34-A6〜A13** (issue #4): Memory hot path 最適化。refillInsnBuf bulk
  arraycopy、load/store を lastSegment fast path + 2-LRU 化、Memory に final 付与。
  累計 sha256sum 5MB **-36%**。
- **issue #3-#5**: emacs-nox 29.3 を Windows native で完全対話動作。5 件連鎖
  (amd64_poll wait-loop / pselect6 max_iter 撤去 + bitmap write-back /
  FIONREAD TTY 対応 / enterRawMode で ISIG=off)。

---

# 累計バグ修正・実装の傾向 (今後の Phase で参考に)

## アドレス幅 / int 切り詰め
- **アドレスの int 切り詰め** — long 化しても個別 syscall に `(int)bx` や
  ローカル int が残ると低位で動き高位 (PIE) で segfault。
- **syscall ABI 不整合 (i386 vs amd64)** — `sys_*` は i386 ABI 専用 (stack 経由)。
  amd64 からは必ず `amd64_<name>` を作って register 直接引数で書く。
- 追跡時の罠: RIP が libgnutls 内に見えても実は libc の `0f 05` (syscall)。

## 命令フラグ / operand-size
- **0x66 operand-size prefix 漏れ** — imm 命令で imm16/imm32 分岐忘れ。
- **AH/CH/DH/BH (REX 無 + rm=4-7)** — 8bit 命令は readReg8/writeReg8。
- **operand-size 無視の dest 書き** — 32-bit dest は zero-extend、16-bit は上位保持
  (LEA / MOVSX/MOVZX r16)。
- **FNSTCW/FNSTSW m16 は厳密に 2 byte だけ store** — 広げると saved rbp 破壊。
- **PF (parity flag)** — glibc の NaN 判定 `ucomisd; setp` で必要。
- **ADC/SBB はフラグ更新必須、INC/DEC は CF を変えない** (Intel SDM)。
- **8-bit ADC/SBB (0x10/0x12/0x18/0x1A)** も実装。多レジスタ命令の上位読み忘れ注意。

## SIMD 命令の細部
- **per-element shift sub-opcode** (Group 12/13/14 `66 0F 71/72/73`) はバイト/要素で違う。
- PALIGNR concat 順序 / PSHUFHW(F3)・PSHUFLW(F2) は prefix 違いを SDM 写経。
- **RIP-relative + imm 命令の address** — target = 命令末尾 RIP + disp。
- 単独 (REP 無し) string ops は別 dispatch (0xa4-0xad)。

## メモリ配置 / mmap / 動的リンク
- 隣接 mmap の境界越え書き込み対策に 16 ページ guard gap。
- prlimit64 / getrlimit は本物の値を返す (RLIMIT_STACK 0 だと pthread overflow)。
- 動的リンク最低ライン: PT_INTERP + interp 別 base load + auxv、PT_LOAD 以外 map しない。
- **st_ino のハッシュは衝突に強く** — ld.so は (st_dev, st_ino) で silent skip。
  Java NIO fileKey() で host 実 inode。

## syscall / glibc 流儀
- **CLOEXEC は per-fd で追跡** (fd table フラグ)。dup2 は clear、F_DUPFD_CLOEXEC は set。
- main thread sys_exit (#60) は process 全体を殺さない (worker 居れば wait)。
- 新 binary は ENOSYS / EAFNOSUPPORT スタブで通す。**stub の `return 0` は
  busy loop 誘発** — 成功と言える syscall 以外は ENOSYS。
- 対話 ioctl (TIOCGPGRP 等) は値 return + `*addr` 書き必須。未知 ioctl は -ENOTTY。
- fcntl(F_SETFL) は Fileinfo nonBlock に反映。
- **既に負の errno に `-` を付けると符号逆転** — `grep -nE "return -E[A-Z]+"` 監査。
- at-syscall は dirfd 解決必須 (共通 helper resolve_at_path)。
- **open(O_DIRECTORY)** 無視で emacs が file を dir 誤認、**access(X_OK)** silent
  成功も致命 (emacs file-executable-p)。

## TTY / readline / raw mode
- **TCGETS は tty fd に限れ** — pipe/file で成功させると isatty() 誤認。
- **TCSETS は全 STD/ERR fd の termios を共有** — fd 別だと bash が ECHO 誤判定。
- **poll/select で TTY は実 availability check** — raw mode + native TTY のとき
  console.Available() を見ないと bash readline が batching。
- **pselect6 NULL timeout は本当に無限待ち** — max_iter 打ち切りで bash EOF 誤検知。

## シグナル / fork / wait
- シグナル配信の最小実装: GPR+flags 保存復元 / red zone (rsp-128) 尊重 /
  SA_SIGINFO 3 引数 ABI / EINTR 前に SA_RESTART 再チェック。
- **wait4 で exec_replacing race を考慮** — `really_exited = exit_flag &&
  !exec_replacing`。is_child_exited で「子なし」(0) と「子未終了」(-1) を区別。
- Kernel.kill の pid は 1-based、ptable は 0-based。
- signal check の間引きは禁忌 — mask 解除直後に確実に handler 発火が必要。

## socket / 非 blocking / DNS
- MSG_PEEK は libc/wget で必須 (Fileinfo に peekBuf)。
- AF_UNIX / UDP は EAFNOSUPPORT で fallback 強制。
- socket EOF 後の poll/select は ready=0 (Fileinfo.socketEof)。
- non-blocking connect は -EINPROGRESS。Java Socket.available() は kernel buffer
  を見ない → setSoTimeout(1) + read(byte[1]) で peek。
- glibc resolver は FIONREAD を recvmsg 前に呼ぶ。2.34+ は sendmmsg/recvmmsg。
  sendmsg/recvmsg は msg_name (UDP dest/src sockaddr)。

## OpenSSL / TLS
- OpenSSL は /dev/urandom を S_ISCHR でチェック (stat で S_IFCHR | 0666)。
- openssl.cnf は `/usr/lib/ssl/openssl.cnf`。
- **gcc -O2 は size_t / 8 を SIMD (PSRLQ) に最適化**。
- emulator の遅さで TLS server idle timeout → Sectigo single CA workaround。

## pthread / atomic / 共有状態
- pthread mutex は CMPXCHG/XCHG/XADD の atomic を synchronized(mem) で。
- FutexManager.wake は actual woken count を返す (max は abort 招く)。
- Memory per-byte cache は ThreadLocal。per-thread signal mask + pending。
- **Pipeinfo は完全 synchronized + wait/notify** — race で多様な data corruption。
- **method-level synchronized は pthread 環境で deadlock の温床** — fd 単位保護に。

## 性能 / プロファイリング
- **JIT は per-byte cache HIT パスを十分最適化済** — 当てずっぽう最適化は逆効果。
  「HotSpot がよしなに」前提の判断は当てにならない、interleaved A/B で実測する。
  小さい buffer copy でも arraycopy intrinsic は per-byte loop より速い。
- decode_and_exec hot opcode を switch (b0) で先取り (tableswitch に compile)。
- cache_size sweet spot は 32。alloclist は ConcurrentSkipListMap。
- **HugeMethod (>8000 byte) は JIT 拒否** — `-XX:-DontCompileHugeMethods` 必須。
- debug 用 env flag は static final で cache。
- JIT 設計原則: per-RIP class は megamorphic で遅い→basic-block 単位に。
  JIT machinery を hot path から完全排除。byte[] per-instruction allocation 禁忌。
  ConcurrentHashMap.get(boxed long) は hot path 不可 → flat array。
- 正確なベンチは `bash -c` 経由でなく直接 binary 起動 (`/bin/busybox ...`)。

## sandbox / 環境
- **sandbox 環境差は emulator core バグの fake 真因** — locale / ssl certs /
  config 不足。core 修正前に host との syscall 並走比較。
- **PT_INTERP は sandbox 経由で resolve** — host fs 直渡しは Linux で偶然動作 /
  Windows で起動不能。
- Windows tar.exe は多段 symlink を壊す → critical file は実 file 埋め込み。
- JLine 3.x は terminal-jni 別 module 必須。jlink には jdk.unsupported を含める
  (sun.misc.Signal)。
- JVM heap は fork 連鎖を考慮して大きめに (-Xmx8g 程度、CoW なし実装)。
- `/etc/resolv.conf` は public DNS で固定 build (WSL 由来だと Windows native で
  到達不能)。

## Windows native filesystem 互換性
- **File.renameTo / File.delete は Windows NTFS で偶発失敗** — NIO Files.move /
  Files.delete に切替、legacy → NIO → 例外 catch の多段 fallback が最も確実。
- Files.delete は broken symlink を silently 残す → NOFOLLOW で再 check、
  rmdir DirectoryNotEmpty 時に残存 symlink を強制 sweep。
- Files.delete on read-only file は AccessDenied → setWritable(true) + retry。
- Java handle の GC タイミング依存で delete 拒否 → System.gc() + sleep + retry。
- glibc lstat() = newfstatat(AT_SYMLINK_NOFOLLOW) — 両方で symlink 対応、完全な
  struct stat を埋める (st_ino=0 だと rm が skip)。getdents64 で symlink は DT_LNK。
- HTTP/2 は emulator slow CPU で server CANCEL → http.version=HTTP/1.1 強制。
- Java Socket.read IOException ≠ EOF (-1 を返す)。read -1 直後の internal buffer
  残存を drain。SO_RCVBUF=4MB に拡大。

## Windows JVM Ctrl-C / signal
- **Windows Ctrl-C → JVM default = ExitProcess** — main 最初で
  `sun.misc.Signal.handle("INT", no-op)` で default 上書き。
- raw mode 中の Ctrl-C は app に byte 0x03 で届く (emulator が SIGINT 配信不要、
  kill(-1, SIGINT) は bash 自身を殺す panic 経路)。
- cmd.exe の "Terminate batch?" prompt は抑制不可 (Windows 仕様)。

## デバッグ env / ツール
- `EMULIN_TRACE_RIP=N` / `EMULIN_TRACE_SH=1` / `EMULIN_TRACE_OPEN=1` /
  `EMULIN_TRACE_EXEC=1` / `EMULIN_DEBUG_TTY=1` / `EMULIN_FORCE_NATIVE_TTY=1` /
  `EMULIN_PROFILE_SYS=1` (syscall 別累積時間)。
- jstack で deadlock / busy spin 特定。bytes ダンプで命令位置確認 (disassembly を疑う)。
- JFR: `java -XX:StartFlightRecording=duration=15s,filename=x.jfr,settings=profile`。

# テスト戦略
- 「1 syscall 1 テスト」原則 (`sys_*64.c`)、`-nostdlib` で syscall 直叩き。
- 期待値は実 Linux カーネル仕様に合わせる。
- 並列実行前提、テスト間で sandbox / /tmp 衝突させない。
