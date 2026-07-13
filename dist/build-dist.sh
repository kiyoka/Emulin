#!/usr/bin/env bash
# --------------------------------------------------------------------
#  dist/build-dist.sh
#
#  Phase 22 step 3f: 配布用 zip を作る。
#
#    1. mvn package で fat jar を生成
#    2. lib/ ・rootfs/bin/ ・rootfs/etc/ を組み立て
#    3. host の busybox を rootfs/bin/ にコピー
#    4. ランチャ (emulin.sh / emulin.bat) と README を入れる
#    5. emulin-dist-<version>.zip にまとめる
#
#  環境変数:
#    HOST_BB        rootfs/bin/busybox にコピーする busybox バイナリ
#                   (default: /usr/bin/busybox)
# --------------------------------------------------------------------
set -eu

HERE=$(cd "$(dirname "$0")" && pwd -P)
PROJECT=$(cd "$HERE/.." && pwd -P)
HOST_BB=${HOST_BB:-/usr/bin/busybox}

if ! command -v mvn >/dev/null 2>&1; then
    echo "build-dist: error: mvn not found on PATH" >&2
    exit 1
fi
if [ ! -f "$HOST_BB" ]; then
    echo "build-dist: error: busybox not found at $HOST_BB (set HOST_BB=...)" >&2
    exit 1
fi

# 1. fat jar
echo "[build-dist] mvn package..."
( cd "$PROJECT" && mvn -q package -DskipTests )
JAR=$(ls "$PROJECT"/target/emulin-*-all.jar | head -1)
if [ ! -f "$JAR" ]; then
    echo "build-dist: error: shaded jar not produced under target/" >&2
    exit 1
fi
VERSION=$(basename "$JAR" | sed 's/^emulin-//; s/-all\.jar$//')
echo "[build-dist] version=$VERSION jar=$JAR"

# 2. dist tree
DIST_NAME=emulin-$VERSION
DIST_DIR=$PROJECT/target/$DIST_NAME
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR/lib" \
         "$DIST_DIR/rootfs/bin" \
         "$DIST_DIR/rootfs/etc" \
         "$DIST_DIR/rootfs/mnt" \
         "$DIST_DIR/rootfs/tmp"
# issue #699: rootfs/mnt は Windows host の /mnt/<drive> auto-mount の親として必須
#   (bare /mnt の rootfs 実体が無いと component 単位の lstat (node realpath 等) が ENOENT)。

cp "$JAR"                          "$DIST_DIR/lib/"
cp "$HERE/launchers/emulin.sh"     "$DIST_DIR/"
cp "$HERE/launchers/emulin.bat"    "$DIST_DIR/"
cp "$HERE/README.txt"              "$DIST_DIR/"
cp "$HERE/NOTICE.txt"              "$DIST_DIR/"
cp "$PROJECT/COPYING"              "$DIST_DIR/" 2>/dev/null || true
chmod +x "$DIST_DIR/emulin.sh"

# 3. busybox + 空の emulin.cnf
cp "$HOST_BB" "$DIST_DIR/rootfs/bin/busybox"
chmod +x      "$DIST_DIR/rootfs/bin/busybox"
:           > "$DIST_DIR/rootfs/etc/emulin.cnf"

# 4. zip
ZIP=$PROJECT/target/emulin-dist-$VERSION.zip
rm -f "$ZIP"
( cd "$PROJECT/target" && zip -qr "$(basename "$ZIP")" "$DIST_NAME" )

echo "[build-dist] -> $ZIP"
ls -lh "$ZIP"
