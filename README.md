# Emulin

**Java で動く 32/64-bit Linux ELF エミュレータ**

GNU General Public License v2 (詳細は `COPYING` を参照)

---

## 概要

Emulin は、Linux x86 (32-bit) / x86-64 (64-bit) ELF バイナリを Java で実行する
エミュレータです。pure Java で動作するため、Windows / macOS で Linux バイナリを動かせます。

実機 Linux binary (git / curl / openssl / Python 3.12 / vim 9.1 / emacs-nox /
GNU coreutils 等) を動かすことができます。busybox が同梱されているため、
すぐに Linux シェル環境を立ち上げることができます。

## 特徴

- 全て Java で記述 (pure Java、JNI 無し)
- 32-bit ELF (i386) と 64-bit ELF (x86-64) の両方を実行可能
- 動的リンクの実機 binary を実行可能 (PIE / ld.so / libc / pthread 対応)
- AES-NI / PCLMULQDQ 命令を完全実装 (FIPS-197 host 一致)
- pthread 完全対応 (clone+futex / per-thread signal mask / mutex 競合)
- TLS 1.3 (gnutls 経由、cert verify 含む) 完全動作
- JLine 3 採用で Linux/macOS/Windows 共通の raw mode / Ctrl-C / SIGWINCH 対応
- **Windows cmd.exe で bash + vim 対話編集が完全動作** (Phase 30)
- **Windows native filesystem 互換** (NTFS rename/delete、Java handle GC、
  HTTPS clone / `rm -rf` 完走、Ctrl-C で emulin が終了しない、Phase 33)
- **fork+exec 連鎖の OOM 解消** (text/mmap segment 共有で fork コスト
  -43%、Phase 31/32)
- **basic-block JIT 翻訳器 (オプション)**: `EMULIN_USE_JIT=1` で x86-64 命令
  → Java bytecode 翻訳。AES-NI / PCLMULQDQ も対応し HTTPS で -13~14%
  speedup (Phase 34-A3/A5、default off)
- 回帰テスト 230 PASS / 0 FAIL 維持

## 動作する実機 binary (例)

- **GNU coreutils 30+** (cat / ls / cp / mv / sort / find / grep 等)
- **bash 5.2 + line edit** (history / cursor / Tab、JLine raw mode 経由)
- **busybox 88 applet** (ash / awk / sed / tar / xargs 等)
- **vim 9.1** — `vim -e -s` ex mode + cmd.exe での対話モード編集 (insert /
  :wq まで)、Phase 29-vim/30 で実装
- **emacs-nox 29.3** — Phase 29-emacs/B/C/D/E の 5 連 fix で動作
- **Python 3.12 stdlib** (re / json / collections / enum / functools /
  math / datetime 等)
- **OpenSSL 3.0.13** (TLS 1.3、AES-GCM、HTTPS handshake)
- **curl / wget HTTPS** (HTTP/1.1 / HTTP/2、multi-site: github / cloudflare
  / google / iana / raw.githubusercontent 等)
