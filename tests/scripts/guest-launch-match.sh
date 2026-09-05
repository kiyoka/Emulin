#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/guest-launch-match.sh
#
#  issue #963: ランチャーは **emulin.bat を経由せず** guest を起動する
#  (cmd.exe / java.exe はコンソールアプリで、GUI から起動すると黒い窓が出るため)。
#  そのぶん起動条件を Java 側 (GuestLaunch) にも持つことになり、
#  **#919 と同じ「2 系統あって片方だけ直す」危険**が生まれた。
#
#  ここで launcher (dist/build-demo-bundle.sh が生成する emulin.bat / emulin.sh) と
#  GuestLaunch.java の値を突き合わせる。
#
#  ★ 実際 #959 では、同型の検査 (jlink-modules-match) が
#    「2 系統のうち片方だけ直した」事故をその日のうちに捕まえている。
#
#  終了コード: 0=PASS / 1=FAIL
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
GEN=$PROJECT/dist/build-demo-bundle.sh
JAVA_SRC=$PROJECT/src/main/java/emulin/GuestLaunch.java

fail=0
echo "===== guest 起動条件の一致検査 (issue #963) ====="

# --- JVM オプション ---
BAT_OPTS=$(grep -ah 'set "JVMOPT=' "$GEN" | sed 's/.*JVMOPT=//; s/"$//' \
           | tr ' ' '\n' | grep -a '^-' | grep -av 'NATIVE_ACCESS' | sort -u | tr '\n' ' ')
JAVA_OPTS=$(sed -n '/String\[\] JVM_OPTS/,/};/p' "$JAVA_SRC" \
           | grep -ao '"-[^"]*"' | tr -d '"' | sort -u | tr '\n' ' ')
echo "  launcher : $BAT_OPTS"
echo "  Java     : $JAVA_OPTS"
if [ "$BAT_OPTS" != "$JAVA_OPTS" ]; then
    echo "FAIL    JVM オプションが食い違っている"
    fail=1
fi

# --- guest env の既定値 ---
for kv in EMULIN_INHERIT_ENV:1 EMULIN_BACKEND:auto EMULIN_NATIVE_POOL_MB:2048; do
    k=${kv%%:*}; v=${kv##*:}
    if ! grep -aq "$k=$v" "$GEN"; then
        echo "FAIL    launcher に $k=$v が無い (この検査の前提が壊れている)"; fail=1; continue
    fi
    if ! grep -aq "\"$k\", *\"$v\"" "$JAVA_SRC"; then
        echo "FAIL    GuestLaunch に $k=$v が無い (launcher と食い違う)"; fail=1
    fi
done

# --- issue #985: app (ランチャー) 起動には pool の既定を渡さない ---
#   ランチャーは自分が開くセッション (Open terminal / sshd) に 1024 を選ぶが、
#   **利用者が設定した値は尊重する**。その判別ができるのは、launcher が
#   「自分が入れた既定 2048」をランチャーに渡さないからで、ここが戻ると
#   「未設定」と「利用者が 2048 を指定」が区別できなくなり、**画面に何も出ないまま
#   2048 のまま遅くなる**。値そのものではなく **app で外していること**を見る。
BAT_POOL=$(grep -a 'set "EMULIN_NATIVE_POOL_MB=2048"' "$GEN" | head -1)
case "$BAT_POOL" in
    *'"%~1"=="app"'*) ;;
    *) echo "FAIL    emulin.bat が app 経由で pool の既定を外していない (#985)"; fail=1 ;;
esac
SH_POOL=$(grep -a -B4 'EMULIN_NATIVE_POOL_MB:-2048' "$GEN" | head -20)
case "$SH_POOL" in
    *'!= "app"'*) ;;
    *) echo "FAIL    emulin.sh が app 経由で pool の既定を外していない (#985)"; fail=1 ;;
esac
# ★ 検査の前提そのものも見る (行が見つからなければ上の case は素通りする)。
[ -n "$BAT_POOL" ] || { echo "FAIL    emulin.bat の pool 既定行が見つからない (検査の前提が壊れている)"; fail=1; }
[ -n "$SH_POOL" ]  || { echo "FAIL    emulin.sh の pool 既定行が見つからない (検査の前提が壊れている)"; fail=1; }

