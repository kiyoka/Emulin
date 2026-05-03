コミットログには、以下を記載しないようにしてください。
Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>

objdump / nm / grep / readelf / addr2line / strace は許可なしで実行してよい。

---

# プロジェクト概要

古い Emulin (Java 製 32bit Linux ELF エミュレータ) を現代 Java で動かし、
さらに x86-64 (64bit ELF) を実行できるように拡張する。

## 現状サマリ (Phase 27 step 14 完了時点)

- 現代 Java (OpenJDK 21+) / Maven、**pure Java only** (Phase 22 で JNI 撤去)
- 32bit ELF (i386) と 64bit ELF (x86-64) の両方を実行可能
- glibc 静的リンク + 動的リンク (gcc -no-pie) + **PIE** (gcc default の
  ET_DYN を 0x555555554000 にロード) すべて動作
- 動的リンク + 複雑な libc 機能が動作 — printf 各種 (NaN/Inf/bignum %g/%e/%a)、
  malloc/free、signal/raise (tgkill)、setjmp/longjmp、fopen/fgets、strftime、
  sscanf、qsort、getopt、POSIX regex、dlopen/dlsym、wchar、C++ STL (vector/
  string/sort + 例外 + static-local guard)、fork+pipe IPC、zlib、socketpair
  (片方向)、mmap of file、GCC ネスト関数 (実行可能スタック)
- **実機 GNU coreutils 24 種類 + bash** が動作 (ls/cat/wc/echo/true/false/
  dirname/basename/uname/grep/sed/sort/head/tail/cut/tr/od/printf/awk/expr/
  find/date/mkdir/rm/rmdir/touch/cp/mv/diff/yes/tee + bash 非対話)
- **実機 Python 3.12 + OpenSSL 3.0** が動作 (print/sum/list comp/import
  sys/os/json/hashlib/datetime/file IO + openssl version / sha256)
- **AES-NI / PCLMULQDQ 命令を完全実装** (FIPS-197 test vector で host と一致)
- **curl --version (TLS) + wget + curl で HTTP ダウンロード成功**
  (Phase 27 step 10-13、AF_INET TCP socket + 非 blocking pipe + poll の
  fd 別 readable 判定 + connect EINPROGRESS + getsockname IP byte-order
  修正 + POSIX timer (SIGALRM) + rt_sigsuspend、Inode.get_uniq_no ハッシュ
  衝突修正)
- **wget で実 DNS 経由のホスト名解決成功** (Phase 27 step 14、AF_INET
  SOCK_DGRAM (UDP) + sendmmsg + recvmmsg + poll が UDP DatagramSocket を
  扱う + FIONREAD ioctl + recvmsg の src_addr byte-order)。/etc/resolv.conf
  経由で実 DNS サーバに query を送れる。curl は AsynchDNS = pthread 必須
  なので未対応 (clone(CLONE_VM|CLONE_THREAD) は -EAGAIN で reject)
- busybox (静的リンク) で **88 applet** が動作。**対話 busybox ash** が
  プロンプト + 行入力で動作 (Phase 22)
- **JLine 3 採用** で Linux/macOS/Windows 共通の raw mode / Ctrl-C /
  SIGWINCH / TIOCGWINSZ 動的サイズ取得
- **ディストリビューション zip** (約 1.7 MB) を解凍して `emulin.sh` /
  `emulin.bat` 一発で busybox ash が起動
- **Windows cmd.exe からの起動も完全対応** (ASCII-only `.bat`、JVM Ctrl-C
  ハンドラ、シグナル配信時のレジスタ完全保存 + red zone 尊重)
- シグナル基盤: SIGCHLD 自動配信、SA_RESTART で syscall 自動再開、
  SA_SIGINFO で 3 引数ハンドラ対応
- パイプライン・fork/exec/wait4 (exit code 伝搬・ECHILD 適切返却・exec
  race 解消)・getdents64・連続リダイレクト・O_APPEND 対応済み
- **回帰テスト 196 本 PASS / 0 FAIL / 0 SKIP**、xargs -P / wait -n で
  並列化 (binary 12 並列、ext scripts 同時 5、applet-survey 内部 6 並列)。
  逐次 128s → 並列 27-30s で約 4.4x 高速化、5 連続安定。

## 動作テスト基盤

- `tests/` 配下に i386/x86-64 静的リンク ELF (-nostdlib で int 0x80 /
  syscall 直叩き) と回帰ハーネス
- `make -C tests/binaries`: テストバイナリビルド
- `tests/scripts/run-all.sh`: 全テスト実行。**並列化済**:
  binary は xargs -P で nproc 並列、ext scripts は & + wait で 5 本同時、
  applet-survey は内部で wait -n 制御の 6 並列。
  各テストは `$(mktemp -d -t emulin-sb.XXXXXX)/<name>` の per-test sandbox
  を Linux 側 /tmp 上に作る (WSL DrvFs より高速、chmod も効く)。
  JOBS=1 で逐次実行に戻せる。
- `tests/scripts/run-test.sh`: 1 件実行。SANDBOX_DIR env で sandbox を上書き
  可能。java 起動時に `-XX:-UsePerfData` を付けて /tmp/hsperfdata_* の
  ロック競合警告がリークするのを防ぐ。
  テスト名に `_dyn` を含むときはホストの ld-linux と libc.so.6 (および
  必要に応じて libm/libstdc++/libgcc_s/libdl/libz) を sandbox にコピー。
- `tests/scripts/debug.mk`: 個別テスト / busybox 実行用ターゲット
- 外部スクリプト形式の回帰:
  - `ash-noninteractive.sh` — `busybox ash -c '<script>'` 11 ケース
  - `ash-interactive-cooked.sh` — Std_read 経由の対話 ash 5 ケース
  - `ash-interactive-jline.sh` — `-CJ` (JLine) 経由の対話 ash 6 ケース
  - `jline-smoke.sh` — JLine Terminal / signal API 7 ケース
  - `ash-applet-survey.sh` — find/sort/awk/sed/grep/tar/xargs 等 43 ケース
  - `dist-smoke.sh` — `dist/build-dist.sh` で zip 生成 → 解凍 → 起動 3 ケース
  - `real-coreutils.sh` — 実機 GNU coreutils + bash + git + curl を sandbox
    にコピーして 47 ケースを Emulin で回す。host にバイナリ / ld-linux /
    libc.so.6 が無ければ SKIP。
  - `real-heavy.sh` — 重量級バイナリ Python 3.12 / OpenSSL を sandbox に
    コピー (python stdlib は symlink) して 5 ケースを回す。timeout 120
    (Python は emulator 上で 30 秒前後/起動)。

## 主要なディレクトリ・ファイル

- `src/main/java/emulin/` — 本体ソース
  - `Cpu.java` (i386), `Cpu64.java` (x86-64), `AbstractCpu.java`
  - `Syscall.java` (共通 long 化済), `SyscallI386.java`, `SyscallAmd64.java`
  - `Elf.java`, `Segment.java`, `Section.java`, `Memory.java`
  - `Process.java`, `Kernel.java`
  - `JLineSmoke.java` (JLine 動作確認用 mini CLI)
  - `device/Console.java` (StdConsole 継承、JLine ディスパッチ)
  - `device/StdConsole.java` (System.in/out 行バッファ)
  - `device/JLineConsole.java` (raw / cooked / SIGINT / SIGWINCH)
