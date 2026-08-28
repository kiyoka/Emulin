#!/usr/bin/env bash
# Verify Debian OpenSSL's default TLS 1.3/PQ-hybrid path under AArch64 Emulin.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)
ROOTFS=${1:-$ROOT/target/aarch64-rootfs}
TIMEOUT_SECONDS=${EMULIN_TLS_TIMEOUT_SECONDS:-240}
HOST=deb.debian.org
CA=/usr/share/ca-certificates/mozilla/ISRG_Root_X1.crt

if [ ! -x "$ROOTFS/usr/bin/openssl" ]; then
    echo "ERROR: Debian arm64 OpenSSL is missing from $ROOTFS" >&2
    exit 2
fi
if [ ! -f "$ROOT/target/classes/emulin/Emulin.class" ]; then
    echo "ERROR: compile Emulin first with: mvn -q test" >&2
    exit 2
fi

OUTPUT=$(
    cd "$ROOTFS/root"
    printf 'Q\n' | env LC_ALL=C LANG=C perl -e 'alarm shift; exec @ARGV' \
        "$TIMEOUT_SECONDS" java -cp "$ROOT/target/classes" emulin.Emulin \
        "$ROOTFS" /usr/bin/openssl s_client \
        -connect "$HOST:443" -servername "$HOST" -verify_hostname "$HOST" \
        -verify_return_error -brief -CAfile "$CA" 2>&1
)

case "$OUTPUT" in
    *"CONNECTION ESTABLISHED"*) ;;
    *) echo "$OUTPUT" >&2; exit 1 ;;
esac
case "$OUTPUT" in
    *"Protocol version: TLSv1.3"*) ;;
    *) echo "$OUTPUT" >&2; exit 1 ;;
esac
case "$OUTPUT" in
    *"Verification: OK"*) ;;
    *) echo "$OUTPUT" >&2; exit 1 ;;
esac
case "$OUTPUT" in
    *"Verified peername: $HOST"*) ;;
    *) echo "$OUTPUT" >&2; exit 1 ;;
esac
case "$OUTPUT" in
    *"X25519MLKEM768"*) ;;
    *) echo "$OUTPUT" >&2; exit 1 ;;
esac

echo "Debian arm64 TLS 1.3 X25519MLKEM768 smoke: PASS"
