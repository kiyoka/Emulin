#!/usr/bin/env bash
# Verify certificate parsing and TLS crypto primitives with Debian arm64 OpenSSL.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)
ROOTFS=${1:-$ROOT/target/aarch64-rootfs}
TIMEOUT_SECONDS=${EMULIN_CRYPTO_TIMEOUT_SECONDS:-240}
CERT=/usr/share/ca-certificates/mozilla/ISRG_Root_X1.crt

test -x "$ROOTFS/usr/bin/openssl" || {
    echo "missing rootfs; run dist/build-debian-arm64-bash-rootfs.sh first" >&2
    exit 2
}
test -f "$ROOT/target/classes/emulin/Emulin.class" || {
    echo "missing classes; run mvn -q -DskipTests package first" >&2
    exit 2
}

run_guest() {
    (
        cd "$ROOTFS/root"
        env LC_ALL=C LANG=C perl -e 'alarm shift; exec @ARGV' "$TIMEOUT_SECONDS" \
            java -cp "$ROOT/target/classes" emulin.Emulin "$ROOTFS" "$@"
    )
}

X509=$(run_guest /usr/bin/openssl x509 -in "$CERT" -noout -subject)
case "$X509" in
    *"CN=ISRG Root X1"*) ;;
    *) echo "$X509" >&2; exit 1 ;;
esac

DIGEST=$(run_guest /usr/bin/openssl dgst -sha256 "$CERT")
case "$DIGEST" in
    *22b557a27055b33606b6559f37703928d3e4ad79f110b407d04986e1843543d1*) ;;
    *) echo "$DIGEST" >&2; exit 1 ;;
esac

AES=$(run_guest /bin/sh -c \
    "openssl enc -aes-128-ctr -K 000102030405060708090a0b0c0d0e0f -iv 101112131415161718191a1b1c1d1e1f -in $CERT | openssl dgst -sha256")
case "$AES" in
    *4dc6952d21828792371888ee9184e18b247bd8e1655b1d09fd1c58e5d7619174*) ;;
    *) echo "$AES" >&2; exit 1 ;;
esac

CHACHA=$(run_guest /bin/sh -c \
    "openssl enc -chacha20 -K 000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f -iv 00000000000000000000000000000000 -in $CERT | openssl dgst -sha256")
case "$CHACHA" in
    *c0cd713cdce1d868500c76bbe51152961e9f34d3a517bfb852ec1f79eec92722*) ;;
    *) echo "$CHACHA" >&2; exit 1 ;;
esac

echo "Debian arm64 certificate/crypto smoke: PASS"
