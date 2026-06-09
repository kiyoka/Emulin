// ----------------------------------------
//  HvVcpu の Windows Hypervisor Platform (WHP) 実装 (issue #221 WHP 移植 Stage 3)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// WHP partition 上の 1 vCPU を HvVcpu として実装する。KvmVcpu (KVM 経路) と同じ interface を満たし、
// NativeCpuBackend は両者を区別しない。WhpSyscallSmoke64 (Windows 実機で long mode + ring-3 syscall trap
// 実証済み、#270) で確認した WHP 呼び出し列を HvVcpu の形に再構成したもの。
//
// ★ register normalize layer の核心: WHV_REGISTER_NAME の GPR 並び (Rax,Rcx,Rdx,Rbx,Rsp,Rbp,Rsi,Rdi,
//   R8..R15) は kvm_regs / HvReg の並び (Rax,Rbx,Rcx,Rdx,Rsi,Rdi,Rsp,Rbp,R8..) と異なるので、HvReg 索引
//   → WHV_REGISTER_NAME の写像表 (WHV_GPR_NAME) で変換する。KvmVcpu が offset=idx*8 で済むのに対し、WHP は
//   この表が必要 = 抽象化が存在する理由そのもの。
//
// register アクセスは cache モデル: readGprs() で WHvGetVirtualProcessorRegisters により全 GPR を gprVals
//   (18×16byte) に読み込み、getGpr/setGpr が gprVals を読み書き、writeGprs() で WHvSetVirtualProcessorRegisters
//   する (KVM_GET_REGS/KVM_SET_REGS と同じ 1 回ずつ)。
//
// ★ WHP は Windows + Hyper-V でしか実行できない。Linux では HvVm.create() が KvmVm を選ぶので本 class は
//   instantiate されない (= KVM oracle に無影響)。Linux ではコンパイルのみ、実機検証は Windows で行う。
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

final class WhpVcpu implements HvVcpu {

  // HvReg 索引 (RAX..RFLAGS = 0..17) → WHV_REGISTER_NAME の写像。★ 並びが kvm_regs と違う点が核心。
  private static final int[] WHV_GPR_NAME = {
      WhpBindings.WHvX64RegisterRax,    // HvReg.RAX = 0
      WhpBindings.WHvX64RegisterRbx,    // RBX = 1
      WhpBindings.WHvX64RegisterRcx,    // RCX = 2
      WhpBindings.WHvX64RegisterRdx,    // RDX = 3
      WhpBindings.WHvX64RegisterRsi,    // RSI = 4
      WhpBindings.WHvX64RegisterRdi,    // RDI = 5
      WhpBindings.WHvX64RegisterRsp,    // RSP = 6
      WhpBindings.WHvX64RegisterRbp,    // RBP = 7
      WhpBindings.WHvX64RegisterR8,     // R8  = 8
      WhpBindings.WHvX64RegisterR9,     // R9  = 9
      WhpBindings.WHvX64RegisterR10,    // R10 = 10
      WhpBindings.WHvX64RegisterR11,    // R11 = 11
      WhpBindings.WHvX64RegisterR12,    // R12 = 12
      WhpBindings.WHvX64RegisterR13,    // R13 = 13
      WhpBindings.WHvX64RegisterR14,    // R14 = 14
      WhpBindings.WHvX64RegisterR15,    // R15 = 15
      WhpBindings.WHvX64RegisterRip,    // RIP = 16
      WhpBindings.WHvX64RegisterRflags, // RFLAGS = 17
  };
  private static final int REGVAL = WhpBindings.WHV_REGISTER_VALUE_SIZE;  // 16

  // x86 architectural MSR 番号 (NativeCpuBackend が setMsrs に渡す index。WHP では WHV register に写像)。
  //   KvmBindings への依存を避けるため local 定義 (値は arch で同一)。
  private static final int MSR_EFER = 0xC0000080, MSR_STAR = 0xC0000081, MSR_LSTAR = 0xC0000082,
                           MSR_SFMASK = 0xC0000084, MSR_FS_BASE = 0xC0000100, MSR_GS_BASE = 0xC0000101;

