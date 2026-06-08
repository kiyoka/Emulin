// ----------------------------------------
//  Linux KVM (/dev/kvm) FFM bindings — Phase 0 step 3a
//  (issue #221)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// 目的: Linux KVM の user-mode VMM API (/dev/kvm + ioctl) を Java FFM (Project
//   Panama / java.lang.foreign、Java 22+ で finalized、emulin は 25 LTS で
//   採用済) で呼ぶ bindings。issue #221 Phase 0 step 3a の hello world =
//   16-bit real mode で NOP×3 + HLT を実 vCPU で実行し KVM_EXIT_HLT を観測
//   する KvmSmoke 用の最小 API surface を提供する。
//
// Windows Hypervisor Platform (WHP、issue #221 §4 / WhpBindings.java) と同等の
//   設計原則:
//   - probe() で /dev/kvm の open 可否を確認 (kvm group 加入 / nested virt
//     有効の WSL2 / Linux native で true、それ以外で false)。
//   - MethodHandle は lazy 初期化 (open/close/ioctl/mmap/munmap を libc 経由)。
//   - 実 VM 起動 / vCPU 制御は KvmSmoke (step 3a) / NativeCpuBackend KVM
//     経路 (step 3b+) で組み立てる。
//
// 範囲 (本 step):
//   - /dev/kvm の open/close、ioctl wrapper (libc)、mmap/munmap (libc)
//   - 主要 ioctl 番号定数 (KVM_GET_API_VERSION / KVM_CREATE_VM /
//     KVM_GET_VCPU_MMAP_SIZE / KVM_CREATE_VCPU / KVM_SET_USER_MEMORY_REGION /
//     KVM_RUN / KVM_GET/SET_REGS / KVM_GET/SET_SREGS)
//   - struct の byte offset 定数 (kvm_regs / kvm_segment / kvm_dtable /
//     kvm_sregs / kvm_run / kvm_userspace_memory_region)
//   - KVM_EXIT_* 定数
//
// 範囲外 (step 3b+):
//   - 64-bit long mode セットアップ (CR0/CR3/EFER/GDT/page table)
//   - MMIO / IO port 等の exit reason 別処理
//   - guest 物理 ↔ Memory.byte[] の本格的 map
//   - syscall trap (LSTAR スタブ方式)
//
// API リファレンス: kernel 公式 doc + /usr/include/linux/kvm.h /
//   /usr/include/x86_64-linux-gnu/asm/kvm.h
//   https://docs.kernel.org/virt/kvm/api.html
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

public final class KvmBindings {

  // ===== ioctl 番号定数 =====
  //
  //   _IO(KVMIO, n)        = 0x0000_AE00 | n     (info-less)
  //   _IOR(KVMIO, n, sz)   = 0x8000_AE00 | n | (sz << 16) (read)
  //   _IOW(KVMIO, n, sz)   = 0x4000_AE00 | n | (sz << 16) (write)
  //   _IOWR(KVMIO, n, sz)  = 0xC000_AE00 | n | (sz << 16)
  //
  //   今回使う値 (sizeof は asm/kvm.h より):
  //     KVM_GET_API_VERSION   _IO 0x00 → 0xAE00
  //     KVM_CREATE_VM         _IO 0x01 → 0xAE01
  //     KVM_GET_VCPU_MMAP_SIZE _IO 0x04 → 0xAE04
  //     KVM_CREATE_VCPU       _IO 0x41 → 0xAE41
  //     KVM_SET_USER_MEMORY_REGION _IOW sz=32 0x46 → 0x4020_AE46
  //     KVM_RUN               _IO 0x80 → 0xAE80
  //     KVM_GET_REGS          _IOR sz=144 0x81 → 0x8090_AE81
  //     KVM_SET_REGS          _IOW sz=144 0x82 → 0x4090_AE82
  //     KVM_GET_SREGS         _IOR sz=312 0x83 → 0x8138_AE83
  //     KVM_SET_SREGS         _IOW sz=312 0x84 → 0x4138_AE84
  //
  //   検算は KvmSmoke 初回 run で行う (KVM_GET_API_VERSION が 12 を返すなら OK)。

