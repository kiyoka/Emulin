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
    env LC_ALL=C LANG=C perl -e 'alarm shift; exec @ARGV' 600 \
        java -cp "$ROOT/target/classes" emulin.Emulin \
        "$ROOTFS" /bin/bash -c '
            /usr/bin/true || exit
            /usr/bin/echo coreutils-echo-ok
            /usr/bin/uname -m
            /usr/bin/ls -1 /usr/bin
            /usr/bin/dpkg --version
            /usr/bin/dpkg --print-architecture
            /usr/bin/apt-get --version > /tmp/apt-version
            IFS= read -r apt_version < /tmp/apt-version
            test "$apt_version" = "apt 3.0.3 (arm64)" || exit 1
            printf "%s\n" "$apt_version"
            apt_architecture=$(/usr/bin/apt-config shell architecture APT::Architecture)
            case "$apt_architecture" in
                *arm64*) ;;
                *) exit 1 ;;
            esac
            printf "apt-offline-config-ok\n"
            /usr/bin/dpkg --audit
            /usr/bin/dpkg-query -W -f="\${Package}\t\${Version}\t\${Architecture}\n" emulin-arm64-smoke
            /usr/bin/dpkg-deb --extract /tmp/emulin-arm64-fixture.deb /tmp/emulin-arm64-fixture
            IFS= read -r fixture_message < /tmp/emulin-arm64-fixture/usr/share/emulin-arm64-fixture/message.txt
            printf "%s\n" "$fixture_message"
            unpack_output=$(/usr/bin/dpkg --unpack /tmp/emulin-arm64-fixture.deb 2>&1) || {
                printf "%s\n" "$unpack_output" >&2
                exit 1
            }
            /usr/bin/dpkg-query -W -f="\${Package}\t\${Status}\n" emulin-arm64-deb-fixture
            test -f /var/lib/dpkg/info/emulin-arm64-deb-fixture.list || exit 1
            test -f /var/lib/dpkg/info/emulin-arm64-deb-fixture.md5sums || exit 1
            IFS= read -r installed_message < /usr/share/emulin-arm64-fixture/message.txt || exit 1
            test "$installed_message" = dpkg-deb-extract-ok || exit 1
            printf "dpkg-unpack-db-ok\n"
            configure_output=$(/usr/bin/dpkg --configure emulin-arm64-deb-fixture 2>&1) || {
                printf "%s\n" "$configure_output" >&2
                exit 1
            }
            IFS= read -r configured_message < /usr/share/emulin-arm64-fixture/configured.txt || exit 1
            test "$configured_message" = postinst-configure-ok || exit 1
            printf "%s\n" "$configured_message"
            /usr/bin/dpkg-query -W -f="\${Package}\t\${Status}\n" emulin-arm64-deb-fixture
            printf "dpkg-configure-db-ok\n"
            printf "real-bash-unpack-start\n" >&2
            real_bash_unpack=$(/usr/bin/dpkg --unpack /tmp/debian-bash.deb 2>&1) || {
                printf "%s\n" "$real_bash_unpack" >&2
                exit 1
            }
            printf "real-bash-unpack-done\n" >&2
            /usr/bin/dpkg-query -W -f="\${Package}\t\${Status}\t\${Architecture}\n" bash
            real_bash_configure=$(/usr/bin/dpkg --configure bash 2>&1) || {
                printf "%s\n" "$real_bash_configure" >&2
                exit 1
            }
            test -f /var/lib/dpkg/info/bash.list || exit 1
            test -f /var/lib/dpkg/info/bash.md5sums || exit 1
            test -x /var/lib/dpkg/info/bash.postinst || exit 1
            /bin/bash -c "printf \"real-debian-bash-package-ok\\n\""
            /usr/bin/dpkg-query -W -f="\${Package}\t\${Status}\t\${Architecture}\n" bash
            printf "bash-rootfs-ok\n"
        '
) > "$OUTPUT"

NORMALIZED=$(sed -E \
    "s/^(Debian 'dpkg' package management program version) [^ ]+ \(arm64\)\.$/\1 VERSION (arm64)./" \
    "$OUTPUT")
EXPECTED=$(printf '%s\n' \
    coreutils-echo-ok \
    aarch64 \
    apt-config \
    apt-get \
    bash \
    dash \
    diff \
    dpkg \
    dpkg-deb \
    dpkg-query \
    dpkg-split \
    echo \
    ln \
    ls \
    rm \
    sh \
    tar \
    true \
    uname \
    update-alternatives \
    "Debian 'dpkg' package management program version VERSION (arm64)." \
    'This is free software; see the GNU General Public License version 2 or' \
    'later for copying conditions. There is NO warranty.' \
    arm64 \
    'apt 3.0.3 (arm64)' \
    apt-offline-config-ok \
    "emulin-arm64-smoke	1.0	arm64" \
    dpkg-deb-extract-ok \
    "emulin-arm64-deb-fixture	install ok unpacked" \
    dpkg-unpack-db-ok \
    postinst-configure-ok \
    "emulin-arm64-deb-fixture	install ok installed" \
    dpkg-configure-db-ok \
    "bash	install ok unpacked	arm64" \
    real-debian-bash-package-ok \
    "bash	install ok installed	arm64" \
    bash-rootfs-ok)
test "$NORMALIZED" = "$EXPECTED"
echo "Debian arm64 bash smoke: PASS"
