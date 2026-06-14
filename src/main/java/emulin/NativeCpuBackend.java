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
  //   DATA_BASE) は ~2000 PT page = 最大 ~4GB の疎な vaddr をカバーできる。
  //   ★ 既定 512MB。大きな footprint の binary (claude CLI = bun/JSC は 247MB ELF + 512MB JS heap mmap
  //   で 512MB 枯渇) には EMULIN_NATIVE_POOL_MB=2048 等で拡大できる。lazy mmap なので default を上げても
  //   実 RAM コストは触った分だけだが、WHP の VirtualAlloc(MEM_COMMIT) は commit を charge するので
  //   default は据置 (oracle 無影響) とし env で opt-in する。
  private static final long POOL_SIZE     = parsePoolSize();
  private static long parsePoolSize() {
    long mb = 512;
    String e = System.getenv( "EMULIN_NATIVE_POOL_MB" );
    if( e != null ) { try { mb = Long.parseLong( e.trim() ); } catch( NumberFormatException ignore ) {} }
    if( mb < 16 ) mb = 512;   // 最低限の sanity
    return mb * 1024L * 1024L;
  }
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
  // signal frame / fork snapshot の register layout: long[0..17] = HvReg.{RAX..RFLAGS} 順
  //   (HvReg.RAX=0..HvReg.RFLAGS=17 で hv.getGpr(i)/setGpr(i) と 1:1)、long[18] = 保存 signal mask。

  // ---- state ----
  private long          entryRip;
  private long          rsp = 0;          // 初期 RSP (setup_initial_stack で確定)
  private SyscallAmd64  sys64;
  private NativeMemoryBackend guestMem;
  private Arena         arena;       // guest RAM の生存期間 (process 寿命)
  private boolean       kvmReady;

  // ★ VM-level の hypervisor 抽象 (#221 WHP 移植 Stage 2)。main が所有、worker は共有、fork 子は新規。
  //   open(/dev/kvm)+CREATE_VM+guest RAM map を担い、KVM ⇄ WHP の VM-API 差を実装 (KvmVm/将来 WhpVm) に閉じる。
  private HvVm          vm;
  private MemorySegment poolSeg;     // guest 物理 RAM の host backing (HvVm.allocGuestRam、teardown で freeGuestRam)
  private WhpGpaBacking gpaBacking;   // WHP lazy commit hook (issue #304、commit-on-map)。KVM は null
  // ★ この vCPU の hypervisor 抽象 (#221 WHP 移植 Stage 1)。register/sregs/MSR/FPU/run を担い、
  //   KVM struct offset ⇄ WHP name-value array の差を実装 (KvmVcpu / 将来 WhpVcpu) に閉じ込める。
  private HvVcpu        hv;

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
    final long[] regs;     // SIGFRAME_OFFS 順 (RAX..R15,RIP,RFLAGS) + [18]=保存 signal mask
    final byte[] fpu;      // KVM_GET_FPU snapshot (struct kvm_fpu、x87/XMM0-15/MXCSR)
    final boolean async;   // ★ true=async 配信 (被中断点は任意命令、全 GPR live)。restore は ring3 直接遷移
                           //   (sysretq は RCX/R11 を潰すので不可)。false=syscall 境界配信 (sysretq 復帰)。
    final long uctxAddr;   // ★ SA_SIGINFO で guest stack に置いた ucontext のアドレス (0=無し)。async 復帰は
                           //   handler が改変しうる ここの uc_mcontext から復元する (Go の preemption は
                           //   ucontext を書換えて実行をリダイレクトするので、内部 frame でなく ここを尊重する)。
    SigFrame( long[] r, byte[] f, boolean a, long u ) { regs = r; fpu = f; async = a; uctxAddr = u; }
  }
  private final java.util.ArrayDeque<SigFrame> sigFrames = new java.util.ArrayDeque<>();

  // ---- platform 判定 (issue #221 WHP 移植 Stage A) ----
  //   ★ KVM (Linux) か WHP (Windows) かの単一の真実。HvVm.create() の dispatch と同じ KvmBindings.probe()
  //   を使う (Linux=true / Windows=false、probe は cache 済)。async signal kick の機構が platform で異なる:
  //   KVM = host signal (sun.misc.Signal + tgkill) で KVM_RUN を EINTR 脱出、WHP =
  //   WHvCancelRunVirtualProcessor で走行中 run を Canceled exit (step 3e-whp-6)。
  private static final boolean IS_KVM = KvmBindings.probe();

  // ---- async signal 配信基盤 (issue #221 step 3d-2c-39 KVM / 3e-whp-6 WHP) ----
  //   走行中 vCPU の guest thread に signal が queue されたら、その vCPU の run を中断して async 配信する
  //   (Go の async preemption = tight-loop 中 goroutine への SIGURG 等、syscall を待たずに届ける)。
  //   KVM: 走行 host thread に host SIG_KICK を tgkill → KVM_RUN が EINTR → EXIT_INTR。
  //   WHP: HvVcpu.kick() = WHvCancelRunVirtualProcessor → run が Canceled exit → EXIT_INTR。
  private static volatile int sigKickNum = -1;   // host SIG_KICK の signal 番号 (sun.misc.Signal、KVM のみ)
  private static volatile int hostPid    = 0;    // tgkill 用 host pid (= thread group id、KVM のみ)
  private static volatile boolean asyncInfraDone = false;
  // KVM: guest tid → 走行中 vCPU の host Linux TID。eval 開始で put、exit で remove。
  private static final java.util.concurrent.ConcurrentHashMap<Integer,Integer> RUNNING_TIDS =
      new java.util.concurrent.ConcurrentHashMap<>();
  // WHP: guest tid → 走行中 HvVcpu (kick = WHvCancelRunVirtualProcessor)。eval 開始で put、exit で remove。
  private static final java.util.concurrent.ConcurrentHashMap<Integer,HvVcpu> RUNNING_VCPUS =
      new java.util.concurrent.ConcurrentHashMap<>();
  private int hostTid = -1;   // この vCPU を走らせる Java thread の Linux TID
  private int myGuestTid() { return isChild ? childTid : process.pid; }

  /** asyncKick hook の設定 (一度だけ)。KVM = host SIG_KICK handler install、WHP = cancel-run kicker。 */
  private static synchronized void ensureAsyncInfra() {
    if( asyncInfraDone ) return;
    if( IS_KVM ) {
      int num = -1;
      for( String nm : new String[]{ "USR2", "URG", "PWR", "IO" } ) {
        try {
          sun.misc.Signal s = new sun.misc.Signal( nm );
          sun.misc.Signal.handle( s, sig -> { /* no-op: KVM_RUN を EINTR 脱出させるためだけ */ } );
          num = s.getNumber();
          break;
        } catch( Throwable ignore ) {}
      }
      try { hostPid = KvmBindings.getpidHost(); } catch( Throwable t ) { hostPid = 0; }
      sigKickNum = num;   // 失敗時 0 以下 → kick は no-op (= 従来の syscall 境界配信のみ)
      if( num > 0 ) Signal.asyncKick = NativeCpuBackend::kickGuestTid;
    } else {
      // WHP (step 3e-whp-6): WHvCancelRunVirtualProcessor は別 thread から走行中/次回の run を
      //   Canceled exit にできる (KVM の tgkill+EINTR 相当)。host signal/tgkill は一切使わない。
      Signal.asyncKick = NativeCpuBackend::kickGuestVcpu;
    }
    asyncInfraDone = true;
  }

  /** signal queue 直後に Signal 層から呼ばれる (KVM): 走行中 vCPU を host signal で kick する。 */
  private static void kickGuestTid( int targetTid ) {
    int sig = sigKickNum, pid = hostPid;
    if( sig <= 0 || pid <= 0 ) return;
    try {
      if( targetTid == -1 ) {   // process-wide: 全走行中 vCPU を kick
        for( Integer t : RUNNING_TIDS.values() ) KvmBindings.tgkill( pid, t, sig );
      } else {
        Integer t = RUNNING_TIDS.get( targetTid );
        if( t != null ) KvmBindings.tgkill( pid, t, sig );
      }
    } catch( Throwable ignore ) {}
  }

  /** signal queue 直後に Signal 層から呼ばれる (WHP): 走行中 vCPU の run を cancel する。best-effort。 */
  private static void kickGuestVcpu( int targetTid ) {
    try {
      if( targetTid == -1 ) {   // process-wide: 全走行中 vCPU を kick
        for( HvVcpu v : RUNNING_VCPUS.values() ) { try { v.kick(); } catch( Throwable ignore ) {} }
      } else {
        HvVcpu v = RUNNING_VCPUS.get( targetTid );
        if( v != null ) v.kick();
      }
    } catch( Throwable ignore ) {}
  }

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

  // ---- fork (issue #221 step 3d-2c-20) ----
  //   fork 子は worker (CLONE_THREAD、VM 共有) と違い、独立 VM + 複製 guestMem を持つ別 process。
  //   duplicate() で親の trap 時 register snapshot (forkRegs) を取り、connect_devices の fork 分岐で
  //   親 guestMem を子の新プールに複製する (ELF reload しない)。setupVcpu の fork 分岐で forkRegs を
  //   復元し rax=0/rip=resume で起動する。null = 通常 boot、非 null = fork 子。
  private NativeCpuBackend forkParent;
  private long[]           forkRegs;          // SIGFRAME_OFFS 順 (RAX..R15,RIP,RFLAGS) の親 snapshot
  // clone(thread) 子が親から継承する全 GPR snapshot (HvReg 索引 0..COUNT-1)。Linux clone は子の
  //   register を親と同一にする (rax=0/rsp=child_stack のみ差分)。glibc pthread は start 関数を
  //   stack/TLS から読むので rsp だけで足りたが、Go runtime.clone は `call *%r12` で mstart を
  //   register 経由で渡すため親の r12/r13/r8 等の継承が必須 (step 3d-2c-37、未継承だと r12=0 で
  //   call 0→triple fault)。null = glibc 流の最小起動 (後方互換)、非 null = 親 GPR 継承。
  private long[]           cloneRegs;

  public NativeCpuBackend( Sysinfo _sysinfo, Process _process ) {
    sysinfo = _sysinfo;
    process = _process;
  }

  // pthread worker (追加 vCPU) 用の private constructor。VM 資源を owner から共有し、子の
  //   初期レジスタ (clone ABI: rax=0、rip=親の syscall 戻り先、rsp=child_stack、fs=tls) を持つ。
  private NativeCpuBackend( NativeCpuBackend owner, long childRip, long childStack,
                            long tls, int tid, long ctidAddr, long[] inheritRegs ) {
    this.sysinfo   = owner.sysinfo;
    this.process   = owner.process;
    this.vmOwner   = owner;
    this.isChild   = true;
    this.vcpuId    = owner.nextVcpuId.getAndIncrement();
    // VM 共有資源 (read-only に扱う): guest RAM/page table、VM (kvmFd/vmFd を内包)、syscall 層。
    this.vm        = owner.vm;
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
    this.cloneRegs     = inheritRegs;
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
    long[] snap = new long[ HvReg.COUNT ];   // [0]=RAX..[15]=R15,[16]=RIP,[17]=RFLAGS
    for( int i = 0; i < HvReg.COUNT; i++ ) snap[i] = src.hv.getGpr( i );
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

    // off-heap 物理プールを確保 (VM が guest 物理 0 に map、process 寿命で leak 許容)。
    arena    = Arena.ofShared();
    // guest 物理 RAM の host backing を HvVm.allocGuestRam で確保する (KVM=mmap MAP_ANON / WHP=VirtualAlloc)。
    //   mmap(MAP_ANON) は OS demand paging で未 touch ページを backing しないので、大きな pool でも実際に
    //   使った分 (page table + guest が write した data ページ) しか実 RAM を食わない。これで POOL_SIZE を
    //   余裕を持って取れる (8 worker × 8MB stack 等の memory-heavy program に対応)。
    // ★ connect_devices は boot path: 例外を投げると非 daemon init thread が JVM を生かし hang する
    //   (#245)。pool 確保失敗 (essentially あり得ないが) は fatalUnsupported (System.exit) で落とす。
    try {
      poolSeg = HvVm.allocGuestRam( POOL_SIZE );
    } catch( Throwable t ) {
      fatalUnsupported( "guest RAM 確保失敗 (pool " + POOL_SIZE + "): " + t );
    }
    guestMem = new NativeMemoryBackend( poolSeg );
    // ★ fork-on-WHP (step 3e-whp-7): WHP は JVM 全体で単一 partition を共有し、process ごとに
    //   GPA slot (POOL_SIZE 刻み) を確保して pool を map する (1-partition-per-process 制限の回避)。
    //   page table entry は gpaBase + pool offset を格納するので、最初の mapPage より前に設定する。
    //   KVM は per-process VM (gpa 0) のままなので slot 確保しない (= 従来と byte-identical)。
    if( !IS_KVM ) {
      guestMem.setGpaBase( WhpVm.allocSlot( POOL_SIZE ) );
      // issue #304 lazy commit: pool は MEM_RESERVE のみ確保済 → allocPt/allocData の chunk を
      //   WhpGpaBacking が commit+map する (commit charge を guest 実使用量に比例)。setGpaBase の後・
      //   enableMmu (PML4 chunk を ensure) の前に attach すること。
      gpaBacking = new WhpGpaBacking( poolSeg.address(), guestMem.gpaBase(), POOL_SIZE );
      guestMem.setGpaBacking( gpaBacking );
    }
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
    // 子専用の物理プールを確保 (boot path と同じ。未 touch ページは backing しない)。
    try {
      poolSeg = HvVm.allocGuestRam( POOL_SIZE );
    } catch( Throwable t ) {
      fatalUnsupported( "fork: 子 guest RAM 確保失敗 (pool " + POOL_SIZE + "): " + t );
    }

    // 親アドレス空間を子プールへ複製。KVM は page table の pool-relative 物理 offset がそのまま子で
    //   valid (childGpaBase=0)。WHP (step 3e-whp-7) は子も同一 partition 内の別 GPA slot に map する
    //   ので、duplicate が全 page table entry を child slot base に rebase する。
    long childGpaBase = IS_KVM ? 0L : WhpVm.allocSlot( POOL_SIZE );
    // issue #304 lazy commit (WHP): 子 pool も MEM_RESERVE のみ → child の commit-on-map hook を作り
    //   duplicate に渡す (duplicate が verbatim copy の前に child [0,dataNext) を commit する)。KVM は null。
    if( !IS_KVM ) gpaBacking = new WhpGpaBacking( poolSeg.address(), childGpaBase, POOL_SIZE );
    guestMem = forkParent.guestMem.duplicate( poolSeg, childGpaBase, gpaBacking );
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
      try { hv.setMsrs( new long[][]{ { KvmBindings.MSR_FS_BASE, base } } ); }
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
    guestMem.seedStack( stackBottom );   // genProcSelfMaps が stack region を [stack] と報告する用
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
      // ★ async signal 基盤の登録 (step 3d-2c-39 KVM / 3e-whp-6 WHP): この vCPU を「走行中」として
      //   登録する。signal が queue されたら KVM は kickGuestTid (tgkill)、WHP は kickGuestVcpu
      //   (WHvCancelRunVirtualProcessor) が run を中断して async 配信させる。
      ensureAsyncInfra();
      if( IS_KVM ) {
        // KVM: host TID を登録 (tgkill 用)。gettid は Linux syscall 186 なので KVM のみ。
        try { hostTid = KvmBindings.gettid(); RUNNING_TIDS.put( myGuestTid(), hostTid ); }
        catch( Throwable ignore ) {}
      } else {
        // WHP: HvVcpu 自体を登録 (kick = cancel-run は vCPU handle で行う)。
        RUNNING_VCPUS.put( myGuestTid(), hv );
      }
      while( !process.is_exited() ) {
        int exitReason = hv.run();
        if( exitReason == HvVcpu.EXIT_HALT ) {
          // syscall trap: regs を読み call_amd64 に dispatch
          hv.readGprs();
          long rax = hv.getGpr( HvReg.RAX );
          long rdi = hv.getGpr( HvReg.RDI );
          long rsi = hv.getGpr( HvReg.RSI );
          long rdx = hv.getGpr( HvReg.RDX );
          long r10 = hv.getGpr( HvReg.R10 );
          long r8  = hv.getGpr( HvReg.R8 );
          long r9  = hv.getGpr( HvReg.R9 );
          long rcx = hv.getGpr( HvReg.RCX );  // syscall 直後アドレス
          syscallCount++;
          if( trace ) System.err.println( "[native] syscall #" + syscallCount + " sysno=" + rax
              + " args=(" + rdi + "," + rsi + "," + rdx + "," + r10 + "," + r8 + "," + r9 + ")" );

          long ret = sys64.call_amd64( rax, rdi, rsi, rdx, r10, r8, r9 );

          if( process.is_exited() ) break;  // exit / exit_group

          // rt_sigreturn(#15): signal handler が trampoline 経由で呼ぶ。被中断 user context を
          //   frame から復元して resume する (RAX 戻り値や handler 起動はしない)。
          if( (int) rax == 15 && restoreSignalFrame() ) {
            hv.writeGprs();
            continue;
          }

          // resume (ring 3): RAX=戻り値、RIP=stub 内 sysretq。sysretq が RCX→RIP /
          //   R11→RFLAGS / CS=0x33 (ring3) で user に戻すので、RCX (user 復帰先) と
          //   R11 (退避 RFLAGS) は絶対に書き換えない (call_amd64 も regsBuf 不変)。
          hv.setGpr( HvReg.RAX, ret );
          hv.setGpr( HvReg.RIP, SYSRETQ_VADDR );
          // pending signal があれば handler 起動に書き換える (red zone + trampoline push、RCX=handler)。
          //   native は syscall 境界でのみ配信するので、被中断点は常に syscall 直後 = RCX/R11 は
          //   syscall ABI で既に dead → sysretq での上書きが許される (delivery も restore も sysretq)。
          deliverPendingSignal();
          hv.writeGprs();
          continue;
        } else if( exitReason == HvVcpu.EXIT_INTR ) {
          // ★ host signal で KVM_RUN が割込まれた (step 3d-2c-39)。SIG_KICK (走行中 vCPU への async
          //   配信 kick) でも JVM GC/safepoint signal でも起きる。pending guest signal があれば
          //   async 配信し (= guest handler を被中断点で起動)、無ければ単に再 run する。
          if( process.is_exited() ) break;
          hv.readGprs();
          deliverPendingSignal( true );   // async: 被中断点で handler 起動 (pending 無ければ no-op)
          hv.writeGprs();
          continue;
        } else {
          // 非 HLT exit (MMIO/IO/INTERNAL_ERROR/FAIL_ENTRY/SHUTDOWN 等) は MVP では
          //   未対応。診断 (rip + 全 GPR) を出して guest を error 終了させる (無限ループ回避)。
          //   raw exit_reason=8 (KVM_EXIT_SHUTDOWN) は IDT 無しでの triple fault = guest が未対応
          //   命令/メモリアクセスをした症状。rip を objdump で逆アセンブルし faulting 命令を特定。
          long rip = 0;
          try { hv.readGprs(); rip = hv.getGpr( HvReg.RIP ); } catch( Throwable ignore ) {}
          System.err.println( "[native] unexpected hypervisor exit raw=" + hv.lastRawExit()
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
      RUNNING_TIDS.remove( myGuestTid(), hostTid );   // async kick 対象から外す (step 3d-2c-39)
      if( hv != null ) RUNNING_VCPUS.remove( myGuestTid(), hv );   // WHP kick 対象から外す (3e-whp-6)
      if( isChild ) teardownVcpu();   // worker: 自分の vcpu fd/mmap/arena だけ閉じる (VM は残す)
      else          teardownKvm();
    }
    if( trace ) System.err.println( "[native] eval done: " + syscallCount
        + " syscalls, exit_code=" + process.exit_code );
    return 0;
  }

  // ===== KVM setup / teardown =====

  private void setupKvm() throws Throwable {
    // KVM = per-process VM (open(/dev/kvm) + KVM_CREATE_VM、guest 物理 0 に map)。
    // WHP (step 3e-whp-7) = JVM 全体で単一 partition を共有し、この process の pool を自分の
    //   GPA slot (guestMem.gpaBase()、connect_devices/connect_fork で確保済) に map する。
    //   これで fork/exec の「2 つ目 partition の map 不可」(1-partition-per-process、§4.4rr) を回避。
    vm = IS_KVM ? HvVm.create() : WhpVm.global();
    if( IS_KVM ) {
      vm.mapGuestRam( guestMem.gpaBase(), guestMem.address(), guestMem.sizeBytes() );
    } else {
      // issue #304 lazy commit: pool 全域を 1 回 map する代わりに、起動までに commit 済の chunk だけを
      //   map する (WhpGpaBacking が以後 alloc 毎に commit+map)。★ setupVcpu (CR3=PML4) より前に
      //   bindVm/flushMaps を完了させる (PML4/PT/PT_LOAD chunk が未 map だと初回 fetch が triple fault)。
      gpaBacking.bindVm( (WhpVm) vm );
    }
    setupVcpu();           // main vCPU (vcpuId = 0)
  }

  // 1 つの vCPU を long-mode ring-3 + syscall trap で構成する。main (vcpuId=0) と pthread
  //   worker (vcpuId>=1) で共通。VM (vmFd) と guest RAM/page table (guestMem) は既に setup 済で、
  //   ここでは vcpuFd/vcpuState/regsBuf と sregs/MSR/CPUID/初期レジスタだけを設定する。worker は
  //   VM owner と同じ CR3 (guestMem.pml4Phys) を使うので同一アドレス空間を共有し、共有メモリ上の
  //   atomic は実 CPU が複数 vCPU 間で実行する (software GIL 不要)。実行する thread 上で呼ぶこと
  //   (vcpuFd の KVM_RUN/GET/SET_REGS は単一 thread から)。
  private void setupVcpu() throws Throwable {
    // hv (KvmVcpu) を作る: KVM_CREATE_VCPU + vcpu-state mmap + 制御 struct 確保。main は arena を
    //   共有 (NativeCpuBackend が teardownKvm で close)、worker は専用 arena を hv が所有 (ownArena=true、
    //   hv.close で解放)。worker thread 上で構築すること (KVM_RUN/GET/SET は単一 thread)。
    Arena buf = isChild ? Arena.ofShared() : arena;
    try {
      hv = vm.createVcpu( vcpuId, buf, /*ownArena*/ isChild );
    } catch( Throwable t ) {
      // KvmVcpu ctor は自分の vcpuFd/mmap を self-clean するが、worker 専用 arena (buf) は caller 所有
      //   なので失敗時はここで閉じる (main は arena=共有なので閉じない、teardownKvm が処理)。
      if( isChild ) { try { buf.close(); } catch( Throwable ignore ) {} }
      throw t;
    }

    // CPUID: host のサポート CPUID を vCPU に流す。glibc 2.33+ は CPUID で CPU ISA level を確認する
    //   ので未設定だと libc.so が "ISA level lower than required" で abort する。
    hv.setCpuidFromHost();

    // sregs: long mode ring 3。CS=0x33 (code64、RPL3)/DS..SS=0x2b (data、RPL3) を DPL=3、
    //   CR0/CR3/CR4/EFER を設定。syscall→hlt→sysretq の往復後も hardware が STAR から同じ
    //   0x33/0x2b を再ロードする。★ worker も CR3=guestMem.pml4Phys() = main と同じ page table →
    //   同一アドレス空間共有。selector/CR/EFER の Linux ABI 値はここ (NativeCpuBackend) が単一の
    //   真実として渡し、KVM struct ⇄ WHP name-value の encoding 差は hv 実装に閉じる。
    hv.configureLongModeRing3( 0x33, 0x2b, /*dpl*/3,
        KvmBindings.CR0_LONG_MODE, guestMem.pml4Phys(),
        KvmBindings.CR4_PAE | KvmBindings.CR4_OSFXSR | KvmBindings.CR4_OSXMMEXCPT,   // SSE 有効化
        KvmBindings.EFER_LME | KvmBindings.EFER_LMA | KvmBindings.EFER_SCE );

    // MSRs: STAR / LSTAR (hlt+sysretq スタブ) / FMASK + 初期 FS base。
    //   STAR[63:48]=0x23 → sysretq で user CS=0x33/SS=0x2b (ring 3)、STAR[47:32]=0x10 →
    //   syscall で kernel CS=0x10/SS=0x18 (ring 0)。FMASK=0: syscall は R11←RFLAGS で user
    //   RFLAGS を退避し、sysretq が R11→RFLAGS で復元するので stub 実行中に RFLAGS clear 不要。
    //   MSR_FS_BASE は main=0 (arch_prctl 前)、worker=tls (CLONE_SETTLS の TLS pointer)。
    hv.setMsrs( new long[][]{
        { KvmBindings.MSR_STAR,         STAR_VALUE },
        { KvmBindings.MSR_LSTAR,        STUB_VADDR },
        { KvmBindings.MSR_SYSCALL_MASK, 0L         },
        { KvmBindings.MSR_FS_BASE,      fsBase     },
    } );

    // entry point が guest RAM 内か検証 (skip された高位 segment に entry がある等を早期検出)。
    //   未 map (= page table に無い) だと初回 fetch で triple fault になるので早期に弾く。
    if( entryRip < 0 || !guestMem.in( entryRip ) )
      fatalUnsupported( "entry point rip=0x" + Long.toHexString( entryRip ) + " が未 map" );

    // 起動レジスタ。hv の GPR cache は新規確保で 0 初期化済 (Arena.allocate) なので、設定しない
    //   GPR は 0。main = Linux プロセス起動時 (rsp/rip 以外 0)、worker = clone ABI (rax=0 で子の
    //   戻り値、rsp=child_stack、rip=親の syscall 戻り先)、fork = 親 GPR 復元 + rax=0/rip=resume。
    //   rflags=2 (bit1 予約=1)。
    if( forkRegs != null ) {
      // ★ fork 子: 親の全 GPR を復元してから rax=0 / rip=resume / rsp / rflags=user(R11) を上書き。
      //   被中断点は親の `syscall` 命令直後なので、sysretq 相当の「RIP←RCX, RFLAGS←R11」を直接
      //   register に焼いて初回 run を ring-3 で開始する (LSTAR stub を経由しない初期 entry)。
      for( int i = 0; i < HvReg.COUNT; i++ ) hv.setGpr( i, forkRegs[i] );
      hv.setGpr( HvReg.RAX,    0L );            // 子の fork() 戻り値 = 0
      hv.setGpr( HvReg.RIP,    entryRip );      // = 親 RCX (Kernel.fork が +2 済) = resume
      hv.setGpr( HvReg.RSP,    rsp );           // = 親 RSP or clone child_stack
      hv.setGpr( HvReg.RFLAGS, forkRegs[11] );  // R11 = 親 user RFLAGS (sysretq 復元値)
    } else if( cloneRegs != null ) {
      // ★ clone(thread) 子: Linux 同様に親の全 GPR を継承し、rax=0(子の clone 戻り値) /
      //   rip=親の syscall 戻り先(RCX) / rsp=child_stack のみ上書きする (step 3d-2c-37)。
      //   Go runtime.clone は r12=mstart fn 等を register で渡すため全 GPR 継承が必須。
      //   被中断点は親の `syscall` 命令直後 = RCX なので RFLAGS は親の R11 を復元 (sysretq 相当)。
      for( int i = 0; i < HvReg.COUNT; i++ ) hv.setGpr( i, cloneRegs[i] );
      hv.setGpr( HvReg.RAX,    0L );
      hv.setGpr( HvReg.RIP,    entryRip );       // = 親 RCX (clone syscall の戻り先)
      hv.setGpr( HvReg.RSP,    rsp );            // = clone child_stack
      hv.setGpr( HvReg.RFLAGS, cloneRegs[11] );  // R11 = 親 user RFLAGS
    } else {
      hv.setGpr( HvReg.RIP,    entryRip );
      hv.setGpr( HvReg.RSP,    rsp );
      hv.setGpr( HvReg.RFLAGS, 2L );
    }
    hv.writeGprs();
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
      if( hv != null ) hv.close();   // この vcpu の vcpuFd + run-state mmap (main の arena は下で close)
      if( workersGone ) {   // worker 残存中は shared 資源を絶対に触らない (UAF 回避)
        if( vm != null ) {
          if( IS_KVM ) vm.close();   // KVM: per-process VM を破棄 (vmFd/kvmFd + VM 制御 struct)
          else {
            // ★ WHP (step 3e-whp-7): vm は JVM 全体共有の単一 partition なので絶対に close しない。
            //   この process の GPA slot を unmap して slot を解放するだけ (partition は JVM 終了で回収)。
            // issue #304: lazy commit では map 済 chunk を 1 つずつ unmap する (gpaBacking.unmapAll)。
            try { if( gpaBacking != null ) gpaBacking.unmapAll(); } catch( Throwable ignore2 ) {}
            WhpVm.releaseSlot( guestMem.gpaBase() );
          }
        }
        if( poolSeg != null ) HvVm.freeGuestRam( poolSeg, POOL_SIZE );  // guest 物理 RAM の host backing を解放
        // arena (main vcpu の制御 struct regsBuf/fpuBuf/sregs/cpuid/msr buffer) を解放する。worker は
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
    if( hv != null ) hv.close();   // worker: vcpuFd + run-state mmap + 専用 arena (ownArena=true)。VM は owner 所有なので不触。
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
    long childRip = hv.getGpr( HvReg.RCX );
    long childTls = (flags & CLONE_SETTLS) != 0 ? tls : 0L;

    int  tid       = sysinfo.kernel.next_tid();
    long ctidClear = (flags & CLONE_CHILD_CLEARTID) != 0 ? ctid : 0L;

    // ★ Linux clone は子の全 register を親と同一にする (rax=0/rsp=child_stack のみ差分)。親 (this) の
    //   hv GPR cache は現 clone trap の KVM_GET_REGS 値を持つので、全 GPR を snapshot して子に継承させる。
    //   Go runtime.clone は `call *%r12` で mstart を渡すため r12 等の継承が必須 (step 3d-2c-37)。
    long[] parentRegs = new long[ HvReg.COUNT ];
    for( int i = 0; i < HvReg.COUNT; i++ ) parentRegs[i] = hv.getGpr( i );

    NativeCpuBackend child = new NativeCpuBackend( owner, childRip, child_stack, childTls, tid, ctidClear, parentRegs );

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
        if( System.getenv( "EMULIN_TRACE_BACKEND" ) != null ) t.printStackTrace();
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

  /** hv の GPR cache (直前に hv.readGprs() 済) を 16 進ダンプ (triple fault 診断用)。 */
  private String dumpRegs() {
    return "rax=" + Long.toHexString( hv.getGpr( HvReg.RAX ) )
        + " rbx=" + Long.toHexString( hv.getGpr( HvReg.RBX ) )
        + " rcx=" + Long.toHexString( hv.getGpr( HvReg.RCX ) )
        + " rdx=" + Long.toHexString( hv.getGpr( HvReg.RDX ) )
        + " rsi=" + Long.toHexString( hv.getGpr( HvReg.RSI ) )
        + " rdi=" + Long.toHexString( hv.getGpr( HvReg.RDI ) )
        + " rbp=" + Long.toHexString( hv.getGpr( HvReg.RBP ) )
        + " rsp=" + Long.toHexString( hv.getGpr( HvReg.RSP ) )
        + " r8=" + Long.toHexString( hv.getGpr( HvReg.R8 ) )
        + " r12=" + Long.toHexString( hv.getGpr( HvReg.R12 ) )
        + " r13=" + Long.toHexString( hv.getGpr( HvReg.R13 ) )
        + " r14=" + Long.toHexString( hv.getGpr( HvReg.R14 ) )
        + " r15=" + Long.toHexString( hv.getGpr( HvReg.R15 ) );
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

  // ★ FPU-in-signal (issue #221 step 3d-2c-21): vCPU の x87/XMM/MXCSR を不透明 snapshot として取得
  //   (hv.getFpu)。eval thread 上でのみ呼ぶ。frame push の前に呼ぶので失敗 (unchecked rethrow) しても
  //   sigFrames は不変 (atomic)。
  private byte[] captureFpuSnapshot() {
    try { return hv.getFpu(); }
    catch( Throwable t ) { throw new RuntimeException( "FPU snapshot (signal save) failed: " + t, t ); }
  }
  // snapshot を vCPU FPU に書き戻す (hv.setFpu)。失敗は eval ループに propagate して process を畳む。
  private void applyFpuSnapshot( byte[] snap ) {
    try { hv.setFpu( snap ); }
    catch( Throwable t ) { throw new RuntimeException( "FPU restore (signal) failed: " + t, t ); }
  }

  // call_amd64 後 (RAX=戻り値/RIP=SYSRETQ_VADDR 設定済) に呼ぶ。pending signal があれば被中断 user
  //   context を sigFrames に積み、regsBuf を handler 起動状態に書き換える (sysretq で handler へ)。
  private void deliverPendingSignal() { deliverPendingSignal( false ); }

  // async=false: syscall 境界配信 (被中断点=syscall 直後、RCX/R11 が user RIP/RFLAGS、sysretq で起動・復帰)。
  // async=true : 走行中 vCPU を host signal で割込んでの配信 (被中断点=任意命令、RIP/RFLAGS/RSP がそのまま
  //   user 値、全 GPR live。handler 起動は RIP=handler 直接、復帰は ring3 直接遷移で RCX/R11 も保持)。
  private void deliverPendingSignal( boolean async ) {
    int sig = process.psig();
    if( sig < 0 ) return;
    if( async && process.is_signal_masked( sig ) ) return;   // masked signal は async 配信しない (pending 維持)
    long handler = process.get_func_adrs( sig );
    process.signal_cancel( sig );
    if( handler == Siginfo.SIG_IGN ) return;
    if( handler == Siginfo.SIG_DFL ) {
      if( process.get_action_type( sig ) == Signal.SIGACTION_EXIT ) process.set_exit_flag();
      return;                              // SIG_DFL の ignore/stop は無視 (software check_pending_signal と同じ)
    }
    // 被中断 user context。sync: regsBuf は RAX=ret/RIP=SYSRETQ に書換済で、RCX=user 復帰 addr /
    //   R11=user RFLAGS / RSP=user RSP。async: 被中断点そのままなので RIP/RFLAGS/RSP が user 値。
    long userRip = async ? hv.getGpr( HvReg.RIP )    : hv.getGpr( HvReg.RCX );
    long userFlg = async ? hv.getGpr( HvReg.RFLAGS ) : hv.getGpr( HvReg.R11 );
    long userRsp = hv.getGpr( HvReg.RSP );
    long[] f = new long[ HvReg.COUNT + 1 ];
    for( int i = 0; i < HvReg.COUNT; i++ ) f[i] = hv.getGpr( i );
    f[16] = userRip;                       // RIP idx: 被中断 RIP (sync は SYSRETQ を RCX で上書き)
    f[17] = userFlg;                       // RFLAGS idx: 被中断 RFLAGS
    f[18] = process.get_signal_mask_bits();
    // ★ FPU-in-signal: この時点の vCPU FPU は被中断コードのもの。handler が clobber しても rt_sigreturn で
    //   復元できるよう snapshot し GPR frame と 1 SigFrame にまとめて push する (KVM_GET_FPU を push 前に
    //   取るので失敗しても sigFrames 不変 = GPR/FPU 片方だけ積む desync を防ぐ)。
    byte[] fpu = captureFpuSnapshot();
    // ハンドラ実行中の mask: 現 mask + sa_mask + (SA_NODEFER でなければ) sig 自身
    long nm = f[18] | process.get_sa_mask( sig );
    if( !process.has_sa_nodefer( sig ) && sig >= 1 && sig < 32 ) nm |= (1L << (sig - 1));
    process.set_signal_mask_bits( nm );
    // handler 用 stack: SA_ONSTACK + sigaltstack 登録時は代替 stack の top から (red zone 不要)、
    //   それ以外は割込み stack の red zone(128) skip。16-align → (SA_SIGINFO なら siginfo/ucontext) →
    //   trampoline push。trampoline push 後に RSP%16==8 = ABI の callee-entry 規約。real CPU は
    //   movaps で 16-align を要求するので software (緩い) と違い必ず align する。
    //   ★ Go runtime は全 handler に SA_ONSTACK を立て M ごとに alt stack を登録する。これを
    //   honor しないと handler が goroutine stack で走り adjustSignalStack→needm→無限 spin (#221)。
    long altBase = process.sig_alt_stack_base( sig, userRsp );
    long rsp = ( altBase >= 0 ? altBase : (userRsp - 128) ) & ~15L;
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
    // frame を push (uctx 確定後)。FPU snapshot は上で取得済なので desync しない。restore は async かつ
    //   uctx!=0 のとき、handler が改変しうる ここの uc_mcontext から復元する (Go の preemption リダイレクト対応)。
    sigFrames.push( new SigFrame( f, fpu, async, uctx ) );
    rsp -= 8;
    guestMem.store64( rsp, SIGTRAMP_VADDR );   // handler の ret 着地先 = trampoline
    hv.setGpr( HvReg.RSP, rsp );
    hv.setGpr( HvReg.RDI, (long) sig );
    if( siginfo != 0 ) {
      hv.setGpr( HvReg.RSI, siginfo );
      hv.setGpr( HvReg.RDX, uctx );
    }
    if( async ) {
      // 被中断点は ring-3 (EXIT_INTR は user code 実行中の割込み)。sysretq を経由せず handler を
      //   直接起動する: RIP=handler、RFLAGS=clean。vCPU は既に ring-3 なので writeGprs + run で handler が走る。
      hv.setGpr( HvReg.RIP,    handler );
      hv.setGpr( HvReg.RFLAGS, 2L );
    } else {
      // syscall 境界: sysretq で起動。RCX=handler (→RIP)、R11=clean RFLAGS、RIP は SYSRETQ_VADDR のまま。
      hv.setGpr( HvReg.RCX, handler );
      hv.setGpr( HvReg.R11, 2L );
    }
  }

  // rt_sigreturn(#15): sigFrames から被中断 context を復元し regsBuf を resume 状態にする。
  //   被中断点は syscall 直後なので RCX/R11 は ABI で dead = sysretq の RIP←RCX/RFLAGS←R11 で上書き
  //   して良い。frame 無し (spurious rt_sigreturn) は false を返し通常 resume に委ねる。
  private boolean restoreSignalFrame() {
    SigFrame sf = sigFrames.pollFirst();
    if( sf == null ) return false;
    long[] f = sf.regs;
    applyFpuSnapshot( sf.fpu );   // ★ FPU-in-signal: 被中断側の x87/XMM/MXCSR を復元 (handler の clobber を上書き)
    hv.setGpr( HvReg.RAX, f[0] );
    hv.setGpr( HvReg.RBX, f[1] );
    hv.setGpr( HvReg.RDX, f[3] );
    hv.setGpr( HvReg.RSI, f[4] );
    hv.setGpr( HvReg.RDI, f[5] );
    hv.setGpr( HvReg.RSP, f[6] );
    hv.setGpr( HvReg.RBP, f[7] );
    hv.setGpr( HvReg.R8,  f[8] );
    hv.setGpr( HvReg.R9,  f[9] );
    hv.setGpr( HvReg.R10, f[10] );
    hv.setGpr( HvReg.R12, f[12] );
    hv.setGpr( HvReg.R13, f[13] );
    hv.setGpr( HvReg.R14, f[14] );
    hv.setGpr( HvReg.R15, f[15] );
    process.set_signal_mask_bits( f[18] );           // mask 復元
    if( sf.async ) {
      // ★ async 配信の復帰: 被中断点は任意命令 (RCX/R11 も live)。sysretq は RCX/R11 を RIP/RFLAGS で
      //   潰すので使えない。全 GPR (RCX/R11 含む) を復元し、RIP/RFLAGS を被中断値に設定して exitToRing3 で
      //   ring-0 (rt_sigreturn syscall trap 後) から ring-3 被中断点へ直接遷移する。
      //   ★ uctx!=0 (SA_SIGINFO) のときは handler が改変しうる guest stack の uc_mcontext を尊重して
      //   そこから全 register を読む (Go の async preemption は ucontext を書換え RIP/RSP をリダイレクトする
      //   ので、内部 frame でなく ここを使わないと preemption が効かない)。
      long uc = sf.uctxAddr;
      long rcx, r11, rip, rflags;
      if( uc != 0 ) {
        hv.setGpr( HvReg.R8,  guestMem.load64( uc + 40 ) );
        hv.setGpr( HvReg.R9,  guestMem.load64( uc + 48 ) );
        hv.setGpr( HvReg.R10, guestMem.load64( uc + 56 ) );
        r11 =                 guestMem.load64( uc + 64 );
        hv.setGpr( HvReg.R12, guestMem.load64( uc + 72 ) );
        hv.setGpr( HvReg.R13, guestMem.load64( uc + 80 ) );
        hv.setGpr( HvReg.R14, guestMem.load64( uc + 88 ) );
        hv.setGpr( HvReg.R15, guestMem.load64( uc + 96 ) );
        hv.setGpr( HvReg.RDI, guestMem.load64( uc + 104 ) );
        hv.setGpr( HvReg.RSI, guestMem.load64( uc + 112 ) );
        hv.setGpr( HvReg.RBP, guestMem.load64( uc + 120 ) );
        hv.setGpr( HvReg.RBX, guestMem.load64( uc + 128 ) );
        hv.setGpr( HvReg.RDX, guestMem.load64( uc + 136 ) );
        hv.setGpr( HvReg.RAX, guestMem.load64( uc + 144 ) );
        rcx =                 guestMem.load64( uc + 152 );
        hv.setGpr( HvReg.RSP, guestMem.load64( uc + 160 ) );
        rip =                 guestMem.load64( uc + 168 );
        rflags =              guestMem.load64( uc + 176 );
      } else {
        rcx = f[2]; r11 = f[11]; rip = f[16]; rflags = f[17];
      }
      hv.setGpr( HvReg.RCX,    rcx );
      hv.setGpr( HvReg.R11,    r11 );
      hv.setGpr( HvReg.RIP,    rip );
      hv.setGpr( HvReg.RFLAGS, (rflags & 0x8C5L) | 0x2L | 0x200L );  // status flags + 予約 bit1 + IF(ring3 は常に 1)
      try { hv.exitToRing3(); } catch( Throwable t ) { throw new RuntimeException( "exitToRing3 failed", t ); }
      // ★ issue #309: mask 復元で unmask された pending signal はここで即時配信する (下の sync 経路と
      //   同じ理由)。被中断点は任意命令 (RCX/R11 live) なので async 配信 (RIP=handler 直接)。
      deliverPendingSignal( true );
      return true;
    }
    // sync 配信の復帰: sysretq で ring-3 へ。RIP←RCX, RFLAGS←R11 (user の RCX/R11 は syscall ABI で dead)。
    hv.setGpr( HvReg.RCX, f[16] );   // user 復帰 RIP
    hv.setGpr( HvReg.R11, f[17] );   // user RFLAGS
    hv.setGpr( HvReg.RIP, SYSRETQ_VADDR );
    // ★ issue #309: Linux kernel は sigreturn の return-to-user 経路でも pending signal を再チェック
    //   する。sa_mask で handler 中 block→pending になった signal は、mask を復元した「ここ」で配信
    //   しないと次の syscall 完了後まで遅れ、被中断 context が syscall を 1 個実行してから handler が
    //   走る (sys_sa_mask64: SIGUSR2 が write("end") の後に届き software/実機と配信順がズレる)。
    //   regs は直上で「sysretq 直前」の形 (RCX=user RIP / R11=user RFLAGS) に復元済 = 通常の syscall
    //   境界と同形なので、sync 配信がそのまま使える (配信されれば RCX が次 handler に差し替わる)。
    deliverPendingSignal();
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
