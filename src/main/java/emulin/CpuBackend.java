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
//   3. default (EMULIN_BACKEND 未設定) は常に software。native は =native で明示選択、
//      =auto は HW 仮想化が使えれば native (step 3d-2 以降、KVM 可用時) / 不可なら software。
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
  /**
   * native backend (HW 仮想化) が利用可能か。
   *
   * issue #221 Phase 0 step 3d-2: 現在 NativeCpuBackend は **Linux KVM** 経路を
   *   実装済 (`/dev/kvm`)。Windows WHP backend は step 3f で実装予定なので、当面は
   *   KvmBindings.probe() を native の可用性とする (WSL2 nested KVM / bare-metal
   *   Linux で true)。WhpBindings は probe だけ既存 (実 backend は未実装)。
   */
  // ★ issue #221 WHP 移植 Stage A: native backend は KVM (Linux) と WHP (Windows) の両方で可用。
  //   HvVm.create() の dispatch と同じ判定 (KVM 優先、無ければ WHP)。Linux では KvmBindings.probe()=true
  //   なので従来と完全に同一挙動 (KVM oracle 無影響)、Windows では WHP を native として選べるようになる。
  static boolean nativeAvailable() {
    return KvmBindings.probe() || WhpBindings.probe();
  }
  static String nativeDescribe() {
    if( KvmBindings.probe() ) return KvmBindings.describeAvailability();   // "KVM detected (/dev/kvm OK)"
    if( WhpBindings.probe() ) return WhpBindings.describeAvailability();   // "WHP detected"
    return KvmBindings.describeAvailability();   // どちらも無 → "KVM not available" 文言を流用
  }

  public CpuBackend effective() {
    if( this == AUTO ) {
      return nativeAvailable() ? NATIVE : SOFTWARE;
    }
    return this;
  }

  /**
   * 起動時に backend が実装されているか確認する。Emulin.main から boot 前に呼ぶ。
   *
   * issue #221 Phase 0 step 3d-2: NATIVE backend (KVM eval loop) が実装された。
   *   - SOFTWARE → 常に true
   *   - AUTO → effective() が NATIVE (= KVM 可用) なら true、不可なら SOFTWARE で true
   *   - NATIVE → KvmBindings.probe() が true なら true (KVM eval を使う)、
   *     false なら明示 error で exit (Windows で KVM 無し等)
   */
  public boolean verifyImplemented() {
    if( effective() == NATIVE ) {
      if( !nativeAvailable() ) {
        System.err.println( "[backend] native backend selected but no hypervisor available: "
            + nativeDescribe() );
        System.err.println( "[backend] Linux: requires KVM (/dev/kvm, join the kvm group + enable nested virt)." );
        System.err.println( "[backend] Windows: enable Hyper-V \"Windows Hypervisor Platform\"." );
        System.err.println( "[backend] For now, start with EMULIN_BACKEND=software (default)." );
        return false;
      }
    }
    return true;
  }

  /** 起動 banner 用の表示文字列 (例: "native (KVM detected (/dev/kvm OK))")。 */
  public String displayName() {
    if( this == AUTO ) {
      return effective().name().toLowerCase() + " (auto, " + nativeDescribe() + ")";
    }
    if( this == NATIVE ) {
      return "native (" + nativeDescribe() + ")";
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
      if( !nativeAvailable() ) {
        throw new UnsupportedOperationException(
            "native backend selected but no hypervisor available (issue #221)" );
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
    // native backend (HW 仮想化) は x86-64 専用。32-bit i386 ELF は AUTO が native を
    //   選んだ環境 (launcher default=auto、issue #221 C-1) でも常に software で実行する。
    //   launcher は busybox (x86-64) を起動するが、稀に i386 ELF を走らせても安全に動く
    //   ようにする。明示 EMULIN_BACKEND=native でも i386 は software (banner は native 表示
    //   だが実害なし。x86-64 を native、i386 を software という混在 backend は許容)。
    return new Cpu( sysinfo, process );
  }
}
