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
   * AUTO を実際に使う backend (= 当面 SOFTWARE) に解決する。
   * 将来 #221 Phase 0 で仮想化可用性 probe を入れたら、ここに分岐を足す。
   */
  public CpuBackend effective() {
    if( this == AUTO ) return SOFTWARE;
    return this;
  }

  /**
   * 起動時に backend が実装されているか確認する。Emulin.main から boot 前に呼ぶ。
   * NATIVE が選ばれていれば error メッセージを出して false を返す (= 早期 exit)。
   */
  public boolean verifyImplemented() {
    if( effective() == NATIVE ) {
      System.err.println( "[backend] native backend is not yet implemented (see issue #221)." );
      System.err.println( "[backend] use EMULIN_BACKEND=software (default) or unset the variable." );
      return false;
    }
    return true;
  }

  /** 起動 banner 用の表示文字列 (例: "software" / "software (auto)" / "native")。 */
  public String displayName() {
    if( this == AUTO ) return effective().name().toLowerCase() + " (auto)";
    return effective().name().toLowerCase();
  }

  /**
   * x86-64 CPU instance を生成する。Process.run の `new Cpu64(sysinfo, this)`
   * 直書きの代替。effective() == NATIVE なら UnsupportedOperationException。
   */
  public AbstractCpu createCpu64( Sysinfo sysinfo, Process process ) {
    if( effective() == NATIVE ) {
      throw new UnsupportedOperationException(
          "native backend not implemented (issue #221)" );
    }
    return new Cpu64( sysinfo, process );
  }

  /**
   * i386 CPU instance を生成する。Process.run の `new Cpu(sysinfo, this)`
   * 直書きの代替。effective() == NATIVE なら UnsupportedOperationException。
   */
  public AbstractCpu createCpu( Sysinfo sysinfo, Process process ) {
    if( effective() == NATIVE ) {
      throw new UnsupportedOperationException(
          "native backend not implemented (issue #221)" );
    }
    return new Cpu( sysinfo, process );
  }
}