  private final MemorySegment partition;
  private final int           vpIndex;
  private final Arena         arena;
  private final boolean       ownArena;

  private final MemorySegment gprNames;   // 18 × u32 (WHV register name、固定)
  private final MemorySegment gprVals;    // 18 × 16byte (GPR cache)
  private final MemorySegment exitCtx;    // WHV_RUN_VP_EXIT_CONTEXT (144)
  private final MemorySegment fpuNames;   // 16 × u32 (Xmm0..15 の WHV name、固定)。signal hot path で再利用
  private final MemorySegment fpuVals;    // 16 × 16byte (XMM cache)
  private int                 lastRawExit;

  // FS/GS base 設定時に segment 全体を書き直すための保存値 (configureLongModeRing3 で確定)。
  private int  dataSel  = 0x2b;
  private int  dataAttr = WhpBindings.WHV_SEG_ATTR_DATA;

  WhpVcpu( MemorySegment partition, int vpIndex, Arena arena, boolean ownArena ) throws Throwable {
    this.partition = partition;
    this.vpIndex   = vpIndex;
    this.arena     = arena;
    this.ownArena  = ownArena;

    boolean ok = false;
    try {
      hr( "WHvCreateVirtualProcessor(" + vpIndex + ")",
          (int) WhpBindings.createVirtualProcessor().invoke( partition, vpIndex, 0 ) );
      gprNames = arena.allocate( (long) WHV_GPR_NAME.length * 4 );
      for( int i = 0; i < WHV_GPR_NAME.length; i++ ) gprNames.set( ValueLayout.JAVA_INT, (long) i * 4, WHV_GPR_NAME[i] );
      gprVals = arena.allocate( (long) WHV_GPR_NAME.length * REGVAL );
      exitCtx = arena.allocate( WhpBindings.WHV_RUN_VP_EXIT_CONTEXT_SIZE );
      // FPU (XMM0-15) の name/value buffer は signal hot path (getFpu/setFpu = 各 signal 配信/rt_sigreturn)
      //   で呼ばれるので、毎回 allocate せず ctor で 1 度確保して再利用する (KvmVcpu の fpuBuf 同様。
      //   さもないと process 寿命の arena に ~640byte/signal が溜まる、review #LOW)。
      fpuNames = arena.allocate( (long) XMM_N * 4 );
      for( int i = 0; i < XMM_N; i++ ) fpuNames.set( ValueLayout.JAVA_INT, (long) i * 4, WhpBindings.WHvX64RegisterXmm0 + i );
      fpuVals = arena.allocate( (long) XMM_N * REGVAL );
      ok = true;
    } finally {
      // exception-safe: VP 作成後の allocate 失敗等で VP を leak しない。
      if( !ok ) { try { WhpBindings.deleteVirtualProcessor().invoke( partition, vpIndex ); } catch( Throwable ignore ) {} }
    }
  }

  // ===== GPR cache =====
  @Override public void readGprs() throws Throwable {
    hr( "WHvGetVirtualProcessorRegisters", (int) WhpBindings.getVirtualProcessorRegisters()
        .invoke( partition, vpIndex, gprNames, WHV_GPR_NAME.length, gprVals ) );
  }
  @Override public void writeGprs() throws Throwable {
    hr( "WHvSetVirtualProcessorRegisters", (int) WhpBindings.setVirtualProcessorRegisters()
        .invoke( partition, vpIndex, gprNames, WHV_GPR_NAME.length, gprVals ) );
  }
  @Override public long getGpr( int reg )         { return gprVals.get( ValueLayout.JAVA_LONG, (long) reg * REGVAL ); }
  @Override public void setGpr( int reg, long v ) { gprVals.set( ValueLayout.JAVA_LONG, (long) reg * REGVAL, v ); }

  // ===== CPUID =====
  @Override public void setCpuidFromHost() throws Throwable {
    // WHP partition は既定で host 由来の CPUID を概ね提示する。glibc の CPU ISA level check に host 機能を
    //   完全に通すには WHvSetPartitionProperty(CpuidResultList/CpuidExitList) + X64Cpuid exit 処理が要るが、
    //   -nostdlib 静的 binary (hello64 系、Windows 実機検証の初期 target) では CPUID を引かないので no-op。
    //   ★ glibc binary の WHP 対応は Windows bring-up での follow-up (要 CpuidResultList 設定)。
  }

