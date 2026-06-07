// ----------------------------------------
//  NativeMemoryBackend ⇄ KVM 双方向 bridge smoke (issue #221 step 3d-1)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// 目的: `NativeMemoryBackend` が KVM guest 物理 RAM の忠実な双方向 view である
//   ことを実証する。すなわち:
//   - emulin が backend の store/bulk で書いた値・命令・page table が KVM guest
//     から見える (guest が実行・参照できる)。
//   - KVM guest が書いた値を emulin が backend の load で読み戻せる。
//
// 手順:
//   1. off-heap 2MB segment を確保 → NativeMemoryBackend で包む。
//   2. backend.store64 で identity page table (PML4/PDPT/PD) を書く。
//      → CPU の page-walk がこれを読む = backend 書込が guest に見える証明。
//   3. backend.bulkStoreToMem で guest code を書く。code:
//        mov rax, [0x8000] ; inc rax ; mov [0x8000], rax ; hlt
//   4. backend.store64(0x8000, 0x41) で seed 値を書く。
//   5. KVM が同 segment を map して long mode で実行。
//      guest は 0x8000 から 0x41 を読み (= backend 書込が見える)、+1=0x42 を
//      0x8000 に書き戻し hlt。
//   6. emulin が backend.load64(0x8000) で読み → 0x42 を期待
//      (= guest 書込が backend に見える)。+ KVM_GET_REGS で rax==0x42。
//
// 起動: java --enable-native-access=ALL-UNNAMED -cp target/classes emulin.NativeMemBackendSmoke
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class NativeMemBackendSmoke {

  private static final long GUEST_RAM_SIZE = 0x200000L;  // 2MB
  private static final long PML4_GPA = 0x1000L, PDPT_GPA = 0x2000L, PD_GPA = 0x3000L;
  private static final long CODE_GPA = 0x6000L, DATA_GPA = 0x8000L;
  private static final long SEED = 0x41L, EXPECTED = 0x42L;

  public static void main( String[] args ) throws Throwable {
    if( !KvmBindings.probe() ) { System.err.println( "[NativeMemBackendSmoke] " + KvmBindings.describeAvailability() ); System.exit( 1 ); }
    System.out.println( "[NativeMemBackendSmoke] " + KvmBindings.describeAvailability() );

    try( Arena arena = Arena.ofShared() ) {
      // 1. off-heap guest RAM → NativeMemoryBackend で包む
      MemorySegment ram = arena.allocate( GUEST_RAM_SIZE, 4096 );
      NativeMemoryBackend backend = new NativeMemoryBackend( ram );
      System.out.println( "[NativeMemBackendSmoke] NativeMemoryBackend over off-heap RAM @0x"
          + Long.toHexString( backend.address() ) + " size=0x" + Long.toHexString( backend.sizeBytes() ) );

      // 2. identity 2MB page table を backend.store64 で書く (CPU page-walk が読む)
      backend.store64( PML4_GPA, PDPT_GPA | KvmBindings.PTE_P | KvmBindings.PTE_RW );
      backend.store64( PDPT_GPA, PD_GPA   | KvmBindings.PTE_P | KvmBindings.PTE_RW );
      backend.store64( PD_GPA,   0x0L     | KvmBindings.PTE_P | KvmBindings.PTE_RW | KvmBindings.PTE_PS );

      // 3. guest code を backend.bulkStoreToMem で書く
      //    48 8B 04 25 00 80 00 00  mov rax, [0x8000]
      //    48 FF C0                 inc rax
      //    48 89 04 25 00 80 00 00  mov [0x8000], rax
      //    F4                       hlt
      byte[] code = {
          (byte)0x48,(byte)0x8B,(byte)0x04,(byte)0x25,(byte)0x00,(byte)0x80,0x00,0x00,
          (byte)0x48,(byte)0xFF,(byte)0xC0,
          (byte)0x48,(byte)0x89,(byte)0x04,(byte)0x25,(byte)0x00,(byte)0x80,0x00,0x00,
          (byte)0xF4,
      };
      backend.bulkStoreToMem( CODE_GPA, code, 0, code.length );
      long ripAfter = CODE_GPA + code.length;

      // 4. seed 値を backend.store64 で書く
      backend.store64( DATA_GPA, SEED );
      System.out.println( "[NativeMemBackendSmoke] emulin wrote via backend: page tables, code, seed[0x8000]=0x"
          + Long.toHexString( backend.load64( DATA_GPA ) ) );

      // 5. KVM: 同 segment を map → long mode で実行
      int kvmFd = KvmBindings.open( arena.allocateFrom( "/dev/kvm" ), KvmBindings.O_RDWR );
      if( kvmFd < 0 ) die( "open(/dev/kvm)" );
      int vmFd = KvmBindings.ioctl( kvmFd, KvmBindings.KVM_CREATE_VM, MemorySegment.NULL );
      if( vmFd < 0 ) die( "KVM_CREATE_VM" );

      MemorySegment mr = arena.allocate( KvmBindings.KVM_MEM_REGION_SIZE );
      mr.set( ValueLayout.JAVA_INT,  KvmBindings.KVM_MEM_OFF_SLOT,       0 );
      mr.set( ValueLayout.JAVA_INT,  KvmBindings.KVM_MEM_OFF_FLAGS,      0 );
      mr.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_MEM_OFF_GUEST_ADDR, 0L );
      mr.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_MEM_OFF_SIZE,       backend.sizeBytes() );
      mr.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_MEM_OFF_USER_ADDR,  backend.address() );  // ★ backend の segment を map
      if( KvmBindings.ioctl( vmFd, KvmBindings.KVM_SET_USER_MEMORY_REGION, mr ) < 0 ) die( "KVM_SET_USER_MEMORY_REGION" );

      int vcpuFd = KvmBindings.ioctl( vmFd, KvmBindings.KVM_CREATE_VCPU, MemorySegment.NULL );
      if( vcpuFd < 0 ) die( "KVM_CREATE_VCPU" );
      int mmapSize = KvmBindings.ioctl( kvmFd, KvmBindings.KVM_GET_VCPU_MMAP_SIZE, MemorySegment.NULL );
      MemorySegment vcpuState = KvmBindings.mmap( MemorySegment.NULL, mmapSize,
          KvmBindings.PROT_READ | KvmBindings.PROT_WRITE, KvmBindings.MAP_SHARED, vcpuFd, 0L );
      if( vcpuState.address() == -1L || vcpuState.address() == 0L ) die( "mmap(vcpu_state)" );
      vcpuState = vcpuState.reinterpret( mmapSize );

      MemorySegment sregs = arena.allocate( KvmBindings.KVM_SREGS_SIZE );
      if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_GET_SREGS, sregs ) < 0 ) die( "KVM_GET_SREGS" );
      setSeg( sregs, KvmBindings.KVM_SREGS_OFF_CS, 0x8, KvmBindings.SEG_TYPE_CODE, 0, 1 );
      for( int o : new int[]{ KvmBindings.KVM_SREGS_OFF_DS, KvmBindings.KVM_SREGS_OFF_ES,
                              KvmBindings.KVM_SREGS_OFF_FS, KvmBindings.KVM_SREGS_OFF_GS,
                              KvmBindings.KVM_SREGS_OFF_SS } )
        setSeg( sregs, o, 0x10, KvmBindings.SEG_TYPE_DATA, 1, 0 );
      sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_CR0,  KvmBindings.CR0_LONG_MODE );
      sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_CR3,  PML4_GPA );
      sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_CR4,  KvmBindings.CR4_PAE );
      sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_EFER, KvmBindings.EFER_LME | KvmBindings.EFER_LMA );
      if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_SET_SREGS, sregs ) < 0 ) die( "KVM_SET_SREGS" );

      MemorySegment regs = arena.allocate( KvmBindings.KVM_REGS_SIZE );
      if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_GET_REGS, regs ) < 0 ) die( "KVM_GET_REGS" );
      regs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RIP,    CODE_GPA );
      regs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RFLAGS, 2L );
      if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_SET_REGS, regs ) < 0 ) die( "KVM_SET_REGS" );

      System.out.println( "[NativeMemBackendSmoke] running guest: rax = [0x8000]; rax++; [0x8000] = rax; hlt ..." );
      if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_RUN, MemorySegment.NULL ) < 0 ) die( "KVM_RUN" );
      int exitReason = vcpuState.get( ValueLayout.JAVA_INT, KvmBindings.KVM_RUN_OFF_EXIT_REASON );
      if( KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_GET_REGS, regs ) < 0 ) die( "KVM_GET_REGS(after)" );
      long rax = regs.get( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RAX );
      long rip = regs.get( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RIP );

      // 6. emulin が backend.load64 で読み戻す = guest 書込が backend に見える
      long readback = backend.load64( DATA_GPA );

      KvmBindings.munmap( vcpuState, mmapSize );
      KvmBindings.close( vcpuFd );
      KvmBindings.close( vmFd );
      KvmBindings.close( kvmFd );

      System.out.println( "[NativeMemBackendSmoke] exit_reason=" + exitReason + " rax=0x" + Long.toHexString( rax )
          + " rip=0x" + Long.toHexString( rip ) + " backend.load64(0x8000)=0x" + Long.toHexString( readback ) );

      boolean ok = exitReason == KvmBindings.KVM_EXIT_HLT && rax == EXPECTED && readback == EXPECTED && rip == ripAfter;
      if( ok ) {
        System.out.println( "NativeMemoryBackend smoke OK: bidirectional guest-RAM bridge verified "
            + "(emulin wrote seed 0x" + Long.toHexString( SEED ) + " via backend → guest read+inc+wrote → "
            + "emulin read 0x" + Long.toHexString( readback ) + " via backend)." );
        System.exit( 0 );
      } else {
        System.err.println( "NativeMemoryBackend smoke FAIL: exit=" + exitReason + " rax=0x" + Long.toHexString( rax )
            + " readback=0x" + Long.toHexString( readback ) + " rip=0x" + Long.toHexString( rip )
            + " (want exit=5 rax=readback=0x" + Long.toHexString( EXPECTED ) + " rip=0x" + Long.toHexString( ripAfter ) + ")" );
        System.exit( 4 );
      }
    }
  }

  private static void setSeg( MemorySegment sregs, int o, int sel, int type, int db, int l ) {
    sregs.set( ValueLayout.JAVA_LONG,  o + KvmBindings.KVM_SEG_OFF_BASE,     0L );
    sregs.set( ValueLayout.JAVA_INT,   o + KvmBindings.KVM_SEG_OFF_LIMIT,    0xFFFFF );
    sregs.set( ValueLayout.JAVA_SHORT, o + KvmBindings.KVM_SEG_OFF_SELECTOR, (short) sel );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_TYPE,     (byte) type );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_PRESENT,  (byte) 1 );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_DPL,      (byte) 0 );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_DB,       (byte) db );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_S,        (byte) 1 );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_L,        (byte) l );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_G,        (byte) 1 );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_AVL,      (byte) 0 );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_UNUSABLE, (byte) 0 );
  }

  private static void die( String op ) throws Throwable {
    System.err.println( "[NativeMemBackendSmoke] " + op + " failed, errno=" + KvmBindings.errno() );
    System.exit( 3 );
  }
}
