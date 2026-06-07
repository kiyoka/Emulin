#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/sshd-env-smoke.sh
#
#  issue #226 の hermetic 動作確認。
#
#  emulin 上で /usr/sbin/sshd を EMULIN_INHERIT_ENV=1 + ホスト env
#  MY_HOST_VAR_226=... 付きで起動し、ホスト側 ssh client から
#  `echo $MY_HOST_VAR_226` を実行して、ホストの環境変数が SSH セッションに
#  継承されていれば PASS。
#
#  仕組み: emulin core は sshd を継承 env 付きで起動するとき、guest env を
#  ~/.ssh/environment に書き出す (#226)。sshd_config の PermitUserEnvironment yes
#  が session 開始時にそれを読み込んで session env に足す。
#
#  SKIP 条件:
#    - host に /usr/sbin/sshd / ssh / ssh-keygen が無い
#    - emulin の target/classes が無い
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes

if [ ! -f "$CLASSES/emulin/Emulin.class" ]; then
    echo "SKIP sshd-env-smoke : Emulin not built ($CLASSES/emulin/Emulin.class)"
    echo "  run 'mvn compile' first"
    exit 2
fi
for tool in /usr/sbin/sshd ssh ssh-keygen; do
    if ! command -v "$tool" >/dev/null 2>&1 && [ ! -x "$tool" ]; then
        echo "SKIP sshd-env-smoke : host に $tool が無い"
        exit 2
    fi
done

SB=$(mktemp -d -t emulin-sshd-env.XXXXXX)
TKEYDIR=$(mktemp -d -t emulin-sshd-env-tkey.XXXXXX)
trap 'pkill -9 -f "emulin.Emulin $SB" 2>/dev/null || true;
      rm -rf "$SB" "$TKEYDIR" 2>/dev/null || true' EXIT

PORT=$(( (($$ % 10000) + 21000) ))   # sshd-smoke と衝突しないよう +21000

mkdir -p "$SB"/{bin,usr/bin,usr/sbin,etc/ssh,tmp,dev,root/.ssh,var/empty,run/sshd,lib64,usr/lib/x86_64-linux-gnu}
if [ -L /lib ]; then ln -sf usr/lib "$SB/lib"; else mkdir -p "$SB/lib/x86_64-linux-gnu"; fi

copy_lib() {
    local lib=$1 src
    for prefix in /lib/x86_64-linux-gnu /usr/lib/x86_64-linux-gnu; do
        src=$prefix/$lib
        if [ -f "$src" ]; then copy_solib "$src"; return 0; fi
    done
    return 1
}
copy_solib() {
    local p=$1 real
    [ -f "$p" ] || return 0
    real=$(readlink -f "$p"); [ -f "$real" ] || return 0
    mkdir -p "$SB$(dirname "$real")"; cp -L "$real" "$SB${real}" 2>/dev/null || true
    if [ "$real" != "$p" ]; then mkdir -p "$SB$(dirname "$p")"; cp -L "$real" "$SB${p}" 2>/dev/null || true; fi
}
copy_solib /lib64/ld-linux-x86-64.so.2
copy_deps_of() {
    local bin=$1; [ -f "$bin" ] || return 0
    while IFS= read -r line; do
        local p
        if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then p="${BASH_REMATCH[1]}"
        elif [[ "$line" =~ ^[[:space:]]*(/[^[:space:]]+) ]]; then p="${BASH_REMATCH[1]}"
        else continue; fi
        copy_solib "$p"
    done < <(ldd "$bin" 2>/dev/null)
}
copy_deps_of /usr/sbin/sshd
copy_deps_of /bin/bash
for lib in libnss_files.so.2 libnss_compat.so.2 libnss_dns.so.2; do copy_lib "$lib"; done

cp /usr/sbin/sshd "$SB/usr/sbin/sshd"
cp /bin/bash "$SB/bin/bash" 2>/dev/null
ln -sf bash "$SB/bin/sh"

ssh-keygen -t ed25519 -N '' -q -f "$SB/etc/ssh/ssh_host_ed25519_key" -C "emulin-sshd-env" \
    || { echo "FAIL sshd-env-smoke : ssh-keygen for host key failed"; exit 1; }
ssh-keygen -t ed25519 -N '' -q -f "$TKEYDIR/clientkey" -C "emulin-sshd-env-client" \
    || { echo "FAIL sshd-env-smoke : ssh-keygen for client key failed"; exit 1; }
