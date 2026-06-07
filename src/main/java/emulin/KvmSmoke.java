// ----------------------------------------
//  Linux KVM smoke — Phase 0 step 3a hello world
//  (issue #221)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// 目的: emulin の Java FFM bindings (KvmBindings) が WSL2 nested KVM 上で実際に
//   vCPU を起動できることを smoke 確認する standalone entry point。
//
//   1. /dev/kvm open
//   2. KVM_GET_API_VERSION == 12
//   3. KVM_CREATE_VM
//   4. 4KB host buffer に NOP×3 + HLT (0x90 0x90 0x90 0xF4) を書き、
//      KVM_SET_USER_MEMORY_REGION で guest 物理 0 に map
//   5. KVM_CREATE_VCPU (index 0)
//   6. KVM_GET_VCPU_MMAP_SIZE + mmap で vcpu_state を取る
//   7. KVM_GET_SREGS → cs.base=0, cs.selector=0 → KVM_SET_SREGS
//      (KVM_CREATE_VCPU 直後の reset state は CS:IP=F000:FFF0 BIOS 規約。
//       cs.base/selector を 0 にして guest 物理 0 の code を当てる)
//   8. KVM_GET_REGS → rip=0, rflags=2 (予約 bit のみ) → KVM_SET_REGS
//   9. KVM_RUN
//   10. vcpu_state.exit_reason == KVM_EXIT_HLT (5) を期待
//   11. rip が 4 に進んでいること (NOP×3 + HLT = 4 byte 実行) を確認
//
// 起動コマンド:
//   java --enable-native-access=ALL-UNNAMED -cp target/classes emulin.KvmSmoke
//
// 期待出力 (主要行):
//   [KvmSmoke] KVM detected (/dev/kvm OK)
//   [KvmSmoke] KVM_GET_API_VERSION = 12
//   ...
//   [KvmSmoke] exit_reason = 5 (KVM_EXIT_HLT)
//   [KvmSmoke] rip after = 0x4
//   KVM smoke OK: exit_reason=5 (KVM_EXIT_HLT)
//
// 失敗時は失敗 step + errno を出して exit code != 0。
//
// 注意 (step 3b で対応する設計上の申し送り、code review #239-followup):
//   - 失敗パス (die / System.exit) は vcpuState の munmap と fd の close を
//     省略する。本 smoke は process がすぐ exit して OS が回収するので無害だが、
//     長命の NativeCpuBackend (step 3b) では try/finally (or AutoCloseable) で
//     mmap/fd 所有権を確実に解放する必要がある。
//   - guest 物理 RAM は **off-heap native MemorySegment** で確保する必要がある。
//     本 smoke は Arena.allocate した off-heap buffer (guestBuf) を使うので OK だが、
//     emulin 本体の Memory は Java heap byte[]。Java heap array は GC で移動し
//     安定した native アドレスを持たないため、KVM_SET_USER_MEMORY_REGION の
//     userspace_addr には使えない。step 3b では guest RAM を単一の off-heap
//     MemorySegment として確保し NativeMemoryBackend と共有する。
//   - KVM_RUN は単発呼び出し。実 workload では exit_reason の while ループ +
//     EINTR (signal) 時の再 KVM_RUN が要る (step 3c の syscall trap dispatch)。
//   - KvmBindings.ioctl は arg が MemorySegment 固定。vcpu index > 0 等の
//     scalar 引数 (step 3b の multi-vCPU) には ioctl(int,long,long) overload を
//     足す (index 0 は NULL == 0 で偶然動く)。
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class KvmSmoke {

  public static void main( String[] args ) throws Throwable {
    if( !KvmBindings.probe() ) {
      System.err.println( "[KvmSmoke] " + KvmBindings.describeAvailability() );
      System.exit( 1 );
    }
    System.out.println( "[KvmSmoke] " + KvmBindings.describeAvailability() );

    try( Arena arena = Arena.ofShared() ) {
      // 1. /dev/kvm を O_RDWR で open
      MemorySegment path = arena.allocateFrom( "/dev/kvm" );
      int kvmFd = KvmBindings.open( path, KvmBindings.O_RDWR );
      if( kvmFd < 0 ) die( "open(/dev/kvm)" );
      System.out.println( "[KvmSmoke] open(/dev/kvm) fd=" + kvmFd );

      // 2. KVM_GET_API_VERSION
      int apiVer = KvmBindings.ioctl( kvmFd, KvmBindings.KVM_GET_API_VERSION, MemorySegment.NULL );
      System.out.println( "[KvmSmoke] KVM_GET_API_VERSION = " + apiVer );
      if( apiVer != KvmBindings.KVM_API_VERSION ) {
        System.err.println( "[KvmSmoke] KVM_API_VERSION mismatch (expected "
            + KvmBindings.KVM_API_VERSION + ", got " + apiVer + ")" );
        System.exit( 2 );
      }

      // 3. KVM_CREATE_VM
      int vmFd = KvmBindings.ioctl( kvmFd, KvmBindings.KVM_CREATE_VM, MemorySegment.NULL );
      if( vmFd < 0 ) die( "KVM_CREATE_VM" );
      System.out.println( "[KvmSmoke] KVM_CREATE_VM vm_fd=" + vmFd );

      // 4. 4KB host buffer (page-aligned) に NOP×3 + HLT を書く
      MemorySegment guestBuf = arena.allocate( 4096, 4096 );
      guestBuf.set( ValueLayout.JAVA_BYTE, 0, (byte) 0x90 ); // NOP
      guestBuf.set( ValueLayout.JAVA_BYTE, 1, (byte) 0x90 ); // NOP
      guestBuf.set( ValueLayout.JAVA_BYTE, 2, (byte) 0x90 ); // NOP
      guestBuf.set( ValueLayout.JAVA_BYTE, 3, (byte) 0xF4 ); // HLT
      System.out.println( "[KvmSmoke] guest buf host addr = 0x" + Long.toHexString( guestBuf.address() )
          + " (NOP×3 + HLT written)" );

      // 5. KVM_SET_USER_MEMORY_REGION で guest 物理 0 に 4KB map
      MemorySegment memRegion = arena.allocate( KvmBindings.KVM_MEM_REGION_SIZE );
      memRegion.set( ValueLayout.JAVA_INT,  KvmBindings.KVM_MEM_OFF_SLOT,       0 );
      memRegion.set( ValueLayout.JAVA_INT,  KvmBindings.KVM_MEM_OFF_FLAGS,      0 );
      memRegion.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_MEM_OFF_GUEST_ADDR, 0L );
      memRegion.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_MEM_OFF_SIZE,       4096L );
      memRegion.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_MEM_OFF_USER_ADDR,  guestBuf.address() );
      int rc = KvmBindings.ioctl( vmFd, KvmBindings.KVM_SET_USER_MEMORY_REGION, memRegion );
      if( rc < 0 ) die( "KVM_SET_USER_MEMORY_REGION" );
      System.out.println( "[KvmSmoke] KVM_SET_USER_MEMORY_REGION OK (4KB @ guest 0x0)" );

      // 6. KVM_CREATE_VCPU (vcpu_index=0)
      //    ioctl の 3rd arg は variadic; MemorySegment.NULL は rdx=0 を渡すので
      //    vcpu_index=0 と等価。
      int vcpuFd = KvmBindings.ioctl( vmFd, KvmBindings.KVM_CREATE_VCPU, MemorySegment.NULL );
      if( vcpuFd < 0 ) die( "KVM_CREATE_VCPU" );
      System.out.println( "[KvmSmoke] KVM_CREATE_VCPU vcpu_fd=" + vcpuFd );

      // 7. KVM_GET_VCPU_MMAP_SIZE (これは /dev/kvm の fd 経由で呼ぶ)
      int vcpuMmapSize = KvmBindings.ioctl( kvmFd, KvmBindings.KVM_GET_VCPU_MMAP_SIZE, MemorySegment.NULL );
      if( vcpuMmapSize <= 0 ) die( "KVM_GET_VCPU_MMAP_SIZE" );
      System.out.println( "[KvmSmoke] vcpu_mmap_size = " + vcpuMmapSize );

      // 8. mmap で vcpu_state を取る (exit_reason 等が書き込まれる struct kvm_run)
      MemorySegment vcpuState = KvmBindings.mmap( MemorySegment.NULL, vcpuMmapSize,
          KvmBindings.PROT_READ | KvmBindings.PROT_WRITE,
          KvmBindings.MAP_SHARED, vcpuFd, 0L );
      if( vcpuState.address() == -1L || vcpuState.address() == 0L ) die( "mmap(vcpu_state)" );
      vcpuState = vcpuState.reinterpret( vcpuMmapSize );
      System.out.println( "[KvmSmoke] vcpu_state mmap addr = 0x" + Long.toHexString( vcpuState.address() ) );

      // 9. KVM_GET_SREGS → cs.base=0, cs.selector=0 → KVM_SET_SREGS
      MemorySegment sregs = arena.allocate( KvmBindings.KVM_SREGS_SIZE );
      rc = KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_GET_SREGS, sregs );
      if( rc < 0 ) die( "KVM_GET_SREGS" );
      sregs.set( ValueLayout.JAVA_LONG,
          KvmBindings.KVM_SREGS_OFF_CS + KvmBindings.KVM_SEG_OFF_BASE, 0L );
      sregs.set( ValueLayout.JAVA_SHORT,
          KvmBindings.KVM_SREGS_OFF_CS + KvmBindings.KVM_SEG_OFF_SELECTOR, (short) 0 );
      rc = KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_SET_SREGS, sregs );
      if( rc < 0 ) die( "KVM_SET_SREGS" );
      System.out.println( "[KvmSmoke] sregs set: cs.base=0 cs.selector=0" );

      // 10. KVM_GET_REGS → rip=0, rflags=2 → KVM_SET_REGS
      MemorySegment regs = arena.allocate( KvmBindings.KVM_REGS_SIZE );
      rc = KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_GET_REGS, regs );
      if( rc < 0 ) die( "KVM_GET_REGS" );
      regs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RIP,    0L );
      regs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RFLAGS, 2L );
      rc = KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_SET_REGS, regs );
      if( rc < 0 ) die( "KVM_SET_REGS" );
      System.out.println( "[KvmSmoke] regs set: rip=0 rflags=2" );

      // 11. KVM_RUN
      System.out.println( "[KvmSmoke] running guest (NOP×3 + HLT) ..." );
      long start = System.nanoTime();
      rc = KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_RUN, MemorySegment.NULL );
      long elapsed = System.nanoTime() - start;
      if( rc < 0 ) die( "KVM_RUN" );
      System.out.println( "[KvmSmoke] KVM_RUN returned rc=" + rc + " elapsed=" + elapsed + "ns" );

      // 12. exit_reason 確認
      int exitReason = vcpuState.get( ValueLayout.JAVA_INT, KvmBindings.KVM_RUN_OFF_EXIT_REASON );
      System.out.println( "[KvmSmoke] exit_reason = " + exitReason + " (" + exitReasonName( exitReason ) + ")" );

      // RIP が 4 (= NOP×3 + HLT の次) に進んでいることを確認
      rc = KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_GET_REGS, regs );
      if( rc < 0 ) die( "KVM_GET_REGS (after run)" );
      long ripAfter = regs.get( ValueLayout.JAVA_LONG, KvmBindings.KVM_REGS_OFF_RIP );
      System.out.println( "[KvmSmoke] rip after = 0x" + Long.toHexString( ripAfter ) );

      // cleanup
      KvmBindings.munmap( vcpuState, vcpuMmapSize );
      KvmBindings.close( vcpuFd );
      KvmBindings.close( vmFd );
      KvmBindings.close( kvmFd );

      if( exitReason == KvmBindings.KVM_EXIT_HLT ) {
        System.out.println( "KVM smoke OK: exit_reason=" + exitReason + " (KVM_EXIT_HLT), rip=0x"
            + Long.toHexString( ripAfter ) + ", KVM_RUN elapsed=" + elapsed + "ns" );
        System.exit( 0 );
      } else {
        System.err.println( "KVM smoke FAIL: expected exit_reason=" + KvmBindings.KVM_EXIT_HLT
            + " (HLT), got " + exitReason );
        System.exit( 4 );
      }
    }
  }

  private static void die( String op ) throws Throwable {
    int errno = KvmBindings.errno();
    System.err.println( "[KvmSmoke] " + op + " failed, errno=" + errno + " (" + errnoName( errno ) + ")" );
    System.exit( 3 );
  }

  private static String exitReasonName( int r ) {
    return switch( r ) {
      case KvmBindings.KVM_EXIT_UNKNOWN        -> "KVM_EXIT_UNKNOWN";
      case KvmBindings.KVM_EXIT_EXCEPTION      -> "KVM_EXIT_EXCEPTION";
      case KvmBindings.KVM_EXIT_IO             -> "KVM_EXIT_IO";
      case KvmBindings.KVM_EXIT_HYPERCALL      -> "KVM_EXIT_HYPERCALL";
      case KvmBindings.KVM_EXIT_DEBUG          -> "KVM_EXIT_DEBUG";
      case KvmBindings.KVM_EXIT_HLT            -> "KVM_EXIT_HLT";
      case KvmBindings.KVM_EXIT_MMIO           -> "KVM_EXIT_MMIO";
      case KvmBindings.KVM_EXIT_INTR           -> "KVM_EXIT_INTR";
      case KvmBindings.KVM_EXIT_SHUTDOWN       -> "KVM_EXIT_SHUTDOWN";
      case KvmBindings.KVM_EXIT_FAIL_ENTRY     -> "KVM_EXIT_FAIL_ENTRY";
      case KvmBindings.KVM_EXIT_INTERNAL_ERROR -> "KVM_EXIT_INTERNAL_ERROR";
      default                                  -> "unknown=" + r;
    };
  }

  private static String errnoName( int e ) {
    return switch( e ) {
      case 1  -> "EPERM";
      case 2  -> "ENOENT";
      case 9  -> "EBADF";
      case 11 -> "EAGAIN";
      case 12 -> "ENOMEM";
      case 13 -> "EACCES";
      case 14 -> "EFAULT";
      case 16 -> "EBUSY";
      case 19 -> "ENODEV";
      case 22 -> "EINVAL";
      default -> "?";
    };
  }
}
