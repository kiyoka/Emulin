# deb-install-lib.sh — issue #361: .deb を rootfs へ「package-managed」で導入する共通関数
#
#   build-debian-base.sh と build-sandbox.sh が source して使う。root / chroot 不要で
#   .deb の data.tar を rootfs に展開し、control から /var/lib/dpkg/status の stanza を
#   合成して「install ok installed」状態にする (= dpkg/apt が「導入済み」と認識する)。
#   maintainer script は実行しない (init を持たない emulin の方針)。
#
#   使い方:
#     . "$HERE/deb-install-lib.sh"
#     deb_install_one  <rootfs> <deb>      # 1 つの .deb を導入
#     deb_install_dir  <rootfs> <debdir>   # <debdir>/*.deb を全部導入
#
#   前提: dpkg-deb / awk / tar / sed (Debian host に標準)。rootfs に var/lib/dpkg/{status,info}
#   が無ければ自動で作る (空 status から始められる)。

# 1 つの .deb を package-managed で rootfs へ展開する。
deb_install_one() {
  local RF=$1 deb=$2
  [ -f "$deb" ] || { echo "deb_install_one: not found: $deb" >&2; return 1; }
  mkdir -p "$RF/var/lib/dpkg/info"
  [ -f "$RF/var/lib/dpkg/status" ] || : > "$RF/var/lib/dpkg/status"
  local CTL; CTL=$(mktemp -d -t emulin-debinst-ctl.XXXXXX)
  dpkg-deb -x "$deb" "$RF"          # data.tar → rootfs
  dpkg-deb -e "$deb" "$CTL"         # control.tar → CTL
  local pkg; pkg=$(awk -F': ' '/^Package:/{print $2; exit}' "$CTL/control")
  # 既に status に居る (同名 package 導入済み) なら data だけ上書きし stanza 重複は避ける。
  if grep -q "^Package: ${pkg}\$" "$RF/var/lib/dpkg/status" 2>/dev/null; then
    rm -rf "$CTL"; return 0
  fi
  # status stanza = control の Package 直後に "Status: install ok installed" を挿入。
  awk '1; /^Package:/ && !d {print "Status: install ok installed"; d=1}' "$CTL/control" > "$CTL/.stanza"
  # Conffiles section (conffiles + md5sums があれば、apt の conffile 認識用)。
  if [ -f "$CTL/conffiles" ]; then
    echo "Conffiles:" >> "$CTL/.stanza"
    while IFS= read -r cf; do
      [ -z "$cf" ] && continue
      local md5; md5=$(awk -v p="${cf#/}" '$2==p{print $1; exit}' "$CTL/md5sums" 2>/dev/null || true)
      echo " $cf ${md5:-0000000000000000}" >> "$CTL/.stanza"
    done < "$CTL/conffiles"
  fi
  printf '\n' >> "$CTL/.stanza"
  cat "$CTL/.stanza" >> "$RF/var/lib/dpkg/status"
  # info/<pkg>.list (ファイル一覧) + control 系。native arch (amd64/all) は arch suffix 無し。
  #   dpkg .list 表記に正規化: root=`/.`、dir は trailing slash 無し、絶対パス。
  local key="$pkg"
  dpkg-deb --fsys-tarfile "$deb" | tar -tf - 2>/dev/null \
    | sed -e 's#^\./#/#' -e 's#^\.$#/.#' -e 's#/$##' -e 's#^$#/.#' > "$RF/var/lib/dpkg/info/$key.list"
  local f
  for f in md5sums conffiles postinst preinst postrm prerm triggers shlibs symbols config; do
    [ -f "$CTL/$f" ] && cp "$CTL/$f" "$RF/var/lib/dpkg/info/$key.$f"
  done
  rm -rf "$CTL"
}

# <debdir>/*.deb を全部 package-managed で導入する。
deb_install_dir() {
  local RF=$1 dir=$2 deb
  for deb in "$dir"/*.deb; do
    [ -f "$deb" ] || continue
    deb_install_one "$RF" "$deb"
  done
}
