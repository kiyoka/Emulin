// ----------------------------------------
//  HvVcpu の Linux KVM 実装 (issue #221 WHP 移植 step 1)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// KVM (/dev/kvm) 上の 1 vCPU を HvVcpu として実装する。NativeCpuBackend.setupVcpu / kvmRun /
// reg/setReg / setSeg / setMsrs / captureFpuSnapshot / applyFpuSnapshot に散っていた KVM ioctl を
// ここへ集約し、NativeCpuBackend を hypervisor 中立にする。
//
//   - register は kvm_regs (144 byte) struct。HvReg 論理索引 → offset = index*8 (kvm_regs layout が
//     RAX..R15,RIP,RFLAGS の 8 byte 刻みなので素朴変換、KvmBindings.KVM_REGS_OFF_* と等価)。
//   - configureLongModeRing3 は kvm_sregs を GET → segment/CR/EFER を書く → SET。
//   - run は KVM_RUN を EINTR retry 付きで回し、kvm_run.exit_reason==KVM_EXIT_HLT を EXIT_HALT に正規化。
//   - FPU は kvm_fpu (416 byte) を不透明 blob として GET/SET。
//
// VM (kvmFd/vmFd) と guest RAM は NativeCpuBackend (将来 HvVm) が所有する。本 class は vcpuFd /
// vcpu-state mmap / 制御 struct (regs/fpu/sregs/cpuid/msr buffer) だけを所有・解放する。
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

final class KvmVcpu implements HvVcpu {

  private final int           kvmFd;        // system fd (KVM_GET_SUPPORTED_CPUID 用)
  private final int           vcpuFd;
  private final int           vcpuMmapSize;
  private final MemorySegment vcpuState;    // kvm_run mmap (exit_reason を読む)
  private final MemorySegment regsBuf;      // kvm_regs (144、GPR cache、再利用)
  private final MemorySegment fpuBuf;       // kvm_fpu  (416、x87/XMM 退避復元、再利用)
  private final Arena         arena;        // 制御 struct の生存域
  private final boolean       ownArena;     // true = close() で arena.close (worker)。false = NativeCpuBackend が所有 (main)。
  private int                 lastRawExit;

  /**
   * vmFd 上に vCPU を作る (KVM_CREATE_VCPU + vcpu-state mmap)。実行する thread 上で構築すること
   *   (KVM_RUN/GET/SET は単一 thread からのみ)。
   * @param arena    制御 struct (regs/fpu/sregs/cpuid/msr) を確保する Arena
   * @param ownArena true なら close() で arena を閉じる (worker の専用 arena)。main は false (NativeCpuBackend が arena を所有)。
   */
  KvmVcpu( int kvmFd, int vmFd, int vcpuId, Arena arena, boolean ownArena ) throws Throwable {
    this.kvmFd    = kvmFd;
    this.arena    = arena;
    this.ownArena = ownArena;

    int fd = KvmBindings.ioctl( vmFd, KvmBindings.KVM_CREATE_VCPU, MemorySegment.ofAddress( vcpuId ) );
    if( fd < 0 )
      throw new IllegalStateException( "KVM_CREATE_VCPU(id=" + vcpuId + ") errno=" + KvmBindings.errno() );
    // ★ exception-safe: KVM_CREATE_VCPU 成功後に後続 (mmap_size/mmap/allocate) が throw した場合、
    //   コンストラクタが正常 return せず caller の `hv = new KvmVcpu(...)` 代入が成立しない →
    //   teardown の `if(hv!=null) hv.close()` が発火せず open 済 vcpuFd / vcpu-state mmap が leak する。
    //   旧 NativeCpuBackend は資源を field 追跡し teardown が常に回収していたので、ここで self-clean して
    //   その堅牢性を維持する (review #LOW)。失敗 path 限定なので success path の挙動は不変。
    int mmapSz = 0; MemorySegment st = null, rb, fb;
    try {
      mmapSz = KvmBindings.ioctl( kvmFd, KvmBindings.KVM_GET_VCPU_MMAP_SIZE, MemorySegment.NULL );
      st = KvmBindings.mmap( MemorySegment.NULL, mmapSz,
          KvmBindings.PROT_READ | KvmBindings.PROT_WRITE, KvmBindings.MAP_SHARED, fd, 0L );
      if( st.address() == -1L || st.address() == 0L )
        throw new IllegalStateException( "mmap(vcpu_state) errno=" + KvmBindings.errno() );
      st = st.reinterpret( mmapSz );
      rb = arena.allocate( KvmBindings.KVM_REGS_SIZE );
      fb = arena.allocate( KvmBindings.KVM_FPU_SIZE );
    } catch( Throwable t ) {
      try { if( st != null && st.address() > 0L ) KvmBindings.munmap( st, mmapSz ); } catch( Throwable ignore ) {}
      try { KvmBindings.close( fd ); } catch( Throwable ignore ) {}
      throw t;
    }
    vcpuFd       = fd;
    vcpuMmapSize = mmapSz;
    vcpuState    = st;
    regsBuf      = rb;
    fpuBuf       = fb;
  }

