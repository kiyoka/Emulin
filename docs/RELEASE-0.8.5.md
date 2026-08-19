# Emulin 0.8.5 Release Notes

## ダウンロード

| ファイル | 対象 | サイズ |
|---|---|---|
| `debian-emulin-0.8.5-windows-x64.zip` | Windows x64 | 約 274 MB |

**JRE を同梱しているので Java のインストールは不要**です。解凍して `emulin.bat` を
実行するだけで、Debian 13 (trixie) 相当の環境 (apt / dpkg / git / curl / python3 /
vim / emacs 等) が立ち上がります。

> 0.8.5 のビルド済み zip は **Windows 用のみ**公開しています。Linux / macOS では
> `PLATFORMS="linux-x64" dist/build-release.sh` 等でローカルビルドしてください。

導入手順は [README](https://github.com/kiyoka/Emulin/blob/v0.8.5/README.ja.md) を参照。
**Windows Hypervisor Platform (WHP) の有効化を強く推奨**します。

---

0.8.5 は **Remote Control を実運用して出てきた認証まわりの不具合**を潰す回です。
エミュレータ本体 (syscall / CPU / メモリ) の変更はありません。

---

## ハイライト

### 1. ★ 認証が切れると、再登録しても永久に直らなくなる (#944)

guest の Claude Code が認証切れを検知して `~/.claude/.credentials.json` の**トークンを
空にする**と、**host 側で再ログインして `setcred` し直しても guest はずっと Login expired**
のままになっていました。

原因は Emulin 側の保護でした。「ファイルが Emulin の placeholder でなければ、利用者が
guest 内で `claude auth login` した結果とみなして触らない」という判定が、**空ファイルにも
効いてしまい**、placeholder を作り直さなくなっていたためです。

守りたいのは**利用者が guest 内で login した本物のトークン**であって、空や壊れたファイルでは
ありません。**使える token が 1 つも無ければ作り直す**ようにしました。

★ 利用者から見ると「host 側は何度やり直しても正しいのに guest だけ直らない」という、
**原因にたどり着けない壊れ方**でした。

**併せて**: `setcred` の保存後に「**稼働中の Emulin には反映されません。再起動してください**」
と明示するようにしました。credential は **Emulin の起動時に一度だけ**読まれます。

### 2. 複数プロセスが同時に refresh すると片方がログアウトされる (#943)

Remote Control は guest 内で **bridge と worker の 2 プロセス**が動きます。両方が同時に
token の refresh を投げると、**同じ refresh token を 2 回提示する**ことになり、後から届いた
方は `invalid_grant` で弾かれます。弾かれた client は「ログインが切れた」と判断して
credential を捨てるので、上記 1. の状態に落ちていました。

Emulin は**実トークンを一手に握っている唯一の場所**なので、ここで仲介します。
**直近に回転していたら、その refresh 要求を上流へ投げず、現在の値で応答**するようにしました
(guest から見れば「refresh が成功して同じ placeholder が返った」だけ)。上流の回転の
無駄遣いも減ります。

> ★ 限界: この仲介は **1 つの Emulin プロセスの中**でのみ効きます。
> **Emulin を 2 つ同時に起動して同じ credential を使う**と、互いの回転は見えないので
> 防げません。同じアカウントのログインを複数の Emulin で共有しないでください。

### 3. host と guest のログインを干渉させない方法を README に明記 (#942)

`claude auth login` を `CLAUDE_CONFIG_DIR` 無しで実行すると、**いま使っている普段の
ログインが置き換わります**。OAuth の refresh token は使うたびに回転するため、同じ
credential を 2 か所で使うと**先に更新した方だけが生き残ります**。

README に、NG / OK のコマンドを並べて明記しました。

```bash
claude auth login                                     # 普段のログインを置き換える (NG)
CLAUDE_CONFIG_DIR=~/.claude-emulin claude auth login   # 別のログインを作る (OK)
```

**WSL2 でログインした場合**に `setcred` が WSL2 のホームを候補として出すこと (0.8.4 以降) も
併せて記載しています。

---

## 既知の制限

- **#740 (稀な凍結)** … 0.8.1 から引き続き未解決です。V8 の rwlock/condvar 待機者が
  プロセス内で起こしを失う事象で、原因未特定です。
- **`mincore` の backend 差** … software backend は匿名メモリを eager に確保するため、
  **未 touch のページも「常駐」と答えます** (native backend は Linux どおり 0)。
  修正には hot path にコストが乗るため、**既知の制約として修正しません**。

---

## アップグレード

zip を展開し直すだけです。`rootfs/` を作り直したくない場合は、
**`lib/emulin-*-all.jar` を差し替えるだけでも修正は反映されます**。

★ 認証の再登録は不要です。
