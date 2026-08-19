// ----------------------------------------
//  Fixed-width AArch64 decoder (issue #951)
// ----------------------------------------
package emulin;

final class Aarch64Decoder {
  Aarch64DecodedInsn decode( int instruction, Aarch64DecodedInsn out ) {
    out.reset( instruction );

    // Architectural hints used by compiler padding.
    if( instruction == 0xd503201f ) {
      out.operation = Aarch64DecodedInsn.Operation.NOP;
      return out;
    }

    // Supervisor call: SVC #imm16.
    if( (instruction & 0xffe0001f) == 0xd4000001 ) {
      out.operation = Aarch64DecodedInsn.Operation.SVC;
      out.immediate = (instruction >>> 5) & 0xffffL;
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

    // Load/store pair, integer W/X forms. Address mode 0 is LDNP/STNP and is
    // kept outside this first executable subset.
    if( (instruction & 0x3e000000) == 0x28000000 ) {
      int opc = (instruction >>> 30) & 3;
      int mode = (instruction >>> 23) & 3;
      if( (opc != 0 && opc != 2) || mode == 0 ) return undefined( instruction );
      boolean load = ((instruction >>> 22) & 1) != 0;
      out.operation = load ? Aarch64DecodedInsn.Operation.LDP
                           : Aarch64DecodedInsn.Operation.STP;
      out.dataSize = opc == 0 ? 32 : 64;
      out.accessSize = out.dataSize / 8;
      out.immediate = signExtend( (instruction >>> 15) & 0x7fL, 7 ) * out.accessSize;
      out.rt2 = (instruction >>> 10) & 31;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = addressMode( mode );
      return out;
    }

    // Load register literal, integer W/X forms.
    if( (instruction & 0x3b000000) == 0x18000000 ) {
      int opc = (instruction >>> 30) & 3;
      if( opc > 1 ) return undefined( instruction );
      out.operation = Aarch64DecodedInsn.Operation.LDR_LITERAL;
      out.dataSize = opc == 0 ? 32 : 64;
      out.accessSize = out.dataSize / 8;
      out.rd = instruction & 31;
      out.immediate = signExtend( (instruction >>> 5) & 0x7ffffL, 19 ) << 2;
      out.addressMode = Aarch64DecodedInsn.AddressMode.LITERAL;
      return out;
    }

    // Load/store unsigned immediate, scaled by access size.
    if( (instruction & 0x3b000000) == 0x39000000 ) {
      int opc = (instruction >>> 22) & 3;
      if( opc > 1 ) return undefined( instruction );
      int size = (instruction >>> 30) & 3;
      out.operation = opc == 0 ? Aarch64DecodedInsn.Operation.STR
                               : Aarch64DecodedInsn.Operation.LDR;
      out.accessSize = 1 << size;
      out.dataSize = size == 3 ? 64 : 32;
      out.immediate = ((instruction >>> 10) & 0xfffL) * out.accessSize;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = Aarch64DecodedInsn.AddressMode.OFFSET;
      return out;
    }

    // Load/store register offset. The option field selects Wm/Xm extension;
    // S scales the extended offset by log2(access size).
    if( (instruction & 0x3b200c00) == 0x38200800 ) {
      int opc = (instruction >>> 22) & 3;
      if( opc > 1 ) return undefined( instruction );
      int size = (instruction >>> 30) & 3;
      out.operation = opc == 0 ? Aarch64DecodedInsn.Operation.STR
                               : Aarch64DecodedInsn.Operation.LDR;
      out.accessSize = 1 << size;
      out.dataSize = size == 3 ? 64 : 32;
      out.rm = (instruction >>> 16) & 31;
      out.extendType = Aarch64DecodedInsn.ExtendType.values()[ ((instruction >>> 13) & 7) + 1 ];
      out.shiftAmount = ((instruction >>> 12) & 1) == 0 ? 0 : size;
      out.rn = (instruction >>> 5) & 31;
      out.rd = instruction & 31;
      out.addressMode = Aarch64DecodedInsn.AddressMode.OFFSET;
      return out;
    }

    // Load/store signed imm9: unscaled offset, pre-index, and post-index.
    if( (instruction & 0x3b200000) == 0x38000000 ) {
      int opc = (instruction >>> 22) & 3;
      int mode = (instruction >>> 10) & 3;
      if( opc > 1 || mode == 2 ) return undefined( instruction );
      int size = (instruction >>> 30) & 3;
      out.operation = opc == 0 ? Aarch64DecodedInsn.Operation.STR
                               : Aarch64DecodedInsn.Operation.LDR;
      out.accessSize = 1 << size;
      out.dataSize = size == 3 ? 64 : 32;
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
