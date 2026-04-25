/*
 * exitcode.c — exit code が正しく返ることを確認
 *
 * カバー対象:
 *   - sys_exit の引数受け渡し (ebx)
 *
 * 期待値: 終了コード 42
 */

void _start(void) {
    __asm__ volatile (
        "int $0x80"
        :
        : "a"(1), "b"(42)
    );
}
