// ----------------------------------------
//  Linux KVM 64-bit long mode smoke — Phase 0 step 3b
//  (issue #221)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// 目的: KvmSmoke (step 3a、16-bit real mode で NOP+HLT) の次段。guest を
//   **64-bit long mode** に入れて 64-bit 命令を実 vCPU で実行し、64-bit
//   register 幅が正しく扱えることを実証する。これは long mode 起動 (制御
//   レジスタ CR0/CR3/CR4 + EFER + identity page table + GDT-less segment 設定)
//   という最も間違えやすい部分を de-risk する step。step 3c (syscall trap) /
//   step 3d (NativeCpuBackend KVM 経路) の土台になる。
//
// guest プログラム (guest 物理/仮想 0 に identity map):
//   48 B8 88 77 66 55 44 33 22 11   movabs rax, 0x1122334455667788
//   48 89 C3                        mov    rbx, rax
//   F4                              hlt
//   → KVM_EXIT_HLT 後に rax==rbx==0x1122334455667788、rip==0xE を検証。
//   これで 64-bit immediate / 64-bit register move が long mode で動くことを
//   実証する (16-bit real mode では movabs imm64 は動かない)。
//
// メモリレイアウト (off-heap native MemorySegment、guest 物理 0 に map):
//   0x0000  code (14 byte)
//   0x1000  PML4   : [0] = 0x2000 | P|RW
//   0x2000  PDPT   : [0] = 0x3000 | P|RW
//   0x3000  PD     : [0] = 0x0000 | P|RW|PS   (2MB page、identity [0,2MB))
//   guest RAM 全体 2MB を off-heap で確保 (Java heap byte[] は GC で移動して
//   安定 native アドレスを持たないため KVM_SET_USER_MEMORY_REGION に使えない —
//   step 3a code review の申し送り)。
//
// 制御レジスタ (long mode entry):
//   CR0  = PE|MP|ET|NE|WP|AM|PG
//   CR3  = 0x1000 (PML4 base)
//   CR4  = PAE
//   EFER = LME|LMA
//   CS   は L=1 (64-bit code segment) で GDT 無し直設定 (KVM が descriptor
//        cache を sregs から load するので in-memory GDT walk は起きない)。
//
// 起動: java --enable-native-access=ALL-UNNAMED -cp target/classes emulin.KvmSmoke64
//
// ★この smoke は guest を RING 0 で走らせる (segment dpl=0、page table に PTE_US
//   無し)。step 3c (syscall trap) で実 emulin user code を RING 3 で動かす際は
//   以下が必要 (long-mode review PR #241 で確定、docs §4.5 参照):
//   - PTE_US を PML4E/PDPTE/PDE/PTE の全 level に (leaf だけでは ring-3 #PF)
//   - 4KB PTE で user 領域と page-table 自身を分離 (2MB page では US 一律で不可)
//   - cs/ss を dpl=3 + RPL=3 selector に (STAR の USER/KERNEL selector と一致)
//   - EFER.SCE + KVM_SET_MSRS (新 binding) で STAR/LSTAR/FMASK を設定
//   - user RSP + kernel stack + swapgs/KERNEL_GS_BASE (syscall は RSP 自動切替なし)
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class KvmSmoke64 {

  private static final long GUEST_RAM_SIZE = 0x200000L;  // 2MB
  private static final long PML4_GPA = 0x1000L;
  private static final long PDPT_GPA = 0x2000L;
  private static final long PD_GPA   = 0x3000L;
  private static final long EXPECTED_IMM = 0x1122334455667788L;

  public static void main( String[] args ) throws Throwable {
    if( !KvmBindings.probe() ) {
      System.err.println( "[KvmSmoke64] " + KvmBindings.describeAvailability() );
      System.exit( 1 );
    }
    System.out.println( "[KvmSmoke64] " + KvmBindings.describeAvailability() );

    try( Arena arena = Arena.ofShared() ) {
      MemorySegment path = arena.allocateFrom( "/dev/kvm" );
      int kvmFd = KvmBindings.open( path, KvmBindings.O_RDWR );
      if( kvmFd < 0 ) die( "open(/dev/kvm)" );

      int apiVer = KvmBindings.ioctl( kvmFd, KvmBindings.KVM_GET_API_VERSION, MemorySegment.NULL );
      if( apiVer != KvmBindings.KVM_API_VERSION ) {
        System.err.println( "[KvmSmoke64] KVM_API_VERSION mismatch (" + apiVer + ")" );
        System.exit( 2 );
      }

      int vmFd = KvmBindings.ioctl( kvmFd, KvmBindings.KVM_CREATE_VM, MemorySegment.NULL );
      if( vmFd < 0 ) die( "KVM_CREATE_VM" );

      // off-heap guest RAM (2MB、page-aligned、zero 初期化)
      MemorySegment guestRam = arena.allocate( GUEST_RAM_SIZE, 4096 );
      System.out.println( "[KvmSmoke64] guest RAM host addr = 0x" + Long.toHexString( guestRam.address() )
          + " size=0x" + Long.toHexString( GUEST_RAM_SIZE ) );

      // --- guest code @ 0x0 ---
      byte[] code = new byte[] {
          (byte)0x48, (byte)0xB8,                                            // movabs rax,
          (byte)0x88, (byte)0x77, (byte)0x66, (byte)0x55,
          (byte)0x44, (byte)0x33, (byte)0x22, (byte)0x11,                    //   0x1122334455667788
          (byte)0x48, (byte)0x89, (byte)0xC3,                                // mov rbx, rax
          (byte)0xF4,                                                        // hlt
      };
      for( int i = 0; i < code.length; i++ ) {
        guestRam.set( ValueLayout.JAVA_BYTE, i, code[i] );
      }
      long ripAfter = code.length;  // 0xE

      // --- identity page table (2MB page) ---
      guestRam.set( ValueLayout.JAVA_LONG, PML4_GPA, PDPT_GPA | KvmBindings.PTE_P | KvmBindings.PTE_RW );
      guestRam.set( ValueLayout.JAVA_LONG, PDPT_GPA, PD_GPA   | KvmBindings.PTE_P | KvmBindings.PTE_RW );
      guestRam.set( ValueLayout.JAVA_LONG, PD_GPA,   0x0L | KvmBindings.PTE_P | KvmBindings.PTE_RW | KvmBindings.PTE_PS );

      // --- KVM_SET_USER_MEMORY_REGION (guest phys 0, 2MB) ---
      MemorySegment memRegion = arena.allocate( KvmBindings.KVM_MEM_REGION_SIZE );
      memRegion.set( ValueLayout.JAVA_INT,  KvmBindings.KVM_MEM_OFF_SLOT,       0 );
      memRegion.set( ValueLayout.JAVA_INT,  KvmBindings.KVM_MEM_OFF_FLAGS,      0 );
      memRegion.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_MEM_OFF_GUEST_ADDR, 0L );
      memRegion.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_MEM_OFF_SIZE,       GUEST_RAM_SIZE );
      memRegion.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_MEM_OFF_USER_ADDR,  guestRam.address() );
      if( KvmBindings.ioctl( vmFd, KvmBindings.KVM_SET_USER_MEMORY_REGION, memRegion ) < 0 )
        die( "KVM_SET_USER_MEMORY_REGION" );

      int vcpuFd = KvmBindings.ioctl( vmFd, KvmBindings.KVM_CREATE_VCPU, MemorySegment.NULL );
      if( vcpuFd < 0 ) die( "KVM_CREATE_VCPU" );

      int vcpuMmapSize = KvmBindings.ioctl( kvmFd, KvmBindings.KVM_GET_VCPU_MMAP_SIZE, MemorySegment.NULL );
      if( vcpuMmapSize <= 0 ) die( "KVM_GET_VCPU_MMAP_SIZE" );
      MemorySegment vcpuState = KvmBindings.mmap( MemorySegment.NULL, vcpuMmapSize,
          KvmBindings.PROT_READ | KvmBindings.PROT_WRITE, KvmBindings.MAP_SHARED, vcpuFd, 0L );
      if( vcpuState.address() == -1L || vcpuState.address() == 0L ) die( "mmap(vcpu_state)" );
      vcpuState = vcpuState.reinterpret( vcpuMmapSize );

      // --- KVM_GET_SREGS → long mode 設定 → KVM_SET_SREGS ---
      MemorySegment sregs = arena.allocate( KvmBindings.KVM_SREGS_SIZE );
      if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_GET_SREGS, sregs ) < 0 ) die( "KVM_GET_SREGS" );

      // 64-bit code segment (L=1, D=0)、selector=0x8
      setSegment( sregs, KvmBindings.KVM_SREGS_OFF_CS, 0L, 0xFFFFFFFF, 0x8,
          KvmBindings.SEG_TYPE_CODE, /*dpl*/0, /*db*/0, /*s*/1, /*l*/1, /*g*/1 );
      // data segments (D=1, L=0)、selector=0x10
      for( int segOff : new int[]{ KvmBindings.KVM_SREGS_OFF_DS, KvmBindings.KVM_SREGS_OFF_ES,
                                   KvmBindings.KVM_SREGS_OFF_FS, KvmBindings.KVM_SREGS_OFF_GS,
                                   KvmBindings.KVM_SREGS_OFF_SS } ) {
        setSegment( sregs, segOff, 0L, 0xFFFFFFFF, 0x10,
            KvmBindings.SEG_TYPE_DATA, 0, /*db*/1, 1, /*l*/0, 1 );
      }
      sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_CR0,  KvmBindings.CR0_LONG_MODE );
      sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_CR3,  PML4_GPA );
      sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_CR4,  KvmBindings.CR4_PAE );
      sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_EFER, KvmBindings.EFER_LME | KvmBindings.EFER_LMA );
      if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_SET_SREGS, sregs ) < 0 ) die( "KVM_SET_SREGS" );
      System.out.println( "[KvmSmoke64] long mode sregs set (CR0=0x" + Long.toHexString( KvmBindings.CR0_LONG_MODE )
          + " CR3=0x" + Long.toHexString( PML4_GPA ) + " CR4=PAE EFER=LME|LMA cs.l=1)" );

      // --- KVM_GET_REGS → rip=0, rflags=2 → KVM_SET_REGS ---
      MemorySegment regs = arena.allocate( KvmBindings.KVM_REGS_SIZE );
      if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_GET_REGS, regs ) < 0 ) die( "KVM_GET_REGS" );
      regs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RIP,    0L );
      regs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RFLAGS, 2L );
      if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_SET_REGS, regs ) < 0 ) die( "KVM_SET_REGS" );

      // --- KVM_RUN ---
      System.out.println( "[KvmSmoke64] running guest (movabs rax,imm64; mov rbx,rax; hlt) in long mode ..." );
      long start = System.nanoTime();
      if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_RUN, MemorySegment.NULL ) < 0 ) die( "KVM_RUN" );
      long elapsed = System.nanoTime() - start;

      int exitReason = vcpuState.get( ValueLayout.JAVA_INT, KvmBindings.KVM_RUN_OFF_EXIT_REASON );
      System.out.println( "[KvmSmoke64] exit_reason = " + exitReason + " (" + exitReasonName( exitReason )
          + ") elapsed=" + elapsed + "ns" );

      if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_GET_REGS, regs ) < 0 ) die( "KVM_GET_REGS (after)" );
      long rax = regs.get( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RAX );
      long rbx = regs.get( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RBX );
      long rip = regs.get( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RIP );
      System.out.println( "[KvmSmoke64] rax=0x" + Long.toHexString( rax ) + " rbx=0x" + Long.toHexString( rbx )
          + " rip=0x" + Long.toHexString( rip ) );

      KvmBindings.munmap( vcpuState, vcpuMmapSize );
      KvmBindings.close( vcpuFd );
      KvmBindings.close( vmFd );
      KvmBindings.close( kvmFd );

      boolean ok = exitReason == KvmBindings.KVM_EXIT_HLT
          && rax == EXPECTED_IMM && rbx == EXPECTED_IMM && rip == ripAfter;
      if( ok ) {
        System.out.println( "KVM64 smoke OK: long mode + 64-bit exec verified "
            + "(rax=rbx=0x" + Long.toHexString( EXPECTED_IMM ) + ", rip=0x" + Long.toHexString( rip )
            + ", HLT, KVM_RUN " + elapsed + "ns)" );
        System.exit( 0 );
      } else {
        System.err.println( "KVM64 smoke FAIL: exit=" + exitReason + " rax=0x" + Long.toHexString( rax )
            + " rbx=0x" + Long.toHexString( rbx ) + " rip=0x" + Long.toHexString( rip )
            + " (expected exit=5 rax=rbx=0x" + Long.toHexString( EXPECTED_IMM )
            + " rip=0x" + Long.toHexString( ripAfter ) + ")" );
        System.exit( 4 );
      }
    }
  }

  /** sregs buffer の segOff にある kvm_segment を設定する */
  private static void setSegment( MemorySegment sregs, int segOff, long base, int limit,
                                  int selector, int type, int dpl, int db, int s, int l, int g ) {
    sregs.set( ValueLayout.JAVA_LONG,  segOff + KvmBindings.KVM_SEG_OFF_BASE,     base );
    sregs.set( ValueLayout.JAVA_INT,   segOff + KvmBindings.KVM_SEG_OFF_LIMIT,    limit );
    sregs.set( ValueLayout.JAVA_SHORT, segOff + KvmBindings.KVM_SEG_OFF_SELECTOR, (short) selector );
    sregs.set( ValueLayout.JAVA_BYTE,  segOff + KvmBindings.KVM_SEG_OFF_TYPE,     (byte) type );
    sregs.set( ValueLayout.JAVA_BYTE,  segOff + KvmBindings.KVM_SEG_OFF_PRESENT,  (byte) 1 );
    sregs.set( ValueLayout.JAVA_BYTE,  segOff + KvmBindings.KVM_SEG_OFF_DPL,      (byte) dpl );
    sregs.set( ValueLayout.JAVA_BYTE,  segOff + KvmBindings.KVM_SEG_OFF_DB,       (byte) db );
    sregs.set( ValueLayout.JAVA_BYTE,  segOff + KvmBindings.KVM_SEG_OFF_S,        (byte) s );
    sregs.set( ValueLayout.JAVA_BYTE,  segOff + KvmBindings.KVM_SEG_OFF_L,        (byte) l );
    sregs.set( ValueLayout.JAVA_BYTE,  segOff + KvmBindings.KVM_SEG_OFF_G,        (byte) g );
    sregs.set( ValueLayout.JAVA_BYTE,  segOff + KvmBindings.KVM_SEG_OFF_AVL,      (byte) 0 );
    sregs.set( ValueLayout.JAVA_BYTE,  segOff + KvmBindings.KVM_SEG_OFF_UNUSABLE, (byte) 0 );
  }

  private static void die( String op ) throws Throwable {
    int errno = KvmBindings.errno();
    System.err.println( "[KvmSmoke64] " + op + " failed, errno=" + errno );
    System.exit( 3 );
  }

  private static String exitReasonName( int r ) {
    return switch( r ) {
      case KvmBindings.KVM_EXIT_HLT            -> "KVM_EXIT_HLT";
      case KvmBindings.KVM_EXIT_FAIL_ENTRY     -> "KVM_EXIT_FAIL_ENTRY";
      case KvmBindings.KVM_EXIT_INTERNAL_ERROR -> "KVM_EXIT_INTERNAL_ERROR";
      case KvmBindings.KVM_EXIT_SHUTDOWN       -> "KVM_EXIT_SHUTDOWN";
      case KvmBindings.KVM_EXIT_MMIO           -> "KVM_EXIT_MMIO";
      case KvmBindings.KVM_EXIT_IO             -> "KVM_EXIT_IO";
      default                                  -> "exit=" + r;
    };
  }
}