  // HvReg 論理索引 → kvm_regs byte offset (layout が 8 byte 刻みなので index*8)。
  private static int off( int reg ) { return reg * 8; }

  // ===== GPR cache =====
  @Override public void readGprs()  throws Throwable { ioctl( KvmBindings.KVM_GET_REGS, regsBuf, "KVM_GET_REGS" ); }
  @Override public void writeGprs() throws Throwable { ioctl( KvmBindings.KVM_SET_REGS, regsBuf, "KVM_SET_REGS" ); }
  @Override public long getGpr( int reg )            { return regsBuf.get( ValueLayout.JAVA_LONG, off( reg ) ); }
  @Override public void setGpr( int reg, long v )    { regsBuf.set( ValueLayout.JAVA_LONG, off( reg ), v ); }

  // ===== CPUID =====
  @Override public void setCpuidFromHost() throws Throwable {
    int maxEnt = 256;
    MemorySegment cpuid = arena.allocate( KvmBindings.KVM_CPUID2_OFF_ENTRIES
        + (long) maxEnt * KvmBindings.KVM_CPUID_ENTRY_SIZE );
    cpuid.set( ValueLayout.JAVA_INT, KvmBindings.KVM_CPUID2_OFF_NENT, maxEnt );
    ioctlFd( kvmFd,  KvmBindings.KVM_GET_SUPPORTED_CPUID, cpuid, "KVM_GET_SUPPORTED_CPUID" );
    ioctlFd( vcpuFd, KvmBindings.KVM_SET_CPUID2,          cpuid, "KVM_SET_CPUID2" );
  }

