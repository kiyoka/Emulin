// ----------------------------------------
//  HvVm の Linux KVM 実装 (issue #221 WHP 移植 Stage 2)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// KVM (/dev/kvm) 上の 1 VM を HvVm として実装する。NativeCpuBackend.setupKvm に散っていた
// open(/dev/kvm) + KVM_GET_API_VERSION + KVM_CREATE_VM + KVM_SET_USER_MEMORY_REGION をここへ集約し、
// NativeCpuBackend から hypervisor-API 呼び出しを排除する (arch 定数の参照は残る = hypervisor 非依存)。
//
// kvmFd (system fd) と vmFd を所有し、createVcpu で KvmVcpu に渡す。VM 制御 struct (memory region) は
// 専用 arena に確保する。close() で vmFd→kvmFd→arena を解放する (guest RAM の host backing は
// NativeCpuBackend が HvVm.freeGuestRam で別途解放する = VM とは独立した lifetime)。
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

final class KvmVm implements HvVm {

  private int         kvmFd = -1;   // system fd (KVM_GET_SUPPORTED_CPUID 等で vcpu にも渡る)
  private int         vmFd  = -1;
  private final Arena arena;        // VM 制御 struct (memory region) + /dev/kvm path の生存域

  KvmVm() throws Throwable {
    arena = Arena.ofShared();
    boolean ok = false;
    try {
      if( !KvmBindings.probe() ) throw new IllegalStateException( "KVM not available" );
      kvmFd = KvmBindings.open( arena.allocateFrom( "/dev/kvm" ), KvmBindings.O_RDWR );
      if( kvmFd < 0 ) throw new IllegalStateException( "open(/dev/kvm) errno=" + KvmBindings.errno() );
      if( KvmBindings.ioctl( kvmFd, KvmBindings.KVM_GET_API_VERSION, MemorySegment.NULL )
          != KvmBindings.KVM_API_VERSION )
        throw new IllegalStateException( "KVM_API_VERSION mismatch" );
      vmFd = KvmBindings.ioctl( kvmFd, KvmBindings.KVM_CREATE_VM, MemorySegment.NULL );
      if( vmFd < 0 ) throw new IllegalStateException( "KVM_CREATE_VM errno=" + KvmBindings.errno() );
      ok = true;
    } finally {
      // ★ exception-safe: コンストラクタ途中失敗 (CREATE_VM 失敗等) で開いた kvmFd / arena を leak しない。
      if( !ok ) close();
    }
  }

  @Override
  public void mapGuestRam( long guestPhysAddr, long hostAddr, long sizeBytes ) throws Throwable {
    MemorySegment mr = arena.allocate( KvmBindings.KVM_MEM_REGION_SIZE );
    mr.set( ValueLayout.JAVA_INT,  KvmBindings.KVM_MEM_OFF_SLOT,       0 );
    mr.set( ValueLayout.JAVA_INT,  KvmBindings.KVM_MEM_OFF_FLAGS,      0 );
    mr.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_MEM_OFF_GUEST_ADDR, guestPhysAddr );
    mr.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_MEM_OFF_SIZE,       sizeBytes );
    mr.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_MEM_OFF_USER_ADDR,  hostAddr );
    if( KvmBindings.ioctl( vmFd, KvmBindings.KVM_SET_USER_MEMORY_REGION, mr ) < 0 )
      throw new IllegalStateException( "KVM_SET_USER_MEMORY_REGION errno=" + KvmBindings.errno() );
  }

  @Override
  public HvVcpu createVcpu( int vcpuId, Arena vcpuArena, boolean ownArena ) throws Throwable {
    return new KvmVcpu( kvmFd, vmFd, vcpuId, vcpuArena, ownArena );
  }

  @Override
  public void close() {
    try { if( vmFd  >= 0 ) KvmBindings.close( vmFd ); }  catch( Throwable ignore ) {}
    try { if( kvmFd >= 0 ) KvmBindings.close( kvmFd ); } catch( Throwable ignore ) {}
    try { arena.close(); } catch( Throwable ignore ) {}
    vmFd = -1; kvmFd = -1;
  }
}
