# Emulin 設計書 (DESIGN.md)

Version 0.3.0 時点のソースコードに基づくアーキテクチャ解説。

> 本書はソースコード (`emulin/*.java`, `emulin/device/`) を解析した結果を、
> 主要クラス・継承関係・実行フロー・データ構造・データフローの観点から
> 整理したものである。

---

## 1. 概要

Emulin は Linux/IA-32 (i386) および Linux/x86-64 用 ELF 実行バイナリを
Java 上で動作させるソフトウェアエミュレータである。以下 3 層から成る:

1. **CPU エミュレーション層** — IA-32 / x86-64 命令のフェッチ / デコード /
   実行 (`Cpu.java` = i386, `Cpu64.java` = x86-64)
2. **OS エミュレーション層** — Linux カーネルの `int 0x80` (i386) /
   `syscall` (x86-64) システムコールの仮想実装 (プロセス、シグナル、
   パイプ、ファイル、ソケット、pthread、TLS 含む)
3. **仮想ファイルシステム層** — Linux 風の `/` ルート以下のパス空間を
   ホスト OS のディレクトリにマップする

エントリポイントは `emulin.Emulin#main`。`Kernel` がプロセステーブルを
保持し、各ユーザプロセスは `java.lang.Thread` を継承した `Process` として
動作する。

---

## 2. パッケージ構成

```
emulin/                          ... コアパッケージ
  Emulin.java                    ... main(): 引数処理 → Kernel 起動
  Version.java                   ... バージョン定数 ("0.3.0")
  -- カーネル層 --
  XKernel.java                   ... ptable, console, println の基底
  PipeManager.java               ... 名前なしパイプの実装
  Kernel.java                    ... boot/start/fork/exec/kill
  ProcessInfo.java               ... ptable のエントリ (process + ppid)
  -- プロセス層 --
  Signal.java / Siginfo.java     ... POSIX シグナル
  Process.java                   ... Thread を継承した実行コンテキスト
  -- CPU 層 --
  XInstruction.java              ... 命令 ID 定数 (mkinstid で自動生成)
  Instruction.java               ... 命令メタ情報 (オペコード, マスク)
  XDecoder.java                  ... 命令テーブル & デコード
  Decoder.java                   ... 命令テーブル定義 (mkope で自動生成)
  Decodeinfo.java                ... デコード結果
  Operand.java                   ... オペランド表現
  Cpu.java                       ... フラグ, レジスタ, eval()
  -- メモリ / ELF --
  Elf.java                       ... ELF32 ヘッダ読み込み, brk 管理
  Segment.java                   ... プログラムセグメント
  Section.java                   ... セクションヘッダ
  Memory.java                    ... 仮想メモリ alloc/load/store
  LoadUtil.java                  ... リトルエンディアン読み込み
  -- システムコール / I/O --
  RootSysinfo.java               ... 設定定数 (stack_bottom, uid 等)
  Mount.java                     ... 仮想 ↔ ネイティブパス変換
  Sysinfo.java                   ... emulin.cnf ロード
  FileAccess.java                ... fd / Vector flist 管理
  Fileinfo.java                  ... 1 fd あたりのメタ情報
  Inode.java                     ... stat 用属性
  EmuSocket.java                 ... socket/bind/connect/listen/...
  SubProcess.java                ... ソケット入力監視スレッド
  RingBuffer.java                ... SubProcess 用バッファ
  Syscall.java                   ... int 0x80 ディスパッチ + 各 sys_*
  -- ユーティリティ --
  Util.java                      ... hex, byte 配列変換, realname
  -- コード生成補助 --
  opecode.dat                    ... 命令テーブル定義 (バイナリ)
  mkope, mkinstid                ... opecode.dat → *.java を生成

emulin/device/                   ... コンソールデバイス
  Console.java                   ... 抽象 read/write/check_int
  StdConsole.java                ... System.in / System.out 経由
  NativeConsole.java             ... JNI (emu_con) 経由 Ctrl-C 取得
  unix/   emu_con.c, libemu_con.so
  windows/ emu_con.c, emu_con.dll
```

---

## 3. クラス継承階層

