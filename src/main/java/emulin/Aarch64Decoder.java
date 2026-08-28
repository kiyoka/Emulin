// ----------------------------------------
//  Fixed-width AArch64 decoder (issue #951)
// ----------------------------------------
package emulin;

final class Aarch64Decoder {
  Aarch64DecodedInsn decode( int instruction, Aarch64DecodedInsn out ) {
    out.reset( instruction );

    // Architectural hints used by compiler padding, branch-target
    // identification, and return-address signing.  The software backend does
    // not advertise BTI or pointer authentication, so these execute as
    // architectural no-ops while retaining an ordinary untagged LR.
    if( instruction == 0xd503201f
        || instruction == 0xd50320ff       // XPACLRI (PAuth not advertised)
        || (instruction & 0xffffff3f) == 0xd503241f
        || instruction == 0xd503251f       // CHKFEAT (FEAT_CHK not advertised)
        || instruction == 0xd503233f       // PACIASP
        || instruction == 0xd50323bf ) {   // AUTIASP
      out.operation = Aarch64DecodedInsn.Operation.NOP;
      return out;
    }

    // Supervisor call: SVC #imm16.
    if( (instruction & 0xffe0001f) == 0xd4000001 ) {
      out.operation = Aarch64DecodedInsn.Operation.SVC;
      out.immediate = (instruction >>> 5) & 0xffffL;
      return out;
    }

    // DMB/DSB/ISB barriers.  Domain/type fields are retained in immediate for
    // diagnostics; the software backend applies a conservative full fence.
    int barrier = instruction & 0xfffff0ff;
    if( barrier == 0xd50330bf || barrier == 0xd503309f
        || barrier == 0xd50330df ) {
      out.operation = Aarch64DecodedInsn.Operation.MEMORY_BARRIER;
      out.immediate = (instruction >>> 8) & 15;
      return out;
    }

    // MRS Xt, DCZID_EL0.  The executor reports DZP=1 because DC ZVA is not
    // implemented or advertised by the software backend.
    if( (instruction & 0xffffffe0) == 0xd53b00e0 ) {
      out.operation = Aarch64DecodedInsn.Operation.MRS_DCZID_EL0;
      out.dataSize = 64;
      out.rd = instruction & 31;
      return out;
    }

    // User TLS base register access used by static libc startup and pthreads.
    int threadPointerAccess = instruction & 0xffffffe0;
    if( threadPointerAccess == 0xd53bd040 || threadPointerAccess == 0xd51bd040 ) {
      boolean read = threadPointerAccess == 0xd53bd040;
      out.operation = read ? Aarch64DecodedInsn.Operation.MRS_TPIDR_EL0
                           : Aarch64DecodedInsn.Operation.MSR_TPIDR_EL0;
      out.dataSize = 64;
      if( read ) out.rd = instruction & 31;
      else out.rn = instruction & 31;
      return out;
    }

    // Unconditional branch immediate: signed imm26 scaled by four.
    if( (instruction & 0x7c000000) == 0x14000000 ) {
      out.operation = (instruction < 0)
          ? Aarch64DecodedInsn.Operation.BL : Aarch64DecodedInsn.Operation.B;
      out.immediate = signExtend( instruction & 0x03ffffffL, 26 ) << 2;
      return out;
    }

    // Conditional branch immediate: signed imm19 scaled by four.
    if( (instruction & 0xff000010) == 0x54000000 ) {
      out.operation = Aarch64DecodedInsn.Operation.B_COND;
      out.immediate = signExtend( (instruction >>> 5) & 0x7ffffL, 19 ) << 2;
      out.condition = instruction & 15;
      return out;
    }

    // Compare and branch on zero/nonzero.
    if( (instruction & 0x7e000000) == 0x34000000 ) {
      out.operation = ((instruction >>> 24) & 1) == 0
          ? Aarch64DecodedInsn.Operation.CBZ : Aarch64DecodedInsn.Operation.CBNZ;
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      out.rd = instruction & 31;
      out.immediate = signExtend( (instruction >>> 5) & 0x7ffffL, 19 ) << 2;
      return out;
    }

    // Test bit and branch on zero/nonzero.
    if( (instruction & 0x7e000000) == 0x36000000 ) {
      out.operation = ((instruction >>> 24) & 1) == 0
          ? Aarch64DecodedInsn.Operation.TBZ : Aarch64DecodedInsn.Operation.TBNZ;
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      out.rd = instruction & 31;
      out.bitIndex = ((instruction >>> 26) & 0x20) | ((instruction >>> 19) & 0x1f);
      out.immediate = signExtend( (instruction >>> 5) & 0x3fffL, 14 ) << 2;
      return out;
    }

    // Unconditional branch register forms.
    int branchRegister = instruction & 0xfffffc1f;
    if( branchRegister == 0xd61f0000 || branchRegister == 0xd63f0000
        || branchRegister == 0xd65f0000 ) {
      out.operation = branchRegister == 0xd61f0000
          ? Aarch64DecodedInsn.Operation.BR
          : branchRegister == 0xd63f0000
              ? Aarch64DecodedInsn.Operation.BLR
              : Aarch64DecodedInsn.Operation.RET;
      out.rn = (instruction >>> 5) & 31;
      out.dataSize = 64;
      return out;
    }

    // Conditional select family: CSEL/CSINC/CSINV/CSNEG.
    int conditionalSelect = instruction & 0x7fe00c00;
    if( conditionalSelect == 0x1a800000 || conditionalSelect == 0x1a800400
        || conditionalSelect == 0x5a800000 || conditionalSelect == 0x5a800400 ) {
      out.operation = switch( conditionalSelect ) {
        case 0x1a800000 -> Aarch64DecodedInsn.Operation.CSEL;
        case 0x1a800400 -> Aarch64DecodedInsn.Operation.CSINC;
        case 0x5a800000 -> Aarch64DecodedInsn.Operation.CSINV;
        default -> Aarch64DecodedInsn.Operation.CSNEG;
      };
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      out.rm = (instruction >>> 16) & 31;
      out.condition = (instruction >>> 12) & 15;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // Conditional compare immediate/register: CCMP and CCMN.
    int conditionalCompare = instruction & 0x7fe00c10;
    if( conditionalCompare == 0x7a400800 || conditionalCompare == 0x7a400000
        || conditionalCompare == 0x3a400800 || conditionalCompare == 0x3a400000 ) {
      boolean compareNegative = (conditionalCompare & 0x40000000) != 0;
      boolean immediateForm = (conditionalCompare & 0x800) != 0;
      out.operation = compareNegative
          ? (immediateForm ? Aarch64DecodedInsn.Operation.CCMP_IMMEDIATE
                           : Aarch64DecodedInsn.Operation.CCMP_REGISTER)
          : (immediateForm ? Aarch64DecodedInsn.Operation.CCMN_IMMEDIATE
                           : Aarch64DecodedInsn.Operation.CCMN_REGISTER);
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      if( immediateForm ) out.immediate = (instruction >>> 16) & 31;
      else out.rm = (instruction >>> 16) & 31;
      out.condition = (instruction >>> 12) & 15;
      out.rn = (instruction >>> 5) & 31;
      out.immr = instruction & 15; // fallback NZCV immediate
      out.setsFlags = true;
      return out;
    }

    // PC-relative address generation.
    int pcRelative = instruction & 0x9f000000;
    if( pcRelative == 0x10000000 || pcRelative == 0x90000000 ) {
      long imm21 = ((long)(instruction >>> 29) & 3L)
          | (((long)(instruction >>> 5) & 0x7ffffL) << 2);
      imm21 = signExtend( imm21, 21 );
      out.operation = pcRelative == 0x10000000
          ? Aarch64DecodedInsn.Operation.ADR : Aarch64DecodedInsn.Operation.ADRP;
      out.rd = instruction & 31;
      out.dataSize = 64;
      out.immediate = pcRelative == 0x10000000 ? imm21 : imm21 << 12;
      return out;
    }

    // Move wide immediate: MOVN/MOVZ/MOVK, W or X form.
    int moveWide = instruction & 0x7f800000;
    if( moveWide == 0x12800000 || moveWide == 0x52800000
        || moveWide == 0x72800000 ) {
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      out.shiftAmount = ((instruction >>> 21) & 3) * 16;
      if( out.dataSize == 32 && out.shiftAmount >= 32 ) return undefined( instruction );
      out.operation = moveWide == 0x12800000
          ? Aarch64DecodedInsn.Operation.MOVN
          : moveWide == 0x52800000
              ? Aarch64DecodedInsn.Operation.MOVZ
              : Aarch64DecodedInsn.Operation.MOVK;
      out.rd = instruction & 31;
      out.immediate = (instruction >>> 5) & 0xffffL;
      out.shiftType = Aarch64DecodedInsn.ShiftType.LSL;
      return out;
    }

    // Add/subtract immediate.
    if( (instruction & 0x1f800000) == 0x11000000 ) {
      boolean subtract = ((instruction >>> 30) & 1) != 0;
      boolean flags = ((instruction >>> 29) & 1) != 0;
      out.operation = addSubOperation( subtract, flags, false, false );
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      out.rd = instruction & 31;
      out.rn = (instruction >>> 5) & 31;
      out.shiftAmount = ((instruction >>> 22) & 1) * 12;
      out.shiftType = Aarch64DecodedInsn.ShiftType.LSL;
      out.immediate = ((instruction >>> 10) & 0xfffL) << out.shiftAmount;
      out.setsFlags = flags;
      return out;
    }

    // Logical immediate. Store the expanded bitmask as immediate while retaining
    // immr/imms for aliases and future disassembly.
    if( (instruction & 0x1f800000) == 0x12000000 ) {
      int opc = (instruction >>> 29) & 3;
      out.operation = switch( opc ) {
        case 0 -> Aarch64DecodedInsn.Operation.AND_IMMEDIATE;
        case 1 -> Aarch64DecodedInsn.Operation.ORR_IMMEDIATE;
        case 2 -> Aarch64DecodedInsn.Operation.EOR_IMMEDIATE;
        default -> Aarch64DecodedInsn.Operation.ANDS_IMMEDIATE;
      };
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      int n = (instruction >>> 22) & 1;
      if( out.dataSize == 32 && n != 0 ) return undefined( instruction );
      out.immr = (instruction >>> 16) & 63;
      out.imms = (instruction >>> 10) & 63;
      out.immediate = decodeLogicalImmediate( n, out.immr, out.imms, out.dataSize,
                                               instruction );
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.setsFlags = opc == 3;
      return out;
    }

    // Bitfield operations: SBFM/BFM/UBFM.
    int bitfield = instruction & 0x7f800000;
    if( bitfield == 0x13000000 || bitfield == 0x33000000
        || bitfield == 0x53000000 ) {
      out.operation = bitfield == 0x13000000
          ? Aarch64DecodedInsn.Operation.SBFM
          : bitfield == 0x33000000
              ? Aarch64DecodedInsn.Operation.BFM
              : Aarch64DecodedInsn.Operation.UBFM;
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      int n = (instruction >>> 22) & 1;
      out.immr = (instruction >>> 16) & 63;
      out.imms = (instruction >>> 10) & 63;
      if( n != (out.dataSize == 64 ? 1 : 0)
          || (out.dataSize == 32 && ((out.immr | out.imms) & 32) != 0) ) {
        return undefined( instruction );
      }
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // Extract register: EXTR.
    if( (instruction & 0x7f800000) == 0x13800000 ) {
      out.operation = Aarch64DecodedInsn.Operation.EXTR;
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      int n = (instruction >>> 22) & 1;
      out.immediate = (instruction >>> 10) & 63;
      if( n != (out.dataSize == 64 ? 1 : 0)
          || (out.dataSize == 32 && out.immediate >= 32) ) return undefined( instruction );
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // Add/subtract extended register. This must precede shifted-register decode.
    if( (instruction & 0x1fe00000) == 0x0b200000 ) {
      boolean subtract = ((instruction >>> 30) & 1) != 0;
      boolean flags = ((instruction >>> 29) & 1) != 0;
      out.operation = addSubOperation( subtract, flags, false, true );
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      out.rm = (instruction >>> 16) & 31;
      out.extendType = Aarch64DecodedInsn.ExtendType.values()[ ((instruction >>> 13) & 7) + 1 ];
      out.shiftAmount = (instruction >>> 10) & 7;
      if( out.shiftAmount > 4 ) return undefined( instruction );
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.setsFlags = flags;
      return out;
    }

    // Add/subtract shifted register.
    if( (instruction & 0x1f200000) == 0x0b000000 ) {
      boolean subtract = ((instruction >>> 30) & 1) != 0;
      boolean flags = ((instruction >>> 29) & 1) != 0;
      out.operation = addSubOperation( subtract, flags, true, false );
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      int shift = (instruction >>> 22) & 3;
      if( shift == 3 ) return undefined( instruction );
      out.shiftType = shiftType( shift );
      out.rm = (instruction >>> 16) & 31;
      out.shiftAmount = (instruction >>> 10) & 63;
      if( out.dataSize == 32 && out.shiftAmount >= 32 ) return undefined( instruction );
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.setsFlags = flags;
      return out;
    }

    // Logical shifted register.
    if( (instruction & 0x1f000000) == 0x0a000000 ) {
      int opc = (instruction >>> 29) & 3;
      boolean invert = ((instruction >>> 21) & 1) != 0;
      out.operation = logicalRegisterOperation( opc, invert );
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      out.shiftType = shiftType( (instruction >>> 22) & 3 );
      out.rm = (instruction >>> 16) & 31;
      out.shiftAmount = (instruction >>> 10) & 63;
      if( out.dataSize == 32 && out.shiftAmount >= 32 ) return undefined( instruction );
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.setsFlags = opc == 3;
      return out;
    }

    // Add/subtract with carry.
    int addSubtractCarry = instruction & 0x7fe0fc00;
    if( addSubtractCarry == 0x1a000000 || addSubtractCarry == 0x3a000000
        || addSubtractCarry == 0x5a000000 || addSubtractCarry == 0x7a000000 ) {
      out.operation = switch( addSubtractCarry ) {
        case 0x1a000000 -> Aarch64DecodedInsn.Operation.ADC;
        case 0x3a000000 -> Aarch64DecodedInsn.Operation.ADCS;
        case 0x5a000000 -> Aarch64DecodedInsn.Operation.SBC;
        default -> Aarch64DecodedInsn.Operation.SBCS;
      };
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.setsFlags = out.operation == Aarch64DecodedInsn.Operation.ADCS
          || out.operation == Aarch64DecodedInsn.Operation.SBCS;
      return out;
    }

    // Multiply-add/subtract (including MUL/MNEG aliases where Ra == 31).
    if( (instruction & 0x7fe00000) == 0x1b000000 ) {
      out.operation = ((instruction >>> 15) & 1) == 0
          ? Aarch64DecodedInsn.Operation.MADD : Aarch64DecodedInsn.Operation.MSUB;
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      out.rm = (instruction >>> 16) & 31;
      out.ra = (instruction >>> 10) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // Unsigned widening multiply-add/subtract (UMULL is UMADDL with XZR).
    int unsignedLongMultiply = instruction & 0xffe08000;
    if( unsignedLongMultiply == 0x9ba00000
        || unsignedLongMultiply == 0x9ba08000 ) {
      out.operation = unsignedLongMultiply == 0x9ba00000
          ? Aarch64DecodedInsn.Operation.UMADDL
          : Aarch64DecodedInsn.Operation.UMSUBL;
      out.dataSize = 64;
      out.rm = (instruction >>> 16) & 31;
      out.ra = (instruction >>> 10) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // Signed widening multiply-add/subtract (SMULL is SMADDL with XZR).
    int signedLongMultiply = instruction & 0xffe08000;
    if( signedLongMultiply == 0x9b200000
        || signedLongMultiply == 0x9b208000 ) {
      out.operation = signedLongMultiply == 0x9b200000
          ? Aarch64DecodedInsn.Operation.SMADDL
          : Aarch64DecodedInsn.Operation.SMSUBL;
      out.dataSize = 64;
      out.rm = (instruction >>> 16) & 31;
      out.ra = (instruction >>> 10) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // Unsigned multiply high: upper 64 bits of the 128-bit product.
    if( (instruction & 0xffe0fc00) == 0x9bc07c00 ) {
      out.operation = Aarch64DecodedInsn.Operation.UMULH;
      out.dataSize = 64;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // Signed multiply high: upper 64 bits of the signed 128-bit product.
    if( (instruction & 0xffe0fc00) == 0x9b407c00 ) {
      out.operation = Aarch64DecodedInsn.Operation.SMULH;
      out.dataSize = 64;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // CRC32 and CRC32C update instructions. The low immediate bit selects the
    // Castagnoli polynomial; accessSize selects B/H/W/X input width.
    int crc = instruction & 0xffe0fc00;
    if( crc == 0x1ac04000 || crc == 0x1ac04400
        || crc == 0x1ac04800 || crc == 0x9ac04c00
        || crc == 0x1ac05000 || crc == 0x1ac05400
        || crc == 0x1ac05800 || crc == 0x9ac05c00 ) {
      out.operation = Aarch64DecodedInsn.Operation.CRC32;
      out.dataSize = 32;
      out.accessSize = 1 << ((instruction >>> 10) & 3);
      out.immediate = (instruction >>> 12) & 1;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // Variable shifts (LSLV/LSRV/ASRV/RORV), W or X form.
    int variableShift = instruction & 0x7fe0fc00;
    if( variableShift == 0x1ac02000 || variableShift == 0x1ac02400
        || variableShift == 0x1ac02800 || variableShift == 0x1ac02c00 ) {
      out.operation = switch( variableShift ) {
        case 0x1ac02000 -> Aarch64DecodedInsn.Operation.LSL_VARIABLE;
        case 0x1ac02400 -> Aarch64DecodedInsn.Operation.LSR_VARIABLE;
        case 0x1ac02800 -> Aarch64DecodedInsn.Operation.ASR_VARIABLE;
        default -> Aarch64DecodedInsn.Operation.ROR_VARIABLE;
      };
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // Integer division, W or X form.
    int division = instruction & 0x7fe0fc00;
    if( division == 0x1ac00800 || division == 0x1ac00c00 ) {
      out.operation = division == 0x1ac00800
          ? Aarch64DecodedInsn.Operation.UDIV : Aarch64DecodedInsn.Operation.SDIV;
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // Byte-reversal family: REV16, REV (32-bit), REV32 (64-bit), and REV X.
    int byteReverse = instruction & 0x7ffffc00;
    if( byteReverse == 0x5ac00000 || byteReverse == 0x5ac00400
        || byteReverse == 0x5ac00800 || byteReverse == 0x5ac00c00 ) {
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      if( byteReverse == 0x5ac00c00 && out.dataSize != 64 ) {
        return undefined( instruction );
      }
      out.operation = switch( byteReverse ) {
        case 0x5ac00000 -> Aarch64DecodedInsn.Operation.RBIT;
        case 0x5ac00400 -> Aarch64DecodedInsn.Operation.REV16;
        case 0x5ac00800 -> Aarch64DecodedInsn.Operation.REV32;
        default -> Aarch64DecodedInsn.Operation.REV64;
      };
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // Count leading zero bits, W or X form.
    if( (instruction & 0x7ffffc00) == 0x5ac01000 ) {
      out.operation = Aarch64DecodedInsn.Operation.CLZ;
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // Count leading sign bits, excluding the sign bit itself.
    if( (instruction & 0x7ffffc00) == 0x5ac01400 ) {
      out.operation = Aarch64DecodedInsn.Operation.CLS;
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // Advanced SIMD forms used by the generic AArch64 libc string routines.
    // MOVI/MVNI Vd.2S/4S, #imm8 {, LSL #0/#8/#16/#24}, plus
    // halfword, byte, and byte-mask forms used by libc/libcrypto.
    int vectorMoveImmediateAnyQ = instruction & 0xbff8fc00;
    if( vectorMoveImmediateAnyQ == 0x0f000400
        || vectorMoveImmediateAnyQ == 0x0f002400
        || vectorMoveImmediateAnyQ == 0x0f004400
        || vectorMoveImmediateAnyQ == 0x0f006400
        || vectorMoveImmediateAnyQ == 0x2f000400
        || vectorMoveImmediateAnyQ == 0x2f002400
        || vectorMoveImmediateAnyQ == 0x2f004400
        || vectorMoveImmediateAnyQ == 0x2f006400
        || vectorMoveImmediateAnyQ == 0x0f008400
        || vectorMoveImmediateAnyQ == 0x0f00a400
        || vectorMoveImmediateAnyQ == 0x2f008400
        || vectorMoveImmediateAnyQ == 0x2f00a400
        || vectorMoveImmediateAnyQ == 0x0f00e400
        || vectorMoveImmediateAnyQ == 0x2f00e400 ) {
      out.operation = Aarch64DecodedInsn.Operation.MOVI_VECTOR;
      out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
      int imm8 = (int)((((instruction >>> 16) & 7L) << 5)
          | ((instruction >>> 5) & 31L));
      if( (vectorMoveImmediateAnyQ & 0x1f008000) == 0x0f000000 ) {
        int shift = ((instruction >>> 13) & 3) * 8;
        long word = (imm8 << shift) & 0xffffffffL;
        if( (instruction & 0x20000000) != 0 ) word = (~word) & 0xffffffffL;
        out.immediate = word | (word << 32);
      } else if( vectorMoveImmediateAnyQ == 0x0f00e400 ) {
        out.immediate = (imm8 & 0xffL) * 0x0101010101010101L;
      } else if( (vectorMoveImmediateAnyQ & 0x1f00c400) == 0x0f008400 ) {
        int shift = (instruction & 0x2000) != 0 ? 8 : 0;
        long halfword = (long)imm8 << shift;
        if( (instruction & 0x20000000) != 0 ) halfword = (~halfword) & 0xffffL;
        out.immediate = halfword | (halfword << 16)
            | (halfword << 32) | (halfword << 48);
      } else if( vectorMoveImmediateAnyQ == 0x2f00e400 ) {
        long mask = 0;
        for( int index = 0; index < 8; index++ ) {
          if( (imm8 & (1 << index)) != 0 ) mask |= 0xffL << (index * 8);
        }
        out.immediate = mask;
      }
      out.rd = instruction & 31;
      return out;
    }

    // DUP Vd.<T>, Wn/Xn. imm5 selects B/H/S/D element width.
    if( (instruction & 0xbf20fc00) == 0x0e000c00 ) {
      int imm5 = (instruction >>> 16) & 31;
      if( imm5 != 0 ) {
        int size = Integer.numberOfTrailingZeros( imm5 );
        if( size <= 3 ) {
          out.operation = Aarch64DecodedInsn.Operation.DUP_VECTOR_GENERAL;
          out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
          out.accessSize = 1 << size;
          out.rn = (instruction >>> 5) & 31;
          out.rd = instruction & 31;
          return out;
        }
      }
    }

    // DUP Vd.<T>, Vn.<Ts>[index], for 64- and 128-bit vector destinations.
    if( (instruction & 0xbf20fc00) == 0x0e000400 ) {
      int imm5 = (instruction >>> 16) & 31;
      if( imm5 != 0 ) {
        int size = Integer.numberOfTrailingZeros( imm5 );
        int elementBytes = 1 << size;
        int index = imm5 >>> (size + 1);
        if( elementBytes <= 8 && index < 16 / elementBytes ) {
          out.operation = Aarch64DecodedInsn.Operation.DUP_VECTOR_LANE;
          out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
          out.accessSize = elementBytes;
          out.bitIndex = index;
          out.rn = (instruction >>> 5) & 31;
          out.rd = instruction & 31;
          return out;
        }
      }
    }

    // DUP Vd.2D, Vn.D[index].
    if( (instruction & 0xffe0fc00) == 0x4e000400 ) {
      int imm5 = (instruction >>> 16) & 31;
      if( imm5 == 8 || imm5 == 24 ) {
        out.operation = Aarch64DecodedInsn.Operation.DUP_VECTOR_D_LANE;
        out.dataSize = 128;
        out.bitIndex = imm5 >>> 4;
        out.rn = (instruction >>> 5) & 31;
        out.rd = instruction & 31;
        return out;
      }
    }

    // USHR Dd, Dn, #shift.
    if( (instruction & 0xff80fc00) == 0x7f000400 ) {
      int encodedShift = (instruction >>> 16) & 0x7f;
      if( encodedShift >= 64 ) {
        out.operation = Aarch64DecodedInsn.Operation.USHR_VECTOR_64;
        out.dataSize = 64;
        out.shiftAmount = 128 - encodedShift;
        out.rn = (instruction >>> 5) & 31;
        out.rd = instruction & 31;
        return out;
      }
    }

    // USHR Vd.2D, Vn.2D, #shift.
    if( (instruction & 0xff80fc00) == 0x6f000400 ) {
      int encodedShift = (instruction >>> 16) & 0x7f;
      if( encodedShift >= 64 ) {
        out.operation = Aarch64DecodedInsn.Operation.USHR_VECTOR_2D;
        out.dataSize = 128;
        out.shiftAmount = 128 - encodedShift;
        out.rn = (instruction >>> 5) & 31;
        out.rd = instruction & 31;
        return out;
      }
    }

    // USHR Vd.<T>, Vn.<T>, #shift for byte, halfword, and word lanes.
    if( (instruction & 0xbf80fc00) == 0x2f000400 ) {
      int encodedShift = (instruction >>> 16) & 0x7f;
      if( encodedShift != 0 ) {
        int elementBits = Integer.highestOneBit( encodedShift );
        if( elementBits <= 32 ) {
          out.operation = Aarch64DecodedInsn.Operation.USHR_VECTOR_ELEMENTS;
          out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
          out.accessSize = elementBits / 8;
          out.shiftAmount = elementBits * 2 - encodedShift;
          out.rn = (instruction >>> 5) & 31;
          out.rd = instruction & 31;
          return out;
        }
      }
    }

    // SHL Vd.2D, Vn.2D, #shift.
    if( (instruction & 0xff80fc00) == 0x4f005400 ) {
      int encodedShift = (instruction >>> 16) & 0x7f;
      if( encodedShift >= 64 ) {
        out.operation = Aarch64DecodedInsn.Operation.SHL_VECTOR_2D;
        out.dataSize = 128;
        out.shiftAmount = encodedShift - 64;
        out.rn = (instruction >>> 5) & 31;
        out.rd = instruction & 31;
        return out;
      }
    }

    // SHL Vd.<T>, Vn.<T>, #shift for byte, halfword, and word lanes.
    if( (instruction & 0xbf80fc00) == 0x0f005400 ) {
      int encodedShift = (instruction >>> 16) & 0x7f;
      if( encodedShift != 0 ) {
        int elementBits = Integer.highestOneBit( encodedShift );
        if( elementBits <= 32 ) {
          out.operation = Aarch64DecodedInsn.Operation.SHL_VECTOR_ELEMENTS;
          out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
          out.accessSize = elementBits / 8;
          out.shiftAmount = encodedShift - elementBits;
          out.rn = (instruction >>> 5) & 31;
          out.rd = instruction & 31;
          return out;
        }
      }
    }

    // USHL Vd.2D, Vn.2D, Vm.2D.
    if( (instruction & 0xffe0fc00) == 0x6ee04400 ) {
      out.operation = Aarch64DecodedInsn.Operation.USHL_VECTOR_2D;
      out.dataSize = 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // USHL Vd.<T>, Vn.<T>, Vm.<T> for byte, halfword, and word lanes.
    if( (instruction & 0xbf20fc00) == 0x2e204400 ) {
      int size = (instruction >>> 22) & 3;
      if( size == 3 ) return undefined( instruction );
      out.operation = Aarch64DecodedInsn.Operation.USHL_VECTOR_ELEMENTS;
      out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
      out.accessSize = 1 << size;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // SSHL Vd.<T>, Vn.<T>, Vm.<T> for byte, halfword, and word lanes.
    if( (instruction & 0xbf20fc00) == 0x0e204400 ) {
      int size = (instruction >>> 22) & 3;
      if( size == 3 ) return undefined( instruction );
      out.operation = Aarch64DecodedInsn.Operation.SSHL_VECTOR_ELEMENTS;
      out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
      out.accessSize = 1 << size;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // ADD/SUB Dd, Dn, Dm (scalar SIMD integer arithmetic).
    int scalarVectorAddSub64 = instruction & 0xffe0fc00;
    if( scalarVectorAddSub64 == 0x5ee08400
        || scalarVectorAddSub64 == 0x7ee08400 ) {
      out.operation = scalarVectorAddSub64 == 0x5ee08400
          ? Aarch64DecodedInsn.Operation.ADD_SCALAR_64
          : Aarch64DecodedInsn.Operation.SUB_SCALAR_64;
      out.dataSize = 64;
      out.accessSize = 8;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // MOV Vd.<T>[destination], Vn.<T>[source] (INS vector element alias).
    if( (instruction & 0xff208400) == 0x6e000400 ) {
      int imm5 = (instruction >>> 16) & 31;
      if( imm5 != 0 ) {
        int elementShift = Integer.numberOfTrailingZeros( imm5 );
        int imm4 = (instruction >>> 11) & 15;
        if( elementShift <= 3 && (imm4 & ((1 << elementShift) - 1)) == 0 ) {
          out.operation = Aarch64DecodedInsn.Operation.MOVE_VECTOR_LANE;
          out.accessSize = 1 << elementShift;
          out.dataSize = out.accessSize * 8;
          out.bitIndex = imm5 >>> (elementShift + 1);
          out.immediate = imm4 >>> elementShift;
          out.rn = (instruction >>> 5) & 31;
          out.rd = instruction & 31;
          return out;
        }
      }
    }

    // MOV Bd/Hd/Sd/Dd, Vn.<T>[source] (scalar DUP alias).
    if( (instruction & 0xffe0fc00) == 0x5e000400 ) {
      int imm5 = (instruction >>> 16) & 31;
      if( imm5 != 0 ) {
        int elementShift = Integer.numberOfTrailingZeros( imm5 );
        if( elementShift <= 3 ) {
          out.operation = Aarch64DecodedInsn.Operation.MOVE_SCALAR_FROM_VECTOR_LANE;
          out.accessSize = 1 << elementShift;
          out.dataSize = out.accessSize * 8;
          out.bitIndex = imm5 >>> (elementShift + 1);
          out.rn = (instruction >>> 5) & 31;
          out.rd = instruction & 31;
          return out;
        }
      }
    }

    // MOV Vd.D[destination], Vn.D[source] (INS vector element alias).
    if( (instruction & 0xffa0bc00) == 0x6e000400 ) {
      int imm5 = (instruction >>> 16) & 31;
      if( imm5 == 8 || imm5 == 24 ) {
        out.operation = Aarch64DecodedInsn.Operation.MOVE_VECTOR_D_LANE;
        out.dataSize = 64;
        out.bitIndex = imm5 >>> 4;
        out.immediate = (instruction >>> 14) & 1;
        out.rn = (instruction >>> 5) & 31;
        out.rd = instruction & 31;
        return out;
      }
    }

    // MOV Vd.<T>[destination], Wn/Xn (INS general alias).
    if( (instruction & 0xffe0fc00) == 0x4e001c00 ) {
      int imm5 = (instruction >>> 16) & 31;
      int elementShift = Integer.numberOfTrailingZeros( imm5 );
      if( imm5 != 0 && elementShift <= 3 ) {
        out.operation = Aarch64DecodedInsn.Operation.MOVE_VECTOR_FROM_GENERAL_LANE;
        out.accessSize = 1 << elementShift;
        out.dataSize = out.accessSize * 8;
        out.bitIndex = imm5 >>> (elementShift + 1);
        out.rn = (instruction >>> 5) & 31;
        out.rd = instruction & 31;
        return out;
      }
    }

    // ADD Vd.2D, Vn.2D, Vm.2D.
    if( (instruction & 0xffe0fc00) == 0x4ee08400 ) {
      out.operation = Aarch64DecodedInsn.Operation.ADD_VECTOR_2D;
      out.dataSize = 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // SUB Vd.2D, Vn.2D, Vm.2D.
    if( (instruction & 0xffe0fc00) == 0x6ee08400 ) {
      out.operation = Aarch64DecodedInsn.Operation.SUB_VECTOR_2D;
      out.dataSize = 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // ADD Vd.<T>, Vn.<T>, Vm.<T> for byte, halfword, and word lanes.
    if( (instruction & 0xbf20fc00) == 0x0e208400 ) {
      int size = (instruction >>> 22) & 3;
      if( size == 3 ) return undefined( instruction );
      out.operation = Aarch64DecodedInsn.Operation.ADD_VECTOR_ELEMENTS;
      out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
      out.accessSize = 1 << size;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // SUB Vd.<T>, Vn.<T>, Vm.<T> for byte, halfword, and word lanes.
    if( (instruction & 0xbf20fc00) == 0x2e208400 ) {
      int size = (instruction >>> 22) & 3;
      if( size == 3 ) return undefined( instruction );
      out.operation = Aarch64DecodedInsn.Operation.SUB_VECTOR_ELEMENTS;
      out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
      out.accessSize = 1 << size;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // SADDW Vd.2D, Vn.2D, Vm.2S.
    if( (instruction & 0xffe0fc00) == 0x0ea01000 ) {
      out.operation = Aarch64DecodedInsn.Operation.SADDW_VECTOR_2D;
      out.dataSize = 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // UADDW Vd.4S, Vn.4S, Vm.4H.
    if( (instruction & 0xffe0fc00) == 0x2e601000 ) {
      out.operation = Aarch64DecodedInsn.Operation.UADDW_VECTOR_4S;
      out.dataSize = 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // UADDW2 Vd.4S, Vn.4S, Vm.8H.
    if( (instruction & 0xffe0fc00) == 0x6e601000 ) {
      out.operation = Aarch64DecodedInsn.Operation.UADDW2_VECTOR_4S;
      out.dataSize = 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // SADDW Vd.4S, Vn.4S, Vm.4H.
    if( (instruction & 0xffe0fc00) == 0x0e601000 ) {
      out.operation = Aarch64DecodedInsn.Operation.SADDW_VECTOR_4S;
      out.dataSize = 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // SADDW2 Vd.4S, Vn.4S, Vm.8H.
    if( (instruction & 0xffe0fc00) == 0x4e601000 ) {
      out.operation = Aarch64DecodedInsn.Operation.SADDW2_VECTOR_4S;
      out.dataSize = 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // ADDV Sd, Vn.4S.
    if( (instruction & 0xfffffc00) == 0x4eb1b800 ) {
      out.operation = Aarch64DecodedInsn.Operation.ADDV_VECTOR_4S;
      out.dataSize = 32;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // REV32 Vd.16B, Vn.16B.
    if( (instruction & 0xfffffc00) == 0x6e200800 ) {
      out.operation = Aarch64DecodedInsn.Operation.REV32_VECTOR_16B;
      out.dataSize = 128;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // ZIP1 Vd.16B, Vn.16B, Vm.16B.
    if( (instruction & 0xffe0fc00) == 0x4e003800 ) {
      out.operation = Aarch64DecodedInsn.Operation.ZIP1_VECTOR_16B;
      out.dataSize = 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // ZIP2 Vd.16B, Vn.16B, Vm.16B.
    if( (instruction & 0xffe0fc00) == 0x4e007800 ) {
      out.operation = Aarch64DecodedInsn.Operation.ZIP2_VECTOR_16B;
      out.dataSize = 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // ZIP1/ZIP2 for the remaining vector element arrangements.
    int vectorZip = instruction & 0xbf20fc00;
    if( vectorZip == 0x0e003800 || vectorZip == 0x0e007800 ) {
      int size = (instruction >>> 22) & 3;
      out.operation = vectorZip == 0x0e003800
          ? Aarch64DecodedInsn.Operation.ZIP1_VECTOR_ELEMENTS
          : Aarch64DecodedInsn.Operation.ZIP2_VECTOR_ELEMENTS;
      out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
      out.accessSize = 1 << size;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // SXTL Vd.8H, Vn.8B (SSHLL #0 alias).
    if( (instruction & 0xfffffc00) == 0x0f08a400 ) {
      out.operation = Aarch64DecodedInsn.Operation.SXTL_VECTOR_8H;
      out.dataSize = 128;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // SXTL2 Vd.8H, Vn.16B (SSHLL2 #0 alias).
    if( (instruction & 0xfffffc00) == 0x4f08a400 ) {
      out.operation = Aarch64DecodedInsn.Operation.SXTL2_VECTOR_8H;
      out.dataSize = 128;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // UZP1 Vd.16B/8H/4S/2D, Vn, Vm.
    if( (instruction & 0xff20fc00) == 0x4e001800 ) {
      out.operation = Aarch64DecodedInsn.Operation.UZP1_VECTOR;
      out.dataSize = 128;
      out.accessSize = 1 << ((instruction >>> 22) & 3);
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // UZP2 Vd.16B/8H/4S/2D, Vn, Vm.
    if( (instruction & 0xff20fc00) == 0x4e005800 ) {
      out.operation = Aarch64DecodedInsn.Operation.UZP2_VECTOR;
      out.dataSize = 128;
      out.accessSize = 1 << ((instruction >>> 22) & 3);
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // XTN Vd.8B/4H/2S, Vn.8H/4S/2D.
    if( (instruction & 0xff3ffc00) == 0x0e212800 ) {
      int size = (instruction >>> 22) & 3;
      if( size == 3 ) return undefined( instruction );
      out.operation = Aarch64DecodedInsn.Operation.XTN_VECTOR;
      out.dataSize = 64;
      out.accessSize = 1 << size;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // CMLT Vd.<T>, Vn.<T>, #0.
    if( (instruction & 0xbf3ffc00) == 0x0e20a800 ) {
      int size = (instruction >>> 22) & 3;
      out.operation = Aarch64DecodedInsn.Operation.CMLT_VECTOR_ZERO;
      out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
      out.accessSize = 1 << size;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // NEG Vd.<T>, Vn.<T>.
    if( (instruction & 0xbf3ffc00) == 0x2e20b800 ) {
      int size = (instruction >>> 22) & 3;
      out.operation = Aarch64DecodedInsn.Operation.NEG_VECTOR_ELEMENTS;
      out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
      out.accessSize = 1 << size;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // USUBL/USUBL2 Vd.8H, Vn.8B/16B, Vm.8B/16B.
    int usubl = instruction & 0xffe0fc00;
    if( usubl == 0x2e202000 || usubl == 0x6e202000 ) {
      out.operation = usubl == 0x2e202000
          ? Aarch64DecodedInsn.Operation.USUBL_VECTOR_8H
          : Aarch64DecodedInsn.Operation.USUBL2_VECTOR_8H;
      out.dataSize = 128;
      out.accessSize = 1;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // USUBW/USUBW2 Vd.8H, Vn.8H, Vm.8B/16B.
    int usubw = instruction & 0xffe0fc00;
    if( usubw == 0x2e203000 || usubw == 0x6e203000 ) {
      out.operation = usubw == 0x2e203000
          ? Aarch64DecodedInsn.Operation.USUBW_VECTOR_8H
          : Aarch64DecodedInsn.Operation.USUBW2_VECTOR_8H;
      out.dataSize = 128;
      out.accessSize = 1;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // UMULL/UMULL2 Vd.4S, Vn.4H/8H, Vm.4H/8H.
    int umull = instruction & 0xffe0fc00;
    if( umull == 0x2e60c000 || umull == 0x6e60c000 ) {
      out.operation = umull == 0x2e60c000
          ? Aarch64DecodedInsn.Operation.UMULL_VECTOR_4S
          : Aarch64DecodedInsn.Operation.UMULL2_VECTOR_4S;
      out.dataSize = 128;
      out.accessSize = 2;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // UMULL/UMULL2 Vd.2D, Vn.2S/4S, Vm.2S/4S.
    if( umull == 0x2ea0c000 || umull == 0x6ea0c000 ) {
      out.operation = umull == 0x2ea0c000
          ? Aarch64DecodedInsn.Operation.UMULL_VECTOR_2D
          : Aarch64DecodedInsn.Operation.UMULL2_VECTOR_2D;
      out.dataSize = 128;
      out.accessSize = 4;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // UMLAL Vd.2D, Vn.2S, Vm.2S and UMLAL2 Vd.2D, Vn.4S, Vm.4S.
    int umlal = instruction & 0xffe0fc00;
    if( umlal == 0x2e608000 || umlal == 0x6e608000 ) {
      out.operation = umlal == 0x2e608000
          ? Aarch64DecodedInsn.Operation.UMLAL_VECTOR_4S
          : Aarch64DecodedInsn.Operation.UMLAL2_VECTOR_4S;
      out.dataSize = 128;
      out.accessSize = 2;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }
    if( umlal == 0x2ea08000 || umlal == 0x6ea08000 ) {
      out.operation = umlal == 0x2ea08000
          ? Aarch64DecodedInsn.Operation.UMLAL_VECTOR_2D
          : Aarch64DecodedInsn.Operation.UMLAL2_VECTOR_2D;
      out.dataSize = 128;
      out.accessSize = 4;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // MUL Vd.4S, Vn.4S, Vm.4S.
    if( (instruction & 0xffe0fc00) == 0x4ea09c00 ) {
      out.operation = Aarch64DecodedInsn.Operation.MUL_VECTOR_4S;
      out.dataSize = 128;
      out.accessSize = 4;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // MLA Vd.<T>, Vn.<T>, Vm.<T> for byte, halfword, and word lanes.
    if( (instruction & 0xbf20fc00) == 0x0e209400 ) {
      int size = (instruction >>> 22) & 3;
      if( size == 3 ) return undefined( instruction );
      out.operation = Aarch64DecodedInsn.Operation.MLA_VECTOR_ELEMENTS;
      out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
      out.accessSize = 1 << size;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // MLS Vd.<T>, Vn.<T>, Vm.<T> for byte, halfword, and word lanes.
    if( (instruction & 0xbf20fc00) == 0x2e209400 ) {
      int size = (instruction >>> 22) & 3;
      if( size == 3 ) return undefined( instruction );
      out.operation = Aarch64DecodedInsn.Operation.MLS_VECTOR_ELEMENTS;
      out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
      out.accessSize = 1 << size;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // SSHLL Vd.2D, Vn.2S, #0 (alias SXTL Vd.2D, Vn.2S).
    if( (instruction & 0xfffffc00) == 0x0f20a400 ) {
      out.operation = Aarch64DecodedInsn.Operation.SSHLL_VECTOR_2D;
      out.dataSize = 128;
      out.accessSize = 4;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // USHLL/USHLL2 Vd, Vn, #shift (UXTL/UXTL2 when shift is zero).
    if( (instruction & 0xbf80fc00) == 0x2f00a400 ) {
      int encodedShift = (instruction >>> 16) & 0x7f;
      if( encodedShift < 8 || encodedShift >= 64 ) return undefined( instruction );
      int sourceBits = Integer.highestOneBit( encodedShift );
      out.operation = Aarch64DecodedInsn.Operation.USHLL_VECTOR;
      out.dataSize = 128;
      out.accessSize = sourceBits / 8;
      out.shiftAmount = encodedShift - sourceBits;
      out.immediate = (instruction >>> 30) & 1;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // LD1 { Vt.16B }, [Xn], with optional immediate post-index by 16.
    int vectorLoadOne = instruction & 0xfffffc00;
    if( vectorLoadOne == 0x4c407000 || vectorLoadOne == 0x4cdf7000 ) {
      out.operation = Aarch64DecodedInsn.Operation.LD1_VECTOR_16B;
      out.dataSize = 128;
      out.accessSize = 16;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = vectorLoadOne == 0x4cdf7000
          ? Aarch64DecodedInsn.AddressMode.POST_INDEX
          : Aarch64DecodedInsn.AddressMode.OFFSET;
      if( out.addressMode == Aarch64DecodedInsn.AddressMode.POST_INDEX ) {
        out.immediate = 16;
      }
      return out;
    }

    // LD1 { Vt.16B, Vt2.16B }, [Xn], with optional immediate post-index by 32.
    int vectorLoadTwo = instruction & 0xfffffc00;
    if( vectorLoadTwo == 0x4c40a000 || vectorLoadTwo == 0x4cdfa000 ) {
      out.operation = Aarch64DecodedInsn.Operation.LD1_VECTOR_2_16B;
      out.dataSize = 128;
      out.accessSize = 32;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.rt2 = (out.rd + 1) & 31;
      out.addressMode = vectorLoadTwo == 0x4cdfa000
          ? Aarch64DecodedInsn.AddressMode.POST_INDEX
          : Aarch64DecodedInsn.AddressMode.OFFSET;
      if( out.addressMode == Aarch64DecodedInsn.AddressMode.POST_INDEX ) {
        out.immediate = 32;
      }
      return out;
    }

    // ST1 { Vt.16B } or { Vt.16B, Vt2.16B }, with optional post-index.
    int vectorStore = instruction & 0xfffffc00;
    if( vectorStore == 0x4c007000 || vectorStore == 0x4c9f7000
        || vectorStore == 0x4c00a000 || vectorStore == 0x4c9fa000 ) {
      boolean two = vectorStore == 0x4c00a000 || vectorStore == 0x4c9fa000;
      boolean postIndex = vectorStore == 0x4c9f7000 || vectorStore == 0x4c9fa000;
      out.operation = two ? Aarch64DecodedInsn.Operation.ST1_VECTOR_2_16B
                          : Aarch64DecodedInsn.Operation.ST1_VECTOR_16B;
      out.dataSize = 128;
      out.accessSize = two ? 32 : 16;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.rt2 = (out.rd + 1) & 31;
      out.addressMode = postIndex ? Aarch64DecodedInsn.AddressMode.POST_INDEX
                                  : Aarch64DecodedInsn.AddressMode.OFFSET;
      if( postIndex ) out.immediate = out.accessSize;
      return out;
    }

    // ST2 { Vt.16B/8H, Vt2.16B/8H }, [Xn], with optional #32 post-index.
    int vectorStoreTwoHalfwords = instruction & 0xfffffc00;
    if( vectorStoreTwoHalfwords == 0x4c008000
        || vectorStoreTwoHalfwords == 0x4c9f8000
        || vectorStoreTwoHalfwords == 0x4c008400
        || vectorStoreTwoHalfwords == 0x4c9f8400 ) {
      out.operation = Aarch64DecodedInsn.Operation.ST2_VECTOR;
      out.dataSize = 128;
      out.accessSize = (vectorStoreTwoHalfwords & 0x400) == 0 ? 1 : 2;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.rt2 = (out.rd + 1) & 31;
      out.addressMode = (vectorStoreTwoHalfwords & 0x00800000) != 0
          ? Aarch64DecodedInsn.AddressMode.POST_INDEX
          : Aarch64DecodedInsn.AddressMode.OFFSET;
      out.immediate = out.addressMode == Aarch64DecodedInsn.AddressMode.POST_INDEX ? 32 : 0;
      return out;
    }

    // LD2 { Vt.16B/8H, Vt2.16B/8H }, [Xn], with optional #32 post-index.
    int vectorLoadTwoElements = instruction & 0xfffffc00;
    if( vectorLoadTwoElements == 0x4c408000
        || vectorLoadTwoElements == 0x4cdf8000
        || vectorLoadTwoElements == 0x4c408400
        || vectorLoadTwoElements == 0x4cdf8400 ) {
      out.operation = Aarch64DecodedInsn.Operation.LD2_VECTOR;
      out.dataSize = 128;
      out.accessSize = (vectorLoadTwoElements & 0x400) == 0 ? 1 : 2;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.rt2 = (out.rd + 1) & 31;
      out.addressMode = (vectorLoadTwoElements & 0x00800000) != 0
          ? Aarch64DecodedInsn.AddressMode.POST_INDEX
          : Aarch64DecodedInsn.AddressMode.OFFSET;
      out.immediate = out.addressMode == Aarch64DecodedInsn.AddressMode.POST_INDEX ? 32 : 0;
      return out;
    }

    // ST1 { Vt.B/H/S/D }[index], [Xn].
    int storeOneLane = instruction & 0xbffffc00;
    int laneBytes;
    int laneIndex;
    if( (instruction & 0xbfffe000) == 0x0d000000 ) {
      laneBytes = 1;
      laneIndex = ((instruction >>> 30) & 1) * 8 + ((instruction >>> 10) & 7);
    } else if( (instruction & 0xbfffe400) == 0x0d004000 ) {
      laneBytes = 2;
      laneIndex = ((instruction >>> 30) & 1) * 4 + ((instruction >>> 11) & 3);
    } else if( (instruction & 0xbfffec00) == 0x0d008000 ) {
      laneBytes = 4;
      laneIndex = ((instruction >>> 30) & 1) * 2 + ((instruction >>> 12) & 1);
    } else if( storeOneLane == 0x0d008400 ) {
      laneBytes = 8;
      laneIndex = (instruction >>> 30) & 1;
    } else {
      laneBytes = 0;
      laneIndex = 0;
    }
    if( laneBytes != 0 ) {
      out.operation = Aarch64DecodedInsn.Operation.ST1_VECTOR_LANE;
      out.dataSize = laneBytes * 8;
      out.accessSize = laneBytes;
      out.bitIndex = laneIndex;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = Aarch64DecodedInsn.AddressMode.OFFSET;
      return out;
    }

    // LD1 { Vt.D }[index], [Xn], preserving the other 64-bit lane.
    if( (instruction & 0xbffffc00) == 0x0d408400 ) {
      out.operation = Aarch64DecodedInsn.Operation.LD1_VECTOR_D_LANE;
      out.dataSize = 64;
      out.accessSize = 8;
      out.bitIndex = (instruction >>> 30) & 1;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = Aarch64DecodedInsn.AddressMode.OFFSET;
      return out;
    }

    // LD1R { Vt.2D }, [Xn].
    if( (instruction & 0xfffffc00) == 0x4d40cc00 ) {
      out.operation = Aarch64DecodedInsn.Operation.LD1R_VECTOR_2D;
      out.dataSize = 128;
      out.accessSize = 8;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // CMEQ Vd.8B/16B, Vn.8B/16B, Vm.8B/16B.
    if( (instruction & 0xbfe0fc00) == 0x2e208c00 ) {
      out.operation = Aarch64DecodedInsn.Operation.CMEQ_VECTOR_BYTE;
      out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // CMEQ Vd.8B/16B, Vn.8B/16B, #0.
    if( (instruction & 0xbffffc00) == 0x0e209800 ) {
      out.operation = Aarch64DecodedInsn.Operation.CMEQ_VECTOR_BYTE_ZERO;
      out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // AND Vd.8B/16B, Vn.8B/16B, Vm.8B/16B.
    if( (instruction & 0xbfe0fc00) == 0x0e201c00 ) {
      out.operation = Aarch64DecodedInsn.Operation.AND_VECTOR;
      out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // ORR Vd.8B/16B, Vn.8B/16B, Vm.8B/16B.
    if( (instruction & 0xbfe0fc00) == 0x0ea01c00 ) {
      out.operation = Aarch64DecodedInsn.Operation.ORR_VECTOR;
      out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // BIT Vd.16B, Vn.16B, Vm.16B (bitwise insert under mask Vm).
    if( (instruction & 0xffe0fc00) == 0x6ea01c00 ) {
      out.operation = Aarch64DecodedInsn.Operation.BIT_VECTOR;
      out.dataSize = 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // BSL Vd.16B, Vn.16B, Vm.16B (Vd is both mask and destination).
    if( (instruction & 0xffe0fc00) == 0x6e601c00 ) {
      out.operation = Aarch64DecodedInsn.Operation.BSL_VECTOR;
      out.dataSize = 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // EOR Vd.8B/16B, Vn.8B/16B, Vm.8B/16B.
    if( (instruction & 0xbfe0fc00) == 0x2e201c00 ) {
      out.operation = Aarch64DecodedInsn.Operation.EOR_VECTOR;
      out.dataSize = ((instruction >>> 30) & 1) == 0 ? 64 : 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // BIC Vd.8H, #imm8, optionally shifted left by eight.
    int vectorBicHalfword = instruction & 0xff80fc00;
    if( vectorBicHalfword == 0x6f009400
        || vectorBicHalfword == 0x6f00b400 ) {
      long imm8 = (((instruction >>> 16) & 7L) << 5)
          | ((instruction >>> 5) & 31L);
      if( vectorBicHalfword == 0x6f00b400 ) imm8 <<= 8;
      out.operation = Aarch64DecodedInsn.Operation.BIC_VECTOR_IMMEDIATE_HALFWORD;
      out.dataSize = 128;
      out.immediate = imm8 * 0x0001000100010001L;
      out.rd = instruction & 31;
      return out;
    }

    // UMAXP Vd.16B, Vn.16B, Vm.16B.
    if( (instruction & 0xffe0fc00) == 0x6e20a400 ) {
      out.operation = Aarch64DecodedInsn.Operation.UMAXP_VECTOR_BYTE;
      out.dataSize = 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // UMAXP Vd.4S, Vn.4S, Vm.4S.
    if( (instruction & 0xffe0fc00) == 0x6ea0a400 ) {
      out.operation = Aarch64DecodedInsn.Operation.UMAXP_VECTOR_4S;
      out.dataSize = 128;
      out.accessSize = 4;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // UMINP Vd.16B, Vn.16B, Vm.16B.
    if( (instruction & 0xffe0fc00) == 0x6e20ac00 ) {
      out.operation = Aarch64DecodedInsn.Operation.UMINP_VECTOR_BYTE;
      out.dataSize = 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // ADDP Vd.16B, Vn.16B, Vm.16B.
    if( (instruction & 0xffe0fc00) == 0x4e20bc00 ) {
      out.operation = Aarch64DecodedInsn.Operation.ADDP_VECTOR_BYTE;
      out.dataSize = 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // EXT Vd.16B, Vn.16B, Vm.16B, #byte-index.
    if( (instruction & 0xffe08400) == 0x6e000000 ) {
      out.operation = Aarch64DecodedInsn.Operation.EXT_VECTOR_16B;
      out.dataSize = 128;
      out.immediate = (instruction >>> 11) & 15;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // CMHS Vd.16B, Vn.16B, Vm.16B (unsigned greater-than-or-equal).
    if( (instruction & 0xffe0fc00) == 0x6e203c00 ) {
      out.operation = Aarch64DecodedInsn.Operation.CMHS_VECTOR_BYTE;
      out.dataSize = 128;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // SHRN/SHRN2 Vd.8B/4H/2S or 16B/8H/4S, Vn, #shift.
    int shrn = instruction & 0xff80fc00;
    if( shrn == 0x0f008400 || shrn == 0x4f008400 ) {
      int immhb = (instruction >>> 16) & 0x7f;
      if( immhb < 8 || immhb >= 64 ) return undefined( instruction );
      int sourceBits = immhb < 16 ? 16 : immhb < 32 ? 32 : 64;
      out.operation = shrn == 0x0f008400
          ? Aarch64DecodedInsn.Operation.SHRN_VECTOR
          : Aarch64DecodedInsn.Operation.SHRN2_VECTOR;
      out.dataSize = shrn == 0x0f008400 ? 64 : 128;
      out.accessSize = sourceBits / 16;
      out.shiftAmount = sourceBits - immhb;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // ADDHN Vd.8B, Vn.8H, Vm.8H: high halves of 16-bit lane sums.
    if( (instruction & 0xffe0fc00) == 0x0e204000 ) {
      out.operation = Aarch64DecodedInsn.Operation.ADDHN_VECTOR_8B;
      out.dataSize = 64;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // FMOV Xd, Dn (bitwise transfer from the low 64-bit vector lane).
    if( (instruction & 0xfffffc00) == 0x9e660000 ) {
      out.operation = Aarch64DecodedInsn.Operation.FMOV_GENERAL_FROM_D;
      out.dataSize = 64;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // FMOV Wd, Sn (bitwise transfer from the low 32-bit vector lane).
    if( (instruction & 0xfffffc00) == 0x1e260000 ) {
      out.operation = Aarch64DecodedInsn.Operation.FMOV_GENERAL_FROM_S;
      out.dataSize = 32;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // FMOV Dd, Xn (bitwise transfer from a general register).
    if( (instruction & 0xfffffc00) == 0x9e670000 ) {
      out.operation = Aarch64DecodedInsn.Operation.FMOV_D_FROM_GENERAL;
      out.dataSize = 64;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // FMOV Sd, Wn (bitwise transfer from a 32-bit general register).
    if( (instruction & 0xfffffc00) == 0x1e270000 ) {
      out.operation = Aarch64DecodedInsn.Operation.FMOV_S_FROM_GENERAL;
      out.dataSize = 32;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // FMOV Sd/Dd, Sn/Dn (bitwise scalar FP register move).
    int floatingMove = instruction & 0xfffffc00;
    if( floatingMove == 0x1e204000 || floatingMove == 0x1e604000 ) {
      out.operation = floatingMove == 0x1e204000
          ? Aarch64DecodedInsn.Operation.FMOV_VECTOR_32
          : Aarch64DecodedInsn.Operation.FMOV_VECTOR_64;
      out.dataSize = floatingMove == 0x1e204000 ? 32 : 64;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // FMOV Sd/Dd, #imm.  The architectural imm8 is expanded directly to the
    // IEEE-754 bit pattern so execution does not depend on host FP parsing.
    int floatingImmediate = instruction & 0xffe01fe0;
    if( floatingImmediate == 0x1e201000
        || floatingImmediate == 0x1e601000 ) {
      out.operation = Aarch64DecodedInsn.Operation.FMOV_IMMEDIATE;
      out.dataSize = floatingImmediate == 0x1e201000 ? 32 : 64;
      out.immediate = expandFloatingImmediate(
          (instruction >>> 13) & 0xff, out.dataSize );
      out.rd = instruction & 31;
      return out;
    }

    // UMOV Wd, Vn.B/H/S[index] (also disassembled as MOV for S lanes).
    if( (instruction & 0xffe0fc00) == 0x0e003c00 ) {
      int imm5 = (instruction >>> 16) & 31;
      int elementShift = Integer.numberOfTrailingZeros( imm5 );
      if( imm5 == 0 || elementShift > 2 ) return undefined( instruction );
      out.operation = Aarch64DecodedInsn.Operation.MOVE_GENERAL_FROM_VECTOR_LANE;
      out.dataSize = 32;
      out.accessSize = 1 << elementShift;
      out.bitIndex = imm5 >>> (elementShift + 1);
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // UMOV Xd, Vn.D[index] (disassembled as MOV).
    if( (instruction & 0xffe0fc00) == 0x4e003c00 ) {
      int imm5 = (instruction >>> 16) & 31;
      if( imm5 == 8 || imm5 == 24 ) {
        out.operation = Aarch64DecodedInsn.Operation.MOVE_GENERAL_FROM_VECTOR_LANE;
        out.dataSize = 64;
        out.accessSize = 8;
        out.bitIndex = imm5 >>> 4;
        out.rn = (instruction >>> 5) & 31;
        out.rd = instruction & 31;
        return out;
      }
    }

    // FCMP/FCMPE Sn/Dn, #0.0 and FCMP/FCMPE Sn/Dn, Sm/Dm. FCMPE differs by bit 4;
    // with FP exceptions unmodelled both forms produce the same NZCV result.
    int floatingCompareZero = instruction & 0xfffffc0f;
    if( floatingCompareZero == 0x1e202008
        || floatingCompareZero == 0x1e602008 ) {
      out.operation = floatingCompareZero == 0x1e202008
          ? Aarch64DecodedInsn.Operation.FCMP_S_ZERO
          : Aarch64DecodedInsn.Operation.FCMP_D_ZERO;
      out.dataSize = floatingCompareZero == 0x1e202008 ? 32 : 64;
      out.rn = (instruction >>> 5) & 31;
      return out;
    }

    // SCVTF Dd, Wn/Xn and UCVTF Dd, Wn/Xn.
    int integerToDouble = instruction & 0x7ffffc00;
    if( integerToDouble == 0x1e220000 || integerToDouble == 0x1e620000
        || integerToDouble == 0x1e230000 || integerToDouble == 0x1e630000 ) {
      boolean unsigned = integerToDouble == 0x1e230000
          || integerToDouble == 0x1e630000;
      out.operation = unsigned
          ? Aarch64DecodedInsn.Operation.UCVTF_D_FROM_GENERAL
          : Aarch64DecodedInsn.Operation.SCVTF_D_FROM_GENERAL;
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // SCVTF/UCVTF Dd, Dn: scalar 64-bit integer vector lane to double.
    int vectorIntegerToDouble = instruction & 0xfffffc00;
    if( vectorIntegerToDouble == 0x5e61d800
        || vectorIntegerToDouble == 0x7e61d800 ) {
      out.operation = vectorIntegerToDouble == 0x5e61d800
          ? Aarch64DecodedInsn.Operation.SCVTF_D_FROM_VECTOR
          : Aarch64DecodedInsn.Operation.UCVTF_D_FROM_VECTOR;
      out.dataSize = 64;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // FCVT Dd, Sn and FCVT Sd, Dn.
    int floatingConvert = instruction & 0xfffffc00;
    if( floatingConvert == 0x1e22c000 || floatingConvert == 0x1e624000 ) {
      out.operation = floatingConvert == 0x1e22c000
          ? Aarch64DecodedInsn.Operation.FCVT_D_FROM_S
          : Aarch64DecodedInsn.Operation.FCVT_S_FROM_D;
      out.dataSize = floatingConvert == 0x1e22c000 ? 64 : 32;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // FRINTM Dd, Dn: round toward minus infinity.
    if( (instruction & 0xfffffc00) == 0x1e654000 ) {
      out.operation = Aarch64DecodedInsn.Operation.FRINTM_D;
      out.dataSize = 64;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // FABS/FNEG Sd/Dd, Sn/Dn.
    int floatingAbsNegate = instruction & 0xfffffc00;
    if( floatingAbsNegate == 0x1e20c000 || floatingAbsNegate == 0x1e214000
        || floatingAbsNegate == 0x1e60c000 || floatingAbsNegate == 0x1e614000 ) {
      boolean isDouble = (floatingAbsNegate & 0x00400000) != 0;
      boolean negate = (floatingAbsNegate & 0x00008000) != 0;
      out.operation = isDouble
          ? (negate ? Aarch64DecodedInsn.Operation.FNEG_D
                    : Aarch64DecodedInsn.Operation.FABS_D)
          : (negate ? Aarch64DecodedInsn.Operation.FNEG_S
                    : Aarch64DecodedInsn.Operation.FABS_S);
      out.dataSize = isDouble ? 64 : 32;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // FCSEL Sd/Dd, Sn, Sm, cond.
    int floatingConditionalSelect = instruction & 0xffe00c00;
    if( floatingConditionalSelect == 0x1e200c00
        || floatingConditionalSelect == 0x1e600c00 ) {
      out.operation = Aarch64DecodedInsn.Operation.FCSEL_FP;
      out.dataSize = floatingConditionalSelect == 0x1e200c00 ? 32 : 64;
      out.rm = (instruction >>> 16) & 31;
      out.condition = (instruction >>> 12) & 15;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // FMADD/FMSUB Sd/Dd, Sn, Sm, Sa/Da.
    int fusedMultiplyAdd = instruction & 0xffe08000;
    if( fusedMultiplyAdd == 0x1f000000 || fusedMultiplyAdd == 0x1f008000
        || fusedMultiplyAdd == 0x1f400000 || fusedMultiplyAdd == 0x1f408000 ) {
      boolean isDouble = (fusedMultiplyAdd & 0x00400000) != 0;
      boolean subtract = (fusedMultiplyAdd & 0x00008000) != 0;
      out.operation = isDouble
          ? (subtract ? Aarch64DecodedInsn.Operation.FMSUB_D
                      : Aarch64DecodedInsn.Operation.FMADD_D)
          : (subtract ? Aarch64DecodedInsn.Operation.FMSUB_S
                      : Aarch64DecodedInsn.Operation.FMADD_S);
      out.dataSize = isDouble ? 64 : 32;
      out.rm = (instruction >>> 16) & 31;
      out.ra = (instruction >>> 10) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // FCVTZS/FCVTZU Wd/Xd, Sn/Dn: FP to integer, rounding toward zero.
    int floatingToInteger = instruction & 0x7ffffc00;
    if( floatingToInteger == 0x1e380000 || floatingToInteger == 0x1e780000
        || floatingToInteger == 0x1e390000
        || floatingToInteger == 0x1e790000 ) {
      boolean unsigned = floatingToInteger == 0x1e390000
          || floatingToInteger == 0x1e790000;
      out.operation = unsigned
          ? Aarch64DecodedInsn.Operation.FCVTZU_GENERAL_FROM_FP
          : Aarch64DecodedInsn.Operation.FCVTZS_GENERAL_FROM_FP;
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      out.accessSize = (floatingToInteger & 0x00400000) == 0 ? 4 : 8;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // FCVTZU Dd, Dn: scalar SIMD FP64 to uint64, rounding toward zero.
    if( (instruction & 0xfffffc00) == 0x7ee1b800 ) {
      out.operation = Aarch64DecodedInsn.Operation.FCVTZU_VECTOR_D_FROM_FP;
      out.dataSize = 64;
      out.accessSize = 8;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // FCVTMS/FCVTMU Wd/Xd, Sn/Dn: FP to integer, rounding toward -infinity.
    int floatingToIntegerMinus = instruction & 0x7ffffc00;
    if( floatingToIntegerMinus == 0x1e300000
        || floatingToIntegerMinus == 0x1e700000
        || floatingToIntegerMinus == 0x1e310000
        || floatingToIntegerMinus == 0x1e710000 ) {
      boolean unsigned = floatingToIntegerMinus == 0x1e310000
          || floatingToIntegerMinus == 0x1e710000;
      out.operation = unsigned
          ? Aarch64DecodedInsn.Operation.FCVTMU_GENERAL_FROM_FP
          : Aarch64DecodedInsn.Operation.FCVTMS_GENERAL_FROM_FP;
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      out.accessSize = (floatingToIntegerMinus & 0x00400000) == 0 ? 4 : 8;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // FCVTPS/FCVTPU Wd/Xd, Sn/Dn: FP to integer, rounding toward +infinity.
    int floatingToIntegerPlus = instruction & 0x7ffffc00;
    if( floatingToIntegerPlus == 0x1e280000
        || floatingToIntegerPlus == 0x1e680000
        || floatingToIntegerPlus == 0x1e290000
        || floatingToIntegerPlus == 0x1e690000 ) {
      boolean unsigned = floatingToIntegerPlus == 0x1e290000
          || floatingToIntegerPlus == 0x1e690000;
      out.operation = unsigned
          ? Aarch64DecodedInsn.Operation.FCVTPU_GENERAL_FROM_FP
          : Aarch64DecodedInsn.Operation.FCVTPS_GENERAL_FROM_FP;
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      out.accessSize = (floatingToIntegerPlus & 0x00400000) == 0 ? 4 : 8;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // FCVTAS/FCVTAU Wd/Xd, Sn/Dn: FP to integer, nearest with ties away.
    int floatingToIntegerAway = instruction & 0x7ffffc00;
    if( floatingToIntegerAway == 0x1e240000
        || floatingToIntegerAway == 0x1e640000
        || floatingToIntegerAway == 0x1e250000
        || floatingToIntegerAway == 0x1e650000 ) {
      boolean unsigned = floatingToIntegerAway == 0x1e250000
          || floatingToIntegerAway == 0x1e650000;
      out.operation = unsigned
          ? Aarch64DecodedInsn.Operation.FCVTAU_GENERAL_FROM_FP
          : Aarch64DecodedInsn.Operation.FCVTAS_GENERAL_FROM_FP;
      out.dataSize = ((instruction >>> 31) & 1) == 0 ? 32 : 64;
      out.accessSize = (floatingToIntegerAway & 0x00400000) == 0 ? 4 : 8;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // Scalar single-precision floating-point arithmetic.
    int singleArithmetic = instruction & 0xffe0fc00;
    if( singleArithmetic == 0x1e200800 || singleArithmetic == 0x1e201800
        || singleArithmetic == 0x1e202800 || singleArithmetic == 0x1e203800 ) {
      out.operation = switch( singleArithmetic ) {
        case 0x1e200800 -> Aarch64DecodedInsn.Operation.FMUL_S;
        case 0x1e201800 -> Aarch64DecodedInsn.Operation.FDIV_S;
        case 0x1e202800 -> Aarch64DecodedInsn.Operation.FADD_S;
        default -> Aarch64DecodedInsn.Operation.FSUB_S;
      };
      out.dataSize = 32;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }

    // Scalar double-precision floating-point arithmetic.
    int doubleArithmetic = instruction & 0xffe0fc00;
    if( doubleArithmetic == 0x1e600800 || doubleArithmetic == 0x1e601800
        || doubleArithmetic == 0x1e602800 || doubleArithmetic == 0x1e603800 ) {
      out.operation = switch( doubleArithmetic ) {
        case 0x1e600800 -> Aarch64DecodedInsn.Operation.FMUL_D;
        case 0x1e601800 -> Aarch64DecodedInsn.Operation.FDIV_D;
        case 0x1e602800 -> Aarch64DecodedInsn.Operation.FADD_D;
        default -> Aarch64DecodedInsn.Operation.FSUB_D;
      };
      out.dataSize = 64;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      return out;
    }
    int floatingCompareRegister = instruction & 0xffe0fc0f;
    if( floatingCompareRegister == 0x1e202000
        || floatingCompareRegister == 0x1e602000 ) {
      out.operation = floatingCompareRegister == 0x1e202000
          ? Aarch64DecodedInsn.Operation.FCMP_S_REGISTER
          : Aarch64DecodedInsn.Operation.FCMP_D_REGISTER;
      out.dataSize = floatingCompareRegister == 0x1e202000 ? 32 : 64;
      out.rm = (instruction >>> 16) & 31;
      out.rn = (instruction >>> 5) & 31;
      return out;
    }

    // Exclusive load/store used by libc's lock-free primitives.  Acquire and
    // release variants share the same execution operation; the executor uses
    // conservative fences for both encodings.
    int exclusiveLoad = instruction & 0x3ffffc00;
    if( exclusiveLoad == 0x085f7c00 || exclusiveLoad == 0x085ffc00 ) {
      out.operation = Aarch64DecodedInsn.Operation.LOAD_EXCLUSIVE;
      int size = (instruction >>> 30) & 3;
      out.dataSize = size == 3 ? 64 : 32;
      out.accessSize = 1 << size;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = Aarch64DecodedInsn.AddressMode.OFFSET;
      return out;
    }

    int exclusiveStore = instruction & 0x3fe0fc00;
    if( exclusiveStore == 0x08007c00 || exclusiveStore == 0x0800fc00 ) {
      out.operation = Aarch64DecodedInsn.Operation.STORE_EXCLUSIVE;
      int size = (instruction >>> 30) & 3;
      out.dataSize = size == 3 ? 64 : 32;
      out.accessSize = 1 << size;
      out.ra = (instruction >>> 16) & 31; // Ws: status destination
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;          // Rt: value to store
      out.addressMode = Aarch64DecodedInsn.AddressMode.OFFSET;
      return out;
    }

    // Ordered non-exclusive loads/stores: LDAR and STLR, byte through X forms.
    int orderedMemory = instruction & 0x3ffffc00;
    if( orderedMemory == 0x08dffc00 || orderedMemory == 0x089ffc00 ) {
      boolean load = orderedMemory == 0x08dffc00;
      int size = (instruction >>> 30) & 3;
      out.operation = load ? Aarch64DecodedInsn.Operation.LOAD_ACQUIRE
                           : Aarch64DecodedInsn.Operation.STORE_RELEASE;
      out.accessSize = 1 << size;
      out.dataSize = size == 3 ? 64 : 32;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = Aarch64DecodedInsn.AddressMode.OFFSET;
      return out;
    }

    // STR/LDR Ht, [Xn, #imm12 * 2].
    int scalarVector16UnsignedMemory = instruction & 0xffc00000;
    if( scalarVector16UnsignedMemory == 0x7d000000
        || scalarVector16UnsignedMemory == 0x7d400000 ) {
      out.operation = scalarVector16UnsignedMemory == 0x7d000000
          ? Aarch64DecodedInsn.Operation.STR_VECTOR_16
          : Aarch64DecodedInsn.Operation.LDR_VECTOR_16;
      out.dataSize = 16;
      out.accessSize = 2;
      out.immediate = ((instruction >>> 10) & 0xfffL) * 2;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = Aarch64DecodedInsn.AddressMode.OFFSET;
      return out;
    }

    // STR/LDR St, [Xn, #imm12 * 4].
    int scalarVector32UnsignedMemory = instruction & 0xffc00000;
    if( scalarVector32UnsignedMemory == 0xbd000000
        || scalarVector32UnsignedMemory == 0xbd400000 ) {
      out.operation = scalarVector32UnsignedMemory == 0xbd000000
          ? Aarch64DecodedInsn.Operation.STR_VECTOR_32
          : Aarch64DecodedInsn.Operation.LDR_VECTOR_32;
      out.dataSize = 32;
      out.accessSize = 4;
      out.immediate = ((instruction >>> 10) & 0xfffL) * 4;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = Aarch64DecodedInsn.AddressMode.OFFSET;
      return out;
    }

    // STR/LDR Dt, [Xn, #imm12 * 8].
    int scalarVectorUnsignedMemory = instruction & 0xffc00000;
    if( scalarVectorUnsignedMemory == 0xfd000000
        || scalarVectorUnsignedMemory == 0xfd400000 ) {
      out.operation = scalarVectorUnsignedMemory == 0xfd000000
          ? Aarch64DecodedInsn.Operation.STR_VECTOR_64
          : Aarch64DecodedInsn.Operation.LDR_VECTOR_64;
      out.dataSize = 64;
      out.accessSize = 8;
      out.immediate = ((instruction >>> 10) & 0xfffL) * 8;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = Aarch64DecodedInsn.AddressMode.OFFSET;
      return out;
    }

    // STR/LDR St/Dt, [Xn, Rm{, extend #scale}].
    int scalarVectorRegisterMemory = instruction & 0xff200c00;
    if( scalarVectorRegisterMemory == 0xbc200800
        || scalarVectorRegisterMemory == 0xbc600800
        || scalarVectorRegisterMemory == 0xfc200800
        || scalarVectorRegisterMemory == 0xfc600800 ) {
      boolean isDouble = (instruction & 0x40000000) != 0;
      boolean load = (instruction & 0x00400000) != 0;
      out.operation = isDouble
          ? (load ? Aarch64DecodedInsn.Operation.LDR_VECTOR_64
                  : Aarch64DecodedInsn.Operation.STR_VECTOR_64)
          : (load ? Aarch64DecodedInsn.Operation.LDR_VECTOR_32
                  : Aarch64DecodedInsn.Operation.STR_VECTOR_32);
      out.dataSize = isDouble ? 64 : 32;
      out.accessSize = isDouble ? 8 : 4;
      out.rm = (instruction >>> 16) & 31;
      out.extendType = Aarch64DecodedInsn.ExtendType.values()[
          ((instruction >>> 13) & 7) + 1 ];
      out.shiftAmount = ((instruction >>> 12) & 1) == 0
          ? 0 : (isDouble ? 3 : 2);
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = Aarch64DecodedInsn.AddressMode.OFFSET;
      return out;
    }

    // STUR/LDUR Bt plus pre/post-index forms with signed imm9.
    int scalarVectorByteSignedMemory = instruction & 0xffe00000;
    if( scalarVectorByteSignedMemory == 0x3c000000
        || scalarVectorByteSignedMemory == 0x3c400000 ) {
      int mode = (instruction >>> 10) & 3;
      if( mode == 2 ) return undefined( instruction );
      boolean load = (instruction & 0x00400000) != 0;
      out.operation = load ? Aarch64DecodedInsn.Operation.LDR_VECTOR_8
                           : Aarch64DecodedInsn.Operation.STR_VECTOR_8;
      out.dataSize = 8;
      out.accessSize = 1;
      out.immediate = signExtend( (instruction >>> 12) & 0x1ffL, 9 );
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = switch( mode ) {
        case 0 -> Aarch64DecodedInsn.AddressMode.OFFSET;
        case 1 -> Aarch64DecodedInsn.AddressMode.POST_INDEX;
        default -> Aarch64DecodedInsn.AddressMode.PRE_INDEX;
      };
      return out;
    }

    // STUR/LDUR Ht plus pre/post-index forms with signed imm9.
    int scalarVectorHalfSignedMemory = instruction & 0xffe00000;
    if( scalarVectorHalfSignedMemory == 0x7c000000
        || scalarVectorHalfSignedMemory == 0x7c400000 ) {
      int mode = (instruction >>> 10) & 3;
      if( mode == 2 ) return undefined( instruction );
      boolean load = (instruction & 0x00400000) != 0;
      out.operation = load ? Aarch64DecodedInsn.Operation.LDR_VECTOR_16
                           : Aarch64DecodedInsn.Operation.STR_VECTOR_16;
      out.dataSize = 16;
      out.accessSize = 2;
      out.immediate = signExtend( (instruction >>> 12) & 0x1ffL, 9 );
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = switch( mode ) {
        case 0 -> Aarch64DecodedInsn.AddressMode.OFFSET;
        case 1 -> Aarch64DecodedInsn.AddressMode.POST_INDEX;
        default -> Aarch64DecodedInsn.AddressMode.PRE_INDEX;
      };
      return out;
    }

    // STUR/LDUR St/Dt plus pre/post-index forms with signed imm9.
    int scalarVectorSignedMemory = instruction & 0xffe00000;
    if( scalarVectorSignedMemory == 0xbc000000
        || scalarVectorSignedMemory == 0xbc400000
        || scalarVectorSignedMemory == 0xfc000000
        || scalarVectorSignedMemory == 0xfc400000 ) {
      int mode = (instruction >>> 10) & 3;
      if( mode == 2 ) return undefined( instruction );
      boolean isDouble = (instruction & 0x40000000) != 0;
      boolean load = (instruction & 0x00400000) != 0;
      out.operation = isDouble
          ? (load ? Aarch64DecodedInsn.Operation.LDR_VECTOR_64
                  : Aarch64DecodedInsn.Operation.STR_VECTOR_64)
          : (load ? Aarch64DecodedInsn.Operation.LDR_VECTOR_32
                  : Aarch64DecodedInsn.Operation.STR_VECTOR_32);
      out.dataSize = isDouble ? 64 : 32;
      out.accessSize = isDouble ? 8 : 4;
      out.immediate = signExtend( (instruction >>> 12) & 0x1ffL, 9 );
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = switch( mode ) {
        case 0 -> Aarch64DecodedInsn.AddressMode.OFFSET;
        case 1 -> Aarch64DecodedInsn.AddressMode.POST_INDEX;
        default -> Aarch64DecodedInsn.AddressMode.PRE_INDEX;
      };
      return out;
    }

    // STR/LDR Qt, [Xn, #imm12 * 16].
    int vectorUnsignedMemory = instruction & 0xffc00000;
    if( vectorUnsignedMemory == 0x3d800000
        || vectorUnsignedMemory == 0x3dc00000 ) {
      out.operation = vectorUnsignedMemory == 0x3d800000
          ? Aarch64DecodedInsn.Operation.STR_VECTOR_128
          : Aarch64DecodedInsn.Operation.LDR_VECTOR_128;
      out.dataSize = 128;
      out.accessSize = 16;
      out.immediate = ((instruction >>> 10) & 0xfffL) * 16;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = Aarch64DecodedInsn.AddressMode.OFFSET;
      return out;
    }

    // STP/LDP Dt/Qt pairs, including pre/post-index forms.
    if( (instruction & 0x3e000000) == 0x2c000000 ) {
      int opc = (instruction >>> 30) & 3;
      int mode = (instruction >>> 23) & 3;
      if( opc != 1 && opc != 2 ) return undefined( instruction );
      boolean load = ((instruction >>> 22) & 1) != 0;
      out.dataSize = opc == 1 ? 64 : 128;
      out.accessSize = out.dataSize / 8;
      out.operation = out.dataSize == 64
          ? (load ? Aarch64DecodedInsn.Operation.LDP_VECTOR_64
                  : Aarch64DecodedInsn.Operation.STP_VECTOR_64)
          : (load ? Aarch64DecodedInsn.Operation.LDP_VECTOR_128
                  : Aarch64DecodedInsn.Operation.STP_VECTOR_128);
      out.immediate = signExtend( (instruction >>> 15) & 0x7fL, 7 )
          * out.accessSize;
      out.rt2 = (instruction >>> 10) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = mode == 0 ? Aarch64DecodedInsn.AddressMode.OFFSET
                                  : addressMode( mode );
      return out;
    }

    // STR/LDR Qt, [Xn, Xm{, extend #4}].
    if( (instruction & 0xff200c00) == 0x3c200800 ) {
      boolean load = ((instruction >>> 22) & 1) != 0;
      out.operation = load ? Aarch64DecodedInsn.Operation.LDR_VECTOR_128
                           : Aarch64DecodedInsn.Operation.STR_VECTOR_128;
      out.dataSize = 128;
      out.accessSize = 16;
      out.rm = (instruction >>> 16) & 31;
      out.extendType = Aarch64DecodedInsn.ExtendType.values()[
          ((instruction >>> 13) & 7) + 1 ];
      out.shiftAmount = ((instruction >>> 12) & 1) == 0 ? 0 : 4;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = Aarch64DecodedInsn.AddressMode.OFFSET;
      return out;
    }

    // STUR/LDUR Qt plus pre/post-index forms with signed imm9.
    int vectorSignedMemory = instruction & 0xffe00000;
    if( vectorSignedMemory == 0x3c800000
        || vectorSignedMemory == 0x3cc00000 ) {
      int mode = (instruction >>> 10) & 3;
      if( mode == 2 ) return undefined( instruction );
      out.operation = vectorSignedMemory == 0x3c800000
          ? Aarch64DecodedInsn.Operation.STR_VECTOR_128
          : Aarch64DecodedInsn.Operation.LDR_VECTOR_128;
      out.dataSize = 128;
      out.accessSize = 16;
      out.immediate = signExtend( (instruction >>> 12) & 0x1ffL, 9 );
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = switch( mode ) {
        case 0 -> Aarch64DecodedInsn.AddressMode.OFFSET;
        case 1 -> Aarch64DecodedInsn.AddressMode.POST_INDEX;
        default -> Aarch64DecodedInsn.AddressMode.PRE_INDEX;
      };
      return out;
    }

    // Load/store pair, integer W/X forms. Address mode 0 is LDNP/STNP and is
    // kept outside this first executable subset.
    if( (instruction & 0x3e000000) == 0x28000000 ) {
      int opc = (instruction >>> 30) & 3;
      int mode = (instruction >>> 23) & 3;
      boolean load = ((instruction >>> 22) & 1) != 0;
      if( opc == 1 ) {
        if( !load || mode == 0 ) return undefined( instruction );
        out.operation = Aarch64DecodedInsn.Operation.LDP_SIGNED;
        out.dataSize = 64;
        out.accessSize = 4;
      } else {
        if( opc != 0 && opc != 2 ) return undefined( instruction );
        out.operation = load ? Aarch64DecodedInsn.Operation.LDP
                             : Aarch64DecodedInsn.Operation.STP;
        out.dataSize = opc == 0 ? 32 : 64;
        out.accessSize = out.dataSize / 8;
      }
      out.immediate = signExtend( (instruction >>> 15) & 0x7fL, 7 ) * out.accessSize;
      out.rt2 = (instruction >>> 10) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = mode == 0 ? Aarch64DecodedInsn.AddressMode.OFFSET
                                  : addressMode( mode );
      return out;
    }

    // PRFM/PRFUM are cache hints. A software interpreter may safely ignore
    // them, but they must not fall through to the signed-load decoder because
    // their Rt field is a prefetch operation rather than a destination.
    if( (instruction & 0xff000000) == 0xd8000000
        || (instruction & 0xffc00000) == 0xf9800000
        || (instruction & 0xffe00c00) == 0xf8a00800
        || (instruction & 0xffe00c00) == 0xf8800000 ) {
      out.operation = Aarch64DecodedInsn.Operation.PREFETCH;
      return out;
    }

    // Load register literal, integer W/X forms.
    if( (instruction & 0x04000000) == 0
        && (instruction & 0x3b000000) == 0x18000000 ) {
      int opc = (instruction >>> 30) & 3;
      if( opc == 3 ) return undefined( instruction );
      out.operation = opc == 2 ? Aarch64DecodedInsn.Operation.LDR_SIGNED_LITERAL
                               : Aarch64DecodedInsn.Operation.LDR_LITERAL;
      out.dataSize = opc == 0 ? 32 : 64;
      out.accessSize = opc == 2 ? 4 : out.dataSize / 8;
      out.rd = instruction & 31;
      out.immediate = signExtend( (instruction >>> 5) & 0x7ffffL, 19 ) << 2;
      out.addressMode = Aarch64DecodedInsn.AddressMode.LITERAL;
      return out;
    }

    // Load/store unsigned immediate, scaled by access size.
    if( (instruction & 0x04000000) == 0
        && (instruction & 0x3b000000) == 0x39000000 ) {
      int opc = (instruction >>> 22) & 3;
      int size = (instruction >>> 30) & 3;
      boolean signedLoad = opc >= 2;
      if( opc == 3 && size >= 2 ) return undefined( instruction );
      out.operation = opc == 0 ? Aarch64DecodedInsn.Operation.STR
          : signedLoad ? Aarch64DecodedInsn.Operation.LDR_SIGNED
                       : Aarch64DecodedInsn.Operation.LDR;
      out.accessSize = 1 << size;
      out.dataSize = signedLoad ? (opc == 2 ? 64 : 32) : (size == 3 ? 64 : 32);
      out.immediate = ((instruction >>> 10) & 0xfffL) * out.accessSize;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = Aarch64DecodedInsn.AddressMode.OFFSET;
      return out;
    }

    // Load/store register offset. The option field selects Wm/Xm extension;
    // S scales the extended offset by log2(access size).
    if( (instruction & 0x04000000) == 0
        && (instruction & 0x3b200c00) == 0x38200800 ) {
      int opc = (instruction >>> 22) & 3;
      int size = (instruction >>> 30) & 3;
      boolean signedLoad = opc >= 2;
      if( opc == 3 && size >= 2 ) return undefined( instruction );
      out.operation = opc == 0 ? Aarch64DecodedInsn.Operation.STR
          : signedLoad ? Aarch64DecodedInsn.Operation.LDR_SIGNED
                       : Aarch64DecodedInsn.Operation.LDR;
      out.accessSize = 1 << size;
      out.dataSize = signedLoad ? (opc == 2 ? 64 : 32) : (size == 3 ? 64 : 32);
      out.rm = (instruction >>> 16) & 31;
      out.extendType = Aarch64DecodedInsn.ExtendType.values()[ ((instruction >>> 13) & 7) + 1 ];
      out.shiftAmount = ((instruction >>> 12) & 1) == 0 ? 0 : size;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = Aarch64DecodedInsn.AddressMode.OFFSET;
      return out;
    }

    // Load/store signed imm9: unscaled offset, pre-index, and post-index.
    if( (instruction & 0x04000000) == 0
        && (instruction & 0x3b200000) == 0x38000000 ) {
      int opc = (instruction >>> 22) & 3;
      int mode = (instruction >>> 10) & 3;
      int size = (instruction >>> 30) & 3;
      boolean signedLoad = opc >= 2;
      if( mode == 2 || (opc == 3 && size >= 2) ) return undefined( instruction );
      out.operation = opc == 0 ? Aarch64DecodedInsn.Operation.STR
          : signedLoad ? Aarch64DecodedInsn.Operation.LDR_SIGNED
                       : Aarch64DecodedInsn.Operation.LDR;
      out.accessSize = 1 << size;
      out.dataSize = signedLoad ? (opc == 2 ? 64 : 32) : (size == 3 ? 64 : 32);
      out.immediate = signExtend( (instruction >>> 12) & 0x1ffL, 9 );
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = switch( mode ) {
        case 0 -> Aarch64DecodedInsn.AddressMode.OFFSET;
        case 1 -> Aarch64DecodedInsn.AddressMode.POST_INDEX;
        default -> Aarch64DecodedInsn.AddressMode.PRE_INDEX;
      };
      return out;
    }

    // Floating-point control/status system registers.
    int fpSystemRegister = instruction & 0xffffffe0;
    if( fpSystemRegister == 0xd53b4400 || fpSystemRegister == 0xd51b4400
        || fpSystemRegister == 0xd53b4420 || fpSystemRegister == 0xd51b4420 ) {
      out.operation = switch( fpSystemRegister ) {
        case 0xd53b4400 -> Aarch64DecodedInsn.Operation.MRS_FPCR;
        case 0xd51b4400 -> Aarch64DecodedInsn.Operation.MSR_FPCR;
        case 0xd53b4420 -> Aarch64DecodedInsn.Operation.MRS_FPSR;
        default -> Aarch64DecodedInsn.Operation.MSR_FPSR;
      };
      out.rd = instruction & 31;
      out.rn = out.rd;
      out.dataSize = 64;
      return out;
    }

    return undefined( instruction );
  }

  private static Aarch64DecodedInsn.Operation addSubOperation(
      boolean subtract, boolean flags, boolean shifted, boolean extended ) {
    if( extended ) {
      if( subtract ) return flags ? Aarch64DecodedInsn.Operation.SUBS_EXTENDED_REGISTER
                                  : Aarch64DecodedInsn.Operation.SUB_EXTENDED_REGISTER;
      return flags ? Aarch64DecodedInsn.Operation.ADDS_EXTENDED_REGISTER
                   : Aarch64DecodedInsn.Operation.ADD_EXTENDED_REGISTER;
    }
    if( shifted ) {
      if( subtract ) return flags ? Aarch64DecodedInsn.Operation.SUBS_SHIFTED_REGISTER
                                  : Aarch64DecodedInsn.Operation.SUB_SHIFTED_REGISTER;
      return flags ? Aarch64DecodedInsn.Operation.ADDS_SHIFTED_REGISTER
                   : Aarch64DecodedInsn.Operation.ADD_SHIFTED_REGISTER;
    }
    if( subtract ) return flags ? Aarch64DecodedInsn.Operation.SUBS_IMMEDIATE
                                : Aarch64DecodedInsn.Operation.SUB_IMMEDIATE;
    return flags ? Aarch64DecodedInsn.Operation.ADDS_IMMEDIATE
                 : Aarch64DecodedInsn.Operation.ADD_IMMEDIATE;
  }

  private static Aarch64DecodedInsn.Operation logicalRegisterOperation( int opc,
                                                                         boolean invert ) {
    return switch( opc ) {
      case 0 -> invert ? Aarch64DecodedInsn.Operation.BIC_SHIFTED_REGISTER
                       : Aarch64DecodedInsn.Operation.AND_SHIFTED_REGISTER;
      case 1 -> invert ? Aarch64DecodedInsn.Operation.ORN_SHIFTED_REGISTER
                       : Aarch64DecodedInsn.Operation.ORR_SHIFTED_REGISTER;
      case 2 -> invert ? Aarch64DecodedInsn.Operation.EON_SHIFTED_REGISTER
                       : Aarch64DecodedInsn.Operation.EOR_SHIFTED_REGISTER;
      default -> invert ? Aarch64DecodedInsn.Operation.BICS_SHIFTED_REGISTER
                        : Aarch64DecodedInsn.Operation.ANDS_SHIFTED_REGISTER;
    };
  }

  private static Aarch64DecodedInsn.ShiftType shiftType( int encoded ) {
    return switch( encoded ) {
      case 0 -> Aarch64DecodedInsn.ShiftType.LSL;
      case 1 -> Aarch64DecodedInsn.ShiftType.LSR;
      case 2 -> Aarch64DecodedInsn.ShiftType.ASR;
      default -> Aarch64DecodedInsn.ShiftType.ROR;
    };
  }

  private static Aarch64DecodedInsn.AddressMode addressMode( int encoded ) {
    return switch( encoded ) {
      case 1 -> Aarch64DecodedInsn.AddressMode.POST_INDEX;
      case 2 -> Aarch64DecodedInsn.AddressMode.OFFSET;
      case 3 -> Aarch64DecodedInsn.AddressMode.PRE_INDEX;
      default -> Aarch64DecodedInsn.AddressMode.NONE;
    };
  }

  private static long decodeLogicalImmediate( int n, int immr, int imms, int width,
                                              int instruction ) {
    int selector = (n << 6) | ((~imms) & 0x3f);
    int len = 31 - Integer.numberOfLeadingZeros( selector );
    if( len < 1 ) return undefinedImmediate( instruction );
    int levels = (1 << len) - 1;
    int s = imms & levels;
    int r = immr & levels;
    if( s == levels ) return undefinedImmediate( instruction );
    int elementSize = 1 << len;
    long element = s == 63 ? -1L : (1L << (s + 1)) - 1L;
    long elementMask = elementSize == 64 ? -1L : (1L << elementSize) - 1L;
    element &= elementMask;
    if( r != 0 ) element = ((element >>> r) | (element << (elementSize - r))) & elementMask;
    long result = 0;
    for( int bit = 0; bit < width; bit += elementSize ) result |= element << bit;
    return width == 32 ? result & 0xffffffffL : result;
  }

  private static long expandFloatingImmediate( int imm8, int width ) {
    long sign = (imm8 >>> 7) & 1;
    long b = (imm8 >>> 6) & 1;
    long fraction = imm8 & 0x3f;
    if( width == 32 ) {
      long exponent = b == 0 ? 0x80L : 0x7fL;
      return (sign << 31) | (exponent << 23) | (fraction << 17);
    }
    long exponent = b == 0 ? 0x400L : 0x3ffL;
    return (sign << 63) | (exponent << 52) | (fraction << 46);
  }

  private static long signExtend( long value, int bits ) {
    long sign = 1L << (bits - 1);
    return (value ^ sign) - sign;
  }

  private static Aarch64DecodedInsn undefined( int instruction ) {
    throw new UnsupportedOperationException(
        "unsupported or unallocated AArch64 instruction 0x"
            + String.format( "%08x", instruction ) );
  }

  private static long undefinedImmediate( int instruction ) {
    undefined( instruction );
    return 0;
  }
}
