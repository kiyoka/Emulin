// ----------------------------------------
//  Allocation-free AArch64 decode result (issue #951 Phase 1)
// ----------------------------------------
package emulin;

final class Aarch64DecodedInsn {
  enum Operation {
    MOVN, MOVZ, MOVK,
    ADR, ADRP,
    ADD_IMMEDIATE, ADDS_IMMEDIATE, SUB_IMMEDIATE, SUBS_IMMEDIATE,
    ADD_SHIFTED_REGISTER, ADDS_SHIFTED_REGISTER,
    SUB_SHIFTED_REGISTER, SUBS_SHIFTED_REGISTER,
    ADD_EXTENDED_REGISTER, ADDS_EXTENDED_REGISTER,
    SUB_EXTENDED_REGISTER, SUBS_EXTENDED_REGISTER,
    AND_IMMEDIATE, ORR_IMMEDIATE, EOR_IMMEDIATE, ANDS_IMMEDIATE,
    AND_SHIFTED_REGISTER, BIC_SHIFTED_REGISTER,
    ORR_SHIFTED_REGISTER, ORN_SHIFTED_REGISTER,
    EOR_SHIFTED_REGISTER, EON_SHIFTED_REGISTER,
    ANDS_SHIFTED_REGISTER, BICS_SHIFTED_REGISTER,
    SBFM, BFM, UBFM, EXTR,
    MADD, MSUB,
    B, BL, B_COND, CBZ, CBNZ, TBZ, TBNZ, BR, BLR, RET,
    STR, LDR, STP, LDP, LDR_LITERAL,
    NOP, SVC
  }

  enum ShiftType { NONE, LSL, LSR, ASR, ROR }
  enum ExtendType { NONE, UXTB, UXTH, UXTW, UXTX, SXTB, SXTH, SXTW, SXTX }
  enum AddressMode { NONE, OFFSET, PRE_INDEX, POST_INDEX, LITERAL }

  Operation operation;
  int raw;
  int dataSize;
  int accessSize;
  int rd;
  int rn;
  int rm;
  int ra;
  int rt2;
  long immediate;
  int immr;
  int imms;
  ShiftType shiftType;
  ExtendType extendType;
  int shiftAmount;
  AddressMode addressMode;
  int condition;
  int bitIndex;
  boolean setsFlags;

  void reset( int instruction ) {
    raw = instruction;
    operation = null;
    dataSize = 0;
    accessSize = 0;
    rd = rn = rm = ra = rt2 = -1;
    immediate = 0;
    immr = imms = -1;
    shiftType = ShiftType.NONE;
    extendType = ExtendType.NONE;
    shiftAmount = 0;
    addressMode = AddressMode.NONE;
    condition = -1;
    bitIndex = -1;
    setsFlags = false;
  }
}
