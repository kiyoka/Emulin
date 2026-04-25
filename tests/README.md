# Emulin 回帰テスト (Phase 0)

DESIGN.md / `.claude/CLAUDE.md` の Phase 0 で計画した、Emulin の回帰
テスト基盤。後続フェーズ (現代 Java 移行 / 64bit 対応) の変更で
既存の 32bit ELF 実行が壊れていないかを判定するための仕組み。

このフェーズでは emulator 本体には一切手を入れない。

## ディレクトリ構成

```
tests/
├── README.md                ... 本書
├── PHASE1-NOTES.md          ... 現代 javac でのビルド試行ログ
├── binaries/
│   ├── Makefile             ... テストバイナリビルド
│   ├── src/                 ... テスト用 C ソース
│   │   ├── hello.c
│   │   ├── exitcode.c
│   │   ├── arith.c
│   │   ├── args.c
│   │   └── echo_stdin.c
│   └── bin/                 ... ビルド成果物 (gitignore)
├── expected/                ... 各テストの期待値
│   ├── <name>.stdout        ... 期待される標準出力 (必須)
│   ├── <name>.exit          ... 期待される終了コード (省略時 0)
│   ├── <name>.argv          ... 実行引数 1 行 (省略時 /bin/<name>)
│   └── <name>.stdin         ... 標準入力に流すデータ (省略時 /dev/null)
├── sandbox/                 ... 実行時の仮想 root (gitignore)
│   ├── bin/                 ... テストバイナリが配置される
│   └── etc/                 ... emulin.cnf 等
└── scripts/
    ├── run-test.sh          ... 単一テスト実行
    └── run-all.sh           ... 全テスト実行
```

## テストプログラムの方針

各 C ソースは **glibc に依存しない**。`int 0x80` を直接叩いて
`sys_write` / `sys_read` / `sys_exit` を呼ぶ自前の薄いラッパしか
使わない。理由:

1. Emulin の現状は glibc の動的リンクや auxv を完全には
   サポートしていない。`-static` でも libc が大量のシステムコールを
   呼び、現在未実装/不完全な経路を踏んで原因の切り分けが難しい
2. テストの目的は「Emulin が壊れていないか」であって glibc 全体の
   動作確認ではない

意図的に小さく、各テストは 1〜2 個のシステムコール経路だけを叩く。

## テストバイナリのビルド

i386 static ELF を作るには i386 用クロスコンパイラが必要。

### Linux ホスト (Debian / Ubuntu)

```bash
sudo apt install gcc-i686-linux-gnu
make -C tests/binaries
```

または `gcc -m32` でも可:

```bash
sudo apt install gcc-multilib
make -C tests/binaries CROSS='gcc -m32'
```

### macOS / その他

ネイティブには i386 toolchain が無いので Docker フォールバックを
用意している:

```bash
make -C tests/binaries docker
```

`i386/debian:bookworm-slim` イメージに gcc を入れてビルドする。
docker (または互換 runtime) が必要。

### 任意のクロスコンパイラを指定

```bash
make -C tests/binaries CROSS=/path/to/i686-elf-gcc
```

## テストの実行

```bash
# 単一テスト
tests/scripts/run-test.sh hello

# 全テスト
tests/scripts/run-all.sh
```

スクリプトは下記前提で動く:

- `emulin/*.class` がプロジェクトルート直下から見える状態
  (`cd <root> && javac emulin/*.java emulin/device/*.java`)
- `java` が PATH 上にある

未ビルドのバイナリは SKIP 扱い (exit 2)、stdout/exit が一致したら
PASS、不一致なら FAIL を返す。

## テストを増やす

1. `tests/binaries/src/<name>.c` を追加 (上記方針に従う)
2. `tests/expected/<name>.stdout` (必須) と必要なら
   `<name>.exit` / `<name>.argv` / `<name>.stdin` を置く
3. `make -C tests/binaries`
4. `tests/scripts/run-test.sh <name>`

`run-all.sh` は `binaries/src/*.c` を自動で拾うので個別登録は不要。

## このフェーズの限界

- **Emulin 本体の挙動変更は行わない。** Phase 1 以降が対象
- **glibc / 動的リンクのテストは含まない。** Phase 6 で別途検討
- **ファイル I/O / fork / pipe / signal のテストは未追加。**
  Phase 0 では最小スモークのみ。Phase 1 完了後に拡張する想定

## 既知の課題

- 当時の `bap` (Basic Application Package) は手元にないため、
  ash や coreutils の動作確認はこのテストセットだけでは出来ない。
  別途 32bit static ELF を入手するか、Phase 1 完了後に
  `tests/binaries/external/` を追加して扱う方針

- 現代の Linux カーネルは 32bit static ELF をサポートしているが、
  ホストの `/proc/sys/abi/x86/disable32bit` 等で無効化されていない
  ことが前提
