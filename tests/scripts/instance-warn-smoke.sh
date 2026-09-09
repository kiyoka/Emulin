#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/instance-warn-smoke.sh
#
#  issue #955: 「同じ rootfs を使う別インスタンス」の検出を検証する。
#
#  守る実害 (2026-08-25 に実機で踏んだ): 稼働中の rootfs にもう 1 つ Emulin を
#  起動すると、guest の credential ファイルが別の placeholder で書き直され、
#  **先に動いていた claude が黙って認証切れになる**。原因は画面に何も出ないので、
#  利用者からは「何もしていないのに Login expired」に見える。
#
#  ★ この検査で肝は 2 つ:
#    - **canonical 比較**。実害は symlink / junction 越しに同じ rootfs を掴んだ形
#      だった。生の文字列で比べる実装は「別物」と判断して**検出したい唯一の場面で黙る**
#    - **違う rootfs では警告しない**。これが無いと「常に警告する実装」でも緑になる
#
#  guest もネットワークも要らない (純 Java)。利用者の ~/.emulin/instances は触らない。
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (未 build)
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes

if [ ! -f "$CLASSES/emulin/InstanceWarnSmoke.class" ]; then
    echo "SKIP instance-warn-smoke : not built ($CLASSES/emulin/InstanceWarnSmoke.class)"
    echo "  run 'mvn compile' first"
    exit 2
fi

OUT=$(java -Xmx1g -cp "$CLASSES" emulin.InstanceWarnSmoke </dev/null 2>&1); RC=$?
printf '%s\n' "$OUT" | sed 's/^/  /'

if [ "$RC" != 0 ] || ! printf '%s' "$OUT" | grep -q 'InstanceWarn smoke OK'; then
    echo "FAIL    instance-warn-smoke (exit=$RC)"
    exit 1
fi

# --------------------------------------------------------------------
#  Phase 2 (e2e): **cwd が違う 2 つを同じ rootfs で起動**して警告が出るか。
#
#  ★ この形にしてあるのは、台帳へ **cwd を rootfs として書いてしまう**実装を通さないため。
#    #948 のダッシュボードが実際にそれをやっており (EmulinStatus.attach に _native_curdir を
#    渡していた)、Egress が書いた本物の rootfs を**あとから上書き**して同居検出を
#    黙って壊すところだった。cwd が同じテストではこの誤りを検出できない。
# --------------------------------------------------------------------
SANDBOX_SRC=$ROOT/sandbox
if [ ! -x "$SANDBOX_SRC/bin/busybox" ]; then
    echo "  (phase 2 skip: tests/sandbox/bin/busybox が無い)"
    echo "PASS    instance-warn-smoke (rootfs 共有の検出 #955)"
    exit 0
fi

SB=$(mktemp -d "${TMPDIR:-/tmp}/emulin-955e2e-XXXXXX")
SB2=$(mktemp -d "${TMPDIR:-/tmp}/emulin-955e2e-b-XXXXXX")
cleanup() { rm -rf "$SB" "$SB2"; }
trap cleanup EXIT
cp -a "$SANDBOX_SRC/." "$SB"/ 2>/dev/null || true
cp -a "$SANDBOX_SRC/." "$SB2"/ 2>/dev/null || true
mkdir -p "$SB/tmp"

# credential が 1 つも無いと Egress は黙る仕様なので、ダミーを env で与える (実キーではない)
export EMULIN_CRED_CLAUDE_ACCESS_TOKEN=sk-ant-oat01-SMOKE-A-00000000000
export EMULIN_CRED_CLAUDE_REFRESH_TOKEN=sk-ant-ort01-SMOKE-R-00000000000

# ★ issue #1018: 制限時間を env で変えられるようにする (負のコントロール用 + 負荷時に上げる用)。
IW_TIMEOUT=${IW_TIMEOUT:-60}

# 1 つ目: cwd = $SB/tmp (rootfs の中の別ディレクトリ)
( cd "$SB/tmp" && timeout "$IW_TIMEOUT" java -Xmx1g -cp "$CLASSES" emulin.Emulin "$SB" \
      /bin/busybox sleep 10 >/dev/null 2>&1 ) &
