# Emulin

**Java で動く 32/64-bit Linux ELF エミュレータ**

Version 0.3.0

Kiyoka Nishiyama

GNU General Public License v2 (詳細は `COPYING` を参照)

---

## 概要

Emulin は、Linux x86 (32-bit) / x86-64 (64-bit) ELF バイナリを Java で実行する
エミュレータです。pure Java only で動作するため、Windows / macOS / Linux の
どこでも同じように Linux バイナリを動かせます。

実機 Linux binary (git / curl / openssl / python / GNU coreutils 等) の
動作を主目的としています。busybox を同梱して、即座に Linux シェル環境
(busybox ash) を立ち上げることもできます。

## 特徴

- 全て Java で記述 (pure Java only、JNI 撤去済)
- 32-bit ELF (i386) と 64-bit ELF (x86-64) の両方を実行可能
- 動的リンクの実機 binary を実行可能 (PIE / ld.so / libc / pthread 対応)
- AES-NI / PCLMULQDQ 命令を完全実装 (FIPS-197 host 一致)
- pthread 完全対応 (mutex / signal / TLS 含む)
- TLS 1.3 (gnutls 経由、cert verify 含む) 完全動作
- JLine 3 採用で Linux/macOS/Windows 共通の raw mode / Ctrl-C / SIGWINCH 対応
- 回帰テスト 211 PASS / 0 FAIL 維持

## 動作する実機 binary (例)

- coreutils 24 種類 (cat / ls / cp / mv / sort 等)
- bash, busybox 88 applet
- Python 3.12 (一部 syscall 制約あり)
- OpenSSL 3.0.13 (TLS / 暗号化)
- wget HTTP / HTTPS (cert verify 含む)
- curl HTTP / `curl --version` (TLS handshake)
- git: init / add / commit / log / status / diff / clone (git:// と https:// 両対応)

## 必要環境

| 項目 | 内容 |
|------|------|
| JDK / JRE | 11 以降 (OpenJDK 21 で開発・テスト) |
| Maven | ビルド時のみ (3.6+) |
| OS | Linux (主) / Windows / macOS |

## クイックスタート

### 配布 zip を使う場合

[Releases](https://github.com/kiyoka/emulin/releases) から `emulin-dist-*.zip` を
ダウンロードして解凍。

```bash
./emulin.sh             # busybox ash 対話シェル
./emulin.sh ls /        # 1 コマンド実行
./emulin.sh sh -c 'echo $((6*7))'
```

詳細は `dist/README.txt` を参照。

### ソースからビルド

```bash
mvn package -DskipTests
java -XX:-DontCompileHugeMethods \
  -jar target/emulin-*-all.jar /path/to/sandbox /bin/busybox echo hello
```

### 実機 Linux binary を動かす

`dist/build-sandbox.sh` で sandbox を構築 (Debian / Ubuntu 系を想定):

```bash
# level=base: 実機 binary 動作の前提条件 (locale / SSL cert 等) を配置
./dist/build-sandbox.sh /tmp/my-sandbox base

# level=full: + git / curl / openssl / python と必要 .so 一式
./dist/build-sandbox.sh /tmp/my-sandbox full

# 実行例: git clone HTTPS (約 10 秒で完走)
cd /tmp/my-sandbox
java -XX:-DontCompileHugeMethods \
  -jar target/emulin-*-all.jar . \
  /usr/bin/git clone --depth=1 \
    https://github.com/octocat/Hello-World.git /tmp/cloned
```

## ビルド方法

```bash
git clone https://github.com/kiyoka/emulin.git
cd emulin
mvn package -DskipTests
```

成果物:
- `target/emulin-<version>-all.jar` (fat jar、JLine 同梱)

## テスト

```bash
make -C tests/binaries        # x86 / x86-64 テストバイナリをビルド
tests/scripts/run-fast.sh     # 軽量 subset (~27s、real-* / dist 抜き、146 ケース)
tests/scripts/run-all.sh      # 全テスト (~1m41s、211 ケース)
tests/scripts/run-network.sh  # ネットワーク関連だけ (~3m)
```

## パフォーマンス

実機 binary を動かす時は **`-XX:-DontCompileHugeMethods`** を必ず付けます:

```bash
java -XX:-DontCompileHugeMethods -jar emulin-*-all.jar ...
```

このフラグなしだと、emulator の中核 dispatch loop (`Cpu64::decode_and_exec`、
20K+ bytecode) が JVM の `HugeMethodLimit` (default 8000 byte) で JIT C2
compile を拒否され、interpreter モードで実行されます。
git clone HTTPS で 28% 高速化します (14.4s → 10.4s)。

`emulin.sh` / `emulin.bat` ランチャは自動的にこのフラグを付けます。

## 既知の制約

- IPv6 (AF_INET6) 未対応 — getaddrinfo は IPv4 のみ
- Python 3 の一部 syscall (signalfd4 等) 未対応
- emulator の実行速度は host より大幅に遅い (300x 程度)
- WSL DrvFs (`/mnt/c/...`) は I/O 遅い → sandbox は Linux /tmp 等に置く
- `git clone --hardlinks file://` は inode 検証で失敗 (`--no-hardlinks` で動作)

## ディレクトリ構成

```
src/main/java/emulin/        Emulin 本体
  Cpu.java (i386), Cpu64.java (x86-64), AbstractCpu.java
  Syscall.java, SyscallI386.java, SyscallAmd64.java
  Elf.java, Segment.java, Section.java, Memory.java
  Process.java, Kernel.java, Thread64.java, FutexManager.java
  device/Console.java, StdConsole.java, JLineConsole.java

dist/
  build-dist.sh             配布 zip ビルドスクリプト
  build-sandbox.sh          sandbox 構築スクリプト
  launchers/emulin.sh / .bat 起動ランチャ
  README.txt                配布 zip 用 README

tests/
  binaries/src/             x86 / x86-64 テスト ELF ソース
  scripts/                  回帰テスト実行スクリプト
  expected/                 期待出力 (stdout / exit / argv / stdin)
```

## 履歴

`.claude/CLAUDE.md` に Phase 別の作業記録があります (現代化 + 64-bit 拡張 +
実機 binary 対応の各 phase の要約と既知バグの累計パターン)。

## 連絡先

- バグ、要望、質問: <kiyokasumibi@gmail.com>
- GitHub Issues: https://github.com/kiyoka/emulin/issues
