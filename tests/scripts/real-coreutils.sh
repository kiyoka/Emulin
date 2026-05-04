#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/real-coreutils.sh
#
#  Phase 27: 実機の GNU coreutils (動的リンク + libc + ...) を Emulin 上で
#  動かす回帰。ls / cat / wc / echo / true / false / dirname / basename /
#  uname の 9 種類を host から sandbox にコピーして起動する。
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (host 不在等)
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
TIMEOUT=120  # wget HTTPS で TLS handshake + 読み込みに ~50 sec かかる

if ! command -v java >/dev/null 2>&1; then echo "SKIP real-coreutils : java not found"; exit 2; fi
if [ ! -x /bin/ls ]; then echo "SKIP real-coreutils : /bin/ls not found"; exit 2; fi
if [ ! -f /lib64/ld-linux-x86-64.so.2 ]; then echo "SKIP real-coreutils : ld-linux not found"; exit 2; fi
if [ ! -f /lib/x86_64-linux-gnu/libc.so.6 ]; then echo "SKIP real-coreutils : libc.so.6 not found"; exit 2; fi

CLASSES=$PROJECT/target/classes
if [ ! -f "$CLASSES/emulin/Emulin.class" ]; then
    echo "SKIP real-coreutils : Emulin not built"
    exit 2
fi

CPFILE=$PROJECT/target/cp.txt
if [ ! -f "$CPFILE" ]; then
    ( cd "$PROJECT" && mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt ) >/dev/null 2>&1
fi
CP="$CLASSES:$(cat "$CPFILE" 2>/dev/null || echo '')"

SANDBOX=${SANDBOX_DIR:-$(mktemp -d -t emulin-coreutils.XXXXXX)}
trap 'rm -rf "$SANDBOX" 2>/dev/null || true' EXIT

mkdir -p "$SANDBOX"/{bin,etc,lib,lib64,tmp,usr/bin}
# 必須バイナリ
for b in /bin/ls /bin/cat /bin/echo /bin/true /bin/false /bin/dirname /bin/basename /bin/uname \
         /bin/grep /bin/sed /bin/sort /bin/date /bin/mkdir /bin/rm /bin/rmdir /bin/touch /bin/cp /bin/mv \
         /bin/bash; do
    [ -x "$b" ] && cp "$b" "$SANDBOX/bin/"
done
for b in /usr/bin/wc /usr/bin/head /usr/bin/tail /usr/bin/cut /usr/bin/tr /usr/bin/od \
         /usr/bin/printf /usr/bin/awk /usr/bin/expr /usr/bin/find /usr/bin/diff /usr/bin/yes /usr/bin/tee \
         /usr/bin/make /usr/bin/file /usr/bin/git /usr/bin/curl; do
    [ -x "$b" ] && cp "$b" "$SANDBOX/usr/bin/"
done

# curl は依存ライブラリが多い (TLS / Kerberos / brotli 等) ので ldd で
# 一括コピー。get_uniq_no のハッシュ衝突修正 (Phase 27 step 10) を確認する。
if [ -x /usr/bin/curl ]; then
    ldd /usr/bin/curl 2>/dev/null | awk '/=>/ {print $3}' | while read f; do
        [ -f "$f" ] && cp -L "$f" "$SANDBOX/lib/" 2>/dev/null
    done
fi

# /usr/bin/file 用の magic ファイル
mkdir -p "$SANDBOX/usr/share/misc"
[ -f /usr/share/misc/magic ] && ln -sf /usr/share/misc/magic "$SANDBOX/usr/share/misc/magic"
[ -f /usr/share/misc/magic.mgc ] && ln -sf /usr/share/misc/magic.mgc "$SANDBOX/usr/share/misc/magic.mgc"

# /etc/gitconfig が存在しないと git は EPERM で落ちるので空ファイル
: > "$SANDBOX/etc/gitconfig"

# 共有ライブラリ
cp /lib64/ld-linux-x86-64.so.2            "$SANDBOX/lib64/"
cp /lib/x86_64-linux-gnu/libc.so.6        "$SANDBOX/lib/"
for lib in libselinux.so.1 libpcre2-8.so.0 libacl.so.1 libcrypto.so.3 libsigsegv.so.2 \
           libgmp.so.10 libmpfr.so.6 libm.so.6 libreadline.so.8 libtinfo.so.6 \
           libz.so.1 libmagic.so.1 libbz2.so.1.0 liblzma.so.5 libzstd.so.1; do
    [ -f "/lib/x86_64-linux-gnu/$lib" ] && cp "/lib/x86_64-linux-gnu/$lib" "$SANDBOX/lib/"