# --- Open terminal と sshd が同じ pool を使うか (issue #985) ---
#   別々の数字を書くと、片方だけ直る形になる。
grep -aq 'AGENT_POOL_MB' "$JAVA_SRC" \
  || { echo "FAIL    GuestLaunch に AGENT_POOL_MB が無い (#985)"; fail=1; }
grep -aq 'GuestLaunch.AGENT_POOL_MB' "$PROJECT/src/main/java/emulin/SshdService.java" \
  || { echo "FAIL    SshdService が pool の値を自前で持っている (#985: 2 箇所に書かない)"; fail=1; }
grep -aq 'GuestLaunch.AGENT_POOL_MB' "$PROJECT/src/main/java/emulin/LauncherApp.java" \
  || { echo "FAIL    LauncherApp が pool の値を自前で持っている (#985: 2 箇所に書かない)"; fail=1; }

# --- issue #996: 非 root で走らせる job は uid と HOME が対 ---
#   出荷 launcher の :choose_login は UID / GID / HOME を **3 つ一緒に**設定している。
#   Java 側が HOME を落とすと、uid 1000 で走るのにホームが root のものになり、
#   Install Claude Code が /root/.local/bin に入って、README どおりの非 root
#   セッションから **command not found** になる (0.9.0 の実機確認で踏んだ)。
#   ★ HOME は **継承させない**。putIfAbsent だと host の HOME がそのまま guest に渡る。
grep -aq 'env.put( "HOME"' "$JAVA_SRC" \
  || { echo "FAIL    GuestLaunch が HOME を明示していない (#996: host の HOME が漏れる)"; fail=1; }
grep -aq '"/home/" + user' "$JAVA_SRC" \
  || { echo "FAIL    GuestLaunch が非 root の HOME を /home/<user> にしていない (#996)"; fail=1; }
CHOOSE=$(sed -n '/^:choose_apply/,/^goto :eof/p' "$GEN")
if [ -z "$CHOOSE" ]; then
    echo "FAIL    launcher の :choose_login が見つからない (この検査の前提が壊れている)"; fail=1
else
    for v in EMULIN_UID EMULIN_GID HOME; do
        case "$CHOOSE" in
            *"$v"*) ;;
            *) echo "FAIL    launcher の :choose_login が $v を設定していない (#996)"; fail=1 ;;
        esac
    done
fi

# --- issue #996: ランチャーは判定より先に非 root ユーザーを用意する ---
#   ランチャーは長らく `emulin-adduser --detect` (既存を拾うだけ) しか呼んでおらず、
#   ユーザーを**作る**のは引数なしの emulin.bat だけだった。そのため展開直後に
#   ランチャーだけで進めると、Install が root のホームに入り、あとから作られた
#   ユーザーで端末が開いて **claude が command not found** になる。
LAUNCHER_SRC=$PROJECT/src/main/java/emulin/LauncherApp.java
grep -aq 'emulin-adduser " + shellQuote' "$LAUNCHER_SRC" \
  || { echo "FAIL    LauncherApp が非 root ユーザーを作らない (#996: --detect だけでは作られない)"; fail=1; }
DETECT_BODY=$(sed -n '/private void detectAll()/,/^  }$/p' "$LAUNCHER_SRC")
if [ -z "$DETECT_BODY" ]; then
    echo "FAIL    detectAll() が見つからない (この検査の前提が壊れている)"; fail=1
else
    case "$DETECT_BODY" in
        *ensureGuestUser*) ;;
        *) echo "FAIL    detectAll が先に ensureGuestUser を呼んでいない (#996)"; fail=1 ;;
    esac
fi

# --- cwd を rootfs にしているか (外すと guest が即死する) ---
grep -aq 'pb.directory( rootfs )' "$JAVA_SRC" \
  || { echo "FAIL    GuestLaunch が cwd を rootfs にしていない"; fail=1; }

# --- 黒い窓を出さない起動になっているか ---
grep -aq 'javaw.exe' "$JAVA_SRC" \
  || { echo "FAIL    GuestLaunch が javaw を使っていない (コンソールが出る)"; fail=1; }
grep -aq 'javaw.exe' "$GEN" \
  || { echo "FAIL    launcher の app 起動が javaw になっていない"; fail=1; }

if [ "$fail" = 0 ]; then
    echo "PASS    guest-launch-match (launcher と GuestLaunch が一致)"
    exit 0
fi
exit 1
