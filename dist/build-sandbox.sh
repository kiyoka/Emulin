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

# 2d2. Phase 28-3k: curated multi-root bundle for HTTPS (~7 certs)
# /etc/ssl/certs (146+ 個) を全 load すると 80s 以上で server idle timeout。
# 主要 root だけを 1 file に concat した bundle を作って sslCAInfo に指定すると
# 14s 前後で HTTPS 完了。github 単独 (Sectigo Root E46) と比べて example.com /
# cloudflare / google / iana / raw.githubusercontent も同時に動作可。
EMULIN_BUNDLE=$SB/etc/ssl/certs/emulin-roots.pem
> "$EMULIN_BUNDLE"
for cert_name in \
    Sectigo_Public_Server_Authentication_Root_E46 \
    USERTrust_RSA_Certification_Authority \
    SSL.com_TLS_ECC_Root_CA_2022 \
    GTS_Root_R4 \
    ISRG_Root_X1 \
    DigiCert_Global_Root_CA \
    DigiCert_Global_Root_G2 ; do
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

# GNU coreutils + bash + 3 大ネット系 (git/curl/wget) を配置 (依存 .so 自動解決)。
# 各 cmd がどこにあっても探す (Debian は /bin と /usr/bin に分散)。
copy_cmd_with_deps() {
    local cmd=$1
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
            return 0
        fi
    done
    warn_once "/usr/bin/$cmd or /bin/$cmd"
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
for cmd in \
    base32 basenc cksum comm csplit dir dircolors fmt install \
    join nice nohup numfmt pathchk pinky pr printenv ptx runcon \
    sha224sum sha384sum split stdbuf sum tsort users vdir \
    column diff3 sdiff gawk lsattr getconf locale ; do
    copy_cmd_with_deps "$cmd"
done
# gawk-5.0.0 は versioned alias。host には大抵 gawk のみなので symlink で代替。
if [ -e "$SB/usr/bin/gawk" ] && [ ! -e "$SB/usr/bin/gawk-5.0.0" ]; then
    ln -sf gawk "$SB/usr/bin/gawk-5.0.0"
fi
# getfacl / setfacl (acl pkg) / psl (psl pkg) は host に無いことが多いので
# apt-get download で取得を試みる。取れなければ skip (xattr 系は emulin が
# ENOSYS stub なので機能限定だが smoke test は通る)。
if command -v apt-get >/dev/null 2>&1; then
    C9_TMP=$(mktemp -d -t emulin-c9.XXXXXX)
    trap 'rm -rf "$C9_TMP" 2>/dev/null || true' EXIT
    ( cd "$C9_TMP" && apt-get download acl psl >/dev/null 2>&1 \
      && for d in *.deb; do dpkg -x "$d" extract 2>/dev/null; done ) || true
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
    else
        ( cd "$D2U_TMP" && apt-get download dos2unix >/dev/null 2>&1 \
          && for d in *.deb; do dpkg -x "$d" extract; done ) || true
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
    if [ -x /usr/bin/emacs-nox ]; then
        # host に emacs-nox があれば直接使う (.eln は host 由来でも互換)
        EMACS_SRC=/
    elif command -v apt-get >/dev/null 2>&1; then
        echo "  emacs-nox を apt-get download で取得中..."
        ( cd "$EMACS_TMP" && apt-get download emacs-nox emacs-common emacs-bin-common >/dev/null 2>&1 \
          && for d in *.deb; do dpkg -x "$d" extract; done )
        EMACS_SRC="$EMACS_TMP/extract"
    else
        echo "  warn: emacs-nox not on host and apt-get unavailable — skipping emacs"
        EMACS_SRC=""
    fi
    if [ -n "$EMACS_SRC" ] && [ -x "$EMACS_SRC/usr/bin/emacs-nox" ]; then
        copy_with_deps "$EMACS_SRC/usr/bin/emacs-nox"
        # binary が EMACS_SRC からコピーされていれば $SB/$EMACS_SRC/usr/bin/emacs-nox
        # に置かれてしまうので、$SB/usr/bin/emacs-nox に正規化
        if [ "$EMACS_SRC" != "/" ] && [ -f "$SB$EMACS_SRC/usr/bin/emacs-nox" ]; then
            mkdir -p "$SB/usr/bin"
            mv "$SB$EMACS_SRC/usr/bin/emacs-nox" "$SB/usr/bin/emacs-nox"
            rm -rf "$SB$EMACS_SRC" 2>/dev/null || true
        fi
        # lisp + native-comp + etc をコピー
        # cp -r: dst が存在しないと src 名が dst にリネームされる
        # (例: cp -r /a/emacs /b/  → dst /b 不存在で /b/{29.3,...} になり
        # /b/emacs/29.3/... にならない)。先に mkdir で確実に作る。
        mkdir -p "$SB/usr/share" "$SB/usr/lib" "$SB/usr/libexec"
        cp -r "$EMACS_SRC/usr/share/emacs"   "$SB/usr/share/"   2>/dev/null || true
        cp -r "$EMACS_SRC/usr/lib/emacs"     "$SB/usr/lib/"     2>/dev/null || true
        # libexec: emacs.pdmp (preloaded dump) と emacsclient 等の helper。
        # pdmp が無いと emacs が遅い再初期化 path に落ち、warning も出る。
        cp -r "$EMACS_SRC/usr/libexec/emacs" "$SB/usr/libexec/" 2>/dev/null || true
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
        # /dev/null 等の device entry (emacs --batch が stdin redirect で必要)
        mkdir -p "$SB/dev"
        for d in null urandom zero tty; do
            [ -e "$SB/dev/$d" ] || touch "$SB/dev/$d"
            chmod 666 "$SB/dev/$d" 2>/dev/null || true
        done
        echo "  emacs-nox + lisp ($(du -sh "$SB/usr/share/emacs" 2>/dev/null | awk '{print $1}'))"
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
    # host の vim.basic があれば直接、無ければ apt-get download
    if [ -x /usr/bin/vim.basic ]; then
        VIM_BIN=/usr/bin/vim.basic
        VIM_RUNTIME_SRC=/usr/share/vim
    elif command -v apt-get >/dev/null 2>&1; then
        echo "  vim を apt-get download で取得中..."
        ( cd "$VIM_TMP" && apt-get download vim vim-common vim-runtime >/dev/null 2>&1 \
          && for d in *.deb; do dpkg -x "$d" extract; done )
        VIM_BIN="$VIM_TMP/extract/usr/bin/vim.basic"
        VIM_RUNTIME_SRC="$VIM_TMP/extract/usr/share/vim"
    else
        echo "  warn: vim not on host and apt-get unavailable — skipping vim"
        VIM_BIN=""
    fi
    if [ -n "$VIM_BIN" ] && [ -x "$VIM_BIN" ]; then
        cp "$VIM_BIN" "$SB/usr/bin/vim"
        # vim が必要とする lib を ldd で取得
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
        # vim runtime files (syntax/colors/indent/help)
        if [ -d "$VIM_RUNTIME_SRC" ]; then
            cp -r "$VIM_RUNTIME_SRC" "$SB/usr/share/" 2>/dev/null || true
        fi
        # /bin/vi /bin/vim symlink
        ln -sf ../usr/bin/vim "$SB/bin/vim"
        ln -sf ../usr/bin/vim "$SB/bin/vi"
        ln -sf vim "$SB/usr/bin/vi"
        # issue #7: Git for Windows /usr/bin の vim alias 4 個。
        #   view     — vim を read-only mode (-R) で起動
        #   vimdiff  — vim を diff mode (-d) で起動
        #   rview    — view を restricted mode (-Z) で起動
        #   rvim     — vim を restricted mode (-Z) で起動
        # 全て host vim と同じく symlink で動作 (vim 本体が argv[0] basename を
        # 見て mode を切替)。
        for alias_name in view vimdiff rview rvim ; do
            ln -sf vim "$SB/usr/bin/$alias_name"
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
    if [ -x /usr/bin/tig ]; then
        TIG_BIN=/usr/bin/tig
        TIG_ETC=/etc/tigrc
    elif command -v apt-get >/dev/null 2>&1; then
        echo "  tig を apt-get download で取得中..."
        ( cd "$TIG_TMP" && apt-get download tig libpcre2-posix3 >/dev/null 2>&1 \
          && for d in *.deb; do dpkg -x "$d" extract; done )
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

# issue #9: INCLUDE_SSH=1 で openssh client tool 群を sandbox に同梱する。
# 対象: ssh / scp / sftp / ssh-add / ssh-agent / ssh-keygen / ssh-keyscan
# sshd (server) は対象外。
# 容量: +5 MB (binary 群) + ~3 MB (libssl/libkrb5/libgssapi 等の依存)
# 動作確認: emulin /usr/bin/ssh -V で OpenSSH バージョン文字列が表示されれば
#   smoke 合格。実際の network 接続は emulin の AF_UNIX 未対応 (ssh-agent
#   通信路) / AF_INET6 未対応 (server fallback) で別途確認が必要。
if [ "${INCLUDE_SSH:-0}" = "1" ]; then
    echo "[stage] ssh: openssh client tool 群を bundle..."
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
    # sshd 本体 (/usr/sbin にあるので copy_cmd_with_deps が使えない → 直接 copy)
    if [ -f /usr/sbin/sshd ]; then
        copy_with_deps /usr/sbin/sshd
    else
        echo "  warn: /usr/sbin/sshd が host に無い — INCLUDE_SSHD skip"
    fi
    # issue #43 Phase 4-3: sftp-server サブシステム (libc.so.6 のみ依存、軽量)。
    # sshd_config の `Subsystem sftp /usr/lib/openssh/sftp-server` を有効化
    # するために必要。host の sftp(1) client から `get`/`put`/`ls` 等が動く。
    if [ -f /usr/lib/openssh/sftp-server ]; then
        mkdir -p "$SB/usr/lib/openssh"
        copy_with_deps /usr/lib/openssh/sftp-server
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
    if [ -x /usr/bin/perl ]; then
        PERL_BIN=/usr/bin/perl
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
        echo "  perl $PERL_VER ($(du -sh "$SB/usr/lib/x86_64-linux-gnu/perl/$PERL_VER" "$SB/usr/share/perl/$PERL_VER" 2>/dev/null | tail -1 | awk '{print $1}') etc.)"
    fi
fi

echo "[done] sandbox at $SB (level=full)"
