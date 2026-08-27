#!/usr/bin/env bash
# Run Debian arm64 dynamic bash under Emulin and require a clean exit.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)
ROOTFS=${1:-$ROOT/target/aarch64-rootfs}

test -x "$ROOTFS/usr/bin/bash" || {
    echo "missing rootfs; run dist/build-debian-arm64-bash-rootfs.sh first" >&2
    exit 2
}
test -f "$ROOT/target/classes/emulin/Emulin.class" || {
    echo "missing classes; run mvn -q -DskipTests package first" >&2
    exit 2
}

OUTPUT=$(mktemp "$ROOT/target/aarch64-bash-smoke.XXXXXX")
cleanup() { rm -f "$OUTPUT"; }
trap cleanup EXIT

(
    cd "$ROOTFS/root"
    env LC_ALL=C LANG=C perl -e 'alarm shift; exec @ARGV' 30 \
        java -cp "$ROOT/target/classes" emulin.Emulin \
        "$ROOTFS" /bin/bash -c '
            /usr/bin/true || exit
            /usr/bin/echo coreutils-echo-ok
            /usr/bin/uname -m
            /usr/bin/ls -1 /usr/bin
            /usr/bin/dpkg --version
            /usr/bin/dpkg --print-architecture
            /usr/bin/dpkg --audit
            /usr/bin/dpkg-query -W -f="\${Package}\t\${Version}\t\${Architecture}\n" emulin-arm64-smoke
            /usr/bin/dpkg-deb --extract /tmp/emulin-arm64-fixture.deb /tmp/emulin-arm64-fixture
            IFS= read -r fixture_message < /tmp/emulin-arm64-fixture/usr/share/emulin-arm64-fixture/message.txt
            printf "%s\n" "$fixture_message"
            printf "bash-rootfs-ok\n"
        '
) > "$OUTPUT"

NORMALIZED=$(sed -E \
    "s/^(Debian 'dpkg' package management program version) [^ ]+ \(arm64\)\.$/\1 VERSION (arm64)./" \
    "$OUTPUT")
EXPECTED=$(printf '%s\n' \
    coreutils-echo-ok \
    aarch64 \
    bash \
    dpkg \
    dpkg-deb \
    dpkg-query \
    echo \
    ls \
    tar \
    true \
    uname \
    "Debian 'dpkg' package management program version VERSION (arm64)." \
    'This is free software; see the GNU General Public License version 2 or' \
    'later for copying conditions. There is NO warranty.' \
    arm64 \
    "emulin-arm64-smoke	1.0	arm64" \
    dpkg-deb-extract-ok \
    bash-rootfs-ok)
test "$NORMALIZED" = "$EXPECTED"
echo "Debian arm64 bash smoke: PASS"
