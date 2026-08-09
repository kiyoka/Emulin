#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/ash-applet-survey.sh
#
#  Phase 23 step (使い込みテスト): busybox の各 applet (find/sort/awk/sed/
#  grep/tar/xargs 等) を ash 経由で叩いて host busybox と出力を比較する。
#  bb-survey.sh の applet 単体テストや ash-noninteractive.sh のシェル構文
#  テストでカバーしきれない「複合パイプライン」「ファイル入出力」を
#  網羅し、潜在バグを発掘する。
#
#  使い方:
#    bash tests/scripts/ash-applet-survey.sh
#    bash tests/scripts/ash-applet-survey.sh find-pat sort-numeric
#    VERBOSE=1 bash tests/scripts/ash-applet-survey.sh
#
#  終了コード: 0=全 PASS / 1=FAIL あり / 2=実行不能 (SKIP)
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
SANDBOX=${SANDBOX_DIR:-$ROOT/sandbox}
CLASSES=$PROJECT/target/classes
# Phase 34-A3: EMULIN_USE_JIT=1 で必要な ASM 9.6 を classpath に補充
ASM_JAR="$HOME/.m2/repository/org/ow2/asm/asm/9.6/asm-9.6.jar"
[ -f "$ASM_JAR" ] && CLASSES="$CLASSES:$ASM_JAR"
HOST_BB=/usr/bin/busybox
TIMEOUT=30  # Phase 30: 並列負荷下で 20s だと flake するため 30s に

if [ ! -f "$PROJECT/target/classes/emulin/Emulin.class" ]; then
    echo "SKIP ash-applet-survey : Emulin not built ($PROJECT/target/classes/emulin/Emulin.class)"
    exit 2
fi
if [ ! -f "$HOST_BB" ]; then
    echo "SKIP ash-applet-survey : host busybox not found at $HOST_BB"
    exit 2
fi

# サンドボックスとフィクスチャを作る
mkdir -p "$SANDBOX/bin" "$SANDBOX/etc" "$SANDBOX/tmp"
cp "$HOST_BB" "$SANDBOX/bin/busybox"
: > "$SANDBOX/etc/emulin.cnf"

# フィクスチャを 2 箇所に同じ内容で用意する:
#   - host 側 /tmp/asurvey   ... host busybox からの expected 取得用
#   - emu  側 $SANDBOX/tmp/asurvey ... emulator が /tmp/asurvey として見る
# シンボリックリンクは find のデフォルト挙動 (シンボリックリンクの追跡なし)
# のせいで使えないので、両方に実ディレクトリ + ファイルを置く。
populate_fixture() {
    local d=$1
    rm -rf "$d"
    mkdir -p "$d/sub"
    printf 'banana\napple\ncherry\napple\n'                 > "$d/fruit.txt"
    printf '3 cat\n1 dog\n2 bird\n10 ant\n'                 > "$d/nums.txt"
    printf 'name:price\napple:120\nbanana:80\ncherry:300\n' > "$d/csv.txt"
    printf 'foo bar\nbaz qux\nfoo zed\n'                    > "$d/words.txt"
    printf 'hello\n' > "$d/sub/a.txt"
    printf 'world\n' > "$d/sub/b.txt"
    printf 'extra\n' > "$d/sub/c.log"
    ( cd "$d" && $HOST_BB tar cf "$d/all.tar" fruit.txt nums.txt 2>/dev/null )
}
HOST_FIX=/tmp/asurvey
EMU_FIX=$SANDBOX/tmp/asurvey
populate_fixture "$HOST_FIX"
populate_fixture "$EMU_FIX"
# ★ issue #909: 後段の trap と 1 本にまとめる。bash の `trap ... EXIT` は
#   **前の登録を置き換える**ので、以前は後段の `trap 'rm -rf $ASURV_RESDIR' EXIT`
#   がこれを上書きして $HOST_FIX (/tmp/asurvey) が消えずに残っていた。
#   ASURV_RESDIR は「失敗 or retry 回復があったら残す」ので条件付きで消す。
ASURV_RESDIR=
ASURV_KEEP=0
cleanup() {
    rm -rf "$HOST_FIX"
    if [ -n "$ASURV_RESDIR" ]; then
        if [ "$ASURV_KEEP" = 1 ]; then
            echo "  (証拠を残しました: $ASURV_RESDIR)" >&2
        else
            rm -rf "$ASURV_RESDIR"
        fi
    fi
}
trap cleanup EXIT

