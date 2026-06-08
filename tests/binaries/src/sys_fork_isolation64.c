// sys_fork_isolation64.c — issue #221 step 3d-2c-20: fork のアドレス空間【分離】検証
//
// fork の本質 = 子は親アドレス空間の【独立した複製】で走り、子の書込みは親に波及しない。
// native (KVM) backend では子は専用 VM + 複製 guestMem を持つので、これが成り立つことを確認する。
//
// g は .data の書込み可 global (= fork でアドレス空間ごと複製される)。子が g=9 に書き換えても
// 親の g は 1 のままであることを確認する。親は wait4 で子の終了を待ってから出力するので、出力は
// 常に:
//   child:g=9
//   parent:g=1
//   done
// となり software / native (KVM) で byte 一致する。"parent:g=CHANGED!" が出たらアドレス空間が
// 分離されていない (= 子の VM が親プールを共有している) バグ。
#include "sys64.h"

static volatile long g = 1;   // .data (writable)

void _start( void )
{
    long pid = sys_fork();
    if( pid == 0 ) {
        g = 9;                                   // 子: 自分の複製アドレス空間で書き換え
        if( g == 9 ) put( "child:g=9\n" );
        sys_exit( 0 );
    }
    long st = 0;
    sys_wait4( pid, (int *)&st, 0, 0 );          // 子の終了を待つ (順序を決定的に + reap)
    if( g == 1 ) put( "parent:g=1\n" );          // 親の g は子の書込みに影響されない (分離)
    else         put( "parent:g=CHANGED!\n" );   // ← 来たらアドレス空間分離が壊れている
    put( "done\n" );
    sys_exit( 0 );
}
