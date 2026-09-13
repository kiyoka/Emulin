#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/fspolicy-smoke.sh
#
#  issue #732: **guest が触れてよい host パス**を allowlist で制限できることを、
#  実際の guest を動かして検査する (FsPolicySmoke は判定ロジックの単体検査)。
#
#  ★ **対照 (制限しなければ読めること) を必ず測る。** これが無いと「拒否できた」が
#    「そもそも届いていなかっただけ」かもしれず、検査として成立しない。
#    実際この issue の調査中、symlink 経由の経路は **Emulin が元から封じ込めていて**
#    対照が成立せず、危うく「効いている」と誤認するところだった。
#
#  ★ 露出は **mount point 経由** (guest の mount(2) / Windows の /mnt/c 自動 mount)。
#    絶対 symlink の飛び先は rootfs 配下として再解釈されるので、そちらは元から届かない。
#
#  ★ 設定は **`~/.emulin/fs-allow.txt` 1 本**。env は使わない (issue #1046)。
#    env で渡す形は `Open terminal` が wt.exe 経由で別プロセス文脈から起動し直されると
#    落ちて、**そこだけ無制限の guest が起きる**。ここでは `-Duser.home` で偽の home を
#    与え、**env を渡さずに**制限が効くことを見る。
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (未 build)
# --------------------------------------------------------------------
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes
SB=$ROOT/sandbox
OPATH_BIN=$ROOT/binaries/bin/sys_opath_symlink64

if [ ! -f "$CLASSES/emulin/Emulin.class" ] || [ ! -x "$SB/bin/busybox" ] \
   || [ ! -x "$OPATH_BIN" ]; then
    echo "SKIP fspolicy-smoke : not built"
    exit 2
fi
cp "$OPATH_BIN" "$SB/bin/" 2>/dev/null || { echo "SKIP fspolicy-smoke : cannot stage binary"; exit 2; }

H=$(mktemp -d -t emulin-fspol.XXXXXX)
FAKEHOME=$(mktemp -d -t emulin-fspol-home.XXXXXX)
trap 'rm -rf "$H" "$FAKEHOME"; rm -rf "$SB/mnt/fspol"; rm -f "$SB/bin/sys_opath_symlink64" "$SB/tmp/fspol-rootfs.txt"' EXIT
mkdir -p "$H/work" "$H/secret" "$SB/mnt/fspol" "$FAKEHOME/.emulin"
mkdir -p "$SB/tmp"
printf 'ROOTDATA\n'   > "$SB/tmp/fspol-rootfs.txt"   # rootfs 配下の目印
printf 'WORKDATA\n'   > "$H/work/ok.txt"
printf 'SECRETDATA\n' > "$H/secret/key.txt"
# O_PATH 分岐 (最終 component が symlink) を踏ませるための symlink。
ln -s ok.txt  "$H/work/link"
ln -s key.txt "$H/secret/link"

CFG=$FAKEHOME/.emulin/fs-allow.txt
fail=0

