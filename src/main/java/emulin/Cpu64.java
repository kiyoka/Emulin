// ----------------------------------------
//  Cpu64: x86-64 CPU エミュレータ
//
//  対応命令 (Phase 4-5: hello64 + echo_stdin64 実行に必要なセット):
//    ENDBR64 / NOP / LEAVE / RET
//    PUSH/POP r64
//    MOV  r/m64 ↔ r64,  MOV r/m64, imm32s,  MOV r32, imm32u
//    XOR  r/m64, r64
//    SUB  r/m64, imm32s (81 /5) / imm8s (83 /5)
//    ADD  r/m64, imm32s (81 /0) / imm8s (83 /0)
//    CMP  r/m64, imm32s (81 /7) / imm8s (83 /7)
//    LEA  r64, m
//    JMP  rel8 / rel32
//    CALL rel32 / RET
//    Jcc  rel8 (all 16 conditions)
//    SYSCALL
//
//  ModRM 対応モード:
//    mod=00  [base]
//    mod=01  [base + disp8 sign-ext]
//    mod=10  [base + disp32 sign-ext]
//    mod=11  register
//    SIB: 基本ケース (index=none) のみ
// ----------------------------------------
package emulin;

public class Cpu64 extends AbstractCpu
{
  // 16 本の 64-bit 汎用レジスタ (AMD64 エンコーディングと同じ順序)
  // 0=RAX 1=RCX 2=RDX 3=RBX 4=RSP 5=RBP 6=RSI 7=RDI 8=R8..15=R15
  static final int R_RAX = 0, R_RCX = 1, R_RDX = 2, R_RBX = 3;
  static final int R_RSP = 4, R_RBP = 5, R_RSI = 6, R_RDI = 7;
  static final int NREGS = 16;

  long[] r64;   // 64-bit 汎用レジスタ
  long   rip;   // 64-bit 命令ポインタ

  SyscallAmd64 syscall64;

  // ModRM デコード結果 (命令ごとに更新)
  private int  mrm_mod, mrm_reg, mrm_rm;
  private long mrm_ea;   // 有効アドレス (mod != 3 の時のみ有効)

  public Cpu64( Sysinfo _sysinfo, Process _process ) {
    sysinfo = _sysinfo;
    process = _process;
    init();
  }

  @Override
  public void init() {
    r64 = new long[NREGS];
    rip = 0;
    interrupt_done = false;
    of = sf = zf = cf = 0;
  }

  @Override
  public AbstractCpu duplicate( Process _process ) {
    Cpu64 c = new Cpu64( sysinfo, _process );
    System.arraycopy( r64, 0, c.r64, 0, NREGS );
    c.rip = rip;
    c.of = of; c.sf = sf; c.zf = zf; c.cf = cf;
    return c;
  }

  @Override public void   set_ip( long _ip )    { rip = _ip; }
  @Override public long   get_ip()               { return rip; }
  @Override public void   set_sp( long sp )      { r64[R_RSP] = sp; }
  @Override public long   get_sp()               { return r64[R_RSP]; }
  @Override public void   set_ax( int value )    { r64[R_RAX] = value & 0xFFFFFFFFL; }

  @Override
  public void connect_devices( Memory _mem, Syscall _syscall ) {
    mem       = _mem;
    syscall   = _syscall;
    syscall64 = (SyscallAmd64)_syscall;
    syscall.connect_mem( _mem );
  }

  @Override
  public void fetch( long address, byte[] buf ) { mem.fetch( address, buf ); }

  // --- stack ---

  private void push64( long value ) {
    r64[R_RSP] -= 8;
    mem.store64( r64[R_RSP], value );
  }

  private long pop64() {
    long v = mem.load64( r64[R_RSP] );
    r64[R_RSP] += 8;
    return v;
  }

  @Override public void push32( long value ) {
    r64[R_RSP] -= 4;
    mem.store32( r64[R_RSP], (int)value );
  }
  @Override public int pop32() {
    int v = mem.load32( r64[R_RSP] );
    r64[R_RSP] += 4;
    return v;
  }

  @Override
  public long pushString( String str ) {
    byte[] bytes = str.getBytes();
    r64[R_RSP] -= bytes.length + 1;
    mem.storeString( r64[R_RSP], str );
    return r64[R_RSP];
  }