  public static final long KVM_GET_API_VERSION         = 0xAE00L;
  public static final long KVM_CREATE_VM               = 0xAE01L;
  public static final long KVM_GET_VCPU_MMAP_SIZE      = 0xAE04L;
  public static final long KVM_CREATE_VCPU             = 0xAE41L;
  public static final long KVM_SET_USER_MEMORY_REGION  = 0x4020AE46L;
  public static final long KVM_RUN                     = 0xAE80L;
  public static final long KVM_GET_REGS                = 0x8090AE81L;
  public static final long KVM_SET_REGS                = 0x4090AE82L;
  public static final long KVM_GET_SREGS               = 0x8138AE83L;
  public static final long KVM_SET_SREGS               = 0x4138AE84L;
  // KVM_GET_MSRS  _IOWR(KVMIO, 0x88, struct kvm_msrs sizeof=8) → 0xC008AE88
  // KVM_SET_MSRS  _IOW (KVMIO, 0x89, struct kvm_msrs sizeof=8) → 0x4008AE89
  //   (struct kvm_msrs の sizeof は flexible array 部を除く header = 8 byte)
  public static final long KVM_GET_MSRS                = 0xC008AE88L;
  public static final long KVM_SET_MSRS                = 0x4008AE89L;

  // CPUID passthrough (glibc の CPU ISA level check 等が CPUID を見るので host 機能を vCPU に流す)。
  // KVM_GET_SUPPORTED_CPUID _IOWR(KVMIO, 0x05, struct kvm_cpuid2 header=8) → 0xC008AE05 (system fd)
  // KVM_SET_CPUID2          _IOW (KVMIO, 0x90, struct kvm_cpuid2 header=8) → 0x4008AE90 (vcpu fd)
  public static final long KVM_GET_SUPPORTED_CPUID     = 0xC008AE05L;
  public static final long KVM_SET_CPUID2              = 0x4008AE90L;
  // struct kvm_cpuid2 { __u32 nent; __u32 padding; struct kvm_cpuid_entry2 entries[0]; }
  //   header = 8 byte、entry = 40 byte (function/index/flags/eax/ebx/ecx/edx + padding[3])。
  public static final int  KVM_CPUID2_OFF_NENT         = 0;
  public static final int  KVM_CPUID2_OFF_ENTRIES      = 8;
  public static final int  KVM_CPUID_ENTRY_SIZE        = 40;

  public static final int KVM_API_VERSION = 12;

  // ===== exit_reason =====

  public static final int KVM_EXIT_UNKNOWN       = 0;
  public static final int KVM_EXIT_EXCEPTION     = 1;
  public static final int KVM_EXIT_IO            = 2;
  public static final int KVM_EXIT_HYPERCALL     = 3;
  public static final int KVM_EXIT_DEBUG         = 4;
  public static final int KVM_EXIT_HLT           = 5;
  public static final int KVM_EXIT_MMIO          = 6;
  public static final int KVM_EXIT_INTR          = 10;
  public static final int KVM_EXIT_SHUTDOWN      = 8;
  public static final int KVM_EXIT_FAIL_ENTRY    = 9;
  public static final int KVM_EXIT_INTERNAL_ERROR = 17;

  // ===== struct kvm_userspace_memory_region (32 byte) =====
  //   slot (u32)              @ 0
  //   flags (u32)             @ 4
  //   guest_phys_addr (u64)   @ 8
  //   memory_size (u64)       @ 16
  //   userspace_addr (u64)    @ 24

  public static final int KVM_MEM_REGION_SIZE     = 32;
  public static final int KVM_MEM_OFF_SLOT        = 0;
  public static final int KVM_MEM_OFF_FLAGS       = 4;
  public static final int KVM_MEM_OFF_GUEST_ADDR  = 8;
  public static final int KVM_MEM_OFF_SIZE        = 16;
  public static final int KVM_MEM_OFF_USER_ADDR   = 24;

