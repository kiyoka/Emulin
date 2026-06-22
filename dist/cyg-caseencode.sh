#!/usr/bin/env bash
# --------------------------------------------------------------------
#  cyg-caseencode.sh <rootfs>   (issue #369)
#
#  Windows bundle 用: rootfs 内の「大小文字違いで同名」の file leaf を、emulin の WinCaseMap と同じ方式
#  (ASCII letter [A-Za-z] → private-use area U+F000+byte) で pre-encode し、Windows の `tar -xzf` が NTFS
#  (case-insensitive) へ衝突なく展開できるようにする。emulin は WinCaseMap の read 経路 lazy scan
#  (rootfs 直下 marker `.emulin-casemap` で有効化) で encode 名を元名に decode して見せるので lossless
#  (dpkg -L/-V も元名で整合)。
#
#  cyg-symlinkify.sh (symlink → Cygwin マジックファイル化) の後、tar czf rootfs.tar.gz の直前に呼ぶこと
#  (symlink は magic file=regular になっているので衝突 leaf として一様に扱える)。
#
#  対象は file leaf のみ。dir 名の case 衝突 (perl Sys/sys 等) は NTFS でマージされ中身が共存する benign な
#  ケースなので encode しない (dir を encode すると配下 path が全てずれて複雑化する)。
#
#  非衝突 (encode 0 件) なら marker を作らず no-op (= 通常 bundle は read lazy scan 無効=無コスト)。
#  終了コード: 常に 0 (best-effort、python3 必須)。
# --------------------------------------------------------------------
set -eu

ROOT="${1:?usage: cyg-caseencode.sh <rootfs>}"
[ -d "$ROOT" ] || { echo "[caseencode] skip: '$ROOT' is not a dir" >&2; exit 0; }
command -v python3 >/dev/null 2>&1 || { echo "[caseencode] skip: python3 not found" >&2; exit 0; }

# review #2: best-effort = python は常に exit 0 (1 件の rename 失敗や想定外でも build を落とさない)。
#   top-level try/except + per-file try/except で uncaught exception を出さない (set -e の caller を abort させない)。
python3 - "$ROOT" <<'PY'
import os, sys
root = sys.argv[1]

# ★review #6: PUA scheme は WinCaseMap.encodeCase/isEncodedChar (Java) との二重定義。
#   ASCII letter [A-Za-z] のみ U+(F000+ord)、他は不変。Java 側を変えたら本関数も必ず追従すること
#   (食い違うと build 時 on-disk encode 名と runtime decode がズレ、encode file が永久に見えなくなる)。
def enc(name):
    return ''.join(chr(0xF000 + ord(c)) if ('A' <= c <= 'Z' or 'a' <= c <= 'z') else c for c in name)

nenc = 0
pairs = []
try:
    for dirpath, dirnames, filenames in os.walk(root):
        # 同一 (source) dir 内の file leaf を lowercase-fold して衝突を検出。
        #   1 件目 (sorted 先頭) は plain で case-fold slot を所有させ、2 件目以降を encode する。
        seen = {}
        for name in sorted(filenames):
            low = name.lower()
            if low in seen:
                src = os.path.join(dirpath, name)
                dst = os.path.join(dirpath, enc(name))
                if os.path.lexists(dst):   # 既に encode 名が在る (二重実行等) → skip
                    continue
                try:
                    os.rename(src, dst)    # symlink も link 自体を rename (lexists/rename は follow しない)
                    nenc += 1
                    pairs.append((os.path.relpath(src, root), seen[low]))
                except OSError as e:       # review #2: 1 件の rename 失敗で build 全体を落とさない
                    sys.stderr.write("  warn: rename skip: %s (%s)\n" % (name, e))
            else:
                seen[low] = name
    if nenc > 0:
        # marker: emulin (Mount.set_root) がこれを検出して WinCaseMap の read 経路 lazy scan を有効化する。
        try:
            open(os.path.join(root, '.emulin-casemap'), 'w').close()
        except OSError as e:
            sys.stderr.write("  warn: marker 生成失敗: %s\n" % e)
except Exception as e:                      # review #2: 想定外でも非 fatal (常に exit 0)
    sys.stderr.write("[caseencode] warn: 中断 (non-fatal): %s\n" % e)

sys.stderr.write("[caseencode] pre-encoded %d colliding file leaf(s)%s\n"
                 % (nenc, " (marker .emulin-casemap created)" if nenc else ""))
for enc_rel, keep in pairs[:20]:
    sys.stderr.write("  encoded: %s  (kept plain: %s)\n" % (enc_rel, keep))
PY
