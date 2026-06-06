#!/usr/bin/env bash
# --------------------------------------------------------------------
#  Emulin launcher (POSIX)
#
#  使い方:
#    ./emulin.sh                  # busybox ash -i (対話シェル)
#    ./emulin.sh ls /             # busybox ls / を 1 回実行
#    ./emulin.sh ash -c 'echo hi'
#
#  必要な物: java (JRE 11+) が PATH に存在すること
# --------------------------------------------------------------------
set -u

# issue #59: sandbox 内 root user (uid=0) の home directory として
# /root を露出。bash の `cd` (引数なし)、ssh の ~/.ssh/、vim の ~/.vimrc 等
# が動作する。本 script の scope のみで host 側 HOME には影響しない。
export HOME=/root

# UTF-8 locale を保証する。Windows と違い通常は LANG 設定済だが、最小環境で
# 未設定だと emacs 等が ASCII へ fallback し UTF-8 (日本語/中国語) を "?" に
# 化けさせる。C.UTF-8 は glibc 組込みで locale ファイル不要。既存 LANG は尊重。
export LANG="${LANG:-C.UTF-8}"

# issue #212: ホスト OS の既存環境変数を guest に引き継ぐ。emulin が必須と
# する PATH/HOME 等は emulin 側 (Kernel.boot) で上書きされるので host が
# 勝つことはない。既存値を尊重するので EMULIN_INHERIT_ENV=0 を export すれば
# 従来 (whitelist のみ通す) 動作に戻せる。
export EMULIN_INHERIT_ENV="${EMULIN_INHERIT_ENV:-1}"

HERE=$(cd "$(dirname "$0")" && pwd -P)
ROOTFS=$HERE/rootfs
JAR=$(ls "$HERE"/lib/emulin-*-all.jar 2>/dev/null | head -1)

if [ -z "${JAR:-}" ] || [ ! -f "$JAR" ]; then
    echo "emulin.sh: error: lib/emulin-*-all.jar not found under $HERE" >&2
    exit 2
fi
if ! command -v java >/dev/null 2>&1; then
    echo "emulin.sh: error: java not found on PATH (install JRE 11+)" >&2
    exit 2
fi
if [ ! -x "$ROOTFS/bin/busybox" ]; then
    echo "emulin.sh: error: $ROOTFS/bin/busybox not found or not executable" >&2
    exit 2
fi

# Phase 27 step 64: -XX:-DontCompileHugeMethods で Cpu64::decode_and_exec
# (20K+ bytecode) も JIT C2 コンパイルさせる。git clone HTTPS で 22% 高速化。
JVM_OPTS=( -XX:-DontCompileHugeMethods )
cd "$ROOTFS"
if [ $# -eq 0 ]; then
    exec java "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" -CJ /bin/busybox ash -i
else
    exec java "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" -CJ /bin/busybox "$@"
fi