  // ===== sregs (long mode ring 3) =====
  @Override
  public void configureLongModeRing3( long csSel, long dataSel, int dpl,
                                      long cr0, long cr3, long cr4, long efer ) throws Throwable {
    MemorySegment sregs = arena.allocate( KvmBindings.KVM_SREGS_SIZE );
    ioctl( KvmBindings.KVM_GET_SREGS, sregs, "KVM_GET_SREGS" );
    setSeg( sregs, KvmBindings.KVM_SREGS_OFF_CS, (int) csSel, KvmBindings.SEG_TYPE_CODE, /*db*/0, /*l*/1, dpl );
    for( int o : new int[]{ KvmBindings.KVM_SREGS_OFF_DS, KvmBindings.KVM_SREGS_OFF_ES,
                            KvmBindings.KVM_SREGS_OFF_FS, KvmBindings.KVM_SREGS_OFF_GS,
                            KvmBindings.KVM_SREGS_OFF_SS } )
      setSeg( sregs, o, (int) dataSel, KvmBindings.SEG_TYPE_DATA, /*db*/1, /*l*/0, dpl );
    sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_CR0,  cr0 );
    sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_CR3,  cr3 );
    sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_CR4,  cr4 );
    sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_EFER, efer );
    ioctl( KvmBindings.KVM_SET_SREGS, sregs, "KVM_SET_SREGS" );
  }

  // 診断用 (issue #339): KVM_GET_SREGS で CS selector を読み CPL (下位2bit) を返す。
  @Override public long getCpl() throws Throwable {
    MemorySegment sregs = arena.allocate( KvmBindings.KVM_SREGS_SIZE );
    ioctl( KvmBindings.KVM_GET_SREGS, sregs, "KVM_GET_SREGS" );
    int csSel = sregs.get( ValueLayout.JAVA_SHORT,
        KvmBindings.KVM_SREGS_OFF_CS + KvmBindings.KVM_SEG_OFF_SELECTOR ) & 0xFFFF;
    return csSel & 3;
  }

  // ===== 例外配送 (issue #392 戦略B #PF demand paging) =====
  @Override public long getCr2() throws Throwable {
    MemorySegment sregs = arena.allocate( KvmBindings.KVM_SREGS_SIZE );
    ioctl( KvmBindings.KVM_GET_SREGS, sregs, "KVM_GET_SREGS" );
    return sregs.get( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_CR2 );
  }

  @Override
  public void configureExceptionTables( long gdtBase, int gdtLimit, long idtBase, int idtLimit,
                                        int trSel, long trBase, int trLimit ) throws Throwable {
    MemorySegment sregs = arena.allocate( KvmBindings.KVM_SREGS_SIZE );
    ioctl( KvmBindings.KVM_GET_SREGS, sregs, "KVM_GET_SREGS" );
    // GDTR / IDTR (kvm_dtable: base u64@0, limit u16@8)
    sregs.set( ValueLayout.JAVA_LONG,  KvmBindings.KVM_SREGS_OFF_GDT, gdtBase );
    sregs.set( ValueLayout.JAVA_SHORT, KvmBindings.KVM_SREGS_OFF_GDT + KvmBindings.KVM_DTABLE_OFF_LIMIT, (short) gdtLimit );
    sregs.set( ValueLayout.JAVA_LONG,  KvmBindings.KVM_SREGS_OFF_IDT, idtBase );
    sregs.set( ValueLayout.JAVA_SHORT, KvmBindings.KVM_SREGS_OFF_IDT + KvmBindings.KVM_DTABLE_OFF_LIMIT, (short) idtLimit );
    // TR = 64-bit busy TSS (type=11、S=0=system descriptor)。RSP0 で ring3→ring0 の stack 切替。
    setSegSys( sregs, KvmBindings.KVM_SREGS_OFF_TR, trSel, /*type*/11, trBase, trLimit );
    ioctl( KvmBindings.KVM_SET_SREGS, sregs, "KVM_SET_SREGS" );
  }

  // TSS/LDT 等の system descriptor 用 (setSeg は S=1 固定なので S=0 版)。
  private void setSegSys( MemorySegment sregs, int o, int sel, int type, long base, long limit ) {
    sregs.set( ValueLayout.JAVA_LONG,  o + KvmBindings.KVM_SEG_OFF_BASE,     base );
    sregs.set( ValueLayout.JAVA_INT,   o + KvmBindings.KVM_SEG_OFF_LIMIT,    (int) limit );
    sregs.set( ValueLayout.JAVA_SHORT, o + KvmBindings.KVM_SEG_OFF_SELECTOR, (short) sel );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_TYPE,     (byte) type );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_PRESENT,  (byte) 1 );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_DPL,      (byte) 0 );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_DB,       (byte) 0 );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_S,        (byte) 0 );  // system descriptor
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_L,        (byte) 0 );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_G,        (byte) 0 );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_AVL,      (byte) 0 );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_UNUSABLE, (byte) 0 );
  }

  private void setSeg( MemorySegment sregs, int o, int sel, int type, int db, int l, int dpl ) {
    sregs.set( ValueLayout.JAVA_LONG,  o + KvmBindings.KVM_SEG_OFF_BASE,     0L );
    sregs.set( ValueLayout.JAVA_INT,   o + KvmBindings.KVM_SEG_OFF_LIMIT,    0xFFFFF );
    sregs.set( ValueLayout.JAVA_SHORT, o + KvmBindings.KVM_SEG_OFF_SELECTOR, (short) sel );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_TYPE,     (byte) type );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_PRESENT,  (byte) 1 );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_DPL,      (byte) dpl );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_DB,       (byte) db );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_S,        (byte) 1 );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_L,        (byte) l );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_G,        (byte) 1 );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_AVL,      (byte) 0 );
    sregs.set( ValueLayout.JAVA_BYTE,  o + KvmBindings.KVM_SEG_OFF_UNUSABLE, (byte) 0 );
  }

  // ===== MSRs =====
  @Override
  public void setMsrs( long[][] msrs ) throws Throwable {
    int n = msrs.length;
    MemorySegment buf = arena.allocate( KvmBindings.KVM_MSRS_OFF_ENTRIES + (long) n * KvmBindings.KVM_MSR_ENTRY_SIZE );
    buf.set( ValueLayout.JAVA_INT, KvmBindings.KVM_MSRS_OFF_NMSRS, n );
    for( int i = 0; i < n; i++ ) {
      long e = KvmBindings.KVM_MSRS_OFF_ENTRIES + (long) i * KvmBindings.KVM_MSR_ENTRY_SIZE;
      buf.set( ValueLayout.JAVA_INT,  e + KvmBindings.KVM_MSR_ENTRY_OFF_INDEX, (int) msrs[i][0] );
      buf.set( ValueLayout.JAVA_LONG, e + KvmBindings.KVM_MSR_ENTRY_OFF_DATA,  msrs[i][1] );
    }
    int rc = KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_SET_MSRS, buf );
    if( rc != n ) throw new IllegalStateException( "KVM_SET_MSRS rc=" + rc + " errno=" + KvmBindings.errno() );
  }

  // ===== run =====
  @Override
  public int run() throws Throwable {
    int rc = KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_RUN, MemorySegment.NULL );
    if( rc < 0 ) {
      int e = KvmBindings.errno();
      // EINTR (4): host signal で KVM_RUN が割込まれた。GC/safepoint signal でも step 3d-2c-39 の
      //   SIG_KICK (走行中 vCPU への async 配信用) でも起きる。eval に EXIT_INTR を返し pending guest
      //   signal を確認させる (retry はそこから)。旧実装は EINTR を内部 retry し guest signal を無視していた。
      if( e == 4 ) { lastRawExit = KvmBindings.KVM_EXIT_INTR; return EXIT_INTR; }
      throw new IllegalStateException( "KVM_RUN errno=" + e );
    }
    lastRawExit = vcpuState.get( ValueLayout.JAVA_INT, KvmBindings.KVM_RUN_OFF_EXIT_REASON );
    if( lastRawExit == KvmBindings.KVM_EXIT_HLT )  return EXIT_HALT;
    if( lastRawExit == KvmBindings.KVM_EXIT_INTR ) return EXIT_INTR;  // rc>=0 で signal exit する経路
    return EXIT_OTHER;
  }
  @Override public int lastRawExit() { return lastRawExit; }

  // ===== ring-3 遷移 (async signal の rt_sigreturn 用、step 3d-2c-39) =====
  @Override
  public void exitToRing3() throws Throwable {
    MemorySegment sregs = arena.allocate( KvmBindings.KVM_SREGS_SIZE );
    ioctl( KvmBindings.KVM_GET_SREGS, sregs, "KVM_GET_SREGS" );
    // CS = ring3 code64 (0x33、L=1、DPL=3)、SS = ring3 data (0x2b、DPL=3)。DS/ES/FS/GS は据置
    //   (base=0 で user 到達可、syscall で壊れない)。CR/EFER も触らない。
    setSeg( sregs, KvmBindings.KVM_SREGS_OFF_CS, 0x33, KvmBindings.SEG_TYPE_CODE, /*db*/0, /*l*/1, /*dpl*/3 );
    setSeg( sregs, KvmBindings.KVM_SREGS_OFF_SS, 0x2b, KvmBindings.SEG_TYPE_DATA, /*db*/1, /*l*/0, /*dpl*/3 );
    ioctl( KvmBindings.KVM_SET_SREGS, sregs, "KVM_SET_SREGS" );
  }

  // ===== FPU =====
  @Override
  public byte[] getFpu() throws Throwable {
    ioctl( KvmBindings.KVM_GET_FPU, fpuBuf, "KVM_GET_FPU" );
    byte[] snap = new byte[ KvmBindings.KVM_FPU_SIZE ];
    MemorySegment.copy( fpuBuf, ValueLayout.JAVA_BYTE, 0, snap, 0, KvmBindings.KVM_FPU_SIZE );
    return snap;
  }
  @Override
  public void setFpu( byte[] snap ) throws Throwable {
    MemorySegment.copy( snap, 0, fpuBuf, ValueLayout.JAVA_BYTE, 0, KvmBindings.KVM_FPU_SIZE );
    ioctl( KvmBindings.KVM_SET_FPU, fpuBuf, "KVM_SET_FPU" );
  }

  // ===== teardown =====
  @Override
  public void close() {
    try {
      if( vcpuState != null ) KvmBindings.munmap( vcpuState, vcpuMmapSize );
      if( vcpuFd >= 0 ) KvmBindings.close( vcpuFd );
      if( ownArena && arena != null ) arena.close();
    } catch( Throwable ignore ) {}
  }

  // ===== helper =====
  private void ioctl( long req, MemorySegment arg, String name ) throws Throwable { ioctlFd( vcpuFd, req, arg, name ); }
  private static void ioctlFd( int fd, long req, MemorySegment arg, String name ) throws Throwable {
    if( KvmBindings.ioctl( fd, req, arg ) < 0 )
      throw new IllegalStateException( name + " errno=" + KvmBindings.errno() );
  }
}
