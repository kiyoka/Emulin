# 累計バグ修正・実装の傾向 (今後の Phase で参考に)

> このファイルは旧 `.claude/CLAUDE.md` から移設した知見集。
> 特に効く鉄則は `.claude/CLAUDE.md` 本体に残してあり、ここはその全カテゴリの
> 詳細版。新しい syscall / 命令 / 環境互換の実装前にざっと目を通すこと。
> 開発履歴は [`CHANGELOG.md`](CHANGELOG.md)、配布は [`build-and-dist.md`](build-and-dist.md)。

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
- AF_UNIX client (#34) / AF_INET6 client+server (#35-#37) は実装済。
  未対応 family は EAFNOSUPPORT で fallback させる。
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
