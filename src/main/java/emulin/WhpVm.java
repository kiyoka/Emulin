// ----------------------------------------
//  HvVm の Windows Hypervisor Platform (WHP) 実装 (issue #221 WHP 移植 Stage 3)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// WHP partition を 1 つ表す HvVm。KvmVm (KVM 経路) と同じ interface を満たす。WhpSyscallSmoke64 で
// Windows 実機実証済みの partition セットアップ列 (CreatePartition → SetPartitionProperty(ProcessorCount)
// → SetupPartition → MapGpaRange → CreateVirtualProcessor) を HvVm の形に再構成したもの。
//
// ★ KVM は vCPU を動的に (KVM_CREATE_VCPU で随時) 追加できるが、WHP は partition の ProcessorCount を
//   SetupPartition 前に宣言する必要がある。pthread worker (追加 vCPU) に備えて MAX_VCPUS を多めに取る。
//
// ★ Linux では HvVm.create() が KvmVm を選ぶので本 class は instantiate されない (KVM oracle 無影響)。
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

final class WhpVm implements HvVm {

  // WHP は ProcessorCount を SetupPartition 前に確定する必要がある (KVM の動的 vCPU 追加と違う)。
  //   pthread program の worker (integ_dyn64 で 8、通常はもっと少ない) に十分な headroom。1 partition =
  //   1 process なので fork 子は別 partition (各々 MAX_VCPUS まで)。
  private static final int MAX_VCPUS = 64;

  private MemorySegment partition;
  private final Arena   arena;     // partition handle 受け取り buffer + property buffer の生存域

  WhpVm() throws Throwable {
    arena = Arena.ofShared();
    boolean ok = false;
    try {
      MemorySegment partBuf = arena.allocate( ValueLayout.ADDRESS );
      hr( "WHvCreatePartition", (int) WhpBindings.createPartition().invoke( partBuf ) );
      partition = partBuf.get( ValueLayout.ADDRESS, 0 );

      MemorySegment prop = arena.allocate( WhpBindings.WHV_PARTITION_PROPERTY_SIZE );
      prop.set( ValueLayout.JAVA_INT, 0, MAX_VCPUS );
      hr( "WHvSetPartitionProperty(ProcessorCount=" + MAX_VCPUS + ")", (int) WhpBindings.setPartitionProperty()
          .invoke( partition, WhpBindings.WHvPartitionPropertyCodeProcessorCount, prop, WhpBindings.WHV_PARTITION_PROPERTY_SIZE ) );
      hr( "WHvSetupPartition", (int) WhpBindings.setupPartition().invoke( partition ) );
      ok = true;
    } finally {
      // exception-safe: SetupPartition 失敗等で partition / arena を leak しない。
      if( !ok ) close();
    }
  }

  @Override
  public void mapGuestRam( long guestPhysAddr, long hostAddr, long sizeBytes ) throws Throwable {
    // host backing (HvVm.allocGuestRam = VirtualAlloc) を guest 物理 gpa に R|W|X で map。
    hr( "WHvMapGpaRange", (int) WhpBindings.mapGpaRange().invoke( partition,
        MemorySegment.ofAddress( hostAddr ), guestPhysAddr, sizeBytes,
        WhpBindings.WHvMapGpaRangeFlagRead | WhpBindings.WHvMapGpaRangeFlagWrite | WhpBindings.WHvMapGpaRangeFlagExecute ) );
  }

  @Override
  public HvVcpu createVcpu( int vcpuId, Arena vcpuArena, boolean ownArena ) throws Throwable {
    return new WhpVcpu( partition, vcpuId, vcpuArena, ownArena );
  }

  @Override
  public void close() {
    try { if( partition != null ) WhpBindings.deletePartition().invoke( partition ); } catch( Throwable ignore ) {}
    try { arena.close(); } catch( Throwable ignore ) {}
    partition = null;
  }

  private static void hr( String op, int hresult ) {
    if( hresult != 0 ) throw new IllegalStateException( op + " HRESULT=0x" + Integer.toHexString( hresult ) );
  }
}
