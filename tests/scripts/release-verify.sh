#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/release-verify.sh — 出荷 zip に対する「利用者の道筋」検査
#
#  issue #939: 3 リリース連続で、**公開した後**に「手順が通らない」ことに気づいている。
#
#    0.8.1 … #919 launcher に setcred が無く README どおりの手順が失敗 → zip 差し替え
#    0.8.2 … #932 非 ASCII の argv 破壊で apt install が 7 パッケージ失敗 → zip 差し替え
#    0.8.3 … WSL2 で claude auth login すると setcred から見えない (UNC 手打ちが必要)
#
#  3 回とも「**テストは全部緑なのに利用者の手順が通らない**」形で、しかも
#  **出荷物 (zip) を対象にしていれば公開前に落ちていた**。
#  そこで、公開する zip そのものを対象に、利用者がなぞる道筋を検査する。
#
#  ★ 検査は過去の実害と 1 対 1 で対応させ、各検査に issue 番号を書く。
#    「なぜこの検査があるか」が消えると、いずれ「無駄だから」と外される。
#
#  使い方:
#      bash tests/scripts/release-verify.sh <path-to-zip>
#      FULL=1 bash tests/scripts/release-verify.sh <zip>   # apt install まで通す (約 10 分)
#
#  ★ 対象は **公開物を落としてきた zip** にすること。手元のビルドではなく、
#    利用者が受け取るバイト列を検査する (SHA256 の突合だけでは中身の手順は分からない)。
# --------------------------------------------------------------------
set -u

ZIP=${1:-}
if [ -z "$ZIP" ] || [ ! -f "$ZIP" ]; then
    echo "usage: bash tests/scripts/release-verify.sh <path-to-release-zip>" >&2
    exit 2
fi
PROJECT=$(cd "$(dirname "$0")/../.." && pwd -P)
POM_VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$PROJECT/pom.xml" | head -1)

PASS=0; FAIL=0
ok()   { echo "PASS  $*"; PASS=$((PASS+1)); }
ng()   { echo "FAIL  $*"; FAIL=$((FAIL+1)); }
note() { echo "      $*"; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "===== release-verify: $(basename "$ZIP") (pom=$POM_VERSION) ====="
unzip -q "$ZIP" -d "$WORK" || { echo "FAIL  zip を展開できない"; exit 1; }
DIST=$(find "$WORK" -mindepth 1 -maxdepth 1 -type d | head -1)
[ -d "$DIST" ] || { echo "FAIL  展開先にディレクトリが無い"; exit 1; }

# --------------------------------------------------------------------
# 1. 版の一貫性 (issue #929: 版を上げた直後に旧版の jar を出荷した)
# --------------------------------------------------------------------
case "$(basename "$ZIP")" in
    *"$POM_VERSION"*) ok "zip 名が pom の版 ($POM_VERSION) と一致" ;;
    *) ng "zip 名が pom の版と違う: $(basename "$ZIP") vs $POM_VERSION" ;;
esac
case "$(basename "$DIST")" in
    *"$POM_VERSION"*) ok "展開後のディレクトリ名が版と一致" ;;
    *) ng "ディレクトリ名が版と違う: $(basename "$DIST")" ;;
esac
JARS=$(ls "$DIST"/lib/emulin-*-all.jar 2>/dev/null | wc -l)
JAR=$(ls "$DIST"/lib/emulin-*-all.jar 2>/dev/null | head -1)
if [ "$JARS" -eq 1 ] && [ "$(basename "$JAR")" = "emulin-$POM_VERSION-all.jar" ]; then
    ok "lib に jar は 1 つだけ ($(basename "$JAR"))"
else
    ng "lib の jar が想定と違う ($JARS 個): $(ls "$DIST"/lib/ | tr '\n' ' ')"
fi
if unzip -p "$JAR" emulin/Version.class 2>/dev/null | strings | grep -q "^$POM_VERSION\$"; then
    ok "jar の Version 文字列が $POM_VERSION"
else
    ng "jar の Version 文字列が $POM_VERSION でない"
fi