# ----------------------------------------------------------------
# テストケース: name@@ash-script
# ash-script の中では PATH=/bin で busybox applet を叩く。
# host busybox と Emulin busybox の両方で実行し、出力を比較する。
# /tmp/asurvey はホスト・エミュ両方から見えるパス。
# ----------------------------------------------------------------
CASES=(
    # find: パターン展開とソート
    'find-name@@find /tmp/asurvey -type f -name "*.txt" | sort'
    'find-prune-log@@find /tmp/asurvey -type f ! -name "*.log" | sort'
    'find-depth@@find /tmp/asurvey -type d | sort'

    # sort
    'sort-default@@sort /tmp/asurvey/fruit.txt'
    'sort-numeric@@sort -k1,1n /tmp/asurvey/nums.txt'
    'sort-uniq@@sort -u /tmp/asurvey/fruit.txt'
    'sort-rev@@sort -r /tmp/asurvey/fruit.txt'
    'sort-field@@sort -t: -k2,2n /tmp/asurvey/csv.txt'

    # uniq
    'uniq-c@@sort /tmp/asurvey/fruit.txt | uniq -c'
    'uniq-d@@sort /tmp/asurvey/fruit.txt | uniq -d'

    # awk
    'awk-pattern@@awk "/foo/{print \$2}" /tmp/asurvey/words.txt'
    'awk-fs@@awk -F: "NR>1{print \$1}" /tmp/asurvey/csv.txt'
    'awk-arith@@awk "BEGIN{for(i=1;i<=5;i++)s+=i;print s}"'
    'awk-print2@@awk "{print NR,\$1}" /tmp/asurvey/words.txt'

    # sed
    'sed-replace@@sed "s/apple/APPLE/g" /tmp/asurvey/fruit.txt'
    'sed-range@@sed -n "2,3p" /tmp/asurvey/fruit.txt'
    'sed-multi@@sed -e "s/a/A/" -e "s/b/B/" /tmp/asurvey/fruit.txt'

    # grep
    'grep-basic@@grep "an" /tmp/asurvey/fruit.txt'
    'grep-v@@grep -v "apple" /tmp/asurvey/fruit.txt'
    'grep-c@@grep -c "apple" /tmp/asurvey/fruit.txt'
    'grep-E@@grep -E "^[ab]" /tmp/asurvey/fruit.txt'

    # tr / cut
    'tr-upper@@tr a-z A-Z < /tmp/asurvey/fruit.txt'
    'tr-d@@echo "abc-def-ghi" | tr -d -'
    'cut-field@@cut -d: -f1 /tmp/asurvey/csv.txt'
    'cut-bytes@@cut -b1-3 /tmp/asurvey/fruit.txt'

    # head / tail / wc
    'head-2@@head -n2 /tmp/asurvey/fruit.txt'
    'tail-2@@tail -n2 /tmp/asurvey/fruit.txt'
    'wc-l@@wc -l /tmp/asurvey/fruit.txt | awk "{print \$1}"'

    # paste / join 周りは applet 有無があるので軽め
    'paste-tab@@paste -d, /tmp/asurvey/fruit.txt /tmp/asurvey/words.txt'

    # xargs
    'xargs-n@@printf "a\nb\nc\nd\n" | xargs -n2 echo'
    'xargs-I@@printf "x\ny\n" | xargs -I@ echo arg=@'

    # tar (round-trip)
    'tar-tf@@tar tf /tmp/asurvey/all.tar | sort'

    # 複合パイプライン
    'pipe-find-sort@@find /tmp/asurvey -type f | sort | head -n3'
    'pipe-grep-wc@@grep -v "apple" /tmp/asurvey/fruit.txt | wc -l | awk "{print \$1}"'
    'pipe-awk-sort@@awk -F: "NR>1{print \$2,\$1}" /tmp/asurvey/csv.txt | sort -k1,1n'
    # pipe-tee は唯一の writer。並列実行時に他テストと衝突しないよう
    # /tmp 直下に PID 付きユニーク名を使う。
    'pipe-tee@@printf "1\n2\n3\n" | tee /tmp/teed-$$.out > /dev/null; cat /tmp/teed-$$.out; rm -f /tmp/teed-$$.out'

    # 数値生成 + 計算
    'seq-sum@@seq 1 10 | awk "{s+=\$1}END{print s}"'
    'seq-rev@@seq 1 5 | sort -r | tr "\n" "," | sed "s/,$//"'

    # printf / expr
    'printf-pad@@printf "%-5s|%5d\n" hi 42'
    'expr-len@@expr length hello'

    # base64 / md5sum (round-trip)
    'base64-rt@@printf "abc" | base64 | base64 -d'
    'md5sum-empty@@printf "" | md5sum | cut -d" " -f1'
    'sha256sum-abc@@printf "abc" | sha256sum | cut -d" " -f1'
)

