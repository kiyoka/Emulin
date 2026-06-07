// ----------------------------------------
//  Native CPU backend stub (Windows Hypervisor Platform / WHP)
//  (issue #221 Phase 0 step 2)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// 目的: x86-64 guest user code を実 CPU の vCPU でネイティブ実行し、syscall
//   だけを emulin の Java 層 (SyscallAmd64.call_amd64) にトラップする native
//   backend の **AbstractCpu subclass stub**。Phase 0 step 2 時点では:
//
//   - class が CpuBackend.NATIVE.createCpu64 から instantiate できる
//   - eval() / fetch() / set_signal_handler() / spawnVCpu() / duplicate() 等の
//     「実 CPU 動作」を要する method は全部 UnsupportedOperationException を
//     投げ、step 3+ で実装する旨を明示する
//   - accessor (set_ip/get_ip/set_sp/get_sp/set_ax) は instance field に保存
//     する最小実装 (= future hypervisor regs を反映する placeholder)
//   - connect_devices は mem/syscall 参照保持
//
// 設計原則:
//   1. **stub は instantiate できる** こと。CpuBackend.NATIVE.createCpu64 が
//      probe 成功時に new NativeCpuBackend(...) を返せる。
//   2. **動作要求は明示 throws**。Phase 0 step 3+ で初期化される vCPU を要する
//      操作 (eval/fetch/regs 同期) は確実に fail-fast。
//   3. **probe との分離**。本 class は probe 結果を仮定し、WhpBindings.probe()
//      が true でないと CpuBackend.createCpu64 経路で到達しない (= 本 ctor
//      が走るのは Windows + WHP available 環境のみ)。
//   4. **AbstractCpu の契約は全部満たす**。spawnVCpu は AbstractCpu の default
//      (UnsupportedOperationException) を流用 = override しない。
//   5. **Linux/WSL でも compile clean**。FFM の MethodHandle や WHP の handle
//      は WhpBindings の getter 経由でしか触らないため、本 class 自体は OS
//      非依存に compile できる (= Java 25 byte code として再現性ある)。
//
// 範囲外 (Phase 0 step 3+):
//   - 実 partition / vCPU 作成 (WHvCreatePartition → WHvSetupPartition →
//     WHvCreateVirtualProcessor)
//   - guest 物理メモリ map (WHvMapGpaRange)
//   - VM-exit loop (WHvRunVirtualProcessor) と syscall trap
//   - vCPU regs 同期 (WHvGet/SetVirtualProcessorRegisters)
//   - LSTAR スタブ配置 (syscall trap 方式)
package emulin;

public class NativeCpuBackend extends AbstractCpu
{
  // Phase 0 step 2 stub 用 placeholder。step 3+ で WHP の partition handle と
  //   vCPU index を持つ。
  private long whpPartitionHandle;     // WHV_PARTITION_HANDLE (将来)
  private int  vpIndex;                // VpIndex (将来、main thread は 0)

  // accessor 用 placeholder。step 3+ で WHvGet/SetVirtualProcessorRegisters を
  //   経由した actual vCPU register read/write に置換される。
  private long stub_rip;
  private long stub_rsp;
  private long stub_rax;

  public NativeCpuBackend( Sysinfo _sysinfo, Process _process ) {
    sysinfo = _sysinfo;
    process = _process;
    whpPartitionHandle = 0L;
    vpIndex            = 0;
    if( System.getenv( "EMULIN_TRACE_BACKEND" ) != null ) {
      System.err.println( "[backend] NativeCpuBackend stub instantiated (Phase 0 step 2; "
          + "actual WHP partition not created until step 3+)" );
    }
  }

  // ===== AbstractCpu の abstract method 実装 (stub) =====

  @Override
  public void init() {
    // step 3+: WHvCreatePartition / WHvSetupPartition / WHvCreateVirtualProcessor
    //   ここで実行する。
  }

