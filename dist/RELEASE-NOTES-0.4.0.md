# Emulin 0.4.0 Release Notes

Java で動く 32/64-bit Linux ELF エミュレータ。0.4.0 は **Git for Windows
(Git Bash) 同等のコマンドセット + 対話 editor / script language** を同梱した
「解凍してすぐ使える Linux 開発環境」を目指したリリース。

## ハイライト

### Git for Windows 互換 + extras 全部入り配布

`dist/build-release.sh` で **4 platform (linux-x64 / windows-x64 /
macos-x64 / macos-arm64)** 用 demo zip を一括 build。同梱:

- **OpenSSH** client (ssh / scp / sftp / ssh-keygen / ssh-keyscan) + **sshd**
- **vim 9.1** (view / vimdiff / rview / rvim 含む)
- **emacs-nox 29.3**
- **perl 5.x** (perl5.36.1 互換 symlink)
- **python 3.12** (python / python3 symlink)
- **tig** (git history browser)
- **dos2unix family** (dos2unix / unix2dos / mac2unix / unix2mac + d2u / u2d)
- GNU coreutils / bash / git / curl / wget / openssl / gpg suite / gettext 等

### Windows native での実用化

- **SSH login 完走**: Windows cmd.exe から emulin 経由で
  `ssh -i key -T git@github.com` が passphrase 付き ed25519 鍵で完走
  (`Hi <user>! You've successfully authenticated, ...`)。
- **`/mnt/c` で C: ドライブにアクセス** (WSL 互換): Windows host の各
  drive を `/mnt/c`, `/mnt/d`, ... に auto-mount。sandbox 内 vim/git で
  Windows 側 file を直接編集可能 (`EMULIN_NO_HOST_MOUNT=1` で opt-out)。
- **`HOME=/root`**: launcher が HOME を設定、`~/.ssh` / `~/.vimrc` /
  `~/.gitconfig` が解決される。

### ライセンス遵守 (GPL/LGPL 配布要件)

- 同梱 binary の `/usr/share/doc/<pkg>/copyright` を **62 package 分** 保持
  (Debian Policy §12.5 準拠)。
- `NOTICE.txt` に GPL §3(b) の 3-year written offer + Ubuntu archive
  source URL。
- `THIRD-PARTY-LICENSES.md` で同梱 package の license inventory。

## 主な変更 (0.3.0 → 0.4.0)

### sshd / ssh (issue #41, #43, #45, #47, #55)
- OpenSSH sshd を emulin 上で動作 (publickey auth + non-interactive exec
  + interactive PTY)。`ssh user@host`, `ssh -tt user@host`,
  `ssh user@host cmd` の 3 モード対応。
- multi-session / sftp-server / ssh-agent forwarding / interactive readline。
- ssh client → emulin sshd の self-loop e2e test (6 KEX algorithm)。
- **ssh client post-auth hang fix** (#55): Windows cmd.exe の cooked mode
  TTY が Enter で `\r` のみ送る件を ICRNL 相当で吸収。

### AF_INET6 / AF_UNIX (issue #9)
- AF_UNIX client (ssh-agent 経由) / AF_INET6 client TCP・UDP・server。
- amd64 で未実装だった accept(43)/accept4(288) を新設 (v4/v6 両対応)。

### Git for Windows 互換 (issue #7)
- 262 binary 中、対応可能な 256 件 (100%) を sandbox に同梱可能化。
- vim aliases / dos2unix family / perl5.36.1 symlink / gencat 追加。

### perf (issue #4, #48)
- JIT translator に ADC/SBB + MUL/IMUL/DIV r64 追加 (RSA 1024-bit -21%)。
- ※ RSA 2048-bit の 30s 目標は未達 (ed25519 推奨で workaround)。

### 配布 (issue #59)
- `dist/build-release.sh` で 4 platform 一括 build。
- launcher で `HOME=/root` export。
- INCLUDE_PYTHON=1 で python3 + stdlib 同梱。

## 既知の制約

- RSA 2048-bit 鍵生成は emulator 速度の制約で実用時間内に終わらない
  (ed25519 推奨)。issue #48 参照。
- emacs-nox は起動が重い (emulator の CPU dispatch がボトルネック)。
  issue #65 で perf 計測中。
- portable dump は emacs 29.x の native-comp 制約で困難 (issue #65)。

## ビルド方法

```bash
# 全 platform (emacs 込み)
dist/build-release.sh

# Windows のみ、emacs 抜き (容量削減)
PLATFORMS="windows-x64" INCLUDE_EMACS=0 dist/build-release.sh
```

生成物: `target/emulin-demo-0.4.0-<platform>.zip`

## 動作要件

- Java Runtime Environment 11 以降 (JRE は zip に同梱されるため別途不要)
- Windows raw mode は jline-terminal-jni 経由 (同梱済)
- Windows Terminal 推奨 (cmd.exe は Ctrl-A / Ctrl-F を intercept)