  // ===== sregs (long mode ring 3) =====
  @Override
  public void configureLongModeRing3( long csSel, long dataSel, int dpl,
                                      long cr0, long cr3, long cr4, long efer ) throws Throwable {
    // segment attribute は DPL を bits[5:6] に持つ。WHV_SEG_ATTR_CODE64/DATA は DPL=0 なので (dpl<<5) を OR。
    int codeAttr = WhpBindings.WHV_SEG_ATTR_CODE64 | ( dpl << 5 );
    int dataAttr = WhpBindings.WHV_SEG_ATTR_DATA   | ( dpl << 5 );
    this.dataSel  = (int) dataSel;       // FS/GS base 更新 (setMsrs) 時に segment 全体を書き直す用に保存
    this.dataAttr = dataAttr;

    int[] names = {
        WhpBindings.WHvX64RegisterCr0,  WhpBindings.WHvX64RegisterCr3,  WhpBindings.WHvX64RegisterCr4,
        WhpBindings.WHvX64RegisterEfer, WhpBindings.WHvX64RegisterCs,   WhpBindings.WHvX64RegisterSs,
        WhpBindings.WHvX64RegisterDs,   WhpBindings.WHvX64RegisterEs,   WhpBindings.WHvX64RegisterFs,
        WhpBindings.WHvX64RegisterGs,
    };
    int n = names.length;
    MemorySegment nm = arena.allocate( (long) n * 4 );
    MemorySegment vl = arena.allocate( (long) n * REGVAL );
    for( int i = 0; i < n; i++ ) nm.set( ValueLayout.JAVA_INT, (long) i * 4, names[i] );
    u64( vl, 0, cr0 );  u64( vl, 1, cr3 );  u64( vl, 2, cr4 );  u64( vl, 3, efer );
    seg( vl, 4, (int) csSel,   codeAttr, 0L );   // Cs (code64)
    seg( vl, 5, (int) dataSel, dataAttr, 0L );   // Ss
    seg( vl, 6, (int) dataSel, dataAttr, 0L );   // Ds
    seg( vl, 7, (int) dataSel, dataAttr, 0L );   // Es
    seg( vl, 8, (int) dataSel, dataAttr, 0L );   // Fs (base 0、後で arch_prctl が setMsrs(FS_BASE) で更新)
    seg( vl, 9, (int) dataSel, dataAttr, 0L );   // Gs
    hr( "WHvSetVirtualProcessorRegisters(sregs)", (int) WhpBindings.setVirtualProcessorRegisters()
        .invoke( partition, vpIndex, nm, n, vl ) );
  }

  // ===== MSRs =====
  @Override
  public void setMsrs( long[][] msrs ) throws Throwable {
    int n = msrs.length;
    MemorySegment nm = arena.allocate( (long) n * 4 );
    MemorySegment vl = arena.allocate( (long) n * REGVAL );
    for( int i = 0; i < n; i++ ) {
      int idx = (int) msrs[i][0];
      long v  = msrs[i][1];
      int name;
      switch( idx ) {
        case MSR_STAR:   name = WhpBindings.WHvX64RegisterStar;   u64( vl, i, v ); break;
        case MSR_LSTAR:  name = WhpBindings.WHvX64RegisterLstar;  u64( vl, i, v ); break;
        case MSR_SFMASK: name = WhpBindings.WHvX64RegisterSfmask; u64( vl, i, v ); break;
        case MSR_EFER:   name = WhpBindings.WHvX64RegisterEfer;   u64( vl, i, v ); break;
        // FS/GS base は WHP では segment register の Base field。selector/attr を保ったまま base を更新する。
        case MSR_FS_BASE: name = WhpBindings.WHvX64RegisterFs; seg( vl, i, dataSel, dataAttr, v ); break;
        case MSR_GS_BASE: name = WhpBindings.WHvX64RegisterGs; seg( vl, i, dataSel, dataAttr, v ); break;
        default: throw new IllegalStateException( "WhpVcpu.setMsrs: 未対応 MSR index=0x" + Integer.toHexString( idx ) );
      }
      nm.set( ValueLayout.JAVA_INT, (long) i * 4, name );
    }
    hr( "WHvSetVirtualProcessorRegisters(msrs)", (int) WhpBindings.setVirtualProcessorRegisters()
        .invoke( partition, vpIndex, nm, n, vl ) );
  }

