/* hello64.c: 64-bit hello world using syscall instruction (no libc) */
static const char msg[] = "hello world\n";

void _start(void) {
    /* write(1, msg, 12) */
    __asm__ volatile (
        "movq $1, %%rax\n"
        "movq $1, %%rdi\n"
        "movq %0, %%rsi\n"
        "movq $12, %%rdx\n"
        "syscall\n"
        :
        : "r"((long)msg)
        : "rax", "rdi", "rsi", "rdx", "memory"
    );
    /* exit(0) */
    __asm__ volatile (
        "movq $60, %%rax\n"
        "xorq %%rdi, %%rdi\n"
        "syscall\n"
        ::: "rax", "rdi"
    );
    __builtin_unreachable();
}
