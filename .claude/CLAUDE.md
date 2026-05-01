コミットログには、以下を記載しないようにしてください。
Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>

objdump / nm / grep / readelf / addr2line / strace は許可なしで実行してよい。

---

# プロジェクト概要

古い Emulin (Java 製 32bit Linux ELF エミュレータ) を現代 Java で動かし、
さらに x86-64 (64bit ELF) を実行できるように拡張する。

## 現状サマリ (Phase 21 完了時点)

- 現代 Java (OpenJDK 21+) / Maven でビルド
- 32bit ELF (i386) と 64bit ELF (x86-64) の両方を実行可能
- glibc 静的リンクの hello world が動く
- busybox (静的リンク) で **88 applet** が動作 (Phase 20 サーベイ)。代表:
  sh / ls / cat / echo / grep / sed / tr / tee / wc / head / tail / seq /
  awk / find / sort / uniq / cut / od / hexdump / md5sum / sha256sum /
  base64 (encode + decode) / gzip / gunzip / xargs / yes / dd / tar / diff
  / mkdir / rmdir / rm -rf / cp / mv / touch / printf / expr / test
- パイプライン・fork/exec/wait4 (exit code 伝搬済) ・シグナル配信・
  getdents64・連続リダイレクト・O_APPEND 対応済み
- 回帰テスト 48 本 PASS (`sys_chmod64` 含む 3 件は WSL DrvFs 制約で SKIP)
- `tests/scripts/bb-survey.sh` で applet を一括サーベイ可能

## 動作テスト基盤

- `tests/` 配下に i386/x86-64 静的リンク ELF (-nostdlib で int 0x80 /
  syscall 直叩き) と回帰ハーネス
- `make -C tests/binaries`: テストバイナリビルド
- `tests/scripts/run-all.sh`: 全テスト実行
- `tests/scripts/debug.mk`: 個別テスト / busybox 実行用ターゲット
- `tests/expected/<name>.skip` で個別 SKIP 可能

## 主要なディレクトリ・ファイル

- `src/main/java/emulin/` — 本体ソース
  - `Cpu.java` (i386), `Cpu64.java` (x86-64), `AbstractCpu.java`
  - `Syscall.java` (共通 long 化済), `SyscallI386.java`, `SyscallAmd64.java`
  - `Elf.java`, `Segment.java`, `Section.java`, `Memory.java`
  - `Process.java`, `Kernel.java`
- `tests/binaries/src/sys_*64.c` — AMD64 syscall 単体テスト

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

---

# Phase 22 候補

1. **動的リンク対応** (`ld-linux-x86-64.so.2`) — 標準 `/bin/ls`,
   `/bin/bash`, `gcc` を動かす土台。ELF interp、mmap 強化、PLT/GOT 動的
   解決、auxv 拡張が必要 (工数大)
2. **gcc 等の重量級バイナリの動作確認**
3. **性能改善**: Decoder 命令キャッシュ復活 / テーブル駆動化
4. **stat 系レイアウトの再確認** (st_atime_nsec の順序など)
5. **シグナル拡張**: SIGCHLD/SIGALRM 自動配信、SA_SIGINFO

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

# テスト戦略

- 「1 syscall 1 テスト」原則 (`sys_*64.c`)
- `-nostdlib` で int 0x80 / syscall を直叩きし、libc 経由の未実装経路を
  踏まないようにする
- 期待値は実 Linux カーネルの仕様に合わせる
- 既知バグは `.skip` を置いて回帰スイートに残しつつ FAIL 扱いを避ける