  // ===== struct kvm_regs (144 byte) =====
  //   rax/rbx/rcx/rdx/rsi/rdi/rsp/rbp/r8..r15/rip/rflags の順、各 u64

  public static final int KVM_REGS_SIZE      = 144;
  public static final int KVM_REGS_OFF_RAX   = 0;
  public static final int KVM_REGS_OFF_RBX   = 8;
  public static final int KVM_REGS_OFF_RCX   = 16;
  public static final int KVM_REGS_OFF_RDX   = 24;
  public static final int KVM_REGS_OFF_RSI   = 32;
  public static final int KVM_REGS_OFF_RDI   = 40;
  public static final int KVM_REGS_OFF_RSP   = 48;
  public static final int KVM_REGS_OFF_RBP   = 56;
  public static final int KVM_REGS_OFF_R8    = 64;
  public static final int KVM_REGS_OFF_R9    = 72;   // syscall arg6
  public static final int KVM_REGS_OFF_R10   = 80;   // syscall arg4
  public static final int KVM_REGS_OFF_R11   = 88;   // syscall: 退避された RFLAGS (sysret で復元)
  public static final int KVM_REGS_OFF_R12   = 96;
  public static final int KVM_REGS_OFF_R13   = 104;
  public static final int KVM_REGS_OFF_R14   = 112;
  public static final int KVM_REGS_OFF_R15   = 120;
  public static final int KVM_REGS_OFF_RIP   = 128;
  public static final int KVM_REGS_OFF_RFLAGS = 136;

  // ===== struct kvm_segment (24 byte) =====
  //   base (u64)     @ 0
  //   limit (u32)    @ 8
  //   selector (u16) @ 12
  //   type (u8) / present (u8) / dpl (u8) / db (u8) / s (u8) / l (u8) /
  //     g (u8) / avl (u8) / unusable (u8) / padding (u8)  @ 14...23

  public static final int KVM_SEG_SIZE         = 24;
  public static final int KVM_SEG_OFF_BASE     = 0;
  public static final int KVM_SEG_OFF_LIMIT    = 8;
  public static final int KVM_SEG_OFF_SELECTOR = 12;
  public static final int KVM_SEG_OFF_TYPE     = 14;
  public static final int KVM_SEG_OFF_PRESENT  = 15;
  public static final int KVM_SEG_OFF_DPL      = 16;
  public static final int KVM_SEG_OFF_DB       = 17;
  public static final int KVM_SEG_OFF_S        = 18;
  public static final int KVM_SEG_OFF_L        = 19;
  public static final int KVM_SEG_OFF_G        = 20;
  public static final int KVM_SEG_OFF_AVL      = 21;
  public static final int KVM_SEG_OFF_UNUSABLE = 22;

  // ===== struct kvm_sregs (312 byte) =====
  //   kvm_segment cs, ds, es, fs, gs, ss   @ 0  (6 × 24 = 144)
  //   kvm_segment tr, ldt                  @ 144 (2 × 24 = 48)
  //   kvm_dtable gdt, idt                  @ 192 (2 × 16 = 32)
  //   cr0, cr2, cr3, cr4, cr8 (u64×5)      @ 224
  //   efer (u64)                           @ 264
  //   apic_base (u64)                      @ 272
  //   interrupt_bitmap[(256+63)/64]=4×u64  @ 280

  public static final int KVM_SREGS_SIZE          = 312;
  public static final int KVM_SREGS_OFF_CS        = 0;
  public static final int KVM_SREGS_OFF_DS        = 24;
  public static final int KVM_SREGS_OFF_ES        = 48;
  public static final int KVM_SREGS_OFF_FS        = 72;
  public static final int KVM_SREGS_OFF_GS        = 96;
  public static final int KVM_SREGS_OFF_SS        = 120;
  public static final int KVM_SREGS_OFF_CR0       = 224;
  public static final int KVM_SREGS_OFF_CR3       = 240;
  public static final int KVM_SREGS_OFF_CR4       = 248;
  public static final int KVM_SREGS_OFF_EFER      = 264;