BG=$!
sleep 3

# 2 つ目: cwd = $SB (1 つ目とは違う cwd・同じ rootfs) → 警告が出るはず
L2=$(mktemp "${TMPDIR:-/tmp}/emulin-955e2e-log-XXXXXX")
( cd "$SB" && timeout "$IW_TIMEOUT" java -Xmx1g -cp "$CLASSES" emulin.Emulin "$SB" \
      /bin/busybox true > "$L2" 2>&1 )
RC2=$?
# ★ issue #1018: **前提 (1 つ目がまだ生きている) が崩れていないかを、判定の前に見る**。
#   1 つ目が先に終わっていたら警告が出ないのは当然で、それを「台帳に cwd が書かれて
#   いる」と読むのは誤診。前提の失敗と機能の失敗を区別できるようにする。
BG_ALIVE=0; kill -0 "$BG" 2>/dev/null && BG_ALIVE=1
# 3 つ目 (負のコントロール): 違う rootfs → 警告が出てはいけない
L3=$(mktemp "${TMPDIR:-/tmp}/emulin-955e2e-log-XXXXXX")
( cd "$SB2" && timeout "$IW_TIMEOUT" java -Xmx1g -cp "$CLASSES" emulin.Emulin "$SB2" \
      /bin/busybox true > "$L3" 2>&1 )
RC3=$?
wait $BG 2>/dev/null; BG_RC=$?

E2E=0
# ★ issue #1018: **ログの中身を読む前に、guest が最後まで走ったかを言う**。
#   ここで黙ると、timeout に殺されただけなのに「警告が出ない = 台帳が壊れている」と
#   読める行が出る (実害 #1018: cyg-symlink で 3 回発生して 3 回とも誤読した)。
for pair in "2:$RC2:$L2" "3:$RC3:$L3"; do
    n=${pair%%:*}; rest=${pair#*:}; rc=${rest%%:*}; log=${rest#*:}
    [ "$rc" = 0 ] && continue
    if [ "$rc" = 124 ]; then
        echo "  FAIL ${n} つ目の guest が ${IW_TIMEOUT}s 以内に終わらなかった (timeout で殺された)"
        echo "       (負荷で伸びたなら IW_TIMEOUT を上げる。伸びていないなら本体の停止を疑う)"
    else
        echo "  FAIL ${n} つ目の guest が rc=$rc で終わった (途中で死んだ)"
    fi
    sed 's/^/       | /' "$log" | tail -8
    E2E=1
done
if [ "$BG_ALIVE" != 1 ]; then
    echo "  FAIL 前提が崩れている: 2 つ目を起動した時点で 1 つ目が既に終わっていた (BG rc=$BG_RC)"
    echo "       (この状態では警告が出なくて当然。台帳の欠陥と区別できないので判定しない)"
    E2E=1
fi
# ★ 前提が崩れているなら中身は判定しない。ここで続けると「警告が出ない = 台帳が壊れて
#   いる」という**誤った断定**が画面に出て、それが唯一の手掛かりになってしまう。
if [ "$E2E" != 0 ]; then
    rm -f "$L2" "$L3"
    echo "FAIL    instance-warn-smoke (guest が最後まで走らなかった / 前提が崩れた)"
    exit 1
fi
if grep -qa 'another Emulin is using the same rootfs' "$L2"; then
    echo "  ok   cwd が違っても同じ rootfs なら警告する (台帳に rootfs が入っている)"
else
    echo "  FAIL cwd が違う 2 つを同じ rootfs で起動したのに警告が出ない"
    echo "       (台帳に cwd が書かれている可能性がある)"
    sed 's/^/       | /' "$L2" | tail -8
    E2E=1
fi
if grep -qa 'another Emulin is using the same rootfs' "$L3"; then
    echo "  FAIL 違う rootfs なのに警告が出た"
    E2E=1
else
    echo "  ok   違う rootfs では警告しない"
fi
rm -f "$L2" "$L3"

if [ "$E2E" = 0 ]; then
    echo "PASS    instance-warn-smoke (rootfs 共有の検出 #955)"
    exit 0
fi
echo "FAIL    instance-warn-smoke (e2e)"
exit 1
