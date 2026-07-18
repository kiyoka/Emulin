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
  //   DATA_BASE) は ~2000 PT page = 最大 ~4GB の疎な vaddr をカバーでき、枯渇後は data 領域へ
  //   fallback する (NativeMemoryBackend.allocPt。長寿命プロセスの累積 mmap VA は 4GB を超える)。
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
  // issue #379: WHP の低位 32GB 窓ひっ迫で POOL_SIZE が取れないとき、pool を段階的に縮小して
  //   retry する (より多くの process が窓に収まる)。floor は process が最低限必要とするサイズ。
  private static final long MIN_POOL      = 256L * 1024 * 1024;   // pool 最小値 (これ未満には縮めない)
  private static final long BOOT_HEADROOM = 256L * 1024 * 1024;   // boot: page table + stack + 初期 heap 余裕
  private static final long FORK_HEADROOM =  64L * 1024 * 1024;   // fork: 子が exec 前に伸ばす分の余裕
  // issue #422: emulin の kernel 構造 (TSS/kstack/GDT/IDT/#PF stub/syscall stub/signal trampoline) を
  //   canonical address space の【上位半 (kernel half)】に置く base。47-bit user-space の guest は
  //   絶対に届かない領域なので、guest がどんな低位 cage を予約しても衝突しない。
  //   ★issue #403 は band を [0x200000,0x280000)→[0x40000,0xc0000) に下げたが、Bun(claude) は低位に
  //   巨大 cage を予約するためまた衝突した (ring-3 が kstack VA を読み US-violation #PF → 無限ループ)。
  //   どんな低位 band も Bun の cage と衝突するので、根治は band を high-half に逃がすこと。
  //   0xffffff8000000000 は canonical (bit47=bit63=1)、PML4[511]→PDPT[0]→PD[0]→PT[0] に収まる。
  //   各構造の相対 offset/spacing は据置 (KERN_HI を足すだけ) なので layout は不変。
  private static final long KERN_HI       = 0xffffff8000000000L; // kernel-half base (high canonical、PML4[511])
  private static final long STUB_VADDR    = KERN_HI + 0xff000L; // LSTAR スタブの仮想 (supervisor)
  // issue #435 追補 (WHP lost-wakeup): stub は hlt×3 + sysretq。WHP の WHvCancelRunVirtualProcessor
  //   (async signal kick) が hlt の HALT exit と競合すると、exit が Canceled に化けて eval が syscall を
  //   dispatch しないまま再 run する経路がある (KVM の EINTR は exit を失わないので Linux では起きない)。
  //   旧 layout (hlt; sysretq) では再 run が即 sysretq を実行し、syscall が RAX=syscall番号 のまま
  //   「成功」して消失 (futex WAKE/eventfd write が消えると tokio が lost-wakeup で全 worker 停止 =
  //   Windows で codex が非決定的に沈黙する真因)。hlt+1/+2 を backstop hlt にし、swallow 後の blind
  //   re-run が必ず再トラップして dispatch されるようにする。dispatch 完了後の復帰は +3 の sysretq に
  //   置く (post-hlt と復帰点の RIP が分離され、INTR rip=+1/+2 = swallow 発生の確定観測にもなる)。
  //   通常経路 (hlt#0 → HALT → dispatch → RIP=+3 → sysretq) の追加コストはゼロ。
  private static final long SYSRETQ_VADDR = STUB_VADDR + 3;     // stub 内 sysretq (hlt×3 の次、復帰専用)
  // STAR: syscall→kernel CS=0x10/SS=0x18、sysretq→user CS=0x33/SS=0x2b (Linux 規約)。
  //   ★ SYSRETQ(64-bit) の selector 算術は非自明: user CS = STAR[63:48] + 16、
  //   user SS = STAR[63:48] + 8 (どちらも RPL=3)。よって user CS=0x33 を得るには
  //   STAR[63:48] に 0x33 でなく 0x23 (=0x33-16) を入れる。これは Linux の
  //   `(__USER32_CS=0x23)<<48 | (__KERNEL_CS=0x10)<<32` と同値。0x33 を入れると
  //   sysretq CS=0x43 になり壊れる (review で 6 agent が +16 を見落とし誤指摘した箇所)。
  private static final long STAR_VALUE = (0x23L << 48) | (0x10L << 32);

  // rt_sigreturn トランポリン: signal handler が ret で着地する user ページ。`mov $15,%rax; syscall`
  //   で rt_sigreturn(#15) を呼び、eval ループが sysno==15 を見て被中断 context を復元する (glibc の
  //   sa_restorer 相当)。handler は ring-3 で動くので user(US=1) かつ実行可能ページ。STUB の 1 page 下
  //   (KERN_HI+0xfe000)。★high-half でも canonical な US=1 ページなら ring-3 RIP として合法 (#422)。
  private static final long SIGTRAMP_VADDR = KERN_HI + 0xfe000L;
  // issue #392 (戦略B #PF demand paging): 例外配送基盤。高位予約帯
  //   [KERN_HI+0xf9000, KERN_HI+0x100000) に GDT/IDT/PF_STUB/TSS/kstack を eager 配置する。
  //   ★2026-06-23 案B (#402 merge): default ON。eager OFF = EMULIN_NO_NATIVE_PF=1 (または
  //   EMULIN_NATIVE_PF=0) のとき構築せず IDTR/GDTR も load しない (旧 default の挙動)。
  //   high-half VA は #424 (KERN_HI 移設) を踏襲。NativeMemoryBackend.NATIVE_PF と一致必須。
  private static final boolean NATIVE_PF  = System.getenv( "EMULIN_NO_NATIVE_PF" ) == null
                                            && !"0".equals( System.getenv( "EMULIN_NATIVE_PF" ) );
  private static final long PF_STUB_VADDR = KERN_HI + 0xfd000L;   // #PF handler stub (hlt; add rsp,8; iretq)
  private static final long EXC_STUB_VADDR = KERN_HI + 0xfa000L;  // raw=4 診断: CPU 例外 vector 0-31 の per-vector hlt stub
  private static final long IDT_VADDR     = KERN_HI + 0xfc000L;   // IDT (256 gate × 16 byte = 1 page、vector14=#PF)
  private static final long GDT_VADDR     = KERN_HI + 0xfb000L;   // GDT (code/data + TSS desc)。band の最下位
  // per-vCPU TSS + kernel stack (multi-vCPU 対応)。共有 page table 上で vcpuId ごとに別 VA に置く。共有すると
  //   並行 #PF で kernel stack が壊れて triple fault する。
  //   ★issue #403: 旧 [0x200000,0x280000) は「binary が 0x400000+」前提だったが Bun(claude) が 0x200000 に load して
  //   衝突→[0x40000,0xc0000) に下げた。★issue #422: それでも Bun は低位に巨大 cage を予約し、ring-3 が kstack VA
  //   (例 0x80f40) を読んで US-violation #PF → faultIn は present を見て true → 無限 #PF ループ (cr2=0x80f40 で死亡)。
  //   どんな低位 band も Bun の cage と衝突するので、TSS/kstack も含め band 全体を KERN_HI (high-half kernel space) に
  //   逃がす。high-half は 47-bit user-space guest が到達不能なので恒久的に衝突しない。相対 offset は据置。
  private static final long TSS_BASE      = KERN_HI + 0x40000L;   // vCPU の TSS    = TSS_BASE + slot*0x1000 ([+0x40000,+0x80000))
  private static final long KSTACK_BASE   = KERN_HI + 0x80000L;   // vCPU の kstack = KSTACK_BASE + slot*0x1000 ([+0x80000,+0xc0000)、RSP0 = +0x1000)
  private static final long RESERVED_LOW  = NATIVE_PF ? TSS_BASE : SIGTRAMP_VADDR;  // PT_LOAD guard の下限 (band 最下位 = TSS)
  // signal frame / fork snapshot の register layout: long[0..17] = HvReg.{RAX..RFLAGS} 順
  //   (HvReg.RAX=0..HvReg.RFLAGS=17 で hv.getGpr(i)/setGpr(i) と 1:1)、long[18] = 保存 signal mask。

  // ---- state ----
  private long          entryRip;
  // issue #392 (戦略B): #PF demand paging の無限ループ guard (同一 cr2 連続 fault 検出)。
  private long          lastFaultCr2 = -1;
  private int           faultRepeat  = 0;
  private long          rsp = 0;          // 初期 RSP (setup_initial_stack で確定)
  private SyscallAmd64  sys64;
  private NativeMemoryBackend guestMem;
  private Arena         arena;       // guest RAM の生存期間 (process 寿命)
  private boolean       kvmReady;

  // ★ VM-level の hypervisor 抽象 (#221 WHP 移植 Stage 2)。main が所有、worker は共有、fork 子は新規。
  //   open(/dev/kvm)+CREATE_VM+guest RAM map を担い、KVM ⇄ WHP の VM-API 差を実装 (KvmVm/将来 WhpVm) に閉じる。
  private HvVm          vm;
  private MemorySegment poolSeg;     // guest 物理 RAM の host backing (HvVm.allocGuestRam、teardown で freeGuestRam)
  private long          poolSize = POOL_SIZE;   // issue #379: 実際に確保した pool サイズ (窓ひっ迫時は POOL_SIZE 未満に縮小)。slot/gpaBacking/teardown はこれを使う
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

  // issue #742 診断: per-vCPU の直近 syscall リング (EMULIN_SYS_RING=1 で有効)。triple fault の
  //   直前に guest が呼んだ syscall 列 (sysno / 復帰先 rip / その時の RSP) を残し、rsp=0 化等の
  //   発端を特定する。3 要素 × N エントリ。既定 off (ゼロコスト)。
  private static final int SYS_RING_N = 32;   // 2 のべき乗
  private final long[] SYS_RING = ( System.getenv( "EMULIN_SYS_RING" ) != null ) ? new long[ SYS_RING_N * 3 ] : null;
  private int sysRingPos = 0;
  // issue #548-native: 同期 fault (#PF wild access) 由来の SIGSEGV を配送する際の siginfo。
  //   deliverPendingSignal が siginfo 構築時に参照する (si_code / si_addr)。0 = fault 由来でない
  //   通常の signal (si_code/si_addr は 0 のまま)。#PF 経路が set → deliverPendingSignal → clear。
  private long pendingFaultAddr;
  private int  pendingFaultCode;

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
  // WHP: guest tid → その vCPU の backend (kick = kickVcpu())。eval 開始で put、exit で remove。
  //   issue #435 追補: 値を HvVcpu → NativeCpuBackend に変更。kick を inRun ゲートで
  //   「本当に WHvRunVirtualProcessor 実行中」のときだけ発行するため (下記 kickVcpu 参照)。
  private static final java.util.concurrent.ConcurrentHashMap<Integer,NativeCpuBackend> RUNNING_VCPUS =
      new java.util.concurrent.ConcurrentHashMap<>();
  // issue #435 追補 (WHP lost-wakeup): 旧実装は WHvCancelRunVirtualProcessor を無条件に呼んでいた。
  //   Cancel は「現在または次回の run」を中断する remembered セマンティクスのため、syscall 処理中
  //   (run 外) の vCPU に発行すると、syscall 完了後の resume run が数秒〜数十秒遅れて Canceled になる
  //   (実測: park 明けに 9〜50 秒遅れで発火)。この Canceled が WHP 内部で register write や exit 配送と
  //   競合し得る窓を潰すため、inRun (WHvRun 実行中) のときだけ Cancel を発行し、run 外に届いた kick は
  //   kickPending に立てて eval ループ先頭で処理する。順序は Dekker 型: kicker は kickPending=true →
  //   inRun read、eval は inRun=true → kickPending read (両方 volatile) なので取りこぼしは無い。
  //   guest tight-loop への async preemption (Go の SIGURG 等) は inRun 中の Cancel で従来通り届く。
  private volatile boolean inRun = false;
  private volatile boolean kickPending = false;
  // issue #435 追補: TLB 診断スイッチ。syscall 境界毎に CR3 を再書込して自 vCPU の TLB を flush する。
  //   emulin はゲスト page table をホスト側 (mmuLock 下) で書き換えるが cross-vCPU TLB shootdown を
  //   持たない。WSL2 nested KVM は VPID 無効の暗黙 flush で隠れるが、ベアメタル WHP は stale TLB が
  //   長生きし得る。この flush で lost-wakeup が消えるなら stale TLB が真因と確定する。
  private static final boolean TLB_FLUSH_SYSCALL = System.getenv( "EMULIN_TLB_FLUSH_SYSCALL" ) != null;
  /** signal queue 直後に呼ばれる: この vCPU が run 中なら Cancel、run 外なら pending 化。 */
  void kickVcpu() {
    kickPending = true;
    if( inRun && hv != null ) { try { hv.kick(); } catch( Throwable ignore ) {} }
  }
  private int hostTid = -1;   // この vCPU を走らせる Java thread の Linux TID
  private int myGuestTid() { return isChild ? childTid : process.pid; }

  /** asyncKick hook の設定 (一度だけ)。KVM = host SIG_KICK handler install、WHP = cancel-run kicker。 */
  private static synchronized void ensureAsyncInfra() {
    if( asyncInfraDone ) return;
    if( IS_KVM && !FORCE_WHP_KICK ) {
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

  // issue #435 追補: WHP lost-wakeup の切り分け用。kick (cancel-run) を無効化すると signal は
  //   syscall 境界 (sync) 配信のみになる。ハングが消えれば Cancel 機構 (Canceled exit + INTR 経路)
  //   が原因と確定する診断スイッチ。
  private static final boolean NO_KICK = System.getenv( "EMULIN_NO_KICK" ) != null;
  // issue #498 診断用: kickPending 経路(WHP 専用、kickGuestVcpu 由来)は KVM では絶対に呼ばれない
  // (kickGuestTid は EXIT_INTR 経由でガード付き)。Windows/WHP 実機が無くても KVM(WSL2) 上で
  // 同じレース(#PF stub 滞在中の async kick → ring0 状態リーク)を再現するため、asyncKick hook と
  // RUNNING_VCPUS 登録を KVM でも WHP と同じものに差し替える診断スイッチ。既定 off・本番影響無し。
  private static final boolean FORCE_WHP_KICK = System.getenv( "EMULIN_FORCE_WHP_KICK" ) != null;

  /** signal queue 直後に Signal 層から呼ばれる (WHP): 走行中 vCPU の run を cancel する。best-effort。 */
  private static void kickGuestVcpu( int targetTid ) {
    if( NO_KICK ) return;
    try {
      if( targetTid == -1 ) {   // process-wide: 全走行中 vCPU を kick
        for( NativeCpuBackend b : RUNNING_VCPUS.values() ) { try { b.kickVcpu(); } catch( Throwable ignore ) {} }
      } else {
        NativeCpuBackend b = RUNNING_VCPUS.get( targetTid );
        if( b != null ) b.kickVcpu();
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
  private int           vcpuId  = 0;          // KVM vcpu id (0 = main、1+ = worker、VM 内で一意、monotonic)
  private int           tssSlot = 0;          // issue #392 review #2: per-vCPU TSS/kstack band の slot (0=main、recyclable)
  private NativeCpuBackend vmOwner;           // VM 共有資源の所有者 (main backend、自分が main なら null)
  // vcpu id 採番は VM owner が持つ単一 counter で行う (nested clone でも衝突しない)。
  private final java.util.concurrent.atomic.AtomicInteger nextVcpuId =
      new java.util.concurrent.atomic.AtomicInteger( 1 );
  // issue #392 review #2: TSS/kstack band [TSS_BASE,KSTACK_BASE+64page) は MAX_TSS_SLOTS 個分。vcpuId は KVM vcpu id
  //   として monotonic (KVM は id 再利用不可) だが、TSS slot は「同時に生きている vCPU 数」だけ要る (死んだ
  //   worker の TSS は不要) ので teardown で free-list に返して再利用する。これで生涯 64 thread 超の guest
  //   (V8 pool / make -j ループ等) でも band が溢れず、vcpuId≥64 → tssBase が KSTACK_BASE と衝突して
  //   triple fault する回帰を防ぐ。owner (main backend) が free-list を保持する。
  private static final int MAX_TSS_SLOTS = 64;   // band サイズと一致 (TSS_BASE..KSTACK_BASE = 64 page)
  private final java.util.ArrayDeque<Integer> freeTssSlots = new java.util.ArrayDeque<>();
  private int nextTssSlot = 1;                    // 0 は main vCPU 専用
  private synchronized int allocTssSlot() {
    Integer s = freeTssSlots.poll();
    if( s != null ) return s;
    if( nextTssSlot >= MAX_TSS_SLOTS )
      throw new IllegalStateException( "native: TSS slot 枯渇 (同時 vCPU 上限 " + MAX_TSS_SLOTS + ")" );
    return nextTssSlot++;
  }
  private synchronized void releaseTssSlot( int s ) { if( s > 0 ) freeTssSlots.offer( s ); }
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
    this.tssSlot   = owner.allocTssSlot();   // issue #392 review #2: recyclable な TSS band slot (band 溢れ防止)
    // VM 共有資源 (read-only に扱う): guest RAM/page table、VM (kvmFd/vmFd を内包)、syscall 層。
    this.vm        = owner.vm;
    this.guestMem  = owner.guestMem;
    this.arena     = owner.arena;     // guest RAM 用 shared arena (vcpu buffer は vcpuArena に分離)
    this.sys64     = owner.sys64;
    this.mem       = owner.mem;
    this.syscall   = owner.syscall;
    // 子の初期状態
    this.entryRip      = childRip;
    // issue #742: clone(2) の child_stack が 0 (NULL) のとき、子は親のスタックを共有する
    //   (CLONE_VM 下では同一 rsp で継続、非共有でも parent stack の複製上で継続)。Go runtime の
    //   raw clone (runtime/internal/syscall.Syscall6(SYS_clone, flags, 0, ...)) がこの形を使い、
    //   子コードは自分で SP を張り替える。旧実装は rsp=0 のまま起動し、子が最初の push で
    //   #PF → IDT 無しで triple fault していた (gh 等 Go バイナリが native で起動不能)。
    //   Linux 準拠に、child_stack==0 なら親の clone 時点の RSP (inheritRegs[6]) を継承する。
    this.rsp           = ( childStack != 0 || inheritRegs == null ) ? childStack : inheritRegs[6];
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

  // ===== vfork (issue #435 Phase 2): 親 VM/guestMem を共有する「別 process」の子 backend =====
  //   posix_spawn / Rust Command の clone(CLONE_VM|CLONE_VFORK) 用。worker(pthread)と同じく
  //   親の VM/guestMem を共有する追加 vCPU だが、pthread と違い別 process(own pid / 複製した
  //   fd テーブル)である。親 guestMem を複製しない(= fork の OOM/storm を回避)。子は execve/
  //   _exit まで走り(親 vCPU は Kernel.vfork が latch で suspend)、execve 時に kernel.exec が
  //   新 VM を作って共有から離脱する。register は clone-ABI(親 RCX 戻り先 / rax=0 / rsp=child_stack /
  //   全 GPR 継承)を worker constructor + setupVcpu の cloneRegs 分岐が設定する。
  public AbstractCpu duplicateVforkChild( Process childProc, long child_stack ) {
    NativeCpuBackend owner = isChild ? vmOwner : this;
    // 呼び出し thread が worker(別 vCPU)なら register snapshot 元をそれにする(spawnVCpu/fork と
    //   同じ #113/#181 = clone 親取り違え対策)。this は Process.duplicateVfork 経由で常に main 固定。
    NativeCpuBackend src = this;
    Thread cur = Thread.currentThread();
    if( cur instanceof GuestThread gt && gt.guestCpu() instanceof NativeCpuBackend w ) src = w;
    long childRip = src.hv.getGpr( HvReg.RCX );    // syscall 戻り先(= clone の次命令)
    long childTls = src.fsBase;
    long[] parentRegs = new long[ HvReg.COUNT ];   // 全 GPR を snapshot(子は rax/rsp/rip 以外を継承)
    for( int i = 0; i < HvReg.COUNT; i++ ) parentRegs[i] = src.hv.getGpr( i );
    int tid = sysinfo.kernel.next_tid();
    NativeCpuBackend child = new NativeCpuBackend( owner, childRip, child_stack, childTls, tid, 0L, parentRegs );
    // vfork 子は別 process: process / fd テーブル(syscall)を子のに差し替える。
    //   guestMem / vm / mem(software Memory)は worker constructor が owner のを共有済み(= 親と共有)。
    child.process = childProc;
    child.syscall = childProc.syscall;
    child.sys64   = (SyscallAmd64) childProc.syscall;
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
    //   (#245)。pool 確保失敗は fatalPoolExhausted (System.exit) で落とす。
    // ★ issue #379: pool は process 終了時に teardownKvm が確実に freeGuestRam + releaseSlot するので
    //   leak しない。が WHP では全 process の pool が host の低位 32GB 窓を共有するため (HvVm.java の
    //   HighestEndingAddress<32GB 制約)、多 process 同時実行 (apt install 等) で「生存 process 数 ×
    //   POOL_SIZE + 8GB heap」が窓を超えると VirtualAlloc2 が失敗する (= 旧コメントの "あり得ない" は誤り)。
    //   回避は EMULIN_NATIVE_POOL_MB=512 / EMULIN_BACKEND=software (fatalPoolExhausted が案内)。
    //   将来の auto-fix 案: (a) pool 確保失敗時に小さい pool で retry (boot は binary が縮小 pool に
    //   収まらない恐れ、fork は親 dataNext を floor に) (b) per-process software fallback (boot path は
    //   software Memory `mem` が fresh ELF を持つので feasible だが、fork child は親の live 状態が native
    //   guestMem 側にあり software へ export する page-table walk が要る)。
    // issue #379: 窓ひっ迫時は pool を bootPoolFloor (binary の PT_LOAD 占有 + 余裕) まで
    //   段階的に縮小 retry する。確保した実サイズは poolSize に入る (slot/gpaBacking/teardown が使う)。
    poolSeg  = allocPoolRetry( bootPoolFloor( _mem ), true );   // issue #379: exec は失敗時 software へ fallback
    guestMem = new NativeMemoryBackend( poolSeg );
    // ★ fork-on-WHP (step 3e-whp-7): WHP は JVM 全体で単一 partition を共有し、process ごとに
    //   GPA slot (POOL_SIZE 刻み) を確保して pool を map する (1-partition-per-process 制限の回避)。
    //   page table entry は gpaBase + pool offset を格納するので、最初の mapPage より前に設定する。
    //   KVM は per-process VM (gpa 0) のままなので slot 確保しない (= 従来と byte-identical)。
    if( !IS_KVM ) {
      // issue #383: slot grid は POOL_SIZE 固定で確保する (allocSlot は「全 slot 同一サイズ」前提)。
      //   #379 の retry で pool が縮小 (poolSize < POOL_SIZE) しても slot grid は POOL_SIZE のままにし、
      //   実 pool は WhpGpaBacking が slot 先頭に poolSize 分だけ map する。これで縮小 pool と非縮小 pool
      //   が混在しても slot size 不一致 crash を起こさない。
      guestMem.setGpaBase( WhpVm.allocSlot( POOL_SIZE ) );
      // issue #304 lazy commit: pool は MEM_RESERVE のみ確保済 → allocPt/allocData の chunk を
      //   WhpGpaBacking が commit+map する (commit charge を guest 実使用量に比例)。setGpaBase の後・
      //   enableMmu (PML4 chunk を ensure) の前に attach すること。
      gpaBacking = new WhpGpaBacking( poolSeg.address(), guestMem.gpaBase(), poolSize );
      guestMem.setGpaBacking( gpaBacking );
    }
    guestMem.enableMmu();
    guestMem.setSyscall( _syscall );   // file-backed mmap (ld.so の .so map) 用

    boolean trace = System.getenv( "EMULIN_TRACE_BACKEND" ) != null;

    // ★ LSTAR スタブ (hlt; sysretq) を PT_LOAD ループの【前】に supervisor ページとして map する
    //   (review): 後だと、仮に PT_LOAD が stub ページ [STUB_VADDR..) に来た場合に先に user (US=1)
    //   で map され、mapSupervisor が既マップを skip して stub が ring-3 アクセス可のまま残る。
    //   先に supervisor 確定すれば、衝突する PT_LOAD は mapRange に skip され (data 未配置で loud
    //   に fault する) stub の supervisor 性は保たれる。STUB_VADDR=KERN_HI+0xff000 は high-half kernel
    //   space で user-space ELF とは原理上衝突しない (#422)。
    guestMem.mapSupervisor( STUB_VADDR, 6 );
    guestMem.store8( STUB_VADDR + 0, 0xF4 );  // hlt (syscall trap)
    guestMem.store8( STUB_VADDR + 1, 0xF4 );  // hlt backstop #1 (issue #435: WHP cancel が HALT exit を飲んだ時の再トラップ)
    guestMem.store8( STUB_VADDR + 2, 0xF4 );  // hlt backstop #2 (二連続 swallow 用の保険)
    guestMem.store8( STUB_VADDR + 3, 0x48 );  // sysretq (REX.W) — 復帰専用 (SYSRETQ_VADDR)
    guestMem.store8( STUB_VADDR + 4, 0x0F );
    guestMem.store8( STUB_VADDR + 5, 0x07 );

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

    // issue #392 (戦略B): #PF demand paging の例外配送基盤 (NATIVE_PF gate)。default は構築せず挙動不変。
    //   reserve ページ touch → #PF → IDT[14] → PF_STUB(ring0) → hlt → VM-exit → eval が CR2 を読む。
    if( NATIVE_PF ) {
      // GDT: selector は configureLongModeRing3 の hidden cache (0x10/0x18/0x2b/0x33) と一致必須
      //   (exception delivery / iretq が GDT から CS/SS を reload する)。
      guestMem.mapSupervisor( GDT_VADDR, 0x50 );
      guestMem.store64( GDT_VADDR + 0x00, 0L );                    // null
      guestMem.store64( GDT_VADDR + 0x10, 0x00209B0000000000L );   // 0x10 kernel code64 (P,DPL0,L=1)
      guestMem.store64( GDT_VADDR + 0x18, 0x0000930000000000L );   // 0x18 kernel data
      guestMem.store64( GDT_VADDR + 0x28, 0x0000F30000000000L );   // 0x2b user data (DPL3)
      guestMem.store64( GDT_VADDR + 0x30, 0x0020FB0000000000L );   // 0x33 user code64 (DPL3,L=1)
      guestMem.store64( GDT_VADDR + 0x40, tssDescLow( TSS_BASE, 0x67 ) );    // 0x40 TSS desc (selector 用、実 base は per-vCPU)
      guestMem.store64( GDT_VADDR + 0x48, tssDescHigh( TSS_BASE ) );
      // IDT: vector14 (#PF) = 64-bit interrupt gate → PF_STUB (CS=0x10, DPL0)。他 vector は 0。
      guestMem.mapSupervisor( IDT_VADDR, 256 * 16 );
      guestMem.store64( IDT_VADDR + 14 * 16,     idtGateLow( PF_STUB_VADDR, 0x10, 0x8E ) );
      guestMem.store64( IDT_VADDR + 14 * 16 + 8, idtGateHigh( PF_STUB_VADDR ) );
      // PF_STUB: hlt (VM-exit) / add rsp,8 (CPU push の error code 破棄) / iretq (faulting 命令へ復帰)
      guestMem.mapSupervisor( PF_STUB_VADDR, 8 );
      guestMem.store8( PF_STUB_VADDR + 0, 0xF4 );                  // hlt
      guestMem.store8( PF_STUB_VADDR + 1, 0x48 );                  // add rsp, 8
      guestMem.store8( PF_STUB_VADDR + 2, 0x83 );
      guestMem.store8( PF_STUB_VADDR + 3, 0xC4 );
      guestMem.store8( PF_STUB_VADDR + 4, 0x08 );
      guestMem.store8( PF_STUB_VADDR + 5, 0x48 );                  // iretq
      guestMem.store8( PF_STUB_VADDR + 6, 0xCF );
      // raw=4 (triple fault) 診断: CPU 例外 vector 0-31 (#PF=14 除く) に per-vector hlt stub を IDT 登録。
      //   IDT 未登録 vector は CPU が dispatch できず double→triple fault (unrecoverable exit, vector 不明) に
      //   なる。stub で受けて hlt→VM-exit させ eval で発生 vector を特定する (standalone Bun が起こす例外調査)。
      guestMem.mapSupervisor( EXC_STUB_VADDR, 32 * 8 );
      for( int v = 0; v < 32; v++ ) {
        if( v == 14 ) continue;  // #PF は PF_STUB が処理
        guestMem.store8( EXC_STUB_VADDR + (long) v * 8, 0xF4 );  // hlt
        guestMem.store64( IDT_VADDR + (long) v * 16,     idtGateLow( EXC_STUB_VADDR + (long) v * 8, 0x10, 0x8E ) );
        guestMem.store64( IDT_VADDR + (long) v * 16 + 8, idtGateHigh( EXC_STUB_VADDR + (long) v * 8 ) );
      }
      // TSS + kernel stack は per-vCPU で setupVcpu が構築する (multi-vCPU で共有すると並行 #PF で壊れるため)。
      // review #3 / issue #403: 予約帯を guestMem に登録し、guest の runtime MAP_FIXED が clobber しないように
      //   する (踏んだら relocate)。TSS/kstack [KERN_HI+0x40000,+0xc0000) + GDT/IDT/PF_STUB/STUB [KERN_HI+0xfb000,
      //   +0x100000) を一括カバー (間の gap も予約扱いで無害)。★#422: band は high-half ゆえ user MAP_FIXED は届かない。
      guestMem.setReservedBand( TSS_BASE, STUB_VADDR + 0x1000L );
      if( trace ) System.err.println( "[native][PF] shared exception tables built (GDT/IDT/PF_STUB)" );
    }

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
      if( va < 0 || memsz < 0 || memsz > poolSize ) {   // 異常/pool 超過 segment は skip (防御、issue #379 で poolSize)
        if( trace ) System.err.println( "[native] skip PT_LOAD vaddr=0x" + Long.toHexString( va )
            + " memsz=0x" + Long.toHexString( memsz ) + " (異常)" );
        continue;
      }
      // ★ audit fix: stub/sigtramp の supervisor/trampoline ページに PT_LOAD が被ると、mapRange は
      //   (既 map で) skip するが直後の bulkStoreToMem が segment 内容で stub の `hlt;sysretq` /
      //   sigtramp の `mov $15;syscall` を上書きし、syscall trap 機構が壊れる (mapRange の skip は US を
      //   保つだけで内容は守らない)。予約帯 [RESERVED_LOW, STUB_VADDR+0x1000) と重なる PT_LOAD は skip。
      //   ★issue #422: 予約帯は KERN_HI (high-half) に移したので user-space ELF (低位 canonical) とは原理上
      //   衝突しない=この guard は実質発火しない。high-half VA は signed では負なので Long.compareUnsigned で
      //   比較する (signed `<` だと高位帯と低位 va の大小が逆転して判定が壊れる)。
      if( Long.compareUnsigned( va, STUB_VADDR + 0x1000L ) < 0
          && Long.compareUnsigned( va + memsz, RESERVED_LOW ) > 0 ) {
        if( trace ) System.err.println( "[native] skip PT_LOAD vaddr=0x" + Long.toHexString( va )
            + " (予約帯 [0x" + Long.toHexString( RESERVED_LOW ) + ",0x"
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
        // issue #403: file 由来の PT_LOAD 部 [va, va+copyLen) を file-backed として登録する。
        //   Bun/V8 (claude) は ELF 埋め込み JS ソース (= この PT_LOAD) を madvise(DONTNEED) で
        //   decommit しつつ zero-copy 文字列 view を保持する。登録しておくと madvise が zero 化を
        //   skip し、view deref 時に元の file 内容が残る (= module 名 garbage 化 #403 を防ぐ)。
        guestMem.registerFileBacked( va, copyLen );
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
        + Long.toHexString( guestMem.address() ) + " size=0x" + Long.toHexString( poolSize ) );
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
    // issue #379: 子 pool は親の [0, usedTop) を複製するので、窓ひっ迫時は「親 usedTop + 余裕」を
    //   floor に縮小 retry する (親が小さければ子も小さく済み、より多くの fork 子が 32GB 窓に収まる)。
    // issue #720: 全縮小 retry でも取れない場合は PoolExhaustedException を上へ投げ、Kernel.fork が
    //   この fork だけを -EAGAIN にする (旧実装は fatalPoolExhausted = System.exit で JVM ごと、
    //   sshd 常駐なら全セッションごと落ちていた)。throw 時点で確保済みなのは空の arena だけなので
    //   閉じて戻す (GPA slot は pool 成功後に確保するため leak しない。pinfo/ptable 登録・
    //   pipe_connection は Kernel.fork で duplicate() より後なので巻き戻し不要)。
    //   EMULIN_FORCE_POOL_EXHAUST=1 は KVM (mmap は自然には失敗しない) で本経路を決定再現する
    //   ための診断スイッチ (#498/#598 の diag switch パターン)。
    try {
      if( FORCE_POOL_EXHAUST ) throw new PoolExhaustedException();
      poolSeg = allocPoolRetry( forkParent.guestMem.usedTop() + FORK_HEADROOM, true,
                                FORCE_FORK_POOL > 0 ? FORCE_FORK_POOL : POOL_SIZE );   // issue #723 diag
    } catch( PoolExhaustedException pe ) {
      try { arena.close(); } catch( Throwable ignore ) {}
      arena = null;
      throw pe;
    }

    // 親アドレス空間を子プールへ複製。KVM は page table の pool-relative 物理 offset がそのまま子で
    //   valid (childGpaBase=0)。WHP (step 3e-whp-7) は子も同一 partition 内の別 GPA slot に map する
    //   ので、duplicate が全 page table entry を child slot base に rebase する。
    long childGpaBase = IS_KVM ? 0L : WhpVm.allocSlot( POOL_SIZE );   // issue #383: slot grid は POOL_SIZE 固定 (縮小 pool でも grid 不変、map は poolSize)
    // issue #304 lazy commit (WHP): 子 pool も MEM_RESERVE のみ → child の commit-on-map hook を作り
    //   duplicate に渡す (duplicate が verbatim copy の前に child [0,dataNext) を commit する)。KVM は null。
    if( !IS_KVM ) gpaBacking = new WhpGpaBacking( poolSeg.address(), childGpaBase, poolSize );
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
  // issue #548-native (ss_onstack): syscall 実行中 (guest が sigaltstack 等を呼ぶ) は hv が
  //   readGprs 済で live な RSP を持つので、それを返す (rsp フィールドは初期 RSP / fork childStack
  //   のみで syscall 中の handler alt stack RSP を反映しない)。hv 未生成時は初期 rsp。
  @Override public long get_sp()              {
    if( hv != null ) { try { return hv.getGpr( HvReg.RSP ); } catch( Throwable ignore ) {} }
    return rsp;
  }
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
      if( IS_KVM && !FORCE_WHP_KICK ) {
        // KVM: host TID を登録 (tgkill 用)。gettid は Linux syscall 186 なので KVM のみ。
        try { hostTid = KvmBindings.gettid(); RUNNING_TIDS.put( myGuestTid(), hostTid ); }
        catch( Throwable ignore ) {}
      } else {
        // WHP (または EMULIN_FORCE_WHP_KICK): backend 自体を登録 (kick は kickVcpu() が inRun ゲートで判断する)。
        RUNNING_VCPUS.put( myGuestTid(), this );
      }
      while( !process.is_exited() ) {
        // issue #435 追補: run 外 (syscall 処理中) に届いた kick はここで拾う。pending signal が
        //   あれば配信を試みる (RIP が stub 内なら deliverPendingSignal の guard が defer するので
        //   従来の Cancel 経由と同じく次の syscall 境界配信に自然に落ちる)。inRun=true を先に立てて
        //   から kickPending を読む (kicker は kickPending=true → inRun read の順、取りこぼし無し)。
        inRun = true;
        if( kickPending ) {
          kickPending = false;
          inRun = false;
          if( process.is_exited() ) break;
          deliverPendingSignal( true );   // 被中断点 = 直前に writeGprs 済みの状態 (stub 内なら defer)
          hv.writeGprs();
          continue;
        }
        int exitReason = hv.run();
        inRun = false;
        if( exitReason == HvVcpu.EXIT_HALT ) {
          // syscall trap: regs を読み call_amd64 に dispatch
          // issue #392 review #14: #PF 判定には RIP のみで足りるが、ここで全 GPR を read している。
          //   単一 RIP read への分割は WHP では得 (18→1 reg) だが、KVM は KVM_GET_ONE_REG binding が無く
          //   KVM_GET_REGS しか無いため syscall path で二重 read になり逆効果。cheap な per-backend RIP
          //   read primitive を入れるまで保留 (demand paging は opt-in、効果は WHP 限定の微小最適化)。
          hv.readGprs();
          // issue #392 (戦略B): syscall HLT(RIP=SYSRETQ) と #PF HLT(RIP∈PF_STUB ページ)を post-hlt RIP で区別。
          //   #PF なら CR2 を読む (4d-lite: 診断 + error 終了。4e で faultIn → reserve なら resume を配線)。
          if( NATIVE_PF ) {
            long pfRip = hv.getGpr( HvReg.RIP );
            // PF_STUB_VADDR は KERN_HI (high-half、signed では負) なので Long.compareUnsigned で範囲判定 (#422)。
            if( Long.compareUnsigned( pfRip, PF_STUB_VADDR ) >= 0
                && Long.compareUnsigned( pfRip, PF_STUB_VADDR + 0x1000 ) < 0 ) {
              long cr2 = hv.getCr2();
              // kernel-stack frame (#PF が push: [RSP0-0x30]=error code, [RSP0-0x28]=faulting RIP) から診断。
              long ksTop = KSTACK_BASE + (long) tssSlot * 0x1000L + 0x1000L;   // review #2: tssSlot に合わせる
              long errCode = guestMem.load64( ksTop - 0x30 ), userRip = guestMem.load64( ksTop - 0x28 );
              long userRsp = guestMem.load64( ksTop - 0x10 );
              // issue #498: error code の P bit (bit0) = 1 は「fault 時点で既に present だったページ」への
              //   保護違反 (権限/RW/NX 違反、または ring0 状態が signal frame 経由で CPL3 に漏れた等)。
              //   faultIn は not-present ページの demand 割当専用で、他 vCPU が先に fill した race を
              //   吸収するため「present なら成功」を返す — これを保護違反にも適用すると、同じ命令が
              //   永久に同じ保護違反を起こし続ける (17 回ループしてから下の repeat guard で fatal 化する
              //   しかなかった)。P=1 は demand paging で直しようがないので faultIn を呼ばず即座に fatal
              //   にする (診断も速く、根本原因の切り分けがしやすい)。
              if( ( errCode & 1 ) != 0 ) {
                System.err.println( "[native][PF] 保護違反 (present page への再 fault) cr2=0x" + Long.toHexString( cr2 )
                    + " err=0x" + Long.toHexString( errCode ) + "(U=" + ((errCode>>2)&1) + " W=" + ((errCode>>1)&1) + ")"
                    + " userRip=0x" + Long.toHexString( userRip ) + " userRsp=0x" + Long.toHexString( userRsp ) );
                process.exit_code = 139; process.set_exit_flag(); break;
              }
              // 無限 #PF ループ guard: 同一 cr2 が faultIn 後も連続再 fault したら fatal (mapPage 失敗等、
              //   上の P bit チェックを抜けても万一ループする場合の保険)。
              if( cr2 == lastFaultCr2 ) faultRepeat++; else { lastFaultCr2 = cr2; faultRepeat = 0; }
              if( faultRepeat > 16 ) {
                System.err.println( "[native][PF] 無限 #PF ループ cr2=0x" + Long.toHexString( cr2 )
                    + " err=0x" + Long.toHexString( errCode ) + "(U=" + ((errCode>>2)&1) + " W=" + ((errCode>>1)&1) + ")"
                    + " userRip=0x" + Long.toHexString( userRip ) + " userRsp=0x" + Long.toHexString( userRsp ) );
                process.exit_code = 139; process.set_exit_flag(); break;
              }
              if( guestMem.faultIn( cr2, true ) ) {
                // demand 割当成功 → PF_STUB の `add rsp,8; iretq` が faulting 命令を再実行 (RIP 不変、writeGprs 不要)。
                continue;
              }
              // wild access (mmapRegions 外、faultIn 失敗 = 真の unmapped)。
              // issue #548-native: SIGSEGV ハンドラが登録済みなら guest に配送する (handler が
              //   ucontext.rip を書き替えて継続 = wasm trap / JS crash handler。async 復帰経路が
              //   uctx+168 を尊重するので生還できる)。未登録 (SIG_DFL/IGN) は従来どおり exit 139。
              // issue #617: file map の EOF を越えるページへのアクセスは SIGBUS(BUS_ADRERR)。
              boolean isBus = ( guestMem instanceof NativeMemoryBackend nmbBus ) && nmbBus.isBeyondEof( cr2 );
              int faultSig = isBus ? Signal.SIGBUS : Signal.SIGSEGV;
              long segvH = process.get_func_adrs( faultSig );
              if( segvH != Siginfo.SIG_DFL && segvH != Siginfo.SIG_IGN ) {
                long userFlg = guestMem.load64( ksTop - 0x18 );        // #PF frame の RFLAGS
                // vCPU を被中断点 (fault 命令) の user context に戻し、async signal として配送する。
                hv.setGpr( HvReg.RIP,    userRip );
                hv.setGpr( HvReg.RSP,    userRsp );
                hv.setGpr( HvReg.RFLAGS, userFlg );
                process.term_sig = 0;                                  // handler で処理 → 死因クリア
                if( isBus ) {                                          // issue #617: SIGBUS(BUS_ADRERR)
                  pendingFaultCode = 2 /*BUS_ADRERR*/; pendingFaultAddr = cr2;
                } else {
                  // issue #559-native: 保護ページ (mprotect PROT_NONE/READ) への違反は SEGV_ACCERR、
                  //   それ以外の unmapped は SEGV_MAPERR(canonical) / SI_KERNEL(非canonical)。
                  Integer prot = ( guestMem instanceof NativeMemoryBackend nmb ) ? nmb.protOf( cr2 ) : null;
                  boolean canon = ( cr2 >= 0 && Long.compareUnsigned( cr2, 0x800000000000L ) < 0 );
                  if( prot != null ) { pendingFaultCode = 2 /*SEGV_ACCERR*/; pendingFaultAddr = cr2; }
                  else { pendingFaultCode = canon ? 1 : 0x80; pendingFaultAddr = canon ? cr2 : 0L; }
                }
                process.recv_to_thread( myGuestTid(), faultSig );
                deliverPendingSignal( true );
                pendingFaultAddr = 0; pendingFaultCode = 0;            // 使用後クリア
                hv.writeGprs();                                        // 変更した RIP/RSP/handler 起動状態を KVM に反映
                continue;                                             // handler から再開
              }
              System.err.println( "[native][PF] SIGSEGV cr2=0x" + Long.toHexString( cr2 )
                  + " err=0x" + Long.toHexString( errCode ) + "(W=" + ( (errCode>>1)&1 ) + " I=" + ( (errCode>>4)&1 ) + ")"
                  + " userRip=0x" + Long.toHexString( userRip ) + " userRsp=0x" + Long.toHexString( userRsp )
                  + " pid=" + process.pid + " name=" + process.name );
              // issue #435: userRip=0 (NULL call) 等の呼び出し元特定用に user stack 先頭を dump。
              //   crash 経路でのみ実行されるので steady-state コストは無い。unmapped は '?' で継続。
              StringBuilder sd = new StringBuilder( "[native][PF] stack:" );
              for( int sw = 0 ; sw < 8 ; sw++ ) {
                sd.append( " [+0x" ).append( Integer.toHexString( sw * 8 )).append( "]=" );
                try { sd.append( "0x" ).append( Long.toHexString( guestMem.load64( userRsp + sw * 8L ))); }
                catch( Exception se ) { sd.append( "?" ); }
              }
              System.err.println( sd.toString( ));
              process.exit_code = 139; process.set_exit_flag(); break;
            }
            else if( Long.compareUnsigned( pfRip - EXC_STUB_VADDR, 32L * 8 ) < 0 ) {
              // raw=4 診断: IDT per-vector stub に来た = #PF 以外の CPU 例外発生。vector を特定して exit。
              int vec = (int) ( ( pfRip - EXC_STUB_VADDR ) / 8 );
              long ksTop = KSTACK_BASE + (long) tssSlot * 0x1000L + 0x1000L;
              long fErr  = guestMem.load64( ksTop - 0x30 );   // err-code 有り例外 (#DF/#TS/#NP/#SS/#GP/#AC) の error code
              long fRipE = guestMem.load64( ksTop - 0x28 );   //   err 有り frame の faulting RIP
              long fRipN = guestMem.load64( ksTop - 0x20 );   // err 無し例外の faulting RIP
              // issue #548-native: #GP (vector 13) は非 canonical アドレスアクセス等で発生する。Linux は
              //   これを SIGSEGV(si_code=SI_KERNEL=0x80、si_addr=0) として配送する。SIGSEGV ハンドラが
              //   登録済みなら guest に配送 (handler が ucontext.rip を書き替えて継続できる)。
              if( vec == 13 ) {
                long segvH = process.get_func_adrs( Signal.SIGSEGV );
                if( segvH != Siginfo.SIG_DFL && segvH != Siginfo.SIG_IGN ) {
                  long uRsp = guestMem.load64( ksTop - 0x10 ), uFlg = guestMem.load64( ksTop - 0x18 );
                  hv.setGpr( HvReg.RIP,    fRipE );
                  hv.setGpr( HvReg.RSP,    uRsp );
                  hv.setGpr( HvReg.RFLAGS, uFlg );
                  process.term_sig = 0;
                  pendingFaultCode = 0x80;   // SI_KERNEL (非 canonical / #GP 由来)
                  pendingFaultAddr = 0L;      //   si_addr は 0
                  process.recv_to_thread( myGuestTid(), Signal.SIGSEGV );
                  deliverPendingSignal( true );
                  pendingFaultAddr = 0; pendingFaultCode = 0;
                  hv.writeGprs();
                  continue;
                }
              }
              System.err.println( "[native][EXC] CPU 例外 vector=" + vec + " (" + excName( vec ) + ")"
                  + " cr2=0x" + Long.toHexString( hv.getCr2() )
                  + " faultRip[w/err]=0x" + Long.toHexString( fRipE )
                  + " [no-err]=0x" + Long.toHexString( fRipN )
                  + " errCode=0x" + Long.toHexString( fErr ) );
              process.exit_code = 139; process.set_exit_flag(); break;
            }
          }
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
          // issue #742 診断: 直近 syscall (sysno / 復帰先 rip=RCX / syscall 時 RSP) をリング記録。
          //   triple fault (rsp=0 等) の直前に guest が何をしたかを特定する。
          if( SYS_RING != null ) {
            int si = (sysRingPos++ & (SYS_RING_N - 1)) * 3;
            SYS_RING[si] = rax; SYS_RING[si+1] = rcx; SYS_RING[si+2] = hv.getGpr( HvReg.RSP );
          }

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
          // issue #435 追補: TLB 診断 (EMULIN_TLB_FLUSH_SYSCALL)。syscall 境界毎に CR3 再書込で self-flush。
          if( TLB_FLUSH_SYSCALL && !IS_KVM ) { try { hv.writeCr3( guestMem.pml4Phys() ); } catch( Throwable ignore ) {} }
          hv.writeGprs();
          continue;
        } else if( exitReason == HvVcpu.EXIT_INTR ) {
          // ★ host signal で KVM_RUN が割込まれた (step 3d-2c-39)。SIG_KICK (走行中 vCPU への async
          //   配信 kick) でも JVM GC/safepoint signal でも起きる。pending guest signal があれば
          //   async 配信し (= guest handler を被中断点で起動)、無ければ単に再 run する。
          if( process.is_exited() ) break;
          hv.readGprs();
          // issue #435 追補: lost-wakeup 診断用。EXIT_INTR (kick/cancel での run 中断) の頻度と
          //   被中断 RIP を可視化する (WHP では WHvCancelRunVirtualProcessor 由来のみのはず)。
          //   rip=STUB_VADDR+1/+2 (post-hlt、dispatch 前) は「cancel が HLT exit を飲み込んだ」確定
          //   観測 (@SWALLOWED-HLT)。backstop hlt が再トラップするので回復は自動、ここは計測のみ。
          if( SyscallAmd64.TRACE_WAKE ) {
            long irip = hv.getGpr( HvReg.RIP );
            String tag = ( irip == STUB_VADDR + 1 || irip == STUB_VADDR + 2 ) ? " @SWALLOWED-HLT"
                       : ( irip == SYSRETQ_VADDR ) ? " @RESUME" : "";
            SyscallAmd64._wakeTrace( "INTR tid=" + myGuestTid()
                + " rip=0x" + Long.toHexString( irip ) + tag
                + " rax=" + hv.getGpr( HvReg.RAX )
                + " rcx=0x" + Long.toHexString( hv.getGpr( HvReg.RCX ) ) );
          }
          // ★ #PF 復帰 race (#392/#422): PF_STUB (add rsp,8; iretq) を ring0 で実行中に kick/GC で中断
          //   されると RSP は CPU が #PF で切替えた RSP0 (kernel stack) を指す。この状態で async 配信すると
          //   deliverPendingSignal が hv.getGpr(RSP)=kstack を base に signal frame を組み、handler の RSP を
          //   kstack に設定 → handler (ring3) が kstack(US=0) を触り無限 #PF loop になる (claude は demand
          //   paging で #PF 頻発し race window が累積、高頻度で発火)。RSP が kernel stack 帯にある間 (=ring0、
          //   stub 実行中) は配信せず再 run し、ring3 境界 (次の syscall hlt=sync 配信、または ring3 での
          //   EXIT_INTR) まで defer する。pending signal は process に残るので失われない。
          if( Long.compareUnsigned( hv.getGpr( HvReg.RSP ) - KSTACK_BASE,
                                    (long) MAX_TSS_SLOTS * 0x1000L ) < 0 ) {
            continue;
          }
          deliverPendingSignal( true );   // async: 被中断点で handler 起動 (pending 無ければ no-op)
          hv.writeGprs();
          continue;
        } else {
          // 非 HLT exit (MMIO/IO/INTERNAL_ERROR/FAIL_ENTRY/SHUTDOWN 等) は MVP では
          //   未対応。診断 (rip + 全 GPR) を出して guest を error 終了させる (無限ループ回避)。
          //   raw exit_reason=8 (KVM_EXIT_SHUTDOWN) は IDT 無しでの triple fault = guest が未対応
          //   命令/メモリアクセスをした症状。rip を objdump で逆アセンブルし faulting 命令を特定。
          long rip = 0, cpl = -1, sb0 = -1, sb1 = -1;
          try { hv.readGprs(); rip = hv.getGpr( HvReg.RIP ); } catch( Throwable ignore ) {}
          try { cpl = hv.getCpl(); } catch( Throwable ignore ) {}
          // issue #339: sysretq(ring0 特権命令)を CPL=3 で実行→#GP→triple fault の仮説確認用。
          //   stub@SYSRETQ は 0x48,0x0F (sysretq REX.W+0F 07) のはず → page map/内容の健全性も確認。
          try { if( guestMem != null ) { sb0 = guestMem.load8( SYSRETQ_VADDR ) & 0xff; sb1 = guestMem.load8( SYSRETQ_VADDR + 1 ) & 0xff; } } catch( Throwable ignore ) {}
          System.err.println( "[native] unexpected hypervisor exit raw=" + hv.lastRawExit()
              + " rip=0x" + Long.toHexString( rip )
              + " CPL=" + cpl + " isChild=" + isChild + " tid=" + myGuestTid()
              + " stub@SYSRETQ=0x" + Long.toHexString( sb0 ) + ",0x" + Long.toHexString( sb1 )
              + " (MVP は HLT syscall trap のみ対応)" );
          System.err.println( "[native] " + dumpRegs() );
          // issue #742 診断: 直近 syscall 履歴 (古い順) を dump。sigFrames 深さ = 未復帰の signal frame。
          if( SYS_RING != null ) {
            StringBuilder rb = new StringBuilder( "[native] recent syscalls (sysno@retRip rsp), sigFrames=" + sigFrames.size() + ":\n" );
            for( int k = 0; k < SYS_RING_N; k++ ) {
              int idx = ((sysRingPos + k) & (SYS_RING_N - 1)) * 3;
              if( SYS_RING[idx+1] == 0 && SYS_RING[idx] == 0 ) continue;
              rb.append( "    #" ).append( SYS_RING[idx] )
                .append( " @0x" ).append( Long.toHexString( SYS_RING[idx+1] ) )
                .append( " rsp=0x" ).append( Long.toHexString( SYS_RING[idx+2] ) ).append( '\n' );
            }
            System.err.print( rb );
          }
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
    } catch( NativeMemoryBackend.NativeOom oom ) {
      // issue #713: pool 枯渇が #PF demand paging (faultIn) や syscall 層の load/store (xlat) で
      //   起きると ENOMEM を返す先が無い (mmap/mremap 経路は amd64_mmap が ENOMEM 変換済。ここは
      //   guest が commit 済み anon ページに touch した瞬間なので errno にできない)。旧実装は下の
      //   catch-all の RuntimeException で thread ごと死に、set_exit_flag が呼ばれず親の wait4 が
      //   永久に返らなかった (#710 の「セッション刺さり」)。Linux の OOM killer と同じ縮退 =
      //   SIGKILL 死として届け、親が WTERMSIG=9 で reap してセッションを継続できるようにする。
      //   pool 解放 (teardownKvm) は finally で従来どおり走る。exit_group (amd64_exit) と同じく
      //   exit_code + set_exit_flag のみで、明示 kick はしない (他 thread は次の trap で気づく)。
      System.err.println( "[native] pool 枯渇 -> OOM-kill (SIGKILL): " + oom.getMessage()
          + " pid=" + ( process != null ? process.pid : -1 )
          + " name=" + ( process != null ? process.name : "?" )
          + " (必要なら EMULIN_NATIVE_POOL_MB で pool 拡大)" );
      if( process != null ) {
        process.term_sig  = Signal.SIGKILL;                 // wait4 が WIFSIGNALED(9) を構築する
        process.exit_code = 128 + Signal.SIGKILL;           // 137 (Linux の OOM kill 準拠)
        // main process (親=init、ppid<=1) は JVM 終了コードにも反映 (Process.run の segfault 経路と同じ扱い)
        ProcessInfo mp = ( sysinfo != null && sysinfo.kernel != null ) ? sysinfo.kernel.get_pinfo( process.pid ) : null;
        if( mp != null && mp.ppid <= 1 ) sysinfo.kernel.last_exit_code = 128 + Signal.SIGKILL;
        process.set_exit_flag();
      }
    } catch( Throwable t ) {
      throw new RuntimeException(
          ( setupDone ? "NativeCpuBackend eval failed: " : "NativeCpuBackend KVM setup failed: " ) + t, t );
    } finally {
      RUNNING_TIDS.remove( myGuestTid(), hostTid );   // async kick 対象から外す (step 3d-2c-39)
      // issue #529: WHP kick 対象から外す (3e-whp-6)。値は put (:702) と同じ this を渡す。
      //   issue #435 で map の値型を HvVcpu → NativeCpuBackend に変えた際ここが hv のまま残り、
      //   remove(key, value) の等価比較が型不一致で常に false = 削除が常に失敗して exit 済み
      //   backend が RUNNING_VCPUS に溜まり続けていた (WHP のメモリリーク + process-wide kick が
      //   死んだ backend も走査)。remove(key, value) 形は「今も自分が登録されている場合だけ外す」
      //   が意図 (tid 再利用で別 backend が登録済みなら消さない) なので、この形のまま値だけ直す。
      RUNNING_VCPUS.remove( myGuestTid(), this );
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
        // SSE (OSFXSR/OSXMMEXCPT) + AVX (OSXSAVE)。OSXSAVE は CPUID.OSXSAVE を立て、XSETBV/XCR0 を許可する。
        KvmBindings.CR4_PAE | KvmBindings.CR4_OSFXSR | KvmBindings.CR4_OSXMMEXCPT | KvmBindings.CR4_OSXSAVE,
        KvmBindings.EFER_LME | KvmBindings.EFER_LMA | KvmBindings.EFER_SCE );

    // XCR0 = x87 | SSE | AVX (0x7) で 256-bit YMM を有効化 (issue: native backend の AVX 対応)。
    //   ★ CR4.OSXSAVE が立った後 (configureLongModeRing3 の後) かつ CPUID 設定後 (setCpuidFromHost、上で実施)
    //   に呼ぶこと。これで guest の VEX-encoded AVX 命令 (vmovdqu ymm 等) が #UD せず実行でき、かつ
    //   CPUID.OSXSAVE=1 を見るプログラム (Claude Code 等) が AVX path を選ぶ。host CPU が AVX 非対応なら
    //   KVM が XCR0.AVX を拒否しうるので、その場合は SSE まで (0x3) に fallback する。
    try {
      hv.setXcr0( KvmBindings.XCR0_X87 | KvmBindings.XCR0_SSE | KvmBindings.XCR0_AVX );   // 0x7
    } catch( Throwable avxErr ) {
      hv.setXcr0( KvmBindings.XCR0_X87 | KvmBindings.XCR0_SSE );                          // 0x3 (SSE のみ)
    }

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

    // issue #392 (戦略B): per-vCPU TSS + kernel stack を構築し GDTR/IDTR/TR をロード (NATIVE_PF gate)。
    //   #PF は IDT[14]→PF_STUB(ring0) へ vector し、TR.RSP0 (この vCPU 専用 kernel stack) に切替わる。
    //   共有 page table 上で vcpuId ごとに別 VA なので worker 並行 #PF でも kernel stack が壊れない。
    if( NATIVE_PF ) {
      long tssBase   = TSS_BASE    + (long) tssSlot * 0x1000L;   // review #2: recyclable slot で band 溢れ防止
      long kstackTop = KSTACK_BASE + (long) tssSlot * 0x1000L + 0x1000L;
      guestMem.mapSupervisor( tssBase, 0x68 );
      for( int b = 0; b < 8; b++ ) guestMem.store8( tssBase + 4 + b, (int)( kstackTop >>> (b * 8) ) & 0xFF );  // RSP0 @ offset 4
      guestMem.mapSupervisor( KSTACK_BASE + (long) tssSlot * 0x1000L, 0x1000 );
      hv.configureExceptionTables( GDT_VADDR, 0x4F, IDT_VADDR, 256 * 16 - 1, 0x40, tssBase, 0x67 );
    }

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
      if( SYS_RING != null )
        System.err.println( "[clone-child] tid=" + childTid + " entryRip=0x" + Long.toHexString( entryRip )
            + " rsp=0x" + Long.toHexString( rsp )
            + " cloneRegs.rsp=0x" + Long.toHexString( cloneRegs[6] )
            + " cloneRegs.rcx=0x" + Long.toHexString( cloneRegs[2] ) );
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
        if( guestMem != null ) guestMem.releaseSharedPages();          // issue #675: 共有 arena 参照を返却 (pool 解放前に)
        if( poolSeg != null ) HvVm.freeGuestRam( poolSeg, poolSize );  // issue #379: 実確保サイズで解放
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
    if( vmOwner != null && NATIVE_PF ) vmOwner.releaseTssSlot( tssSlot );   // review #2: TSS slot を free-list へ返却 (再利用)
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
    // issue #709 診断: clone→start→exit のライフサイクルを追跡 (stuck dump 有効時のみ)。
    //   「clone は tid を返したのに start が出ない/即 exit した」スレッドを凍結時に特定する。
    if( SyscallAmd64.EPOLL_STUCK_MS > 0 )
      System.err.println( "[thread] clone pid=" + process.pid + " name=" + process.name
          + " -> tid=" + tid + " vcpu=" + child.vcpuId );
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
      if( SyscallAmd64.EPOLL_STUCK_MS > 0 )    // issue #709 診断: thread 実始動の確認
        System.err.println( "[thread] start pid=" + child.process.pid + " tid=" + child.childTid );
      try {
        child.eval();   // setupVcpu (worker) + KVM_RUN loop
      } catch( SyscallAmd64.ThreadExitException te ) {
        // 正常な thread exit (#60)
      } catch( Throwable t ) {
        // issue #709: Linux では thread の未処理 fault は thread group 全体を殺す (SIGSEGV で
        //   process 死)。旧実装 (#432) は該当 worker だけ静かに退場させていたため、死んだ
        //   thread が握っていた userspace lock (mutex/channel) が永久に残り、残存 thread が
        //   contended mutex / join 待ちで deadlock = 「プロセスは生きているのにハング」に
        //   なっていた (実機の rg ハング → claude 凍結の直接原因)。software backend の
        //   Thread64 (#113/#597: worker segfault は process 全体を殺す) と同じ Linux 準拠に
        //   揃え、#713 の OOM-kill と同じ縮退で process 全体を signal 死させる。親は
        //   WTERMSIG=11 で reap できる (ツール失敗として可視化され、セッションは続行できる)。
        System.err.println( "[native] worker vcpu " + child.vcpuId + " (tid=" + child.childTid
            + ") crashed -> kill thread group (SIGSEGV): " + t
            + " pid=" + child.process.pid + " name=" + child.process.name );
        if( System.getenv( "EMULIN_TRACE_BACKEND" ) != null || SyscallAmd64.EPOLL_STUCK_MS > 0 )
          t.printStackTrace();
        child.process.term_sig  = Signal.SIGSEGV;
        child.process.exit_code = 128 + Signal.SIGSEGV;
        child.process.set_exit_flag();
        // futex/poll で park 中の sibling thread も SIGKILL の pending で EINTR させ、
        //   syscall 境界の psig で退場させる (set_exit_flag だけだと park したままになる)。
        child.process.recv( Signal.SIGKILL );
      } finally {
        // CLONE_CHILD_CLEARTID 慣例: *ctid=0 を書いて futex wake → pthread_join の FUTEX_WAIT
        //   (val=tid) を起こす。ctid が破損 unmapped を指す場合は skip (二次 fault 回避)。
        if( child.childCtidAddr != 0 && child.guestMem.in( child.childCtidAddr ) ) {
          try { child.guestMem.store32( child.childCtidAddr, 0 );
                FutexManager.wake( child.childCtidAddr, Integer.MAX_VALUE, child.guestMem ); }
          catch( Throwable ignore ) {}
        }
        FutexManager.onThreadExit( child.childTid );
        synchronized( child.process.active_thread_count ) {
          child.process.active_thread_count.decrementAndGet();
          child.process.active_thread_count.notifyAll();
        }
        if( SyscallAmd64.EPOLL_STUCK_MS > 0 )    // issue #709 診断: thread 退場の確認
          System.err.println( "[thread] exit pid=" + child.process.pid + " tid=" + child.childTid );
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
    System.err.println( "[native] native backend で処理できないケース: " + msg );
    System.err.println( "[native]   EMULIN_BACKEND=software で再実行してください "
        + "(software は全 binary を canonical に実行)。" );
    System.exit( 127 );
  }

  // issue #379: guest RAM pool を POOL_SIZE で確保。WHP の低位 32GB 窓ひっ迫で失敗したら
  //   floor まで段階的 (半分ずつ) に縮小して retry し、より多くの process が窓に収まるように
  //   する (pool は process 終了時に解放されるので、同時生存数 × pool が窓を超えるのが #379)。
  //   確保できた実サイズを poolSize に記録 (teardown/slot/gpaBacking が使う)。floor でも失敗
  //   なら fatalPoolExhausted。★KVM は mmap(MAP_ANON) が常に成功するので最初の POOL_SIZE で
  //   返り poolSize=POOL_SIZE = 従来と byte-identical (retry は WHP の VirtualAlloc2 失敗時のみ)。
  // issue #379: native pool が窓ひっ迫で取れないときの signal。boot/exec プロセスはこれを catch して
  //   software backend に fallback する (ELF は software Memory に load 済みなので feasible)。
  static final class PoolExhaustedException extends RuntimeException {
    PoolExhaustedException() { super( "native guest RAM pool 確保失敗 (32GB 窓枯渇、issue #379)" ); }
  }
  // issue #720: fork の pool 枯渇 → EAGAIN 経路を KVM で決定再現する診断スイッチ (fork 専用。
  //   boot/exec の software fallback 経路には影響させない)。
  static final boolean FORCE_POOL_EXHAUST = System.getenv( "EMULIN_FORCE_POOL_EXHAUST" ) != null;
  // issue #723: 「fork 子の pool だけが縮小される」状況 (32GB 窓ひっ迫、#379) を KVM で決定再現する
  //   診断スイッチ。EMULIN_FORCE_FORK_POOL_MB=<MB> で fork 子の確保開始サイズを強制的に小さくする
  //   (KVM の mmap は失敗しないため自然には縮小が起きない)。0 = 無効 (既定)。
  static final long FORCE_FORK_POOL = parseForceForkPool();
  private static long parseForceForkPool() {
    String e = System.getenv( "EMULIN_FORCE_FORK_POOL_MB" );
    if( e == null ) return 0;
    try { return Math.max( 0, Long.parseLong( e.trim() ) ) * 1024 * 1024; }
    catch( NumberFormatException ignore ) { return 0; }
  }

  // canFallback=true: 全縮小 retry が失敗したら PoolExhaustedException を投げ、呼び出し側が縮退する
  //   (boot/exec = Process が software backend へ fallback、fork = Kernel.fork が -EAGAIN、issue #720)。
  //   false は「throw できない呼び出し元」用の backstop (現在は未使用) で fatalPoolExhausted (System.exit)。
  //   startSize: 確保を試みる最大サイズ (通常 POOL_SIZE。fork 子は診断スイッチ #723 で縮められる)。
  private MemorySegment allocPoolRetry( long floor, boolean canFallback ) {
    return allocPoolRetry( floor, canFallback, POOL_SIZE );
  }
  private MemorySegment allocPoolRetry( long floor, boolean canFallback, long startSize ) {
    if( floor < MIN_POOL )  floor = MIN_POOL;
    if( floor > POOL_SIZE ) floor = POOL_SIZE;
    if( startSize < floor ) startSize = floor;     // floor (親 usedTop 等) は必ず満たす
    Throwable last = null;
    long sz = startSize;
    while( true ) {
      try {
        MemorySegment s = HvVm.allocGuestRam( sz );
        poolSize = sz;
        if( sz < POOL_SIZE )
          System.err.println( "[native] guest RAM pool を " + ( POOL_SIZE >> 20 ) + "->" + ( sz >> 20 )
              + "MB に縮小して確保 (32GB 窓ひっ迫、issue #379)" );
        return s;
      } catch( Throwable t ) { last = t; }
      if( sz <= floor ) break;
      sz = Math.max( sz / 2, floor );
    }
    if( canFallback ) throw new PoolExhaustedException();   // issue #379: exec は software へ fallback
    fatalPoolExhausted( floor, last );
    return null;   // unreachable (fatalPoolExhausted は System.exit)
  }

  // issue #379: boot/exec の pool floor = PT_LOAD memsz 合計 (pool の data 占有量) + 余裕。
  //   これ未満に縮めると binary の segment が pool に収まらず壊れるので最低限ここは確保する。
  private static long bootPoolFloor( Memory m ) {
    long sum = 0;
    for( int i = 0; i < m.segments; i++ ) {
      Segment seg = m.segment[i];
      if( seg == null || seg.p_type != 1 /* PT_LOAD */ ) continue;
      if( seg.p_memsz > 0 && seg.p_memsz < POOL_SIZE )
        sum += ( seg.p_memsz + 0xFFFL ) & ~0xFFFL;   // page 切り上げ
    }
    return sum + BOOT_HEADROOM;
  }

  // issue #379: native pool (guest RAM の host backing) の確保失敗専用。原因はほぼ常に
  //   「WHP の低位 32GB 仮想アドレス窓の枯渇」(VirtualAlloc2 は MEM_RESERVE のみで
  //   HighestEndingAddress<32GB に制約、HvVm.java)。多プロセス同時実行 (apt install 等) で
  //   生存プロセス数 × POOL_SIZE が 8GB JVM heap と窓を奪い合い、連続領域が取れなくなる。
  //   ★物理メモリ不足ではないので RAM 増設では直らない (旧 fatalUnsupported の "KVM backend /
  //   static ELF のみ" は WHP でも出る誤メッセージだった)。正しい対策を案内する。
  private static void fatalPoolExhausted( long size, Throwable cause ) {
    long mb = size / ( 1024L * 1024L );
    System.err.println( "[native] guest RAM pool (" + mb + "MB) を低位 32GB 仮想アドレス窓に確保できません。" );
    System.err.println( "[native]   = 多プロセス同時実行 (apt install 等) で 32GB 窓が枯渇 (issue #379)。" );
    System.err.println( "[native]   ★物理メモリ不足ではない (MEM_RESERVE は RAM/commit を消費しない)。" );
    System.err.println( "[native]   対策: EMULIN_NATIVE_POOL_MB=512 (pool を小さく窓に収める) または" );
    System.err.println( "[native]         EMULIN_BACKEND=software (pool 制約なし、apt 等は実用速度) で再実行。" );
    if( cause != null ) System.err.println( "[native]   詳細: " + cause );
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

  // ---- issue #392 (戦略B): x86 descriptor 構築 helper ----
  /** IDT 64-bit interrupt gate (16 byte) の下位 8 byte。offset/selector/type_attr を packing。 */
  private static long idtGateLow( long handler, int sel, int typeAttr ) {
    return ( handler & 0xFFFFL )
         | ( (long)( sel & 0xFFFF ) << 16 )
         | ( (long)( typeAttr & 0xFF ) << 40 )      // IST=0 (bits 32-39 は 0)
         | ( ( (handler >>> 16) & 0xFFFFL ) << 48 );
  }
  /** IDT gate の上位 8 byte = offset[32:63]。 */
  private static long idtGateHigh( long handler ) { return ( handler >>> 32 ) & 0xFFFFFFFFL; }

  // raw=4 (triple fault) 診断: CPU 例外 vector → 名前 (Intel SDM Vol.3 Table 6-1)。
  private static String excName( int v ) {
    switch( v ) {
      case 0:  return "#DE div-by-zero";
      case 1:  return "#DB debug";
      case 2:  return "NMI";
      case 3:  return "#BP breakpoint";
      case 4:  return "#OF overflow";
      case 5:  return "#BR bound-range";
      case 6:  return "#UD invalid-opcode";
      case 7:  return "#NM device-not-available";
      case 8:  return "#DF double-fault";
      case 10: return "#TS invalid-TSS";
      case 11: return "#NP segment-not-present";
      case 12: return "#SS stack-fault";
      case 13: return "#GP general-protection";
      case 16: return "#MF x87-fp";
      case 17: return "#AC alignment-check";
      case 18: return "#MC machine-check";
      case 19: return "#XM SIMD-fp";
      case 20: return "#VE virtualization";
      case 21: return "#CP control-protection";
      default: return "vec" + v;
    }
  }
  /** 64-bit TSS descriptor (16 byte) の下位 8 byte。type=0xB (busy 64-bit TSS)、S=0、DPL=0、G=0。 */
  private static long tssDescLow( long base, int limit ) {
    return ( limit & 0xFFFFL )
         | ( ( base & 0xFFFFFFL ) << 16 )
         | ( 0x8BL << 40 )                          // P=1, DPL=0, S=0, type=0xB (busy 64-bit TSS)
         | ( ( (long)( limit >>> 16 ) & 0xF ) << 48 )
         | ( ( (base >>> 24) & 0xFFL ) << 56 );
  }
  /** TSS descriptor の上位 8 byte = base[32:63]。 */
  private static long tssDescHigh( long base ) { return ( base >>> 32 ) & 0xFFFFFFFFL; }

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

  // issue #498: RIP が ring0 専用 stub (syscall/#PF/例外 stub) 内かを判定。いずれも mapSupervisor で
  //   US=0 のため CPL3 から直接実行される値ではないが、async kick が stub 実行中 (ring0) の vCPU を
  //   捕まえると deliverPendingSignal がこの RIP を「被中断 user RIP」として扱ってしまう (詳細は
  //   deliverPendingSignal のコメント参照)。SIGTRAMP_VADDR は US=1 で ring-3 実行が正当なため含めない。
  private static boolean inKernelStub( long rip ) {
    return ( Long.compareUnsigned( rip, STUB_VADDR ) >= 0
             && Long.compareUnsigned( rip, STUB_VADDR + 0x1000L ) < 0 )
        || ( Long.compareUnsigned( rip, PF_STUB_VADDR ) >= 0
             && Long.compareUnsigned( rip, PF_STUB_VADDR + 0x1000L ) < 0 )
        || ( Long.compareUnsigned( rip - EXC_STUB_VADDR, 32L * 8 ) < 0 );
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
    // issue #435 追補: lost-wakeup 診断用。pending signal の配信試行を可視化する。
    if( SyscallAmd64.TRACE_WAKE )
      SyscallAmd64._wakeTrace( "SIGDELIV sig=" + sig + " async=" + async
          + " tid=" + myGuestTid() + " masked=" + process.is_signal_masked( sig ) );
    if( async && process.is_signal_masked( sig ) ) return;   // masked signal は async 配信しない (pending 維持)
    // ★ issue #339: async kick が syscall-return stub の窓 (RIP=SYSRETQ_VADDR、ring0、sysretq
    //   実行直前) で KVM_RUN を割込んだ場合、ここで async 配信すると被中断 RIP=stub が SigFrame に
    //   保存され、handler の rt_sigreturn (async 復帰) が exitToRing3 で ring-3 の stub に着地 →
    //   sysretq (ring-0 特権命令) を CPL=3 で実行 → #GP → IDT 無し → triple fault (KVM_EXIT_SHUTDOWN)。
    //   並行 nested fork (子の SIGCHLD 多発 = kick 多発) で顕在化 (dpkg-realpath の fork 連鎖等)。
    //   stub ページ内被中断では配信せず signal を pending のまま残す → vCPU は sysretq を完了して ring-3
    //   user に戻り、次の syscall 境界 (sync) か ring-3 での次 kick (async) で安全に配信される。
    if( async ) {
      long irip = hv.getGpr( HvReg.RIP );
      // stub 窓: pending 維持。STUB_VADDR は KERN_HI (high-half、signed では負) なので Long.compareUnsigned で
      //   範囲判定する (#422。signed `>=`/`<` は high-half 帯と低位 ring-3 RIP の大小が逆転して壊れうる)。
      // issue #498: 元は syscall stub (STUB_VADDR) しか見ておらず、#PF stub (PF_STUB_VADDR) / CPU 例外
      //   stub (EXC_STUB_VADDR) 滞在中 (ring0、faultIn 処理待ちの窓) の kick はここを素通りしていた。
      //   その結果 ring0 の RIP(=PF_STUB+1)/RSP(=kstack) がそのまま SigFrame に「user context」として
      //   保存され、rt_sigreturn の exitToRing3 で CPL3 に復元 → CPL3 が supervisor 専用ページ (PF_STUB) を
      //   命令フェッチして #PF (US 違反)、faultIn は present ページを無条件成功扱いするため同一命令を再実行
      //   → 無限 #PF ループ (cr2=userRip=PF_STUB+1) で fatal、という git index-pack 大規模 pack での
      //   クラッシュが発生していた (KVM は kickGuestTid→EXIT_INTR 経由でこの kickPending 経路を通らず
      //   非該当。EMULIN_FORCE_WHP_KICK=1 で KVM 上でも同一クラッシュを確定的に再現し検証済み)。
      //   RIP がいずれかの ring0 stub 内、または RSP∈kstack 帯 (:848 の EXIT_INTR 経路と同じ述語、未知の
      //   ring0 経路への保険) なら pending を維持して defer する。SIGTRAMP は US=1 で ring-3 実行が正当
      //   なので対象外 (この窓での defer は次の syscall 境界か次回 kick で安全に配信されるだけで signal は
      //   失われない)。
      if( inKernelStub( irip )
          || Long.compareUnsigned( hv.getGpr( HvReg.RSP ) - KSTACK_BASE,
                                    (long) MAX_TSS_SLOTS * 0x1000L ) < 0 ) {
        if( SyscallAmd64.TRACE_WAKE )
          SyscallAmd64._wakeTrace( "SIGDELIV DEFER sig=" + sig + " tid=" + myGuestTid()
              + " rip=0x" + Long.toHexString( irip ) + " rsp=0x" + Long.toHexString( hv.getGpr( HvReg.RSP ) )
              + " (ring0 stub/kstack window, issue #498)" );
        return;
      }
    }
    long handler = process.get_func_adrs( sig );
    // issue #615: 配送する siginfo を消費前に読む (RT signal は 1 つずつ消費、標準は合体)。
    int  siCode  = process.get_si_code( sig );
    long siValue = process.get_si_value( sig );
    int  siPid   = process.get_si_pid( sig );
    process.consume_one( sig );
    if( handler == Siginfo.SIG_IGN ) return;
    if( handler == Siginfo.SIG_DFL ) {
      if( process.get_action_type( sig ) == Signal.SIGACTION_EXIT ) {
        process.term_sig = sig;   // issue #411: 死因 signal を記録 → wait4 が WIFSIGNALED(sig) を返す
        process.set_exit_flag();
      }
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
      if( pendingFaultCode != 0 ) {        // issue #548-native: #PF 由来 SIGSEGV の si_code / si_addr
        guestMem.store32( siginfo + 8,  pendingFaultCode );
        guestMem.store64( siginfo + 16, pendingFaultAddr );
      } else {
        // issue #615: user 生成 signal (kill=SI_USER(0) / sigqueue=SI_QUEUE(-1)) の siginfo。
        //   si_code<=0 は si_pid@16 / si_uid@20 / si_value@24 (sigqueue の si_value を運ぶ)。
        guestMem.store32( siginfo + 8, siCode );
        if( siCode <= 0 ) {
          guestMem.store32( siginfo + 16, siPid );
          guestMem.store32( siginfo + 20, process.uid );
          guestMem.store64( siginfo + 24, siValue );
        }
      }
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
