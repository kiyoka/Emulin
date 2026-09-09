#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/test-registration-check.sh
#
#  issue #1015: **検査が runner に登録されていることを検査する。**
#
#  ★ 実害 (2026-09-07、#1013):
#    `sshd-pty-smoke.sh` は今回の欠陥の経路そのもの (`ssh -tt` で pty を確保して
#    `tty` を実行) を検査していた。**バグを戻すと実際に FAIL する**ことも確認済み。
#    にもかかわらず 0.9.1 は 327 PASS で緑だった —— **このスクリプトが run-all にも
#    run-fast にも登録されておらず、一度も走っていなかった**ため。
#    「327 PASS」は「通った」ではなく「**呼ばれなかった**」を意味していた。
#
#  ★ 同時に見つかった構造の歪み:
#    - 未登録のスクリプトが **30 件**あった (ssh 軸は 4 本とも未登録)
#    - **run-all が run-fast より 11 件少なかった** (リリースゲートの方が手薄)
#    - run-fast は `sys_segv_child_64` を SKIP し「segv-child-smoke.sh で検証する」と
#      表示するが、**その segv-child-smoke が run-all では走っていなかった**
#      (検査が在るように読めて、実際には無い)
#
#  ★ なぜ腐るか: バイナリテスト (.c) は `src/*.c` から**自動列挙**なので落ちない。
#    シェルの smoke だけ手で **3 か所** (連想配列・並列実行ループ・報告ループ) に
#    書く必要があり、しかも報告ループには `[ -f ... ] || continue` があるので
#    **実行され忘れたラベルは黙って消える**。#112 で書いた「軸は作るだけでは腐る」。
#
#  検査するもの:
#    A. tests/scripts の smoke が run-all に登録されているか (除外は理由付きで明示)
#    B. run-fast のラベルが run-all にもあるか (run-fast ⊄ run-all を許さない)
#    C. 各 runner 内で、配列のラベルが**すべてのループ**に現れるか (3 リストの一致)
#    D. 除外リストに、もう存在しないスクリプトが残っていないか
#
#  ★ 負のコントロール: 登録を 1 行消して FAIL になることを確認してから入れること。
#
#  終了コード: 0=PASS / 1=FAIL
# --------------------------------------------------------------------
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
S=$ROOT/scripts

FAIL=0
ng() { echo "FAIL    test-registration-check: $*"; FAIL=$((FAIL + 1)); }

# ★ 走らせない物は **理由を書いて**ここに置く。理由の無い除外を作らない。
#   (書式: <script 名>|<理由>)
EXEMPT=(
  "run-all|runner 本体"
  "run-fast|runner 本体"
  "run-test|runner 本体 (1 件実行)"
  "run-network|runner 本体 (ネットワーク軸)"
  "check-build-fresh|runner が直接呼ぶビルド鮮度ガード (#924)"
  "flake-rate|他のテストの再現率を測るメタ道具 (#133)"
  "release-verify|出荷 zip を引数に取るリリース時ゲート (#939)"
  "claude-smoke|実 credential とネットワークが要る"
  "bb-survey|busybox applet の調査ツール (合否を持たない)"
  "bench-curl|ベンチマーク"
  "bench-git-clone|ベンチマーク"
  "bench-go|ベンチマーク"
  "bench-gonogo|ベンチマーク"
  "bench-native|ベンチマーク"
  "kvm-latency|ベンチマーク"
  # ★ issue #1024: 除外理由が**事実と違っていた**。3 本とも mktemp で自前の sandbox を
  #   作るので「専用 rootfs」は要らない。要るのは /dev/kvm で、無ければ自分で SKIP する。
  #   理由が違うと「動かせない物」として誰も再検討しない。native-oracle-full は登録した。
  #   ★ 恐らく **WHP(Windows) 側の bundle** (build-native-oracle-full-bundle.sh が作る
  #     whp-oracle-full.ps1 用の zip) と混同したもの。あちらは確かに bundle が要る。
  #   ★ 残り 2 本は **timeout が 1 か所も無い** = 止まったら永久に終わらない。実際 2026-09-09 に
  #     native-oracle の「guest 内で gcc」ケースが **38 分ハング**した (jstack: Kernel.vfork の
  #     CountDownLatch で park、CPU は 38 分で 400ms = 止まっている)。**登録する前に timeout を
  #     入れること**。無制限のテストを runner に載せるとゲート自体が固まる。
  "native-oracle|timeout が無く固まりうる (2026-09-09 に guest 内 gcc で 38 分ハング)。先に timeout を入れる"
  "native-pf-oracle|同上: timeout が無い。#PF 特化なので native-oracle-full と範囲も重なる"
  "build-native-oracle-full-bundle|オラクル用 bundle の生成ツール"
)

