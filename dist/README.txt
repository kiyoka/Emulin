Emulin
======

Java で動く 32/64-bit Linux ELF エミュレータ。busybox を同梱しているので、
Windows / macOS / Linux のどこでも素早く Linux のシェル環境 (busybox ash)
を立ち上げられる。動的リンクの実機 binary (git / curl / openssl / python 等)
にも対応。

------------------------------------------------------------
必要な物
------------------------------------------------------------
- Java Runtime Environment 11 以降 (Adoptium / Microsoft OpenJDK 等)
  PATH に `java` が入っていれば OK


============================================================
A. クイックスタート: busybox 対話シェル
============================================================

解凍したディレクトリで:

  Windows  :  emulin.bat
  Linux/mac:  ./emulin.sh

引数なしで起動すると busybox ash の対話シェルが立ち上がる。

  $ ./emulin.sh
  / # echo hello
  hello
  / # ls /bin
  busybox
  / # exit

引数を付けると 1 コマンド実行モード:

  ./emulin.sh ls -la /tmp
  ./emulin.sh ash -c 'for i in $(seq 1 5); do echo $i; done'
  ./emulin.sh sh -c 'echo $((6*7))'


============================================================
B. 実機 Linux binary (git / curl / openssl 等) を動かす
============================================================

実機 binary を動かすには、host の Linux 環境から sandbox を構築する必要が
ある (locale files / ld.so.cache / SSL cert など)。

build-sandbox.sh を使って sandbox を構築 (Debian/Ubuntu 系を想定):

  ./dist/build-sandbox.sh /tmp/my-sandbox base    # 実機 binary 用 (~5 MB)
  ./dist/build-sandbox.sh /tmp/my-sandbox full    # + git/curl/openssl 同梱 (~91 MB)

Level 別:

  minimal — busybox のみ (~2 MB、 dist-zip 同等)
  base    — minimal + locale / ld.so.cache / SSL cert / 基本 config (~5 MB)
            実機 binary を動かすための前提条件を満たす。binary は別途追加。
  full    — base + git / curl / openssl / python と必要 .so 一式 (~91 MB)
            ldd で binary の依存 .so を解析して最小限だけコピー。

実行例 (full sandbox + git --version):

  cd /tmp/my-sandbox
  java -XX:-DontCompileHugeMethods \
    -jar lib/emulin-*-all.jar . /usr/bin/git --version

実行例 (full sandbox + git clone HTTPS、約 10 秒):

  cd /tmp/my-sandbox
  java -XX:-DontCompileHugeMethods \
    -jar lib/emulin-*-all.jar . \
    /usr/bin/git clone --depth=1 \
      https://github.com/octocat/Hello-World.git /tmp/cloned


============================================================
C. パフォーマンス
============================================================

実機 binary を動かす時は **`-XX:-DontCompileHugeMethods`** を必ず付ける:

  java -XX:-DontCompileHugeMethods -jar lib/emulin-*-all.jar ...

このフラグが無いと emulator の中核 dispatch loop (decode_and_exec、
20K+ bytecode) が JVM の `HugeMethodLimit` (default 8000 byte) で
JIT C2 compile を拒否され、interpreter モードで実行される。
git clone HTTPS で 28% 高速化する (14.4s → 10.4s)。

emulin.sh / emulin.bat ランチャは自動的にこのフラグを付ける。
直接 java を呼ぶ場合は手動で付ける必要あり。


============================================================
D. ディレクトリ構成
============================================================

  emulin.bat / emulin.sh   起動ランチャ (busybox 用)
  lib/emulin-*-all.jar     Emulin 本体 + JLine (fat jar)
  dist/build-sandbox.sh    sandbox 構築スクリプト (実機 binary 用)
  rootfs/                  busybox 用の最小 rootfs
    bin/busybox            静的リンクの busybox (Linux x86-64 ELF)
    etc/emulin.cnf         emulin の設定 (空でも可)
    tmp/                   作業ディレクトリ


============================================================
E. 動作する実機 binary (例)
============================================================

- coreutils 24 種類 (cat / ls / cp / mv / sort / 等)
- bash, busybox 88 applet
- python 3.12 (一部 syscall 制約あり)
- openssl 3.0.13 (AES-NI / PCLMULQDQ 完全実装)
- wget HTTP / HTTPS (iana RSA cert + TLS 1.3 確実動作)
- curl HTTP, curl --version
- git: init / add / commit / log / status / diff / clone (git:// と
  https:// 両方、cert verify あり)


============================================================
F. 既知の制約
============================================================

- IPv6 (AF_INET6) 未対応 — getaddrinfo は IPv4 のみ
- Python 3 の一部 syscall (signalfd4 等) 未対応
- emulator の実行速度は host より大幅に遅い (300x 程度)
  → CA cert load が 83 秒かかる等。dist/build-sandbox.sh の base level は
    /etc/gitconfig に sslCAInfo+sslCAPath= の workaround を自動設定する
- WSL DrvFs (`/mnt/c/...`) は I/O 遅い。sandbox は Linux /tmp 等に置く
- 単独で git clone --hardlinks file:// は inode 検証で失敗 (--no-hardlinks
  なら動作)


============================================================
G. トラブルシュート
============================================================

「current path is out of virtual path area」エラー:
  → sandbox dir に cd してから java を起動する必要あり
  → emulin.sh / emulin.bat は自動で対処

「gnutls_handshake() failed: TLS connection was non-properly terminated」:
  → CA cert ロードが遅すぎて server が idle timeout
  → /etc/gitconfig に sslCAInfo + sslCAPath= を設定 (build-sandbox.sh が自動)

「locale: Cannot set LC_ALL to default locale」:
  → /usr/lib/locale/C.utf8 が sandbox に無い
  → build-sandbox.sh base level で自動コピーされる

git clone HTTPS が「Failed sending HTTP request」/「Send failure: Broken pipe」:
  → 同じく cert ロード遅延 → server timeout。上記 sslCAInfo workaround で解決



==============================================================================
License / Copyright Notice
==============================================================================

This distribution bundles:

  1. Emulin core           — GPL v2 (see COPYING)
  2. Eclipse Temurin JRE   — GPL v2 + Classpath Exception (see jre/legal/)
  3. BusyBox               — GPL v2
  4. JLine 3 / ASM         — BSD-3-Clause (compiled into the fat jar)
  5. Real Linux binaries   — Various (GPL-2/3, LGPL, BSD, MIT, Apache, OpenSSH,
                              curl, Vim, Artistic+GPL for Perl, PSF for Python)

For the full upstream copyright text of each rootfs/ binary, see:

  rootfs/usr/share/doc/<package>/copyright

For a one-page inventory of bundled components with versions and source URLs:

  THIRD-PARTY-LICENSES.md

For GPL/LGPL source code availability and the legal written offer:

  NOTICE.txt

The Emulin maintainer hereby offers to provide, for at least 3 years from
the release date, a complete machine-readable copy of the corresponding
source for any GPL/LGPL package bundled here, upon request, at the cost of
physical media and shipping. Contact via https://github.com/kiyoka/emulin .
