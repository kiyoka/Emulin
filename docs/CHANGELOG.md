# Emulin 開発履歴 (Phase 別作業記録)

> このファイルは旧 `.claude/CLAUDE.md` から移設した開発履歴。
> 「これまで何をやったか」の参照用。日々の作業ルール・落とし穴は
> [`lessons.md`](lessons.md)、配布まわりは [`build-and-dist.md`](build-and-dist.md) を参照。
> より細かい粒度は `git log` を参照。

## Phase 別 作業記録 (要約)

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
- **issue #9 (AF_INET6 / AF_UNIX)**: AF_UNIX client (#34、ssh-agent 経由)、
  AF_INET6 client TCP (#35) / UDP (#36) / server (#37) を実装。amd64 で長らく
  完全未実装だった accept(43)/accept4(288) syscall も #37 で新設 (v4/v6 両対応)。
  Fileinfo.family_v6 で v6 識別、sockaddr_in6 (28 byte) を bind/connect/
  sendto/recvfrom/sendmsg/recvmsg/getsockname/accept で扱う。v4 peer は
  v4-mapped (`::ffff:a.b.c.d`) で 16 byte 化。UDP の eagerly-bound ephemeral
  port を getsockname に反映 (`Fileinfo.get_port()` に dgram 経路追加)。
  回帰 `sys_inet6_64` / `sys_inet6_udp_64` / `sys_inet6_server_64` (hermetic)。
- **issue #41 (sshd Phase 1 MVP)**: OpenSSH sshd を emulin 上で動かして
  `ssh -i key root@host 'echo hello'` を完走 (publickey auth + non-interactive
  exec)。`-D -d -e` (debug 単発接続) mode 限定。fork-rexec 経路は未対応 (Phase 2)。
  主要 fix:
  (a) **socketpair / pipe の poll で data availability 判定** — 旧実装は POLLIN
      を立てず privsep monitor↔preauth IPC が ∞ block していた。PipeManager に
      pipe_available() を追加して amd64_poll の pipe 経路で POLLIN を判定。
      これで KEX (sntrup761x25519-sha512) が完走。
  (b) **STREAM socket の SubProcess 抑止** — 旧 SubProcess は ring buffer に
      socket bytes を先読みするが、STREAM の FileRead は finfo.Read で直接
      socket から読むため、2 thread が同じ socket から read を race して
      sshd の SSH packet length が壊れる ("Bad packet length 0")。STREAM では
      SubProcess.run を即 return、FileRead は subprocess.read_byte_top を
      bypass。
  (c) **Fileinfo.close の subprocess.close ガード** — dup2(newsock, 3) +
      close(newsock) で fd 3 read が EOF を返す regression を修正。opened < 1
      の最後の close でだけ subprocess を止める。
  (d) **getsockopt(SOL_IP, IP_OPTIONS) を optlen=0 で返す** — 旧 4 byte ゼロ
      返却だと sshd が IP source routing を検出して cleanup_exit(255)。
  (e) **setresuid/setresgid (117/119) + setuid/setgid (105/106) の paranoia** —
      sshd の permanently_set_uid は setresgid 後に setegid(0) で「root に
      戻れないこと」を確認、戻れると fatal。process.uid/gid を実 tracking して
      non-root から 0 への戻しを EPERM。
  (f) **syscall 116 を setgroups と誤同定から修正** — 旧 commit で 116 を
      klogctl/syslog としていたが、x86-64 では 116 = setgroups (klogctl は 103)。
      sshd の privsep child が setgroups で EPERM だと fatal exit。
  (g) **NSS dlopen は無罪、原因は /bin/sh 不在** — sshd の allowed_user は
      pw_shell の existence を check するため、sandbox に bash + /bin/sh
      symlink + libtinfo.so.6 を bundle する必要。
  Phase 1 sandbox bundling は `INCLUDE_SSHD=1 dist/build-sandbox.sh` で完結
  (sshd + ホストキー生成 + sshd_config + NSS lib + /etc/{nsswitch,shells,
  passwd,group} + /root/.ssh + /run/sshd + /var/empty を配置)。回帰
  `tests/scripts/sshd-smoke.sh` は ssh client から `echo HELLO-FROM-EMULIN-SSHD`
  を完走するか hermetic に検証 (`run-network.sh` 内)。
- **issue #41 (sshd Phase 2 — PTY)**: `ssh -tt root@host` で interactive bash
  session (`pwd`, `echo $UID`, `exit` 等) が動作。新規 `PtyManager` で ptn
  ↔ (pipe_a, pipe_b) を Kernel-wide 管理し、/dev/ptmx / /dev/pts/N の open を
  双方向 pipe pair で繋ぐ。ioctl は TIOCGPTN (0x80045430) / TIOCSPTLCK
  (0x40045431) / TIOCGPTPEER (0x5441 → EINVAL で fall back) を実装。
  stat / fstat / newfstatat で pty path と pty fd の両方を S_IFCHR + 同一
  st_dev/st_rdev で返して `ttyname(3)` の match 要件を満たす。
  readlink(/proc/self/fd/N) が pty slave なら "/dev/pts/N" を返す経路を
  追加。sys_readlink (89) を amd64_readlinkat に redirect (旧 EINVAL stub)。
  chmod(/dev/pts/N) は no-op success (sshd の pty_setowner)。
  TCGETS は pty fd を tty 扱い (isatty 通過)。
- **issue #41 (sshd Phase 3 — fork-rexec + interactive 安定化)**:
  (a) `sshd -D` (debug `-d` 無し) の fork-rexec mode で hang していた
      regression を修正。原因は amd64_write / amd64_read で fd=-1 を
      `flist.elementAt(-1)` に渡して ArrayIndexOutOfBoundsException が
      thrown され Process.run thread が死に、親が wait4 で永遠に block。
      無効 fd は -EBADF を返すように guard 追加。
  (b) sys_dup / sys_dup2 / fcntl(F_DUPFD*) も同じ invariant (oldfd が
      null / 範囲外なら EBADF)。Phase 2 で fix 済の path を確実化。
  (c) TIOCSCTTY (0x540e) / TIOCNOTTY (0x5422) を no-op success。session
      leader が controlling tty を設定/解除する ioctl、emulator では
      session/process group の概念が緩いので無視で OK。"Unsupported ioctl"
      warning を消す。
  これで sshd は `-D -e -p N` の standard daemon mode + interactive PTY
  両対応。`ssh user@host`, `ssh -tt user@host`, `ssh user@host cmd` の 3
  モード全て動作。