cp "$TKEYDIR/clientkey.pub" "$SB/root/.ssh/authorized_keys"
chmod 700 "$SB/root/.ssh"
chmod 600 "$SB/root/.ssh/authorized_keys"

# sshd_config: #226 = PermitUserEnvironment yes で ~/.ssh/environment を読む。
cat > "$SB/etc/ssh/sshd_config" <<EOF
Port $PORT
HostKey /etc/ssh/ssh_host_ed25519_key
PidFile /run/sshd.pid
ListenAddress 127.0.0.1
UsePAM no
PasswordAuthentication no
PubkeyAuthentication yes
PermitRootLogin yes
StrictModes no
PrintMotd no
PrintLastLog no
X11Forwarding no
LogLevel INFO
AuthorizedKeysFile /root/.ssh/authorized_keys
PermitUserEnvironment yes
EOF

cat > "$SB/etc/nsswitch.conf" <<'EOF'
passwd:         files
group:          files
shadow:         files
hosts:          files dns
EOF
cat > "$SB/etc/shells" <<'EOF'
/bin/sh
/bin/bash
EOF
cat > "$SB/etc/passwd" <<'EOF'
root:x:0:0:root:/root:/bin/bash
sshd:x:74:74:Privilege-separated SSH:/run/sshd:/usr/sbin/nologin
EOF
cat > "$SB/etc/group" <<'EOF'
root:x:0:
sshd:x:74:
EOF
for d in null urandom zero tty random ptmx; do
    [ -e "$SB/dev/$d" ] || touch "$SB/dev/$d"; chmod 666 "$SB/dev/$d" 2>/dev/null || true
done

# emulin で sshd を起動。EMULIN_INHERIT_ENV=1 + ホスト env MY_HOST_VAR_226 を渡す。
#   emulin core が継承 env を /root/.ssh/environment に書き出す (#226)。
HOSTVAL='hello-from-host-226'
SSHD_LOG=$(mktemp -t emulin-sshd-env-log.XXXXXX)
(
    cd "$SB"
    EMULIN_INHERIT_ENV=1 MY_HOST_VAR_226="$HOSTVAL" \
    java -XX:-UsePerfData -XX:-DontCompileHugeMethods -cp "$CLASSES" \
        emulin.Emulin "$SB" \
        /usr/sbin/sshd -D -d -e -p "$PORT" -f /etc/ssh/sshd_config
) > "$SSHD_LOG" 2>&1 &
EPID=$!

ready=0
for i in $(seq 1 20); do
    sleep 1
    if grep -q "Server listening on 127.0.0.1 port $PORT" "$SSHD_LOG" 2>/dev/null; then ready=1; break; fi
    if ! kill -0 $EPID 2>/dev/null; then
        echo "FAIL sshd-env-smoke : emulin sshd died before listening"
        tail -20 "$SSHD_LOG"; rm -f "$SSHD_LOG"; exit 1
    fi
done
if [ "$ready" != "1" ]; then
    echo "FAIL sshd-env-smoke : sshd did not start listening within 20s"
    tail -20 "$SSHD_LOG"; kill -9 $EPID 2>/dev/null; rm -f "$SSHD_LOG"; exit 1
fi

# ssh client で `echo $MY_HOST_VAR_226` (remote 展開) を実行。
#   セッション env にホスト変数が継承されていれば値が返る。
OUT=$(timeout 20 ssh -p "$PORT" -i "$TKEYDIR/clientkey" \
    -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
    -o ConnectTimeout=10 -q \
    root@127.0.0.1 'echo "[$MY_HOST_VAR_226]"' 2>/dev/null)
RC=$?

kill -9 $EPID 2>/dev/null; wait $EPID 2>/dev/null

if [ "$RC" != "0" ]; then
    echo "FAIL sshd-env-smoke : ssh client exit=$RC"
    tail -30 "$SSHD_LOG" 2>/dev/null; rm -f "$SSHD_LOG"; exit 1
fi
rm -f "$SSHD_LOG"
EXPECTED="[$HOSTVAL]"
if [ "$OUT" != "$EXPECTED" ]; then
    echo "FAIL sshd-env-smoke : expected '$EXPECTED' got '$OUT' (host env not inherited into SSH session)"
    exit 1
fi

echo "PASS sshd-env-smoke"
exit 0
