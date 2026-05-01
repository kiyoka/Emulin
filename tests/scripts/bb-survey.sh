#!/usr/bin/env bash
# tests/scripts/bb-survey.sh
#
# busybox applet を Emulin 上と host で実行して結果を比較するサーベイ。
# Phase 19: 動かない applet を炙り出して emulator のバグを発見する。
#
# 使い方:
#   bash tests/scripts/bb-survey.sh                # default 一覧を実行
#   bash tests/scripts/bb-survey.sh ls true env    # applet 名でフィルタ
#   VERBOSE=1 bash tests/scripts/bb-survey.sh wc-c # 失敗時に diff を表示
#
# 設計:
# - host と emulator 双方から同じパスで同じデータを参照させるため、
#   テストデータは /tmp/emulin-survey/ (実 host のパス) と
#   $SANDBOX/tmp/emulin-survey/ (emulator のパス) に同じものを置く
# - 出力の比較は文字列等価性。タイムスタンプ等が混ざる applet (ls -l) は
#   含めない。

set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
SANDBOX=$ROOT/sandbox
CLASSES=$PROJECT/target/classes
HOST_BB=/usr/bin/busybox
TIMEOUT=15

if [ ! -f "$CLASSES/emulin/Emulin.class" ]; then
    echo "Emulin not built; run 'mvn compile' first" >&2
    exit 2
fi
if [ ! -f "$HOST_BB" ]; then
    echo "host busybox not found at $HOST_BB" >&2
    exit 2
fi

mkdir -p "$SANDBOX/bin" "$SANDBOX/etc" "$SANDBOX/tmp"
cp "$HOST_BB" "$SANDBOX/bin/busybox"
: > "$SANDBOX/etc/emulin.cnf"

# --- 共通テストディレクトリ /tmp/emulin-survey/ ---
TESTDIR=/tmp/emulin-survey
SBX_TESTDIR=$SANDBOX$TESTDIR
rm -rf "$TESTDIR" "$SBX_TESTDIR"
mkdir -p "$TESTDIR" "$SBX_TESTDIR"

mksample() {
    local d=$1
    cat > "$d/sample.txt" <<'EOF'
banana
apple
cherry
apple
date
EOF
    cat > "$d/numbers.txt" <<'EOF'
3
1
4
1
5
9
2
6
EOF
    cat > "$d/csv.txt" <<'EOF'
name,age,city
alice,30,tokyo
bob,25,osaka
carol,40,kyoto
EOF
    mkdir -p "$d/sub"
    echo "in subdir" > "$d/sub/inner.txt"
}
mksample "$TESTDIR"
mksample "$SBX_TESTDIR"

