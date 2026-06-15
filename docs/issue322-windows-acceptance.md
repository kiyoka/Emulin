# issue #322 — 0.6.0 Windows 実機受入 runbook (M1 sshd / M2 Hyper-V native / M3 apt)

0.6.0 の 3 本柱を **Windows 実機**で受入するための手順書。各マイルストーンは
dev host (WSL2/KVM、software) で検証済なので、ここでは **Windows 固有 (WHP / Tera Term /
実 SSH client / 実機 apt)** の確認に絞る。

- M1 = `emulin sshd` で外部 SSH client から接続
- M2 = Hyper-V (WHP) ネイティブ実行 (byte 一致 + 実 workload)
- M3 = Debian repo から実パッケージ install → 実行

## 凡例

- 🪟 = Windows 実機の **PowerShell** (特記時のみ cmd / Windows Terminal)。作業ディレクトリは
  `C:\emulin-accept` (ASCII path、日本語/OneDrive path を避ける)。
- 🐧 = WSL2 / Linux (`/home/kiyoka/GitHub/Emulin`) での bundle ビルド。
- 各ステップに **期待結果** を明記。すべて満たせば受入 OK。

---

## (0) 前提

### 0-1. Hyper-V「Windows Hypervisor Platform」を有効化 🪟 (管理者 PowerShell) — M2 用

```powershell
Get-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform | Select FeatureName,State
# 無効なら (再起動が要る)
Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All -NoRestart
Restart-Computer
```

期待結果: 再起動後 `State` が **Enabled**。(GUI: コントロール パネル → プログラム →
Windows の機能 → 「Windows ハイパーバイザー プラットフォーム」にチェック。)
WSL2 と共存可。

### 0-2. JDK 22+ を PATH に (whp-oracle 用) 🪟

```powershell
java -version    # openjdk 22+ (無ければ後で bundle 同梱 JRE を前置する)
```

demo bundle は同梱 JRE (JDK 25) で自走するので launcher 自体は別 JDK 不要。ただし
`whp-oracle-full.ps1` は PATH の `java` を使うので 22+ が要る。無ければ:
`$env:Path = "C:\emulin-accept\<bundle>\jre\bin;" + $env:Path`

### 0-3. 作業ディレクトリ 🪟

```powershell
New-Item -ItemType Directory -Force C:\emulin-accept | Out-Null
```

### 0-4. 受入用 bundle をビルド 🐧

sshd (M1) を含む Windows demo bundle と、M2 用 oracle bundle を作る。
**注**: 単一 platform なので `build-demo-bundle.sh` を直接使う (4-platform 一括は
`build-release.sh` だが下記「リリース実作業」の命名注意を参照)。

```bash
cd /home/kiyoka/GitHub/Emulin
mvn -q package -DskipTests

# (a) sshd + git/vim/emacs 入りの Windows bundle (DEBIAN_BASE=1 が既定 = apt 入り)
TARGET_PLATFORM=windows-x64 INCLUDE_SSHD=1 INCLUDE_VIM=1 INCLUDE_EMACS=1 \
    dist/build-demo-bundle.sh
cp target/*emulin*-0.6.0-windows*.zip /mnt/c/emulin-accept/

# (b) M2 用 oracle bundle (hermetic、whp-oracle-full 用)
tests/scripts/build-native-oracle-full-bundle.sh
cp target/native-oracle-full-bundle.zip /mnt/c/emulin-accept/
cp tests/scripts/whp-oracle.ps1 /mnt/c/emulin-accept/
```

期待結果: `/mnt/c/emulin-accept/` に bundle zip (`debian-emulin-0.6.0-windows.zip` など) +
`native-oracle-full-bundle.zip` + `whp-oracle.ps1`。

### 0-5. Windows 側で展開 🪟

```powershell
cd C:\emulin-accept
Get-ChildItem *.zip                         # 実際の zip 名を確認
Expand-Archive -Force .\debian-emulin-0.6.0-windows.zip .\   # 実名に読み替え
Expand-Archive -Force .\native-oracle-full-bundle.zip   .\
Copy-Item .\whp-oracle.ps1 .\native-oracle-full-bundle\ -Force
# 以後 $B を bundle ディレクトリとする
$B = (Get-ChildItem -Directory .\*emulin-0.6.0-windows* | Select -First 1).FullName
```

期待結果: `$B` に `emulin.bat` / `jre\` / `rootfs\` / `lib\emulin-0.6.0-all.jar`。

---

## (M1) sshd で接続して使う 🪟

### M1-1. 接続元の SSH 公開鍵を登録

```powershell
# 接続に使う鍵 (例: ~/.ssh/id_ed25519.pub) の中身を authorized_keys へ
type $env:USERPROFILE\.ssh\id_ed25519.pub | Out-File -Append -Encoding ascii `
    "$B\rootfs\root\.ssh\authorized_keys"
```

鍵が無ければ `ssh-keygen -t ed25519` で作成。

### M1-2. sshd を起動 (この窓は起動しっぱなし)

```powershell
cd $B
.\emulin.bat sshd            # 既定 port 2222、127.0.0.1 待受、user=root、publickey
```

期待結果: `[emulin sshd] OpenSSH sshd on 127.0.0.1:2222 ...` が表示され待機。

### M1-3. 別の窓から接続して操作

```powershell
ssh -p 2222 root@127.0.0.1 'echo HELLO-FROM-EMULIN; uname -a; id'   # 非対話 exec
ssh -tt -p 2222 root@127.0.0.1                                       # 対話 (pty)
#   → bash プロンプトで pwd / ls / vim ファイル編集 / exit
```

