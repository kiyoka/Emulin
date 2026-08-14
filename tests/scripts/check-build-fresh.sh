#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/check-build-fresh.sh
#
#  issue #924: 「target/classes がソースより古い状態でテストを走らせない」ガード。
#
#  テストは事前ビルド済みの target/classes を直接 classpath にする。ソースを触った
#  あとビルドせずに走らせると:
#    - 緑でも赤でも **直前の編集を含まない別物を測っている** (無意味な結果)
#    - run-all は dist-smoke が内部で mvn package を呼ぶため、それが実ビルドになり
#      同時に走る他のスイートが書き換え中の target/classes を読んで大量の偽 FAIL になる
#  実際に #921 の作業中、run-all が 26 件の偽 FAIL を出して原因調査が本題から逸れた。
#
#  そこで「古ければテストを実行せずに止める」。無言で走って嘘の結果を出すより良い。
#
#  使い方: bash tests/scripts/check-build-fresh.sh [呼び出し元の名前] || exit 2
#  終了コード: 0 = 新鮮 (テスト続行可) / 2 = 未ビルド or 古い (呼び出し側は中止する)
# --------------------------------------------------------------------
set -u

PROJECT=$(cd "$(dirname "$0")/../.." && pwd -P)
CALLER=${1:-tests/scripts/run-all.sh}
CLASSES=$PROJECT/target/classes

if [ ! -f "$CLASSES/emulin/Emulin.class" ]; then
    echo "ERROR: Emulin is not built ($CLASSES/emulin/Emulin.class)" >&2
    echo "       run 'mvn compile' first" >&2
    exit 2
fi

# 最新のソース (java / pom.xml) と 最新の class を mtime で比べる。
#   mvn は変更のあった class だけを更新するので、特定の 1 つ (Emulin.class 等) との
#   比較では取りこぼす。両方とも「最新のもの」同士で比べる。
newest() {
    # $@ = find の対象。見つからなければ 0 を返す。
    find "$@" -printf '%T@\n' 2>/dev/null | sort -n | tail -1 | cut -d. -f1
}
SRC_T=$(newest "$PROJECT/src/main/java" -name '*.java')
POM_T=$(newest "$PROJECT" -maxdepth 1 -name 'pom.xml')
CLS_T=$(newest "$CLASSES" -name '*.class')
SRC_T=${SRC_T:-0}; POM_T=${POM_T:-0}; CLS_T=${CLS_T:-0}
[ "$POM_T" -gt "$SRC_T" ] && SRC_T=$POM_T

if [ "$SRC_T" -gt "$CLS_T" ]; then
    # 逃げ道は用意するが**黙って通さない**。ログに必ず 1 行残す
    #   (「検査が走ったことも検査する」= #874 の教訓)。
    if [ -n "${EMULIN_SKIP_FRESH_CHECK:-}" ]; then
        echo "WARNING: stale build (target/classes older than sources) —" \
             "continuing because EMULIN_SKIP_FRESH_CHECK is set." >&2
        exit 0
    fi
    echo "ERROR: target/classes is older than the sources (stale build)" >&2
    echo "       newest source: $(date -d "@$SRC_T" '+%Y-%m-%d %H:%M:%S')" >&2
    echo "       newest class : $(date -d "@$CLS_T" '+%Y-%m-%d %H:%M:%S')" >&2
    echo "" >&2
    echo "       Running tests now would measure a build that does NOT include your" >&2
    echo "       latest edit, and run-all would rebuild in the middle of the run" >&2
    echo "       (dist-smoke calls 'mvn package'), poisoning the other suites." >&2
    echo "       Build first, then run the tests:" >&2
    echo "" >&2
    echo "         mvn compile && bash $CALLER" >&2
    exit 2
fi
exit 0
