#!/usr/bin/env bash
# --------------------------------------------------------------------
#  dist/build-release.sh
#
#  emulin release wrapper (version は pom.xml から取得)。Git for Windows 同等コマンド +
#  extras (vim / emacs / perl / python / tig / ssh / sshd) を全部
#  同梱した demo zip を、複数 platform 用に一括 build する。
#
#  使い方:
#    dist/build-release.sh                  # 全 platform (linux/windows/macos x64/arm64)
#    PLATFORMS="windows-x64" dist/build-release.sh   # 特定 platform のみ
#
#  生成物:
#    target/debian-emulin-<version>-<platform>.zip (各 platform、DEBIAN_BASE=0 なら emulin-demo-*)
#
#  仕組み:
#    1. Linux host で rootfs (sandbox) を 1 度だけ build (INCLUDE_* 全 on)
#    2. その rootfs を PREBUILT_ROOTFS として各 platform の
#       build-demo-bundle.sh に渡す (rootfs は OS 非依存の Linux ELF 群、
#       JRE のみ platform 別)
#
#  環境変数:
#    PLATFORMS         build 対象 (space 区切り)。default: 全 4 platform
#                      候補: linux-x64 windows-x64 macos-x64 macos-arm64
#    INCLUDE_EMACS     emacs-nox を含めるか (default: 1 = 含める)
#                      容量を抑えたいときは INCLUDE_EMACS=0 で除外可
#    EMULIN_JDK_CACHE  cross-compile 用 JDK の cache dir
# --------------------------------------------------------------------
set -eu

HERE=$(cd "$(dirname "$0")" && pwd -P)
PROJECT=$(cd "$HERE/.." && pwd -P)

PLATFORMS=${PLATFORMS:-"linux-x64 windows-x64 macos-x64 macos-arm64"}

# release は Git for Windows 同等 + extras を全部入れる。
# emacs は容量が大きい (+120 MB raw) ので env で除外可能にする。
export INCLUDE_VIM=1
export INCLUDE_EMACS=${INCLUDE_EMACS:-1}
export INCLUDE_SSH=1
export INCLUDE_SSHD=1
export INCLUDE_TIG=1
export INCLUDE_PERL=1
export INCLUDE_PYTHON=1
# issue #130 Tier 1: 開発 CLI tool 群
export INCLUDE_JQ=1
export INCLUDE_SQLITE=1
export INCLUDE_NANO=1
export INCLUDE_TREE=1
export INCLUDE_PATCH=1
export INCLUDE_ZIP=1
# issue #130 Tier 2: rsync + tmux + ripgrep/fd。tmux/rg/fd の emulator-core gap は
#   issue #131 で全て解消済 (rg/fd=statx#136+NULL probe#141、tmux=l14/15b/17)。
export INCLUDE_RSYNC=1
export INCLUDE_TMUX=1
export INCLUDE_RIPGREP=1
export INCLUDE_FD=1
export INCLUDE_MAKE=1

# issue #322: Debian base (docker debian:trixie 相当 78 package + apt/dpkg + status DB) を
#   土台にするか。既定 1 (build-demo-bundle.sh と同じ)。これを export しないと
#   下の shared rootfs build (build-sandbox.sh 直叩き) が DEBIAN_BASE=0 (busybox base) に
#   fallback し、bundle 名は debian-emulin-* なのに中身は apt/dpkg 不在の busybox という
#   不整合 bundle が出来てしまう (apt install 不能)。
export DEBIAN_BASE=${DEBIAN_BASE:-1}

