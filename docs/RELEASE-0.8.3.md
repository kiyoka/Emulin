# Emulin 0.8.3 Release Notes

## ダウンロード

| ファイル | 対象 | サイズ |
|---|---|---|
| `debian-emulin-0.8.3-windows-x64.zip` | Windows x64 | 約 274 MB |

**JRE を同梱しているので Java のインストールは不要**です。解凍して `emulin.bat` を
実行するだけで、Debian 13 (trixie) 相当の環境 (apt / dpkg / git / curl / python3 /
vim / emacs 等) が立ち上がります。

> 0.8.3 のビルド済み zip は **Windows 用のみ**公開しています。Linux / macOS では
> `PLATFORMS="linux-x64" dist/build-release.sh` 等でローカルビルドしてください。

導入手順は [README](https://github.com/kiyoka/Emulin/blob/v0.8.3/README.ja.md) を参照。
**Windows Hypervisor Platform (WHP) の有効化を強く推奨**します。

---

0.8.3 は **credential サンドボックスの穴を 1 つ塞ぎ**、**スマホから guest を操作できる**
ようにする回です。

---

## ハイライト

### 1. ★ token 応答が chunked だと実トークンが guest に漏れる (#935)

**credential サンドボックス (#401) の不変条件が破れる不具合を実機で発見し、修正しました。**

OAuth の refresh は、guest が placeholder を送り → Emulin が wire 上で実トークンに差し替え →
応答の新しいトークンを **host 側に取り込み、guest には placeholder を返す**、という往復で
成り立っています。ところが応答を処理していたのは **`Content-Length` のある応答だけ**で、
**chunked の応答は素通し**していました。素通しされると、**回転後の実トークンがそのまま
guest のファイル (`~/.claude/.credentials.json` 等) に保存されます**。

★ **症状が「正常に動く」**のが最悪でした。トークンが本物なので guest も host も何も
文句を言いません。手掛かりは診断モードでしか出ない 1 行だけでした。

- **chunked を読み切って回転**し、`Content-Length` に付け替えて返す
- credential を載せた要求は **`Accept-Encoding` を落として平文で返させる** (gzip 対策)
- それでも回転できない応答は **502 で遮断 (fail closed)**。漏らして動くより、止めて気付ける
- 遮断したら**終了時サマリで最優先に表示**する (「host の credential も無効なので再ログインが要る」まで)

影響: 0.8.2 以前で **OAuth の refresh を伴う運用** (Codex の ChatGPT サブスクリプション認証など)
をしていた場合、応答が chunked であれば実トークンが guest 側に書き込まれ得ます。心当たりが
あれば、guest の `~/.codex/auth.json` / `~/.claude/.credentials.json` を確認し、
**再ログイン + `setcred` のやり直し**をおすすめします (placeholder には `emph01` が入っています)。

### 2. ★ 仕様変更: Claude の認証を**ブラウザ認証 (OAuth) に一本化** (#935)

**`claude setup-token` の長期トークンは廃止しました。** `emulin.bat setcred` の
「Claude (Pro/Max subscription)」は、これからは `claude auth login` の
**access / refresh 2 本組**を取り込みます。

廃止した理由:

- 長期トークンは **仕様として inference 限定**で、Remote Control 等は claude 自身が拒否する
- 選択肢が 2 つあると「どちらを選ぶべきか」で迷わせる。**片方が劣化版なら残す意味が無い**

> 既に `CLAUDE_CODE_OAUTH_TOKEN` を登録している場合、**推論はそのまま動きます**が、
> 起動時に移行の案内を出します。ウィザードからは選べなくなりました。
>
> ```bash
> CLAUDE_CONFIG_DIR=~/.claude-emulin  claude auth login   # ホスト側
> ```
> ```bat
> emulin.bat setcred      rem [1] Claude (Pro/Max subscription)
> ```

- `setcred` に取り込み経路を追加 (`CLAUDE_CONFIG_DIR` 対応)
- guest には placeholder の `~/.claude/.credentials.json` を起動ごとに配置
- access token は数時間で切れるので、**失効時は wire 上で refresh を回転**させます
- ★ full-scope を登録しているときは `CLAUDE_CODE_OAUTH_TOKEN` を **guest env に出しません**
  (env があると claude はそちらを優先し、inference 限定の経路に落ちるため)

> **1 クライアントにつき 1 ログイン。** OAuth の refresh token は使うたびに回転するので、
> 他の Claude Code セッションと credential を共有すると、先に更新した方だけが生き残ります。
> サンドボックス用は `CLAUDE_CONFIG_DIR=~/.claude-emulin claude auth login` で別に作ってください。

### 3. スマホから guest のセッションを操作する (#934)

guest の中で `claude remote-control` を実行し、表示される URL / QR から入ると、
**iPhone の Claude アプリ (Code タブ) や claude.ai/code から guest の中で作業**できます。
実機で確認しました:

```
uname -a と pwd を実行して
→ Linux bedroom 0.8.3 Emulin 0.8.3 x86_64 GNU/Linux
  /home/kiyoka
```

**実トークンはホストに残ったまま**です (guest が持つのは RC のセッション固有トークンのみ)。
端末を紛失しても鍵は漏れません。

★ #895 (Tailscale + SSH で iPhone から使う) は「フルスクリーン TUI と電話の画面幅」という
構造的な理由で断念しましたが、こちらは折り返し・キーボード・通知をアプリ側が持つので実用になります。

使い方と注意点 (PATH / workspace trust / environment ID は起動ごとに変わる 等) は
README の「Remote Control」節を参照してください。

### 4. 診断が TUI を埋めない (#934)

`EMULIN_TRACE_FILE` は **TUI を壊さずに診断を採る**ために 0.8.2 で入れた仕組みですが、
credential サンドボックス側の 54 箇所が `System.err` 直書きのままで、**5 秒ごとに画面へ
出ていました**。すべてトレースファイルへ寄せました。

| | 画面 | trace file |
|---|---|---|
| 修正前 | 56 行 | 283 行 |
| 修正後 | **0 行** | 2860 行 |

あわせて、**実トークンと placeholder を区別できる**診断にしました (どちらも `sk-ant-oat01-`
で始まるため、従来の「接頭辞が一致」では判別できませんでした)。値は出さず、marker の有無と
「host の実キーと一致するか」だけを出します。この計器のおかげで「アカウントのトークンは
漏れていない」と言い切れました。

---

## 既知の制限

- **#740 (稀な凍結)** … 0.8.1 から引き続き未解決です。V8 の rwlock/condvar 待機者が
  プロセス内で起こしを失う事象で、原因未特定です。

---

## アップグレード

zip を展開し直すだけです。`rootfs/` を作り直したくない場合は、**`lib/emulin-*-all.jar` を
差し替えるだけでも修正は反映されます**。

★ 0.8.2 以前で OAuth の refresh を伴う運用をしていた場合は、上記 1. のとおり
**guest 側に実トークンが残っていないか確認**してください。
