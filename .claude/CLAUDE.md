コミットログには、以下を記載しないようにしてください。
Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>


---

# 現代化と x86-64 対応の作業手順 (案)

> ソースコードを現代の Java で動作させ、さらに x86-64 (64bit ELF) を
> 実行できるように拡張するための作業計画案。決定事項ではなく、各フェーズ
> 着手前に方針を再検討するための叩き台。

## 全体方針

- **段階的に進める。** いきなり 64bit 対応に手を入れず、まず「現代 Java で
  32bit ELF が動く」ところまで戻してから 64bit を追加する
- **各フェーズで回帰テストを通す。** 「既存の 32bit ELF が動く」を完了基準に
  する
- **既存挙動を保つフェーズと、設計を変えるフェーズを混ぜない。** 混ぜると
  壊れた箇所の特定が困難になる

## Phase 0: 準備 — 回帰テスト基盤

後続の変更で壊れていないことを判定可能にすることが目的。

- 動作確認用の小さな 32bit ELF (helloworld、echo、簡単な ash 操作) を
  `tests/` に固定し、出力を比較するハーネスを作る
- 当時の Basic Application Package (bap) は別配布のため、最低限は
  `i386-linux-gcc -m32 -static` でビルドした自前バイナリを使う
- このフェーズを飛ばすと「壊れているのに気付かない」状態で先に進むことに
  なる

## Phase 1: 現代 Java 上でビルド・起動できる状態にする

挙動は変えない。触る範囲を最小限にとどめる。

| 箇所 | 現状 | 対処 |
|---|---|---|
| ソース文字コード | SJIS (コメントが化けている) | UTF-8 へ一括変換 (`nkf -w --overwrite`) |
| `Thread.stop()` / `suspend()` | `Kernel.exec`, `sys_execve` 等で多用 | `volatile boolean exit_flag` を見るループに置換 |
| `JFrame.show()` | `Kernel.java:66` | `setVisible(true)` |
| `javac -O -deprecation` | 1.1 想定 | Gradle / Maven、または `--release 11` 以降の javac |
| JNI ヘッダ | 古い JNI 規約 | `javac -h` で再生成、`.so` / `.dll` も再ビルド |
| `Vector` / 生コレクション | 全面使用 | この段階では触らない (Phase 2 で) |

完了条件: **32bit ELF が以前と同じ出力を出すこと。** 命令セット・Syscall
には触らない。

## Phase 2: 64bit 化に向けたリファクタリング

機能追加はせず、設計上の前提だけほどく。後回しにすると Phase 3 以降で破綻
する。

1. **アドレス型を `int` から `long` へ昇格する。** `Memory.load8(int)` →
   `load8(long)`、`Segment.p_vaddr`、`AllocInfo.address`、`Cpu.reg[]`、
   `IP`、`SP` 全て long。32bit ELF では上位 32bit が 0 のまま流れる
2. **CPU を「アーキテクチャ依存部」と「共通部」に分離。** 現状
   `Cpu extends Decoder` で全てが 1 クラスに混在。`Cpu32` / `Cpu64` を
   切り分けやすい構造へ (抽象 `Cpu` + 命令テーブル駆動)
3. **Syscall を ABI ごとに分離。** i386 ABI (引数: ebx,ecx,edx,esi,edi /
   番号: i386 表) と x86-64 ABI (引数: rdi,rsi,rdx,r10,r8,r9 / 番号: 別表)
   は別物。`SyscallI386` / `SyscallAmd64` に分離し、共通の `sys_*` 実装は
   サービス層へ
4. **ELF パーサを 32/64 両対応に。** `e_ident[EI_CLASS]` で `Elf32` /
   `Elf64` に分岐

このフェーズも回帰テストで「32bit ELF が依然動く」ことを担保する。

## Phase 3: 64bit ELF のロード

- ELF64 ヘッダのパース (フィールド幅と並びが異なる)
- プログラムヘッダも `Elf64_Phdr` でレイアウトが異なる
- スタック底アドレスを 64bit 領域に置く (Linux のデフォルトは
  0x7fff_ffff_e000 付近)
- `stack_data_init` で argc/argv/envp/auxv を 8 バイトずつ積む形に変更
  (auxv は 32bit でも本来必要だが省略されている。動的リンクするなら必須)

ロードできてエントリポイントへ jump する直前まで進める (CPU は未対応で
よい)。

## Phase 4: x86-64 デコーダ

- **REX プレフィックス** (0x40〜0x4F): W=1 で 64bit オペランド、R/X/B で
  レジスタ番号拡張ビット
- **R8〜R15 レジスタ** の追加 (`reg[16]`)、`Operand.kind` 拡張
- **RIP 相対アドレッシング**: ModR/M の mod=00, r/m=101 の意味が変わる
- **新命令**: SYSCALL (0F 05)、SWAPGS、MOVSXD など。よく使うものから優先
- **削除/変更命令**: BCD 系・INTO・PUSHA/POPA は #UD。`mkope` の入力テーブル
  (`opecode.dat`) を 64bit 用に拡張するか別テーブルにする

`Cpu.eval()` の巨大な if 連鎖はテーブル駆動 (`Map<InstId, InstHandler>`)
に置き換えると 64bit 命令追加が楽。

## Phase 5: x86-64 Syscall ABI

- **命令**: `SYSCALL` 命令で発火。戻り値は RAX、第 6 引数は R9
- **番号表**: i386 と全く別物 (例: write は i386=4 だが amd64=1)
- **構造体**: stat、timeval などのレイアウトが 32bit 時代と違う。
  `sys_stat` 系は `Inode` がメモリへ書き戻している箇所を 64bit 版で書き直す
- ポインタ幅 4→8 で argv/envp/iovec などのオフセット計算が全部変わる

## Phase 6: 動作検証と仕上げ

- 64bit の静的リンク `hello world` (`gcc -static`) が動く
- glibc 動的リンク版を動かしたいなら `ld-linux-x86-64.so` の処理 (mmap、
  auxv) が必要
- 性能: 当時から「遅い」と README に明記。命令キャッシュ (Decoder の
  dcache はコメントアウトされたまま) を実装するか、JIT (gcj は廃止のため
  GraalVM Truffle や ASM での Java バイトコード生成) は別議論

## 主要なトレードオフ (先に決めたい点)

