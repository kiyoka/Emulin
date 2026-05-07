#!/usr/bin/env bash
# --------------------------------------------------------------------
#  dist/build-sandbox.sh
#
#  実機 Linux binary を Emulin で動かすための sandbox を構築する。
#
#  使い方:
#    dist/build-sandbox.sh <sandbox_dir> [LEVEL]
#
#  LEVEL:
#    minimal — busybox のみ (= dist-zip 同等、デフォルト)
#    base    — minimal + locale / ld.so.cache / ssl certs / 基本 config
#              (実機 binary 動作の前提条件、step 58-59 で必須と判明)
#    full    — base + git/curl/openssl/python と必要 library (デモ用)
#
#  例:
#    dist/build-sandbox.sh /tmp/my-sandbox base
#    java -XX:-DontCompileHugeMethods \
#      -jar target/emulin-*-all.jar /tmp/my-sandbox /usr/bin/git --version
#
#  注意:
#    Debian/Ubuntu 系を前提 (パスが /usr/lib/x86_64-linux-gnu/ 等)。
#    他 distro では host の path 探索ロジックを修正する必要あり。
# --------------------------------------------------------------------
set -eu

if [ $# -lt 1 ]; then
    cat >&2 <<USAGE
usage: $0 <sandbox_dir> [LEVEL]
       LEVEL = minimal | base (default) | full
USAGE
    exit 2
fi

SB=$(realpath "$1")
LEVEL=${2:-base}
HERE=$(cd "$(dirname "$0")" && pwd -P)

case "$LEVEL" in
    minimal|base|full) ;;
    *) echo "$0: error: unknown LEVEL '$LEVEL' (expected: minimal | base | full)" >&2; exit 2 ;;
esac

echo "[build-sandbox] target=$SB level=$LEVEL"

# helper: copy file/dir if exists, warn if missing (warn 1 回ずつ)
declare -A WARNED
warn_once() {
    if [ -z "${WARNED[$1]:-}" ]; then
        echo "  warn: $1 not found on host (skipped)" >&2
        WARNED[$1]=1
    fi
}
copy_if() {
    local src=$1 dst=$2
    if [ -e "$src" ]; then
        mkdir -p "$(dirname "$dst")"
        cp -a "$src" "$dst"
    else
        warn_once "$src"
    fi
}
copy_dir_if() {
    local src=$1 dst=$2
    if [ -d "$src" ]; then
        mkdir -p "$dst"
        cp -a "$src/." "$dst/"
    else
        warn_once "$src"
    fi
}

# -----------------------------
# Stage 0: 基本ディレクトリ
# -----------------------------
mkdir -p "$SB"/{bin,etc,lib64,proc,sys,tmp,usr/bin,usr/lib,usr/share}
mkdir -p "$SB/usr/lib/x86_64-linux-gnu"

# Debian: /lib は /usr/lib への symlink。binary は /lib/x86_64-linux-gnu/...
# でも /usr/lib/x86_64-linux-gnu/... でも同じ実体に解決される。
# sandbox でも同様に /lib/x86_64-linux-gnu → ../usr/lib/x86_64-linux-gnu の
# symlink を作っておく (copy_with_deps が両方のパスから探せるように)
mkdir -p "$SB/lib"
if [ ! -e "$SB/lib/x86_64-linux-gnu" ]; then
    ln -sf ../usr/lib/x86_64-linux-gnu "$SB/lib/x86_64-linux-gnu"
fi

# 空 emulin.cnf (Kernel が探す)
: > "$SB/etc/emulin.cnf"

# -----------------------------
# Stage 1: minimal (busybox)
# -----------------------------
HOST_BB=${HOST_BB:-/usr/bin/busybox}
if [ -x "$HOST_BB" ]; then
    cp "$HOST_BB" "$SB/bin/busybox"
    chmod +x "$SB/bin/busybox"
    echo "[stage] minimal: busybox copied from $HOST_BB"
else
    echo "  warn: busybox not at $HOST_BB (skipped). set HOST_BB=path env to override." >&2
