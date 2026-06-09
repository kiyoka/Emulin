// ----------------------------------------
//  WhpVm/WhpVcpu (Stage 3) 検証 smoke — HvVm/HvVcpu 抽象経由で ring-3 syscall trap (issue #221 WHP Stage 3)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// 目的: WHP 移植 Stage 3 で追加した WhpVm/WhpVcpu (HvVm/HvVcpu の WHP 実装) を、NativeCpuBackend が使うのと
//   同じ抽象 API 経由で Windows 実機検証する。WhpSyscallSmoke64 (#270) は raw WhpBindings 呼び出しで機構を
//   実証したが、本 smoke は **Stage 3 の wrapper (HvVm.allocGuestRam dispatch → WhpVm → WhpVcpu の
//   configureLongModeRing3/setMsrs/readGprs/setGpr/writeGprs/run/getGpr)** を通すので、抽象化層自体の
//   正しさを確認できる。guest は WhpSyscallSmoke64 と同一 (ring-3 `syscall` → LSTAR `hlt` stub → trap)。
//
//   ★ Windows + Hyper-V でのみ実行可。Linux では HvVm.create() が KvmVm を選ぶので本 smoke は probe で skip。
//   起動 (Windows): java --enable-native-access=ALL-UNNAMED -cp target/classes emulin.WhpVcpuSmoke64
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class WhpVcpuSmoke64 {

  private static final long GUEST_RAM_SIZE = 0x200000L;   // 2MB
  private static final long PML4_GPA = 0x1000L, PDPT_GPA = 0x2000L, PD_GPA = 0x3000L, PT_GPA = 0x4000L;
  private static final long CODE_GPA = 0x6000L, LSTAR_GPA = 0x7000L, USER_STACK_TOP = 0x10000L;
  private static final long STAR_VALUE = (0x23L << 48) | (0x10L << 32);
  private static final long CR0_LONG_MODE = 0x80050033L, CR4_PAE = 0x20L;
  private static final long EFER_LME = 0x100L, EFER_LMA = 0x400L, EFER_SCE = 0x1L;
  private static final long PTE_P = 0x1L, PTE_RW = 0x2L, PTE_US = 0x4L;
  private static final long MAGIC_SYSNO = 0xCAFEL, MAGIC_ARG0 = 0xBEEFL;
  // arch MSR index (NativeCpuBackend が hv.setMsrs に渡すのと同じ値)
  private static final int MSR_STAR = 0xC0000081, MSR_LSTAR = 0xC0000082, MSR_SFMASK = 0xC0000084;

  public static void main( String[] args ) throws Throwable {
    if( !WhpBindings.probe() ) {
      System.err.println( "[WhpVcpuSmoke64] " + WhpBindings.describeAvailability()
          + " — Windows + Hyper-V (Windows Hypervisor Platform) が必要です。Linux/WSL2 では実行不可。" );
      System.exit( 2 );
    }
    System.out.println( "[WhpVcpuSmoke64] " + WhpBindings.describeAvailability() + " — Stage 3 (HvVm/HvVcpu 経由) を検証します" );

    // ★ Stage 3 の guest RAM 確保 dispatch を通す (Windows では VirtualAlloc 経路)。
    MemorySegment ram = HvVm.allocGuestRam( GUEST_RAM_SIZE );
    System.out.println( "[WhpVcpuSmoke64] HvVm.allocGuestRam OK @0x" + Long.toHexString( ram.address() ) );

    // guest を構築 (WhpSyscallSmoke64 と同一): 4KB identity page table 全 US、ring-3 user code、LSTAR hlt stub。
    long uf = PTE_P | PTE_RW | PTE_US;
    ram.set( ValueLayout.JAVA_LONG, PML4_GPA, PDPT_GPA | uf );
    ram.set( ValueLayout.JAVA_LONG, PDPT_GPA, PD_GPA   | uf );
    ram.set( ValueLayout.JAVA_LONG, PD_GPA,   PT_GPA   | uf );
    for( int i = 0; i < 512; i++ ) ram.set( ValueLayout.JAVA_LONG, PT_GPA + (long) i * 8, ((long) i << 12) | uf );
    byte[] code = { (byte)0xB8,(byte)0xFE,(byte)0xCA,0x00,0x00, (byte)0xBF,(byte)0xEF,(byte)0xBE,0x00,0x00,
                    (byte)0x0F,(byte)0x05, (byte)0xEB,(byte)0xFC };   // mov eax,0xCAFE; mov edi,0xBEEF; syscall; jmp back
    for( int i = 0; i < code.length; i++ ) ram.set( ValueLayout.JAVA_BYTE, CODE_GPA + i, code[i] );
    long syscallRetAddr = CODE_GPA + 12;
    byte[] stub = { (byte)0xF4, (byte)0x48,(byte)0x0F,(byte)0x07 };   // hlt; sysretq
    for( int i = 0; i < stub.length; i++ ) ram.set( ValueLayout.JAVA_BYTE, LSTAR_GPA + i, stub[i] );

    HvVm vm = null; HvVcpu hv = null;
    try( Arena arena = Arena.ofShared() ) {
      // ★ Stage 3 の VM 抽象を通す: HvVm.create() → WhpVm (CreatePartition+ProcessorCount+SetupPartition)
      vm = HvVm.create();
      System.out.println( "[WhpVcpuSmoke64] HvVm.create() OK (" + vm.getClass().getSimpleName() + ")" );
      vm.mapGuestRam( 0L, ram.address(), GUEST_RAM_SIZE );
      System.out.println( "[WhpVcpuSmoke64] vm.mapGuestRam OK" );
      hv = vm.createVcpu( 0, arena, /*ownArena*/ false );
      System.out.println( "[WhpVcpuSmoke64] vm.createVcpu OK (" + hv.getClass().getSimpleName() + ")" );

      // ★ Stage 3 の vCPU 抽象を NativeCpuBackend.setupVcpu と同じ順序で叩く。
      hv.configureLongModeRing3( 0x33, 0x2b, /*dpl*/3, CR0_LONG_MODE, PML4_GPA, CR4_PAE,
          EFER_LME | EFER_LMA | EFER_SCE );
      hv.setMsrs( new long[][]{ { MSR_STAR, STAR_VALUE }, { MSR_LSTAR, LSTAR_GPA }, { MSR_SFMASK, 0L } } );
      hv.readGprs();
      hv.setGpr( HvReg.RIP, CODE_GPA );
      hv.setGpr( HvReg.RSP, USER_STACK_TOP );
      hv.setGpr( HvReg.RFLAGS, 2L );
      hv.writeGprs();
      System.out.println( "[WhpVcpuSmoke64] ring-3 long mode 設定完了、guest 実行..." );

      int exit = hv.run();
      hv.readGprs();
      long rax = hv.getGpr( HvReg.RAX ), rdi = hv.getGpr( HvReg.RDI ), rcx = hv.getGpr( HvReg.RCX );
      System.out.println( "[WhpVcpuSmoke64] run() → " + ( exit == HvVcpu.EXIT_HALT ? "EXIT_HALT" : "EXIT_OTHER(raw=0x"
          + Integer.toHexString( hv.lastRawExit() ) + ")" ) + " rax=0x" + Long.toHexString( rax )
          + " rdi=0x" + Long.toHexString( rdi ) + " rcx=0x" + Long.toHexString( rcx ) );

      boolean ok = exit == HvVcpu.EXIT_HALT && rax == MAGIC_SYSNO && rdi == MAGIC_ARG0 && rcx == syscallRetAddr;
      if( !ok ) {
        System.err.println( "[WhpVcpuSmoke64] FAIL: exit=" + exit + " rax=0x" + Long.toHexString( rax )
            + " (want 0x" + Long.toHexString( MAGIC_SYSNO ) + ") rdi=0x" + Long.toHexString( rdi )
            + " (want 0x" + Long.toHexString( MAGIC_ARG0 ) + ") rcx=0x" + Long.toHexString( rcx )
            + " (want 0x" + Long.toHexString( syscallRetAddr ) + ")" );
        System.exit( 4 );
      }
      System.out.println( "WhpVcpu smoke OK: HvVm/HvVcpu (WHP) 経由で ring-3 syscall trap、sysno=0x"
          + Long.toHexString( rax ) + " arg0=0x" + Long.toHexString( rdi ) + " retaddr=0x" + Long.toHexString( rcx )
          + " を回収。Stage 3 抽象層が WHP で動作。" );
    } finally {
      if( hv != null ) hv.close();
      if( vm != null ) vm.close();
      HvVm.freeGuestRam( ram, GUEST_RAM_SIZE );
    }
    System.exit( 0 );
  }
}
