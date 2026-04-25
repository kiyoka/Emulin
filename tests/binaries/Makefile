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

CFLAGS  := -O0 -g -nostdlib -fno-pie -fno-stack-protector -fno-builtin \
           -ffreestanding -m32 -static -Wall
LDFLAGS := -Wl,--build-id=none

SRCS := $(wildcard $(SRC_DIR)/*.c)
BINS := $(patsubst $(SRC_DIR)/%.c,$(BIN_DIR)/%,$(SRCS))

.PHONY: all clean docker check-cross

all: check-cross $(BINS)

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
	$(CROSS) $(CFLAGS) $(LDFLAGS) -o $@ $<

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
