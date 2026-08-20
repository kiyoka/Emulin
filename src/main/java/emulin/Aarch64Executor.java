// ----------------------------------------
//  AArch64 integer instruction semantics (issue #951)
// ----------------------------------------
package emulin;

final class Aarch64Executor {
  long execute( Aarch64State state, Aarch64DecodedInsn instruction,
                SyscallAarch64 syscall, MemoryBackend memory ) {
    long nextPc = state.pc + 4;
    switch( instruction.operation ) {
      case MOVN, MOVZ, MOVK -> executeMoveWide( state, instruction );
      case ADR -> state.writeRegister(
          instruction.rd, state.pc + instruction.immediate, 64, false );
      case ADRP -> state.writeRegister(
          instruction.rd, (state.pc & ~0xfffL) + instruction.immediate, 64, false );

      case ADD_IMMEDIATE, ADDS_IMMEDIATE, SUB_IMMEDIATE, SUBS_IMMEDIATE ->
          executeAddSub( state, instruction, instruction.immediate, true );
      case ADD_SHIFTED_REGISTER, ADDS_SHIFTED_REGISTER,
           SUB_SHIFTED_REGISTER, SUBS_SHIFTED_REGISTER -> {
        long operand = shift( state.readRegister( instruction.rm, instruction.dataSize, false ),
                              instruction.shiftType, instruction.shiftAmount,
                              instruction.dataSize );
        executeAddSub( state, instruction, operand, false );
      }
      case ADD_EXTENDED_REGISTER, ADDS_EXTENDED_REGISTER,
           SUB_EXTENDED_REGISTER, SUBS_EXTENDED_REGISTER -> {
        long operand = extend( state, instruction.rm, instruction.extendType,
                               instruction.shiftAmount, instruction.dataSize );
        executeAddSub( state, instruction, operand, true );
      }

      case AND_IMMEDIATE, ORR_IMMEDIATE, EOR_IMMEDIATE, ANDS_IMMEDIATE ->
          executeLogical( state, instruction, instruction.immediate );
      case AND_SHIFTED_REGISTER, BIC_SHIFTED_REGISTER,
           ORR_SHIFTED_REGISTER, ORN_SHIFTED_REGISTER,
           EOR_SHIFTED_REGISTER, EON_SHIFTED_REGISTER,
           ANDS_SHIFTED_REGISTER, BICS_SHIFTED_REGISTER -> {
        long operand = shift( state.readRegister( instruction.rm, instruction.dataSize, false ),
                              instruction.shiftType, instruction.shiftAmount,
                              instruction.dataSize );
        if( isInvertedLogical( instruction.operation ) ) operand = ~operand;
        executeLogical( state, instruction, operand );
      }

      case SBFM, BFM, UBFM -> executeBitfield( state, instruction );
      case EXTR -> executeExtract( state, instruction );
      case MADD, MSUB -> executeMultiplyAddSub( state, instruction );

      case B -> { return state.pc + instruction.immediate; }
      case BL -> {
        state.writeX( 30, nextPc );
        return state.pc + instruction.immediate;
      }
      case B_COND -> {
        return conditionHolds( state, instruction.condition )
            ? state.pc + instruction.immediate : nextPc;
      }
      case CBZ, CBNZ -> {
        boolean zero = state.readRegister( instruction.rd, instruction.dataSize, false ) == 0;
        boolean take = instruction.operation == Aarch64DecodedInsn.Operation.CBZ ? zero : !zero;
        return take ? state.pc + instruction.immediate : nextPc;
      }
      case TBZ, TBNZ -> {
        boolean zero = (state.readX( instruction.rd ) & (1L << instruction.bitIndex)) == 0;
        boolean take = instruction.operation == Aarch64DecodedInsn.Operation.TBZ ? zero : !zero;
        return take ? state.pc + instruction.immediate : nextPc;
      }
      case BR -> { return state.readX( instruction.rn ); }
      case BLR -> {
        long target = state.readX( instruction.rn );
        state.writeX( 30, nextPc );
        return target;
      }
      case RET -> { return state.readX( instruction.rn ); }

      case STR, LDR -> executeSingleMemory( state, instruction, memory );
      case STP, LDP -> executePairMemory( state, instruction, memory );
      case LDR_LITERAL -> {
        requireMemory( memory, instruction );
        state.writeRegister( instruction.rd,
            load( memory, state.pc + instruction.immediate, instruction.accessSize ),
            instruction.dataSize, false );
      }

      case NOP -> { }
      case SVC -> {
        if( syscall == null ) throw new IllegalStateException( "SVC without SyscallAarch64" );
        long result = syscall.callAarch64(
            (int)state.readX( 8 ),
            state.readX( 0 ), state.readX( 1 ), state.readX( 2 ),
            state.readX( 3 ), state.readX( 4 ), state.readX( 5 ) );
        state.writeX( 0, result );
      }
      default -> throw new UnsupportedOperationException(
          "AArch64 execution semantics not implemented for " + instruction.operation );
    }
    return nextPc;
  }

