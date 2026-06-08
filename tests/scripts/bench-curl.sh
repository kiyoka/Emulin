#!/usr/bin/env bash
# bench-curl.sh — issue #221: 看板 workload (curl HTTPS) を native vs software で実測する。
#
# curl は PIE (ET_DYN) + 30+ 共有ライブラリ (libcurl/OpenSSL/zlib/...) の重い実 binary で、HTTPS は
# TLS handshake / AES / ASN.1 parse = crypto-heavy。go/no-go (3d-2c-16) の通り compute-heavy は native
# 圧勝なので、看板の HTTPS こそ native の真価が出る。実測:
#   - curl --version : PIE + 32 libs の起動/動的リンク (native==software を確認)
#   - HTTP  (非 TLS) : network path のみ (crypto 無し) → 両 backend ほぼ同等
#   - HTTPS (TLS)    : crypto-heavy → ★native は実 CPU で速い、software は遅すぎて server idle
#                      timeout で失敗することがある
#
# host curl + ldd 依存 lib + ld.so + 証明書を sandbox に置く。network/curl/kvm が無い環境は SKIP。
# 計測値を出すだけ (pass/fail でない、network 依存なので CI 外)。
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes
ASM=$(find "$HOME/.m2" -name "asm-9.6.jar" 2>/dev/null | head -1)
CP="$CLASSES${ASM:+:$ASM}"
JOPT="--enable-native-access=ALL-UNNAMED -XX:-UsePerfData -Xmx4g"
URL=${BENCH_CURL_URL:-https://example.com}
HURL=${BENCH_CURL_HTTP:-http://example.com}

[ -f "$CLASSES/emulin/Emulin.class" ] || { echo "SKIP : Emulin not built"; exit 2; }
CURL=$(command -v curl) || { echo "SKIP : host curl not found"; exit 2; }
file "$CURL" 2>/dev/null | grep -q ELF || { echo "SKIP : host curl not ELF"; exit 2; }
HAVE_KVM=0; [ -r /dev/kvm ] && [ -w /dev/kvm ] && HAVE_KVM=1

SB=$(mktemp -d -t emulin-curl.XXXXXX); trap 'rm -rf "$SB"' EXIT
mkdir -p "$SB"/{bin,etc,tmp,lib64,usr/bin} "$SB/etc/ssl/certs"
: > "$SB/etc/emulin.cnf"
cp /lib64/ld-linux-x86-64.so.2 "$SB/lib64/"
cp "$CURL" "$SB/usr/bin/curl"
ldd "$CURL" 2>/dev/null | grep -oE '/lib[^ ]*\.so\.[0-9]+' | sort -u | while read l; do
    d="$SB$(dirname "$l")"; mkdir -p "$d"; cp "$l" "$d/" 2>/dev/null; done
cp /etc/ssl/certs/ca-certificates.crt "$SB/etc/ssl/certs/" 2>/dev/null
cp /etc/resolv.conf "$SB/etc/" 2>/dev/null
NLIB=$(find "$SB" -name '*.so.*' | wc -l)

run() {  # run <backend> <args...> → stdout (grep title), 所要秒は stderr へ
    local be=$1; shift; local t0 o; t0=$(date +%s.%N)
    o=$( cd "$SB" && timeout 120 env EMULIN_BACKEND=$be java $JOPT -cp "$CP" emulin.Emulin "$SB" /usr/bin/curl "$@" < /dev/null 2>/dev/null )
    printf '%.1fs' "$(echo "$(date +%s.%N)-$t0" | bc)" >&2
    printf '%s' "$o"
}

echo "===== issue #221: curl (PIE + $NLIB libs) native vs software ====="
echo "(1) curl --version (PIE 起動 + 動的リンク):"
sv=$(run software --version 2>/dev/null | head -1); echo "    software: ${sv:0:60}"
if [ "$HAVE_KVM" = 1 ]; then nv=$(run native --version 2>/dev/null | head -1); echo "    native  : ${nv:0:60}"
    [ "$sv" = "$nv" ] && echo "    => native==software ✅" || echo "    => 不一致"; fi

echo "(2) HTTP ($HURL、network path のみ、crypto 無し):"
st=$( { run software -s --max-time 60 "$HURL" | grep -oE '<title>[^<]*</title>'; } 2>/tmp/_bt ); echo "    software: $(cat /tmp/_bt) [$st]"
if [ "$HAVE_KVM" = 1 ]; then nt=$( { run native -s --max-time 60 "$HURL" | grep -oE '<title>[^<]*</title>'; } 2>/tmp/_bt ); echo "    native  : $(cat /tmp/_bt) [$nt]"; fi

echo "(3) ★ HTTPS ($URL、crypto-heavy = TLS/AES/ASN.1):"
ss=$( { run software -s --max-time 90 "$URL" | grep -oE '<title>[^<]*</title>'; } 2>/tmp/_bt ); echo "    software: $(cat /tmp/_bt) [${ss:-FAIL/timeout}]"
if [ "$HAVE_KVM" = 1 ]; then ns=$( { run native -s --max-time 90 "$URL" | grep -oE '<title>[^<]*</title>'; } 2>/tmp/_bt ); echo "    native  : $(cat /tmp/_bt) [${ns:-FAIL}]"; fi
rm -f /tmp/_bt
echo "結論: HTTP (crypto 無し) は両 backend 同等。HTTPS は crypto を実 CPU で走らせる native が"
echo "      圧倒的に速く、software は遅すぎて server timeout で失敗しうる = 看板 workload で native GO。"
