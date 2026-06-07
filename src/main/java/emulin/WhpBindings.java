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

  // -------- 内部 helper --------

  private static MethodHandle downcall( String symbol, FunctionDescriptor descriptor ) {
    MemorySegment addr = whpLookup.find( symbol ).orElseThrow(
        () -> new UnsatisfiedLinkError( "WHP symbol not found: " + symbol ) );
    return linker.downcallHandle( addr, descriptor );
  }
}
