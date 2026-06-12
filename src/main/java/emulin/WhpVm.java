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

  // ★ issue #221 Stage B fork 診断: EMULIN_WHP_DEBUG=1 で partition 作成 / GPA map の各段を出力。
  private static final boolean DBG = System.getenv( "EMULIN_WHP_DEBUG" ) != null;

  private MemorySegment partition;
  private final Arena   arena;     // partition handle 受け取り buffer + property buffer の生存域

  WhpVm() throws Throwable {
    arena = Arena.ofShared();
    boolean ok = false;
    try {
      MemorySegment partBuf = arena.allocate( ValueLayout.ADDRESS );
      hr( "WHvCreatePartition", (int) WhpBindings.createPartition().invoke( partBuf ) );
      partition = partBuf.get( ValueLayout.ADDRESS, 0 );
      if( DBG ) System.err.println( "[whp] CreatePartition OK partition=0x" + Long.toHexString( partition.address() )
          + " (thread=" + Thread.currentThread().getName() + ")" );

      MemorySegment prop = arena.allocate( WhpBindings.WHV_PARTITION_PROPERTY_SIZE );
      prop.set( ValueLayout.JAVA_INT, 0, MAX_VCPUS );
      hr( "WHvSetPartitionProperty(ProcessorCount=" + MAX_VCPUS + ")", (int) WhpBindings.setPartitionProperty()
          .invoke( partition, WhpBindings.WHvPartitionPropertyCodeProcessorCount, prop, WhpBindings.WHV_PARTITION_PROPERTY_SIZE ) );
      hr( "WHvSetupPartition", (int) WhpBindings.setupPartition().invoke( partition ) );
      if( DBG ) System.err.println( "[whp] SetupPartition OK partition=0x" + Long.toHexString( partition.address() ) );
      ok = true;
    } finally {
      // exception-safe: SetupPartition 失敗等で partition / arena を leak しない。
      if( !ok ) close();
    }
  }

  @Override
  public void mapGuestRam( long guestPhysAddr, long hostAddr, long sizeBytes ) throws Throwable {
    // host backing (HvVm.allocGuestRam = VirtualAlloc) を guest 物理 gpa に R|W|X で map。
    if( DBG ) System.err.println( "[whp] mapGuestRam partition=0x" + Long.toHexString( partition.address() )
        + " gpa=0x" + Long.toHexString( guestPhysAddr ) + " host=0x" + Long.toHexString( hostAddr )
        + " size=0x" + Long.toHexString( sizeBytes ) );
    // ★ HRESULT 0xC0370008 = WHP の「1 process につき memory を map できる partition は 1 つ」制限
    //   (Microsoft Q&A #320005、KVM と違い multi-partition GPA map 不可。VirtualAlloc2 低位確保でも回避不可)。
    //   ただし execve (step 3e-whp-5) では「旧 process の partition teardown」と「新 process の partition
    //   map」が thread 競合するだけで、旧側は ms オーダーで close される → 短い retry で待てば成立する。
    //   fork (旧 partition = 親が生存し続ける) は retry しても解放されないので、retry 窓を使い切ったら
    //   明示メッセージで fail する (診断しやすさ優先、cryptic HRESULT のまま投げない)。
    int rc = 0;
    for( int attempt = 0; attempt < 100; attempt++ ) {   // 100 × 50ms = 最大 5 秒待つ
      rc = (int) WhpBindings.mapGpaRange().invoke( partition,
          MemorySegment.ofAddress( hostAddr ), guestPhysAddr, sizeBytes,
          WhpBindings.WHvMapGpaRangeFlagRead | WhpBindings.WHvMapGpaRangeFlagWrite | WhpBindings.WHvMapGpaRangeFlagExecute );
      if( rc != 0xC0370008 ) break;
      if( DBG && attempt == 0 ) System.err.println( "[whp] mapGuestRam 0xc0370008 → 旧 partition 解放待ち retry (exec 経路)" );
      try { Thread.sleep( 50 ); } catch( InterruptedException ie ) { Thread.currentThread().interrupt(); break; }
    }
    if( DBG ) System.err.println( "[whp] mapGuestRam -> HRESULT=0x" + Integer.toHexString( rc ) );
    if( rc == 0xC0370008 ) {
      throw new IllegalStateException(
          "WHvMapGpaRange HRESULT=0xc0370008: WHP は 1 process につき 1 partition しか guest memory を map "
          + "できない既知の制限です (fork = 2 つ目の partition の map が失敗)。fork/multi-process が要るなら "
          + "Linux + KVM backend を使うか、single-process の workload で実行してください。" );
    }
    hr( "WHvMapGpaRange", rc );
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
