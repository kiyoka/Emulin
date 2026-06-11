#!/usr/bin/env bash
# bench-git-clone.sh — issue #221: 看板 workload (git clone over HTTPS) を native で実測する。
#
# emulin の看板 = 「HTTPS clone 動作」。git clone は git → git-remote-https を fork+exec し、TLS
# (gnutls/OpenSSL) で pack を受信、index-pack で解凍 (zlib) + delta 解決、checkout で working tree を
# 書き出す = crypto + 圧縮 + multi-process + 大量 file I/O の総合 workload。3d-2c-20 (#265) で fork が
# native 対応し git clone が完走するようになった。本スクリプトは大きめの実リポジトリ
# (既定 google/mozc、depth1 で pack ~29MB / checkout ~193MB) を native で clone して所要時間を出す。
#
# ★native の優位: HTTPS の TLS は AES-NI 等で crypto-heavy。native は実 CPU で走るので速い。software は
#   crypto emulation が遅く、大きな repo では実用的でない (server idle timeout / 桁違いに遅い)。よって
#   既定は native のみ計測。BENCH_GIT_SOFTWARE=1 で software も試せる (小 repo 推奨)。
#
# host git + git-core helper (git-remote-https) + ldd 依存 lib + ld.so + 証明書を sandbox に置く。
# network/git/kvm が無い環境は SKIP。計測値を出すだけ (pass/fail でない、network 依存なので CI 外)。
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes
ASM=$(find "$HOME/.m2" -name "asm-9.6.jar" 2>/dev/null | head -1)
CP="$CLASSES${ASM:+:$ASM}"
JOPT="--enable-native-access=ALL-UNNAMED -XX:-UsePerfData -Xmx6g"
URL=${BENCH_GIT_URL:-https://github.com/google/mozc}
DEPTH=${BENCH_GIT_DEPTH:-1}
POOL=${EMULIN_NATIVE_POOL_MB:-8192}
TIMEOUT=${BENCH_GIT_TIMEOUT:-1800}

[ -f "$CLASSES/emulin/Emulin.class" ] || { echo "SKIP : Emulin not built"; exit 2; }
GIT=$(command -v git) || { echo "SKIP : host git not found"; exit 2; }
GIT=$(readlink -f "$GIT")
GITCORE=$(git --exec-path 2>/dev/null)
RHTTP=$(readlink -f "$GITCORE/git-remote-http" 2>/dev/null)
[ -x "$RHTTP" ] || { echo "SKIP : git-remote-http not found (HTTPS clone 不可)"; exit 2; }
HAVE_KVM=0; [ -r /dev/kvm ] && [ -w /dev/kvm ] && HAVE_KVM=1
[ "$HAVE_KVM" = 1 ] || { echo "SKIP : /dev/kvm not accessible (native backend 不可)"; exit 2; }

SB=$(mktemp -d -t emulin-gitclone.XXXXXX); trap 'rm -rf "$SB"' EXIT
mkdir -p "$SB"/{bin,etc,tmp,lib64,usr/bin,usr/lib/git-core,root} "$SB/etc/ssl/certs"
: > "$SB/etc/emulin.cnf"
cp /lib64/ld-linux-x86-64.so.2 "$SB/lib64/"
cp "$GIT" "$SB/usr/bin/git"
# git-core helper (HTTPS clone = git-remote-https → git-remote-http symlink)
cp "$(readlink -f "$GITCORE/git")" "$SB/usr/lib/git-core/git" 2>/dev/null
cp "$RHTTP" "$SB/usr/lib/git-core/git-remote-http"
ln -sf git-remote-http "$SB/usr/lib/git-core/git-remote-https"
# git + git-remote-http の全依存 lib を ldd で集約
for b in "$GIT" "$RHTTP"; do
    ldd "$b" 2>/dev/null | grep -oE '/lib[^ ]*\.so[^ ]*' | sort -u | while read -r l; do
        d="$SB$(dirname "$l")"; mkdir -p "$d"; cp "$(readlink -f "$l")" "$d/$(basename "$l")" 2>/dev/null; done
done
cp /etc/ssl/certs/ca-certificates.crt "$SB/etc/ssl/certs/" 2>/dev/null
cp /etc/resolv.conf "$SB/etc/" 2>/dev/null
# safe.directory=* (ownership check 無効化) + CA path 明示
printf '[safe]\n\tdirectory = *\n[http]\n\tsslCAInfo = /etc/ssl/certs/ca-certificates.crt\n' > "$SB/root/.gitconfig"
NLIB=$(find "$SB" -name '*.so*' | wc -l)

run_clone() {  # run_clone <backend> <dest> → RC、所要秒は stderr
    local be=$1 dest=$2 t0 rc
    rm -rf "$SB$dest"
    t0=$(date +%s)
    ( cd "$SB" && timeout "$TIMEOUT" env HOME=/root EMULIN_NATIVE_POOL_MB="$POOL" EMULIN_BACKEND="$be" \
        java $JOPT -cp "$CP" emulin.Emulin "$SB" /usr/bin/git clone --depth "$DEPTH" --single-branch "$URL" "$dest" \
        < /dev/null > "$SB/tmp/clone_$be.log" 2>&1 )
    rc=$?
    printf '%ds' "$(( $(date +%s) - t0 ))" >&2
    return $rc
}

echo "===== issue #221: git clone HTTPS ($URL, depth=$DEPTH, $NLIB libs) native ====="
echo -n "(native) clone ... "
if run_clone native /tmp/repo 2>/tmp/_bt; then
    gitsz=$(du -sh "$SB/tmp/repo/.git" 2>/dev/null | cut -f1)
    wtsz=$(du -sh "$SB/tmp/repo" 2>/dev/null | cut -f1)
    nfiles=$(find "$SB/tmp/repo" -type f 2>/dev/null | wc -l)
    head=$( cd "$SB/tmp/repo" 2>/dev/null && git -c safe.directory='*' rev-parse --short HEAD 2>/dev/null )
    echo "$(cat /tmp/_bt) ✅ OK"
    echo "    .git=$gitsz  worktree=$wtsz  files=$nfiles  HEAD=$head"
else
    echo "$(cat /tmp/_bt) ❌ FAIL/timeout (RC=$?)"
    echo "    --- tail of clone log ---"; tail -8 "$SB/tmp/clone_native.log" | sed 's/^/    /'
fi

if [ "${BENCH_GIT_SOFTWARE:-0}" = 1 ]; then
    echo -n "(software) clone ... "
    if run_clone software /tmp/repo_s 2>/tmp/_bt; then
        echo "$(cat /tmp/_bt) ✅ OK  ($(du -sh "$SB/tmp/repo_s/.git" 2>/dev/null | cut -f1))"
    else
        echo "$(cat /tmp/_bt) ❌ FAIL/timeout (software は crypto emulation が遅く大 repo は非実用)"
    fi
fi
rm -f /tmp/_bt
