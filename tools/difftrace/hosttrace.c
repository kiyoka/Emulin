// issue #84: host x86 instruction-level RIP トレーサ (perf/gdb/PT 不要)
//
// ptrace で子プロセス (= 実 x86 上の被験プログラム) を single-step し、指定
// アドレス範囲の RIP を 8-byte little-endian で出力ファイルに逐次書く。
// emulin の EMULIN_TRACE_RIP_FILE 出力と同じ binary 形式なので、difftrace.py
// で突き合わせて「emulin が実 x86 と発散する最初の命令」を特定できる。
//
// 注意:
//   - 子は ADDR_NO_RANDOMIZE (ASLR off) で起動。非 PIE バイナリは emulin と
//     同じ固定アドレスに load されるので RIP が直接一致する (lib は要 offset 補正)。
//   - メインスレッドのみ single-step する (PTRACE_O_TRACECLONE は付けない)。
//     被験プログラムは --single-threaded 等でスレッドを抑えること。
//
// build:  cc -O2 -o hosttrace hosttrace.c
// usage:  hosttrace <out.bin> <lo_hex> <hi_hex> -- <prog> [args...]
//   例:   hosttrace /tmp/host-rip.bin dfd000 3061000 -- \
//             /path/to/node --jitless --single-threaded -e 'console.log(6*7)'

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <errno.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/user.h>
#include <sys/personality.h>

// issue #98: BB 単位 step。HOSTTRACE_SINGLEBLOCK=1 で single-step の代わりに
//   PTRACE_SINGLEBLOCK を使い、branch のたびに停止する (= 各停止の RIP は
//   分岐先 = basic-block head)。命令数の数分の 1 の停止回数で済むので、
//   数千万命令地点の発散にも現実的な時間で到達できる。emulin の full RIP
//   trace に対し「host BB-head ⊆ emulin full」の subseq diff で突き合わせる。
#ifndef PTRACE_SINGLEBLOCK
#define PTRACE_SINGLEBLOCK 33
#endif

// issue #98: host の cpuid 結果を emulin (Cpu64) の cpuid と同じ値に上書きする。
//   simdutf / V8 は生 cpuid 命令で SIMD feature を直接検出するため (V8 flags や
//   OPENSSL_ia32cap では変えられない)、emu (ECX=0 等) と host (実 HW cpuid) で
//   分岐が割れて benign 発散になる。leaf 1 ECX は HOSTTRACE_CPUID_ECX で指定。
static void emu_cpuid(uint32_t leaf, uint32_t ecx1, struct user_regs_struct *r) {
    uint32_t a=0,b=0,c=0,d=0;
    if      (leaf==0)          { a=1; b=0x756E6547u; d=0x49656E69u; c=0x6C65746Eu; }
    else if (leaf==1)          { a=0x000506E3u; b=0x00010800u; d=0x178BFBFFu; c=ecx1; }
    else if (leaf==0x80000000u){ a=0x80000001u; }
    else if (leaf==0x80000001u){ d=0x20000000u; }
    /* それ以外は全 0 (Cpu64 と同じ) */
    r->rax=a; r->rbx=b; r->rcx=c; r->rdx=d;
}