fi

if [ "$LEVEL" = "minimal" ]; then
    echo "[done] sandbox at $SB (level=minimal)"
    exit 0
fi

# -----------------------------
# Stage 2: base
# -----------------------------
echo "[stage] base: 実機 binary 動作の前提 file を配置..."

# 2a. dynamic linker (interp)
copy_if /lib64/ld-linux-x86-64.so.2 "$SB/lib64/ld-linux-x86-64.so.2"

# 2b. ld.so.cache (= step 58 で必須。これが無いと glibc が異なる malloc pattern を取る)
copy_if /etc/ld.so.cache "$SB/etc/ld.so.cache"

# 2c. locale files (step 58 必須)
copy_if /usr/share/locale/locale.alias \
        "$SB/usr/share/locale/locale.alias"
copy_dir_if /usr/lib/locale/C.utf8 \
            "$SB/usr/lib/locale/C.utf8"
copy_if /usr/lib/x86_64-linux-gnu/gconv/gconv-modules.cache \
        "$SB/usr/lib/x86_64-linux-gnu/gconv/gconv-modules.cache"

# 2d. SSL / cert
copy_dir_if /etc/ssl/certs "$SB/etc/ssl/certs"
copy_if /etc/gnutls/config "$SB/etc/gnutls/config"

# 2e. /etc/gitconfig — git clone HTTPS workaround (step 59)
#  cert load の遅さで server idle timeout する問題を回避する設定。
#  CAPath= 空 + CAInfo= 単一 root cert (github 用 Sectigo Root E46) を指定。
SECTIGO=$SB/etc/ssl/certs/Sectigo_Public_Server_Authentication_Root_E46.pem
cat > "$SB/etc/gitconfig" <<EOF
# Phase 28-3: emulator 上で git を使うための共通設定。
[safe]
	# host 側 uid と emulator 側 uid が違うため、git の "dubious ownership"
	# protection を無効化。clone file:// で git-upload-pack 子プロセスが
	# repo を読めるようになる。
	directory = *
# Phase 28-3 注意: protocol.version は transport 別に挙動が違う。
#   file:// : default v2 で sideband demuxer "unexpected disconnect" → v0 必須
#   https://: default v2 でこそ動作。v0 にすると "https unexpectedly said"
#             warnings が出て pack の master 参照が壊れる
# 実用的には launcher 側 GIT_CONFIG_PARAMETERS で file:// 時のみ v0 設定する
# 想定。global は default (= v2) のまま。
EOF
if [ -f "$SECTIGO" ]; then
    cat >> "$SB/etc/gitconfig" <<EOF
# Phase 28-1: emulator 上で git clone HTTPS を高速化するための workaround。
# /etc/ssl/certs を全 scan すると 83 秒かかり server timeout するため、
# CAPath= empty で system path を skip し CAInfo に単一 root を指定する。
[http]
	sslCAInfo = /etc/ssl/certs/Sectigo_Public_Server_Authentication_Root_E46.pem
	sslCAPath =
EOF
    echo "  /etc/gitconfig: safe.directory=* + CAInfo=Sectigo Root E46 (github 用)"
else
    echo "  /etc/gitconfig: safe.directory=* (Sectigo Root E46 not found, HTTPS workaround skipped)" >&2
fi

# 2f. 基本的な system config
copy_if /etc/passwd       "$SB/etc/passwd"
copy_if /etc/group        "$SB/etc/group"
copy_if /etc/hosts        "$SB/etc/hosts"
copy_if /etc/resolv.conf  "$SB/etc/resolv.conf"
copy_if /etc/services     "$SB/etc/services"
copy_if /etc/nsswitch.conf "$SB/etc/nsswitch.conf"

# 2g. /dev/urandom 用 (1 MB の乱数 buf。OpenSSL が S_ISCHR で check するので
#     stat side で character device として申告する。実体は固定 file)
URANDOM_SB=$SB/dev/urandom
if [ ! -f "$URANDOM_SB" ]; then
    mkdir -p "$SB/dev"
    dd if=/dev/urandom of="$URANDOM_SB" bs=1M count=1 status=none
    echo "  /dev/urandom: 1 MB 乱数 file 作成"
