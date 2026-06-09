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

  /** VM-level handle (KVM: vmFd+kvmFd / WHP: partition) を閉じる。vCPU は別途 close 済のこと。 */
  void close();

  // ===== factory + platform guest-RAM 確保 (VM 作成前に呼ぶため static) =====

  /** 新しい VM を作る (KVM: open /dev/kvm + KVM_CREATE_VM)。platform で実装を選ぶ (現状 KVM)。 */
  static HvVm create() throws Throwable {
    return new KvmVm();
  }

  /** guest 物理 RAM の host backing を確保する (KVM=mmap MAP_ANON、未 touch は非 backing)。 */
  static MemorySegment allocGuestRam( long sizeBytes ) throws Throwable {
    MemorySegment s = KvmBindings.mmap( MemorySegment.NULL, sizeBytes,
        KvmBindings.PROT_READ | KvmBindings.PROT_WRITE,
        KvmBindings.MAP_PRIVATE | KvmBindings.MAP_ANONYMOUS, -1, 0L );
    if( s.address() == 0L || s.address() == -1L )
      throw new IllegalStateException( "guest RAM mmap(" + sizeBytes + ") errno=" + KvmBindings.errno() );
    return s.reinterpret( sizeBytes );
  }

  /** 確保済 host backing を解放する。 */
  static void freeGuestRam( MemorySegment seg, long sizeBytes ) {
    try { KvmBindings.munmap( seg, sizeBytes ); } catch( Throwable ignore ) {}
  }
}