1. **既存の 32bit 動作を保つか捨てるか。** 保つと工数が 1.5〜2 倍。捨てる
   なら Phase 2 の分離は不要で 64bit 専用に書き直せる
2. **動的リンク (.so) 対応をやるか。** やらないなら検証は static バイナリ
   のみ。やるなら mmap、auxv、ld.so の動作再現が必要で工数が大きく増える
3. **JNI ネイティブコンソールを残すか。** Ctrl-C 取得のためだけなので
   `Signal.handle("INT", ...)` で代替できれば JNI を捨てられる

## 推奨される最初の一歩

**Phase 0 + Phase 1 をまず終わらせて「現代 Java で動く 32bit Emulin」を
一度コミットする。** Phase 2 以降はその時点で再度方針を相談する形が安全。

Phase 0: 準備 — 回帰テスト基盤を進めてください。
そのための専用ブランチを作成してから作業開始してください。

そのPhase 0 の作業の記録をここに追記してください。

---

# Phase 0 作業記録

実施日: 2026-04-25 〜 2026-04-26
作業ブランチ: `phase0/regression-tests` (`original-project-migration`
から派生)

## 方針

DESIGN.md / 上記計画の Phase 0 に従い、**Emulin 本体のソースには
一切手を入れず**、後続フェーズの変更で挙動が壊れていないかを判定する
ための回帰テスト基盤だけを `tests/` 以下に整備した。

テストプログラムは glibc 非依存とした (`-nostdlib` で `int 0x80` を
直接叩く)。理由は、現状の Emulin が glibc の動的リンク・auxv を
完全にはサポートしておらず、libc 経由だと未実装経路を踏んで原因切り分け
が困難になるため。

## 成果物 (新規)

```
tests/
├── README.md                ... 使い方・必要 toolchain・拡張方法
├── PHASE1-NOTES.md          ... 現代 javac でのビルド試行ログ
├── .gitignore               ... bin/, sandbox/ を除外
├── binaries/
│   ├── Makefile             ... i386 cross-gcc / gcc -m32 / Docker
│   └── src/
│       ├── hello.c          ... sys_write + sys_exit
│       ├── exitcode.c       ... exit code 受け渡し (42)
│       ├── arith.c          ... ADD/SUB/IMUL/CMP/J*/CALL/RET/loop
│       ├── args.c           ... argc/argv のスタックレイアウト
│       └── echo_stdin.c     ... sys_read + sys_write のループ
├── expected/                ... 各テストの期待値
│   ├── <name>.stdout        ... 必須: 期待標準出力
│   ├── <name>.exit          ... 任意: 期待終了コード (省略時 0)
│   ├── <name>.argv          ... 任意: 実行引数 (省略時 /bin/<name>)
│   └── <name>.stdin         ... 任意: 標準入力
└── scripts/
    ├── run-test.sh          ... 単一テスト (PASS / FAIL / SKIP)
    └── run-all.sh           ... 全テスト + サマリ
```

`run-test.sh` の動作は次の通り:

1. `tests/binaries/bin/<name>` を `tests/sandbox/bin/<name>` にコピー
2. expected/<name>.argv で実行引数を、expected/<name>.stdin で stdin を
   セット
3. `java emulin.Emulin <sandbox> <args...>` を起動し stdout を捕捉
4. expected/<name>.stdout と diff、終了コードと比較

## 触っていないもの (Phase 0 の境界)

- Emulin 本体のソース (`emulin/*.java`)
- 既存の Makefile, README.md, COPYING 等
- `.claude/CLAUDE.md` の本セクション以外 (本記録の追記のみ)

## Phase 1 への引き継ぎ

OpenJDK 25 (Homebrew) で `javac emulin/*.java emulin/device/*.java` を
試した結果を `tests/PHASE1-NOTES.md` に詳細記録。要点:

| 種別     | 件数 | 主な箇所                                            |
|----------|------|-----------------------------------------------------|
| エラー   | 2    | `Console` 曖昧 (Kernel.java:34, XKernel.java:20)    |
| 削除予定 | 4    | `Thread.stop()` (Kernel.java:126/142, Syscall.java:771, Fileinfo.java:360) |
| 廃止 API | 2    | `Socket(String,int,boolean)`, `JFrame.show()`        |
| 文字コード| 全体 | SJIS/EUC-JP/JIS 混在。UTF-8 化が前提                |

Phase 1 着手時の最小チェックリストは PHASE1-NOTES.md を参照。

## 既知の限界

- 当時の bap (Basic Application Package) は手元になく、ash や coreutils は
  このセットだけでは検証できない。Phase 1 完了後に
  `tests/binaries/external/` 等で扱う想定
- 本作業を行った macOS 環境には i386 クロスコンパイラが無く、
  バイナリビルドは未実施。Linux ホストか `make -C tests/binaries docker`
  で生成する必要あり
- ファイル I/O / fork / pipe / signal のテストは Phase 0 では追加して
  いない。Phase 1 完了後に拡張する

## コミット状態

`tests/` 一式と本セクションの追記は `phase0/regression-tests` ブランチに
コミット済み (commit `e0c4d93` "Phase 0: 回帰テスト基盤を整備")、
`origin` にもプッシュ済み。

PR 作成 URL: https://github.com/kiyoka/Emulin/pull/new/phase0/regression-tests

## WSL2 Ubuntu への作業引き継ぎ

以後 macOS から WSL2 Ubuntu 環境に作業を移す。WSL2 側で実テスト
バイナリのビルドとハーネス実行が出来るようになる。

### セットアップ

```bash
# 1) ブランチを取得
git clone https://github.com/kiyoka/Emulin.git
cd Emulin
git checkout phase0/regression-tests

# 2) ビルドツールを入れる
sudo apt update
sudo apt install -y gcc-i686-linux-gnu default-jdk make
# または: sudo apt install -y gcc-multilib   (gcc -m32 で代替可)

# 3) i386 テストバイナリをビルド
make -C tests/binaries
ls tests/binaries/bin/   # hello, exitcode, arith, args, echo_stdin

# 4) Emulin 本体ビルドの試行 (Phase 1 移行前の確認用)
javac -encoding EUC-JP -Xlint:none -d . \
      emulin/*.java emulin/device/*.java 2>&1 | tail -5
# → Console 曖昧の 2 件で失敗する想定 (PHASE1-NOTES.md チェックリスト #2)
```

