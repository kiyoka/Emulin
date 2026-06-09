// ----------------------------------------
//  WHP (Windows Hypervisor Platform) 64-bit long-mode smoke (issue #221 step 3e-whp-1)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// 目的: KVM 経路の KvmSmoke64 (step 3b、#241) と同型の smoke を WHP で行う。WHP backend 移植の
//   最初の足場 = WhpBindings の FFM bindings + WHP の register/exit ABI を実機で end-to-end 検証する。
//
// ★ WSL2/Linux では実行できない (Windows 10/11 + Hyper-V「Windows ハイパーバイザー プラットフォーム」
//   有効 + WinHvPlatform.dll が必要)。Linux 上では probe() が false を返し exit 2 で graceful に抜ける。
//   実機検証は Windows で:
//     java --enable-native-access=ALL-UNNAMED -cp target/classes emulin.WhpSmoke64
//   各 WHP API call の HRESULT を出力するので、失敗時はどの段で何の HRESULT かを報告してほしい。
//
// guest プログラム (KvmSmoke64 と同一、guest 物理 0 に identity map):
//   48 B8 88 77 66 55 44 33 22 11   movabs rax, 0x1122334455667788
//   48 89 C3                        mov    rbx, rax
//   F4                              hlt
//   → WHvRunVpExitReasonX64Halt 後に rax==rbx==0x1122334455667788 を検証。
//
// メモリ: guest RAM 2MB を VirtualAlloc(MEM_COMMIT|MEM_RESERVE, PAGE_READWRITE) で確保し
//   WHvMapGpaRange(gpa=0, Read|Write|Execute) で map。identity 2MB huge page (PD[0].PS=1)。
//
// register: KVM の GET/SET_SREGS+REGS struct と違い、WHP は WHV_REGISTER_NAME[] + WHV_REGISTER_VALUE[16B]
//   の name-value 配列で 1 register ずつ設定する。CR0/CR3/CR4/EFER/CS(L=1)/SS/DS/ES/FS/GS/RIP/RFLAGS を
//   一括 set。差は「KVM ioctl 構造体 ⇄ WHP name-value 配列」だけで、long mode の CPU 設定値は同一。
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class WhpSmoke64 {

  private static final long GUEST_RAM_SIZE = 0x200000L;   // 2MB
  private static final long PML4_GPA = 0x1000L, PDPT_GPA = 0x2000L, PD_GPA = 0x3000L;
  private static final long EXPECTED_IMM = 0x1122334455667788L;

  // x86 architectural な long mode 設定値 (KVM と共通。WhpSmoke を self-contained にするため再掲)。
  private static final long CR0_LONG_MODE = 0x80050033L;   // PE|MP|ET|NE|WP|AM|PG
  private static final long CR4_PAE       = 0x20L;
  private static final long EFER_LME = 0x100L, EFER_LMA = 0x400L;
  private static final long PTE_P = 0x1L, PTE_RW = 0x2L, PTE_PS = 0x80L;

  public static void main( String[] args ) throws Throwable {
    if( !WhpBindings.probe() ) {
      System.err.println( "[WhpSmoke64] " + WhpBindings.describeAvailability()
          + " — このテストは Windows + Hyper-V (Windows Hypervisor Platform) が必要です。Linux/WSL2 では実行不可。" );
      System.exit( 2 );
    }
    System.out.println( "[WhpSmoke64] " + WhpBindings.describeAvailability() );

    try( Arena arena = Arena.ofShared() ) {
      // 1. partition 作成
      MemorySegment partBuf = arena.allocate( ValueLayout.ADDRESS );
      hr( "WHvCreatePartition", (int) WhpBindings.createPartition().invoke( partBuf ) );
      MemorySegment partition = partBuf.get( ValueLayout.ADDRESS, 0 );

      // 2. ProcessorCount = 1 (SetupPartition の前に property 設定が必須)
      MemorySegment prop = arena.allocate( WhpBindings.WHV_PARTITION_PROPERTY_SIZE );
      prop.set( ValueLayout.JAVA_INT, 0, 1 );
      hr( "WHvSetPartitionProperty(ProcessorCount=1)", (int) WhpBindings.setPartitionProperty()
          .invoke( partition, WhpBindings.WHvPartitionPropertyCodeProcessorCount, prop,
                   WhpBindings.WHV_PARTITION_PROPERTY_SIZE ) );

      // 3. partition setup
      hr( "WHvSetupPartition", (int) WhpBindings.setupPartition().invoke( partition ) );

      // 4. guest RAM (VirtualAlloc、page-align 済 commit memory)
      MemorySegment guestRam = (MemorySegment) WhpBindings.virtualAlloc().invoke(
          MemorySegment.NULL, GUEST_RAM_SIZE,
          (int) ( WhpBindings.MEM_COMMIT | WhpBindings.MEM_RESERVE ), (int) WhpBindings.PAGE_READWRITE );
      if( guestRam.address() == 0L ) { System.err.println( "[WhpSmoke64] VirtualAlloc failed" ); System.exit( 3 ); }
      guestRam = guestRam.reinterpret( GUEST_RAM_SIZE );
      System.out.println( "[WhpSmoke64] guest RAM host addr = 0x" + Long.toHexString( guestRam.address() ) );

      // guest code @ 0x0
      byte[] code = {
          (byte)0x48, (byte)0xB8, (byte)0x88, (byte)0x77, (byte)0x66, (byte)0x55,
          (byte)0x44, (byte)0x33, (byte)0x22, (byte)0x11,                  // movabs rax, 0x1122334455667788
          (byte)0x48, (byte)0x89, (byte)0xC3,                              // mov rbx, rax
          (byte)0xF4,                                                      // hlt
      };
      for( int i = 0; i < code.length; i++ ) guestRam.set( ValueLayout.JAVA_BYTE, i, code[i] );
      // identity 2MB huge page: PML4→PDPT→PD[0](PS=1, phys 0)
      guestRam.set( ValueLayout.JAVA_LONG, PML4_GPA, PDPT_GPA | PTE_P | PTE_RW );
      guestRam.set( ValueLayout.JAVA_LONG, PDPT_GPA, PD_GPA   | PTE_P | PTE_RW );
      guestRam.set( ValueLayout.JAVA_LONG, PD_GPA,   0L       | PTE_P | PTE_RW | PTE_PS );

      // 5. GPA map (guest 物理 0, 2MB, RWX)
      hr( "WHvMapGpaRange", (int) WhpBindings.mapGpaRange().invoke( partition, guestRam, 0L, GUEST_RAM_SIZE,
          WhpBindings.WHvMapGpaRangeFlagRead | WhpBindings.WHvMapGpaRangeFlagWrite | WhpBindings.WHvMapGpaRangeFlagExecute ) );

      // 6. vcpu 0
      hr( "WHvCreateVirtualProcessor", (int) WhpBindings.createVirtualProcessor().invoke( partition, 0, 0 ) );

      // 7. long mode register 設定
      setLongModeRegs( arena, partition );

      // 8. run
      System.out.println( "[WhpSmoke64] running guest (movabs rax,imm64; mov rbx,rax; hlt) in long mode ..." );
      MemorySegment exitCtx = arena.allocate( WhpBindings.WHV_RUN_VP_EXIT_CONTEXT_SIZE );
      long t0 = System.nanoTime();
      hr( "WHvRunVirtualProcessor", (int) WhpBindings.runVirtualProcessor().invoke(
          partition, 0, exitCtx, WhpBindings.WHV_RUN_VP_EXIT_CONTEXT_SIZE ) );
      long elapsed = System.nanoTime() - t0;
      int exitReason = exitCtx.get( ValueLayout.JAVA_INT, WhpBindings.WHV_EXIT_OFF_REASON );
      System.out.println( "[WhpSmoke64] exit_reason = 0x" + Integer.toHexString( exitReason )
          + " (" + exitReasonName( exitReason ) + ") elapsed=" + elapsed + "ns" );

      // 9. RAX/RBX/RIP 読み出し
      long[] v = getRegs( arena, partition, new int[]{
          WhpBindings.WHvX64RegisterRax, WhpBindings.WHvX64RegisterRbx, WhpBindings.WHvX64RegisterRip } );
      long rax = v[0], rbx = v[1], rip = v[2];
      System.out.println( "[WhpSmoke64] rax=0x" + Long.toHexString( rax ) + " rbx=0x" + Long.toHexString( rbx )
          + " rip=0x" + Long.toHexString( rip ) );

      // cleanup
      WhpBindings.deleteVirtualProcessor().invoke( partition, 0 );
      WhpBindings.deletePartition().invoke( partition );
      WhpBindings.virtualFree().invoke( guestRam, 0L, (int) WhpBindings.MEM_RELEASE );

      boolean ok = exitReason == WhpBindings.WHvRunVpExitReasonX64Halt
          && rax == EXPECTED_IMM && rbx == EXPECTED_IMM;
      if( ok ) {
        System.out.println( "WHP64 smoke OK: long mode + 64-bit exec verified (rax=rbx=0x"
            + Long.toHexString( EXPECTED_IMM ) + ", X64Halt, WHvRunVirtualProcessor " + elapsed + "ns)" );
        System.exit( 0 );
      } else {
        System.err.println( "WHP64 smoke FAIL: exit=0x" + Integer.toHexString( exitReason )
            + " rax=0x" + Long.toHexString( rax ) + " rbx=0x" + Long.toHexString( rbx )
            + " (expected X64Halt(0x8) rax=rbx=0x" + Long.toHexString( EXPECTED_IMM ) + ")" );
        System.exit( 4 );
      }
    }
  }

  // long mode CPU register を WHV name-value 配列で一括 set。
  private static void setLongModeRegs( Arena arena, MemorySegment partition ) throws Throwable {
    int[] names = {
        WhpBindings.WHvX64RegisterCr0,  WhpBindings.WHvX64RegisterCr3,  WhpBindings.WHvX64RegisterCr4,
        WhpBindings.WHvX64RegisterEfer, WhpBindings.WHvX64RegisterCs,   WhpBindings.WHvX64RegisterSs,
        WhpBindings.WHvX64RegisterDs,   WhpBindings.WHvX64RegisterEs,   WhpBindings.WHvX64RegisterFs,
        WhpBindings.WHvX64RegisterGs,   WhpBindings.WHvX64RegisterRip,  WhpBindings.WHvX64RegisterRflags,
    };
    int n = names.length;
    MemorySegment nameArr = arena.allocate( (long) n * 4 );
    MemorySegment valArr  = arena.allocate( (long) n * WhpBindings.WHV_REGISTER_VALUE_SIZE );
    for( int i = 0; i < n; i++ ) nameArr.set( ValueLayout.JAVA_INT, (long) i * 4, names[i] );
    setU64( valArr, 0, CR0_LONG_MODE );          // Cr0
    setU64( valArr, 1, PML4_GPA );               // Cr3
    setU64( valArr, 2, CR4_PAE );                // Cr4
    setU64( valArr, 3, EFER_LME | EFER_LMA );    // Efer
    setSeg( valArr, 4, 0x8,  WhpBindings.WHV_SEG_ATTR_CODE64 );   // Cs (L=1)
    setSeg( valArr, 5, 0x10, WhpBindings.WHV_SEG_ATTR_DATA );     // Ss
    setSeg( valArr, 6, 0x10, WhpBindings.WHV_SEG_ATTR_DATA );     // Ds
    setSeg( valArr, 7, 0x10, WhpBindings.WHV_SEG_ATTR_DATA );     // Es
    setSeg( valArr, 8, 0x10, WhpBindings.WHV_SEG_ATTR_DATA );     // Fs
    setSeg( valArr, 9, 0x10, WhpBindings.WHV_SEG_ATTR_DATA );     // Gs
    setU64( valArr, 10, 0L );                    // Rip
    setU64( valArr, 11, 2L );                    // Rflags (bit1=1)
    hr( "WHvSetVirtualProcessorRegisters", (int) WhpBindings.setVirtualProcessorRegisters()
        .invoke( partition, 0, nameArr, n, valArr ) );
    System.out.println( "[WhpSmoke64] long mode registers set (CR0=0x" + Long.toHexString( CR0_LONG_MODE )
        + " CR3=0x" + Long.toHexString( PML4_GPA ) + " CR4=PAE EFER=LME|LMA cs.l=1)" );
  }

  // 16-byte WHV_REGISTER_VALUE slot idx に u64 を書く (低位 8 byte)。
  private static void setU64( MemorySegment valArr, int idx, long v ) {
    valArr.set( ValueLayout.JAVA_LONG, (long) idx * WhpBindings.WHV_REGISTER_VALUE_SIZE, v );
  }
  // 16-byte slot idx に WHV_X64_SEGMENT_REGISTER を書く (base=0, limit=0xFFFFFFFF)。
  private static void setSeg( MemorySegment valArr, int idx, int selector, int attr ) {
    long off = (long) idx * WhpBindings.WHV_REGISTER_VALUE_SIZE;
    valArr.set( ValueLayout.JAVA_LONG,  off + WhpBindings.WHV_SEG_OFF_BASE,       0L );
    valArr.set( ValueLayout.JAVA_INT,   off + WhpBindings.WHV_SEG_OFF_LIMIT,      0xFFFFFFFF );
    valArr.set( ValueLayout.JAVA_SHORT, off + WhpBindings.WHV_SEG_OFF_SELECTOR,   (short) selector );
    valArr.set( ValueLayout.JAVA_SHORT, off + WhpBindings.WHV_SEG_OFF_ATTRIBUTES, (short) attr );
  }

  // 指定 register を WHV name-value 配列で読み出し、各 u64 を返す。
  private static long[] getRegs( Arena arena, MemorySegment partition, int[] names ) throws Throwable {
    int n = names.length;
    MemorySegment nameArr = arena.allocate( (long) n * 4 );
    MemorySegment valArr  = arena.allocate( (long) n * WhpBindings.WHV_REGISTER_VALUE_SIZE );
    for( int i = 0; i < n; i++ ) nameArr.set( ValueLayout.JAVA_INT, (long) i * 4, names[i] );
    hr( "WHvGetVirtualProcessorRegisters", (int) WhpBindings.getVirtualProcessorRegisters()
        .invoke( partition, 0, nameArr, n, valArr ) );
    long[] out = new long[ n ];
    for( int i = 0; i < n; i++ )
      out[i] = valArr.get( ValueLayout.JAVA_LONG, (long) i * WhpBindings.WHV_REGISTER_VALUE_SIZE );
    return out;
  }

  // HRESULT check: 0 (S_OK) 以外は失敗を出力して exit。
  private static void hr( String op, int hresult ) {
    if( hresult != 0 ) {
      System.err.println( "[WhpSmoke64] " + op + " failed: HRESULT=0x" + Integer.toHexString( hresult ) );
      System.exit( 3 );
    }
    System.out.println( "[WhpSmoke64] " + op + " OK" );
  }

  private static String exitReasonName( int r ) {
    if( r == WhpBindings.WHvRunVpExitReasonX64Halt )         return "X64Halt";
    if( r == WhpBindings.WHvRunVpExitReasonMemoryAccess )    return "MemoryAccess";
    if( r == WhpBindings.WHvRunVpExitReasonX64IoPortAccess ) return "X64IoPortAccess";
    if( r == WhpBindings.WHvRunVpExitReasonX64Cpuid )        return "X64Cpuid";
    if( r == WhpBindings.WHvRunVpExitReasonX64MsrAccess )    return "X64MsrAccess";
    if( r == WhpBindings.WHvRunVpExitReasonNone )            return "None";
    if( r == WhpBindings.WHvRunVpExitReasonCanceled )        return "Canceled";
    return "0x" + Integer.toHexString( r );
  }
}
