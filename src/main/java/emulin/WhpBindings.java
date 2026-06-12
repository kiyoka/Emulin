// ----------------------------------------
//  Windows Hypervisor Platform (WHP) FFM bindings — stub
//  (issue #221 Phase 0 step 2)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// 目的: Hyper-V の user-mode VMM API である Windows Hypervisor Platform
//   (WinHvPlatform.dll) を Java FFM (Project Panama / java.lang.foreign、
//   Java 22+ で finalized、25 LTS 採用) で呼ぶための bindings。Phase 0 step 2
//   時点では「probe = 可用性チェック」と「MethodHandle 雛形」までで、実 partition
//   起動は Phase 0 step 3 (Windows 実機 PoC) で実装する。
//
// 設計原則:
//   1. Linux/WSL2/macOS 上では probe() が即 false を返す = 起動失敗しない。
//   2. Windows 上では WinHvPlatform.dll を SymbolLookup.libraryLookup で
//      解決できれば available=true。Hyper-V 機能無効・VBS 制約・dll 欠落
//      等は全部 false に倒す (細かい原因区別は step 3 で)。
//   3. probe は 1 度しか走らない (static initializer 相当)。重い操作なので
//      cache 必須 (起動時の Emulin.main、EMULIN_BACKEND=auto の effective()
//      経路、CpuBackend.NATIVE.createCpu64 で複数回呼ばれる)。
//   4. MethodHandle は **lazy 初期化**。probe() が false なら呼ばれないので、
//      Linux 上で SymbolLookup を再試行することは無い。
//   5. FFM の restricted-method 警告 (--enable-native-access 未指定時) は
//      try/catch で吸収。Windows 実機 PoC では launcher に
//      `--enable-native-access=ALL-UNNAMED` を追加する想定。
//
// API リファレンス: Microsoft Docs > Windows Hypervisor Platform
//   https://learn.microsoft.com/en-us/virtualization/api/hypervisor-platform/
//   主要 entry point:
//     HRESULT WHvCreatePartition(WHV_PARTITION_HANDLE *Partition);
//     HRESULT WHvSetupPartition(WHV_PARTITION_HANDLE Partition);
//     HRESULT WHvCreateVirtualProcessor(
//         WHV_PARTITION_HANDLE Partition, UINT32 VpIndex, UINT32 Flags);
//     HRESULT WHvRunVirtualProcessor(
//         WHV_PARTITION_HANDLE Partition, UINT32 VpIndex,
//         void *ExitContext, UINT32 ExitContextSize);
//     HRESULT WHvMapGpaRange(
//         WHV_PARTITION_HANDLE Partition, void *SourceAddress,
//         WHV_GUEST_PHYSICAL_ADDRESS GuestAddress, UINT64 SizeInBytes,
//         WHV_MAP_GPA_RANGE_FLAGS Flags);
//     HRESULT WHvSetVirtualProcessorRegisters(
//         WHV_PARTITION_HANDLE Partition, UINT32 VpIndex,
//         const WHV_REGISTER_NAME *RegisterNames, UINT32 RegisterCount,
//         const WHV_REGISTER_VALUE *RegisterValues);
//     HRESULT WHvGetVirtualProcessorRegisters(
//         WHV_PARTITION_HANDLE Partition, UINT32 VpIndex,
//         const WHV_REGISTER_NAME *RegisterNames, UINT32 RegisterCount,
//         WHV_REGISTER_VALUE *RegisterValues);
//
// すべて HRESULT (= int) 戻り値。S_OK = 0、それ以外は失敗。HRESULT を
// long で保持して 0 == 成功と判定する典型パターン。
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

public final class WhpBindings {

  // probe 結果 cache。volatile + 同期で 1 度だけ走らせる。
  private static volatile Boolean probedAvailable;     // null = 未 probe、true/false = 結果

  // probe が成功したときに保持する Linker / SymbolLookup。lazy 初期化用。
  private static SymbolLookup whpLookup;
  private static Linker        linker;