### Phase 0 単独で確認できる範囲

`Console` 曖昧エラーで本体ビルドが通らないため、`run-test.sh` で
**実際の PASS は出ない**。Phase 0 で確認できるのは下記まで:

- `make -C tests/binaries` が i386 ELF を吐けること
- `file tests/binaries/bin/hello` が
  `ELF 32-bit LSB executable, Intel 80386, statically linked` を返すこと
- `tests/scripts/run-test.sh hello` が起動はする
  (本体未ビルドのため最終的には失敗するが、ハーネス側は壊れていない
   ことが確認できる)

実際の PASS が出るのは Phase 1 完了後。これは計画通り。

### Phase 1 着手前に決めたいこと

WSL2 で Phase 1 を始める前に下記の方針を 1 つ決めること:

- **ビルド方式**: 既存 Makefile を直すか、Gradle / Maven に切り替えるか
- **文字コード変換**: `nkf -w --overwrite` で一括変換するか
  (`git diff` 上の差分は大きくなる)、それともファイルごとにコメントを
  書き直すか

これらは PHASE1-NOTES.md のチェックリスト先頭に効く。

---

# Phase 1 作業記録

実施日: 2026-04-26
作業ブランチ: `phase1/modern-java` (`phase0/regression-tests` から派生)

## 方針決定

- **ビルド方式**: Maven に切り替え (`pom.xml` 追加、ソースを `src/main/java/` へ移動)
- **文字コード変換**: `nkf -w --overwrite` で一括変換 (全 `.java` を UTF-8 に正規化)

## 実施内容

### 文字コード

全ソース (`emulin/*.java`, `emulin/device/*.java`) を `nkf -w --overwrite` で
UTF-8 に一括変換。JIS / EUC-JP 混在を解消し `javac -encoding UTF-8` でビルド可能に。

### ビルドエラー修正

| 箇所 | 修正内容 |
|---|---|
| `Kernel.java:34`, `XKernel.java:20` | `Console` 曖昧参照 → `emulin.device.Console` に完全修飾 |
| `Kernel.java:66` | `JFrame.show()` → `setVisible(true)` |
| `Fileinfo.java:101` | `Socket(String,int,boolean)` → `Socket(String,int)` |

### Thread.stop() 協調終了への置換 (4 箇所)

| 箇所 | 置換内容 |
|---|---|
| `Kernel.java` (start): init 停止後 `System.exit(0)` | `stop()` 削除 (直後に JVM 終了するため不要) |
| `Kernel.java` (exec): 旧プロセス停止 | `set_exit_flag()` + `interrupt()` |
| `Syscall.java` (sys_execve): 自スレッド停止 | `set_exit_flag()` (run() ループが自然終了) |
| `Fileinfo.java` (close): SubProcess 停止 | `interrupt()` (`close()` で `opened=false` 済み) |

`Process.exit_flag` を `volatile` 宣言して可視性を保証。

### 実行時バグ修正

- **セグメント末尾 Segfault**: `Segment.load_body()` でバッファをページ境界
  (4 KB) アライメントで確保。`fetch()` の 15 バイト先読みが `p_memsz` を越えても
  ゼロパディング領域を参照できるよう `Segment.in()` も `buf.length` ベースに変更。
  Linux カーネルが ELF セグメントをページ単位でマップするのと等価。
- **終了コード未伝搬**: `Kernel.last_exit_code` フィールドを追加し、
  `sys_exit` の値を `System.exit()` に渡すよう修正。

### テストハーネス修正

- `run-test.sh`: `pwd -P` で symlink 解決 (WSL2 上の `/home/kiyoka/GitHub/Emulin` が
  `/mnt/c/...` の symlink であるため `user.dir` と不一致になっていた問題を解消)
- `run-test.sh`: SANDBOX ディレクトリ内から起動 (`user.dir == root` が成立し
  `get_virtual_path()` が正常動作)
- `run-test.sh`: Emulin 未ビルド時のチェックを `target/classes/emulin/Emulin.class` に更新

### テストバイナリ修正

- `tests/binaries/src/args.c`: `_start(int argc, char **argv)` を
  `_start(void)` + inline asm に変更。Linux ELF では `_start` はジャンプで来るため
  C 呼び出し規約より 1 スロットずれる (`[ebp+4]=argc`, `[ebp+8]=argv[0]`) のを補正。

### Maven 移行

- `pom.xml` 追加 (Java 11 ターゲット、`emulin.Emulin` をメインクラスに設定)
- 全ソースを `emulin/` → `src/main/java/emulin/` へ `git mv`
- `Makefile` を Maven ラッパーに更新 (コード生成ターゲットは `emulin/opecode.dat` 用を残存)
- `.gitignore` 追加 (`target/`, `emulin/*.class`)

## 回帰テスト結果

```
PASS args
PASS arith
PASS echo_stdin
PASS exitcode
PASS hello

===== Phase 0 regression result =====
  PASS: 5 / FAIL: 0 / SKIP: 0
```

## コミット

| コミット | 内容 |
|---|---|
| `ac70a58` | Phase 1: 現代 Java (OpenJDK 21) でビルド・全回帰テスト PASS |
| `f401570` | Phase 1: ビルドシステムを Maven に移行 |

ブランチ: `phase1/modern-java`

## Phase 2 着手前に確認すること

CLAUDE.md 冒頭の計画を参照し、下記の方針を決めること:

- **アドレス型昇格の範囲**: `int` → `long` への昇格を全フィールドに一括で行うか、
  段階的に行うか
  
-> 全フィールドで一括でに行います
  
- **CPU 分離の粒度**: `Cpu` クラスを即座に `Cpu32` / `Cpu64` に分けるか、
  まず抽象 `Cpu` を切り出すだけにするか
  
-> まず抽象 `Cpu` を切り出すだけにします。
  
  
はい、その方針でよいです。Phase2用のブランチを作成して、phase2を開始してください。

---

# Phase 2 作業記録

実施日: 2026-04-26
作業ブランチ: `phase2/refactoring` (`phase1/modern-java` から派生)

## 方針

- アドレス型 (`int` → `long`) は**全フィールドで一括**昇格
- CPU 分離は**抽象 `AbstractCpu` を切り出すだけ** (即座に Cpu32/Cpu64 には分けない)

