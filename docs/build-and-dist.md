# 配布・ビルド (zip 早見表 / 0.4.0 release)

> このファイルは旧 `.claude/CLAUDE.md` から移設した配布まわりの参照情報。
> ビルドスクリプト本体は `dist/build-{dist,sandbox,jre-bundle,demo-bundle,release}.sh`。

## 配布 zip 早見表
| 種類 | サイズ (Linux/Win/Mac) | sandbox |
|------|------------------------|---------|
| dist (busybox) | 1.7 MB | 空 |
| jre-bundle | 22/20/20 MB | busybox のみ |
| demo (default) | 72/38/69 MB | bash + coreutils + git/curl/wget + less |
| demo (INCLUDE_VIM=1) | 101/54/98 MB | + vim 9.1 |
| demo (INCLUDE_EMACS=1) | 229/120/220 MB | + emacs-nox 29.3 |
| **release (0.4.0、全部入り)** | **~258 MB (emacs 抜) / ~380 MB (emacs 込)** | + perl/python3/ssh/sshd/tig/make/vim(/emacs) |

INCLUDE_TIG / INCLUDE_PERL / INCLUDE_SSH / INCLUDE_SSHD / INCLUDE_PYTHON /
INCLUDE_MAKE / INCLUDE_TMUX / INCLUDE_RIPGREP / INCLUDE_FD で tig / Perl 5 /
openssh-client / sshd / Python 3.12 / GNU make / tmux 3.4 / ripgrep (rg) /
fd も同梱可 (make は ~254 KB・依存 libc のみ、issue #129。tmux は ~1.1 MB +
libevent/libtinfo/libutempter 等、issue #131。tmux 対話は JLine console =
`-CJ` 必須。rg/fd は Rust 製で statx#136+NULL probe#141 で走査 segfault 解消、
issue #131 Part A。fd の binary 名は fdfind で fd→fdfind の symlink を張る)。

## 0.4.0 release (Git for Windows 同等 + extras)
- **`dist/build-release.sh`** で INCLUDE_* 全 on の demo zip を 4 platform
  (linux-x64 / windows-x64 / macos-x64 / macos-arm64) 一括 build。
  - `PLATFORMS="windows-x64"` で対象を絞れる
  - `INCLUDE_EMACS=0` で emacs 除外 (容量 -120 MB)
  - project が `/mnt/c` (NTFS) 上のとき staging を /tmp に逃がし
    terminfo/perl の case-collision を回避 (`EMULIN_STAGE_DIR` で override 可)
- launcher (`emulin.bat`/`emulin.sh`) は `HOME=/root` を export
  (sandbox 内 root user の home、`~/.ssh` 等が解決)。
- license: `rootfs/usr/share/doc/<pkg>/copyright` (62 package) +
  `NOTICE.txt` (GPL §3(b) written offer) + `THIRD-PARTY-LICENSES.md`。