Tera Term: Host=`localhost` / TCP port=`2222` / User=`root` / 認証=publickey (鍵指定)。

**期待結果 (M1 受入)**:
- [ ] 非対話 exec で `HELLO-FROM-EMULIN` / `uname` / `id` が返る
- [ ] `ssh -tt` で対話 bash、`vim` 編集・保存、`exit` まで動く
- [ ] Tera Term から同様に接続・操作できる

---

## (M2) ネイティブ実行 (Hyper-V / WHP) 🪟

### M2-1. native で起動することの確認

```powershell
cd $B
.\emulin.bat /usr/bin/uname -a       # launcher 既定 EMULIN_BACKEND=auto
```

期待結果: バナーに **`[backend=native (auto, WHP detected ...)]`**。
(software になる場合は 0-1 の WHP 有効化と再起動を確認。`set EMULIN_BACKEND=native` で強制可。)

### M2-2. whp-oracle-full で byte 一致 (native(WHP) == software)

```powershell
cd C:\emulin-accept\native-oracle-full-bundle
# PATH の java が 22+ であること (0-2)
powershell -ExecutionPolicy Bypass -File .\whp-oracle-full.ps1
# (51 件版だけ素早く見たいなら: .\whp-oracle.ps1)
```

期待結果: 末尾 `ok=NN FAIL=0`、**exit code 0**。全 *64 binary で native(WHP) が software と
byte 一致。

### M2-3. 大規模 git clone を native で

```powershell
cd $B
.\emulin.bat /usr/bin/git clone --depth=1 https://github.com/git/git.git /root/clonetest
.\emulin.bat /usr/bin/sh -c "ls /root/clonetest | head; cd /root/clonetest && git log --oneline -1"
```

期待結果: clone 完走 (`EMULIN_CLONE_RC=0` 相当)、checkout したファイルが見える。
(掃除: guest の `rm -rf` が NTFS で残す場合は host で `cmd /c rmdir /s /q $B\rootfs\root\clonetest`。)

### M2-4. 対話 binary (Windows Terminal 推奨)

```powershell
.\emulin.bat bash            # 対話 bash
.\emulin.bat /usr/bin/vim /tmp/t.txt
.\emulin.bat /usr/bin/emacs -nw   # emacs 入り bundle のとき
```

**期待結果 (M2 受入)**:
- [ ] バナーが `[backend=native ... WHP detected]`
- [ ] `whp-oracle-full.ps1` が `FAIL=0` / exit 0
- [ ] native で git clone 完走
- [ ] 対話 bash / vim (/ emacs) が native で動作

---

## (M3) apt でパッケージ追加 🪟

> apt は emulator CPU で **遅い** (初回 `apt-get update` の index 解析が律速)。また apt の
> mremap 多用は native allocator 制約 (#304) に当たるため、**software backend で実行**する。

```powershell
cd $B
# software 強制 (native の #304 leak 回避)
$env:EMULIN_BACKEND = "software"
.\emulin.bat /usr/bin/apt-get update           # 初回は十数分かかることがある
.\emulin.bat /usr/bin/apt-get install -y hello
.\emulin.bat /usr/bin/hello
.\emulin.bat /usr/bin/dpkg-query -W hello
Remove-Item Env:\EMULIN_BACKEND
```

**期待結果 (M3 受入)**:
- [ ] `apt-get update` が GPG 署名検証込みで完了 (`E:` エラーなし)
- [ ] `apt-get install -y hello` が download → unpack → configure 完走
- [ ] `/usr/bin/hello` が `Hello, world!` を出力、`dpkg-query` が `hello <ver> ... installed`

---

## 落とし穴 (Windows 計測/実行の罠、issue #304 で実証済)

- **ps1 は ASCII-only + CRLF**。日本語コメント入り ps1 を BOM 無しで置くと CP932 解釈で
  パース崩壊する。
- **`emulin.bat` は argv[0] を PATH 解決しない** → `git` でなく `/usr/bin/git` のように
  **絶対パス**で渡す。
- **guest の `rm -rf` は NTFS の深い tree を消しきれない** → 残骸は host の
  `cmd /c rmdir /s /q <path>` で掃除。
- WHP は **JDK 22+ 必須** (FFM API)。`whp-oracle*.ps1` の `java` が 21 以下だと SKIP/失敗。
- cmd.exe は Ctrl-A / Ctrl-F を intercept → 対話は **Windows Terminal** 推奨。

---

## リリース実作業 (M1/M2/M3 受入 OK 後)

1. **⚠️ `build-release.sh` の bundle 命名を要確認/修正**: `dist/build-release.sh` は
   `target/emulin-demo-$VERSION-*.zip` を探すが、`build-demo-bundle.sh` は
   `DEBIAN_BASE=1` (既定) で **`debian-emulin-$VERSION-*.zip`** を生成する (rename ロジック
   無し)。0.6.0 の 4-platform 一括 build で zip が見つからず "zip が生成されなかった" 警告に
   なる可能性が高い。`build-release.sh` の探索名を `debian-emulin-*` に合わせる (または glob)
   修正を入れてから release build すること。
2. `dist/build-release.sh` で 4 platform (linux/windows-x64 + macos x64/arm64) を build。
3. annotated tag `v0.6.0` を作成し push。
4. `gh release create v0.6.0` で windows-x64 zip を添付 (慣例)。

詳細なリリース手順は memory `project_release_process` / `RELEASE-NOTES-0.6.0.md` 参照。
