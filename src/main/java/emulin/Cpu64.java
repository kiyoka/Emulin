// ----------------------------------------
//  Cpu64: x86-64 CPU エミュレータ
//
//  対応命令 (Phase 4: hello64 実行に必要な最小セット):
//    ENDBR64 (F3 0F 1E FA) → NOP
//    PUSH r64  (50+rd)
//    POP  r64  (58+rd)
//    MOV  r/m64, r64  (REX.W 89 /r)
//    MOV  r/m64, imm32 sign-extended (REX.W C7 /0)
//    MOV  r32,   imm32 zero-extends  (B8+rd)
//    XOR  r/m64, r64  (REX.W 31 /r)
//    SYSCALL (0F 05)
// ----------------------------------------
package emulin;

public class Cpu64 extends AbstractCpu
{
  // 16 本の 64-bit 汎用レジスタ (索引は x86-64 エンコーディングと同じ)
  // 0=RAX 1=RCX 2=RDX 3=RBX 4=RSP 5=RBP 6=RSI 7=RDI 8=R8..15=R15
  static final int R_RAX = 0, R_RCX = 1, R_RDX = 2, R_RBX = 3;
  static final int R_RSP = 4, R_RBP = 5, R_RSI = 6, R_RDI = 7;
  static final int NREGS = 16;

  long r64[];   // 64-bit 汎用レジスタ
  long rip;     // 64-bit 命令ポインタ