  private static void executeMoveWide( Aarch64State state,
                                       Aarch64DecodedInsn instruction ) {
    long mask = widthMask( instruction.dataSize );
    long field = (instruction.immediate << instruction.shiftAmount) & mask;
    long result = switch( instruction.operation ) {
      case MOVN -> ~field & mask;
      case MOVZ -> field;
      case MOVK -> {
        long old = state.readRegister( instruction.rd, instruction.dataSize, false );
        long fieldMask = (0xffffL << instruction.shiftAmount) & mask;
        yield (old & ~fieldMask) | field;
      }
      default -> throw new AssertionError( instruction.operation );
    };
    state.writeRegister( instruction.rd, result, instruction.dataSize, false );
  }

  private static void executeAddSub( Aarch64State state,
                                     Aarch64DecodedInsn instruction,
                                     long operand2, boolean stackPointerForm ) {
    boolean subtract = switch( instruction.operation ) {
      case SUB_IMMEDIATE, SUBS_IMMEDIATE,
           SUB_SHIFTED_REGISTER, SUBS_SHIFTED_REGISTER,
           SUB_EXTENDED_REGISTER, SUBS_EXTENDED_REGISTER -> true;
      default -> false;
    };
    long operand1 = state.readRegister(
        instruction.rn, instruction.dataSize, stackPointerForm );
    AddResult result = subtract
        ? addWithCarry( operand1, ~operand2, 1, instruction.dataSize )
        : addWithCarry( operand1, operand2, 0, instruction.dataSize );
    state.writeRegister( instruction.rd, result.value, instruction.dataSize,
                         stackPointerForm && !instruction.setsFlags );
    if( instruction.setsFlags ) {
      state.setNzcv( result.negative, result.zero, result.carry, result.overflow );
    }
  }

  private static void executeLogical( Aarch64State state,
                                      Aarch64DecodedInsn instruction,
                                      long operand2 ) {
    long operand1 = state.readRegister( instruction.rn, instruction.dataSize, false );
    long result = switch( instruction.operation ) {
      case AND_IMMEDIATE, ANDS_IMMEDIATE,
           AND_SHIFTED_REGISTER, BIC_SHIFTED_REGISTER,
           ANDS_SHIFTED_REGISTER, BICS_SHIFTED_REGISTER -> operand1 & operand2;
      case ORR_IMMEDIATE, ORR_SHIFTED_REGISTER, ORN_SHIFTED_REGISTER -> operand1 | operand2;
      case EOR_IMMEDIATE, EOR_SHIFTED_REGISTER, EON_SHIFTED_REGISTER -> operand1 ^ operand2;
      default -> throw new AssertionError( instruction.operation );
    };
    result &= widthMask( instruction.dataSize );
    state.writeRegister( instruction.rd, result, instruction.dataSize, false );
    if( instruction.setsFlags ) {
      state.setNzcv( (result & signBit( instruction.dataSize )) != 0,
                     result == 0, false, false );
    }
  }

  private static boolean isInvertedLogical( Aarch64DecodedInsn.Operation operation ) {
    return operation == Aarch64DecodedInsn.Operation.BIC_SHIFTED_REGISTER
        || operation == Aarch64DecodedInsn.Operation.ORN_SHIFTED_REGISTER
        || operation == Aarch64DecodedInsn.Operation.EON_SHIFTED_REGISTER
        || operation == Aarch64DecodedInsn.Operation.BICS_SHIFTED_REGISTER;
  }

  private static void executeBitfield( Aarch64State state,
                                       Aarch64DecodedInsn instruction ) {
    int width = instruction.dataSize;
    long source = state.readRegister( instruction.rn, width, false );
    BitMasks masks = decodeBitMasks( width, instruction.immr, instruction.imms );
    long rotated = rotateRightElement( source, instruction.immr, width );
    long result = switch( instruction.operation ) {
      case SBFM -> {
        long signFill = ((source >>> instruction.imms) & 1) != 0 ? -1L : 0;
        yield (signFill & ~masks.tmask) | (rotated & masks.wmask & masks.tmask);
      }
      case BFM -> {
        long destination = state.readRegister( instruction.rd, width, false );
        long bottom = (destination & ~masks.wmask) | (rotated & masks.wmask);
        yield (destination & ~masks.tmask) | (bottom & masks.tmask);
      }
      case UBFM -> rotated & masks.wmask & masks.tmask;
      default -> throw new AssertionError( instruction.operation );
    };
    state.writeRegister( instruction.rd, result & widthMask( width ), width, false );
  }

