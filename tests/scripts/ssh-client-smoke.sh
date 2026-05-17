#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/ssh-client-smoke.sh
#
#  issue #47: emulin 内 ssh client → emulin 内 sshd への real login が
#  完走するか hermetic に検証 (self-loop)。
#
#  シナリオ:
#    1. server 用 sandbox を作って emulin で /usr/sbin/sshd を起動 (port N)
#    2. client 用 sandbox を作って emulin で /usr/bin/ssh を起動
#    3. client → server で `echo SSH-REAL-LOGIN-OK` を実行
#    4. output が期待値と一致したら PASS
#
#  emulin の ssh client + sshd 両方が同時動作する full e2e test。
#  Phase 1〜4 sshd の動作 + issue #34 (AF_UNIX) / #35-#37 (AF_INET6) /
#  PR #46 (readline) / PR #34 (ssh client) が全部協調動作する確認。
#
#  SKIP 条件:
#    - host に /usr/sbin/sshd / /usr/bin/ssh / ssh-keygen が無い
#    - emulin の target/classes が無い
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes

if [ ! -f "$CLASSES/emulin/Emulin.class" ]; then
    echo "SKIP ssh-client-smoke : Emulin not built ($CLASSES/emulin/Emulin.class)"
    exit 2
fi
for tool in /usr/sbin/sshd /usr/bin/ssh ssh-keygen; do
    if ! command -v "$tool" >/dev/null 2>&1 && [ ! -x "$tool" ]; then
        echo "SKIP ssh-client-smoke : host に $tool が無い"
        exit 2
    fi
done

SRV_SB=$(mktemp -d -t emulin-ssh-srv.XXXXXX)
CLI_SB=$(mktemp -d -t emulin-ssh-cli.XXXXXX)
KEYDIR=$(mktemp -d -t emulin-ssh-key.XXXXXX)
SSHD_LOG=$(mktemp -t emulin-ssh-srv-log.XXXXXX)
trap 'pkill -9 -f "java.*emulin.Emulin .*$SRV_SB" 2>/dev/null || true;
      pkill -9 -f "java.*emulin.Emulin .*$CLI_SB" 2>/dev/null || true;
      rm -rf "$SRV_SB" "$CLI_SB" "$KEYDIR" "$SSHD_LOG" 2>/dev/null || true' EXIT

# port を PID で散らす
PORT=$(( (($$ % 10000) + 20300) ))

