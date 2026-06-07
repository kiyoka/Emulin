// ----------------------------------------
//  CPU backend selector (issue #231 / Step 1 of #221 refactor)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// 目的: software emulator (Cpu64 / Cpu) と、将来追加予定の native backend
//   (WHP / KVM / Hypervisor.framework、issue #221) を **並立する第一級モード**
//   として扱える factory 層を提供する。
//
// 設計原則 (#221 §1 / docs/issue221-design-plan.md §1 を参照):
//   1. software emulator は恒久維持。default かつ最終 fallback。
//   2. 正しさの canonical は software。回帰は software backend で常時 PASS。
//   3. native は EMULIN_BACKEND=native で明示選択時のみ。auto は当面 software。
//   4. pure-Java を保持。FFM/JNI は native backend にのみ閉じ込める (本 step では
//      まだ実装しない)。
//   5. behavior change ゼロ。EMULIN_BACKEND 未設定なら従来動作と完全一致。
//
// 本 step (#231) の範囲は **factory + env var + banner だけ**。実 native backend
// 実装、MemoryBackend 抽象化 (#232)、signal/thread 抽象化 (#233) は別 issue。
package emulin;

import java.lang.*;

public enum CpuBackend {
  /** 現状の software emulator (Cpu / Cpu64)。default かつ最終 fallback。 */
  SOFTWARE,

  /** HW 仮想化 backend (WHP/KVM/HVF)。本 step では未実装、起動時に error。 */
  NATIVE,

  /** 仮想化可用時のみ native、不可なら software。本 step では常に software。 */
  AUTO;

  // 起動時 1 回 resolve した結果を cache。Process が fork/exec 経由で毎回
  //   instantiation するときに env var を読み直さないため。
  private static volatile CpuBackend resolved;

  /**
   * EMULIN_BACKEND env var を読んで backend を解決する。
   *   - 未設定 / 空     : SOFTWARE
   *   - "software"     : SOFTWARE
   *   - "native"       : NATIVE  (effective() = NATIVE、現状未実装)
   *   - "auto"         : AUTO    (effective() = SOFTWARE、将来 probe 追加)
   *   - その他         : SOFTWARE に fallback + 警告
   * 初回呼び出しの結果を cache し、以降は同じ値を返す。
   */
  public static CpuBackend resolve() {
    CpuBackend r = resolved;
    if( r != null ) return r;
    String env = System.getenv( "EMULIN_BACKEND" );
    if( env == null || env.isEmpty() ) {
      r = SOFTWARE;
    }
    else {
      switch( env.toLowerCase() ) {
        case "software": r = SOFTWARE; break;
        case "native":   r = NATIVE;   break;
        case "auto":     r = AUTO;     break;
        default:
          System.err.println( "[backend] unknown EMULIN_BACKEND=\"" + env
              + "\" (expected software|native|auto). Defaulting to software." );
          r = SOFTWARE;
      }
    }
    resolved = r;
    return r;
  }

  /**
   * AUTO を実際に使う backend に解決する。
   *
   * issue #221 Phase 0 step 2: AUTO は WhpBindings.probe() で
   *   WinHvPlatform.dll の可用性をチェックし、可用なら NATIVE、
   *   不可なら SOFTWARE を返す。Linux/WSL/macOS では当然 SOFTWARE。
   *   Windows でも Hyper-V 無効・VBS 制約・dll 欠落等で false 可。
   *
   * AUTO 以外 (SOFTWARE / NATIVE) はそのまま自分自身を返す。
   */
  public CpuBackend effective() {
    if( this == AUTO ) {
      return WhpBindings.probe() ? NATIVE : SOFTWARE;
    }
    return this;
  }