  @Override
  public void set_signal_handler( long _ip, long goto_adrs ) {
    push64( _ip );
    rip = goto_adrs;
  }

  @Override
  public boolean is_interrupt_done() { return interrupt_done; }

  // --- CPU ループ ---

  @Override
  public long eval() {
    long executed = 0;
    while( !process.is_exited() ) {
      executed++;
      process.evals = executed;
      rip = decode_and_exec( rip );
    }
    return executed;
  }

  // --- ModRM デコード ---
  // pc: ModRM バイトの位置。戻り値: ModRM + displacement を消費した後の pc。
  // mrm_mod/reg/rm/ea が設定される。
  private long decodeModRM( long pc, boolean rexR, boolean rexB, boolean rexX ) {
    int b   = mem.load8( pc ) & 0xFF;
    mrm_mod = (b >> 6) & 3;
    mrm_reg = ((b >> 3) & 7) | (rexR ? 8 : 0);
    int rm_base = b & 7;
    mrm_rm  = rm_base | (rexB ? 8 : 0);
    long next = pc + 1;

    if( mrm_mod == 3 ) {
      mrm_ea = 0;
      return next;
    }

    if( rm_base == 4 ) {
      // SIB バイト
      int sib     = mem.load8( next ) & 0xFF;
      next++;
      int ss      = (sib >> 6) & 3;
      int sib_idx = (sib >> 3) & 7;
      int sib_bas = sib & 7;
      long base  = (sib_bas == 5 && mrm_mod == 0) ? 0 : r64[sib_bas | (rexB ? 8 : 0)];
      long index = (sib_idx == 4) ? 0 : (r64[sib_idx | (rexX ? 8 : 0)] << ss);
      mrm_ea = base + index;
      if( sib_bas == 5 && mrm_mod == 0 ) {
        mrm_ea += (long)(int)loadImm32u( next );
        next += 4;
        return next;
      }
      // fall through to disp handling
      if( mrm_mod == 1 ) { mrm_ea += (long)(byte)mem.load8( next ); next++; }
      else if( mrm_mod == 2 ) { mrm_ea += (long)(int)loadImm32u( next ); next += 4; }
      return next;
    }

    if( mrm_mod == 0 && rm_base == 5 ) {
      // RIP 相対 ([RIP + disp32])
      // 呼び出し元が命令終端アドレスを mrm_ea に加算すること
      mrm_ea = (long)(int)loadImm32u( next );
      mrm_rm = -1;  // RIP 相対フラグ
      return next + 4;
    }

    if( mrm_mod == 0 ) {
      mrm_ea = r64[mrm_rm];
    } else if( mrm_mod == 1 ) {
      mrm_ea = r64[mrm_rm] + (long)(byte)mem.load8( next );
      next++;
    } else {
      mrm_ea = r64[mrm_rm] + (long)(int)loadImm32u( next );
      next += 4;
    }
    return next;
  }

  // ModRM が指す 64-bit 値を読む
  private long readRM64() {
    return (mrm_mod == 3) ? r64[mrm_rm] : mem.load64( mrm_ea );
  }

  // ModRM が指す 32-bit 値を読む (符号なし拡張)
  private long readRM32() {
    return (mrm_mod == 3) ? (r64[mrm_rm] & 0xFFFFFFFFL) : (mem.load32( mrm_ea ) & 0xFFFFFFFFL);
  }

  // ModRM が指す場所に 64-bit 値を書く
  private void writeRM64( long val ) {
    if( mrm_mod == 3 ) r64[mrm_rm] = val;
    else               mem.store64( mrm_ea, val );
  }

  // ModRM が指す場所に 32-bit 値を書く (RM がレジスタの場合は上位 32-bit をゼロクリア)
  private void writeRM32( long val ) {
    if( mrm_mod == 3 ) r64[mrm_rm] = val & 0xFFFFFFFFL;
    else               mem.store32( mrm_ea, (int)val );
  }

  // --- フラグ計算 ---

  private void setFlags64Sub( long a, long b ) {
    long result = a - b;
    zf = (result == 0) ? 1 : 0;
    sf = (result < 0)  ? 1 : 0;
    of = (((a ^ b) & (a ^ result)) < 0) ? 1 : 0;
    cf = Long.compareUnsigned( a, b ) < 0 ? 1 : 0;
  }

