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

# issue #216: Windows は TERM を持たないので、未設定なら xterm-256color を与える。
# vt100 だと emacs が修飾キー (Shift+矢印 = \e[1;2C 等) を decode できず、
# shift-select (範囲選択 → M-w/C-w コピペ) が壊れる。Windows Terminal は xterm 互換。
# 既存 TERM は尊重 (Linux/macOS の host TERM をそのまま使う)。
export TERM="${TERM:-xterm-256color}"

# issue #212: ホスト OS の既存環境変数を guest に引き継ぐ。emulin が必須と
# する PATH/HOME 等は emulin 側 (Kernel.boot) で上書きされるので host が
# 勝つことはない。既存値を尊重するので EMULIN_INHERIT_ENV=0 を export すれば
# 従来 (whitelist のみ通す) 動作に戻せる。
export EMULIN_INHERIT_ENV="${EMULIN_INHERIT_ENV:-1}"
# issue #221 C-1: HW 仮想化 (KVM /dev/kvm / Hyper-V WHP) があれば native backend で実 vCPU
#   実行 (compute ~200x、HTTPS/git clone は software 完走不可を秒で処理)。無ければ software
#   に自動 fallback。EMULIN_BACKEND を直接 export すれば尊重 (CI/テストは software のまま)。
export EMULIN_BACKEND="${EMULIN_BACKEND:-auto}"
# issue #221 C-3: native backend の物理プール。512MB default は大きな git clone (index-pack が
#   pack 全体を mmap) で枯渇する。KVM は lazy mmap なので大きく取っても安い。CI/テストは
#   env 未設定で 512MB 据置。
export EMULIN_NATIVE_POOL_MB="${EMULIN_NATIVE_POOL_MB:-2048}"

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
# issue #401: TLS-MITM (EMULIN_EGRESS_MITM) 有効時は EmulinCA が sun.security.x509 で
#   CA/leaf cert を生成するため add-exports が要る (cert pure Java 生成、依存追加ゼロ)。
if [ -n "$EMULIN_EGRESS_MITM" ]; then
    JVM_OPTS+=( --add-exports java.base/sun.security.x509=ALL-UNNAMED \
                --add-exports java.base/sun.security.util=ALL-UNNAMED \
                --add-exports java.base/sun.security.tools.keytool=ALL-UNNAMED )
fi
cd "$ROOTFS"
# issue #219: `emulin.sh sshd [port]` で OpenSSH sshd を SSH サーバとして起動。
#   Tera Term/PuTTY 等の SSH クライアントから接続すると端末が Ctrl+Space=NUL /
#   修飾キーを正しく送るので Windows console の制約 (issue #216) を回避できる。
if [ "${1:-}" = "sshd" ]; then
    SSHD_PORT="${2:-2222}"
    if [ ! -x "$ROOTFS/usr/sbin/sshd" ]; then
        echo "emulin: sshd not bundled (need a bundle built with INCLUDE_SSHD=1)" >&2
        exit 2
    fi
    if [ ! -s "$ROOTFS/root/.ssh/authorized_keys" ]; then
        echo "[emulin sshd] WARNING: no public key registered. Add your SSH client's" >&2
        echo "  public key to: $ROOTFS/root/.ssh/authorized_keys" >&2
    fi
    echo "[emulin sshd] OpenSSH sshd on 127.0.0.1:$SSHD_PORT (user=root, publickey) - Ctrl-C to stop"
    echo "[emulin sshd]   connect: ssh -p $SSHD_PORT root@127.0.0.1"
    echo "[emulin sshd]   Tera Term: Host=localhost / TCP port=$SSHD_PORT / User=root / publickey"
    # sshd は group/world-readable な host key を拒否する。Windows NTFS では
    # emulin が mode 未保存 file を 0755 と報告するので、先に 600 を NTFS ADS
    # へ保存する (chmod は process を跨いで persist する)。
    java "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" /bin/busybox chmod 600 /etc/ssh/ssh_host_ed25519_key >/dev/null 2>&1 || true
    exec java "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" /usr/sbin/sshd -D -e -p "$SSHD_PORT" -f /etc/ssh/sshd_config
fi
if [ $# -eq 0 ]; then
    exec java "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" -CJ /bin/busybox ash -i
else
    exec java "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" -CJ /bin/busybox "$@"
fi
