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

# issue #361: <pkg...> + (rootfs に無い) 依存閉包を apt download し package-managed で導入する。
#   rootfs の dpkg status を「導入済み集合」として解決するので、base に既にある依存は再取得しない。
#   成功 (1 つ以上の .deb を登録) で 0、rootfs に dpkg DB が無い / apt-get 不在 / download 失敗で 1。
#   1 のとき呼び出し側は従来の copy_with_deps 等へ fallback する。
#   usage: deb_bundle_closure <rootfs> <debdir> <pkg...>
deb_bundle_closure() {
  local RF=$1 dir=$2; shift 2
  [ -s "$RF/var/lib/dpkg/status" ] || return 1
  command -v apt-get >/dev/null 2>&1 || return 1
  mkdir -p "$dir/partial"
  apt-get install -y --no-install-recommends --download-only \
      -o Dir::State::status="$RF/var/lib/dpkg/status" \
      -o Dir::Cache::archives="$dir" \
      -o Dir::Cache::archives::partial="$dir/partial" \
      "$@" >/dev/null 2>&1 || true
  if ls "$dir"/*.deb >/dev/null 2>&1; then
    deb_install_dir "$RF" "$dir"
    return 0
  fi
  # download すべき .deb が無い = 要求 package が既に rootfs に導入済み (他ツールの依存閉包で
  #   先に入った等)。その場合も既に package-managed なので、要求 package が全て status に居れば
  #   success とする (冗長な copy fallback を避ける)。
  local p
  for p in "$@"; do
    dpkg-query --admindir="$RF/var/lib/dpkg" -W "$p" >/dev/null 2>&1 || return 1
  done
  return 0
}

# issue #867: rootfs の dpkg status DB に **未解決の依存が無いか**を検査し、あれば
#   その package を package-managed で足して直す。直らなければ失敗 (1) を返す。
#
#   ★ なぜ要るか: status DB に「入っている」と書かれた package の依存が DB に無いと、
#     guest の apt は **あらゆる install を拒否する** (apt-get install hello ですら
#     "Unmet dependencies ... p11-kit : Depends: libtasn1-6" で止まる)。
#     実際 0.8.0 の bundle がこの状態で出かかった (libtasn1-6 がファイルだけ入り
#     status に未登録だった)。ldd ベースのコピーと package 管理が混在すると起こる。
#   ★ 検査自体はネットワーク不要 (apt-get check は status DB だけ見る)。
#     修復には package list が新しい必要があるので、失敗したら **build を止めて**
#     `sudo apt-get update` を促す。黙って壊れた bundle を出さないことが目的。
#
#   usage: deb_verify_deps <rootfs> <debdir>
deb_verify_deps() {
  local RF=$1 dir=$2 out missing
  command -v apt-get >/dev/null 2>&1 || return 0
  [ -s "$RF/var/lib/dpkg/status" ] || return 0
  if out=$( LC_ALL=C apt-get check -o Dir::State::status="$RF/var/lib/dpkg/status" 2>&1 ); then
    return 0
  fi
  missing=$( printf '%s\n' "$out" | awk '/: Depends: /{print $4}' | sort -u | tr '\n' ' ' )
  if [ -n "${missing// /}" ]; then
    echo "[deb] status DB に未解決依存: $missing -> package-managed で追加を試みる"
    deb_bundle_closure "$RF" "$dir" $missing >/dev/null 2>&1 || true
    if LC_ALL=C apt-get check -o Dir::State::status="$RF/var/lib/dpkg/status" >/dev/null 2>&1; then
      echo "[deb] 解消した"
      return 0
    fi
  fi
  echo "ERROR: rootfs の dpkg status に未解決の依存が残っている。" >&2
  echo "       この bundle では guest の apt install が **全部** 失敗する。" >&2
  LC_ALL=C apt-get check -o Dir::State::status="$RF/var/lib/dpkg/status" 2>&1 | sed 's/^/       /' >&2
  echo "       (package list が古いと修復 download が 404 になる。sudo apt-get update を試すこと)" >&2
  return 1
}
