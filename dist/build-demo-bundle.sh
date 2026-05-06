#!/usr/bin/env bash
# --------------------------------------------------------------------
#  dist/build-demo-bundle.sh
#
#  実機 binary 同梱 demo zip を作る。
#  build-jre-bundle.sh + full sandbox (git/curl/openssl 等) を組み合わせて、
#  解凍直後から git clone HTTPS 等が動く即席 demo 配布版。
#
#  使い方:
#    dist/build-demo-bundle.sh
#
#  生成物:
#    target/emulin-demo-<version>-<platform>.zip
#
#  注意:
#    Debian/Ubuntu host (glibc 2.39 系) でビルドしたバイナリ・library が
#    そのまま同梱される。互換性は host と同じ系統の Linux でのみ保証。
# --------------------------------------------------------------------
set -eu

HERE=$(cd "$(dirname "$0")" && pwd -P)
PROJECT=$(cd "$HERE/.." && pwd -P)

if ! command -v jlink >/dev/null 2>&1; then
    echo "build-demo-bundle: error: jlink not found (need JDK 11+)" >&2
    exit 1
fi
if ! command -v ldd >/dev/null 2>&1; then
    echo "build-demo-bundle: error: ldd not found (Linux host required)" >&2
    exit 1
fi

# 1. fat jar
echo "[build-demo] mvn package..."
( cd "$PROJECT" && mvn -q package -DskipTests )
JAR=$(ls "$PROJECT"/target/emulin-*-all.jar | head -1)
VERSION=$(basename "$JAR" | sed 's/^emulin-//; s/-all\.jar$//')

# 2. platform
case "$(uname -s)" in
    Linux*)  PLATFORM=linux ;;
    *) echo "build-demo: error: 現在 Linux host のみサポート" >&2; exit 1 ;;
esac

DIST_NAME=emulin-demo-$VERSION-$PLATFORM
DIST_DIR=$PROJECT/target/$DIST_NAME
rm -rf "$DIST_DIR"

# 3. jlink JRE
mkdir -p "$DIST_DIR"
echo "[build-demo] jlink → $DIST_DIR/jre ..."
jlink \
    --add-modules java.base,java.logging \
    --output "$DIST_DIR/jre" \
    --no-header-files --no-man-pages --strip-debug --compress=zip-6

# 4. fat jar + scripts
mkdir -p "$DIST_DIR/lib"
cp "$JAR"                   "$DIST_DIR/lib/"
cp "$HERE/README.txt"       "$DIST_DIR/"
cp "$HERE/build-sandbox.sh" "$DIST_DIR/"

# 5. full sandbox を直接 build (rootfs は demo の中)
ROOTFS=$DIST_DIR/rootfs
echo "[build-demo] sandbox (full) を構築中..."
"$HERE/build-sandbox.sh" "$ROOTFS" full > /dev/null

# 6. 専用 launcher (bundled JRE + bundled rootfs)
cat > "$DIST_DIR/emulin.sh" <<'EOF'
#!/usr/bin/env bash
# Emulin demo bundle launcher (bundled JRE + full sandbox)
set -u
HERE=$(cd "$(dirname "$0")" && pwd -P)
JAVA=$HERE/jre/bin/java
JAR=$(ls "$HERE"/lib/emulin-*-all.jar 2>/dev/null | head -1)
ROOTFS=$HERE/rootfs

if [ ! -x "$JAVA" ]; then
    echo "emulin.sh: error: bundled JRE not at $JAVA" >&2
    exit 2
fi
if [ -z "${JAR:-}" ] || [ ! -f "$JAR" ]; then
    echo "emulin.sh: error: lib/emulin-*-all.jar not found" >&2
    exit 2
fi
if [ ! -d "$ROOTFS" ]; then
    echo "emulin.sh: error: $ROOTFS not found" >&2
    exit 2
fi

JVM_OPTS=( -XX:-DontCompileHugeMethods )
cd "$ROOTFS"
if [ $# -eq 0 ]; then
    exec "$JAVA" "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" -CJ /bin/busybox ash -i
else
    # 第 1 引数が emulin native binary path なら直接、そうでなければ busybox 経由
    if [ -e "$ROOTFS/$1" ] || [ -e "$ROOTFS/usr/bin/$1" ] || [ -e "$ROOTFS/bin/$1" ]; then
        exec "$JAVA" "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" "$@"
    else
        exec "$JAVA" "${JVM_OPTS[@]}" -jar "$JAR" "$ROOTFS" -CJ /bin/busybox "$@"
    fi
fi
EOF
chmod +x "$DIST_DIR/emulin.sh"

# 7. demo 用 README 追記
cat > "$DIST_DIR/QUICKSTART.txt" <<EOF
Emulin Demo Bundle (Linux x86-64, glibc 2.39 系)
========================================================

このパッケージは、実機 Linux binary 同梱の即席 demo です。
Java も別 sandbox も用意せず、解凍してすぐ動かせます。

クイックスタート:

  ./emulin.sh                                       # busybox ash 対話シェル
  ./emulin.sh /usr/bin/git --version                # 実機 git
  ./emulin.sh /usr/bin/openssl version              # 実機 openssl
  ./emulin.sh /usr/bin/git clone --depth=1 \\
      https://github.com/octocat/Hello-World.git /tmp/cloned   # HTTPS clone

動作する binary (rootfs/usr/bin):
  git, curl, openssl, python3, wget, ...

詳細は README.txt 参照。
EOF

# 8. zip
cd "$PROJECT/target"
ZIP=$DIST_NAME.zip
rm -f "$ZIP"
echo "[build-demo] zipping..."
zip -qr "$ZIP" "$DIST_NAME"
SIZE=$(du -sh "$ZIP" | awk '{print $1}')
RAW=$(du -sh "$DIST_DIR" | awk '{print $1}')
echo "[build-demo] $PROJECT/target/$ZIP ($SIZE compressed, $RAW raw)"
