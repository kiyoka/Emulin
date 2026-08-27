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
