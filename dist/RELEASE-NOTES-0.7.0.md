# Emulin 0.7.0 Release Notes

Java で動く 32/64-bit Linux ELF エミュレータ。0.6.0 (「ネイティブ実行・SSH サーバ・
Debian base + apt」) を土台に、**0.7.0 の目玉は「Emulin 上で実用的な AI コーディング
エージェントが動く」こと** (issue #698):

1. **Claude Code (Node.js 版 2.1.112)** — 対話・キー入力・サブスクリプション認証
   (`/login`)・実コーディングまで動作
2. **Codex** — パッチ生成・コマンド実行・対話まで動作
   (`sandbox_mode = "danger-full-access"` 前提)

この実現のために、エミュレータ core の安定性 (native メモリ管理・シグナル・
pty/console・Windows ファイル I/O) と CPU 命令実装を大規模に強化した。

## ハイライト

### AI コーディングエージェント対応 (issue #698)

- **Node.js 版 Claude Code (2.1.112) が動作**: 対話 TUI・キー入力・`/login`
  (サブスクリプション OAuth)・実コーディングまで。導入手順と launcher 例は README の
  「AI コーディングエージェントを動かす」を参照。2.1.113 以降は Bun ネイティブ
  バイナリで入力が通らないため (#422)、**使える最新版は 2.1.112** (バージョン固定 +
  `DISABLE_AUTOUPDATER=1` を推奨)。
- **Codex が動作**: `~/.codex/config.toml` に `sandbox_mode = "danger-full-access"` を
  設定して利用する (Emulin の rootfs 自体が隔離境界。guest 内の Landlock+seccomp
  sandbox は未対応、bwrap 用 user namespace エミュレーションは #497 で計画中)。
- エージェントが要求する基盤を多数実装: console→pty-master bridge (#413)、JSC/V8 系の
  suspend-resume シグナル基盤 (#533)、epoll starvation 解消 (#485)、pty line discipline
  (ECHO/ISIG/ICANON、#688/#690/#692)、fork/execve をまたぐ termios・fd 属性の保持
  (#550/#552) 等。

### 長時間セッションの安定化 (native メモリ管理)

- **page table 領域の枯渇で長時間セッションが刺さる問題を解消** (#710): 累積 mmap で
  leaf PT を使い切ると guest thread が例外死していた。PT を data プールへ fallback
  割当して実質無制限化 (#711)。
- **pool 枯渇時は Linux の OOM killer と同じ縮退に** (#713): demand paging 中の枯渇でも
  当該プロセスだけ SIGKILL 死し、親が WTERMSIG=9 で reap してセッションを継続できる。
- **リソース逼迫でも「その fork だけ EAGAIN」で継続** (#720): 32GB 窓ひっ迫 (#379) で fork 子の
  guest RAM pool が確保できないとき、旧実装は JVM ごと終了していた (= `emulin sshd` 常駐なら
  全 SSH セッション消失)。Linux の fork(2) と同じく **EAGAIN** を返す縮退にし、シェルの
  リトライで作業が続く (`sshd` 上での実運用で確認)。
- **fork 子 pool 縮小時のアドレス空間不整合を修正** (#723): 窓ひっ迫で子 pool が縮小されると
  page table 領域の境界 (DATA_BASE) が親と食い違い、孫世代の fork が未 commit 領域を読んで
  JVM が EXCEPTION_ACCESS_VIOLATION で死ぬ経路があった。親のレイアウトを継承して解消。
- codex/claude 級のワークロードで踏んだ native backend のバグを多数修正: mmap 下方
  bump の stack 帯貫通 + FUTEX_WAIT_BITSET 絶対タイムアウト誤解釈 (#435)、SHUFPD の
  RIP-relative EA ずれによる Rust HashMap 破壊 (#597)、epoll 能動 peek と reader の
  無同期 race による TLS デッドロック (#494)、WHP kick のレース (#498)、動的リンク
  全滅回帰 (#617→#629) 等。

### Windows 実用性能 (ファイル I/O・プロセス起動)

- **小ファイル I/O を大幅高速化** (#495): namei dentry cache で git checkout
  67.5s→11.1s、codex の 60 秒級 stall も解消。
- **stat 属性の InodeCache** (#704) と **fork+exec の高速化** (#701–#705、
  ~375ms→~36ms/spawn): claude の起動時 ~90 子プロセス spawn が実用速度に。
- Windows の st_ino が経路依存で揺れて glibc ldconfig が無言 abort する問題 (#598)、
  rootfs に `/mnt` が無く `/mnt/c` 作業ディレクトリで claude が黒画面になる問題
  (#699) を解消。

### CPU 命令実装の体系的強化

- x86-64 / i386 の命令実装を体系的に検証し、多数の非互換を修正 (#518–#527、
  #631–#655 ほか)。x87 全域・string 命令・BCD・3-op IMUL・shift/rotate フラグ等を
  実装・修正し、**例外セマンティクス (#UD→SIGILL / 特権→#GP / INT3→SIGTRAP /
  div→SIGFPE) を実装**して「未実装命令で silent に exit 0」を排除 (#645–#648)。
- メモリ/ページング周りの Linux 互換を強化: MAP_SHARED ページの fork 親子共有
  (#675)、`/proc/<pid>/maps` の VMA 化 (#681)、≥2GiB file-backed mmap (#527)、
  mprotect/PROT 追跡と SIGSEGV/SIGBUS の配送 (#559/#617/#548) 等。

## 主な変更 (0.6.0 → 0.7.0)

ハイライトに加えた主要修正:

- **#700**: `creat(2)` 実装 (`tar cf` が ENOSYS で失敗していた)。
- **#549/#550/#551/#552**: FUTEX_CMP_REQUEUE / execve 属性喪失 / O_NONBLOCK pipe
  write / fork 後の pty termios 喪失を修正。
- **#553–#556, #615–#619**: mmap 幾何 (MAP_FIXED_NOREPLACE / MAP_32BIT / brk 境界等)・
  msync・madvise 系の Linux 互換修正。
- **#692**: Windows console の Backspace を Linux 端末既定 (DEL 0x7f) に整合。
- **#706/#707/#708**: Windows stat 意味論の診断スイッチ、getrusage の who 解釈。
- **#712**: 配布 rootfs に `/mnt` を常設 (Windows の `/mnt/<drive>` auto-mount の親)。
- **#722**: 末尾 `/` ・`/.` の path にディレクトリ要求 (POSIX) を実装 (emacs/magit のファイル訪問が
  `Doing vfork: Not a directory` で失敗していた)。
- v0.6.0 以降 **423 commits**。

## 既知の制約 (0.7.0)

- **Claude Code は Node.js 版 2.1.112 まで** — 2.1.113 以降は Bun バイナリで
  キー入力が通らない (#422)。
- **Claude Code の `/quit` に時間がかかる** (#695、#696 で大幅改善済みだが数十秒残る)。
- **tmux 上のエージェント TUI は未対応** (#694) — tmux の外で起動する。
- **Windows でまれに入力がフリーズ** (#709) — Windows の **ConPTY 層**でキーイベントが滞留する
  (Emulin は無罪: `emulin sshd` に ssh で繋いだ構成 = Emulin が入力経路に居ない場合でも同様に発生する)。
  **ウィンドウを一度リサイズすると溜まった入力が流れて回復**し、セッションはそのまま継続できる。
  ConPTY を通らない端末 (WezTerm 内蔵 SSH / Tera Term / PuTTY 等) なら回避できる可能性がある (未検証)。
- `/mnt/c` 上の大規模 repo は workspace スキャンが遅い — rootfs 内に clone して作業を推奨。
- (0.6.0 から継続) systemd を含む大規模 package 群は configure 不可 / macOS HVF native
  backend は未対応 (#306)。

## ビルド方法

```bash
# 全 platform (linux/windows-x64 + macos x64/arm64、emacs + sshd 込み)
dist/build-release.sh

# Windows のみ、emacs 抜き (容量削減)
PLATFORMS="windows-x64" INCLUDE_EMACS=0 dist/build-release.sh
```

生成物: `target/debian-emulin-0.7.0-<platform>.zip` (Debian base 構成。`DEBIAN_BASE=0` では `emulin-demo-*`)

## 動作要件

- Java Runtime Environment **25 以降** (JRE は zip に同梱されるため別途不要)
- ネイティブ実行 (任意): Windows = 「Windows ハイパーバイザー プラットフォーム」を有効化 /
  Linux = `/dev/kvm` にアクセス可能。無くても software backend で動作する。
- Windows raw mode は jline-terminal-jni 経由 (同梱済)
- Windows Terminal 推奨 (cmd.exe は Ctrl-A / Ctrl-F を intercept)
