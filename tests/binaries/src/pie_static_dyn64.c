/* pie_static_dyn64.c — 静的 PIE (ET_DYN かつ libc 依存なし) の回帰固定
 *
 * Phase 26 候補 #4 (PIE 対応) の中間進展。
 *
 * gcc -nostdlib -fpie -pie で生成される ET_DYN バイナリを直接実行する
 * (PT_INTERP は付くが ld.so の relocation 処理は不要)。これで:
 *   - Elf.load64 が ET_DYN を正しくロードできること
 *   - load_bias = 0x555555554000 で本体を配置できること
 *   - lea rip-relative も bias 付きで動作すること
 * を検証する。
 *
 * 完全 PIE (libc 動的リンク) は ld.so の relocation 適用中に
 * 32bit 切り詰めっぽい crash があり、本テストの範囲外。
 *
 * 期待出力: "Y\n" (改行込み 2 byte)
 */

void _start(void) {
    /* write(1, msg, 2); exit(0); — sys64.h を借りずインラインアセンブリで */
    __asm__ volatile (
        "mov $1, %%rax\n"
        "mov $1, %%rdi\n"
        "lea pie_msg(%%rip), %%rsi\n"
        "mov $2, %%rdx\n"
        "syscall\n"
        "mov $60, %%rax\n"
        "xor %%rdi, %%rdi\n"
        "syscall\n"
        ::: "rax","rdi","rsi","rdx"
    );
}
__asm__ ("pie_msg: .ascii \"Y\\n\"\n");
