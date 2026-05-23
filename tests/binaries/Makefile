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
SRCS64_STATIC_NAMES := hello_static64 ctype_static64 varexp_repro64 fgetc_static64 bb_decode_static64 aesni_static64 sse_audit64

# 64-bit バイナリ: -no-pie で動的リンク (Phase 24 step 2 回帰固定)
# libc.so.6 と ld-linux-x86-64.so.2 を実機から sandbox に置いた上で動かす。
SRCS64_DYN_NAMES := hello_dyn64 printf_dyn64 regex_dyn64 cpp_dyn64 zlib_dyn64 sockpair_dyn64 sockpair_bidir_dyn64 mmap_dyn64 nested_dyn64 pie_static_dyn64 pie_dyn64 pthread_basic_dyn64 pthread_mutex_dyn64 pthread_sigmask_dyn64

SRCS64 := $(patsubst %,$(SRC_DIR)/%.c,$(SRCS64_NAMES))

# 32-bit バイナリ (クロスコンパイラ必須)
SRCS32 := $(filter-out $(SRCS64) $(patsubst %,$(SRC_DIR)/%.c,$(SRCS64_STATIC_NAMES)) $(patsubst %,$(SRC_DIR)/%.c,$(SRCS64_DYN_NAMES)),$(wildcard $(SRC_DIR)/*.c))
BINS32 := $(patsubst $(SRC_DIR)/%.c,$(BIN_DIR)/%,$(SRCS32))

# 64-bit バイナリ (ネイティブ GCC でビルド)
BINS64        := $(patsubst %,$(BIN_DIR)/%,$(SRCS64_NAMES))
BINS64_STATIC := $(patsubst %,$(BIN_DIR)/%,$(SRCS64_STATIC_NAMES))
BINS64_DYN    := $(patsubst %,$(BIN_DIR)/%,$(SRCS64_DYN_NAMES))

.PHONY: all clean docker check-cross

all: check-cross $(BINS32) $(BINS64) $(BINS64_STATIC) $(BINS64_DYN)

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

$(BIN_DIR)/fgetc_static64: $(SRC_DIR)/fgetc_static64.c | $(BIN_DIR)
	gcc -O0 -static -Wl,--build-id=none -o $@ $<

$(BIN_DIR)/bb_decode_static64: $(SRC_DIR)/bb_decode_static64.c | $(BIN_DIR)
	gcc -O0 -static -Wl,--build-id=none -o $@ $<

# Phase 27: AES-NI / PCLMULQDQ の回帰固定
$(BIN_DIR)/aesni_static64: $(SRC_DIR)/aesni_static64.c | $(BIN_DIR)
	gcc -O0 -maes -mpclmul -static -Wl,--build-id=none -o $@ $<

# issue #87: claude/Bun 対応で追加した SSE4.1 等の命令を host と差分検証
$(BIN_DIR)/sse_audit64: $(SRC_DIR)/sse_audit64.c | $(BIN_DIR)
	gcc -O0 -msse4.1 -static -Wl,--build-id=none -o $@ $<

# Phase 24 step 2 回帰固定: -no-pie 動的リンク
$(BIN_DIR)/hello_dyn64: $(SRC_DIR)/hello_dyn64.c | $(BIN_DIR)
	gcc -O0 -no-pie -Wl,--build-id=none -o $@ $<

# Phase 25: 浮動小数点 printf (bignum 経路) の回帰固定
$(BIN_DIR)/printf_dyn64: $(SRC_DIR)/printf_dyn64.c | $(BIN_DIR)
	gcc -O0 -no-pie -Wl,--build-id=none -o $@ $<

# Phase 25: POSIX regex の回帰固定 (PMAXUB 等の SSE 経路の検収)
$(BIN_DIR)/regex_dyn64: $(SRC_DIR)/regex_dyn64.c | $(BIN_DIR)
	gcc -O0 -no-pie -Wl,--build-id=none -o $@ $<

# Phase 25: C++ STL + 例外の回帰固定 (futex no-op の検収を含む)
$(BIN_DIR)/cpp_dyn64: $(SRC_DIR)/cpp_dyn64.cc | $(BIN_DIR)
	g++ -O0 -no-pie -Wl,--build-id=none -o $@ $<

# Phase 25: zlib 動的リンク回帰 (PINSRW の検収を兼ねる)
$(BIN_DIR)/zlib_dyn64: $(SRC_DIR)/zlib_dyn64.c | $(BIN_DIR)
	gcc -O0 -no-pie -Wl,--build-id=none -o $@ $< -lz

# Phase 25: socketpair (片方向) の回帰
$(BIN_DIR)/sockpair_dyn64: $(SRC_DIR)/sockpair_dyn64.c | $(BIN_DIR)
	gcc -O0 -no-pie -Wl,--build-id=none -o $@ $<

# Phase 27 step 21: socketpair 双方向化の回帰
$(BIN_DIR)/sockpair_bidir_dyn64: $(SRC_DIR)/sockpair_bidir_dyn64.c | $(BIN_DIR)
	gcc -O0 -no-pie -Wl,--build-id=none -o $@ $<

# Phase 27 step 28: pthread の基礎動作 (clone CLONE_VM + futex)
$(BIN_DIR)/pthread_basic_dyn64: $(SRC_DIR)/pthread_basic_dyn64.c | $(BIN_DIR)
	gcc -O0 -no-pie -Wl,--build-id=none -pthread -o $@ $<

# Phase 27 step 29: pthread mutex 競合下の動作確認
$(BIN_DIR)/pthread_mutex_dyn64: $(SRC_DIR)/pthread_mutex_dyn64.c | $(BIN_DIR)
	gcc -O0 -no-pie -Wl,--build-id=none -pthread -o $@ $<

# Phase 27 step 34: per-thread signal mask
$(BIN_DIR)/pthread_sigmask_dyn64: $(SRC_DIR)/pthread_sigmask_dyn64.c | $(BIN_DIR)
	gcc -O0 -no-pie -Wl,--build-id=none -pthread -o $@ $<

# Phase 25: mmap of regular file の回帰 (ページ境界切り上げ)
$(BIN_DIR)/mmap_dyn64: $(SRC_DIR)/mmap_dyn64.c | $(BIN_DIR)
	gcc -O0 -no-pie -Wl,--build-id=none -o $@ $<

# Phase 25: GCC ネスト関数 (実行可能スタック上のトランポリン) の回帰
$(BIN_DIR)/nested_dyn64: $(SRC_DIR)/nested_dyn64.c | $(BIN_DIR)
	gcc -O0 -no-pie -Wl,-z,execstack -Wl,--build-id=none -o $@ $<

# Phase 26: 静的 PIE (ET_DYN かつ libc 依存なし) の回帰
# load_bias による本体配置と rip-relative lea の動作確認用
$(BIN_DIR)/pie_static_dyn64: $(SRC_DIR)/pie_static_dyn64.c | $(BIN_DIR)
	gcc -nostdlib -fpie -pie -O0 -Wl,--build-id=none -o $@ $<

# Phase 26: 完全 PIE (gcc デフォルト) の回帰
$(BIN_DIR)/pie_dyn64: $(SRC_DIR)/pie_dyn64.c | $(BIN_DIR)
	gcc -O0 -Wl,--build-id=none -o $@ $<

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