# --------------------------------------------------------------------
# 2. README が書いている launcher サブコマンドが出荷 launcher に実在するか
#    (issue #919: README どおり `emulin.bat setcred` を叩くと「そんなコマンドは無い」)
# --------------------------------------------------------------------
SUBS=$(grep -aoh 'emulin\.\(bat\|sh\) [a-z][a-z-]*' "$PROJECT/README.md" "$PROJECT/README.ja.md" 2>/dev/null \
       | awk '{print $2}' | sort -u | grep -avx 'setcred\|sshd' ; \
       grep -aoh 'emulin\.\(bat\|sh\) \(setcred\|sshd\)' "$PROJECT/README.md" "$PROJECT/README.ja.md" 2>/dev/null \
       | awk '{print $2}' | sort -u)
MISSING=""
for sub in $(echo "$SUBS" | sort -u); do
    case "$sub" in /*|"") continue ;; esac          # パス指定 (emulin.bat /usr/bin/...) は対象外
    if ! grep -aq "$sub" "$DIST/emulin.bat" || ! grep -aq "$sub" "$DIST/emulin.sh"; then
        MISSING="$MISSING $sub"
    fi
done
if [ -z "$MISSING" ]; then
    ok "README が書く launcher サブコマンドはすべて出荷 launcher に在る"
else
    ng "出荷 launcher に無いサブコマンド:$MISSING  (README どおりの手順が失敗する)"
fi

# --------------------------------------------------------------------
# 3. 出荷 jar + 出荷 rootfs で guest が動くか / 非 ASCII の argv が壊れないか
#    (issue #932: 非 ASCII の argv/env が二重エンコードされ apt install が失敗した)
# --------------------------------------------------------------------
if [ ! -f "$DIST/rootfs.tar.gz" ] && [ ! -d "$DIST/rootfs" ]; then
    note "rootfs が無い bundle なので guest 検査は skip"
else
    [ -d "$DIST/rootfs" ] || tar -xzf "$DIST/rootfs.tar.gz" -C "$DIST"
    # Windows 向け bundle は symlink を Cygwin magic file にしてあるので、Linux でも同じ扱いにする
    export EMULIN_FORCE_CYGWIN_SYMLINK=1
    export EMULIN_BACKEND=${EMULIN_BACKEND:-auto}
    UNAME=$( cd "$DIST/rootfs/root" 2>/dev/null && \
             timeout 300 java -Xmx2g -jar "$JAR" "$DIST/rootfs" /bin/uname -a 2>/dev/null | tail -1 )
    if echo "$UNAME" | grep -q "Emulin $POM_VERSION"; then
        ok "出荷 jar + 出荷 rootfs で guest が起動 ($UNAME)"
    else
        ng "guest が起動しない / 版が違う: [$UNAME]"
    fi

    # 非 ASCII の argv と、そのパスでの open。ő (U+0151) と 日本語 を使う。
    #   ★ ここが壊れると apt (ca-certificates の Hungarian 名 cert) が失敗する = #932 の実害。
    # ★ 検査自身の落とし穴: **dash の二重引用符は \305 のようなエスケープを解釈しない**。
    #   `f="/tmp/rv_\305\221.txt"` と書くと中身は純 ASCII (バックスラッシュ + 数字) のままで、
    #   非 ASCII を一度も通さずに PASS する。負のコントロール (#932 修正前の jar) を当てて
    #   初めて気付いた。**必ず printf で組み立てる**こと。
    #   さらに、判定は **exec 経由**でなければ意味が無い ([ -f ] は builtin なので argv を通らない)。
    cat > "$DIST/rootfs/nonascii-check.sh" <<'EOS'
f=$(printf '/tmp/rv_\305\221_\346\227\245\346\234\254\350\252\236.txt')
printf 'ok' > "$f" 2>/dev/null
# /bin/cat を **exec** して argv 経由で開かせる (#932 が壊したのはこの経路)
if [ "$(/bin/cat "$f" 2>/dev/null)" = "ok" ]; then echo NONASCII_OK; else echo NONASCII_NG; fi
rm -f "$f"
EOS
    OUT=$( cd "$DIST/rootfs/root" 2>/dev/null && \
           timeout 300 java -Xmx2g -jar "$JAR" "$DIST/rootfs" /bin/sh /nonascii-check.sh 2>/dev/null )
    if echo "$OUT" | grep -q NONASCII_OK; then
        ok "非 ASCII の argv とファイル名が壊れない (#932 の回帰)"
    else
        ng "非 ASCII の argv/ファイル名が壊れる: [$OUT]"
    fi
    rm -f "$DIST/rootfs/nonascii-check.sh"
fi

# --------------------------------------------------------------------
# 4. 出荷 jar の setcred が README の記述と一致するか
#    (0.8.3: 認証方式を一本化したのに、選択肢が 2 つ出ていないか / 消えていないか)
# --------------------------------------------------------------------
MENU=$( printf '\n' | timeout 120 java -Duser.home="$WORK/nohome" -cp "$JAR" emulin.SetCred 2>&1 \
        | grep -a '^  \[[0-9]\]' )
# ★ ラベル名だけで判定してはいけない: 0.8.2 では**旧 setup-token のラベルが
#   "Claude (Pro/Max subscription)"** だった。名前が同じで中身が別物なので、
#   文字列一致では新旧を区別できない (この検査を 0.8.2 に当てたら素通しした = 負のコントロールで発覚)。
#   → 新実装だけが持つ「.credentials.json を読む」注記で判定する。
CLAUDE_N=$( echo "$MENU" | grep -ac 'Claude (Pro/Max subscription).*credentials\.json' )
LEGACY_N=$( echo "$MENU" | grep -ac 'setup-token' )
if [ "$CLAUDE_N" = "1" ] && [ "$LEGACY_N" = "0" ]; then
    ok "setcred の Claude はブラウザ認証 1 択 (.credentials.json を読む / setup-token は出ない)"
else
    ng "setcred のメニューが想定と違う (browser-login=$CLAUDE_N setup-token=$LEGACY_N)"
    echo "$MENU" | sed 's/^/        /'
fi

# --------------------------------------------------------------------
# 5. 診断が EMULIN_TRACE_FILE に落ち、画面に出ないか
#    (issue #934: [mitm] が 5 秒ごとに TUI へ出て使い物にならなかった)
# --------------------------------------------------------------------
if [ -d "$DIST/rootfs" ]; then
    TRACE="$WORK/trace.log"
    ERR=$( cd "$DIST/rootfs/root" && EMULIN_TRACE_FILE="$TRACE" EMULIN_TRACE_MITM=1 \
           timeout 300 java -Xmx2g -jar "$JAR" "$DIST/rootfs" /bin/true 2>&1 >/dev/null \
           | grep -ac '^\[mitm\]\|^\[egress\]\|^\[cred\]' )
    if [ "${ERR:-0}" = "0" ]; then
        ok "診断が画面 (stderr) に出ない (#934 の回帰)"
    else
        ng "診断が画面に $ERR 行出ている (TUI を壊す)"
    fi
fi

# --------------------------------------------------------------------
# 6. (opt-in) 出荷 zip で README の apt install が完走するか — 約 10 分
#    (issue #932 の実害そのもの。既定では走らせない)
# --------------------------------------------------------------------
if [ "${FULL:-0}" = "1" ] && [ -d "$DIST/rootfs" ]; then
    echo "--- FULL=1: apt-get install -y nodejs npm を通します (約 10 分) ---"
    LOG="$WORK/apt.log"
    ( cd "$DIST/rootfs/root" && timeout 1800 java -Xmx4g -XX:-DontCompileHugeMethods -jar "$JAR" "$DIST/rootfs" \
        /bin/bash -c 'apt-get update && apt-get install -y nodejs npm; echo "===EXIT=$?"' </dev/null ) > "$LOG" 2>&1
    if grep -aq '===EXIT=0' "$LOG" && [ "$(grep -ac 'dpkg: error\|Errors were' "$LOG")" = "0" ]; then
        ok "README の apt install が完走 (dpkg エラー 0)"
    else
        ng "apt install が失敗した: $(grep -a 'dpkg: error\|Errors were\|===EXIT' "$LOG" | head -3)"
    fi
else
    note "apt install の検査は skip (FULL=1 で有効)"
fi

echo
echo "===== release-verify: PASS=$PASS FAIL=$FAIL ====="
[ "$FAIL" = "0" ] || exit 1
exit 0
