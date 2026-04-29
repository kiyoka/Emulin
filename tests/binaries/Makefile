# --------------------------------------------------------------------
#  tests/binaries/Makefile
#
#  i386 静的リンク ELF テストバイナリのビルド。
#
#  使い方:
#    make all              ... bin/ にバイナリを生成
#    make all CROSS=...    ... クロスコンパイラを明示
#    make docker           ... Docker (i386/debian) 内でビルド
#    make clean            ... bin/ を消す
#
#  優先順位:
#    1. CROSS が指定されていればそれを使う
#    2. i686-linux-gnu-gcc があればそれを使う
#    3. gcc -m32 が動くならそれを使う (Linux/x86_64 ホスト想定)
#    4. それ以外は失敗。`make docker` を案内する
# --------------------------------------------------------------------

SRC_DIR := src
BIN_DIR := bin

CROSS ?=
ifeq ($(CROSS),)
  CROSS_TRY := $(shell command -v i686-linux-gnu-gcc 2>/dev/null)
  ifneq ($(CROSS_TRY),)
    CROSS := $(CROSS_TRY)
  endif
endif
ifeq ($(CROSS),)
  ifeq ($(shell uname -s),Linux)
    GCC_M32_OK := $(shell gcc -m32 -xc -E - </dev/null >/dev/null 2>&1 && echo yes)
    ifeq ($(GCC_M32_OK),yes)
      CROSS := gcc -m32
    endif
  endif
endif

CFLAGS32 := -O0 -g -nostdlib -fno-pie -fno-stack-protector -fno-builtin \
            -ffreestanding -m32 -static -Wall
CFLAGS64 := -O0 -g -nostdlib -fno-pie -fno-stack-protector -fno-builtin \
            -ffreestanding -static -Wall -Wno-unused-function
LDFLAGS  := -Wl,--build-id=none

# 64-bit バイナリ: -nostdlib 版 (シンプルなシステムコールテスト)
# sys_*64.c は 1 syscall 1 テストの個別検証バイナリ
# *64.c で終わるその他 (myls64.c 等) も 64-bit としてビルドする
EXTRA_64_SRCS  := $(wildcard $(SRC_DIR)/sys_*64.c) $(wildcard $(SRC_DIR)/myls64.c) $(wildcard $(SRC_DIR)/argvdump64.c)
EXTRA_64_NAMES := $(patsubst $(SRC_DIR)/%.c,%,$(EXTRA_64_SRCS))

SRCS64_NAMES := hello64 echo_stdin64 fileio64 $(EXTRA_64_NAMES)

# 64-bit バイナリ: -static 版 (glibc 込み, gcc -static)
SRCS64_STATIC_NAMES := hello_static64 ctype_static64 varexp_repro64

SRCS64 := $(patsubst %,$(SRC_DIR)/%.c,$(SRCS64_NAMES))

# 32-bit バイナリ (クロスコンパイラ必須)
SRCS32 := $(filter-out $(SRCS64) $(patsubst %,$(SRC_DIR)/%.c,$(SRCS64_STATIC_NAMES)),$(wildcard $(SRC_DIR)/*.c))
BINS32 := $(patsubst $(SRC_DIR)/%.c,$(BIN_DIR)/%,$(SRCS32))

# 64-bit バイナリ (ネイティブ GCC でビルド)
BINS64        := $(patsubst %,$(BIN_DIR)/%,$(SRCS64_NAMES))
BINS64_STATIC := $(patsubst %,$(BIN_DIR)/%,$(SRCS64_STATIC_NAMES))

.PHONY: all clean docker check-cross

all: check-cross $(BINS32) $(BINS64) $(BINS64_STATIC)

check-cross:
	@if [ -z "$(CROSS)" ]; then \
	  echo "ERROR: i386 cross compiler not found."; \
	  echo "  Try one of:"; \
	  echo "    make all CROSS=/path/to/i686-linux-gnu-gcc"; \
	  echo "    make docker     # use Docker fallback"; \
	  echo "  On Debian/Ubuntu: apt install gcc-i686-linux-gnu"; \
	  exit 1; \
	fi
	@echo "Using CROSS=$(CROSS)"

$(BIN_DIR)/%: $(SRC_DIR)/%.c | $(BIN_DIR)
	$(CROSS) $(CFLAGS32) $(LDFLAGS) -o $@ $<

# 64-bit バイナリはネイティブ GCC でビルド (クロスコンパイラ不要)
$(BIN_DIR)/hello64: $(SRC_DIR)/hello64.c | $(BIN_DIR)
	gcc $(CFLAGS64) $(LDFLAGS) -o $@ $<

$(BIN_DIR)/echo_stdin64: $(SRC_DIR)/echo_stdin64.c | $(BIN_DIR)
	gcc $(CFLAGS64) $(LDFLAGS) -o $@ $<

$(BIN_DIR)/fileio64: $(SRC_DIR)/fileio64.c | $(BIN_DIR)
	gcc $(CFLAGS64) $(LDFLAGS) -o $@ $<

# sys_*64: 1 syscall 1 テストの個別検証バイナリ (sys64.h を include)
$(BIN_DIR)/sys_%64: $(SRC_DIR)/sys_%64.c $(SRC_DIR)/sys64.h | $(BIN_DIR)
	gcc $(CFLAGS64) $(LDFLAGS) -o $@ $<

# myls64 や他の *64.c (sys_ 以外) も同じ要領でビルド
$(BIN_DIR)/myls64: $(SRC_DIR)/myls64.c $(SRC_DIR)/sys64.h | $(BIN_DIR)
	gcc $(CFLAGS64) $(LDFLAGS) -o $@ $<

$(BIN_DIR)/argvdump64: $(SRC_DIR)/argvdump64.c $(SRC_DIR)/sys64.h | $(BIN_DIR)
	gcc $(CFLAGS64) $(LDFLAGS) -o $@ $<

# -static 版: glibc を静的リンク (nostdlib なし)
$(BIN_DIR)/hello_static64: $(SRC_DIR)/hello_static64.c | $(BIN_DIR)
	gcc -O0 -static -Wl,--build-id=none -o $@ $<

$(BIN_DIR)/ctype_static64: $(SRC_DIR)/ctype_static64.c | $(BIN_DIR)
	gcc -O0 -static -Wl,--build-id=none -o $@ $<

$(BIN_DIR)/varexp_repro64: $(SRC_DIR)/varexp_repro64.c | $(BIN_DIR)
	gcc -O0 -static -Wl,--build-id=none -o $@ $<

$(BIN_DIR):
	mkdir -p $@

clean:
	rm -rf $(BIN_DIR)

# ----- Docker フォールバック ----------------------------------------
# i386/debian イメージを使ってコンテナ内でビルドする。
# ホストに gcc-i686-linux-gnu が入っていない macOS 等で利用。
docker:
	docker run --rm --platform linux/i386 \
	  -v "$(CURDIR)":/work -w /work \
	  i386/debian:bookworm-slim \
	  bash -c "apt-get update -qq && apt-get install -qq -y --no-install-recommends gcc make >/dev/null && make all CROSS='gcc -m32'"
