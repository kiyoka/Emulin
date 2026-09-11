#!/usr/bin/env bash
# --------------------------------------------------------------------
#  dist/mandb-autoupdate-off.sh — rootfs の man データベース自動再構築を止める
#
#  usage: mandb-autoupdate-off.sh <rootfs>
#
#  issue #1031: guest で `apt install` すると man-db が **man データベースの全再構築**
#  (`mandb -cq`) を走らせ、実機 (Windows + WHP、rootfs は C: 上、man ページ 3,661 本) で
#  **40 分以上終わらなかった**。しかも dpkg は設定中の SIGINT を無視するので Ctrl-C で
#  止められない。実 Linux なら数十秒で終わる処理が、エミュレートされた I/O と
#  Windows のファイルシステム (#495) の組み合わせで最悪ケースになる。
#
#  ★ 効かせ方: man-db の postinst / トリガは **`/var/lib/man-db/auto-update` の有無**だけを
#    見て動作を決める (`run_mandb()` の先頭)。そのファイルを作るか消すかは debconf の
#    `man-db/auto-update` (既定 true) で決まる。→ **debconf を false にしておけば、
#    初回インストールの全再構築も、以後のトリガによる更新も、どちらも走らない**。
#
#  ★ rootfs の build は guest を一切動かさない (deb を展開して status を合成する方式) ので、
#    `debconf-set-selections` は使えない。**config.dat の stanza を直接置く**。
#    これは preseed と同じ形で、man-db が後から入るときに値として読まれる。
#
#  ★ man ページ自体は普通に入るので `man <cmd>` は使える (index が無いぶん初回が遅い)。
#    欲しい人は guest で `mandb` を 1 回走らせればよい。
#
#  ★ **設定はここ 1 箇所に集める**。build 側と検査側が別々に同じことを書くと、
#    片方だけ直る形になる (#919 で踏んだ型)。検査は必ずこのスクリプトを通すこと。
# --------------------------------------------------------------------
set -u

RF=${1:?usage: mandb-autoupdate-off.sh <rootfs>}
[ -d "$RF" ] || { echo "mandb-autoupdate-off: not a directory: $RF" >&2; exit 1; }

DB=$RF/var/cache/debconf/config.dat
mkdir -p "$(dirname "$DB")"
[ -f "$DB" ] || : > "$DB"

# stanza は空行区切り。既にあれば Value/Flags を差し替え、無ければ末尾に足す。
awk '
  BEGIN { RS=""; ORS="\n\n"; found=0 }
  {
    if ($0 ~ /(^|\n)Name: man-db\/auto-update(\n|$)/) {
      found=1
      n=split($0, L, "\n"); out=""; owners=""
      for (i=1; i<=n; i++) {
        if (L[i] ~ /^Value:/ || L[i] ~ /^Flags:/) continue     # 自分で書き直す
        if (L[i] ~ /^Owners:/) owners=L[i]
        if (L[i] == "") continue
        out = (out == "" ? L[i] : out "\n" L[i])
      }
      if (owners == "") out = out "\nOwners: man-db"
      print out "\nValue: false\nFlags: seen"
      next
    }
    print
  }
  END {
    if (!found)
      print "Name: man-db/auto-update\nTemplate: man-db/auto-update\nValue: false\nOwners: man-db\nFlags: seen"
  }
' "$DB" > "$DB.emulin-new" && mv "$DB.emulin-new" "$DB"

# 既に man-db が入っている rootfs 向け: フラグファイル自体も落とす
#   (postinst を通らずに deb を展開した rootfs では、これが残っていることがある)
rm -f "$RF/var/lib/man-db/auto-update"

echo "[mandb] man-db/auto-update=false を設定 (issue #1031: apt が man DB 全再構築で止まらないように)"