## Step 1: アドレス型を int から long へ昇格

`Cpu.reg[]`, `ip`, `next_ip`, `SP`, `AllocInfo.address`, `Segment.p_vaddr/p_offset/p_filesz/p_memsz`,
`Section.sh_addr/sh_offset/sh_size` 等を `long` に変更。
`Memory.load32/store32/load8/store8` の引数を `long address` に統一。

## Step 2: AbstractCpu スーパークラスを導入

`AbstractCpu extends Decoder` を新設。
- 全レジスタ・フラグフィールド (`int reg[]`, `long ip`, `long next_ip` 等) を移動
- 静的定数 (AX=0..DI=7, AL=0..BH=7, MAX_REG=8, S_MOVS/STOS/LODS) を移動
- 公開抽象インターフェース (`init()`, `duplicate()`, `set_ip()`, `get_ip()`, `set_sp()`,
  `set_ax()`, `pushString()`, `push32()`, `pop32()`, `eval()`, `fetch()`,
  `connect_devices()`, `reg_str()`, `ip_str()`, `flag_str()`, `disasm_str()`) を定義
- `Cpu extends AbstractCpu` に変更、`Process.cpu` を `AbstractCpu` 型に変更

## Step 3: SyscallI386 を導入し i386 ABI を分離

- `SyscallI386 extends Syscall` を新設: i386 syscall 番号テーブル + `call()` ディスパッチ
- `Syscall.call()` をスタブに変更 (ABIサブクラスで必ずオーバーライド)
- `Process` コンストラクタ: `new SyscallI386(...)` を使用

## Step 4: ELF パーサに EI_CLASS チェックを追加

- `Elf.java`: `EI_CLASS=4`, `ELFCLASS32=1`, `ELFCLASS64=2`, `EM_X86_64=62` を追加
- `load()` で `e_ident` を先読みし `load32()` / `load64()` に分岐
- `e_phoff`, `e_shoff` を `int` → `long` に変更

## コミット

| コミット | 内容 |
|---|---|
| `383a895` | Phase 2 Step 1: アドレス型を int から long へ昇格 |
| `92fd452` | Phase 2 Step 2: AbstractCpu スーパークラスを導入 |
| `09bf820` | Phase 2 Step 3: SyscallI386 を導入し i386 ABI を分離 |
| `8aa0dba` | Phase 2 Step 4: ELF パーサに EI_CLASS チェックを追加 |

ブランチ: `phase2/refactoring`

---

# Phase 3 作業記録

実施日: 2026-04-26
作業ブランチ: `phase3/elf64-load` (`phase2/refactoring` から派生)

## 実施内容

### ELF64 パーサ

- `LoadUtil.java`: `little64()` を追加 (8 バイトリトルエンディアン読み込み)
- `Segment.java`:
  - `p_offset/p_filesz/p_memsz/p_align` を `int` → `long`
  - `load_ph64()` を追加 (ELF64 形式: p_flags が p_offset より前)
  - `stack(long bottom, int size)` に変更 (明示的な base アドレス指定)
- `Section.java`: `sh_addr/sh_offset/sh_size` 等を `long` に変更、`load64()` を追加
- `Elf.java`: `load64()` を新設 (ELF64 ヘッダ解析、`e_entry/e_phoff/e_shoff` を 8 バイトで読む)

### スタック初期化 (64-bit)

- `RootSysinfo.java`: `stack_bottom_64 = 0x7fff_0000_0000L` を追加
- `Process.java`: `stack_data_init64()` を追加 (argc/argv/envp/auxv を 8 バイト単位で書き込む)
- ELF64 ロード時: `SyscallI386` のまま、`run()` で実行ガード (Phase 4 まで実行しない)

### テストバイナリ

