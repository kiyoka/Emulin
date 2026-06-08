// sys_fork_exec64.c — issue #221 step 3d-2c-20: fork + execve + wait4 (git の multi-process パターン)
//
// git clone は helper (git-remote-https 等) を fork してから execve で別 program に置換し、親は
// wait4 で子を待つ。その最小再現:
//   親 fork → 子 execve("/bin/hello64") → 子は hello64 に置換され "hello world" を出力 → exit
//   親 wait4 で子を reap → "parent_after_wait" を出力 → exit
//
// 親は wait4 で子の終了を待ってから出力するので、出力は常に:
//   hello world
//   parent_after_wait
// となり software / native (KVM) で byte 一致する。native では fork が子専用 VM + 複製 guestMem を
// 作り、その上で execve が kernel.exec → 新 native Process (新 ELF を fresh guestMem にロード) に
// 置換する = fork と execve が連携して動くことの検証。
//
// /bin/hello64 は run-test.sh / native-oracle.sh が sandbox に配置済 (前者は全 bin を copy、後者は
// hello64 oracle を先に実行)。
#include "sys64.h"

void _start( void )
{
    long pid = sys_fork();
    if( pid == 0 ) {
        char *argv[] = { "/bin/hello64", 0 };
        char *envp[] = { 0 };
        sys_execve( "/bin/hello64", argv, envp );   // 子を hello64 に置換 ("hello world" 出力)
        put( "exec_failed\n" );                     // execve 成功なら到達しない
        sys_exit( 127 );
    }
    long st = 0;
    sys_wait4( pid, (int *)&st, 0, 0 );             // 子 (hello64) の終了を待つ
    put( "parent_after_wait\n" );
    sys_exit( 0 );
}
