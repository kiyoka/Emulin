#!/usr/bin/env bash
# --------------------------------------------------------------------
#  tests/scripts/sshd-pty-smoke.sh
#
#  issue #322: sshd の PTY (対話) 経路の hermetic 回帰。sshd-smoke は非対話 exec のみ
#  だったので、ここでは ssh -tt の対話 PTY を検証する。
#
#  emulin 上で /usr/sbin/sshd (OpenSSH 10 privsep) を起動 → ホスト側 ssh client から
#  `ssh -tt` で publickey 認証 + PTY 確保し、PTY 内で `tty; echo PTY-OK-FROM-EMULIN` を
#  実行。期待文字列が返れば PASS。
#
#  検証する経路:
#    - OpenSSH 10 privsep: monitor が pty を確保し master/slave fd を SCM_RIGHTS で
#      session に渡す (mm_pty_allocate)。emulin の recvmsg/sendmsg の pty fd 受け渡しと
#      sendmsg framing 保持 (Kernel.pendingScmFds) が効いていないと
#      "mm_receive_fd: no message header" → mm_pty_allocate 失敗で対話が即切断される。
#
#  SKIP 条件:
#    - host に /usr/sbin/sshd / ssh / ssh-keygen が無い
#    - emulin の target/classes が無い
#    - port 衝突 (別 PID の sshd が emulin に listen 済み)
#
#  終了コード: 0=PASS / 1=FAIL / 2=SKIP
# --------------------------------------------------------------------
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes

if [ ! -f "$CLASSES/emulin/Emulin.class" ]; then
    echo "SKIP sshd-pty-smoke : Emulin not built ($CLASSES/emulin/Emulin.class)"
    echo "  run 'mvn compile' first"
    exit 2
fi
for tool in /usr/sbin/sshd ssh ssh-keygen; do
    if ! command -v "$tool" >/dev/null 2>&1 && [ ! -x "$tool" ]; then
        echo "SKIP sshd-pty-smoke : host に $tool が無い"
        exit 2
    fi
done

# 並列実行を考慮して TMP に独立 sandbox を作る
SB=$(mktemp -d -t emulin-sshd-pty-smoke.XXXXXX)
TKEYDIR=$(mktemp -d -t emulin-sshd-tkey.XXXXXX)
trap 'pkill -9 -f "emulin.Emulin $SB" 2>/dev/null || true;
      rm -rf "$SB" "$TKEYDIR" 2>/dev/null || true' EXIT

# port は PID + 20000 で重複回避 (10000~30000 範囲)
PORT=$(( (($$ % 10000) + 20000) ))

mkdir -p "$SB"/{bin,usr/bin,usr/sbin,etc/ssh,tmp,dev,root/.ssh,var/empty,run/sshd,lib64,usr/lib/x86_64-linux-gnu}
# host が /lib → /usr/lib の symlink (Debian/usr-merge) なら同じ構造に。
# こうしないと、ld.so が /lib/... を resolve したときに real file が
# /usr/lib/... 配下に置いてあって見つからない (dangling)。
if [ -L /lib ]; then
    ln -sf usr/lib "$SB/lib"
else
    mkdir -p "$SB/lib/x86_64-linux-gnu"
fi

# lib 名で /lib /usr/lib を探して copy_solib で配置
copy_lib() {
    local lib=$1
    local src
    for prefix in /lib/x86_64-linux-gnu /usr/lib/x86_64-linux-gnu; do
        src=$prefix/$lib
        if [ -f "$src" ]; then
            copy_solib "$src"
            return 0
        fi
    done
    return 1
}

# 1 file を sandbox に「絶対 path での real file 配置」+「元の参照 path
# にも real file をそのまま copy」で展開。symlink は使わない方針 (相対
# path 計算 / dangling の罠を避ける、重複は数 MB 程度なので許容)。
copy_solib() {
    local p=$1
    [ -f "$p" ] || return 0
    local real
    real=$(readlink -f "$p")
    [ -f "$real" ] || return 0
    mkdir -p "$SB$(dirname "$real")"
    cp -L "$real" "$SB${real}" 2>/dev/null || true
    if [ "$real" != "$p" ]; then
        mkdir -p "$SB$(dirname "$p")"
        cp -L "$real" "$SB${p}" 2>/dev/null || true
    fi
}
copy_solib /lib64/ld-linux-x86-64.so.2

# /usr/sbin/sshd と /bin/bash の依存 .so を ldd で展開して全部 copy。
# (ldd 出力に表れない dlopen 経由の lib は別途 copy_lib で補完)
copy_deps_of() {
    local bin=$1
    [ -f "$bin" ] || return 0
    while IFS= read -r line; do
        local p
        if [[ "$line" =~ \=\>[[:space:]]+(/[^[:space:]]+) ]]; then
            p="${BASH_REMATCH[1]}"
        elif [[ "$line" =~ ^[[:space:]]*(/[^[:space:]]+) ]]; then
            p="${BASH_REMATCH[1]}"
        else
            continue
        fi
        copy_solib "$p"
    done < <(ldd "$bin" 2>/dev/null)
}
copy_deps_of /usr/sbin/sshd
copy_deps_of /bin/bash
# OpenSSH 10 privsep helpers (issue #317): listener(sshd) が per-connection の sshd-session と
#   auth の sshd-auth を rexec する。これらが無いと sshd は "sshd-session does not exist" で
#   起動できない (OpenSSH 9 まではモノリシックで不要だった)。
copy_deps_of /usr/lib/openssh/sshd-session
copy_deps_of /usr/lib/openssh/sshd-auth

