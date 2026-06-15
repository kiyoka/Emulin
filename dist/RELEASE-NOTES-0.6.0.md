# Emulin 0.6.0 Release Notes

Java で動く 32/64-bit Linux ELF エミュレータ。0.5.0 (「実マルチスレッドアプリ・
Debian パッケージ管理・日本語入力が動く実用段階」) を土台に、**0.6.0 は次の 3 本柱を
製品機能としたリリース**:

1. **ネイティブ実行** — Hyper-V (WHP) / KVM で guest を実 vCPU 実行し compute 律速の
   処理を大幅高速化
2. **SSH サーバ化** — `emulin sshd` で外部 SSH クライアントから接続して使う
3. **Debian base + apt** — 素の Debian 13 (trixie) base 相当の bundle 上で
   `apt-get install` / `dpkg` によるパッケージ追加

bundle は busybox curated 構成から **Debian 13 (trixie) base 相当**へ移行した。

## ハイライト

### ネイティブ実行: Hyper-V (WHP) / KVM backend (issue #221)

- **guest を実 vCPU (ring-3) で実行し、syscall だけ emulin にトラップする native
  backend** を新搭載。Windows は Hyper-V (Windows Hypervisor Platform / WHP)、
  Linux は KVM を使う。compute 律速の workload で **~200x 高速化** (sort / grep /
  sha256sum 等)、大規模 git clone (HTTPS) や対話 binary (bash/vim/emacs) もネイティブで
  実用速度になる。
- **launcher 既定で auto 検出** (#300): HW 仮想化が使えれば native、無ければ software に
  自動 fallback。`EMULIN_BACKEND=software|native|auto` で明示制御できる。
- WHP は 1-partition 制限を **単一 partition + GPA slot** で構造的に回避し fork まで
  対応 (#298)。guest RAM の **lazy commit** で fork 連鎖の commit charge を git clone
  full で 12.3GB→3.5GB に削減 (#320)。物理プール既定 2GB (#303)。
- **software backend は引き続き正しさの canonical (基準)**。native は software と
  **byte 一致** — whp-oracle 51 件 + native-oracle の多数の実 binary (grep/gawk/sed/
  perl/python/node/git/sqlite/tmux 等) で検証。

### SSH サーバとして使う: `emulin sshd` (issue #41, #219)

- emulin 上で **OpenSSH sshd** を起動し、外部の SSH クライアント (`ssh` / Tera Term 等)
  から接続して bash / vim / emacs を対話操作できる (`emulin sshd [port]`、既定 2222、
  publickey 認証、user=root)。本物の SSH クライアント経由なので Windows コンソールの
  キー制約 (Ctrl+Space 等) を回避できる。
- bundle に **OpenSSH 10 の sshd-session / sshd-auth** (privsep 分離) を同梱 (#317)。
  デーモンは自動起動せず、ユーザが明示的に起動する方針。

### Debian base + apt / dpkg でパッケージ追加 (issue #322, #324)

- demo/release bundle を **Debian 13 (trixie) base 相当** (essential + apt/dpkg +
  正しい dpkg status DB + `/etc/apt` + keyring) へ移行 (#323)。素の Debian の上に
  git/vim/emacs 等を overlay する構成にした。
- emulin 上で **`apt-get update` / `apt-get install` / `dpkg -i` が GPG 署名検証込みで
  動作** (deb.debian.org の trixie)。Debian の usrmerge 相対 symlink で顕在化した
  2 つの core バグ — rmdir sweep が rootfs root の symlink を削除する問題、apt の
  `seteuid(0)` を uid paranoia が拒否する問題 — を修正 (#324、uid/gid を
  real/effective/saved trio で追跡)。

### Ubuntu → Debian 13 (trixie) 移行 (issue #308)

- 開発 host・同梱 binary を **Ubuntu から Debian 13 (trixie、glibc 2.41)** へ移行
  (#313–#319)。同梱 binary は全て Debian package 由来、`/etc/os-release` 等の distro
  id file・license/copyright も Debian のものに更新。

## 主な変更 (0.5.0 → 0.6.0)

ハイライトに加え、主な emulator-core / 安定化の変更:

- **#326**: native backend に `mremap` (`NativeMemoryBackend.realloc`) を実装。
- **#309 / #311**: native の `rt_sigreturn` で pending signal を即時配信 (handler 中の
  `sa_mask` block の配信順)。
- **#302**: Ctrl-C 中断で index-pack が握る tmp file の handle リークによる `rm -rf` の
  EPERM を修正。
- **#307**: Windows host の TEMP 漏れで emacs melpa install が crash する問題を修正。
- native 対応の過程で software backend の命令バグも多数修正 (IMUL CF/OF、x87 FPREM、
  PUSHFQ/POPFQ、RCL/RCR、main stack 8MB 化 等)。
- v0.5.0 以降 **94 commits** (PR #215〜#328)。

## 既知の制約

- native backend は `apt` のように `mremap` で内部 cache を繰り返し拡張する workload で
  memory allocator 制約 (解放領域の reclaim 未対応、issue #304) によりメモリを消費しきる
  場合がある。`apt` 等は `EMULIN_BACKEND=software` を推奨。
- 初回 `apt-get update` は Debian index (~9.6 MB) の解析が律速で、software backend では
  十数分かかることがある。
- macOS の Hypervisor.framework (HVF) native backend は未対応 (issue #306、将来)。
- emacs-nox は software backend では起動・一部操作が重い (native backend で改善)。

## ビルド方法

```bash
# 全 platform (linux/windows-x64 + macos x64/arm64、emacs + sshd 込み)
dist/build-release.sh

# Windows のみ、emacs 抜き (容量削減)
PLATFORMS="windows-x64" INCLUDE_EMACS=0 dist/build-release.sh
```

生成物: `target/debian-emulin-0.6.0-<platform>.zip` (Debian base 構成。`DEBIAN_BASE=0` では `emulin-demo-*`)

## 動作要件

- Java Runtime Environment 11 以降 (JRE は zip に同梱されるため別途不要)
- ネイティブ実行 (任意): Windows = 「Windows ハイパーバイザー プラットフォーム」を有効化 /
  Linux = `/dev/kvm` にアクセス可能。無くても software backend で動作する。
- Windows raw mode は jline-terminal-jni 経由 (同梱済)
- Windows Terminal 推奨 (cmd.exe は Ctrl-A / Ctrl-F を intercept)