  private void setFlags64Add( long a, long b ) {
    long result = a + b;
    zf = (result == 0) ? 1 : 0;
    sf = (result < 0)  ? 1 : 0;
    of = (((a ^ ~b) & (a ^ result)) < 0) ? 1 : 0;
    cf = Long.compareUnsigned( result, a ) < 0 ? 1 : 0;
  }

  // Jcc 判定 (cond は opcode 下位 4 ビット)
  private boolean evalCond( int cond ) {
    switch( cond & 0xF ) {
      case 0: return of != 0;                         // JO
      case 1: return of == 0;                         // JNO
      case 2: return cf != 0;                         // JB/JNAE/JC
      case 3: return cf == 0;                         // JNB/JAE/JNC
      case 4: return zf != 0;                         // JE/JZ
      case 5: return zf == 0;                         // JNE/JNZ
      case 6: return cf != 0 || zf != 0;             // JBE/JNA
      case 7: return cf == 0 && zf == 0;             // JA/JNBE
      case 8: return sf != 0;                         // JS
      case 9: return sf == 0;                         // JNS
      case 10: return false;  // JP (pf not tracked)
      case 11: return true;   // JNP
      case 12: return sf != of;                       // JL/JNGE
      case 13: return sf == of;                       // JGE/JNL
      case 14: return zf != 0 || (sf != of);         // JLE/JNG
      case 15: return zf == 0 && (sf == of);         // JG/JNLE
      default: return false;
    }
  }

  // --- メイン デコード+実行 ---