  // ===== run =====
  @Override
  public int run() throws Throwable {
    hr( "WHvRunVirtualProcessor", (int) WhpBindings.runVirtualProcessor()
        .invoke( partition, vpIndex, exitCtx, WhpBindings.WHV_RUN_VP_EXIT_CONTEXT_SIZE ) );
    lastRawExit = exitCtx.get( ValueLayout.JAVA_INT, WhpBindings.WHV_EXIT_OFF_REASON );
    return lastRawExit == WhpBindings.WHvRunVpExitReasonX64Halt ? EXIT_HALT : EXIT_OTHER;
  }
  @Override public int lastRawExit() { return lastRawExit; }

  // ===== FPU (XMM0-15、signal 退避復元) =====
  //   ★ KvmVcpu は kvm_fpu (x87/XMM/MXCSR、416byte) を扱うが、WHP では XMM0-15 (16×16=256byte) を
  //   Get/SetRegisters で退避復元する。getFpu/setFpu は同一 backend 上の opaque snapshot/restore なので
  //   format が WHP 内で自己完結していれば良い (sys_sig_fpu64 の xmm 保持を満たす)。x87/MMX/MXCSR の完全
  //   カバーは Windows bring-up での follow-up。
  private static final int XMM_N = 16, FPU_BLOB = XMM_N * REGVAL;   // 256
  @Override
  public byte[] getFpu() throws Throwable {
    hr( "WHvGetVirtualProcessorRegisters(xmm)", (int) WhpBindings.getVirtualProcessorRegisters()
        .invoke( partition, vpIndex, fpuNames, XMM_N, fpuVals ) );
    byte[] snap = new byte[ FPU_BLOB ];
    MemorySegment.copy( fpuVals, ValueLayout.JAVA_BYTE, 0, snap, 0, FPU_BLOB );
    return snap;
  }
  @Override
  public void setFpu( byte[] snap ) throws Throwable {
    MemorySegment.copy( snap, 0, fpuVals, ValueLayout.JAVA_BYTE, 0, Math.min( snap.length, FPU_BLOB ) );
    hr( "WHvSetVirtualProcessorRegisters(xmm)", (int) WhpBindings.setVirtualProcessorRegisters()
        .invoke( partition, vpIndex, fpuNames, XMM_N, fpuVals ) );
  }

  // ===== teardown =====
  @Override
  public void close() {
    try { WhpBindings.deleteVirtualProcessor().invoke( partition, vpIndex ); } catch( Throwable ignore ) {}
    if( ownArena && arena != null ) { try { arena.close(); } catch( Throwable ignore ) {} }
  }

  // ===== helper =====
  private void u64( MemorySegment a, int idx, long v ) { a.set( ValueLayout.JAVA_LONG, (long) idx * REGVAL, v ); }
  // WHV_X64_SEGMENT_REGISTER (16byte): Base@0(u64), Limit@8(u32), Selector@12(u16), Attributes@14(u16)。
  private void seg( MemorySegment a, int idx, int selector, int attr, long base ) {
    long off = (long) idx * REGVAL;
    a.set( ValueLayout.JAVA_LONG,  off + WhpBindings.WHV_SEG_OFF_BASE,       base );
    a.set( ValueLayout.JAVA_INT,   off + WhpBindings.WHV_SEG_OFF_LIMIT,      0xFFFFFFFF );
    a.set( ValueLayout.JAVA_SHORT, off + WhpBindings.WHV_SEG_OFF_SELECTOR,   (short) selector );
    a.set( ValueLayout.JAVA_SHORT, off + WhpBindings.WHV_SEG_OFF_ATTRIBUTES, (short) attr );
  }
  private static void hr( String op, int hresult ) {
    if( hresult != 0 ) throw new IllegalStateException( op + " HRESULT=0x" + Integer.toHexString( hresult ) );
  }
}
