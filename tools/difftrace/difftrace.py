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

def collapse(seq):
    # 連続同一 RIP を 1 つに潰す。host の ptrace single-step は rep prefix
    # (rep stos/movs/cmps 等) の各反復を同じ RIP で別個に記録するが、emulin は
    # rep 全体を 1 エントリで記録する。連続同一 RIP の出所は rep のみ (通常の
    # コードは必ず別アドレスへ進む。自己 jmp 無限ループは実行されない) なので、
    # 両者を collapse すれば rep の粒度差が消える。
    out = []
    prev = None
    for x in seq:
        if x != prev: out.append(x); prev = x
    return out

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
    elf = None; ctx = 12; allmode = False; maxskip = 8
    pos = []
    i = 0
    while i < len(args):
        if args[i] == "--sym": elf = args[i+1]; i += 2
        elif args[i] == "--ctx": ctx = int(args[i+1]); i += 2
        elif args[i] == "--all": allmode = True; i += 1
        elif args[i] == "--maxskip": maxskip = int(args[i+1]); i += 2
        else: pos.append(args[i]); i += 1
    if len(pos) != 2:
        print("usage: difftrace.py <host.bin> <emu.bin> [--sym <elf>] [--ctx N] [--all] [--maxskip W]"); return 2
    host = load(pos[0]); emu = load(pos[1])
    rawh, rawe = len(host), len(emu)
    host = collapse(host); emu = collapse(emu)
    syms = load_syms(elf) if elf else []
    print("host RIPs=%d (collapsed from %d)  emu RIPs=%d (collapsed from %d)" % (len(host), rawh, len(emu), rawe))

    # resync: mismatch 時に小窓 (di+dj <= K) を探索し、以降 M 命令一致する最小
    #   シフトで再同期する。これで以下を吸収する:
    #     - 単命令ズレ artifact (WSL2 cpuid single-step 取りこぼし、K 小で足りる)
    #     - benign な制御フロー分岐 (環境差で文字列内容が違い strlen-分岐が割れる等)
    #       で片側が数〜数十命令多く実行し、その後 rejoin する型 (K を大きく)
    #   再同期できなければ「真の (rejoin しない) 発散」。
    K = maxskip; M = 16
    def matches(i, j, m):
        if i+m > len(host) or j+m > len(emu): return False
        for t in range(m):
            if host[i+t] != emu[j+t]: return False
        return True
    def resync(i, j):
        for s in range(1, K+1):
            for di in range(0, s+1):
                dj = s - di
                if matches(i+di, j+dj, M):
                    return (di, dj)
        return None
    def report(tag, div_i, div_j):
        print("\n=== %s: host[%d] / emu[%d] ===" % (tag, div_i, div_j))
        if div_i>0:
            print("  最後に一致した RIP: 0x%x%s" % (host[div_i-1], sym_for(syms, host[div_i-1])))
        print("  host 次 RIP: 0x%x%s" % (host[div_i], sym_for(syms, host[div_i])) if div_i<len(host) else "  host 末尾")
        print("  emu  次 RIP: 0x%x%s" % (emu[div_j],  sym_for(syms, emu[div_j]))  if div_j<len(emu)  else "  emu 末尾")
        lo = max(0, div_i-ctx)
        print("  --- 一致末尾 (host %d..%d) ---" % (lo, div_i-1))
        for k in range(lo, div_i): print("    [%d] 0x%x%s" % (k, host[k], sym_for(syms, host[k])))
        print("  --- 発散後 host ---")
        for k in range(div_i, min(len(host), div_i+ctx)): print("    H[%d] 0x%x%s" % (k, host[k], sym_for(syms, host[k])))
        print("  --- 発散後 emu ---")
        for k in range(div_j, min(len(emu), div_j+ctx)): print("    E[%d] 0x%x%s" % (k, emu[k], sym_for(syms, emu[k])))

    i = j = 0; artifacts = 0; divs = 0
    while i < len(host) and j < len(emu):
        if host[i] == emu[j]:
            i += 1; j += 1; continue
        r = resync(i, j)
        if r:
            di, dj = r; artifacts += 1
            if allmode and (di+dj) > 2:
                divs += 1
                report("発散#%d (rejoin: host+%d emu+%d)" % (divs, di, dj), i, j)
            i += di; j += dj; continue
        # K 窓内で rejoin しない発散 = 永久分岐 (本物のバグ、または K 超の大きな
        # benign split)。ここで停止する (これ以降は両者が別経路で意味のある比較
        # 不可。--all で吸収済の rejoin 分岐は benign と判断してよい)。
        divs += 1
        report("真の発散 (K=%d 内で rejoin せず)" % K, i, j)
        print("\n(ここまでに resync で吸収した rejoin 分岐: %d)" % artifacts)
        return 0
    print("\n=== 走査終了 (片側が末尾に到達) ===")
    print("吸収した rejoin 分岐: %d" % artifacts)
    print("host 残 %d / emu 残 %d (host 末尾到達=%s)" % (len(host)-i, len(emu)-j, i>=len(host)))
    return 0

if __name__ == "__main__":
    sys.exit(main())
