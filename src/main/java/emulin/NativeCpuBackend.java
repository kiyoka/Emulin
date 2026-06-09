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
  // 物理プール: guest 物理 RAM の上限。MMU は仮想ページごとに allocData() で物理を bump-allocate
  //   するので、プールは「同時に map できる物理ページの総量」= guest が確保するメモリ量の上限。
  //   glibc の pthread 既定 stack は 8MB/thread (RLIMIT_STACK) を mmap するので、N worker では
  //   ~8MB×N を即座に map する (native は guest 内 demand paging しない=eager)。64MB だと 8 thread
  //   で枯渇したため 512MB に拡大。pool は lazy な mmap(MAP_ANON) で確保する (connect_devices) ので、
  //   未 touch 領域は host 実 RAM を消費しない = 大きく取っても安い。page table 領域 (8MB、PT_BASE..
  //   DATA_BASE) は ~2000 PT page = 512MB の疎な vaddr を十分カバーする。
  private static final long POOL_SIZE     = 512L * 1024 * 1024;  // 512MB 物理プール (lazy mmap)
  private static final long STUB_VADDR    = 0xff000L;           // LSTAR スタブの仮想 (supervisor)
  private static final long SYSRETQ_VADDR = STUB_VADDR + 1;     // stub 内 sysretq (hlt の次)
  // STAR: syscall→kernel CS=0x10/SS=0x18、sysretq→user CS=0x33/SS=0x2b (Linux 規約)。
  //   ★ SYSRETQ(64-bit) の selector 算術は非自明: user CS = STAR[63:48] + 16、
  //   user SS = STAR[63:48] + 8 (どちらも RPL=3)。よって user CS=0x33 を得るには
  //   STAR[63:48] に 0x33 でなく 0x23 (=0x33-16) を入れる。これは Linux の
  //   `(__USER32_CS=0x23)<<48 | (__KERNEL_CS=0x10)<<32` と同値。0x33 を入れると
  //   sysretq CS=0x43 になり壊れる (review で 6 agent が +16 を見落とし誤指摘した箇所)。
  private static final long STAR_VALUE = (0x23L << 48) | (0x10L << 32);

  // rt_sigreturn トランポリン: signal handler が ret で着地する user ページ。`mov $15,%rax; syscall`
  //   で rt_sigreturn(#15) を呼び、eval ループが sysno==15 を見て被中断 context を復元する (glibc の
  //   sa_restorer 相当)。handler は ring-3 で動くので user(US=1) かつ実行可能ページ。STUB(0xff000)
  //   とは別ページ 0xfe000 (通常 binary は 0x400000+ なので衝突しない)。
  private static final long SIGTRAMP_VADDR = 0xfe000L;
  // signal frame の register layout: [0..17]=下記 offset 順の値、[18]=保存 signal mask。
  private static final int[] SIGFRAME_OFFS = {
      KvmBindings.KVM_REGS_OFF_RAX, KvmBindings.KVM_REGS_OFF_RBX, KvmBindings.KVM_REGS_OFF_RCX,
      KvmBindings.KVM_REGS_OFF_RDX, KvmBindings.KVM_REGS_OFF_RSI, KvmBindings.KVM_REGS_OFF_RDI,
      KvmBindings.KVM_REGS_OFF_RSP, KvmBindings.KVM_REGS_OFF_RBP, KvmBindings.KVM_REGS_OFF_R8,
      KvmBindings.KVM_REGS_OFF_R9,  KvmBindings.KVM_REGS_OFF_R10, KvmBindings.KVM_REGS_OFF_R11,
      KvmBindings.KVM_REGS_OFF_R12, KvmBindings.KVM_REGS_OFF_R13, KvmBindings.KVM_REGS_OFF_R14,
      KvmBindings.KVM_REGS_OFF_R15, KvmBindings.KVM_REGS_OFF_RIP, KvmBindings.KVM_REGS_OFF_RFLAGS,
  };  // index: RAX0 RBX1 RCX2 RDX3 RSI4 RDI5 RSP6 RBP7 R8=8..R15=15 RIP16 RFLAGS17

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
  private MemorySegment fpuBuf;      // KVM_GET/SET_FPU 用 (x87/XMM、signal 退避復元、再利用)
  private MemorySegment poolSeg;     // guest 物理 RAM (lazy mmap、teardown で munmap)

  // ---- signal 配信 (issue #221 step 3d-2c-13) ----
  //   syscall 境界 (call_amd64 後) で pending signal を guest handler に配信する。被中断 user
  //   context (全 GPR+RIP+RSP+RFLAGS+mask) を sigFrames に積み、handler を sysretq で起動。handler
  //   が trampoline 経由で rt_sigreturn(#15) を呼ぶと frame を pop して復帰する。per-vCPU (この
  //   backend instance 専用) なので thread ごとに独立する。
  // ★ FPU-in-signal (issue #221 step 3d-2c-21): handler が x87/XMM を使っても被中断側の live FP データが
  //   壊れないよう、被中断 GPR context と vCPU FPU snapshot (KVM_GET_FPU、x87/XMM/MXCSR) を 1 つの
  //   SigFrame にまとめて積む。software (Cpu64.check_pending_signal) が GPR+xmm+x87 を 1 frame に退避する
  //   のと同じ単位。★ GPR と FPU を別 deque にすると ioctl 失敗時に片方だけ積まれて desync しうる (review
  //   指摘) ので 1 frame に統合する。nested signal も LIFO。
  private static final class SigFrame {
    final long[] regs;   // SIGFRAME_OFFS 順 (RAX..R15,RIP,RFLAGS) + [18]=保存 signal mask
    final byte[] fpu;    // KVM_GET_FPU snapshot (struct kvm_fpu、x87/XMM0-15/MXCSR)
    SigFrame( long[] r, byte[] f ) { regs = r; fpu = f; }
  }
  private final java.util.ArrayDeque<SigFrame> sigFrames = new java.util.ArrayDeque<>();

  // ---- multi-vCPU (issue #221 pthread) ----
  //   pthread worker は同一 VM (vmFd) 上の追加 vCPU として走る。VM 資源 (kvmFd/vmFd/
  //   guestMem/arena = guest RAM + page table) は VM owner (main backend) が所有し、worker は
  //   それを共有して自分の vcpuFd/vcpuState/regsBuf だけ持つ。各 vCPU は専用 Java thread
  //   (Worker) で自分の KVM_RUN ループを回す。共有メモリ上の atomic (LOCK CMPXCHG 等) は実
  //   CPU が複数 vCPU 間で実行するので software GIL 不要、futex の slow path だけ trap される。
  private boolean       isChild = false;      // true = worker vCPU (VM は owner が所有)
  private int           vcpuId  = 0;          // KVM vcpu id (0 = main、1+ = worker、VM 内で一意)
  private NativeCpuBackend vmOwner;           // VM 共有資源の所有者 (main backend、自分が main なら null)
  // vcpu id 採番は VM owner が持つ単一 counter で行う (nested clone でも衝突しない)。
  private final java.util.concurrent.atomic.AtomicInteger nextVcpuId =
      new java.util.concurrent.atomic.AtomicInteger( 1 );
  private int           childTid;             // worker の tid (gettid / pthread_join 用)
  private long          childCtidAddr;        // CLONE_CHILD_CLEARTID の clear/wake address
  private Arena         vcpuArena;            // worker の per-vCPU buffer (vcpuState/regs/sregs)。teardown で close

  // ---- fork (issue #221 step 3d-2c-20) ----
  //   fork 子は worker (CLONE_THREAD、VM 共有) と違い、独立 VM + 複製 guestMem を持つ別 process。
  //   duplicate() で親の trap 時 register snapshot (forkRegs) を取り、connect_devices の fork 分岐で
  //   親 guestMem を子の新プールに複製する (ELF reload しない)。setupVcpu の fork 分岐で forkRegs を
  //   復元し rax=0/rip=resume で起動する。null = 通常 boot、非 null = fork 子。
  private NativeCpuBackend forkParent;
  private long[]           forkRegs;          // SIGFRAME_OFFS 順 (RAX..R15,RIP,RFLAGS) の親 snapshot

  public NativeCpuBackend( Sysinfo _sysinfo, Process _process ) {
    sysinfo = _sysinfo;
    process = _process;
  }

  // pthread worker (追加 vCPU) 用の private constructor。VM 資源を owner から共有し、子の
  //   初期レジスタ (clone ABI: rax=0、rip=親の syscall 戻り先、rsp=child_stack、fs=tls) を持つ。
  private NativeCpuBackend( NativeCpuBackend owner, long childRip, long childStack,
                            long tls, int tid, long ctidAddr ) {
    this.sysinfo   = owner.sysinfo;
    this.process   = owner.process;
    this.vmOwner   = owner;
    this.isChild   = true;
    this.vcpuId    = owner.nextVcpuId.getAndIncrement();
    // VM 共有資源 (read-only に扱う): guest RAM/page table、KVM/VM fd、syscall 層。
    this.kvmFd     = owner.kvmFd;
    this.vmFd      = owner.vmFd;
    this.guestMem  = owner.guestMem;
    this.arena     = owner.arena;     // guest RAM 用 shared arena (vcpu buffer は vcpuArena に分離)
    this.sys64     = owner.sys64;
    this.mem       = owner.mem;
    this.syscall   = owner.syscall;
    // 子の初期状態
    this.entryRip      = childRip;
    this.rsp           = childStack;
    this.fsBase        = tls;
    this.childTid      = tid;
    this.childCtidAddr = ctidAddr;
  }

  @Override public void init() { /* eval() で lazy 初期化 */ }

  // ===== fork (issue #221 step 3d-2c-20): 子 backend を生成し親 register を snapshot =====
  //   Kernel.fork → Process.duplicate → cpu.duplicate(child) で呼ばれる。実 guestMem 複製と KVM
  //   setup は connect_devices (fork 分岐) と eval→setupKvm で後から行う。子は独立 VM を持つ別
  //   process なので worker (isChild=true、VM 共有) ではない。
  @Override
  public AbstractCpu duplicate( Process _process ) {
    NativeCpuBackend child = new NativeCpuBackend( sysinfo, _process );
    child.forkParent = this;     // VM owner (= main backend): 子はこの guestMem を複製する
    // ★ fork を呼んだのが pthread worker (別 vCPU) の場合、register/TLS は「呼んだ worker」のものを
    //   snapshot する (software の issue #113/#181 = clone 親取り違えと同 class の防御)。Kernel.fork →
    //   process.duplicate → process.cpu.duplicate なので this は常に main backend 固定で、this.regsBuf は
    //   main vCPU の stale 値。worker fork でこれを使うと子が main の rip/rsp/fs を継承して wild jump する。
    //   worker は main と同一 VM・guestMem を共有する (worker.guestMem == owner.guestMem) ので、複製元
    //   (forkParent=this) はそのままで良く、register/fsBase の snapshot 元 (src) だけを呼び出し thread の
    //   backend にする。software は Kernel.fork:340-344 の copy_state_from で同じ補正をするが、その gate は
    //   instanceof Thread64 && Cpu64 で native worker (GuestThread/NativeCpuBackend) には効かないため、
    //   native 側で補正する。
    NativeCpuBackend src = this;
    Thread cur = Thread.currentThread();
    if( cur instanceof GuestThread gt && gt.guestCpu() instanceof NativeCpuBackend w ) src = w;
    child.fsBase = src.fsBase;          // 子は呼び出し thread の TLS (FS base) を継承
    // ★ register snapshot を今ここで固定する: src.regsBuf は現 fork trap の KVM_GET_REGS 値を持つ
    //   (eval が call_amd64 前に GET 済) が、呼び出し thread が fork から復帰し次の syscall を打つと上書き
    //   される。子の setupVcpu は別 thread で後から走るので、直接参照すると race する。long[] に copy して
    //   おけば子は安全に自分の起動レジスタを再構築できる。
    long[] snap = new long[ SIGFRAME_OFFS.length ];   // [0]=RAX..[15]=R15,[16]=RIP,[17]=RFLAGS
    for( int i = 0; i < SIGFRAME_OFFS.length; i++ ) snap[i] = src.reg( SIGFRAME_OFFS[i] );
    child.forkRegs = snap;
    return child;
  }

  // ===== device 接続: ELF segment を guest RAM に写し、syscall mem を向ける =====

  @Override
  public void connect_devices( Memory _mem, Syscall _syscall ) {
    this.mem     = _mem;          // software Memory (segment[] / ELF メタ参照用)
    this.syscall = _syscall;
    this.sys64   = (SyscallAmd64) _syscall;

    // fork 子: 親の guestMem を子の新プールに複製し、ELF reload はしない (boot path とは別経路)。
    if( forkParent != null ) { connect_fork( _syscall ); return; }

    // off-heap 物理プールを確保 (KVM が guest 物理 0 に map、process 寿命で leak 許容)。
    arena    = Arena.ofShared();
    // guest 物理 RAM は lazy な mmap(MAP_ANONYMOUS) で確保する。Arena.allocate は全域を eager に
    //   zero して RSS を POOL_SIZE 分消費する (測定: 256MB pool で +255MB RSS) が、mmap(MAP_ANON)
    //   は OS demand paging で未 touch ページを backing しないので、大きな pool でも実際に使った分
    //   (page table + guest が write した data ページ) しか実 RAM を食わない。これで POOL_SIZE を
    //   余裕を持って取れる (8 worker × 8MB stack 等の memory-heavy program に対応)。
    // ★ connect_devices は boot path: 例外を投げると非 daemon init thread が JVM を生かし hang する
    //   (#245)。pool 確保失敗 (essentially あり得ないが) は fatalUnsupported (System.exit) で落とす。
    try {
      poolSeg = KvmBindings.mmap( MemorySegment.NULL, POOL_SIZE,
          KvmBindings.PROT_READ | KvmBindings.PROT_WRITE,
          KvmBindings.MAP_PRIVATE | KvmBindings.MAP_ANONYMOUS, -1, 0L );
    } catch( Throwable t ) {
      fatalUnsupported( "mmap(guest pool, " + POOL_SIZE + ") threw " + t );
    }
    if( poolSeg.address() == -1L || poolSeg.address() == 0L )
      fatalUnsupported( "mmap(guest pool, " + POOL_SIZE + ") errno=" + KvmBindings.errno() );
    poolSeg = poolSeg.reinterpret( POOL_SIZE );
    guestMem = new NativeMemoryBackend( poolSeg );
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

    // rt_sigreturn トランポリン (user/ring-3 から実行): `mov $15,%rax; syscall`。signal handler が
    //   ret で着地し rt_sigreturn(#15) を呼ぶ → eval ループが被中断 context を復元する。NX bit は
    //   立てないので実行可能。
    guestMem.mapRange( SIGTRAMP_VADDR, 9, true );
    guestMem.store8( SIGTRAMP_VADDR + 0, 0x48 );  // mov rax, 15
    guestMem.store8( SIGTRAMP_VADDR + 1, 0xC7 );
    guestMem.store8( SIGTRAMP_VADDR + 2, 0xC0 );
    guestMem.store8( SIGTRAMP_VADDR + 3, 0x0F );
    guestMem.store8( SIGTRAMP_VADDR + 4, 0x00 );
    guestMem.store8( SIGTRAMP_VADDR + 5, 0x00 );
    guestMem.store8( SIGTRAMP_VADDR + 6, 0x00 );
    guestMem.store8( SIGTRAMP_VADDR + 7, 0x0F );  // syscall
    guestMem.store8( SIGTRAMP_VADDR + 8, 0x05 );

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
      // ★ audit fix: stub(0xff000)/sigtramp(0xfe000) の supervisor/trampoline ページに PT_LOAD が
      //   被ると、mapRange は (既 map で) skip するが直後の bulkStoreToMem が segment 内容で stub の
      //   `hlt;sysretq` / sigtramp の `mov $15;syscall` を上書きし、syscall trap 機構が壊れる
      //   (mapRange の skip は US を保つだけで内容は守らない=旧コメントの "loud に fault" は誤り)。
      //   予約低位帯 [SIGTRAMP_VADDR, STUB_VADDR+0x1000) と重なる PT_LOAD は skip (実 binary は
      //   0x400000+ なので無害、pathological/低位リンク binary のみ該当)。
      if( va < STUB_VADDR + 0x1000L && va + memsz > SIGTRAMP_VADDR ) {
        if( trace ) System.err.println( "[native] skip PT_LOAD vaddr=0x" + Long.toHexString( va )
            + " (stub/sigtramp 予約帯 [0x" + Long.toHexString( SIGTRAMP_VADDR ) + ",0x"
            + Long.toHexString( STUB_VADDR + 0x1000L ) + ") と重複)" );
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

  // ===== fork 経路の device 接続 (issue #221 step 3d-2c-20) =====
  //   通常 boot は ELF を fresh プールにロードするが、fork 子は「親の実行時状態をそのまま継ぐ」ので
  //   ELF reload してはいけない (heap/stack/mmap/brk が全部消える)。代わりに親 guestMem (page table +
  //   data + brk/mmap top) を子の新プールへ複製する。親 backend は fork trap で停止中なので親プールは
  //   quiescent (安全に copy できる)。LSTAR stub / sigtramp / PT_LOAD / 初期 stack は親プールに既に在り
  //   複製でそのまま子に入るので、boot path の再構築は一切不要。
  private void connect_fork( Syscall _syscall ) {
    arena = Arena.ofShared();
    // 子専用の物理プールを lazy mmap で確保 (boot path と同じ。未 touch ページは backing しない)。
    try {
      poolSeg = KvmBindings.mmap( MemorySegment.NULL, POOL_SIZE,
          KvmBindings.PROT_READ | KvmBindings.PROT_WRITE,
          KvmBindings.MAP_PRIVATE | KvmBindings.MAP_ANONYMOUS, -1, 0L );
    } catch( Throwable t ) {
      fatalUnsupported( "fork: mmap(child pool, " + POOL_SIZE + ") threw " + t );
    }
    if( poolSeg.address() == -1L || poolSeg.address() == 0L )
      fatalUnsupported( "fork: mmap(child pool, " + POOL_SIZE + ") errno=" + KvmBindings.errno() );
    poolSeg = poolSeg.reinterpret( POOL_SIZE );

    // 親アドレス空間を子プールへ複製 (page table の pool-relative 物理 offset がそのまま子で valid)。
    guestMem = forkParent.guestMem.duplicate( poolSeg );
    guestMem.setSyscall( _syscall );        // 子の file-backed mmap (子が exec せず .so を map する場合) 用
    _syscall.connect_mem( guestMem );       // 子 syscall 層 (amd64_write 等) を子 guestMem に向ける

    // 子の resume = fork syscall の次命令 = 親 RCX (forkRegs[2])。Kernel.fork が
    //   set_ip(get_ip()+2) する (i386 `int 0x80` 由来の generic fixup) ので、entryRip には
    //   RCX-2 (= user の `syscall` 命令アドレス) を入れて +2 で RCX に戻す。これは software amd64
    //   fork の「rip=syscall 命令アドレス、+2 で次へ」と同じ意味で、generic fixup を共有できる。
    entryRip = forkRegs[2] - 2;             // RCX - 2 (= syscall 命令アドレス)
    rsp      = forkRegs[6];                 // RSP (clone の child_stack は Kernel.fork が set_sp で上書き)
    if( System.getenv( "EMULIN_TRACE_BACKEND" ) != null )
      System.err.println( "[native] connect_fork: child pool @0x" + Long.toHexString( guestMem.address() )
          + " resume rip=0x" + Long.toHexString( forkRegs[2] ) + " rsp=0x" + Long.toHexString( rsp )
          + " fs=0x" + Long.toHexString( fsBase ) );
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
      if( isChild ) setupVcpu();   // worker: VM は owner 所有、自分の vCPU だけ作る
      else          setupKvm();    // main: VM + vCPU
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

          // rt_sigreturn(#15): signal handler が trampoline 経由で呼ぶ。被中断 user context を
          //   frame から復元して resume する (RAX 戻り値や handler 起動はしない)。
          if( (int) rax == 15 && restoreSignalFrame() ) {
            ioctl( vcpuFd, KvmBindings.KVM_SET_REGS, regsBuf, "KVM_SET_REGS" );
            continue;
          }

          // resume (ring 3): RAX=戻り値、RIP=stub 内 sysretq。sysretq が RCX→RIP /
          //   R11→RFLAGS / CS=0x33 (ring3) で user に戻すので、RCX (user 復帰先) と
          //   R11 (退避 RFLAGS) は絶対に書き換えない (call_amd64 も regsBuf 不変)。
          setReg( KvmBindings.KVM_REGS_OFF_RAX, ret );
          setReg( KvmBindings.KVM_REGS_OFF_RIP, SYSRETQ_VADDR );
          // pending signal があれば handler 起動に書き換える (red zone + trampoline push、RCX=handler)。
          //   native は syscall 境界でのみ配信するので、被中断点は常に syscall 直後 = RCX/R11 は
          //   syscall ABI で既に dead → sysretq での上書きが許される (delivery も restore も sysretq)。
          deliverPendingSignal();
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
    } catch( SyscallAmd64.ThreadExitException te ) {
      // worker thread の正常 exit(#60): call_amd64 → amd64_exit_thread が投げる。
      //   再 throw して Worker.run の catch で握る (ctid clear + futex wake はそこで)。
      //   main thread は GuestThread でないため exit_thread が投げず、ここには来ない。
      throw te;
    } catch( Throwable t ) {
      throw new RuntimeException(
          ( setupDone ? "NativeCpuBackend eval failed: " : "NativeCpuBackend KVM setup failed: " ) + t, t );
    } finally {
      if( isChild ) teardownVcpu();   // worker: 自分の vcpu fd/mmap/arena だけ閉じる (VM は残す)
      else          teardownKvm();
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

    setupVcpu();   // main vCPU (vcpuId = 0)
  }

  // 1 つの vCPU を long-mode ring-3 + syscall trap で構成する。main (vcpuId=0) と pthread
  //   worker (vcpuId>=1) で共通。VM (vmFd) と guest RAM/page table (guestMem) は既に setup 済で、
  //   ここでは vcpuFd/vcpuState/regsBuf と sregs/MSR/CPUID/初期レジスタだけを設定する。worker は
  //   VM owner と同じ CR3 (guestMem.pml4Phys) を使うので同一アドレス空間を共有し、共有メモリ上の
  //   atomic は実 CPU が複数 vCPU 間で実行する (software GIL 不要)。実行する thread 上で呼ぶこと
  //   (vcpuFd の KVM_RUN/GET/SET_REGS は単一 thread から)。
  private void setupVcpu() throws Throwable {
    // vcpu buffer (vcpuState/sregs/regs/cpuid) の生存域: main は process 寿命の arena、worker は
    //   専用 vcpuArena (teardownVcpu で close) に置く。worker thread 上で確保・利用・解放する。
    Arena buf = isChild ? ( vcpuArena = Arena.ofShared() ) : arena;

    // worker は VM owner と同じ vmFd 上に追加 vCPU を作る。KVM_CREATE_VCPU の arg は vcpu id 値
    //   (ポインタでなくスカラ) なので ofAddress(vcpuId) で渡す (vcpuId=0 は NULL と等価)。
    vcpuFd = KvmBindings.ioctl( vmFd, KvmBindings.KVM_CREATE_VCPU, MemorySegment.ofAddress( vcpuId ) );
    if( vcpuFd < 0 ) throw new IllegalStateException( "KVM_CREATE_VCPU(id=" + vcpuId + ") errno=" + KvmBindings.errno() );
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
    MemorySegment cpuid = buf.allocate( KvmBindings.KVM_CPUID2_OFF_ENTRIES
        + (long) maxEnt * KvmBindings.KVM_CPUID_ENTRY_SIZE );
    cpuid.set( ValueLayout.JAVA_INT, KvmBindings.KVM_CPUID2_OFF_NENT, maxEnt );
    ioctl( kvmFd,  KvmBindings.KVM_GET_SUPPORTED_CPUID, cpuid, "KVM_GET_SUPPORTED_CPUID" );
    ioctl( vcpuFd, KvmBindings.KVM_SET_CPUID2,          cpuid, "KVM_SET_CPUID2" );

    // sregs: long mode ring 3 + EFER.SCE (syscall 有効)。
    //   初回 entry を ring-3 にするため CS=0x33 (RPL3)/SS=0x2b (RPL3) を DPL=3 で設定。
    //   syscall→hlt→sysretq の往復後も hardware が STAR から同じ 0x33/0x2b を再ロードする。
    //   ★ worker も CR3=guestMem.pml4Phys() = main と同じ page table → 同一アドレス空間共有。
    MemorySegment sregs = buf.allocate( KvmBindings.KVM_SREGS_SIZE );
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

    // MSRs: STAR / LSTAR (hlt+sysretq スタブ) / FMASK + 初期 FS base。
    //   STAR[63:48]=0x23 → sysretq で user CS=0x33/SS=0x2b (ring 3)、STAR[47:32]=0x10 →
    //   syscall で kernel CS=0x10/SS=0x18 (ring 0)。FMASK=0: syscall は R11←RFLAGS で user
    //   RFLAGS を退避し、sysretq が R11→RFLAGS で復元するので、stub 実行中に RFLAGS を clear
    //   する必要が無い (stub は hlt;sysretq だけで RFLAGS 非依存)。real Linux の 0x47700 は
    //   kernel handler 実行中の IF/DF clear 用で、本 stub には不要。MSR_FS_BASE は main=0
    //   (arch_prctl 前)、worker=tls (CLONE_SETTLS の TLS pointer)。
    setMsrs( new long[][]{
        { KvmBindings.MSR_STAR,         STAR_VALUE },
        { KvmBindings.MSR_LSTAR,        STUB_VADDR },
        { KvmBindings.MSR_SYSCALL_MASK, 0L         },
        { KvmBindings.MSR_FS_BASE,      fsBase     },
    } );

    // entry point が guest RAM 内か検証 (skip された高位 segment に entry がある等を早期検出)。
    //   未 map (= page table に無い) だと初回 fetch で triple fault になるので早期に弾く。
    if( entryRip < 0 || !guestMem.in( entryRip ) )
      fatalUnsupported( "entry point rip=0x" + Long.toHexString( entryRip ) + " が未 map" );

    // regs: 起動レジスタ状態。main = Linux プロセス起動時 (rsp/rip 以外 0)、worker = clone ABI
    //   (rax=0 で子の戻り値、rsp=child_stack、rip=親の syscall 戻り先)。どちらも fill(0) 後に
    //   rsp/rip/rflags を上書きする = rax を含む他 GPR は 0 (worker の rax=0 が子の clone 戻り値)。
    //   rflags=2 (bit1 予約=1)。
    fpuBuf  = buf.allocate( KvmBindings.KVM_FPU_SIZE );   // signal の x87/XMM 退避復元用 (再利用)
    regsBuf = buf.allocate( KvmBindings.KVM_REGS_SIZE );
    regsBuf.fill( (byte) 0 );
    if( forkRegs != null ) {
      // ★ fork 子: 親の全 GPR を復元してから rax=0 / rip=resume / rsp / rflags=user(R11) を上書き。
      //   これで子は fork syscall 直後 (rax=0 以外は親と同一) の状態で ring-3 から resume する。
      //   被中断点は親の `syscall` 命令直後なので、sysretq 相当の「RIP←RCX, RFLAGS←R11」を直接
      //   register に焼いて初回 KVM_RUN を ring-3 で開始する (LSTAR stub を経由しない初期 entry)。
      for( int i = 0; i < SIGFRAME_OFFS.length; i++ ) setReg( SIGFRAME_OFFS[i], forkRegs[i] );
      setReg( KvmBindings.KVM_REGS_OFF_RAX,    0L );            // 子の fork() 戻り値 = 0
      setReg( KvmBindings.KVM_REGS_OFF_RIP,    entryRip );      // = 親 RCX (Kernel.fork が +2 済) = resume
      setReg( KvmBindings.KVM_REGS_OFF_RSP,    rsp );           // = 親 RSP or clone child_stack
      setReg( KvmBindings.KVM_REGS_OFF_RFLAGS, forkRegs[11] );  // R11 = 親 user RFLAGS (sysretq 復元値)
    } else {
      setReg( KvmBindings.KVM_REGS_OFF_RIP,    entryRip );
      setReg( KvmBindings.KVM_REGS_OFF_RSP,    rsp );
      setReg( KvmBindings.KVM_REGS_OFF_RFLAGS, 2L );
    }
    ioctl( vcpuFd, KvmBindings.KVM_SET_REGS, regsBuf, "KVM_SET_REGS" );
    kvmReady = true;
  }

  private void teardownKvm() {
    // ★ audit fix (use-after-free): worker vCPU は guestMem(poolSeg) と vmFd を共有する。exit_group
    //   (amd64_exit) は worker を待たずに main の eval を抜けるので、ここで shared 資源を即 free すると
    //   まだ走っている worker が call_amd64 内で guestMem(poolSeg) を load/store して host UAF になる
    //   (FFM の reinterpret segment は lifetime guard 無し)。通常の pthread program は exit 前に join
    //   済 (active_thread_count==0) なので影響しないが、detached thread や join 前 exit で発火する。
    //   対策: worker 全停止を bounded-wait (worker は is_exited を見て次 trap で抜け teardownVcpu で
    //   counter を減らす)。なお残るなら shared 資源 (vmFd/kvmFd/poolSeg) の free を skip = process
    //   終了で OS が回収する (leak は限定的、UAF より安全)。vcpu-local (vcpuState/vcpuFd) は常に free。
    if( !isChild && process != null ) {
      long deadline = System.currentTimeMillis() + 3000;
      synchronized( process.active_thread_count ) {
        while( process.active_thread_count.get() > 0 && System.currentTimeMillis() < deadline ) {
          try { process.active_thread_count.wait( 50 ); } catch( InterruptedException ie ) { break; }
        }
      }
    }
    boolean workersGone = isChild || process == null || process.active_thread_count.get() == 0;
    try {
      if( vcpuState != null ) KvmBindings.munmap( vcpuState, vcpuMmapSize );
      if( vcpuFd >= 0 ) KvmBindings.close( vcpuFd );
      if( workersGone ) {   // worker 残存中は shared 資源を絶対に触らない (UAF 回避)
        if( vmFd   >= 0 ) KvmBindings.close( vmFd );
        if( kvmFd  >= 0 ) KvmBindings.close( kvmFd );
        if( poolSeg != null ) KvmBindings.munmap( poolSeg, POOL_SIZE );  // guest 物理 RAM を解放
        // arena (KVM 制御 struct mr/cpuid/sregs/regsBuf + setMsrs 毎の MSR buffer) を解放する。worker は
        //   owner.arena を共有する (worker constructor) が、ここは workersGone 分岐で全 worker 停止済なので
        //   安全。fork を多用する program (shell loop 等) で 1 fork = 1 arena が GC 待ちで溜まるのを防ぐ。
        if( arena != null ) arena.close();
      } else if( System.getenv( "EMULIN_TRACE_BACKEND" ) != null ) {
        System.err.println( "[native] teardownKvm: worker 残存 ("
            + process.active_thread_count.get() + ") のため shared 資源 free を skip (process 終了で OS 回収)" );
      }
    } catch( Throwable ignore ) {}
  }

  // worker vCPU の teardown: 自分の vcpu fd / mmap / per-vcpu arena だけ閉じる。VM (vmFd/kvmFd)
  //   と guest RAM は VM owner (main) が所有するので絶対に閉じない。
  private void teardownVcpu() {
    try {
      if( vcpuState != null ) KvmBindings.munmap( vcpuState, vcpuMmapSize );
      if( vcpuFd >= 0 ) KvmBindings.close( vcpuFd );
      if( vcpuArena != null ) vcpuArena.close();
    } catch( Throwable ignore ) {}
  }

  // ===== pthread: clone(CLONE_VM|CLONE_THREAD) で追加 vCPU を spawn =====
  //   amd64_clone_thread が「clone を呼んだ thread の CPU」(= 親) に対して呼ぶ。子は同一 VM 上の
  //   新 vCPU として、親の syscall 戻り先 (RCX) から rax=0 で実行を再開する (clone ABI)。glibc の
  //   __clone が child_stack 上の start_routine を呼ぶ。futex の WAIT/WAKE は FutexManager 経由で
  //   thread を跨いで動く (backend 非依存)。
  @Override
  public long spawnVCpu( long flags, long child_stack, long ptid, long ctid, long tls ) {
    final long CLONE_PARENT_SETTID  = 0x100000L;
    final long CLONE_CHILD_CLEARTID = 0x200000L;
    final long CLONE_SETTLS         = 0x80000L;

    // VM 資源の真の所有者 (nested clone では this 自身が worker なので owner を辿る)。
    NativeCpuBackend owner = isChild ? vmOwner : this;

    // 子の再開先 = この clone syscall の戻りアドレス (RCX)。親 (this) の regsBuf は現トラップの
    //   register を保持している (eval ループが KVM_GET_REGS 済)。
    long childRip = reg( KvmBindings.KVM_REGS_OFF_RCX );
    long childTls = (flags & CLONE_SETTLS) != 0 ? tls : 0L;

    int  tid       = sysinfo.kernel.next_tid();
    long ctidClear = (flags & CLONE_CHILD_CLEARTID) != 0 ? ctid : 0L;

    NativeCpuBackend child = new NativeCpuBackend( owner, childRip, child_stack, childTls, tid, ctidClear );

    if( System.getenv( "EMULIN_TRACE_BACKEND" ) != null )
      System.err.println( "[native] spawnVCpu tid=" + tid + " vcpuId=" + child.vcpuId
          + " rip=0x" + Long.toHexString( childRip ) + " stack=0x" + Long.toHexString( child_stack )
          + " tls=0x" + Long.toHexString( childTls ) );

    // clone の SETTID 系: 親/子 アドレスに tid を書く (glibc pthread descriptor の tid フィールド)。
    //   ★ audit fix: ptid/ctid が unmapped を指す場合 store32→xlat が IllegalStateException を投げ
    //   eval ループ全体を RuntimeException で落とす (native crash)。software は guest SIGSEGV で済む
    //   ので分岐する。in() guard で skip し native crash を避ける (Worker.run の ctid clear と同方針)。
    if( (flags & CLONE_PARENT_SETTID) != 0 && ptid != 0 && guestMem.in( ptid ) ) guestMem.store32( ptid, tid );
    if( ctid != 0 && guestMem.in( ctid ) ) guestMem.store32( ctid, tid );

    // POSIX: 子 thread は clone を呼んだ thread の signal mask を継承する。get_signal_mask_bits は
    //   呼び出し thread (main or worker = GuestThread) の per-thread mask を返す。
    long parentMask = process.get_signal_mask_bits();
    new Worker( child, parentMask ).start();
    return tid;
  }

  // worker vCPU を走らせる Java thread。GuestThread で syscall 層 (clone 親選択 / gettid /
  //   thread exit 判定 / per-thread signal mask) が backend 非依存に worker を認識する。
  static final class Worker extends Thread implements GuestThread {
    private final NativeCpuBackend child;
    private volatile long signalMask;   // per-thread signal mask (spawn 時に親から継承)
    Worker( NativeCpuBackend c, long initialMask ) {
      super( "emulin-native-vcpu-" + c.vcpuId );
      this.child = c;
      this.signalMask = initialMask;
      setDaemon( true );
      // main thread の exit(#231/#60) が worker 完了を待つための counter。
      c.process.active_thread_count.incrementAndGet();
    }
    @Override public AbstractCpu guestCpu() { return child; }
    @Override public int         guestTid() { return child.childTid; }
    @Override public long        getSignalMask() { return signalMask; }
    @Override public void        setSignalMask( long m ) { signalMask = m; }
    @Override public void run() {
      try {
        child.eval();   // setupVcpu (worker) + KVM_RUN loop
      } catch( SyscallAmd64.ThreadExitException te ) {
        // 正常な thread exit (#60)
      } catch( Throwable t ) {
        System.err.println( "[native] worker vcpu " + child.vcpuId + " (tid=" + child.childTid + ") crashed: " + t );
      } finally {
        // CLONE_CHILD_CLEARTID 慣例: *ctid=0 を書いて futex wake → pthread_join の FUTEX_WAIT
        //   (val=tid) を起こす。ctid が破損 unmapped を指す場合は skip (二次 fault 回避)。
        if( child.childCtidAddr != 0 && child.guestMem.in( child.childCtidAddr ) ) {
          try { child.guestMem.store32( child.childCtidAddr, 0 );
                FutexManager.wake( child.childCtidAddr, Integer.MAX_VALUE ); }
          catch( Throwable ignore ) {}
        }
        FutexManager.onThreadExit( child.childTid );
        synchronized( child.process.active_thread_count ) {
          child.process.active_thread_count.decrementAndGet();
          child.process.active_thread_count.notifyAll();
        }
      }
    }
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
  // set_signal_handler は Process.run の i386/legacy 経路 (cpu.is_interrupt_done() gate) からの
  //   signal 注入 hook。native は ELF64 自己完結 eval (Process.run:382) を使い is_interrupt_done()
  //   は常に false なのでこの経路を通らない。native の signal 配信は eval ループの
  //   deliverPendingSignal()/restoreSignalFrame() で行う。よって no-op (throw すると安全側で誤爆)。
  @Override public void set_signal_handler( long _ip, long goto_adrs ) { /* native: eval ループで配信 */ }
  @Override public boolean is_interrupt_done() { return false; }

  // ===== signal 配信 (syscall 境界、issue #221 step 3d-2c-13) =====

  // ★ FPU-in-signal (issue #221 step 3d-2c-21): vCPU の x87/XMM/MXCSR を byte[] に snapshot する
  //   (KVM_GET_FPU)。eval thread (vcpuFd 所有) 上でのみ呼ぶ。throwing なのは ioctl のみで、frame push
  //   の前に呼ぶので失敗しても sigFrames は不変 (atomic)。
  private byte[] captureFpuSnapshot() {
    try {
      ioctl( vcpuFd, KvmBindings.KVM_GET_FPU, fpuBuf, "KVM_GET_FPU" );
      byte[] snap = new byte[ KvmBindings.KVM_FPU_SIZE ];
      MemorySegment.copy( fpuBuf, ValueLayout.JAVA_BYTE, 0, snap, 0, KvmBindings.KVM_FPU_SIZE );
      return snap;
    } catch( Throwable t ) {
      throw new RuntimeException( "KVM_GET_FPU (signal save) failed: " + t, t );
    }
  }
  // snapshot を vCPU FPU に書き戻す (KVM_SET_FPU)。失敗は eval ループに propagate して process を畳む
  //   (recovery path 無し = 部分復元のまま継続することはない)。
  private void applyFpuSnapshot( byte[] snap ) {
    try {
      MemorySegment.copy( snap, 0, fpuBuf, ValueLayout.JAVA_BYTE, 0, KvmBindings.KVM_FPU_SIZE );
      ioctl( vcpuFd, KvmBindings.KVM_SET_FPU, fpuBuf, "KVM_SET_FPU" );
    } catch( Throwable t ) {
      throw new RuntimeException( "KVM_SET_FPU (signal restore) failed: " + t, t );
    }
  }

  // call_amd64 後 (RAX=戻り値/RIP=SYSRETQ_VADDR 設定済) に呼ぶ。pending signal があれば被中断 user
  //   context を sigFrames に積み、regsBuf を handler 起動状態に書き換える (sysretq で handler へ)。
  private void deliverPendingSignal() {
    int sig = process.psig();
    if( sig < 0 ) return;
    long handler = process.get_func_adrs( sig );
    process.signal_cancel( sig );
    if( handler == Siginfo.SIG_IGN ) return;
    if( handler == Siginfo.SIG_DFL ) {
      if( process.get_action_type( sig ) == Signal.SIGACTION_EXIT ) process.set_exit_flag();
      return;                              // SIG_DFL の ignore/stop は無視 (software check_pending_signal と同じ)
    }
    // 被中断 user context = この syscall の通常 resume 後の状態。regsBuf は RAX=ret/RIP=SYSRETQ に
    //   書換済だが、RCX(=user 復帰 addr)・R11(=user RFLAGS)・RSP は未変更なのでそこから user 値を取る。
    long userRip = reg( KvmBindings.KVM_REGS_OFF_RCX );
    long userFlg = reg( KvmBindings.KVM_REGS_OFF_R11 );
    long userRsp = reg( KvmBindings.KVM_REGS_OFF_RSP );
    long[] f = new long[ SIGFRAME_OFFS.length + 1 ];
    for( int i = 0; i < SIGFRAME_OFFS.length; i++ ) f[i] = reg( SIGFRAME_OFFS[i] );
    f[16] = userRip;                       // RIP idx: SYSRETQ_VADDR を user 復帰 addr で上書き
    f[17] = userFlg;                       // RFLAGS idx: ring0 RFLAGS を user RFLAGS(R11) で上書き
    f[18] = process.get_signal_mask_bits();
    // ★ FPU-in-signal: この時点の vCPU FPU は被中断コードのもの (syscall は x87/XMM を変えない)。
    //   handler が clobber しても rt_sigreturn で復元できるよう snapshot し、GPR frame と 1 つの SigFrame に
    //   まとめて push する。snapshot (throwing な KVM_GET_FPU) を push の前に取るので、失敗しても sigFrames
    //   は不変 = GPR/FPU が片方だけ積まれる desync が起きない。
    byte[] fpu = captureFpuSnapshot();
    sigFrames.push( new SigFrame( f, fpu ) );
    // ハンドラ実行中の mask: 現 mask + sa_mask + (SA_NODEFER でなければ) sig 自身
    long nm = f[18] | process.get_sa_mask( sig );
    if( !process.has_sa_nodefer( sig ) && sig >= 1 && sig < 32 ) nm |= (1L << (sig - 1));
    process.set_signal_mask_bits( nm );
    // handler 用 stack: red zone(128) skip → 16-align → (SA_SIGINFO なら siginfo/ucontext) →
    //   trampoline push。trampoline push 後に RSP%16==8 = ABI の callee-entry 規約。real CPU は
    //   movaps で 16-align を要求するので software (緩い) と違い必ず align する。
    long rsp = (userRsp - 128) & ~15L;
    long siginfo = 0, uctx = 0;
    if( process.has_sa_siginfo( sig ) ) {
      rsp -= 128; siginfo = rsp;
      guestMem.bulkZero( siginfo, 128 );
      guestMem.store32( siginfo, sig );    // si_signo
      rsp -= 256; uctx = rsp;
      guestMem.bulkZero( uctx, 256 );
      // uc_mcontext gregs (ucontext+40..): software check_pending_signal と同じ layout
      guestMem.store64( uctx + 40,  f[8]  );   // r8
      guestMem.store64( uctx + 48,  f[9]  );   // r9
      guestMem.store64( uctx + 56,  f[10] );   // r10
      guestMem.store64( uctx + 64,  f[11] );   // r11
      guestMem.store64( uctx + 72,  f[12] );   // r12
      guestMem.store64( uctx + 80,  f[13] );   // r13
      guestMem.store64( uctx + 88,  f[14] );   // r14
      guestMem.store64( uctx + 96,  f[15] );   // r15
      guestMem.store64( uctx + 104, f[5]  );   // rdi
      guestMem.store64( uctx + 112, f[4]  );   // rsi
      guestMem.store64( uctx + 120, f[7]  );   // rbp
      guestMem.store64( uctx + 128, f[1]  );   // rbx
      guestMem.store64( uctx + 136, f[3]  );   // rdx
      guestMem.store64( uctx + 144, f[0]  );   // rax
      guestMem.store64( uctx + 152, f[2]  );   // rcx
      guestMem.store64( uctx + 160, f[6]  );   // rsp
      guestMem.store64( uctx + 168, userRip ); // rip (割込み点)
      // eflags: software (Cpu64.check_pending_signal) は status flags のみ再構築する
      //   (2 | CF | PF | ZF | SF | OF)。byte 一致のため R11 を同じ bit に mask する
      //   (DF(bit10)/AF(bit4)/TF 等は software が落とすので native も落とす)。
      guestMem.store64( uctx + 176, (userFlg & 0x8C5L) | 0x2L );
    }
    rsp -= 8;
    guestMem.store64( rsp, SIGTRAMP_VADDR );   // handler の ret 着地先 = trampoline
    setReg( KvmBindings.KVM_REGS_OFF_RSP, rsp );
    setReg( KvmBindings.KVM_REGS_OFF_RCX, handler );  // sysretq → RIP=handler
    setReg( KvmBindings.KVM_REGS_OFF_R11, 2L );       // handler 進入時 RFLAGS (clean、bit1=1)
    setReg( KvmBindings.KVM_REGS_OFF_RDI, (long) sig );
    if( siginfo != 0 ) {
      setReg( KvmBindings.KVM_REGS_OFF_RSI, siginfo );
      setReg( KvmBindings.KVM_REGS_OFF_RDX, uctx );
    }
    // RAX は ret のまま (handler は依存しない)、RIP は SYSRETQ_VADDR のまま。
  }

  // rt_sigreturn(#15): sigFrames から被中断 context を復元し regsBuf を resume 状態にする。
  //   被中断点は syscall 直後なので RCX/R11 は ABI で dead = sysretq の RIP←RCX/RFLAGS←R11 で上書き
  //   して良い。frame 無し (spurious rt_sigreturn) は false を返し通常 resume に委ねる。
  private boolean restoreSignalFrame() {
    SigFrame sf = sigFrames.pollFirst();
    if( sf == null ) return false;
    long[] f = sf.regs;
    applyFpuSnapshot( sf.fpu );   // ★ FPU-in-signal: 被中断側の x87/XMM/MXCSR を復元 (handler の clobber を上書き)
    setReg( KvmBindings.KVM_REGS_OFF_RAX, f[0] );
    setReg( KvmBindings.KVM_REGS_OFF_RBX, f[1] );
    setReg( KvmBindings.KVM_REGS_OFF_RDX, f[3] );
    setReg( KvmBindings.KVM_REGS_OFF_RSI, f[4] );
    setReg( KvmBindings.KVM_REGS_OFF_RDI, f[5] );
    setReg( KvmBindings.KVM_REGS_OFF_RSP, f[6] );
    setReg( KvmBindings.KVM_REGS_OFF_RBP, f[7] );
    setReg( KvmBindings.KVM_REGS_OFF_R8,  f[8] );
    setReg( KvmBindings.KVM_REGS_OFF_R9,  f[9] );
    setReg( KvmBindings.KVM_REGS_OFF_R10, f[10] );
    setReg( KvmBindings.KVM_REGS_OFF_R12, f[12] );
    setReg( KvmBindings.KVM_REGS_OFF_R13, f[13] );
    setReg( KvmBindings.KVM_REGS_OFF_R14, f[14] );
    setReg( KvmBindings.KVM_REGS_OFF_R15, f[15] );
    // sysretq: RIP←RCX, RFLAGS←R11 (user の RCX/R11 は syscall ABI で既に dead なので上書き可)。
    setReg( KvmBindings.KVM_REGS_OFF_RCX, f[16] );   // user 復帰 RIP
    setReg( KvmBindings.KVM_REGS_OFF_R11, f[17] );   // user RFLAGS
    setReg( KvmBindings.KVM_REGS_OFF_RIP, SYSRETQ_VADDR );
    process.set_signal_mask_bits( f[18] );           // mask 復元
    return true;
  }

  @Override public void fetch( long address, byte[] buf ) {
    // native では命令 fetch は vCPU が直接行う。debug 用に guest RAM から読む。
    if( guestMem != null ) guestMem.fetch( address, buf );
  }

  @Override public String reg_str()                  { return "<native vCPU regs>"; }
  @Override public String ip_str()                   { return "<native rip>"; }
  @Override public String flag_str()                 { return "<native rflags>"; }
  @Override public String disasm_str( long address ) { return "<native>"; }
}
