#!/usr/bin/env bash
# build-debian-base.sh — issue #322 (0.6.0): Debian base image 相当の rootfs を生成
#
# demo/release bundle の土台を「debian:trixie の Docker base イメージ相当」にするための
# rootfs を、root 不要 (debootstrap/docker 不要) で組み立てる。
#
#   方式: docker base の package 集合 (= dpkg-query -W 出力 = 依存閉包済みの完全集合) を
#     dist/debian-base-trixie.manifest から読み、apt-get download で .deb を取得 →
#     dpkg-deb -x で data 展開 → 各 .deb の control から /var/lib/dpkg/status を合成して
#     「installed ok」状態にする。これで bundle 内で dpkg/apt が base を「導入済み」と認識し、
#     その上に apt-get install で追加導入できる (issue #322 マイルストーン3)。
#
#   ★ デーモンは起動しない: init/systemd は持たない。base に含まれる daemon 系 (この base には
#     無いが将来 apt で入れた cron/sshd 等) も emulin は boot 時に自動起動しない (user が明示起動)。
#
#   前提ツール (host、いずれも Debian trixie に標準): apt-get / dpkg-deb。ldconfig は任意 (あれば
#     ld.so.cache 生成)。★ host が Debian trixie であること (apt-get download が現行 trixie 版を取得)。
#
#   使い方:
#     dist/build-debian-base.sh <out-rootfs-dir> [manifest]
#   既定 manifest = dist/debian-base-trixie.manifest。出力 dir に rootfs を生成 (既存は消す)。
set -eu

HERE=$(cd "$(dirname "$0")" && pwd -P)
OUT=${1:?"usage: build-debian-base.sh <out-rootfs-dir> [manifest]"}
MANIFEST=${2:-$HERE/debian-base-trixie.manifest}
# issue #361: .deb を package-managed で rootfs へ導入する共通関数 (build-sandbox.sh と共有)。
. "$HERE/deb-install-lib.sh"
MIRROR=${MIRROR:-}   # 空なら host の sources.list をそのまま使う

command -v apt-get  >/dev/null 2>&1 || { echo "ERROR: apt-get が要る (Debian host)"; exit 2; }
command -v dpkg-deb >/dev/null 2>&1 || { echo "ERROR: dpkg-deb が要る"; exit 2; }
[ -f "$MANIFEST" ] || { echo "ERROR: manifest 無し: $MANIFEST"; exit 2; }

RF=$OUT
# DEB_CACHE を指定すると .deb 取得をそこにキャッシュして再利用 (build-release の再実行高速化)。
CTL=$(mktemp -d -t emulin-debbase-ctl.XXXXXX)
if [ -n "${DEB_CACHE:-}" ]; then DEBS=$DEB_CACHE; mkdir -p "$DEBS"; trap 'rm -rf "$CTL"' EXIT
else DEBS=$(mktemp -d -t emulin-debbase-debs.XXXXXX); trap 'rm -rf "$DEBS" "$CTL"' EXIT; fi

rm -rf "$RF"
mkdir -p "$RF/var/lib/dpkg/info" "$RF/var/lib/dpkg/updates" "$RF/var/lib/dpkg/triggers"
: > "$RF/var/lib/dpkg/status"
: > "$RF/var/lib/dpkg/available"
echo "amd64" > "$RF/var/lib/dpkg/arch"
# issue #699: /mnt を実体ディレクトリとして用意する。Windows host の /mnt/<drive>
#   auto-mount は prefix 置換で /mnt/c 以下しか解決しないため、bare /mnt が rootfs に
#   無いと node realpath (component 単位 lstat) が ENOENT になり、claude が
#   cwd=/mnt/c/... を「存在しないパス」と誤判定して黒画面ハングする。
mkdir -p "$RF/mnt"

# manifest から package 名を抽出 (version 落とし、:arch suffix も base 名に正規化)
mapfile -t PKGS < <(awk -F'\t' 'NF{print $1}' "$MANIFEST" | sed 's/:.*//' | sort -u)
echo "[debian-base] manifest = $MANIFEST (${#PKGS[@]} packages, docker debian:trixie base 相当)"