```
java.lang.Thread
 ├─ Signal ── Process                    (ユーザプロセス本体)
 └─ XKernel ── PipeManager ── Kernel     (カーネル本体。boot 後 sleep ループ)

RootSysinfo ── Mount ── Sysinfo          (システム情報)
FileAccess  ── EmuSocket ── Syscall      (I/O)
Elf         ── Memory                    (アドレス空間)
XInstruction ── Instruction              (命令テーブル要素)
XDecoder    ── Decoder ── Cpu            (CPU)
StdConsole  ── NativeConsole ── Console  (コンソール抽象)
```

継承を組み合わせて、各クラスは多数のフィールドを横断的に持つ
（小さなインタフェースで分割する設計ではなく、Java 1.1 時代の
"ベースクラスへ機能を継ぎ足していく" スタイル）。

---

## 4. 起動シーケンス

```
Emulin.main(argv)
 ├─ Sysinfo を生成 (verbose / debug / console-type をパース)
 ├─ Kernel を生成 → sysinfo.kernel = kernel
 ├─ sysinfo.set_root(argv[0])         ... 仮想 / の指す native パス
 ├─ sysinfo.load_config("/etc/emulin.cnf")
 ├─ kernel.boot(args, user.dir)
 │    ├─ ネイティブコンソール選択時は JFrame "Interrupt" ボタン表示
 │    ├─ pid=1: init プロセスを生成 (Process.set_init_process)
 │    ├─ pid=2: ユーザプロセス (引数の ELF) を生成
 │    │         ├─ Process(...) コンストラクタ
 │    │         │  ├─ Syscall, Memory, Cpu を新規 / 継承
 │    │         │  ├─ Memory.load(ELF)         (Elf.load)
 │    │         │  ├─ Memory.load_symbol(*.nm)
 │    │         │  ├─ Cpu.connect_devices(mem, syscall)
 │    │         │  ├─ Cpu.set_ip(entry), set_sp(stack_bottom)
 │    │         │  └─ stack_data_init(): argc/argv/envp + シグナル
 │    │         │                          ハンドラフックコード書き込み
 │    │         └─ pinfo.process.start()      (Thread 起動)
 │    └─ ptable に登録, cur_pid++
 └─ kernel.start()
       └─ メインループ: 1 秒スリープ + exec 要求の処理 + 残プロセス監視
                       残プロセスが init のみになると System.exit(0)
```

`-S` スイッチ時は `setup()` がプラットフォームを判別して `boot.sh` /
`boot.bat` 相当の起動スクリプトを標準出力へ吐き出すだけで終了する。

---

## 5. プロセスとスレッドモデル

| 役割     | 実体                 | 振る舞い |
|----------|---------------------|----------|
| Kernel   | `XKernel` (Thread)  | 1 秒ごとに `processes()` を確認、`exec_request` 処理、生存プロセスを監視 |
| init     | `Process` (Thread)  | pid=1。ループ内でネイティブコンソールから割り込みを取り込み続ける |
| ユーザ   | `Process` (Thread)  | pid≥2。1 命令ずつフェッチ → デコード → `Cpu.eval()` を回す |
| SubProc. | `SubProcess` (Thread)| ソケット fd ごとに 1 本。`accept()` または読み込み待ちを別スレッドで実行し、`RingBuffer` 経由で本体に渡す |

`Thread.yield()` と `Thread.sleep(...)` を多用するクラシックな
協調マルチスレッド方式（Java 1.1 想定）。同期は `synchronized`
キーワードのみ。

### fork

`Kernel.fork()` は `Process.duplicate()` で:

- `Cpu`, `Memory`, `Syscall` をディープコピー
- ELF セグメント, alloc 済みメモリ (`AllocInfo`) を全部コピー
- 子の `EAX = 0`、`IP += 2`（`int 0x80` の次の命令）に進める
- 親の `Syscall` (FileAccess) と `pipe_connection()` で fd 表をコピーし、
  パイプ参照カウントをインクリメント

### exec

`sys_execve` は `Kernel.exec_request()` でカーネルに依頼するだけで自身は
`Thread.stop()` する。Kernel スレッドのメインループが要求を拾い、
`Kernel.exec()` で:

