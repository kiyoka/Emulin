
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

## まだコミットしていない

`tests/` 一式と本セクションの追記は未コミット。コミットの可否・
メッセージはユーザ判断とする。