# 許可リストを書く (引数なし = 制限なし)。★ guest を起こす前に確定している必要がある。
policy() {
    if [ $# -eq 0 ]; then rm -f "$CFG"; return; fi
    { echo "# test"; for p in "$@"; do echo "$p"; done; } > "$CFG"
}

# guest で任意の sh コマンドを走らせる。mount 越しに host を見せた状態にする。
#   ★ **env は渡さない。** 効くのは設定ファイルだけ、というのがこの設計。
#   ★ 最終行だけを返す (途中の banner を拾わないため)。
runcmd() {   # runcmd <sh コマンド>
    ( cd "$SB" && java -Xmx1g -XX:-DontCompileHugeMethods "-Duser.home=$FAKEHOME" -cp "$CLASSES" \
        emulin.Emulin "$SB" /bin/busybox sh -c "mount -t none $H /mnt/fspol; $1" 2>&1 ) | tail -1
}
# 出力全体を返す版 (起動時の案内行を見る用)。
runall() {   # runall <sh コマンド>
    ( cd "$SB" && java -Xmx1g -XX:-DontCompileHugeMethods "-Duser.home=$FAKEHOME" -cp "$CLASSES" \
        emulin.Emulin "$SB" /bin/busybox sh -c "mount -t none $H /mnt/fspol; $1" 2>&1 )
}

want() {  # want <期待する文字列> <説明> <出力>
    if printf '%s' "$3" | grep -q "$1"; then echo "  ok   $2"
    else echo "  FAIL $2 -> [$3]"; fail=$(( fail + 1 )); fi
}

echo "=== #732 host パス allowlist (guest 実行) ==="

# ★ 対照: 制限しなければ host の秘密が読める = これが塞ぎたい露出
policy
want SECRETDATA "対照: 制限しなければ mount 越しに host を読める (露出が実在する)" \
     "$(runcmd 'cat /mnt/fspol/secret/key.txt')"

policy "$H/work"
want "can't open" "★ allowlist の外は読めない" \
     "$(runcmd 'cat /mnt/fspol/secret/key.txt')"
want WORKDATA "allowlist の中は読める" \
     "$(runcmd 'cat /mnt/fspol/work/ok.txt')"

# ★ rootfs は常に許可されること。ここが効いていないと guest は自分自身 (rootfs 配下の
#   binary / 共有ライブラリ) を見失う。静的 busybox は起動だけならこの判定を通らないので、
#   **起動後に rootfs の file を open して**確かめる (ls /bin/busybox では hook を踏まず、
#   この規則が消えても緑のままだった — 検査が自分の狙いを外していた)。
want ROOTDATA "★ allowlist を設定しても rootfs は常に許可される" \
     "$(runcmd 'cat /tmp/fspol-rootfs.txt')"

# ★ 効いていることが画面に出ること。出ないと、設定をタイプミスしても無制限のまま気付けない。
#   ★ **どこを直せばよいか** (設定ファイルの場所) まで出す。
want "filesystem policy: allow=" "★ 起動時に policy が表示される" \
     "$(runall 'true')"
want "fs-allow.txt" "★ 設定ファイルの場所が表示される" \
     "$(runall 'true')"

# ★ open だけでなく **stat 経路 (Inode)** も塞ぐこと。cat は open を通るので、
#   open 側の判定だけでもここまでは緑になる。**片方が壊れても気づけない検査にしない**
#   (実際、最初は 2 つの hook が冗長で、片方を外しても落ちなかった)。
policy
want "^0$" "★ 対照: 制限しなければ stat で見える" \
     "$(runcmd 'test -f /mnt/fspol/secret/key.txt; echo $?')"
policy "$H/work"
want "^1$" "★ allowlist の外は stat でも見えない (Inode 経路)" \
     "$(runcmd 'test -f /mnt/fspol/secret/key.txt; echo $?')"
want "^0$" "allowlist の中は stat で見える" \
     "$(runcmd 'test -f /mnt/fspol/work/ok.txt; echo $?')"

# ★ O_PATH + 最終 component が symlink の分岐 (issue #349) は **new Inode(...) より前に
#   return する**ので、Inode 側の判定では守れない。open 側 hook 専用の検査。
#   busybox はこの分岐を踏まないため、この binary が無いと open 側 hook を消しても緑のまま。
policy
want "opath=ok" "★ 対照: 制限しなければ O_PATH で symlink を開ける (分岐に到達している)" \
     "$(runcmd '/bin/sys_opath_symlink64 /mnt/fspol/secret/link')"
policy "$H/work"
want "opath=-2" "★ allowlist の外は O_PATH 分岐でも塞がる (open 側 hook 専用)" \
     "$(runcmd '/bin/sys_opath_symlink64 /mnt/fspol/secret/link')"
want "opath=ok" "allowlist の中なら O_PATH で開ける" \
     "$(runcmd '/bin/sys_opath_symlink64 /mnt/fspol/work/link')"

# ★ **env では効かない** (issue #1046)。env で渡す形に戻すと、wt.exe 経由の
#   `Open terminal` で落ちて「そこだけ無制限」になる。env を見る実装に戻ったら
#   ここが赤くなる。
policy
want SECRETDATA "★ env (EMULIN_FS_ALLOW) では制限できない (設定ファイルだけが効く)" \
     "$( ( cd "$SB" && EMULIN_FS_ALLOW=$H/work java -Xmx1g -XX:-DontCompileHugeMethods \
            "-Duser.home=$FAKEHOME" -cp "$CLASSES" emulin.Emulin "$SB" /bin/busybox \
            sh -c "mount -t none $H /mnt/fspol; cat /mnt/fspol/secret/key.txt" 2>&1 ) | tail -1 )"

if [ "$fail" = 0 ]; then
    echo "PASS    fspolicy-smoke (host パス allowlist #732)"
    exit 0
fi
echo "FAIL    fspolicy-smoke: $fail 件"
exit 1