  private static void executeExtract( Aarch64State state,
                                      Aarch64DecodedInsn instruction ) {
    int width = instruction.dataSize;
    int lsb = (int)instruction.immediate;
    long high = state.readRegister( instruction.rn, width, false );
    long low = state.readRegister( instruction.rm, width, false );
    long result = lsb == 0 ? low
        : (low >>> lsb) | (high << (width - lsb));
    state.writeRegister( instruction.rd, result & widthMask( width ), width, false );
  }

  private static void executeMultiplyAddSub( Aarch64State state,
                                              Aarch64DecodedInsn instruction ) {
    int width = instruction.dataSize;
    long left = state.readRegister( instruction.rn, width, false );
    long right = state.readRegister( instruction.rm, width, false );
    long accumulator = state.readRegister( instruction.ra, width, false );
    long product = left * right;
    long result = instruction.operation == Aarch64DecodedInsn.Operation.MADD
        ? accumulator + product : accumulator - product;
    state.writeRegister( instruction.rd, result & widthMask( width ), width, false );
  }

  private static void executeSingleMemory( Aarch64State state,
                                           Aarch64DecodedInsn instruction,
                                           MemoryBackend memory ) {
    requireMemory( memory, instruction );
    long base = state.readRegister( instruction.rn, 64, true );
    long offset = instruction.rm >= 0
        ? extend( state, instruction.rm, instruction.extendType,
                  instruction.shiftAmount, 64 )
        : instruction.immediate;
    long address;
    if( instruction.addressMode == Aarch64DecodedInsn.AddressMode.PRE_INDEX ) {
      base += offset;
      state.writeRegister( instruction.rn, base, 64, true );
      address = base;
    } else {
      address = base + (instruction.addressMode == Aarch64DecodedInsn.AddressMode.OFFSET
                        ? offset : 0);
    }

    if( instruction.operation == Aarch64DecodedInsn.Operation.LDR ) {
      state.writeRegister( instruction.rd, load( memory, address, instruction.accessSize ),
                           instruction.dataSize, false );
    } else {
      store( memory, address,
             state.readRegister( instruction.rd, instruction.dataSize, false ),
             instruction.accessSize );
    }

    if( instruction.addressMode == Aarch64DecodedInsn.AddressMode.POST_INDEX ) {
      state.writeRegister( instruction.rn, base + offset, 64, true );
    }
  }

  private static void executePairMemory( Aarch64State state,
                                         Aarch64DecodedInsn instruction,
                                         MemoryBackend memory ) {
    requireMemory( memory, instruction );
    long base = state.readRegister( instruction.rn, 64, true );
    long address;
    if( instruction.addressMode == Aarch64DecodedInsn.AddressMode.PRE_INDEX ) {
      base += instruction.immediate;
      state.writeRegister( instruction.rn, base, 64, true );
      address = base;
    } else {
      address = base + (instruction.addressMode == Aarch64DecodedInsn.AddressMode.OFFSET
                        ? instruction.immediate : 0);
    }

    if( instruction.operation == Aarch64DecodedInsn.Operation.LDP ) {
      state.writeRegister( instruction.rd, load( memory, address, instruction.accessSize ),
                           instruction.dataSize, false );
      state.writeRegister( instruction.rt2,
          load( memory, address + instruction.accessSize, instruction.accessSize ),
          instruction.dataSize, false );
    } else {
      store( memory, address,
             state.readRegister( instruction.rd, instruction.dataSize, false ),
             instruction.accessSize );
      store( memory, address + instruction.accessSize,
             state.readRegister( instruction.rt2, instruction.dataSize, false ),
             instruction.accessSize );
    }

    if( instruction.addressMode == Aarch64DecodedInsn.AddressMode.POST_INDEX ) {
      state.writeRegister( instruction.rn, base + instruction.immediate, 64, true );
    }
  }

  private static long load( MemoryBackend memory, long address, int bytes ) {
    return switch( bytes ) {
      case 1 -> memory.load8( address ) & 0xffL;
      case 2 -> memory.load16( address ) & 0xffffL;
      case 4 -> memory.load32( address ) & 0xffffffffL;
      case 8 -> memory.load64( address );
      default -> throw new AssertionError( "invalid AArch64 access size " + bytes );
    };
  }

  private static void store( MemoryBackend memory, long address, long value, int bytes ) {
    switch( bytes ) {
      case 1 -> memory.store8( address, (int)value );
      case 2 -> memory.store16( address, (short)value );
      case 4 -> memory.store32( address, (int)value );
      case 8 -> memory.store64( address, value );
      default -> throw new AssertionError( "invalid AArch64 access size " + bytes );
    }
  }

