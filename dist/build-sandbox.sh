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
