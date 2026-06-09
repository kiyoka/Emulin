// ----------------------------------------
//  Hypervisor 非依存の論理 GPR インデックス (issue #221 WHP 移植 step 1)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// NativeCpuBackend が register を「論理名」で扱い、KVM (kvm_regs の byte offset) と
// WHP (WHV_REGISTER_NAME enum) の物理表現差を HvVcpu 実装に閉じ込めるための索引。
//
// ★ 順序は kvm_regs struct と一致 (RAX..RBP, R8..R15, RIP, RFLAGS が 8 byte 刻み)。
//   よって KvmVcpu は offset = index * 8 で素朴に変換できる (KvmBindings.KVM_REGS_OFF_*
//   と等価)。WhpVcpu は index → WHV_REGISTER_NAME 配列で変換する。
package emulin;

public final class HvReg {
  private HvReg() {}

  public static final int RAX = 0,  RBX = 1,  RCX = 2,  RDX = 3,
                          RSI = 4,  RDI = 5,  RSP = 6,  RBP = 7,
                          R8  = 8,  R9  = 9,  R10 = 10, R11 = 11,
                          R12 = 12, R13 = 13, R14 = 14, R15 = 15,
                          RIP = 16, RFLAGS = 17;
  /** GPR + RIP + RFLAGS の総数 (signal frame / GPR 一括 copy の長さ)。 */
  public static final int COUNT = 18;
}
