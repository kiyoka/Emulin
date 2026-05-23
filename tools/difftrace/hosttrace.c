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
        execvp(child_argv[0], child_argv);
        perror("execvp");
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

    for (;;) {
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