  // 主要 MethodHandle (Windows 上で probe 成功時に lazy 初期化)。
  private static MethodHandle  mhCreatePartition;
  private static MethodHandle  mhSetupPartition;
  private static MethodHandle  mhCreateVirtualProcessor;
  private static MethodHandle  mhRunVirtualProcessor;
  private static MethodHandle  mhMapGpaRange;
  private static MethodHandle  mhSetVirtualProcessorRegisters;
  private static MethodHandle  mhGetVirtualProcessorRegisters;

  private WhpBindings() {}  // static-only

  /**
   * WinHvPlatform.dll を SymbolLookup で読み込めるか確認する。
   *
   *   - Windows + Hyper-V 機能有効 + dll 存在 → true
   *   - Linux / WSL2 / macOS / Hyper-V 無効 / dll 欠落 → false
   *
   * 結果は cache されるので 2 回目以降は実 dlopen を再実行しない。
   * MethodHandle の lazy 初期化はここでは行わず、最初の actual API call で
   * 行う (step 3 で実装)。
   *
   * 例外は全部 catch して false を返す。原因の区別は step 3 で TRACE 等を
   * 入れたときに追加する。
   */
  public static synchronized boolean probe() {
    if( probedAvailable != null ) return probedAvailable;
    try {
      // Arena.global() に依存させる: probe 結果の SymbolLookup を application
      //   全体で 1 つだけ持つ (process が exit するまで dll を unload しない)。
      whpLookup = SymbolLookup.libraryLookup( "WinHvPlatform", Arena.global() );
      // 軽い sanity check: 主要 entry point が見つかるか。
      if( whpLookup.find( "WHvCreatePartition" ).isEmpty() ) {
        probedAvailable = false;
        return false;
      }
      linker = Linker.nativeLinker();
      probedAvailable = true;
      return true;
    }
    catch( Throwable t ) {
      // UnsatisfiedLinkError (Linux で WinHvPlatform.dll 無し),
      // IllegalCallerException (--enable-native-access 無し),
      // SecurityException 等を全部 false に倒す。
      probedAvailable = false;
      return false;
    }
  }

  /**
   * probe() が true を返した状況用の 1 行説明 (banner 出力)。Linux/WSL では
   * `"WHP not available"`、Windows で probe 成功なら `"WHP detected"`。
   */
  public static String describeAvailability() {
    if( probedAvailable == null ) probe();
    return probedAvailable ? "WHP detected" : "WHP not available";
  }

  // -------- MethodHandle accessor (lazy 初期化、step 3 で初使用) --------
  //
  //  以下の getter は Phase 0 step 3 (Windows 実機 PoC) で initial 実装の時に
  //  cache されない方が望ましければ unbind して再 link すれば良い。今は全て
  //  lazy で、最初の getter 呼び出しで Linker.downcallHandle を組み立てる。
  //  Linux 上では probe() が false なので getter まで到達することはない。

  /** WHvCreatePartition: HRESULT (*)(WHV_PARTITION_HANDLE *Partition) */
  public static MethodHandle createPartition() {
    if( !probe() ) throw new IllegalStateException( "WHP not available; call WhpBindings.probe() first" );
    if( mhCreatePartition == null ) {
      mhCreatePartition = downcall( "WHvCreatePartition",
          FunctionDescriptor.of( ValueLayout.JAVA_INT, ValueLayout.ADDRESS ) );
    }
    return mhCreatePartition;
  }

  /** WHvSetupPartition: HRESULT (*)(WHV_PARTITION_HANDLE Partition) */
  public static MethodHandle setupPartition() {
    if( !probe() ) throw new IllegalStateException( "WHP not available" );
    if( mhSetupPartition == null ) {
      mhSetupPartition = downcall( "WHvSetupPartition",
          FunctionDescriptor.of( ValueLayout.JAVA_INT, ValueLayout.ADDRESS ) );
    }
    return mhSetupPartition;
  }

  /** WHvCreateVirtualProcessor: HRESULT (*)(WHV_PARTITION_HANDLE, UINT32 VpIndex, UINT32 Flags) */
  public static MethodHandle createVirtualProcessor() {
    if( !probe() ) throw new IllegalStateException( "WHP not available" );
    if( mhCreateVirtualProcessor == null ) {
      mhCreateVirtualProcessor = downcall( "WHvCreateVirtualProcessor",
          FunctionDescriptor.of( ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT ) );
    }
    return mhCreateVirtualProcessor;
  }