  private static long extend( Aarch64State state, int register,
                              Aarch64DecodedInsn.ExtendType type,
                              int shift, int resultWidth ) {
    long raw = state.readX( register );
    long value = switch( type ) {
      case UXTB -> raw & 0xffL;
      case UXTH -> raw & 0xffffL;
      case UXTW -> raw & 0xffffffffL;
      case UXTX, NONE -> raw;
      case SXTB -> (byte)raw;
      case SXTH -> (short)raw;
      case SXTW -> (int)raw;
      case SXTX -> raw;
    };
    return (value << shift) & widthMask( resultWidth );
  }

  private static long shift( long value, Aarch64DecodedInsn.ShiftType type,
                             int amount, int width ) {
    long mask = widthMask( width );
    value &= mask;
    if( amount == 0 || type == Aarch64DecodedInsn.ShiftType.NONE ) return value;
    return switch( type ) {
      case LSL -> (value << amount) & mask;
      case LSR -> value >>> amount;
      case ASR -> width == 32 ? ((int)value >> amount) & 0xffffffffL : value >> amount;
      case ROR -> width == 32
          ? Integer.toUnsignedLong( Integer.rotateRight( (int)value, amount ) )
          : Long.rotateRight( value, amount );
      case NONE -> value;
    };
  }

  // ARM ARM DecodeBitMasks for bitfield instructions (immediate=false).
  private static BitMasks decodeBitMasks( int width, int immr, int imms ) {
    int n = width == 64 ? 1 : 0;
    int lengthSource = (n << 6) | ((~imms) & 0x3f);
    int length = 31 - Integer.numberOfLeadingZeros( lengthSource );
    int levels = (1 << length) - 1;
    int s = imms & levels;
    int r = immr & levels;
    int difference = (s - r) & levels;
    int elementSize = 1 << length;
    long writeElement = rotateRightElement( ones( s + 1 ), r, elementSize );
    long testElement = ones( difference + 1 );
    return new BitMasks( replicate( writeElement, elementSize, width ),
                         replicate( testElement, elementSize, width ) );
  }

  private static long rotateRightElement( long value, int amount, int width ) {
    long mask = widthMask( width );
    value &= mask;
    amount &= width - 1;
    if( amount == 0 ) return value;
    if( width == 64 ) return Long.rotateRight( value, amount );
    return ((value >>> amount) | (value << (width - amount))) & mask;
  }

  private static long replicate( long element, int elementWidth, int width ) {
    long result = 0;
    element &= ones( elementWidth );
    for( int bit = 0; bit < width; bit += elementWidth ) result |= element << bit;
    return result & widthMask( width );
  }

  private static long ones( int bits ) {
    return bits >= 64 ? -1L : (1L << bits) - 1;
  }

  private static AddResult addWithCarry( long left, long right, int carryIn, int width ) {
    long mask = widthMask( width );
    left &= mask;
    right &= mask;
    long result;
    boolean carry;
    if( width == 32 ) {
      long sum = left + right + carryIn;
      result = sum & mask;
      carry = (sum >>> 32) != 0;
    } else {
      long partial = left + right;
      boolean carry1 = Long.compareUnsigned( partial, left ) < 0;
      result = partial + carryIn;
      boolean carry2 = carryIn != 0 && Long.compareUnsigned( result, partial ) < 0;
      carry = carry1 || carry2;
    }
    long sign = signBit( width );
    boolean overflow = ((~(left ^ right) & (left ^ result)) & sign) != 0;
    return new AddResult( result, (result & sign) != 0, result == 0, carry, overflow );
  }

  private static boolean conditionHolds( Aarch64State state, int condition ) {
    boolean n = state.negative(), z = state.zero();
    boolean c = state.carry(), v = state.overflow();
    return switch( condition & 15 ) {
      case 0 -> z;                 // EQ
      case 1 -> !z;                // NE
      case 2 -> c;                 // CS/HS
      case 3 -> !c;                // CC/LO
      case 4 -> n;                 // MI
      case 5 -> !n;                // PL
      case 6 -> v;                 // VS
      case 7 -> !v;                // VC
      case 8 -> c && !z;           // HI
      case 9 -> !c || z;           // LS
      case 10 -> n == v;           // GE
      case 11 -> n != v;           // LT
      case 12 -> !z && n == v;     // GT
      case 13 -> z || n != v;      // LE
      default -> true;             // AL and NV
    };
  }

  private static long widthMask( int width ) {
    return width == 32 ? 0xffffffffL : -1L;
  }

  private static long signBit( int width ) {
    return width == 32 ? 0x80000000L : Long.MIN_VALUE;
  }

  private static void requireMemory( MemoryBackend memory,
                                     Aarch64DecodedInsn instruction ) {
    if( memory == null ) {
      throw new IllegalStateException( instruction.operation + " without guest memory" );
    }
  }

  private record AddResult( long value, boolean negative, boolean zero,
                            boolean carry, boolean overflow ) { }
  private record BitMasks( long wmask, long tmask ) { }
}
