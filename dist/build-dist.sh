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
# ★ issue #929: **pom.xml の版と一致する jar だけ**を採る。
#   旧実装は `ls target/emulin-*-all.jar | head -1` で、版を上げた直後 (mvn clean を
#   していない target に旧版の jar が残っている状態) だと **古い版を掴んで出荷**していた。
#   実際 0.8.2 へ上げた直後の dist-smoke が `zip=emulin-dist-0.8.1.zip` を作っていた。
#   「ビルドしたものと出荷するものが違う」= #919 と同じ型なので、名前で当てず版で照合する。
VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$PROJECT/pom.xml" | head -1)
if [ -z "$VERSION" ]; then
    echo "build-dist: error: could not read <version> from pom.xml" >&2
    exit 1
fi
JAR="$PROJECT/target/emulin-$VERSION-all.jar"
if [ ! -f "$JAR" ]; then
    echo "build-dist: error: shaded jar for version $VERSION not found: $JAR" >&2
    echo "            (run 'mvn clean package -DskipTests' first)" >&2
    exit 1
fi
# 旧版の jar が残っていると混乱の元なので知らせる (出荷物には影響しない)。
OTHER=$(ls "$PROJECT"/target/emulin-*-all.jar 2>/dev/null | grep -v "^$JAR$" || true)
if [ -n "$OTHER" ]; then
    echo "build-dist: note: other versions are left in target/ (ignored):" >&2
    echo "$OTHER" | sed 's/^/            /' >&2
fi
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
# ★ .bat は cmd.exe が **OEM code page** (日本語 Windows なら CP932) で読む。UTF-8 の
#   多バイト文字を入れると復号がずれて行末 (CRLF) まで食われ、**コメントの続きが
#   コマンドとして実行される**。実測: この zip の `emulin.bat sshd` は
#   「'続すれ…端末ぁECtrl+Space' は…認識されていません」を出していた (#219 の日本語 rem)。
#   ★ 同じゲートが build-demo-bundle.sh にもある。**launcher は 2 系統あるので、片方だけ
#     検査すると出荷側が素通りする** (#919 で実際に踏んだ形)。
if [ -n "$(LC_ALL=C tr -d '\000-\177' < "$DIST_DIR/emulin.bat")" ]; then
    echo "build-dist: error: emulin.bat contains non-ASCII bytes (cmd.exe reads .bat in the OEM code page)" >&2
    LC_ALL=C awk 'BEGIN{FS=""} {for(i=1;i<=NF;i++) if ($i !~ /^[ -~\t\r]$/) { print FILENAME": "NR": "$0; next }}' "$DIST_DIR/emulin.bat" >&2
    exit 1
fi
# issue #891: 同梱ドキュメントは QUICKSTART.txt だけ (詳細は GitHub の README.md)。
#   この zip は emulin.bat / emulin.sh の両方を入れる platform 非依存版なので any。
"$HERE/gen-quickstart.sh" "$DIST_DIR/QUICKSTART.txt" minimal any
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
