Emulin
======

Java で動く 32/64-bit Linux ELF エミュレータ。busybox を同梱しており、
Windows / macOS / Linux のどこでも Linux のシェル環境 (busybox ash) を
立ち上げられる。

------------------------------------------------------------
必要な物
------------------------------------------------------------
- Java Runtime Environment 11 以降 (Adoptium / Microsoft OpenJDK 等)
  PATH に `java` が入っていれば OK

------------------------------------------------------------
使い方
------------------------------------------------------------
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

引数を付けると 1 コマンド実行モード (busybox 直叩き):

  ./emulin.sh ls -la /tmp
  ./emulin.sh ash -c 'for i in $(seq 1 5); do echo $i; done'
  ./emulin.sh sh -c 'echo $((6*7))'

------------------------------------------------------------
ディレクトリ構成
------------------------------------------------------------
  emulin.bat / emulin.sh   起動ランチャ
  lib/emulin-*-all.jar     Emulin 本体 + JLine (fat jar)
  rootfs/                  Emulin が見る仮想ルート
    bin/busybox            静的リンクの busybox (Linux x86-64 ELF)
    etc/emulin.cnf         emulin の設定 (空でも可)
    tmp/                   作業ディレクトリ

------------------------------------------------------------
既知の制約
------------------------------------------------------------
- 動的リンク ELF はまだ未対応 (busybox 等の静的リンク専用)
- Windows コンソールでの矢印キー / Ctrl-C は JLine 経由
  (jline-terminal 3.x が入った fat jar に同梱)
- 実 tty でのみ raw mode が有効。パイプ入力では cooked モード
- /proc は最小限のスタブのみ
