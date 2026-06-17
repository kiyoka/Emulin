# Emulin

[English](README.md) | **日本語**

**Java で動く 32/64-bit Linux ELF エミュレータ**

GNU General Public License v2 (詳細は `COPYING` を参照)

---

## 概要

Emulin は、Linux x86 (32-bit) / x86-64 (64-bit) ELF バイナリを Java で実行する
エミュレータです。pure Java で動作するため、Windows / macOS で Linux バイナリを動かせます。

実機 Linux binary (git / curl / openssl / Python 3.12 / vim 9.1 / emacs-nox /
GNU coreutils 等) を動かせます。

Windows では **Windows Hypervisor Platform (WHP)**、Linux では **KVM** を使った
ネイティブ実行 backend にも対応しており、利用できる環境では guest を実 vCPU で実行して
大幅に高速化します (利用できない環境では pure Java 実行に自動で fallback)。

## まず動かす

[Releases](https://github.com/kiyoka/Emulin/releases) から配布 zip を取得
(または `dist/build-release.sh` でビルド)し、任意の場所に解凍します。JRE 同梱なので
**Java のインストールは不要**です。

## 特徴

- 全て Java で記述 (pure Java、JNI 無し)
- 32-bit ELF (i386) と 64-bit ELF (x86-64) の両方を実行可能
- 動的リンクの実機 binary を実行可能 (PIE / ld.so / libc / pthread 対応)
- **Debian 13 (trixie) base 相当の bundle + `apt` / `dpkg`** — emulin 上で
  `apt-get install` / `dpkg -i` によりパッケージを GPG 署名検証込みで追加可能
  ([Debian パッケージの追加](#debian-パッケージの追加-apt--dpkg))
- AES-NI / PCLMULQDQ 命令を完全実装 (FIPS-197 host 一致)
- pthread 完全対応 (clone+futex / per-thread signal mask / mutex 競合)
- TLS 1.3 (gnutls 経由、cert verify 含む) 完全動作
- AF_INET6 (IPv6) socket 対応 — client TCP / UDP + server (accept4)、AF_UNIX も対応
- JLine 3 採用で Linux/macOS/Windows 共通の raw mode / Ctrl-C / SIGWINCH 対応
- **Windows cmd.exe / Windows Terminal で bash・vim・emacs の対話編集が動作**
- **basic-block JIT 翻訳器 (オプション)**: `EMULIN_USE_JIT=1` で x86-64 命令を
  Java bytecode に翻訳。AES-NI / PCLMULQDQ も対応し HTTPS で -13〜14% 高速化 (既定 off)
- **ネイティブ実行 backend (Hyper-V WHP / KVM)**: guest を実 vCPU で実行し syscall だけ
  emulin にトラップ。compute 律速で ~200x 高速化、software と byte 一致
  ([ネイティブ実行](#ネイティブ実行で高速化-hyper-v--kvm))
- **SSH サーバ対応**: `emulin sshd` で OpenSSH sshd を起動し外部 SSH クライアントから
  接続 ([SSH サーバとして使う](#ssh-サーバとして使う-emulin-sshd))

## 動作する実機 binary (例)

- **GNU coreutils 30+** (cat / ls / cp / mv / sort / find / grep 等)
- **bash 5.2 + line edit** (history / cursor / Tab、JLine raw mode 経由)
- **vim 9.1** — `vim -e -s` ex mode + cmd.exe での対話モード編集 (insert / `:wq`)
- **emacs-nox 29.3** (対話編集)
- **Python 3.12 stdlib** (re / json / collections / enum / functools /
  math / datetime 等)
- **OpenSSL 3.0.13** (TLS 1.3、AES-GCM、HTTPS handshake)
- **curl / wget HTTPS** (HTTP/1.1 / HTTP/2、multi-site: github / cloudflare
  / google / iana / raw.githubusercontent 等)
- **git**: init / add / commit / log / status / diff / clone
  (git:// / file:// / https:// 全対応、`--depth` / templates / hardlinks 含む)
- **less 643** (vt100 keybind、SIGWINCH 対応)

## 動作環境

| 項目 | 内容 |
|------|------|
| JDK / JRE | **25 以降** (OpenJDK 25 LTS で開発・テスト、Java FFM 採用のため #221) |
| OS | Linux (主) / Windows 11 Home 以上 / macOS |

## クイックスタート

### Windows で使い始める (Java 不要)

JRE (Microsoft Build of OpenJDK 25) を同梱しているので、**Java を別途インストール
する必要はありません**。解凍するだけで動きます。

1. **配布 zip をダウンロード**
   [Releases](https://github.com/kiyoka/Emulin/releases) から
   `debian-emulin-0.6.0-windows-x64.zip` を取得します(ローカルでビルドする場合は
   `dist/build-release.sh`)。Debian 13 (trixie) ベース + `apt` / `dpkg` に
   git / curl / wget / openssl / python3 / vim / emacs 等を同梱した bundle です。

2. **任意の場所に解凍**
   例: `C:\Tools\debian-emulin-0.6.0-windows\`(パスに日本語・空白を含めても
   動きますが、できるだけ ASCII のパスを推奨)。

3. **bash 対話シェルを起動**
   解凍ディレクトリで `emulin.bat` をダブルクリック、または cmd / Windows Terminal で:
   ```cmd
   cd C:\Tools\debian-emulin-0.6.0-windows
   emulin.bat
   ```
   ```
   # echo hello
   hello
   # uname -m
   x86_64
   # exit
   ```
   (初回起動時は同梱 rootfs を展開するため少し時間がかかります。)

4. **1 コマンド実行モード / 実機 binary の実行**
   `debian-emulin-0.6.0-windows` には git / curl / openssl / python3 等が同梱
   されているので、解凍直後から実行できます:
   ```cmd
   emulin.bat ls /
   emulin.bat /usr/bin/git --version
   emulin.bat /usr/bin/git clone --depth=1 https://github.com/octocat/Hello-World.git /tmp/cloned
   ```

`apt` でのパッケージ追加は [Debian パッケージの追加](#debian-パッケージの追加-apt--dpkg) を参照。

> **メモ**:
> - 同梱 JRE は Microsoft Build of OpenJDK 25 (GPLv2 + Classpath Exception)。詳細は同梱の `NOTICE.txt` 参照。
> - `emulin.bat` は内部で同梱 JRE (`jre\bin\java.exe`) を呼び出すため、PATH に Java が無くても動作します。
> - 引数なしの `emulin.bat` は Windows Terminal で対話 bash を起動します(`set EMULIN_NO_WT=1` で通常コンソール)。

### ソースからビルド

```bash
mvn package -DskipTests   # → target/emulin-<version>-all.jar
```

ビルドした fat jar は配布 zip の `emulin.bat` / `emulin.sh` が内部で呼び出します。
ローカルで Debian ベースの bundle を作るには `dist/build-release.sh` を使います。

## Debian パッケージの追加 (apt / dpkg)

demo / release bundle (既定 `DEBIAN_BASE=1`) は **Debian 13 (trixie) base 相当**の
rootfs を土台にしており、`apt` / `dpkg` と apt の前提環境
(`/etc/apt/sources.list.d/debian.sources` + `debian-archive-keyring` 署名鍵) を
同梱しています。そのため emulin 上で `apt-get` によるパッケージ追加が
**GPG 署名検証込みで** end-to-end 動作します (deb.debian.org の trixie main /
trixie-security)。

```bash
# パッケージインデックスの取得 (初回は時間がかかります。後述「速度」参照)
./emulin.sh /usr/bin/apt-get update </dev/null

# パッケージの追加 (例: GNU hello)
./emulin.sh /usr/bin/apt-get install -y hello </dev/null

# 追加した binary の実行 / 確認
./emulin.sh /usr/bin/hello
./emulin.sh /usr/bin/dpkg-query -W hello
```

Windows は `emulin.bat /usr/bin/apt-get ...`、ソースからの直接実行は
`java -XX:-DontCompileHugeMethods -jar emulin-*-all.jar <rootfs> /usr/bin/apt-get ...`
に読み替えてください。`apt` 入りのローカル rootfs は
`dist/build-debian-base.sh <rootfs>` でも作れます。`dpkg -i <pkg>.deb` による
ローカル install も同様に動作します。

### 運用上の注意

- **`</dev/null` または `-y` を付ける** — `apt-get` は標準入力 (fd 0) を読みます。
  端末を持たないスクリプト経由などで stdin が塞がっていると、確認プロンプトで
  待ち続けて「ハング」したように見えます。非対話で使うときは
  `apt-get install -y <pkg> </dev/null` のように **`-y` + `</dev/null`** を付けてください
  (端末から対話的に実行する場合は不要です)。

- **速度 (初回 `apt-get update` は遅い)** — `apt-get update` は trixie main の Packages
  インデックス (約 9.6 MB) をダウンロードして解析します。emulator の CPU は実機より
  大幅に遅く、**インデックスの解析が律速**になるため、software backend では初回の
  `apt-get update` だけで十数分かかることがあります。一度 update した後の
  `apt-get install` は取得済みリストを使うので相対的に高速です。

- **native (Hyper-V / KVM) backend と apt** — native backend は compute 律速の処理を
  大幅に高速化しますが、apt のように内部 cache を `mremap` で繰り返し拡張する workload は
  現状 native の memory allocator 制約 (解放領域の再利用が未対応) でメモリを消費しきる
  場合があります。**確実に動かすなら software backend (既定) での apt 利用を推奨**します
  (native での apt 完走は継続課題、issue #304)。

## SSH サーバとして使う (`emulin sshd`)

emulin 上で OpenSSH **sshd** を起動し、外部の SSH クライアント (OpenSSH `ssh` /
Tera Term 等) から接続して bash / vim / emacs を対話操作できます。本物の SSH
クライアント経由なので、Windows コンソールのキー制約 (Ctrl+Space 等) を回避できます。

> **デーモンは自動起動しません。** emulin は init/systemd を持たない単一プロセス
> 起動のエミュレータです。sshd はユーザが明示的に `emulin sshd` で起動します。

```bash
# 1. sshd 入りの bundle が必要 (release/full bundle、または INCLUDE_SSHD=1 で build)
# 2. 接続する SSH クライアントの公開鍵を authorized_keys に登録
#    (bundle 内 rootfs/root/.ssh/authorized_keys)
cat ~/.ssh/id_ed25519.pub >> <bundle>/rootfs/root/.ssh/authorized_keys

# 3. sshd を起動 (port 省略時は 2222、127.0.0.1 で待受、user=root、publickey 認証)
./emulin.sh sshd            # または: ./emulin.sh sshd 2222   (Windows は emulin.bat sshd)

# 4. 別の端末から接続
ssh -p 2222 root@127.0.0.1
#   Tera Term: Host=localhost / TCP port=2222 / User=root / 認証=publickey
```

ホスト鍵は起動時に自動で `chmod 600` されます。停止は Ctrl-C。host の環境変数は
guest に引き継がれます (issue #228)。

## ネイティブ実行で高速化 (Hyper-V / KVM)

Windows の **Hyper-V (WHP)** / Linux の **KVM** が使える環境では、guest を実 vCPU で
実行し syscall だけ emulin にトラップする **native backend** が利用できます。compute
律速の処理が大幅に高速化します (sort / grep / sha256sum 等で ~200x、大規模 git clone
も実用速度)。

ランチャ (`emulin.sh` / `emulin.bat`) は既定で `EMULIN_BACKEND=auto` を設定し、
**HW 仮想化が使えれば native、無ければ software に自動 fallback** します。起動時の
バナーで現在の backend が分かります:

```
[backend=native (auto, KVM detected (/dev/kvm OK))]   ← native で実行中
[backend=software]                                    ← software で実行中
```

**要件:**

- **Windows**: 「**Windows ハイパーバイザー プラットフォーム**」(Windows Hypervisor
  Platform) を Windows の機能から有効化 (WSL2 と共存可)。
- **Linux**: `/dev/kvm` にアクセスできること (`kvm` グループに参加、または
  `sudo chmod 666 /dev/kvm`)。

**切り替え / チューニング (環境変数):**

| 変数 | 既定 (launcher) | 説明 |
|------|------|------|
| `EMULIN_BACKEND` | `auto` | `auto` (HW 仮想化を自動検出) / `native` (強制) / `software` (強制) |
| `EMULIN_NATIVE_POOL_MB` | `2048` | native backend の guest 物理プール (MB)。大きな git clone 等で拡大 |

> software backend は **正しさの canonical (基準)** であり常時維持されます。回帰テストは
> software で常に PASS し、native は software と **byte 一致** (native-oracle で検証)。
> 困ったときや `apt` のような mremap 多用 workload (issue #304) は
> `EMULIN_BACKEND=software` で確実に動かせます。macOS の Hypervisor.framework (HVF) は
> 将来対応予定 (issue #306)。

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

- Python 3 の一部 syscall (signalfd4 / pidfd_open 等) 未対応 (optional 経路のため通常は動作)
- **software backend** の実行速度は host より大幅に遅い (curl HTTPS で ~100x、git clone で ~13x)。
  HW 仮想化が使える環境では **native backend (Hyper-V / KVM、既定 auto)** が compute を ~200x 高速化
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
