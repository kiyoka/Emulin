# Emulin 0.9.1 Release Notes

## ダウンロード

| ファイル | 対象 | サイズ |
|---|---|---|
| `debian-emulin-0.9.1-windows-x64.zip` | Windows x64 | 約 287 MB |

SHA256: `55ae17735e6f9a6da6386ea270d9345af2bb7f6203432499a7ef90d71f9fcfc4`

★ この zip は **draft で上げ、落として `tests/scripts/release-verify.sh` と実機での
README 通し確認を経てから公開**しています。

**JRE を同梱しているので Java のインストールは不要**です。解凍して
**`emulin-app.bat` をダブルクリック**すればランチャーが開きます。

> 0.9.1 のビルド済み zip は **Windows 用のみ**公開しています。Linux / macOS では
> `PLATFORMS="linux-x64" dist/build-release.sh` 等でローカルビルドしてください。

導入手順は [README](https://github.com/kiyoka/Emulin/blob/v0.9.1/README.ja.md) を参照。

---

0.9.1 は **0.9.0 を実際に使って出てきた不具合**を潰す回です。ランチャー (0.9.0 の目玉)
そのものの変更はありません。

★ 5 件のうち **3 件は「実 Linux と挙動が違う」もの**で、いずれも
「**実 Linux では動くのに Emulin だけ失敗する**」という、原因に辿り着きにくい形でした。

---

## ハイライト

### 1. ★ 並列実行で SIGCHLD ハンドラが呼ばれないことがある (#962)

子プロセスが終了したとき、`wait4` は終了ステータスを正しく返すのに
**SIGCHLD ハンドラが 1 度も呼ばれない**ことがありました (負荷が高いときに 8-10%)。

原因は「子をゾンビにする」順序でした。**先に「終了が観測できる」状態にしてから**
SIGCHLD を積んでいたため、その間に親が `wait4` で結果を取り、ハンドラを実行しないまま
先へ進んでしまいます。実 Linux ではゾンビ化と SIGCHLD の生成は**不可分**で、
「wait4 で終了が取れたのに SIGCHLD が来ていない」状態は存在しません。同じ順序にしました。

> ★ あわせて、**無視しているシグナルで `wait4` / `waitid` が中断される** (spurious EINTR)
> 問題も直しました。実 Linux は無視/ブロック中のシグナルで syscall を中断しません。
> 同じ誤りは `FUTEX_WAIT` では既に直っており、**wait 系だけが残っていました**。

### 2. `/proc/self/fd/N` が名前を持たない fd で失敗する (#984 / #1003)

pipe / eventfd / epoll のような**名前を持たない fd** について、
`/proc/self/fd/N` の `stat` / `statx` / `readlink` が失敗していました
(片方は内部エラーが `EFAULT` に化ける形)。

実 Linux で測って合わせました:

| fd | `fstat` の型 | `readlink` |
|---|---|---|
| pipe | **FIFO** | `pipe:[<ino>]` |
| eventfd | **型ビット無し** | `anon_inode:[eventfd]` |
| epoll | 同上 | `anon_inode:[eventpoll]` |

★ **pipe はこれまで character device として返していました。** guest から見ると
「pipe が tty に見える」状態で、`/dev/stdin` の種別を見て分岐するプログラムに影響します。

> `/proc/self/fd` は Bun / node 系で普通に使われます (0.9.0 で直した #982 も、claude が
> `mkdir("/proc/self/fd/N/...")` を使ったのが発端でした)。

### 3. 認証が切れたあとの復旧が分かりにくい (#1002)

guest の claude が `Login expired` になったとき、**自然に導かれる操作**である
「**Set up credentials から取り込み直す**」は、実は**必ず失敗します**。

refresh token は使うたびに回転し、新しい値は Emulin 側にだけ保存されるため、
取り込み元 (`~/.claude-emulin`) は**最初のブラウザ認証のまま古くなります**。
取り込み直すと**生きている値を消費済みの値で上書き**することになります。

0.9.1 では、取り込み元が古い場合に画面で警告します:

```
[Emulin already holds a NEWER token for this account (saved 17 h ago). ...
 importing it would REPLACE a working token with a dead one.
 Log in again on the host first, then import]
```

正しい復旧手順は、host で `CLAUDE_CONFIG_DIR=~/.claude-emulin claude auth login` を
やり直してから取り込むことです。

### 4. 診断メッセージを英語に統一 (#969)

ランチャーのログ欄で日本語と英語が混在していたのを揃えました (37 箇所)。

★ このとき、**内容が既に嘘になっていた警告文**が見つかりました。「別の Emulin を
起動すると認証が切れます」という警告は、0.9.0 の #955 修正で**成立しなくなっていました**。
現在の実害 (同じ rootfs への同時書き込みによる apt/dpkg の DB 破損) に書き換えています。

---

## 検査

回帰テストは **315 → 399 本**になりました。新しく入った道具:

- **`tests/scripts/flake-rate.sh`** … テストの**再現率を測る**。「flake」を判断ではなく
  測定の対象にするためのもので、#962 の追跡はここから始めました
- **`tests/scripts/message-lang-check.sh`** … 利用者向けメッセージに日本語が混ざらないか
- **`sigchld-order-smoke.sh`** … ★ 8% の間欠 FAIL を**100% 決定的に落ちる**形にした
  (診断スイッチで窓を人為的に広げる)。8% のままでは回帰しても「たまに赤い」で片付きます
- **`sys_readlink_pty64`** … pty の `readlink` / `fstat` / `statx` と inode 突合を
  **実 Linux をオラクルにして**固定します
- **`test-registration-check.sh`** … ★ **検査が runner に登録されていることを検査**します

★ 本数が大きく増えたのは、**登録されておらず一度も走っていなかった検査が 30 件あった**
ためです (ssh 軸は 4 本とも未登録でした)。公開前の実機確認でそれが露見し、
「登録漏れ自体を検査する」ところまで直しました。**「テストが全部緑」が
「テストが呼ばれた」を意味していなかった**わけで、本数より重い発見でした。

---

## 既知の制限

- **#740 (稀な凍結)** … 0.8.1 から引き続き未解決です。V8 の rwlock/condvar 待機者が
  プロセス内で起こしを失う事象で、原因未特定です。
- **`mincore` の backend 差** … software backend は匿名メモリを eager に確保するため、
  **未 touch のページも「常駐」と答えます** (native backend は Linux どおり 0)。
  修正には hot path にコストが乗るため、**既知の制約として修正しません**。

---

## アップグレード

zip を展開し直してください。`rootfs/` を作り直したくない場合は、
**`lib/emulin-*-all.jar` を差し替えるだけでも修正は反映されます**
(0.9.1 は `emulin.bat` を変更していません)。

★ 認証の再登録は不要です。