  /** WHvRunVirtualProcessor: HRESULT (*)(WHV_PARTITION_HANDLE, UINT32, void *ExitContext, UINT32 ExitContextSize) */
  public static MethodHandle runVirtualProcessor() {
    if( !probe() ) throw new IllegalStateException( "WHP not available" );
    if( mhRunVirtualProcessor == null ) {
      mhRunVirtualProcessor = downcall( "WHvRunVirtualProcessor",
          FunctionDescriptor.of( ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.JAVA_INT ) );
    }
    return mhRunVirtualProcessor;
  }

  /** WHvMapGpaRange: HRESULT (*)(WHV_PARTITION_HANDLE, void *Source, UINT64 GpaAddr, UINT64 Size, UINT32 Flags) */
  public static MethodHandle mapGpaRange() {
    if( !probe() ) throw new IllegalStateException( "WHP not available" );
    if( mhMapGpaRange == null ) {
      mhMapGpaRange = downcall( "WHvMapGpaRange",
          FunctionDescriptor.of( ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.ADDRESS,
              ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT ) );
    }
    return mhMapGpaRange;
  }

  /** WHvSetVirtualProcessorRegisters: HRESULT (*)(WHV_PARTITION_HANDLE, UINT32, RegName*, UINT32, RegValue*) */
  public static MethodHandle setVirtualProcessorRegisters() {
    if( !probe() ) throw new IllegalStateException( "WHP not available" );
    if( mhSetVirtualProcessorRegisters == null ) {
      mhSetVirtualProcessorRegisters = downcall( "WHvSetVirtualProcessorRegisters",
          FunctionDescriptor.of( ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS ) );
    }
    return mhSetVirtualProcessorRegisters;
  }

  /** WHvGetVirtualProcessorRegisters: HRESULT (*)(WHV_PARTITION_HANDLE, UINT32, RegName*, UINT32, RegValue*) */
  public static MethodHandle getVirtualProcessorRegisters() {
    if( !probe() ) throw new IllegalStateException( "WHP not available" );
    if( mhGetVirtualProcessorRegisters == null ) {
      mhGetVirtualProcessorRegisters = downcall( "WHvGetVirtualProcessorRegisters",
          FunctionDescriptor.of( ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS ) );
    }
    return mhGetVirtualProcessorRegisters;
  }

  /** WHvSetPartitionProperty: HRESULT (*)(WHV_PARTITION_HANDLE, WHV_PARTITION_PROPERTY_CODE, const void* PropertyBuffer, UINT32 PropertyBufferSizeInBytes) */
  public static MethodHandle setPartitionProperty() {
    if( !probe() ) throw new IllegalStateException( "WHP not available" );
    if( mhSetPartitionProperty == null ) {
      mhSetPartitionProperty = downcall( "WHvSetPartitionProperty",
          FunctionDescriptor.of( ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT ) );
    }
    return mhSetPartitionProperty;
  }
  /** WHvDeleteVirtualProcessor: HRESULT (*)(WHV_PARTITION_HANDLE, UINT32 VpIndex) */
  public static MethodHandle deleteVirtualProcessor() {
    if( !probe() ) throw new IllegalStateException( "WHP not available" );
    if( mhDeleteVirtualProcessor == null ) {
      mhDeleteVirtualProcessor = downcall( "WHvDeleteVirtualProcessor",
          FunctionDescriptor.of( ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT ) );
    }
    return mhDeleteVirtualProcessor;
  }

  /** WHvUnmapGpaRange: HRESULT (*)(WHV_PARTITION_HANDLE, UINT64 GpaAddr, UINT64 Size)。
   *  GPA slot の解放 (fork-on-WHP step 3e-whp-7、process 終了時に自分の slot を partition から外す)。 */
  public static MethodHandle unmapGpaRange() {
    if( !probe() ) throw new IllegalStateException( "WHP not available" );
    if( mhUnmapGpaRange == null ) {
      mhUnmapGpaRange = downcall( "WHvUnmapGpaRange",
          FunctionDescriptor.of( ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG ) );
    }
    return mhUnmapGpaRange;
  }