- **git**: init / add / commit / log / status / diff / clone
  (git:// / file:// / https:// 全対応、`--depth` / templates / hardlinks 含む)
- **less 643** (vt100 keybind、SIGWINCH 対応)

## 必要環境

| 項目 | 内容 |
|------|------|
| JDK / JRE | 21 以降 (OpenJDK 21 で開発・テスト) |
| Maven | ビルド時のみ (3.6+) |
| OS | Linux (主) / Windows / macOS |

## クイックスタート

> **🚧 リリース成果物の提供は予定です (v0.3.0 時点)**
>
> 下記の配布 zip は [GitHub Releases](https://github.com/kiyoka/Emulin/releases)
> に **まだアップロードされていません**。
> tag `v0.3.0` は打たれていますが、release page で公開された binary 成果物は
> 現時点ではありません。
>
> ローカルで作成する場合は `dist/` 以下の build script で生成できます:
> ```bash
> mvn package -DskipTests
> # Linux native dist (1.7 MB、要 system Java):
> dist/build-dist.sh
> # JRE 同梱版 (22 MB、native build):
> dist/build-jre-bundle.sh
> # demo 同梱版 (= JRE + bash + coreutils + git/curl/wget):
> dist/build-demo-bundle.sh
> # Windows cross-build:
> TARGET_PLATFORM=windows-x64 dist/build-demo-bundle.sh
> ```

### Windows で使い始める (Java 不要)

JRE (OpenJDK Temurin 21) を同梱した zip を**配布する予定**のため、**Java
を別途インストールする必要はありません** (将来 release が公開された時点で)。
解凍するだけで動きます。

1. **配布 zip をダウンロード (予定)**
   将来 [Releases](https://github.com/kiyoka/Emulin/releases) から用途に
   応じて 1 つを選択。現時点ではローカルビルドで作成してください
   (上記「リリース成果物の提供は予定」 callout 参照)。

   | zip (予定) | サイズ (Linux/Win/Mac) | 中身 | 用途 |
   |-----|------------|------|------|
   | `emulin-dist-<ver>.zip`        | 1.7 MB         | jar + busybox のみ、JRE 別途必要 | system Java 持ちの軽量利用 |
   | `emulin-jre-<ver>-{linux,windows,macos}.zip` | 22 / 20 / 20 MB | JRE + busybox    | シェル / coreutils を試す |
   | `emulin-demo-<ver>-{linux,windows,macos}.zip` (default) | 72 / 38 / 69 MB | + bash + coreutils + git / curl / wget / less | すぐに `git clone` 等を動かす |
   | `emulin-demo-<ver>-...` (INCLUDE_VIM=1) | 101 / 54 / 98 MB | + vim 9.1 | vim 編集も試す |
   | `emulin-demo-<ver>-...` (INCLUDE_EMACS=1) | 229 / 120 / 220 MB | + emacs-nox 29.3 | emacs も試す (重い) |

2. **任意の場所に解凍**
   例: `C:\Tools\emulin\` (パスに日本語・空白を含めても動きますが、
   できるだけ ASCII のパスを推奨)

3. **busybox ash 対話シェルを起動**
   解凍ディレクトリでエクスプローラから `emulin.bat` をダブルクリック、
   または `cmd` で:
   ```cmd
   cd C:\Tools\emulin
   emulin.bat
   ```
   ```
   / # echo hello
   hello
   / # ls /bin
   busybox
   / # exit
   ```

4. **1 コマンド実行モード**
   ```cmd
   emulin.bat ls /
   emulin.bat sh -c "echo $((6*7))"
   emulin.bat ash -c "for i in 1 2 3; do echo $i; done"
   ```

5. **demo bundle を選んだ場合のみ — 実機 Linux binary を実行**
   `emulin-demo-*-windows.zip` には git / curl / openssl / python3 が
   同梱されているので、解凍直後から実行可能:
   ```cmd
   emulin.bat /usr/bin/git --version
   emulin.bat /usr/bin/openssl version
   emulin.bat /usr/bin/git clone --depth=1 https://github.com/octocat/Hello-World.git /tmp/cloned
   ```

> **メモ**:
> - 同梱 JRE は Eclipse Temurin OpenJDK 21 (GPLv2 + Classpath Exception)。
>   詳細は同梱の `NOTICE.txt` 参照
> - `emulin.bat` は内部で同梱 JRE (`jre\bin\java.exe`) を呼び出すため、
>   PATH に Java が無くても動作します
> - Linux / macOS 用も同様に `-linux` / `-macos` suffix の zip が並びます
> - 別途 system Java を持っていて軽量版で良い場合は `emulin-dist-<ver>.zip`
>   (~1.7 MB、Java 別途 install 必要) も利用可能

詳細は `dist/README.txt` を参照。

### Linux / macOS で使い始める

```bash
# 1. Java 21+ を install (apt install openjdk-21-jre 等)
# 2. 配布 zip をダウンロード (予定) して解凍、または:
#    dist/build-demo-bundle.sh でローカル build (推奨、本 README 冒頭 callout 参照)
./emulin.sh             # busybox ash 対話シェル
./emulin.sh ls /        # 1 コマンド実行
./emulin.sh sh -c 'echo $((6*7))'
```

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
tests/scripts/run-all.sh      # 全テスト (~4m、230 ケース)
tests/scripts/run-network.sh  # ネットワーク関連だけ (~3m、HTTPS clone 含む)
```

並列負荷下で稀に 1-3 件 timing flake が出ますが standalone では全 PASS します。

## パフォーマンス

### `-XX:-DontCompileHugeMethods` (必須)

実機 binary を動かす時は **`-XX:-DontCompileHugeMethods`** を必ず付けます:

```bash
java -XX:-DontCompileHugeMethods -jar emulin-*-all.jar ...
```

このフラグなしだと、emulator の中核 dispatch loop (`Cpu64::decode_and_exec`、
20K+ bytecode) が JVM の `HugeMethodLimit` (default 8000 byte) で JIT C2
compile を拒否され、interpreter モードで実行されます。
git clone HTTPS で 28% 高速化します (14.4s → 10.4s)。

`emulin.sh` / `emulin.bat` ランチャは自動的にこのフラグを付けます。

### `EMULIN_USE_JIT=1` (オプション、Phase 34-A3/A5)

x86-64 命令を実行時に Java bytecode へ翻訳する basic-block JIT を内蔵
しています。default off ですが crypto 系 workload で speedup が出ます:

| Workload | no JIT | with JIT | 効果 |
|----------|-------:|---------:|------|
| curl https://example.com  | 9.3s | 8.1s | -14% |
| curl https://github.com (570KB) | 10.4s | 9.1s | -13% |
| sha256sum 5MB             | 2.4s | 2.3s | -5%  |

vim 起動のような短尺 cold start workload では neutral〜やや不利
(JIT compile cost と相殺)。HTTPS / SIMD 重い workload で有効です:

```bash
EMULIN_USE_JIT=1 java -XX:-DontCompileHugeMethods -jar emulin-*-all.jar ...
```

## 既知の制約

- IPv6 (AF_INET6) 未対応 — getaddrinfo は IPv4 のみ
- Python 3 の一部 syscall (signalfd4 等) 未対応
- 実行速度は host より大幅に遅い (curl HTTPS で ~100x、git clone で ~13x)
- WSL DrvFs (`/mnt/c/...`) は I/O 遅い → sandbox は Linux /tmp 等に置く

## ディレクトリ構成

```
src/main/java/emulin/        Emulin 本体
  Cpu.java (i386), Cpu64.java (x86-64), AbstractCpu.java
  Syscall.java, SyscallI386.java, SyscallAmd64.java
  Elf.java, ElfCache.java, Segment.java, Section.java, Memory.java
  Process.java, Kernel.java, Thread64.java, FutexManager.java
  device/Console.java, StdConsole.java, JLineConsole.java
  jit/Translator.java, jit/CompiledInsn.java  (Phase 34-A3/A5 JIT)

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
