# 指示

- コミットログに `Co-Authored-By: Claude ...` 行を含めない。
- ディスク容量が足りない場合は `/mnt/d/gitwork` を使用 (約 500GB 空き)。

# プロジェクト概要

古い Emulin (Java 製 32bit Linux ELF エミュレータ) を現代 Java で動かし、
x86-64 (64bit ELF) も実行できるよう拡張する。pure Java only (JNI 撤去済)。

- **OpenJDK 21+ / Maven**、i386 + x86-64 ELF、glibc 静的/動的リンク + **PIE**
  (ET_DYN を 0x555555554000)
- AES-NI / PCLMULQDQ 完全実装、シグナル基盤 (SIGCHLD / SA_RESTART / SA_SIGINFO /
  red zone / per-thread mask + tgkill)、pthread (clone+futex+共有 memory)、
  at-syscall の dirfd 解決
- 動作する実機 binary: coreutils / bash 5.2 / git / curl / wget / less / vim 9.1 /
  emacs-nox 29.3 / Python 3.12。Windows native でも cmd.exe で対話編集 + git clone 完走
- JLine 3.27 で Linux/macOS/Windows 共通 raw mode。`-CJ` launcher デフォルト。
  **Windows raw mode は jline-terminal-jni 必須**

## 既知の未解決課題
- **A. emulator の根本的高速化** — HTTPS の遅さは dispatch + ASN.1 parse が
  ボトルネック。手筋: ASN.1 hot path 最適化 / cert verify cache。
- **E. WSL DrvFs (`/mnt/c/...`) は I/O 遅く chmod 効かない** → sandbox は Linux /tmp に。

# 主要ディレクトリ
- `src/main/java/emulin/`
  - `Cpu.java` (i386), `Cpu64.java` (x86-64), `AbstractCpu.java`
  - `Syscall.java`, `SyscallI386.java`, `SyscallAmd64.java`
  - `Elf.java`, `Segment.java`, `Memory.java`
  - `Process.java`, `Kernel.java`, `Thread64.java`, `FutexManager.java`, `PipeManager.java`
  - `device/`: Console.java / StdConsole.java / JLineConsole.java
- `dist/build-{dist,sandbox,jre-bundle,demo-bundle,release}.sh`

# テスト
- `tests/`: i386/x86-64 静的リンク ELF (-nostdlib で syscall 直叩き)。
  `make -C tests/binaries` でビルド。「1 syscall 1 テスト」原則 (`sys_*64.c`)。
- `tests/scripts/`: `run-all.sh` (全 230) / `run-fast.sh` (~27s) /
  `run-network.sh` (~3m) / `run-test.sh <name>` (1 件、SANDBOX_DIR で上書き可)。
- 現在 230 PASS / 0 FAIL。並列実行前提、テスト間で sandbox / /tmp を衝突させない。
  期待値は実 Linux カーネル仕様に合わせる。

# 実装の鉄則 (最頻出・違反すると silent 破壊)

詳細は [`docs/lessons.md`](../docs/lessons.md) に全カテゴリ。特に効くもの:

- **アドレスの int 切り詰め禁止** — `(int)bx` 等が残ると低位で動き高位 (PIE) で segfault。
- **syscall ABI: i386 と amd64 を混ぜない** — `sys_*` は i386 ABI 専用 (stack 経由)。
  amd64 からは必ず `amd64_<name>` を作り register 直接引数で書く。
- **stub の `return 0` は busy loop 誘発** — 成功と言える syscall 以外は ENOSYS、
  未対応 family は EAFNOSUPPORT、未知 ioctl は -ENOTTY を返す。
- **既に負の errno に `-` を付けると符号逆転** — `grep -nE "return -E[A-Z]+"` で監査。
- **CLOEXEC は per-fd 追跡** / at-syscall は dirfd 解決必須 (resolve_at_path)。
- **method-level synchronized は pthread 環境で deadlock の温床** — fd 単位保護に。
- **性能は当てずっぽう最適化が逆効果** — interleaved A/B で実測。直接 binary 起動で計測。
- **sandbox 環境差は core バグの fake 真因** — core 修正前に host と syscall 並走比較。
- **Windows: NIO Files.move/delete に切替** (File.renameTo/delete は NTFS で偶発失敗)。
  Ctrl-C は main 冒頭で `sun.misc.Signal.handle("INT", no-op)`。

# デバッグ env
`EMULIN_TRACE_RIP=N` / `EMULIN_TRACE_SH=1` / `EMULIN_TRACE_OPEN=1` /
`EMULIN_TRACE_EXEC=1` / `EMULIN_DEBUG_TTY=1` / `EMULIN_FORCE_NATIVE_TTY=1` /
`EMULIN_PROFILE_SYS=1` (syscall 別累積時間)。jstack で deadlock/spin 特定。

# 参照ドキュメント (毎回読み込まない詳細はこちら)
- [`docs/CHANGELOG.md`](../docs/CHANGELOG.md) — Phase 別 開発履歴 (これまで何をやったか)
- [`docs/lessons.md`](../docs/lessons.md) — バグ修正・実装の傾向 全カテゴリ詳細
- [`docs/build-and-dist.md`](../docs/build-and-dist.md) — 配布 zip 早見表 / INCLUDE_* / 0.4.0 release
- より細かい粒度は `git log` を参照
