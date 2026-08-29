#!/usr/bin/env bash
# Run a deterministic apt update/install/execute cycle under AArch64 Emulin.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)
ROOTFS=${1:-$ROOT/target/aarch64-rootfs}
TIMEOUT_SECONDS=${EMULIN_APT_TIMEOUT_SECONDS:-300}

test -x "$ROOTFS/usr/bin/apt-get" || {
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

# Keep update and install in separate guest processes.  A single bash process
# retains both apt method trees and takes more than ten minutes in the software
# interpreter; separate invocations also match normal command-line use.
run_guest /usr/bin/apt-get -qq update \
    -o Acquire::Languages=none -o APT::Color=0
echo apt-update-ok
run_guest /usr/bin/apt-get -qq install -y emulin-phase6 \
    -o APT::Color=0 -o Dpkg::Progress-Fancy=0
QUERY=$(run_guest /usr/bin/dpkg-query -W \
    '-f=${Package}\t${Version}\t${Architecture}\t${Status}\n' emulin-phase6)
case "$QUERY" in
    *"emulin-phase6"*"1.0"*"arm64"*"install ok installed"*) ;;
    *) echo "$QUERY" >&2; exit 1 ;;
esac
EXECUTED=$(run_guest /usr/bin/emulin-phase6)
case "$EXECUTED" in
    *apt-installed-arm64-ok*) ;;
    *) echo "$EXECUTED" >&2; exit 1 ;;
esac
echo apt-install-execute-ok
