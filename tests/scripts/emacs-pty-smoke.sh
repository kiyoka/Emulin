#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/emacs-pty-smoke.sh
#
#  issue #76 の hermetic 動作確認。
#
#  emulin 上で emacs を --batch で起動し、make-process(:connection-type 'pty)
#  で `bash -i` を pty 経由で起動、`echo $((6*7))` を送って `42` が filter に
#  返ってくれば PASS。これは M-x shell の pty I/O 往復そのもの。
#
#  検証する経路 (issue #76 の 3 修正):
#    - access("/dev/pts/N") を成功させ emacs に pty を採用させる
#      (失敗すると pipe fallback して hang していた)
#    - pselect6 の pipe availability 判定 (pty master/slave の read 端)
#    - fork での CLOEXEC 伝播 → emacs の exec-status pipe が exec 後に閉じ、
#      親の read(status pipe) が EOF を受け取って先へ進む (これが無いと hang)
#
#  SKIP 条件:
#    - emulin が未ビルド
#    - emacs rootfs が無い (emacs-nox + bash 入り、~数百 MB なのでリポジトリ外)
#        → EMACS_SANDBOX=<rootfs> で指定。Windows 配布由来の Cygwin symlink
#          rootfs の場合は EMACS_SANDBOX_CYG=1 も指定する。
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes
FATJAR=$(ls "$PROJECT"/target/emulin-*-all.jar 2>/dev/null | head -1)
CP="${FATJAR:-$CLASSES}"

if [ ! -f "$CLASSES/emulin/Emulin.class" ] && [ -z "$FATJAR" ]; then
    echo "SKIP emacs-pty-smoke : Emulin not built"
    exit 2
fi

SBROOT="${EMACS_SANDBOX:-}"
if [ -z "$SBROOT" ] || [ ! -x "$SBROOT/usr/bin/emacs-nox" ]; then
    echo "SKIP emacs-pty-smoke : emacs rootfs 無し (EMACS_SANDBOX=<rootfs>、usr/bin/emacs-nox 必須)"
    exit 2
fi
SB=$(cd "$SBROOT" && pwd -P)

CYG=""
[ "${EMACS_SANDBOX_CYG:-0}" = "1" ] && CYG="EMULIN_FORCE_CYGWIN_SYMLINK=1"

# pty 往復: prompt 待ち → echo 送信 → 出力待ち → exit。$((6*7)) は guest bash が
#   評価するので test 側 bash で展開されないよう single quote で囲む。
ELISP='(let ((p (make-process :name "sh" :command (list "/bin/bash" "-i") :connection-type (quote pty) :filter (lambda (proc str) (princ str)))))
  (accept-process-output p 4)
  (process-send-string p "echo TAG-$((6*7))\n")
  (accept-process-output p 3)
  (accept-process-output p 2)
  (process-send-string p "exit\n")
  (accept-process-output p 2)
  (princ "\n=RT-DONE=\n"))'

OUT=$(cd "$SB" && timeout 150 env -i \
    HOME=/root USER=root LOGNAME=root PATH=/usr/bin:/bin LANG=C.UTF-8 TERM=dumb \
    $CYG \
    java -Xmx6g -XX:-DontCompileHugeMethods -cp "$CP" emulin.Emulin \
    "$SB" /usr/bin/emacs-nox --batch -Q --eval "$ELISP" 2>/dev/null)
RC=$?

if [ "$RC" = 124 ]; then
    echo "FAIL emacs-pty-smoke : timeout (pty I/O hang の可能性)"
    exit 1
fi
if echo "$OUT" | grep -q "TAG-42"; then
    echo "PASS emacs-pty-smoke : pty round-trip OK (echo \$((6*7)) -> 42)"
    exit 0
fi

echo "FAIL emacs-pty-smoke : 'TAG-42' が返らない got='$(echo "$OUT" | tr -c '[:print:]' '.' | tail -c 200)'"
exit 1
