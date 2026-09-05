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
# 2b. 出荷 launcher が `app` に native pool の既定を渡していないか
#     (issue #985: 渡すと launcher が「利用者が 2048 を指定した」と誤解し、
#      Open terminal が 1024 にならず **画面に何も出ないまま**遅くなる)
#
#     ★ 同型の検査は tests/scripts/guest-launch-match.sh にもあるが、あれは
#       **ソース (dist/build-demo-bundle.sh) を見ている**。生成が壊れれば
#       ソースは正しいまま出荷物だけ壊れるので、ここで**出荷物に当てる** (#939)。
# --------------------------------------------------------------------
BAT_POOL=$(grep -a 'set "EMULIN_NATIVE_POOL_MB=2048"' "$DIST/emulin.bat" | head -1)
SH_POOL=$(grep -a -B4 'EMULIN_NATIVE_POOL_MB:-2048' "$DIST/emulin.sh" | head -20)
if [ -z "$BAT_POOL" ] || [ -z "$SH_POOL" ]; then
    ng "出荷 launcher に pool の既定行が無い (この検査の前提が壊れている)"
else
    POOL_NG=""
    case "$BAT_POOL" in *'"%~1"=="app"'*) ;; *) POOL_NG="$POOL_NG emulin.bat" ;; esac
    case "$SH_POOL"  in *'!= "app"'*)     ;; *) POOL_NG="$POOL_NG emulin.sh"  ;; esac
    if [ -z "$POOL_NG" ]; then
        ok "出荷 launcher は app に pool の既定を渡さない (#985: Open terminal が 1024 になる)"
    else
        ng "app に pool の既定を渡している:$POOL_NG  (Open terminal が 2048 のまま遅くなる)"
    fi
fi

# --------------------------------------------------------------------
# 2c. 出荷 QUICKSTART.txt が「今の入口」を案内しているか
#     (issue #985: zip を展開して最初に読むのは QUICKSTART。ここが古いと、
#      0.9.0 の入口 (ランチャー) に一度も触れないまま 0.8.x の手順を踏ませる)
#
#     ★ 0.9.0 の 1 回目のビルドが実際にそうなっていた。**README は直っていたのに
#       同梱ドキュメントだけ 0.8.x のまま**で、release-verify も 12 PASS で素通しした
#       (README のサブコマンドは見ていたが、同梱ドキュメントの中身は誰も見ていなかった)。
#     ★ 「エージェントは同梱していない」も見る。QUICKSTART は 0.8.x から
#       「登録後はそのまま claude を起動できます」と書いていたが、claude は
#       同梱されておらず、そのまま打っても動かない。
# --------------------------------------------------------------------
QS="$DIST/QUICKSTART.txt"
if [ ! -f "$QS" ]; then
    ng "QUICKSTART.txt が同梱されていない"
elif [ ! -f "$DIST/emulin-app.bat" ]; then
    note "この bundle にランチャーが無いので QUICKSTART の入口検査は skip"
else
    if grep -aq 'emulin-app\.bat' "$QS"; then
        ok "出荷 QUICKSTART がランチャー (emulin-app.bat) を案内している"
    else
        ng "QUICKSTART がランチャーに触れていない (展開直後の利用者が旧手順を踏む)"
    fi
    if grep -aq 'Install Claude Code' "$QS"; then
        ok "出荷 QUICKSTART がエージェントの導入方法を書いている"
    else
        ng "QUICKSTART が導入に触れていない (claude は同梱されていないので打っても動かない)"
    fi
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
# 4b. setcred が **WSL2 側のログイン**を候補として出せるか (0.8.4)
#
#     ★ この検査を足した理由: 0.8.4 の版上げ枝を **修正のマージ前**に切ってしまい、
#       「0.8.4 と名乗るのに WSL2 対応が入っていない zip」を作りかけた (#929 と同じ型)。
#       版を上げる作業と修正が並行して進むと必ず起きるので、**その版の目玉が
#       出荷物に実在するか**を機械で確かめる。
#     Windows 以外では候補探索が動かないので、実装の有無で判定する。
# --------------------------------------------------------------------
if unzip -p "$JAR" emulin/SetCred.class 2>/dev/null | strings | grep -q "wsl.localhost"; then
    ok "setcred が WSL2 のログインを探せる (0.8.4 の目玉が出荷物に入っている)"