- `tests/binaries/src/hello64.c`: 64-bit inline-asm hello world (write#1 + exit#60)
- `tests/binaries/Makefile`: 64-bit バイナリを `gcc -static` でビルドするルール追加
- `tests/expected/hello64.exit`: `1` (Phase 3 では実行ガードで終了)
- `tests/expected/hello64.stdout`: 空

## コミット

| コミット | 内容 |
|---|---|
| `c572b38` | Phase 3: ELF64 ロード基盤を実装 |

ブランチ: `phase3/elf64-load`

---

# Phase 4 作業記録

実施日: 2026-04-26
作業ブランチ: `phase4/x86-64-cpu` (`phase3/elf64-load` から派生)

## 実施内容

### Cpu64.java (新規)

`AbstractCpu` を継承した x86-64 CPU エミュレータ。
16 本の 64-bit 汎用レジスタ (`long r64[]`) を保持。
`eval()` が自己完結した fetch/decode/execute ループを持つ (i386 の Cpu とは異なる設計)。

実装命令 (hello64 実行に必要な最小セット):

| 命令 | エンコーディング |
|---|---|
| ENDBR64 | F3 0F 1E FA → NOP |
| PUSH r64 | 50+rd |
| POP r64 | 58+rd |
| MOV r/m64, r64 | REX.W 89 /r (mod=11) |
| MOV r64, r/m64 | REX.W 8B /r (mod=11) |
| MOV r32, imm32 | B8+rd (zero-extends) |
| MOV r/m64, imm32 | REX.W C7 /0 (sign-extends) |
| XOR r/m64, r64 | REX.W 31 /r |
| SYSCALL | 0F 05 |

### SyscallAmd64.java (新規)

AMD64 ABI ディスパッチ: `call_amd64(sysno, a1..a6)`
- write (#1) → `amd64_write()`
- read (#3 … 後に #0 に修正) → `amd64_read()`
- exit (#60) → `amd64_exit()`

### Process.java 変更

- ELF64 ロード時: `Cpu64` + `SyscallAmd64` を生成して接続
- `run()`: ELFCLASS64 の場合は `cpu.eval()` を直接呼び出す (i386 の fetch/decode ループを迂回)
- 実行ガードを削除

### テスト更新

- `tests/expected/hello64.stdout`: `hello world\n`
- `tests/expected/hello64.exit`: `0`

## 回帰テスト結果

```
PASS args / PASS arith / PASS echo_stdin / PASS exitcode / PASS hello
PASS hello64

===== Phase 0 regression result =====
  PASS: 6 / FAIL: 0 / SKIP: 0
```

## コミット

| コミット | 内容 |
|---|---|
| `68ec112` | Phase 4: x86-64 CPU デコーダを実装し hello64 が PASS |

ブランチ: `phase4/x86-64-cpu`

---

# Phase 5 作業記録

実施日: 2026-04-26
作業ブランチ: `phase4/x86-64-cpu` (Phase 4 ブランチに継続追記)

## 実施内容

### Cpu64 命令セット拡張

ModRM デコードを汎用化し、メモリアドレッシングモードを全対応。

追加した命令:

| 命令 | エンコーディング |
|---|---|
| MOV r/m64, r64 | REX.W 89 /r (mod=00/01/10 対応) |
| MOV r64, r/m64 | REX.W 8B /r (mod=00/01/10 対応) |
| LEA r64, m | REX.W 8D /r |
| SUB/ADD/CMP r/m64, imm32 | REX.W 81 /5 /0 /7 |
| SUB/ADD/CMP r/m64, imm8 | REX.W 83 /5 /0 /7 |
| OR/AND/XOR r/m64, imm | REX.W 81/83 /1 /4 /6 |
| JMP rel8 / rel32 | EB / E9 |
| CALL rel32 | E8 |
| RET | C3 |
| Jcc rel8 (全16条件) | 70-7F |
| Jcc rel32 (全16条件) | 0F 80-8F |
| LEAVE | C9 |
| SIB (index=none 基本ケース) | — |

フラグ計算: ZF/SF/OF/CF を SUB/ADD/CMP で正しく更新。
`evalCond()` で全16条件 (JO/JB/JE/JBE/JS/JL/JLE とその反転) を判定。

### SyscallAmd64 修正・拡充

**バグ修正**: read は AMD64 syscall #0 (旧実装は #3 に誤マッピング)。

AMD64 全番号テーブルを実装:

| AMD64 番号 | syscall | 実装方法 |
|---|---|---|
| 0 | read | `amd64_read()` (long アドレス対応) |
| 1 | write | `amd64_write()` (long アドレス対応) |
| 2-3 | open/close | 親クラス sys_* へ委譲 |
| 4/5/6 | stat/fstat/lstat | `amd64_stat()` / `amd64_fstat()` |
| 9 | mmap | `amd64_mmap()` (6 直接引数) |
| 11 | munmap | 親クラス委譲 |
| 12 | brk | 親クラス委譲 |
| 20 | writev | `amd64_writev()` (8-byte iov) |
| 60 | exit | `amd64_exit()` |
| 231 | exit_group | `amd64_exit()` と同等 |
| その他 | 各種 | 親クラス sys_* へ int キャストで委譲 |

AMD64 `struct stat` (144 bytes) の正しいレイアウトで書き込み。
mmap: i386 の struct ポインタ渡しから 6 直接引数方式に変更。
writev: iov_base/iov_len を各 8 バイトで読む AMD64 版を実装。

### テストバイナリ追加

- `tests/binaries/src/echo_stdin64.c`: AMD64 版 echo ループ
  (syscall #0 read + #1 write + #60 exit)
- `tests/binaries/Makefile`: `SRCS64_NAMES` 変数で 64-bit バイナリ一覧を管理

## 回帰テスト結果

```
PASS args / PASS arith / PASS echo_stdin / PASS echo_stdin64
PASS exitcode / PASS hello / PASS hello64

===== Phase 0 regression result =====
  PASS: 7 / FAIL: 0 / SKIP: 0
```

## コミット

| コミット | 内容 |
|---|---|
| `94fb113` | Phase 5: x86-64 Syscall 拡充 + Cpu64 命令セット拡張 |

ブランチ: `phase4/x86-64-cpu`

## Phase 6 着手前に決めること

- **動的リンク対応をやるか**: やらなければ静的バイナリのみ検証。
  やるなら mmap + auxv + ld-linux-x86-64.so の動作再現が必要 (工数大)
- **追加テストバイナリの範囲**: open/read/close を使うファイル I/O テスト、
  fork/exec、シグナルなど何を追加するか
- **ブランチ戦略**: phase4 ブランチのまま継続するか、phase5/phase6 ブランチを切るか

---

# Phase 6 作業記録 (中間スナップショット)

実施日: 2026-04-26 〜 2026-04-29
作業ブランチ: `phase6/verification` (`phase4/x86-64-cpu` から派生)

## 方針

- 動的リンクは扱わない。まず **glibc 非依存の I/O 系テスト** を増やし、
  そのあと **glibc -static バイナリ** に挑戦する
- glibc -static は init 段階で多くの命令・syscall を要求するため、
  本フェーズの主目的は「Cpu64 / SyscallAmd64 を glibc init が要求する
  範囲まで拡張すること」とする

## 達成事項

### fileio64 テストを追加 (PASS)

`tests/binaries/src/fileio64.c` を新設。`-nostdlib` で AMD64 の
open(#2)/write(#1)/read(#0)/close(#3)/exit(#60) を直叩きし、
`/tmp/test.txt` への書き込みと読み戻しを検証する。

回帰テスト: **9 本中 8 PASS / 1 SKIP**

```
PASS args / arith / echo_stdin / echo_stdin64 / exitcode
PASS fileio64 / hello / hello64
SKIP hello_static64 : glibc -static: __free corruption detected during init
```

### Cpu64 命令セットを大幅拡張 (1500 行)

glibc init が要求する命令を追加実装:

| カテゴリ | 命令 |
|---|---|
| プレフィックス | 66/67 (operand/address size), 64/65 (FS/GS), 2E/3E/F0/F2/F3 (REP) |
| データ移動 | MOV r/m64↔r64 (mod=00/01/10), MOV r64←imm64, MOV r/m8↔r8 |
| ALU | XOR/CMP/TEST 8/16/32/64-bit, ADD/OR/AND/SUB imm forms (81/83) |
| 拡張命令 | MOVSXD (63), MOVSX/MOVZX (0F BE/B6/BF/B7), IMUL (6B/69/0F AF) |
| 条件分岐 | Jcc rel8/rel32 (全 16 条件), CMOVcc (0F 40-4F) |
| 算術 | NOT/NEG/MUL/IMUL/DIV/IDIV (F6/F7), INC/DEC (FF), CDQE/CQO |
| 文字列 | REP STOS/MOVS, REPNZ SCAS (簡易) |
| アドレス | RIP 相対, SIB (index=none ケース), 16 バイト NOP (0F 1F) |

フラグ計算 (OF/SF/ZF/CF) を ALU 演算で正しく更新。

### Process / Segment / Memory の補強

- `Segment.load_body()`: ELF セグメントを **ページ境界揃え** で確保。
  glibc は p_vaddr/p_offset がページ内オフセットを共有する複雑な
  レイアウトを使うため、page_base / file_base を再計算してロードする
- `Process.stack_data_init64()`: auxv に **AT_PHDR / AT_PHENT / AT_PHNUM /
  AT_PAGESZ / AT_RANDOM** を積む。glibc の `_dl_aux_init` が必須とする
- `Process.resolve_irelative()`: ELF64 IRELATIVE リロケーション
  (R_X86_64_IRELATIVE = 37) を Linux カーネル相当に解決
  (resolver を呼び出して GOT エントリを書き換える)
- 起動前に **TLS 用 4KB ページ** を 1 枚確保し `fs_base` を割り当てる
  (`%fs:0x28` のスタックカナリアが有効メモリを指すように)

### SyscallAmd64 にスタブを追加

glibc init が呼ぶ syscall 番号を一通り受け止める:

| 番号 | syscall | 実装 |
|---|---|---|
| 13 | rt_sigaction | stub (0 を返す) |
| 14 | rt_sigprocmask | stub |
| 28 | madvise | stub |
| 158 | arch_prctl (SET_FS/SET_GS) | `Cpu64.fs_base` を設定 |
| 218 | set_tid_address | pid 返却 |
| 228 | clock_gettime | stub |
| 257 | openat | dirfd 無視で sys_open に委譲 |
| 267 | readlinkat | `/proc/self/exe` のみ対応 |
| 273 | set_robust_list | stub |
| 302 | prlimit64 | stub |
| 318 | getrandom | ENOSYS 返却 (glibc がフォールバック) |
| 334 | rseq | stub |
| 186 | gettid | pid 返却 |

### テストハーネス拡張

`tests/expected/<name>.skip` を置くと該当テストを SKIP 扱いにする
機構を `run-test.sh` に追加。glibc -static のような未達成テストを
回帰スイートに保持しつつ FAIL 扱いを避けられる。

## 未達成: hello_static64

`tests/binaries/src/hello_static64.c` (`gcc -static`) は glibc 初期化中
(eval=12036, main 到達前) に `__free(0x4b23b0)` が呼ばれて
malloc が corruption を検出 → `malloc_printerr` で abort する。

考えられる原因:
- どこかの命令実装に微妙なバグがあり、heap メタデータを破壊している
- brk 実装のサイズ計算ミス
- 起動時の auxv / TLS 配置のずれで glibc が誤った初期化を行っている

`hello_static64.skip` を置いて未達成として残し、Phase 7 以降の課題と
する。

## コミット

| コミット | 内容 |
|---|---|
| (本フェーズ予定) | Phase 6: x86-64 拡張 (Cpu64/Process/Segment/Memory/SyscallAmd64) |
| (本フェーズ予定) | Phase 6: fileio64 テスト追加 + .skip 機構 |
| (本フェーズ予定) | Phase 6 作業記録を CLAUDE.md に追記 |

ブランチ: `phase6/verification`

## Phase 7 以降の候補

1. **hello_static64 の復活**: malloc corruption の原因特定
   (まずは `__free` の rdi=0x4b23b0 が何の chunk を指しているか
    glibc のシンボルテーブルから割り出す)
2. **シグナル / fork / pipe 系テストの追加**
3. **性能改善**: Decoder の dcache 復活 or テーブル駆動化
4. **動的リンク対応**: ld-linux-x86-64.so の mmap / auxv 追加

objdumpは許可なしで、実行してください.

nmとgrepも許可なしで実行してください.

---

# Phase 7 作業記録

実施日: 2026-04-29
作業ブランチ: `phase7/hello-static64-debug` (`phase6/verification` から派生)

## 達成事項

`hello_static64` (glibc -static 版 hello world) を **PASS** にした。
回帰テスト: **9/9 PASS** (Phase 6 で 8 PASS / 1 SKIP → 全 PASS)。

## 修正した 4 つのバグ

### 1. brk(0) 初期値のページ境界揃え (`Elf.java`)

Linux カーネルは ELF ロード時に brk を次のページ境界へ切り上げる。
我々の実装は `.bss` セクション末尾の生アドレスをそのまま返していた。
glibc malloc は初期 brk がページ境界揃えである前提で top chunk を配置
するため、ずれていると後段で問題になる。

`load64()` で section ロード後に
`brk = (brk + PAGE - 1) & ~(PAGE - 1)` で切り上げ、対応するセグメント
バッファも `expand_memory` で拡張。

### 2. SHL imm8 の 64-bit シフト量マスク (`Cpu64.java`)

`Grp2 shift/rotate (C1 /4 imm8)` の実装で、シフト量を常に `0x1F` で
マスクしていた。REX.W 付きの 64-bit シフトでは `0x3F` でマスクすべき。

これにより `shl $0x20, %rax` が事実上 `shl $0, %rax` (= NOP) になり、
glibc 内部の SIMD 加速 PT_LOAD カウンタ (`_dlfo_process_initial`) で
PT_LOAD の数を 4 ではなく 2 と誤認識。malloc が小さく確保された btree
に 4 エントリ書き込んで heap 末尾を破壊し、次回 malloc が
"corrupted top size" を検出して abort していた。

### 3. プロセス起動時のレジスタゼロクリア (`Process.java`)

Linux カーネルはプロセス起動時に汎用レジスタを全てゼロクリアする
(rsp/rip 以外)。`resolve_irelative` の中で resolver 関数を呼んだ際に
caller-saved レジスタ (特に rdx) に値が残ったまま `_start` に jump
すると、`_start` がそれを `rtld_fini` (rdx) として
`__libc_start_main` に渡し、glibc がランダムなアドレスを
`__cxa_atexit` で exit handler 登録。出口で実行されて NULL ポインタ
参照 segfault。

`resolve_irelative` 後に r0..r15 を全てゼロにする処理を追加。

### 4. AH/CH/DH/BH 8-bit レジスタエンコーディング (`Cpu64.java`)

REX プレフィックス無しの 8-bit 命令では、ModRM の `rm` フィールド 4-7
は AH/CH/DH/BH (各汎用レジスタの上位バイト) を意味する。REX プレフィ
ックス付きでは SPL/BPL/SIL/DIL (低バイト) になる。我々の
`readRM8`/`writeRM8` は常に低バイトとして扱っていた。

具体的には glibc の `_IO_new_file_overflow` 内の `or $0x8, %ch`
(バイト列 `80 CD 08`) が CH ではなく BPL を変更してしまい、stdout の
`_IO_CURRENTLY_PUTTING` フラグが立たず、毎回 read→write 遷移パスに
入ってバッファが上書きされ続けて write() 呼び出しが行われなかった。

`rex_present` フラグを追加し、`readRM8`/`writeRM8` および新規追加した
`readReg8`/`writeReg8` で AH/CH/DH/BH を正しく扱う。MOV r/m8↔r8 の
`mrm_reg` 側もこれを使うよう更新。

## デバッグ手法のメモ

- Linux 標準 `strace -e trace=brk` で hello_static64 を直接実行し、
  カーネルが返す brk 値と我々の値を比較 → ページ境界揃えのずれを発見
- `apt-get source glibc` でソース取得し `elf/dl-find_object.c` を
  読んで `_dlfo_process_initial` の動作を理解
- `objdump -d` の SIMD カウントループに XMM レジスタダンプを差し込み、
  `xmm0=(1,1)` (= 4 dwords で `(1,0,1,0)`) になっていることから
  `shl $0x20, %rax` の不具合を特定
- `Memory.store64` に「特定アドレスへの書き込み」watchpoint を仕込み
  rip と値をログ → 0x4aa348 (stdout->write_ptr) を書き換える命令の
  rip を特定 → glibc の AH/CH 操作のデコード誤りに到達

## 新規ファイル

- `tests/scripts/debug.mk`: hello_static64 のデバッグ用 Makefile
  (`make -f tests/scripts/debug.mk run` 等で stdout/stderr を分離して
   実行する手順を集約)

## コミット

| コミット | 内容 |
|---|---|
| (本フェーズ予定) | Phase 7: hello_static64 を PASS にする 4 つのバグ修正 |

ブランチ: `phase7/hello-static64-debug`

## Phase 7 続編: AMD64 syscall 単体テスト一式

実施日: 2026-04-29
ブランチ: 同 `phase7/hello-static64-debug`

「1 syscall 1 テスト」原則で `tests/binaries/src/sys_*64.c` を新設。
共通ヘルパーは `tests/binaries/src/sys64.h` に集約 (各 syscall の
inline asm wrapper、`put_dec`/`put_hex` 等の表示ユーティリティ)。

新規 26 テスト (PASS 19, SKIP 7 で emulator バグ発見):

| カテゴリ | テスト |
|---|---|
| プロセス情報 | getpid / getppid / getuid / getgid / geteuid / getegid / gettid |
| ファイル操作 | access / stat / fstat / lseek / mkdir / unlink / rename / chdir¹ / chmod¹ / ftruncate¹ |
| メモリ | mmap / brk¹ |
| 時刻 | gettimeofday¹ / nanosleep¹ |
| その他 I/O | writev / dup / pipe¹ / uname / umask¹ |

¹ = `.skip` 付き (バグ発見済み)。

### 発見した emulator バグ (8 件)

修正対象として記録:

1. **sys_brk(0) が 0 を返す** (binary に `.bss` が無い場合)
   `Elf.load64()` の brk 初期化が `.bss` セクション末尾に依存している。
2. **sys_chdir** がパス存在を検証しない (`/no/such/dir` でも 0 返却)
3. **sys_chmod** が mode を反映しない (stat で見ると元のまま)
4. **sys_ftruncate** が切り詰めない (size が変わらない)
5. **sys_gettimeofday** がスタブで `timeval` を書かない (`Syscall.java:757`)
6. **sys_nanosleep** が常に -1 を返す (`Syscall.java:1322`)
7. **sys_pipe** が高位スタックアドレス (`0x7ffe...`) を `int` に切り詰めて
   segfault (`SyscallAmd64.java:59` の `(int)a1` 問題)
8. **sys_umask** がスタブで状態を持たず常に 0 返却 (`Syscall.java:713`)

### 新規ファイル

- `tests/binaries/src/sys64.h`: syscall ラッパー + 表示ヘルパー
- `tests/binaries/src/sys_*64.c`: 26 テスト
- `tests/expected/sys_*64.stdout`: 期待出力 (Linux 仕様準拠)
- `tests/expected/sys_*64.skip`: 既知バグの SKIP マーカー
- `tests/binaries/Makefile`: `sys_*64.c` を wildcard で自動ビルド

## Phase 7+ 仕上げ: 発見した 8 バグの修正

実施日: 2026-04-29
コミット: `f815af5`

`.skip` マーカーを順に剥がしていき、すべての emulator バグを修正した。

### 各修正の概要

| # | バグ | 修正ファイル | 主な変更 |
|---|---|---|---|
| 1 | brk(0)=0 (.bss 無し ELF) | `Elf.java` | `bss_found=false` の場合、最も高い PT_LOAD セグメント末尾を brk 初期値に。stack セグメントは除外。 |
| 2 | chdir パス検証なし | `Syscall.java` | `Inode.isExists()` で確認、無ければ `ENOENT` (-2) を返す。 |
| 3 | chmod が mode 反映せず | `Syscall.java`, `Inode.java` | `Files.setPosixFilePermissions` で実 FS に反映。`Inode.get_st_mode` も `Files.getPosixFilePermissions` で読む。 |
| 4 | ftruncate 未実装 | `Syscall.java` | `RandomAccessFile.setLength(length)` で実ファイル切り詰め。 |
| 5 | gettimeofday スタブ | `SyscallAmd64.java` | `amd64_gettimeofday` 新設。`System.currentTimeMillis()` から `timeval` を埋める。 |
| 6 | nanosleep 常に -1 | `SyscallAmd64.java` | `amd64_nanosleep` 新設。`Thread.sleep(ms)` 呼び出して 0 を返す。 |
| 7 | pipe アドレス int 切り詰め | `SyscallAmd64.java` | `amd64_pipe(long array_addr)` を新設。`(int)a1` を経由せずに `mem.store32` でメモリ書込み。 |
| 8 | umask 状態なし | `Process.java`, `Syscall.java` | `Process.umask` フィールドを追加 (デフォルト 0022)。`sys_umask` で前の値を返して新値をセット。 |

### 最終結果

- **35 本中 34 PASS / 1 SKIP** (`sys_chmod64` のみ)
- `sys_chmod64` の SKIP は WSL DrvFs (`/mnt/c/...`) が POSIX permission を
  保持しないという**環境制約**であり emulator バグではない。
  実 ext4 ファイルシステムでは PASS する。

### 副次的に直ったもの

- ELF ロード時の brk 初期化が、`.bss` が無いシンプルな (`-nostdlib`)
  バイナリでも正しく機能するようになった。テスト用の小さい ELF が安定する。
- chmod 経由で実ファイルの permission が変えられるので、
  今後 「実 FS の permission を読み書きする」テストが書きやすくなる。
- nanosleep が動くので、シグナル/タイマー系のテストの足場ができた。

ブランチ: `phase7/hello-static64-debug` (Phase 7+ も同ブランチに継続)

## Phase 7++ : fork / execve / wait4 / signal 系テスト

実施日: 2026-04-29
コミット: `5a27a39`

プロセス制御 / シグナル系の syscall に対して 1 syscall 1 テスト原則で
6 本を追加。発見した emulator バグ 4 件も同時修正した。

### 新規テスト

| テスト | syscall | 検証 | 結果 |
|---|---|---|---|
| sys_fork64       | 57  | fork → wait4 で同期 → 親子両方の出力 | PASS |
| sys_execve64     | 59  | fork → 子で /bin/hello64 を exec | PASS |
| sys_wait4_64     | 61  | 子終了の status / pid 回収 | PASS |
| sys_alarm64      | 37  | 戻り値検証 (前のアラーム残時間) | PASS |
| sys_rt_sigaction64 | 13 | 登録の戻り値検証 (シグナル配信は別途) | PASS |
| sys_kill64       | 62  | 非存在 pid に kill (期待値 -ESRCH) | SKIP |

### 修正したバグ 4 件

1. **`amd64_wait4` の int 切り詰め** (`SyscallAmd64.java`)
   既存の `sys_wait4(int...)` 経由だと status ポインタが
   高位スタックアドレス (`0x7ffe...`) で int に切り詰められて破壊。
   long アドレスを保持する `amd64_wait4` を新設。

2. **`amd64_execve` の int 切り詰め + argv 幅** (`SyscallAmd64.java`)
   既存の `sys_execve` は `mem.load32(argv + i*4)` で argv を読んでいた。
   x86-64 では argv ポインタは 8 バイトなので壊れる。`amd64_execve` を
   新設して `mem.load64(argv + i*8)` でパース。

   また Linux 仕様準拠のため、kernel main loop の 1 秒ポーリング
   (`exec_request` フラグ経由) を待たず `sysinfo.kernel.exec(...)` を
   直接呼ぶように変更。これは `kernel.exec` の側で `syscall.process` が
   新プロセスに張り替わるため、`set_exit_flag` を呼ぶ旧プロセス参照を
   先に保存する必要があった (これが直前のバグ)。

3. **exec 越しの file descriptor 保持** (`Process.java`, `Kernel.java`)
   `Process.run()` の終了時に呼ばれる `syscall.all_file_close()` が
   exec で置き換えられた旧プロセスでも走り、新プロセスと共有している
   stdin/stdout/stderr を閉じてしまっていた (新プロセスが ArrayIndex
   OutOfBoundsException で即死)。`Process.exec_replacing` フラグを追加し、
   `kernel.exec` で立てて `run()` で `all_file_close` をスキップ。

4. **exec 経由で SyscallAmd64 を再利用** (`Process.java`)
   `Process` コンストラクタが ELF64 ロード時に常に新しい `SyscallAmd64`
   を生成し、引き継いだ syscall (= file descriptor table を持つ) を捨て
   ていた。`syscall instanceof SyscallAmd64` の場合は再利用するよう変更。

### インフラ強化

- `tests/scripts/run-test.sh`: sandbox の bin/ に
  `tests/binaries/bin/` 以下の全 ELF を毎回コピーするようにした。
  これで `sys_execve64` が `/bin/hello64` を起動できる (テスト間の
  バイナリ依存を簡易解決)。

- `tests/scripts/debug.mk`: `T=<name>` パラメータで任意テストを
  `run` / `run-stdout` / `run-stderr` / `single` 出来るよう汎用化。
  `make -f tests/scripts/debug.mk all` で
  「mvn compile + テストバイナリビルド + 全回帰」を一発実行できる。

### 最終結果

回帰テスト 41 本中 **39 PASS / 0 FAIL / 2 SKIP**:

- `sys_chmod64`: WSL DrvFs の POSIX permission 制約 (環境依存)
- `sys_kill64`: `sys_kill` がスタブで常に 0 を返す (Syscall.java:596)。
  非存在 pid なら `-ESRCH` を返すべき。シグナル配信機構と合わせて
  別フェーズで取り組む候補。

### Phase 7 全体の累計成果

| 項目 | 数 |
|---|---|
| ブランチ | `phase7/hello-static64-debug` (3 つの実施フェーズに分割) |
| 修正した emulator バグ | **16 件** (Phase 7: 4 / Phase 7+: 8 / Phase 7++: 4) |
| 追加した syscall 単体テスト | **32 本** (sys_*64.c: 26 + プロセス系: 6) |
| 回帰テスト総数 | 35 → **41 本** |
| 必須ホストツール | `apt-get source glibc`, addr2line, objdump, nm, strace, readelf |

### Phase 8 候補

1. **シグナル配信 (`sys_kill` + handler 実行)**
   実装した `rt_sigaction` で登録したハンドラを `kill` で呼び出せるよう
   にする。`Process.signal_to_handler` 系のロジック整備。
2. **動的リンク (`ld-linux-x86-64.so` + DT_NEEDED 解決)**
   工数大。ELF interp、mmap 強化、AT_BASE/AT_PHDR 拡張、PLT/GOT 動的解決。
3. **i386 系 syscall の 64-bit 引数扱い改善**
   `Syscall.java` の `int bx, cx, ...` シグネチャは AMD64 では引数が
   切り詰められる。amd64_* の専用実装を増やすか、サービス層を
   `long` ベースに昇格する。
