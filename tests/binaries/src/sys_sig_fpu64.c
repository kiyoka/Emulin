// sys_sig_fpu64.c — issue #221 step 3d-2c-21: signal 配信を跨いだ XMM (FPU) の退避復元
//
// signal handler が XMM を破壊しても、被中断側の live XMM data が保たれることを確認する。
// native backend では signal 配信時に KVM_GET_FPU で vCPU FPU を退避し、rt_sigreturn で
// KVM_SET_FPU で復元する。software (Cpu64) は sigSavedFrames に xmm_lo/xmm_hi を退避する。
// どちらも被中断側 XMM を保つので native==software で byte 一致する。
//
// 手順:
//   1. SIGUSR1 ハンドラを登録 (ハンドラは xmm3 を all-ones に破壊する)
//   2. 1 つの asm block で: xmm3 = known をロード → kill(self, SIGUSR1) → 復帰後 xmm3 を読む
//      (compiler が間で xmm3 に触れないよう単一 block にする)
//   3. 読んだ値が known と一致するか = XMM が signal を跨いで保たれたか
//
// 期待出力 (software / native-with-fix 双方):
//   handler_called=1
//   xmm_preserved=1
// FPU-in-signal が無いと native では xmm_preserved=0 になり software と byte 不一致 = oracle FAIL。
#include "sys64.h"

#define SIGUSR1 10

static volatile long handler_called = 0;
static long          sa[4];
static unsigned char known[16]  = { 1,2,3,4,5,6,7,8, 9,10,11,12,13,14,15,16 };
static unsigned char outbuf[16];

// ハンドラ: xmm3 を all-ones (0xFF...) に破壊する (known と異なる値)。
void handler( long sig )
{
    __asm__ volatile( "pcmpeqd %%xmm3, %%xmm3\n" : : : "xmm3" );  // xmm3 = 全 1
    handler_called = 1;
}

void _start( void )
{
    sa[0] = (long) &handler;
    sa[1] = 0;                 // sa_flags=0 (既存 signal テストと同じ、SA_SIGINFO 無し)
    sa[2] = 0;
    sa[3] = 0;
    sys_rt_sigaction( SIGUSR1, sa, 0, 8 );

    long pid = sys_getpid();

    // xmm3 に known をロード → kill で handler が xmm3 を破壊 → 復帰後 xmm3 を読む。被中断側 (この asm)
    //   の xmm3 が保たれていれば outbuf == known。kill の signal は native では syscall 境界で配信され、
    //   handler は syscall 命令の直後に発火し rt_sigreturn で戻ってから次命令 (movdqu read) に進む。
    __asm__ volatile(
        "movdqu (%[k]), %%xmm3\n\t"      // xmm3 = known
        "mov    $62, %%rax\n\t"          // kill
        "mov    %[pid], %%rdi\n\t"
        "mov    $10, %%rsi\n\t"          // SIGUSR1
        "syscall\n\t"                    // → handler 発火 (xmm3 破壊) → rt_sigreturn (復元)
        "movdqu %%xmm3, (%[o])\n\t"      // 復帰後の xmm3 を読む
        :
        : [k] "r" (known), [o] "r" (outbuf), [pid] "r" (pid)
        : "rax", "rdi", "rsi", "rcx", "r11", "xmm3", "memory" );

    int preserved = 1;
    for( int i = 0; i < 16; i++ ) if( outbuf[i] != known[i] ) preserved = 0;

    put( "handler_called=" ); put_dec( handler_called ); put( "\n" );
    put( "xmm_preserved=" );  put_dec( preserved );      put( "\n" );
    sys_exit( 0 );
}