done
: > "$SANDBOX/etc/emulin.cnf"

# サンプル入力ファイル (4 行)
printf 'hello world from cat\none\ntwo\nthree\n' > "$SANDBOX/tmp/sample.txt"

PASS=0
FAIL=0
declare -a FAILED=()

run_case() {
    local name=$1 pat=$2
    shift 2
    local act
    act=$(cd "$SANDBOX" && timeout $TIMEOUT java -XX:-UsePerfData -cp "$CP" emulin.Emulin "$SANDBOX" "$@" 2>/dev/null)
    if printf '%s' "$act" | grep -F -q -- "$pat"; then
        printf 'PASS    real-coreutils-%s\n' "$name"
        PASS=$((PASS+1))
    else
        printf 'FAIL    real-coreutils-%s\n' "$name"
        FAIL=$((FAIL+1)); FAILED+=("$name")
        if [ "${VERBOSE:-0}" = "1" ]; then
            echo "  --- expected pattern (grep -F) ---"
            echo "  | $pat"
            echo "  --- actual ---"
            printf '%s\n' "$act" | sed 's/^/  | /' | head -10
        fi
    fi
}

run_case_exit() {
    local name=$1 expected_exit=$2
    shift 2
    ( cd "$SANDBOX" && timeout $TIMEOUT java -XX:-UsePerfData -cp "$CP" emulin.Emulin "$SANDBOX" "$@" >/dev/null 2>&1 )
    local rc=$?
    if [ "$rc" = "$expected_exit" ]; then
        printf 'PASS    real-coreutils-%s\n' "$name"
        PASS=$((PASS+1))
    else
        printf 'FAIL    real-coreutils-%s (exit: expected=%s actual=%s)\n' "$name" "$expected_exit" "$rc"
        FAIL=$((FAIL+1)); FAILED+=("$name")
    fi
}

# ls
run_case ls-bare    'lib64'        /bin/ls /
run_case ls-la-tmp  'total '       /bin/ls -la /tmp
run_case ls-l-lib   'libc.so.6'    /bin/ls -l /lib
# cat / wc — sample.txt の内容で確認
run_case cat        'hello world'  /bin/cat /tmp/sample.txt
run_case wc         '4'            /usr/bin/wc -l /tmp/sample.txt
# echo はそのまま echo back
run_case echo       'hello-echo'   /bin/echo hello-echo
# true/false の exit code
run_case_exit true   0  /bin/true
run_case_exit false  1  /bin/false
# dirname / basename
run_case dirname    '/a/b'         /bin/dirname /a/b/c
run_case basename   'c'            /bin/basename /a/b/c
# uname (Emulin の identifier が出るのを確認)
run_case uname      'Emulin'       /bin/uname -a
# grep — マッチ行 / -v / -c / -E を確認
run_case grep        'hello world'  /bin/grep hello /tmp/sample.txt
run_case grep-v      'two'          /bin/grep -v hello /tmp/sample.txt
run_case grep-c      '3'            /bin/grep -c o /tmp/sample.txt
run_case grep-E      'three'        /bin/grep -E '^t' /tmp/sample.txt
# sed — 置換と削除を確認
run_case sed-subst   'HELLO world'  /bin/sed 's/hello/HELLO/' /tmp/sample.txt
run_case sed-delete  'one'          /bin/sed '/three/d' /tmp/sample.txt
# sort — 通常 / 逆順
run_case sort        'three'        /bin/sort /tmp/sample.txt
run_case sort-r      'two'          /bin/sort -r /tmp/sample.txt
# head / tail / cut / od — sample.txt
run_case head-n2     'one'          /usr/bin/head -n2 /tmp/sample.txt
run_case tail-n2     'three'        /usr/bin/tail -n2 /tmp/sample.txt
run_case cut         'hello'        /usr/bin/cut -d' ' -f1 /tmp/sample.txt
run_case od-c        'h   e   l   l   o'  /usr/bin/od -An -c /tmp/sample.txt
# awk — BEGIN ブロック / フィールド参照
run_case awk-arith   '5'            /usr/bin/awk 'BEGIN{print 2+3}'
run_case awk-fields  'cat'          /usr/bin/awk '{print $NF}' /tmp/sample.txt
# expr — 算術 / 文字列長
run_case expr-add    '13'           /usr/bin/expr 6 + 7
run_case expr-len    '11'           /usr/bin/expr length hello-world
# printf — フォーマット
run_case printf      '42 ff hello'  /usr/bin/printf '%d %x %s\n' 42 255 hello
# find — 通常ファイル列挙
run_case find        'sample.txt'   /usr/bin/find /tmp -type f
# date (epoch=0 → 1969-12-31 か 1970-01-01)
# date は実時刻を返すので "20" (2020 年代以降) でマッチ
run_case date        '20'           /bin/date +%Y
# bash — 非対話の典型ケース
run_case bash-ver    'GNU bash'     /bin/bash --version
run_case bash-echo   'hi'           /bin/bash -c 'echo hi'
run_case bash-for    'i=3'          /bin/bash -c 'for i in 1 2 3; do echo i=$i; done'
run_case bash-arith  '42'           /bin/bash -c 'echo $((6 * 7))'
run_case bash-pipe   'HELLO'        /bin/bash -c 'echo hello | tr a-z A-Z'
run_case bash-if     'yes'          /bin/bash -c '[ 5 -lt 10 ] && echo yes || echo no'
run_case bash-array  'two'          /bin/bash -c 'a=(one two three); echo ${a[1]}'
run_case bash-while  'i=2'          /bin/bash -c 'i=0; while [ $i -lt 3 ]; do echo i=$i; ((i++)); done'
run_case bash-redir  'hi-redir'     /bin/bash -c 'echo hi-redir > /tmp/redir.out; cat /tmp/redir.out'
run_case bash-cmdsub '20'           /bin/bash -c 'echo "1+19=$((1+19))"'
# make / file / git の --version (起動経路の確認)
run_case make-ver    'GNU Make'     /usr/bin/make --version
run_case file-bin    'ELF 64'       /usr/bin/file /bin/ls
run_case git-ver     'git version'  /usr/bin/git --version

