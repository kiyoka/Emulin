#!/usr/bin/env bash
# --------------------------------------------------------------------
#  dist/gen-quickstart.sh — 配布 zip 同梱の QUICKSTART.txt を生成する
#
#  使い方:
#    dist/gen-quickstart.sh <出力パス> <kind> <platform>
#      kind     : minimal | jre | demo
#                 minimal … busybox のみ・JRE 非同梱 (host の java が要る)
#                 jre     … busybox + 同梱 JRE
#                 demo    … Debian base rootfs + 同梱 JRE (0.8.0 の配布物)
#      platform : windows | linux | macos | any
#                 any … 両方の launcher を同梱する platform 非依存 zip
#                       (build-dist.sh)。起動例を 2 行並べる。
#
#  ★ issue #891: 以前は dist/README.txt (238 行) を手で保守し、3 つの
#    build script が個別にコピーしていた。README.md と**二重管理**になるので
#    必ず古くなる (最後の更新が 2026-07-18 のまま「JRE 11 以降」「busybox 前提」
#    「0.5.0 release bundle」と書かれた状態で 0.8.0 に同梱されていた)。
#
#    → zip に入れるのは**この短い QUICKSTART だけ**にし、詳細は GitHub の
#      README.md へ誘導する。README.md は release ごとに実機検証と一緒に
#      更新されている (#888) ので、そちらだけを真実とする。
#
#  ★ 生成をこの 1 本に寄せてあるのは、build script 側に heredoc を散らすと
#    「1 つだけ直して他が古いまま」になるため (公開 #898 / #903 と同じ形)。
#    kind/platform の分岐はここだけを見ればよい。
# --------------------------------------------------------------------
set -eu

OUT=${1:?usage: gen-quickstart.sh <out> <minimal|jre|demo> <windows|linux|macos>}
KIND=${2:?}
PLATFORM=${3:?}

case "$KIND" in minimal|jre|demo) ;; *) echo "gen-quickstart: unknown kind=$KIND" >&2; exit 1 ;; esac
# ★ platform は build script 側で `uname -s` から決まることがあり、想定外の値
#   (FreeBSD 等) が来うる。そこでビルドを落とすのは筋が悪い (同梱ドキュメントの
#   ために release ビルドを失敗させる価値は無い) ので、警告して sh 系に倒す。
case "$PLATFORM" in
  windows|linux|macos|any) ;;
  *) echo "gen-quickstart: warning: unknown platform=$PLATFORM, treating as linux" >&2
     PLATFORM=linux ;;
esac

# launcher の呼び方は platform で決まる (README.md 側と表記を揃える)。
case "$PLATFORM" in
  windows) LAUNCH="emulin.bat" ;;
  any)     LAUNCH="emulin.bat"; LAUNCH_ALT="./emulin.sh" ;;   # 両方同梱
  *)       LAUNCH="./emulin.sh" ;;
esac

# ★ issue #985 (0.9.0): ランチャー (#948) が既定の入口。**demo bundle だけ**が持つ
#   (dist/launchers の最小 launcher に app モードは無い。tests/scripts/launcher-subcommands.sh
#   がその差を検査している)。Windows はダブルクリック用の .bat を同梱する。
case "$PLATFORM" in
  windows) LAUNCH_APP="emulin-app.bat" ;;
  *)       LAUNCH_APP="$LAUNCH app" ;;
esac

# any のときだけ「もう一方の launcher」を 1 行添える。
alt_note() {
  [ "$PLATFORM" = any ] || return 0
  echo
  echo "  (Windows は emulin.bat、Linux / macOS は ./emulin.sh を使います)"
}

{
case "$KIND" in
  demo) cat <<EOF
Emulin — クイックスタート
=========================

Java で動く x86-64 / i386 Linux ELF エミュレータです。Debian 13 (trixie) の
rootfs と JRE を同梱しているので、解凍してすぐ Linux 環境が立ち上がります。
EOF
  ;;
  jre) cat <<EOF
Emulin — クイックスタート
=========================