- 既存 Process スレッドを stop
- `Syscall` (= fd 表) をバックアップして引き継ぎ
- 新しい `Process` を同じ pid で生成・起動

### kill / signal

`Kernel.kill(pid, sig)` → `Process.recv(sig)` →
`Signal.signals[sig].count++`。

`Process.run()` の各反復先頭で `psig()` が立っているシグナルを検出し、

- `SIG_IGN` なら無視
- `SIG_DFL` なら既定動作（ほとんど exit）
- 登録済ハンドラなら `cpu.set_signal_handler(ip, hook_addr)` で
  ユーザコードへ分岐。スタック上には `stack_data_init` で予め書き込まれた
  「PUSHA → ハンドラ呼出し → POPA → IRET 風の RET」コードが用意されており、
  その途中の即値領域 (`sig_no_embed_adrs`, `handler_embed_adrs`) を
  実行直前に書き換えてシグナル番号と関数アドレスを埋め込む

---

## 6. CPU エミュレーション

### デコード

- `Decoder.add_inst()` (mkope による自動生成) が約 200 件のオペコードを
  `Instruction` 配列に登録する
- `XDecoder.decode(ip, buf, cache)` が:
  - プレフィックス (`0x66` o16, `0x67` a16, `0xF2/F3` REPNZ/REPZ) を処理
  - 命令テーブルをマスクとオペコードで線形検索
  - 必要なら ModR/M, SIB, disp, imm を解析して `Decodeinfo` の
    `src` / `dst` / `fst` に `Operand` を構築
  - 命令長 `inst_len` を返す

`Decodeinfo` には d/w/W/s/r/c/D の各フラグおよび値が保持され、
`Cpu.calc_operand_size()` で 1/2/4/8 バイトのうち実効サイズを決定する。

### 実行

`Cpu.eval()` は dinfo.inst_id に対する巨大な if 連鎖で各実装メソッド
（`add()`, `sub()`, `mov()`, `j()`, `call()`, `interrupt()` ...）を呼ぶ。

- レジスタは `int reg[8]` (EAX, ECX, EDX, EBX, ESP, EBP, ESI, EDI)
- フラグは個別の `int of, df, sf, zf, af, pf, cf` フィールド
- FPU は `long float_stack` 1 本のみで FLD / FST / FILD / ... は
  ほぼスタブ (実数演算は実装されていない)
- `INT 0x80` は `interrupt()` → `syscall.call(eax, ebx, ecx, edx, esi, edi)` →
  戻り値を `EAX` へ
- 8086 セグメンテーションは未サポート（フラットメモリ前提）

`set()` / `_set()` がサイズ別に書き戻し、`ref()` / `_ref()` が読み出し、
実効アドレスは `ea()` が `base + index*scale + disp` で算出する。

### 命令キャッシュ

`Decoder` 内に dcache 用の枠は残るが、現バージョンではコメントアウト
されており実質無効。`cache_check()` は常に false を返す。

---

## 7. メモリモデル

```
0x00000000                                                       0x70000000
 |---- ELF segments (R/W/X) ----|----- alloc 領域 (mmap/brk) -----|--Stack--|
                                ↑ memory_top = 0x40000000           ↑ stack_bottom
                                                                   = 0x70000000
                                                                   stack_size  = 0x10000
```

- `Segment` … ELF プログラムヘッダから読み込んだバイト列を `buf` に保持。
  追加でスタック用セグメント 1 個を `Segment.stack(stack_size)` で確保
- `AllocInfo` … `Memory.alloc()` が確保する追加メモリ。`mmap` 用には
  `alloc_and_map()` が `fd`, `map_offset`, `map_size` を保持
- ELF セクションのうち `.bss` をスキャンして `brk` 初期値を取得。
  `sys_brk` は所属セグメントを `expand_memory()` で伸縮
- `load8()` は `cache_size = 8` バイトの簡易ラインキャッシュを 1 本保持
- 範囲外アクセスは `Segmentation Fault` を出力して `System.exit(1)`