  /** WHvCancelRunVirtualProcessor: HRESULT (*)(WHV_PARTITION_HANDLE, UINT32 VpIndex, UINT32 Flags)。
   *  別 thread から走行中 (または次回) の WHvRunVirtualProcessor を Canceled exit にする
   *  (async signal kick、step 3e-whp-6。KVM の tgkill → KVM_RUN EINTR 相当)。 */
  public static MethodHandle cancelRunVirtualProcessor() {
    if( !probe() ) throw new IllegalStateException( "WHP not available" );
    if( mhCancelRunVirtualProcessor == null ) {
      mhCancelRunVirtualProcessor = downcall( "WHvCancelRunVirtualProcessor",
          FunctionDescriptor.of( ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT ) );
    }
    return mhCancelRunVirtualProcessor;
  }
  /** WHvDeletePartition: HRESULT (*)(WHV_PARTITION_HANDLE) */
  public static MethodHandle deletePartition() {
    if( !probe() ) throw new IllegalStateException( "WHP not available" );
    if( mhDeletePartition == null ) {
      mhDeletePartition = downcall( "WHvDeletePartition",
          FunctionDescriptor.of( ValueLayout.JAVA_INT, ValueLayout.ADDRESS ) );
    }
    return mhDeletePartition;
  }
  private static MethodHandle mhSetPartitionProperty, mhDeleteVirtualProcessor, mhDeletePartition,
                              mhCancelRunVirtualProcessor, mhUnmapGpaRange;

  // -------- kernel32: VirtualAlloc/VirtualFree (WHvMapGpaRange の source は page-align された commit 済
  //   memory が要る。JVM の Arena.allocate は heap allocator 由来で WHP に受理されない可能性があるので
  //   VirtualAlloc(MEM_COMMIT|MEM_RESERVE, PAGE_READWRITE) で確保する) --------
  private static SymbolLookup k32Lookup;
  private static MethodHandle  mhVirtualAlloc, mhVirtualFree;
  public static final long MEM_COMMIT  = 0x00001000L;
  public static final long MEM_RESERVE = 0x00002000L;
  public static final long MEM_RELEASE = 0x00008000L;
  public static final long PAGE_READWRITE = 0x04L;
  /** LPVOID VirtualAlloc(LPVOID lpAddress, SIZE_T dwSize, DWORD flAllocationType, DWORD flProtect) */
  public static MethodHandle virtualAlloc() {
    if( !probe() ) throw new IllegalStateException( "WHP not available" );
    if( mhVirtualAlloc == null ) {
      if( k32Lookup == null ) k32Lookup = SymbolLookup.libraryLookup( "kernel32", Arena.global() );
      mhVirtualAlloc = linker.downcallHandle(
          k32Lookup.find( "VirtualAlloc" ).orElseThrow( () -> new UnsatisfiedLinkError( "VirtualAlloc" ) ),
          FunctionDescriptor.of( ValueLayout.ADDRESS,
              ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT ) );
    }
    return mhVirtualAlloc;
  }
  /** BOOL VirtualFree(LPVOID lpAddress, SIZE_T dwSize, DWORD dwFreeType) */
  public static MethodHandle virtualFree() {
    if( !probe() ) throw new IllegalStateException( "WHP not available" );
    if( mhVirtualFree == null ) {
      if( k32Lookup == null ) k32Lookup = SymbolLookup.libraryLookup( "kernel32", Arena.global() );
      mhVirtualFree = linker.downcallHandle(
          k32Lookup.find( "VirtualFree" ).orElseThrow( () -> new UnsatisfiedLinkError( "VirtualFree" ) ),
          FunctionDescriptor.of( ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT ) );
    }
    return mhVirtualFree;
  }