else
    ng "setcred に WSL2 探索が入っていない (版だけ上げて修正が入っていない可能性)"
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

# --------------------------------------------------------------------
# 7. 出荷 JRE でランチャー画面 (#948) が起動できるか
#    (issue #959: Swing は java.desktop を要るが、同梱 JRE には入っていなかった。
#     手元の開発 JDK では動くので **zip を作って初めて分かる**形だった)
#
#    ★ 「module が入っているか」だけを見る検査にしない。#939 の原則は
#      **利用者の手順が出荷物で通るか**。実際に出荷 JRE で AWT/Swing のクラスを
#      load させ、NoClassDefFoundError が出ないことを見る (画面は出さない)。
# --------------------------------------------------------------------
JREREL=$DIST/jre/release
JREJAVA=""
for c in "$DIST/jre/bin/java" "$DIST/jre/bin/java.exe"; do [ -f "$c" ] && JREJAVA=$c; done

if [ -z "$JREJAVA" ]; then
    ng "出荷 zip に JRE が入っていない"
elif [ ! -f "$JREREL" ]; then
    ng "出荷 JRE の release ファイルが読めない (module 構成を確認できない)"
elif grep -aq 'java\.desktop' "$JREREL"; then
    ok "出荷 JRE の MODULES に java.desktop がある (ランチャー画面 #948 が開ける)"
else
    ng "出荷 JRE に java.desktop が無い (#959: ランチャー画面が NoClassDefFoundError で開けない)"
fi

# ★ 追加で、**この host でそのまま動く JRE のときだけ** 実際に load させる。
#   Windows 向け zip を WSL から検査する場合はやらない: WSL 越しの java.exe は
#   **Linux パスを解決できず**、java.desktop が入っていても ClassNotFoundException で
#   落ちる (実測)。**正しい zip をブロックする検査**になってしまう。
if [ -n "$JREJAVA" ] && [ "${JREJAVA##*/}" = "java" ] \
   && { [ "$(uname -s)" = "Linux" ] || [ "$(uname -s)" = "Darwin" ]; } \
   && "$JREJAVA" -version >/dev/null 2>&1; then
    PROBE=$WORK/DesktopProbe.java
    cat > "$PROBE" <<'JAVAEOF'
public class DesktopProbe {
  public static void main( String[] a ) throws Exception {
    Class.forName( "javax.swing.JFrame" );
    Class.forName( "java.awt.LayoutManager" );
    System.out.println( "DESKTOP_OK" );
  }
}
JAVAEOF
    if OUT2=$( "$JREJAVA" -Djava.awt.headless=true "$PROBE" 2>&1 ) \
       && printf '%s' "$OUT2" | grep -aq DESKTOP_OK; then
        ok "出荷 JRE で実際に Swing/AWT を load できた"
    else
        # `Picked up JAVA_TOOL_OPTIONS:` は JVM が必ず先頭に出すノイズ。
        #   そのまま head すると**失敗理由が隠れる** (実際 0.8.5 で隠れた)。
        ng "出荷 JRE で Swing/AWT を load できない (#959): $(printf '%s' "$OUT2" | grep -av 'Picked up ' | grep -a . | head -2 | tr '\n' ' ')"
    fi
else
    note "出荷 JRE はこの host でそのまま実行できないので load までは未確認 (MODULES で判定した)"
fi

echo
echo "===== release-verify: PASS=$PASS FAIL=$FAIL ====="
[ "$FAIL" = "0" ] || exit 1
exit 0
