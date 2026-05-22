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

    // 1命令ずつ進める
    struct user_regs_struct regs;
    uint64_t buf[4096];
    int bn = 0;
    uint64_t count = 0;
    long sig = 0;

    for (;;) {
        if (ptrace(PTRACE_SINGLESTEP, pid, 0, (void*)sig) < 0) {
            if (errno == ESRCH) break;              // 子が消えた
            perror("SINGLESTEP"); break;
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