# ----- 共通 helper -----
copy_solib() {
    local target_sb=$1 p=$2
    [ -f "$p" ] || return 0
    local real; real=$(readlink -f "$p"); [ -f "$real" ] || return 0
    mkdir -p "$target_sb$(dirname "$real")"
    cp -L "$real" "$target_sb${real}" 2>/dev/null || true
    if [ "$real" != "$p" ]; then
        mkdir -p "$target_sb$(dirname "$p")"
        cp -L "$real" "$target_sb${p}" 2>/dev/null || true
    fi
}
sandbox_init() {
    local SB=$1
    mkdir -p "$SB"/{bin,usr/bin,usr/sbin,etc/ssh,tmp,dev,root/.ssh,var/empty,run/sshd,lib64,usr/lib/x86_64-linux-gnu,dev/pts,usr/share/terminfo}
    if [ -L /lib ]; then ln -sf usr/lib "$SB/lib"; else mkdir -p "$SB/lib/x86_64-linux-gnu"; fi
    copy_solib "$SB" /lib64/ld-linux-x86-64.so.2
    for lib in libnss_files.so.2 libnss_compat.so.2 libnss_dns.so.2; do
        copy_solib "$SB" /lib/x86_64-linux-gnu/$lib
    done
    cp /bin/bash "$SB/bin/bash"; ln -sf bash "$SB/bin/sh"
    while IFS= read -r line; do
        [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]] && copy_solib "$SB" "${BASH_REMATCH[1]}"
    done < <(ldd /bin/bash)
    [ -d /usr/share/terminfo ] && cp -r /usr/share/terminfo/* "$SB/usr/share/terminfo/" 2>/dev/null || true
    cat > "$SB/etc/nsswitch.conf" <<'EOF'
passwd: files
group: files
EOF
    cat > "$SB/etc/shells" <<EOF
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
        [ -e "$SB/dev/$d" ] || touch "$SB/dev/$d"
        chmod 666 "$SB/dev/$d" 2>/dev/null || true
    done
}

# ----- server sandbox -----
sandbox_init "$SRV_SB"
cp /usr/sbin/sshd "$SRV_SB/usr/sbin/sshd"
while IFS= read -r line; do
    [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]] && copy_solib "$SRV_SB" "${BASH_REMATCH[1]}"
done < <(ldd /usr/sbin/sshd)
ssh-keygen -t ed25519 -N '' -q -f "$SRV_SB/etc/ssh/ssh_host_ed25519_key" -C srv \
    || { echo "FAIL ssh-client-smoke : host key gen failed"; exit 1; }
ssh-keygen -t ed25519 -N '' -q -f "$KEYDIR/clientkey" -C cli \
    || { echo "FAIL ssh-client-smoke : client key gen failed"; exit 1; }
cp "$KEYDIR/clientkey.pub" "$SRV_SB/root/.ssh/authorized_keys"
chmod 700 "$SRV_SB/root/.ssh"; chmod 600 "$SRV_SB/root/.ssh/authorized_keys"
cat > "$SRV_SB/etc/ssh/sshd_config" <<EOF
Port $PORT
HostKey /etc/ssh/ssh_host_ed25519_key
ListenAddress 127.0.0.1
UsePAM no
PasswordAuthentication no
PubkeyAuthentication yes
PermitRootLogin yes
StrictModes no
LogLevel ERROR
AuthorizedKeysFile /root/.ssh/authorized_keys
EOF

# ----- client sandbox -----
sandbox_init "$CLI_SB"
cp /usr/bin/ssh "$CLI_SB/usr/bin/ssh"
while IFS= read -r line; do
    [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]] && copy_solib "$CLI_SB" "${BASH_REMATCH[1]}"
done < <(ldd /usr/bin/ssh)
[ -f /etc/ssh/ssh_config ] && cp /etc/ssh/ssh_config "$CLI_SB/etc/ssh/ssh_config"
cp "$KEYDIR/clientkey" "$CLI_SB/root/.ssh/clientkey"
chmod 700 "$CLI_SB/root/.ssh"; chmod 600 "$CLI_SB/root/.ssh/clientkey"

# ----- server 起動 -----
(
    cd "$SRV_SB"
    java -XX:-UsePerfData -XX:-DontCompileHugeMethods -cp "$CLASSES" \
        emulin.Emulin "$SRV_SB" \
        /usr/sbin/sshd -D -d -e -p "$PORT" -f /etc/ssh/sshd_config
) > "$SSHD_LOG" 2>&1 &
SRV_PID=$!

ready=0
for i in $(seq 1 20); do
    sleep 1
    if grep -q "Server listening on 127.0.0.1 port $PORT" "$SSHD_LOG" 2>/dev/null; then
        ready=1
        break
    fi
    if ! kill -0 $SRV_PID 2>/dev/null; then
        echo "FAIL ssh-client-smoke : sshd died before listening"
        tail -10 "$SSHD_LOG"
        exit 1
    fi
done
if [ "$ready" != "1" ]; then
    echo "FAIL ssh-client-smoke : sshd not ready within 20s"
    tail -10 "$SSHD_LOG"
    kill -9 $SRV_PID 2>/dev/null
    exit 1
fi

# ----- emulin client ssh で remote command 実行 -----
EXPECTED='SSH-REAL-LOGIN-OK'
OUT=$(
    cd "$CLI_SB"
    timeout 60 java -XX:-UsePerfData -XX:-DontCompileHugeMethods -cp "$CLASSES" \
        emulin.Emulin "$CLI_SB" \
        /usr/bin/ssh -i /root/.ssh/clientkey \
            -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
            -o LogLevel=ERROR \
            -p "$PORT" root@127.0.0.1 "echo $EXPECTED" 2>/dev/null
)
RC=$?

kill -9 $SRV_PID 2>/dev/null
wait $SRV_PID 2>/dev/null

# output から余分 (Emulin banner 等) を除いて取り出す
GOT=$(echo "$OUT" | grep -E "^$EXPECTED$" | tail -1)

if [ "$RC" != "0" ]; then
    echo "FAIL ssh-client-smoke : emulin ssh client exit=$RC"
    echo "--- sshd log tail ---"
    tail -15 "$SSHD_LOG"
    exit 1
fi
if [ "$GOT" != "$EXPECTED" ]; then
    echo "FAIL ssh-client-smoke : expected '$EXPECTED' got '$GOT'"
    echo "--- client raw output ---"
    echo "$OUT"
    exit 1
fi

echo "PASS ssh-client-smoke"
exit 0