  // -------- VirtualAlloc2 (kernelbase、Win10 1803+): host source を低位アドレス帯に確保するため。
  //   ★ WHvMapGpaRange は host source address の magnitude に敏感で、高位アドレス (>~45GB 帯) だと
  //   2 つ目の partition の map が HRESULT 0xC0370008 で失敗する (fork、Simpleator issue #2)。
  //   VirtualAlloc2 + MEM_ADDRESS_REQUIREMENTS(HighestEndingAddress) で guest RAM を低位に確保して回避する。
  private static SymbolLookup  kbLookup;
  private static MethodHandle  mhVirtualAlloc2;
  /** MEM_EXTENDED_PARAMETER.Type = MemExtendedParameterAddressRequirements。 */
  public static final long MemExtendedParameterAddressRequirements = 1L;
  /**
   * PVOID VirtualAlloc2(HANDLE Process, PVOID BaseAddress, SIZE_T Size, ULONG AllocationType,
   *                     ULONG PageProtection, MEM_EXTENDED_PARAMETER* ExtendedParameters, ULONG ParameterCount)
   */
  public static MethodHandle virtualAlloc2() {
    if( !probe() ) throw new IllegalStateException( "WHP not available" );
    if( mhVirtualAlloc2 == null ) {
      if( kbLookup == null ) kbLookup = SymbolLookup.libraryLookup( "kernelbase", Arena.global() );
      mhVirtualAlloc2 = linker.downcallHandle(
          kbLookup.find( "VirtualAlloc2" ).orElseThrow( () -> new UnsatisfiedLinkError( "VirtualAlloc2" ) ),
          FunctionDescriptor.of( ValueLayout.ADDRESS,
              ValueLayout.ADDRESS,    // HANDLE Process (NULL=current)
              ValueLayout.ADDRESS,    // PVOID  BaseAddress (NULL)
              ValueLayout.JAVA_LONG,  // SIZE_T Size
              ValueLayout.JAVA_INT,   // ULONG  AllocationType
              ValueLayout.JAVA_INT,   // ULONG  PageProtection
              ValueLayout.ADDRESS,    // MEM_EXTENDED_PARAMETER* ExtendedParameters
              ValueLayout.JAVA_INT    // ULONG  ParameterCount
          ) );
    }
    return mhVirtualAlloc2;
  }

  // ============================================================================
  //  WHP ABI 定数 (winhvplatformdefs.h、Microsoft Docs / libwhp で確認)
  // ----------------------------------------------------------------------------
  //  ★ WSL2 では実行できない (Windows + Hyper-V 必須) ので、値は header / 公式 doc を
  //    写経している。実機検証は WhpSmoke64 を Windows で走らせて行う。
  // ============================================================================

  // WHV_REGISTER_NAME (UINT32)
  public static final int WHvX64RegisterRax = 0x00000000, WHvX64RegisterRcx = 0x00000001;
  public static final int WHvX64RegisterRdx = 0x00000002, WHvX64RegisterRbx = 0x00000003;
  public static final int WHvX64RegisterRsp = 0x00000004, WHvX64RegisterRbp = 0x00000005;
  public static final int WHvX64RegisterRsi = 0x00000006, WHvX64RegisterRdi = 0x00000007;
  public static final int WHvX64RegisterR8  = 0x00000008, WHvX64RegisterR9  = 0x00000009;
  public static final int WHvX64RegisterR10 = 0x0000000A, WHvX64RegisterR11 = 0x0000000B;
  public static final int WHvX64RegisterR12 = 0x0000000C, WHvX64RegisterR13 = 0x0000000D;
  public static final int WHvX64RegisterR14 = 0x0000000E, WHvX64RegisterR15 = 0x0000000F;
  public static final int WHvX64RegisterRip = 0x00000010, WHvX64RegisterRflags = 0x00000011;
  // segment registers
  public static final int WHvX64RegisterEs = 0x00000012, WHvX64RegisterCs = 0x00000013;
  public static final int WHvX64RegisterSs = 0x00000014, WHvX64RegisterDs = 0x00000015;
  public static final int WHvX64RegisterFs = 0x00000016, WHvX64RegisterGs = 0x00000017;
  public static final int WHvX64RegisterLdtr = 0x00000018, WHvX64RegisterTr = 0x00000019;
  public static final int WHvX64RegisterIdtr = 0x0000001A, WHvX64RegisterGdtr = 0x0000001B;
  // control registers
  public static final int WHvX64RegisterCr0 = 0x0000001C, WHvX64RegisterCr2 = 0x0000001D;
  public static final int WHvX64RegisterCr3 = 0x0000001E, WHvX64RegisterCr4 = 0x0000001F;
  public static final int WHvX64RegisterCr8 = 0x00000020;
  // XMM (FPU/SSE)
  public static final int WHvX64RegisterXmm0 = 0x00001000;   // Xmm0..Xmm15 = 0x1000..0x100F
  // x87/MMX/MXCSR (issue #304: sys_signal_x87_64 の x87 state 保存に必要。旧実装は XMM のみで
  //   signal handler 越しに x87 (cw/st0) が WHP で壊れていた)。
  public static final int WHvX64RegisterFpMmx0 = 0x00001010;           // FpMmx0..7 = 0x1010..0x1017 (st0-7 / mm0-7)
  public static final int WHvX64RegisterFpControlStatus = 0x00001018;  // FCW/FSW/FTW/last op/ip/dp
  public static final int WHvX64RegisterXmmControlStatus = 0x00001019; // MXCSR
  // MSRs
  public static final int WHvX64RegisterEfer = 0x00002001, WHvX64RegisterKernelGsBase = 0x00002002;
  public static final int WHvX64RegisterStar = 0x00002008, WHvX64RegisterLstar = 0x00002009;
  public static final int WHvX64RegisterCstar = 0x0000200A, WHvX64RegisterSfmask = 0x0000200B;

