コミットログには、以下を記載しないようにしてください。
Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>

objdump / nm / grep / readelf / addr2line / strace は許可なしで実行してよい。

---

# プロジェクト概要

古い Emulin (Java 製 32bit Linux ELF エミュレータ) を現代 Java で動かし、
さらに x86-64 (64bit ELF) を実行できるように拡張する。

## 現状サマリ (Phase 24 step 2 完了時点)

- 現代 Java (OpenJDK 21+) / Maven、**pure Java only** (Phase 22 で JNI 撤去)
- 32bit ELF (i386) と 64bit ELF (x86-64) の両方を実行可能
- glibc 静的リンクの hello world が動く
- **動的リンク (gcc -no-pie) hello world が動く** — `/lib64/ld-linux-x86-64.so.2`
  がロード、`libc.so.6` が mapping、`puts("hello dynamic")` まで通る (Phase 24)
- busybox (静的リンク) で **88 applet** が動作 (Phase 20 サーベイ)。代表:
  sh / ls / cat / echo / grep / sed / tr / tee / wc / head / tail / seq /
  awk / find / sort / uniq / cut / od / hexdump / md5sum / sha256sum /
  base64 (encode + decode) / gzip / gunzip / xargs / yes / dd / tar / diff
  / mkdir / rmdir / rm -rf / cp / mv / touch / printf / expr / test
- **対話 busybox ash** がプロンプト + 行入力で動作 (Phase 22)
- **JLine 3 採用** で Windows / macOS / Linux 共通に raw mode / Ctrl-C /
  SIGWINCH / TIOCGWINSZ 動的サイズ取得 (Phase 22 step 3a-3d)
- **ディストリビューション zip** (約 1.7 MB) を解凍して `emulin.sh` /
  `emulin.bat` 一発で busybox ash が起動 (Phase 22 step 3f)
- **Windows cmd.exe からの起動も完全対応** — ASCII-only `.bat`、JVM レベル
  Ctrl-C ハンドラ、シグナル配信時のレジスタ完全保存 + red zone 尊重
  (Phase 22 後修正、commit 032f5ac)
- **シグナル基盤の整備**: SIGCHLD 自動配信、SA_RESTART で syscall 自動再開、
  SA_SIGINFO で 3 引数ハンドラ対応 (Phase 23 step 1-3)
- パイプライン・fork/exec/wait4 (exit code 伝搬・ECHILD 適切返却・exec
  race 解消)・シグナル配信・getdents64・連続リダイレクト・O_APPEND 対応済み
- **回帰テスト 131 本 PASS / 0 FAIL / 0 SKIP** — sandbox を Linux 側 /tmp に
  置いて WSL DrvFs の制約 (chmod 不可) を回避し、SKIP をゼロ化
- 回帰スイートを **xargs -P / wait -n で並列化** (binary 12 並列、ext scripts
  同時 5、applet-survey 内部 6 並列)。逐次 128s → 並列 27-30s で約 4.4x 高速化、
  5 連続安定。
- `tests/scripts/bb-survey.sh` で applet を一括サーベイ可能

## 動作テスト基盤

- `tests/` 配下に i386/x86-64 静的リンク ELF (-nostdlib で int 0x80 /
  syscall 直叩き) と回帰ハーネス
- `make -C tests/binaries`: テストバイナリビルド
- `tests/scripts/run-all.sh`: 全テスト実行。**並列化済** (Phase 23):
  binary テストは xargs -P で nproc 並列、ext scripts は & + wait で
  5 本同時起動、applet-survey は内部で wait -n 制御の 6 並列。
  各テストは `$(mktemp -d -t emulin-sb.XXXXXX)/<name>` の per-test
  sandbox を Linux 側 /tmp 上に作る (WSL DrvFs より高速、chmod も効く)。
  JOBS=1 で逐次実行に戻せる。
- `tests/scripts/run-test.sh`: 1 件実行。SANDBOX_DIR env で sandbox を上書き
  可能 (並列ランナーから per-test sandbox を渡すために導入)。
  java 起動時に `-XX:-UsePerfData` を付け、/tmp/hsperfdata_* のロック
  競合警告が他テストの stdout にリークするのを防ぐ。
  テスト名に `_dyn` を含むときはホストの ld-linux と libc.so.6 を sandbox
  にコピーする (動的リンクテスト用、Phase 24)。
- `tests/scripts/debug.mk`: 個別テスト / busybox 実行用ターゲット
- `tests/expected/<name>.skip` で個別 SKIP 可能 (Phase 23 で全 .skip 解消済)
- 外部スクリプト形式の回帰:
  - `ash-noninteractive.sh` — `busybox ash -c '<script>'` 11 ケース (Phase 22)
  - `ash-interactive-cooked.sh` — Std_read 経由の対話 ash 5 ケース (Phase 22)
  - `ash-interactive-jline.sh` — `-CJ` (JLine) 経由の対話 ash 6 ケース (Phase 22 + tar-tf 1 追加)
  - `jline-smoke.sh` — JLine Terminal / signal API 7 ケース (Phase 22)
  - `ash-applet-survey.sh` — find/sort/awk/sed/grep/tar/xargs 等 43 ケース
    の使い込み回帰 (Phase 23、内部並列実行)
  - `dist-smoke.sh` — `dist/build-dist.sh` で zip 生成 → 解凍 → 起動 3 ケース
    (Phase 22)

## 主要なディレクトリ・ファイル

- `src/main/java/emulin/` — 本体ソース
  - `Cpu.java` (i386), `Cpu64.java` (x86-64), `AbstractCpu.java`
  - `Syscall.java` (共通 long 化済), `SyscallI386.java`, `SyscallAmd64.java`
  - `Elf.java`, `Segment.java`, `Section.java`, `Memory.java`
  - `Process.java`, `Kernel.java`
  - `JLineSmoke.java` (JLine 動作確認用 mini CLI、Phase 22)
  - `device/Console.java` (StdConsole 継承、JLine ディスパッチ)
  - `device/StdConsole.java` (System.in/out 行バッファ)
  - `device/JLineConsole.java` (raw / cooked / SIGINT / SIGWINCH、Phase 22)
