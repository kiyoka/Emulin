#!/usr/bin/env bash
# Build the minimal Debian 13 arm64 rootfs used by the issue #951 bash smoke.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)
REMOTE=${AARCH64_ROOTFS_SSH:-emulin-arm64}
OUT=${1:-$ROOT/target/aarch64-rootfs}

case "$OUT" in
    "$ROOT"/target/*) ;;
    *) echo "refusing output outside $ROOT/target: $OUT" >&2; exit 2 ;;
esac

mkdir -p "$ROOT/target"
STAGE=$(mktemp -d "$ROOT/target/.aarch64-rootfs.XXXXXX")
cleanup() { rm -rf "$STAGE"; }
trap cleanup EXIT

ssh -o BatchMode=yes "$REMOTE" '
    set -eu
    test "$(uname -m)" = aarch64
    test "$(dpkg --print-architecture)" = arm64
    test "$(. /etc/os-release; echo "$VERSION_ID")" = 13
    set -- \
        usr/bin/bash \
        usr/bin/dash \
        usr/bin/sh \
        usr/bin/true \
        usr/bin/echo \
        usr/bin/uname \
        usr/bin/ln \
        usr/bin/ls \
        usr/bin/rm \
        usr/bin/diff \
        usr/bin/dpkg \
        usr/bin/dpkg-deb \
        usr/bin/dpkg-query \
        usr/bin/dpkg-split \
        usr/bin/update-alternatives \
        usr/bin/tar \
        usr/sbin/ldconfig \
        usr/sbin/start-stop-daemon \
        usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1 \
        usr/lib/aarch64-linux-gnu/libc.so.6 \
        usr/lib/aarch64-linux-gnu/libtinfo.so.6 \
        usr/lib/aarch64-linux-gnu/libselinux.so.1 \
        usr/lib/aarch64-linux-gnu/libcap.so.2 \
        usr/lib/aarch64-linux-gnu/libacl.so.1 \
        usr/lib/aarch64-linux-gnu/libpcre2-8.so.0 \
        usr/lib/aarch64-linux-gnu/libmd.so.0 \
        usr/lib/aarch64-linux-gnu/libz.so.1 \
        usr/lib/aarch64-linux-gnu/liblzma.so.5 \
        usr/lib/aarch64-linux-gnu/libzstd.so.1 \
        usr/lib/aarch64-linux-gnu/libbz2.so.1.0
    for library in \
        usr/lib/aarch64-linux-gnu/libtinfo.so.6 \
        usr/lib/aarch64-linux-gnu/libselinux.so.1 \
        usr/lib/aarch64-linux-gnu/libcap.so.2 \
        usr/lib/aarch64-linux-gnu/libacl.so.1 \
        usr/lib/aarch64-linux-gnu/libpcre2-8.so.0 \
        usr/lib/aarch64-linux-gnu/libmd.so.0 \
        usr/lib/aarch64-linux-gnu/libz.so.1 \
        usr/lib/aarch64-linux-gnu/liblzma.so.5 \
        usr/lib/aarch64-linux-gnu/libzstd.so.1 \
        usr/lib/aarch64-linux-gnu/libbz2.so.1.0
    do
        resolved=$(readlink -f "/$library")
        resolved=${resolved#/}
        test "$resolved" = "$library" || set -- "$@" "$resolved"
    done
    tar -C / -cf - "$@"
' | tar -C "$STAGE" -xf -

mkdir -p "$STAGE/tmp"
ssh -o BatchMode=yes "$REMOTE" '
    set -eu
    work=$(mktemp -d)
    trap '\''rm -rf "$work"'\'' EXIT
    mkdir -p "$work/pkg/DEBIAN" "$work/pkg/usr/share/emulin-arm64-fixture"
    printf "%s\n" \
        "Package: emulin-arm64-deb-fixture" \
        "Version: 1.0" \
        "Architecture: all" \
        "Maintainer: Emulin conformance fixture" \
        "Description: Emulin AArch64 dpkg-deb extraction fixture" \
        > "$work/pkg/DEBIAN/control"
    printf "dpkg-deb-extract-ok\n" \
        > "$work/pkg/usr/share/emulin-arm64-fixture/message.txt"
    printf "%s\n" \
        "#!/bin/sh" \
        "set -e" \
        "test \"\$1\" = configure" \
        "printf \"postinst-configure-ok\\n\" > /usr/share/emulin-arm64-fixture/configured.txt" \
        > "$work/pkg/DEBIAN/postinst"
    chmod 0755 "$work/pkg/DEBIAN/postinst"
    dpkg-deb --root-owner-group --build "$work/pkg" "$work/fixture.deb" >/dev/null
    cat "$work/fixture.deb"
' > "$STAGE/tmp/emulin-arm64-fixture.deb"

ssh -o BatchMode=yes "$REMOTE" '
    set -eu
    package=$(find /var/cache/apt/archives -maxdepth 1 -type f \
        -name "bash_*_arm64.deb" | sort | tail -1)
    test -n "$package"
    cat "$package"
' > "$STAGE/tmp/debian-bash.deb"

mkdir -p "$STAGE/etc" "$STAGE/root" "$STAGE/tmp" \
    "$STAGE/etc/alternatives" \
    "$STAGE/var/lib/dpkg/info" "$STAGE/var/lib/dpkg/parts" \
    "$STAGE/var/lib/dpkg/alternatives" \
    "$STAGE/var/lib/dpkg/triggers" "$STAGE/var/lib/dpkg/updates"
ln -s usr/bin "$STAGE/bin"
ln -s usr/lib "$STAGE/lib"
ln -s aarch64-linux-gnu/ld-linux-aarch64.so.1 \
    "$STAGE/usr/lib/ld-linux-aarch64.so.1"
: > "$STAGE/etc/emulin.cnf"
printf '%s\n' \
    'Package: emulin-arm64-smoke' \
    'Status: install ok installed' \
    'Maintainer: Emulin conformance fixture' \
    'Architecture: arm64' \
    'Version: 1.0' \
    'Description: Emulin AArch64 dpkg-query smoke fixture' \
    '' \
    'Package: base-files' \
    'Status: install ok installed' \
    'Maintainer: Emulin dependency fixture' \
    'Architecture: arm64' \
    'Version: 999' \
    'Description: dependency fixture' \
    '' \
    'Package: debianutils' \
    'Status: install ok installed' \
    'Maintainer: Emulin dependency fixture' \
    'Architecture: arm64' \
    'Version: 999' \
    'Description: dependency fixture' \
    '' \
    'Package: libc6' \
    'Status: install ok installed' \
    'Maintainer: Emulin dependency fixture' \
    'Architecture: arm64' \
    'Version: 999' \
    'Description: dependency fixture' \
    '' \
    'Package: libtinfo6' \
    'Status: install ok installed' \
    'Maintainer: Emulin dependency fixture' \
    'Architecture: arm64' \
    'Version: 999' \
    'Description: dependency fixture' \
    > "$STAGE/var/lib/dpkg/status"
: > "$STAGE/var/lib/dpkg/info/emulin-arm64-smoke.list"
: > "$STAGE/var/lib/dpkg/info/emulin-arm64-smoke.md5sums"
printf '1\n' > "$STAGE/var/lib/dpkg/info/format"
for dependency in base-files debianutils libc6 libtinfo6; do
    : > "$STAGE/var/lib/dpkg/info/$dependency.list"
    : > "$STAGE/var/lib/dpkg/info/$dependency.md5sums"
done
printf 'arm64\n' > "$STAGE/var/lib/dpkg/arch"

rm -rf "$OUT"
mv "$STAGE" "$OUT"
trap - EXIT
echo "Debian arm64 bash rootfs: $OUT"