# git status — テンポラリ repo を作って read-only 操作を確認
( cd "$SANDBOX/tmp" && mkdir myrepo && cd myrepo && \
    git init -q . && \
    echo "git-test-content" > test.txt && \
    git -c user.name=t -c user.email=t@t add test.txt && \
    git -c user.name=t -c user.email=t@t commit -q -m initial && \
    echo "modified-line" >> test.txt ) >/dev/null 2>&1 || echo "(repo init skipped)"
# git ls-files (新規 repo の中で確認)
run_case git-status  'test.txt'   /usr/bin/git -c safe.directory='*' -C /tmp/myrepo status -s
run_case git-log     'initial'    /usr/bin/git -c safe.directory='*' --no-pager -C /tmp/myrepo log --oneline

# curl --version: TLS / OpenSSL を含む全ライブラリがロードできることの検証
run_case curl-ver    'OpenSSL'    /usr/bin/curl --version
run_case curl-https  'https'      /usr/bin/curl --version

# wget HTTP ダウンロード (Phase 27 step 12 の EOF 検出 + 非 blocking 対応で
#   wget が hang せず exit するようになった)
HOST_REACHABLE=0
if [ -x /usr/bin/wget ] && command -v getent >/dev/null && getent hosts example.com >/dev/null 2>&1; then
    if curl -sS -m 5 -o /dev/null http://example.com/ 2>/dev/null; then
        HOST_REACHABLE=1
        cp /usr/bin/wget "$SANDBOX/usr/bin/" 2>/dev/null
        ldd /usr/bin/wget 2>/dev/null | awk '/=>/ {print $3}' | while read f; do
            [ -f "$f" ] && cp -L "$f" "$SANDBOX/lib/" 2>/dev/null
        done
        # IPv4 のみ抽出 (我々の socket impl は AF_INET6 未対応)
        EXAMPLE_IP=$(getent ahostsv4 example.com 2>/dev/null | awk '{print $1; exit}')
        [ -z "$EXAMPLE_IP" ] && EXAMPLE_IP=$(host example.com 2>/dev/null | awk '/has address/{print $4; exit}')
        if [ -n "$EXAMPLE_IP" ]; then
            cat > "$SANDBOX/etc/hosts" <<EOF
127.0.0.1 localhost
$EXAMPLE_IP example.com www.example.com
EOF
            echo "hosts: files" > "$SANDBOX/etc/nsswitch.conf"
        fi
    fi
