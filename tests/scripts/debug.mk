# tests/scripts/debug.mk — Phase 7 デバッグ用ヘルパー
#
# 使い方:
#   make -f tests/scripts/debug.mk run          ... hello_static64 を実行 (stdout/stderr 分離)
#   make -f tests/scripts/debug.mk run-stdout   ... stdout のみ
#   make -f tests/scripts/debug.mk run-stderr   ... stderr のみ
#   make -f tests/scripts/debug.mk regression   ... 全回帰テスト
#   make -f tests/scripts/debug.mk build        ... mvn compile
#   make -f tests/scripts/debug.mk addr ADDR=0x... ... addr2line でシンボル解決
#   make -f tests/scripts/debug.mk disasm START=0x... END=0x... ... 範囲を逆アセンブル
#
# Bash パイプを許可リストに増やさず単一コマンドで実行するためのまとめ。

ROOT     := $(CURDIR)
BIN_DIR  := tests/binaries/bin
SAND     := tests/sandbox
CLASSES  := target/classes
HBIN     := $(BIN_DIR)/hello_static64

.PHONY: build run run-stdout run-stderr regression addr disasm syms cleanlogs

build:
	@mvn -q compile

run: build
	@mkdir -p $(SAND)/bin
	@cp $(HBIN) $(SAND)/bin/hello_static64
	@cd $(SAND) && timeout 15 java -cp $(ROOT)/$(CLASSES) emulin.Emulin "$$(pwd -P)" /bin/hello_static64 < /dev/null > /tmp/hs64.stdout.txt 2> /tmp/hs64.stderr.txt; echo "exit=$$?"
	@echo "=== STDOUT ==="
	@cat /tmp/hs64.stdout.txt
	@echo "=== STDERR (head -40) ==="
	@head -40 /tmp/hs64.stderr.txt
	@echo "=== STDERR (tail -10) ==="
	@tail -10 /tmp/hs64.stderr.txt

run-stdout: build
	@mkdir -p $(SAND)/bin
	@cp $(HBIN) $(SAND)/bin/hello_static64
	@cd $(SAND) && timeout 15 java -cp $(ROOT)/$(CLASSES) emulin.Emulin "$$(pwd -P)" /bin/hello_static64 < /dev/null 2>/dev/null

run-stderr: build
	@mkdir -p $(SAND)/bin
	@cp $(HBIN) $(SAND)/bin/hello_static64
	@cd $(SAND) && timeout 15 java -cp $(ROOT)/$(CLASSES) emulin.Emulin "$$(pwd -P)" /bin/hello_static64 < /dev/null 2>&1 1>/dev/null

regression: build
	@bash tests/scripts/run-all.sh

addr:
	@addr2line -e $(HBIN) -f $(ADDR)

disasm:
	@objdump -d $(HBIN) --start-address=$(START) --stop-address=$(END)

syms:
	@nm $(HBIN) | grep -E "$(SYM)"

cleanlogs:
	@rm -f /tmp/hs64.stdout.txt /tmp/hs64.stderr.txt
