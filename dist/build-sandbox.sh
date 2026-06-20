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
# issue #361: .deb を package-managed で rootfs へ導入する共通関数 (deb_install_one/dir)。
. "$HERE/deb-install-lib.sh"

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

# issue #63 (license documentation): 同梱 binary の Debian package が
# 持つ /usr/share/doc/<pkg>/copyright を sandbox に copy する。
#
# GPL/LGPL の binary 配布要件 + Debian Policy §12.5 遵守のため、
# 同梱した全ての deb package の copyright file を保持する必要がある。
#
# 引数:
#   copy_copyrights_from_extract <extract_dir>
#     dpkg -x で展開した dir から /usr/share/doc/*/copyright を再帰 copy
#   copy_host_copyright <pkg_name>
#     host の /usr/share/doc/<pkg>/copyright を sandbox に copy
copy_copyrights_from_extract() {
    local extract_dir=$1
    [ -d "$extract_dir/usr/share/doc" ] || return 0
    for pkg_dir in "$extract_dir/usr/share/doc"/*/ ; do
        [ -d "$pkg_dir" ] || continue
        local pkg
        pkg=$(basename "$pkg_dir")
        if [ -f "$pkg_dir/copyright" ]; then
            mkdir -p "$SB/usr/share/doc/$pkg"
            cp "$pkg_dir/copyright" "$SB/usr/share/doc/$pkg/copyright"
        fi
    done
}
copy_host_copyright() {
    local pkg=$1
    if [ -f "/usr/share/doc/$pkg/copyright" ]; then
        mkdir -p "$SB/usr/share/doc/$pkg"
        cp "/usr/share/doc/$pkg/copyright" "$SB/usr/share/doc/$pkg/copyright"
    fi
}
# host の binary を copy するとき、dpkg -S でその binary の所属 package を
# 自動解決して copyright を copy する版。binary path を引数に取る。
copy_host_copyright_for_binary() {
    local binary=$1
    [ -f "$binary" ] || return 0
    local pkg
    pkg=$(dpkg -S "$binary" 2>/dev/null | head -1 | cut -d: -f1)
    if [ -n "$pkg" ]; then
        copy_host_copyright "$pkg"
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
# issue #322: DEBIAN_BASE=1 のとき busybox を同梱しない。Debian base (docker
#   debian:trixie 相当、build-debian-base.sh が事前に敷く) の実 coreutils/bash/dash
#   が ls/cat/sh/chmod 等を提供するので busybox は不要 (重複排除)。この mode では
#   build-sandbox は base の上に extras (git/vim/emacs/...) と config だけを overlay する。
HOST_BB=${HOST_BB:-/usr/bin/busybox}
if [ "${DEBIAN_BASE:-0}" = 1 ]; then
    echo "[stage] minimal: DEBIAN_BASE=1 → busybox skip (Debian base の coreutils/bash を使用)"
elif [ -x "$HOST_BB" ]; then
    cp "$HOST_BB" "$SB/bin/busybox"
    chmod +x "$SB/bin/busybox"
    # issue #63: busybox の copyright (host package 解決経由)
    copy_host_copyright_for_binary "$HOST_BB"
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
# issue #322: DEBIAN_BASE では ld-linux は libc6 (Debian base) が正しい symlink で提供済み
#   (/usr/lib64/ld-linux-x86-64.so.2 → ../lib/x86_64-linux-gnu/ld-linux-x86-64.so.2)。ここで
#   host の ld-linux を cp -a すると既存 symlink を自己参照に壊して全 binary が起動不能になるので skip。
if [ "${DEBIAN_BASE:-0}" != 1 ]; then
    copy_if /lib64/ld-linux-x86-64.so.2 "$SB/lib64/ld-linux-x86-64.so.2"
fi

# issue #63: base system (glibc / ld.so / libgcc 等) の copyright を一括 copy。
# binary 依存解決の前段で配置する。host 由来の core package のみ。
for base_pkg in libc6 libc-bin libgcc-s1 libcrypt1 libssl3 libgssapi-krb5-2 \
                libkrb5-3 libk5crypto3 libkrb5support0 libcom-err2 libkeyutils1 \
                libresolv2 zlib1g; do
    copy_host_copyright "$base_pkg"
done

# 2a-2. issue #3 followup: libgcc_s.so.1 は glibc の pthread が thread 終了時に
# dlopen() で動的ロードするため、ldd 出力には現れず copy_with_deps では捕捉
# できない。これが欠けていると "libgcc_s.so.1 must be installed for
# pthread_exit to work" で multi-thread binary (例: git の index-pack で
# 188+ objects 規模 clone) が失敗する。明示的に copy する。
LIBGCC_SRC=$(readlink -f /usr/lib/x86_64-linux-gnu/libgcc_s.so.1 2>/dev/null \
            || readlink -f /lib/x86_64-linux-gnu/libgcc_s.so.1 2>/dev/null)
if [ -n "$LIBGCC_SRC" ] && [ -f "$LIBGCC_SRC" ]; then
    copy_if "$LIBGCC_SRC" "$SB/usr/lib/x86_64-linux-gnu/libgcc_s.so.1"
    # 名前付き symlink (real file が version 付きの場合に必要)
    if [ "$LIBGCC_SRC" != "/usr/lib/x86_64-linux-gnu/libgcc_s.so.1" ] \
       && [ ! -e "$SB/usr/lib/x86_64-linux-gnu/libgcc_s.so.1" ]; then
        ln -sf "$(basename "$LIBGCC_SRC")" "$SB/usr/lib/x86_64-linux-gnu/libgcc_s.so.1"
    fi
fi

# 2b. ld.so.cache (= step 58 で必須。これが無いと glibc が異なる malloc pattern を取る)
copy_if /etc/ld.so.cache "$SB/etc/ld.so.cache"

# 2b2. distro 固有 file (os-release / debian_version / issue)。bundle が Debian
#   由来であることを明示し、distro を検出する program (python の
#   platform.freedesktop_os_release() / os-release 読み等) に正しい情報を渡す。
#   host (Debian) の実体を copy、無ければ Debian 内容を生成する。os-release は
#   通常 /etc/os-release → /usr/lib/os-release の symlink なので readlink -f で
#   実体を解決し、/etc と /usr/lib の両方に置く (program はどちらも見る)。
#   issue #308 (Ubuntu→Debian 移行、Ubuntu os-release を漏らさず Debian を提供)。
OSREL_SRC=$(readlink -f /etc/os-release 2>/dev/null)
[ -f "$OSREL_SRC" ] || OSREL_SRC=/usr/lib/os-release
if [ -f "$OSREL_SRC" ]; then
    mkdir -p "$SB/usr/lib"
    cp -L "$OSREL_SRC" "$SB/usr/lib/os-release"
    cp -L "$OSREL_SRC" "$SB/etc/os-release"
else
    cat > "$SB/etc/os-release" <<'OSRELEOF'
PRETTY_NAME="Debian GNU/Linux"
NAME="Debian GNU/Linux"
ID=debian
HOME_URL="https://www.debian.org/"
BUG_REPORT_URL="https://bugs.debian.org/"
OSRELEOF
    mkdir -p "$SB/usr/lib"; cp "$SB/etc/os-release" "$SB/usr/lib/os-release"
fi
copy_if /etc/debian_version "$SB/etc/debian_version"
[ -f "$SB/etc/debian_version" ] || echo "trixie/sid" > "$SB/etc/debian_version"
copy_if /etc/issue "$SB/etc/issue"
[ -f "$SB/etc/issue" ] || printf 'Debian GNU/Linux \\n \\l\n' > "$SB/etc/issue"
echo "  /etc/os-release + debian_version + issue (Debian distro id)"

# 2c. locale files (step 58 必須)
copy_if /usr/share/locale/locale.alias \
        "$SB/usr/share/locale/locale.alias"
copy_dir_if /usr/lib/locale/C.utf8 \
            "$SB/usr/lib/locale/C.utf8"
copy_if /usr/lib/x86_64-linux-gnu/gconv/gconv-modules.cache \
        "$SB/usr/lib/x86_64-linux-gnu/gconv/gconv-modules.cache"
# issue #19: gconv モジュール (.so) 一式。これが無いと iconv / glibc の
# 文字コード変換が UTF-8 ⇔ C 以外で "conversion not supported" になる。
# 253 module / 8.4 MB。gconv-modules (cache でない方の text 定義) も必要。
copy_if /usr/lib/x86_64-linux-gnu/gconv/gconv-modules \
        "$SB/usr/lib/x86_64-linux-gnu/gconv/gconv-modules"
if [ -d /usr/lib/x86_64-linux-gnu/gconv ]; then
    for so in /usr/lib/x86_64-linux-gnu/gconv/*.so; do
        [ -f "$so" ] && copy_if "$so" "$SB/usr/lib/x86_64-linux-gnu/gconv/$(basename "$so")"
    done
fi

# 2d. SSL / cert
copy_dir_if /etc/ssl/certs "$SB/etc/ssl/certs"
copy_if /etc/gnutls/config "$SB/etc/gnutls/config"

# 2d2. Phase 28-3k: curated multi-root bundle for HTTPS (~8 certs)
# /etc/ssl/certs (146+ 個) を全 load すると 80s 以上で server idle timeout。
# 主要 root だけを 1 file に concat した bundle を作って sslCAInfo に指定すると
# 14s 前後で HTTPS 完了。github 単独 (Sectigo Root E46) と比べて example.com /
# cloudflare / google / iana / raw.githubusercontent も同時に動作可。
# issue #108: pypi.org / files.pythonhosted.org は GlobalSign Atlas R3 →
#   GlobalSign Root CA - R3 発行。これが束に無いと pip の cert 検証器 truststore
#   (既定の /etc/ssl/cert.pem 系を読む) が issuer を辿れず CERTIFICATE_VERIFY_FAILED
#   になり pip install が失敗する。R3 を 1 本足すと pip が --cert 無しで動く
#   (最小性は維持: 8 certs でも truststore 検証 2.6s)。
EMULIN_BUNDLE=$SB/etc/ssl/certs/emulin-roots.pem
> "$EMULIN_BUNDLE"
for cert_name in \
    Sectigo_Public_Server_Authentication_Root_E46 \
    USERTrust_RSA_Certification_Authority \
    SSL.com_TLS_ECC_Root_CA_2022 \
    GTS_Root_R4 \
    ISRG_Root_X1 \
    DigiCert_Global_Root_CA \
    DigiCert_Global_Root_G2 \
    GlobalSign_Root_CA_-_R3 ; do
    src=/etc/ssl/certs/${cert_name}.pem
    if [ -f "$src" ]; then
        cat "$src" >> "$EMULIN_BUNDLE"
        echo "" >> "$EMULIN_BUNDLE"
    fi
done
echo "  /etc/ssl/certs/emulin-roots.pem: $(grep -c BEGIN $EMULIN_BUNDLE) certs (multi-site HTTPS 用)"
# curl の default bundle (compile-time path) を curated bundle で上書き。
# こうすると curl --cacert flag 無しでも emulin-roots.pem を使う。
# /etc/ssl/certs/ca-certificates.crt は Debian/Ubuntu の curl default。
if [ -f "$EMULIN_BUNDLE" ] && [ -s "$EMULIN_BUNDLE" ]; then
    cp "$EMULIN_BUNDLE" "$SB/etc/ssl/certs/ca-certificates.crt"
    cp "$EMULIN_BUNDLE" "$SB/etc/ssl/cert.pem" 2>/dev/null
fi

# 2d3. issue #108: /etc/pip.conf で pip に cert bundle を明示。
# pip 24.2+ の既定 cert 検証器 truststore は emulin sandbox 上で
# files.pythonhosted.org の取得に失敗する (pypi→files の多段フローで
# UNEXPECTED_EOF)。cert= を指定すると pip は truststore を使わず certifi 方式
# 検証になり、上の curated bundle (GlobalSign R3 込み) で pypi /
# files.pythonhosted.org を検証して install できる。これで `pip install` が
# ユーザーの --cert 指定無しで動く。
cat > "$SB/etc/pip.conf" <<'PIPCONF'
[global]
cert = /etc/ssl/certs/ca-certificates.crt
PIPCONF

# 2e. /etc/gitconfig — git clone HTTPS workaround (step 59 + 28-3k)
cat > "$SB/etc/gitconfig" <<EOF
# Phase 28-3: emulator 上で git を使うための共通設定。
[safe]
	# host 側 uid と emulator 側 uid が違うため、git の "dubious ownership"
	# protection を無効化。clone file:// で git-upload-pack 子プロセスが
	# repo を読めるようになる。
	directory = *
[core]
	# Phase 29: terminfo + less + TCGETS を tty 判定に修正で git の default
	# pager (less -FRX) が動作するようになった。LESSCHARSET=utf-8 が必要なので
	# emulin.sh / emulin.bat で env に export しておく。設定不要。
	# Phase 33-10: git は repo init/clone 時に filesystem の symlink サポート
	# を test するため .git/<random> -> testing という dangling symlink を
	# 作成して probe する。test 用 symlink は本来 cleanup されるはずだが、
	# Java NIO Files.delete + Windows NTFS の組合せで silently 削除失敗し
	# .git/ に残留 → rm -rf sekka/ が \"Operation not permitted\" で失敗する
	# 致命問題があった。symlinks=false を明示すると probe 自体が走らないので
	# 根本回避。emulator 上の git ワークツリーは symlink 不要 (実 Linux でも
	# Windows では default false 相当) なので影響なし。
	symlinks = false
# Phase 28-3 注意: protocol.version は transport 別に挙動が違う。
#   file:// : default v2 で sideband demuxer "unexpected disconnect" → v0 必須
#   https://: default v2 でこそ動作。v0 にすると "https unexpectedly said"
#             warnings が出て pack の master 参照が壊れる
# 実用的には launcher 側 GIT_CONFIG_PARAMETERS で file:// 時のみ v0 設定する
# 想定。global は default (= v2) のまま。
EOF
if [ -s "$EMULIN_BUNDLE" ]; then
    cat >> "$SB/etc/gitconfig" <<EOF
# Phase 28-3k: emulator 上で git clone HTTPS を高速化 + 多サイト対応する
# workaround。/etc/ssl/certs を全 scan すると 80s+ で server idle timeout
# するため、CAPath= empty で system path を skip し CAInfo に主要 root だけ
# 入れた curated bundle (~7 cert) を指定する。
[http]
	sslCAInfo = /etc/ssl/certs/emulin-roots.pem
	sslCAPath =
	# Phase 33-4: emulator は CPU 遅で大 pack 受信時に 100KB/s 程度。
	# HTTP/2 は server 側 multiplexing 制御で client 不活性と判断されると
	# RST_STREAM (CANCEL err 8) が飛んできて clone が中断する。
	# HTTP/1.1 の方が server timeout に寛容で大 repo (sekka 等) を最後まで
	# 受信できる。HTTP/1.1 化で小さな性能損失はあるが安定性を優先。
	version = HTTP/1.1
EOF
    echo "  /etc/gitconfig: safe.directory=* + CAInfo=emulin-roots.pem (multi-site HTTPS) + HTTP/1.1"
else
    echo "  /etc/gitconfig: safe.directory=* (no roots found, HTTPS workaround skipped)" >&2
fi

# 2f. 基本的な system config
copy_if /etc/passwd       "$SB/etc/passwd"
copy_if /etc/group        "$SB/etc/group"

# issue #9: root の HOME ディレクトリ (/etc/passwd 上は /root)。
# 親 dir が無いと ssh が ~/.ssh/known_hosts を保存できず warning を出す
# (TCP/KEX 自体は動く)。空の /root と /root/.ssh を事前作成。
mkdir -p "$SB/root/.ssh"
chmod 700 "$SB/root" "$SB/root/.ssh" 2>/dev/null || true
# Phase 33: host の /etc/hosts / /etc/resolv.conf をそのままコピーすると
# WSL host で生成された "nameserver 10.255.255.254" (WSL2 内部 DNS proxy)
# を含み、Windows native cmd.exe では到達不能で github.com 等の解決が失敗
# する。public DNS (Cloudflare 1.1.1.1 + Google 8.8.8.8) を generic に
# 書き出す方が cross-platform で確実。
cat > "$SB/etc/resolv.conf" <<'RESOLV_EOF'
# generic public DNS (cross-platform 用、emulin sandbox)
nameserver 1.1.1.1
nameserver 1.0.0.1
nameserver 8.8.8.8
RESOLV_EOF
cat > "$SB/etc/hosts" <<'HOSTS_EOF'
127.0.0.1	localhost
::1		localhost ip6-localhost ip6-loopback
HOSTS_EOF
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
# Stage 3: full (実機 GNU coreutils + bash + git/curl/wget)
# -----------------------------
# Phase 28-3g: 旧 full は git/curl/openssl/python3/wget の重 binary 中心
# (~ 80 MB demo zip) だったが、実用的な「実機 Linux 風シェル体験」のため
# GNU coreutils + bash + 3 大ネット系 binary (git/curl/wget) に絞り込んだ。
# python3 / openssl CLI は容量に対して使い道が限定的なので除外
# (curl/git の HTTPS は libssl/libgnutls 経由なのでそちらの dependency
# として自動コピーされる)。
echo "[stage] full: 実機 coreutils + bash + git/curl/wget とその依存 library を配置..."

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
    # issue #361: dpkg DB があり対象が既に $SB に存在する (base / deb 由来) なら、host 版で
    #   上書きしない (apt upgrade で dpkg DB の md5sum と実体が食い違わないようにする)。
    if [ -s "$SB/var/lib/dpkg/status" ] && [ -e "$SB${bin}" ]; then
        return 0
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
            # ★ basename だけの相対 target は real_lib と「同一 dir」のときだけ正しい。
            #   cross-dir のエイリアス (例: /lib64/ld-linux-x86-64.so.2 → 実体は
            #   /usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2) でこれをやると、同名 basename ゆえ
            #   自己参照 symlink (ld-linux→ld-linux) になり dynamic linker が壊れて全 binary が
            #   起動不能になる (issue #322、DEBIAN_BASE で /lib64→usr/lib64 のとき顕在化)。
            #   よって lp_real と real_lib が同一 dir のときだけ張る (versioned lib エイリアス用)。
            if [ "$lp_real" != "$real_lib" ] && \
               [ "$(dirname "$lp_real")" = "$(dirname "$real_lib")" ]; then
                mkdir -p "$(dirname "$SB$lp_real")"
                ln -sf "$(basename "$real_lib")" "$SB$lp_real"
            fi
        fi
    done < <(ldd "$bin" 2>/dev/null)
}

# apt-get download で extract した binary の依存 .so だけを ldd で解決して SB に
# copy する (binary 自身は呼び出し側が SB の正しい場所に cp 済みの前提)。
# copy_with_deps は binary を $SB${bin} (= src path) に置くため host 外の
# extract path には使えない。その依存解決部分だけを切り出したもの (issue #308)。
copy_extract_bin_deps() {
    local bin=$1 lib_path real_lib lp_real
    while IFS= read -r line; do
        if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then
            lib_path="${BASH_REMATCH[1]}"
        elif [[ "$line" =~ ^[[:space:]]*(/[^[:space:]]+) ]]; then
            lib_path="${BASH_REMATCH[1]}"
        else
            continue
        fi
        real_lib=$(readlink -f "$lib_path")
        [ -f "$real_lib" ] || continue
        copy_if "$real_lib" "$SB${real_lib}"
        if [ "$real_lib" != "$lib_path" ] && [ -e "$SB${real_lib}" ]; then
            lp_real=$(readlink -f "$(dirname "$lib_path")")"/$(basename "$lib_path")"
            if [ "$lp_real" != "$real_lib" ]; then
                mkdir -p "$(dirname "$SB$lp_real")"
                ln -sf "$(basename "$real_lib")" "$SB$lp_real"
            fi
        fi
    done < <(ldd "$bin" 2>/dev/null)
}

# GNU coreutils + bash + 3 大ネット系 (git/curl/wget) を配置 (依存 .so 自動解決)。
# 各 cmd がどこにあっても探す (Debian は /bin と /usr/bin に分散)。
copy_cmd_with_deps() {
    local cmd=$1
    # issue #361: dpkg DB があれば package-managed を優先する (apt upgrade 整合性のため、
    #   base の deb file を host 版で上書きしない / 追加ツールは deb で依存閉包ごと導入する)。
    if [ -s "$SB/var/lib/dpkg/status" ]; then
        # 既に存在 (base 等 deb 由来) → host 版で上書きしない
        if [ -e "$SB/usr/bin/$cmd" ] || [ -e "$SB/bin/$cmd" ]; then return 0; fi
        # host で cmd を所有する package を dpkg -S で特定し package-managed 導入
        local hp pkg
        hp=$(command -v "$cmd" 2>/dev/null || true)
        if [ -n "$hp" ]; then
            pkg=$(dpkg -S "$(readlink -f "$hp")" 2>/dev/null | head -1 | cut -d: -f1)
            [ -z "${pkg:-}" ] && pkg=$(dpkg -S "$hp" 2>/dev/null | head -1 | cut -d: -f1)
            if [ -n "${pkg:-}" ]; then
                local pmtmp; pmtmp=$(mktemp -d -t "emulin-pmc-$cmd.XXXXXX")
                if deb_bundle_closure "$SB" "$pmtmp/debs" "$pkg"; then
                    rm -rf "$pmtmp"
                    [ ! -e "$SB/bin/$cmd" ] && [ -e "$SB/usr/bin/$cmd" ] && ln -sf "../usr/bin/$cmd" "$SB/bin/$cmd"
                    return 0
                fi
                rm -rf "$pmtmp"
            fi
        fi
        # package 特定できず (host-only tool 等) → 下の host copy に落ちる
    fi
    for prefix in /usr/bin /bin; do
        local src=$prefix/$cmd
        if [ -f "$src" ]; then
            copy_with_deps "$src"
            # /bin と /usr/bin 両方から呼べるよう symlink を補完
            if [ ! -e "$SB/bin/$cmd" ]; then
                ln -sf "../usr/bin/$cmd" "$SB/bin/$cmd"
            fi
            if [ ! -e "$SB/usr/bin/$cmd" ]; then
                # host が /bin/$cmd を実体として持つレアケース
                ln -sf "../../bin/$cmd" "$SB/usr/bin/$cmd"
            fi
            # issue #63: host binary 由来の package の copyright を copy
            copy_host_copyright_for_binary "$src"
            return 0
        fi
    done
    warn_once "/usr/bin/$cmd or /bin/$cmd"
}

# issue #130: 単体 CLI tool を bundle する汎用ヘルパー (#129 make の apt fallback を一般化)。
#   host に binary があれば copy_cmd_with_deps (soname symlink 込みで依存解決)。
#   無ければ apt-get download <pkgs> → dpkg -x して binary + 同梱 lib を staging。
#   usage: bundle_cli_tool <cmd> [apt_pkg...]
#     apt_pkg 複数指定で private lib の package も download (例: jq jq libjq1 libonig5)。
bundle_cli_tool() {
    local cmd=$1; shift
    local pkgs=("$@"); [ ${#pkgs[@]} -eq 0 ] && pkgs=("$cmd")
    # issue #361: rootfs に dpkg DB がある (Debian base) なら package-managed で導入する
    #   (.deb + 依存閉包を dpkg DB へ登録、dpkg -l に出る / 依存・copyright が deb 由来)。
    #   失敗時 (dpkg DB 無し等) は下の従来経路 (host copy / apt download + 手コピー) に落ちる。
    if [ -s "$SB/var/lib/dpkg/status" ]; then
        local tmpd; tmpd=$(mktemp -d -t "emulin-pm-$cmd.XXXXXX")
        if deb_bundle_closure "$SB" "$tmpd/debs" "${pkgs[@]}"; then
            rm -rf "$tmpd"
            echo "  $cmd を package-managed で導入 (${pkgs[*]})"
            return 0
        fi
        rm -rf "$tmpd"
    fi
    if [ -x "/usr/bin/$cmd" ] || [ -x "/bin/$cmd" ]; then
        copy_cmd_with_deps "$cmd"
    elif command -v apt-get >/dev/null 2>&1; then
        local tmp; tmp=$(mktemp -d -t "emulin-$cmd.XXXXXX")
        ( cd "$tmp" && apt-get download "${pkgs[@]}" >/dev/null 2>&1 \
          && for d in *.deb; do dpkg -x "$d" extract; done ) || true
        copy_copyrights_from_extract "$tmp/extract"   # issue #63
        local bin=""
        for cand in "$tmp/extract/usr/bin/$cmd" "$tmp/extract/bin/$cmd"; do
            [ -x "$cand" ] && { bin=$cand; break; }
        done
        if [ -n "$bin" ]; then
            cp "$bin" "$SB/usr/bin/$cmd"
            [ -e "$SB/bin/$cmd" ] || ln -sf "../usr/bin/$cmd" "$SB/bin/$cmd"
            # .deb 同梱の lib (libjq/libonig/libsqlite3 等、host に無い private lib)。
            #   soname symlink と実体の両方が .deb に入るので cp -L で両名コピー。
            mkdir -p "$SB/usr/lib/x86_64-linux-gnu"
            local so
            while IFS= read -r so; do
                cp -L "$so" "$SB/usr/lib/x86_64-linux-gnu/$(basename "$so")" 2>/dev/null || true
            done < <(find "$tmp/extract" -name '*.so*' 2>/dev/null)
            # host にある依存 (libreadline/libncurses/libm 等) を ldd で補完 + soname symlink。
            local line lib_path real_lib local_lp_real
            while IFS= read -r line; do
                if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then
                    lib_path="${BASH_REMATCH[1]}"; real_lib=$(readlink -f "$lib_path")
                    if [ -f "$real_lib" ]; then
                        copy_if "$real_lib" "$SB${real_lib}"
                        if [ "$real_lib" != "$lib_path" ] && [ -e "$SB${real_lib}" ]; then
                            local_lp_real=$(readlink -f "$(dirname "$lib_path")")"/$(basename "$lib_path")"
                            if [ "$local_lp_real" != "$real_lib" ]; then
                                mkdir -p "$(dirname "$SB$local_lp_real")"
                                ln -sf "$(basename "$real_lib")" "$SB$local_lp_real"
                            fi
                        fi
                    fi
                fi
            done < <(ldd "$bin" 2>/dev/null)
        else
            echo "  warn: $cmd (${pkgs[*]}) not retrievable via apt-get — skipping"
        fi
        rm -rf "$tmp"
    else
        echo "  warn: $cmd not on host and apt-get unavailable — skipping"
    fi
    [ -e "$SB/usr/bin/$cmd" ] && echo "  $cmd ($(du -sh "$SB/usr/bin/$cmd" 2>/dev/null | awk '{print $1}'))"
}

# シェル
for cmd in bash; do copy_cmd_with_deps "$cmd"; done

# /bin/sh — POSIX shell。git clone file:// が fork+exec で使う、bash や
# 多くのスクリプトの shebang。bash を sh として symlink で代用。
if [ -e "$SB/bin/bash" ] && [ ! -e "$SB/bin/sh" ]; then
    ln -sf bash "$SB/bin/sh"
fi

# GNU coreutils + 定番 (grep/sed/awk/find/file)
for cmd in \
    ls cat cp mv rm mkdir rmdir touch ln \
    echo true false dirname basename uname pwd \
    sleep date dd chmod chown chgrp \
    wc head tail cut tr uniq sort \
    printf find diff yes tee stat \
    id whoami who groups logname \
    df du ; do
    copy_cmd_with_deps "$cmd"
done
for cmd in grep sed awk file expr ; do
    copy_cmd_with_deps "$cmd"
done

# issue #17 (C9 coreutils-extra): Git for Windows /usr/bin にある GNU
# coreutils 拡張 + 周辺 tool。上の最小 list に無いものを追加で同梱する。
# 全て host から copy_cmd_with_deps で依存解決して bundle (1 binary
# 50-150 KB と軽量、合計 +3-5 MB 程度)。
#   coreutils:   base32 basenc cksum comm csplit dir dircolors fmt install
#                join nice nohup numfmt pathchk pinky pr printenv ptx runcon
#                sha224sum sha384sum split stdbuf sum tsort users vdir
#   bsdextrautils: column
#   diffutils:   diff3 sdiff
#   gawk:        gawk
#   e2fsprogs:   lsattr
#   libc-bin:    getconf locale
#   util-linux:  whereis
for cmd in \
    base32 basenc cksum comm csplit dir dircolors fmt install \
    join nice nohup numfmt pathchk pinky pr printenv ptx runcon \
    sha224sum sha384sum split stdbuf sum tsort users vdir \
    column diff3 sdiff gawk lsattr getconf locale whereis ; do
    copy_cmd_with_deps "$cmd"
done
# gawk-5.0.0 は versioned alias。host には大抵 gawk のみなので symlink で代替。
if [ -e "$SB/usr/bin/gawk" ] && [ ! -e "$SB/usr/bin/gawk-5.0.0" ]; then
    ln -sf gawk "$SB/usr/bin/gawk-5.0.0"
fi
# which: debianutils 提供。/usr/bin/which は alternatives symlink
# (/etc/alternatives/which → which.debianutils) で、辿ると sandbox で壊れる。
# 実体 (POSIX sh script、/bin/sh 依存=bash 同梱時に解決) を直接 copy して
# /usr/bin/which symlink を張る。host に無ければ busybox の which applet で
# 代替 (busybox 同梱済)。issue #308。
if [ -f /usr/bin/which.debianutils ]; then
    cp /usr/bin/which.debianutils "$SB/usr/bin/which.debianutils"
    ln -sf which.debianutils "$SB/usr/bin/which"
    [ -e "$SB/bin/which" ] || ln -sf ../usr/bin/which "$SB/bin/which"
    copy_host_copyright debianutils  # issue #63
elif [ -e "$SB/bin/busybox" ]; then
    ln -sf ../../bin/busybox "$SB/usr/bin/which"
fi
# getfacl / setfacl (acl pkg) / psl (psl pkg) は host に無いことが多いので
# apt-get download で取得を試みる。取れなければ skip (xattr 系は emulin が
# ENOSYS stub なので機能限定だが smoke test は通る)。
if command -v apt-get >/dev/null 2>&1; then
    C9_TMP=$(mktemp -d -t emulin-c9.XXXXXX)
    trap 'rm -rf "$C9_TMP" 2>/dev/null || true' EXIT
    ( cd "$C9_TMP" && apt-get download acl psl >/dev/null 2>&1 \
      && for d in *.deb; do dpkg -x "$d" extract 2>/dev/null; done ) || true
    copy_copyrights_from_extract "$C9_TMP/extract"
    for cmd in getfacl setfacl psl; do
        src="$C9_TMP/extract/usr/bin/$cmd"
        if [ -f "$src" ]; then
            cp "$src" "$SB/usr/bin/$cmd"
            # 依存 lib を ldd で解決 (libacl / libpsl 等)
            while IFS= read -r line; do
                if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then
                    lib_path="${BASH_REMATCH[1]}"
                    real_lib=$(readlink -f "$lib_path")
                    if [ -f "$real_lib" ]; then
                        copy_if "$real_lib" "$SB${real_lib}"
                        if [ "$real_lib" != "$lib_path" ] && [ -e "$SB${real_lib}" ]; then
                            local_lp_real=$(readlink -f "$(dirname "$lib_path")")"/$(basename "$lib_path")"
                            if [ "$local_lp_real" != "$real_lib" ]; then
                                mkdir -p "$(dirname "$SB$local_lp_real")"
                                ln -sf "$(basename "$real_lib")" "$SB$local_lp_real"
                            fi
                        fi
                    fi
                fi
            done < <(ldd "$src" 2>/dev/null)
            [ ! -e "$SB/bin/$cmd" ] && ln -sf "../usr/bin/$cmd" "$SB/bin/$cmd"
        fi
    done
fi

# issue #14 (C6 crypto helper): Git for Windows /usr/bin にある crypto /
# TLS helper tool 群。
#   coreutils:  b2sum (BLAKE2 hash — busybox には未収録)
#   openssl:    openssl (OpenSSL CLI — host にあれば copy)
#   nettle-bin: nettle-hash nettle-lfib-stream nettle-pbkdf2 pkcs1-conv
#   p11-kit:    p11-kit trust (PKCS#11 certificate store 管理)
# 注: Git for Windows の `ssp` は Cygwin の single-step profiler で Linux
#     package が無いため対象外 (実質 category D)。
for cmd in b2sum openssl ; do
    copy_cmd_with_deps "$cmd"
done
if command -v apt-get >/dev/null 2>&1; then
    C6_TMP=$(mktemp -d -t emulin-c6.XXXXXX)
    trap 'rm -rf "$C6_TMP" 2>/dev/null || true' EXIT
    ( cd "$C6_TMP" && apt-get download nettle-bin p11-kit >/dev/null 2>&1 \
      && for d in *.deb; do dpkg -x "$d" extract 2>/dev/null; done ) || true
    copy_copyrights_from_extract "$C6_TMP/extract"
    for cmd in nettle-hash nettle-lfib-stream nettle-pbkdf2 pkcs1-conv p11-kit trust ; do
        src="$C6_TMP/extract/usr/bin/$cmd"
        if [ -f "$src" ]; then
            cp "$src" "$SB/usr/bin/$cmd"
            # 依存 lib を ldd で解決 (libnettle / libhogweed / libp11-kit / libffi 等)
            while IFS= read -r line; do
                if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then
                    lib_path="${BASH_REMATCH[1]}"
                    real_lib=$(readlink -f "$lib_path")
                    if [ -f "$real_lib" ]; then
                        copy_if "$real_lib" "$SB${real_lib}"
                        if [ "$real_lib" != "$lib_path" ] && [ -e "$SB${real_lib}" ]; then
                            local_lp_real=$(readlink -f "$(dirname "$lib_path")")"/$(basename "$lib_path")"
                            if [ "$local_lp_real" != "$real_lib" ]; then
                                mkdir -p "$(dirname "$SB$local_lp_real")"
                                ln -sf "$(basename "$real_lib")" "$SB$local_lp_real"
                            fi
                        fi
                    fi
                fi
            done < <(ldd "$src" 2>/dev/null)
            [ ! -e "$SB/bin/$cmd" ] && ln -sf "../usr/bin/$cmd" "$SB/bin/$cmd"
        fi
    done
fi

# issue #11 (C3 gettext): Git for Windows /usr/bin にある gettext / i18n
# tool 群。msgfmt / msgmerge / xgettext は po file の build tool、
# gettext / ngettext / envsubst は runtime の message 展開。
#   gettext-base: envsubst gettext ngettext
#   gettext:      msgattrib msgcat msgcmp msgcomm msgconv msgen msgexec
#                 msgfilter msgfmt msggrep msginit msgmerge msgunfmt
#                 msguniq recode-sr-latin xgettext
# 注意: gettext package の binary は private lib (libgettextsrc-0.21.so /
#   libgettextlib-0.21.so) に依存する。これは package 内に同梱されるが host
#   未 install だと ldd では "not found" になるため、deb から明示 copy する。
if command -v apt-get >/dev/null 2>&1; then
    C3_TMP=$(mktemp -d -t emulin-c3.XXXXXX)
    trap 'rm -rf "$C3_TMP" 2>/dev/null || true' EXIT
    ( cd "$C3_TMP" && apt-get download gettext gettext-base >/dev/null 2>&1 \
      && for d in *.deb; do dpkg -x "$d" extract 2>/dev/null; done ) || true
    copy_copyrights_from_extract "$C3_TMP/extract"
    # private lib を先に copy (ldd では解決できないため)。
    # さらに private lib 自身の transitive 依存 (libxml2 / libicuuc /
    # libstdc++ 等) も ldd で解決して copy する。
    for plib in "$C3_TMP/extract/usr/lib/x86_64-linux-gnu/"libgettext*.so \
                "$C3_TMP/extract/usr/lib/x86_64-linux-gnu/"preloadable_libintl.so ; do
        [ -f "$plib" ] || continue
        cp -L "$plib" "$SB/usr/lib/x86_64-linux-gnu/$(basename "$plib")"
        while IFS= read -r line; do
            if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then
                lib_path="${BASH_REMATCH[1]}"
                real_lib=$(readlink -f "$lib_path")
                if [ -f "$real_lib" ]; then
                    copy_if "$real_lib" "$SB${real_lib}"
                    if [ "$real_lib" != "$lib_path" ] && [ -e "$SB${real_lib}" ]; then
                        local_lp_real=$(readlink -f "$(dirname "$lib_path")")"/$(basename "$lib_path")"
                        if [ "$local_lp_real" != "$real_lib" ]; then
                            mkdir -p "$(dirname "$SB$local_lp_real")"
                            ln -sf "$(basename "$real_lib")" "$SB$local_lp_real"
                        fi
                    fi
                fi
            fi
        done < <(ldd "$plib" 2>/dev/null)
    done
    for cmd in envsubst gettext ngettext \
               msgattrib msgcat msgcmp msgcomm msgconv msgen msgexec \
               msgfilter msgfmt msggrep msginit msgmerge msgunfmt \
               msguniq recode-sr-latin xgettext ; do
        src="$C3_TMP/extract/usr/bin/$cmd"
        if [ -f "$src" ]; then
            cp "$src" "$SB/usr/bin/$cmd"
            # ldd で解決可能な依存 (libunistring / libc 等) を copy。
            # private lib は上で copy 済 (ldd は "not found" を返すが無視)。
            while IFS= read -r line; do
                if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then
                    lib_path="${BASH_REMATCH[1]}"
                    real_lib=$(readlink -f "$lib_path")
                    if [ -f "$real_lib" ]; then
                        copy_if "$real_lib" "$SB${real_lib}"
                        if [ "$real_lib" != "$lib_path" ] && [ -e "$SB${real_lib}" ]; then
                            local_lp_real=$(readlink -f "$(dirname "$lib_path")")"/$(basename "$lib_path")"
                            if [ "$local_lp_real" != "$real_lib" ]; then
                                mkdir -p "$(dirname "$SB$local_lp_real")"
                                ln -sf "$(basename "$real_lib")" "$SB$local_lp_real"
                            fi
                        fi
                    fi
                fi
            done < <(ldd "$src" 2>/dev/null)
            [ ! -e "$SB/bin/$cmd" ] && ln -sf "../usr/bin/$cmd" "$SB/bin/$cmd"
        fi
    done
fi

# issue #10 (C2 gpg): Git for Windows /usr/bin にある GnuPG / OpenPGP
# tool 群。git tag 検証 (git verify-tag / git -S) や commit signing で使う。
#   host 同梱 (14): dirmngr dirmngr-client gpg gpg-agent gpg-connect-agent
#                   gpgconf gpgparsemail gpgsm gpgsplit gpgtar gpgv kbxutil
#                   watchgnupg + pinentry
#   apt-get download (4): gpg-error yat2m (gpgrt-tools) / gpg-wks-server /
#                         sexp-conv (nettle-bin)
#   未収録 (5): dumpsexp gpgscm hmac256 ldh mpicalc — GnuPG / libgcrypt の
#               source-only test tool で Debian/Ubuntu の package archive に
#               存在しない。必要なら source build が要るが本 issue 範囲外。
# pinentry は host が GTK2 版 (X11 依存で headless 不可) なので、emulin
# 向けに pinentry-tty を apt-get download して /usr/bin/pinentry に置く。
for cmd in dirmngr dirmngr-client gpg gpg-agent gpg-connect-agent gpgconf \
           gpgparsemail gpgsm gpgsplit gpgtar gpgv kbxutil watchgnupg ; do
    copy_cmd_with_deps "$cmd"
done
if command -v apt-get >/dev/null 2>&1; then
    C2_TMP=$(mktemp -d -t emulin-c2.XXXXXX)
    trap 'rm -rf "$C2_TMP" 2>/dev/null || true' EXIT
    ( cd "$C2_TMP" && apt-get download gpgrt-tools gpg-wks-server nettle-bin pinentry-tty >/dev/null 2>&1 \
      && for d in *.deb; do dpkg -x "$d" extract 2>/dev/null; done ) || true
    copy_copyrights_from_extract "$C2_TMP/extract"
    # gpg-error / yat2m / gpg-wks-server / sexp-conv は extract/usr/bin から。
    # pinentry-tty は extract/usr/bin/pinentry-tty → /usr/bin/pinentry に置く。
    for entry in gpg-error gpg-wks-server yat2m sexp-conv "pinentry-tty:pinentry" ; do
        srcname=${entry%%:*}
        dstname=${entry##*:}
        src="$C2_TMP/extract/usr/bin/$srcname"
        if [ -f "$src" ]; then
            cp "$src" "$SB/usr/bin/$dstname"
            while IFS= read -r line; do
                if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then
                    lib_path="${BASH_REMATCH[1]}"
                    real_lib=$(readlink -f "$lib_path")
                    if [ -f "$real_lib" ]; then
                        copy_if "$real_lib" "$SB${real_lib}"
                        if [ "$real_lib" != "$lib_path" ] && [ -e "$SB${real_lib}" ]; then
                            local_lp_real=$(readlink -f "$(dirname "$lib_path")")"/$(basename "$lib_path")"
                            if [ "$local_lp_real" != "$real_lib" ]; then
                                mkdir -p "$(dirname "$SB$local_lp_real")"
                                ln -sf "$(basename "$real_lib")" "$SB$local_lp_real"
                            fi
                        fi
                    fi
                fi
            done < <(ldd "$src" 2>/dev/null)
            [ ! -e "$SB/bin/$dstname" ] && ln -sf "../usr/bin/$dstname" "$SB/bin/$dstname"
        fi
    done
fi
# GnuPG の helper (gpg-agent / dirmngr 等は /usr/libexec or /usr/lib/gnupg
# から spawn される)。gpg が子プロセスとして起動するので copy しておく。
for helper_dir in /usr/libexec /usr/lib/gnupg ; do
    if [ -d "$helper_dir" ]; then
        for h in gpg-agent dirmngr scdaemon gpg-protect-tool gpg-preset-passphrase \
                 keyboxd gpg-check-pattern ; do
            if [ -f "$helper_dir/$h" ]; then
                copy_with_deps "$helper_dir/$h"
            fi
        done
    fi
done

# issue #19 (C11 misc): Git for Windows /usr/bin にある misc / debug tool。
#   host 同梱 (6): chattr chcon iconv ldd pldd strace
#     - chattr/chcon は ext4 拡張属性 / SELinux 操作。emulin は xattr を
#       ENOSYS stub するので機能限定だが smoke (--help) は通る。
#     - strace は emulator-on-emulator (ptrace) なので実 trace は不可、
#       smoke のみ。
#   apt-get download (2):
#     - locate  → plocate package の plocate を /usr/bin/locate に配置
#     - pluginviewer → sasl2-bin の saslpluginviewer (Cyrus SASL plugin
#       viewer の Debian 改名版) を /usr/bin/pluginviewer に配置
#   除外 (4, 実質 category D): gkill / gmondump / minidumper / profiler は
#     Cygwin 固有の debug tool で Linux 等価物が無いため対象外。
for cmd in chattr chcon iconv ldd pldd strace ; do
    copy_cmd_with_deps "$cmd"
done
if command -v apt-get >/dev/null 2>&1; then
    C11_TMP=$(mktemp -d -t emulin-c11.XXXXXX)
    trap 'rm -rf "$C11_TMP" 2>/dev/null || true' EXIT
    # plocate は liburing2 (io_uring) に依存するが host 未 install のことが
    # 多い。liburing2 も一緒に download して extract から ldd 解決させる。
    ( cd "$C11_TMP" && apt-get download plocate sasl2-bin liburing2 >/dev/null 2>&1 \
      && for d in *.deb; do dpkg -x "$d" extract 2>/dev/null; done ) || true
    copy_copyrights_from_extract "$C11_TMP/extract"
    # liburing2 等の同梱 lib を sandbox に先回り copy (ldd が host で
    # "not found" を返すため)。
    for plib in "$C11_TMP/extract/usr/lib/x86_64-linux-gnu/"liburing.so* ; do
        if [ -f "$plib" ]; then
            base=$(basename "$plib")
            cp -L "$plib" "$SB/usr/lib/x86_64-linux-gnu/$base"
        fi
    done
    # liburing.so.2 の soname symlink (.so.2.x.x → .so.2)
    for real in "$SB/usr/lib/x86_64-linux-gnu/"liburing.so.2.* ; do
        [ -f "$real" ] && ln -sf "$(basename "$real")" "$SB/usr/lib/x86_64-linux-gnu/liburing.so.2"
    done
    # plocate → locate / saslpluginviewer → pluginviewer に rename して配置。
    # saslpluginviewer は /usr/sbin にあるので両 path を探す。
    for entry in "plocate:locate" "saslpluginviewer:pluginviewer" ; do
        srcname=${entry%%:*}
        dstname=${entry##*:}
        src="$C11_TMP/extract/usr/bin/$srcname"
        [ -f "$src" ] || src="$C11_TMP/extract/usr/sbin/$srcname"
        if [ -f "$src" ]; then
            cp "$src" "$SB/usr/bin/$dstname"
            while IFS= read -r line; do
                if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then
                    lib_path="${BASH_REMATCH[1]}"
                    real_lib=$(readlink -f "$lib_path")
                    if [ -f "$real_lib" ]; then
                        copy_if "$real_lib" "$SB${real_lib}"
                        if [ "$real_lib" != "$lib_path" ] && [ -e "$SB${real_lib}" ]; then
                            local_lp_real=$(readlink -f "$(dirname "$lib_path")")"/$(basename "$lib_path")"
                            if [ "$local_lp_real" != "$real_lib" ]; then
                                mkdir -p "$(dirname "$SB$local_lp_real")"
                                ln -sf "$(basename "$real_lib")" "$SB$local_lp_real"
                            fi
                        fi
                    fi
                fi
            done < <(ldd "$src" 2>/dev/null)
            [ ! -e "$SB/bin/$dstname" ] && ln -sf "../usr/bin/$dstname" "$SB/bin/$dstname"
        fi
    done
fi

# pager (less): git log / git diff / man 等が PAGER として呼ぶ。
# 192 KB と軽量、deps は libc + libtinfo のみ。
copy_cmd_with_deps "less"

# terminfo (xterm/vt100/screen 等の terminal capability database)。
# less / vim / emacs / ncurses 系全般で必要。7.4 MB。
if [ -d /usr/share/terminfo ] && [ ! -d "$SB/usr/share/terminfo" ]; then
    cp -r /usr/share/terminfo "$SB/usr/share/" 2>/dev/null || true
fi

# issue #12 (C4 terminfo tools): ncurses-bin + less の terminal capability
# 操作 tool 群。tput / clear / reset は shell script で頻出、tic / infocmp
# は terminfo DB の compile / decompile。terminfo DB は上で同梱済。
#   ncurses-bin: captoinfo infocmp infotocap tabs tic toe tput tset
#   less:        lessecho lesskey
# 順序注意: captoinfo / infotocap は tic への symlink なので、tic を先に
# copy しないと copy_cmd_with_deps の fallback symlink 補完が circular
# symlink (../../bin/captoinfo → ../usr/bin/captoinfo) を作ってしまう。
for cmd in \
    tic infocmp captoinfo infotocap tabs toe tput tset \
    lessecho lesskey ; do
    copy_cmd_with_deps "$cmd"
done

# issue #16 (C8 shell-editor): 軽量 shell / editor の代替。
#   dash:  Debian Almquist shell。POSIX shell の軽量実装、bash の代替。
#   nano:  軽量 editor。vim/emacs より入門しやすい。
#   rnano: restricted nano (= nano への symlink)。
# 順序注意: rnano は nano への symlink なので nano を先に copy する
# (C4 の tic / captoinfo と同じ理由)。
for cmd in dash nano rnano ; do
    copy_cmd_with_deps "$cmd"
done
# nano の config / syntax highlight 定義
copy_if /etc/nanorc "$SB/etc/nanorc"
if [ -d /usr/share/nano ] && [ ! -d "$SB/usr/share/nano" ]; then
    cp -r /usr/share/nano "$SB/usr/share/" 2>/dev/null || true
fi

# issue #15 (C7 archive): Git for Windows /usr/bin にある archive / 圧縮
# 補完 tool。基本的な tar / gzip / unzip は busybox で代替できるので
# 補完的だが、完全性のため同梱する。
#   bzip2: bzip2recover (壊れた .bz2 から block 単位で復旧)
#   unzip: funzip (zip/gzip stream を stdout に展開) / unzipsfx
#          (self-extracting zip stub) / zipinfo (zip 詳細表示)
for cmd in bzip2recover funzip unzipsfx zipinfo ; do
    copy_cmd_with_deps "$cmd"
done

# issue #7 (Git for Windows /usr/bin 互換): dos2unix package。
#   dos2unix / unix2dos は busybox applet にもあるが、Git for Windows は
#   さらに mac2unix / unix2mac (Mac line ending 変換) と短縮 alias
#   d2u / u2d を同梱している。Debian の dos2unix package が全部含む
#   ので、host にあれば copy、無ければ apt-get download。
if command -v apt-get >/dev/null 2>&1 || command -v dos2unix >/dev/null 2>&1; then
    D2U_TMP=$(mktemp -d -t emulin-d2u.XXXXXX)
    trap 'rm -rf "$D2U_TMP" 2>/dev/null || true' EXIT
    if [ -x /usr/bin/dos2unix ]; then
        D2U_SRC_DIR=/usr/bin
        copy_host_copyright "dos2unix"  # issue #63
    else
        ( cd "$D2U_TMP" && apt-get download dos2unix >/dev/null 2>&1 \
          && for d in *.deb; do dpkg -x "$d" extract; done ) || true
        copy_copyrights_from_extract "$D2U_TMP/extract"
        D2U_SRC_DIR="$D2U_TMP/extract/usr/bin"
    fi
    for cmd in dos2unix unix2dos mac2unix unix2mac ; do
        src="$D2U_SRC_DIR/$cmd"
        if [ -f "$src" ]; then
            cp "$src" "$SB/usr/bin/$cmd"
            while IFS= read -r line; do
                if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then
                    lib_path="${BASH_REMATCH[1]}"
                    real_lib=$(readlink -f "$lib_path")
                    if [ -f "$real_lib" ]; then
                        copy_if "$real_lib" "$SB${real_lib}"
                        if [ "$real_lib" != "$lib_path" ] && [ -e "$SB${real_lib}" ]; then
                            local_lp_real=$(readlink -f "$(dirname "$lib_path")")"/$(basename "$lib_path")"
                            if [ "$local_lp_real" != "$real_lib" ]; then
                                mkdir -p "$(dirname "$SB$local_lp_real")"
                                ln -sf "$(basename "$real_lib")" "$SB$local_lp_real"
                            fi
                        fi
                    fi
                fi
            done < <(ldd "$src" 2>/dev/null)
            [ ! -e "$SB/bin/$cmd" ] && ln -sf "../usr/bin/$cmd" "$SB/bin/$cmd"
        fi
    done
    # 短縮 alias: d2u = dos2unix, u2d = unix2dos (Git for Windows 同梱)
    [ -f "$SB/usr/bin/dos2unix" ] && ln -sf dos2unix "$SB/usr/bin/d2u"
    [ -f "$SB/usr/bin/unix2dos" ] && ln -sf unix2dos "$SB/usr/bin/u2d"
fi

# 重 binary: git / curl / wget (HTTPS 動作デモ + ネットワーク用途)
for cmd in git curl wget; do copy_cmd_with_deps "$cmd"; done

# git-core (clone HTTPS で git-remote-https が必要、file:// で git-upload-pack)
if [ -d /usr/lib/git-core ]; then
    mkdir -p "$SB/usr/lib/git-core"
    for f in git git-remote-http git-remote-https git-receive-pack git-upload-pack; do
        if [ -f "/usr/lib/git-core/$f" ]; then
            copy_with_deps "/usr/lib/git-core/$f"
        fi
    done
fi

# Phase 33-14: git templates (hooks/info/branches/description) を同梱。
# 旧版では git clone 中に "warning: templates not found in
# /usr/share/git-core/templates" が出ていた。実害は無いが、ユーザー
# experience 向上のため host から copy する。~30 KB しか増えない。
if [ -d /usr/share/git-core/templates ]; then
    mkdir -p "$SB/usr/share/git-core"
    cp -a /usr/share/git-core/templates "$SB/usr/share/git-core/"
    echo "  /usr/share/git-core/templates: copied from host"
fi

# Phase 29-emacs (experimental): INCLUDE_EMACS=1 で emacs-nox + lisp +
# native-comp + terminfo を bundle する。
# 容量: +120 MB (compressed +40-50 MB)。
# 機能: --version / -Q --batch eval / find-file / save-buffer / interactive
#       編集が動く (Phase 29-B/C/D/E でサポート)。
# 性能: full sandbox 経由の init は重く (load-path の stat 走査が膨大)、
#       --batch 系は数十秒〜数分。 minimal sandbox なら 18 秒程度。
# 取得: host に emacs-nox があれば直接コピー、無ければ apt-get download
#       で .deb を取得 (sudo 不要)。
if [ "${INCLUDE_EMACS:-0}" = "1" ]; then
    echo "[stage] emacs: emacs-nox + lisp + native-comp + terminfo を bundle..."
    EMACS_TMP=$(mktemp -d -t emulin-emacs.XXXXXX)
    trap 'rm -rf "$EMACS_TMP" 2>/dev/null || true' EXIT
    # issue #361: rootfs に dpkg DB がある (Debian base) なら emacs-nox を「package-managed」で
    #   導入する。.deb を dpkg DB に登録 (dpkg -l に現れ、依存・copyright が package metadata 由来)
    #   し、data.tar の全ファイル (binary / lisp / native-comp / libexec pdmp / copyright) を rootfs
    #   へ展開する。busybox base 等 dpkg DB が無い場合は従来どおり copy_with_deps で手コピーする。
    if deb_bundle_closure "$SB" "$EMACS_TMP/debs" emacs-nox; then
        echo "  emacs-nox を package-managed で導入 ($(ls "$EMACS_TMP/debs"/*.deb 2>/dev/null | wc -l) .deb を dpkg DB 登録)"
    else
        # 従来経路 (dpkg DB 無し): host か apt download の extract から copy_with_deps で手コピー。
        if [ -x /usr/bin/emacs-nox ]; then
            EMACS_SRC=/
            for p in emacs-nox emacs-common emacs-bin-common; do copy_host_copyright "$p"; done
        elif command -v apt-get >/dev/null 2>&1; then
            echo "  emacs-nox を apt-get download で取得中..."
            ( cd "$EMACS_TMP" && apt-get download emacs-nox emacs-common emacs-bin-common >/dev/null 2>&1 \
              && for d in *.deb; do dpkg -x "$d" extract; done )
            copy_copyrights_from_extract "$EMACS_TMP/extract"  # issue #63
            EMACS_SRC="$EMACS_TMP/extract"
        else
            echo "  warn: emacs-nox not on host and apt-get unavailable — skipping emacs"
            EMACS_SRC=""
        fi
        if [ -n "$EMACS_SRC" ] && [ -x "$EMACS_SRC/usr/bin/emacs-nox" ]; then
            copy_with_deps "$EMACS_SRC/usr/bin/emacs-nox"
            if [ "$EMACS_SRC" != "/" ] && [ -f "$SB$EMACS_SRC/usr/bin/emacs-nox" ]; then
                mkdir -p "$SB/usr/bin"
                mv "$SB$EMACS_SRC/usr/bin/emacs-nox" "$SB/usr/bin/emacs-nox"
                rm -rf "$SB$EMACS_SRC" 2>/dev/null || true
            fi
            mkdir -p "$SB/usr/share" "$SB/usr/lib" "$SB/usr/libexec"
            cp -r "$EMACS_SRC/usr/share/emacs"   "$SB/usr/share/"   2>/dev/null || true
            cp -r "$EMACS_SRC/usr/lib/emacs"     "$SB/usr/lib/"     2>/dev/null || true
            cp -r "$EMACS_SRC/usr/libexec/emacs" "$SB/usr/libexec/" 2>/dev/null || true
        fi
    fi
    # ここから先は配置方法 (package-managed / copy_with_deps) に依らない emulin 固有の後処理。
    if [ -x "$SB/usr/bin/emacs-nox" ]; then
        # /bin に symlink (POSIX 慣習)
        if [ -e "$SB/usr/bin/emacs-nox" ] && [ ! -e "$SB/bin/emacs-nox" ]; then
            ln -sf ../usr/bin/emacs-nox "$SB/bin/emacs-nox"
        fi
        # Phase 33-22: emacs は argv[0] (/bin/emacs-nox) からそのまま
        # Vinvocation_directory を計算するため、/bin/../native-lisp/ =
        # /native-lisp/ を native-comp .eln 検索 path に含める。
        # 実 .eln files は /usr/lib/emacs/<ver>/native-lisp/ にあるので
        # /native-lisp → /usr/lib/emacs/<ver>/native-lisp の symlink で
        # 解決させる。/bin/emacs-nox 経由でも /usr/bin/emacs-nox 経由でも
        # 動作。
        EMACS_VER_DIR=$(ls "$SB/usr/lib/emacs/" 2>/dev/null | head -1)
        if [ -n "$EMACS_VER_DIR" ] && [ -d "$SB/usr/lib/emacs/$EMACS_VER_DIR/native-lisp" ]; then
            ln -sfn "usr/lib/emacs/$EMACS_VER_DIR/native-lisp" "$SB/native-lisp"
            echo "  /native-lisp → usr/lib/emacs/$EMACS_VER_DIR/native-lisp (eln search path)"
        fi
        # terminfo (xterm/vt100/screen 等の terminal capability database)
        if [ -d /usr/share/terminfo ]; then
            cp -r /usr/share/terminfo "$SB/usr/share/" 2>/dev/null || true
        fi
        # issue #132 (続き: info install): 空の /usr/share/info を作る。
        #   ddskk 等は info の install 先を PREFIX(/usr)/share/info → /usr/info →
        #   /info → Info-default-directory-list の順で「既存 dir」から探す。実 Linux
        #   には /usr/share/info があるが sandbox には無く、Info-default-directory-list
        #   も -Q 起動では nil のため SKK_INFODIR=nil → info install が Error 255 に
        #   なる。空 dir を置けば install 先として採用され .info/dir を書き込める。
        mkdir -p "$SB/usr/share/info"
        # /dev/null 等の device entry (emacs --batch が stdin redirect で必要)
        mkdir -p "$SB/dev"
        for d in null urandom zero tty; do
            [ -e "$SB/dev/$d" ] || touch "$SB/dev/$d"
            chmod 666 "$SB/dev/$d" 2>/dev/null || true
        done
        echo "  emacs-nox + lisp ($(du -sh "$SB/usr/share/emacs" 2>/dev/null | awk '{print $1}'))"
        # issue #132: emacs-nox を wrapper 化し runtime native-comp を抑止する。
        #   emacs 29.3 は subr への advice-add で trampoline を実行時 native-compile し
        #   as/ld を exec するが sandbox に toolchain が無く失敗する (ddskk の make
        #   install が Error 255)。ddskk install は emacs --quick 起動で
        #   site-start.el/default.el が読まれないため、native-comp 抑止変数を --eval で
        #   前置注入する wrapper にする (prebuilt .eln のロードには影響しない)。
        if [ -f "$SB/usr/bin/emacs-nox" ] && [ ! -f "$SB/usr/bin/emacs-nox.bin" ]; then
            mv "$SB/usr/bin/emacs-nox" "$SB/usr/bin/emacs-nox.bin"
            cat > "$SB/usr/bin/emacs-nox" <<'EMACS_WRAP'
#!/bin/sh
# issue #132: runtime native-comp (subr trampoline / deferred compile) を抑止する。
# emulin sandbox には as/ld (binutils) が無く実行時 native-comp が失敗するため、
# 抑止変数を --eval で前置して emacs 本体を起動する。prebuilt .eln は通常通りロード。
exec /usr/bin/emacs-nox.bin \
  --eval '(setq native-comp-enable-subr-trampolines nil native-comp-jit-compilation nil)' \
  "$@"
EMACS_WRAP
            chmod 755 "$SB/usr/bin/emacs-nox"
            echo "  emacs-nox を native-comp 抑止 wrapper 化 (issue #132)"
        fi
        # `emacs` 別名: bundle が提供するのは端末版 emacs-nox だが、user は素の
        #   `emacs` で起動したい (apt の emacs 慣習)。emacs-nox wrapper を指す
        #   symlink を /usr/bin と /bin に張る (wrapper 経由なので native-comp
        #   抑止も継承。Windows build では cyg-symlinkify で magic file 化される)。
        if [ -e "$SB/usr/bin/emacs-nox" ] && [ ! -e "$SB/usr/bin/emacs" ]; then
            ln -sf emacs-nox "$SB/usr/bin/emacs"
            echo "  /usr/bin/emacs → emacs-nox (別名 symlink)"
        fi
        if [ -e "$SB/usr/bin/emacs-nox" ] && [ ! -e "$SB/bin/emacs" ]; then
            ln -sf ../usr/bin/emacs-nox "$SB/bin/emacs"
        fi
        # issue #132 (補強): 上の wrapper の --eval は『対話起動』では遅すぎる。
        #   emacs 29 は package-enable-at-startup=t のとき、init.el を読む前に
        #   package-activate-all で全パッケージ autoloads をロードし、そこで subr
        #   trampoline の native-compile が走る (sumibi 等が message を再定義する経路)。
        #   --eval は command-line-1 = init.el の後に処理されるため間に合わない。
        #   package-activate-all より前に読まれる唯一の hook = early-init.el に
        #   native-comp 抑止を置く (launcher が HOME=/root を export するため /root)。
        mkdir -p "$SB/root/.emacs.d"
        cat > "$SB/root/.emacs.d/early-init.el" <<'EARLYINIT_EOF'
;;; early-init.el --- emulin sandbox: 実行時 native compilation を無効化 -*- lexical-binding: t; -*-
;; emulin の sandbox には gcc/binutils が無いため、emacs 29 の実行時 native
;; compilation (libgccjit -> gcc driver) は "error invoking gcc driver" で失敗する。
;; package-activate-all (init.el より前に全パッケージ autoloads をロード) で subr
;; trampoline の native-compile が走るため、それより前に読まれる early-init.el で
;; 抑止する (init.el や emacs-nox wrapper の --eval では遅い)。bytecode/interpreted
;; で正常動作する (prebuilt .eln のロードには影響しない)。
(setq native-comp-jit-compilation nil)
(setq native-comp-enable-subr-trampolines nil)
;;; early-init.el ends here
EARLYINIT_EOF
        echo "  emacs: /root/.emacs.d/early-init.el で native-comp 抑止 (issue #132、対話起動経路)"
    fi
fi

# Phase 29-vim: INCLUDE_VIM=1 で vim + runtime + terminfo を bundle する。
# 容量: +50 MB (compressed +15 MB)。emacs より遥かに軽量で init も速い。
# 機能: -e -s ex mode で file 編集 + 置換 + save が動く (Phase 29-vim で
#       8-bit ADC/SBB + xattr stub サポート)。
# 取得: host に vim があれば直接コピー、無ければ apt-get download (.deb) で
#       sudo 不要に取得。
if [ "${INCLUDE_VIM:-0}" = "1" ]; then
    echo "[stage] vim: vim + runtime + terminfo を bundle..."
    VIM_TMP=$(mktemp -d -t emulin-vim.XXXXXX)
    trap 'rm -rf "$VIM_TMP" 2>/dev/null || true' EXIT
    # issue #361: dpkg DB があれば vim を package-managed で導入する。無ければ従来の
    #   host copy / apt download + 手コピー (binary は alternatives 実体名 vim.basic に置く)。
    if deb_bundle_closure "$SB" "$VIM_TMP/debs" vim vim-common vim-runtime; then
        echo "  vim を package-managed で導入 ($(ls "$VIM_TMP/debs"/*.deb 2>/dev/null | wc -l) .deb を dpkg DB 登録)"
    else
        if [ -x /usr/bin/vim.basic ]; then
            VIM_BIN=/usr/bin/vim.basic
            VIM_RUNTIME_SRC=/usr/share/vim
            for p in vim vim-common vim-runtime; do copy_host_copyright "$p"; done
        elif command -v apt-get >/dev/null 2>&1; then
            echo "  vim を apt-get download で取得中..."
            ( cd "$VIM_TMP" && apt-get download vim vim-common vim-runtime >/dev/null 2>&1 \
              && for d in *.deb; do dpkg -x "$d" extract; done )
            copy_copyrights_from_extract "$VIM_TMP/extract"  # issue #63
            VIM_BIN="$VIM_TMP/extract/usr/bin/vim.basic"
            VIM_RUNTIME_SRC="$VIM_TMP/extract/usr/share/vim"
        else
            echo "  warn: vim not on host and apt-get unavailable — skipping vim"
            VIM_BIN=""
        fi
        if [ -n "$VIM_BIN" ] && [ -x "$VIM_BIN" ]; then
            # issue #322: vim 本体は Debian alternatives の実体名 vim.basic に置く
            #   (usr-merge で /bin==/usr/bin。実体を vim.basic に分離し vim/vi/... を symlink)。
            cp "$VIM_BIN" "$SB/usr/bin/vim.basic"
            while IFS= read -r line; do
                if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then
                    lib_path="${BASH_REMATCH[1]}"
                    real_lib=$(readlink -f "$lib_path")
                    if [ -f "$real_lib" ]; then
                        copy_if "$real_lib" "$SB${real_lib}"
                        if [ "$real_lib" != "$lib_path" ] && [ -e "$SB${real_lib}" ]; then
                            local_lp_real=$(readlink -f "$(dirname "$lib_path")")"/$(basename "$lib_path")"
                            if [ "$local_lp_real" != "$real_lib" ]; then
                                mkdir -p "$(dirname "$SB$local_lp_real")"
                                ln -sf "$(basename "$real_lib")" "$SB$local_lp_real"
                            fi
                        fi
                    fi
                fi
            done < <(ldd "$VIM_BIN" 2>/dev/null)
            if [ -d "$VIM_RUNTIME_SRC" ]; then
                cp -r "$VIM_RUNTIME_SRC" "$SB/usr/share/" 2>/dev/null || true
            fi
        fi
    fi
    # ここから先は配置方法に依らない emulin 固有の後処理 (vim.basic がある場合)。
    #   alternatives postinst は走らないので vim/vi/view/... の symlink を自前で張る。
    if [ -x "$SB/usr/bin/vim.basic" ]; then
        # vim 本体 (実体は vim.basic)。/usr/bin/vim → vim.basic の symlink。
        ln -sf vim.basic "$SB/usr/bin/vim"
        # issue #7: Git for Windows /usr/bin の vim alias 群。
        #   vi       — vi 互換 mode
        #   view     — vim を read-only mode (-R) で起動
        #   vimdiff  — vim を diff mode (-d) で起動
        #   rview    — view を restricted mode (-Z) で起動
        #   rvim     — vim を restricted mode (-Z) で起動
        # 全て host vim と同じく symlink で動作 (vim 本体が argv[0] basename を
        # 見て mode を切替)。実体 vim.basic を直接指して多段 symlink を避ける。
        for alias_name in vi view vimdiff rview rvim ; do
            ln -sf vim.basic "$SB/usr/bin/$alias_name"
        done
        # /bin/vi /bin/vim symlink (非 usr-merge bundle 用)。usr-merge では
        #   /bin == /usr/bin で既に上の symlink が見えるため [ ! -e ] で skip し、
        #   実体 (/usr/bin/vim) を clobber しない (issue #322)。
        for _vcmd in vim vi ; do
            [ ! -e "$SB/bin/$_vcmd" ] && ln -sf "../usr/bin/$_vcmd" "$SB/bin/$_vcmd"
        done
        # terminfo (xterm/vt100 等の terminal capability database)
        if [ -d /usr/share/terminfo ] && [ ! -d "$SB/usr/share/terminfo" ]; then
            cp -r /usr/share/terminfo "$SB/usr/share/" 2>/dev/null || true
        fi
        # /dev nodes (vim swap file 等で必要)
        mkdir -p "$SB/dev"
        for d in null urandom zero tty; do
            [ -e "$SB/dev/$d" ] || touch "$SB/dev/$d"
            chmod 666 "$SB/dev/$d" 2>/dev/null || true
        done
        echo "  vim ($(du -sh "$SB/usr/share/vim" 2>/dev/null | awk '{print $1}'))"
    else
        # issue #322: apt-get download 失敗 (network 不通等) で VIM_BIN が
        #   実体を指さないと、ここを silent に通過して vim 無しの bundle を
        #   出荷していた。INCLUDE_VIM=1 を明示したのに入らないのは事故なので
        #   loud に warn する (build 自体は継続)。
        echo "  warn: INCLUDE_VIM=1 だが vim.basic を取得できませんでした。" >&2
        echo "        network 不通か apt repo 未設定の可能性。vim は bundle されません。" >&2
    fi
fi

# issue #18: INCLUDE_TIG=1 で tig (git history browser) を bundle する。
# 容量: +400 KB (tig 本体) + libpcre2-posix3 (+10 KB)。tig は ncurses ベース
# の git 履歴 browser、矢印キーで commit 移動できる開発者必需 tool。
# 取得: host に tig があれば直接、無ければ apt-get download (.deb) で取得。
# 動作確認: emulin /usr/bin/tig --version で version 文字列が表示されれば OK。
if [ "${INCLUDE_TIG:-0}" = "1" ]; then
    echo "[stage] tig: tig (git history browser) を bundle..."
    TIG_TMP=$(mktemp -d -t emulin-tig.XXXXXX)
    trap 'rm -rf "$TIG_TMP" 2>/dev/null || true' EXIT
    # issue #361: dpkg DB があれば tig を package-managed で導入 (tig + libpcre2-posix3 + tigrc は
    #   deb 由来。無ければ従来の host copy / apt download + 手コピー)。
    if deb_bundle_closure "$SB" "$TIG_TMP/debs" tig libpcre2-posix3; then
        echo "  tig を package-managed で導入 ($(ls "$TIG_TMP/debs"/*.deb 2>/dev/null | wc -l) .deb を dpkg DB 登録)"
    else
    if [ -x /usr/bin/tig ]; then
        TIG_BIN=/usr/bin/tig
        TIG_ETC=/etc/tigrc
        # issue #63: host tig + libpcre2-posix3 の copyright
        for p in tig libpcre2-posix3; do copy_host_copyright "$p"; done
    elif command -v apt-get >/dev/null 2>&1; then
        echo "  tig を apt-get download で取得中..."
        ( cd "$TIG_TMP" && apt-get download tig libpcre2-posix3 >/dev/null 2>&1 \
          && for d in *.deb; do dpkg -x "$d" extract; done )
        copy_copyrights_from_extract "$TIG_TMP/extract"  # issue #63
        TIG_BIN="$TIG_TMP/extract/usr/bin/tig"
        TIG_ETC="$TIG_TMP/extract/etc/tigrc"
    else
        echo "  warn: tig not on host and apt-get unavailable — skipping tig"
        TIG_BIN=""
    fi
    if [ -n "$TIG_BIN" ] && [ -x "$TIG_BIN" ]; then
        cp "$TIG_BIN" "$SB/usr/bin/tig"
        # tig の依存 lib を ldd で取得 + soname symlink を作成。
        # soname (libncursesw.so.6) が無いと dynamic linker が解決できない。
        while IFS= read -r line; do
            if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then
                lib_path="${BASH_REMATCH[1]}"
                real_lib=$(readlink -f "$lib_path")
                if [ -f "$real_lib" ]; then
                    copy_if "$real_lib" "$SB${real_lib}"
                    if [ "$real_lib" != "$lib_path" ] && [ -e "$SB${real_lib}" ]; then
                        local_lp_real=$(readlink -f "$(dirname "$lib_path")")"/$(basename "$lib_path")"
                        if [ "$local_lp_real" != "$real_lib" ]; then
                            mkdir -p "$(dirname "$SB$local_lp_real")"
                            ln -sf "$(basename "$real_lib")" "$SB$local_lp_real"
                        fi
                    fi
                fi
            fi
        done < <(ldd "$TIG_BIN" 2>/dev/null)
        # libpcre2-posix.so.3 (apt-get download 経由) — ldd では「not found」
        # と出るので別途 copy + soname symlink を作る。
        for src in "$TIG_TMP/extract/usr/lib/x86_64-linux-gnu/"libpcre2-posix.so.3*; do
            if [ -f "$src" ]; then
                base=$(basename "$src")
                mkdir -p "$SB/usr/lib/x86_64-linux-gnu"
                cp -L "$src" "$SB/usr/lib/x86_64-linux-gnu/$base"
            fi
        done
        # /lib/x86_64-linux-gnu/libpcre2-posix.so.3 の symlink (.so.3.0.4 → .so.3)
        if [ -f "$SB/usr/lib/x86_64-linux-gnu/libpcre2-posix.so.3.0.4" ]; then
            ln -sf libpcre2-posix.so.3.0.4 "$SB/usr/lib/x86_64-linux-gnu/libpcre2-posix.so.3"
        fi
        # tigrc (default keybinding / theme)
        if [ -f "$TIG_ETC" ]; then
            cp "$TIG_ETC" "$SB/etc/tigrc"
        fi
        # terminfo (まだ無ければ)
        if [ -d /usr/share/terminfo ] && [ ! -d "$SB/usr/share/terminfo" ]; then
            cp -r /usr/share/terminfo "$SB/usr/share/" 2>/dev/null || true
        fi
        echo "  tig ($(du -sh "$SB/usr/bin/tig" 2>/dev/null | awk '{print $1}'))"
    fi
    fi
    # terminfo は配置方法に依らず補完 (package-managed 経路でも端末描画に必要)。
    if [ -x "$SB/usr/bin/tig" ] && [ -d /usr/share/terminfo ] && [ ! -d "$SB/usr/share/terminfo" ]; then
        cp -r /usr/share/terminfo "$SB/usr/share/" 2>/dev/null || true
    fi
fi

# issue #130 Tier 1: 開発 CLI tool 群を INCLUDE_* で選択同梱する (jq/sqlite3/nano/
#   tree/patch/zip)。いずれも短命・I/O 中心で emulator 上の実用度が高い。bundle_cli_tool
#   が host コピー優先 + apt-get download fallback (private lib 込み) を処理する。
#   動作確認: emulin /usr/bin/<tool> --version 等。
if [ "${INCLUDE_JQ:-0}" = "1" ]; then
    echo "[stage] jq: JSON processor を bundle..."
    bundle_cli_tool jq jq libjq1 libonig5
fi
if [ "${INCLUDE_SQLITE:-0}" = "1" ]; then
    echo "[stage] sqlite3: SQLite CLI を bundle..."
    bundle_cli_tool sqlite3 sqlite3 libsqlite3-0
fi
if [ "${INCLUDE_NANO:-0}" = "1" ]; then
    echo "[stage] nano: 軽量エディタを bundle..."
    bundle_cli_tool nano
    # nano は端末制御に terminfo を読む (まだ無ければ host から copy)
    if [ -d /usr/share/terminfo ] && [ ! -d "$SB/usr/share/terminfo" ]; then
        cp -r /usr/share/terminfo "$SB/usr/share/" 2>/dev/null || true
    fi
fi
if [ "${INCLUDE_TREE:-0}" = "1" ]; then
    echo "[stage] tree: ディレクトリ表示を bundle..."
    bundle_cli_tool tree
fi
if [ "${INCLUDE_PATCH:-0}" = "1" ]; then
    echo "[stage] patch: diff 適用を bundle..."
    bundle_cli_tool patch
fi
if [ "${INCLUDE_ZIP:-0}" = "1" ]; then
    echo "[stage] zip: zip/unzip/xz を bundle..."
    bundle_cli_tool zip
    bundle_cli_tool unzip
    bundle_cli_tool xz xz-utils
fi

# issue #130 Tier 2: rsync (ファイル同期)。local sync は fork+socketpair で動作確認済み
#   (remote は同梱 ssh transport 経由)。openat2(437) は ENOSYS で rsync が gracefully
#   fallback する。Tier 2 の tmux は issue #131 (layer 14 SCM_RIGHTS + layer 15b
#   accept spin 解消) で対応済 (下記 INCLUDE_TMUX)。ripgrep/fd (Rust) も
#   issue #131 Part A (statx(2) #136 + amd64_statx NULL probe EFAULT 化 #141) で
#   file 走査 segfault が解消し対応済 (下記 INCLUDE_RIPGREP / INCLUDE_FD)。
if [ "${INCLUDE_RSYNC:-0}" = "1" ]; then
    echo "[stage] rsync: ファイル同期を bundle..."
    bundle_cli_tool rsync
fi

# issue #131 Tier 2: INCLUDE_TMUX=1 で terminal multiplexer tmux を同梱する。
#   emulator-core 対応は layer 14 (sendmsg/recvmsg の SCM_RIGHTS fd passing で
#   "open terminal failed: not a terminal" を解消) + layer 15b (listen socket の
#   bind 直後 POLLIN one-shot 化で accept spin 解消) で完了。
#   前景 `tmux new-session` は client が server を fork し socketpair で IPC する
#   ため両端が同一 JVM に居り、client の tty fd を SCM_RIGHTS で server へ渡して
#   isatty() を通せる。status bar まで描画する。
#   必須: JLine console (-CJ、emulin.sh/.bat 既定)。StdConsole では tty fd の
#   readiness 判定が raw mode を扱えず動かない。pane shell に /bin/sh (= bash)、
#   端末能力に terminfo、pty は emulin PtyManager が内部合成 (/dev/ptmx 実体不要)。
#   動作確認: emulin -CJ /usr/bin/tmux -V で "tmux 3.x" が表示されれば smoke 合格。
#     interactive 描画は実端末で `tmux` → status bar 確認 (vim/emacs と同経路)。
if [ "${INCLUDE_TMUX:-0}" = "1" ]; then
    echo "[stage] tmux: terminal multiplexer を bundle..."
    bundle_cli_tool tmux
    # tmux は terminal capability database (terminfo: screen/tmux/xterm/vt100 等) を
    #   読む。base / vim / emacs 同梱で既に存在することが多いが、無ければ host から copy。
    if [ -d /usr/share/terminfo ] && [ ! -d "$SB/usr/share/terminfo" ]; then
        cp -r /usr/share/terminfo "$SB/usr/share/" 2>/dev/null || true
    fi
    # pane が pty を確保する経路 (/dev/ptmx open) は emulin PtyManager が内部処理
    #   するので /dev/ptmx 実体は不要。/dev/null,tty 等は base 同梱で存在。
    echo "  tmux ($(du -sh "$SB/usr/bin/tmux" 2>/dev/null | awk '{print $1}'))"
fi

# issue #131 Tier 2 Part A: INCLUDE_RIPGREP=1 / INCLUDE_FD=1 で Rust 製の高速検索
#   ツール ripgrep (rg) / fd を同梱する。「file 走査中 segfault」は statx(2) 実装
#   (#136) + amd64_statx の NULL probe を EFAULT で返す修正 (#141) で解消済み
#   (Rust std の fstat=statx → fallback newfstatat(0,NULL) 不整合が原因だった)。
#   いずれも libc/libgcc_s/libpcre2 依存のみ・terminal 非対話。
#   動作確認: emulin /usr/bin/rg -n PATTERN DIR で走査が rc=0 完走すれば smoke 合格。
if [ "${INCLUDE_RIPGREP:-0}" = "1" ]; then
    echo "[stage] ripgrep: 高速 grep (rg) を bundle..."
    bundle_cli_tool rg ripgrep
fi
if [ "${INCLUDE_FD:-0}" = "1" ]; then
    echo "[stage] fd: 高速 find (fd) を bundle..."
    # Debian/Ubuntu の binary 名は fdfind (package fd-find)。fd-find を取得し
    #   /usr/bin/fd → fdfind の symlink も張る (慣習的な fd 名で呼べるように)。
    bundle_cli_tool fdfind fd-find
    if [ -e "$SB/usr/bin/fdfind" ] && [ ! -e "$SB/usr/bin/fd" ]; then
        ln -sf fdfind "$SB/usr/bin/fd"
        [ -e "$SB/bin/fd" ] || ln -sf ../usr/bin/fdfind "$SB/bin/fd"
    fi
fi
# issue #129: INCLUDE_MAKE=1 で GNU make を sandbox に同梱する。
# Makefile ベースの build / task 実行用。make 本体は ~254 KB、依存は libc のみ
# (既に同梱済み) なので追加 .so 不要。recipe 実行には /bin/sh が要るが bash
# 同梱時に存在する。C/C++ compile は gcc/cc 等が別途必要で本 issue 範囲外。
# 取得: host に make があれば copy_cmd_with_deps で直接、無ければ apt-get download。
# 動作確認: emulin /usr/bin/make --version で "GNU Make" が表示されれば OK。
if [ "${INCLUDE_MAKE:-0}" = "1" ]; then
    echo "[stage] make: GNU make を bundle..."
    # issue #361: dpkg DB があれば package-managed で導入 (無ければ従来の host copy / apt download)。
    MAKE_PM_TMP=$(mktemp -d -t emulin-makepm.XXXXXX); trap 'rm -rf "$MAKE_PM_TMP" 2>/dev/null || true' EXIT
    if deb_bundle_closure "$SB" "$MAKE_PM_TMP/debs" make; then
        echo "  make を package-managed で導入"
    else
    if [ -x /usr/bin/make ] || [ -x /bin/make ]; then
        copy_cmd_with_deps make
    elif command -v apt-get >/dev/null 2>&1; then
        echo "  make を apt-get download で取得中..."
        MAKE_TMP=$(mktemp -d -t emulin-make.XXXXXX)
        trap 'rm -rf "$MAKE_TMP" 2>/dev/null || true' EXIT
        ( cd "$MAKE_TMP" && apt-get download make >/dev/null 2>&1 \
          && for d in *.deb; do dpkg -x "$d" extract; done ) || true
        copy_copyrights_from_extract "$MAKE_TMP/extract"  # issue #63
        MAKE_BIN="$MAKE_TMP/extract/usr/bin/make"
        if [ -x "$MAKE_BIN" ]; then
            cp "$MAKE_BIN" "$SB/usr/bin/make"
            # 依存 .so を ldd で解決 (make は通常 libc のみで既に同梱済み)
            while IFS= read -r line; do
                if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then
                    real_lib=$(readlink -f "${BASH_REMATCH[1]}")
                    [ -f "$real_lib" ] && copy_if "$real_lib" "$SB${real_lib}"
                fi
            done < <(ldd "$MAKE_BIN" 2>/dev/null)
            [ -e "$SB/bin/make" ] || ln -sf ../usr/bin/make "$SB/bin/make"
        else
            echo "  warn: make not retrievable via apt-get — skipping make"
        fi
    else
        echo "  warn: make not on host and apt-get unavailable — skipping make"
    fi
    fi
    if [ -e "$SB/usr/bin/make" ]; then
        echo "  make ($(du -sh "$SB/usr/bin/make" 2>/dev/null | awk '{print $1}'))"
    fi
fi

# issue #9: INCLUDE_SSH=1 で openssh client tool 群を sandbox に同梱する。
# 対象: ssh / scp / sftp / ssh-add / ssh-agent / ssh-keygen / ssh-keyscan
# sshd (server) は対象外。
# 容量: +5 MB (binary 群) + ~3 MB (libssl/libkrb5/libgssapi 等の依存)
# 動作確認: emulin /usr/bin/ssh -V で OpenSSH バージョン文字列が表示されれば
#   smoke 合格。実際の network 接続は emulin の AF_UNIX 未対応 (ssh-agent
#   通信路) / AF_INET6 未対応 (server fallback) で別途確認が必要。
if [ "${INCLUDE_SSH:-0}" = "1" ]; then
    echo "[stage] ssh: openssh client tool 群を bundle..."
    # issue #361: dpkg DB があれば openssh-client を package-managed で導入する
    #   (ssh/scp/sftp/ssh-keygen 等 + /etc/ssh/ssh_config が deb 由来)。無ければ従来の host copy。
    SSH_PM_TMP=$(mktemp -d -t emulin-sshpm.XXXXXX); trap 'rm -rf "$SSH_PM_TMP" 2>/dev/null || true' EXIT
    if deb_bundle_closure "$SB" "$SSH_PM_TMP/debs" openssh-client; then
        echo "  ssh を package-managed で導入 ($(ls "$SSH_PM_TMP/debs"/*.deb 2>/dev/null | wc -l) .deb を dpkg DB 登録)"
    else
        for cmd in ssh scp sftp ssh-add ssh-agent ssh-keygen ssh-keyscan; do
            copy_cmd_with_deps "$cmd"
        done
        # /etc/ssh の default ssh_config (Algorithms / Ciphers の host default)
        if [ -d /etc/ssh ]; then
            mkdir -p "$SB/etc/ssh"
            for f in ssh_config moduli; do
                [ -f "/etc/ssh/$f" ] && cp -L "/etc/ssh/$f" "$SB/etc/ssh/$f"
            done
            # /etc/ssh/ssh_config.d/ は version 別 override
            if [ -d /etc/ssh/ssh_config.d ]; then
                cp -r /etc/ssh/ssh_config.d "$SB/etc/ssh/" 2>/dev/null || true
            fi
        fi
    fi
    # /dev nodes (ssh が /dev/tty を open)
    mkdir -p "$SB/dev"
    for d in null urandom zero tty; do
        [ -e "$SB/dev/$d" ] || touch "$SB/dev/$d"
        chmod 666 "$SB/dev/$d" 2>/dev/null || true
    done
    echo "  ssh ($(ls "$SB/usr/bin/ssh" 2>/dev/null | xargs -r du -sh | awk '{print $1}'))"
fi

# issue #41: INCLUDE_SSHD=1 で OpenSSH sshd (server) を sandbox に同梱する。
# 容量: +1 MB (sshd 本体) + ~500 KB (libnss_* 3 個 + 既に bundle 済の libssl
#   / libkrb5 / libgssapi / libcrypto を共用)。
# 動作確認: emulin /usr/sbin/sshd -V で OpenSSH バージョン文字列が表示されれば
#   smoke 合格。実際の接続テストは tests/scripts/sshd-smoke.sh を参照。
# 用途: Phase 1 MVP — publickey auth + non-interactive command exec
#   (`ssh -i key root@host 'echo hello'` 相当)。
#   interactive shell (PTY) は Phase 2 (stretch goal、別 issue)。
# 設定: INCLUDE_SSHD_AUTHORIZED_KEY=/path/to/pubkey を指定すると、その
#   pubkey が /root/.ssh/authorized_keys に追加される (動作確認用)。
#   未指定なら user が後で sandbox 内に置く。
if [ "${INCLUDE_SSHD:-0}" = "1" ]; then
    echo "[stage] sshd: openssh server を bundle..."
    # sshd 本体 + per-connection / auth helper (/usr/sbin/sshd は
    # copy_cmd_with_deps が使えない → 直接 copy)。OpenSSH 9.8+ は sshd を
    # listener (sshd) / per-connection (sshd-session) / auth (sshd-auth) に
    # privsep 分離した。sshd-session・sshd-auth (/usr/lib/openssh/) が無いと
    # sshd は起動 or 接続受付に失敗する ("sshd-session does not exist" /
    # 接続時 re-exec 失敗)。issue #308 (Debian trixie = OpenSSH 10.0 で顕在化)。
    # host に無ければ apt-get download openssh-server で取得 (clean host / CI 対応)。
    # 依存 (libselinux/libcrypto/libpcre2/libwrap/libpam 等) は ldd で補完。
    # issue #361: dpkg DB があれば openssh-server (+ sftp-server) を package-managed で導入する
    #   (sshd / sshd-session / sshd-auth / sftp-server + 依存が deb 由来 + dpkg DB 登録)。無ければ
    #   従来の host copy / apt download。以降の NSS / host key / sshd_config 設定は配置方法に依らず共通。
    SSHD_PM_TMP=$(mktemp -d -t emulin-sshdpm.XXXXXX); trap 'rm -rf "$SSHD_PM_TMP" 2>/dev/null || true' EXIT
    if deb_bundle_closure "$SB" "$SSHD_PM_TMP/debs" openssh-server openssh-sftp-server; then
        echo "  sshd を package-managed で導入 (openssh-server + openssh-sftp-server, $(ls "$SSHD_PM_TMP/debs"/*.deb 2>/dev/null | wc -l) .deb を dpkg DB 登録)"
    else
    if [ -f /usr/sbin/sshd ]; then
        copy_with_deps /usr/sbin/sshd
        copy_host_copyright_for_binary /usr/sbin/sshd  # issue #63
        # OpenSSH 9.8+ の per-connection / auth helper (host にあれば bundle)
        for h in sshd-session sshd-auth; do
            [ -f "/usr/lib/openssh/$h" ] && { mkdir -p "$SB/usr/lib/openssh"; copy_with_deps "/usr/lib/openssh/$h"; }
        done
    elif command -v apt-get >/dev/null 2>&1; then
        echo "  sshd を apt-get download (openssh-server) で取得中..."
        SSHD_TMP=$(mktemp -d -t emulin-sshd.XXXXXX)
        ( cd "$SSHD_TMP" && apt-get download openssh-server >/dev/null 2>&1 \
          && for d in *.deb; do dpkg -x "$d" extract; done ) || true
        copy_copyrights_from_extract "$SSHD_TMP/extract"  # issue #63
        if [ -f "$SSHD_TMP/extract/usr/sbin/sshd" ]; then
            mkdir -p "$SB/usr/sbin"
            cp "$SSHD_TMP/extract/usr/sbin/sshd" "$SB/usr/sbin/sshd"
            copy_extract_bin_deps "$SSHD_TMP/extract/usr/sbin/sshd"
            # per-connection / auth helper は同じ openssh-server deb に入る
            for h in sshd-session sshd-auth; do
                if [ -f "$SSHD_TMP/extract/usr/lib/openssh/$h" ]; then
                    mkdir -p "$SB/usr/lib/openssh"
                    cp "$SSHD_TMP/extract/usr/lib/openssh/$h" "$SB/usr/lib/openssh/$h"
                    copy_extract_bin_deps "$SSHD_TMP/extract/usr/lib/openssh/$h"
                fi
            done
        else
            echo "  warn: sshd not retrievable via apt-get — INCLUDE_SSHD skip"
        fi
        rm -rf "$SSHD_TMP"
    else
        echo "  warn: /usr/sbin/sshd が host に無く apt-get も不可 — INCLUDE_SSHD skip"
    fi
    # issue #43 Phase 4-3: sftp-server サブシステム (libc.so.6 のみ依存、軽量)。
    # sshd_config の `Subsystem sftp /usr/lib/openssh/sftp-server` を有効化
    # するために必要。host の sftp(1) client から `get`/`put`/`ls` 等が動く。
    # host に無ければ apt-get download openssh-sftp-server (issue #308)。
    if [ -f /usr/lib/openssh/sftp-server ]; then
        mkdir -p "$SB/usr/lib/openssh"
        copy_with_deps /usr/lib/openssh/sftp-server
    elif command -v apt-get >/dev/null 2>&1; then
        SFTP_TMP=$(mktemp -d -t emulin-sftp.XXXXXX)
        ( cd "$SFTP_TMP" && apt-get download openssh-sftp-server >/dev/null 2>&1 \
          && for d in *.deb; do dpkg -x "$d" extract; done ) || true
        copy_copyrights_from_extract "$SFTP_TMP/extract"  # issue #63
        if [ -f "$SFTP_TMP/extract/usr/lib/openssh/sftp-server" ]; then
            mkdir -p "$SB/usr/lib/openssh"
            cp "$SFTP_TMP/extract/usr/lib/openssh/sftp-server" "$SB/usr/lib/openssh/sftp-server"
            copy_extract_bin_deps "$SFTP_TMP/extract/usr/lib/openssh/sftp-server"
        fi
        rm -rf "$SFTP_TMP"
    fi
    fi

    # NSS modules: glibc が getpwnam / getgrnam で dlopen する。
    #   passwd: files の経路で libnss_files.so.2 が必要。
    #   無いと getpwnam("root") が NULL を返し sshd が "invalid user" で reject。
    for lib in libnss_files.so.2 libnss_compat.so.2 libnss_dns.so.2; do
        src=/lib/x86_64-linux-gnu/$lib
        if [ -f "$src" ]; then
            real=$(readlink -f "$src")
            mkdir -p "$SB/lib/x86_64-linux-gnu"
            cp -L "$real" "$SB${real}" 2>/dev/null || true
            # canonical 名と versioned 名が違うときは symlink を追加
            if [ "$real" != "$src" ]; then
                ln -sf "$(basename "$real")" "$SB/lib/x86_64-linux-gnu/$lib" 2>/dev/null || true
            fi
        fi
    done

    # /etc/nsswitch.conf : passwd を files plugin で読ませる。host の
    #   設定は systemd 経由が default だが、emulin に systemd は無いので
    #   files だけにする。
    if [ ! -f "$SB/etc/nsswitch.conf" ]; then
        cat > "$SB/etc/nsswitch.conf" <<'NSSWEOF'
passwd:         files
group:          files
shadow:         files
hosts:          files dns
networks:       files
protocols:      files
services:       files
ethers:         files
rpc:            files
netgroup:       files
NSSWEOF
    fi

    # /etc/shells : sshd の allowed_user() が pw_shell が登録されているか
    #   check するため必須。これが無いと "User not allowed because shell
    #   does not exist" で auth deny される。
    if [ ! -f "$SB/etc/shells" ]; then
        cat > "$SB/etc/shells" <<'SHELLSEOF'
/bin/sh
/bin/bash
SHELLSEOF
    fi

    # /etc/passwd, /etc/group : root + sshd privsep user。
    #   既に sandbox にあれば上書きしない (user が独自 entry を入れている
    #   ことがあるため)。
    #   issue #45: root の login shell は /bin/bash (旧: /bin/sh)。/bin/sh
    #   経由だと bash が sh-compat mode で起動し readline 機能が制限される。
    if [ ! -f "$SB/etc/passwd" ]; then
        cat > "$SB/etc/passwd" <<'PASSWDEOF'
root:x:0:0:root:/root:/bin/bash
sshd:x:74:74:Privilege-separated SSH:/run/sshd:/usr/sbin/nologin
PASSWDEOF
    fi
    if [ ! -f "$SB/etc/group" ]; then
        cat > "$SB/etc/group" <<'GROUPEOF'
root:x:0:
sshd:x:74:
GROUPEOF
    fi

    # ホスト鍵 (ed25519 を生成。host の ssh-keygen を使う)。
    mkdir -p "$SB/etc/ssh"
    if [ ! -f "$SB/etc/ssh/ssh_host_ed25519_key" ]; then
        if command -v ssh-keygen >/dev/null 2>&1; then
            ssh-keygen -t ed25519 -N '' -q \
                -f "$SB/etc/ssh/ssh_host_ed25519_key" -C "emulin-sshd" \
                || echo "  warn: ssh-keygen 失敗 — host key を手動で配置してください"
        else
            echo "  warn: ssh-keygen が host に無い — host key を手動で配置してください"
        fi
    fi

    # sshd_config : Phase 1 MVP 用 minimal config (PAM 無し、publickey only、
    #   debug log on stderr)。
    if [ ! -f "$SB/etc/ssh/sshd_config" ]; then
        cat > "$SB/etc/ssh/sshd_config" <<'SSHDCONFEOF'
Port 2222
HostKey /etc/ssh/ssh_host_ed25519_key
PidFile /run/sshd.pid
ListenAddress 127.0.0.1
UsePAM no
PasswordAuthentication no
PubkeyAuthentication yes
PermitRootLogin yes
ChallengeResponseAuthentication no
KbdInteractiveAuthentication no
KerberosAuthentication no
GSSAPIAuthentication no
StrictModes no
PrintMotd no
PrintLastLog no
X11Forwarding no
LogLevel INFO
AuthorizedKeysFile /root/.ssh/authorized_keys
# issue #226: ~/.ssh/environment を session env に読み込む。emulin core が sshd
#   起動時に host から継承した env (EMULIN_INHERIT_ENV) をそこへ書き出す。
PermitUserEnvironment yes
Subsystem sftp /usr/lib/openssh/sftp-server
SSHDCONFEOF
    fi

    # 必要 dir : /root/.ssh, /run/sshd (PidFile), /var/empty (privsep chroot)。
    mkdir -p "$SB/root/.ssh" "$SB/run/sshd" "$SB/var/empty"
    chmod 700 "$SB/root/.ssh" 2>/dev/null || true

    # issue #45: bash の readline (Ctrl-C / Tab / 履歴 / 行編集) を有効化。
    #   sshd は login shell として `bash` を argv[0]=`-bash` で起動する。
    #   stdin が pipe (= 自動 test) でない限り interactive 判定が走り
    #   readline は通常 ON だが、念のため `.bash_profile` で `-i` を強制
    #   して、`.bashrc` で emacs mode + 履歴 key bind を明示する。
    if [ ! -f "$SB/root/.bash_profile" ]; then
        cat > "$SB/root/.bash_profile" <<'BPROFEOF'
# login shell — interactive bash を強制起動
[ -f /root/.bashrc ] && . /root/.bashrc
BPROFEOF
    fi
    if [ ! -f "$SB/root/.bashrc" ]; then
        cat > "$SB/root/.bashrc" <<'BRCEOF'
PS1='\u@\h:\w\$ '
set -o emacs
HISTSIZE=500
HISTFILE=~/.bash_history
BRCEOF
    fi
    if [ ! -f "$SB/root/.inputrc" ]; then
        cat > "$SB/root/.inputrc" <<'IRCEOF'
set editing-mode emacs
"\e[A": previous-history
"\e[B": next-history
"\e[C": forward-char
"\e[D": backward-char
"\e[H": beginning-of-line
"\e[F": end-of-line
TAB: complete
IRCEOF
    fi

    # issue #45: terminfo (`/usr/share/terminfo`) を bundle。bash readline が
    #   ESC sequence 解釈 + cursor control に必要。
    if [ -d /usr/share/terminfo ] && [ ! -d "$SB/usr/share/terminfo" ]; then
        cp -r /usr/share/terminfo "$SB/usr/share/" 2>/dev/null || true
    fi

    # INCLUDE_SSHD_AUTHORIZED_KEY で渡された pubkey を authorized_keys に append。
    if [ -n "${INCLUDE_SSHD_AUTHORIZED_KEY:-}" ] && [ -f "$INCLUDE_SSHD_AUTHORIZED_KEY" ]; then
        cat "$INCLUDE_SSHD_AUTHORIZED_KEY" >> "$SB/root/.ssh/authorized_keys"
        chmod 600 "$SB/root/.ssh/authorized_keys" 2>/dev/null || true
        echo "  authorized_keys に $INCLUDE_SSHD_AUTHORIZED_KEY を追加"
    fi

    # /dev nodes (sshd は /dev/urandom / /dev/null を open)
    mkdir -p "$SB/dev"
    for d in null urandom zero tty random ptmx; do
        [ -e "$SB/dev/$d" ] || touch "$SB/dev/$d"
        chmod 666 "$SB/dev/$d" 2>/dev/null || true
    done

    if [ -f "$SB/usr/sbin/sshd" ]; then
        echo "  sshd ($(du -sh "$SB/usr/sbin/sshd" 2>/dev/null | awk '{print $1}'))"
    fi
fi

# issue #13: INCLUDE_PERL=1 で perl 5 を sandbox に同梱する。
# 容量: +50 MB (perl 本体 + core .pm + arch dependent .so)。
# 動作確認: emulin /usr/bin/perl -e 'print "hello\n"' で "hello" が出れば OK。
# 用途: git-svn / git-add -i / git-send-email 等の git 内部 script で使う。
# 取得: host に perl があれば直接、無ければ apt-get download。
if [ "${INCLUDE_PERL:-0}" = "1" ]; then
    echo "[stage] perl: perl 5 interpreter + core modules を bundle..."
    PERL_TMP=$(mktemp -d -t emulin-perl.XXXXXX)
    trap 'rm -rf "$PERL_TMP" 2>/dev/null || true' EXIT
    # issue #361: dpkg DB があれば perl を package-managed で導入する。apt が依存
    #   (perl-base / perl-modules-X.YY / libperl 等) を自動解決するので version 指定も不要。
    #   無ければ従来の host copy / apt download + @INC 手コピー。
    if deb_bundle_closure "$SB" "$PERL_TMP/debs" perl; then
        echo "  perl を package-managed で導入 ($(ls "$PERL_TMP/debs"/*.deb 2>/dev/null | wc -l) .deb を dpkg DB 登録)"
    else
    if [ -x /usr/bin/perl ]; then
        PERL_BIN=/usr/bin/perl
        # issue #63: host 由来の perl package の copyright
        for p in perl perl-base perl-modules-5.38; do copy_host_copyright "$p"; done
        # 動的 @INC を host perl から取得 (5.38 等の version dir 名)。
        # $^V は v5.38.2 形式、$Config{version} は 5.38.2 形式、ディレクトリ名は
        # major.minor (5.38) で /usr/share/perl/<ver>/ にある。
        # /usr/share/perl/5.* を glob して dir 名から抽出するのが一番堅い。
        PERL_VER=$(ls -d /usr/share/perl/5.* 2>/dev/null | head -1 | xargs -r basename)
        PERL_LIB_ARCH=/usr/lib/x86_64-linux-gnu/perl
        PERL_SHARE=/usr/share/perl
        PERL_BASE=/usr/lib/x86_64-linux-gnu/perl-base
        PERL_SHARE5=/usr/share/perl5
    elif command -v apt-get >/dev/null 2>&1; then
        echo "  perl を apt-get download で取得中..."
        ( cd "$PERL_TMP" && apt-get download perl perl-base perl-modules-5.38 2>&1 \
          | grep -v Get: \
          && for d in *.deb; do dpkg -x "$d" extract; done )
        copy_copyrights_from_extract "$PERL_TMP/extract"  # issue #63
        PERL_BIN="$PERL_TMP/extract/usr/bin/perl"
        PERL_VER=5.38
        PERL_LIB_ARCH="$PERL_TMP/extract/usr/lib/x86_64-linux-gnu/perl"
        PERL_SHARE="$PERL_TMP/extract/usr/share/perl"
        PERL_BASE="$PERL_TMP/extract/usr/lib/x86_64-linux-gnu/perl-base"
        PERL_SHARE5="$PERL_TMP/extract/usr/share/perl5"
    else
        echo "  warn: perl not on host and apt-get unavailable — skipping perl"
        PERL_BIN=""
    fi
    if [ -n "$PERL_BIN" ] && [ -x "$PERL_BIN" ]; then
        cp "$PERL_BIN" "$SB/usr/bin/perl"
        # 依存 lib (libcrypt.so.1 / libm.so.6 / libc.so.6) を ldd で
        while IFS= read -r line; do
            if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then
                lib_path="${BASH_REMATCH[1]}"
                real_lib=$(readlink -f "$lib_path")
                if [ -f "$real_lib" ]; then
                    copy_if "$real_lib" "$SB${real_lib}"
                    if [ "$real_lib" != "$lib_path" ] && [ -e "$SB${real_lib}" ]; then
                        local_lp_real=$(readlink -f "$(dirname "$lib_path")")"/$(basename "$lib_path")"
                        if [ "$local_lp_real" != "$real_lib" ]; then
                            mkdir -p "$(dirname "$SB$local_lp_real")"
                            ln -sf "$(basename "$real_lib")" "$SB$local_lp_real"
                        fi
                    fi
                fi
            fi
        done < <(ldd "$PERL_BIN" 2>/dev/null)
        # perl @INC 配下の core .pm + arch-dependent .so を 4 dir copy
        # /usr/lib/x86_64-linux-gnu/perl/<ver>   — arch dependent (.so で書かれた XS)
        # /usr/share/perl/<ver>                  — core .pm (Pure-Perl)
        # /usr/lib/x86_64-linux-gnu/perl-base    — base 必須 .pm (perl-base deb)
        # /usr/share/perl5                       — site 共通 .pm
        if [ -d "$PERL_LIB_ARCH/$PERL_VER" ]; then
            mkdir -p "$SB/usr/lib/x86_64-linux-gnu/perl"
            cp -rL "$PERL_LIB_ARCH/$PERL_VER" "$SB/usr/lib/x86_64-linux-gnu/perl/$PERL_VER"
        fi
        if [ -d "$PERL_SHARE/$PERL_VER" ]; then
            mkdir -p "$SB/usr/share/perl"
            cp -rL "$PERL_SHARE/$PERL_VER" "$SB/usr/share/perl/$PERL_VER"
        fi
        if [ -d "$PERL_BASE" ]; then
            cp -rL "$PERL_BASE" "$SB/usr/lib/x86_64-linux-gnu/perl-base"
        fi
        if [ -d "$PERL_SHARE5" ]; then
            cp -rL "$PERL_SHARE5" "$SB/usr/share/perl5"
        fi
        # /dev nodes (perl が /dev/null を seed source として open する)
        mkdir -p "$SB/dev"
        for d in null urandom zero tty; do
            [ -e "$SB/dev/$d" ] || touch "$SB/dev/$d"
            chmod 666 "$SB/dev/$d" 2>/dev/null || true
        done
        # issue #7: Git for Windows /usr/bin/perl5.36.1 互換の version-suffixed
        # symlink。host の perl は version 違い (5.38 等) だが、Git Bash
        # script で perl5.36.1 を expect する code があれば fallback で動かす。
        ln -sf perl "$SB/usr/bin/perl5.36.1"
        echo "  perl $PERL_VER ($(du -sh "$SB/usr/lib/x86_64-linux-gnu/perl/$PERL_VER" "$SB/usr/share/perl/$PERL_VER" 2>/dev/null | tail -1 | awk '{print $1}') etc.)"
    fi
    fi
    # 配置方法に依らない後処理 (perl がある場合): /dev + version-suffixed symlink (issue #7)。
    if [ -x "$SB/usr/bin/perl" ]; then
        mkdir -p "$SB/dev"
        for d in null urandom zero tty; do
            [ -e "$SB/dev/$d" ] || touch "$SB/dev/$d"
            chmod 666 "$SB/dev/$d" 2>/dev/null || true
        done
        [ -e "$SB/usr/bin/perl5.36.1" ] || ln -sf perl "$SB/usr/bin/perl5.36.1"
    fi
fi

# issue #59 (0.4.0 release): INCLUDE_PYTHON=1 で python3 + stdlib を sandbox
# に同梱する。Git for Windows には含まれないが、Windows native 開発者の
# script 用途で需要が高い。
# 容量: +60 MB (python3 本体 + stdlib + .so modules)
# 取得: host に python3 があれば直接コピー、無ければ apt-get download。
# 動作確認: emulin /usr/bin/python3 -c 'print("OK")' で "OK" 出力。
if [ "${INCLUDE_PYTHON:-0}" = "1" ]; then
    echo "[stage] python: python3 + stdlib を bundle..."
    PYTHON_TMP=$(mktemp -d -t emulin-python.XXXXXX)
    trap 'rm -rf "$PYTHON_TMP" 2>/dev/null || true' EXIT
    # issue #361: dpkg DB があれば python3 を package-managed で導入する (apt が
    #   python3.X-minimal / libpython3.X-stdlib 等を自動解決)。無ければ従来の host copy / apt download。
    PY_PM=0
    if deb_bundle_closure "$SB" "$PYTHON_TMP/debs" python3; then
        PY_PM=1
        PY_BIN=$(ls "$SB/usr/bin/python3."[0-9]* 2>/dev/null | head -1)
        [ -n "${PY_BIN:-}" ] && PY_VER=$(basename "$PY_BIN" | sed 's/^python//')
        echo "  python3 を package-managed で導入 (python${PY_VER:-?}, $(ls "$PYTHON_TMP/debs"/*.deb 2>/dev/null | wc -l) .deb を dpkg DB 登録)"
    else
    # host の python3 path 解決 (symlink → real)
    if [ -x /usr/bin/python3 ]; then
        PY_REAL=$(readlink -f /usr/bin/python3)
        PY_VER=$(basename "$PY_REAL" | sed 's/^python//')  # e.g., 3.12
        PY_LIB_DIR=/usr/lib/python$PY_VER
        PY_BIN="$PY_REAL"
        # issue #63: host 由来 python package の copyright (PSF license)
        for p in python3 python3-minimal "python$PY_VER" "python$PY_VER-minimal" \
                 libpython3-stdlib "libpython$PY_VER-stdlib" "libpython$PY_VER-minimal"; do
            copy_host_copyright "$p"
        done
    elif command -v apt-get >/dev/null 2>&1; then
        echo "  python3 を apt-get download で取得中..."
        # Debian/Ubuntu の python3-minimal は /usr/bin/python3 → python3.X の symlink
        # のみで、interpreter 実体・stdlib は versioned package (python3.X-minimal /
        # libpython3.X-minimal / libpython3.X-stdlib) に入る。unversioned package だけ
        # では実体が取れず空振りするので、version を動的解決して versioned package を
        # download する (issue #308、clean host / CI 対応)。python3.X 本体は libpython を
        # static link するので別途 libpython.so の配慮は不要。
        PYV=$(apt-cache depends python3-minimal 2>/dev/null \
              | grep -oE 'python3\.[0-9]+-minimal' | head -1 | sed 's/-minimal$//')
        [ -z "$PYV" ] && PYV=$(apt-cache depends python3 2>/dev/null \
              | grep -oE 'python3\.[0-9]+' | head -1)
        if [ -n "$PYV" ]; then
            ( cd "$PYTHON_TMP" && apt-get download "$PYV-minimal" "lib$PYV-minimal" "lib$PYV-stdlib" >/dev/null 2>&1 \
              && for d in *.deb; do dpkg -x "$d" extract; done ) || true
            copy_copyrights_from_extract "$PYTHON_TMP/extract"  # issue #63
            PY_BIN=$(ls "$PYTHON_TMP/extract/usr/bin/python3."[0-9]* 2>/dev/null | head -1)
            if [ -n "$PY_BIN" ]; then
                PY_VER=$(basename "$PY_BIN" | sed 's/^python//')
                PY_LIB_DIR="$PYTHON_TMP/extract/usr/lib/python$PY_VER"
            fi
        fi
    else
        echo "  warn: python3 not on host and apt-get unavailable — skipping python"
        PY_BIN=""
    fi
    if [ -n "${PY_BIN:-}" ] && [ -x "$PY_BIN" ]; then
        # 本体: /usr/bin/python3.X + /usr/bin/python3 symlink + /usr/bin/python symlink
        cp "$PY_BIN" "$SB/usr/bin/python$PY_VER"
        ln -sf "python$PY_VER" "$SB/usr/bin/python3"
        ln -sf "python$PY_VER" "$SB/usr/bin/python"  # Git Bash 等で python expect 用
        # 依存 lib (libm / libz / libexpat / libc) を ldd で copy
        while IFS= read -r line; do
            if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then
                lib_path="${BASH_REMATCH[1]}"
                real_lib=$(readlink -f "$lib_path")
                if [ -f "$real_lib" ]; then
                    copy_if "$real_lib" "$SB${real_lib}"
                    if [ "$real_lib" != "$lib_path" ] && [ -e "$SB${real_lib}" ]; then
                        local_lp_real=$(readlink -f "$(dirname "$lib_path")")"/$(basename "$lib_path")"
                        if [ "$local_lp_real" != "$real_lib" ]; then
                            mkdir -p "$(dirname "$SB$local_lp_real")"
                            ln -sf "$(basename "$real_lib")" "$SB$local_lp_real"
                        fi
                    fi
                fi
            fi
        done < <(ldd "$PY_BIN" 2>/dev/null)
        # stdlib (/usr/lib/python3.X 配下): ~55 MB。.py + lib-dynload/*.so
        if [ -d "$PY_LIB_DIR" ]; then
            mkdir -p "$SB/usr/lib/python$PY_VER"
            cp -rL "$PY_LIB_DIR"/. "$SB/usr/lib/python$PY_VER/" 2>/dev/null || true
            # lib-dynload の .so が ldd 経由でない依存 (libssl 等) を持つことが
            # あるので、追加 lib も copy しておく。
            for so in "$SB/usr/lib/python$PY_VER/lib-dynload/"*.so; do
                [ -f "$so" ] || continue
                while IFS= read -r line; do
                    if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then
                        lib_path="${BASH_REMATCH[1]}"
                        real_lib=$(readlink -f "$lib_path")
                        if [ -f "$real_lib" ]; then
                            copy_if "$real_lib" "$SB${real_lib}"
                            if [ "$real_lib" != "$lib_path" ] && [ -e "$SB${real_lib}" ]; then
                                local_lp_real=$(readlink -f "$(dirname "$lib_path")")"/$(basename "$lib_path")"
                                if [ "$local_lp_real" != "$real_lib" ]; then
                                    mkdir -p "$(dirname "$SB$local_lp_real")"
                                    ln -sf "$(basename "$real_lib")" "$SB$local_lp_real"
                                fi
                            fi
                        fi
                    fi
                done < <(ldd "$so" 2>/dev/null)
            done
        fi
        # /dev nodes (python が urandom を seed source として open)
        mkdir -p "$SB/dev"
        for d in null urandom zero tty; do
            [ -e "$SB/dev/$d" ] || touch "$SB/dev/$d"
            chmod 666 "$SB/dev/$d" 2>/dev/null || true
        done
        echo "  python $PY_VER ($(du -sh "$SB/usr/lib/python$PY_VER" 2>/dev/null | awk '{print $1}'))"
        # pip 同梱: pip 本体は stdlib に無く ensurepip にも bundled wheel が無い
        #   ため、build host で pip wheel を取得して dist-packages に展開し、
        #   /usr/bin/pip{,3,3.X} ラッパーを置く。これで sandbox 上で
        #   `pip install` が動く (/etc/pip.conf の cert= と合わせて --cert 不要)。
        #   install 先 = /usr/local/lib/pythonX.Y/dist-packages (Debian python の
        #   getsitepackages 先頭 = site が sys.path に追加 + pip 既定 install 先)。
        # pip は optional (取れなければ下の else で warn skip)。set -eu 下では
        # bare な PIP_WHL=$(...) が非ゼロ終了で全体を abort してしまう (build host
        # の python3 に pip module が無い minimal Debian 等)。`|| true` で意図通り
        # graceful skip にする (issue #308)。
        PIP_WHL=$( cd "$PYTHON_TMP" && python3 -m pip download --no-deps pip >/dev/null 2>&1 && ls pip-*-py3-none-any.whl 2>/dev/null | head -1 ) || true
        if [ -n "${PIP_WHL:-}" ] && [ -f "$PYTHON_TMP/$PIP_WHL" ]; then
            PIP_SITE="$SB/usr/local/lib/python$PY_VER/dist-packages"
            mkdir -p "$PIP_SITE"
            ( cd "$PIP_SITE" && unzip -o -q "$PYTHON_TMP/$PIP_WHL" )
            for s in pip pip3 "pip$PY_VER"; do
                printf '#!/usr/bin/python%s\nimport sys\nfrom pip._internal.cli.main import main\nsys.exit(main())\n' "$PY_VER" > "$SB/usr/bin/$s"
                chmod +x "$SB/usr/bin/$s"
            done
            echo "  pip ($PIP_WHL) → dist-packages + /usr/bin/pip{,3,$PY_VER}"
        else
            echo "  warn: pip wheel 取得失敗 — pip 同梱 skip (build host で 'pip download pip' を確認)"
        fi
    fi
    fi
    # issue #361: package-managed 経路の共通後処理。deb は /usr/bin/python3 と python3.X を
    #   提供するが、python (python-is-python3 相当) symlink・/dev・pip は別途必要なので補う
    #   (従来 copy 経路は上の body 内で実施済みなので PY_PM=1 のときだけ実行)。
    if [ "$PY_PM" = 1 ] && [ -n "${PY_VER:-}" ] && [ -e "$SB/usr/bin/python$PY_VER" ]; then
        [ -e "$SB/usr/bin/python3" ] || ln -sf "python$PY_VER" "$SB/usr/bin/python3"
        [ -e "$SB/usr/bin/python" ]  || ln -sf python3 "$SB/usr/bin/python"
        mkdir -p "$SB/dev"
        for d in null urandom zero tty; do
            [ -e "$SB/dev/$d" ] || touch "$SB/dev/$d"
            chmod 666 "$SB/dev/$d" 2>/dev/null || true
        done
        # pip 同梱 (build host の pip で wheel 取得 → dist-packages 展開 + ラッパー、issue #308)。
        PIP_WHL=$( cd "$PYTHON_TMP" && python3 -m pip download --no-deps pip >/dev/null 2>&1 && ls pip-*-py3-none-any.whl 2>/dev/null | head -1 ) || true
        if [ -n "${PIP_WHL:-}" ] && [ -f "$PYTHON_TMP/$PIP_WHL" ]; then
            PIP_SITE="$SB/usr/local/lib/python$PY_VER/dist-packages"
            mkdir -p "$PIP_SITE"
            ( cd "$PIP_SITE" && unzip -o -q "$PYTHON_TMP/$PIP_WHL" )
            for s in pip pip3 "pip$PY_VER"; do
                printf '#!/usr/bin/python%s\nimport sys\nfrom pip._internal.cli.main import main\nsys.exit(main())\n' "$PY_VER" > "$SB/usr/bin/$s"
                chmod +x "$SB/usr/bin/$s"
            done
            echo "  pip ($PIP_WHL) → dist-packages + /usr/bin/pip{,3,$PY_VER}"
        else
            echo "  warn: pip wheel 取得失敗 — pip 同梱 skip"
        fi
        echo "  python $PY_VER (package-managed)"
    fi
fi

# issue #7: gencat (libc-dev-bin 同梱の glibc utility)。message catalog
# 生成 tool で、Git for Windows の /usr/bin/gencat と互換性を取る。
# host に gencat があれば直接 copy、無ければ apt-get download libc-dev-bin。
if [ -e "/usr/bin/gencat" ] || command -v apt-get >/dev/null 2>&1; then
    GENCAT_TMP=$(mktemp -d -t emulin-gencat.XXXXXX)
    trap 'rm -rf "$GENCAT_TMP" 2>/dev/null || true' EXIT
    if [ -x /usr/bin/gencat ]; then
        GENCAT_SRC=/usr/bin/gencat
        copy_host_copyright "libc-dev-bin"  # issue #63
    else
        ( cd "$GENCAT_TMP" && apt-get download libc-dev-bin >/dev/null 2>&1 \
          && for d in *.deb; do dpkg -x "$d" extract; done ) || true
        copy_copyrights_from_extract "$GENCAT_TMP/extract"
        GENCAT_SRC="$GENCAT_TMP/extract/usr/bin/gencat"
    fi
    if [ -f "$GENCAT_SRC" ]; then
        cp "$GENCAT_SRC" "$SB/usr/bin/gencat"
        while IFS= read -r line; do
            if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then
                lib_path="${BASH_REMATCH[1]}"
                real_lib=$(readlink -f "$lib_path")
                if [ -f "$real_lib" ]; then
                    copy_if "$real_lib" "$SB${real_lib}"
                    if [ "$real_lib" != "$lib_path" ] && [ -e "$SB${real_lib}" ]; then
                        local_lp_real=$(readlink -f "$(dirname "$lib_path")")"/$(basename "$lib_path")"
                        if [ "$local_lp_real" != "$real_lib" ]; then
                            mkdir -p "$(dirname "$SB$local_lp_real")"
                            ln -sf "$(basename "$real_lib")" "$SB$local_lp_real"
                        fi
                    fi
                fi
            fi
        done < <(ldd "$GENCAT_SRC" 2>/dev/null)
        [ ! -e "$SB/bin/gencat" ] && ln -sf "../usr/bin/gencat" "$SB/bin/gencat"
    fi
fi

echo "[done] sandbox at $SB (level=full)"
