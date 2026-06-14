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
  //   ★ fork-on-WHP (step 3e-whp-7) で partition は JVM 全体共有 = 全 guest process の全 vCPU が
  //   この上限を分け合う (VP index は close で free-list に戻して再利用するので、同時実行数の上限)。
  private static final int MAX_VCPUS = 64;

  // ★ issue #221 Stage B fork 診断: EMULIN_WHP_DEBUG=1 で partition 作成 / GPA map の各段を出力。
  private static final boolean DBG = System.getenv( "EMULIN_WHP_DEBUG" ) != null;

  // ===== JVM 全体共有の単一 partition (issue #221 step 3e-whp-7、fork-on-WHP) =====
  //   WHP は 1 process につき 1 partition しか guest memory を map できない (§4.4rr) ので、fork/exec の
  //   「2 つ目の partition」が作れない。対策 = JVM で partition を 1 つだけ作り、guest process ごとに
  //   別 GPA slot (POOL_SIZE 刻み) へ pool を map する。NativeCpuBackend が global()/allocSlot()/
  //   releaseSlot() を使う。partition は JVM 終了まで生存 (close しない)。
  private static WhpVm GLOBAL;
  static synchronized WhpVm global() throws Throwable {
    if( GLOBAL == null ) GLOBAL = new WhpVm();
    return GLOBAL;
  }

  // GPA slot allocator: slot base を slotSize 刻みで払い出す。WHvMapGpaRange は高 GPA (>~64GB) で
  //   失敗しうる (Simpleator issue #2 の知見) ので上限を 64GB とし、解放済 slot を free-list で再利用
  //   する (bash pipeline 等の短命 fork の連鎖で枯渇しないように)。
  private static final long GPA_LIMIT = 1L << 36;   // 64GB
  private static long slotSizeUsed = 0;             // 最初の allocSlot で確定 (全 slot 同一サイズ前提)
  private static long nextSlotBase = 0;
  private static final java.util.ArrayDeque<Long> freeSlots = new java.util.ArrayDeque<>();
  static synchronized long allocSlot( long slotSize ) {
    if( slotSizeUsed == 0 ) slotSizeUsed = slotSize;
    if( slotSize != slotSizeUsed )
      throw new IllegalStateException( "WhpVm.allocSlot: slot size 不一致 (" + slotSize + " != " + slotSizeUsed + ")" );
    Long re = freeSlots.poll();
    if( re != null ) return re;
    long base = nextSlotBase;
    if( base + slotSize > GPA_LIMIT )
      throw new IllegalStateException( "WhpVm.allocSlot: GPA 空間枯渇 (limit 64GB、同時 process 数を減らすか EMULIN_NATIVE_POOL_MB を小さく)" );
    nextSlotBase = base + slotSize;
    return base;
  }
  static synchronized void releaseSlot( long base ) {
    if( base >= 0 ) freeSlots.push( base );
  }

  // VP index allocator: WHP の VP index は ProcessorCount (MAX_VCPUS) 未満でなければならず、partition
  //   共有化で全 process が分け合う。close で free-list に戻して再利用する。
  private final java.util.ArrayDeque<Integer> freeVps = new java.util.ArrayDeque<>();
  private int nextVp = 0;
  synchronized int allocVp() {
    Integer re = freeVps.poll();
    if( re != null ) return re;
    if( nextVp >= MAX_VCPUS )
      throw new IllegalStateException( "WhpVm: VP index 枯渇 (同時 vCPU 上限 " + MAX_VCPUS + ")" );
    return nextVp++;
  }
  synchronized void releaseVp( int idx ) { freeVps.push( idx ); }

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

  // ★ synchronized (issue #304): partition は JVM 全体共有の単一 instance (GLOBAL) なので、別 guest
  //   process (親 + 各 fork 子 = 別 eval thread) が各自の WhpGpaBacking 経由で並行に map/unmap しうる。
  //   #304 の lazy commit で map/unmap は「process あたり 1 回」から「chunk 単位の継続 stream」になり
  //   並行度が上がるため、同一 partition handle への WHvMapGpaRange/WHvUnmapGpaRange を本 instance
  //   monitor で直列化する (WHP の partition mutation thread-safety は未保証)。lock 順は WhpGpaBacking
  //   monitor → WhpVm monitor の一方向で deadlock 無し。retry (0xC0370008 = global partition 設計では
  //   本来出ない legacy 経路) の sleep は monitor 保持のままだが、発火しない前提なので実害小。
  @Override
  public synchronized void mapGuestRam( long guestPhysAddr, long hostAddr, long sizeBytes ) throws Throwable {
    // host backing (HvVm.allocGuestRam = VirtualAlloc) を guest 物理 gpa に R|W|X で map。
    if( DBG ) System.err.println( "[whp] mapGuestRam partition=0x" + Long.toHexString( partition.address() )
        + " gpa=0x" + Long.toHexString( guestPhysAddr ) + " host=0x" + Long.toHexString( hostAddr )
        + " size=0x" + Long.toHexString( sizeBytes ) );
    // ★ HRESULT 0xC0370008 = WHP の「1 process につき memory を map できる partition は 1 つ」制限
    //   (Microsoft Q&A #320005)。step 3e-whp-7 で partition は JVM 共有 + GPA slot 化したので通常は
    //   発生しない。発生し得る残り経路 = 旧実装の別 partition (smoke 等) との一時競合 → 短い retry で
    //   待ち、使い切ったら明示メッセージで fail (診断しやすさ優先、cryptic HRESULT のまま投げない)。
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
          "WHvMapGpaRange HRESULT=0xc0370008: WHP の 1-partition-per-process 制限に衝突しました。"
          + "fork/exec は global partition + GPA slot (step 3e-whp-7) で対応済みのはずなので、"
          + "別 partition (smoke 等) との併用や partition leak を疑ってください。" );
    }
    hr( "WHvMapGpaRange", rc );
  }

  @Override
  public HvVcpu createVcpu( int vcpuId, Arena vcpuArena, boolean ownArena ) throws Throwable {
    // ★ step 3e-whp-7: partition は JVM 共有なので、呼び出し側の vcpuId (process 内で一意) は使わず
    //   partition 全体で一意な VP index を allocVp で採番する (close で free-list に戻り再利用)。
    return new WhpVcpu( this, partition, allocVp(), vcpuArena, ownArena );
  }

  /** GPA slot の unmap (step 3e-whp-7): process 終了時に自分の slot を partition から外す。
   *  ★ synchronized (issue #304): mapGuestRam と同様、JVM 共有 partition への並行 mutation を直列化。 */
  @Override
  public synchronized void unmapGuestRam( long guestPhysAddr, long sizeBytes ) throws Throwable {
    if( DBG ) System.err.println( "[whp] unmapGuestRam gpa=0x" + Long.toHexString( guestPhysAddr )
        + " size=0x" + Long.toHexString( sizeBytes ) );
    try { WhpBindings.unmapGpaRange().invoke( partition, guestPhysAddr, sizeBytes ); }
    catch( Throwable ignore ) {}   // best-effort (万一失敗すると slot 再利用時の map が fail して表面化する)
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
