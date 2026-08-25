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
    MADD, MSUB, UMADDL, UMSUBL, UMULH, UDIV, SDIV,
    RBIT, REV16, REV32, REV64, CLZ,
    LSL_VARIABLE, LSR_VARIABLE, ASR_VARIABLE, ROR_VARIABLE,
    CSEL, CSINC, CSINV, CSNEG,
    CCMP_IMMEDIATE, CCMP_REGISTER, CCMN_IMMEDIATE, CCMN_REGISTER,
    B, BL, B_COND, CBZ, CBNZ, TBZ, TBNZ, BR, BLR, RET,
    STR, LDR, LDR_SIGNED, STP, LDP, LDR_LITERAL, LDR_SIGNED_LITERAL,
    DUP_VECTOR_BYTE, LD1_VECTOR_16B, LD1_VECTOR_D_LANE,
    CMEQ_VECTOR_BYTE, CMEQ_VECTOR_BYTE_ZERO,
    BIT_VECTOR, EOR_VECTOR, BIC_VECTOR_IMMEDIATE_HALFWORD, UMAXP_VECTOR_BYTE,
    ADDP_VECTOR_BYTE, EXT_VECTOR_16B, CMHS_VECTOR_BYTE,
    SHRN_VECTOR_8B, ADDHN_VECTOR_8B, FMOV_GENERAL_FROM_D, FMOV_VECTOR_64,
    MOVE_GENERAL_FROM_VECTOR_LANE,
    STR_VECTOR_32, LDR_VECTOR_32, STR_VECTOR_64, LDR_VECTOR_64,
    STR_VECTOR_128, LDR_VECTOR_128,
    STP_VECTOR_64, LDP_VECTOR_64, STP_VECTOR_128, LDP_VECTOR_128, MOVI_VECTOR,
    MRS_DCZID_EL0, MRS_TPIDR_EL0, MSR_TPIDR_EL0,
    LOAD_EXCLUSIVE, STORE_EXCLUSIVE, LOAD_ACQUIRE, STORE_RELEASE,
    MEMORY_BARRIER, NOP, SVC
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
