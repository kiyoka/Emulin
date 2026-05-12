コミットログには、以下を記載しないようにしてください。
Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>

objdump / nm / grep / readelf / addr2line / strace は許可なしで実行してよい。

---

# プロジェクト概要

古い Emulin (Java 製 32bit Linux ELF エミュレータ) を現代 Java で動かし、
さらに x86-64 (64bit ELF) を実行できるように拡張する。

## 現状サマリ (Phase 34-A5 / SIMD JIT 実装後)

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
- **Windows native で git clone → rm -rf 完走 + Ctrl-C で emulin が
  終了しない** (Phase 33 系)

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

## Phase 31: leak fix で fork+exec 連鎖の OOM 解消
Process exit 時に Memory.alloclist の AllocInfo.buf と segment[] の
Segment.buf を null 化する `Memory.release_buffers()` を追加。Process が
他経路で retained されても byte[] (~10 MB / process) は GC 対象になる。
- vim 5 連続起動 (-Xmx2g): OOM 解消、heap 4 倍効率化、JIT warmup 効果
  も安定 (Run 3 GC spike 9.27s → 4.02s)
- syscall 別累積時間プロファイラ `EMULIN_PROFILE_SYS=1` も同梱

## Phase 32: text segment / mmap の reference share で fork 軽量化
read-only Segment (PF_X & !PF_W) と mmap region (PROT_EXEC & !PROT_WRITE)
を fork 時に親子間で byte[] を deep copy せず参照共有する。実 Linux の
text 共有 (file mapping share) を模擬。
- vim 5 連続: 27.0s → 15.4s (-43%)、cold 9.77s → 5.06s (-48%)
- ★ pre-existing bug fix: `Segment.stack()` が p_type と p_flags を
  取り違え (stack の p_flags=0)。fork 全 deep copy で隠れていたが
  Phase 32 で stack まで share されて顕在化

## Phase 33: Windows native で git clone / rm -rf / Ctrl-C 実用化
Windows demo zip での動作確認で多数の native filesystem / cmd.exe 互換性
問題を一気に潰した小 phase 連鎖 (33 〜 33-21)。

### 33: rename/unlink を NIO Files に
File.renameTo/delete は Windows native NTFS で偶発的に false を返す。
Files.move + REPLACE_EXISTING / Files.delete + 例外 catch で fallback
を組んだ。git config 書き込み (lock_file → rename) で EPERM になる
症状の根本対策。

### 33-2/3/4/5/6: HTTPS clone を Windows でも完走
- 33-2: sandbox /etc/resolv.conf を WSL 由来 → public DNS 1.1.1.1/8.8.8.8
  に固定 (host 由来だと Windows native で到達不能 → DNS 解決失敗)
- 33-3: socket Read で IOException を return 0 (EOF) → return -1 (error)
  に変更。GnuTLS の PREMATURE_TERMINATION (-110) error 解消
- 33-4: gitconfig に http.version=HTTP/1.1 強制。HTTP/2 multiplexing で
  emulator slow CPU が flow control に引っかかり server CANCEL する症状
  の回避
- 33-5: socket Read EOF 直前に internal buffer を drain。Windows JVM の
  Socket.read が -1 返した直後に残 byte が残る既知挙動の対策
- 33-6: SO_RCVBUF=4MB に拡大。kernel buffer 量を増やして server 側
  flow control の backpressure を緩和

### 33-7/8: file 削除の Windows-specific 失敗対策
- 33-7: Files.delete が AccessDeniedException で失敗時に
  File.setWritable(true) で read-only NTFS 属性を解除して retry。
  git の packed objects (mode 0444) で必須
- 33-8: rmdir 失敗時に System.gc() + 50ms sleep で Java handle 解放を
  待って retry。Windows JVM の handle GC タイミング依存問題