  // WHV_RUN_VP_EXIT_REASON (UINT32)
  public static final int WHvRunVpExitReasonNone            = 0x00000000;
  public static final int WHvRunVpExitReasonMemoryAccess    = 0x00000001;
  public static final int WHvRunVpExitReasonX64IoPortAccess = 0x00000002;
  public static final int WHvRunVpExitReasonX64Halt         = 0x00000008;
  public static final int WHvRunVpExitReasonX64MsrAccess    = 0x00001000;
  public static final int WHvRunVpExitReasonX64Cpuid        = 0x00001001;
  public static final int WHvRunVpExitReasonCanceled        = 0x00002001;

  // WHV_MAP_GPA_RANGE_FLAGS (UINT32)
  public static final int WHvMapGpaRangeFlagNone    = 0x00000000;
  public static final int WHvMapGpaRangeFlagRead    = 0x00000001;
  public static final int WHvMapGpaRangeFlagWrite   = 0x00000002;
  public static final int WHvMapGpaRangeFlagExecute = 0x00000004;

  // WHV_PARTITION_PROPERTY_CODE (UINT32)
  public static final int WHvPartitionPropertyCodeProcessorCount = 0x00001FFF;

  // 構造体サイズ / offset
  public static final int WHV_REGISTER_VALUE_SIZE     = 16;   // union (Reg128/Reg64/segment/table…)
  public static final int WHV_RUN_VP_EXIT_CONTEXT_SIZE = 144; // ★ WHvRunVirtualProcessor が size 検証する
  public static final int WHV_PARTITION_PROPERTY_SIZE = 32;
  public static final int WHV_EXIT_OFF_REASON         = 0;    // ExitReason は exit context の先頭
  // WHV_X64_SEGMENT_REGISTER (16 byte): Base(u64)@0, Limit(u32)@8, Selector(u16)@12, Attributes(u16)@14
  public static final int WHV_SEG_OFF_BASE       = 0;
  public static final int WHV_SEG_OFF_LIMIT      = 8;
  public static final int WHV_SEG_OFF_SELECTOR   = 12;
  public static final int WHV_SEG_OFF_ATTRIBUTES = 14;
  // Attributes bitfield: SegmentType[0:3], NonSystem(S)[4], DPL[5:6], Present[7], (Reserved[8:11]),
  //   Available(AVL)[12], Long(L)[13], Default(D/B)[14], Granularity(G)[15]。
  //   64-bit code = type 0xB,S=1,DPL=0,P=1,L=1,G=1 → 0xA09B、data = type 0x3,S=1,DPL=0,P=1,D=1,G=1 → 0xC093。
  public static final int WHV_SEG_ATTR_CODE64 = 0xA09B;
  public static final int WHV_SEG_ATTR_DATA   = 0xC093;

  // -------- 内部 helper --------

  private static MethodHandle downcall( String symbol, FunctionDescriptor descriptor ) {
    MemorySegment addr = whpLookup.find( symbol ).orElseThrow(
        () -> new UnsatisfiedLinkError( "WHP symbol not found: " + symbol ) );
    return linker.downcallHandle( addr, descriptor );
  }
}
