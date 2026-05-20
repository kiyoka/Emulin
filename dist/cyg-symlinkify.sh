#!/usr/bin/env bash
# --------------------------------------------------------------------
#  dist/cyg-symlinkify.sh <rootfs_dir>
#
#  issue #68 Phase 3: rootfs 内の全 POSIX symlink を Cygwin 式の
#  「マジック regular file (!<symlink> cookie)」に変換する。
#
#  目的:
#    - Windows native 配布で、symlink を含まない rootfs にする
#    - tar.exe 展開時に symlink を作らない → admin/Developer Mode 不要
#    - emulin は Windows host (= CygSymlink.enabled()) で namei 解決して
#      マジックファイルを symlink として追従する
#
#  マジックファイル形式 (emulin.CygSymlink と一致させること):
#    "!<symlink>" (10 byte ASCII) + 0xFF 0xFE (UTF-16LE BOM)
#    + target を UTF-16LE + 0x00 0x00 (UTF-16 NUL)
#
#  broken symlink (target 不在) も変換する。emulin は access 時に
#  ENOENT を返す (= POSIX の dangling symlink semantics と一致)。
# --------------------------------------------------------------------
set -u

ROOTFS=${1:?usage: cyg-symlinkify.sh <rootfs_dir>}
if [ ! -d "$ROOTFS" ]; then
    echo "cyg-symlinkify: error: $ROOTFS is not a directory" >&2
    exit 1
fi
if ! command -v iconv >/dev/null 2>&1; then
    echo "cyg-symlinkify: error: iconv not found (UTF-16LE 変換に必要)" >&2
    exit 1
fi

# 1 個の symlink をマジックファイルに変換する。
write_magic() {
    local file=$1 target=$2
    {
        printf '!<symlink>'                       # cookie (10 byte)
        printf '\377\376'                          # UTF-16LE BOM
        printf '%s' "$target" | iconv -f UTF-8 -t UTF-16LE
        printf '\000\000'                          # UTF-16 NUL 終端
    } > "$file"
}

count=0
# symlink の path を先に集める (-print0 で space 安全)。main shell で
# 処理するため process substitution を使う (関数を参照できる)。
while IFS= read -r -d '' link; do
    target=$(readlink "$link")
    rm -f "$link"
    write_magic "$link" "$target"
    count=$((count + 1))
done < <(find "$ROOTFS" -type l -print0)

echo "cyg-symlinkify: $count symlink → マジックファイルに変換 ($ROOTFS)"
