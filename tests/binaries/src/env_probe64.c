/* env_probe64.c — guest プロセスの環境変数を全て stdout に dump する。
 *
 * issue #212 (EMULIN_INHERIT_ENV) の検証用ヘルパー。env-inherit-smoke.sh が
 * host 側に既知の env を set した上で本バイナリを emulin 上で起動し、
 *   - host の任意変数 (FOO_ISSUE212 等) が guest に届いているか
 *   - emulin 必須変数 (PATH/HOME) が emulin の値で上書きされているか
 * を grep で検証する。出力は host 環境依存 (passthrough される LANG/TERM 等を
 * 含む) なので generic な stdout 一致テストには向かない → env_probe64.skip で
 * 通常回帰からは除外し、専用 smoke だけが直接起動する。
 *
 * argvdump64.c と同じく _start の prologue (push rbp; mov rsp,rbp) を利用して
 * 初期スタックから argc/argv/envp を取り出す (-nostdlib なので libc 無し)。
 */
#include "sys64.h"

void _start(void) {
    long argc;
    char **argv;
    __asm__ volatile(
        "movq 8(%%rbp), %0\n"
        "leaq 16(%%rbp), %1\n"
        : "=r"(argc), "=r"(argv)
    );
    /* argv[argc] == NULL の次から envp が並ぶ。NULL 終端まで 1 行ずつ出す。 */
    char **envp = argv + argc + 1;
    for (long i = 0; envp[i]; i++) {
        put(envp[i]);
        put("\n");
    }
    sys_exit(0);
}
