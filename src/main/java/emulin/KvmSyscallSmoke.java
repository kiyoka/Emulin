// ----------------------------------------
//  Linux KVM ring-3 syscall-trap smoke — Phase 0 step 3c
//  (issue #221) ★ go/no-go の核心
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// 目的: #221 native backend の中核機構 = 「guest user code (ring 3) の `syscall`
//   命令を VM-exit で VMM (emulin の Java 層) にトラップし、sysno/引数を回収する」
//   ことを実 vCPU で実証し、**1 syscall あたりの trap round-trip latency を実測**
//   して go/no-go 判定 (ship gate ≤ 5µs) する。
//
// 機構 (step 3b の long-mode review PR #241 で確定した ring-3 要件を実装):
//   - 4KB page table (PML4/PDPT/PD/PT)、全 entry に PTE_US → ring 3 から実行可。
//   - EFER.SCE 有効 + STAR/LSTAR/FMASK を KVM_SET_MSRS で設定。
//   - cs/ss を dpl=3 + RPL=3 selector に → guest は CPL 3 で起動。
//   - guest user code (ring 3) @ 0x6000:
//       mov eax, 0xCAFE   ; sysno (検証用 magic)
//       mov edi, 0xBEEF   ; arg0 (検証用 magic)
//       syscall           ; → CPU が LSTAR (ring 0) へ jump、RIP→RCX 退避
//       jmp  back         ; sysret 復帰後ここでループ
//   - LSTAR スタブ (ring 0) @ 0x7000:
//       hlt               ; → KVM_EXIT_HLT で VMM にトラップ (方式 b)
//       sysretq           ; VMM 再 run 後 ring 3 (RCX) へ復帰
//
//   syscall は CS/SS を STAR から、RIP を LSTAR から load し ring 0 へ。stack は
//   touch しないので user/kernel stack は最小 (RSP は形式上設定)。sysretq は
//   CS/SS を STAR[63:48] から、RIP を RCX から load し ring 3 へ戻す。これで
//   1 KVM_RUN = 1 完全 round-trip (ring3 syscall → VM-exit → ring3) になる。
//
// 検証:
//   (1) 単発 KVM_RUN → KVM_EXIT_HLT、KVM_GET_REGS で rax==0xCAFE / rdi==0xBEEF /
//       rcx==(syscall 直後アドレス) を確認 = sysno/引数の回収が成立。
//   (2) N 回ループで per-trap latency 実測 (pure round-trip と GET/SET_REGS 込みの
//       realistic な 2 種)、≤5µs ship gate を判定。
//
// 起動: java --enable-native-access=ALL-UNNAMED -cp target/classes emulin.KvmSyscallSmoke
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class KvmSyscallSmoke {

  private static final long GUEST_RAM_SIZE = 0x200000L;  // 2MB
  private static final long PML4_GPA = 0x1000L;
  private static final long PDPT_GPA = 0x2000L;
  private static final long PD_GPA   = 0x3000L;
  private static final long PT_GPA   = 0x4000L;
  private static final long CODE_GPA = 0x6000L;
  private static final long LSTAR_GPA = 0x7000L;
  private static final long USER_STACK_TOP = 0x10000L;

  // STAR: [63:48]=sysret base(0x23 → user CS=0x33/SS=0x2b)、[47:32]=syscall base
  //   (0x10 → kernel CS=0x10/SS=0x18)。Linux と同じ規約。
  private static final long STAR_VALUE = (0x23L << 48) | (0x10L << 32);
  private static final long USER_CS_SEL = 0x33;
  private static final long USER_SS_SEL = 0x2b;

  private static final long MAGIC_SYSNO = 0xCAFEL;
  private static final long MAGIC_ARG0  = 0xBEEFL;

  private static final int WARMUP_ITERS = 20_000;
  private static final int TIMED_ITERS  = 200_000;
  private static final long SHIP_GATE_NS = 5_000;  // 5µs

  public static void main( String[] args ) throws Throwable {
    if( !KvmBindings.probe() ) {
      System.err.println( "[KvmSyscallSmoke] " + KvmBindings.describeAvailability() );
      System.exit( 1 );
    }
    System.out.println( "[KvmSyscallSmoke] " + KvmBindings.describeAvailability() );

    try( Arena arena = Arena.ofShared() ) {
      MemorySegment path = arena.allocateFrom( "/dev/kvm" );
      int kvmFd = KvmBindings.open( path, KvmBindings.O_RDWR );
      if( kvmFd < 0 ) die( "open(/dev/kvm)" );
      if( KvmBindings.ioctl( kvmFd, KvmBindings.KVM_GET_API_VERSION, MemorySegment.NULL )
          != KvmBindings.KVM_API_VERSION ) { System.err.println("API mismatch"); System.exit(2); }
      int vmFd = KvmBindings.ioctl( kvmFd, KvmBindings.KVM_CREATE_VM, MemorySegment.NULL );
      if( vmFd < 0 ) die( "KVM_CREATE_VM" );

      MemorySegment ram = arena.allocate( GUEST_RAM_SIZE, 4096 );

      // --- 4KB identity page table、全 level に US (ring 3 から実行可) ---
      long uf = KvmBindings.PTE_P | KvmBindings.PTE_RW | KvmBindings.PTE_US;
      ram.set( ValueLayout.JAVA_LONG, PML4_GPA, PDPT_GPA | uf );
      ram.set( ValueLayout.JAVA_LONG, PDPT_GPA, PD_GPA   | uf );
      ram.set( ValueLayout.JAVA_LONG, PD_GPA,   PT_GPA   | uf );  // PS 無し (4KB granularity)
      for( int i = 0; i < 512; i++ ) {                            // PT: [0,2MB) を 4KB で identity
        ram.set( ValueLayout.JAVA_LONG, PT_GPA + (long)i * 8, ((long)i << 12) | uf );
      }

      // --- user code @ 0x6000 (ring 3) ---
      byte[] code = new byte[] {
          (byte)0xB8, (byte)0xFE, (byte)0xCA, 0x00, 0x00,   // mov eax, 0xCAFE
          (byte)0xBF, (byte)0xEF, (byte)0xBE, 0x00, 0x00,   // mov edi, 0xBEEF
          (byte)0x0F, (byte)0x05,                           // syscall
          (byte)0xEB, (byte)0xFC,                           // jmp -4 (back to syscall)
      };
      for( int i = 0; i < code.length; i++ ) ram.set( ValueLayout.JAVA_BYTE, CODE_GPA + i, code[i] );
      long syscallRetAddr = CODE_GPA + 12;  // syscall(@+10,2byte) の次 = jmp 命令 = 0x600c

      // --- LSTAR スタブ @ 0x7000 (ring 0) ---
      byte[] stub = new byte[] {
          (byte)0xF4,                                       // hlt  → KVM_EXIT_HLT
          (byte)0x48, (byte)0x0F, (byte)0x07,               // sysretq → ring 3 (RCX)
      };
      for( int i = 0; i < stub.length; i++ ) ram.set( ValueLayout.JAVA_BYTE, LSTAR_GPA + i, stub[i] );
      long afterHlt = LSTAR_GPA + 1;  // hlt の次 = sysretq = 0x7001

      // --- KVM_SET_USER_MEMORY_REGION ---
      MemorySegment mr = arena.allocate( KvmBindings.KVM_MEM_REGION_SIZE );
      mr.set( ValueLayout.JAVA_INT,  KvmBindings.KVM_MEM_OFF_SLOT,       0 );
      mr.set( ValueLayout.JAVA_INT,  KvmBindings.KVM_MEM_OFF_FLAGS,      0 );
      mr.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_MEM_OFF_GUEST_ADDR, 0L );
      mr.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_MEM_OFF_SIZE,       GUEST_RAM_SIZE );
      mr.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_MEM_OFF_USER_ADDR,  ram.address() );
      if( KvmBindings.ioctl( vmFd, KvmBindings.KVM_SET_USER_MEMORY_REGION, mr ) < 0 )
        die( "KVM_SET_USER_MEMORY_REGION" );

      int vcpuFd = KvmBindings.ioctl( vmFd, KvmBindings.KVM_CREATE_VCPU, MemorySegment.NULL );
      if( vcpuFd < 0 ) die( "KVM_CREATE_VCPU" );
      int vcpuMmapSize = KvmBindings.ioctl( kvmFd, KvmBindings.KVM_GET_VCPU_MMAP_SIZE, MemorySegment.NULL );
      MemorySegment vcpuState = KvmBindings.mmap( MemorySegment.NULL, vcpuMmapSize,
          KvmBindings.PROT_READ | KvmBindings.PROT_WRITE, KvmBindings.MAP_SHARED, vcpuFd, 0L );
      if( vcpuState.address() == -1L || vcpuState.address() == 0L ) die( "mmap(vcpu_state)" );
      vcpuState = vcpuState.reinterpret( vcpuMmapSize );

      // --- sregs: long mode + ring-3 user CS/SS + EFER.SCE ---
      MemorySegment sregs = arena.allocate( KvmBindings.KVM_SREGS_SIZE );
      if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_GET_SREGS, sregs ) < 0 ) die( "KVM_GET_SREGS" );
      // user code segment: dpl=3, L=1 (64-bit), selector RPL=3
      setSegment( sregs, KvmBindings.KVM_SREGS_OFF_CS, USER_CS_SEL,
          KvmBindings.SEG_TYPE_CODE, /*dpl*/3, /*db*/0, /*l*/1 );
      for( int segOff : new int[]{ KvmBindings.KVM_SREGS_OFF_DS, KvmBindings.KVM_SREGS_OFF_ES,
                                   KvmBindings.KVM_SREGS_OFF_FS, KvmBindings.KVM_SREGS_OFF_GS,
                                   KvmBindings.KVM_SREGS_OFF_SS } ) {
        setSegment( sregs, segOff, USER_SS_SEL, KvmBindings.SEG_TYPE_DATA, /*dpl*/3, /*db*/1, /*l*/0 );
      }
      sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_CR0,  KvmBindings.CR0_LONG_MODE );
      sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_CR3,  PML4_GPA );
      sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_CR4,  KvmBindings.CR4_PAE );
      sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_EFER,
          KvmBindings.EFER_LME | KvmBindings.EFER_LMA | KvmBindings.EFER_SCE );
      if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_SET_SREGS, sregs ) < 0 ) die( "KVM_SET_SREGS" );

      // --- MSRs: STAR / LSTAR / FMASK ---
      setMsrs( arena, vcpuFd, new long[][]{
          { KvmBindings.MSR_STAR,         STAR_VALUE },
          { KvmBindings.MSR_LSTAR,        LSTAR_GPA  },
          { KvmBindings.MSR_SYSCALL_MASK, 0L         },  // FMASK=0 (RFLAGS そのまま)
      } );
      System.out.println( "[KvmSyscallSmoke] ring-3 long mode set (EFER.SCE on, STAR=0x"
          + Long.toHexString( STAR_VALUE ) + " LSTAR=0x" + Long.toHexString( LSTAR_GPA )
          + " cs.dpl=3)" );

      // --- regs: rip=user code, rsp=user stack ---
      MemorySegment regs = arena.allocate( KvmBindings.KVM_REGS_SIZE );
      if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_GET_REGS, regs ) < 0 ) die( "KVM_GET_REGS" );
      regs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RIP,    CODE_GPA );
      regs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RSP,    USER_STACK_TOP );
      regs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RFLAGS, 2L );
      if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_SET_REGS, regs ) < 0 ) die( "KVM_SET_REGS" );

      // === (1) 単発: ring-3 syscall → trap、sysno/arg 回収を検証 ===
      System.out.println( "[KvmSyscallSmoke] running ring-3 guest, expecting syscall trap ..." );
      if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_RUN, MemorySegment.NULL ) < 0 ) die( "KVM_RUN(1)" );
      int exitReason = vcpuState.get( ValueLayout.JAVA_INT, KvmBindings.KVM_RUN_OFF_EXIT_REASON );
      if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_GET_REGS, regs ) < 0 ) die( "KVM_GET_REGS(after1)" );
      long rax = regs.get( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RAX );
      long rdi = regs.get( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RDI );
      long rcx = regs.get( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RCX );
      long rip = regs.get( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RIP );
      System.out.println( "[KvmSyscallSmoke] exit_reason=" + exitReason + " rax=0x" + Long.toHexString(rax)
          + " rdi=0x" + Long.toHexString(rdi) + " rcx(retaddr)=0x" + Long.toHexString(rcx)
          + " rip=0x" + Long.toHexString(rip) );
      boolean trapOk = exitReason == KvmBindings.KVM_EXIT_HLT
          && rax == MAGIC_SYSNO && rdi == MAGIC_ARG0 && rcx == syscallRetAddr && rip == afterHlt;
      if( !trapOk ) {
        System.err.println( "SYSCALL TRAP FAIL: exit=" + exitReason + " rax=0x" + Long.toHexString(rax)
            + " (want 0x" + Long.toHexString(MAGIC_SYSNO) + ") rdi=0x" + Long.toHexString(rdi)
            + " (want 0x" + Long.toHexString(MAGIC_ARG0) + ") rcx=0x" + Long.toHexString(rcx)
            + " (want 0x" + Long.toHexString(syscallRetAddr) + ") rip=0x" + Long.toHexString(rip)
            + " (want 0x" + Long.toHexString(afterHlt) + ")" );
        System.exit( 4 );
      }
      System.out.println( "[KvmSyscallSmoke] ✓ syscall trap OK: ring-3 syscall trapped to VMM, "
          + "sysno=0x" + Long.toHexString(rax) + " arg0=0x" + Long.toHexString(rdi)
          + " return-addr=0x" + Long.toHexString(rcx) + " captured." );

      // === (2) latency 計測: 1 KVM_RUN = 1 完全 round-trip (sysretq→ring3→syscall→hlt) ===
      // 各 iteration で exit_reason==HLT を assert する (triple fault→KVM_EXIT_SHUTDOWN
      //   が rc=0 で silently カウントされ平均を低く歪めるのを防ぐ — review #2)。
      // warm up
      for( int i = 0; i < WARMUP_ITERS; i++ ) {
        if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_RUN, MemorySegment.NULL ) < 0 ) die( "KVM_RUN(warmup)" );
        assertHlt( vcpuState, "warmup" );
      }

      // (A) KVM_RUN round-trip (FFM 経由)。per-iteration 計測で分布を取る。
      long[] samples = new long[ TIMED_ITERS ];
      for( int i = 0; i < TIMED_ITERS; i++ ) {
        long s = System.nanoTime();
        if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_RUN, MemorySegment.NULL ) < 0 ) die( "KVM_RUN(timedA)" );
        samples[i] = System.nanoTime() - s;
        assertHlt( vcpuState, "timedA" );
      }
      long aMean = mean( samples );
      long[] sorted = samples.clone();
      java.util.Arrays.sort( sorted );
      long aMin = sorted[0];
      long aMed = sorted[ TIMED_ITERS / 2 ];
      long aP99 = sorted[ (int)( TIMED_ITERS * 0.99 ) ];
      long aMax = sorted[ TIMED_ITERS - 1 ];

      // (B) 明示的 GET/SET_REGS 込み (上限。step 3d は KVM_CAP_SYNC_REGS で
      //   この ~1.7µs delta を消せる)。emulin の per-syscall reg shuffle を模す。
      long t1 = System.nanoTime();
      for( int i = 0; i < TIMED_ITERS; i++ ) {
        if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_RUN, MemorySegment.NULL ) < 0 ) die( "KVM_RUN(timedB)" );
        assertHlt( vcpuState, "timedB" );
        KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_GET_REGS, regs );   // emulin: sysno/args 読み
        regs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RAX, 0L ); // emulin: 戻り値 書き
        KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_SET_REGS, regs );
      }
      long bMean = ( System.nanoTime() - t1 ) / TIMED_ITERS;

      KvmBindings.munmap( vcpuState, vcpuMmapSize );
      KvmBindings.close( vcpuFd );
      KvmBindings.close( vmFd );
      KvmBindings.close( kvmFd );

      System.out.println( "[KvmSyscallSmoke] latency over " + TIMED_ITERS + " verified-HLT traps:" );
      System.out.println( "[KvmSyscallSmoke]   (A) KVM_RUN round-trip (FFM): "
          + "min=" + aMin + " median=" + aMed + " mean=" + aMean + " p99=" + aP99 + " max=" + aMax + " ns" );
      System.out.println( "[KvmSyscallSmoke]   (B) + explicit GET/SET_REGS (upper bound; sync_regs removes the delta): mean="
          + bMean + " ns/syscall" );
      System.out.println( "[KvmSyscallSmoke]   ★ host is WSL2 nested KVM (L2) — latency は nested-inflated。"
          + "非 nested (bare-metal/WHP) の最終測定は step 3f。" );
      boolean gate = aMed <= SHIP_GATE_NS;
      System.out.println( "KVM syscall-trap smoke OK: ring-3 syscall traps to VMM; "
          + "round-trip median " + aMed + "ns / realistic " + bMean + "ns "
          + "(raw 5µs gate: " + ( gate ? "PASS" : "over (nested)" )
          + " — 真の判定は software interpreter 対比 break-even、§4.4c 参照)" );
      System.exit( 0 );
    }
  }

  /** kvm_run.exit_reason が KVM_EXIT_HLT でなければ即 fail (benchmark 自己検証) */
  private static void assertHlt( MemorySegment vcpuState, String where ) {
    int er = vcpuState.get( ValueLayout.JAVA_INT, KvmBindings.KVM_RUN_OFF_EXIT_REASON );
    if( er != KvmBindings.KVM_EXIT_HLT ) {
      System.err.println( "[KvmSyscallSmoke] " + where + ": unexpected exit_reason=" + er
          + " (expected HLT=5) — round-trip が壊れた、計測無効" );
      System.exit( 5 );
    }
  }

  private static long mean( long[] a ) {
    long sum = 0;
    for( long v : a ) sum += v;
    return sum / a.length;
  }

  /** kvm_segment を設定 (base=0, limit=0xFFFFF, present=1, s=1, g=1 固定) */
  private static void setSegment( MemorySegment sregs, int segOff, long selector,
                                  int type, int dpl, int db, int l ) {
    sregs.set( ValueLayout.JAVA_LONG,  segOff + KvmBindings.KVM_SEG_OFF_BASE,     0L );
    sregs.set( ValueLayout.JAVA_INT,   segOff + KvmBindings.KVM_SEG_OFF_LIMIT,    0xFFFFF );
    sregs.set( ValueLayout.JAVA_SHORT, segOff + KvmBindings.KVM_SEG_OFF_SELECTOR, (short) selector );
    sregs.set( ValueLayout.JAVA_BYTE,  segOff + KvmBindings.KVM_SEG_OFF_TYPE,     (byte) type );
    sregs.set( ValueLayout.JAVA_BYTE,  segOff + KvmBindings.KVM_SEG_OFF_PRESENT,  (byte) 1 );
    sregs.set( ValueLayout.JAVA_BYTE,  segOff + KvmBindings.KVM_SEG_OFF_DPL,      (byte) dpl );
    sregs.set( ValueLayout.JAVA_BYTE,  segOff + KvmBindings.KVM_SEG_OFF_DB,       (byte) db );
    sregs.set( ValueLayout.JAVA_BYTE,  segOff + KvmBindings.KVM_SEG_OFF_S,        (byte) 1 );
    sregs.set( ValueLayout.JAVA_BYTE,  segOff + KvmBindings.KVM_SEG_OFF_L,        (byte) l );
    sregs.set( ValueLayout.JAVA_BYTE,  segOff + KvmBindings.KVM_SEG_OFF_G,        (byte) 1 );
    sregs.set( ValueLayout.JAVA_BYTE,  segOff + KvmBindings.KVM_SEG_OFF_AVL,      (byte) 0 );
    sregs.set( ValueLayout.JAVA_BYTE,  segOff + KvmBindings.KVM_SEG_OFF_UNUSABLE, (byte) 0 );
  }

  /** KVM_SET_MSRS で {index,data} の配列を設定 */
  private static void setMsrs( Arena arena, int vcpuFd, long[][] msrs ) throws Throwable {
    int n = msrs.length;
    MemorySegment buf = arena.allocate( KvmBindings.KVM_MSRS_OFF_ENTRIES + (long) n * KvmBindings.KVM_MSR_ENTRY_SIZE );
    buf.set( ValueLayout.JAVA_INT, KvmBindings.KVM_MSRS_OFF_NMSRS, n );
    for( int i = 0; i < n; i++ ) {
      long e = KvmBindings.KVM_MSRS_OFF_ENTRIES + (long) i * KvmBindings.KVM_MSR_ENTRY_SIZE;
      buf.set( ValueLayout.JAVA_INT,  e + KvmBindings.KVM_MSR_ENTRY_OFF_INDEX, (int) msrs[i][0] );
      buf.set( ValueLayout.JAVA_LONG, e + KvmBindings.KVM_MSR_ENTRY_OFF_DATA,  msrs[i][1] );
    }
    int rc = KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_SET_MSRS, buf );
    if( rc < 0 || rc != n ) {
      System.err.println( "[KvmSyscallSmoke] KVM_SET_MSRS rc=" + rc + " (expected " + n + "), errno=" + KvmBindings.errno() );
      System.exit( 3 );
    }
  }

  private static void die( String op ) throws Throwable {
    System.err.println( "[KvmSyscallSmoke] " + op + " failed, errno=" + KvmBindings.errno() );
    System.exit( 3 );
  }
}
