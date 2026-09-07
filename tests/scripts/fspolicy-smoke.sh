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
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (未 build)
# --------------------------------------------------------------------
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes
SB=$ROOT/sandbox

if [ ! -f "$CLASSES/emulin/Emulin.class" ] || [ ! -x "$SB/bin/busybox" ]; then
    echo "SKIP fspolicy-smoke : not built"
    exit 2
fi

H=$(mktemp -d -t emulin-fspol.XXXXXX)
trap 'rm -rf "$H"; rm -rf "$SB/mnt/fspol"' EXIT
mkdir -p "$H/work" "$H/secret" "$SB/mnt/fspol"
printf 'WORKDATA\n'   > "$H/work/ok.txt"
printf 'SECRETDATA\n' > "$H/secret/key.txt"

fail=0
# guest を 1 回動かし、mount 越しに <path> を cat した結果を返す。
run() {   # run <env assignments...> -- <guest path>
    local envs=() p=""
    while [ "$1" != "--" ]; do envs+=("$1"); shift; done
    shift; p=$1
    ( cd "$SB" && env "${envs[@]}" java -Xmx1g -XX:-DontCompileHugeMethods -cp "$CLASSES" \
        emulin.Emulin "$SB" /bin/busybox sh -c "mount -t none $H /mnt/fspol; cat $p" 2>&1 ) | tail -1
}
# guest で任意の sh コマンドを走らせる (stat 経路の検査用)。
runcmd() {   # runcmd <env...> -- <sh コマンド>
    local envs=() c=""
    while [ "$1" != "--" ]; do envs+=("$1"); shift; done
    shift; c=$1
    ( cd "$SB" && env "${envs[@]}" java -Xmx1g -XX:-DontCompileHugeMethods -cp "$CLASSES" \
        emulin.Emulin "$SB" /bin/busybox sh -c "mount -t none $H /mnt/fspol; $c" 2>&1 ) | tail -1
}

want() {  # want <期待する文字列> <説明> <出力>
    if printf '%s' "$3" | grep -q "$1"; then echo "  ok   $2"
    else echo "  FAIL $2 -> [$3]"; fail=$(( fail + 1 )); fi
}

echo "=== #732 host パス allowlist (guest 実行) ==="

# ★ 対照: ポリシー無しなら host の秘密が読める = これが塞ぎたい露出
want SECRETDATA "対照: ポリシー無しなら mount 越しに host を読める (露出が実在する)" \
     "$(run EMULIN_FSPOL_OFF=1 -- /mnt/fspol/secret/key.txt)"

# allowlist: work だけ許可
want "can't open" "★ allowlist の外は読めない" \
     "$(run EMULIN_FS_ALLOW=$H/work -- /mnt/fspol/secret/key.txt)"
want WORKDATA "allowlist の中は読める" \
     "$(run EMULIN_FS_ALLOW=$H/work -- /mnt/fspol/work/ok.txt)"

# ★ open だけでなく **stat 経路 (Inode)** も塞ぐこと。cat は open を通るので、
#   open 側の判定だけでもここまでは緑になる。**片方が壊れても気づけない検査にしない**
#   (実際、最初は 2 つの hook が冗長で、片方を外しても落ちなかった)。
want "^0$" "★ 対照: ポリシー無しなら stat で見える" \
     "$(runcmd EMULIN_FSPOL_OFF=1 -- 'test -f /mnt/fspol/secret/key.txt; echo $?')"
want "^1$" "★ allowlist の外は stat でも見えない (Inode 経路)" \
     "$(runcmd EMULIN_FS_ALLOW=$H/work -- 'test -f /mnt/fspol/secret/key.txt; echo $?')"
want "^0$" "allowlist の中は stat で見える" \
     "$(runcmd EMULIN_FS_ALLOW=$H/work -- 'test -f /mnt/fspol/work/ok.txt; echo $?')"

# deny だけの運用
want "can't open" "deny した場所は読めない" \
     "$(run EMULIN_FS_DENY=$H/secret -- /mnt/fspol/secret/key.txt)"
want WORKDATA "deny 運用では、それ以外は読める" \
     "$(run EMULIN_FS_DENY=$H/secret -- /mnt/fspol/work/ok.txt)"

if [ "$fail" = 0 ]; then
    echo "PASS    fspolicy-smoke (host パス allowlist #732)"
    exit 0
fi
echo "FAIL    fspolicy-smoke: $fail 件"
exit 1