- `dist/` — 配布パッケージ生成 (Phase 22 step 3f)
  - `build-dist.sh` (fat jar + busybox + ランチャを zip 化)
  - `launchers/emulin.sh`, `launchers/emulin.bat`
  - `README.txt`
- `tests/binaries/src/sys_*64.c` — AMD64 syscall 単体テスト
- `tests/binaries/src/hello_dyn64.c` — 動的リンク hello world (Phase 24)
  gcc -no-pie でビルド。回帰時は run-test.sh が ld-linux + libc を sandbox
  にコピーした上で起動する。

---

# Phase 別 作業記録 (要約)

各フェーズの詳細はその phase ブランチの commit に残っている。

## Phase 0: 回帰テスト基盤 (`phase0/regression-tests`)

i386 ELF (-nostdlib) のテスト一式と `run-test.sh` ハーネスを作成。
当時の bap は使えないので自前バイナリで。Emulin 本体には未着手。

## Phase 1: 現代 Java 化 (`phase1/modern-java`)

- 全ソースを `nkf -w --overwrite` で UTF-8 化
- `javac --release 11` でビルド可能に: `JFrame.show` → `setVisible`,
  `Console` 曖昧解消, `Socket(host,port,boolean)` 廃止対応
- `Thread.stop()` を `volatile exit_flag` + `interrupt()` に置換 (4 箇所)
- Maven 移行 (`pom.xml`、ソースを `src/main/java/` へ)
- セグメント末尾 segfault を**ページ境界アライメント**で解消、終了コード伝搬修正
- 全 5 回帰テスト PASS

## Phase 2: 64bit 化リファクタリング (`phase2/refactoring`)

- アドレス型を **int → long** に全フィールド一括昇格
- `AbstractCpu` を抽出 (Cpu32/Cpu64 分離はせず継承させるだけ)
- `SyscallI386` を分離して i386 ABI を独立クラスに
- ELF パーサに EI_CLASS チェック追加 (`load32`/`load64` 分岐)

## Phase 3: ELF64 ロード (`phase3/elf64-load`)

ELF64 ヘッダ・PHdr・Section パース実装。`stack_data_init64`
(argc/argv/envp/auxv 8byte 単位)。CPU は未対応。

## Phase 4: x86-64 CPU (`phase4/x86-64-cpu`)

`Cpu64` 新設 (16 本の 64bit GPR、自己完結 fetch/decode/execute ループ)。
`SyscallAmd64` 新設。hello64 (write+exit 直叩き) PASS。

## Phase 5: x86-64 命令拡張 (`phase4/x86-64-cpu` 継続)