  private long decode_and_exec( long pc ) {
    boolean rex_w = false, rex_r = false, rex_x = false, rex_b = false;
    int b0 = mem.load8( pc ) & 0xFF;

    // REX プレフィックス
    if( (b0 & 0xF0) == 0x40 ) {
      rex_w = (b0 & 0x08) != 0;
      rex_r = (b0 & 0x04) != 0;
      rex_x = (b0 & 0x02) != 0;
      rex_b = (b0 & 0x01) != 0;
      pc++;
      b0 = mem.load8( pc ) & 0xFF;
    }

    // REPZ プレフィックス (F3): ENDBR64 など
    if( b0 == 0xF3 ) {
      int b1 = mem.load8( pc+1 ) & 0xFF;
      int b2 = mem.load8( pc+2 ) & 0xFF;
      int b3 = mem.load8( pc+3 ) & 0xFF;
      if( b1 == 0x0F && b2 == 0x1E && b3 == 0xFA ) return pc + 4; // ENDBR64
      if( b1 == 0x0F && b2 == 0x1E && b3 == 0xFB ) return pc + 4; // ENDBR32
      process.println( "Cpu64: unsupported F3 at 0x" + Long.toHexString(pc) );
      process.set_exit_flag(); return pc;
    }

    // 0F エスケープ
    if( b0 == 0x0F ) {
      int b1 = mem.load8( pc+1 ) & 0xFF;
      if( b1 == 0x05 ) return exec_syscall( pc + 2 );     // SYSCALL
      // Jcc rel32 (0F 8x)
      if( (b1 & 0xF0) == 0x80 ) {
        int rel32 = (int)loadImm32u( pc + 2 );
        long next = pc + 6;
        return evalCond( b1 & 0xF ) ? next + rel32 : next;
      }
      process.println( "Cpu64: unsupported 0F " + Integer.toHexString(b1) + " at 0x" + Long.toHexString(pc) );
      process.set_exit_flag(); return pc;
    }

    // NOP
    if( b0 == 0x90 ) return pc + 1;

    // PUSH r64 (50+rd)
    if( b0 >= 0x50 && b0 <= 0x57 ) {
      push64( r64[(b0 & 7) | (rex_b ? 8 : 0)] );
      return pc + 1;
    }

    // POP r64 (58+rd)
    if( b0 >= 0x58 && b0 <= 0x5F ) {
      r64[(b0 & 7) | (rex_b ? 8 : 0)] = pop64();
      return pc + 1;
    }

    // LEAVE (C9)
    if( b0 == 0xC9 ) {
      r64[R_RSP] = r64[R_RBP];
      r64[R_RBP] = pop64();
      return pc + 1;
    }

    // RET near (C3)
    if( b0 == 0xC3 ) {
      return pop64();
    }

    // CALL rel32 (E8)
    if( b0 == 0xE8 ) {
      int rel32 = (int)loadImm32u( pc + 1 );
      long next = pc + 5;
      push64( next );
      return next + rel32;
    }

    // JMP rel8 (EB)
    if( b0 == 0xEB ) {
      byte rel8 = mem.load8( pc + 1 );
      long next = pc + 2;
      return next + rel8;
    }

    // JMP rel32 (E9)
    if( b0 == 0xE9 ) {
      int rel32 = (int)loadImm32u( pc + 1 );
      long next = pc + 5;
      return next + rel32;
    }

    // Jcc rel8 (70-7F)
    if( b0 >= 0x70 && b0 <= 0x7F ) {
      byte rel8 = mem.load8( pc + 1 );
      long next = pc + 2;
      return evalCond( b0 & 0xF ) ? next + rel8 : next;
    }

    // MOV r/m, r  (89 /r)  r → r/m
    if( b0 == 0x89 ) {
      long next = decodeModRM( pc+1, rex_r, rex_b, rex_x );
      if( rex_w ) writeRM64( r64[mrm_reg] );
      else        writeRM32( r64[mrm_reg] );
      return next;
    }

    // MOV r, r/m  (8B /r)  r/m → r
    if( b0 == 0x8B ) {
      long next = decodeModRM( pc+1, rex_r, rex_b, rex_x );
      if( rex_w ) r64[mrm_reg] = readRM64();
      else        r64[mrm_reg] = readRM32();
      return next;
    }

    // LEA r, m  (8D /r)
    if( b0 == 0x8D ) {
      long next = decodeModRM( pc+1, rex_r, rex_b, rex_x );
      if( mrm_rm == -1 ) {
        // RIP 相対: mrm_ea は disp32, next がこの命令終端
        r64[mrm_reg] = next + mrm_ea;
      } else {
        r64[mrm_reg] = mrm_ea;
      }
      return next;
    }

    // XOR r/m, r  (31 /r)  r → r/m XOR
    if( b0 == 0x31 ) {
      long next = decodeModRM( pc+1, rex_r, rex_b, rex_x );
      long res = readRM64() ^ r64[mrm_reg];
      if( rex_w ) writeRM64( res ); else writeRM32( res );
      zf = (res == 0) ? 1 : 0; sf = (res < 0) ? 1 : 0; of = 0; cf = 0;
      return next;
    }

    // XOR r, r/m  (33 /r)
    if( b0 == 0x33 ) {
      long next = decodeModRM( pc+1, rex_r, rex_b, rex_x );
      long res = r64[mrm_reg] ^ readRM64();
      r64[mrm_reg] = rex_w ? res : (res & 0xFFFFFFFFL);
      zf = (res == 0) ? 1 : 0; sf = (res < 0) ? 1 : 0; of = 0; cf = 0;
      return next;
    }

    // MOV r32/r64, imm  (B8+rd)
    if( b0 >= 0xB8 && b0 <= 0xBF ) {
      int rd = (b0 & 7) | (rex_b ? 8 : 0);
      if( rex_w ) { r64[rd] = loadImm64( pc+1 ); return pc + 9; }
      else        { r64[rd] = loadImm32u( pc+1 ); return pc + 5; }
    }

    // Grp1 r/m, imm32 sign-ext (81 /x)
    if( b0 == 0x81 ) {
      long next = decodeModRM( pc+1, rex_r, rex_b, rex_x );
      long imm  = (long)(int)loadImm32u( next );
      next += 4;
      return execGrp1( imm, rex_w, next );
    }

    // Grp1 r/m, imm8 sign-ext (83 /x)
    if( b0 == 0x83 ) {
      long next = decodeModRM( pc+1, rex_r, rex_b, rex_x );
      long imm  = (long)(byte)mem.load8( next );
      next++;
      return execGrp1( imm, rex_w, next );
    }

    // MOV r/m64, imm32 sign-ext (C7 /0)
    if( b0 == 0xC7 ) {
      long next = decodeModRM( pc+1, rex_r, rex_b, rex_x );
      long imm  = (long)(int)loadImm32u( next );
      next += 4;
      if( mrm_reg == 0 ) {
        if( rex_w ) writeRM64( imm ); else writeRM32( imm );
      }
      return next;
    }

    process.println( "Cpu64: unknown opcode 0x" + Integer.toHexString(b0)
                     + " at rip=0x" + Long.toHexString(pc) );
    process.set_exit_flag();
    return pc;
  }

