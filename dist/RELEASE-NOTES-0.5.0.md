# Emulin 0.5.0 Release Notes

Java で動く 32/64-bit Linux ELF エミュレータ。0.4.0 (「解凍してすぐ使える
Git for Windows 同等の Linux 開発環境」) を土台に、**0.5.0 は実マルチスレッド
アプリ・Debian パッケージ管理・日本語入力が動く実用段階**へ到達したリリース。

並行性 (clone/pthread) の根本バグ (issue #113) を解消し、`claude` CLI のような
Node.js 製マルチスレッドアプリ、`apt-get install` / `dpkg` による Debian
パッケージ管理、対話 tmux 3.4、mozc / ddskk による日本語入力が動作する。

## ハイライト

### 実アプリが動く: claude CLI / マルチスレッド安定化 (issue #113, #185–#189)

- **非決定的 SIGSEGV の根本原因を解消** (issue #113): nested `clone` で子
  スレッドが親 (main 固定) の Cpu64 レジスタを取り違えて生成直後に wild jump
  していた本丸バグ (#181) を修正。併せて alloclist mutation の thread-safety
  (#182)、`madvise(MADV_DONTNEED/FREE)` のゼロ化で glibc stack-cache garbage
  残存を解消 (#180/#183)、命令フェッチ (bulkLoad) の epoch coherence (#174)、
  aligned wide store/load・LOCK prefix RMW の atomic 化 (#171/#173) 等、
  並行性ハードニングを一通り実施。
- **`claude` (Anthropic CLI) が動作**: 決定的 SIGSEGV だった ADC AL,imm8 の
  carry-in 欠落 (#185/#186)、未実装の単独 string ops CMPS/SCAS (#187)、
  自己書き換えコード (JIT) の prefix decode cache coherence (#189) を修正し、
  `claude --version` 〜実アプリが完走。

### Debian パッケージ管理: apt / dpkg (issue #191)

- **`dpkg -i` / `apt-get install` (file:// + http) / gpg 署名検証** を
  end-to-end で完走。ed25519 署名 repo の `apt-get update` が gpg 検証を通り、
  署名パッケージの install → configure → 実行まで動作。
- 多数の syscall fix: `rmdir` の ENOTEMPTY / ENOTDIR 区別、`wait4` exit_code、
  `fchmod`、`statfs` / `fstatfs`、`msync`、`O_TMPFILE` の EOPNOTSUPP fallback、
  Debian default PATH での execve fallthrough (EMU_PATH 不要化)。

### 対話 tmux 3.4 + ripgrep / fd (issue #131)

- **対話 tmux** が動作 (`-CJ` JLine console 必須)。SCM_RIGHTS (fd passing)、
  AF_UNIX 接続済 socket の POLLIN、`/proc/<pid>/fd` 合成 directory、
  SO_PEERCRED、pty 継承 など tmux 依存経路を実装 (layer 2–17)。
- **ripgrep (rg) / fd** を同梱可能化 (`INCLUDE_RIPGREP` / `INCLUDE_FD`)。
  Rust 製バイナリの statx (#136) + NULL probe (#141) を整備し走査 segfault
  を解消。

### 日本語入力: mozc かな漢字変換 + emacs ddskk (issue #202, ddskk)

- **mozc-server** (12.5 MB の実パッケージ) を動かし、**かな漢字変換が完全動作**
  ("nihonngo"→「日本語」)。真因は `shutdown(SHUT_WR)` が no-op で mozc IPC の
  half-close (EOF による request 終端) が機能していなかった点。非 root 実行・
  SSE2・abstract Unix socket も対応 (#201)。
- **emacs の ddskk** 日本語入力時のクラッシュ2件を修正 (signal trampoline の
  実体化 + IMUL RIP 相対 EA、`/proc/self/maps` の実 p_flags 報告)。

### emacs の実用性向上 (issue #206, #207, #76, #132/#210)

- **対話レイテンシ解消** (#206): poll / pselect / select の busy-sleep を
  blocking peek 化。isearch 等が軽快に。
- **melpa package install 対応** (#210/#211): `copy_file_range` (syscall #326)
  実装で `package-install` の warning を解消、native-comp の gcc 起動を
  `early-init.el` で抑止し対話起動の gcc エラーを解消。
- getdents64 の per-entry NIO 削減 (#207 第1段)。
- **`M-x shell` (subshell) が動作** (issue #76 解消): PTY I/O の hang→約30秒後
  segfault を修正。emacs 内でサブシェルが使える。

### host 環境変数の継承 (issue #212)

- launcher 経由起動で `EMULIN_INHERIT_ENV=1` を set し、ホスト OS の既存環境
  変数を guest プロセスへ継承。emulin が必須とする PATH / HOME 等は emulin
  値で上書きされ host の Windows 値が guest を壊さない (`=0` で従来動作)。

## 主な変更 (0.4.0 → 0.5.0)

ハイライトに加え、主な emulator-core / JIT / 配布の変更:

- **#138 / #161**: JIT の RIP-relative + immediate メモリ命令のアドレス誤計算
  を修正。
- **#162**: amd64 `select` を 64-bit address 対応に (切り詰め解消)。
- **#187 / #4**: 単独 string ops (CMPS/SCAS 0xA6/A7/AE/AF) や ADC/SBB の
  carry 取りこぼし等、命令デコードの取りこぼし監査・修正。
- **#130 / #131**: 開発 CLI tool 群 (jq / sqlite3 / nano / tree / patch / zip /
  rsync) + tmux / ripgrep / fd の同梱整備。
- v0.4.0 以降 **102 commits**。

## 既知の制約

- RSA 2048-bit 鍵生成は emulator 速度の制約で実用時間内に終わらない
  (ed25519 推奨、issue #48)。
- emacs-nox は起動・一部操作が重い (emulator の CPU dispatch がボトルネック)。
  高速化は issue #190 で継続検討中 (per-byte memory emulation の load8 が支配的)。
- emacs `(package-initialize)` での melpa archive 全反復起動が遅い
  (issue #207、第1段のみ着手)。

## ビルド方法

```bash
# 全 platform (linux/windows-x64 + macos x64/arm64、emacs 込み)
dist/build-release.sh

# Windows のみ、emacs 抜き (容量削減)
PLATFORMS="windows-x64" INCLUDE_EMACS=0 dist/build-release.sh
```

生成物: `target/emulin-demo-0.5.0-<platform>.zip`

## 動作要件

- Java Runtime Environment 11 以降 (JRE は zip に同梱されるため別途不要)
- Windows raw mode は jline-terminal-jni 経由 (同梱済)
- Windows Terminal 推奨 (cmd.exe は Ctrl-A / Ctrl-F を intercept)
