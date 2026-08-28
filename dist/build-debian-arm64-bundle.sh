#!/usr/bin/env bash
# Build the Issue 951 experimental macOS/AArch64 Debian rootfs bundle.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd -P)
ROOTFS=${AARCH64_ROOTFS:-$ROOT/target/aarch64-rootfs}
VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1)
NAME=emulin-aarch64-phase6-$VERSION
STAGE=$ROOT/target/$NAME
TAR=$ROOT/target/$NAME.tar
ARCHIVE=$TAR.gz

test -x "$ROOTFS/usr/bin/bash" || {
    echo "missing rootfs; run dist/build-debian-arm64-bash-rootfs.sh first" >&2
    exit 2
}
test -n "$VERSION" || { echo "could not read version from pom.xml" >&2; exit 2; }

mvn -q -DskipTests package
JAR=$ROOT/target/emulin-$VERSION-all.jar
test -f "$JAR"

rm -rf "$STAGE"
mkdir -p "$STAGE/lib"
cp -a "$ROOTFS" "$STAGE/rootfs"
cp "$JAR" "$STAGE/lib/"
cp dist/launchers/emulin.sh "$STAGE/"
cp dist/AARCH64-PHASE6-README.md "$STAGE/README.md"
cp dist/debian-arm64-bash-rootfs.manifest "$STAGE/"
chmod 0755 "$STAGE/emulin.sh"
find "$STAGE" -exec touch -h -t 200001010000 {} +

# Sort the path list and suppress gzip timestamps so identical inputs produce
# identical bytes.  Archive ownership is normalized while POSIX modes and
# symlink objects are retained.
LIST=$(mktemp "$ROOT/target/aarch64-bundle-list.XXXXXX")
cleanup() { rm -f "$LIST" "$TAR"; }
trap cleanup EXIT
(
    cd "$ROOT/target"
    find "$NAME" -print | LC_ALL=C sort > "$LIST"
    COPYFILE_DISABLE=1 tar --format=ustar --uid 0 --gid 0 \
        --uname root --gname root -cf "$TAR" -T "$LIST"
)
gzip -n -f "$TAR"
shasum -a 256 "$ARCHIVE" > "$ARCHIVE.sha256"

tar -tzf "$ARCHIVE" | grep -q "^$NAME/rootfs/usr/bin/bash$"
tar -tzf "$ARCHIVE" | grep -q "^$NAME/lib/emulin-$VERSION-all.jar$"
echo "AArch64 Debian bundle: $ARCHIVE"
cat "$ARCHIVE.sha256"