  // ===== struct kvm_run (mmap 経由で読む。先頭部だけ使う) =====
  //   request_interrupt_window (u8) @ 0
  //   immediate_exit (u8)           @ 1
  //   padding1[6] (u8)              @ 2-7
  //   exit_reason (u32)             @ 8
  //   ...

  public static final int KVM_RUN_OFF_EXIT_REASON = 8;

  // ===== x86-64 long mode セットアップ用 architectural 定数 =====
  //   (KvmSmoke64 / step 3d の NativeCpuBackend KVM 経路で guest を 64-bit
  //    long mode に入れるのに使う。Intel SDM Vol.3 準拠)

  // CR0 bits
  public static final long CR0_PE = 1L << 0;   // Protection Enable
  public static final long CR0_MP = 1L << 1;   // Monitor Coprocessor
  public static final long CR0_ET = 1L << 4;   // Extension Type
  public static final long CR0_NE = 1L << 5;   // Numeric Error
  public static final long CR0_WP = 1L << 16;  // Write Protect
  public static final long CR0_AM = 1L << 18;  // Alignment Mask
  public static final long CR0_PG = 1L << 31;  // Paging
  // long mode で広く使われる well-tested な CR0 値 (PE|MP|ET|NE|WP|AM|PG)
  public static final long CR0_LONG_MODE = CR0_PE | CR0_MP | CR0_ET | CR0_NE | CR0_WP | CR0_AM | CR0_PG;

  // CR4 bits
  public static final long CR4_PAE        = 1L << 5;   // Physical Address Extension (long mode 必須)
  public static final long CR4_OSFXSR     = 1L << 9;   // SSE 有効 (FXSAVE/FXRSTOR + SSE 命令)。
                                                       //   未設定だと guest の SSE 命令が #UD→triple fault。
  public static final long CR4_OSXMMEXCPT = 1L << 10;  // SSE 浮動小数点例外 (#XM) を有効化

  // EFER (MSR 0xC0000080) bits
  public static final long EFER_SCE = 1L << 0;  // System Call Extensions (syscall 命令、step 3c)
  public static final long EFER_LME = 1L << 8;  // Long Mode Enable
  public static final long EFER_LMA = 1L << 10; // Long Mode Active (paging on で CPU が立てる)
  public static final long EFER_NXE = 1L << 11; // No-Execute Enable

  // page table entry (PML4E / PDPTE / PDE / PTE) flags
  public static final long PTE_P  = 1L << 0;   // Present
  public static final long PTE_RW = 1L << 1;   // Read/Write
  public static final long PTE_US = 1L << 2;   // User/Supervisor (1 = user/ring3 可)
  public static final long PTE_PS = 1L << 7;   // Page Size (PDE で 1 = 2MB page)

  // segment descriptor type field 値 (kvm_segment.type)
  public static final int SEG_TYPE_CODE = 0xB;  // execute/read/accessed (1011)
  public static final int SEG_TYPE_DATA = 0x3;  // read/write/accessed (0011)

  // ===== MSR (model specific register) — syscall trap 用 (step 3c) =====
  //   struct kvm_msrs   { u32 nmsrs@0; u32 pad@4; kvm_msr_entry entries[]@8; }
  //   struct kvm_msr_entry { u32 index@0; u32 reserved@4; u64 data@8; }  size=16
  public static final int KVM_MSRS_OFF_NMSRS   = 0;
  public static final int KVM_MSRS_OFF_ENTRIES = 8;
  public static final int KVM_MSR_ENTRY_SIZE   = 16;
  public static final int KVM_MSR_ENTRY_OFF_INDEX = 0;
  public static final int KVM_MSR_ENTRY_OFF_DATA  = 8;