# ---- 1. apt-get download (依存閉包済み集合なので名前指定だけで完結) ----
if [ -n "${DEB_CACHE:-}" ] && ls "$DEBS"/*.deb >/dev/null 2>&1; then
  echo "[debian-base] cached .deb を再利用: $DEBS ($(ls "$DEBS"/*.deb | wc -l) 個)"
else
  echo "[debian-base] apt-get download (current trixie) ..."
  ( cd "$DEBS" && apt-get download "${PKGS[@]}" ) || { echo "ERROR: apt-get download 失敗 (host が trixie か / network を確認)"; exit 2; }
fi
GOT=$(ls "$DEBS"/*.deb 2>/dev/null | wc -l)
echo "[debian-base] 取得 .deb = $GOT"
[ "$GOT" -ge "${#PKGS[@]}" ] || echo "[debian-base] warn: 取得数 < manifest 数 (一部 virtual/別名の可能性)"

# ---- 2. data 展開 + dpkg status DB 合成 ---- (issue #361: deb_install_dir に共通化)
echo "[debian-base] extract + dpkg status DB 合成 ..."
deb_install_dir "$RF" "$DEBS"
echo "[debian-base] status stanza = $(grep -c '^Package:' "$RF/var/lib/dpkg/status")"

# ---- 3. maintainer script 代替の最小 essential 設定 ----
# base-passwd.postinst 相当: /etc/passwd /etc/group を master から生成
if [ -f "$RF/usr/share/base-passwd/passwd.master" ]; then
  cp "$RF/usr/share/base-passwd/passwd.master" "$RF/etc/passwd"
  cp "$RF/usr/share/base-passwd/group.master"  "$RF/etc/group"
  # shadow も最小生成 (login/passwd が要求)。root はパスワード無しロック。
  [ -f "$RF/etc/shadow" ] || awk -F: '{print $1":*:19000:0:99999:7:::"}' "$RF/etc/passwd" > "$RF/etc/shadow"
  echo "[debian-base] /etc/passwd /etc/group /etc/shadow 生成 (base-passwd master)"
fi
# libc-bin.postinst 相当: ld.so.cache を rootfs 内に生成 (chroot 不要、-r でターゲット指定)
if command -v ldconfig >/dev/null 2>&1; then
  ldconfig -r "$RF" 2>/dev/null && echo "[debian-base] ld.so.cache 生成 (ldconfig -r)" || echo "[debian-base] warn: ldconfig -r 失敗"
fi

# ---- 4. apt sources + keyring (apt-get update / install できるように) ----
mkdir -p "$RF/etc/apt/sources.list.d" "$RF/etc/apt/preferences.d" \
         "$RF/var/lib/apt/lists/partial" "$RF/var/cache/apt/archives/partial"
# deb822 形式 (trixie 標準)。debian-archive-keyring が /usr/share/keyrings に署名鍵を提供。
cat > "$RF/etc/apt/sources.list.d/debian.sources" <<'SRC'
Types: deb
URIs: http://deb.debian.org/debian
Suites: trixie trixie-updates
Components: main
Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg

Types: deb
URIs: http://deb.debian.org/debian-security
Suites: trixie-security
Components: main
Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg
SRC
echo "[debian-base] /etc/apt/sources.list.d/debian.sources 設置"

# issue #322: apt の download sandbox (非特権 _apt ユーザへの privilege drop) を無効化。
#   apt は acquire 時に .apt-acquire-privs-test を作って _apt で unlink できるか試すが、
#   emulin/Windows では「open 中 file の unlink」が拒否され (Linux 流の unlink-while-open
#   idiom)、(a) `IsAccessibleBySandboxUser` warning が出る (b) privilege drop が emulin の
#   プロセス/パイプ模型と相性が悪く apt-get update の署名検証 (sqv) が時々 "Broken pipe" で
#   失敗する。emulin は単一ユーザ環境で privsep の意味が無いので sandbox を root 固定にする
#   (= 検証を root で一貫実行)。これで warning 消滅 + update が安定する。
mkdir -p "$RF/etc/apt/apt.conf.d"
printf 'APT::Sandbox::User "root";\n' > "$RF/etc/apt/apt.conf.d/00-emulin-no-sandbox"
echo "[debian-base] /etc/apt/apt.conf.d/00-emulin-no-sandbox 設置 (download sandbox 無効化)"

# issue #349: maintainer script が daemon を起動しようとする (invoke-rc.d / SysV
#   init script) のを抑止する。emulin は init (PID 1) を持たないため daemon を起動
#   できないので、chroot install の定石どおり policy-rc.d で exit 101 を返し
#   invoke-rc.d を no-op にする (cron / exim4 等 SysV service の configure 用。
#   systemd unit は /run/systemd/system 不在で deb-systemd-helper が自動 skip する)。
mkdir -p "$RF/usr/sbin"
printf '#!/bin/sh\nexit 101\n' > "$RF/usr/sbin/policy-rc.d"
chmod 755 "$RF/usr/sbin/policy-rc.d"
echo "[debian-base] /usr/sbin/policy-rc.d 設置 (daemon 起動抑止、issue #349)"

# issue #349: systemd-tmpfiles 等は /proc/sys/kernel/random/boot_id を読んで `%b`
#   (boot ID) 指定子を解決する。emulin は /proc を rootfs ディレクトリで裏打ちして
#   読ませるので、boot_id を実ファイルとして用意しておく (不在だと systemd-tmpfiles
#   が `/tmp/systemd-private-%b-*` 等で "Failed to replace specifiers" になり、
#   systemd の configure (tmpfiles trigger) が exit≠0 → dpkg error)。値は build 時に
#   生成する固定 UUID (single-process な emulin では per-boot 同一で問題ない)。
mkdir -p "$RF/proc/sys/kernel/random"
BOOT_ID=$( (cat /proc/sys/kernel/random/uuid 2>/dev/null) || (command -v uuidgen >/dev/null 2>&1 && uuidgen) || echo "00000000-0000-4000-8000-000000000000" )
printf '%s\n' "$BOOT_ID" > "$RF/proc/sys/kernel/random/boot_id"
echo "[debian-base] /proc/sys/kernel/random/boot_id 設置 ($BOOT_ID、systemd-tmpfiles %b 用、issue #349)"

# issue #349: update-alternatives が張る `awk` の symlink chain を用意する。
#   build-debian-base.sh は maintainer script (mawk.postinst の update-alternatives)
#   を実行しないため /usr/bin/awk → /etc/alternatives/awk → /usr/bin/mawk が無く、
#   多数の maintainer script (exim4-config.postinst 等が awk を使う) が
#   "awk: not found" で失敗 → apt-get install が dpkg error になる。mawk を既定の
#   awk provider として alternatives chain を直接張る (Debian の既定と同じ)。
if [ -e "$RF/usr/bin/mawk" ]; then
  mkdir -p "$RF/etc/alternatives"
  ln -sf /usr/bin/mawk          "$RF/etc/alternatives/awk"
  ln -sf /etc/alternatives/awk  "$RF/usr/bin/awk"
  echo "[debian-base] awk alternative (/usr/bin/awk → /etc/alternatives/awk → mawk) 設置 (issue #349)"
fi

# issue #716: 対話シェルが必ず UTF-8 ロケールを持つようにする (build-sandbox.sh と同じ仕込み。
#   素の Debian base rootfs 単体で使うケースのため、ここでも行う)。useradd -m で作られる
#   将来のユーザーには /etc/skel/.bashrc 経由で行き渡る。既存 LANG は尊重
#   (C.UTF-8 は glibc 組込みでロケールファイル不要)。
mkdir -p "$RF/etc/profile.d"
if [ ! -f "$RF/etc/profile.d/emulin-lang.sh" ]; then
    cat > "$RF/etc/profile.d/emulin-lang.sh" <<'LANGEOF'
# issue #716: default to glibc's built-in UTF-8 locale so Japanese/CJK text works
export LANG="${LANG:-C.UTF-8}"
LANGEOF
fi
for _rc in "$RF/etc/skel/.bashrc" "$RF/root/.bashrc"; do
    if [ -f "$_rc" ]; then
        grep -q 'EMULIN_LANG_DEFAULT' "$_rc" || printf '\n# EMULIN_LANG_DEFAULT (issue #716): UTF-8 locale for Japanese/CJK text\nexport LANG="${LANG:-C.UTF-8}"\n' >> "$_rc"
    else
        mkdir -p "$(dirname "$_rc")"
        printf '# EMULIN_LANG_DEFAULT (issue #716): UTF-8 locale for Japanese/CJK text\nexport LANG="${LANG:-C.UTF-8}"\n' > "$_rc"
    fi
done
echo "[debian-base] LANG=C.UTF-8 既定を profile.d + skel/.bashrc + root/.bashrc に設置 (issue #716)"

# ---- 5. 仕上げ ----
du -sh "$RF" 2>/dev/null | awk '{print "[debian-base] rootfs size = "$1}'
echo "[debian-base] DONE: $RF"
echo "[debian-base] 検証: dpkg-query --admindir=$RF/var/lib/dpkg -l | grep -c '^ii'"
