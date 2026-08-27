#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/sshkeys-smoke.sh
#
#  issue #964: ランチャーからの公開鍵登録が **秘密鍵を通さない**ことを検査する。
#
#  ★ ここが最重要。秘密鍵を guest の authorized_keys に書き込むと、#401 の不変条件
#    (実 credential は host 側にのみ置く) が真っ向から破れる。しかも **sshd は動いてしまう**
#    ので、気付く手掛かりが無い。
#
#  ★ 拡張子で判定しないことも検査する (.pub という名前の秘密鍵を作って当てる)。
#  ★ fingerprint は ssh-keygen が使えるなら **ssh-keygen を oracle にして**突き合わせる。
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP (未 build)
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes

if [ ! -f "$CLASSES/emulin/SshKeysSmoke.class" ]; then
    echo "SKIP sshkeys-smoke : not built"
    exit 2
fi

OUT=$(java -cp "$CLASSES" emulin.SshKeysSmoke </dev/null 2>&1); RC=$?
printf '%s\n' "$OUT" | sed 's/^/  /'

if [ "$RC" != 0 ] || ! printf '%s' "$OUT" | grep -q 'SshKeys smoke OK'; then
    echo "FAIL    sshkeys-smoke (exit=$RC)"
    exit 1
fi

# --- fingerprint を ssh-keygen と突き合わせる (使えるときだけ) ---
if command -v ssh-keygen >/dev/null 2>&1; then
    TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
    ssh-keygen -q -t ed25519 -N '' -C 'smoke@test' -f "$TMP/k" >/dev/null 2>&1
    if [ -f "$TMP/k.pub" ]; then
        WANT=$(ssh-keygen -lf "$TMP/k.pub" 2>/dev/null | awk '{print $2}')
        cat > "$TMP/Fp.java" <<'JAVAEOF'
package emulin;
import java.io.File;
public class Fp { public static void main(String[] a) throws Exception {
  SshKeys.PubKey k = SshKeys.parse(new File(a[0]), "x");
  System.out.println(k == null ? "NG" : k.fingerprint); } }
JAVAEOF
        mkdir -p "$TMP/emulin" && mv "$TMP/Fp.java" "$TMP/emulin/Fp.java"
        if javac -cp "$CLASSES" -d "$TMP" "$TMP/emulin/Fp.java" >/dev/null 2>&1; then
            GOT=$(java -cp "$CLASSES:$TMP" emulin.Fp "$TMP/k.pub" 2>/dev/null)
            if [ "$WANT" = "$GOT" ]; then
                echo "  ok   fingerprint が ssh-keygen と一致 ($GOT)"
            else
                echo "  FAIL fingerprint が ssh-keygen と違う: want=$WANT got=$GOT"
                echo "FAIL    sshkeys-smoke (fingerprint)"
                exit 1
            fi
        fi
    fi
else
    echo "  (ssh-keygen が無いので fingerprint の突合は skip)"
fi

echo "PASS    sshkeys-smoke (公開鍵の登録 / 秘密鍵の拒否 #964)"
exit 0
