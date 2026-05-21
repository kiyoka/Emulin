#!/usr/bin/env python3
# issue #84: host x86 と emulin の RIP trace を突き合わせて最初の発散点を特定する。
#
# 両 trace は 8-byte little-endian の RIP 列 (hosttrace / EMULIN_TRACE_RIP_FILE 出力)。
# 同じ非PIEバイナリの同じ実行なら RIP 列は途中まで一致し、emulin が誤った命令を
# 実行して制御フローが分岐した点で初めて食い違う。その「最後に一致した RIP」=
# 容疑命令 (分岐条件を計算した命令、または分岐自体)。
#
# usage: difftrace.py <host.bin> <emu.bin> [--sym <elf>] [--ctx N]
import sys, struct, subprocess, bisect

def load(path):
    with open(path, "rb") as f:
        d = f.read()
    n = len(d) // 8
    return struct.unpack("<%dQ" % n, d[:n*8])

def load_syms(elf):
    # nm -n でアドレス昇順のシンボル表を作る (RIP -> 関数名 解決用)
    syms = []
    try:
        out = subprocess.check_output(["nm","-n","--defined-only",elf],
                                      stderr=subprocess.DEVNULL).decode("utf-8","replace")
        for line in out.splitlines():
            p = line.split()
            if len(p) >= 3 and p[1] in "tTwW":
                try: syms.append((int(p[0],16), p[2]))
                except ValueError: pass
    except Exception as e:
        print("nm 失敗 (シンボル解決なし):", e, file=sys.stderr)
    return syms

def sym_for(syms, addr):
    if not syms: return ""
    i = bisect.bisect_right([s[0] for s in syms], addr) - 1
    if i < 0: return ""
    base, name = syms[i]
    return " %s+0x%x" % (name, addr - base)

def main():
    args = sys.argv[1:]
    elf = None; ctx = 12
    pos = []
    i = 0
    while i < len(args):
        if args[i] == "--sym": elf = args[i+1]; i += 2
        elif args[i] == "--ctx": ctx = int(args[i+1]); i += 2
        else: pos.append(args[i]); i += 1
    if len(pos) != 2:
        print("usage: difftrace.py <host.bin> <emu.bin> [--sym <elf>] [--ctx N]"); return 2
    host = load(pos[0]); emu = load(pos[1])
    syms = load_syms(elf) if elf else []
    print("host RIPs=%d  emu RIPs=%d" % (len(host), len(emu)))

    # resync: 単命令ズレ (WSL2 の cpuid 等で host trace が 1 命令取りこぼす
    #   artifact) を吸収する。mismatch 時に小窓 (di,dj <= K) を探索し、以降 M 命令
    #   一致する最小シフトで再同期。再同期できなければ「真の発散」。
    K = 8; M = 16
    def matches(i, j, m):
        if i+m > len(host) or j+m > len(emu): return False
        for t in range(m):
            if host[i+t] != emu[j+t]: return False
        return True
    i = j = 0
    artifacts = 0
    n = min(len(host), len(emu))
    div = -1; div_i = div_j = -1
    while i < len(host) and j < len(emu):
        if host[i] == emu[j]:
            i += 1; j += 1; continue
        # mismatch — resync を試みる
        best = None
        for s in range(1, K+1):
            for di in range(0, s+1):
                dj = s - di
                if matches(i+di, j+dj, M):
                    best = (di, dj); break
            if best: break
        if best:
            di, dj = best
            artifacts += 1
            i += di; j += dj
            continue
        div_i, div_j = i, j; break
    if div_i < 0:
        print("発散なし (resync で %d 個の単命令 artifact を吸収、残りは一致)。" % artifacts)
        return 0

    div = div_i
    print("\n(resync で吸収した単命令 artifact: %d)" % artifacts)
    print("\n=== 最初の (真の) 発散: host[%d] / emu[%d] ===" % (div_i, div_j))
    print("  最後に一致した RIP: 0x%x%s" % (host[div_i-1], sym_for(syms, host[div_i-1])) if div_i>0 else "  (先頭から不一致)")
    print("  host 次 RIP: 0x%x%s" % (host[div_i], sym_for(syms, host[div_i])))
    print("  emu  次 RIP: 0x%x%s" % (emu[div_j],  sym_for(syms, emu[div_j])))
    div = div_i  # 以降の context 表示用 (host index 基準)
    lo = max(0, div_i-ctx)
    print("\n--- 一致区間の末尾 (host index %d..%d) ---" % (lo, div_i-1))
    for k in range(lo, div_i):
        print("  [%d] 0x%x%s" % (k, host[k], sym_for(syms, host[k])))
    print("--- 発散後 host ---")
    for k in range(div_i, min(len(host), div_i+ctx)):
        print("  H[%d] 0x%x%s" % (k, host[k], sym_for(syms, host[k])))
    print("--- 発散後 emu ---")
    for k in range(div_j, min(len(emu), div_j+ctx)):
        print("  E[%d] 0x%x%s" % (k, emu[k], sym_for(syms, emu[k])))
    return 0

if __name__ == "__main__":
    sys.exit(main())