VERSION=$(grep -m1 '<version>' "$PROJECT/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')

echo "=============================================================="
echo " Emulin $VERSION release build"
echo "   platforms: $PLATFORMS"
echo "   INCLUDE: VIM=$INCLUDE_VIM EMACS=$INCLUDE_EMACS SSH=$INCLUDE_SSH"
echo "            SSHD=$INCLUDE_SSHD TIG=$INCLUDE_TIG PERL=$INCLUDE_PERL"
echo "            TIER1(#130)=JQ/SQLITE/NANO/TREE/PATCH/ZIP"
echo "            TIER2(#130/#131)=RSYNC/TMUX/RIPGREP/FD"
echo "            PYTHON=$INCLUDE_PYTHON MAKE=$INCLUDE_MAKE TMUX=$INCLUDE_TMUX"
echo "=============================================================="

if ! command -v ldd >/dev/null 2>&1; then
    echo "build-release: error: Linux host required (ldd not found)" >&2
    exit 1
fi

# stale な古い version の fat jar を排除 (VERSION は jar 名から導出されるため)。
echo "[release] mvn clean (stale jar 排除)..."
( cd "$PROJECT" && mvn -q clean 2>&1 | tail -2 ) || true

# project が /mnt/c (NTFS) 上にある場合、case-colliding file (terminfo
# A/a 等) を staging できないので Linux fs を staging に使う。EMULIN_STAGE_DIR
# が未指定なら、project が NTFS 上のときだけ /tmp staging を自動採用。
if [ -z "${EMULIN_STAGE_DIR:-}" ]; then
    case "$PROJECT" in
        /mnt/*) export EMULIN_STAGE_DIR=$(mktemp -d -t emulin-stage.XXXXXX)
                echo "[release] project が $PROJECT (NTFS) なので staging を $EMULIN_STAGE_DIR に設定" ;;
    esac
fi

# --- 1. rootfs を 1 度だけ build (全 platform で共用) ---
ROOTFS_SHARED=$(mktemp -d -t emulin-release-rootfs.XXXXXX)
cleanup_release() {
    rm -rf "$ROOTFS_SHARED" 2>/dev/null || true
    [ -n "${EMULIN_STAGE_DIR:-}" ] && [ "${EMULIN_STAGE_DIR:-}" != "$PROJECT/target" ] \
        && rm -rf "$EMULIN_STAGE_DIR" 2>/dev/null || true
}
trap cleanup_release EXIT
echo ""
# issue #322: DEBIAN_BASE=1 のときは build-sandbox の前に Debian base を展開する
#   (build-demo-bundle.sh と同じ手順)。build-demo-bundle は PREBUILT_ROOTFS を
#   渡されると base 展開を skip するので、shared rootfs では release 側で base を
#   敷く必要がある。これを怠ると apt/dpkg 不在の busybox bundle が出来る。
if [ "$DEBIAN_BASE" = 1 ]; then
    echo "[release] Debian base (docker debian:trixie 相当) を shared rootfs に展開中..."
    DEB_CACHE="${EMULIN_DEB_CACHE:-$HOME/.cache/emulin/debian-base-debs}" \
        "$HERE/build-debian-base.sh" "$ROOTFS_SHARED" > /dev/null
fi
echo "[release] shared rootfs を構築中 (INCLUDE_* 全 on)..."
"$HERE/build-sandbox.sh" "$ROOTFS_SHARED" full > /dev/null
echo "[release] rootfs 構築完了: $(du -sh "$ROOTFS_SHARED" | awk '{print $1}')"

# --- 2. 各 platform で zip build ---
BUILT=()
for plat in $PLATFORMS; do
    echo ""
    echo "[release] ===== building $plat ====="
    # build-demo-bundle.sh は TARGET_PLATFORM で cross-compile JRE を取得し、
    # PREBUILT_ROOTFS で共用 rootfs を使う。
    PREBUILT_ROOTFS="$ROOTFS_SHARED" TARGET_PLATFORM="$plat" \
        "$HERE/build-demo-bundle.sh"
    # 生成 zip 名は platform 名 (= linux/windows/macos) に正規化される
    case "$plat" in
        linux-*)  pname=linux ;;
        windows-*) pname=windows ;;
        macos-*)  pname=macos ;;
        *)        pname=$plat ;;
    esac
    # build-demo-bundle.sh は DEBIAN_BASE=1 (既定) で debian-emulin-*、=0 で emulin-demo-* を
    # 出力する (#323)。実際に出来た zip を glob で拾い、prefix を保ったまま $plat 名へ rename
    # する (macos-x64 / macos-arm64 は正規化名 macos が衝突するため $plat で区別)。
    ZIP=$(ls "$PROJECT"/target/*emulin*-"$VERSION"-"$pname".zip 2>/dev/null | head -1)
    if [ -n "$ZIP" ] && [ -f "$ZIP" ]; then
        base=$(basename "$ZIP")
        prefix=${base%-"$VERSION"-"$pname".zip}      # debian-emulin or emulin-demo
        FINAL="$PROJECT/target/$prefix-$VERSION-$plat.zip"
        if [ "$ZIP" != "$FINAL" ]; then
            mv "$ZIP" "$FINAL"
        fi
        BUILT+=("$FINAL")
        echo "[release] -> $(basename "$FINAL") ($(du -h "$FINAL" | awk '{print $1}'))"
    else
        echo "[release] warn: $plat の zip が生成されなかった (target/*emulin*-$VERSION-$pname.zip)" >&2
    fi
done

echo ""
echo "=============================================================="
echo " Release build 完了: ${#BUILT[@]} zip"
for z in "${BUILT[@]}"; do
    echo "   $(basename "$z")  $(du -h "$z" | awk '{print $1}')"
done
echo "=============================================================="
