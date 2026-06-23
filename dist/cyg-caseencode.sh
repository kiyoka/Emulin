#!/usr/bin/env bash
# --------------------------------------------------------------------
#  cyg-caseencode.sh <rootfs>   (issue #369)
#
#  Windows bundle 用: rootfs 内の「大小文字違いで同名」の file leaf を、Windows の `tar -xzf` (bsdtar) が
#  NTFS (case-insensitive) へ衝突なく展開できる形に staging する。
#
#  ★方式 C (2026-06-23): bsdtar は U+F000–F0FF (PUA) のファイル名を system ANSI codepage で表現できず
#    作れない (実機検証: "Invalid empty pathname")。そこで衝突 leaf を **PUA 名で tar に入れず**、
#    ASCII-safe な payload (`.emulin-casemap.d/NNNN`) へ退避し、元 path を manifest (`.emulin-casemap`) に
#    記録する。bsdtar は ASCII payload + manifest を問題なく展開でき、emulin が初回起動時
#    (Mount.set_root → WinCaseMap.bootstrapFromPayload) に payload から **PUA 名の本体を Java NIO で生成**
#    する (NIO は NTFS に PUA file を作れる = #349/#342 で実績)。以降は read 経路 lazy scan
#    (marker `.emulin-casemap` で有効化) が PUA 名を元名に decode して見せるので lossless
#    (dpkg -L/-V も元名で整合)。
#
#  cyg-symlinkify.sh (symlink → Cygwin マジックファイル化) の後、tar czf rootfs.tar.gz の直前に呼ぶこと
#  (symlink は magic file=regular になっているので衝突 leaf として一様に扱える)。
#
#  対象は file leaf のみ。dir 名の case 衝突 (perl Sys/sys 等) は NTFS でマージされ中身が共存する benign な
#  ケースなので対象外 (dir を encode すると配下 path が全てずれて複雑化する)。
#
#  非衝突 (0 件) なら payload/manifest を作らず no-op (= 通常 bundle は read lazy scan 無効=無コスト)。
#  終了コード: 常に 0 (best-effort、python3 必須)。
# --------------------------------------------------------------------
set -eu

ROOT="${1:?usage: cyg-caseencode.sh <rootfs>}"
[ -d "$ROOT" ] || { echo "[caseencode] skip: '$ROOT' is not a dir" >&2; exit 0; }
command -v python3 >/dev/null 2>&1 || { echo "[caseencode] skip: python3 not found" >&2; exit 0; }

# best-effort = python は常に exit 0 (1 件の move 失敗や想定外でも build を落とさない)。
python3 - "$ROOT" <<'PY'
import os, sys
root = sys.argv[1]
payload_dir = os.path.join(root, '.emulin-casemap.d')   # ASCII payload (bsdtar 展開可)
manifest    = os.path.join(root, '.emulin-casemap')      # <id>\t<元 rel-path>。存在自体が read lazy scan の gate

n = 0
lines = []
try:
    for dirpath, dirnames, filenames in os.walk(root):
        if os.path.basename(dirpath) == '.emulin-casemap.d':   # 自分の payload dir は走査しない
            dirnames[:] = []
            continue
        # 同一 dir 内の file leaf を lowercase-fold して衝突検出。1 件目 (sorted 先頭) は plain で
        #   case-fold slot を所有させ、2 件目以降を payload へ退避する。
        seen = {}
        for name in sorted(filenames):
            low = name.lower()
            if low in seen:
                src = os.path.join(dirpath, name)
                rel = os.path.relpath(src, root)               # 元 path (元 leaf 込み、'/' 区切り)
                pid = "%04d" % (n + 1)
                try:
                    if not os.path.isdir(payload_dir):
                        os.makedirs(payload_dir)
                    os.rename(src, os.path.join(payload_dir, pid))   # ASCII payload 名へ退避
                    lines.append("%s\t%s" % (pid, rel))
                    n += 1
                except OSError as e:                            # 1 件の失敗で build 全体を落とさない
                    sys.stderr.write("  warn: stage skip: %s (%s)\n" % (name, e))
            else:
                seen[low] = name
    if lines:
        # marker 兼 manifest: emulin (Mount.set_root) が検出して bootstrap + read lazy scan を有効化。
        try:
            with open(manifest, 'w') as f:
                f.write('\n'.join(lines) + '\n')
        except OSError as e:
            sys.stderr.write("  warn: manifest 生成失敗: %s\n" % e)
except Exception as e:                                          # 想定外でも非 fatal (常に exit 0)
    sys.stderr.write("[caseencode] warn: 中断 (non-fatal): %s\n" % e)

sys.stderr.write("[caseencode] staged %d colliding file(s) as ASCII payload%s\n"
                 % (n, " (.emulin-casemap.d/ + manifest .emulin-casemap)" if lines else ""))
for l in lines[:20]:
    sys.stderr.write("  staged: %s\n" % l)
PY