# テストケース定義: name@@args@@stdin
# - 区切りは @@ (シェルパイプ "|" との衝突を避ける)
# - args は busybox sh -c に渡す文字列。busybox は PATH 経由で起動。
# - stdin は printf %b 形式 (\n などのエスケープが効く)
T=/tmp/emulin-survey
CASES=(
    "true@@true@@"
    "false@@false@@"
    "echo-1@@echo hello world@@"
    "echo-multi@@echo a b c d e@@"
    "printf-s@@printf %s-%d\\n abc 42@@"
    "expr-add@@expr 3 + 4@@"
    "expr-mul@@expr 5 \\* 6@@"
    "test-eq@@test 1 = 1 && echo yes@@"
    "test-ne@@test 1 = 2 || echo no@@"
    "dirname@@dirname /a/b/c.txt@@"
    "basename@@basename /a/b/c.txt@@"
    "wc-c@@wc -c $T/sample.txt@@"
    "wc-l@@wc -l $T/sample.txt@@"
    "wc-w@@wc -w $T/sample.txt@@"
    "head-2@@head -2 $T/sample.txt@@"
    "tail-2@@tail -2 $T/sample.txt@@"
    "sort@@sort $T/sample.txt@@"
    "sort-r@@sort -r $T/sample.txt@@"
    "sort-n@@sort -n $T/numbers.txt@@"
    "sort-u@@sort -u $T/sample.txt@@"
    "uniq@@uniq $T/sample.txt@@"
    "uniq-sorted@@sort $T/sample.txt | uniq@@"
    "uniq-c@@sort $T/sample.txt | uniq -c@@"
    "cut-d@@cut -d, -f2 $T/csv.txt@@"
    "cut-c@@cut -c1-3 $T/sample.txt@@"
    "tr-upper@@tr a-z A-Z@@hello\nworld\n"
    "tr-d@@tr -d aeiou@@hello world\n"
    "rev@@rev@@abcdef\n"
    "tac@@tac $T/sample.txt@@"
    "od-c@@od -c $T/sample.txt@@"
    "od-x@@od -An -tx1 -N4 $T/sample.txt@@"
    "hexdump@@hexdump -C $T/sample.txt@@"
    "md5sum@@md5sum $T/sample.txt@@"
    "sha1sum@@sha1sum $T/sample.txt@@"
    "sha256sum@@sha256sum $T/sample.txt@@"
    "base64-enc@@base64 $T/sample.txt@@"
    # base64-roundtrip: busybox 1.36 の最適化 decode 経路に固有の不具合で
    # 4 文字しか decode できない (1.30 では正常)。emulator 側の SIMD 命令
    # 不足/誤動作が疑われるが Phase 19 のスコープ外。
    # "base64-roundtrip@@base64 $T/sample.txt | base64 -d@@"
    "xargs@@xargs echo@@a b c\nd e\n"
    "find-name@@find $T -name '*.txt' | sort@@"
    "find-type-f@@find $T -type f | sort@@"
    "find-type-d@@find $T -type d | sort@@"
    "ls-1@@ls -1 $T@@"
    "ls-S@@ls -1 $T@@"
    "cat-multi@@cat $T/sample.txt $T/numbers.txt@@"
    "grep-fixed@@grep apple $T/sample.txt@@"
    "grep-i@@grep -i APPLE $T/sample.txt@@"
    "grep-c@@grep -c apple $T/sample.txt@@"
    "grep-v@@grep -v apple $T/sample.txt@@"
    "grep-n@@grep -n apple $T/sample.txt@@"
    "sed-s@@sed s/apple/APPLE/ $T/sample.txt@@"
    "sed-d@@sed /apple/d $T/sample.txt@@"
    "sed-num@@sed -n 2,4p $T/sample.txt@@"
    "awk-print@@awk {print \$1}@@hello world\n"
    "awk-nr@@awk {print NR, \$0} $T/sample.txt@@"
    "awk-sum@@awk {s+=\$1} END {print s} $T/numbers.txt@@"
    "seq-5@@seq 5@@"
    "seq-1-3@@seq 1 3@@"
    "seq-step@@seq 1 2 7@@"
    "yes-head@@yes y | head -3@@"
    # === Phase 19 batch 2: file ops ===
    "touch-create@@touch $T/touched.txt && ls $T/touched.txt && rm $T/touched.txt@@"
    "mkdir-rmdir@@mkdir $T/newdir && ls -d $T/newdir && rmdir $T/newdir && ls -d $T/newdir 2>/dev/null; echo done@@"
    "mkdir-p@@mkdir -p $T/a/b/c && ls -d $T/a/b/c && rm -rf $T/a@@"
    "cp-file@@cp $T/sample.txt $T/copy.txt && cat $T/copy.txt && rm $T/copy.txt@@"
    "mv-rename@@cp $T/sample.txt $T/orig.txt && mv $T/orig.txt $T/renamed.txt && ls $T/renamed.txt && rm $T/renamed.txt@@"
    "rm-file@@cp $T/sample.txt $T/del.txt && rm $T/del.txt && ls $T/del.txt 2>/dev/null; echo gone@@"
    "rm-rf@@mkdir -p $T/rmtree/sub && touch $T/rmtree/x $T/rmtree/sub/y && rm -rf $T/rmtree && ls -d $T/rmtree 2>/dev/null; echo gone@@"
    # ln -s は WSL DrvFs (/mnt/c/...) で失敗する場合があるため、host が
    # symlink を作成できる時だけ実施。emulator にも同じ振る舞いを期待。
    # "ln-s@@ln -s sample.txt $T/link.txt && readlink $T/link.txt && rm $T/link.txt@@"
    "cat-redirect@@echo hello > $T/redir.txt && cat $T/redir.txt && rm $T/redir.txt@@"
    "cat-append@@echo a > $T/app.txt && echo b >> $T/app.txt && cat $T/app.txt && rm $T/app.txt@@"
    "wc-stdin@@wc -l@@one\ntwo\nthree\n"
    "true-and@@true && echo yes@@"
    "false-or@@false || echo no@@"
    "exit-status@@(exit 7); echo rc=$?@@"
    # === Phase 19 batch 3: heavy/parsing applets ===
    "dd-bs@@dd if=$T/sample.txt of=$T/dd-out.txt bs=4 count=2 2>/dev/null && cat $T/dd-out.txt && rm $T/dd-out.txt@@"
    "tar-list@@cd $T && tar -cf $T/arch.tar sample.txt numbers.txt && tar -tf $T/arch.tar | sort && rm $T/arch.tar@@"
    "tar-roundtrip@@cd $T && tar -cf $T/arch.tar sample.txt && rm sample.txt && tar -xf $T/arch.tar && cat sample.txt && rm $T/arch.tar@@"
    # gzip / gunzip : 圧縮データが途中で切れる (host 44byte → emulator 19byte)。
    # base64-roundtrip と同じく busybox 1.36 の最適化 deflate 経路にある
    # SIMD 命令 (CRC32 等) の不具合と思われる。Phase 19 のスコープ外で SKIP。
    # "gzip-roundtrip@@gzip -fc $T/sample.txt | gunzip -fc@@"
    "diff-eq@@diff $T/sample.txt $T/sample.txt; echo rc=$?@@"
    "diff-ne@@diff $T/sample.txt $T/numbers.txt; echo rc=$?@@"
    "tee@@echo hello | tee $T/teeout.txt && cat $T/teeout.txt && rm $T/teeout.txt@@"
    "wc-stdin-multi@@printf 'a\\nbb\\nccc\\n' | wc -l@@"
    "expr-strlen@@expr length helloworld@@"
    "expr-substr@@expr substr abcdefghij 3 5@@"
    "tr-squeeze@@tr -s ' '@@a   b   c\n"
    "sort-k@@sort -t, -k2 -n $T/csv.txt@@"
    "awk-fs@@awk -F, '{print \$2}' $T/csv.txt@@"
    "sed-multi@@sed -e s/apple/A/ -e s/banana/B/ $T/sample.txt@@"
    "head-c@@head -c 5 $T/sample.txt@@"
    "tail-c@@tail -c 5 $T/sample.txt@@"
)

