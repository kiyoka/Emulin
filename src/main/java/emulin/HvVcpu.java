// ----------------------------------------
//  Hypervisor 非依存の vCPU 抽象 (issue #221 WHP 移植 step 1)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// 1 つの仮想 CPU を long-mode ring-3 + syscall trap で操作する hypervisor 中立 interface。
// Linux KVM (KvmVcpu) と Windows Hypervisor Platform (WhpVcpu、後続 step) が実装し、
// NativeCpuBackend は本 interface 経由で register / sregs / MSR / FPU / run を扱う。
// これにより「KVM の struct byte offset」と「WHP の WHV_REGISTER_NAME ⇄ WHV_REGISTER_VALUE
// (name-value array)」という表現差を実装側に閉じ込める (#221 の WHP 移植の核心 = register
// normalize layer)。
//
// register アクセスは cache モデル:
//   readGprs()  : hardware → cache   (KVM_GET_REGS / WHvGetVirtualProcessorRegisters)
//   getGpr/setGpr : cache を読み書き  (HvReg.* 索引)
//   writeGprs() : cache → hardware    (KVM_SET_REGS / WHvSetVirtualProcessorRegisters)
// syscall hot path は readGprs 1 回 → getGpr で引数収集 → setGpr(RAX/RIP) → writeGprs 1 回。
package emulin;

public interface HvVcpu {
  /** run() の戻り値: guest が HLT で停止 = syscall trap (LSTAR stub の hlt)。 */
  int EXIT_HALT  = 0;
  /** run() の戻り値: HLT 以外の exit (MMIO/IO/triple fault/internal error 等、MVP 未対応)。 */
  int EXIT_OTHER = 1;
  /** run() の戻り値: host signal で KVM_RUN が EINTR 脱出した (issue #221 step 3d-2c-39)。
   *  GC/safepoint signal でも起きるので eval は pending guest signal を確認して async 配信する。 */
  int EXIT_INTR  = 2;

  // ---- GPR cache ----
  /** hardware の GPR を cache へ読み込む (この後 getGpr で参照可)。 */
  void readGprs()  throws Throwable;
  /** cache の GPR を hardware へ書き戻す。 */
  void writeGprs() throws Throwable;
  /** cache から論理 register (HvReg.*) を読む。先に readGprs() しておくこと。 */
  long getGpr( int reg );
  /** cache の論理 register (HvReg.*) を書く。反映には writeGprs() が要る。 */
  void setGpr( int reg, long value );

  // ---- boot 設定 (NativeCpuBackend.setupVcpu) ----
  /** host のサポート CPUID を vCPU に流す (glibc の CPU ISA level check 対策)。 */
  void setCpuidFromHost() throws Throwable;
  /**
   * long-mode ring-3 の segment + control register を設定する。
   *   CS = csSel (code64、L=1)、DS/ES/FS/GS/SS = dataSel (data)、いずれも DPL=dpl。
   *   CR0/CR3/CR4/EFER を与値で設定する。selector / CR bit 等の Linux ABI 値は呼び出し側
   *   (NativeCpuBackend) が単一の真実として渡し、実装は hypervisor 固有の encoding だけ行う。
   */
  void configureLongModeRing3( long csSel, long dataSel, int dpl,
                               long cr0, long cr3, long cr4, long efer ) throws Throwable;
  /** MSR を設定する。msrs[i] = { index, value }。STAR/LSTAR/FMASK/FS_BASE 等。 */
  void setMsrs( long[][] msrs ) throws Throwable;

  /**
   * vCPU を ring-3 (CPL=3) に遷移させる (issue #221 step 3d-2c-39)。CS/SS の selector を
   *   ring-3 (csSel=0x33 / ssSel=0x2b、DPL=3) に設定する。CR0/CR3/CR4/EFER は触らない。
   * async signal の rt_sigreturn で、ring-0 (syscall trap 後) から被中断 ring-3 コードへ全 GPR
   *   (RCX/R11 含む) を保持したまま復帰するために使う (sysretq は RCX/R11 を RIP/RFLAGS で潰すので不可)。
   * 呼び出し前に setGpr で全 register + RIP/RSP/RFLAGS を被中断値に設定し、本メソッド + writeGprs +
   *   run() で ring-3 再開する。
   */
  void exitToRing3() throws Throwable;

  // ---- run ----
  /** vCPU を実行し、EXIT_HALT (syscall trap) か EXIT_OTHER を返す。 */
  int run() throws Throwable;
  /** 直近 run() の hypervisor 固有 raw exit 値 (EXIT_OTHER 時の診断用)。 */
  int lastRawExit();

  /** 診断用 (issue #339): 現 vCPU の CPL (= CS selector の下位2bit)。-1 = 未対応 backend。 */
  default long getCpl() throws Throwable { return -1; }

  // ---- 例外配送 (issue #392 戦略B #PF demand paging) ----
  /**
   * GDTR/IDTR/TR を load し、guest 内の #PF を IDT[14] 経由で PF_STUB(ring-0)へ vector できるようにする。
   *   tr は 64-bit busy TSS (RSP0=kernel stack top、ring3→ring0 の stack 切替用)。
   *   default は未対応 (KVM が override、WHP は 4f で実装)。
   */
  default void configureExceptionTables( long gdtBase, int gdtLimit, long idtBase, int idtLimit,
                                         int trSel, long trBase, int trLimit ) throws Throwable {
    throw new UnsupportedOperationException( "configureExceptionTables 未対応 backend" );
  }
  /** #PF の faulting 仮想アドレス (CR2)。-1 = 未対応 backend。 */
  default long getCr2() throws Throwable { return -1; }

  /**
   * 別 thread から、走行中 (または次回) の run() を中断させる (async signal 配信用、step 3e-whp-6)。
   *   WHP = WHvCancelRunVirtualProcessor (Canceled exit → EXIT_INTR)。
   *   KVM は host signal (tgkill → KVM_RUN EINTR) 経路を NativeCpuBackend.kickGuestTid が使うので
   *   本メソッドは呼ばれない (default no-op のまま)。best-effort: 取りこぼしても signal は pending に
   *   残り syscall 境界配信 (#258) か次の kick で届く。
   */
  default void kick() throws Throwable {}

  // ---- FPU (signal 退避復元、x87/XMM/MXCSR) ----
  /** 現 vCPU の FPU 状態を不透明 snapshot として取得 (KVM_GET_FPU / WHvGetVirtualProcessorXsaveState 相当)。 */
  byte[] getFpu() throws Throwable;
  /** snapshot を vCPU FPU に書き戻す。 */
  void   setFpu( byte[] snap ) throws Throwable;

  // ---- teardown ----
  /** vCPU 資源 (fd / run-state mmap / per-vcpu arena 等) を解放する。VM 資源は触らない。 */
  void close();
}