  @Override
  public AbstractCpu duplicate( Process _process ) {
    throw new UnsupportedOperationException(
        "NativeCpuBackend.duplicate (fork) not implemented yet (issue #221 Phase 0 step 3+; "
        + "WHP では partition 単位 fork が無いため、追加 partition or copy-on-write "
        + "page table の設計が必要)" );
  }

  // accessor: placeholder。step 3+ で WHvGet/SetVirtualProcessorRegisters に置換。

  @Override public void set_ip( long _ip )    { stub_rip = _ip; }
  @Override public long get_ip()              { return stub_rip; }
  @Override public void set_sp( long sp )     { stub_rsp = sp; }
  @Override public long get_sp()              { return stub_rsp; }
  @Override public void set_ax( int value )   { stub_rax = value & 0xFFFFFFFFL; }

  @Override
  public long pushString( String str ) {
    throw new UnsupportedOperationException(
        "NativeCpuBackend.pushString not implemented yet (issue #221 Phase 0 step 3+; "
        + "Process.stack_data_init64 が ELF startup で呼ぶ。WHvMapGpaRange + 直 byte 書込で実装予定)" );
  }

  @Override
  public void push32( long value ) {
    throw new UnsupportedOperationException(
        "NativeCpuBackend.push32 not implemented yet (issue #221 Phase 0 step 3+)" );
  }

  @Override
  public int pop32() {
    throw new UnsupportedOperationException(
        "NativeCpuBackend.pop32 not implemented yet (issue #221 Phase 0 step 3+)" );
  }

  @Override
  public void set_signal_handler( long _ip, long goto_adrs ) {
    // step 3+: vCPU regs を WHvSetVirtualProcessorRegisters で書き換え、red zone
    //   尊重で push_pc → set RIP = goto_adrs。本 stub では throw。
    throw new UnsupportedOperationException(
        "NativeCpuBackend.set_signal_handler not implemented yet (issue #221 Phase 0 step 3+; "
        + "vCPU regs を WHvSetVirtualProcessorRegisters で書き換える)" );
  }

  @Override
  public boolean is_interrupt_done() {
    // step 3+: VM-exit から復帰したら true を返す。本 stub では常に false で
    //   signal 配信ループから抜けさせる。
    return false;
  }

  @Override
  public long eval() {
    // step 3+: VM-exit loop (WHvRunVirtualProcessor) を回し、syscall exit が
    //   出るたびに SyscallAmd64.call_amd64 に dispatch する。本 stub では throw。
    throw new UnsupportedOperationException(
        "NativeCpuBackend.eval (VM-exit loop) not implemented yet (issue #221 Phase 0 step 3+; "
        + "WHvRunVirtualProcessor + syscall trap dispatch)" );
  }

  @Override
  public void fetch( long address, byte[] buf ) {
    // step 3+: native backend では命令 fetch は vCPU が直接行うので Java 側に
    //   to come ない。debug 用に WHvMapGpaRange の backing を direct read する
    //   可能性はある。本 stub では throw。
    throw new UnsupportedOperationException(
        "NativeCpuBackend.fetch not implemented yet (issue #221 Phase 0 step 3+; "
        + "本来 vCPU が直接実行するので Java 側 fetch は不要、debug 用 reader だけ)" );
  }

  @Override
  public void connect_devices( Memory _mem, Syscall _syscall ) {
    // step 3+: ここで WHvMapGpaRange を呼んで Memory の byte[] backing を
    //   guest 物理に map する。本 stub では参照保持のみ。
    mem     = _mem;
    syscall = _syscall;
    syscall.connect_mem( _mem );
  }

  // disasm / trace 系: native では命令 stream を Java 側で持たないため、
  //   全て "<native>" placeholder。

  @Override public String reg_str()                  { return "<native vCPU regs not synced>"; }
  @Override public String ip_str()                   { return "<native rip>"; }
  @Override public String flag_str()                 { return "<native rflags>"; }
  @Override public String disasm_str( long address ) { return "<native disasm not supported>"; }

  // spawnVCpu は AbstractCpu の default (UnsupportedOperationException) を
  //   そのまま使う。step 3+ で「同 partition に追加 vCPU を作る」実装で override。
}