Java で動く x86-64 / i386 Linux ELF エミュレータです。busybox の rootfs と
JRE を同梱しているので、解凍してすぐ Linux シェルが立ち上がります。
EOF
  ;;
  minimal) cat <<EOF
Emulin — クイックスタート
=========================

Java で動く x86-64 / i386 Linux ELF エミュレータです。busybox の rootfs を
同梱した最小構成です。
EOF
  ;;
esac

echo
echo "------------------------------------------------------------"
echo "必要な物"
echo "------------------------------------------------------------"
if [ "$KIND" = minimal ]; then
cat <<EOF
  Java 25 以降 (PATH に java が入っていること)

  ※ Java FFM API を使うため 25 以降が必要です (それ以前では起動しません)。
    自前で用意したくない場合は JRE 同梱版の zip を使ってください。
EOF
else
cat <<EOF
  なし。JRE を同梱しているので Java の install は不要です。

  ※ 同梱 JRE を使わず自前の java で起動する場合は 25 以降が必要です
    (Java FFM API を使うため)。
EOF
fi

echo
echo "------------------------------------------------------------"
echo "起動"
echo "------------------------------------------------------------"
if [ "$KIND" = demo ]; then
cat <<EOF
  $LAUNCH_APP
      ランチャー (推奨)。AI エージェントの導入・credential の登録・端末の起動を
      ボタンで行えます。Windows ではダブルクリックで開きます。

  $LAUNCH                        対話シェル (bash) に直行する
  $LAUNCH ls -la /tmp            1 コマンド実行
  $LAUNCH /usr/bin/git --version 実機 binary を直接指定

$LAUNCH で直接起動したときは、ログインするユーザ (root / 一般ユーザ) を選べます。
EOF
else
cat <<EOF
  $LAUNCH                        対話シェル (busybox ash)
  $LAUNCH ls -la /tmp            1 コマンド実行
EOF
alt_note
fi

if [ "$KIND" = demo ]; then
cat <<EOF

------------------------------------------------------------
API キーを渡さずに AI コーディングエージェントを使う
------------------------------------------------------------
Emulin は **実 API キーを guest (エミュレータの中) に一切渡しません**。
host 側に保存したキーを、通信の途中で差し替えます。guest が持つのは
placeholder だけなので、エージェントが暴走してもキーは読み取れません。

  $LAUNCH_APP の [Set up credentials]   登録・確認・削除を画面で行う (推奨)
  $LAUNCH setcred                      同じことを CLI の対話ウィザードで行う

対応: Claude / OpenAI Codex / Gemini / GitHub (gh・git push)

エージェント本体は同梱していません。$LAUNCH_APP の
[Install Claude Code] / [Install Codex CLI] で guest に導入してください
(nodejs/npm の導入を含むので 20 分ほどかかります)。

導入と登録が済んだら、[Open terminal] (または $LAUNCH) で:

  \$ claude
  \$ codex

※ guest の中で claude /login や gh auth login、codex login は実行しないで
  ください。実キーが guest 側に書き戻され、この仕組みの意味がなくなります。
EOF
fi

cat <<EOF

------------------------------------------------------------
速度について
------------------------------------------------------------
HW 仮想化 (Windows = Hyper-V「Windows ハイパーバイザー プラットフォーム」/
Linux = KVM) が使える環境では、launcher が自動で guest を実 vCPU 上で
実行します。使えない環境では software エミュレータに自動で切り替わるので、
設定は不要です。

  EMULIN_BACKEND=software   常に software (最も移植性が高い)
  EMULIN_BACKEND=native     常に native (HW 仮想化が無いと起動時エラー)
  EMULIN_BACKEND=auto       使えれば native (既定)

------------------------------------------------------------
詳細
------------------------------------------------------------
インストール手順・設定・既知の制約・トラブルシュートは GitHub の README を
参照してください (release ごとに実機で検証しています):

  https://github.com/kiyoka/Emulin#readme

同梱物のライセンスは COPYING / NOTICE.txt / THIRD-PARTY-LICENSES.md を
参照してください。
EOF
} > "$OUT"

echo "[gen-quickstart] $OUT (kind=$KIND platform=$PLATFORM)"
