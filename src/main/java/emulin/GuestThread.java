package emulin;

/**
 * GuestThread — guest の pthread worker を表す Java Thread の backend 非依存マーカ。
 *
 * <p>従来 syscall 層は「呼び出し thread が worker か」を {@code instanceof Thread64}
 * (= software backend の worker) で判定していたが、issue #221 の native backend
 * (KVM) は worker を別 vCPU + 別 Java thread ({@link NativeCpuBackend.Worker}) で
 * 走らせるため、両者を共通に扱えるマーカが要る。
 *
 * <p>clone (pthread spawn) の親 CPU 選択 / thread exit 判定 / gettid は本 interface
 * 経由で backend を問わず動く。software 専用の signal mask / Memory fault cpu 等の
 * {@code instanceof Thread64} 判定は Cpu64 固有なので据置 (native はそれらの経路を
 * 通らない)。
 */
public interface GuestThread {
  /** この worker thread が実行している CPU backend。clone の親選択に使う。 */
  AbstractCpu guestCpu();
  /** この worker thread の kernel TID (gettid が返す値)。 */
  int guestTid();
  /** この worker thread の per-thread signal mask (bit i = signum i+1)。pthread_sigmask は
   *  呼び出し thread のみの mask を変える (POSIX)。Thread64/NativeCpuBackend.Worker が実装。 */
  long getSignalMask();
  void setSignalMask( long mask );
}
