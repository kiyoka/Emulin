# Emulin 0.8.2 Release Notes

## ダウンロード

| ファイル | 対象 | サイズ |
|---|---|---|
| `debian-emulin-0.8.2-windows-x64.zip` | Windows x64 | 約 277 MB |

**JRE を同梱しているので Java のインストールは不要**です。解凍して `emulin.bat` を
実行するだけで、Debian 13 (trixie) 相当の環境 (apt / dpkg / git / curl / python3 /
vim / emacs 等) が立ち上がります。

> 0.8.2 のビルド済み zip は **Windows 用のみ**公開しています。Linux / macOS では
> `PLATFORMS="linux-x64" dist/build-release.sh` 等でローカルビルドしてください。

導入手順は [README](https://github.com/kiyoka/Emulin/blob/v0.8.2/README.ja.md) を参照。
**Windows Hypervisor Platform (WHP) の有効化を強く推奨**します。

---

0.8.2 は **0.8.1 の既知の不具合 #921 を潰す**回です。

0.8.1 では「credential サンドボックス経由で Codex を動かすと、チャットは返るのに
**コマンド実行を伴う依頼だけが永久に返らない**」という状態で出荷し、README に既知の
不具合として明記していました。0.8.2 でこれが直り、**Codex で実作業のセッションが
継続できる**ことを実機で確認しています。

真因は 1 つではなく **4 つ**でした。1 つ直すと次が出る、を繰り返した結果、
いずれも「エミュレータの土台の穴」で、Codex 以外にも影響し得るものでした。

### 実機での動作確認 (Windows 11 / WHP native backend)

| | |
|---|---|
| **OpenAI Codex** (npm の最新版 = 0.147.0) | ツール実行を伴う依頼が完走。**ブログ執筆セッションを継続できることを確認** |
| **Claude Code** | 0.8.1 から影響なし |

---

## ハイライト

### 1. Codex のコマンド実行が返らない (#921) — 真因 4 件

**① EPOLLET の EPOLLOUT が「full→空き」の遷移で再 arm されない**

Codex は tool 実行の要求を **474,198 byte の JSON を 1 回の write** で送ります。pipe の
buffer は 64KB なので部分書き込みになり、残りは EPOLLOUT (EPOLLET) を待って書きます。
Emulin は EPOLLOUT を boolean ラッチでしか解除しておらず、「buffer が full になった」ことを
観測したスキャンが無いとラッチが立ったままになり、**EPOLLOUT が二度と報告されません**。
受け手は残りを待ち続け、双方が停止していました。

★ EPOLLIN は #435 で「読んだら再 arm」に直っていたのに、**EPOLLOUT だけ古い実装のまま**でした。

**② `loadString` が 10,000 byte で無言 truncate / ③ native backend に同じ上限が重複**

Codex が生成する shell スクリプト (約 15KB) が 10,000 byte で切られ、
`bash: -c: line 129: unexpected EOF` で実行に失敗していました。しかも**同じ上限が
`Memory` と `NativeMemoryBackend` に別々に書かれており**、②を直しても実機 (WHP/KVM =
native backend) では切れ続けました。上限は Linux の `MAX_ARG_STRLEN` (128KB) に合わせ、
**定数を interface で共有**して二重管理をやめました。打ち切ったときは警告を出します。

**④ `kill(-pgid)` が呼び出し元自身を殺す**

`kill(2)` の `pid <= 0` (プロセスグループ宛 / ブロードキャスト) が、すべて
**「自分自身へ送信」**として実装されていました。子のプロセスグループを掃除する定番の
`kill(-pgid, SIGKILL)` が呼び出し元を道連れにし、Codex が `Killed` で落ちていました。

### 2. 能動 peek の同期漏れ (#923 / #926)

Emulin は「読めるか」を判定するために、**実際に 1 byte / 1 データグラムを先読み**します。
この先読みが socket / UDP の複数箇所に**独立して書かれ、同期を欠いていました**。

- **TCP (#923)**: poll / select の 4 箇所が無同期。host 単体の再現で、10 秒あたり
  順序不整合 57〜136 件・byte 消失・偽 EBADF 1896 件を観測。
- **UDP (#926)**: poll / epoll / `ioctl(FIONREAD)` の 3 箇所が無同期。UDP は
  **待っている側が起きられない**ぶん深刻で、blocking `recvfrom` で待つ相手から到着
  データグラムを横取りすると、次が来るまで永久に待ちます (DNS のように応答が 1 つだと
  timeout し、musl の resolver は `EAI_AGAIN` を返します)。

いずれも**先読みを 1 箇所に集約**し、guest が受信中は触らないようにしました。

### 3. テスト基盤: 古いビルドで走らせない (#924)

`run-all` は 9 本のスイートを並列起動し、その 1 本 `dist-smoke` が内部で `mvn package`
(= `target/classes` を書き換える) を呼んでいました。ソースを触った直後に走らせると
**実ビルドが他の 8 本を巻き添えにし、26 件の偽 FAIL** が出ます。

- `dist-smoke` を並列群から外して単独実行に
- **ソースより `target/classes` が古ければテストを実行せずに停止**するガードを新設
  (`run-all` / `run-fast` / `run-network`)。逃げ道はありますが黙っては通さず警告を出します

### 4. 診断の常設

同じ調査を繰り返せるように、今回役に立った計器を残しました。

| | |
|---|---|
| `EMULIN_TRACE_FILE=<path>` | 診断出力をファイルへ。**`emulin.bat` は Windows Terminal があると `wt.exe` で起動し直すため `2> file` が届きません**。TUI を壊さない利点もあります |
| `DBG_EXEC_FAIL` | `execve` が弾いた理由 (ENOENT / EACCES / ENOTDIR / ENOEXEC) |
| epoll-stuck ダンプの ET 抑制状態 | 「起こし漏れ」と「抑制」を 1 行で切り分け |
| `[write]` / `[kill]` / `[signal-death]` | 誰が誰に何をしたか (pid・プロセス名つき) |
| native backend の致命的縮退 | OOM-kill / worker crash とスタックトレース |

### 5. 回帰テスト 6 本を追加

| テスト | 修正前の挙動 (負のコントロール) |
|---|---|
| `epollet_out_pipe_dyn64` | 69632/409600 byte で EPOLLOUT が来ない |
| `argv_long_dyn64` | 20,000 byte の argv が切られる |
| `killpg_dyn64` | **親自身が SIGKILL される** |
| `udp_peek_steal_dyn64` | blocking `recvfrom` が受け取れず timeout |
| `sigchld_execfail_dyn64` | (元から PASS。子プロセス経路の対照として追加) |
| host 単体ハーネス (#923) | 順序不整合・byte 消失・偽 EBADF |

`run-all`: **313 PASS / 0 FAIL / 9 SKIP**。

---

## 既知の制限

- **#740 (稀な凍結)** … 0.8.1 から引き続き未解決です。V8 の rwlock/condvar 待機者が
  プロセス内で起こしを失う事象で、原因未特定です。
- **#926 (`getaddrinfo` の `EAI_AGAIN`)** … Codex の WebSocket 接続時にごく稀に
  名前解決が一時失敗します。Codex は再試行するので実害は小さいですが、**原因は未確定**です。
  同型の実在バグ (UDP の先読み横取り) は本リリースで直しましたが、症状の再現には
  至っていないため issue は開いたままにしています。
- **メモリプールの既定値** … `EMULIN_NATIVE_POOL_MB` は 1 プロセスあたりの guest 物理
  メモリで、WHP では commit を charge します。**大きくすれば良いわけではありません**
  (搭載メモリが控えめな機械では逆効果)。README に切り分け方を追記しました。

---

## アップグレード

zip を展開し直すだけです。`rootfs/` を作り直したくない場合は、**`lib/emulin-*-all.jar` を
差し替えるだけでも修正は反映されます** (今回の変更はすべてエミュレータ本体です)。

credential (`emulin.bat setcred`) の再登録は不要です。

★ **ホストと guest で同じ Codex アカウントを同時に使わないでください。** refresh token は
使用時にローテートされるため、後に動かした方だけが有効になり、もう片方は 401 になります。