fi
if [ "$HOST_REACHABLE" = "1" ]; then
    run_case wget-http 'Example Domain' /usr/bin/wget --connect-timeout=15 -O - http://example.com/
    # curl HTTP ダウンロード (Phase 27 step 13: poll で fd ごとの readable
    #   判定 + connect が non-blocking socket で EINPROGRESS を返すように
    #   なって動作)。/etc/passwd を NSS が読むのでサンドボックスにコピー、
    #   curl 用に追加ライブラリ (libidn/libpsl/libssh/libnghttp2 等) は
    #   curl-ver 用に既に揃っている。
    [ -f /etc/passwd ] && cp /etc/passwd "$SANDBOX/etc/passwd"
    run_case curl-http 'Example Domain' /usr/bin/curl --resolve "example.com:80:$EXAMPLE_IP" --connect-timeout 10 --max-time 20 -sS http://example.com/

    # wget DNS lookup (Phase 27 step 14: AF_INET SOCK_DGRAM + sendmmsg/
    #   recvmmsg + poll が UDP DatagramSocket を扱う + FIONREAD ioctl)。
    #   /etc/hosts に example.com を載せず、/etc/resolv.conf 経由で実 DNS を
    #   引かせる。host で /etc/resolv.conf に有効な nameserver があるとき
    #   のみ実行 (DNS query が外部 nameserver に行くため)。
    if [ -f /etc/resolv.conf ] && grep -qE '^nameserver [0-9]' /etc/resolv.conf; then
        cp -L /etc/resolv.conf "$SANDBOX/etc/resolv.conf"
        # /etc/hosts から example.com 行を消して DNS 経由を強制
        cat > "$SANDBOX/etc/hosts" <<EOF
127.0.0.1 localhost
EOF
        echo "hosts: files dns" > "$SANDBOX/etc/nsswitch.conf"
        run_case wget-dns 'Example Domain' /usr/bin/wget --connect-timeout=15 -O - http://example.com/
        # 後続テスト用に /etc/hosts と nsswitch を元に戻しておく
        cat > "$SANDBOX/etc/hosts" <<EOF
127.0.0.1 localhost
$EXAMPLE_IP example.com www.example.com
EOF
        echo "hosts: files" > "$SANDBOX/etc/nsswitch.conf"
    fi

    # wget HTTPS (Phase 27 step 18: -EPIPE タイポ修正で write が partial-write
    #   retry ループに陥らなくなった + pselect6 が timeout を honor + Fileinfo.
    #   Read 非 blocking が setSoTimeout 経由で実 read を試行)。
    #   www.iana.org は RSA cert + 安定。例題.com は Cloudflare ECDSA + TLS 1.3
    #   の特殊経路でまだ動かないので使わない。
    IANA_IP=$(getent ahostsv4 www.iana.org 2>/dev/null | awk '{print $1; exit}')
    if [ -n "$IANA_IP" ] && curl -sS -m 5 -o /dev/null https://www.iana.org/ 2>/dev/null; then
        # CA certs と /usr/lib/ssl/openssl.cnf, /dev/urandom が必要
        mkdir -p "$SANDBOX/etc/ssl/certs" "$SANDBOX/usr/lib/ssl" "$SANDBOX/dev"
        [ -f /etc/ssl/certs/ca-certificates.crt ] && \
            ln -sf /etc/ssl/certs/ca-certificates.crt "$SANDBOX/etc/ssl/certs/ca-certificates.crt" 2>/dev/null
        [ -L /usr/lib/ssl/openssl.cnf ] && \
            cp -L /usr/lib/ssl/openssl.cnf "$SANDBOX/usr/lib/ssl/" 2>/dev/null
        dd if=/dev/urandom of="$SANDBOX/dev/urandom" bs=4096 count=8 2>/dev/null
        echo "$IANA_IP www.iana.org" >> "$SANDBOX/etc/hosts"
        run_case wget-https 'IANA' /usr/bin/wget --no-check-certificate --connect-timeout=15 --read-timeout=120 --tries=1 -O - https://www.iana.org/
    fi
fi

echo
echo "===== real-coreutils: PASS=$PASS FAIL=$FAIL ====="
if [ ${#FAILED[@]} -gt 0 ]; then
    echo "failures: ${FAILED[*]}"
fi
[ "$FAIL" = 0 ]
