# tests/scripts/debug.mk — Emulin デバッグ用ヘルパー (Phase 7+ 以降)
#
# パイプ + 連結シェルで許可ダイアログが出ないよう、定型手順は
# Makefile ターゲットにまとめて単一コマンドで実行する。
#
# 主要な使い方:
#   make -f tests/scripts/debug.mk build         ... mvn compile
#   make -f tests/scripts/debug.mk regression    ... 全回帰テスト
#   make -f tests/scripts/debug.mk testbins      ... テストバイナリのビルド
#   make -f tests/scripts/debug.mk all           ... build + testbins + regression
#
#   make -f tests/scripts/debug.mk run T=<name>  ... 任意のテストを stdout/stderr 分離して実行
#   make -f tests/scripts/debug.mk run-stdout T=<name>  ... stdout のみ
#   make -f tests/scripts/debug.mk run-stderr T=<name>  ... stderr のみ
#   make -f tests/scripts/debug.mk single T=<name>      ... bash run-test.sh
#
#   make -f tests/scripts/debug.mk run-static    ... hello_static64 を実行 (デフォルト)
#
#   make -f tests/scripts/debug.mk addr   B=<bin> ADDR=0x...        ... addr2line
#   make -f tests/scripts/debug.mk disasm B=<bin> START=0x... END=0x... ... objdump 範囲
#   make -f tests/scripts/debug.mk syms   B=<bin> SYM=<regex>       ... nm | grep

ROOT     := $(CURDIR)
BIN_DIR  := tests/binaries/bin
SAND     := tests/sandbox
CLASSES  := target/classes

# デフォルトのテスト名 (T= で上書き可)
T ?= hello_static64
B ?= $(BIN_DIR)/$(T)

.PHONY: build testbins all run run-stdout run-stderr run-static single regression addr disasm syms cleanlogs

build:
	@mvn -q compile

testbins:
	@$(MAKE) -C tests/binaries

all: build testbins regression

run: build
	@mkdir -p $(SAND)/bin
	@cp $(BIN_DIR)/$(T) $(SAND)/bin/$(T)
	@cd $(SAND) && timeout 15 java -cp $(ROOT)/$(CLASSES) emulin.Emulin "$$(pwd -P)" /bin/$(T) < /dev/null > /tmp/emulin.stdout.txt 2> /tmp/emulin.stderr.txt; echo "exit=$$?"
	@echo "=== STDOUT ==="
	@cat /tmp/emulin.stdout.txt
	@echo "=== STDERR (head -40) ==="
	@head -40 /tmp/emulin.stderr.txt
	@echo "=== STDERR (tail -10) ==="
	@tail -10 /tmp/emulin.stderr.txt

run-stdout: build
	@mkdir -p $(SAND)/bin
	@cp $(BIN_DIR)/$(T) $(SAND)/bin/$(T)
	@cd $(SAND) && timeout 15 java -cp $(ROOT)/$(CLASSES) emulin.Emulin "$$(pwd -P)" /bin/$(T) < /dev/null 2>/dev/null

run-stderr: build
	@mkdir -p $(SAND)/bin
	@cp $(BIN_DIR)/$(T) $(SAND)/bin/$(T)
	@cd $(SAND) && timeout 15 java -cp $(ROOT)/$(CLASSES) emulin.Emulin "$$(pwd -P)" /bin/$(T) < /dev/null 2>&1 1>/dev/null

run-static:
	@$(MAKE) -f tests/scripts/debug.mk run T=hello_static64

single: build
	@bash tests/scripts/run-test.sh $(T)

regression: build
	@bash tests/scripts/run-all.sh

addr:
	@addr2line -e $(B) -f $(ADDR)

disasm:
	@objdump -d $(B) --start-address=$(START) --stop-address=$(END)

syms:
	@nm $(B) | grep -E "$(SYM)"

cleanlogs:
	@rm -f /tmp/emulin.stdout.txt /tmp/emulin.stderr.txt /tmp/hs64.stdout.txt /tmp/hs64.stderr.txt