int main(int argc, char **argv) {
    if (argc < 6 || strcmp(argv[4], "--") != 0) {
        fprintf(stderr, "usage: %s <out.bin> <lo_hex> <hi_hex> -- <prog> [args...]\n", argv[0]);
        return 2;
    }
    const char *outpath = argv[1];
    uint64_t lo = strtoull(argv[2], NULL, 16);
    uint64_t hi = strtoull(argv[3], NULL, 16);
    char **child_argv = &argv[5];   // argv[4] == "--"

    FILE *out = fopen(outpath, "wb");
    if (!out) { perror("fopen"); return 2; }

    pid_t pid = fork();
    if (pid < 0) { perror("fork"); return 2; }

    if (pid == 0) {
        // --- 子プロセス ---
        personality(ADDR_NO_RANDOMIZE);            // ASLR off (非PIEは固定アドレス)
        if (ptrace(PTRACE_TRACEME, 0, 0, 0) < 0) { perror("TRACEME"); _exit(127); }
        // issue #98: HOSTTRACE_ARGV0 で argv[0] を上書き (exec path は元のまま)。
        //   emulin 側の guest argv[0] (例 /usr/bin/node) と揃えて、process.title /
        //   uv_get_process_title 等の argv 依存 benign 発散を消すため。
        char *exec_path = child_argv[0];
        const char *a0 = getenv("HOSTTRACE_ARGV0");
        if (a0) child_argv[0] = (char*)a0;
        execv(exec_path, child_argv);
        perror("execv");
        _exit(127);
    }

    // --- 親 (tracer) ---
    int status;
    waitpid(pid, &status, 0);                       // execve 後の最初の停止 (SIGTRAP)
    if (WIFEXITED(status)) { fprintf(stderr, "child exited before trace\n"); return 1; }

    // 1命令ずつ進める (HOSTTRACE_SINGLEBLOCK=1 なら BB 単位)
    struct user_regs_struct regs;
    uint64_t buf[4096];
    int bn = 0;
    uint64_t count = 0;
    long sig = 0;
    int step_req = getenv("HOSTTRACE_SINGLEBLOCK") ? PTRACE_SINGLEBLOCK : PTRACE_SINGLESTEP;

    // issue #98: FF (fast-forward) mode。WSL2 では SINGLEBLOCK が single-step に
    //   fallback して速くならないため、PTRACE_CONT で HOSTTRACE_FF_RIP まで
    //   ネイティブ速度で進め、そこから single-step + 記録する。発散が深い地点
    //   (数千万命令) でも、手前の共通 bootstrap を native 速度で飛ばせる。
    //   FF_RIP は emulin trace で「1 回だけ実行される」late RIP を選ぶこと
    //   (breakpoint hit の一意性のため)。
    uint64_t ff_rip = 0;
    { const char *e = getenv("HOSTTRACE_FF_RIP"); if (e) ff_rip = strtoull(e, NULL, 16); }
    if (ff_rip) {
        errno = 0;
        long orig = ptrace(PTRACE_PEEKTEXT, pid, (void*)ff_rip, 0);
        if (orig == -1 && errno) { perror("PEEKTEXT(ff)"); }
        long bp = (orig & ~0xFFL) | 0xCCL;
        ptrace(PTRACE_POKETEXT, pid, (void*)ff_rip, (void*)bp);
        for (;;) {
            if (ptrace(PTRACE_CONT, pid, 0, (void*)sig) < 0) { perror("CONT(ff)"); break; }
            sig = 0;
            if (waitpid(pid, &status, 0) < 0) break;
            if (WIFEXITED(status) || WIFSIGNALED(status)) {
                fprintf(stderr, "hosttrace: child ended before FF_RIP=0x%llx\n",
                        (unsigned long long)ff_rip);
                fclose(out); return 1;
            }
            if (WIFSTOPPED(status)) {
                int s = WSTOPSIG(status);
                if (s != SIGTRAP) { sig = s; continue; }
                if (ptrace(PTRACE_GETREGS, pid, 0, &regs) < 0) continue;
                if (regs.rip == ff_rip + 1) {            // int3 を踏んだ
                    ptrace(PTRACE_POKETEXT, pid, (void*)ff_rip, (void*)orig); // 元に戻す
                    regs.rip = ff_rip;
                    ptrace(PTRACE_SETREGS, pid, 0, &regs);
                    fprintf(stderr, "hosttrace: reached FF_RIP=0x%llx, single-step from here\n",
                            (unsigned long long)ff_rip);
                    if (ff_rip >= lo && ff_rip <= hi) { buf[bn++] = ff_rip; count++; } // FF_RIP を先頭に記録
                    break;
                }
                // 別 SIGTRAP は無視して継続
            }
        }
    }

    // cpuid intercept 用。HOSTTRACE_CPUID_ECX 未指定なら emu 既定 0x02980203。
    int do_cpuid = getenv("HOSTTRACE_CPUID") != NULL;
    uint32_t emu_ecx1 = 0x02980203u;
    { const char *e = getenv("HOSTTRACE_CPUID_ECX"); if (e) emu_ecx1 = (uint32_t)strtoull(e, NULL, 16); }
    // FF 直後の regs は ff_rip の命令 (これから実行)。FF 無しなら execve 後停止点。
    if (!ff_rip) { if (ptrace(PTRACE_GETREGS, pid, 0, &regs) < 0) regs.rip = 0; }

    for (;;) {
        // 次に実行する命令 (regs.rip) が cpuid (0f a2) か事前に判定
        int is_cpuid = 0; uint32_t cpuid_leaf = 0;
        if (do_cpuid) {
            errno = 0;
            long w = ptrace(PTRACE_PEEKTEXT, pid, (void*)regs.rip, 0);
            if (!(w == -1 && errno) && ((unsigned)(w & 0xFFFF) == 0xA20F)) {
                is_cpuid = 1; cpuid_leaf = (uint32_t)regs.rax;
            }
        }
        if (ptrace(step_req, pid, 0, (void*)sig) < 0) {
            if (errno == ESRCH) break;              // 子が消えた
            perror("step"); break;
        }
        sig = 0;
        if (waitpid(pid, &status, 0) < 0) break;
        if (WIFEXITED(status) || WIFSIGNALED(status)) break;
        if (WIFSTOPPED(status)) {
            int s = WSTOPSIG(status);
            // SIGTRAP は single-step 完了。それ以外のシグナルは子に転送して継続。
            if (s != SIGTRAP) { sig = s; continue; }
        }
        if (ptrace(PTRACE_GETREGS, pid, 0, &regs) < 0) {
            if (errno == ESRCH) break;
            continue;
        }
        if (is_cpuid) {                              // 直前 cpuid の結果を emu 値に上書き
            emu_cpuid(cpuid_leaf, emu_ecx1, &regs);
            ptrace(PTRACE_SETREGS, pid, 0, &regs);
        }
        uint64_t rip = regs.rip;
        if (rip >= lo && rip <= hi) {
            buf[bn++] = rip;
            if (bn == 4096) { fwrite(buf, 8, bn, out); bn = 0; }
            count++;
        }
    }
    if (bn) fwrite(buf, 8, bn, out);
    fclose(out);
    fprintf(stderr, "hosttrace: %llu RIPs in [%llx,%llx] -> %s\n",
            (unsigned long long)count, (unsigned long long)lo,
            (unsigned long long)hi, outpath);
    return 0;
}