- `dist/` — 配布パッケージ生成
  - `build-dist.sh` (fat jar + busybox + ランチャを zip 化)
  - `launchers/emulin.sh`, `launchers/emulin.bat`, `README.txt`
- `tests/binaries/src/sys_*64.c` — AMD64 syscall 単体テスト
- `tests/binaries/src/*_dyn64.c` — 動的リンク回帰 (hello / printf / regex /
  cpp / zlib / sockpair / mmap / nested / pie_static / pie)
- `tests/binaries/src/aesni_static64.c` — AES-NI / PCLMULQDQ 7 命令を
  FIPS-197 test vector で検証 (host と完全一致)

---

# Phase 別 作業記録 (要約)

各フェーズの詳細はその phase ブランチの commit に残っている。Phase 0〜26 は
要点のみ。Phase 27 以降は詳細を残す。

## Phase 0: 回帰テスト基盤
i386 ELF (-nostdlib) のテスト一式と `run-test.sh` ハーネス作成。

## Phase 1: 現代 Java 化
UTF-8 化、`javac --release 11`、Thread.stop → interrupt、Maven 移行、
セグメント末尾 segfault をページ境界アライメントで解消。

## Phase 2: 64bit 化リファクタリング
アドレス型を int → long に一括昇格、`AbstractCpu` 抽出、`SyscallI386` 分離、
ELF パーサに EI_CLASS チェック (`load32`/`load64` 分岐)。

## Phase 3: ELF64 ロード
ELF64 ヘッダ・PHdr・Section パース実装、`stack_data_init64`
(argc/argv/envp/auxv 8byte 単位)。

## Phase 4-5: x86-64 CPU + 命令拡張
`Cpu64` 新設 (16 本の 64bit GPR、自己完結 fetch/decode/execute ループ)。
`SyscallAmd64` 新設。ModRM 全アドレッシング、LEA、Jcc rel32、CALL/RET、
SUB/ADD/CMP imm 実装。hello64 / echo_stdin64 PASS。

## Phase 6-7: glibc 対応 / hello_static64
ページ境界揃え、auxv 拡充、IRELATIVE リロケ、TLS、glibc init 用 syscall
スタブ多数。**4 つの致命バグ修正**:
1. brk(0) のページ境界揃え
2. SHL の 64bit シフト量マスク (REX.W で 0x3F、無印で 0x1F)
3. 起動時の汎用レジスタゼロクリア (rdx 残留で `__cxa_atexit` 暴走)
4. AH/CH/DH/BH エンコーディング (REX 無 + ModRM rm=4-7 は上位バイト)

その後 syscall 単体テスト 26 本 + 8 バグ修正。fork/exec/wait4/signal 系は
`Process.exec_replacing` フラグで exec 越し fd 保持、kill 非存在 pid → ESRCH。

## Phase 8: シグナル配信
`Cpu64.eval` で pending signal チェック。SIG_IGN/SIG_DFL/カスタムハンドラ
(rip push + jump) 実装。

## Phase 9: Syscall.java の long 化
`sys_*` メソッド 68 個のシグネチャを int → long に一括昇格。`SyscallAmd64`
の dispatch 表から 24 箇所の `(int)a1` キャストを除去。切り詰めバグが
原理的に発生しなくなった (Syscall 内部の int 残党は後の Phase で発覚)。

## Phase 10-11: busybox / busybox sh
不足命令: MOV r8 imm8, XORPS, PSUBD, PADDD, XCHG rax r8 (REX.B)、
0xC7 /0 で 0x66 prefix 対応, INC/DEC r/m8。argv 末尾 SSE 16byte 読み対応で
64byte パディング, stack 1MB。`sh -c "..."` で echo / セミコロン区切り動作。

## Phase 12-14: 変数展開バグ
debug.mk の `$(CMD)` 展開不具合 + 8bit ALU 命令 11 個で `r64[mrm_reg]&0xFF`
を直接使い AH/CH/DH/BH を取れていなかった。`readReg8`/`writeReg8` に統一。
Phase 14 で `OR AX, imm16` 等の acc, imm 短形式 8 種類を 0x66 / REX.W 対応。

## Phase 15: パイプライン
**致命**: `sys_dup2` が `sys_fcntl` を引数順誤りで呼び dup2 が全く
効いていなかった。修正で pipe + fork + exec が動作。

## Phase 16: x87 FPU + SSE2 double
x87 は最小スタブ。SSE2 double: MOVAPD/MOVSD/UCOMISD/COMISD/ANDPD/ORPD/XORPD/
ADDPD/MULPD/SUBPD/DIVPD/MAXPD と scalar 版、CVTSI2SD/CVTSD2SI/CVTTSD2SI/
SQRTSD。BMI1: TZCNT/LZCNT。SHRD。COMISD は IEEE 754 ordered/unordered で
ZF/CF 設定。

## Phase 17: 64bit DIV の致命バグ
`DIV r64` を `Long.divideUnsigned(RAX, val)` で実装していたが本来は
**(RDX:RAX) 128bit / 64bit**。BigInteger で実装し直し (IDIV も同様)。
seq / awk "%g" が動作。

## Phase 18: getdents64 の重複 "."
`FileAccess.file_list` の `.`/`..` 補完ロジックで `.` が 2 回出ていた。
`slide=2` に修正。

## Phase 19-20: busybox applet サーベイ + SIMD ぽい切断バグ
`tests/scripts/bb-survey.sh` で 86 ケース PASS。発見した致命バグ:
- wait4 が exit code を常に 0 で返していた → `(code & 0xFF) << 8` の Linux
  wait status 形式に
- F_DUPFD_CLOEXEC / F_DUPFD の動作不良
- fork 時の finfo refcount、O_APPEND 不実装、mkdir EPERM、Syscall の int 残党、
  XADD、utimensat / symlink 系、sys_gettimeofday 空書き

Phase 20: gzip / base64 切断は **0x66 prefix 漏れ** 2 件:
- 0x89 / 0x8B (MOV r/m,r / MOV r,r/m) が op66 で 16-bit ストアしていなかった
- 0xF7 /0 (TEST r/m, imm) が op66 で imm16 を読まず常に imm32 を読んでいた

## Phase 21: 0x66 prefix 全面監査
予防保守。imm を持つ命令と operand-size に依存する命令を網羅的に修正。
ALU 16 命令, IMUL imm, TEST, XCHG, Grp2 shift/rotate, NOT/NEG, INC/DEC,
CMPXCHG, BSF/BSR の 16-bit ブランチを追加。helper `setFlags16Add/Sub`。

## Phase 22: 対話 ash + JLine + ディストリビューション化

