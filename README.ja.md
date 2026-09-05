# Emulin

[English](README.md) | **日本語**

**Java で動く 32/64-bit Linux ELF エミュレータ**

GNU General Public License v2 (詳細は `COPYING` を参照)

---

## 概要

Emulin は、Linux x86 (32-bit) / x86-64 (64-bit) ELF バイナリを Java で実行する
エミュレータです。pure Java で動作するため、Windows / macOS で Linux バイナリを動かせます。

実機 Linux binary (git / curl / openssl / Python 3.12 / vim 9.1 / emacs-nox /
GNU coreutils 等) を動かせます。

Windows では **Windows Hypervisor Platform (WHP)**、Linux では **KVM** を使った
ネイティブ実行 backend にも対応しており、利用できる環境では guest を実 vCPU で実行して
大幅に高速化します (利用できない環境では pure Java 実行に自動で fallback)。

## まず動かす

[Releases](https://github.com/kiyoka/Emulin/releases) から配布 zip を取得
(または `dist/build-release.sh` でビルド)し、任意の場所に解凍します。JRE 同梱なので
**Java のインストールは不要**です。

> 0.9.0 時点で、ビルド済みの配布 zip は **Windows 用のみ**公開しています
> (`debian-emulin-<version>-windows-x64.zip`)。Linux / macOS では
> `PLATFORMS="linux-x64" dist/build-release.sh` 等でローカルビルドしてください。

## 特徴

- 全て Java で記述 (pure Java、JNI 無し)
- 32-bit ELF (i386) と 64-bit ELF (x86-64) の両方を実行可能
- 動的リンクの実機 binary を実行可能 (PIE / ld.so / libc / pthread 対応)
- **Debian 13 (trixie) base 相当の bundle + `apt` / `dpkg`** — emulin 上で
  `apt-get install` / `dpkg -i` によりパッケージを GPG 署名検証込みで追加可能
  ([Debian パッケージの追加](#debian-パッケージの追加-apt--dpkg))
- AES-NI / PCLMULQDQ 命令を完全実装 (FIPS-197 host 一致)
- pthread 完全対応 (clone+futex / per-thread signal mask / mutex 競合)
- TLS 1.3 (gnutls 経由、cert verify 含む) 完全動作
- AF_INET6 (IPv6) socket 対応 — client TCP / UDP + server (accept4)、AF_UNIX も対応
- JLine 3 採用で Linux/macOS/Windows 共通の raw mode / Ctrl-C / SIGWINCH 対応
- **Windows Terminal で bash・vim・emacs の対話編集が動作**
- **basic-block JIT 翻訳器 (オプション)**: `EMULIN_USE_JIT=1` で x86-64 命令を
  Java bytecode に翻訳。AES-NI / PCLMULQDQ も対応し HTTPS で -13〜14% 高速化 (既定 off)
- **ネイティブ実行 backend (Hyper-V WHP / KVM)**: guest を実 vCPU で実行し syscall だけ
  emulin にトラップ。compute 律速で ~200x 高速化、software と byte 一致
  ([ネイティブ実行](#ネイティブ実行で高速化-hyper-v--kvm))
- **SSH サーバ対応**: ランチャーの **Start** ボタン (または `emulin sshd`) で
  OpenSSH sshd を起動し、外部 SSH クライアントから接続
  ([SSH サーバとして使う](#ssh-サーバとして使う))
- **AI コーディングエージェント**: Claude Code (現行の Bun 版) と Codex が
  対話コーディングまで動作
  ([AI コーディングエージェントを動かす](#ai-コーディングエージェントを動かす-claude-code--codex))

## 動作する実機 binary (例)

- **GNU coreutils 30+** (cat / ls / cp / mv / sort / find / grep 等)
- **bash 5.2 + line edit** (history / cursor / Tab、JLine raw mode 経由)
- **vim 9.1** — `vim -e -s` ex mode + 対話モード編集 (insert / `:wq`)
- **emacs-nox 29.3** (対話編集)
- **Python 3.12 stdlib** (re / json / collections / enum / functools /
  math / datetime 等)
- **OpenSSL 3.0.13** (TLS 1.3、AES-GCM、HTTPS handshake)
- **curl / wget HTTPS** (HTTP/1.1 / HTTP/2、multi-site: github / cloudflare
  / google / iana / raw.githubusercontent 等)
- **git**: init / add / commit / log / status / diff / clone
  (git:// / file:// / https:// 全対応、`--depth` / templates / hardlinks 含む)
- **less 643** (vt100 keybind、SIGWINCH 対応)
- **Claude Code / Codex** — 対話 AI コーディング
  ([AI コーディングエージェントを動かす](#ai-コーディングエージェントを動かす-claude-code--codex))

## 動作環境

| 項目 | 内容 |
|------|------|
| JDK / JRE | **25 以降** (OpenJDK 25 LTS で開発・テスト、Java FFM 採用のため #221) |
| OS | Linux (主) / Windows 11 Home 以上 / macOS |

## クイックスタート

### Windows で使い始める (Java 不要)

JRE (Microsoft Build of OpenJDK 25) を同梱しているので、**Java を別途インストール
する必要はありません**。

1. **Windows Hypervisor Platform (WHP) を有効化**(初回のみ・推奨)
   emulin は WHP が有効だと guest を実 vCPU で実行して大幅に高速化します。ほぼ
   すべての用途でこちらを推奨します(WHP が無くても pure Java で動きますが低速です)。
   **管理者権限の PowerShell** で:
   ```powershell
   dism /Online /Enable-Feature /FeatureName:HypervisorPlatform /All
   ```
   または「コントロールパネル → プログラム → Windows の機能の有効化または無効化」で
   **「Windows ハイパーバイザー プラットフォーム」**にチェックを入れます。
   **有効化後は Windows を再起動**してください(Windows 11 Home 以上で利用可、WSL2 と共存可)。

2. **配布 zip をダウンロード**
   [Releases](https://github.com/kiyoka/Emulin/releases) から
   `debian-emulin-0.9.0-windows-x64.zip` を取得します(ローカルでビルドする場合は
   `dist/build-release.sh`)。Debian 13 (trixie) ベース + `apt` / `dpkg` に
   git / curl / wget / openssl / python3 / vim / emacs 等を同梱した bundle です。

3. **任意の場所に解凍**
   例: `C:\Tools\debian-emulin-0.9.0-windows\`(パスに日本語・空白を含めても
   動きますが、できるだけ ASCII のパスを推奨)。

4. **ランチャーを開く**(推奨)
   解凍ディレクトリで `emulin-app.bat` をダブルクリック、または
   cmd / Windows Terminal で:
   ```cmd
   cd C:\Tools\debian-emulin-0.9.0-windows
   emulin-app.bat
   ```
   **Open terminal** で bash が起動し、他のボタンでエージェントの導入と
   credential の登録ができます([ランチャーを使う](#ランチャーを使う-推奨090))。
   (初回起動時は同梱 rootfs を展開するため少し時間がかかります。)

<details>
<summary><code>emulin.bat</code> でシェルに直行する場合と、bash が立ち上がる前に出る 2 つの案内</summary>

```cmd
cd C:\Tools\debian-emulin-0.9.0-windows
emulin.bat
```

いきなり `#` プロンプトにはなりません:

- **一般ユーザーの作成(初回のみ)** — `emulin.bat` は root に加えて非 root の
  一般ユーザーも用意します(mozc IME など一部アプリは実 Linux と同じく root では
  動かないため)。初回はユーザー名を尋ねられます:
  ```
  [emulin] First-time setup: create a regular (non-root) user account.
  Username to create (uid 1000, blank to skip):
  ```
  名前を入力すると **uid 1000 / home `/home/<名前>` / shell `/bin/bash`** の
  ユーザーが作成され、`/etc/emulin-user` に記録されます(2 回目以降この作成は
  スキップされます)。空のまま Enter するとスキップし、root のみになります。

- **ログインユーザーの選択(毎回)** — 一般ユーザーがあると、起動のたびに
  root かそのユーザーかを選びます:
  ```
  [emulin] Log in as:  [1] root   [2] <ユーザー名>
  Choice (1/2, default 1):
  ```
  `1` または空 Enter で **root**(HOME=`/root`、apt などのシステム作業向け)、
  `2` で **そのユーザー**(uid 1000、HOME=`/home/<名前>`、日常作業・デスクトップ
  アプリ向け)。あらかじめ `set EMULIN_LOGIN=user` を設定しておくと、このメニューを
  省いて常に一般ユーザーで起動できます。

選択が終わると bash が起動します:
```
# echo hello
hello
# uname -m
x86_64
# exit
```

</details>

5. **1 コマンド実行モード / 実機 binary の実行**
   `debian-emulin-0.9.0-windows` には git / curl / openssl / python3 等が同梱
   されているので、解凍直後から実行できます:
   ```cmd
   emulin.bat ls /
   emulin.bat /usr/bin/git --version
   emulin.bat /usr/bin/git clone --depth=1 https://github.com/octocat/Hello-World.git /tmp/cloned
   ```

`apt` でのパッケージ追加は [Debian パッケージの追加](#debian-パッケージの追加-apt--dpkg) を参照。

> **メモ**:
> - 同梱 JRE は Microsoft Build of OpenJDK 25 (GPLv2 + Classpath Exception)。詳細は同梱の `NOTICE.txt` 参照。
> - `emulin.bat` は内部で同梱 JRE (`jre\bin\java.exe`) を呼び出すため、PATH に Java が無くても動作します。
> - 引数なしの `emulin.bat` は Windows Terminal で対話 bash を起動します(`set EMULIN_NO_WT=1` で通常コンソール)。

## Debian パッケージの追加 (apt / dpkg)

`debian-emulin-0.9.0-windows-x64.zip` は **Debian 13 (trixie) base 相当**の
rootfs を土台にしており、`apt` / `dpkg` と apt の前提環境
(`/etc/apt/sources.list.d/debian.sources` + `debian-archive-keyring` 署名鍵) を
同梱しています。そのため emulin 上で `apt-get` によるパッケージ追加が
**GPG 署名検証込みで** end-to-end 動作します (deb.debian.org の trixie main /
trixie-security)。

```cmd
rem パッケージインデックスの取得
emulin.bat /usr/bin/apt-get update <nul

rem パッケージの追加 (例: GNU hello)
emulin.bat /usr/bin/apt-get install -y hello <nul

rem 追加した binary の実行 / 確認
emulin.bat /usr/bin/hello
emulin.bat /usr/bin/dpkg-query -W hello
```

Linux / macOS で bundle をローカルビルドした場合は `./emulin.sh /usr/bin/apt-get ...`
(標準入力を塞ぐのは `</dev/null`)、ソースからの直接実行は
`java -XX:-DontCompileHugeMethods -jar emulin-*-all.jar <rootfs> /usr/bin/apt-get ...`
に読み替えてください。`apt` 入りのローカル rootfs は
`dist/build-debian-base.sh <rootfs>` でも作れます。`dpkg -i <pkg>.deb` による
ローカル install も同様に動作します。

> **★ `-y` と標準入力の遮断を付ける。** `apt-get` は標準入力 (fd 0) を読みます。
> 端末を持たないスクリプト経由などで stdin が塞がっていると、確認プロンプトで
> 待ち続けて「ハング」したように見えます。非対話で使うときは **`-y`** と、
> Windows なら **`<nul`**、Linux / macOS なら **`</dev/null`** を付けてください
> — 上の例が全行これを付けているのはそのためです (端末から対話的に実行する場合は
> 不要です)。

## SSH サーバとして使う

emulin 上で OpenSSH **sshd** を起動し、外部の SSH クライアント (OpenSSH `ssh` /
Tera Term 等) から接続して bash / vim / emacs を対話操作できます。本物の SSH
クライアント経由なので、Windows コンソールのキー制約 (Ctrl+Space 等) を回避できます。

> **デーモンは自動起動しません。** emulin は init/systemd を持たない単一プロセス
> 起動のエミュレータです。sshd はユーザが明示的に起動します — ランチャーの
> **Start** ボタン、または `emulin sshd` です。

> **★ 待ち受けは同一 LAN から到達可能です。** emulin は guest が `bind()` で
> 指定したアドレスを使わず、**常に全インターフェース (`0.0.0.0`) で待ち受けます**。
> そのため sshd 自身が `Server listening on 127.0.0.1 port 2222.` と表示し、
> `sshd_config` に `ListenAddress 127.0.0.1` と書いてあっても、**loopback 限定には
> なりません** (公開鍵認証のみなので、鍵を登録していないクライアントは入れません)。
> 外部からの到達を塞ぎたい場合は、Windows のファイアウォールでポート 2222 への
> 受信を制限してください。

### ランチャーから操作する (推奨・0.9.0 以降)

ランチャー最下段の 1 行 — `port` 入力欄・**Start**・**Add public key** —
がこの機能のすべてです。

#### 1. `Add public key` — クライアントの公開鍵を登録する

ここの sshd は**公開鍵認証のみ**なので、まずこれを行います。ボタンを押すと
host 側で見つかった公開鍵が、種別・`SHA256:` フィンガープリント・コメント・
**どのファイルから見つけたか**とともに一覧になります:

```
[installed] ssh-ed25519  SHA256:xxxx...  you@windows  - C:\Users\you\.ssh\id_ed25519.pub
[   new    ] ssh-ed25519  SHA256:yyyy...  you@wsl      - \\wsl.localhost\Debian\home\you\.ssh\id_ed25519.pub
```

探索先は `%USERPROFILE%\.ssh` **と、WSL の全ディストリの** `~/.ssh`
(`\\wsl.localhost\<distro>\home\<user>\.ssh` と `\root\.ssh`) の両方です。
`ssh` を打つのは WSL 側であることが多いので、**使いたい鍵は WSL 側**という
ことがよくあります。行を選んで **Add** を押します。一覧に出ない場所の鍵は
**Choose a file...** から選べます (同じ検査を通ります)。

- 書き込み先は `/root/.ssh/authorized_keys` **と**非 root ユーザーの
  `/home/<ユーザー>/.ssh/authorized_keys` の**両方**です。`ssh root@` と
  `ssh <ユーザー>@` のどちらでも入れます。判定はフィンガープリントなので、
  登録済みの鍵に **Add** を押しても何も起きず、既存の行も消しません。
- `[installed]` は**その全部に入っている**という意味です。root にだけ入った鍵は
  意図的に new のままにしています — そうしないと画面が「登録済み」と言っている
  のに `ssh <ユーザー>@` が拒否され続け、画面から手がかりが得られません。
- **秘密鍵は拒否します。** 判定はファイル名ではなく**中身**
  (`-----BEGIN` / `PRIVATE KEY`) で行うので、`*.pub` という名前の秘密鍵も
  弾きます。秘密鍵を guest に置くと
  [API キーを guest に置かない](#api-キーを-guest-に置かない) が根本から崩れます。

登録すると `SSH server` の欄に、登録済みの鍵のフィンガープリントが出ます。
**思っている鍵が本当に guest に入っているか**は、ここで確認します:

```
[stopped]
      public key: SHA256:xxxx...
```

#### 2. `Start` — sshd を起動する

![ランチャーの SSH server の行: port 入力欄・Start ボタン・Add public key ボタン](docs/images/launcher-sshd-row.png)

2222 以外を使うなら port を書き換えて **Start** を押します。**押す前に**、
動かない理由が `SSH server` の欄に出ます:

```
[stopped]
      ★ port 2222 is already in use.  (another Emulin is using it: pid 12345)
      ★ this build has no sshd (you need a zip built with INCLUDE_SSHD=1)
      ★ no public key: put your SSH client's public key in ...\rootfs\root\.ssh\authorized_keys (sshd will start without it, but nobody can connect)
      public key: none (use "Add public key")
```

port の判定は**実際に bind してみる**ので、Emulin 以外のプロセスが掴んでいる
場合も捕まえます。そして問題があるときは **Start を押しても sshd は起動しません** —
理由をログに書いて止まります:

```
★ no public key: put your SSH client's public key in ...\rootfs\root\.ssh\authorized_keys (sshd will start without it, but nobody can connect)
  Add a public key first, then press the button again.
```

鍵が無いときに起動を拒むのは意図的です。鍵が無くても sshd は起動でき、その場合は
すべてのログインを黙って弾くだけなので、何時間か経ってから「クライアント側の
問題」に見えてしまいます。

起動すると同じ欄に、**そのまま貼れる接続コマンド**が出ます。WSL2 から入るための
1 行も含みます:

```
[running] 127.0.0.1:2222
      ssh -p 2222 root@127.0.0.1
      ssh -p 2222 <ユーザー>@127.0.0.1
      ssh -p 2222 <ユーザー>@172.25.144.1     (from WSL)
```

> **★ WSL2 からは `127.0.0.1` では届きません。** WSL2 は独立したネットワークを
> 持つので、その `127.0.0.1` は Windows のものではありません。ゲートウェイの
> アドレスが必要で、だからランチャーがこの行を出しています。

ボタンは **Stop** に変わります。sshd は**別プロセス**なので、ランチャーの窓を
閉じても動き続けます。ランチャーを開き直すと**動いているものを見つけて** Stop を
表示します (port 台帳と生存インスタンス台帳の**両方**が一致したときだけ自分の
sshd と見なすので、2222 を掴んでいる無関係なプロセスを止めに行くことはありません)。

> **★ sshd の起動後に鍵を足したら、起動し直してください。** 非 root ユーザーの
> `authorized_keys` を root 側から更新し、所有者・パーミッションを直すのは
> **起動処理の中**です。**sshd はパーミッションが不正な鍵を黙って拒否する**ので
> (StrictModes)、**Stop** → **Start** を押し直します。

<details>
<summary>手動で行う場合 (<code>emulin sshd</code>)</summary>

```bash
# 1. sshd 入りの bundle が必要 (release/full bundle、または INCLUDE_SSHD=1 で build)
# 2. 接続する SSH クライアントの公開鍵を authorized_keys に登録
#    (bundle 内 rootfs/root/.ssh/authorized_keys)
cat ~/.ssh/id_ed25519.pub >> <bundle>/rootfs/root/.ssh/authorized_keys

# 3. sshd を起動 (port 省略時は 2222、user=root、publickey 認証)
emulin.bat sshd             # または: emulin.bat sshd 2222   (Linux / macOS は ./emulin.sh sshd)

# 4. 別の端末から接続
ssh -p 2222 root@127.0.0.1
#   Tera Term: Host=localhost / TCP port=2222 / User=root / 認証=publickey
```

停止は Ctrl-C です。

WSL2 や同じネットワークの別マシンからは、`127.0.0.1` ではなく Windows 側の
アドレスを使います:

```bash
# WSL2 から (172.25.144.1 は Windows 側 = WSL2 のゲートウェイ。ip route で確認)
ssh -p 2222 <ユーザー>@172.25.144.1
```

**非 root ユーザー (uid 1000) でも接続する。** sshd の公開鍵認証は
**ユーザーごと**なので、`/root/.ssh/authorized_keys` だけでは root にしか
ログインできません。非 root ユーザーには
`/home/<ユーザー>/.ssh/authorized_keys` が別途必要です (claude など root で
動かせないものはこちらで使います)。

`emulin.bat sshd` はこれを自動で行います — 起動時に root の
`authorized_keys` をユーザー側へコピーし、`chmod 700` (ディレクトリ) /
`chmod 600` (鍵ファイル) / `chown 1000:1000` まで設定します。起動時に接続先が
両方表示されます:

```
[emulin sshd]   connect as root: ssh -p 2222 root@127.0.0.1
[emulin sshd]   connect as user: ssh -p 2222 <ユーザー>@127.0.0.1
```

> **★ 鍵の登録は sshd を起動する前に行ってください。** コピーは sshd 起動時に
> 1 度だけ走るので、起動後に `/root/.ssh/authorized_keys` へ鍵を足しても、
> ユーザー側には次回起動まで反映されません。

ユーザー側に**別の鍵**を使いたい場合は手動で置きます。**パーミッションと所有者を
正しくしないと sshd が黙って認証を拒否します** (StrictModes):

```bash
# guest 内で root として
u=<ユーザー>
mkdir -p /home/$u/.ssh
cat /path/to/id_ed25519.pub >> /home/$u/.ssh/authorized_keys
chmod 700 /home/$u /home/$u/.ssh
chmod 600 /home/$u/.ssh/authorized_keys
chown -R 1000:1000 /home/$u
```

</details>

[API キーを guest に置かない](#api-キーを-guest-に置かない) のプレースホルダは
`~/.ssh/environment` 経由で SSH セッションに渡され、これも root と非 root
ユーザーの**両方**に書かれます。したがって非 root で ssh ログインしても
`claude` / `codex` はそのまま credential を使えます。

ホスト鍵は起動時に自動で `chmod 600` されます。host の環境変数は guest に
引き継がれます (issue #228)。

## API キーを guest に置かない

AI コーディングエージェントは**任意のコードを実行します**。本物の API キーを
guest 内に置けば、エージェント自身も、エージェントが起動した何かも、それを読めます。
0.8.0 はこれを構造的に解決します — guest には**プレースホルダだけ**を置き、
実際のキーは host 側にとどめます。

```
  guest (サンドボックス)          host
  claude / codex                 ~/.emulin/credentials.json  (実キー)
    ANTHROPIC_API_KEY      TLS         |
      = sk-ant-emph01-...  ------->  MITM 中継が通信の瞬間だけ
                                     プレースホルダを実キーに置換
                                             |
                                             v  api.anthropic.com
```

guest 内で環境変数や設定ファイルを読んでも**本物のキーは出てきません**。
横取りするのは credential の送り先への TLS 接続だけで、それ以外は素通しです。

> **★ プレースホルダは Emulin の起動ごとに変わります。**
> 設定ファイルにリテラルで書き写すと、次に起動したとき 401 になります。
> ツール側の設定は環境変数を**その場で読む**形にしてください。
> 例 (Emacs): `(setenv "SUMIBI_AI_API_KEY" (getenv "OPENAI_API_KEY"))`

host 側への登録は、ランチャー (`emulin-app.bat`) の **Set up credentials** 画面、
または CLI の対話ウィザードで行います。

```bat
emulin.bat setcred
```

Claude / OpenAI / Gemini / **GitHub** に対応し、起動時に何が設定済みかを一覧表示します。
保存先は `C:\Users\<ユーザー>\.emulin\credentials.json` です
(**Windows** のホームで、WSL のホームとは別なので注意)。

![Set up credentials 画面。左が provider 一覧、右が詳細と取得手順](docs/images/launcher-credentials.png)

左で provider を選ぶと、右に**状態・取り込み元・送り先**と、**How to get it**(取得手順と URL)が
出ます。**値は 1 文字も表示しません** — 出るのは名前・登録日・送り先だけです。
手順のテキストは選択してコピーできます。

### GitHub トークン (`gh` / `git push`)

GitHub の personal access token を登録しておくと、guest から `gh` と
`git push` (HTTPS) を **実トークンを guest に置かずに**使えます。
エージェントに PR を書かせる用途では、API キー以上にここが重要になります。

**Set up credentials** (または `setcred`) で **GitHub (personal access token)** を選び、
`https://github.com/settings/credentials` で作ったトークンを貼ってください。
どちらの種類でも動きます — fine-grained (`github_pat_...`) なら push したい repo に
**Contents: Read and write**、classic (`ghp_...`) なら `repo` スコープが要ります。

guest 側では 1 度だけ次を実行し、git が gh 経由で認証するようにします:

```bash
gh auth setup-git
```

> トークンは 1 個で `gh` (API) と `git push` (HTTPS) の両方を賄います。
> git の HTTPS 認証は Basic 認証でトークンが base64 の中に入りますが、
> MITM 中継がそれを解いて差し替えるので、guest 側に実トークンは現れません。

既定で有効です。`EMULIN_EGRESS_MITM=0` で無効にできます。
credential を 1 つも登録していなければ、この経路全体が no-op になります。

## AI コーディングエージェントを動かす (Claude Code / Codex)

0.7.0 以降、**Emulin 上で実用的な AI コーディングエージェントが動きます**。
Claude Code と Codex の両方で対話コーディングができます。Windows では
WHP ネイティブバックエンドの利用を強く推奨します
([ネイティブ実行](#ネイティブ実行で高速化-hyper-v--kvm))。

**0.8.0 では、エージェントに API キーを渡さずに使えるようになりました** —
[API キーを guest に置かない](#api-キーを-guest-に置かない) を参照してください。

### ランチャーを使う (推奨、0.9.0〜)

`emulin-app.bat` (または `emulin.bat app`) で開くランチャー画面から、
**導入・認証設定・セッション開始まで一通り操作できます**。以下は各手順の要約です
(コマンドラインで手動で行いたい場合は各節の「手動で行う場合」を参照)。

![展開直後のランチャー。Agents は導入前、credential も未登録](docs/images/launcher-main-before.png)

zip を展開して初めて開いた状態です。**Agents** に何が入っていないか、**Credentials** に
何が未登録かが並びます。ここから、必要なボタンを押していくだけで揃います。

| ランチャーの画面 | やること |
|---|---|
| **Install Claude Code** / **Install Codex CLI** ボタン | 現状を判定し、未導入の工程だけ実行ユーザー (root/非 root) を自動で使い分けて導入する |
| **Set up credentials** | host 側で済ませたログイン (下記) を取り込み、登録状況を確認・削除する (`emulin.bat setcred` の GUI 版) |
| **Open terminal** | `emulin.bat` 相当を開く (Windows Terminal)。**非 root ユーザーで開く**ので、`claude` / `codex` をそのまま起動できる |
| **SSH server** `Start` / **Add public key** | sshd を起動し、SSH クライアントの公開鍵を登録する。コンソールではなく `ssh` 経由で作業できる ([SSH サーバとして使う](#ssh-サーバとして使う)) |

ボタンが実行ユーザーを自動で切り替えるので、下の表にある
「インストールは root/非 root のどちらか」を意識する必要はありません。
**認証だけは host 側のブラウザ操作が必要**なので、GUI では肩代わりできません
(下記「認証」節の手順は引き続き必要です)。

導入を押すと、工程ごとに実行ユーザーと経過時間が出ます。**nodejs/npm の導入は約 20 分**
かかるので、止まっているのか進んでいるのかがここで分かるようにしてあります。

![Install Claude Code を押した直後。工程と実行ユーザーがログに出る](docs/images/launcher-install-progress.png)

一通り済むと、**Agents が `[installed]`、Credentials が `[registered]`** になります。
この画面が「使える状態」の目印です。

![導入と認証が済んだ状態](docs/images/launcher-main-after.png)

> 上の画像で灰色に塗ってある箇所は、掲載にあたって伏せたものです
> (展開先のパスと SSH 公開鍵の指紋)。実際の画面には値が表示されます。

<details>
<summary>guest メモリ pool の調整 (<code>EMULIN_NATIVE_POOL_MB</code>) — エージェントには 1024。<code>Killed</code> がプール不足かの見分け方</summary>

**ランチャーが値を選びます (0.9.0 以降)。自分で設定した値が優先されます:**

| guest の起動経路 | `EMULIN_NATIVE_POOL_MB` |
|---|---|
| **Open terminal** と **SSH server** の `Start` | **1024** — エージェントを動かすセッション |
| **Install Claude Code** / **Install Codex CLI** | **外す** — 固定すると大量の `apt` / `dpkg` が途中で止まる |
| `emulin.bat` を単体で起動 | **2048** |

自分で設定すればすべてに優先します。起動元のシェルで `set` しても、Windows の
ユーザー環境変数に入れても構いません:

```cmd
set EMULIN_NATIVE_POOL_MB=512
emulin-app.bat
```

**なぜエージェントには 1024 か。** この値は **1 プロセスあたり**の guest 物理メモリで、
WHP は低位 32GB の窓から確保します。エージェントは本体に加えてシェルやツールの
プロセスを並行して起動するため、1 プロセス 2048MB のままでは窓が先に埋まり、
収まらなかったプロセスが software backend に落ちて (#379) **極端に遅く**なります。
1024 なら倍のプロセスが窓に収まり、全プロセスを native で実行できます。

**逆に、`apt-get install` で大量のパッケージを入れるときは小さめ (512 など) が
向きます。** dpkg は短命プロセスを大量に並べるので窓が逼迫し、実機では pool が
2048 から 264MB まで縮小されました。次の行が出ていたら窓が窮屈になっています:

```
[native] guest RAM pool shrunk to fit: 2048->264MB (32GB window tight, issue #379)
```

**★ 大きくすれば良いわけではありません。`Killed` の万能薬でもありません。**
WHP では `VirtualAlloc(MEM_COMMIT)` がプールをシステムの commit 上限に対して
charge します。エージェントは同時に複数の guest プロセスを走らせるので、搭載メモリが
控えめな機械 (16GB 等) で 1 プロセスあたりを大きく取ると、セッション全体を圧迫します。

guest プロセスが `Killed` で落ちたときは、値を上げる前に**本当にプール不足か**を
確認してください。診断はファイルに取ります (TUI のエージェントは画面を占有しますし、
ランチャが Windows Terminal で起動し直すため `emulin.bat 2> file` は java まで届きません)。

```cmd
set EMULIN_TRACE_FILE=C:\temp\emulin-trace.log
emulin.bat
```

```
[native] pool exhausted -> OOM-kill (SIGKILL): ... name=<落ちたプロセス>
```

この行が出ていればプール不足です。**出ていなければプールは原因ではありません。**
#921 では同じ `Killed` の正体が `kill(-pgid)` の呼び出し元への誤配送で、プールを
増やしても何も変わりませんでした。

インストールが何らかの理由で途中で止まった場合は、続きから復旧できます:

```cmd
emulin.bat /usr/bin/dpkg --configure -a <nul
emulin.bat /usr/bin/apt-get -f install -y <nul
```

</details>

### Claude Code

作業ごとに**実行する場所とユーザーが変わります**。まず全体像:

| 作業 | 実行する場所 | 実行ユーザー |
|---|---|---|
| インストール | **guest** | 非 root ユーザー (uid 1000) |
| 認証設定 (`claude auth login` → `setcred`) | **host (Windows)** | — |
| セッション開始 (`claude`) | **guest** | 非 root ユーザー (uid 1000) |

インストールと起動が同じ非 root ユーザーなのは、公式インストーラが
`~/.local/bin` へのユーザー単位インストールだからです (root で入れると
`/root/.local/bin` に入り、uid 1000 のセッションからは見えません)。
認証だけ host 側で行うのは、**実トークンを guest に置かない**ためです
([API キーを guest に置かない](#api-キーを-guest-に置かない))。

以下、順に説明します。

#### インストール

ランチャーの **Install Claude Code** ボタンで導入します。現状を判定し、
非 root ユーザーへ公式インストーラを実行して `~/.bashrc` の PATH まで設定します。

<details>
<summary>手動で行う場合</summary>

公式インストーラで導入します。現行の Bun ネイティブ版が Emulin 上で動くので、
**バージョンを固定する必要はなく、自動アップデートも有効のままで構いません**。
Claude Code は root 権限での実行を避ける必要があるため、`emulin.bat` 起動時の
`Log in as:  [1] root   [2] <ユーザー>` で **`2`** を選び、非 root ユーザーで
実行してください ([非 root ユーザー (uid=1000) で使う](#非-root-ユーザー-uid1000-で使う))。

```bash
# 非 root ユーザーで起動した Emulin の中で:
curl -fsSL https://claude.ai/install.sh | bash

# インストーラは ~/.local/bin に置くので PATH を通す
export PATH="$HOME/.local/bin:$PATH"
claude --version
```

</details>

#### 認証 — ★ **credential 登録済みなら `/login` は不要**

Claude の credential を登録してあれば (ランチャーの **Set up credentials**、
または `emulin.bat setcred`)、guest の中で `/login` する必要は**ありません** —
`claude` を起動すれば登録済みの credential が使われます。`/status` で確認できます:

```
Auth token:             CLAUDE_CODE_OAUTH_TOKEN
Additional CA cert(s):  /etc/ssl/emulin-ca.pem
```

> **★ guest の中で `/login` しないでください。** そこで OAuth を完了させると
> **実トークンがサンドボックスの中に書き込まれ**、
> [API キーを guest に置かない](#api-キーを-guest-に置かない) 仕組みが無効になります。

登録するには、**ホスト側**でサンドボックス専用の設定ディレクトリを使ってログインし、
ランチャーの **Set up credentials** 画面で Claude を選んで取り込みます
(CLI なら `emulin.bat setcred`):

```bash
# ホスト側 (Windows / WSL2 いずれでも可)
CLAUDE_CONFIG_DIR=~/.claude-emulin  claude auth login
```

> **★ `CLAUDE_CONFIG_DIR` を必ず付けてください。** 付けずに `claude auth login` すると、
> いま使っている普段のログイン (`~/.claude`) が置き換わります。OAuth の refresh token は
> **使うたびに回転**するので、同じログインを guest とホストで共有すると、**先に更新した
> 方だけが生き残り**、もう片方は次のリクエストでログアウトされます。別々にログインすれば
> 干渉しません (PC と Mac で同時に使えるのと同じです)。

<details>
<summary>credential の詳細 — 0.8.3 で変わった点 / WSL2 でログインした場合 / guest に実際に置かれるもの</summary>

credential を 1 つも登録していない場合は、従来どおり `/login` でサブスクリプション
(Claude アカウントの OAuth) または API キーを設定します。

**0.8.3 から、サブスクリプションの認証はブラウザ認証 (OAuth) に一本化しました。**
登録するのは `claude auth login` で得られる **access / refresh の 2 本組**です
(`CLAUDE_ACCESS_TOKEN` / `CLAUDE_REFRESH_TOKEN`)。

> **★ 0.8.3 で `claude setup-token` の長期トークンは廃止しました。**
> あれは仕様として **inference 限定**で、Remote Control 等は claude 自身が拒否します:
>
> ```
> Error: Remote Control requires a full-scope login token. Long-lived tokens ... are
> limited to inference-only for security reasons. Run `claude auth login` ...
> ```
>
> 既に `CLAUDE_CODE_OAUTH_TOKEN` を登録している場合、**推論はそのまま動きます**が、
> 起動時に移行の案内が出ます。上の手順で登録し直してください。

**WSL2 でログインした場合**: `.credentials.json` は **WSL2 のホーム**に置かれ、
Windows のホームとは別物です。**Set up credentials** 画面 (`emulin.bat setcred` も同様)
は WSL2 のホームも探して候補に出すので、そこから選べます (0.8.4 以降):

```
Found these Claude logins on this machine:
  [1] WSL2 Debian / <user> (.claude-emulin)  \\wsl.localhost\Debian\home\<user>\...
  [2] WSL2 Debian / <user> (.claude)         ...   <- 普段使い。選ばないこと
  [0] type a path myself
```

**`.claude-emulin` (サンドボックス専用) を選んでください。** 一覧の先頭に来るように
並べてあります。普段使いの `.claude` を選ぶと、上記の回転で**そちらがログアウト**します。

guest には placeholder の `~/.claude/.credentials.json` が起動ごとに置かれ、
**実トークンはホストの `~/.emulin/credentials.json` にのみ**残ります。
access token の失効時は、Emulin が wire 上で refresh を差し替えて回転させるので、
登録し直す必要はありません (refresh token 自体が切れる約 1 週間までは)。

</details>

#### Remote Control — **スマホから guest のセッションを操作する**

ブラウザ認証を登録していれば、guest の中で:

```bash
claude remote-control
```

起動時に表示される `https://claude.ai/code?environment=env_...` を開くか、
端末で **`space` を押して QR コード**を表示してスマホで読み取ります。
以後、iPhone の Claude アプリ (Code タブ) や claude.ai/code から、**guest の中で**
コマンドを実行させられます。実トークンはホストに残ったままです。

<details>
<summary>ハマりやすい点 — URL は起動のたびに変わる / <code>PATH</code> / <code>Workspace not trusted</code> / 初回の応答が遅い</summary>

- **一覧に出るのを待つ仕組みではありません。** 起動時に出る URL / QR から入ります。
  **environment ID は起動のたびに変わります**。古い URL を開くと、チャット画面は
  普通に開けるのに応答だけ返らない、という紛らわしい状態になります。
- claude は `~/.local/bin` に入りますが、Emulin は bash を `-i` (非ログイン) で
  起動するため `~/.profile` が読まれません。`~/.bashrc` に
  `PATH="$HOME/.local/bin:$PATH"` を入れてください。
- `Workspace not trusted` と出たら、そのディレクトリで一度 `claude` を起動して
  承認します。
- 初回の応答は分単位かかります (guest の中で **もう 1 つの claude プロセス**が
  起動するため)。

</details>

#### セッション開始

ランチャーの **Open terminal** ボタンで端末を開き、作業したいディレクトリに
移動してから `claude` を実行します。**Open terminal は非 root ユーザーで開く**ので、
ログインユーザーを選ぶ必要はありません (エージェントはこのユーザーのホームに
入っているため。`emulin.bat` を直接起動した場合は `[2]` を選んでください):

```bash
cd /mnt/c/dev/<プロジェクト>
claude
```

初回だけ `Quick safety check: Is this a project you created or one you trust?` と
そのディレクトリを信頼するかを尋ねられます (**ログインではありません**。claude 標準の
動作で、ディレクトリごとに 1 回だけ出ます)。`1. Yes, I trust this folder` を選べば
セッションが始まります。

### Codex

Claude Code と違うのは**インストールだけ**で、そこだけ root が要ります:

| 作業 | 実行する場所 | 実行ユーザー |
|---|---|---|
| インストール | **guest** | **root** |
| 認証設定 (`codex login` → `setcred`) | **host (Windows)** | — |
| セッション開始 (`codex`) | **guest** | **非 root** |

インストールが root なのは、`apt-get` でシステムに nodejs/npm を入れ、
`npm -g` で `/usr/lib/node_modules` に導入するからです。導入先は全ユーザー共通なので、
**セッションは Claude Code と同じく非 root ユーザーで動かします**。
`~/.codex/auth.json` は Emulin が root と非 root の両方に置くため、
非 root に戻るのに追加の作業は要りません。

#### インストール

ランチャーの **Install Codex CLI** ボタンで導入します。root で `apt-get` /
`npm -g` を実行してから `~/.codex/config.toml` の `sandbox_mode` (下記) まで
非 root ユーザーのホームに作成する、という**実行ユーザーの使い分けを自動で行います**。

<details>
<summary>手動で行う場合</summary>

> **★ この 2 つは guest の中で root として実行してください。** システム全体への
> パッケージ導入 (`apt-get`) と `/usr/lib/node_modules` への global install
> (`npm -g`) なので、非 root ユーザーでは権限エラーになります。`emulin.bat` を
> 引数なしで起動したときの `Log in as:  [1] root   [2] <ユーザー>` で **`1` (root)**
> を選んでください。

```bash
# root で (プロンプトが # であること)
apt-get update && apt-get install -y nodejs npm </dev/null
npm install -g @openai/codex
```

> **通る条件** (実測値は Emulin 0.8.2 / Debian 13 trixie / 559 パッケージのとき)
>
> | | |
> |---|---|
> | 実行ユーザー | **root** (上の注意書きのとおり) |
> | stdin | **`</dev/null` を付ける**。付けないと postinst の対話プロンプトで止まることがあります |
> | 空き容量 | **2GB 以上**。rootfs が 765MB → **2.0GB** になります (ダウンロード 177MB) |
> | 所要時間 | **7〜8 分** (WSL2 + KVM で実測)。Windows (WHP) でも同程度です |
> | `EMULIN_NATIVE_POOL_MB` | **設定不要**。既定 (512MB) のまま通ります。増やしても速くはなりません |
>
> 途中で止まった場合は、続きから復旧できます:
>
> ```cmd
> emulin.bat /usr/bin/dpkg --configure -a <nul
> emulin.bat /usr/bin/apt-get -f install -y <nul
> ```

Emulin の rootfs 自体が隔離境界であり、codex が guest 内に張ろうとする OS レベルの
sandbox (Landlock + seccomp) は未対応です (codex が install 時に panic します)。
初回起動前に `~/.codex/config.toml` で無効化してください:

```toml
sandbox_mode = "danger-full-access"
```

> **★ このファイルは非 root ユーザーで作成してください。** root が要るのは上の
> インストールだけで、セッションは非 root で動かします。codex は**起動したユーザーの
> ホーム**から設定を読むので、root のホームに置いても効きません。
> `Log in as:  [1] root   [2] <ユーザー>` で `2` を選び直してから作成してください。

</details>

#### 認証 — ★ **ホスト側でログインしてください**

guest の中で `codex login` すると、**実トークンがサンドボックスの中に置かれます**。
それでは [API キーを guest に置かない](#api-キーを-guest-に置かない) 仕組みの意味が
無くなるので、ログインは**ホスト (Windows) 側**で行い、ランチャーの
**Set up credentials** 画面 (または `emulin.bat setcred`) で取り込みます。

```bat
rem 1. ホスト側でログイン (ブラウザが開く。ヘッドレスなら --device-auth で
rem    画面にコードが出る方式も使えます)
codex login
rem    または  codex login --device-auth
```

取り込みはランチャーの **Set up credentials** から OpenAI (Codex) を選ぶか、CLI なら:

```bat
rem ウィザードが C:\Users\<ユーザー>\.codex\auth.json を読みます
emulin.bat setcred
```

guest 側の `~/.codex/auth.json` は Emulin が**起動ごとにプレースホルダで生成**するので、
guest では `codex` を起動するだけで使えます。実トークンは host 側にとどまり、
MITM 中継が通信の瞬間だけ差し替えます (短命トークンの更新も host 側で行われます)。

> **WSL2 でログインした場合**: `auth.json` は WSL2 のホームに置かれ、
> **Set up credentials** / `setcred` からは見えません (Windows のホームとは別です)。
> コピーしてください:
> ```bash
> cp ~/.codex/auth.json /mnt/c/Users/<ユーザー>/.codex/auth.json
> ```

API キー (従量課金) を使う場合は `emulin.bat setcred` で **OpenAI (API key)** を選びます。

#### セッション開始

ランチャーの **Open terminal** ボタンで端末を開き、作業したいディレクトリに
移動して `codex` を実行します (**非 root ユーザーで開きます**。`emulin.bat` を
直接起動した場合は `[2]` を選んでください):

```bash
cd /mnt/c/dev/<プロジェクト>
codex
```

### 非 root ユーザー (uid=1000) で使う

**設定は不要です。** `emulin.bat` を引数なしで起動すると、初回にユーザー名を尋ねて
uid 1000 のユーザーを作成し、以後は起動のたびに root かそのユーザーかを選べます
([クイックスタート](#windows-で使い始める-java-不要) の手順 4)。USER / HOME は guest の
`/etc/passwd` から自動解決されます (#611)。

毎回メニューを出さず**常に非 root で起動**したい場合だけ、次を設定します:

```cmd
set EMULIN_LOGIN=user
emulin.bat
```

claude のように root で動かせないものはこのユーザーで使います
([AI コーディングエージェントを動かす](#ai-コーディングエージェントを動かす-claude-code--codex))。

<details>
<summary>ランチャを介さず <code>java -jar</code> を直接起動する場合</summary>

この自動処理は働きません。rootfs にユーザーを一度作成し、`EMULIN_UID` /
`EMULIN_GID` を自分で指定してください:

```bash
./emulin.sh /usr/sbin/useradd -m -u 1000 -s /bin/bash devuser   # 初回のみ
EMULIN_UID=1000 EMULIN_GID=1000 java -jar emulin-*-all.jar <rootfs> -CJ /bin/bash -i
```

</details>

### 日本語 (UTF-8) について

日本語の入出力は既定で通ります (#716) — 設定は不要です。

<details>
<summary>ロケールがどう決まるか / <code>ja_JP.UTF-8</code> が必要な場合</summary>

- launcher は LANG 未設定時に `C.UTF-8` (glibc 組込みの UTF-8 ロケール、ロケール
  ファイル不要) を設定します。
- emulin 自身も guest の LANG を保証します — host の LANG が指すロケールのデータが
  rootfs に無い場合 (例: Linux host の `ja_JP.UTF-8`) は `C.UTF-8` に正規化します
  (素通しすると glibc が ASCII の `C` ロケールに fallback し、`ls` の日本語ファイル名
  が化けるため)。
- rootfs 側にも `/etc/profile.d/`・`/etc/skel/.bashrc`・`/root/.bashrc` に
  `export LANG="${LANG:-C.UTF-8}"` を仕込んであるので、`su` / SSH 経由で入った
  シェルでも有効です。

`ja_JP.UTF-8` そのもの (日本語メッセージ・照合順序) が必要な場合は、guest に一度
ロケールを導入してください。データが入れば host の LANG はそのまま素通しされます:

```cmd
emulin.bat /usr/bin/apt-get install -y locales <nul
emulin.bat /usr/bin/localedef --no-archive -i ja_JP -f UTF-8 ja_JP.UTF-8
```

`localedef --no-archive` を使ってください — `locale-gen` の archive モードは
Emulin 上ではまだ動きません (#717)。特定の値を強制したい場合は
`EMU_LANG=<locale>` が最優先されます。

</details>

### 既知の制限事項 (AI エージェント)

| 制限 | 詳細 / 回避策 |
|---|---|
| Claude Code の `/quit` と自動更新が遅い | 終了処理も updater も大量のファイル I/O を伴います (バイナリだけで約 275MB)。セッションを切らずに完了を待ってください (#695 / #696)。 |
| Claude Code の `/quit` に時間がかかる | 終了時に npm が走り多数のファイルを開くため。大幅改善済み (#696) ですが数十秒かかることがあります。そのまま待ってください (#695)。 |
| まれに入力がフリーズする (Windows) | Windows の **ConPTY 層**がキーイベント (Ctrl-C 含む) を配送しなくなることがまれにあります (#709)。Emulin 側の問題ではありません — `emulin sshd` に ssh で接続した構成 (= Emulin が入力経路に居ない) でも同様に発生します。**ターミナルウィンドウを一度リサイズ**すると滞留した入力が流れ、セッションはそのまま継続できます。ConPTY を通らない端末 (WezTerm 内蔵 SSH / Tera Term / PuTTY 等) なら回避できる可能性があります。 |
| `/mnt/c` 上の大きな repo は起動が遅い | host マウント越しの workspace スキャン (`git ls-files` / `rg --files`) は rootfs 内より大幅に遅くなります。rootfs 内に clone して作業するのを推奨します (例: `git clone file:///mnt/c/dev/repo ~/repo`)。 |
| Codex の内蔵 sandbox は使えない | `sandbox_mode = "danger-full-access"` が必須です。隔離境界は Emulin の rootfs が担います (bwrap 用 user namespace エミュレーションは #497 で計画中)。 |

## ネイティブ実行で高速化 (Hyper-V / KVM)

Windows の **Hyper-V (WHP)** / Linux の **KVM** が使える環境では、guest を実 vCPU で
実行し syscall だけ emulin にトラップする **native backend** が利用できます。compute
律速の処理が大幅に高速化します (sort / grep / sha256sum 等で ~200x、大規模 git clone
も実用速度)。

ランチャ (`emulin.sh` / `emulin.bat`) は既定で `EMULIN_BACKEND=auto` を設定し、
**HW 仮想化が使えれば native、無ければ software に自動 fallback** します。起動時の
バナーで現在の backend が分かります:

```
[backend=native (auto, KVM detected (/dev/kvm OK))]   ← native で実行中
[backend=software]                                    ← software で実行中
```

**要件:**

- **Windows**: 「**Windows ハイパーバイザー プラットフォーム**」(Windows Hypervisor
  Platform) を Windows の機能から有効化 (WSL2 と共存可)。
- **Linux**: `/dev/kvm` にアクセスできること (`kvm` グループに参加、または
  `sudo chmod 666 /dev/kvm`)。

**切り替え / チューニング (環境変数):**

| 変数 | 既定 (launcher) | 説明 |
|------|------|------|
| `EMULIN_BACKEND` | `auto` | `auto` (HW 仮想化を自動検出) / `native` (強制) / `software` (強制) |
| `EMULIN_NATIVE_POOL_MB` | `2048` | native backend の guest 物理プール (MB)。**1 プロセスあたり**で低位 32GB の窓から取る。既定の 2048 は `emulin.bat` / `emulin.sh` が設定する値 (ランチャを介さず `java -jar` を直接起動したときは 512)。AI エージェントは `1024`、apt の大量 install は `512` が向く ([詳細](#ai-コーディングエージェントを動かす-claude-code--codex)) |
| `EMULIN_TLB_FLUSH_SYSCALL` | `1` | (Windows/WHP のみ) syscall 境界で自 vCPU の TLB を flush する。**既定 ON**。off にすると stale TLB で guest のヒープが壊れることがある (#880) |
| `EMULIN_WHP_MAX_VCPUS` | `256` | (Windows/WHP のみ) 同時 vCPU 数の上限。guest の thread 1 本 = vCPU 1 個で、JVM 内の全 guest process が分け合う。thread を多用する guest で上限に達したら引き上げる (最小 64) |

> software backend は **正しさの canonical (基準)** であり常時維持されます。回帰テストは
> software で常に PASS し、native は software と **byte 一致** (native-oracle で検証)。
> 困ったときや `apt` のような mremap 多用 workload (issue #304) は
> `EMULIN_BACKEND=software` で確実に動かせます。macOS の Hypervisor.framework (HVF) は
> 将来対応予定 (issue #306)。

## パフォーマンス

### `-XX:-DontCompileHugeMethods` (必須)

実機 binary を動かす時は **`-XX:-DontCompileHugeMethods`** を必ず付けます:

```bash
java -XX:-DontCompileHugeMethods -jar emulin-*-all.jar ...
```

このフラグなしだと、emulator の中核 dispatch loop (`Cpu64::decode_and_exec`、
20K+ bytecode) が JVM の `HugeMethodLimit` (default 8000 byte) で JIT C2
compile を拒否され、interpreter モードで実行されます。
git clone HTTPS で 28% 高速化します (14.4s → 10.4s)。

`emulin.sh` / `emulin.bat` ランチャは自動的にこのフラグを付けます。

### `EMULIN_USE_JIT=1` (オプション、Phase 34-A3/A5)

x86-64 命令を実行時に Java bytecode へ翻訳する basic-block JIT を内蔵
しています。default off ですが crypto 系 workload で speedup が出ます:

| Workload | no JIT | with JIT | 効果 |
|----------|-------:|---------:|------|
| curl https://example.com  | 9.3s | 8.1s | -14% |
| curl https://github.com (570KB) | 10.4s | 9.1s | -13% |
| sha256sum 5MB             | 2.4s | 2.3s | -5%  |

vim 起動のような短尺 cold start workload では neutral〜やや不利
(JIT compile cost と相殺)。HTTPS / SIMD 重い workload で有効です:

```bash
EMULIN_USE_JIT=1 java -XX:-DontCompileHugeMethods -jar emulin-*-all.jar ...
```

## 既知の制約

- Python 3 の一部 syscall (signalfd4 / pidfd_open 等) 未対応 (optional 経路のため通常は動作)
- **software backend** の実行速度は host より大幅に遅い (curl HTTPS で ~100x、git clone で ~13x)。
  HW 仮想化が使える環境では **native backend (Hyper-V / KVM、既定 auto)** が compute を ~200x 高速化
- WSL DrvFs (`/mnt/c/...`) は I/O 遅い → sandbox は Linux /tmp 等に置く
- AI エージェント固有の制限 (Claude Code のバージョン上限等) は
  [既知の制限事項 (AI エージェント)](#既知の制限事項-ai-エージェント) を参照

## ディレクトリ構成

```
src/main/java/emulin/        Emulin 本体
  Cpu.java (i386), Cpu64.java (x86-64), AbstractCpu.java
  Syscall.java, SyscallI386.java, SyscallAmd64.java
  Elf.java, ElfCache.java, Segment.java, Section.java, Memory.java
  Process.java, Kernel.java, Thread64.java, FutexManager.java
  device/Console.java, StdConsole.java, JLineConsole.java
  jit/Translator.java, jit/CompiledInsn.java  (Phase 34-A3/A5 JIT)

dist/
  build-dist.sh             配布 zip ビルドスクリプト
  build-sandbox.sh          sandbox 構築スクリプト
  launchers/emulin.sh / .bat 起動ランチャ
  gen-quickstart.sh         配布 zip 同梱の QUICKSTART.txt を生成

tests/
  binaries/src/             x86 / x86-64 テスト ELF ソース
  scripts/                  回帰テスト実行スクリプト
  expected/                 期待出力 (stdout / exit / argv / stdin)
```

## ビルド方法

> 通常は不要です。配布 zip に一式 (Emulin 本体・JRE・Debian rootfs) が入っています。
> Emulin 自体に手を入れたい場合だけどうぞ。

```bash
git clone https://github.com/kiyoka/emulin.git
cd emulin
mvn package -DskipTests
```

成果物:
- `target/emulin-<version>-all.jar` (fat jar、JLine 同梱)

この fat jar は配布 zip の `emulin.bat` / `emulin.sh` が内部で呼び出すものと同じです。
ローカルで Debian ベースの bundle (配布 zip 相当) を作るには `dist/build-release.sh` を
使います。

## テスト

```bash
make -C tests/binaries        # x86 / x86-64 テストバイナリをビルド
tests/scripts/run-fast.sh     # 軽量 subset (~27s、real-* / dist 抜き、146 ケース)
tests/scripts/run-all.sh      # 全テスト (~4m、230 ケース)
tests/scripts/run-network.sh  # ネットワーク関連だけ (~3m、HTTPS clone 含む)
```

並列負荷下で稀に 1-3 件 timing flake が出ますが standalone では全 PASS します。

## 履歴

`.claude/CLAUDE.md` に Phase 別の作業記録があります (現代化 + 64-bit 拡張 +
実機 binary 対応の各 phase の要約と既知バグの累計パターン)。

## 連絡先

- バグ、要望、質問: <kiyokasumibi@gmail.com>
- GitHub Issues: https://github.com/kiyoka/emulin/issues
