#------------------------------------------------------------
#  Makefile for Emulin
#
#  Java のビルドは Maven に移行済み。
#  以下のターゲットはコード生成ツール (mkope / mkinstid) のみ残す。
#------------------------------------------------------------

REL_VER = 0.2.13b

# --- Java ビルド (Maven) ---
.PHONY: compile package clean

compile:
	mvn compile

package:
	mvn package

clean:
	mvn clean

# --- コード生成 (Decoder.java / XInstruction.java) ---
# 通常は生成済みファイルをそのまま使う。opecode.dat を変更した場合のみ実行する。
src/main/java/emulin/Decoder.java: emulin/opecode.dat emulin/mkope
	emulin/mkope < emulin/opecode.dat > src/main/java/emulin/Decoder.java

src/main/java/emulin/XInstruction.java: emulin/opecode.dat emulin/mkinstid
	emulin/mkinstid < emulin/opecode.dat > src/main/java/emulin/XInstruction.java
