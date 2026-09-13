#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/fspolicy-smoke.sh
#
#  issue #732: **guest が触れてよい host パス**を allowlist で制限できることを、
#  実際の guest を動かして検査する (FsPolicySmoke は判定ロジックの単体検査)。
#
#  ★ **対照 (ポリシー無しで読めること) を必ず測る。** これが無いと「拒否できた」が
#    「そもそも届いていなかっただけ」かもしれず、検査として成立しない。
#    実際この issue の調査中、symlink 経由の経路は **Emulin が元から封じ込めていて**
#    対照が成立せず、危うく「効いている」と誤認するところだった。
#
#  ★ 露出は **mount point 経由** (guest の mount(2) / Windows の /mnt/c 自動 mount)。
#    絶対 symlink の飛び先は rootfs 配下として再解釈されるので、そちらは元から届かない。
#
#  ★ 設定は **EMULIN_FS_ALLOW 1 本だけ** (2026-09-13 に deny 廃止)。deny は
#    「書き忘れ = 見える」に倒れるので境界にならない。ここでも allow だけを検査する。
#
#  ★ guest 表記 (/mnt/x 等) → host パスの変換は、起動時に確定している mount が要る
#    (Windows の drive 自動 mount がそれ)。この検査は Linux で走るため startup mount が
#    無く、変換経路は FsPolicySmoke 側 (実 Mount 表を組んで検査) が受け持つ。
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
trap 'rm -rf "$H"; rm -rf "$SB/mnt/fspol"; rm -f "$SB/bin/sys_opath_symlink64" "$SB/tmp/fspol-rootfs.txt"' EXIT
mkdir -p "$H/work" "$H/secret" "$SB/mnt/fspol"
mkdir -p "$SB/tmp"
printf 'ROOTDATA\n'   > "$SB/tmp/fspol-rootfs.txt"   # rootfs 配下の目印
printf 'WORKDATA\n'   > "$H/work/ok.txt"
printf 'SECRETDATA\n' > "$H/secret/key.txt"
# O_PATH 分岐 (最終 component が symlink) を踏ませるための symlink。
ln -s ok.txt  "$H/work/link"
ln -s key.txt "$H/secret/link"

fail=0
# guest で任意の sh コマンドを走らせる。mount 越しに host を見せた状態にする。
#   ★ 最終行だけを返す (途中の banner を拾わないため)。
runcmd() {   # runcmd <env...> -- <sh コマンド>
    local envs=() c=""
    while [ "$1" != "--" ]; do envs+=("$1"); shift; done
    shift; c=$1
    ( cd "$SB" && env "${envs[@]}" java -Xmx1g -XX:-DontCompileHugeMethods -cp "$CLASSES" \
        emulin.Emulin "$SB" /bin/busybox sh -c "mount -t none $H /mnt/fspol; $c" 2>&1 ) | tail -1
}
# 出力全体を返す版 (起動時の案内行を見る用)。
runall() {   # runall <env...> -- <sh コマンド>
    local envs=() c=""
    while [ "$1" != "--" ]; do envs+=("$1"); shift; done
    shift; c=$1
    ( cd "$SB" && env "${envs[@]}" java -Xmx1g -XX:-DontCompileHugeMethods -cp "$CLASSES" \
        emulin.Emulin "$SB" /bin/busybox sh -c "mount -t none $H /mnt/fspol; $c" 2>&1 )
}

want() {  # want <期待する文字列> <説明> <出力>
    if printf '%s' "$3" | grep -q "$1"; then echo "  ok   $2"
    else echo "  FAIL $2 -> [$3]"; fail=$(( fail + 1 )); fi
}

ALLOW="EMULIN_FS_ALLOW=$H/work"   # work だけ許可する
OFF="EMULIN_FS_ALLOW="            # ★ 空値は「未設定」= 無制限 (誤って有効にしない)

echo "=== #732 host パス allowlist (guest 実行) ==="

# ★ 対照: 制限しなければ host の秘密が読める = これが塞ぎたい露出
want SECRETDATA "対照: 制限しなければ mount 越しに host を読める (露出が実在する)" \
     "$(runcmd $OFF -- 'cat /mnt/fspol/secret/key.txt')"

want "can't open" "★ allowlist の外は読めない" \
     "$(runcmd $ALLOW -- 'cat /mnt/fspol/secret/key.txt')"
want WORKDATA "allowlist の中は読める" \
     "$(runcmd $ALLOW -- 'cat /mnt/fspol/work/ok.txt')"

# ★ rootfs は常に許可されること。ここが効いていないと guest は自分自身 (rootfs 配下の
#   binary / 共有ライブラリ) を見失う。静的 busybox は起動だけならこの判定を通らないので、
#   **起動後に rootfs の file を open して**確かめる (ls /bin/busybox では hook を踏まず、
#   この規則が消えても緑のままだった — 検査が自分の狙いを外していた)。
want ROOTDATA "★ allowlist を設定しても rootfs は常に許可される" \
     "$(runcmd $ALLOW -- 'cat /tmp/fspol-rootfs.txt')"

# ★ 効いていることが画面に出ること。出ないと、設定をタイプミスしても無制限のまま気付けない。
want "filesystem policy: allow=" "★ 起動時に policy が表示される" \
     "$(runall $ALLOW -- 'true')"

# ★ open だけでなく **stat 経路 (Inode)** も塞ぐこと。cat は open を通るので、
#   open 側の判定だけでもここまでは緑になる。**片方が壊れても気づけない検査にしない**
#   (実際、最初は 2 つの hook が冗長で、片方を外しても落ちなかった)。
want "^0$" "★ 対照: 制限しなければ stat で見える" \
     "$(runcmd $OFF -- 'test -f /mnt/fspol/secret/key.txt; echo $?')"
want "^1$" "★ allowlist の外は stat でも見えない (Inode 経路)" \
     "$(runcmd $ALLOW -- 'test -f /mnt/fspol/secret/key.txt; echo $?')"
want "^0$" "allowlist の中は stat で見える" \
     "$(runcmd $ALLOW -- 'test -f /mnt/fspol/work/ok.txt; echo $?')"

# ★ O_PATH + 最終 component が symlink の分岐 (issue #349) は **new Inode(...) より前に
#   return する**ので、Inode 側の判定では守れない。open 側 hook 専用の検査。
#   busybox はこの分岐を踏まないため、この binary が無いと open 側 hook を消しても緑のまま。
want "opath=ok" "★ 対照: 制限しなければ O_PATH で symlink を開ける (分岐に到達している)" \
     "$(runcmd $OFF -- '/bin/sys_opath_symlink64 /mnt/fspol/secret/link')"
want "opath=-2" "★ allowlist の外は O_PATH 分岐でも塞がる (open 側 hook 専用)" \
     "$(runcmd $ALLOW -- '/bin/sys_opath_symlink64 /mnt/fspol/secret/link')"
want "opath=ok" "allowlist の中なら O_PATH で開ける" \
     "$(runcmd $ALLOW -- '/bin/sys_opath_symlink64 /mnt/fspol/work/link')"

if [ "$fail" = 0 ]; then
    echo "PASS    fspolicy-smoke (host パス allowlist #732)"
    exit 0
fi
echo "FAIL    fspolicy-smoke: $fail 件"
exit 1