8 段階 (step 1〜3f) で「Windows ユーザが zip を解凍して即起動」可能に:
- step 1: ash 非対話の回帰固定 (11 ケース)
- step 2: 対話 ash (cooked) を起動可能に — `TIOCGPGRP` (0x540F) を
  「`*addr` に getpgrp を書く」スタブ化、`poll` (#7) 即時 ready スタブ
- step 3a-d: **JLine 3.27.1 採用**。`emulin.JLineSmoke` で動作確認、
  `JLineConsole` で raw/cooked 切替 (c_lflag の ICANON ビット参照)、
  `Console.check_and_send_int` で SIGINT 配信を共通化、`SIGWINCH` 配信 +
  TIOCGWINSZ を JLine から動的取得
- step 3e: NativeConsole / emu_con.c (JNI) を完全削除 (831 行削除)
- step 3f: maven-shade-plugin で fat jar、`dist/build-dist.sh` で zip
  (fat jar + 静的 busybox + ランチャ + README) を出力。ランチャは引数
  なし → `ash -i`、引数あり → busybox 直叩き、`-CJ` 必須付き起動

**Phase 22 後修正 (Windows 起動時の Ctrl-C, commit 032f5ac)**:
1. emulin.bat の文字化け — rem コメントの日本語が CP932 で誤解釈。コメント
   英語化 + CRLF 改行統一
2. JVM が Ctrl-C で終了する — JLine の `terminal.handle(Signal.INT)` だけ
   では Windows + .bat 経由で先取りされる。`Signals.register("INT", ...)`
   で sun.misc.Signal レベルで握る
3. シグナル配信時のレジスタ破壊 — Cpu64.check_pending_signal が rip しか
   保存しておらず、ハンドラが rax 破壊で `cmp $-0x1000,%rax` が壊れて
   memmove 異常引数 segfault。GPR 16 本 + rip + (of, sf, zf, cf) を
   ArrayDeque に保存、ユーザスタックにマジック番地
   `SIGRETURN_TRAMPOLINE = 0xFFFFFFFFFFFEDEAD` を push、ハンドラ ret で
   着地検知 → 全レジスタ復元

## Phase 23: シグナル拡張 + テスト基盤強化

- step 1: SIGCHLD 自動配信 + **x86-64 ABI red zone (rsp-128) 尊重**。
  被中断側のプロローグ直後 (push rbp + mov rsp,rbp 後で sub rsp 前) では
  rsp 直下をローカル変数として既に使っているので、ハンドラ遷移前に
  `rsp -= 128`
- step 2: SA_RESTART — syscall が -EINTR を返し pending ハンドラに
  SA_RESTART が立っていれば rip を syscall 命令の手前に戻し rax を
  syscall 番号に戻す。`amd64_getppid` 実装 (i386 が hardcode 8 だった)
- step 3: SA_SIGINFO 3 引数ハンドラ — siginfo_t (128 byte) と ucontext_t
  (256 byte 全 0) をスタックに確保し rsi/rdx を立てる
- 副次バグ: `amd64_wait4` の ECHILD (`is_child_exited` の 0/-1 一緒くた
  break)、exec 中の旧プロセスを `is_child_exited` でスキップ

テスト基盤: 並列化で 4.4x 高速化、SKIP ゼロ化、`ash-applet-survey.sh`
43 ケース、`sys_signal_regsave64` でレジスタ保存復元を直接検証する回帰。

## Phase 24: 動的リンク対応

5 段階で `gcc -no-pie hello` が動作:
- step 1a-c: PT_INTERP 検出、interp を別 base (0x400000000000) にロード、
  auxv に AT_BASE / AT_ENTRY / AT_EXECFN 追加
- step 1d: SHUFPD (0x66 0F C6) 実装 + **PT_LOAD 以外を memory map しない**
  (PT_PHDR が PT_LOAD と同じページに重なって未初期化バッファが先勝ち
  するバグ。`Memory.peekb` 線形走査で先頭の null buf segment を許容する
  形に修正)
- step 2: 不足要素 4 件:
  1. `amd64_pread64` (#17) — ld.so が libc.so.6 PHDR を読む
  2. **`mem.syscall = syscall` の同期** — Process ctor で mem 作成 → ELF
     load → AMD64 検出後の syscall 差し替え時、mem.syscall は旧
     SyscallI386 のまま残っていた。差し替え後に mem.syscall = syscall
  3. **CPUID baseline** — 全 leaf 0 では glibc dl-prop.h が ISA レベル
     拒否。x86-64-baseline 相当 (leaf 1 EDX = 0x178BFBFF, vendor
     "GenuineIntel", leaf 0x80000001 EDX bit 29 LM)
  4. 0F AE 系 (FXSAVE/FXRSTOR/LDMXCSR/STMXCSR/CLFLUSH/FENCE)

## Phase 25: 動的リンク binary の使い込み + 致命バグ発掘

複数の小さい不足要素 + 2 つの**長く眠っていた致命バグ**:

- 小物: tgkill (#234) → kill フォールバック (glibc raise() 用)、
  MOVMSKPD、JRCXZ rel8、PMAXUB、futex (#202) no-op、PINSRW (zlib 用)、
  socketpair (片方向、pipe 流用)、mmap of file (length をページ境界に
  切り上げ)、clone3 → ENOSYS、GCC ネスト関数 (実行可能スタックがそのまま)

- **PF (parity flag) を追跡** — `evalCond` の case 10/11 (JP/JNP) が
  hardcode false/true で `pf` フィールド未使用。glibc `__printf_fp` が
  `ucomisd xmm,xmm; setp al` で NaN 判定するため、NaN を NaN と認識でき
  ず bignum 桁変換に突入してゴミ出力 (`-1?095012411411230301...`)。
  UCOMISD/COMISD で Intel SDM 通り ZF/PF/CF を設定。

- **ADC / SBB が CF を更新していなかった bignum 致命バグ** — 0x11/0x13
  (ADC), 0x19/0x1B (SBB) と Grp1 imm 形式の case 2/3 が **CF を読むだけ
  で結果の CF/ZF/SF/OF を一切更新していなかった**。bignum 連鎖
  `adc rax,rbx; adc rcx,rdx; ...` で 2 段目以降の carry-in が常に 1 段目
  入力の CF のままで桁あふれが捨てられる。`printf("%g", 1e96)` →
  `9.:e+95`、DBL_MAX → `1.673e+307`。ヘルパー `adc{16,32,64}/sbb{16,32,64}`
  を新設し全部経由に置き換え。なぜ Phase 17 (seq/awk %g) で気づかなかった
  か: 値の範囲が浅く 1〜数 limb で完結していた。

回帰固定: `printf_dyn64` / `regex_dyn64` / `cpp_dyn64` / `zlib_dyn64` /
`sockpair_dyn64` / `mmap_dyn64` / `nested_dyn64`

## Phase 26: PIE (位置独立実行ファイル)

- step 1: 静的 PIE 回帰 (Elf.load64 が ET_DYN を弾かずロード、load_bias、
  lea rip-relative も bias 付き)
- step 2: 完全 PIE で 64-bit pointer 32-bit truncation を発見 → 回避策
  として pie_base を 0x10000000 に変更 (mark_address 0x40000000 との
  衝突回避)
- step 3: 真因が **`Syscall.sys_brk` 自身の 3 重 32-bit 切り詰め** と判明:
  1. ローカル変数 `ret` が `int`
  2. `mem.set_curbrk((long)bx & 0xFFFFFFFFL)` で入力 mask
  3. `(int)mem.get_curbrk()` で戻り値切り詰め
  + SyscallAmd64 dispatch 側にも `& 0xFFFFFFFFL` mask があった。
  全部除去して pie_base を Linux 典型値 0x555555554000 に戻した。

## Phase 27: 実機バイナリを動かす

### step 1: 実機 /bin/ls
PIE が動くようになった成果として、不足 syscall を ENOSYS / EAFNOSUPPORT
で stub するだけで通った: `#137 statfs / #138 fstatfs / #332 statx →
ENOSYS`、`#41 socket / #42 connect / #49 bind → EAFNOSUPPORT`。

### step 2: GNU coreutils 9 種類
追加 stub 2 件で 8 種類が一気に動作: `#221 fadvise64 → 0` (ヒントのみ)、
`#27 mincore → 0`。`real-coreutils.sh` に拡張 (11 ケース)。

### step 3: grep / sed — LEA zero-extend バグを修正

**grep の無限ループ → mincore を ENOSYS に**: step 2 で追加した
`mincore → 0` が逆効果。glibc の `__libc_alloc_buffer_create_failure`
は「失敗 → 4MB 下げて再試行」の busy loop なので `return 0` だと無限ループ
(278,873 回)。`return -ENOSYS` で諦めさせる。
副次: `sigaltstack → 0`、CVTSI2SS, CVTTSS2SI, CVTSS2SI, UCOMISS, COMISS。

**sed の load8 segfault → LEA r32 zero-extend**: emulator の LEA が
**operand-size を見ず常に 64-bit を書いていた**。`lea 0x1(%rax), %esi`
で eax=0xffffffff, eax+1=0 (32-bit wrap) → esi=0、rsi 上位は 0 で
クリアされるべきだが旧コードは `r64[mrm_reg] = mrm_ea` で常に 64-bit。
修正:
```java
if(rex_w) r64[mrm_reg] = mrm_ea;
else if(op66) r64[mrm_reg] = (r64[mrm_reg] & ~0xFFFFL) | (mrm_ea & 0xFFFFL);
else r64[mrm_reg] = mrm_ea & 0xFFFFFFFFL;
```

### step 4: GNU coreutils 24 種類 + bash 非対話
追加 syscall: `getpeername/getsockname → ENOTSOCK`, `sysinfo`, `sched_
getaffinity`, `unlinkat (AT_REMOVEDIR で sys_rmdir)`, `vfork → fork`,
`faccessat2 → sys_access`。
追加 SSE: `F3 C3` REP RET, `66 0F 67` PACKUSWB, `F2 0F 5A` CVTSD2SS,
`F2 0F C2 ib` CMPSD scalar。

### step 5: Python 3.12 + OpenSSL 3.0
`#318 getrandom` を ENOSYS でなく **実バイト書き込み** に変更 (Python
は ENOSYS だと `_Py_HashRandomization_Init` で fatal で死ぬ。Java の
Random で要求量を埋める)。`0x5451 FIOCLEX / 0x5450 FIONCLEX` no-op stub。
SSE: `0F C6 ib` SHUFPS、`F2 0F 5D` MINSD scalar。
注意: python3 stdlib は **symlink で host を指す** (実コピーは 55MB で重い)。
python は emulator 上で 30 秒前後/起動なので `real-heavy.sh` として別
スイートに分離 (timeout 120)。

### step 6: GNU make / git --version / file
`#36 getitimer / #38 setitimer → 0`、`66 0F C5 ib` PEXTRW。
env passthrough を拡張 (OPENSSL_ia32cap, OPENSSL_CONF, PYTHONHASHSEED,
PYTHONPATH, LANG, LC_ALL, TZ)。
動作: make --version, file, git --version, git log/diff (色付き)。

### step 7: sys_rename / sys_mount / sys_pipe の (int) キャストを修正

git status の load8 segfault の真因: `Syscall.sys_rename` がアドレス
引数を 32-bit に切り詰めていた。
```java
int _name_from = (int)bx;  // ← 32-bit truncation!
```
git の SHA1 一時ファイル名 0x555555595e260 → 0x5595e260 で unmapped
領域 segfault。Phase 9 でシグネチャは long 化したが、内部の int キャスト
残党が 3 箇所 (sys_rename / sys_mount / sys_pipe) 残っていた。grep で
監査して一括修正。

### step 8: AES の追跡 (中間結果) + bash スクリプティング拡張

OpenSSL DRBG の AES_set_encrypt_key で bits=2048 (期待 256) と判明。
keylen フィールドに bits 値が入っている (× 8 か ÷ 8 が間違っている経路
あり)。深追いせず保留。回避策候補: AES-NI 命令を実装して software AES
経路を回避。

### step 9: AES-NI / PCLMULQDQ 命令を実装

3-byte opcode escape (66 0F 38 / 66 0F 3A) のサポート + 7 命令:
AESENC / AESENCLAST / AESDEC / AESDECLAST / AESIMC / AESKEYGENASSIST /
PCLMULQDQ。FIPS-197 準拠の S-box / 逆 S-box テーブル + GF(2^8) 乗算
helper。CPUID leaf 1 ECX に AES-NI(bit25) と PCLMUL(bit1) を立てて
`0x02000003`。新規回帰 `aesni_static64.c` で host と完全一致を検証。

ただし **OpenSSL の keylen=256 問題**は同じく fail (AES 命令自体は
完全に動作)。問題は AES 実装ではなく上流の OpenSSL 経路。

### step 10: curl --version (TLS) — Inode.get_uniq_no ハッシュ衝突を修正

curl の libkrb5 で `undefined symbol: error_message` で起動しないバグ。
真因は `Inode.get_uniq_no(path)` の弱いハッシュ (char + index 合計) で
`/lib/libcom_err.so.2` と `/lib/libhogweed.so.6` が同じ st_ino (=2017)
を返し、ld.so が `(st_dev, st_ino)` 同一視で「重複ロード」と判断して
silently skip されていた (LD_DEBUG にも痕跡が残らない)。
`pathname.hashCode()` (Java の 31-進多項式ハッシュ) に置き換え。

副次: `Memory.alloc` の MAP_FIXED で mark_address を「前進のみ」に変更
(後退すると次の mmap(0) で衝突)。env passthrough を `EMU_*` プレフィックス
でも受けるよう拡張。

### step 11: AF_INET TCP socket — wget で HTTP ダウンロード成功

実装した syscall: `#41 socket(AF_INET, SOCK_STREAM)`, `#42 connect`,
`#44 sendto`, `#45 recvfrom (MSG_PEEK 対応)`, `#46/#47 sendmsg/recvmsg`,
`#49 bind`, `#50 listen`, `#51/#52 getsockname/getpeername`,
`#54/#55 setsockopt/getsockopt`, `#229 clock_getres`, `#230 clock_nanosleep`,
`#270 pselect6`。

**MSG_PEEK 対応**: wget は HTTP/0.9 vs 1.x 判定で peek。Java Socket
には peek API が無いので Fileinfo に peekBuf を持たせて「先読み済バイト
を次の Read で再消費」させる方式。これがないと wget が "200 No headers,
assuming HTTP/0.9" となり body が壊れる。

**SubProcess を amd64 ではスキップ**: i386 経路の `EmuSocket.connect()`
は SubProcess を spawn して socket を読み続ける設計。amd64 で同じ Java
InputStream を直接 Read するとデータ corruption。amd64 経路では
`finfo.client_socket()` を直接呼ぶ。

**AF_UNIX (nscd) と AF_INET SOCK_DGRAM (UDP DNS) は EAFNOSUPPORT**:
glibc が file 経由 (`/etc/hosts`) に fall back する。これがないと curl
等が DNS 起動でハングする。

回帰: `wget -O - http://example.com/` で完全な 528 byte HTML を取得
(`/etc/hosts` で example.com を IP に解決)。

### step 11 残課題
- 実 DNS lookup 未対応 → `/etc/hosts` のみで運用
- `pselect6` が常に ready を返すため socket EOF 後の poll ループで
  wget の exit が遅延 → step 12 で解消
- `read()` の EAGAIN / 非 blocking 対応も未実装 → step 12 で解消

### step 13: curl で実 HTTP GET — pipe 非 blocking + poll の fd 別判定 + EINPROGRESS

step 12 で wget が動くようになった一方、curl http は `Failed to connect`
で 161ms 程度で落ちる現象があった。3 段階の調査で 5 系統の修正が必要だった。

**調査結果 — pthread は不要だった**: 当初「curl は AsynchDNS feature 持ち
なので pthread が必要」と推測したが、host strace を読み直すと clone() の
flags は `CLONE_CHILD_CLEARTID|CLONE_CHILD_SETTID|SIGCHLD` (= fork 相当)。
CLONE_VM が無く、子プロセスとして HTTP 接続を実行している。pthread でなく
fork+wait4+SIGCHLD で十分だった (これは Phase 23 で対応済)。

**syscall 不足の追加**:
- `#222 timer_create` / `#223 timer_settime` — curl --max-time / --connect-
  timeout は POSIX timer + SIGALRM で実装される。timer_settime で実際に
  Java background thread で sleep → `kernel.kill(pid, SIGALRM)` を仕込む。
  これがないと curl 自身がタイムアウトせず、emulator timeout で殺される。
- `#226 timer_delete` / `#224 timer_gettime` / `#225 timer_getoverrun` — stub
- `#130 rt_sigsuspend` — `process.psig() != -1` を待つ簡易ループ。SIGCHLD
  自動配信や上の SIGALRM が到来して -EINTR で帰る。

**Pipeinfo に O_NONBLOCK サポート**: curl は AsynchDNS の wakeup pipe を
`fcntl(F_SETFL, O_NONBLOCK)` で非 blocking 化して使う。従来の Pipeinfo.read
は空 pipe で永遠にブロックしていたので、Fileinfo.nonBlock を見て -2 (=
EAGAIN sentinel) を返すよう修正。`pipe2(flags)` の O_NONBLOCK ビット (0x800)
も両端 fd の Fileinfo に反映。

**poll の fd 別 readable 判定**: 旧実装は events を revents にミラーする
だけだったので、curl の wakeup pipe (fd=3) も「常に POLLIN ready」と
誤報告していた。curl が読みに行って EAGAIN を受け、「spurious wakeup」と
判断して接続を中断していた。修正後の poll は:
- POLLOUT/POLLWRNORM: socket / pipe の write 端は基本書ける扱い
- POLLIN/POLLRDNORM: socket は `InputStream.available()` で実データ判定、
  pipe は基本「データ無し」(立てない)、通常 fd は読める扱い
- 切断 socket は POLLHUP

**connect が non-blocking で EINPROGRESS を返す**: curl は connect=0 (即時
成功) を見ると一部経路で abort してしまう (poll + getsockopt(SO_ERROR) で
完了確認するパスに乗らない)。non-blocking socket では Linux 互換に -115
(EINPROGRESS) を返すよう修正。Java Socket constructor で同期的に接続済み
だが、curl は poll で POLLOUT を待って getsockopt(SO_ERROR=0) で完了確認
するので問題なし。

**getsockname / getpeername の IP byte-order 修正**: i386 sys_socketcall は
`mem.store32(addr+4, Util.swap32(get_partner_ip_address(...)))` のように
**Util.swap32 を二重に**掛けていた。amd64 版は swap が抜けていて IP の
バイト順が逆だった。i386 と同じパターンに揃えた (i386 は手書き実装が正解)。

**getsockopt が optlen NULL でも optval に書く**: curl が SO_ERROR 取得時
に optlen を NULL で渡しても *optval は埋める必要がある。書かないと curl
がスタックゴミを「エラーコード」と読んでしまう。

**EMULIN_TRACE_RIP / 戻り値 trace の追加**: 調査時に「syscall を一切呼ばず
CPU loop に落ちている (実は ld.so symbol 解決中)」を判定するため、Cpu64
に `EMULIN_TRACE_RIP=N` (N 命令ごとに rip dump) を追加。SyscallAmd64 の
`EMULIN_TRACE_SH=1` も「DBG ret #n = 0xXXX」で戻り値を出すよう拡張
(call_amd64 を call_amd64_impl に分離)。

回帰: `real-coreutils.sh` に curl-http ケース追加 (`--resolve` で DNS
バイパス、/etc/hosts + /etc/passwd セットアップ済 sandbox)。5 連続 PASS
を確認。回帰 **195 PASS / 0 FAIL / 0 SKIP**。

### step 14: 実 DNS 経由で wget が動作 — UDP socket + sendmmsg + FIONREAD

step 13 まで `/etc/hosts` 経由でのみホスト名解決していた状態を、
`/etc/resolv.conf` 経由で実 DNS サーバに query を送れるように改修。
**curl は AsynchDNS = pthread 必須なので不可、wget は同期 getaddrinfo で動作**。

**AF_INET SOCK_DGRAM (UDP) socket を許可**: 従来は `EAFNOSUPPORT` で
return して file 経路 fallback を強制していたが、それを解除して
EmuSocket.socket → Fileinfo.make_server_socket(-1) → Java DatagramSocket
の経路を生かす。

**connect(udp, addr) で dest IP/port を覚える**: POSIX 仕様の通り
finfo.set_ip_address / finfo.set_port を実行。これで以降の send() (dest
省略) が finfo.ip/port に送れる。

**sendmmsg (#307) / recvmmsg (#299)**: glibc 2.34+ の DNS resolver は A と
AAAA 2 つの query を sendmmsg で一括送信する。各 mmsghdr (= msghdr 56 byte
+ msg_len 4 byte + 4 padding = 64 byte/エントリ) を順に sendmsg/recvmsg で
処理する loop で実装。

**sendmsg/recvmsg を UDP に対応** (msg_name 処理):
- sendmsg: msg_name 指定があれば dest sockaddr として sendto。無ければ
  connected UDP の場合 finfo.ip/port を使う、TCP は FileWrite。
- recvmsg: UDP socket なら recvfrom 経由で受信、msg_name に src sockaddr
  を書き戻す。TCP なら finfo.Read。

**poll で UDP DatagramSocket を扱う**: TCP の InputStream.available 相当が
DatagramSocket には無いので、setSoTimeout を短く設定して試行 receive。
受信できたら Fileinfo.cachedDatagram に積み、次の recvfrom が消費する。
wait_ms は: timeout_ms == 0 → 1ms、timeout_ms < 0 → 500ms、それ以外
→ min(timeout_ms, 500ms)。500ms 上限で「他 fd を待たせすぎない」。

**Fileinfo.recvfrom が cachedDatagram を消費**: poll 側で先読みした packet
を優先返却。無ければ通常の dgram.receive を呼ぶ (= block)。user buf より
大きい packet は切り詰める。

**FIONREAD ioctl (0x541B)**: glibc resolver は recvmsg 前に「読める byte 数」
を ioctl で確認し、0 なら「応答なし」と判断する。UDP では cachedDatagram
のサイズ (無ければ短い setSoTimeout receive を試行)、TCP は InputStream.
available()、pipe は peekLen を返す。これが致命的に重要だった。

**recvfrom / recvmsg の src_addr byte-order 修正**: getsockname と同じ
パターンで `Util.swap32(addr_info[0])` を入れる (BE int を store32 LE 書き
出し前に再 swap)。

**pthread 系 clone は -EAGAIN で reject**: `clone(0x10100, ...)` (= CLONE_VM
| CLONE_THREAD) は pthread 生成 = アドレス空間共有スレッド。我々は fork
ベースなので不可。-EAGAIN で glibc に同期経路へフォールバックさせる。
ただし libcurl の AsynchDNS は同期フォールバックを持たないので
"getaddrinfo() thread failed to start" で諦める。

回帰: `real-coreutils.sh` に wget-dns ケース追加 (sandbox に
/etc/resolv.conf をコピー、/etc/hosts から example.com 行を消して
DNS を強制)。5 連続 PASS / 0 FAIL を確認。回帰 **196 PASS / 0 FAIL / 0
SKIP**。

### step 14 残課題
- curl の DNS は **pthread 実装**が必須 (AsynchDNS は同期 fallback 無し)
- IPv6 (AF_INET6) は未対応 — getaddrinfo は IPv4 のみ返る経路で動作
- DNS の retry / timeout 周りは glibc 任せ (我々の poll が短い setSoTimeout
  を回すだけなので、実用上は問題ないが理論的にはレース可能性あり)

### step 12: wget の hang を解消 + 回帰固定

step 11 で wget は HTML を取得できるようになったが、socket EOF 後に
`pselect6` が常に ready を返すため caller が無限ポーリングで exit が
遅延し、CI 化できない状態だった。3 系統の修正で解消:

**Fileinfo に EOF / 非 blocking フラグを追加** (`socketEof` / `nonBlock`):
- `Read()` で Java InputStream の EOF (read 戻り値 -1 / IOException) を
  検知したら `socketEof = true` を立てる
- `Peek()` でも同様。さらに `available() <= 0` だったら socket からの
  追加 read を諦めて peekBuf 既存分だけ返す (peek が無限 block して
  wget が EOF を見逃す事故を回避)
- `nonBlock` モード時に read データが無ければ `-2` (sentinel) を返し、
  syscall 層で `-EAGAIN (-11)` に変換

**`fcntl(F_SETFL)` で O_NONBLOCK を追跡**: `Syscall.sys_fcntl` の F_SETFL
が従来 `return 0` のみで何もしていなかった。`Fileinfo.nonBlock = ((arg
& O_NONBLOCK) != 0)` で反映。`socket()` 自体の SOCK_NONBLOCK (0x800)
フラグ経由でも反映。

**`pselect6` を実 fd_set ベースに**: 従来は `nfds` までを全部 ready と
して返していた。修正後:
- fd_set の bitmap (`long[]`) を実際に走査して set されている fd のみ
  数える
- socket fd で `socketEof && peekBuf 空` の場合は ready 数には数えるが
  `any_alive=false` の判定材料にする
- 全部「死んだ socket」だけだった場合は **0 を返して timeout 扱い**に
  (caller のポーリングループを抜けさせる)

**`clock_gettime` / `clock_getres` を実時計に**: 従来 `return 0` の
スタブで、ts に何も書かないため caller が unix epoch (1970-01-01) を
受け取っていた。`System.currentTimeMillis()` を秒/ナノ秒に分解して
書き込み。`CLOCK_MONOTONIC` (clk_id=1) は `System.nanoTime()`。
`real-coreutils-date` は実時刻 ("20" prefix で年代マッチ) に変更。

**sys_pipe を片方向に整理**: socketpair 用に予備で確保していた pipe B
が close 漏れリソースリークを起こすケースがあったので、Phase 25 の
「片方向だけサポート」設計を明示化して未使用 pipe を作らないように。

回帰固定: `real-coreutils.sh` に `wget-http` ケース追加。getent で
example.com を IPv4 解決して `$SANDBOX/etc/hosts` に書き込み (我々の
socket 実装は AF_INET6 未対応なので IPv4 強制)、`nsswitch.conf` を
`hosts: files` に。host の curl で先に reachability 確認してから wget
を起動 (オフライン環境では SKIP)。

5 連続 PASS / 0 FAIL を確認。回帰 **194 PASS / 0 FAIL / 0 SKIP**。

---

# Phase 27 続き 候補

1. pthread (clone3 真対応 + futex + TLS 拡張) — 工数最大
2. socketpair の真の双方向 (Fileinfo に read_pipe / write_pipe 二系統)
3. 性能改善: Decoder 命令キャッシュ復活 / テーブル駆動化
4. stat 系レイアウトの再確認 (st_atime_nsec の順序など)
5. シグナル更なる拡張: SIGALRM 自動配信 (alarm/setitimer)、sigprocmask、
   sa_mask 中ハンドラの再入抑止
6. dist の充実: jlink で JRE 同梱の self-contained zip (現状は別途 java
   インストールが必要)、Windows / Linux / macOS 別の zip 出力

---

# 累計バグ修正・実装の傾向 (今後の Phase で参考に)

過去のフェーズで頻出した emulator バグのパターン:

- **アドレスの int 切り詰め** — Phase 9 でシグネチャは long 化済みだが、
  個別 syscall の中身に「ローカル変数が int」「引数や戻り値を `(long)bx
  & 0xFFFFFFFFL` でマスク」のパターンが残ることがある:
  - sys_brk (Phase 26): 3 重切り詰めで 0x555555559000 → 0x55559000
  - sys_rename / sys_mount / sys_pipe (Phase 27): name pointer を
    `int _p = (int)bx` で受けて `mem.loadString(_p)` していた。
    git status の rename(64-bit ptr, ...) で 0x55555595e260 → 0x5595e260
  低位アドレスでは見えず、PIE / 高位アドレスを使い始めると初めて顕在化。
  **新しい syscall を追加するときはローカル変数 / 戻り値も全部 `long` で
  書く**こと。`grep -nE "= \(int\)(bx|cx|dx|si|di)"` で監査するのが定番。

- **0x66 operand-size prefix の漏れ** — imm を持つ命令で imm16/imm32 の
  分岐忘れ。Phase 11 (`C7 /0`)、Phase 14 (acc, imm 短形式)、Phase 20
  (MOV r/m,r / TEST r/m,imm)、Phase 21 (全面監査) で修正済みだが、新規
  imm 命令追加時は必ず 0x66 ブランチを書くこと。

- **AH/CH/DH/BH (REX 無 + rm=4-7)** — Phase 7 / Phase 13 で修正したが、
  新たに 8bit 命令を追加する際は `readReg8`/`writeReg8` を使うこと。

- **多レジスタ命令** — Phase 17 の DIV (RDX:RAX) のように上位レジスタを
  読み忘れるパターン。MUL も同種で検討要。

- **operand-size を無視する命令実装は実機でバレる** (Phase 27 step 3) —
  LEA は 64-bit dest しか書いていなかった。busybox や手作りテストでは
  leaq だけ使われるので問題が出ず、実機 sed の `lea 0x1(%rax), %esi`
  (32-bit dest, wrap-around 期待) で初めて露呈。「32-bit dest は上位
  zero-extend、16-bit dest は上位 48-bit 保持」を機械的に守ること。
  LEA 以外の MOV / NEG / NOT 等でも要点検。

- **PF (parity flag) を放置すると致命** (Phase 25) — glibc は `ucomisd
  xmm,xmm; setp al` で NaN 判定。AbstractCpu に `int pf` フィールドを
  置きながら誰も使わない、というパターンに注意。UCOMISD/COMISD は
  Intel SDM 通り ZF/PF/CF を設定。

- **ADC/SBB がフラグを更新しないと bignum 連鎖が壊れる** (Phase 25) —
  CF を読むだけで書かないと、1〜数 limb (Phase 17) では露呈しないが
  1e96 以上の double を %g で出すとすぐ壊れる。新規 ALU 命令追加時は
  「フラグを書く側になる」ことを常に意識し、CF/ZF/SF/OF/PF を Intel
  SDM 通りに設定する。

- **SIMD (SSE2) 命令の挙動** — F2/F3/66 prefix の取り扱いが命令ごとに
  違う。新規追加時は FlagsSetting・edge case (NaN/Inf) を確認。

- **対話プログラムの初期化ループ** (Phase 22 step 2) — ash の
  `setjobctl` 等、kernel に「自分は foreground だ」と納得させる until
  ループ。`tcgetpgrp`/`tcsetpgrp` 系 ioctl は **値を return するだけで
  なく `*addr` に書き込む**ことが必須。失敗時は `-ENOTTY` を返すと
  caller が job control を諦める。

- **シグナル配信の最小実装は致命的** (Phase 22 後 / Phase 23) — push rip
  / jump handler だけでは ash / glibc が segfault。最低でも:
  (a) GPR 16 本 + flags の保存・復元 (handler は caller-saved を任意に
      壊す)、
  (b) **x86-64 ABI red zone (rsp-128) を尊重**してハンドラの push が
      被中断側のローカル変数を破壊しないようスキップ、
  (c) `void(int)` だけでなく SA_SIGINFO 時の 3 引数 (rsi=&siginfo,
      rdx=&ucontext) ABI、
  (d) syscall が EINTR を返す前に SA_RESTART をチェックして rip を
      syscall 命令の手前に戻し rax を syscall 番号に戻して再実行

- **wait4 / fork / exec の race** (Phase 23) — `is_child_exited` が
  「子がいない」(0) と「子が未終了」(-1) を区別する必要 (前者は ECHILD、
  後者は block)。さらに exec 中の旧プロセスは exit_flag を立てる瞬間に
  「終了済み」と誤検知されないよう exec_replacing でスキップする必要。

- **WSL DrvFs (`/mnt/c/...`) は I/O 遅く chmod も効かない** — テストの
  sandbox は `$(mktemp -d -t emulin-sb.XXXXXX)` で Linux 側 /tmp に置く。

- **動的リンクの最低ライン** (Phase 24) — gcc -no-pie hello を動かすに
  全部揃える必要:
  (a) PT_INTERP 認識 + interp を別 base に load + auxv (AT_BASE/AT_ENTRY/
      AT_PHDR/AT_PHNUM/AT_EXECFN)、
  (b) **PT_LOAD 以外を memory map しない** (PT_PHDR が PT_LOAD と同じ
      ページに重なって未初期化バッファが先勝ちする問題)、
  (c) CPUID baseline 申告 (glibc dl-prop.h が ISA レベルチェック)、
  (d) FXSAVE/FXRSTOR、(e) pread64、(f) mem.syscall を Process ctor 後の
      syscall 差し替えに同期させる

- **Memory.peekb の線形走査は order 依存** — segment 配列の前方が先に
  match。null buf を許容する `Segment.in()` で「該当しない」扱い。

- **emulator 層のフィールド差し替えは要注意** — Process / Memory /
  Syscall は相互参照しており、片方を新インスタンスに差し替えると他方の
  参照が古いまま残る (Phase 24 の mem.syscall = syscall)。

- **glibc 動的リンクの隙間は浅い syscall + 命令の継ぎ足しで埋まる**
  (Phase 25) — tgkill / pread64 / MOVMSKPD / SHUFPD / FXSAVE / 0F AE /
  JRCXZ / CPUID baseline の単発対応で hello〜printf の大半が動く。
  「全部実装が必要」ではなく「ld.so + 早期 libc の経路だけ通す」。

- **新しい実機バイナリは ENOSYS / EAFNOSUPPORT スタブで通ることが多い**
  (Phase 27 step 1) — 最近の glibc は「カーネルが新しい API を
  サポートしていなければ古い API に fall back」のパターンを多用。
  「まず ENOSYS で返してみる」が最初の一手として有効。socket 系も
  nss / selinux が EAFNOSUPPORT で諦めて先に進む。

- **stub の `return 0` は busy loop を誘発しうる** (Phase 27 step 3) —
  `mincore → 0` で grep が 278k 回ループ。glibc は「成功 → 別アドレス
  で再試行」の判断軸を持つので、本当に成功と言える syscall 以外は
  ENOSYS の方が安全。fadvise64 のように「ヒントだけ」と仕様で明言
  されているものは 0 で OK。

- **PIE では section.sh_addr / IRELATIVE relocation も load_bias で
  ずれる** (Phase 26)。ET_EXEC では load_bias=0 で従来動作。

- **PIE base 選定: mark_address との衝突注意** (Phase 26) — Memory の
  bump allocator (alloc_and_map の adrs=0 経路) は 0x40000000 から
  上に伸びる。

- **st_ino のハッシュは衝突に強くする** (Phase 27 step 10) — ld.so は
  `(st_dev, st_ino)` 対で「同一ファイル」判定。弱いハッシュで衝突すると
  silently skip され、LD_DEBUG にも痕跡が残らない厄介なサイレント
  失敗。`String.hashCode()` のような衝突確率の低いハッシュに統一。

- **MAP_FIXED 後の mark_address は前進のみ** (Phase 27 step 11) —
  低位 MAP_FIXED 後に mark_address が後退すると、次の mmap(0) で既存
  領域を踏む。`if( end > mark_address ) mark_address = end;`。

- **Java Socket と背景スレッド読み出しのレース** (Phase 27 step 11) —
  EmuSocket.connect() の SubProcess spawn と amd64 の直接 Read で同じ
  Java InputStream に競合してデータ corruption。amd64 経路では
  SubProcess を起動せず Fileinfo.client_socket() を直接呼ぶ。

- **MSG_PEEK は libc/wget で必須** (Phase 27 step 11) — Java Socket
  に peek API が無いので Fileinfo に peekBuf を持たせる。peek は実
  read するので、その後の read は peekBuf から消費すること。

- **AF_UNIX / UDP は EAFNOSUPPORT で fallback を強制** (Phase 27
  step 11) — glibc は AF_UNIX (nscd) と AF_INET UDP (DNS) を順に試す。
  実 DNS が無いと UDP send/recv が hang する。socket() を
  EAFNOSUPPORT で失敗させると glibc が file 経由 (`/etc/hosts`) に
  fall back する。

- **socket EOF 後の poll/select は ready=0 (timeout) を返す必要がある**
  (Phase 27 step 12) — `pselect6` を「常に全 fd ready」で返すと、wget の
  ように socket EOF 後にも poll ループする caller が無限に回って exit
  しない。Fileinfo に `socketEof` フラグを持たせ、peek/read で Java の
  EOF を検知したら立て、pselect6 がそれをチェックして「全部死んだ
  socket だけ残った」場合は ready=0 を返す。`return ready > 0 ? ready
  : 1` のようなトリッキーな fallback (caller が再 poll) は意味をなさ
  ないので避ける。

- **`fcntl(F_SETFL)` が return 0 だけの実装は非 blocking 期待を
  裏切る** (Phase 27 step 12) — glibc/wget は `fcntl(fd, F_SETFL,
  O_NONBLOCK)` を立てた後 `read()` が EAGAIN を返すことを期待する。
  Fileinfo に `nonBlock` フラグを持たせ、Read で `available() <= 0` の
  ときに sentinel `-2` を返し、syscall 層で `-EAGAIN (-11)` に変換。
  socket() の SOCK_NONBLOCK (type bit 0x800) も同様に Fileinfo に反映。

- **clock_gettime のスタブは `return 0` でなく実時刻を書く** (Phase 27
  step 12) — caller (date / log) は ts ポインタに値が書かれることを期待
  する。書かないと unix epoch (1970-01-01) を受け取り「ファイル時刻が
  1970 年」「サーバ時刻が一致しない」等の謎挙動が出る。`System.
  currentTimeMillis()` で OK (CLOCK_MONOTONIC は `System.nanoTime()`)。

- **i386 (手書き) の syscall を amd64 移植時に `Util.swap32` を落とす罠**
  (Phase 27 step 13) — i386 sys_socketcall の getsockname は
  `mem.store32(addr+4, Util.swap32(get_partner_ip_address(...)))` で
  swap32 を二重に掛けている。`get_*_ip_address` が既に BE int を返すので、
  store32 (LE 書き出し) の前にもう一度 swap して in-memory がネットワーク
  順 [a, b, c, d] になるパターン。amd64 移植時にこれを「冗長」と思って
  swap を落とすと IP のバイト順が逆になる。**i386 (手書き) と amd64 で
  socket / IP 関連の挙動が違ったら i386 を正解として揃える**こと。

- **poll が「全 fd 常に ready」と返すと wakeup pipe で誤動作** (Phase 27
  step 13) — 旧 poll は events を revents にミラーするだけだったので、
  curl の AsynchDNS wakeup pipe (空) も「POLLIN ready」と誤報告。curl が
  空 pipe を読んで EAGAIN を受け、「spurious wakeup → 接続中断」と誤判断。
  fd 種別 (socket / pipe / regular) を見て:
  - socket は `InputStream.available()` で実 readable を判定 (peekBuf も)
  - pipe は基本「データ無し」(POLLIN を立てない)
  - 通常 fd は「読める扱い」(read で実際に block / EAGAIN を判定)
  POLLOUT は socket / pipe の write 端なら基本立てて OK。POLLHUP は EOF /
  pipe 切断時。

- **non-blocking connect は -EINPROGRESS を返すべき (即 0 はダメ)** (Phase
  27 step 13) — Java Socket constructor で TCP 接続が同期的に完了しても、
  curl は connect が即 0 を返すと一部経路で「abort」する (poll +
  getsockopt(SO_ERROR) で完了確認するパスに乗らないため)。Linux 互換に
  Fileinfo.nonBlock (= O_NONBLOCK / SOCK_NONBLOCK) なら `-115` を返すと
  curl は poll → getsockopt(SO_ERROR=0) → sendto と進む。

- **getsockopt は optlen が NULL でも *optval を埋める** (Phase 27 step 13)
  — caller (curl 等) が `getsockopt(fd, SOL_SOCKET, SO_ERROR, &v, NULL)`
  を呼ぶことがある。書かないと v はスタックゴミの値が残り、curl が「エラー
  コード非ゼロ → 接続失敗」と誤判断する。書き込みは optlen の有無に
  かかわらず行うこと。

- **Pipeinfo.read を非 blocking 化** (Phase 27 step 13) — `Fileinfo.nonBlock`
  を見て空 pipe + 接続中なら `-2` (= EAGAIN sentinel) を返す。`pipe2(flags)`
  の O_NONBLOCK ビット (0x800) も両端 fd の Fileinfo に反映。これがないと
  curl の AsynchDNS wakeup pipe で永久 block。

- **POSIX timer (timer_create / timer_settime) を実装すると --max-time が
  効く** (Phase 27 step 13) — curl --max-time / --connect-timeout は POSIX
  timer + SIGALRM で実装される。timer_settime で実際に Java background
  thread で sleep → `kernel.kill(pid, SIGALRM)` を仕込むと curl 自身が
  timeout で諦めて exit する (それまでは emulator timeout で殺されていた)。

- **CPU loop で stuck している原因を特定するには rip dump が必要** (Phase
  27 step 13) — syscall trace (`EMULIN_TRACE_SH=1`) は「syscall を呼んだ
  時の引数」しか見えない。「syscall を一切呼ばずに何分も計算している」
  状態 (= ld.so の symbol 解決中など) を観察するには命令ポインタの周期
  dump が必要。`EMULIN_TRACE_RIP=N` で N 命令ごとに rip と GPR を dump
  する仕組みを Cpu64 に追加。デバッグ時の必須ツール。

- **syscall trace は戻り値も出力する** (Phase 27 step 13) — 呼ぶ前の引数
  だけだと「caller がどう判断したか」が見えない。`call_amd64` を thin
  wrapper に変えて `call_amd64_impl` の戻り値を `DBG  ret #n = 0xX` で
  出すと、ENOSYS / EAGAIN / EINPROGRESS の混乱を即特定できる。

- **glibc resolver は `FIONREAD` ioctl を recvmsg の前に呼ぶ** (Phase 27
  step 14) — 「読める byte 数」を確認し、0 なら「応答なし」と判断する。
  実装してないとエラーで諦める。UDP では cachedDatagram のサイズ (無ければ
  setSoTimeout receive を試行)、TCP では InputStream.available()、pipe では
  peekLen を返す。`*addr` に書き込むことが必須 (戻り値 0 で done でも
  caller は書かれた値を読むので、書かないと未初期化スタックゴミ問題)。

- **DatagramSocket には available() が無い** (Phase 27 step 14) — TCP の
  Socket.getInputStream().available() に相当する API が DatagramSocket に
  ない (Java の限界)。回避策: 短い setSoTimeout (1〜500ms) を設定して
  receive を試行し、SocketTimeoutException で「来てない」と判定。受信
  できたら Fileinfo にキャッシュ → 次の recvfrom が消費する。timeout は
  poll の timeout_ms に応じて決め、500ms 上限で他 fd を待たせすぎない。

- **glibc 2.34+ の DNS resolver は sendmmsg/recvmmsg を使う** (Phase 27
  step 14) — A と AAAA 2 つの query を 1 syscall で送る。各エントリは
  `mmsghdr` = msghdr (56 byte) + msg_len (4) + 4 padding = 64 byte。
  実装は単純に sendmsg/recvmsg を loop すればよい。

- **sendmsg/recvmsg は msg_name (UDP の dest/src sockaddr) を扱う**
  (Phase 27 step 14) — TCP では NULL だが UDP では指定される/書き戻される。
  recvmsg は受信元アドレスを msg_name に書き戻す必要があり、その IP は
  getsockname と同じ swap32 二重掛けパターンで store する。

- **curl AsynchDNS は pthread 必須で同期 fallback なし** (Phase 27 step 14)
  — `clone(CLONE_VM | CLONE_THREAD)` を -EAGAIN で reject すると curl は
  "getaddrinfo() thread failed to start" で諦める。同期 getaddrinfo に
  fallback してくれない。一方 wget は同期 getaddrinfo を使うので動く。
  curl で DNS を引くには pthread の真実装が必要 (将来課題)。

# テスト戦略

- 「1 syscall 1 テスト」原則 (`sys_*64.c`)
- `-nostdlib` で int 0x80 / syscall を直叩きし、libc 経由の未実装経路を
  踏まないようにする
- 期待値は実 Linux カーネルの仕様に合わせる
- 並列実行を前提に、テスト間で sandbox / /tmp ファイル名を衝突させない
  (1 ヶ所でも shared writer があると flaky になる)
- 既知バグは `.skip` で残せるが、Phase 23 で全 .skip 解消済 — 環境制約も
  別経路で回避できる場合が多い