# NSS modules: getpwnam で glibc が dlopen する。ldd には現れない。
for lib in libnss_files.so.2 libnss_compat.so.2 libnss_dns.so.2; do
    copy_lib "$lib"
done

# sshd binary
cp /usr/sbin/sshd "$SB/usr/sbin/sshd"
# OpenSSH 10 privsep helper binaries (issue #317): sshd-session / sshd-auth
mkdir -p "$SB/usr/lib/openssh"
cp /usr/lib/openssh/sshd-session "$SB/usr/lib/openssh/sshd-session" 2>/dev/null
cp /usr/lib/openssh/sshd-auth    "$SB/usr/lib/openssh/sshd-auth"    2>/dev/null

# /bin/bash (sshd の allowed_user が pw_shell の存在を check する)
cp /bin/bash "$SB/bin/bash" 2>/dev/null
ln -sf bash "$SB/bin/sh"

# host key generation
ssh-keygen -t ed25519 -N '' -q -f "$SB/etc/ssh/ssh_host_ed25519_key" -C "emulin-sshd-pty-smoke" \
    || { echo "FAIL sshd-pty-smoke : ssh-keygen for host key failed"; exit 1; }

# client key generation
ssh-keygen -t ed25519 -N '' -q -f "$TKEYDIR/clientkey" -C "emulin-sshd-pty-smoke-client" \
    || { echo "FAIL sshd-pty-smoke : ssh-keygen for client key failed"; exit 1; }
cp "$TKEYDIR/clientkey.pub" "$SB/root/.ssh/authorized_keys"
chmod 700 "$SB/root/.ssh"
chmod 600 "$SB/root/.ssh/authorized_keys"

# sshd_config (Phase 1 MVP 用 minimal)
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
EOF

# nsswitch.conf (passwd を files plugin で読ませる)
cat > "$SB/etc/nsswitch.conf" <<'EOF'
passwd:         files
group:          files
shadow:         files
hosts:          files dns
EOF

# /etc/shells (allowed_user の shell check)
cat > "$SB/etc/shells" <<'EOF'
/bin/sh
/bin/bash
EOF

# /etc/passwd, /etc/group
cat > "$SB/etc/passwd" <<'EOF'
root:x:0:0:root:/root:/bin/bash
sshd:x:74:74:Privilege-separated SSH:/run/sshd:/usr/sbin/nologin
EOF
cat > "$SB/etc/group" <<'EOF'
root:x:0:
sshd:x:74:
EOF

# /dev nodes
for d in null urandom zero tty random ptmx; do
    [ -e "$SB/dev/$d" ] || touch "$SB/dev/$d"
    chmod 666 "$SB/dev/$d" 2>/dev/null || true
done

# emulin で sshd を background 起動
SSHD_LOG=$(mktemp -t emulin-sshd-pty-smoke-log.XXXXXX)
(
    cd "$SB"
    java -XX:-UsePerfData -XX:-DontCompileHugeMethods -cp "$CLASSES" \
        emulin.Emulin "$SB" \
        /usr/sbin/sshd -D -d -e -p "$PORT" -f /etc/ssh/sshd_config
) > "$SSHD_LOG" 2>&1 &
EPID=$!

# sshd の listening を待つ (最大 20 秒)
ready=0
for i in $(seq 1 20); do
    sleep 1
    if grep -q "Server listening on 127.0.0.1 port $PORT" "$SSHD_LOG" 2>/dev/null; then
        ready=1
        break
    fi
    # emulin がもう死んでいたら skip
    if ! kill -0 $EPID 2>/dev/null; then
        echo "FAIL sshd-pty-smoke : emulin sshd died before listening"
        echo "--- sshd log tail ---"
        tail -20 "$SSHD_LOG"
        rm -f "$SSHD_LOG"
        exit 1
    fi
done
if [ "$ready" != "1" ]; then
    echo "FAIL sshd-pty-smoke : sshd did not start listening within 20s"
    echo "--- sshd log tail ---"
    tail -20 "$SSHD_LOG"
    kill -9 $EPID 2>/dev/null
    rm -f "$SSHD_LOG"
    exit 1
fi

# ssh client で `echo HELLO-FROM-EMULIN-SSHD` を実行
EXPECTED='PTY-OK-FROM-EMULIN'
OUT=$(timeout 20 ssh -tt -p "$PORT" -i "$TKEYDIR/clientkey" \
    -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
    -o ConnectTimeout=10 \
    -q \
    root@127.0.0.1 "tty; echo $EXPECTED; exit" 2>/dev/null)
RC=$?

# emulin sshd を kill
kill -9 $EPID 2>/dev/null
wait $EPID 2>/dev/null

if [ "$RC" != "0" ]; then
    echo "FAIL sshd-pty-smoke : ssh client exit=$RC"
    echo "--- sshd log tail ---"
    tail -30 "$SSHD_LOG" 2>/dev/null
    rm -f "$SSHD_LOG"
    exit 1
fi
rm -f "$SSHD_LOG"
if ! printf "%s" "$OUT" | grep -q "$EXPECTED"; then
    echo "FAIL sshd-pty-smoke : expected '$EXPECTED' got '$OUT'"
    exit 1
fi

echo "PASS sshd-pty-smoke"
exit 0