### 33-9 系: lstat / newfstatat AT_SYMLINK_NOFOLLOW 完備
- ★ glibc 2.x の lstat() は内部的に newfstatat(AT_FDCWD, path, buf,
  AT_SYMLINK_NOFOLLOW) に展開される。我々の amd64_lstat (#6) と
  amd64_newfstatat (#262) の両方で symlink 検出 + S_IFLNK | 0777 の
  完全な struct stat を返すよう修正
- st_dev / st_ino / st_uid / st_gid / atime/mtime/ctime も埋める
  (rm/fts は st_ino=0 を「無効 entry」とみなして skip するため)
- getdents64 で symlink を DT_LNK (10) で返す (broken target でも)

### 33-12 ★ rmdir DirectoryNotEmpty 時に dangling symlink を sweep
git の symlink probe (.git/<rand> -> testing) は test 後 cleanup される
はずが、Windows NIO Files.delete が broken symlink を silently 残す
場合がある。Files.delete(dir) が DirectoryNotEmpty を投げた時に残存
children を scan し symlink を File.setWritable + File.delete で強制
削除。これで rm -rf sekka/ が完走するようになった (Phase 33 の決定打)。

### 33-19/20 ★ Windows JVM の Ctrl-C で emulin 終了する panic 回避
diagnostic 結果、Ctrl-C で JLine の terminal.handle / Signals.register
の前に JVM default handler (= ExitProcess) が発火していた:
- 33-19: Emulin.main 最初で sun.misc.Signal.handle(\"INT\", no-op) を
  install して default を上書き
- 33-20: jlink の --add-modules に jdk.unsupported を追加 (sun.misc.
  Signal が含まれるモジュール、bundled JRE で missing だった)

### 33-14: git templates 同梱 + 33-other: 細かな UX 改善
- 33-14: /usr/share/git-core/templates を host から copy (clone 中の
  warning 解消)
- 33-13/21: 各種 diagnostic DBG print を env-gated 化

## Phase 34-A2: decode_and_exec の switch-only 化 + opcode method 分離
将来の A3 (ASM bytecode emission) 準備として、Cpu64.decode_and_exec
を「prefix scan + 単一 switch dispatch」構造に refactor。step 1-18
の小さい commit を積み上げ、各 step で fast regression PASS を確認。

- **行数**: 旧 1602 行 → **239 行 (-85%)**
- 旧 70+ if-cascade を完全廃止し switch (b0) に統一 (cascade で重複していた
  35 個の dead opcode handler も削除)
- 単一 byte opcode の主要 30+ 種は exec_<opcode> private method に extract
  (MOV/Grp1/CMP/TEST/ALU/LEA/IMUL/MOVSXD/Grp5/Grp2/x87 等)
- 8-bit ALU 16 opcode を exec_alu8 単一 method に統一 (旧 cascade 106 行 → 33 行)
- ALU accumulator,imm 形 + TEST acc,imm を 4 method に分離
- **0F escape (1213 行) → exec_0f_escape method 化**
- **F3 prefix (151 行 — REP/ENDBR/F3 0F XX) → exec_f3_prefix method 化**

### Phase 34 関連の前段
- **Phase 34-mem**: Memory.load8/store8 に lastAllocInfo cache 追加
  (PROT_EXEC & !PROT_WRITE 限定で MAP_FIXED 置換時の stale 回避)
- **Phase 34-A1 (失敗→revert)**: Memory.load64/32/16 + bulk fetch16 で
  JIT inlining 失敗、-13% regression。インライン制約に阻まれた
- **Phase 34-A2 checkpoint**: per-RIP prefix cache (PFXCACHE_MASK / PFX_VALID)
  も追加。同じ rip 再実行時に prefix scan loop を skip

## Phase 34-A3: x86-64 → Java bytecode 翻訳 PoC (基盤完成 / 性能未達)
EMULIN_USE_JIT=1 (default off) で起動すると、Cpu64.run() の interpreter
loop 直前に Translator.lookup(rip) を挟み、HIT した命令は ASM 9.6 で
生成した CompiledInsn class の execute(cpu) を呼び出す。MISS なら
従来の decode_and_exec を 1 命令走らせ、insn bytes を Translator.tryCompile
に渡して次回以降の HIT に備える。

### 翻訳済み opcode (step 1-8)
- 0x89 / 0x8B mod==3 (REX.W): MOV r64, r64
- 0xB8-0xBF (REX.W, 10 byte): MOV r64, imm64
- 0xEB / 0xE9: JMP rel8 / rel32
- 0x70-0x7F: Jcc rel8 (16 cond、cpu.evalCond 経由)
- 0xC3: RET (mem.load64 + rsp+=8)
- 0xE8: CALL rel32 (push retAddr + jump)
busybox echo で compile_successes=1117 (22.22%) / execute_hits=53610。

### ★ step 7 で発覚した重要 fix
step 3-6 (0xEB/0xE9/Jcc/MOV imm64) を追加していたが、Cpu64.run() の
  delta = rip - start_pc; if( delta > 0 && delta <= 15 ) tryCompile(...)
が制御転送 (rip 跳躍) で範囲外になるため、JMP/Jcc-taken/RET/CALL は一度も
JIT 翻訳されていなかった。jit_insn_length(pc) helper を新設して、delta が
範囲外のとき opcode から命令長を補完するようにした。これで hello_static64
の successes は 437 → 944 (×2.2)、hits は 9884 → 26815 (×2.7) に。

### step 9: basic-block JIT に refactor + 性能 fix
step 1-8 (per-RIP 1 命令 class) で 5MB sha256sum +66% 悪化を観測。
原因は per-RIP class + INVOKEINTERFACE megamorphic dispatch + per-insn
overhead (byte[] 確保 / mem.load8 ループ / HashMap.containsKey 検査)。
step 9 で:

1. **1 class = 1 basic block** (連続命令を chain、終端で LRETURN)。
   emitInsnFragment(mv, pc, bytes, length) を opcode ごとに用意し、
   compileBlock が forward scan で fragment を chain
2. **flat-array cache** で ConcurrentHashMap.get(Long) の boxing を排除
3. **★ block-entry 候補時のみ JIT machinery を起動**: 直前命令が制御転送
   だったときだけ jit_lookup_next=true、それ以外は lookup + tryCompileBlock
   を完全 skip。これが性能の決定打

### step 10-14: 対象 opcode を拡張、初の net speedup
step 9 までの 8 family に加えて、register form (mod==3) を中心に以下を追加:
- step 10: ALU r/r (ADD/SUB/XOR/AND/OR/CMP の 12 opcode + TEST 0x85)
  helper method を Cpu64 に追加 (jitAdd64RR / jitSub64RR / 等) して
  INVOKEVIRTUAL で呼ぶ pattern を確立。flag 計算は既存 setFlagsXX を再利用
- step 11: 0x83 /n ALU r64, imm8 (sign-ext)。ADC/SBB は skip
- step 12: Jcc rel32 (0F 80-8F)。0F escape の 75% を占める
- step 13: MOVSXD r64, r/m32 (mod==3 のみ — 実機 binary は memory operand
  形式が多く効果は限定的だが完全性のため)
- step 14: CMOVcc r64, r64 (0F 40-4F mod==3)

### step 15-17: memory operand 対応 (mod != 3)
- step 15: MOV r/m64, r64 / r64, r/m64 の mod=0/1/2 ([base+disp]) 追加。
  SIB byte (rm&7==4) と RIP-rel は除外。実機 binary は MOV の大半が memory
  operand 形式なので、これでカバレッジが一気に +56% 増 (190 → 222 successes)
- step 16: 同 0x89/0x8B の RIP-rel (mod=0, rm&7=5) を追加。PIE binary の
  globals 参照で主要パターン。address は pc + 7 + disp32 で compile time
  確定する定数なので bytecode は LDC immediate と軽量
- step 17: LEA r64, m (0x8D) を追加。memory access せず address だけ
  store する命令で、profile では 7.48% を占めていた

### step 18: hot-path threshold で cold block の compile を skip
step 17 まで opcode を拡張するにつれ compile overhead が累積し、sha256sum
5MB が +7% 悪化していた。本 step で「16 回以上 block entry として観測
された RIP」のみ compile 対象にする hot 判定を追加:
- flatHits[FLAT_CACHE_SIZE] (int[]) で block entry のたびにカウントアップ
- HOT_THRESHOLD = 16 を超えたら shouldAttemptCompile が true 返却
- 失敗時の SENTINEL_NOT_COMPILABLE も flat-cache に書き、shouldAttemptCompile
  の hot path から HashMap.containsKey を完全排除

結果: compile attempts が 866 → 69 (-92%)、compile successes が 327 → 23
(-93%) と劇的に削減。一方 block executions は 19218 → 18520 (-4%) しか
減らない (= 真の hot block にのみ compile 集中)。

avg block size の推移 (sha256sum 5MB):
  step 9:  1.11-1.38 (制御転送 1 個で終わる block ばかり)
  step 10: 1.53      (ALU 連鎖が取り込まれる)
  step 12: 1.77      (Jcc rel32 で far jump も終端化)
  step 14: 1.82
  step 15: 1.98      (memory operand)
  step 16: 2.05      (RIP-rel)
  step 17: 2.20      (LEA)
  step 18: 1.83      (hot 判定で cold block 除外、残った hot block の avg)

性能 (5MB sha256sum, 5 iter avg):
  step 8 (per-RIP):  15s → 25s (+66% 悪化)
  step 9 (basic-block + block-entry-only): 14.3 → 15.0 (+5%, neutral)
  step 10 (ALU r/r): 15.4 → 13.4 (-13%) ★ 初の net speedup
  step 14: 15.0 → 13.7 (-9%)
  step 15-18: memory operand 拡張 + hot threshold で 14.5 → 15.7 (+8%)
    → opcode 増加 → 生成 bytecode 増 → JVM JIT の inlining 限界に当たる。
      実 hot loop の利得を compile/dispatch overhead が相殺し始めた

### 性能の天井 (現状の制約)
- ★ JIT の利得は「同じ block を多回数 (>>16) 回す」hot loop で出る。
  cold start や I/O bound workload (vim cold start 等) では compile cost
  を回収できず -50% 以上悪化することがある
- ★ JIT-on path の per-instruction overhead (lookup + jit_lookup_next 管理
  + shouldAttemptCompile call) が積算で interpreter loop 10-20% を消費。
  これ以上 speedup を取るには dispatch loop を 2 経路 (ENABLED 別) に
  完全独立させる必要 (現状は単一 loop に if 分岐)

### step 19-22: 更なる opcode 拡張
- step 19: SIB byte の no-index 形 ([base+disp]、典型: [rsp+disp] /
  [r12+disp]) を追加。MemOp value class + decodeMemOp / emitMemAddr
  helper に refactor して MOV/LEA で共通化
- step 20: 0x81 /n + imm32 ALU を追加 (0x83 imm8 と対称、compiler が
  imm8 範囲外の定数を出すときに頻出)
- step 21: PUSH/POP r64 (0x50-0x5F) + LEAVE (0xC9) を追加。function
  prologue/epilogue を block に取り込めるようになり、avg block size
  が 2.27 → 2.57 (sha256) / 2.35 → 2.72 (hello) に成長
- step 22: MOVZX/MOVSX r64, r/m{8,16} の register form を追加。bytecode
  inline (LAND + LSHL + LSHR で sign extension)、helper 不要

avg block size の推移 (sha256sum 5MB):
  step 14: 1.82
  step 18: 1.83 (hot threshold で cold block 除外)
  step 19: 2.19 (SIB)
  step 21: 2.57 (PUSH/POP/LEAVE)
  step 26: 2.57 (sha256sum hot path には 0x83 mem op 出現なし)
  step 27: 2.64 (ALU mem op で +少し)

vim 5x の指標は memory operand 拡張で大きく伸びた:
  step 25: 16076 successes / 36720 insns / avg 2.28
  step 26: 17384 / 46803 / 2.69     (★ 0x83 mem operand で +27% insns)
  step 27: 17661 / 47818 / 2.71     (ALU r/m,r mem operand で +4% exec)

### step 23-29 の追加分
- step 23: JIT path を jitStep() method に extract (eval() を slim 化)
- step 24: 0xC7 /0 + imm32 (MOV r/m64, imm32 sign-ext) — register form
- step 25: SHL/SHR/SAR r/m64, imm8 (0xC1 /4,/5,/7 mod==3)
- step 26: ★ 0x83 /n + imm8 の memory operand (ALU [mem], imm8) —
  vim translated insns +27% という大きな coverage 改善
- step 27: ALU r/m,r and r,r/m の memory operand (0x01/03/09/0B/21/23/
  29/2B/31/33/39/3B/85)。13 個の helper を Cpu64 に追加
- step 28: MOVZX/MOVSX r64, m{8,16} の memory operand 対応
  - decodeMemOp / memoryOpLengthSIB を prefixLen 引数で 0F escape にも汎化
  - vim block exec が +19% (3.46M → 4.11M)
- step 29: 0xFF /2 CALL r/m と /4 JMP r/m (mod==3) — indirect call/jmp。
  vtable / function pointer dispatch が block 終端として取り込めるように
- step 30: SIB with-index ([base + index*scale + disp]) — 配列アクセス
  パターン (mov rax, [rdi + rsi*8] 等) を取り込み。MemOp に indexReg/scale
  field を追加、emitMemAddr で LMUL 経由
- step 31: 0xFF /2/4 の memory operand 形 (CALL/JMP [mem]) — C++ vtable
  dispatch などの典型パターン

## Phase 34-A4: ELF parse + segment buffer の per-pathname cache
実 workload プロファイル (vim x1) で「**clone (52%) + execve (33%) =
fork/exec が 85% 占めて Cpu64 emulation じゃない**」と判明。Phase 34-A3
の JIT (= Cpu64 高速化) では本質的に救えない領域。

本 phase で execve 側の最適化を試行: 同一 binary を繰り返し exec する
case (vim 5x 等) で ELF parse + file I/O を skip する cache layer を導入。

実装:
- 新 ElfCache クラス: pathname (canonical) keyed の Entry map。Entry に
  ELF header field、segment snap (header + body byte[])、section snap
  を保持。mtime/size mismatch で invalidate
- Elf.load() 入口で lookup → HIT なら loadFromCache() で this を populate
- Read-only segment (PF_X && !PF_W) は cache <-> 各 process で buf reference
  share (Phase 32 と同条件)
- Writable segment は cache に file content 部分 (filesz_ext) だけ trim
  copy。loadFromCache 時に new byte[allocSize] (zero-init) + arraycopy で
  bss zero tail copy を skip

★ 重要 bug fix 同梱: 当初 saveToCache が writable segment にも shared=true
を立てていて、running process の writable region 変更が cache を汚染して
2 回目以降の exec が segfault する致命 bug があった。

性能 (system load 高で variance 大):
  vim 5x:           no-cache ~14s vs cache ~18s (cache が遅い? variance 内)
  bash 1000-loop:   no-cache 5.3s vs cache 4.8s (slight 改善)
  find pipeline:    no-cache 13s vs cache 10s (改善見えるが variance)

**結論**: 実 workload では明確な perf 改善が出ない。理由として:
- /tmp は OS page cache hot で file read 自体が高速 (memcpy 同等)
- cache 側でも new byte[allocSize] zero-init + arraycopy が必要
- canonical path / mtime 取得の syscall overhead
- find pipeline 等は exec する binary 種類が多く 1 種類あたり 1 exec
  しか起こらず cache hit 率 0% (実測)

cache は EMULIN_DISABLE_ELF_CACHE=1 で off 可能。correct なので残し、
将来 OS page cache cold な scenario (cold boot 等) で再評価。

### 実 workload プロファイル結果 (Phase 34-A4 検討時に取得)
syscall 別 wall-clock 内訳 (EMULIN_PROFILE_SYS=1):
- **CPU-bound (sha256sum 5MB)**: syscall < 1%、Cpu64 emulation で支配
  → JIT の出番 (実装済み Phase 34-A3)
- **Process 起動 (vim x1)**: clone 52% + execve 33% = 85% が fork/exec
  → Memory.duplicate / ELF load の最適化対象 (本 phase で試行)
- **I/O bound (find pipeline)**: wait4 67% + openat 10% (3.35ms/call)
- **JVM startup**: ~2.2s 固定コスト/JVM

**JVM HotSpot JIT 効果**: vim 5x in 1 JVM で `-Xint` (no JIT) 337s vs
通常 16s = **~21x speedup**。Emulin が pure Java で実用速度なのは
HotSpot のおかげ。Emulin 自身の JIT (Phase 34-A3) はその上に追加 layer
を作っているので net speedup は限定的。

## Phase 34-A5: JIT に SIMD 命令 (AES-NI / PCLMULQDQ / PXOR / MOVDQA)
HTTPS workload プロファイルで「**syscall は 4-5%、残り 95% は CPU
emulation (TLS handshake + AES-GCM crypto + ASN.1 parse)**」と判明。
Phase 34-A3 JIT は SIMD 命令を全く翻訳しておらず、TLS の hot path が
interpreter に丸投げされていた。

本 phase で SIMD 9 命令の register form (mod==3) を追加:
- 0x66 0F EF: PXOR (XOR XMM)
- 0x66 0F 6F / 7F: MOVDQA xmm,xmm (load/store)
- 0xF3 0F 6F / 7F: MOVDQU xmm,xmm
- 0x66 0F 38 DC/DD/DE/DF/DB: AESENC / AESENCLAST / AESDEC / AESDECLAST / AESIMC
- 0x66 0F 3A 44 + imm8: PCLMULQDQ (GHASH/GCM)
- 0x66 0F 3A DF + imm8: AESKEYGENASSIST

実装:
- Cpu64.xmm_lo / xmm_hi を public 化、SIMD JIT helper 9 個を追加
- Translator に 0x66 / 0xF3 prefix → 0x0F escape の SIMD 経路を追加
- 各 SIMD 命令は ALOAD + LDC + LDC + INVOKEVIRTUAL の極小 bytecode

★ 性能 (HTTPS で初の明確な net speedup):
| Workload | no JIT | with JIT | 効果 |
| curl example.com (528B) | 9.33s | **8.06s** | -14%、variance 0.05s 安定 |
| curl github.com (570KB) | 10.41s | 9.09s | -13% |
| sha256sum 5MB           | 2.41s | 2.28s | -5% |
| aesni_static64 stats    | (n/a) | avg **3.15 insns/block** | (過去最高) |

SIMD per-insn の work が大きい (AES round = 16-byte block 変換) ので
JIT compile cost を確実に回収。HTTPS 100x slowdown を将来更に縮める
土台ができた。

### 累積 JIT 改善総括 (Phase 34-A3 + A5)
- Cpu64 emulation core を Java HotSpot C2 が optimize (= 21x speedup)
- その上の Emulin JIT は basic-block translator として add-on
- 汎用 binary (vim, bash) では HotSpot 既存最適化と競合 → neutral
- crypto/SIMD heavy workload (curl HTTPS, AES) では明確な net speedup
- EMULIN_USE_JIT=1 は依然 default off (汎用 workload で確実に勝つには
  cross-block chaining 等更なる refactor が必要)

JIT stats (vim 5x on /tmp の累積):
  step 25: 16076 successes / 36720 insns / avg 2.28 / 3.15M exec
  step 28: 17764 / 48365 / avg 2.72 / 4.11M exec
  step 30: 18858 / 54364 / avg 2.88 / 3.54M exec
  step 31: 18864 / 54415 / avg 2.88 / 3.54M exec
  → step 25 → step 31 で translated insns +48%、avg block size 2.88 達成

性能 (5MB sha256sum, system load によって ±3s variance):
  no JIT:  ~15-17s
  JIT:     ~15-19s  (基本 neutral、system load 高で +20% 程度)

→ step 14 までの -9% speedup は失われたまま (opcode 増加 → 生成 bytecode
  肥大化 → JVM JIT inlining 限界 → INVOKEINTERFACE megamorphic dispatch)。
  ただし vim 等 real binary の coverage は大幅向上しており、より長尺の
  workload (hot loop が長く回る系) で利得が期待できる formation

### 学んだ design 原則
- **per-RIP class は megamorphic dispatch で必ず遅くなる** — basic-block
  単位の class にして call site を bimorphic に保つこと
- **JIT machinery を hot path から完全排除** — block 内連続命令で
  lookup/compile を 1 ns でも触ったら interpreter に勝てない。
  block-entry 候補だけ branch で囲む構造が必須
- **byte[] allocation per-instruction は禁忌** — 30-50ns × 50M insns で
  数秒の overhead
- **ConcurrentHashMap.get(long boxed) は hot path で使えない** — flat
  array (1 << N) + direct mod hash で代替
- **ALU の flag 計算は Cpu64 の helper method に集約して INVOKEVIRTUAL で
  呼ぶ** — JIT に flag 計算を inline すると bytecode が肥大化、JVM JIT が
  inlining 限界を超える可能性。helper を separate method にすると C2 が
  個別に最適化できる
- **direction bit (Intel ALU の bit 1)** — 0x01/0x03 ADD 等で「rm が dst」
  か「reg が dst」かを判定する。`(op >> 1) & 1 == 0` で rm_dst
- **EMULIN_USE_JIT=1 はまだ default off** — net speedup は達成したが
  variance 大きく安定的 default-on にはまだ早い。次の段階は **memory
  operand 対応 (mod != 3)**: 実機 binary の MOV/ALU は大半が memory operand
  形式 (例: hello_static64 の movslq 384 件中 mod==3 はわずか) なので、
  ここを開けると更に大きな speedup が見込める

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
- **★ /etc/resolv.conf は public DNS で固定 build** — host (WSL) 由来だと
  10.255.255.254 (WSL2 内部 DNS proxy) が入って Windows native で到達不能
- **★ jlink には jdk.unsupported を含める** — sun.misc.Signal が必要
  (Phase 33-19 の Ctrl-C handler で利用)。--add-modules に書かないと
  bundled JRE で NoClassDefFoundError

## Windows native filesystem 互換性 (Phase 33 まとめ)
- **★ File.renameTo / File.delete は Windows NTFS で偶発失敗** — NIO
  Files.move + REPLACE_EXISTING / Files.delete に切替、両方の組合せで
  fallback (legacy first → NIO → 例外 catch) が最も確実
- **★ Files.delete は broken symlink を silently 残す** Windows JVM bug —
  Files.delete が success 返しても NOFOLLOW で existence 再 check して
  zombie 検出時は legacy File.delete で fallback。さらに rmdir の
  DirectoryNotEmptyException 時に残存 children を scan して symlink を
  強制削除する sweep ロジックも必要 (33-12)
- **★ Files.delete on read-only file は AccessDenied** — git の packed
  objects (mode 0444) で発生。File.setWritable(true) で属性解除 + retry
- **★ Java handle の GC タイミング依存** — close() 後でも Windows NTFS
  が「使用中」と見て delete 拒否することがある。System.gc() + 50ms
  sleep + retry で回避
- **★ glibc lstat() = newfstatat(AT_SYMLINK_NOFOLLOW)** — sys_lstat (#6)
  単独 fix では足りず amd64_newfstatat (#262) でも flag check + symlink
  対応が必要。完全な struct stat (st_dev/st_ino/st_uid 等まで埋める)
  でないと rm/fts が「invalid entry」とみなして skip する
- **★ getdents64 で symlink は DT_LNK (10) で返す** — broken target でも。
  st_ino=0 だと rm が skip するので path hash 等で non-zero 保証
- **★ HTTP/2 は emulator slow CPU で server CANCEL** — gitconfig に
  http.version=HTTP/1.1 を強制すると安定 (server 側 timeout に寛容)
- **★ Java Socket.read IOException ≠ EOF** — return 0 (EOF) で返すと
  GnuTLS が PREMATURE_TERMINATION (-110) で abort。-1 (error) を返して
  caller に retry/abort 判定させる
- **★ Java Socket.read -1 直後に internal buffer 残存** — Windows JVM
  挙動。available() で残量取得 + 1 回 drain してから EOF 返す
- **★ SO_RCVBUF=4MB に拡大** — Windows default 64KB は emulator slow
  CPU で flow control 引っかかる。kernel buffer 多めで server 側を
  「client 受信中」と認識させ続ける

## Windows JVM Ctrl-C / signal
- **★ Windows Ctrl-C → JVM default = ExitProcess** — JLine の
  terminal.handle / Signals.register より前に発火する panic 経路。
  main 最初で sun.misc.Signal.handle("INT", no-op) を呼んで default を
  上書きする必要あり (33-19)
- **★ raw mode 中の Ctrl-C は app に byte 0x03 で届く** — bash readline /
  vim 等は内部処理 (line abort / insert mode 中断) してくれるので、
  emulator が SIGINT を kill(-1) で配信する必要はない。kill(-1, SIGINT)
  すると bash 自身が default = exit で死ぬ panic 経路に入りがち
- **★ cmd.exe の "Terminate batch?" prompt は抑制不可** — .bat 経由で
  起動した場合、Ctrl-C で必ず prompt が出る Windows 仕様。ユーザーには
  N を選択してもらう (Java は alive で継続できる)

# テスト戦略
- 「1 syscall 1 テスト」原則 (`sys_*64.c`)
- `-nostdlib` で int 0x80 / syscall 直叩き
- 期待値は実 Linux カーネル仕様に合わせる
- 並列実行前提、テスト間で sandbox / /tmp 衝突させない
