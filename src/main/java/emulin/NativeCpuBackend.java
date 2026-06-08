// ----------------------------------------
//  Native CPU backend — Linux KVM 経路 (issue #221 Phase 0 step 3d-2 / 3d-2c)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// x86-64 guest を実 vCPU (KVM) でネイティブ実行し、`syscall` 命令だけを VM-exit で
// emulin の Java 層 (`SyscallAmd64.call_amd64`) にトラップする native backend。
// software emulator (Cpu64) と並立する第一級モード (#221 §1)。
//
// step 3d-2c MVP の範囲: `-nostdlib` 静的 ELF (hello64 等、ET_EXEC、固定 vaddr) を
//   ring 3 long mode で実行し、write/exit 系 syscall を call_amd64 に流して
//   software backend と同じ stdout を出す (oracle)。
//   - guest 物理 RAM = off-heap `NativeMemoryBackend` (#221 step 3d-1)。
//   - ELF segment は connect_devices で software Memory から guest RAM に identity copy。
//   - syscall.connect_mem(guestMem) で syscall 層の `mem` を guest RAM に向ける
//     (#221 step 3d-2a で Syscall.mem を MemoryBackend に widen 済)。
//   - page table は user page (PD[1..7]) を US=1、page table 自身 + LSTAR stub を含む
//     低位 2MB (PD[0]) を US=0 (supervisor) に分離し ring-3 から隠す (3c review)。
//   - LSTAR に `hlt; sysretq` スタブ (方式 b) を置き、`syscall` (ring3→ring0)→hlt→
//     VM-exit→call_amd64→RAX に戻り値→RIP=sysretq で ring3 復帰。RCX (user 復帰先) /
//     R11 (退避 RFLAGS) は syscall hardware の退避値を保持 (sysretq が使う)。
//
// 範囲外 (step 3d-2c の続き): PIE/動的リンク (guest 仮想≠物理)、stack/auxv の guest RAM
//   配置、mmap/brk の guest 物理割当、signal/pthread、KVM_CAP_SYNC_REGS 最適化、
//   KVM_SET_SIGNAL_MASK、WHP 移植。
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class NativeCpuBackend extends AbstractCpu
{
  // ---- guest メモリ (非 identity MMU、issue #221 step 3d-2c-8) ----
  //   物理プールは off-heap、KVM が guest 物理 0 に map。NativeMemoryBackend が 4-level
  //   page table で疎な仮想 (0x400000 の ELF / 0x7ffff7... の ld.so・libc / 高位 stack) を
  //   compact な物理プールに乗せる。connect_devices は各 PT_LOAD を mapRange で物理割当 +
  //   page table 構築してから copy する。stack は software と同じ高位アドレスに置く。
  private static final long POOL_SIZE     = 64L * 1024 * 1024;  // 64MB 物理プール
  private static final long STUB_VADDR    = 0xff000L;           // LSTAR スタブの仮想 (supervisor)
  private static final long SYSRETQ_VADDR = STUB_VADDR + 1;     // stub 内 sysretq (hlt の次)
  // STAR: syscall→kernel CS=0x10/SS=0x18、sysretq→user CS=0x33/SS=0x2b (Linux 規約)。
  //   ★ SYSRETQ(64-bit) の selector 算術は非自明: user CS = STAR[63:48] + 16、
  //   user SS = STAR[63:48] + 8 (どちらも RPL=3)。よって user CS=0x33 を得るには
  //   STAR[63:48] に 0x33 でなく 0x23 (=0x33-16) を入れる。これは Linux の
  //   `(__USER32_CS=0x23)<<48 | (__KERNEL_CS=0x10)<<32` と同値。0x33 を入れると
  //   sysretq CS=0x43 になり壊れる (review で 6 agent が +16 を見落とし誤指摘した箇所)。
  private static final long STAR_VALUE = (0x23L << 48) | (0x10L << 32);

  // ---- state ----
  private long          entryRip;
  private long          rsp = 0;          // 初期 RSP (setup_initial_stack で確定)
  private SyscallAmd64  sys64;
  private NativeMemoryBackend guestMem;
  private Arena         arena;       // guest RAM の生存期間 (process 寿命)
  private boolean       kvmReady;

  // KVM handles
  private int           kvmFd = -1, vmFd = -1, vcpuFd = -1, vcpuMmapSize;
  private MemorySegment vcpuState;
  private MemorySegment regsBuf;     // KVM_GET/SET_REGS 用 (再利用)

  public NativeCpuBackend( Sysinfo _sysinfo, Process _process ) {
    sysinfo = _sysinfo;
    process = _process;
  }

  @Override public void init() { /* eval() で lazy 初期化 */ }

  @Override
  public AbstractCpu duplicate( Process _process ) {
    throw new UnsupportedOperationException(
        "NativeCpuBackend.duplicate (fork) not implemented yet (issue #221 step 3d-2c)" );
  }

  // ===== device 接続: ELF segment を guest RAM に写し、syscall mem を向ける =====

  @Override
  public void connect_devices( Memory _mem, Syscall _syscall ) {
    this.mem     = _mem;          // software Memory (segment[] / ELF メタ参照用)
    this.syscall = _syscall;
    this.sys64   = (SyscallAmd64) _syscall;

    // off-heap 物理プールを確保 (KVM が guest 物理 0 に map、process 寿命で leak 許容)。
    arena    = Arena.ofShared();
    guestMem = new NativeMemoryBackend( arena.allocate( POOL_SIZE, 4096 ) );
    guestMem.enableMmu();
    guestMem.setSyscall( _syscall );   // file-backed mmap (ld.so の .so map) 用

    boolean trace = System.getenv( "EMULIN_TRACE_BACKEND" ) != null;

    // ★ LSTAR スタブ (hlt; sysretq) を PT_LOAD ループの【前】に supervisor ページとして map する
    //   (review): 後だと、仮に PT_LOAD が stub ページ [STUB_VADDR..) に来た場合に先に user (US=1)
    //   で map され、mapSupervisor が既マップを skip して stub が ring-3 アクセス可のまま残る。
    //   先に supervisor 確定すれば、衝突する PT_LOAD は mapRange に skip され (data 未配置で loud
    //   に fault する) stub の supervisor 性は保たれる。STUB_VADDR=0xff000 は通常 binary
    //   (vaddr 0x400000+) と衝突しない低位の未使用域。
    guestMem.mapSupervisor( STUB_VADDR, 4 );
    guestMem.store8( STUB_VADDR + 0, 0xF4 );  // hlt
    guestMem.store8( STUB_VADDR + 1, 0x48 );  // sysretq (REX.W)
    guestMem.store8( STUB_VADDR + 2, 0x0F );
    guestMem.store8( STUB_VADDR + 3, 0x07 );

    // ELF の各 PT_LOAD segment を「仮想アドレスのまま」guest に配置: mapRange で物理ページを
    //   割当てて 4-level page table を構築し、software Memory から読んだ内容を copy する。
    //   非 identity なので 0x7ffff7... の ld.so / 高位 stack segment も収まる (16MB 制約撤廃)。
    //   stack segment (Elf が .stack で追加) も PT_LOAD なのでここで map される。
    for( int i = 0; i < _mem.segments; i++ ) {
      Segment seg = _mem.segment[i];
      if( seg == null ) continue;
      // software loader (Memory.java:559) と同じく PT_LOAD (p_type==1) のみ配置。
      if( seg.p_type != 1 /* PT_LOAD */ ) {
        if( trace ) System.err.println( "[native] skip non-PT_LOAD p_type=0x"
            + Integer.toHexString( seg.p_type ) + " vaddr=0x" + Long.toHexString( seg.p_vaddr ) );
        continue;
      }
      long va    = seg.p_vaddr;
      long memsz = seg.p_memsz;
      if( va < 0 || memsz < 0 || memsz > POOL_SIZE ) {   // 異常 segment は skip (防御)
        if( trace ) System.err.println( "[native] skip PT_LOAD vaddr=0x" + Long.toHexString( va )
            + " memsz=0x" + Long.toHexString( memsz ) + " (異常)" );
        continue;
      }
      guestMem.mapRange( va, memsz, true );              // 物理割当 + page table 構築 (user)
      if( seg.p_filesz > Integer.MAX_VALUE )
        fatalUnsupported( "PT_LOAD filesz=0x" + Long.toHexString( seg.p_filesz ) + " exceeds Integer.MAX_VALUE" );
      // ★ emulin は p_vaddr を page 境界に切り下げ buf を filesz+(p_offset&0xFFF) で確保する
      //   (Elf.java cacheSegments)。非 page-align segment の file-backed 末尾欠落を防ぐため
      //   software と同じ長さ (memsz で clamp) でコピーする (3d-2c-4 の page_offset 修正)。
      int page_off = (int)( seg.p_offset & 0xFFFL );
      int copyLen  = (int) Math.min( seg.p_filesz + (long) page_off, memsz );
      if( copyLen > 0 ) {
        byte[] tmp = new byte[ copyLen ];
        _mem.bulkLoadFromMem( va, tmp, 0, copyLen );     // software から読む (page prefix 込み)
        guestMem.bulkStoreToMem( va, tmp, 0, copyLen );  // guest 物理に書く (page table 経由)
      }
      // BSS は mapRange の物理ページが Arena で 0 初期化済なので追加不要
      if( trace ) System.err.println( "[native] mapped+copied PT_LOAD vaddr=0x" + Long.toHexString( va )
          + " len=0x" + Long.toHexString( copyLen ) + " memsz=0x" + Long.toHexString( memsz ) );
    }

    // 初期 brk を software Memory の ELF 由来 brk で seed (map はしない、brk 成長時に map)。
    long elfBrk = _mem.get_curbrk();
    if( elfBrk > 0 ) guestMem.seedBrk( elfBrk );
    if( trace ) System.err.println( "[native] initial brk = 0x" + Long.toHexString( guestMem.get_curbrk() ) );

    // ★ syscall 層の mem を guest RAM に向ける (amd64_write 等が guest buffer を読む)
    _syscall.connect_mem( guestMem );
    if( trace ) System.err.println( "[native] connect_devices done (非 identity MMU): pool @0x"
        + Long.toHexString( guestMem.address() ) + " size=0x" + Long.toHexString( POOL_SIZE ) );
  }

  // ===== register accessor (eval 前は field、eval 中は guest regs を反映する起点) =====

  @Override public void set_ip( long _ip )    { entryRip = _ip; }
  @Override public long get_ip()              { return entryRip; }
  @Override public void set_sp( long sp )     { rsp = sp; }
  @Override public long get_sp()              { return rsp; }
  @Override public void set_ax( int value )   { /* unused in MVP */ }

  // TLS の FS base (arch_prctl ARCH_SET_FS)。guest vCPU の MSR_FS_BASE を KVM 経由で更新する。
  //   eval ループの syscall trap ハンドラ (call_amd64) から呼ばれる = vcpuFd を所有する eval
  //   thread 上なので KVM_SET_MSRS を直接発行できる。eval 開始前は fsBase 保存のみ (setupKvm が
  //   初期 MSR set で反映)。
  private long fsBase = 0;
  @Override public void set_fs_base( long base ) {
    fsBase = base;
    if( kvmReady ) {
      try { setMsrs( new long[][]{ { KvmBindings.MSR_FS_BASE, base } } ); }
      catch( Throwable t ) { throw new RuntimeException( "set FS base via KVM_SET_MSRS failed: " + t, t ); }
    }
  }
  @Override public long get_fs_base() { return fsBase; }

  // ===== 初期 stack (argc/argv/envp/auxv) を guest RAM に構築 =====

  /**
   * System V x86-64 初期プロセス stack を guest に構築し RSP を設定。
   *   非 identity MMU では software と同じ高位 stack アドレス (sysinfo.get_stack_bottom_64() =
   *   0x7fff_0000_0000) を使う。その stack 領域は connect_devices が stack segment (Elf が
   *   .stack で追加) を mapRange 済なので、buildInitialStack64 の store は mapped ページに届く。
   *   software と同じ stack 配置 = auxv/pointer も完全一致し、動的リンク (ld.so) の前提も揃う。
   */
  void setup_initial_stack( String[] args, String[] envs ) {
    long stackBottom = sysinfo.get_stack_bottom_64();
    rsp = Process.buildInitialStack64( guestMem, stackBottom, args, envs, mem );
    if( System.getenv( "EMULIN_TRACE_BACKEND" ) != null )
      System.err.println( "[native] initial stack built: argc=" + args.length
          + " stackBottom=0x" + Long.toHexString( stackBottom ) + " rsp=0x" + Long.toHexString( rsp ) );
  }

  // ===== eval: KVM_RUN loop → syscall を call_amd64 に dispatch =====

  @Override
  public long eval() {
    boolean trace = System.getenv( "EMULIN_TRACE_BACKEND" ) != null;
    long syscallCount = 0;
    boolean setupDone = false;
    // setupKvm() も teardownKvm() の finally 配下に置く (review #1 CRITICAL): setup が
    //   kvmFd/vmFd/vcpuFd/vcpuState を確保した直後に失敗しても、独立 try だと teardown に
    //   到達せず fd + mmap が leak していた。teardownKvm は null/負値 guard 済で部分初期化
    //   状態でも安全に呼べる。
    try {
      setupKvm();
      setupDone = true;
      while( !process.is_exited() ) {
        int exitReason = kvmRun();
        if( exitReason == KvmBindings.KVM_EXIT_HLT ) {
          // syscall trap: regs を読み call_amd64 に dispatch
          ioctl( vcpuFd, KvmBindings.KVM_GET_REGS, regsBuf, "KVM_GET_REGS" );
          long rax = reg( KvmBindings.KVM_REGS_OFF_RAX );
          long rdi = reg( KvmBindings.KVM_REGS_OFF_RDI );
          long rsi = reg( KvmBindings.KVM_REGS_OFF_RSI );
          long rdx = reg( KvmBindings.KVM_REGS_OFF_RDX );
          long r10 = reg( KvmBindings.KVM_REGS_OFF_R10 );
          long r8  = reg( KvmBindings.KVM_REGS_OFF_R8 );
          long r9  = reg( KvmBindings.KVM_REGS_OFF_R9 );
          long rcx = reg( KvmBindings.KVM_REGS_OFF_RCX );  // syscall 直後アドレス
          syscallCount++;
          if( trace ) System.err.println( "[native] syscall #" + syscallCount + " sysno=" + rax
              + " args=(" + rdi + "," + rsi + "," + rdx + "," + r10 + "," + r8 + "," + r9 + ")" );

          long ret = sys64.call_amd64( rax, rdi, rsi, rdx, r10, r8, r9 );

          if( process.is_exited() ) break;  // exit / exit_group

          // resume (ring 3): RAX=戻り値、RIP=stub 内 sysretq。sysretq が RCX→RIP /
          //   R11→RFLAGS / CS=0x33 (ring3) で user に戻すので、RCX (user 復帰先) と
          //   R11 (退避 RFLAGS) は絶対に書き換えない (call_amd64 も regsBuf 不変)。
          setReg( KvmBindings.KVM_REGS_OFF_RAX, ret );
          setReg( KvmBindings.KVM_REGS_OFF_RIP, SYSRETQ_VADDR );
          ioctl( vcpuFd, KvmBindings.KVM_SET_REGS, regsBuf, "KVM_SET_REGS" );
          continue;
        } else {
          // 非 HLT exit (MMIO/IO/INTERNAL_ERROR/FAIL_ENTRY/SHUTDOWN 等) は MVP では
          //   未対応。診断 (rip + 全 GPR) を出して guest を error 終了させる (無限ループ回避)。
          //   exit_reason=8 (KVM_EXIT_SHUTDOWN) は IDT 無しでの triple fault = guest が未対応
          //   命令/メモリアクセスをした症状。rip を objdump で逆アセンブルし faulting 命令を特定。
          long rip = 0;
          try { ioctl( vcpuFd, KvmBindings.KVM_GET_REGS, regsBuf, "KVM_GET_REGS" );
                rip = reg( KvmBindings.KVM_REGS_OFF_RIP ); } catch( Throwable ignore ) {}
          System.err.println( "[native] unexpected KVM exit_reason=" + exitReason
              + " rip=0x" + Long.toHexString( rip ) + " (MVP は HLT syscall trap のみ対応)" );
          System.err.println( "[native] " + dumpRegs() );
          process.exit_code = 127;
          process.set_exit_flag();
          break;
        }
      }
    } catch( Throwable t ) {
      throw new RuntimeException(
          ( setupDone ? "NativeCpuBackend eval failed: " : "NativeCpuBackend KVM setup failed: " ) + t, t );
    } finally {
      teardownKvm();
    }
    if( trace ) System.err.println( "[native] eval done: " + syscallCount
        + " syscalls, exit_code=" + process.exit_code );
    return 0;
  }

  // ===== KVM setup / teardown =====

  private void setupKvm() throws Throwable {
    if( !KvmBindings.probe() ) throw new IllegalStateException( "KVM not available" );
    kvmFd = KvmBindings.open( arena.allocateFrom( "/dev/kvm" ), KvmBindings.O_RDWR );
    if( kvmFd < 0 ) throw new IllegalStateException( "open(/dev/kvm) errno=" + KvmBindings.errno() );
    if( KvmBindings.ioctl( kvmFd, KvmBindings.KVM_GET_API_VERSION, MemorySegment.NULL )
        != KvmBindings.KVM_API_VERSION ) throw new IllegalStateException( "KVM_API_VERSION mismatch" );
    vmFd = KvmBindings.ioctl( kvmFd, KvmBindings.KVM_CREATE_VM, MemorySegment.NULL );
    if( vmFd < 0 ) throw new IllegalStateException( "KVM_CREATE_VM errno=" + KvmBindings.errno() );

    // guest RAM を guest 物理 0 に map
    MemorySegment mr = arena.allocate( KvmBindings.KVM_MEM_REGION_SIZE );
    mr.set( ValueLayout.JAVA_INT,  KvmBindings.KVM_MEM_OFF_SLOT,       0 );
    mr.set( ValueLayout.JAVA_INT,  KvmBindings.KVM_MEM_OFF_FLAGS,      0 );
    mr.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_MEM_OFF_GUEST_ADDR, 0L );
    mr.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_MEM_OFF_SIZE,       guestMem.sizeBytes() );
    mr.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_MEM_OFF_USER_ADDR,  guestMem.address() );
    ioctl( vmFd, KvmBindings.KVM_SET_USER_MEMORY_REGION, mr, "KVM_SET_USER_MEMORY_REGION" );

    vcpuFd = KvmBindings.ioctl( vmFd, KvmBindings.KVM_CREATE_VCPU, MemorySegment.NULL );
    if( vcpuFd < 0 ) throw new IllegalStateException( "KVM_CREATE_VCPU errno=" + KvmBindings.errno() );
    vcpuMmapSize = KvmBindings.ioctl( kvmFd, KvmBindings.KVM_GET_VCPU_MMAP_SIZE, MemorySegment.NULL );
    vcpuState = KvmBindings.mmap( MemorySegment.NULL, vcpuMmapSize,
        KvmBindings.PROT_READ | KvmBindings.PROT_WRITE, KvmBindings.MAP_SHARED, vcpuFd, 0L );
    if( vcpuState.address() == -1L || vcpuState.address() == 0L )
      throw new IllegalStateException( "mmap(vcpu_state) errno=" + KvmBindings.errno() );
    vcpuState = vcpuState.reinterpret( vcpuMmapSize );

    // CPUID: host のサポート CPUID を vCPU に流す。glibc 2.33+ は CPUID で CPU ISA level
    //   (SSE3/SSSE3/AVX 等) を確認するので、未設定だと libc.so が "ISA level lower than required"
    //   で abort する。KVM_GET_SUPPORTED_CPUID (system fd) → KVM_SET_CPUID2 (vcpu fd) で copy。
    int maxEnt = 256;
    MemorySegment cpuid = arena.allocate( KvmBindings.KVM_CPUID2_OFF_ENTRIES
        + (long) maxEnt * KvmBindings.KVM_CPUID_ENTRY_SIZE );
    cpuid.set( ValueLayout.JAVA_INT, KvmBindings.KVM_CPUID2_OFF_NENT, maxEnt );
    ioctl( kvmFd,  KvmBindings.KVM_GET_SUPPORTED_CPUID, cpuid, "KVM_GET_SUPPORTED_CPUID" );
    ioctl( vcpuFd, KvmBindings.KVM_SET_CPUID2,          cpuid, "KVM_SET_CPUID2" );

    // sregs: long mode ring 3 + EFER.SCE (syscall 有効)。
    //   初回 entry を ring-3 にするため CS=0x33 (RPL3)/SS=0x2b (RPL3) を DPL=3 で設定。
    //   syscall→hlt→sysretq の往復後も hardware が STAR から同じ 0x33/0x2b を再ロードする。
    MemorySegment sregs = arena.allocate( KvmBindings.KVM_SREGS_SIZE );
    ioctl( vcpuFd, KvmBindings.KVM_GET_SREGS, sregs, "KVM_GET_SREGS" );
    setSeg( sregs, KvmBindings.KVM_SREGS_OFF_CS, 0x33, KvmBindings.SEG_TYPE_CODE, /*db*/0, /*l*/1, /*dpl*/3 );
    for( int o : new int[]{ KvmBindings.KVM_SREGS_OFF_DS, KvmBindings.KVM_SREGS_OFF_ES,
                            KvmBindings.KVM_SREGS_OFF_FS, KvmBindings.KVM_SREGS_OFF_GS,
                            KvmBindings.KVM_SREGS_OFF_SS } )
      setSeg( sregs, o, 0x2b, KvmBindings.SEG_TYPE_DATA, /*db*/1, /*l*/0, /*dpl*/3 );
    sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_CR0,  KvmBindings.CR0_LONG_MODE );
    sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_CR3,  guestMem.pml4Phys() );
    sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_CR4,
        KvmBindings.CR4_PAE | KvmBindings.CR4_OSFXSR | KvmBindings.CR4_OSXMMEXCPT );  // SSE 有効化
    sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_EFER,
        KvmBindings.EFER_LME | KvmBindings.EFER_LMA | KvmBindings.EFER_SCE );
    ioctl( vcpuFd, KvmBindings.KVM_SET_SREGS, sregs, "KVM_SET_SREGS" );

    // MSRs: STAR / LSTAR (hlt+sysretq スタブ) / FMASK。
    //   STAR[63:48]=0x23 → sysretq で user CS=0x33/SS=0x2b (ring 3)、STAR[47:32]=0x10 →
    //   syscall で kernel CS=0x10/SS=0x18 (ring 0)。FMASK=0: syscall は R11←RFLAGS で user
    //   RFLAGS を退避し、sysretq が R11→RFLAGS で復元するので、stub 実行中に RFLAGS を clear
    //   する必要が無い (stub は hlt;sysretq だけで RFLAGS 非依存)。real Linux の 0x47700 は
    //   kernel handler 実行中の IF/DF clear 用で、本 stub には不要。
    setMsrs( new long[][]{
        { KvmBindings.MSR_STAR,         STAR_VALUE },
        { KvmBindings.MSR_LSTAR,        STUB_VADDR },
        { KvmBindings.MSR_SYSCALL_MASK, 0L         },
        { KvmBindings.MSR_FS_BASE,      fsBase     },  // arch_prctl 前の初期 FS base (通常 0)
    } );

    // entry point が guest RAM 内か検証 (skip された高位 segment に entry がある等を早期検出)。
    //   未 map (= page table に無い) だと初回 fetch で triple fault になるので早期に弾く。
    if( entryRip < 0 || !guestMem.in( entryRip ) )
      fatalUnsupported( "entry point rip=0x" + Long.toHexString( entryRip ) + " が未 map" );

    // regs: Linux のプロセス起動時レジスタ状態 = rsp/rip 以外の全 GPR を 0 にする
    //   (rdx も 0: 静的 binary は rtld_fini 無し)。KVM reset 値 (rdx=family/model 等) が
    //   残ると glibc _start が rtld_fini 等を誤読して暴走する。software 経路の
    //   `for(i) cpu64.r64[i]=0` と等価。rflags=2 (bit1 予約=1)。
    regsBuf = arena.allocate( KvmBindings.KVM_REGS_SIZE );
    regsBuf.fill( (byte) 0 );
    setReg( KvmBindings.KVM_REGS_OFF_RIP,    entryRip );
    setReg( KvmBindings.KVM_REGS_OFF_RSP,    rsp );
    setReg( KvmBindings.KVM_REGS_OFF_RFLAGS, 2L );
    ioctl( vcpuFd, KvmBindings.KVM_SET_REGS, regsBuf, "KVM_SET_REGS" );
    kvmReady = true;
  }

  private void teardownKvm() {
    try {
      if( vcpuState != null ) KvmBindings.munmap( vcpuState, vcpuMmapSize );
      if( vcpuFd >= 0 ) KvmBindings.close( vcpuFd );
      if( vmFd   >= 0 ) KvmBindings.close( vmFd );
      if( kvmFd  >= 0 ) KvmBindings.close( kvmFd );
    } catch( Throwable ignore ) {}
  }

  /** KVM_RUN を EINTR retry 付きで回し、exit_reason を返す */
  private int kvmRun() {
    try {
      int rc;
      do {
        rc = KvmBindings.ioctl( vcpuFd, KvmBindings.KVM_RUN, MemorySegment.NULL );
      } while( rc < 0 && KvmBindings.errno() == 4 );  // EINTR (JVM GC/safepoint signal)
      if( rc < 0 ) throw new IllegalStateException( "KVM_RUN errno=" + KvmBindings.errno() );
      return vcpuState.get( ValueLayout.JAVA_INT, KvmBindings.KVM_RUN_OFF_EXIT_REASON );
    } catch( Throwable t ) {
      throw new RuntimeException( "KVM_RUN failed: " + t, t );
    }
  }

  private long reg( int off )            { return regsBuf.get( ValueLayout.JAVA_LONG, off ); }
  private void setReg( int off, long v ) { regsBuf.set( ValueLayout.JAVA_LONG, off, v ); }

  /** regsBuf (直前に KVM_GET_REGS 済) の全 GPR を 16 進ダンプ (triple fault 診断用)。 */
  private String dumpRegs() {
    return "rax=" + Long.toHexString( reg( KvmBindings.KVM_REGS_OFF_RAX ) )
        + " rbx=" + Long.toHexString( reg( KvmBindings.KVM_REGS_OFF_RBX ) )
        + " rcx=" + Long.toHexString( reg( KvmBindings.KVM_REGS_OFF_RCX ) )
        + " rdx=" + Long.toHexString( reg( KvmBindings.KVM_REGS_OFF_RDX ) )
        + " rsi=" + Long.toHexString( reg( KvmBindings.KVM_REGS_OFF_RSI ) )
        + " rdi=" + Long.toHexString( reg( KvmBindings.KVM_REGS_OFF_RDI ) )
        + " rbp=" + Long.toHexString( reg( KvmBindings.KVM_REGS_OFF_RBP ) )
        + " rsp=" + Long.toHexString( reg( KvmBindings.KVM_REGS_OFF_RSP ) )
        + " r8=" + Long.toHexString( reg( KvmBindings.KVM_REGS_OFF_R8 ) )
        + " r12=" + Long.toHexString( reg( KvmBindings.KVM_REGS_OFF_R12 ) )
        + " r13=" + Long.toHexString( reg( KvmBindings.KVM_REGS_OFF_R13 ) )
        + " r14=" + Long.toHexString( reg( KvmBindings.KVM_REGS_OFF_R14 ) )
        + " r15=" + Long.toHexString( reg( KvmBindings.KVM_REGS_OFF_R15 ) );
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

  private void setMsrs( long[][] msrs ) throws Throwable {
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

  private void ioctl( int fd, long req, MemorySegment arg, String name ) throws Throwable {
    if( KvmBindings.ioctl( fd, req, arg ) < 0 )
      throw new IllegalStateException( name + " errno=" + KvmBindings.errno() );
  }

  /**
   * native (KVM) backend が扱えない binary 構成に当たったときに loud に終了する。
   *
   * connect_devices は boot path (Kernel.boot→Process.<init>) で走り、この時点で既に
   *   非 daemon の init 監視 thread (Process.run の while(true)) が稼働している。ここで
   *   例外を throw すると main thread は死ぬが init thread が生き続け JVM が **hang** する
   *   (= 旧 overlap guard が hello64 の vaddr=0 PT_GNU_STACK を弾いて 22 分 hang した真因)。
   *   よって throw でなく System.exit で確実に JVM を落とす。MVP では到達しない防御経路。
   */
  private static void fatalUnsupported( String msg ) {
    System.err.println( "[native] unsupported binary for KVM backend: " + msg );
    System.err.println( "[native] EMULIN_BACKEND=native は現状 -nostdlib 静的 ELF (hello64 系、"
        + "vaddr 0x10000+) のみ対応。software backend で再実行して下さい (issue #221 step 3d-2c で拡張)。" );
    System.exit( 127 );
  }

  // ===== AbstractCpu の残り (MVP では未使用 / stub) =====

  @Override public long pushString( String str ) {
    throw new UnsupportedOperationException( "NativeCpuBackend.pushString (stack) not in MVP (step 3d-2c)" );
  }
  @Override public void push32( long value ) {
    throw new UnsupportedOperationException( "NativeCpuBackend.push32 not in MVP" );
  }
  @Override public int pop32() {
    throw new UnsupportedOperationException( "NativeCpuBackend.pop32 not in MVP" );
  }
  @Override public void set_signal_handler( long _ip, long goto_adrs ) {
    throw new UnsupportedOperationException( "NativeCpuBackend.set_signal_handler not in MVP (step 3d-2c)" );
  }
  @Override public boolean is_interrupt_done() { return false; }

  @Override public void fetch( long address, byte[] buf ) {
    // native では命令 fetch は vCPU が直接行う。debug 用に guest RAM から読む。
    if( guestMem != null ) guestMem.fetch( address, buf );
  }

  @Override public String reg_str()                  { return "<native vCPU regs>"; }
  @Override public String ip_str()                   { return "<native rip>"; }
  @Override public String flag_str()                 { return "<native rflags>"; }
  @Override public String disasm_str( long address ) { return "<native>"; }
}
