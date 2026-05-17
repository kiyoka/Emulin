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