  SyscallAmd64 syscall64;

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
  }

  @Override
  public AbstractCpu duplicate( Process _process ) {
    Cpu64 c = new Cpu64( sysinfo, _process );
    System.arraycopy( r64, 0, c.r64, 0, NREGS );
    c.rip = rip;
    return c;
  }

  @Override public void   set_ip( long _ip )    { rip = _ip; }
  @Override public long   get_ip()               { return rip; }
  @Override public void   set_sp( long sp )      { r64[R_RSP] = sp; }
  @Override public long   get_sp()               { return r64[R_RSP]; }
  @Override public void   set_ax( int value )    { r64[R_RAX] = value & 0xFFFFFFFFL; }

  @Override
  public void connect_devices( Memory _mem, Syscall _syscall ) {
    mem      = _mem;
    syscall  = _syscall;
    syscall64 = (SyscallAmd64)_syscall;
    syscall.connect_mem( _mem );
  }

  @Override
  public void fetch( long address, byte[] buf ) {
    mem.fetch( address, buf );
  }

  // push/pop 64-bit (8 バイト)
  private void push64( long value ) {
    r64[R_RSP] -= 8;
    mem.store64( r64[R_RSP], value );
  }

  private long pop64() {
    long v = mem.load64( r64[R_RSP] );
    r64[R_RSP] += 8;
    return v;
  }

  // AbstractCpu の 32-bit push/pop は 64-bit モードでは使わないが実装は必要
  @Override
  public void push32( long value ) {
    r64[R_RSP] -= 4;
    mem.store32( r64[R_RSP], (int)value );
  }

  @Override
  public int pop32() {
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

  // --- デバッグ文字列 ---

  private static final String[] REG_NAMES = {
    "rax","rcx","rdx","rbx","rsp","rbp","rsi","rdi",
    "r8","r9","r10","r11","r12","r13","r14","r15"
  };

  @Override
  public String reg_str() {
    StringBuilder sb = new StringBuilder();
    for( int i = 0; i < NREGS; i++ ) {
      sb.append( REG_NAMES[i] ).append( '=' )
        .append( Long.toHexString( r64[i] ) ).append( ' ' );
    }
    return sb.toString();
  }

  @Override
  public String ip_str() {
    return "rip=" + Long.toHexString( rip ) + " ";
  }

  @Override
  public String flag_str() {
    return "";
  }

  @Override
  public String disasm_str( long address ) {
    return "0x" + Long.toHexString( address );
  }

  // --- CPU 実行ループ ---

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

  private long decode_and_exec( long pc ) {
    // REX プレフィックスを収集
    boolean rex_w = false;  // 64-bit オペランドサイズ
    boolean rex_r = false;  // ModRM.reg 拡張
    boolean rex_x = false;  // SIB.index 拡張
    boolean rex_b = false;  // ModRM.r/m / オペコード reg 拡張

    byte b0 = mem.load8( pc );

    // REX プレフィックス (0x40-0x4F)
    if( (b0 & 0xF0) == 0x40 ) {
      rex_w = (b0 & 0x08) != 0;
      rex_r = (b0 & 0x04) != 0;
      rex_x = (b0 & 0x02) != 0;
      rex_b = (b0 & 0x01) != 0;
      pc++;
      b0 = mem.load8( pc );
    }

    int opc = b0 & 0xFF;

    // F3 プレフィックス (REPZ) → ENDBR64 候補として読み飛ばす
    if( opc == 0xF3 ) {
      // F3 0F 1E FA = ENDBR64 → NOP
      byte b1 = mem.load8( pc + 1 );
      byte b2 = mem.load8( pc + 2 );
      byte b3 = mem.load8( pc + 3 );
      if( (b1 & 0xFF) == 0x0F && (b2 & 0xFF) == 0x1E && (b3 & 0xFF) == 0xFA ) {
        return pc + 4;
      }
      // その他の F3 は未対応
      process.println( "Cpu64: unsupported F3 opcode at 0x" + Long.toHexString( pc ) );
      process.set_exit_flag();
      return pc;
    }

    // 0F エスケープ
    if( opc == 0x0F ) {
      byte b1 = mem.load8( pc + 1 );
      int opc2 = b1 & 0xFF;
      if( opc2 == 0x05 ) {
        // SYSCALL
        return exec_syscall( pc + 2 );
      }
      process.println( "Cpu64: unsupported 0F " + Integer.toHexString( opc2 )
                       + " at 0x" + Long.toHexString( pc ) );
      process.set_exit_flag();
      return pc;
    }

    // PUSH r64: 50+rd (no REX needed for base 8 regs in 64-bit mode)
    if( opc >= 0x50 && opc <= 0x57 ) {
      int rd = (opc & 7) | (rex_b ? 8 : 0);
      push64( r64[rd] );
      return pc + 1;
    }

    // POP r64: 58+rd
    if( opc >= 0x58 && opc <= 0x5F ) {
      int rd = (opc & 7) | (rex_b ? 8 : 0);
      r64[rd] = pop64();
      return pc + 1;
    }

    // MOV r/m, r (89 /r): store reg → r/m
    if( opc == 0x89 ) {
      byte modrm = mem.load8( pc + 1 );
      int mod = (modrm >> 6) & 3;
      int reg = ((modrm >> 3) & 7) | (rex_r ? 8 : 0);
      int rm  = (modrm & 7)        | (rex_b ? 8 : 0);
      if( mod == 3 ) {
        // register-to-register
        if( rex_w ) {
          r64[rm] = r64[reg];
        } else {
          // 32-bit move: zero-extends to 64-bit
          r64[rm] = r64[reg] & 0xFFFFFFFFL;
        }
        return pc + 2;
      }
      process.println( "Cpu64: MOV r/m,r mem mode unimplemented at 0x" + Long.toHexString( pc ) );
      process.set_exit_flag();
      return pc;
    }

    // MOV r, r/m (8B /r): load r/m → reg
    if( opc == 0x8B ) {
      byte modrm = mem.load8( pc + 1 );
      int mod = (modrm >> 6) & 3;
      int reg = ((modrm >> 3) & 7) | (rex_r ? 8 : 0);
      int rm  = (modrm & 7)        | (rex_b ? 8 : 0);
      if( mod == 3 ) {
        r64[reg] = rex_w ? r64[rm] : (r64[rm] & 0xFFFFFFFFL);
        return pc + 2;
      }
      process.println( "Cpu64: MOV r,r/m mem mode unimplemented at 0x" + Long.toHexString( pc ) );
      process.set_exit_flag();
      return pc;
    }

    // MOV r32/64, imm32/64: B8+rd
    if( opc >= 0xB8 && opc <= 0xBF ) {
      int rd = (opc & 7) | (rex_b ? 8 : 0);
      if( rex_w ) {
        // MOV r64, imm64 (8-byte immediate)
        long imm64 = load_imm64( pc + 1 );
        r64[rd] = imm64;
        return pc + 9;
      } else {
        // MOV r32, imm32 (zero-extends to 64-bit)
        long imm32 = load_imm32u( pc + 1 );
        r64[rd] = imm32;
        return pc + 5;
      }
    }

    // XOR r/m64, r64 (31 /r)
    if( opc == 0x31 ) {
      byte modrm = mem.load8( pc + 1 );
      int mod = (modrm >> 6) & 3;
      int reg = ((modrm >> 3) & 7) | (rex_r ? 8 : 0);
      int rm  = (modrm & 7)        | (rex_b ? 8 : 0);
      if( mod == 3 ) {
        if( rex_w ) {
          r64[rm] ^= r64[reg];
        } else {
          r64[rm] = (r64[rm] ^ r64[reg]) & 0xFFFFFFFFL;
        }
        return pc + 2;
      }
      process.println( "Cpu64: XOR r/m,r mem mode unimplemented at 0x" + Long.toHexString( pc ) );
      process.set_exit_flag();
      return pc;
    }

    // XOR r64, r/m64 (33 /r)
    if( opc == 0x33 ) {
      byte modrm = mem.load8( pc + 1 );
      int mod = (modrm >> 6) & 3;
      int reg = ((modrm >> 3) & 7) | (rex_r ? 8 : 0);
      int rm  = (modrm & 7)        | (rex_b ? 8 : 0);
      if( mod == 3 ) {
        if( rex_w ) {
          r64[reg] ^= r64[rm];
        } else {
          r64[reg] = (r64[reg] ^ r64[rm]) & 0xFFFFFFFFL;
        }
        return pc + 2;
      }
      process.println( "Cpu64: XOR r,r/m mem mode unimplemented at 0x" + Long.toHexString( pc ) );
      process.set_exit_flag();
      return pc;
    }

    // MOV r/m64, imm32 sign-extended (C7 /0)
    if( opc == 0xC7 ) {
      byte modrm = mem.load8( pc + 1 );
      int mod = (modrm >> 6) & 3;
      int reg = (modrm >> 3) & 7;  // must be /0
      int rm  = (modrm & 7)        | (rex_b ? 8 : 0);
      if( mod == 3 && reg == 0 ) {
        long imm = load_imm32s( pc + 2 );   // sign-extended to 64-bit
        r64[rm] = rex_w ? imm : (imm & 0xFFFFFFFFL);
        return pc + 6;
      }
      process.println( "Cpu64: MOV r/m,imm32 unimplemented variant at 0x" + Long.toHexString( pc ) );
      process.set_exit_flag();
      return pc;
    }

    // NOP (90)
    if( opc == 0x90 ) {
      return pc + 1;
    }

    process.println( "Cpu64: unknown opcode 0x" + Integer.toHexString( opc )
                     + " at rip=0x" + Long.toHexString( pc ) );
    process.set_exit_flag();
    return pc;
  }

  private long exec_syscall( long next_pc ) {
    // RCX = return address (Linux ABI: SYSCALL saves RIP to RCX)
    r64[R_RCX] = next_pc;
    long result = syscall64.call_amd64(
        r64[R_RAX], r64[R_RDI], r64[R_RSI], r64[R_RDX],
        r64[10],    r64[8],     r64[9] );
    r64[R_RAX] = result;
    interrupt_done = true;
    return next_pc;
  }

  // --- 即値ロードユーティリティ (リトルエンディアン) ---

  private long load_imm32u( long addr ) {
    return ((mem.load8( addr     ) & 0xFFL)      )
         | ((mem.load8( addr + 1 ) & 0xFFL) <<  8)
         | ((mem.load8( addr + 2 ) & 0xFFL) << 16)
         | ((mem.load8( addr + 3 ) & 0xFFL) << 24);
  }

  private long load_imm32s( long addr ) {
    int v = (int)load_imm32u( addr );
    return (long)v;   // sign-extend
  }

  private long load_imm64( long addr ) {
    return load_imm32u( addr )
         | (load_imm32u( addr + 4 ) << 32);
  }
}