ModRM 全アドレッシング、LEA、Jcc rel32、CALL/RET、SUB/ADD/CMP imm 等を実装。
SyscallAmd64 全番号テーブル整備 (read#0/write#1/stat/mmap/writev/exit_group)。
echo_stdin64 PASS。

## Phase 6: glibc 対応の足場 (`phase6/verification`)

- セグメントを page 境界揃えで配置 (glibc の複雑なロード対応)
- auxv に AT_PHDR/PHENT/PHNUM/PAGESZ/RANDOM 追加
- IRELATIVE リロケーション解決
- TLS 用ページ確保 + `fs_base` 設定
- glibc init が呼ぶ syscall を多数スタブ化 (rt_sigaction, arch_prctl,
  set_tid_address, prlimit64, getrandom 等)
- fileio64 PASS。hello_static64 はまだ malloc corruption で SKIP

## Phase 7: hello_static64 を PASS (`phase7/hello-static64-debug`)

4 つの致命バグを修正:
1. **brk(0) のページ境界揃え** (`Elf.load64`): カーネル相当の切り上げ
2. **SHL の 64bit シフト量マスク**: REX.W で 0x3F、無印で 0x1F
3. **起動時の汎用レジスタゼロクリア**: rdx 残留で `__cxa_atexit` 暴走
4. **AH/CH/DH/BH エンコーディング**: REX 無 + ModRM rm=4-7 は上位バイト

## Phase 7+: syscall 単体テスト 26 本 + 8 バグ修正 (同ブランチ)

`sys_*64.c` を整備。`Syscall` 側のバグを順次修正:
brk(0) (.bss なし)、chdir 検証、chmod 反映、ftruncate、gettimeofday、
nanosleep、pipe (int 切り詰め)、umask 状態保持。

## Phase 7++: fork/exec/wait4/signal 系 (同ブランチ)

- `amd64_wait4`/`amd64_execve` で int 切り詰めバグ解消 (long ポインタ)
- `Process.exec_replacing` フラグで exec 越し fd 保持
- exec 経由で SyscallAmd64 を再利用
- `kill` 非存在 pid → -ESRCH

## Phase 8: シグナル配信 (`phase8/signal-delivery`)

`Cpu64.eval` で pending signal をチェック。SIG_IGN/SIG_DFL/カスタム
ハンドラ (rip push + jump) 実装。`amd64_kill`/`amd64_rt_sigaction`/
`amd64_rt_sigreturn` 追加。`sys_signal_delivery64` PASS。

## Phase 9: Syscall.java の long 化 (`phase9/syscall-long`)

`sys_*` メソッド 68 個のシグネチャを int → long に一括昇格。
`SyscallAmd64` の dispatch 表から 24 箇所の `(int)a1` キャストを除去。
これで切り詰めバグが原理的に発生しなくなった。

## Phase 9+: getdents64 + myls64 (同ブランチ)

`amd64_getdents64` (#217) を AMD64 dirent64 レイアウトで実装。
`prctl` (#157) スタブ。自作 `myls64` PASS。

## Phase 10: busybox を動かす (`phase10/busybox`)

実 busybox の echo / ls / cat / grep が動作。
- **BSR 32bit バグ**: `Long.numberOfLeadingZeros` を operand size で分岐
- 不足命令: `MOV r8, imm8`, `XORPS`, `PSUBD`, `PADDD`
- 不足 syscall: `time`, `newfstatat`, `sendfile`, `openat` 引数順
- argv 末尾の SSE 16byte 読みに対応する 64byte パディング、stack 1MB

## Phase 11: busybox sh (`phase11/busybox-shell`)

- `XCHG rax, r8` (REX.B 付き 0x90) 修正
- `0xC7 /0` で 0x66 prefix 対応 (`mov word imm16` decode ずれ)
- `sys_uname` の int 切り詰め残党を修正
- `getcwd` (#79)、`INC/DEC r/m8` 追加

`sh -c "..."` で echo / セミコロン区切りシーケンスが動作。

## Phase 12: 変数展開バグ調査 (中間)

`$VAR` が壊れて見える症状を調査。確証取れず Phase 13 へ。
ctype_static64 テスト追加。

## Phase 13: make の偽陽性 + 0x40-0x47 truncation (`phase13/sh-varexp-trace`)

- Phase 12 の症状の半分は **`debug.mk` の `$(CMD)` 展開** が原因。
  `$(value CMD)` + シングルクォートで解決
- もう半分は emulator バグ: 8bit ALU 命令 11 個で
  `r64[mrm_reg]&0xFF` を直接使い AH/CH/DH/BH を取れていなかった
  (TEST/XOR/CMP/OR/AND/SUB/ADD/XCHG r/m8,r8 と CMPXCHG)
- `readReg8`/`writeReg8` に統一。stpcpy_sse2 の `test %ah, %ah` 経路が動作

## Phase 14: `"${VAR}"` segfault (`phase14/sh-quoted-varexp`)

`OR AX, imm16` (`66 0D 01 04`) を imm32 で読んで命令ストリームが
2byte ずれていた。0x05/0D/15/1D/25/2D/35/3D の **acc, imm 短形式** を
0x66 / REX.W 対応に修正。BSWAP (0F C8+rd)、pipe2 (#293)、clone (#56) 追加。

## Phase 15: パイプライン (`phase15/sh-pipeline`)

致命バグ: **`sys_dup2` の `sys_fcntl` 呼び出しが引数順誤り**
(`fcntl(bx, cx, F_DUPFD)` → `fcntl(bx, F_DUPFD, cx)`)。これで dup2 が
全く効いていなかった。修正で pipe + fork + exec が動作。

副次対応: argv[0] を path で上書きしないよう (busybox applet 識別保護)、
`/proc/self/exe` の exec 時自動解決、`dup3`、`SHLD` 命令。

## Phase 16: x87 FPU + SSE2 double (`phase16/x87-fpu`)

- x87 は最小スタブ (CW/SW/Tag フィールド + FLDCW/FNSTCW/FNSTSW/FNINIT/FWAIT)。
  64bit Linux では float/double は SSE で扱うので startup の制御ワード操作のみ
- SSE2 double: MOVAPD/MOVSD/UCOMISD/COMISD/ANDPD/ORPD/XORPD/ADDPD/MULPD/
  SUBPD/DIVPD/MAXPD と scalar 版、CVTSI2SD/CVTSD2SI/CVTTSD2SI/SQRTSD
- BMI1: TZCNT/LZCNT。SHRD (0F AC/AD)
- COMISD は ordered/unordered で ZF/CF を IEEE 754 に従って設定

seq / awk が動作。

## Phase 17: 64bit DIV の致命バグ (`phase17/seq-numeric-fixes`)

`DIV r64` を `Long.divideUnsigned(RAX, val)` で実装していたが、本来は
**(RDX:RAX) 128bit / 64bit**。glibc の `__printf_fp` が bignum 内部で
頻用するため "%g" 出力が壊れていた。BigInteger で正しく実装し直し
(IDIV も同様)。`seq 1 12` / `awk "%g"` 等が完全動作。

## Phase 18: getdents64 の重複 "." (`phase18/getdents-duplicate-dot`)

`FileAccess.file_list` の `.` `..` 補完ロジックが誤りで `.` が 2 回
出ていた既存バグ。`slide=2` (`.`/`..` 各 1) に修正。新規回帰テスト
`sys_getdents64` 追加 (dot=1, dotdot=1, emulin=1 を直接検証)。

## Phase 21: 0x66 prefix 全面監査 (`phase21/op66-audit`)

Phase 20 で 2 件の 0x66 漏れを修正した直後の予防保守。imm を持つ命令と
operand-size に依存する命令を網羅して 16-bit ブランチを追加した。

修正した命令 (calが起こる前に潰した):

- ALU r/m,r / r,r/m (16 命令): 0x01/03 ADD、0x09/0B OR、0x11/13 ADC、
  0x19/1B SBB、0x21/23 AND、0x29/2B SUB、0x31/33 XOR、0x39/3B CMP
- 0x69/6B IMUL r,r/m,imm — imm16/imm32 切替も追加
- 0x85 TEST r/m,r、0xA9 TEST AX/EAX/RAX,imm
- 0x87 XCHG r/m,r
- 0xC1/D1/D3 Grp2 shift/rotate (16-bit 用に size/mask を統一)
- 0xF7 /2 NOT、/3 NEG
- 0xFF /0 INC、/1 DEC r/m
- 0x0F B1 CMPXCHG
- 0x0F BC BSF、0x0F BD BSR

新規 helper: `setFlags16Add` / `setFlags16Sub`

新規テスト `sys_op66_16bit64` で ADD/SUB/AND/OR/XOR/NOT/NEG/INC/DEC/SHL/
TEST/XCHG/CMP の 16-bit 版を ASM レベルで host と等価動作確認。

bb-survey 88/88 PASS、回帰 48/47 PASS (旧 47 + 新 1)。

## Phase 20: gzip/base64 の SIMD ぽい切断バグ (`phase20/simd-decode-bugs`)

Phase 19 で繰越した base64-roundtrip と gzip-roundtrip を解決。原因は
SIMD ではなく **operand-size prefix (0x66) の漏れ** 2 件:

1. **0x89 / 0x8B (MOV r/m,r / MOV r,r/m)** が 0x66 prefix で 16-bit
   ストア/ロードしておらず常に 32-bit で書いていた。busybox 1.36 の
   最適化された decode_base64 が `mov %cx, 1(%rax)` で 2 byte だけ
   書きたいところに 4 byte 書いて、入力 buffer の次の 2 byte を 0
   で潰していたため `*src=='\0'` ループ脱出 → 4 文字で切れていた
2. **0xF7 /0 (TEST r/m, imm)** が 0x66 prefix で imm16 を読まず常に
   imm32 を読んでいた。gunzip の `test $0x400, %bp` (gzip ヘッダの
   FEXTRA フラグ判定) が後続 2 byte を imm に巻き込み、命令ストリーム
   が 2 byte ずれていた → 全 gz ファイルで "corrupted data"

新規回帰テスト `sys_base64decode64` (-nostdlib 自前 decoder) と
`bb_decode_static64` / `fgetc_static64` (glibc 静的リンク) を追加。
busybox 1.36 を `/tmp/busybox-1.36.1/` に -O0 -g で再ビルドして
unstripped で関数アドレスを使った命令単位トレースで特定。

bb-survey.sh 88/88 PASS (base64-roundtrip / gzip-roundtrip 含む)。
回帰テスト 47/47 PASS。

ここまでの傾向: **0x66 prefix 漏れは新しい命令を追加する際の最頻バグ**。
Phase 11/14 でも修正済 (0xC7 /0、0x05/0D/15/1D/25/2D/35/3D の
acc, imm)。今後 imm を持つ命令を追加する際は必ず 0x66 ブランチを
書くこと。

## Phase 19: busybox applet サーベイ (`phase19/busybox-applets`)

`tests/scripts/bb-survey.sh` を新設し、host busybox 出力と Emulin
出力を applet 単位で比較。86 ケース PASS。発見した致命バグ:

1. **wait4 が exit code を常に 0 で返していた** — `mem.store32(status_addr, 0)`
   のままだった。`Process.exit_code` / `ProcessInfo.exit_code` を追加し、
   `(code & 0xFF) << 8` の Linux wait status 形式で返すよう修正。
   `false || echo no` 等が初めて動くようになった
2. **`fcntl(F_DUPFD_CLOEXEC=1030)` がデフォルト分岐に落ちて 0 を返していた** —
   busybox sh の連続リダイレクト (`echo a > x; echo b > x`) で saved fd が
   0 になり、stdin が破壊されていた。F_DUPFD と同じパスで処理
3. **`F_DUPFD` が「lowest free >= arg」ではなく「arg を強制的に dup2」だった** —
   busybox sh の saved fd 戦略と整合しなかった。仕様準拠に修正
4. **fork 時に共有 finfo の opened を increment していなかった** — 子プロセスの
   `all_file_close` で親側 fd の refcount が下がりすぎ、本来開いている
   ファイルが close されていた潜在バグ
5. **O_APPEND 不実装** — `>>` リダイレクトで上書きされていた。`Fileinfo.open`
   で O_APPEND 時に `f.seek( f.length() )`
6. **mkdir が既存 dir で EPERM を返していた** — busybox `mkdir -p` が中間階層で
   失敗。EEXIST を返すよう修正
7. **Syscall.java に int 切り詰め残党が大量** — sys_mkdir/unlink/chdir/access/
   stat/fstat/execve/umount/read/write/ioctl/munmap/getrlimit の path/address
   引数を long 化。busybox の rdi=高位スタックアドレスで segfault していた
8. **XADD 命令 (0F C0 / 0F C1) 未実装** — busybox rm 等で必要。実装
9. **utimensat (#280)、symlink (#88)、symlinkat (#266)、utime (#132)、utimes
   (#235) 未実装** — busybox touch / ln -s で必要。実装 (symlink は WSL DrvFs
   では失敗するが SKIP)
10. **sys_gettimeofday がスタブで何も書いていなかった** — `tv_sec/tv_usec` を
    書くよう修正

サーベイハーネス上は base64 1.36 最適化 decode + gzip/gunzip の deflate
経路で SIMD 起因とみられる不具合が残るが Phase 19 ではスコープ外。

## Phase 22: 対話 ash + JLine + ディストリビューション化 (`phase22/ash-startup`, `phase22/jline-raw`)

Phase 21 までで非対話 busybox は完成。Phase 22 では「Windows ユーザが
zip を解凍して即起動」できる完成度まで持ち上げる。8 段階で実装した。

### Step 1: ash 非対話の回帰固定 (`phase22/ash-startup`)

`tests/scripts/ash-noninteractive.sh` で `busybox ash -c '<script>'` の
for / while / if / case / function / `${param}` / `$(cmd)` / `$(())` /
pipeline / `&& ||` / exit-status の 11 ケースを host の busybox ash と
比較。`run_ext_script` ヘルパーで run-all.sh から集計。

### Step 2: 対話 ash (cooked) を起動可能に

素で `busybox ash -i` を起動すると 2 つの致命バグで止まる:

1. **ioctl `TIOCGPGRP` (0x540F) 未実装** — ash の `setjobctl` 初期化が
   `tcgetpgrp() == getpgrp()` を満たすまで `killpg(SIGTTIN)` を
   呼び続けるので無限ループ。`*addr` に `getpgrp()` 値を書いて
   1 回でループを抜けさせる (実質 job control 無効と等価)。
   `TIOCSPGRP` (0x5410) も成功スタブにした。
2. **syscall #7 (`poll`) 未実装** — 全 fd 即時 ready で返すスタブを
   追加。実際のブロッキングは後続 `read()` 側で `Std_read` が
   System.in で待つ。

これだけで対話 ash がプロンプトを出して 1 行入力を受けるところまで
動く。`Std_read` (System.in.read 行バッファ) のままで成立し、
JNI/emu_con.c は引き続き不要。`ash-interactive-cooked.sh` で 5 ケース
回帰を追加。

### Step 3a: JLine 依存導入のスモーク (`phase22/jline-raw`)

`emu_con.c` (JNI) の置き換え先として **JLine 3.27.1** を採用:

- pom.xml に `org.jline:jline-terminal` を追加 (transitive で
  `jline-native` も入る、合計 ~1MB)
- `emulin.JLineSmoke` を新設。`TerminalBuilder.system(true).dumb(true)`
  で Terminal を作り `enterRawMode/setAttributes` の往復を確認する
  ミニ CLI。非 tty では dumb terminal にフォールバック
- `tests/scripts/jline-smoke.sh` で 4 ケース起動確認
- target/cp.txt (`mvn dependency:build-classpath` 出力) をキャッシュ
  して 2 回目以降は java 直叩き

### Step 3b: Console の Native パスを JLine ベースに置換

- `JLineConsole` 新設。`Terminal` + `NonBlockingReader` で cooked / raw
  両対応。`set_parameter` で c_lflag の **ICANON ビット** を見て
  raw/cooked 切替 (実 tty では `terminal.enterRawMode()`、dumb では
  no-op)
- `RootSysinfo` に `CONSOLE_JLINE = 3`、`Emulin` に `-CJ` フラグを追加
- `Console.java` は型ディスパッチで Std/Native/JLine を選択
  (この時点では Native も併存)
- `ash-interactive-jline.sh` で `-CJ` 経由の 5 ケース回帰

### Step 3c: SIGINT 配信を Native/JLine 共通化

step 3b で JLine 経路に Ctrl-C ハンドラを仕込んだが、配信は Native
限定だったので汎用化:

- `Console.check_and_send_int(Sysinfo)` を新設。型ディスパッチ済の
  `check_int()` / `cancel_int()` を使って `kernel.kill(-1, SIGINT)` する
- `Process.java` から Native 限定分岐を撤去し `check_and_send_int` を
  無条件で呼ぶ (Std は check_int が常に false なので no-op)
- JLineSmoke に `setInt/checkInt/cancelInt` の遷移確認を追加

### Step 3d: SIGWINCH 配信 + TIOCGWINSZ を JLine から動的取得

- `JLineConsole` に `pendingWinch` フラグ +
  `Terminal.handle(Signal.WINCH, ...)` ハンドラを追加。
  `getColumns/getRows` も公開
- `Console.check_and_send_winch` で `kernel.kill(-1, SIGWINCH)` 配信
- `SyscallAmd64.amd64_ioctl` の TIOCGWINSZ を `console.getRows/Columns`
  経由に変更。0 のとき 25x80 にフォールバック
- 実 tty で window をリサイズすると ash の line editing が追従する
  (実機検証は手動)

### Step 3e: NativeConsole / emu_con.c (JNI) を撤去

JLine 経路が安定したので JNI 系を完全削除。**16 files changed,
30 insertions, 831 deletions** の大幅な掃除:

- `src/main/java/emulin/device/NativeConsole.java` 削除
- `emulin/device/{unix,windows}/` の C/.so/.dll/Makefile/header を全削除
- `Console` を `StdConsole` 継承に変更。`Native_*` ディスパッチを除去
  して **Std と JLine の 2 分岐** に整理
- `RootSysinfo`: `CONSOLE_NATIVE` と `is_console_native` 削除
  (値 1 は欠番のまま)。`Emulin`: `-CN` フラグ削除、usage に `-CJ` 追記
- `Kernel.java`: NATIVE 専用の Interrupt JFrame/JButton/ButtonListener
  を撤去、`javax.swing` / `java.awt` 依存も削除

これで C ツールチェイン依存が完全消滅。

### Step 3f: ディストリビューション zip 化

Phase 22 のクライマックス。`maven-shade-plugin` で fat jar、
`dist/build-dist.sh` で zip を組み立てる:

- `pom.xml` に shade plugin を追加し
  `target/emulin-<ver>-all.jar` (約 632 KB) として JLine 同梱の
  fat jar を生成 (Main-Class=emulin.Emulin)
- `dist/build-dist.sh` で fat jar + 静的 busybox + ランチャ + README を
  `emulin-<ver>/` ツリーに組み立てて
  `target/emulin-dist-<ver>.zip` (約 1.7 MB) を出力
- `dist/launchers/emulin.{sh,bat}` ランチャ。引数なし → `ash -i`、
  引数あり → busybox 直叩き。`-CJ` 必須付き起動。java / busybox /
  fat jar の存在確認 + わかりやすいエラーで落ちる
- `tests/scripts/dist-smoke.sh` で zip ビルド → /tmp 解凍 →
  `emulin.sh ash -c '<cmd>'` で 3 ケース動作確認 (echo / for-seq / pipe)
- 罠メモ: `find ... -name 'emulin-*'` は EXTRACT 自身 (mktemp の
  emulin-dist-test.*) にもマッチするので `-mindepth 1` が必要

### Phase 22 の到達点

- 回帰 **79 PASS / 0 FAIL / 3 SKIP** (binary 47 + ash 11 + ash-cooked 5
  + jline-smoke 7 + ash-jline 5 + dist 3 + op66 1)
- pure Java + JLine + 静的 busybox の 1 zip で Linux/macOS/Windows
  どこでも対話 busybox ash が動く構成
- C ツールチェイン依存ゼロ (JNI/.dll/.so/.h/Makefile が
  リポジトリから消滅)
- `phase22/ash-startup` (step 1+2) と `phase22/jline-raw` (step 1〜3f)
  の 2 ブランチで origin と同期済

## Phase 22 後修正: Windows 起動時の Ctrl-C (`phase22/jline-raw` 続き)

emulin.bat ダブルクリック起動で 3 つの致命バグを連続修正 (commit 032f5ac):

1. **emulin.bat の文字化け** — `rem` コメントの日本語が CP932 (日本語 Windows
   cmd.exe) で誤解釈され rem 行がコマンドとして実行される。コメントを英語化
   + CRLF 改行に統一して回避。
2. **JVM が Ctrl-C で終了する** — JLine の `terminal.handle(Signal.INT,...)`
   だけでは Windows cmd.exe + .bat 経由起動で JVM 既定ハンドラに先取りされ
   java ごと exit。JLine 標準の `Signals.register("INT", ...)` を併用して
   sun.misc.Signal レベルで握り、pendingInt を直接立てる。
3. **シグナル配信時のレジスタ破壊** — Cpu64.check_pending_signal が rip しか
   保存していなかったため、ハンドラ (record_pending_signo 等) が rax を破壊し
   syscall 直後の `cmp $-0x1000,%rax` 比較が壊れて memmove 異常引数 segfault。
   GPR 16 本 + rip + (of, sf, zf, cf) を Java 側 ArrayDeque に保存し、
   ユーザスタックにマジック番地 SIGRETURN_TRAMPOLINE (0xFFFFFFFFFFFEDEAD) を
   push、ハンドラの ret で着地したら Cpu64.eval が検知して全レジスタを復元。

---

# Phase 23: シグナル拡張 + テスト基盤強化 (`phase22/jline-raw` 続き)

## Phase 23 step 1: SIGCHLD 自動配信 + red zone 尊重 (commit e49d91e)

1. **`Process.set_exit_flag` で親に SIGCHLD を自動配信** — 従来は子 exit
   後も親に通知が行かず ash の wait4 が sleep ポーリングだった。init_process
   と exec_replacing は除外。これで POSIX 準拠の非同期通知が成立。
2. **`amd64_wait4` が SIGCHLD と子終了の併発を扱う** — sleep 中に SIGCHLD
   が来た場合、recheck で子が終了していれば EINTR ではなく子の pid を優先
   して返す (Linux 互換)。
3. **シグナル配信時に x86-64 ABI の red zone (rsp-128) を尊重** — 致命的な
   発見: 被中断側のプロローグ直後 (push rbp + mov rsp,rbp 後で sub rsp 前)
   では rsp 直下をローカル変数として既に使っており、ハンドラの push/sub が
   そこを破壊する。`sys_sigchld64` で実際に -0x18(rbp_wait4) が SIGCHLD 番号
   17 に書き換わり flaky に segfault していた。Linux カーネルと同様、
   ハンドラ遷移前に `rsp -= 128` で red zone をスキップ。
4. **新規回帰** `sys_sigchld64`: fork → 子 exit(7) → 親 wait4(-1) で
   SIGCHLD ハンドラ起動 + wstatus=0x700 を確認。

## Phase 23 step 2: SA_RESTART + getppid 正規化 (commit da52322)

1. **`Siginfo.sa_flags` を導入** — `amd64_rt_sigaction` が act_addr+8 から
   sa_flags を読み取り保存。SA_RESTART (0x10000000) を判定する `has_sa_restart`。
2. **`Cpu64.exec_syscall` で SA_RESTART** — syscall が -EINTR を返し、現在
   pending のシグナルハンドラに SA_RESTART が立っていれば、rip を syscall 命令
   (0F 05) の手前に戻し、rax を syscall 番号に戻す。check_pending_signal が
   ハンドラを delivery し、復帰後 rip = syscall_pc から再実行される
   (Linux カーネルと同じ挙動)。
3. **`amd64_getppid` を実装** — 従来 i386 の sys_getppid が hardcode で 8 を
   返していた。ProcessInfo.ppid を引いて実 ppid を返す amd64 版を追加し、
   Kernel.boot で boot プロセスの ppid=1 (init) を明示。

## Phase 23 step 3: SA_SIGINFO 3 引数ハンドラ (commit d16849f)

POSIX `void(int)` に加え `void(int, siginfo_t*, ucontext_t*)` の 3 引数
規約をサポート。sa_flags に SA_SIGINFO (0x4) が立っていれば、
check_pending_signal がユーザスタックに siginfo_t (128 byte; si_signo
だけ埋める) と ucontext_t (簡易 256 byte、全 0) を確保し、rsi = &siginfo,
rdx = &ucontext を立ててハンドラへ。ハンドラ復帰時は saved frame の rsp に
戻すので両領域は自動的に「解放」される (handler の自分のフレームより上に
置いてあるだけ)。

## Phase 23: 副次的に発見・修正したバグ

- **`amd64_wait4` の ECHILD** (commit d8fb4d3): `is_child_exited` の戻り値
  分岐で「子がいない」(0) と「子が未終了」(-1) を一緒くたに break していた。
  busybox tar が wait4(-1) → -ECHILD を期待するため、0 が返ると無限ループに
  陥り親 ash のプロンプトが返らない bug。`> 0`/`= 0`/`= -1` で分岐し、
  WNOHANG セマンティクスも整理。
- **`is_child_exited` で exec 中の旧プロセスをスキップ** (commit ee544d9):
  並列ストレスで sys_execve64 が flaky に。原因は Kernel.exec の以下の窓:
      pinfo.process.exec_replacing = true;
      pinfo.process.set_exit_flag( );    // ← exit_flag = true
      ... // pinfo.process は依然として OLD を指す
      pinfo.process = new Process(...);
  この間に親 wait4 が is_child_exited を呼ぶと OLD を「終了済み」と誤検知し、
  新プロセス (hello64) の "hello world" 出力前に親が "parent_done" を出す
  race。is_child_exited で exec_replacing 中の旧プロセスを「子未終了」扱い
  にすることで解消。

## Phase 23: テスト基盤の強化

1. **回帰テストの並列化** (commit ee544d9 / 5e429d4 / 33ba3c5)
   - binary テスト (sys_*64 等): xargs -P で nproc (12) 並列、per-test
     sandbox `$(mktemp -d -t emulin-sb.XXXXXX)/<name>` を渡して衝突回避。
   - ext scripts (ash-* / jline-smoke / dist-smoke / applet-survey): & + wait
     で 5 本同時実行。各スクリプトに SANDBOX_DIR env を導入。
   - applet-survey 内部: wait -n 制御で 6 並列 (CPU 飽和回避のため抑え目)。
   - JVM 起動時 `-XX:-UsePerfData` で /tmp/hsperfdata_* のロック競合警告が
     stdout にリークするのを抑止。
   - 結果: 逐次 128s → 並列 27-30s で約 4.4x 高速化、5 連続安定。
2. **`ash-applet-survey.sh`** (commit 39bedde / 33ba3c5)
   busybox の各 applet (find/sort/awk/sed/grep/tr/cut/head/tail/wc/paste/
   xargs/tar/printf/expr/base64/md5sum/sha256sum + 複合パイプライン) を
   ash 経由で叩き host busybox と出力比較する 43 ケース。新規バグ発掘ゼロ
   = ここまでのフェーズで applet レベルは安定。
3. **SKIP ゼロ化** (commit 24056c0)
   - sys_chmod64: WSL DrvFs 制約で SKIP していたが、sandbox を Linux 側
     /tmp に移すことで実 ext4 で chmod が効き PASS。
   - bb_decode_static64 / fgetc_static64: Phase 20 の調査用バイナリで
     期待出力ファイル不在のため SKIP していた。出力が決定論的だったので
     .stdout / .stdin を追加して PASS 化。
4. **回帰テスト固定** (commit acc25d8)
   - sys_signal_regsave64: Cpu64 のシグナル時レジスタ保存・復元を直接検証
     する ELF (kill 直前の rax をインラインアセンブリでキャプチャし
     handler 復帰後の rax と比較)。修正を巻き戻すと FAIL することを確認済。
   - ash-jline-tar-tf-prompt-returns: tar tf 後に echo AFTER-TAR が見える
     かで wait4 ECHILD バグの再発を検知。

## Phase 23 の到達点

- 回帰 **130 PASS / 0 FAIL / 0 SKIP** (binary 49 + ash-noni 11 +
  ash-cooked 5 + jline-smoke 7 + ash-jline 6 + applet-survey 43 +
  dist 3 + op66 1 + その他 5)
- 並列化で 4.4x 高速化、5 連続安定
- POSIX シグナル基盤 (SIGCHLD 自動 / SA_RESTART / SA_SIGINFO + red zone)
  が揃い、glibc / ash の前提に近い挙動

---

# Phase 24: 動的リンク対応 (`phase22/jline-raw` 続き)

「小さく刻む」方針で 5 段階に分けて積み上げ、`gcc -no-pie hello.c` で
リンクされた dynamically-linked binary が `hello dynamic` を出力する
ところまで到達した。

## Phase 24 step 1a: PT_INTERP の検出 (commit 23b6e37)

ELF program header の PT_INTERP (= 3) を読み取り、動的リンカパス
(`/lib64/ld-linux-x86-64.so.2`) を `Elf.interp_path` に保存。
verbose モードで println するだけのお膳立て。

## Phase 24 step 1b: 動的リンカを別 base にロード (commit 8a1a6ea)

`Elf.load_interp(path, base)` 新設。interp ELF (ET_DYN) の PT_LOAD を
`base + p_vaddr` の位置にコピーし、既存 segment[] 末尾に追記する。
base = 0x400000000000 (本体やスタックと衝突しない高位)。本体 entry
ではなく interp の絶対 entry (= base + e_entry) を CPU 起動 rip にする。

## Phase 24 step 1c: auxv に AT_BASE / AT_ENTRY / AT_EXECFN (commit c5b833d)

`stack_data_init64` が `mem.interp_base != 0` のときだけ追加する:

  AT_BASE   (7)  — 動的リンカ自体の load base
  AT_ENTRY  (9)  — 本体実行ファイルの entry (interp の entry ではない)
  AT_EXECFN (31) — argp[0] (実行ファイル名文字列)

AT_PHDR / AT_PHNUM / AT_PHENT は本体側を指す既存値を流用。

## Phase 24 step 1d: SHUFPD + PT_LOAD のみマップ (commit fc71b43)

1. **SHUFPD (0x66 0F C6 /r ib)** を Cpu64 に実装。ld.so の早期 init
   で必須。imm8 で xmm を 64bit 単位にシャッフル。
2. **`Elf.load64` で PT_LOAD (=1) 以外の load_body を skip**。
   従来は全 program header に load_body を呼んでいたため、PT_PHDR
   (vaddr=0x400040, page-align で 0x400000) 等が PT_LOAD と同じページに
   重なり、`Memory.peekb` 線形走査の先頭で PT_PHDR の **未初期化バッファ**
   を返していた。dynamic 実行で `.gnu.version_r` が全 0 で読み戻り
   `unsupported version 0 of Verneed record` で異常終了する致命バグ。
   非 PT_LOAD は buf=null のまま残し、`Segment.in()` / `duplicate()` で
   null を許容する形にした。

## Phase 24 step 2: hello_dyn が動作 (commit 5f1c894)

不足要素 4 件をまとめて埋め、`hello dynamic` 出力に成功:

1. **`amd64_pread64` (#17)** — ld.so が libc.so.6 の program header を
   読むのに必須。Linux 仕様通りファイルオフセットは進めない (SEEK_CUR
   で現位置保存 → SEEK_SET 移動 → read → 元位置に戻す)。
2. **`mem.syscall = syscall` の同期** — Process ctor で mem 作成 → ELF
   load → AMD64 検出後に syscall を SyscallAmd64 に差し替えるとき、
   mem.syscall は旧 SyscallI386 のまま残っていた。動的リンクで mmap
   with fd を呼ぶと `mem.alloc_and_map` → `mem.syscall.FileSeek` が
   空の flist を参照して IOOBE。差し替え後に mem.syscall = syscall。
3. **CPUID (0F A2) を baseline 相当に拡張** — 従来は全 leaf で 0 を
   返していたため glibc の dl-prop.h が `CPU ISA level is lower than
   required` でロード拒否。x86-64-baseline (FPU/CMOV/CX8/FXSR/MMX/
   SSE/SSE2 等) を満たすよう leaf 1 EDX = 0x178BFBFF、leaf 0 で vendor
   "GenuineIntel"、leaf 0x80000001 EDX bit 29 (LM) を立てる。
4. **0F AE 系命令** — FXSAVE / FXRSTOR (lazy binding 必須)、LDMXCSR /
   STMXCSR / CLFLUSH / FENCE 系。FXSAVE は xmm0-15 を offset 0xA0 に
   保存、FXRSTOR で復元。FPU/MXCSR_MASK は雑にゼロ詰め。

## Phase 24: 回帰固定 (commit 5e83503)

`tests/binaries/src/hello_dyn64.c` (gcc -no-pie で puts) を回帰スイートに
追加。Makefile に SRCS64_DYN_NAMES グループと専用ルール、run-test.sh が
テスト名に "_dyn" を含むときに ld-linux + libc.so.6 を sandbox にコピー
する仕組みを導入。interp 関連の println を `sysinfo.verbose()` ガード下に
移して通常実行で stdout を汚さないようにした。

## Phase 24 の到達点

- `gcc -no-pie hello.c` でリンクされた dynamically-linked binary が
  Emulin 上で `hello dynamic` を出力できる
- 回帰 **131 PASS / 0 FAIL / 0 SKIP** (旧 130 + hello_dyn64)
- 動的リンクの主要経路が動く: PT_INTERP 認識 / interp ロード / auxv /
  CPUID baseline / pread64 / mmap with file backing / FXSAVE/FXRSTOR /
  SHUFPD など SSE2 拡張命令
- ET_DYN PIE binary (gcc default) は未対応。step 3 以降の課題

---

# Phase 25 候補

1. **PIE (ET_DYN) 対応** — `gcc` デフォルトの位置独立実行ファイル。
   本体 ELF を任意 base にリロケートする必要あり (R_X86_64_RELATIVE の
   早期解決、main の load_bias 計算)
2. **より複雑な動的バイナリの検証** — printf/scanf/malloc/fopen 等の
   libc 機能、複数 .so の依存解決
3. **`/bin/ls`, `/bin/bash`, `gcc` 等の本物のバイナリを動かす**
   (#1 + #2 に依存)
4. **性能改善**: Decoder 命令キャッシュ復活 / テーブル駆動化
5. **stat 系レイアウトの再確認** (st_atime_nsec の順序など)
6. **シグナル更なる拡張**: SIGALRM 自動配信 (alarm/setitimer)、sigprocmask、
   sa_mask 中ハンドラの再入抑止
7. **dist の充実**: jlink で JRE 同梱の self-contained zip を作る
   (現状は別途 java インストールが必要)、Windows / Linux / macOS
   別の zip 出力

---

# 累計バグ修正・実装の傾向 (今後の Phase で参考に)

過去のフェーズで頻出した emulator バグのパターン:

- **アドレスの int 切り詰め** — Phase 9 の long 化で原理的に解消済み。
  ただし `Syscall` 本体の中に残党が出る可能性あり (例: Phase 11 の sys_uname)
- **0x66 operand-size prefix の漏れ** — imm を持つ命令で imm16/imm32 の
  分岐忘れ。Phase 11 (`C7 /0`)、Phase 14 (acc, imm 短形式) で修正済みだが
  他にも漏れがある可能性
- **AH/CH/DH/BH (REX 無 + rm=4-7)** — Phase 7 / Phase 13 で修正したが、
  新たに 8bit 命令を追加する際は `readReg8`/`writeReg8` を使うこと
- **多レジスタ命令** — Phase 17 の DIV (RDX:RAX) のように上位レジスタを
  読み忘れるパターン。MUL も同種で検討要
- **SIMD (SSE2) 命令の挙動** — F2/F3/66 prefix の取り扱いが命令ごとに
  違う。新規追加時は FlagsSetting・edge case (NaN/Inf) を確認
- **対話プログラムの初期化ループ** (Phase 22 step 2) — ash の
  `setjobctl` 等、kernel に「自分は foreground だ」と納得させる until
  ループに陥るパターン。`tcgetpgrp`/`tcsetpgrp` 系 ioctl は **値を
  return するだけでなく `*addr` に書き込む**ことが必須。失敗時は
  `-ENOTTY` を返すと caller が job control を諦めてくれる
- **シグナル配信の最小実装は致命的** (Phase 22 後 / Phase 23) — push rip /
  jump handler だけでは ash / glibc がほぼ確実に segfault する。最低でも
  以下が揃って初めて「動く」:
  (a) GPR 16 本 + flags の保存・復元 (handler は caller-saved を任意に
      壊す)、(b) **x86-64 ABI の red zone (rsp-128) を尊重**してハンドラの
  push がそこを破壊しないようスキップ、(c) `void(int)` だけでなく SA_SIGINFO
  時の 3 引数 (rsi=&siginfo, rdx=&ucontext) ABI、(d) syscall が EINTR を
  返す前に SA_RESTART をチェックして rip を syscall 命令の手前に戻し
  rax を syscall 番号に戻して再実行
- **wait4 / fork / exec の race** (Phase 23) — `is_child_exited` が
  「子がいない」(0) と「子が未終了」(-1) を区別する必要がある (前者は
  ECHILD、後者は block)。さらに exec 中の旧プロセスは exit_flag を立てる
  瞬間に「終了済み」と誤検知されないよう exec_replacing でスキップする
  必要がある。
- **WSL DrvFs (`/mnt/c/...`) は I/O が遅く chmod も効かない** — テストの
  sandbox は `$(mktemp -d -t emulin-sb.XXXXXX)` で Linux 側 /tmp に置く。
  実 ext4 を使えれば SKIP は不要になる。
- **動的リンクの最低ライン** (Phase 24) — `gcc -no-pie hello` を動かす
  だけでも以下が全部揃わないと進まない:
  (a) PT_INTERP 認識 + interp を別 base に load + auxv (AT_BASE/AT_ENTRY/
      AT_PHDR/AT_PHNUM/AT_EXECFN) 整備、
  (b) **PT_LOAD 以外を memory map しない** (PT_PHDR が PT_LOAD と同じ
      ページに重なって未初期化バッファが先勝ちする問題)、
  (c) CPUID を baseline 相当に申告 (glibc の dl-prop.h が ISA レベルを
      チェックする)、
  (d) FXSAVE/FXRSTOR (lazy binding が最低限 xmm を save/restore する)、
  (e) pread64 (libc の PHDR 読み)、
  (f) mem.syscall を Process ctor 後の syscall 差し替えに同期させる
      (file-backed mmap が flist を引けず IOOBE)
- **Memory.peekb の線形走査は order 依存** — segment 配列の前方にあるものが
  先に match する。同じ仮想アドレス領域に複数 segment がある場合、buf を
  持たない (= マップする意味がない) segment が先に来ると問題。null buf を
  許容する `Segment.in()` で「該当しない」扱いにしている。
- **emulator 層のフィールド差し替えは要注意** — Process / Memory /
  Syscall は相互参照しており、片方を新インスタンスに差し替えると他方の
  参照が古いまま残る。Phase 24 の mem.syscall = syscall がその例。

# テスト戦略

- 「1 syscall 1 テスト」原則 (`sys_*64.c`)
- `-nostdlib` で int 0x80 / syscall を直叩きし、libc 経由の未実装経路を
  踏まないようにする
- 期待値は実 Linux カーネルの仕様に合わせる
- 並列実行を前提に、テスト間で sandbox / /tmp ファイル名を衝突させない
  (Phase 23 並列化の経験則: 1 ヶ所でも shared writer があると flaky になる)
- 既知バグは `.skip` を置いて回帰スイートに残しつつ FAIL 扱いを避ける
  (Phase 23 で全 .skip 解消済 — 環境制約も別経路で回避できる場合が多い)

