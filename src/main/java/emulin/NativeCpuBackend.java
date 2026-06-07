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
  // ---- guest メモリレイアウト (guest 物理 = 仮想、identity) ----
  private static final long GUEST_RAM_SIZE = 16L * 1024 * 1024;  // 16MB
  private static final long PML4_GPA  = 0x1000L;
  private static final long PDPT_GPA  = 0x2000L;
  private static final long PD_GPA    = 0x3000L;
  private static final long LSTAR_GPA   = 0x5000L;        // hlt + sysretq スタブ
  private static final long SYSRETQ_GPA = LSTAR_GPA + 1;  // stub 内 sysretq (hlt の次)
  // PT_LOAD は [CODE_BASE, GUEST_RAM_SIZE) (= PD[2..7]) にのみ配置できる。それ未満は
  //   page table/LSTAR stub (PD[0] 低位) と user stack (PD[1]) の予約域で、ここに ELF
  //   segment を copy すると上書き破壊される (silent corruption 防止、3d-2 の hang と同種)。
  private static final long CODE_BASE = 0x400000L; // 最低 PT_LOAD vaddr (PD[2] 先頭)
  // user stack は PD[1] ([0x200000,0x400000)) の US=1 領域に置く (PD[0] の低位 2MB は
  //   page table/LSTAR の supervisor 域、PD[2]+ は ELF code/data)。初期 stack
  //   (argc/argv/envp/auxv + 文字列) は GUEST_STACK_TOP から下方に構築し、残りは関数呼び
  //   出しの stack として下方 (~0x200000 まで 2MB) に伸ばせる。software の 0x7fff... は
  //   16MB guest RAM 外なのでここに再配置する (#221 step 3d-2c-2)。
  private static final long GUEST_STACK_TOP = 0x3ff000L;  // PD[1] 上端付近 (code 0x400000 の直下)
  private static final long PD1_BASE        = 0x200000L;  // user stack 領域の下限 (PD[1] 先頭)
  private static final long INIT_RSP        = GUEST_STACK_TOP; // setup_initial_stack 前の暫定値
  // STAR: syscall→kernel CS=0x10/SS=0x18、sysretq→user CS=0x33/SS=0x2b (Linux 規約)。
  //   ★ SYSRETQ(64-bit) の selector 算術は非自明: user CS = STAR[63:48] + 16、
  //   user SS = STAR[63:48] + 8 (どちらも RPL=3)。よって user CS=0x33 を得るには
  //   STAR[63:48] に 0x33 でなく 0x23 (=0x33-16) を入れる。これは Linux の
  //   `(__USER32_CS=0x23)<<48 | (__KERNEL_CS=0x10)<<32` と同値。0x33 を入れると
  //   sysretq CS=0x43 になり壊れる (review で 6 agent が +16 を見落とし誤指摘した箇所)。
  private static final long STAR_VALUE = (0x23L << 48) | (0x10L << 32);

  // ---- state ----
  private long          entryRip;
  private long          rsp = INIT_RSP;   // 初期 RSP (setup_initial_stack で確定)
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

    // off-heap guest 物理 RAM を確保 (KVM がマップ、process 寿命で leak 許容)
    arena    = Arena.ofShared();
    guestMem = new NativeMemoryBackend( arena.allocate( GUEST_RAM_SIZE, 4096 ) );

    boolean trace = System.getenv( "EMULIN_TRACE_BACKEND" ) != null;

    // ELF の各 PT_LOAD segment を guest 物理 (= vaddr) に identity copy。
    //   software Memory (_mem) の canonical accessor で読み、guest RAM に書く。
    //   guest RAM に収まらない高位 segment (stack の 0x7fff_... 等) は skip。
    for( int i = 0; i < _mem.segments; i++ ) {
      Segment seg = _mem.segment[i];
      if( seg == null ) continue;
      // ★ software loader (Memory.java:559) と同じく PT_LOAD (p_type==1) のみ guest RAM に
      //   写す。segment[] には PT_PHDR / PT_NOTE / PT_GNU_STACK (vaddr=0) 等も含まれ、
      //   これらを copy すると低位 RAM (page table 等) を汚す。software は "PT_LOAD 以外
      //   map しない" ので、native も同一 filter にしないと oracle が崩れる。
      if( seg.p_type != 1 /* PT_LOAD */ ) {
        if( trace ) System.err.println( "[native] skip non-PT_LOAD p_type=0x"
            + Integer.toHexString( seg.p_type ) + " vaddr=0x" + Long.toHexString( seg.p_vaddr ) );
        continue;
      }
      long va    = seg.p_vaddr;
      long memsz = seg.p_memsz;
      // overflow-safe な範囲 check: va + memsz は va≈2^63 で符号反転し得るので減算形で。
      if( va < 0 || va > GUEST_RAM_SIZE || memsz > GUEST_RAM_SIZE - va ) {
        if( trace ) System.err.println( "[native] skip PT_LOAD vaddr=0x" + Long.toHexString( va )
            + " memsz=0x" + Long.toHexString( memsz ) + " (out of guest RAM)" );
        continue;
      }
      // code 領域 [CODE_BASE,16MB) より下に来た PT_LOAD は予約域 (page table/LSTAR stub の
      //   PD[0] 低位、user stack の PD[1]) と重なり、copy 後に page table 書き込み or stack
      //   構築で上書き破壊される。MVP の hello64/argvdump64 (vaddr 0x400000+) では発生しない
      //   が、低位 load の binary は native 非対応。fatalUnsupported で loud に終了 (throw だと
      //   init 監視 thread が生きていて JVM が hang するため System.exit で確実に落とす)。
      if( va < CODE_BASE )
        fatalUnsupported( "PT_LOAD vaddr=0x" + Long.toHexString( va )
            + " は code 領域 [0x" + Long.toHexString( CODE_BASE ) + ",0x"
            + Long.toHexString( GUEST_RAM_SIZE ) + ") の外 (低位は page table/LSTAR + user stack の予約域)" );
      if( seg.p_filesz > Integer.MAX_VALUE )  // 仕様準拠 ELF では起き得ない (filesz<=memsz<=16MB)
        fatalUnsupported( "PT_LOAD filesz=0x" + Long.toHexString( seg.p_filesz )
            + " exceeds Integer.MAX_VALUE (malformed ELF)" );
      int fsz = (int) seg.p_filesz;
      if( fsz > 0 ) {
        byte[] tmp = new byte[ fsz ];
        _mem.bulkLoadFromMem( va, tmp, 0, fsz );   // software から読む
        guestMem.bulkStoreToMem( va, tmp, 0, fsz ); // guest RAM に書く
      }
      // BSS (memsz > filesz) は Arena.allocate が 0 初期化済なので追加不要
      if( trace ) System.err.println( "[native] copied PT_LOAD vaddr=0x" + Long.toHexString( va )
          + " filesz=0x" + Long.toHexString( fsz ) + " memsz=0x" + Long.toHexString( memsz ) );
    }

    // identity page table (2MB page × 8 = [0,16MB)) を guest RAM に書く。
    //   US は全 paging level の AND なので user page に届かせるには PML4/PDPT も US 必須。
    //   leaf (PD) で per-2MB に US を制御: PD[0] ([0,2MB)) は page table 自身 + LSTAR stub を
    //   含むので US=0 (supervisor) のままにして ring-3 から隠す。PD[1..7] は US=1 (user)。
    long link = KvmBindings.PTE_P | KvmBindings.PTE_RW | KvmBindings.PTE_US;
    guestMem.store64( PML4_GPA, PDPT_GPA | link );
    guestMem.store64( PDPT_GPA, PD_GPA   | link );
    for( int i = 0; i < 8; i++ ) {
      long flags = KvmBindings.PTE_P | KvmBindings.PTE_RW | KvmBindings.PTE_PS;
      if( i != 0 ) flags |= KvmBindings.PTE_US;   // PD[0] のみ supervisor (page table/LSTAR)
      guestMem.store64( PD_GPA + (long) i * 8, ((long) i << 21) | flags );
    }

    // LSTAR スタブ: hlt (syscall trap で VM-exit) → sysretq (ring3 復帰)。
    //   hlt=F4、sysretq=REX.W(48) 0F 07 (REX.W で 64-bit user mode へ復帰)。
    guestMem.store8( LSTAR_GPA + 0, 0xF4 );  // hlt
    guestMem.store8( LSTAR_GPA + 1, 0x48 );  // sysretq (REX.W)
    guestMem.store8( LSTAR_GPA + 2, 0x0F );
    guestMem.store8( LSTAR_GPA + 3, 0x07 );

    // ★ syscall 層の mem を guest RAM に向ける (amd64_write 等が guest buffer を読む)
    _syscall.connect_mem( guestMem );

    if( trace ) System.err.println( "[native] connect_devices done: guest RAM @0x"
        + Long.toHexString( guestMem.address() ) + ", syscall.mem → NativeMemoryBackend" );
  }

  // ===== register accessor (eval 前は field、eval 中は guest regs を反映する起点) =====

  @Override public void set_ip( long _ip )    { entryRip = _ip; }
  @Override public long get_ip()              { return entryRip; }
  @Override public void set_sp( long sp )     { rsp = sp; }
  @Override public long get_sp()              { return rsp; }
  @Override public void set_ax( int value )   { /* unused in MVP */ }

  // ===== 初期 stack (argc/argv/envp/auxv) を guest RAM に構築 =====

  /**
   * System V x86-64 初期プロセス stack を guest RAM (PD[1] の US 領域) に構築し RSP を設定。
   *   Process.run の native 経路から connect_devices の後に呼ぶ。software と同じ
   *   `buildInitialStack64` を共有し、stack バイトは guestMem へ、ELF メタデータは software
   *   Memory (`mem`) から読む。argv/envp/auxv の pointer 値は guest RAM アドレス (identity)。
   */
  void setup_initial_stack( String[] args, String[] envs ) {
    long sp = Process.buildInitialStack64( guestMem, GUEST_STACK_TOP, args, envs, mem );
    if( sp < PD1_BASE )
      fatalUnsupported( "initial stack (argc/argv/envp/auxv) が PD[1] US 領域 [0x"
          + Long.toHexString( PD1_BASE ) + ",0x" + Long.toHexString( GUEST_STACK_TOP )
          + ") に収まらない (sp=0x" + Long.toHexString( sp ) + ")" );
    rsp = sp;
    if( System.getenv( "EMULIN_TRACE_BACKEND" ) != null )
      System.err.println( "[native] initial stack built: argc=" + args.length
          + " rsp=0x" + Long.toHexString( rsp ) );
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
          setReg( KvmBindings.KVM_REGS_OFF_RIP, SYSRETQ_GPA );
          ioctl( vcpuFd, KvmBindings.KVM_SET_REGS, regsBuf, "KVM_SET_REGS" );
          continue;
        } else {
          // 非 HLT exit (MMIO/IO/INTERNAL_ERROR/FAIL_ENTRY/SHUTDOWN 等) は MVP では
          //   未対応。診断を出して guest を error 終了させる (無限ループ回避)。
          long rip = 0;
          try { ioctl( vcpuFd, KvmBindings.KVM_GET_REGS, regsBuf, "KVM_GET_REGS" );
                rip = reg( KvmBindings.KVM_REGS_OFF_RIP ); } catch( Throwable ignore ) {}
          System.err.println( "[native] unexpected KVM exit_reason=" + exitReason
              + " rip=0x" + Long.toHexString( rip ) + " (MVP は HLT syscall trap のみ対応)" );
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
    sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_CR3,  PML4_GPA );
    sregs.set( ValueLayout.JAVA_LONG, KvmBindings.KVM_SREGS_OFF_CR4,  KvmBindings.CR4_PAE );
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
        { KvmBindings.MSR_LSTAR,        LSTAR_GPA  },
        { KvmBindings.MSR_SYSCALL_MASK, 0L         },
    } );

    // entry point が guest RAM 内か検証 (skip された高位 segment に entry がある等を早期検出)。
    //   範囲外だと初回 fetch で非 HLT exit になり 127 終了するが、明示メッセージの方が親切。
    if( entryRip < 0 || entryRip >= GUEST_RAM_SIZE )
      fatalUnsupported( "entry point rip=0x" + Long.toHexString( entryRip )
          + " outside guest RAM [0,0x" + Long.toHexString( GUEST_RAM_SIZE ) + ")" );

    // regs: rip=entry, rsp=構築した初期 stack (argc を指す), rflags=2
    regsBuf = arena.allocate( KvmBindings.KVM_REGS_SIZE );
    ioctl( vcpuFd, KvmBindings.KVM_GET_REGS, regsBuf, "KVM_GET_REGS" );
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