fi

if [ "$LEVEL" = "base" ]; then
    echo "[done] sandbox at $SB (level=base)"
    exit 0
fi

# -----------------------------
# Stage 3: full (デモ用 binary)
# -----------------------------
# ldd で binary の依存 library を解析して最小限だけコピー
echo "[stage] full: デモ用 binary とその依存 library を配置..."

# 依存 .so をたどって sandbox 内の同じパスにコピーする
# host の /lib/x86_64-linux-gnu/foo.so → /usr/lib/x86_64-linux-gnu/foo.so
# (Debian の /lib symlink) を考慮して、real file は /usr/lib/... 側に配置。
# Stage 0 で /lib/x86_64-linux-gnu → ../usr/lib/x86_64-linux-gnu の symlink を
# 既に作ってあるので、binary が /lib/... を参照しても解決可。
copy_with_deps() {
    local bin=$1
    if [ ! -f "$bin" ]; then
        warn_once "$bin"
        return
    fi
    # binary 自身を $SB/{bin の置き場所と同じ} にコピー
    copy_if "$bin" "$SB${bin}"
    # ldd 出力から依存 .so を抽出 (=> 後ろに path) → コピー
    local lib_path real_lib
    while IFS= read -r line; do
        if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then
            lib_path="${BASH_REMATCH[1]}"
        elif [[ "$line" =~ ^[[:space:]]*(/[^[:space:]]+) ]]; then
            lib_path="${BASH_REMATCH[1]}"
        else
            continue
        fi
        real_lib=$(readlink -f "$lib_path")
        # canonical path に real file をコピー
        copy_if "$real_lib" "$SB${real_lib}"
        # 同 dir 内に lib_path 名の symlink (relative) を張る
        # 例: libpcre2-8.so.0 → libpcre2-8.so.0.11.2
        if [ "$real_lib" != "$lib_path" ] && [ -e "$SB${real_lib}" ]; then
            local lp_real=$(readlink -f "$(dirname "$lib_path")")"/$(basename "$lib_path")"
            if [ "$lp_real" != "$real_lib" ]; then
                mkdir -p "$(dirname "$SB$lp_real")"
                ln -sf "$(basename "$real_lib")" "$SB$lp_real"
            fi
        fi
    done < <(ldd "$bin" 2>/dev/null)
}

# よく使う実機 binary を /usr/bin にコピー (依存 .so 自動解決)
for cmd in bash git curl openssl python3 wget; do
    src=/usr/bin/$cmd
    if [ -f "$src" ]; then
        copy_with_deps "$src"
        # /bin/<cmd> → /usr/bin/<cmd> symlink
        if [ ! -e "$SB/bin/$cmd" ]; then
            ln -s ../usr/bin/$cmd "$SB/bin/$cmd"
        fi
    fi
done

# /bin/sh — POSIX shell。git clone file:// が fork+exec で使う、bash や
# 多くのスクリプトの shebang。bash を sh として symlink で代用。
if [ -e "$SB/bin/bash" ] && [ ! -e "$SB/bin/sh" ]; then
    ln -s bash "$SB/bin/sh"
fi

# git-core (clone HTTPS で git-remote-https が必要)
if [ -d /usr/lib/git-core ]; then
    mkdir -p "$SB/usr/lib/git-core"
    for f in git git-remote-http git-remote-https git-receive-pack git-upload-pack; do
        if [ -f "/usr/lib/git-core/$f" ]; then
            copy_with_deps "/usr/lib/git-core/$f"
        fi
    done
fi

# Python 3 が import で動的に load する .so (cpython modules)
if [ -d /usr/lib/python3.12 ]; then
    mkdir -p "$SB/usr/lib/python3.12"
    cp -a /usr/lib/python3.12/. "$SB/usr/lib/python3.12/"
fi

echo "[done] sandbox at $SB (level=full)"