  // MSR index (Intel SDM Vol.4 / AMD)
  public static final int MSR_EFER          = 0xC0000080;  // Extended Feature Enable
  public static final int MSR_STAR          = 0xC0000081;  // syscall/sysret CS/SS base selectors
  public static final int MSR_LSTAR         = 0xC0000082;  // 64-bit syscall entry RIP
  public static final int MSR_CSTAR         = 0xC0000083;  // compat syscall entry (未使用)
  public static final int MSR_SYSCALL_MASK  = 0xC0000084;  // FMASK: syscall 時に clear する RFLAGS bits
  public static final int MSR_FS_BASE       = 0xC0000100;
  public static final int MSR_GS_BASE       = 0xC0000101;
  public static final int MSR_KERNEL_GS_BASE = 0xC0000102; // swapgs 用

  // ===== probe + libc FFM =====

  private static volatile Boolean probedAvailable;

  private static SymbolLookup libc;
  private static Linker       linker;
  private static MethodHandle mhOpen;     // int open(const char*, int)
  private static MethodHandle mhClose;    // int close(int)
  private static MethodHandle mhIoctl;    // int ioctl(int, unsigned long, void*)
  private static MethodHandle mhMmap;     // void* mmap(void*, size_t, int, int, int, off_t)
  private static MethodHandle mhMunmap;   // int munmap(void*, size_t)
  // errno capture (FFM canonical idiom)。captureCallState("errno") を downcall に
  //   付けると、native stub が呼び出し直後に errno を capture segment へ書き込む
  //   = JVM の活動 (GC / JIT helper / downcall machinery 自身) が thread-local の
  //   errno を clobber する前に保存される。失敗した downcall の「後」に別の
  //   __errno_location() downcall で errno を読む旧実装は java.lang.foreign.Linker
  //   契約上 unspecified (= 間の JVM 活動で errno が上書きされうる)。errno は
  //   step 3b/3c の KVM_RUN/SET_SREGS デバッグの主信号なので確実性が要る。
  //   capture segment は per-thread (ThreadLocal) — step 3b の multi-vCPU thread でも
  //   各 thread の errno を正しく取れる。__errno_location への glibc/musl/Windows
  //   依存も同時に解消する。
  private static final StructLayout CAPTURE_LAYOUT = Linker.Option.captureStateLayout();
  private static final VarHandle    ERRNO_VH =
      CAPTURE_LAYOUT.varHandle( MemoryLayout.PathElement.groupElement( "errno" ) );
  private static final ThreadLocal<MemorySegment> CAPTURE_SEG =
      ThreadLocal.withInitial( () -> Arena.ofAuto().allocate( CAPTURE_LAYOUT ) );

  private KvmBindings() {}  // static-only

  /**
   * /dev/kvm が open できるかを確認する。
   *
   *   - Linux + nested virt 有効 WSL2 + kvm group 加入 → true
   *   - macOS / Windows / kvm 権限無し / dev/kvm 無し → false
   *
   * libc FFM 解決も同時に行うので、初回 probe で MethodHandle 全部の link を済ませる。
   * 結果は cache されるので 2 回目以降は実 open を再実行しない。
   */
  public static synchronized boolean probe() {
    if( probedAvailable != null ) return probedAvailable;
    try {
      linker = Linker.nativeLinker();
      libc   = linker.defaultLookup();
      // libc 主要関数の resolve。captureCallState("errno") を付けると downcall の
      //   第 1 引数が capture segment になる (各 wrapper が CAPTURE_SEG.get() を渡す)。
      Linker.Option cap = Linker.Option.captureCallState( "errno" );
      mhOpen   = downcall( "open",   FunctionDescriptor.of(
          ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT ), cap );
      mhClose  = downcall( "close",  FunctionDescriptor.of(
          ValueLayout.JAVA_INT, ValueLayout.JAVA_INT ), cap );
      mhIoctl  = downcall( "ioctl",  FunctionDescriptor.of(
          ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
          ValueLayout.ADDRESS ), cap );
      mhMmap   = downcall( "mmap",   FunctionDescriptor.of(
          ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
          ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
          ValueLayout.JAVA_LONG ), cap );
      mhMunmap = downcall( "munmap", FunctionDescriptor.of(
          ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG ), cap );

      // 軽い probe: /dev/kvm を O_RDWR で open 試行 → 成功なら close。
      //   capture segment を第 1 引数で渡す。
      try( Arena a = Arena.ofConfined() ) {
        MemorySegment path = a.allocateFrom( "/dev/kvm" );
        int fd = (int) mhOpen.invoke( CAPTURE_SEG.get(), path, O_RDWR );
        if( fd < 0 ) { probedAvailable = false; return false; }
        mhClose.invoke( CAPTURE_SEG.get(), fd );
      }
      probedAvailable = true;
      return true;
    }
    catch( Throwable t ) {
      probedAvailable = false;
      return false;
    }
  }

