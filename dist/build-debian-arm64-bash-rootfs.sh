#!/usr/bin/env bash
# Build the minimal Debian 13 arm64 rootfs used by the issue #951 bash smoke.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)
REMOTE=${AARCH64_ROOTFS_SSH:-emulin-arm64}
OUT=${1:-$ROOT/target/aarch64-rootfs}
MANIFEST=${AARCH64_ROOTFS_MANIFEST:-$ROOT/dist/debian-arm64-bash-rootfs.manifest}

case "$OUT" in
    "$ROOT"/target/*) ;;
    *) echo "refusing output outside $ROOT/target: $OUT" >&2; exit 2 ;;
esac
test -f "$MANIFEST" || {
    echo "missing arm64 rootfs manifest: $MANIFEST" >&2
    exit 2
}

mkdir -p "$ROOT/target"
STAGE=$(mktemp -d "$ROOT/target/.aarch64-rootfs.XXXXXX")
cleanup() { rm -rf "$STAGE"; }
trap cleanup EXIT

# Fail before copying anything if the VM no longer matches the pinned Debian
# baseline.  This keeps a package upgrade on the VM from silently changing the
# public smoke fixture and its ABI/ISA coverage.
packages=()
while IFS=$'\t' read -r package _; do
    case "$package" in
        ''|'#'*) continue ;;
    esac
    packages+=("$package")
done < "$MANIFEST"
ssh -o BatchMode=yes "$REMOTE" dpkg-query -W "${packages[@]}" \
    > "$STAGE/installed-packages"
awk -F '\t' '!/^#/ && NF >= 2 { print $1 "\t" $2 }' "$MANIFEST" \
    > "$STAGE/expected-packages"
if ! diff -u "$STAGE/expected-packages" "$STAGE/installed-packages"; then
    echo "Debian arm64 VM package versions differ from $MANIFEST" >&2
    exit 2
fi

ssh -o BatchMode=yes "$REMOTE" '
    set -eu
    test "$(uname -m)" = aarch64
    test "$(dpkg --print-architecture)" = arm64
    test "$(. /etc/os-release; echo "$VERSION_ID")" = 13
    set -- \
        usr/bin/apt-config \
        usr/bin/apt-get \
        usr/bin/bash \
        usr/bin/dash \
        usr/bin/sh \
        usr/bin/true \
        usr/bin/echo \
        usr/bin/uname \
        usr/bin/ln \
        usr/bin/ls \
        usr/bin/openssl \
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
        usr/lib/apt \
        usr/share/dpkg \
        usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1 \
        usr/lib/aarch64-linux-gnu/libc.so.6 \
        usr/lib/aarch64-linux-gnu/libapt-private.so.0.0 \
        usr/lib/aarch64-linux-gnu/libapt-pkg.so.7.0 \
        usr/lib/aarch64-linux-gnu/libstdc++.so.6 \
        usr/lib/aarch64-linux-gnu/libgcc_s.so.1 \
        usr/lib/aarch64-linux-gnu/libtinfo.so.6 \
        usr/lib/aarch64-linux-gnu/libselinux.so.1 \
        usr/lib/aarch64-linux-gnu/libcap.so.2 \
        usr/lib/aarch64-linux-gnu/libacl.so.1 \
        usr/lib/aarch64-linux-gnu/libpcre2-8.so.0 \
        usr/lib/aarch64-linux-gnu/libmd.so.0 \
        usr/lib/aarch64-linux-gnu/libz.so.1 \
        usr/lib/aarch64-linux-gnu/liblzma.so.5 \
        usr/lib/aarch64-linux-gnu/liblz4.so.1 \
        usr/lib/aarch64-linux-gnu/libzstd.so.1 \
        usr/lib/aarch64-linux-gnu/libbz2.so.1.0 \
        usr/lib/aarch64-linux-gnu/libudev.so.1 \
        usr/lib/aarch64-linux-gnu/libsystemd.so.0 \
        usr/lib/aarch64-linux-gnu/libseccomp.so.2 \
        usr/lib/aarch64-linux-gnu/libssl.so.3 \
        usr/lib/aarch64-linux-gnu/libcrypto.so.3 \
        usr/lib/aarch64-linux-gnu/libxxhash.so.0 \
        usr/lib/aarch64-linux-gnu/libm.so.6 \
        etc/hosts \
        etc/nsswitch.conf \
        etc/resolv.conf \
        etc/ssl/openssl.cnf \
        etc/ssl/certs/ca-certificates.crt \
        usr/share/ca-certificates/mozilla/ISRG_Root_X1.crt \
        usr/lib/aarch64-linux-gnu/libnss_dns.so.2 \
        usr/lib/aarch64-linux-gnu/libnss_files.so.2 \
        usr/lib/aarch64-linux-gnu/ossl-modules/legacy.so \
        usr/lib/ssl/cert.pem \
        usr/lib/ssl/certs \
        usr/lib/ssl/openssl.cnf
    for library in \
        usr/lib/aarch64-linux-gnu/libapt-private.so.0.0 \
        usr/lib/aarch64-linux-gnu/libapt-pkg.so.7.0 \
        usr/lib/aarch64-linux-gnu/libstdc++.so.6 \
        usr/lib/aarch64-linux-gnu/libgcc_s.so.1 \
        usr/lib/aarch64-linux-gnu/libtinfo.so.6 \
        usr/lib/aarch64-linux-gnu/libselinux.so.1 \
        usr/lib/aarch64-linux-gnu/libcap.so.2 \
        usr/lib/aarch64-linux-gnu/libacl.so.1 \
        usr/lib/aarch64-linux-gnu/libpcre2-8.so.0 \
        usr/lib/aarch64-linux-gnu/libmd.so.0 \
        usr/lib/aarch64-linux-gnu/libz.so.1 \
        usr/lib/aarch64-linux-gnu/liblzma.so.5 \
        usr/lib/aarch64-linux-gnu/liblz4.so.1 \
        usr/lib/aarch64-linux-gnu/libzstd.so.1 \
        usr/lib/aarch64-linux-gnu/libbz2.so.1.0 \
        usr/lib/aarch64-linux-gnu/libudev.so.1 \
        usr/lib/aarch64-linux-gnu/libsystemd.so.0 \
        usr/lib/aarch64-linux-gnu/libseccomp.so.2 \
        usr/lib/aarch64-linux-gnu/libssl.so.3 \
        usr/lib/aarch64-linux-gnu/libcrypto.so.3 \
        usr/lib/aarch64-linux-gnu/libxxhash.so.0 \
        usr/lib/aarch64-linux-gnu/libm.so.6
    do
        resolved=$(readlink -f "/$library")
        resolved=${resolved#/}
        test "$resolved" = "$library" || set -- "$@" "$resolved"
    done
    tar -C / -cf - "$@"
' | tar -C "$STAGE" -xf -

# Compile the local Phase 6 syscall probe natively in the arm64 VM.  Only the
# source is versioned; generated ELF files stay under target/.
ssh -o BatchMode=yes "$REMOTE" '
    set -eu
    work=$(mktemp -d)
    trap '\''rm -rf "$work"'\'' EXIT
    cat > "$work/probe.c"
    cc -O1 "$work/probe.c" -o "$work/emulin-aarch64-phase6-probe"
    cat "$work/emulin-aarch64-phase6-probe"
' < "$ROOT/tests/tools/aarch64_phase6_probe.c" \
    > "$STAGE/usr/bin/emulin-aarch64-phase6-probe"
chmod 0755 "$STAGE/usr/bin/emulin-aarch64-phase6-probe"

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

# Build a deterministic flat file:// repository containing a real AArch64 ELF.
# It gives Phase 6 an apt update/install/execute gate without depending on a
# live Debian mirror or committing generated binaries to the repository.
ssh -o BatchMode=yes "$REMOTE" '
    set -eu
    work=$(mktemp -d)
    trap '\''rm -rf "$work"'\'' EXIT
    mkdir -p "$work/pkg/DEBIAN" "$work/pkg/usr/bin" "$work/opt/emulin-phase6-repo"
    printf "%s\n" \
        "Package: emulin-phase6" \
        "Version: 1.0" \
        "Architecture: arm64" \
        "Maintainer: Emulin Phase 6 fixture" \
        "Description: pinned AArch64 apt installation fixture" \
        > "$work/pkg/DEBIAN/control"
    printf "%s\n" \
        "#include <stdio.h>" \
        "int main(void) { puts(\"apt-installed-arm64-ok\"); return 0; }" \
        > "$work/phase6.c"
    cc -Os "$work/phase6.c" -o "$work/pkg/usr/bin/emulin-phase6"
    dpkg-deb --root-owner-group --build \
        "$work/pkg" "$work/opt/emulin-phase6-repo/emulin-phase6_1.0_arm64.deb" \
        >/dev/null
    (
        cd "$work/opt/emulin-phase6-repo"
        dpkg-scanpackages . /dev/null > Packages
    )
    tar -C "$work" -cf - opt
' | tar -C "$STAGE" -xf -

read -r BASH_ARCHIVE BASH_SHA256 < <(
    awk -F '\t' '$1 == "bash" { print $3, $4 }' "$MANIFEST"
)
test -n "$BASH_ARCHIVE" && test "$BASH_ARCHIVE" != -
test -n "$BASH_SHA256" && test "$BASH_SHA256" != -
ssh -o BatchMode=yes "$REMOTE" bash -s -- "$BASH_ARCHIVE" "$BASH_SHA256" \
    > "$STAGE/tmp/debian-bash.deb" <<'REMOTE'
    set -eu
    package=/var/cache/apt/archives/$1
    expected_sha256=$2
    test -f "$package"
    actual_sha256=$(sha256sum "$package")
    actual_sha256=${actual_sha256%% *}
    test "$actual_sha256" = "$expected_sha256"
    test "$(dpkg-deb -f "$package" Package)" = bash
    test "$(dpkg-deb -f "$package" Architecture)" = arm64
    cat "$package"
REMOTE

mkdir -p "$STAGE/etc/apt/apt.conf.d" "$STAGE/etc/apt/sources.list.d" \
    "$STAGE/etc/apt/preferences.d" \
    "$STAGE/root" "$STAGE/tmp" \
    "$STAGE/etc/alternatives" \
    "$STAGE/var/cache/apt/archives/partial" \
    "$STAGE/var/lib/apt/lists/partial" \
    "$STAGE/var/log/apt" \
    "$STAGE/var/lib/dpkg/info" "$STAGE/var/lib/dpkg/parts" \
    "$STAGE/var/lib/dpkg/alternatives" \
    "$STAGE/var/lib/dpkg/triggers" "$STAGE/var/lib/dpkg/updates"
: > "$STAGE/var/lib/apt/extended_states"
chmod 0644 "$STAGE/var/lib/apt/extended_states"
ln -s usr/bin "$STAGE/bin"
ln -s usr/lib "$STAGE/lib"
ln -s aarch64-linux-gnu/ld-linux-aarch64.so.1 \
    "$STAGE/usr/lib/ld-linux-aarch64.so.1"
: > "$STAGE/etc/emulin.cnf"
printf '%s\n' \
    'APT::Architecture "arm64";' \
    'APT::Architectures { "arm64"; };' \
    'APT::Sandbox::User "root";' \
    > "$STAGE/etc/apt/apt.conf.d/00-emulin-arm64"
printf '%s\n' \
    'deb [trusted=yes] file:/opt/emulin-phase6-repo ./' \
    > "$STAGE/etc/apt/sources.list.d/emulin-phase6.list"
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