リトルエンディアン読み書き (`load16/32/64`, `store16/32/64`) と
文字列読み書き (`loadString`, `storeString`) は全て `load8` / `store8`
の上に積まれる。

---

## 8. ELF ローディング

`Elf.load(filename)`:

1. 16 バイトの `e_ident` を読み、`0x7F 'E' 'L' 'F'` を確認
2. `ET_EXEC` かつ `EM_386` を確認
3. `Program Header` を全件 `Segment.load_ph()` で読み込み、末尾に
   スタック用セグメントを追加
4. 各セグメントの本体を `Segment.load_body()` でファイルから読み込み
5. `Section Header` を読み、`.bss` を見つけたら `brk` を初期化
6. `<filename>.nm` (objdump --syms 風のテキスト) を `load_symbol()` で
   読み込み、`get_symbol(addr)` でディスアセンブル時に補助情報として
   表示

---

## 9. システムコール

`Syscall.call(id, bx, cx, dx, si, di)` が Linux i386 ABI に従って
ID をディスパッチする。実装されている主な ID:

| 区分        | ID 例                                                       |
|-------------|-------------------------------------------------------------|
| プロセス    | 1 exit, 2 fork, 11 execve, 7/114 wait4, 20/64 getpid/getppid|
| ファイル    | 3 read, 4 write, 5 open, 6 close, 19 lseek, 33 access, 39 mkdir, 40 rmdir, 38 rename, 10 unlink, 41/63 dup/dup2 |
| メモリ      | 45 brk, 90 mmap (`old_mmap`), 91 munmap, 125 mprotect       |
| 時刻        | 13 time, 78 gettimeofday, 27 alarm, 162 nanosleep            |
| シグナル    | 37 kill, 67 sigaction, 126 sigprocmask                       |
| 端末        | 54 ioctl (TCGETS, TCSETS 系を `Console.set_parameter`)        |
| FS マウント | 21 mount, 22 umount → `Mount.add/remove_mountpoint`          |
| ソケット    | 102 socketcall (sub-op で socket/bind/connect/listen/accept/send/recv) |
| その他      | 122 uname, 75/76 set/getrlimit, 142 select, 141 getdents 等   |

未実装の ID は警告を出して `sys_exit(1)` する。

引数受け渡しは Linux/i386 ABI のレジスタ規約 (`%ebx, %ecx, %edx, %esi, %edi`)。
ポインタ渡しは `mem.loadString()` や `mem.load32(addr+i*4)` で
配列を組み立てる。

---

## 10. 仮想ファイルシステムとパス変換

`Mount` クラスが下記 2 系統の対応表を保持する:

- ルートマウント: `set_root(native_path)` で 1 つだけ
- 追加マウント: `add_mountpoint(virtual, native)` で複数。`fstab` 風

API:

- `get_native_path("/bin/ls")` → ホスト OS のフルパス
  (Windows ならセパレータも `\` に変換)
- `get_virtual_path(...)` … 逆方向
- `get_full_path(curdir, name)` … 相対 → 絶対 + `..` 解決 (`Util.realname`)
- `<std>`, `<err>`, `<sock>`, `<null>` のような `<...>` は内部疑似パスで
  変換しない

`Inode(vpath, sysinfo)` は `java.io.File` の API でファイル属性を取得し
`stat` 構造体相当を組み立てる。`st_ino` は実 inode が取れないので
パス文字列のチェックサムで疑似生成する。

---

## 11. ファイルディスクリプタとパイプ / ソケット

`FileAccess.flist` は `Vector<Fileinfo>`。空き fd は番号の小さい方から再利用。

`Fileinfo` は通常ファイル / ディレクトリ / 標準入出力 / パイプ /
ソケットを 1 つの構造で扱うフラグ付き表現:

- 通常ファイル … `RandomAccessFile f`
- 標準入出力 … `std_flag / stderr_flag` のみ
- パイプ … `pipe_in_flag / pipe_out_flag + pipe_no` (`Pipeinfo`
  を `PipeManager.pipetable[pipe_no]` で参照)
- ソケット … `socket_flag, stream_flag, conn (Socket), sconn
  (ServerSocket), dgram (DatagramSocket)`、入力受信用 `SubProcess`

### パイプ

`Pipeinfo` は 64KB のバイトリングバッファで、`i_connected / o_connected` の
参照カウントで両端の close を検出。`fork` / `dup` でカウント増、`close` で
カウント減。0 になれば EOF。

### ソケット

`SubProcess` がブロッキング `read` / `accept` を別スレッドで実行し、
データを `RingBuffer` (`BUFSIZE = 1024`) に貯める。本体の
`sys_read` 系は `RingBuffer` から取り出すので非同期化される。

---

## 12. コンソール

`Console` extends `NativeConsole` extends `StdConsole`。

- `CONSOLE_NONE` (`-CN` なし) … `Std_read`/`Std_write` のみ
- `CONSOLE_NATIVE` (`-CN`) …
  - ロード: `System.loadLibrary("emu_con")`
    (`emulin/device/unix/libemu_con.so` または `windows/emu_con.dll`)
  - 1 文字ずつ非ブロッキング読み込み
  - 端末の `c_lflag/c_iflag/c_oflag` を JNI 経由で設定
  - Ctrl-C 検出 → `Kernel.kill(-1, SIGINT)` で全プロセスへ送信
  - Swing の "Interrupt" ボタンも同じ経路で SIGINT を送る

---

## 13. データフロー（典型: `read(0, buf, n)`）

```
ユーザコード:   mov $3, %eax  ;  int $0x80
Cpu.eval()      → INT 命令を識別
Cpu.interrupt() → syscall.call(eax=3, ebx=0, ecx=buf_addr, edx=n)
Syscall.sys_read(fd=0, addr=buf_addr, len=n)
  isSTD(0) == true
  → Console.read(byte[len], process)
  → CONSOLE_NONE: System.in.read() ループ
  → CONSOLE_NATIVE: native_read() + ICRNL 等の変換
  読み込んだバイトを Memory.store8(addr+i, b) で書き戻す
戻り値: 読み込み長 → eax にセットして次命令へ
```

---

## 14. ビルドとコード生成

`Makefile`:

- `JAVAC = javac -O -deprecation` で全 `*.java` をコンパイル
- `Decoder.java` と `XInstruction.java` は `mkope`, `mkinstid` (Perl)
  が `opecode.dat` から生成。テーブル変更時は `opecode.dat` を編集する
  運用が想定されている
- `bench.c` / `elfbin.c` は性能測定および ELF 生成テスト用
- `release` ターゲットは外部 `release.script` を呼ぶ

`bootunix.sh` / `bootwin.sh` は `setup` (-S スイッチ) が出力する
起動スクリプトの雛形。

---

## 15. 制約と未実装

- **CPU**: FPU は完全な実装ではない。FCHS/FXCH は空、FILD は値を 0 にする等
  正確な浮動小数点演算は出来ない。`af` (補助キャリーフラグ) は更新されない
- **アドレス空間**: 16bit セグメンテーションなし。LDT/GDT も無し
- **mmap**: 単にメモリにファイル内容を読み込むのみ。書き戻しは保留警告のみ
- **シンボリックリンク**: `Inode` 段階で「常に nlink=1」と扱う
- **マルチスレッド**: `Thread.stop()` を多用しており、Java 2 以降では非推奨
- **HotSpot 1.0 (Windows)** では正しく動作しないと README に明記

---

## 16. 主要な不変条件 / 規約

- 全クラスのメソッドは "仮想パス" (`/` 区切り) を入出力とし、
  ホスト OS への接触直前に `Mount.get_native_path` で変換する
- `Process.evals` は実行命令数カウンタ。デバッグ時にトリガとして
  使えるようハードコードされた条件式が残る
- `Sysinfo` は全体で 1 つだけ作られ、`Kernel`、`Process`、`Syscall`、
  `Memory`、`Console` から参照される疑似グローバル
- `verbose() / debug()` でログを大量に出すため、性能を出したい場合は
  両方 OFF が前提

---

## 17. 参考

- README.md … インストール / 起動方法
- `emulin/Makefile` … ビルドターゲット一覧
- 各ソースの `$Id:` 行 (RCS/CVS のキーワード展開) … 当時の更新履歴
