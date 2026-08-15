# Emulin 0.8.4 Release Notes

## ダウンロード

| ファイル | 対象 | サイズ |
|---|---|---|
| `debian-emulin-0.8.4-windows-x64.zip` | Windows x64 | 約 274 MB |

**JRE を同梱しているので Java のインストールは不要**です。解凍して `emulin.bat` を
実行するだけで、Debian 13 (trixie) 相当の環境 (apt / dpkg / git / curl / python3 /
vim / emacs 等) が立ち上がります。

> 0.8.4 のビルド済み zip は **Windows 用のみ**公開しています。Linux / macOS では
> `PLATFORMS="linux-x64" dist/build-release.sh` 等でローカルビルドしてください。

導入手順は [README](https://github.com/kiyoka/Emulin/blob/v0.8.4/README.ja.md) を参照。
**Windows Hypervisor Platform (WHP) の有効化を強く推奨**します。

---

0.8.4 は **0.8.3 で実際に詰まった手順の穴を塞ぐ**回です。エミュレータ本体の変更は
ありません (0.8.3 の jar と挙動は同じ)。

---

## ハイライト

### 1. `setcred` が WSL2 のログインを見つけられるようにした

Claude の認証は 0.8.3 で `claude auth login` (ブラウザ認証) に一本化しましたが、
**WSL2 側でログインすると Windows のホームからは見えません** (WSL2 のホームは別物)。
0.8.3 では UNC パスを手打ちする必要があり、実際にそこで詰まりました。

**見つかったログインから選ぶ**形にしました:

```
Found these Claude logins on this machine:
  [1] WSL2 Debian / kiyoka (.claude-emulin)  \\wsl.localhost\Debian\home\kiyoka\.claude-emulin\.credentials.json
  [2] WSL2 Debian / kiyoka (.claude)         ...
  [3] WSL2 Ubuntu-22.04 / kiyoka (.claude)   ...
  [0] type a path myself
```

★ **サンドボックス専用 (`.claude-emulin`) を先頭**に置いています。普段使いの `.claude` を
共有すると、OAuth の refresh 回転で**もう片方のセッションがログアウトされる**ためです。

### 2. リリース手順を「公開してから間違いに気づく」形から変えた (#939)

3 リリース連続で、公開した**後**に「手順が通らない」と気づいていました。

| リリース | 公開後に判明 |
|---|---|
| 0.8.1 | #919 launcher に `setcred` が無く README どおりの手順が失敗 |
| 0.8.2 | #932 非 ASCII の argv 破壊で `apt install` が失敗 |
| 0.8.3 | 上記の WSL2 の件 |

3 回とも「**テストは全部緑なのに利用者の手順が通らない**」形で、しかも**出荷物 (zip) を
対象に手順を通していれば公開前に落ちていた**ものでした。

- **公開を最後にする** — draft で上げ、**公開物を落としてきた zip** を検査し、
  実機で README どおりになぞってから公開する (`docs/release-checklist.md`)
- **`tests/scripts/release-verify.sh`** — 出荷 zip に対する「利用者の道筋」検査。
  各検査は過去の実害と 1 対 1 で対応させ、issue 番号を書いた
  (版の一貫性 #929 / launcher サブコマンド #919 / **非 ASCII の argv** #932 /
   `setcred` メニュー / 診断が画面に出ない #934 / (opt-in) `apt install` 完走)

★ **この 0.8.4 は、新しい手順で出した最初のリリースです。**

---

## 既知の制限

- **#740 (稀な凍結)** … 0.8.1 から引き続き未解決です。V8 の rwlock/condvar 待機者が
  プロセス内で起こしを失う事象で、原因未特定です。

---

## アップグレード

zip を展開し直すだけです。**エミュレータ本体は 0.8.3 と同じ**なので、0.8.3 で困っていない
場合は急いで更新する必要はありません (`setcred` を使うときだけ効きます)。
