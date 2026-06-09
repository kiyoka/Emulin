// ----------------------------------------
//  WHP ring-3 syscall-trap smoke + latency (issue #221 Phase 0 step 3e-whp-2)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// 目的: KVM の KvmSyscallSmoke (step 3c、#242、★go/no-go) と同型を WHP で行う。native backend の中核
//   機構 = 「guest user code (ring 3) の `syscall` を LSTAR `hlt` スタブで VM-exit させ VMM にトラップ」
//   を WHP partition で実証し、**1 syscall あたりの trap round-trip latency を実測**する。
//
// ★★ WHP は Windows + Hyper-V でしか実行できない。だが Hyper-V は Windows host の **L1 (root) ハイパー
//   バイザー**なので、WSL2 nested KVM (L2) と違い **non-nested = bare-metal 相当の latency** が得られる。
//   = step 3f (bare-metal trap latency 絶対値=要実機) の測定をこの smoke が兼ねる。WSL2 KVM の
//   nested-inflated ~6µs に対し、WHP は ~1-3µs 見込み。
//
// 機構 (KvmSyscallSmoke と同一、register 設定だけ WHP name-value 配列に):
//   - 4KB page table 全 US → ring 3 から実行可。EFER.SCE + STAR/LSTAR/FMASK。CS/SS dpl=3。
//   - user code @ 0x6000 (ring 3): mov eax,0xCAFE; mov edi,0xBEEF; syscall; jmp back。
//   - LSTAR スタブ @ 0x7000 (ring 0): hlt (→X64Halt で VMM へ); sysretq (ring 3 復帰)。
//   - 検証: X64Halt + rax==0xCAFE(sysno) + rdi==0xBEEF(arg0) + rcx==syscall 直後アドレス を回収。
//   - latency: 各 iter で Rip=sysretq に set→Run→X64Halt の round-trip 時間を実測 (WHP は X64Halt 後の
//     RIP 挙動が KVM と違いうるので Rip を明示 set して robust に。native backend も per-syscall で
//     Get/SetRegisters するので realistic な per-trap cost に相当)。
//
// 起動 (Windows): java --enable-native-access=ALL-UNNAMED -cp target/classes emulin.WhpSyscallSmoke64
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class WhpSyscallSmoke64 {

  private static final long GUEST_RAM_SIZE = 0x200000L;   // 2MB
  private static final long PML4_GPA = 0x1000L, PDPT_GPA = 0x2000L, PD_GPA = 0x3000L, PT_GPA = 0x4000L;
  private static final long CODE_GPA = 0x6000L, LSTAR_GPA = 0x7000L, USER_STACK_TOP = 0x10000L;

  // STAR: [63:48]=0x23 → sysretq user CS=0x33/SS=0x2b、[47:32]=0x10 → syscall kernel CS=0x10/SS=0x18 (Linux 規約)
  private static final long STAR_VALUE = (0x23L << 48) | (0x10L << 32);
  private static final long USER_CS_SEL = 0x33, USER_SS_SEL = 0x2b;
  private static final long MAGIC_SYSNO = 0xCAFEL, MAGIC_ARG0 = 0xBEEFL;

  // long mode 設定値 (architectural)。EFER.SCE = bit0 (syscall 有効)。
  private static final long CR0_LONG_MODE = 0x80050033L, CR4_PAE = 0x20L;
  private static final long EFER_LME = 0x100L, EFER_LMA = 0x400L, EFER_SCE = 0x1L;
  private static final long PTE_P = 0x1L, PTE_RW = 0x2L, PTE_US = 0x4L;
  // ring-3 segment attributes: code64 = type0xB,S=1,DPL=3,P=1,L=1,G=1 → 0xA0FB、data = type0x3,S,DPL3,P,D,G → 0xC0F3
  private static final int SEG_ATTR_USER_CODE64 = 0xA0FB, SEG_ATTR_USER_DATA = 0xC0F3;

  private static final int WARMUP_ITERS = 5_000, TIMED_ITERS = 100_000;

  public static void main( String[] args ) throws Throwable {
    if( !WhpBindings.probe() ) {
      System.err.println( "[WhpSyscallSmoke64] " + WhpBindings.describeAvailability()
          + " — Windows + Hyper-V (Windows Hypervisor Platform) が必要です。Linux/WSL2 では実行不可。" );
      System.exit( 2 );
    }
    System.out.println( "[WhpSyscallSmoke64] " + WhpBindings.describeAvailability() );

    try( Arena arena = Arena.ofShared() ) {
      MemorySegment partBuf = arena.allocate( ValueLayout.ADDRESS );
      hr( "WHvCreatePartition", (int) WhpBindings.createPartition().invoke( partBuf ) );
      MemorySegment partition = partBuf.get( ValueLayout.ADDRESS, 0 );
      MemorySegment prop = arena.allocate( WhpBindings.WHV_PARTITION_PROPERTY_SIZE );
      prop.set( ValueLayout.JAVA_INT, 0, 1 );
      hr( "WHvSetPartitionProperty(ProcessorCount=1)", (int) WhpBindings.setPartitionProperty()
          .invoke( partition, WhpBindings.WHvPartitionPropertyCodeProcessorCount, prop, WhpBindings.WHV_PARTITION_PROPERTY_SIZE ) );
      hr( "WHvSetupPartition", (int) WhpBindings.setupPartition().invoke( partition ) );

      MemorySegment ram = (MemorySegment) WhpBindings.virtualAlloc().invoke(
          MemorySegment.NULL, GUEST_RAM_SIZE, (int)( WhpBindings.MEM_COMMIT | WhpBindings.MEM_RESERVE ), (int) WhpBindings.PAGE_READWRITE );
      if( ram.address() == 0L ) { System.err.println( "VirtualAlloc failed" ); System.exit( 3 ); }
      ram = ram.reinterpret( GUEST_RAM_SIZE );

      // 4KB identity page table、全 US (ring 3 実行可)
      long uf = PTE_P | PTE_RW | PTE_US;
      ram.set( ValueLayout.JAVA_LONG, PML4_GPA, PDPT_GPA | uf );
      ram.set( ValueLayout.JAVA_LONG, PDPT_GPA, PD_GPA   | uf );
      ram.set( ValueLayout.JAVA_LONG, PD_GPA,   PT_GPA   | uf );
      for( int i = 0; i < 512; i++ ) ram.set( ValueLayout.JAVA_LONG, PT_GPA + (long) i * 8, ((long) i << 12) | uf );
      // user code @ 0x6000 (ring 3)
      byte[] code = { (byte)0xB8,(byte)0xFE,(byte)0xCA,0x00,0x00, (byte)0xBF,(byte)0xEF,(byte)0xBE,0x00,0x00,
                      (byte)0x0F,(byte)0x05, (byte)0xEB,(byte)0xFC };
      for( int i = 0; i < code.length; i++ ) ram.set( ValueLayout.JAVA_BYTE, CODE_GPA + i, code[i] );
      long syscallRetAddr = CODE_GPA + 12;   // syscall(@+10,2byte) の次 = jmp = 0x600c
      // LSTAR スタブ @ 0x7000 (ring 0): hlt; sysretq
      byte[] stub = { (byte)0xF4, (byte)0x48,(byte)0x0F,(byte)0x07 };
      for( int i = 0; i < stub.length; i++ ) ram.set( ValueLayout.JAVA_BYTE, LSTAR_GPA + i, stub[i] );
      long afterHlt = LSTAR_GPA + 1;   // hlt の次 = sysretq = 0x7001

      hr( "WHvMapGpaRange", (int) WhpBindings.mapGpaRange().invoke( partition, ram, 0L, GUEST_RAM_SIZE,
          WhpBindings.WHvMapGpaRangeFlagRead | WhpBindings.WHvMapGpaRangeFlagWrite | WhpBindings.WHvMapGpaRangeFlagExecute ) );
      hr( "WHvCreateVirtualProcessor", (int) WhpBindings.createVirtualProcessor().invoke( partition, 0, 0 ) );

      // ring-3 long mode + EFER.SCE + STAR/LSTAR/FMASK
      setRing3Regs( arena, partition );

      // === (1) 単発: ring-3 syscall → trap、sysno/arg/retaddr 回収を検証 ===
      System.out.println( "[WhpSyscallSmoke64] running ring-3 guest, expecting syscall trap ..." );
      MemorySegment exitCtx = arena.allocate( WhpBindings.WHV_RUN_VP_EXIT_CONTEXT_SIZE );
      hr( "WHvRunVirtualProcessor(1)", (int) WhpBindings.runVirtualProcessor().invoke( partition, 0, exitCtx, WhpBindings.WHV_RUN_VP_EXIT_CONTEXT_SIZE ) );
      int exitReason = exitCtx.get( ValueLayout.JAVA_INT, WhpBindings.WHV_EXIT_OFF_REASON );
      long[] v = getRegs( arena, partition, new int[]{
          WhpBindings.WHvX64RegisterRax, WhpBindings.WHvX64RegisterRdi, WhpBindings.WHvX64RegisterRcx, WhpBindings.WHvX64RegisterRip } );
      long rax = v[0], rdi = v[1], rcx = v[2], rip = v[3];
      System.out.println( "[WhpSyscallSmoke64] exit_reason=0x" + Integer.toHexString( exitReason )
          + " rax=0x" + Long.toHexString( rax ) + " rdi=0x" + Long.toHexString( rdi )
          + " rcx(retaddr)=0x" + Long.toHexString( rcx ) + " rip=0x" + Long.toHexString( rip ) );
      boolean trapOk = exitReason == WhpBindings.WHvRunVpExitReasonX64Halt
          && rax == MAGIC_SYSNO && rdi == MAGIC_ARG0 && rcx == syscallRetAddr;
      if( !trapOk ) {
        System.err.println( "SYSCALL TRAP FAIL: exit=0x" + Integer.toHexString( exitReason )
            + " rax=0x" + Long.toHexString( rax ) + " (want 0x" + Long.toHexString( MAGIC_SYSNO ) + ")"
            + " rdi=0x" + Long.toHexString( rdi ) + " (want 0x" + Long.toHexString( MAGIC_ARG0 ) + ")"
            + " rcx=0x" + Long.toHexString( rcx ) + " (want 0x" + Long.toHexString( syscallRetAddr ) + ")" );
        System.exit( 4 );
      }
      System.out.println( "[WhpSyscallSmoke64] ✓ syscall trap OK: ring-3 syscall trapped to VMM, sysno=0x"
          + Long.toHexString( rax ) + " arg0=0x" + Long.toHexString( rdi ) + " return-addr=0x" + Long.toHexString( rcx ) + " captured." );
      System.out.println( "[WhpSyscallSmoke64]   (X64Halt 後 rip=0x" + Long.toHexString( rip )
          + " — WHP は " + ( rip == afterHlt ? "hlt の次に進む (KVM 同様)" : "hlt 位置に留まる" ) + ")" );

      // === (2) latency: 各 iter で Rip=sysretq に set→Run→X64Halt の round-trip を実測 ===
      MemorySegment ripName = arena.allocate( 4 ); ripName.set( ValueLayout.JAVA_INT, 0, WhpBindings.WHvX64RegisterRip );
      MemorySegment ripVal  = arena.allocate( WhpBindings.WHV_REGISTER_VALUE_SIZE );
      ripVal.set( ValueLayout.JAVA_LONG, 0, afterHlt );
      for( int i = 0; i < WARMUP_ITERS; i++ ) roundTrip( partition, ripName, ripVal, exitCtx, "warmup" );
      long[] samples = new long[ TIMED_ITERS ];
      for( int i = 0; i < TIMED_ITERS; i++ ) {
        long s = System.nanoTime();
        roundTrip( partition, ripName, ripVal, exitCtx, "timed" );
        samples[i] = System.nanoTime() - s;
      }
      long[] sorted = samples.clone(); java.util.Arrays.sort( sorted );
      long min = sorted[0], med = sorted[ TIMED_ITERS / 2 ], p99 = sorted[ (int)( TIMED_ITERS * 0.99 ) ], max = sorted[ TIMED_ITERS - 1 ];
      long sum = 0; for( long x : samples ) sum += x; long mean = sum / TIMED_ITERS;

      WhpBindings.deleteVirtualProcessor().invoke( partition, 0 );
      WhpBindings.deletePartition().invoke( partition );
      WhpBindings.virtualFree().invoke( ram, 0L, (int) WhpBindings.MEM_RELEASE );

      System.out.println( "[WhpSyscallSmoke64] latency over " + TIMED_ITERS + " round-trips (SetRip+Run+X64Halt):" );
      System.out.println( "[WhpSyscallSmoke64]   min=" + min + " median=" + med + " mean=" + mean + " p99=" + p99 + " max=" + max + " ns" );
      System.out.println( "[WhpSyscallSmoke64]   ★ WHP = Hyper-V L1 (non-nested) なので bare-metal 相当 = step 3f の latency 実測。"
          + " WSL2 nested KVM の ~6µs と比較してください。" );
      System.out.println( "WHP syscall-trap smoke OK: ring-3 syscall traps to VMM (sysno/arg/retaddr 回収); "
          + "round-trip median " + med + "ns (non-nested)。" );
      System.exit( 0 );
    }
  }

  // 1 round-trip: Rip=sysretq に set → Run → X64Halt 検証。
  private static void roundTrip( MemorySegment partition, MemorySegment ripName, MemorySegment ripVal,
                                 MemorySegment exitCtx, String where ) throws Throwable {
    int r1 = (int) WhpBindings.setVirtualProcessorRegisters().invoke( partition, 0, ripName, 1, ripVal );
    if( r1 != 0 ) { System.err.println( "[WhpSyscallSmoke64] " + where + " SetRegisters HRESULT=0x" + Integer.toHexString( r1 ) ); System.exit( 5 ); }
    int r2 = (int) WhpBindings.runVirtualProcessor().invoke( partition, 0, exitCtx, WhpBindings.WHV_RUN_VP_EXIT_CONTEXT_SIZE );
    if( r2 != 0 ) { System.err.println( "[WhpSyscallSmoke64] " + where + " Run HRESULT=0x" + Integer.toHexString( r2 ) ); System.exit( 5 ); }
    int er = exitCtx.get( ValueLayout.JAVA_INT, WhpBindings.WHV_EXIT_OFF_REASON );
    if( er != WhpBindings.WHvRunVpExitReasonX64Halt ) {
      System.err.println( "[WhpSyscallSmoke64] " + where + ": exit_reason=0x" + Integer.toHexString( er )
          + " (expected X64Halt=0x8) — round-trip が壊れた、計測無効" ); System.exit( 5 );
    }
  }

  // ring-3 long mode register を一括 set。
  private static void setRing3Regs( Arena arena, MemorySegment partition ) throws Throwable {
    int[] names = {
        WhpBindings.WHvX64RegisterCr0,  WhpBindings.WHvX64RegisterCr3,  WhpBindings.WHvX64RegisterCr4,
        WhpBindings.WHvX64RegisterEfer, WhpBindings.WHvX64RegisterStar, WhpBindings.WHvX64RegisterLstar,
        WhpBindings.WHvX64RegisterSfmask, WhpBindings.WHvX64RegisterCs, WhpBindings.WHvX64RegisterSs,
        WhpBindings.WHvX64RegisterDs,   WhpBindings.WHvX64RegisterEs,   WhpBindings.WHvX64RegisterFs,
        WhpBindings.WHvX64RegisterGs,   WhpBindings.WHvX64RegisterRip,  WhpBindings.WHvX64RegisterRsp,
        WhpBindings.WHvX64RegisterRflags,
    };
    int n = names.length;
    MemorySegment nameArr = arena.allocate( (long) n * 4 );
    MemorySegment valArr  = arena.allocate( (long) n * WhpBindings.WHV_REGISTER_VALUE_SIZE );
    for( int i = 0; i < n; i++ ) nameArr.set( ValueLayout.JAVA_INT, (long) i * 4, names[i] );
    setU64( valArr, 0, CR0_LONG_MODE );                  // Cr0
    setU64( valArr, 1, PML4_GPA );                       // Cr3
    setU64( valArr, 2, CR4_PAE );                        // Cr4
    setU64( valArr, 3, EFER_LME | EFER_LMA | EFER_SCE ); // Efer (SCE で syscall 有効)
    setU64( valArr, 4, STAR_VALUE );                     // Star
    setU64( valArr, 5, LSTAR_GPA );                      // Lstar (hlt スタブ)
    setU64( valArr, 6, 0L );                             // Sfmask
    setSeg( valArr, 7, (int) USER_CS_SEL, SEG_ATTR_USER_CODE64 );   // Cs (dpl=3, L=1)
    setSeg( valArr, 8, (int) USER_SS_SEL, SEG_ATTR_USER_DATA );     // Ss (dpl=3)
    setSeg( valArr, 9,  (int) USER_SS_SEL, SEG_ATTR_USER_DATA );    // Ds
    setSeg( valArr, 10, (int) USER_SS_SEL, SEG_ATTR_USER_DATA );    // Es
    setSeg( valArr, 11, (int) USER_SS_SEL, SEG_ATTR_USER_DATA );    // Fs
    setSeg( valArr, 12, (int) USER_SS_SEL, SEG_ATTR_USER_DATA );    // Gs
    setU64( valArr, 13, CODE_GPA );                      // Rip = user code (ring 3)
    setU64( valArr, 14, USER_STACK_TOP );                // Rsp
    setU64( valArr, 15, 2L );                            // Rflags
    hr( "WHvSetVirtualProcessorRegisters(ring3)", (int) WhpBindings.setVirtualProcessorRegisters().invoke( partition, 0, nameArr, n, valArr ) );
    System.out.println( "[WhpSyscallSmoke64] ring-3 long mode set (EFER.SCE on, STAR=0x" + Long.toHexString( STAR_VALUE )
        + " LSTAR=0x" + Long.toHexString( LSTAR_GPA ) + " cs.dpl=3)" );
  }

  private static void setU64( MemorySegment a, int idx, long v ) { a.set( ValueLayout.JAVA_LONG, (long) idx * WhpBindings.WHV_REGISTER_VALUE_SIZE, v ); }
  private static void setSeg( MemorySegment a, int idx, int selector, int attr ) {
    long off = (long) idx * WhpBindings.WHV_REGISTER_VALUE_SIZE;
    a.set( ValueLayout.JAVA_LONG,  off + WhpBindings.WHV_SEG_OFF_BASE,       0L );
    a.set( ValueLayout.JAVA_INT,   off + WhpBindings.WHV_SEG_OFF_LIMIT,      0xFFFFFFFF );
    a.set( ValueLayout.JAVA_SHORT, off + WhpBindings.WHV_SEG_OFF_SELECTOR,   (short) selector );
    a.set( ValueLayout.JAVA_SHORT, off + WhpBindings.WHV_SEG_OFF_ATTRIBUTES, (short) attr );
  }
  private static long[] getRegs( Arena arena, MemorySegment partition, int[] names ) throws Throwable {
    int n = names.length;
    MemorySegment nameArr = arena.allocate( (long) n * 4 );
    MemorySegment valArr  = arena.allocate( (long) n * WhpBindings.WHV_REGISTER_VALUE_SIZE );
    for( int i = 0; i < n; i++ ) nameArr.set( ValueLayout.JAVA_INT, (long) i * 4, names[i] );
    hr( "WHvGetVirtualProcessorRegisters", (int) WhpBindings.getVirtualProcessorRegisters().invoke( partition, 0, nameArr, n, valArr ) );
    long[] out = new long[ n ];
    for( int i = 0; i < n; i++ ) out[i] = valArr.get( ValueLayout.JAVA_LONG, (long) i * WhpBindings.WHV_REGISTER_VALUE_SIZE );
    return out;
  }
  private static void hr( String op, int hresult ) {
    if( hresult != 0 ) { System.err.println( "[WhpSyscallSmoke64] " + op + " failed: HRESULT=0x" + Integer.toHexString( hresult ) ); System.exit( 3 ); }
    System.out.println( "[WhpSyscallSmoke64] " + op + " OK" );
  }
}