  /**
   * 起動時に backend が実装されているか確認する。Emulin.main から boot 前に呼ぶ。
   *
   * issue #221 Phase 0 step 2:
   *   - SOFTWARE → 常に true
   *   - AUTO → effective() の結果が NATIVE なら NATIVE 経路で確認、SOFTWARE
   *     なら true (fallback で動く)
   *   - NATIVE → WhpBindings.probe() が true なら true (Phase 0 step 3+ で
   *     実装される vCPU 経路を使う)、false なら明示 error で exit
   */
  public boolean verifyImplemented() {
    CpuBackend eff = effective();
    if( eff == NATIVE ) {
      // probe() 自体は effective() / createCpu64 経路で既に走るが、ここで
      //   念のため確認 + Phase 0 step 2 では「stub のみで実 vCPU 起動は
      //   未実装」を明示するため、stub 以外の用途で NATIVE が選ばれた場合は
      //   明示的に error。
      //   Phase 0 step 3+ で実 partition が動くようになったらこの error を
      //   解除し、stub class の eval/fetch/spawnVCpu 等の throws を実装に
      //   置換する。
      System.err.println( "[backend] native backend probe: " + WhpBindings.describeAvailability() );
      System.err.println( "[backend] NativeCpuBackend stub is in place but vCPU loop (eval/fetch/" );
      System.err.println( "[backend]   set_signal_handler/spawnVCpu) is not yet implemented." );
      System.err.println( "[backend] Phase 0 step 3+ で WHvRunVirtualProcessor + syscall trap を実装予定。" );
      System.err.println( "[backend] 当面は EMULIN_BACKEND=software (default) または unset で起動して下さい。" );
      return false;
    }
    return true;
  }

  /**
   * 起動 banner 用の表示文字列。
   *   - "software"               → SOFTWARE 明示
   *   - "software (auto, WHP not available)"  → AUTO で probe 失敗
   *   - "native (auto, WHP detected)"         → AUTO で probe 成功
   *   - "native"                 → NATIVE 明示 (verifyImplemented で結果別途表示)
   */
  public String displayName() {
    if( this == AUTO ) {
      CpuBackend eff = effective();
      return eff.name().toLowerCase() + " (auto, " + WhpBindings.describeAvailability() + ")";
    }
    return name().toLowerCase();
  }

  /**
   * x86-64 CPU instance を生成する。Process.run の `new Cpu64(sysinfo, this)`
   * 直書きの代替。
   *
   * issue #221 Phase 0 step 2: effective() == NATIVE のとき、probe 成功なら
   *   NativeCpuBackend stub を返す (eval/fetch 等は未実装で throw する)。これは
   *   verifyImplemented() を bypass して NATIVE を強制した debug 経路、または
   *   AUTO で probe 成功した経路から到達する想定。verifyImplemented() で false
   *   が返れば Emulin.main が exit 2 する。
   */
  public AbstractCpu createCpu64( Sysinfo sysinfo, Process process ) {
    if( effective() == NATIVE ) {
      if( !WhpBindings.probe() ) {
        throw new UnsupportedOperationException(
            "native backend selected but WHP probe failed (issue #221)" );
      }
      return new NativeCpuBackend( sysinfo, process );
    }
    return new Cpu64( sysinfo, process );
  }

  /**
   * i386 CPU instance を生成する。Process.run の `new Cpu(sysinfo, this)`
   * 直書きの代替。
   *
   * 注意: 32-bit guest を native backend で動かす計画は無い (Phase 0+ は
   *   x86-64 限定)。AUTO で probe 成功した場合に i386 ELF が走ることは
   *   通常無いが、NativeCpuBackend は AbstractCpu の subclass として
   *   construct はできる。
   */
  public AbstractCpu createCpu( Sysinfo sysinfo, Process process ) {
    if( effective() == NATIVE ) {
      if( !WhpBindings.probe() ) {
        throw new UnsupportedOperationException(
            "native backend selected but WHP probe failed (issue #221)" );
      }
      return new NativeCpuBackend( sysinfo, process );
    }
    return new Cpu( sysinfo, process );
  }
}