  // Grp1: ADD(0) OR(1) ADC(2) SBB(3) AND(4) SUB(5) XOR(6) CMP(7)
  private long execGrp1( long imm, boolean is64, long next_pc ) {
    long val = is64 ? readRM64() : readRM32();
    long res;
    switch( mrm_reg ) {
      case 0: // ADD
        res = val + imm;
        if( is64 ) { setFlags64Add(val,imm); writeRM64(res); }
        else { res &= 0xFFFFFFFFL; writeRM32(res); }
        break;
      case 1: // OR
        res = val | imm;
        if( is64 ) writeRM64(res); else writeRM32(res);
        zf=(res==0)?1:0; sf=(res<0)?1:0; of=0; cf=0;
        break;
      case 4: // AND
        res = val & imm;
        if( is64 ) writeRM64(res); else writeRM32(res);
        zf=(res==0)?1:0; sf=(res<0)?1:0; of=0; cf=0;
        break;
      case 5: // SUB
        res = val - imm;
        if( is64 ) { setFlags64Sub(val,imm); writeRM64(res); }
        else { res &= 0xFFFFFFFFL; writeRM32(res); }
        break;
      case 6: // XOR
        res = val ^ imm;
        if( is64 ) writeRM64(res); else writeRM32(res);
        zf=(res==0)?1:0; sf=(res<0)?1:0; of=0; cf=0;
        break;
      case 7: // CMP (flags only, no write)
        if( is64 ) setFlags64Sub(val,imm);
        else { long a32=val&0xFFFFFFFFL; long b32=imm&0xFFFFFFFFL;
               long r32=a32-b32; zf=(r32==0)?1:0; sf=(r32<0)?1:0;
               of=(((a32^b32)&(a32^r32)) < 0)?1:0; cf=Long.compareUnsigned(a32,b32)<0?1:0; }
        break;
      default:
        process.println( "Cpu64: unsupported Grp1 /"+mrm_reg+" at rip=0x"+Long.toHexString(next_pc) );
        process.set_exit_flag();
    }
    return next_pc;
  }

  // SYSCALL 命令の処理
  private long exec_syscall( long next_pc ) {
    r64[R_RCX] = next_pc;  // Linux ABI: SYSCALL saves RIP to RCX
    long result = syscall64.call_amd64(
        r64[R_RAX], r64[R_RDI], r64[R_RSI], r64[R_RDX],
        r64[10],    r64[8],     r64[9] );
    r64[R_RAX] = result;
    interrupt_done = true;
    return next_pc;
  }

  // --- 即値ロードユーティリティ ---

  private long loadImm32u( long addr ) {
    return ((mem.load8(addr  ) & 0xFFL)      )
         | ((mem.load8(addr+1) & 0xFFL) <<  8)
         | ((mem.load8(addr+2) & 0xFFL) << 16)
         | ((mem.load8(addr+3) & 0xFFL) << 24);
  }

  private long loadImm64( long addr ) {
    return loadImm32u(addr) | (loadImm32u(addr+4) << 32);
  }

  // --- デバッグ文字列 ---

  private static final String[] REG_NAMES = {
    "rax","rcx","rdx","rbx","rsp","rbp","rsi","rdi",
    "r8","r9","r10","r11","r12","r13","r14","r15"
  };

  @Override
  public String reg_str() {
    StringBuilder sb = new StringBuilder();
    for( int i = 0; i < NREGS; i++ )
      sb.append( REG_NAMES[i] ).append('=').append( Long.toHexString(r64[i]) ).append(' ');
    return sb.toString();
  }

  @Override public String ip_str()     { return "rip=" + Long.toHexString(rip) + " "; }
  @Override public String flag_str()   { return "zf="+zf+" sf="+sf+" of="+of+" cf="+cf+" "; }
  @Override public String disasm_str( long address ) { return "0x" + Long.toHexString(address); }
}