is_exempt() {
  local n=$1 e
  for e in "${EXEMPT[@]}"; do [ "${e%%|*}" = "$n" ] && return 0; done
  return 1
}

# runner の連想配列から「登録済み script 名」を取り出す
labels_of()  { sed -n '/declare -A EXT_LABELS=(/,/^)/p' "$1" | grep -oE '^\s*\[[a-z0-9-]+\]' | tr -d ' []'; }
scripts_of() { sed -n '/declare -A EXT_LABELS=(/,/^)/p' "$1" | grep -oE '\$ROOT/scripts/[a-z0-9-]+\.sh' | sed 's|.*/||;s|\.sh$||' | sort -u; }

# --- A. すべての smoke が run-all に登録されているか -------------------
REG_ALL=$(scripts_of "$S/run-all.sh")
for f in "$S"/*.sh; do
  n=$(basename "$f" .sh)
  is_exempt "$n" && continue
  if ! printf '%s\n' "$REG_ALL" | grep -qx "$n"; then
    ng "$n.sh が run-all に登録されていない (走っていない)。登録するか、理由付きで EXEMPT へ"
  fi
done

# --- D. 除外リストの掃除 ----------------------------------------------
for e in "${EXEMPT[@]}"; do
  n=${e%%|*}
  [ -f "$S/$n.sh" ] || ng "EXEMPT に無い script が残っている: $n (消したら除外も消す)"
done

# --- B. run-fast のラベルが run-all にもあるか -------------------------
#   ★ 逆転していた (run-all の方が 11 件少なかった)。リリースゲートが手薄になる。
while IFS= read -r n; do
  [ -z "$n" ] && continue
  printf '%s\n' "$REG_ALL" | grep -qx "$n" \
    || ng "$n.sh は run-fast にあるが run-all に無い (全体テストの方が手薄になる)"
done <<< "$(scripts_of "$S/run-fast.sh")"

# --- C. 各 runner 内でラベルが全ループに現れるか -----------------------
#   ★ 配列に足してループに足し忘れると、**黙って走らない**。
#     報告ループの `[ -f ... ] || continue` が握り潰すので画面にも出ない。
for r in run-all run-fast; do
  file=$S/$r.sh
  loops=$(grep -cE '^for label in .*; do' "$file")
  [ "$loops" -ge 2 ] || ng "$r.sh のラベルループが $loops 個しか無い (構造が変わった。この検査を見直すこと)"
  for lb in $(labels_of "$file"); do
    # ★ ラベルは **実行ループ 1 つ + 報告ループ** の計 2 つに現れるのが正。
    #   実行ループは複数ある (並列群 / ssh 軸の別バッチ #1015) ので個数では縛らない。
    #   dist-smoke だけは #924 で単独実行するため、報告ループの 1 つだけで良い。
    want=2
    [ "$lb" = "dist-smoke" ] && want=1
    got=$(grep -E '^for label in .*; do' "$file" | grep -cE "(^| )$lb( |;)")
    [ "$got" -ge "$want" ] \
      || ng "$r.sh: ラベル $lb がループ $got/$want 個にしか無い (実行か報告のどちらかが欠けている)"
  done
done

if [ "$FAIL" = 0 ]; then
  echo "PASS    test-registration-check (検査が runner に登録されている #1015)"
  exit 0
fi
exit 1