  /** 1 行 banner 用説明 */
  public static String describeAvailability() {
    if( probedAvailable == null ) probe();
    return probedAvailable ? "KVM detected (/dev/kvm OK)" : "KVM not available";
  }

  // ===== libc 関数アクセサ (probe 後にのみ呼べる) =====

  public static final int O_RDWR  = 0x02;
  public static final int PROT_READ  = 0x01;
  public static final int PROT_WRITE = 0x02;
  public static final int MAP_SHARED = 0x01;

  private static void requireProbe() {
    if( !probe() ) throw new IllegalStateException( "KVM not available; call KvmBindings.probe() first" );
  }

  /** open(path, flags) → fd (失敗 = -1、errno は errno() で取得) */
  public static int open( MemorySegment path, int flags ) throws Throwable {
    requireProbe();
    return (int) mhOpen.invoke( CAPTURE_SEG.get(), path, flags );
  }

  /** close(fd) → 0 / -1 */
  public static int close( int fd ) throws Throwable {
    requireProbe();
    return (int) mhClose.invoke( CAPTURE_SEG.get(), fd );
  }

  /**
   * ioctl(fd, request, arg) — KVM ioctl 全般。
   *   request が「番号のみ」(KVM_CREATE_VM / KVM_GET_API_VERSION /
   *   KVM_GET_VCPU_MMAP_SIZE / KVM_CREATE_VCPU / KVM_RUN) のときは arg に
   *   MemorySegment.NULL を渡す。それ以外は MemorySegment ポインタ。
   *   戻り値は KVM の種別による:
   *     KVM_CREATE_VM / KVM_CREATE_VCPU → 新 fd (失敗 = -1)
   *     KVM_GET_API_VERSION             → API version (12)
   *     KVM_GET_VCPU_MMAP_SIZE          → vcpu_run mmap サイズ
   *     その他                          → 0 (成功) / -1 (失敗)
   */
  public static int ioctl( int fd, long request, MemorySegment arg ) throws Throwable {
    requireProbe();
    return (int) mhIoctl.invoke( CAPTURE_SEG.get(), fd, request, arg );
  }

  /** mmap(addr, length, prot, flags, fd, offset) → 領域先頭 (失敗 = MAP_FAILED = -1) */
  public static MemorySegment mmap( MemorySegment addr, long length, int prot,
                                    int flags, int fd, long offset ) throws Throwable {
    requireProbe();
    return (MemorySegment) mhMmap.invoke( CAPTURE_SEG.get(), addr, length, prot, flags, fd, offset );
  }

  /** munmap(addr, length) → 0 / -1 */
  public static int munmap( MemorySegment addr, long length ) throws Throwable {
    requireProbe();
    return (int) mhMunmap.invoke( CAPTURE_SEG.get(), addr, length );
  }

  /**
   * 直前の libc 呼び出し (open/close/ioctl/mmap/munmap) の errno を返す。
   * captureCallState で各 downcall 直後に capture segment へ保存された値を読む
   * (= 呼び出し直後の真の errno、JVM 活動で clobber されない)。
   */
  public static int errno() {
    return (int) ERRNO_VH.get( CAPTURE_SEG.get(), 0L );
  }

  // ===== 内部 helper =====

  private static MethodHandle downcall( String symbol, FunctionDescriptor descriptor,
                                        Linker.Option... opts ) {
    MemorySegment addr = libc.find( symbol ).orElseThrow(
        () -> new UnsatisfiedLinkError( "libc symbol not found: " + symbol ) );
    return linker.downcallHandle( addr, descriptor, opts );
  }
}
