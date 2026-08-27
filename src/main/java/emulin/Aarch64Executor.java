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
      case UMADDL, UMSUBL -> executeUnsignedLongMultiplyAddSub( state, instruction );
      case SMADDL, SMSUBL -> executeSignedLongMultiplyAddSub( state, instruction );
      case UMULH -> {
        long left = state.readX( instruction.rn );
        long right = state.readX( instruction.rm );
        long high = Math.multiplyHigh( left, right );
        if( left < 0 ) high += right;
        if( right < 0 ) high += left;
        state.writeX( instruction.rd, high );
      }
      case SMULH -> state.writeX( instruction.rd,
          Math.multiplyHigh( state.readX( instruction.rn ),
                             state.readX( instruction.rm ) ) );
      case UDIV, SDIV -> executeDivision( state, instruction );
      case RBIT, REV16, REV32, REV64 -> executeByteReverse( state, instruction );
      case CLZ -> {
        long value = state.readRegister( instruction.rn, instruction.dataSize, false );
        int count = instruction.dataSize == 32
            ? Integer.numberOfLeadingZeros( (int)value )
            : Long.numberOfLeadingZeros( value );
        state.writeRegister( instruction.rd, count, instruction.dataSize, false );
      }
      case CLS -> {
        long value = state.readRegister( instruction.rn, instruction.dataSize, false );
        int count = instruction.dataSize == 32
            ? Integer.numberOfLeadingZeros( (int)value < 0 ? ~(int)value : (int)value ) - 1
            : Long.numberOfLeadingZeros( value < 0 ? ~value : value ) - 1;
        state.writeRegister( instruction.rd, count, instruction.dataSize, false );
      }
      case CRC32 -> executeCrc32( state, instruction );
      case LSL_VARIABLE, LSR_VARIABLE, ASR_VARIABLE, ROR_VARIABLE ->
          executeVariableShift( state, instruction );
      case CSEL, CSINC, CSINV, CSNEG -> executeConditionalSelect( state, instruction );
      case CCMP_IMMEDIATE, CCMP_REGISTER, CCMN_IMMEDIATE, CCMN_REGISTER ->
          executeConditionalCompare( state, instruction );

      case DUP_VECTOR_BYTE -> {
        long value = state.readRegister( instruction.rn, 32, false ) & 0xffL;
        long repeated = value * 0x0101010101010101L;
        state.writeV128( instruction.rd, repeated, repeated );
      }
      case MOVI_VECTOR -> {
        if( instruction.dataSize == 128 ) {
          state.writeV128( instruction.rd, instruction.immediate, instruction.immediate );
        } else {
          state.writeV64( instruction.rd, instruction.immediate );
        }
      }
      case LD1_VECTOR_16B -> {
        requireMemory( memory, instruction );
        long address = state.readRegister( instruction.rn, 64, true );
        state.writeV128( instruction.rd,
            memory.load64( address ), memory.load64( address + 8 ) );
        if( instruction.addressMode == Aarch64DecodedInsn.AddressMode.POST_INDEX ) {
          state.writeRegister( instruction.rn, address + instruction.immediate, 64, true );
        }
      }
      case LD1_VECTOR_2_16B -> {
        requireMemory( memory, instruction );
        long address = state.readRegister( instruction.rn, 64, true );
        state.writeV128( instruction.rd,
            memory.load64( address ), memory.load64( address + 8 ) );
        state.writeV128( instruction.rt2,
            memory.load64( address + 16 ), memory.load64( address + 24 ) );
        if( instruction.addressMode == Aarch64DecodedInsn.AddressMode.POST_INDEX ) {
          state.writeRegister( instruction.rn, address + instruction.immediate, 64, true );
        }
      }
      case LD1_VECTOR_D_LANE -> {
        requireMemory( memory, instruction );
        long value = memory.load64(
            state.readRegister( instruction.rn, 64, true ) );
        if( instruction.bitIndex == 0 ) {
          state.writeV128( instruction.rd, value,
              state.readV64( instruction.rd, true ) );
        } else {
          state.writeV128( instruction.rd,
              state.readV64( instruction.rd, false ), value );
        }
      }
      case CMEQ_VECTOR_BYTE -> {
        long low = compareEqualBytes( state.readV64( instruction.rn, false ),
                                      state.readV64( instruction.rm, false ) );
        if( instruction.dataSize == 128 ) {
          state.writeV128( instruction.rd, low,
              compareEqualBytes( state.readV64( instruction.rn, true ),
                                 state.readV64( instruction.rm, true ) ) );
        } else {
          state.writeV64( instruction.rd, low );
        }
      }
      case CMEQ_VECTOR_BYTE_ZERO -> {
        long low = compareEqualBytes( state.readV64( instruction.rn, false ), 0 );
        if( instruction.dataSize == 128 ) {
          state.writeV128( instruction.rd, low,
              compareEqualBytes( state.readV64( instruction.rn, true ), 0 ) );
        } else {
          state.writeV64( instruction.rd, low );
        }
      }
      case AND_VECTOR -> {
        long low = state.readV64( instruction.rn, false )
            & state.readV64( instruction.rm, false );
        if( instruction.dataSize == 128 ) {
          state.writeV128( instruction.rd, low,
              state.readV64( instruction.rn, true )
                  & state.readV64( instruction.rm, true ) );
        } else {
          state.writeV64( instruction.rd, low );
        }
      }
      case BIT_VECTOR -> {
        long maskLow = state.readV64( instruction.rm, false );
        long maskHigh = state.readV64( instruction.rm, true );
        state.writeV128( instruction.rd,
            (state.readV64( instruction.rd, false ) & ~maskLow)
                | (state.readV64( instruction.rn, false ) & maskLow),
            (state.readV64( instruction.rd, true ) & ~maskHigh)
                | (state.readV64( instruction.rn, true ) & maskHigh) );
      }
      case EOR_VECTOR -> {
        long low = state.readV64( instruction.rn, false )
            ^ state.readV64( instruction.rm, false );
        if( instruction.dataSize == 128 ) {
          state.writeV128( instruction.rd, low,
              state.readV64( instruction.rn, true )
                  ^ state.readV64( instruction.rm, true ) );
        } else {
          state.writeV64( instruction.rd, low );
        }
      }
      case BIC_VECTOR_IMMEDIATE_HALFWORD -> state.writeV128( instruction.rd,
          state.readV64( instruction.rd, false ) & ~instruction.immediate,
          state.readV64( instruction.rd, true ) & ~instruction.immediate );
      case UMAXP_VECTOR_BYTE -> state.writeV128( instruction.rd,
          pairwiseUnsignedMaxBytes( state, instruction.rn ),
          pairwiseUnsignedMaxBytes( state, instruction.rm ) );
      case UMINP_VECTOR_BYTE -> state.writeV128( instruction.rd,
          pairwiseUnsignedMinBytes( state, instruction.rn ),
          pairwiseUnsignedMinBytes( state, instruction.rm ) );
      case ADDP_VECTOR_BYTE -> state.writeV128( instruction.rd,
          pairwiseAddBytes( state, instruction.rn ),
          pairwiseAddBytes( state, instruction.rm ) );
      case EXT_VECTOR_16B -> {
        int index = (int)instruction.immediate;
        long low = extractVectorWord( state, instruction.rn, instruction.rm, index );
        long high = extractVectorWord( state, instruction.rn, instruction.rm, index + 8 );
        state.writeV128( instruction.rd, low, high );
      }
      case CMHS_VECTOR_BYTE -> state.writeV128( instruction.rd,
          compareUnsignedHigherSameBytes(
              state.readV64( instruction.rn, false ),
              state.readV64( instruction.rm, false ) ),
          compareUnsignedHigherSameBytes(
              state.readV64( instruction.rn, true ),
              state.readV64( instruction.rm, true ) ) );
      case SHRN_VECTOR_8B -> executeShrn8B( state, instruction );
      case ADDHN_VECTOR_8B -> executeAddhn8B( state, instruction );
      case FMOV_GENERAL_FROM_D ->
          state.writeX( instruction.rd, state.readV64( instruction.rn, false ) );
      case FMOV_VECTOR_64 ->
          state.writeV64( instruction.rd, state.readV64( instruction.rn, false ) );
      case MOVE_GENERAL_FROM_VECTOR_LANE -> {
        int byteIndex = instruction.bitIndex * instruction.accessSize;
        long word = state.readV64( instruction.rn, byteIndex >= 8 );
        int shift = (byteIndex & 7) * 8;
        long mask = (1L << (instruction.accessSize * 8)) - 1;
        state.writeRegister( instruction.rd, (word >>> shift) & mask, 32, false );
      }
      case MRS_DCZID_EL0 -> state.writeX( instruction.rd, 0x10 ); // DZP=1
      case MRS_TPIDR_EL0 -> state.writeX( instruction.rd, state.tpidrEl0 );
      case MSR_TPIDR_EL0 -> state.tpidrEl0 = state.readX( instruction.rn );
      case STR_VECTOR_128, LDR_VECTOR_128 -> {
        requireMemory( memory, instruction );
        long offset = instruction.rm >= 0
            ? extend( state, instruction.rm, instruction.extendType,
                      instruction.shiftAmount, 64 )
            : instruction.immediate;
        long base = state.readRegister( instruction.rn, 64, true );
        long address;
        if( instruction.addressMode == Aarch64DecodedInsn.AddressMode.PRE_INDEX ) {
          base += offset;
          state.writeRegister( instruction.rn, base, 64, true );
          address = base;
        } else {
          address = base + (instruction.addressMode == Aarch64DecodedInsn.AddressMode.OFFSET
                            ? offset : 0);
        }
        if( instruction.operation == Aarch64DecodedInsn.Operation.LDR_VECTOR_128 ) {
          state.writeV128( instruction.rd,
              memory.load64( address ), memory.load64( address + 8 ) );
        } else {
          memory.store64( address, state.readV64( instruction.rd, false ) );
          memory.store64( address + 8, state.readV64( instruction.rd, true ) );
        }
        if( instruction.addressMode == Aarch64DecodedInsn.AddressMode.POST_INDEX ) {
          state.writeRegister( instruction.rn, base + offset, 64, true );
        }
      }
      case STR_VECTOR_32, LDR_VECTOR_32, STR_VECTOR_64, LDR_VECTOR_64 -> {
        requireMemory( memory, instruction );
        long offset = instruction.rm >= 0
            ? extend( state, instruction.rm, instruction.extendType,
                      instruction.shiftAmount, 64 )
            : instruction.immediate;
        long base = state.readRegister( instruction.rn, 64, true );
        long address;
        if( instruction.addressMode == Aarch64DecodedInsn.AddressMode.PRE_INDEX ) {
          base += offset;
          state.writeRegister( instruction.rn, base, 64, true );
          address = base;
        } else {
          address = base + (instruction.addressMode == Aarch64DecodedInsn.AddressMode.OFFSET
                            ? offset : 0);
        }
        boolean load = instruction.operation == Aarch64DecodedInsn.Operation.LDR_VECTOR_32
            || instruction.operation == Aarch64DecodedInsn.Operation.LDR_VECTOR_64;
        if( load && instruction.accessSize == 4 ) {
          state.writeV64( instruction.rd,
              Integer.toUnsignedLong( memory.load32( address ) ) );
        } else if( load ) {
          state.writeV64( instruction.rd, memory.load64( address ) );
        } else if( instruction.accessSize == 4 ) {
          memory.store32( address, (int)state.readV64( instruction.rd, false ) );
        } else {
          memory.store64( address, state.readV64( instruction.rd, false ) );
        }
        if( instruction.addressMode == Aarch64DecodedInsn.AddressMode.POST_INDEX ) {
          state.writeRegister( instruction.rn, base + offset, 64, true );
        }
      }
      case STP_VECTOR_64, LDP_VECTOR_64, STP_VECTOR_128, LDP_VECTOR_128 ->
          executeVectorPairMemory( state, instruction, memory );

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

      case STR, LDR, LDR_SIGNED -> executeSingleMemory( state, instruction, memory );
      case STP, LDP, LDP_SIGNED -> executePairMemory( state, instruction, memory );
      case LOAD_EXCLUSIVE -> executeLoadExclusive( state, instruction, memory );
      case STORE_EXCLUSIVE -> executeStoreExclusive( state, instruction, memory );
      case LOAD_ACQUIRE -> executeLoadAcquire( state, instruction, memory );
      case STORE_RELEASE -> executeStoreRelease( state, instruction, memory );
      case LDR_LITERAL -> {
        requireMemory( memory, instruction );
        state.writeRegister( instruction.rd,
            load( memory, state.pc + instruction.immediate, instruction.accessSize ),
            instruction.dataSize, false );
      }
      case LDR_SIGNED_LITERAL -> {
        requireMemory( memory, instruction );
        long value = load( memory, state.pc + instruction.immediate, instruction.accessSize );
        state.writeX( instruction.rd, (long)(int)value );
      }

      case NOP -> { }
      case MEMORY_BARRIER -> java.lang.invoke.VarHandle.fullFence();
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

  private static void executeUnsignedLongMultiplyAddSub(
      Aarch64State state, Aarch64DecodedInsn instruction ) {
    long left = state.readRegister( instruction.rn, 32, false );
    long right = state.readRegister( instruction.rm, 32, false );
    long accumulator = state.readX( instruction.ra );
    long product = left * right;
    state.writeX( instruction.rd,
        instruction.operation == Aarch64DecodedInsn.Operation.UMADDL
            ? accumulator + product : accumulator - product );
  }

  private static void executeSignedLongMultiplyAddSub(
      Aarch64State state, Aarch64DecodedInsn instruction ) {
    long left = (int)state.readRegister( instruction.rn, 32, false );
    long right = (int)state.readRegister( instruction.rm, 32, false );
    long accumulator = state.readX( instruction.ra );
    long product = left * right;
    state.writeX( instruction.rd,
        instruction.operation == Aarch64DecodedInsn.Operation.SMADDL
            ? accumulator + product : accumulator - product );
  }

  private static void executeCrc32( Aarch64State state,
                                    Aarch64DecodedInsn instruction ) {
    int crc = (int)state.readRegister( instruction.rn, 32, false );
    long value = state.readRegister( instruction.rm,
        instruction.accessSize == 8 ? 64 : 32, false );
    int polynomial = instruction.immediate == 0 ? 0xedb88320 : 0x82f63b78;
    for( int byteIndex = 0; byteIndex < instruction.accessSize; byteIndex++ ) {
      crc ^= (int)value & 0xff;
      value >>>= 8;
      for( int bit = 0; bit < 8; bit++ ) {
        crc = (crc >>> 1) ^ ((crc & 1) == 0 ? 0 : polynomial);
      }
    }
    state.writeRegister( instruction.rd, Integer.toUnsignedLong( crc ), 32, false );
  }

  private static void executeVariableShift( Aarch64State state,
                                            Aarch64DecodedInsn instruction ) {
    int width = instruction.dataSize;
    long value = state.readRegister( instruction.rn, width, false );
    int amount = (int)state.readRegister( instruction.rm, width, false ) & (width - 1);
    long result = switch( instruction.operation ) {
      case LSL_VARIABLE -> value << amount;
      case LSR_VARIABLE -> value >>> amount;
      case ASR_VARIABLE -> width == 32 ? (int)value >> amount : value >> amount;
      case ROR_VARIABLE -> width == 32
          ? Integer.toUnsignedLong( Integer.rotateRight( (int)value, amount ) )
          : Long.rotateRight( value, amount );
      default -> throw new AssertionError( instruction.operation );
    };
    state.writeRegister( instruction.rd, result & widthMask( width ), width, false );
  }

  private static void executeDivision( Aarch64State state,
                                       Aarch64DecodedInsn instruction ) {
    int width = instruction.dataSize;
    long dividend = state.readRegister( instruction.rn, width, false );
    long divisor = state.readRegister( instruction.rm, width, false );
    long result;
    if( divisor == 0 ) {
      result = 0;
    } else if( instruction.operation == Aarch64DecodedInsn.Operation.UDIV ) {
      result = width == 32
          ? Integer.toUnsignedLong( Integer.divideUnsigned( (int)dividend, (int)divisor ) )
          : Long.divideUnsigned( dividend, divisor );
    } else {
      result = width == 32 ? (int)dividend / (int)divisor : dividend / divisor;
    }
    state.writeRegister( instruction.rd, result, width, false );
  }

  private static void executeByteReverse( Aarch64State state,
                                          Aarch64DecodedInsn instruction ) {
    int width = instruction.dataSize;
    long value = state.readRegister( instruction.rn, width, false );
    long result = switch( instruction.operation ) {
      case RBIT -> width == 32
          ? Integer.toUnsignedLong( Integer.reverse( (int)value ) )
          : Long.reverse( value );
      case REV16 -> ((value & 0x00ff00ff00ff00ffL) << 8)
          | ((value & 0xff00ff00ff00ff00L) >>> 8);
      case REV32 -> Integer.toUnsignedLong( Integer.reverseBytes( (int)value ) )
          | (width == 64
              ? (long)Integer.reverseBytes( (int)(value >>> 32) ) << 32 : 0);
      case REV64 -> Long.reverseBytes( value );
      default -> throw new AssertionError( instruction.operation );
    };
    state.writeRegister( instruction.rd, result, width, false );
  }

  private static void executeConditionalSelect( Aarch64State state,
                                                Aarch64DecodedInsn instruction ) {
    int width = instruction.dataSize;
    long result;
    if( conditionHolds( state, instruction.condition ) ) {
      result = state.readRegister( instruction.rn, width, false );
    } else {
      long alternative = state.readRegister( instruction.rm, width, false );
      result = switch( instruction.operation ) {
        case CSEL -> alternative;
        case CSINC -> alternative + 1;
        case CSINV -> ~alternative;
        case CSNEG -> -alternative;
        default -> throw new AssertionError( instruction.operation );
      };
    }
    state.writeRegister( instruction.rd, result & widthMask( width ), width, false );
  }

  private static void executeConditionalCompare( Aarch64State state,
                                                 Aarch64DecodedInsn instruction ) {
    if( !conditionHolds( state, instruction.condition ) ) {
      int nzcv = instruction.immr;
      state.setNzcv( (nzcv & 8) != 0, (nzcv & 4) != 0,
                     (nzcv & 2) != 0, (nzcv & 1) != 0 );
      return;
    }

    int width = instruction.dataSize;
    long left = state.readRegister( instruction.rn, width, false );
    boolean immediate = instruction.operation == Aarch64DecodedInsn.Operation.CCMP_IMMEDIATE
        || instruction.operation == Aarch64DecodedInsn.Operation.CCMN_IMMEDIATE;
    boolean subtract = instruction.operation == Aarch64DecodedInsn.Operation.CCMP_IMMEDIATE
        || instruction.operation == Aarch64DecodedInsn.Operation.CCMP_REGISTER;
    long right = immediate ? instruction.immediate
        : state.readRegister( instruction.rm, width, false );
    AddResult result = subtract
        ? addWithCarry( left, ~right, 1, width )
        : addWithCarry( left, right, 0, width );
    state.setNzcv( result.negative, result.zero, result.carry, result.overflow );
  }

  private static long compareEqualBytes( long left, long right ) {
    long result = 0;
    for( int lane = 0; lane < 8; lane++ ) {
      int shift = lane * 8;
      if( ((left >>> shift) & 0xffL) == ((right >>> shift) & 0xffL) ) {
        result |= 0xffL << shift;
      }
    }
    return result;
  }

  private static long compareUnsignedHigherSameBytes( long left, long right ) {
    long result = 0;
    for( int lane = 0; lane < 8; lane++ ) {
      int shift = lane * 8;
      if( ((left >>> shift) & 0xffL) >= ((right >>> shift) & 0xffL) ) {
        result |= 0xffL << shift;
      }
    }
    return result;
  }

  private static long pairwiseUnsignedMaxBytes( Aarch64State state, int register ) {
    long low = state.readV64( register, false );
    long high = state.readV64( register, true );
    long result = 0;
    for( int pair = 0; pair < 8; pair++ ) {
      int firstLane = pair * 2;
      int secondLane = firstLane + 1;
      int first = vectorByte( low, high, firstLane );
      int second = vectorByte( low, high, secondLane );
      result |= (long)Math.max( first, second ) << (pair * 8);
    }
    return result;
  }

  private static long pairwiseUnsignedMinBytes( Aarch64State state, int register ) {
    long low = state.readV64( register, false );
    long high = state.readV64( register, true );
    long result = 0;
    for( int pair = 0; pair < 8; pair++ ) {
      int firstLane = pair * 2;
      int secondLane = firstLane + 1;
      int first = vectorByte( low, high, firstLane );
      int second = vectorByte( low, high, secondLane );
      result |= (long)Math.min( first, second ) << (pair * 8);
    }
    return result;
  }

  private static long pairwiseAddBytes( Aarch64State state, int register ) {
    long low = state.readV64( register, false );
    long high = state.readV64( register, true );
    long result = 0;
    for( int pair = 0; pair < 8; pair++ ) {
      int firstLane = pair * 2;
      int sum = vectorByte( low, high, firstLane )
          + vectorByte( low, high, firstLane + 1 );
      result |= (long)(sum & 0xff) << (pair * 8);
    }
    return result;
  }

  private static int vectorByte( long low, long high, int lane ) {
    return (int)((lane < 8 ? low >>> (lane * 8)
                           : high >>> ((lane - 8) * 8)) & 0xffL);
  }

  private static long extractVectorWord( Aarch64State state, int rn, int rm,
                                         int startByte ) {
    int word = startByte >>> 3;
    int shift = (startByte & 7) * 8;
    long current = vectorConcatWord( state, rn, rm, word );
    if( shift == 0 ) return current;
    long next = vectorConcatWord( state, rn, rm, word + 1 );
    return (current >>> shift) | (next << (64 - shift));
  }

  private static long vectorConcatWord( Aarch64State state, int rn, int rm,
                                        int word ) {
    return switch( word ) {
      case 0 -> state.readV64( rn, false );
      case 1 -> state.readV64( rn, true );
      case 2 -> state.readV64( rm, false );
      case 3 -> state.readV64( rm, true );
      default -> throw new AssertionError( "invalid vector concatenation word " + word );
    };
  }

  private static void executeShrn8B( Aarch64State state,
                                     Aarch64DecodedInsn instruction ) {
    long low = state.readV64( instruction.rn, false );
    long high = state.readV64( instruction.rn, true );
    long result = 0;
    for( int lane = 0; lane < 8; lane++ ) {
      long half = lane < 4 ? low >>> (lane * 16)
                           : high >>> ((lane - 4) * 16);
      result |= ((half >>> instruction.shiftAmount) & 0xffL) << (lane * 8);
    }
    state.writeV64( instruction.rd, result );
  }

  private static void executeAddhn8B( Aarch64State state,
                                      Aarch64DecodedInsn instruction ) {
    long nLow = state.readV64( instruction.rn, false );
    long nHigh = state.readV64( instruction.rn, true );
    long mLow = state.readV64( instruction.rm, false );
    long mHigh = state.readV64( instruction.rm, true );
    long result = 0;
    for( int lane = 0; lane < 8; lane++ ) {
      int shift = (lane & 3) * 16;
      long nWord = lane < 4 ? nLow : nHigh;
      long mWord = lane < 4 ? mLow : mHigh;
      int sum = (int)(((nWord >>> shift) & 0xffffL)
          + ((mWord >>> shift) & 0xffffL));
      result |= (long)((sum >>> 8) & 0xff) << (lane * 8);
    }
    state.writeV64( instruction.rd, result );
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

    if( instruction.operation == Aarch64DecodedInsn.Operation.LDR
        || instruction.operation == Aarch64DecodedInsn.Operation.LDR_SIGNED ) {
      long value = load( memory, address, instruction.accessSize );
      if( instruction.operation == Aarch64DecodedInsn.Operation.LDR_SIGNED ) {
        int bits = instruction.accessSize * 8;
        value = (value << (64 - bits)) >> (64 - bits);
      }
      state.writeRegister( instruction.rd, value, instruction.dataSize, false );
    } else {
      store( memory, address,
             state.readRegister( instruction.rd, instruction.dataSize, false ),
             instruction.accessSize );
    }

    if( instruction.addressMode == Aarch64DecodedInsn.AddressMode.POST_INDEX ) {
      state.writeRegister( instruction.rn, base + offset, 64, true );
    }
  }

  private static void executeLoadExclusive( Aarch64State state,
                                            Aarch64DecodedInsn instruction,
                                            MemoryBackend memory ) {
    requireMemory( memory, instruction );
    long address = state.readRegister( instruction.rn, 64, true );
    long value = instruction.dataSize == 32
        ? Integer.toUnsignedLong( memory.load32( address ) )
        : memory.load64( address );
    java.lang.invoke.VarHandle.acquireFence();
    state.writeRegister( instruction.rd, value, instruction.dataSize, false );
    state.exclusiveAddress = address;
    state.exclusiveValue = value;
    state.exclusiveSize = instruction.accessSize;
  }

  private static void executeStoreExclusive( Aarch64State state,
                                             Aarch64DecodedInsn instruction,
                                             MemoryBackend memory ) {
    requireMemory( memory, instruction );
    long address = state.readRegister( instruction.rn, 64, true );
    boolean reservationMatches = state.exclusiveAddress == address
        && state.exclusiveSize == instruction.accessSize;
    boolean success = false;
    if( reservationMatches ) {
      long value = state.readRegister( instruction.rd, instruction.dataSize, false );
      java.lang.invoke.VarHandle.releaseFence();
      success = instruction.dataSize == 32
          ? memory.atomicCompareAndSet32(
              address, (int)state.exclusiveValue, (int)value )
          : memory.atomicCompareAndSet64( address, state.exclusiveValue, value );
    }
    state.exclusiveAddress = -1;
    state.exclusiveSize = 0;
    state.writeRegister( instruction.ra, success ? 0 : 1, 32, false );
  }

  private static void executeLoadAcquire( Aarch64State state,
                                          Aarch64DecodedInsn instruction,
                                          MemoryBackend memory ) {
    requireMemory( memory, instruction );
    long address = state.readRegister( instruction.rn, 64, true );
    long value = load( memory, address, instruction.accessSize );
    java.lang.invoke.VarHandle.acquireFence();
    state.writeRegister( instruction.rd, value, instruction.dataSize, false );
  }

  private static void executeStoreRelease( Aarch64State state,
                                           Aarch64DecodedInsn instruction,
                                           MemoryBackend memory ) {
    requireMemory( memory, instruction );
    long address = state.readRegister( instruction.rn, 64, true );
    long value = state.readRegister( instruction.rd, instruction.dataSize, false );
    java.lang.invoke.VarHandle.releaseFence();
    store( memory, address, value, instruction.accessSize );
  }

  private static void executeVectorPairMemory( Aarch64State state,
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

    boolean load = instruction.operation == Aarch64DecodedInsn.Operation.LDP_VECTOR_64
        || instruction.operation == Aarch64DecodedInsn.Operation.LDP_VECTOR_128;
    if( load && instruction.dataSize == 64 ) {
      state.writeV64( instruction.rd, memory.load64( address ) );
      state.writeV64( instruction.rt2, memory.load64( address + 8 ) );
    } else if( load ) {
      state.writeV128( instruction.rd,
          memory.load64( address ), memory.load64( address + 8 ) );
      state.writeV128( instruction.rt2,
          memory.load64( address + 16 ), memory.load64( address + 24 ) );
    } else if( instruction.dataSize == 64 ) {
      memory.store64( address, state.readV64( instruction.rd, false ) );
      memory.store64( address + 8, state.readV64( instruction.rt2, false ) );
    } else {
      memory.store64( address, state.readV64( instruction.rd, false ) );
      memory.store64( address + 8, state.readV64( instruction.rd, true ) );
      memory.store64( address + 16, state.readV64( instruction.rt2, false ) );
      memory.store64( address + 24, state.readV64( instruction.rt2, true ) );
    }

    if( instruction.addressMode == Aarch64DecodedInsn.AddressMode.POST_INDEX ) {
      state.writeRegister( instruction.rn, base + instruction.immediate, 64, true );
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

    if( instruction.operation == Aarch64DecodedInsn.Operation.LDP
        || instruction.operation == Aarch64DecodedInsn.Operation.LDP_SIGNED ) {
      long first = load( memory, address, instruction.accessSize );
      long second = load( memory, address + instruction.accessSize,
                          instruction.accessSize );
      if( instruction.operation == Aarch64DecodedInsn.Operation.LDP_SIGNED ) {
        first = (int)first;
        second = (int)second;
      }
      state.writeRegister( instruction.rd, first, instruction.dataSize, false );
      state.writeRegister( instruction.rt2, second, instruction.dataSize, false );
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
