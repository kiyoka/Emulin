// ----------------------------------------
//  Hypervisor 非依存の VM 抽象 (issue #221 WHP 移植 step 1 / Stage 2)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// 1 つの VM (KVM partition 相当) を表す hypervisor 中立 interface。Linux KVM (KvmVm) と
// Windows Hypervisor Platform (WhpVm、後続 step) が実装する。NativeCpuBackend は本 interface
// 経由で「VM 作成 / guest RAM の map / vCPU 生成 / VM 破棄」を行い、KVM の open(/dev/kvm)+
// KVM_CREATE_VM+KVM_SET_USER_MEMORY_REGION ⇄ WHP の WHvCreatePartition+WHvMapGpaRange の差を
// 実装に閉じ込める。
//
// 所有関係 (NativeCpuBackend):
//   - main backend が HvVm を 1 つ所有し、自分の HvVcpu(id=0) を createVcpu で得る。
//   - pthread worker は owner の HvVm を共有し、createVcpu(id>=1) で追加 vCPU を得る (同一 VM)。
//   - fork 子は独立した新しい HvVm を持つ別 process。
//
// ★ guest 物理 RAM の host backing 確保 (allocGuestRam) は VM 作成より前に行う必要がある
//   (connect_devices で NativeMemoryBackend を構築し ELF を copy してから、eval で VM を lazy に
//   作る)。よって VM instance のメソッドでなく static にし、platform (KVM=mmap MAP_ANON /
//   WHP=VirtualAlloc) で確保する。
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public interface HvVm {

  /**
   * host backing を guest 物理アドレス gpa に map する。
   *   KVM = KVM_SET_USER_MEMORY_REGION (slot 0)、WHP = WHvMapGpaRange (R|W|X)。
   * @param hostAddr  allocGuestRam が返した host backing の先頭アドレス
   */
  void mapGuestRam( long guestPhysAddr, long hostAddr, long sizeBytes ) throws Throwable;

  /**
   * この VM 上に vCPU を 1 つ作る。
   * @param arena    vCPU の制御 struct を確保する Arena
   * @param ownArena true なら HvVcpu.close() で arena を閉じる (worker の専用 arena)
   */
  HvVcpu createVcpu( int vcpuId, Arena arena, boolean ownArena ) throws Throwable;

  /**
   * map 済 guest RAM を GPA 空間から外す (issue #221 step 3e-whp-7、fork-on-WHP)。
   *   WHP = WHvUnmapGpaRange (partition は JVM 共有なので process 終了時に自分の slot だけ外す)。
   *   KVM = per-process VM を丸ごと close() するので不要 (default no-op)。
   */
  default void unmapGuestRam( long guestPhysAddr, long sizeBytes ) throws Throwable {}

  /** VM-level handle (KVM: vmFd+kvmFd / WHP: partition) を閉じる。vCPU は別途 close 済のこと。 */
  void close();

  // ===== factory + platform guest-RAM 確保 (VM 作成前に呼ぶため static) =====
  //
  //  platform 選択: KVM (Linux、/dev/kvm 可用) を優先し、無ければ WHP (Windows + Hyper-V) を使う。
  //    Linux では KvmBindings.probe()=true / WhpBindings.probe()=false → KVM。
  //    Windows では KvmBindings.probe()=false (/dev/kvm 無し) / WhpBindings.probe()=true → WHP。
  //  probe() は cache 済なので毎回の dispatch は安価。

  /** 新しい VM を作る (KVM: open(/dev/kvm)+CREATE_VM / WHP: CreatePartition+SetupPartition)。 */
  static HvVm create() throws Throwable {
    if( KvmBindings.probe() ) return new KvmVm();
    if( WhpBindings.probe() ) return new WhpVm();
    throw new IllegalStateException( "native backend: 利用可能な hypervisor (KVM/WHP) がありません" );
  }

  /** guest 物理 RAM の host backing を確保する (KVM=mmap MAP_ANON / WHP=VirtualAlloc、未 touch は非 backing)。 */
  static MemorySegment allocGuestRam( long sizeBytes ) throws Throwable {
    if( KvmBindings.probe() ) {
      MemorySegment s = KvmBindings.mmap( MemorySegment.NULL, sizeBytes,
          KvmBindings.PROT_READ | KvmBindings.PROT_WRITE,
          KvmBindings.MAP_PRIVATE | KvmBindings.MAP_ANONYMOUS, -1, 0L );
      if( s.address() == 0L || s.address() == -1L )
        throw new IllegalStateException( "guest RAM mmap(" + sizeBytes + ") errno=" + KvmBindings.errno() );
      return s.reinterpret( sizeBytes );
    }
    if( WhpBindings.probe() ) {
      // ★ issue #304 (lazy commit): pool は MEM_RESERVE のみで確保し、commit charge (pagefile 予約) を
      //   起動時に払わない。実際の MEM_COMMIT + WHvMapGpaRange は WhpGpaBacking が allocPt/allocData の
      //   chunk 単位で遅延実行する (commit charge を guest 実使用量に比例させ、fork 連鎖 N×POOL_SIZE の
      //   system commit limit 圧迫を緩和)。WHvMapGpaRange の source は commit 済 page-aligned memory が
      //   要るが、それは chunk commit が満たす。物理 RAM は MEM_COMMIT でも demand-zero で元から遅延。
      // ★ fork (step 3e-whp-x): WHvMapGpaRange は host source address の magnitude に敏感で、高位
      //   アドレス帯 (>~45GB) だと 2 つ目の partition の map が HRESULT 0xC0370008 で失敗する
      //   (Simpleator issue #2)。VirtualAlloc2 + MEM_ADDRESS_REQUIREMENTS で HighestEndingAddress を
      //   低位 (< 32GB) に制約して確保 → 親/子とも確実に map 可能にする。VirtualAlloc2 不可な古い
      //   Windows では従来 VirtualAlloc に fallback。
      MemorySegment s = null;
      try {
        try( Arena tmp = Arena.ofConfined() ) {
          // MEM_ADDRESS_REQUIREMENTS { PVOID Lowest; PVOID Highest; SIZE_T Alignment } = 24 byte
          MemorySegment req = tmp.allocate( 24 );
          req.set( ValueLayout.JAVA_LONG, 0,  0L );             // LowestStartingAddress = NULL (制約なし)
          req.set( ValueLayout.JAVA_LONG, 8,  0x7FFFFFFFFL );   // HighestEndingAddress = 32GB-1 (失敗境界 ~45GB 未満)
          req.set( ValueLayout.JAVA_LONG, 16, 0L );             // Alignment = 0 (既定 64KB granularity)
          // MEM_EXTENDED_PARAMETER { DWORD64 Type:8|Reserved:56; union { ... PVOID Pointer } } = 16 byte
          MemorySegment ext = tmp.allocate( 16 );
          ext.set( ValueLayout.JAVA_LONG, 0, WhpBindings.MemExtendedParameterAddressRequirements ); // Type=1 (低 8bit)
          ext.set( ValueLayout.JAVA_LONG, 8, req.address() );   // Pointer = &MEM_ADDRESS_REQUIREMENTS
          s = (MemorySegment) WhpBindings.virtualAlloc2().invoke(
              MemorySegment.NULL, MemorySegment.NULL, sizeBytes,
              (int) WhpBindings.MEM_RESERVE, (int) WhpBindings.PAGE_READWRITE,   // commit は WhpGpaBacking が chunk 単位 (issue #304)
              ext, 1 );
        }
      } catch( Throwable t ) {
        s = (MemorySegment) WhpBindings.virtualAlloc().invoke( MemorySegment.NULL, sizeBytes,
            (int) WhpBindings.MEM_RESERVE, (int) WhpBindings.PAGE_READWRITE );   // commit は WhpGpaBacking が chunk 単位 (issue #304)
      }
      if( s == null || s.address() == 0L )
        throw new IllegalStateException( "guest RAM VirtualAlloc2(" + sizeBytes + ") failed" );
      return s.reinterpret( sizeBytes );
    }
    throw new IllegalStateException( "native backend: guest RAM 確保に使える hypervisor がありません" );
  }

  /** 確保済 host backing を解放する (KVM=munmap / WHP=VirtualFree)。 */
  static void freeGuestRam( MemorySegment seg, long sizeBytes ) {
    try {
      if( KvmBindings.probe() ) { KvmBindings.munmap( seg, sizeBytes ); return; }
      if( WhpBindings.probe() ) { WhpBindings.virtualFree().invoke( seg, 0L, (int) WhpBindings.MEM_RELEASE ); return; }
    } catch( Throwable ignore ) {}
  }
}
