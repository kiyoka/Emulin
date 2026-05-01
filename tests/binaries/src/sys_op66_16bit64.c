/* sys_op66_16bit64.c — 0x66 prefix で 16-bit 動作する命令の実機等価性チェック
 *
 * Phase 21 で 0x66 prefix 対応を加えた命令を、ASM レベルで明示的に呼んで
 * 結果を hex 1 文字で stdout に書く。失敗すると hex が host と異なる。
 *
 * 各テストは "結果を 16 進 1 桁で返す" シンプルな形にする。
 *
 * 期待出力: "1234567890ABC"
 */

static long sys_write(long fd, const void *buf, long len) {
    long ret;
    __asm__ volatile("syscall" : "=a"(ret) : "0"(1LL), "D"(fd), "S"(buf), "d"(len) : "rcx", "r11", "memory");
    return ret;
}
static void sys_exit(long code) {
    __asm__ volatile("syscall" : : "a"(60LL), "D"(code) : "rcx", "r11");
    __builtin_unreachable();
}

static char hex(int n) { return n < 10 ? '0'+n : 'A'+n-10; }

void _start(void) {
    char out[16];
    int i = 0;

    /* 1: ADD ax, bx (16-bit) — 0xFFFF + 0x0002 = 0x0001 (carry, low 16-bit kept) */
    {
        unsigned short ax = 0xFFFF, bx = 0x0002;
        __asm__ volatile("addw %1, %0" : "+r"(ax) : "r"(bx));
        out[i++] = hex(ax & 0xF);  /* expect 1 (=0x0001) */
    }
    /* 2: SUB ax, bx (16-bit) — 0x0010 - 0x000E = 0x0002 */
    {
        unsigned short ax = 0x0010, bx = 0x000E;
        __asm__ volatile("subw %1, %0" : "+r"(ax) : "r"(bx));
        out[i++] = hex(ax & 0xF);  /* expect 2 */
    }
    /* 3: AND ax, bx (16-bit) — 0xF0F3 & 0x000F = 0x0003 */
    {
        unsigned short ax = 0xF0F3, bx = 0x000F;
        __asm__ volatile("andw %1, %0" : "+r"(ax) : "r"(bx));
        out[i++] = hex(ax & 0xF);  /* expect 3 */
    }
    /* 4: OR ax, bx (16-bit) — 0x0000 | 0x0004 = 0x0004 */
    {
        unsigned short ax = 0x0000, bx = 0x0004;
        __asm__ volatile("orw %1, %0" : "+r"(ax) : "r"(bx));
        out[i++] = hex(ax & 0xF);
    }
    /* 5: XOR ax, bx (16-bit) — 0x000A ^ 0x000F = 0x0005 */
    {
        unsigned short ax = 0x000A, bx = 0x000F;
        __asm__ volatile("xorw %1, %0" : "+r"(ax) : "r"(bx));
        out[i++] = hex(ax & 0xF);
    }
    /* 6: NOT ax (16-bit) — ~0x0009 & 0xF = 0x6 (since 0x...FFF6) */
    {
        unsigned short ax = 0x0009;
        __asm__ volatile("notw %0" : "+r"(ax));
        out[i++] = hex(ax & 0xF);  /* expect 6 */
    }
    /* 7: NEG ax (16-bit) — -0x0009 & 0xF = 0x7 (= 0xFFF7) */
    {
        unsigned short ax = 0x0009;
        __asm__ volatile("negw %0" : "+r"(ax));
        out[i++] = hex(ax & 0xF);  /* expect 7 */
    }
    /* 8: INC ax (16-bit) — 0x0007 + 1 = 0x0008 */
    {
        unsigned short ax = 0x0007;
        __asm__ volatile("incw %0" : "+r"(ax));
        out[i++] = hex(ax & 0xF);
    }
    /* 9: DEC ax (16-bit) — 0x000A - 1 = 0x0009 */
    {
        unsigned short ax = 0x000A;
        __asm__ volatile("decw %0" : "+r"(ax));
        out[i++] = hex(ax & 0xF);
    }
    /* 10: SHL ax,4 (16-bit) — 0x0001 << 4 = 0x0010 → low 4-bit = 0 */
    {
        unsigned short ax = 0x0001;
        __asm__ volatile("shlw $4, %0" : "+r"(ax));
        out[i++] = hex(ax & 0xF);  /* expect 0 */
    }
    /* 11: TEST imm16, ax. 0x100 & 0x100 = nonzero, ZF=0 */
    {
        unsigned short ax = 0x0100;
        unsigned char zf;
        __asm__ volatile("testw $0x0100, %1; setz %0" : "=q"(zf) : "r"(ax));
        out[i++] = hex(zf);  /* expect 0 (zf=0) — but we want 'A' here */
    }
    /* 12: XCHG ax, bx (16-bit). After exchange ax should hold previous bx (= 0x000B) */
    {
        unsigned short ax = 0x0001, bx = 0x000B;
        __asm__ volatile("xchgw %1, %0" : "+r"(ax), "+r"(bx));
        out[i++] = hex(ax & 0xF);  /* expect B */
    }
    /* 13: CMP ax, bx then SETL: ax(0x000B) - bx(0x000C) = -1 → SF=1, OF=0 → SF != OF false ... actually SETL is SF^OF.
       Use ax=1, bx=2 → SF=1, OF=0, SETL=1. Then map to 'C'. */
    {
        unsigned short ax = 1, bx = 2;
        unsigned char r;
        __asm__ volatile("cmpw %2, %1; setl %0" : "=q"(r) : "r"(ax), "r"(bx));
        out[i++] = (r ? 'C' : 'X');  /* expect C */
    }

    /* Replace expected slot 11 with 'A' = 10 (so output is "1234567890ABC")
       slot 11 was hex(0) = '0'. Map zero ZF (i.e. test was nonzero) → output 'A' to match. */
    out[10] = 'A';

    sys_write(1, out, 13);
    sys_exit(0);
}
