# Emulin

**java based EMUlation technology for Linux IA-32 Native application**

Version 0.2.13b

Kiyoka Nishiyama

---

## 1) Emulin とは？

Emulin はフリーソフトウェアです。
Linux for IA-32 バイナリを実行するエミュレータです。
java で記述されています。
Emulin は GNU General Public License のもとで配布されています。
詳細は COPYING を参照してください。

## 2) 特徴

- 全て java で記述されている。
- IA-32 のバイナリレベルでエミュレーションを行う。
- 完全に Unix ライクな環境で作業できる。(Windows 上でも `\` 記号のパスに苦しまなくてよい。)
- 実行速度が遅い (^_^;)

## 3) 対応機種／環境

| 項目 | 内容 |
|------|------|
| JDK | JDK 1.1.6 + Swing 以上 |
| OS  | Windows 95/98/2000 と Linux 2.0.32 のみで動作確認 |

> **注意!** Windows の HotSpot 1.0 では、正しく動作しません。Emulin を使うときは HotSpot は外してください。  
> **注意!** Java 2 以前の JDK には Swing が入っていないため、各自インストールしてください。

## 4) 本パッケージの内容

```
emulin/README         ...  このファイルです。(SJIS)
emulin/README.*       ...  各漢字コードでの README ファイル
emulin/COPYING        ...  コピーイング(法的に正規の文書はこのファイルです。)
emulin/GPL-2j.txt     ...  GPL2 文書の和訳(このファイルは日本語での参照用です。法的に通用する文書ではありません。)
emulin/emulin/        ...  Emulin のソースファイル・クラスファイル等が格納されています。
emulin/Makefile       ...  Emulin をビルドするための Makefile です。
emulin/setup          ...  UNIX 用セットアップスクリプト
emulin/setup.bat      ...  Windows 用セットアップスクリプト
emulin/bootunix.sh    ...  UNIX 用 rc ファイル
emulin/bootwin.sh     ...  Windows 用 rc ファイル
```

> ※ 本パッケージには、Linux アプリケーションと `/etc` `/lib` は含まれていません。  
> Basic Application Package for Emulin、と Etc and Library Files for Emulin をダウンロードし、インストールしてください。

## 5) インストール方法

1. 展開先のディレクトリに移り、`jar xvf emulin.jar` でアーカイブを解凍します。

2. `CLASSPATH` 環境変数に展開したディレクトリパスを追加します。(JDK 1.2 以降では不要です。)

   ```
   # Windows の例
   set CLASSPATH=.;[bin]\..\classes;[bin]\..\lib\classes.zip;c:\emulin

   # Linux (csh) の例
   setenv CLASSPATH ./:/usr/local/java/lib/classes.zip:/home/xxx/emulin
   ```

3. 環境のセットアップ

   - Windows の場合: `setup.bat` が存在するディレクトリで `setup.bat` を実行
   - Linux の場合: `setup` が存在するディレクトリで `sh setup` を実行

   以下のように表示されるので、コンソールタイプを `1` か `2` で選択してください。

   ```
   Please select console type
     1. Native console (you can interrupt processes)
     2. normal console
   ```

   ネイティブコンソールを使う場合は以下 4. 5. の項目を設定してください。

4. ダイナミックリンクライブラリのパスを環境変数で指定します。(ネイティブコンソールを使う場合のみ必要)

   ```
   # Windows の例
   set PATH=c:\emulin\emulin\device\windows;%PATH%
   # (めんどうな場合は emu_con.dll を c:\windows 等にコピーする方法もあります。)

   # Linux (csh) の例
   setenv LD_LIBRARY_PATH /home/xxx/emulin/emulin/device/unix:$LD_LIBRARY_PATH
   # (めんどうな場合は emu_con.so を /usr/local/lib 等にコピーする方法もあります。)
   ```

5. `cygwin1.dll` を入手する。(Windows で、ネイティブコンソールを使う場合のみ必要)

   1. ダウンロードする。(`ftp://ring.etl.go.jp/archives/pc/gnu-win32/cygwin-b20/cygwin1-20.1.dll.bz2`)
   2. bzip2 で展開する。
   3. `cygwin1.dll` にリネームする。
   4. `c:\windows` ディレクトリにコピーする。

6. Emulin のビルド方法

   **Unix 環境の場合**

   必要なツール:
   - JDK 1.1.6 以上
   - GNU make
   - perl 5
   - egrep, awk, sort, uniq

   ビルド: `root/` ディレクトリで `make` を実行。

   **Windows 環境の場合**

   必要なツール:
   - JDK 1.1.6 以上
   - cygwin32 b20.1
   - Windows 用 perl

   ビルド: `root\` ディレクトリで `make` を実行。

## 6) 使用方法

### 起動方法

- Windows の場合: `boot.bat` を実行
- Linux の場合: `boot` を実行

> **ヒント!** Windows の場合は `boot.bat` のショートカットをデスクトップなどに作るとよいでしょう。

### コマンドスペック

```
java emulin.Emulin [ルートパス] [switch] [elf バイナリファイル]
```

**スイッチ一覧:**

| スイッチ | 説明 |
|----------|------|
| `-CN` | ネイティブコンソールを使う (JNI を使用) |
| `-D`  | デバッグ情報を表示する |
| `-V`  | バーボーズ情報を表示する |
| `-V2` | より詳しいバーボーズ情報を表示する |
| `-S`  | セットアップ用 |

ルートパスは仮想ファイルパス名で指定します。Emulin 環境では、root を起点とした完全に閉じたファイルツリーでファイルを扱います。

### emulin.cnf について

`emulin.cnf` は仮想パス上の `/etc` ディレクトリ内に存在している必要があります。

設定パラメータ:

| パラメータ | 説明 |
|------------|------|
| `uid [0-9]*` | 実行時のユーザー ID を指定する |
| `gid [0-9]*` | 実行時のグループ ID を指定する |

### 実行例

```
# C:\root 以下のパスから
java emulin.Emulin C:\root /bin/ash
```

> ※ ash コマンドを実行するためには、emulin basic package が必要です。

## 7) mount, umount の注意事項

version 0.2.11b から mount 機能がサポートされています。

> ※ mount, umount プログラムは bap-1.0.6 以降に収録されています。

### mount

```
mount -t ext2 [local ファイルパス] [マウントポイント]
```

`/etc/fstab` を用意すると `mount -a` でマウントすることができます。

Windows の場合の `/etc/fstab` の例:

```
C:\	/win	ext2	defaults	0 0
M:\	/home	ext2	defaults	0 0
```

### umount

```
umount [local ファイルパス]
# または
umount [マウントポイント]
```

## 8) 連絡先

バグ、御意見、御要望があれば下記まで御連絡ください。

- e-mail: kiyokasumibi@gmail.com

