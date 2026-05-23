#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/claude-smoke.sh
#
#  issue #87 / #63 の hermetic 動作確認。
#
#  emulin 上で claude (Bun 製ネイティブ ELF、Claude Code) を起動し、
#  `claude --version` が "X.Y.Z (Claude Code)" を出力したら PASS。
#
#  確認する経路:
#    - Bun ランタイム起動 (gigacage は sparse paging で backing、有効のまま)
#    - /proc/self/maps の動的生成 (stack 境界)
#    - simdutf の SSE4.2 (PCMPISTRI 等) UTF-8→UTF-16 デコード経路
#  gigacage は無効化しない (= host と同じデフォルト構成を検証)。
#
#  SKIP 条件:
#    - emulin の target/classes が無い
#    - claude binary が無い (238MB、リポジトリには含めない)
#        → 環境変数 CLAUDE_BIN で path を指定するか、
#          /tmp/node-sb/usr/bin/claude 等の既知 path に置く
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes
# classpath: fat jar (asm 込み) があればそれ、無ければ classes (JIT off なら asm 不要)
FATJAR=$(ls "$PROJECT"/target/emulin-*-all.jar 2>/dev/null | head -1)
CP="${FATJAR:-$CLASSES}"

if [ ! -f "$CLASSES/emulin/Emulin.class" ] && [ -z "$FATJAR" ]; then
    echo "SKIP claude-smoke : Emulin not built ($CLASSES/emulin/Emulin.class)"
    echo "  run 'mvn compile' first"
    exit 2
fi

# claude binary を探す: CLAUDE_BIN > 既知の候補 path
CLAUDE_BIN="${CLAUDE_BIN:-}"
if [ -z "$CLAUDE_BIN" ]; then
    for cand in /tmp/node-sb/usr/bin/claude "$HOME/.local/bin/claude"; do
        if [ -x "$cand" ]; then CLAUDE_BIN=$cand; break; fi
    done
fi
if [ -z "$CLAUDE_BIN" ] || [ ! -x "$CLAUDE_BIN" ]; then
    echo "SKIP claude-smoke : claude binary が見つからない (CLAUDE_BIN で指定可)"
    exit 2
fi

# 並列実行を考慮して TMP に独立 sandbox を作る
SB=$(mktemp -d -t emulin-claude-smoke.XXXXXX)
trap 'pkill -9 -f "emulin.Emulin $SB" 2>/dev/null || true; rm -rf "$SB" 2>/dev/null || true' EXIT

mkdir -p "$SB"/{bin,usr/bin,etc,tmp,dev,root,proc/self,sys/fs/cgroup/memory,lib64,usr/lib/x86_64-linux-gnu}
if [ -L /lib ]; then ln -sf usr/lib "$SB/lib"; else mkdir -p "$SB/lib/x86_64-linux-gnu"; fi

# .so を real path 解決して sandbox に copy
copy_solib() {
    local p=$1
    [ -f "$p" ] || return 0
    local real; real=$(readlink -f "$p")
    [ -f "$real" ] || return 0
    mkdir -p "$SB$(dirname "$real")"; cp -L "$real" "$SB${real}" 2>/dev/null || true
    if [ "$real" != "$p" ]; then
        mkdir -p "$SB$(dirname "$p")"; cp -L "$real" "$SB${p}" 2>/dev/null || true
    fi
}
copy_solib /lib64/ld-linux-x86-64.so.2
# ldd で依存 .so を展開して全部 copy
while IFS= read -r line; do
    p=""
    if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then p="${BASH_REMATCH[1]}"
    elif [[ "$line" =~ ^[[:space:]]*(/[^[:space:]]+) ]]; then p="${BASH_REMATCH[1]}"; fi
    [ -n "$p" ] && copy_solib "$p"
done < <(ldd "$CLAUDE_BIN" 2>/dev/null)

# claude binary を配置
cp -L "$CLAUDE_BIN" "$SB/usr/bin/claude" 2>/dev/null || { echo "FAIL claude-smoke : claude copy 失敗"; exit 1; }
chmod +x "$SB/usr/bin/claude"

# Bun が起動時に読む補助ファイル (無くても動くが host 寄りにしておく)
#   /proc/self/maps は emulin が動的生成するので不要。
printf '0::/\n' > "$SB/proc/self/cgroup"
printf '9223372036854771712\n' > "$SB/sys/fs/cgroup/memory/memory.limit_in_bytes" 2>/dev/null || true

# emulin で claude --version を実行 (gigacage は有効のまま = 既定構成)
OUT=$(cd "$SB/root" && timeout 360 env -i \
    HOME=/root USER=root LOGNAME=root SHELL=/bin/sh TERM=xterm \
    PATH=/usr/bin:/bin LANG=C.UTF-8 LC_ALL=C.UTF-8 \
    OPENSSL_CONF=/dev/null LD_BIND_NOW=1 UV_USE_IO_URING=0 \
    java -Xmx8g -cp "$CP" emulin.Emulin \
    "$SB" /usr/bin/claude --version 2>/dev/null)
RC=$?

if [ "$RC" = 124 ]; then
    echo "FAIL claude-smoke : timeout (360s 以内に --version が完了せず)"
    exit 1
fi
# version は更新で変わるので "(Claude Code)" のパターンで判定
if echo "$OUT" | grep -Eq '[0-9]+\.[0-9]+\.[0-9]+ \(Claude Code\)'; then
    echo "PASS claude-smoke : $(echo "$OUT" | grep -E '\(Claude Code\)' | head -1)"
    exit 0
fi

echo "FAIL claude-smoke : expected 'X.Y.Z (Claude Code)' got '$OUT'"
exit 1