# 引数で名前指定があれば絞り込み
if [ $# -gt 0 ]; then
    SELECT=" $* "
    NEW=()
    for c in "${CASES[@]}"; do
        n=${c%%@@*}
        if [[ "$SELECT" == *" $n "* ]]; then
            NEW+=("$c")
        fi
    done
    CASES=("${NEW[@]}")
fi

PASS=0
FAIL=0
declare -a FAILED=()
# ★ issue #909: retry で回復した数。緑でも flake は起きているので、その回数を出す。
RETRIED=0
declare -a RETRIED_NAMES=()

# 並列度: 環境変数 JOBS 優先。未指定なら min(nproc, 6) を使う。
# 抑え目にしている理由: 本スクリプトは run-all.sh から他 ext script と
# 並列で起動される。そこから更に nproc 並列を投げると CPU 飽和して
# JVM の起動・ash の syscall が伸び、稀に出力タイミングの race で
# flaky になることがある (実測: 12 並列 → 5%、6 並列 → 0%)。
if [ -z "${JOBS:-}" ]; then
    np=$( (nproc 2>/dev/null || echo 4) )
    JOBS=$(( np < 6 ? np : 6 ))
fi

# 各ケースを background で走らせ、結果を ASURV_RESDIR/<name>.result に書く。
# 結果ファイルの 1 行目: "PASS" / "FAIL" / "TIMEOUT"
ASURV_RESDIR=$(mktemp -d -t emulin-asurv.XXXXXX)
# ★ 後始末は上の cleanup() に集約した (trap を 2 回張ると前のが消える。issue #909)。

run_one_case() {
    local entry=$1 outdir=$2
    local name=${entry%%@@*}
    local script=${entry#*@@}
    local exp act rc attempt

    exp=$($HOST_BB ash -c "export PATH=$SANDBOX/bin:\$PATH; $script" </dev/null 2>/dev/null)
    # 並列負荷下 (CI JOBS=4+) で稀に emulin が空 stdout を返す flake が
    # あるため、最初の試行で mismatch / timeout のときに 1 回だけ retry。
    # 真の regression なら 2 回連続で fail、flake なら 2 回目で recover する。
    for attempt in 1 2; do
        act=$(cd "$SANDBOX" && timeout $TIMEOUT \
            java -Xmx2g -XX:-UsePerfData -cp "$CLASSES" emulin.Emulin "$SANDBOX" \
                /bin/busybox ash -c "$script" \
            </dev/null 2>/dev/null)
        rc=$?
        if [ "$rc" != 124 ] && [ "$exp" = "$act" ]; then
            # ★ issue #909: 1 回目で失敗し 2 回目で回復した = flake が起きたが緑になった回。
            #   従来はこれが跡形も無く消えており、「今日は緑だった」以上のことが
            #   分からなかった。間欠バグは**緑の回にも情報がある**ので記録する。
            [ "$attempt" = 2 ] && printf 'recovered-on-retry\n' > "$outdir/$name.retry"
            break
        fi
        # ★ 1 回目の失敗の中身も残す (2 回目で回復すると従来は完全に失われていた)。
        [ "$attempt" = 1 ] && save_evidence "$outdir/$name.attempt1" "$exp" "$act" "$rc"
    done

    if [ "$rc" = 124 ]; then
        printf 'TIMEOUT\n' > "$outdir/$name.result"
        save_evidence "$outdir/$name.diff" "$exp" "$act" "$rc"
    elif [ "$exp" = "$act" ]; then
        printf 'PASS\n' > "$outdir/$name.result"
    else
        printf 'FAIL\n' > "$outdir/$name.result"
        # ★ issue #909: VERBOSE に関係なく**必ず**残す。間欠バグは再現待ちなのに、
        #   再現したその回の expected/actual が消えていては次に進めなかった。
        save_evidence "$outdir/$name.diff" "$exp" "$act" "$rc"
    fi
}

# ★ issue #909: 失敗の中身をファイルに残す。
#   「actual が空だったのか、部分出力だったのか」を**必ず**区別できるようにする。
#   script のコメントにある「並列負荷下で稀に emulin が空 stdout を返す」が
#   事実かどうかは、これが無いと永遠に確かめられない。
save_evidence() {
    local path=$1 exp=$2 act=$3 rc=$4
    {
        echo "rc=$rc"
        echo "expected: ${#exp} bytes"
        echo "actual  : ${#act} bytes$( [ -z "$act" ] && echo '   <<< 空 (EMPTY STDOUT)' )"
        echo "--- expected ---"; printf '%s\n' "$exp" | head -20
        echo "--- actual ---";   printf '%s\n' "$act" | head -20
    } > "$path" 2>/dev/null
}

# 並列ディスパッチ: 同時 $JOBS まで投げ、wait -n で 1 個空くごとに次を投げる。
running=0
for entry in "${CASES[@]}"; do
    while [ $running -ge $JOBS ]; do
        wait -n 2>/dev/null || true
        running=$((running - 1))
    done
    run_one_case "$entry" "$ASURV_RESDIR" &
    running=$((running + 1))
done
wait

# 結果を CASES 順序で表示・集計
for entry in "${CASES[@]}"; do
    name=${entry%%@@*}
    local_res=$(cat "$ASURV_RESDIR/$name.result" 2>/dev/null || echo "MISSING")
    case "$local_res" in
        PASS)
            printf 'PASS    asurvey-%s\n' "$name"
            PASS=$((PASS+1))
            ;;
        TIMEOUT)
            printf 'FAIL    asurvey-%s (timeout)\n' "$name"
            FAIL=$((FAIL+1)); FAILED+=("$name(timeout)")
            ASURV_KEEP=1
            ;;
        *)
            printf 'FAIL    asurvey-%s\n' "$name"
            FAIL=$((FAIL+1)); FAILED+=("$name")
            ASURV_KEEP=1
            # ★ issue #909: 失敗時は VERBOSE でなくても要点 (actual の長さ / 空か) を出す。
            #   全文は $ASURV_RESDIR に残るので、必要なら後から読める。
            if [ -f "$ASURV_RESDIR/$name.diff" ]; then
                if [ "${VERBOSE:-0}" = "1" ]; then sed 's/^/  | /' "$ASURV_RESDIR/$name.diff"
                else head -3 "$ASURV_RESDIR/$name.diff" | sed 's/^/  | /'; fi
            fi
            ;;
    esac
    # ★ issue #909: retry で回復した回も数える。緑でも flake は起きている。
    if [ -f "$ASURV_RESDIR/$name.retry" ]; then
        RETRIED=$((RETRIED+1)); RETRIED_NAMES+=("$name")
        ASURV_KEEP=1
    fi
done

echo
echo "===== ash applet survey: PASS=$PASS FAIL=$FAIL RETRIED=$RETRIED (total=${#CASES[@]}) ====="
if [ ${#FAILED[@]} -gt 0 ]; then
    echo "failures: ${FAILED[*]}"
fi
# ★ issue #909: retry で回復したケースは PASS に数えられるが、flake は起きている。
#   ここを出さないと「今日は緑だった」以上のことが分からず、間欠バグの再現率を
#   測る材料が毎回捨てられる (非公開 #133「flake は判断でなく測定の対象」)。
if [ ${#RETRIED_NAMES[@]} -gt 0 ]; then
    echo "recovered on retry (flake but green): ${RETRIED_NAMES[*]}"
fi
[ "$FAIL" = 0 ]