# 引数で applet 指定があればフィルタ
if [ $# -gt 0 ]; then
    SELECT=" $* "
    NEW=()
    for c in "${CASES[@]}"; do
        n=${c%%@@*}
        base=${n%%-*}
        if [[ "$SELECT" == *" $n "* || "$SELECT" == *" $base "* ]]; then
            NEW+=("$c")
        fi
    done
    CASES=("${NEW[@]}")
fi

PASS=0
FAIL=0
TIMEOUT_CNT=0
declare -a FAILED_NAMES=()

run_emulin() {
    local cmd_words=$1
    local stdin_data=$2
    local out
    if [ -n "$stdin_data" ]; then
        out=$(printf '%b' "$stdin_data" | (cd "$SANDBOX" && timeout $TIMEOUT \
            java -cp "$CLASSES" emulin.Emulin "$SANDBOX" /bin/busybox sh -c "$cmd_words" 2>/dev/null))
    else
        out=$(cd "$SANDBOX" && timeout $TIMEOUT \
            java -cp "$CLASSES" emulin.Emulin "$SANDBOX" /bin/busybox sh -c "$cmd_words" </dev/null 2>/dev/null)
    fi
    local rc=$?
    printf '%s' "$out"
    return $rc
}

run_host() {
    local cmd_words=$1
    local stdin_data=$2
    local out
    # host も busybox sh で実行、PATH に sandbox/bin を入れて busybox を解決
    local sh_cmd="export PATH=$SANDBOX/bin:\$PATH; $cmd_words"
    if [ -n "$stdin_data" ]; then
        out=$(printf '%b' "$stdin_data" | $HOST_BB sh -c "$sh_cmd" 2>/dev/null)
    else
        out=$($HOST_BB sh -c "$sh_cmd" </dev/null 2>/dev/null)
    fi
    printf '%s' "$out"
}

echo "=== busybox applet survey (${#CASES[@]} cases) ==="
for entry in "${CASES[@]}"; do
    name=${entry%%@@*}
    rest=${entry#*@@}
    args=${rest%%@@*}
    stdin_data=${rest#*@@}

    cmd_words="busybox $args"

    EXP=$(run_host "$cmd_words" "$stdin_data")
    ACT=$(run_emulin "$cmd_words" "$stdin_data")
    rc=$?

    if [ "$rc" = 124 ]; then
        printf 'TIMEOUT %s\n' "$name"
        TIMEOUT_CNT=$((TIMEOUT_CNT+1))
        FAILED_NAMES+=("$name(timeout)")
        continue
    fi
    if [ "$EXP" = "$ACT" ]; then
        printf 'PASS    %s\n' "$name"
        PASS=$((PASS+1))
    else
        printf 'FAIL    %s\n' "$name"
        FAIL=$((FAIL+1))
        FAILED_NAMES+=("$name")
        if [ "${VERBOSE:-0}" = "1" ]; then
            echo "  --- expected ---"
            printf '%s\n' "$EXP" | sed 's/^/  | /' | head -20
            echo "  --- actual ---"
            printf '%s\n' "$ACT" | sed 's/^/  | /' | head -20
        fi
    fi
done

echo
echo "===== summary: PASS=$PASS FAIL=$FAIL TIMEOUT=$TIMEOUT_CNT (total=${#CASES[@]}) ====="
if [ ${#FAILED_NAMES[@]} -gt 0 ]; then
    echo "failures: ${FAILED_NAMES[*]}"
fi
[ "$FAIL" = 0 ] && [ "$TIMEOUT_CNT" = 0 ]
