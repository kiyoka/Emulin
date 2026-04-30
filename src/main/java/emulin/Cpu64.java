// ----------------------------------------
//  Cpu64: x86-64 CPU エミュレータ
//
//  対応命令 (Phase 4-6):
//    プレフィックス: REX, 66/67, 64(FS)/65(GS), 2E/3E/F0/F2, F3(REP)
//    ENDBR64 / NOP / LEAVE / RET / HLT(→exit)
//    PUSH/POP r64, PUSH r/m64 (FF/6), PUSH imm8/imm32
//    MOV r/m64↔r64, MOV r/m64←imm32s, MOV r32←imm32u, MOV r64←imm64
//    MOV r/m8↔r8, MOV r/m8←imm8
//    ALU r/m←r, r←r/m (ADD/OR/AND/SUB/CMP, 32/64-bit)
//    ALU imm forms (81/83): ADD/OR/AND/SUB/XOR/CMP
//    ALU 8-bit: XOR/CMP/TEST r/m8
//    XOR r/m64←r64 (31), r64←r/m64 (33)
//    TEST r/m, r (84/85)
//    MOVSXD (63), MOVSX/MOVZX (0F BE/B6/BF/B7)
//    IMUL (6B/69/0F AF)
//    LEA, CALL rel32, CALL r/m64 (FF/2), JMP rel8/rel32, JMP r/m64 (FF/4)
//    Jcc rel8/rel32 (70-7F / 0F 80-8F)
//    CMOVcc (0F 40-4F)
//    NOT/NEG/MUL/IMUL/DIV/IDIV (F6/F7)
//    INC/DEC r/m (FF/0, FF/1)
//    CDQE (98), CQO (99)
//    SYSCALL (0F 05)
//    REP STOS/MOVS (F3 AA/AB/A4/A5)
//    REPNZ SCAS (F2/F3 AE/AF — simplified)
//    Multi-byte NOP (0F 1F)
// ----------------------------------------
package emulin;

public class Cpu64 extends AbstractCpu
{
  // 16 本の 64-bit 汎用レジスタ (AMD64 エンコーディングと同じ順序)
  static final int R_RAX = 0, R_RCX = 1, R_RDX = 2, R_RBX = 3;
  static final int R_RSP = 4, R_RBP = 5, R_RSI = 6, R_RDI = 7;
  static final int NREGS = 16;

  long[] r64;
  long   rip;
  long   fs_base;
  long[] xmm_lo = new long[16];  // XMM0-15 下位 64bit
  long[] xmm_hi = new long[16];  // XMM0-15 上位 64bit

  SyscallAmd64 syscall64;

  // ModRM デコード結果
  private int  mrm_mod, mrm_reg, mrm_rm;
  private long mrm_ea;
  // REX prefix presence (for 8-bit reg encoding: with REX, rm=4-7 means SPL/BPL/SIL/DIL;
  // without REX, rm=4-7 means AH/CH/DH/BH)
  private boolean rex_present;

  public Cpu64( Sysinfo _sysinfo, Process _process ) {
    sysinfo = _sysinfo;
    process = _process;
    init();
  }

  @Override
  public void init() {
    r64 = new long[NREGS];
    rip = 0;
    fs_base = 0;
    interrupt_done = false;
    of = sf = zf = cf = 0;
  }

  @Override
  public AbstractCpu duplicate( Process _process ) {
    Cpu64 c = new Cpu64( sysinfo, _process );
    System.arraycopy( r64, 0, c.r64, 0, NREGS );
    c.rip = rip;
    c.fs_base = fs_base;
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

  // IRELATIVE リロケーション解決用: addr の関数を呼び出し RAX を返す。
  // ret_addr は既にマップ済みで RET (0xC3) が置かれていること。
  long call_resolver( long addr, long ret_addr ) {
    r64[R_RSP] -= 8;
    mem.store64( r64[R_RSP], ret_addr );
    rip = addr;
    int limit = 200_000;
    while( !process.is_exited() && rip != ret_addr && limit-- > 0 ) {
      rip = decode_and_exec( rip );
    }
    return r64[R_RAX];
  }

  @Override
  public long eval() {
    long executed = 0;
    while( !process.is_exited() ) {
      executed++;
      process.evals = executed;
      // pending シグナルがあればハンドラへ分岐
      check_pending_signal();
      if( System.getenv("EMULIN_TRACE_SH") != null ) {
        if( rip == 0x548cc4L ) {
          long head = r64[R_RDI];
          long e0 = mem.load64(head);
          if( e0 == 0x100000000000L ) {
            // Dump 64 bytes around the address
            StringBuilder sb = new StringBuilder();
            for( int i = -16; i < 32; i += 8 ) {
              long v = mem.load64(head + i);
              sb.append(String.format(" [+%d]=0x%x", i, v));
            }
            System.err.println("DBG BAD hash_lookup(rdi=0x"+Long.toHexString(head)+") dump:"+sb);
          }
          System.err.println("DBG hash_lookup(rdi=0x"+Long.toHexString(head)+") *head=0x"+Long.toHexString(e0)+" lr=0x"+Long.toHexString(mem.load64(r64[R_RSP]))+" eval="+executed);
        }
        if( rip == 0x5490a7L ) {
          System.err.println("DBG before mov(rax),r12: rax=0x"+Long.toHexString(r64[R_RAX])+" *rax=0x"+Long.toHexString(r64[R_RAX]!=0?mem.load64(r64[R_RAX]):0)+" eval="+executed);
        }
        if( rip == 0x548cdaL ) {
          System.err.println("  hash_loop r9=0x"+Long.toHexString(r64[9])+" eval="+executed);
        }
        // 直前の loop iteration: mov %r9, %r8; mov (%r9), %r9
        if( rip == 0x548ce7L ) {
          System.err.println("  hash_advance r9=0x"+Long.toHexString(r64[9])+" *(r9)=0x"+Long.toHexString(mem.load64(r64[9])));
        }
      }
      rip = decode_and_exec( rip );
    }
    return executed;
  }

  // 保留中のシグナルを 1 件処理する (1 命令あたり 1 シグナルまで)
  private void check_pending_signal() {
    int sig = process.psig();
    if( sig < 0 ) return;
    long handler = process.get_func_adrs( sig );
    process.signal_cancel( sig );
    if( handler == Siginfo.SIG_IGN ) return;
    if( handler == Siginfo.SIG_DFL ) {
      int action = process.get_action_type( sig );
      if( action == Signal.SIGACTION_EXIT ) {
        process.set_exit_flag();
      }
      return;
    }
    // ユーザーハンドラ呼び出し:
    //   1. 現在の rip を返り番地としてスタックに push
    //   2. rip = handler、rdi = sig をセット
    //   ハンドラは終了時に ret で rip を pop する
    push64( rip );
    rip = handler;
    r64[R_RDI] = (long)sig;
  }

  // --- ModRM デコード ---
  private long decodeModRM( long pc, boolean rexR, boolean rexB, boolean rexX, boolean addr32 ) {
    int b   = mem.load8( pc ) & 0xFF;
    mrm_mod = (b >> 6) & 3;
    mrm_reg = ((b >> 3) & 7) | (rexR ? 8 : 0);
    int rm_base = b & 7;
    mrm_rm  = rm_base | (rexB ? 8 : 0);
    long next = pc + 1;

    if( mrm_mod == 3 ) { mrm_ea = 0; return next; }

    if( rm_base == 4 ) {
      int sib     = mem.load8( next ) & 0xFF;
      next++;
      int ss      = (sib >> 6) & 3;
      int sib_idx = (sib >> 3) & 7;
      int sib_bas = sib & 7;
      long base   = (sib_bas == 5 && mrm_mod == 0) ? 0L : r64[sib_bas | (rexB ? 8 : 0)];
      long index  = (sib_idx == 4 && !rexX) ? 0L : (r64[sib_idx | (rexX ? 8 : 0)] << ss);
      mrm_ea = base + index;
      if( sib_bas == 5 && mrm_mod == 0 ) {
        mrm_ea += (long)(int)loadImm32u( next ); next += 4;
        if( addr32 ) mrm_ea &= 0xFFFFFFFFL;
        return next;
      }
      if( mrm_mod == 1 ) { mrm_ea += (long)(byte)mem.load8( next ); next++; }
      else if( mrm_mod == 2 ) { mrm_ea += (long)(int)loadImm32u( next ); next += 4; }
      if( addr32 ) mrm_ea &= 0xFFFFFFFFL;
      return next;
    }

    if( mrm_mod == 0 && rm_base == 5 ) {
      mrm_ea = (long)(int)loadImm32u( next );
      mrm_rm = -1;  // RIP 相対フラグ
      return next + 4;
    }

    long reg_val = addr32 ? (r64[mrm_rm] & 0xFFFFFFFFL) : r64[mrm_rm];
    if( mrm_mod == 0 ) {
      mrm_ea = reg_val;
    } else if( mrm_mod == 1 ) {
      mrm_ea = reg_val + (long)(byte)mem.load8( next ); next++;
    } else {
      mrm_ea = reg_val + (long)(int)loadImm32u( next ); next += 4;
    }
    if( addr32 ) mrm_ea &= 0xFFFFFFFFL;
    return next;
  }

  // --- RM read/write helpers ---

  private long readRM64() { return (mrm_mod==3) ? r64[mrm_rm] : mem.load64(mrm_ea); }
  private long readRM32() { return (mrm_mod==3) ? (r64[mrm_rm]&0xFFFFFFFFL) : (mem.load32(mrm_ea)&0xFFFFFFFFL); }
  private long readRM16() { return (mrm_mod==3) ? (r64[mrm_rm]&0xFFFFL) : loadImm16(mrm_ea); }
  private void writeRM16( long v ) {
    if(mrm_mod==3) r64[mrm_rm]=(r64[mrm_rm]&~0xFFFFL)|(v&0xFFFFL);
    else { mem.store8(mrm_ea,(int)v&0xFF); mem.store8(mrm_ea+1,(int)(v>>8)&0xFF); }
  }
  private long readRM8()  {
    if( mrm_mod != 3 ) return mem.load8(mrm_ea)&0xFFL;
    // Without REX: rm=4-7 selects AH/CH/DH/BH (high byte of A/C/D/B = r64[rm-4] >> 8)
    if( !rex_present && mrm_rm >= 4 && mrm_rm <= 7 ) return (r64[mrm_rm-4]>>8)&0xFFL;
    return r64[mrm_rm]&0xFFL;
  }

  private void writeRM64( long v ) { if(mrm_mod==3) r64[mrm_rm]=v; else mem.store64(mrm_ea,v); }
  private void writeRM32( long v ) { if(mrm_mod==3) r64[mrm_rm]=v&0xFFFFFFFFL; else mem.store32(mrm_ea,(int)v); }
  private void writeRM8( long v )  {
    if( mrm_mod != 3 ) { mem.store8(mrm_ea,(byte)v); return; }
    if( !rex_present && mrm_rm >= 4 && mrm_rm <= 7 ) {
      // Write high byte of r64[rm-4]
      int idx = mrm_rm - 4;
      r64[idx] = (r64[idx] & ~0xFF00L) | ((v & 0xFFL) << 8);
      return;
    }
    r64[mrm_rm]=(r64[mrm_rm]&~0xFFL)|(v&0xFFL);
  }


  // Used for mrm_reg in 8-bit MOV / TEST etc. (mrm_rm uses readRM8/writeRM8).
  private long readReg8( int idx ) {
    if( !rex_present && idx >= 4 && idx <= 7 ) return (r64[idx-4]>>8)&0xFFL;
    return r64[idx]&0xFFL;
  }
  private void writeReg8( int idx, long v ) {
    if( !rex_present && idx >= 4 && idx <= 7 ) {
      int base = idx - 4;
      r64[base] = (r64[base] & ~0xFF00L) | ((v & 0xFFL) << 8);
      return;
    }
    r64[idx] = (r64[idx] & ~0xFFL) | (v & 0xFFL);
  }

  // RIP-relative fix-up + FS segment adjust.
  // Call after every decodeModRM, passing the returned `next` and fs_prefix.
  private void fixEA( long next, boolean fs ) {
    if( mrm_rm == -1 ) mrm_ea += next;        // RIP-relative: mrm_ea was just disp32
    if( fs && mrm_mod != 3 ) mrm_ea += fs_base;
  }

  // --- フラグ計算 ---

  private void setFlags64Sub( long a, long b ) {
    long result = a - b;
    zf = (result==0)?1:0; sf=(result<0)?1:0;
    of=(((a^b)&(a^result))<0)?1:0;
    cf=Long.compareUnsigned(a,b)<0?1:0;
  }

  private void setFlags64Add( long a, long b ) {
    long result = a + b;
    zf=(result==0)?1:0; sf=(result<0)?1:0;
    of=(((a^~b)&(a^result))<0)?1:0;
    cf=Long.compareUnsigned(result,a)<0?1:0;
  }

  private void setFlags32Sub( long a, long b ) {
    a &= 0xFFFFFFFFL; b &= 0xFFFFFFFFL;
    long r = (a - b) & 0xFFFFFFFFL;
    zf=(r==0)?1:0; sf=(int)(r>>31)&1;
    of=(int)(((a^b)&(a^r))>>31)&1;
    cf=Long.compareUnsigned(a,b)<0?1:0;
  }

  private void setFlags32Add( long a, long b ) {
    a &= 0xFFFFFFFFL; b &= 0xFFFFFFFFL;
    long result = a + b;
    long r = result & 0xFFFFFFFFL;
    zf=(r==0)?1:0; sf=(int)(r>>31)&1;
    of=(int)(((a^~b)&(a^r))>>31)&1;
    cf=result>0xFFFFFFFFL?1:0;
  }

  // --- SSE2 バイト演算ヘルパー ---

  private static long pcmpeqb( long a, long b ) {
    long r=0;
    for(int i=0;i<8;i++){
      int ab=(int)(a>>(i*8))&0xFF, bb=(int)(b>>(i*8))&0xFF;
      if(ab==bb) r|=(0xFFL<<(i*8));
    }
    return r;
  }

  private static long pminub( long a, long b ) {
    long r=0;
    for(int i=0;i<8;i++){
      int ab=(int)(a>>(i*8))&0xFF, bb=(int)(b>>(i*8))&0xFF;
      r|=((long)(ab<bb?ab:bb)<<(i*8));
    }
    return r;
  }

  private static long punpckhalf8( long lo_a, long lo_b ) {
    // lo_a: bytes 0..3, lo_b: bytes 0..3 → interleave into 8 bytes
    long r=0;
    for(int i=0;i<4;i++){
      r|=(long)((int)(lo_a>>(i*8))&0xFF)<<(i*16);
      r|=(long)((int)(lo_b>>(i*8))&0xFF)<<(i*16+8);
    }
    return r;
  }

  private static long punpckhalf16( long lo_a, long lo_b ) {
    long r=0;
    for(int i=0;i<2;i++){
      r|=(lo_a>>(i*16)&0xFFFFL)<<(i*32);
      r|=(lo_b>>(i*16)&0xFFFFL)<<(i*32+16);
    }
    return r;
  }

  private static long paddb( long a, long b ) {
    long r=0;
    for(int i=0;i<8;i++) r|=((long)(((int)(a>>(i*8))+(int)(b>>(i*8)))&0xFF))<<(i*8);
    return r;
  }

  private static long subb( long a, long b ) {
    long r=0;
    for(int i=0;i<8;i++) r|=((long)(((int)(a>>(i*8))-(int)(b>>(i*8)))&0xFF))<<(i*8);
    return r;
  }

  private static long pcmpgtb( long a, long b ) {
    long r=0;
    for(int i=0;i<8;i++){
      byte ab=(byte)(a>>(i*8)), bb=(byte)(b>>(i*8));
      if(ab>bb) r|=(0xFFL<<(i*8));
    }
    return r;
  }

  private static long pavgb( long a, long b ) {
    long r=0;
    for(int i=0;i<8;i++){
      int ab=(int)(a>>(i*8))&0xFF, bb=(int)(b>>(i*8))&0xFF;
      r|=((long)((ab+bb+1)>>1)<<(i*8));
    }
    return r;
  }

  private boolean evalCond( int cond ) {
    switch( cond & 0xF ) {
      case  0: return of!=0;
      case  1: return of==0;
      case  2: return cf!=0;
      case  3: return cf==0;
      case  4: return zf!=0;
      case  5: return zf==0;
      case  6: return cf!=0||zf!=0;
      case  7: return cf==0&&zf==0;
      case  8: return sf!=0;
      case  9: return sf==0;
      case 10: return false;   // JP (pf not tracked)
      case 11: return true;    // JNP
      case 12: return sf!=of;
      case 13: return sf==of;
      case 14: return zf!=0||(sf!=of);
      case 15: return zf==0&&(sf==of);
      default: return false;
    }
  }

  // --- メイン デコード+実行 ---

  private long decode_and_exec( long pc ) {
    boolean rex_w=false, rex_r=false, rex_x=false, rex_b=false;
    boolean fs_prefix=false, op66=false;
    rex_present = false;
    int b0 = mem.load8(pc) & 0xFF;

    // プレフィックス スキャン
    prefix_scan:
    while( true ) {
      switch( b0 ) {
        case 0x66: op66=true; pc++; b0=mem.load8(pc)&0xFF; break;
        case 0x67: pc++; b0=mem.load8(pc)&0xFF; break;  // addr32 (handled in decodeModRM)
        case 0x64: fs_prefix=true; pc++; b0=mem.load8(pc)&0xFF; break;
        case 0x65: pc++; b0=mem.load8(pc)&0xFF; break;  // GS prefix (ignored)
        case 0x2E: pc++; b0=mem.load8(pc)&0xFF; break;  // CS hint
        case 0x3E: pc++; b0=mem.load8(pc)&0xFF; break;  // DS hint
        case 0xF0: pc++; b0=mem.load8(pc)&0xFF; break;  // LOCK
        case 0xF2: pc++; b0=mem.load8(pc)&0xFF; break;  // REPNZ
        default:
          if( (b0&0xF0)==0x40 ) {
            rex_w=(b0&0x08)!=0; rex_r=(b0&0x04)!=0;
            rex_x=(b0&0x02)!=0; rex_b=(b0&0x01)!=0;
            rex_present=true;
            pc++; b0=mem.load8(pc)&0xFF; break;
          }
          break prefix_scan;
      }
    }

    // F3 prefix: ENDBR64 / REP string ops
    if( b0 == 0xF3 ) {
      int b1 = mem.load8(pc+1) & 0xFF;
      if( b1 == 0x0F ) {
        int b2 = mem.load8(pc+2)&0xFF, b3 = mem.load8(pc+3)&0xFF;
        if( b2==0x1E && (b3==0xFA||b3==0xFB) ) return pc+4; // ENDBR64/32
      }
      // REP: optional REX between F3 and op
      boolean rep_rexw = rex_w;
      boolean rep_rex_r=rex_r, rep_rex_x=rex_x, rep_rex_b=rex_b;
      int b_op; long rep_end;
      if( (b1&0xF0)==0x40 ) {
        rep_rexw=(b1&0x08)!=0; rep_rex_r=(b1&0x04)!=0; rep_rex_x=(b1&0x02)!=0; rep_rex_b=(b1&0x01)!=0;
        b_op=mem.load8(pc+2)&0xFF; rep_end=pc+3;
      }
      else { b_op=b1; rep_end=pc+2; }
      if( b_op==0xAA ) { while(r64[R_RCX]!=0){mem.store8(r64[R_RDI],(byte)r64[R_RAX]);r64[R_RDI]++;r64[R_RCX]--;} return rep_end; }
      if( b_op==0xAB ) {
        if(rep_rexw) while(r64[R_RCX]!=0){mem.store64(r64[R_RDI],r64[R_RAX]);r64[R_RDI]+=8;r64[R_RCX]--;}
        else         while(r64[R_RCX]!=0){mem.store32(r64[R_RDI],(int)r64[R_RAX]);r64[R_RDI]+=4;r64[R_RCX]--;}
        return rep_end;
      }
      if( b_op==0xA4 ) { while(r64[R_RCX]!=0){mem.store8(r64[R_RDI],mem.load8(r64[R_RSI]));r64[R_RDI]++;r64[R_RSI]++;r64[R_RCX]--;} return rep_end; }
      if( b_op==0xA5 ) {
        if(rep_rexw) while(r64[R_RCX]!=0){mem.store64(r64[R_RDI],mem.load64(r64[R_RSI]));r64[R_RDI]+=8;r64[R_RSI]+=8;r64[R_RCX]--;}
        else         while(r64[R_RCX]!=0){mem.store32(r64[R_RDI],mem.load32(r64[R_RSI]));r64[R_RDI]+=4;r64[R_RSI]+=4;r64[R_RCX]--;}
        return rep_end;
      }
      // REPE SCAS (F3 AE/AF) — treat as "not found" (ZF=0, RCX=0)
      if( b_op==0xAE||b_op==0xAF ) { r64[R_RCX]=0; zf=0; return rep_end; }
      // F3 0F (with or without embedded REX): SSE scalar / MOVDQU
      if( b_op==0x0F || b1==0x0F ) {
        int b2_off = (b_op==0x0F) ? (int)(rep_end-pc) : 2;  // offset of SSE opcode from pc
        int b2=mem.load8(pc+b2_off)&0xFF;
        // F3 0F 6F: MOVDQU xmm, xmm/m128
        if( b2==0x6F ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          int dst=mrm_reg, src=mrm_rm;
          if(mrm_mod==3){xmm_lo[dst]=xmm_lo[src];xmm_hi[dst]=xmm_hi[src];}
          else{xmm_lo[dst]=mem.load64(mrm_ea);xmm_hi[dst]=mem.load64(mrm_ea+8);}
          return xnext;
        }
        // F3 0F 7F: MOVDQU xmm/m128, xmm
        if( b2==0x7F ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          int dst=mrm_reg, src=mrm_rm;
          if(mrm_mod==3){xmm_lo[src]=xmm_lo[dst];xmm_hi[src]=xmm_hi[dst];}
          else{mem.store64(mrm_ea,xmm_lo[dst]);mem.store64(mrm_ea+8,xmm_hi[dst]);}
          return xnext;
        }
        // F3 0F 7E: MOVQ xmm, xmm/m64 (load low 64 bits, zero high)
        if( b2==0x7E ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          int dst=mrm_reg, src=mrm_rm;
          if(mrm_mod==3) xmm_lo[dst]=xmm_lo[src];
          else           xmm_lo[dst]=mem.load64(mrm_ea);
          xmm_hi[dst]=0;
          return xnext;
        }
        // F3 0F 10: MOVSS xmm, xmm/m32
        if( b2==0x10 ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          int dst=mrm_reg, src=mrm_rm;
          if(mrm_mod==3) xmm_lo[dst]=(xmm_lo[dst]&0xFFFFFFFF00000000L)|(xmm_lo[src]&0xFFFFFFFFL);
          else           xmm_lo[dst]=(xmm_lo[dst]&0xFFFFFFFF00000000L)|(mem.load32(mrm_ea)&0xFFFFFFFFL);
          return xnext;
        }
        // F3 0F 11: MOVSS xmm/m32, xmm
        if( b2==0x11 ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); fixEA(xnext,fs_prefix);
          int dst=mrm_reg, src=mrm_rm;
          if(mrm_mod==3) xmm_lo[src]=(xmm_lo[src]&0xFFFFFFFF00000000L)|(xmm_lo[dst]&0xFFFFFFFFL);
          else mem.store32(mrm_ea,(int)xmm_lo[dst]);
          return xnext;
        }
        // F3 0F 1E (ENDBR/CET/NOP variants) — NOP
        if( b2==0x1E||b2==0x1F ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); return xnext;
        }
        // F3 0F 58/59/5C/5E/51/52/53/54: scalar FP — NOP (float ops not emulated)
        if( b2==0x58||b2==0x59||b2==0x5C||b2==0x5E||b2==0x51||b2==0x52||b2==0x53||b2==0x54 ) {
          long xnext=decodeModRM(pc+b2_off+1,rep_rex_r,rep_rex_b,rep_rex_x,false); return xnext;
        }
      }
      process.println("Cpu64: unsupported F3 op="+Integer.toHexString(b_op)+" at 0x"+Long.toHexString(pc));
      process.set_exit_flag(); return pc;
    }

    // 0F escape
    if( b0 == 0x0F ) {
      int b1 = mem.load8(pc+1) & 0xFF;
      if( b1==0x05 ) return exec_syscall(pc+2); // SYSCALL
      if( (b1&0xF0)==0x80 ) { // Jcc rel32
        int rel32=(int)loadImm32u(pc+2); long next=pc+6;
        return evalCond(b1&0xF)?next+rel32:next;
      }
      if( (b1&0xF0)==0x40 ) { // CMOVcc r, r/m
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        if(evalCond(b1&0xF)) { if(rex_w) r64[mrm_reg]=readRM64(); else r64[mrm_reg]=readRM32(); }
        return next;
      }
      if( (b1&0xF0)==0x90 ) { // SETcc r/m8
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        long val=evalCond(b1&0xF)?1L:0L;
        writeRM8(val); return next;
      }
      if( b1==0x1F ) { // multi-byte NOP
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); return next;
      }
      if( b1==0xB0 ) { // CMPXCHG r/m8, r8
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        long dst=readRM8(), al=r64[R_RAX]&0xFFL;
        long diff=(al-dst)&0xFFL; zf=(diff==0)?1:0; sf=(int)(diff>>7)&1;
        if(zf==1) writeRM8(readReg8(mrm_reg)); else r64[R_RAX]=(r64[R_RAX]&~0xFFL)|dst;
        return next;
      }
      if( b1==0xB1 ) { // CMPXCHG r/m, r
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        if(rex_w) {
          long dst=readRM64(), ax=r64[R_RAX];
          setFlags64Sub(ax,dst); zf=(ax==dst)?1:0;
          if(zf==1) writeRM64(r64[mrm_reg]); else r64[R_RAX]=dst;
        } else {
          long dst=readRM32()&0xFFFFFFFFL, ax=r64[R_RAX]&0xFFFFFFFFL;
          setFlags32Sub(ax,dst); zf=(ax==dst)?1:0;
          if(zf==1) writeRM32(r64[mrm_reg]); else r64[R_RAX]=dst;
        }
        return next;
      }
      if( b1==0xAF ) { // IMUL r, r/m
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        long src=rex_w?readRM64():readRM32();
        long res=r64[mrm_reg]*src;
        if(rex_w) r64[mrm_reg]=res; else r64[mrm_reg]=res&0xFFFFFFFFL;
        of=0; cf=0; return next;
      }
      if( b1==0xB6 ) { // MOVZX r, r/m8
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        r64[mrm_reg]=readRM8(); return next;
      }
      if( b1==0xBE ) { // MOVSX r, r/m8
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        long v=(long)(byte)readRM8();
        r64[mrm_reg]=rex_w?v:(v&0xFFFFFFFFL); return next;
      }
      if( b1==0xB7 ) { // MOVZX r, r/m16
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        r64[mrm_reg]=readRM16(); return next;
      }
      if( b1==0xBF ) { // MOVSX r, r/m16
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        long v=(long)(short)readRM16();
        r64[mrm_reg]=rex_w?v:(v&0xFFFFFFFFL); return next;
      }
      if( b1==0xBA ) { // Grp8 bit: BT/BTS/BTR/BTC r/m, imm8
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false);
        int bit=(mem.load8(next)&0xFF)&(rex_w?63:31); next++;
        fixEA(next,fs_prefix);
        long dst=rex_w?readRM64():readRM32();
        cf=(int)(dst>>bit)&1;
        // /5=BTS /6=BTR /7=BTC
        if(mrm_reg==5){long r=dst|(1L<<bit); if(rex_w)writeRM64(r);else writeRM32(r&0xFFFFFFFFL);}
        else if(mrm_reg==6){long r=dst&~(1L<<bit); if(rex_w)writeRM64(r);else writeRM32(r&0xFFFFFFFFL);}
        else if(mrm_reg==7){long r=dst^(1L<<bit); if(rex_w)writeRM64(r);else writeRM32(r&0xFFFFFFFFL);}
        return next;
      }
      if( b1==0xA3 ) { // BT r/m, r
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int bit=(int)(r64[mrm_reg]&(rex_w?63:31));
        long dst=rex_w?readRM64():readRM32();
        cf=(int)(dst>>bit)&1; return next;
      }
      if( b1==0xAB ) { // BTS r/m, r
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int bit=(int)(r64[mrm_reg]&(rex_w?63:31));
        long dst=rex_w?readRM64():readRM32();
        cf=(int)(dst>>bit)&1;
        long r=dst|(1L<<bit); if(rex_w)writeRM64(r);else writeRM32(r&0xFFFFFFFFL);
        return next;
      }
      if( b1==0x31 ) { // RDTSC
        r64[R_RAX]=System.nanoTime()&0xFFFFFFFFL;
        r64[R_RDX]=0; return pc+2;
      }
      // SHLD r/m, r, imm8 (0F A4) / SHLD r/m, r, CL (0F A5)
      if( b1==0xA4 || b1==0xA5 ) {
        long next = decodeModRM(pc+2, rex_r, rex_b, rex_x, false);
        long imm; long n;
        if( b1==0xA4 ) { imm = mem.load8(next) & 0xFFL; next++; n = imm; }
        else           { n = r64[R_RCX] & 0xFFL; }
        fixEA(next, fs_prefix);
        long dst = rex_w ? readRM64() : (op66 ? readRM16()&0xFFFFL : readRM32()&0xFFFFFFFFL);
        long src = rex_w ? r64[mrm_reg] : (op66 ? r64[mrm_reg]&0xFFFFL : r64[mrm_reg]&0xFFFFFFFFL);
        int size = rex_w ? 64 : (op66 ? 16 : 32);
        n &= (rex_w ? 0x3F : 0x1F);  // shift count masked
        if( n != 0 ) {
          long mask = (rex_w ? -1L : (1L << size) - 1L);
          long res;
          if( rex_w ) res = (dst << n) | (src >>> (64 - n));
          else        res = ((dst << n) | (src >>> (size - n))) & mask;
          if( rex_w )      writeRM64(res);
          else if( op66 )  writeRM16((short)res);
          else             writeRM32(res);
          // フラグ: 簡易 (ZF/SF のみ更新、CF/OF は IA32 仕様に近い)
          long r2 = rex_w ? res : (res & mask);
          zf = (r2 == 0) ? 1 : 0;
          sf = (int)(r2 >>> (size-1)) & 1;
          cf = (int)((dst >>> (size - n)) & 1);
        }
        return next;
      }

      // BSWAP r — 0F C8+rd (32-bit unless REX.W: 64-bit)
      if( (b1 & 0xF8) == 0xC8 ) {
        int idx = (b1 & 7) | (rex_b ? 8 : 0);
        long v = r64[idx];
        if( rex_w ) {
          long r = ((v & 0xFFL) << 56) | ((v >> 56) & 0xFFL)
                 | ((v & 0xFF00L) << 40) | ((v >> 40) & 0xFF00L)
                 | ((v & 0xFF0000L) << 24) | ((v >> 24) & 0xFF0000L)
                 | ((v & 0xFF000000L) << 8) | ((v >> 8) & 0xFF000000L);
          r64[idx] = r;
        } else {
          int x = (int)v;
          int r = ((x & 0xFF) << 24) | ((x & 0xFF00) << 8)
                | ((x >> 8) & 0xFF00) | ((x >>> 24) & 0xFF);
          r64[idx] = ((long)r) & 0xFFFFFFFFL;
        }
        return pc+2;
      }
      if( b1==0xA2 ) { // CPUID
        long leaf=r64[R_RAX]&0xFFFFFFFFL;
        if(leaf==0){ r64[R_RAX]=0; r64[R_RBX]=0; r64[R_RCX]=0; r64[R_RDX]=0; }
        else { r64[R_RAX]=0; r64[R_RBX]=0; r64[R_RCX]=0; r64[R_RDX]=0; }
        return pc+2;
      }
      if( b1==0xBC ) { // BSF r, r/m
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        long src=rex_w?readRM64():(readRM32()&0xFFFFFFFFL);
        if(src==0){zf=1;}
        else {
          zf=0;
          // BSF は最下位ビットを返すので 32/64 で結果は同じ (但し 32bit 値の
          // 上位 32bit が 0 なので Long.numberOfTrailingZeros は正しく動く)
          if( rex_w ) r64[mrm_reg]=Long.numberOfTrailingZeros(src);
          else        r64[mrm_reg]=Long.numberOfTrailingZeros(src) & 0xFFFFFFFFL;
        }
        return next;
      }
      if( b1==0xBD ) { // BSR r, r/m
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        long src=rex_w?readRM64():(readRM32()&0xFFFFFFFFL);
        if(src==0){zf=1;}
        else {
          zf=0;
          // 32-bit 時は Integer.numberOfLeadingZeros を使う (Long で数えると
          // 64bit 視点で 32 ずれる)。
          int idx = rex_w ? (63 - Long.numberOfLeadingZeros(src))
                          : (31 - Integer.numberOfLeadingZeros((int)src));
          if( rex_w ) r64[mrm_reg] = idx;
          else        r64[mrm_reg] = idx & 0xFFFFFFFFL;
        }
        return next;
      }
      // --- SSE2 (66 0F prefix) ---
      if( op66 ) {
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        // load 128-bit source (XMM or memory)
        long sl, sh;
        if(mrm_mod==3){ sl=xmm_lo[src]; sh=xmm_hi[src]; }
        else { sl=mem.load64(mrm_ea); sh=mem.load64(mrm_ea+8); }
        if( b1==0x12 ) { // MOVLPD xmm, m64 (load low 64 bits, keep high)
          if(mrm_mod!=3) xmm_lo[dst]=mem.load64(mrm_ea); return next;
        }
        if( b1==0x13 ) { // MOVLPD m64, xmm (store low 64 bits)
          if(mrm_mod!=3) mem.store64(mrm_ea,xmm_lo[dst]); return next;
        }
        if( b1==0x16 ) { // MOVHPD xmm, m64 (load high 64 bits, keep low)
          if(mrm_mod!=3) xmm_hi[dst]=mem.load64(mrm_ea); return next;
        }
        if( b1==0x17 ) { // MOVHPD m64, xmm (store high 64 bits)
          if(mrm_mod!=3) mem.store64(mrm_ea,xmm_hi[dst]); return next;
        }
        if( b1==0x6E ) { // MOVD xmm, r/m32 (or MOVQ with REX.W)
          xmm_lo[dst]= rex_w ? (mrm_mod==3?r64[src]:mem.load64(mrm_ea)) : ((mrm_mod==3?r64[src]:mem.load32(mrm_ea))&0xFFFFFFFFL);
          xmm_hi[dst]=0; return next;
        }
        if( b1==0x7E ) { // MOVD r/m32, xmm
          if(mrm_mod==3) r64[src]=(rex_w?xmm_lo[dst]:xmm_lo[dst]&0xFFFFFFFFL); else {if(rex_w)mem.store64(mrm_ea,xmm_lo[dst]);else mem.store32(mrm_ea,(int)xmm_lo[dst]);} return next;
        }
        if( b1==0x6F ) { // MOVDQA xmm, xmm/m128
          xmm_lo[dst]=sl; xmm_hi[dst]=sh; return next;
        }
        if( b1==0x7F ) { // MOVDQA xmm/m128, xmm
          if(mrm_mod==3){xmm_lo[src]=xmm_lo[dst];xmm_hi[src]=xmm_hi[dst];}
          else{mem.store64(mrm_ea,xmm_lo[dst]);mem.store64(mrm_ea+8,xmm_hi[dst]);} return next;
        }
        if( b1==0xEF ) { // PXOR
          xmm_lo[dst]^=sl; xmm_hi[dst]^=sh; return next;
        }
        if( b1==0xEB ) { // POR
          xmm_lo[dst]|=sl; xmm_hi[dst]|=sh; return next;
        }
        if( b1==0xDB ) { // PAND
          xmm_lo[dst]&=sl; xmm_hi[dst]&=sh; return next;
        }
        if( b1==0xDF ) { // PANDN
          xmm_lo[dst]=(~xmm_lo[dst])&sl; xmm_hi[dst]=(~xmm_hi[dst])&sh; return next;
        }
        if( b1==0x75 ) { // PCMPEQW
          long lo=0,hi=0; for(int i=0;i<4;i++){long a=(xmm_lo[dst]>>(i*16))&0xFFFFL,b2=(sl>>(i*16))&0xFFFFL;lo|=(a==b2?0xFFFFL:0)<<(i*16);a=(xmm_hi[dst]>>(i*16))&0xFFFFL;b2=(sh>>(i*16))&0xFFFFL;hi|=(a==b2?0xFFFFL:0)<<(i*16);}
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0x76 ) { // PCMPEQD
          long lo=0,hi=0; for(int i=0;i<2;i++){long a=(xmm_lo[dst]>>(i*32))&0xFFFFFFFFL,b2=(sl>>(i*32))&0xFFFFFFFFL;lo|=(a==b2?0xFFFFFFFFL:0)<<(i*32);a=(xmm_hi[dst]>>(i*32))&0xFFFFFFFFL;b2=(sh>>(i*32))&0xFFFFFFFFL;hi|=(a==b2?0xFFFFFFFFL:0)<<(i*32);}
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0x74 ) { // PCMPEQB
          xmm_lo[dst]=pcmpeqb(xmm_lo[dst],sl); xmm_hi[dst]=pcmpeqb(xmm_hi[dst],sh); return next;
        }
        if( b1==0xDA ) { // PMINUB
          xmm_lo[dst]=pminub(xmm_lo[dst],sl); xmm_hi[dst]=pminub(xmm_hi[dst],sh); return next;
        }
        if( b1==0x60 ) { // PUNPCKLBW
          xmm_hi[dst]=punpckhalf8(xmm_lo[dst]>>32, sl>>32);
          xmm_lo[dst]=punpckhalf8(xmm_lo[dst], sl); return next;
        }
        if( b1==0x61 ) { // PUNPCKLWD
          xmm_hi[dst]=punpckhalf16(xmm_lo[dst]>>32, sl>>32);
          xmm_lo[dst]=punpckhalf16(xmm_lo[dst], sl); return next;
        }
        if( b1==0x62 ) { // PUNPCKLDQ
          long old_lo=xmm_lo[dst];
          xmm_hi[dst]=((old_lo>>>32)&0xFFFFFFFFL)|(sl&0xFFFFFFFF00000000L);
          xmm_lo[dst]=(old_lo&0xFFFFFFFFL)|((sl&0xFFFFFFFFL)<<32); return next;
        }
        if( b1==0x68 ) { // PUNPCKHBW xmm, xmm/m128
          long lo=punpckhalf8(xmm_hi[dst],sh); xmm_hi[dst]=punpckhalf8(xmm_hi[dst]>>32,sh>>32); xmm_lo[dst]=lo; return next;
        }
        if( b1==0x69 ) { // PUNPCKHWD xmm, xmm/m128
          long lo=punpckhalf16(xmm_hi[dst],sh); xmm_hi[dst]=punpckhalf16(xmm_hi[dst]>>32,sh>>32); xmm_lo[dst]=lo; return next;
        }
        if( b1==0x6A ) { // PUNPCKHDQ xmm, xmm/m128
          long new_lo=(xmm_hi[dst]&0xFFFFFFFFL)|((sh&0xFFFFFFFFL)<<32);
          long new_hi=((xmm_hi[dst]>>>32)&0xFFFFFFFFL)|(sh&0xFFFFFFFF00000000L);
          xmm_lo[dst]=new_lo; xmm_hi[dst]=new_hi; return next;
        }
        if( b1==0x6C ) { // PUNPCKLQDQ xmm, xmm/m128
          xmm_hi[dst]=sl; return next; // lo stays, hi = src_lo
        }
        if( b1==0x6D ) { // PUNPCKHQDQ xmm, xmm/m128
          xmm_lo[dst]=xmm_hi[dst]; xmm_hi[dst]=sh; return next;
        }
        if( b1==0x70 ) { // PSHUFD xmm, xmm/m128, imm8
          int imm=mem.load8(next)&0xFF; next++;
          long[] t=new long[]{sl&0xFFFFFFFFL,(sl>>>32)&0xFFFFFFFFL,sh&0xFFFFFFFFL,(sh>>>32)&0xFFFFFFFFL};
          long r0=t[imm&3],r1=t[(imm>>2)&3],r2=t[(imm>>4)&3],r3=t[(imm>>6)&3];
          xmm_lo[dst]=r0|(r1<<32); xmm_hi[dst]=r2|(r3<<32); return next;
        }
        if( b1==0xD7 ) { // PMOVMSKB r32, xmm
          long mask=0;
          for(int i=0;i<8;i++) if(((xmm_lo[src]>>(i*8))&0x80)!=0) mask|=(1L<<i);
          for(int i=0;i<8;i++) if(((xmm_hi[src]>>(i*8))&0x80)!=0) mask|=(1L<<(i+8));
          r64[dst]=mask; return next;
        }
        if( b1==0xD6 ) { // MOVQ xmm/m64, xmm (store low 64 bits)
          if(mrm_mod==3){xmm_lo[src]=xmm_lo[dst];xmm_hi[src]=0;}
          else{mem.store64(mrm_ea,xmm_lo[dst]);} return next;
        }
        if( b1==0xFB ) { // PSUBQ
          xmm_lo[dst]-=sl; xmm_hi[dst]-=sh; return next;
        }
        if( b1==0xFA ) { // PSUBD (4 dwords each half)
          long lo=0,hi=0;
          for(int i=0;i<2;i++){
            int a=(int)(xmm_lo[dst]>>(i*32)), b=(int)(sl>>(i*32));
            lo|=((long)(a-b) & 0xFFFFFFFFL) << (i*32);
            a=(int)(xmm_hi[dst]>>(i*32)); b=(int)(sh>>(i*32));
            hi|=((long)(a-b) & 0xFFFFFFFFL) << (i*32);
          }
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0xFE ) { // PADDD (4 dwords each half)
          long lo=0,hi=0;
          for(int i=0;i<2;i++){
            int a=(int)(xmm_lo[dst]>>(i*32)), b=(int)(sl>>(i*32));
            lo|=((long)(a+b) & 0xFFFFFFFFL) << (i*32);
            a=(int)(xmm_hi[dst]>>(i*32)); b=(int)(sh>>(i*32));
            hi|=((long)(a+b) & 0xFFFFFFFFL) << (i*32);
          }
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0xD4 ) { // PADDQ
          xmm_lo[dst]+=sl; xmm_hi[dst]+=sh; return next;
        }
        if( b1==0xFC ) { // PADDB
          xmm_lo[dst]=paddb(xmm_lo[dst],sl); xmm_hi[dst]=paddb(xmm_hi[dst],sh); return next;
        }
        if( b1==0xE8 ) { // PSUBSB (signed saturate)
          xmm_lo[dst]=subb(xmm_lo[dst],sl); xmm_hi[dst]=subb(xmm_hi[dst],sh); return next;
        }
        if( b1==0xF8 ) { // PSUBB (wrapping byte subtract)
          long lo=0,hi=0; for(int i=0;i<8;i++){lo|=(((xmm_lo[dst]>>(i*8))-(sl>>(i*8)))&0xFFL)<<(i*8);hi|=(((xmm_hi[dst]>>(i*8))-(sh>>(i*8)))&0xFFL)<<(i*8);}
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0x65 ) { // PCMPGTW
          long lo=0,hi=0; for(int i=0;i<4;i++){short a=(short)((xmm_lo[dst]>>(i*16))&0xFFFF),b2=(short)((sl>>(i*16))&0xFFFF);lo|=(a>b2?0xFFFFL:0)<<(i*16);a=(short)((xmm_hi[dst]>>(i*16))&0xFFFF);b2=(short)((sh>>(i*16))&0xFFFF);hi|=(a>b2?0xFFFFL:0)<<(i*16);}
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0x66 ) { // PCMPGTD
          long lo=0,hi=0; for(int i=0;i<2;i++){int a=(int)(xmm_lo[dst]>>(i*32)),b2=(int)(sl>>(i*32));lo|=(a>b2?0xFFFFFFFFL:0)<<(i*32);a=(int)(xmm_hi[dst]>>(i*32));b2=(int)(sh>>(i*32));hi|=(a>b2?0xFFFFFFFFL:0)<<(i*32);}
          xmm_lo[dst]=lo; xmm_hi[dst]=hi; return next;
        }
        if( b1==0x64 ) { // PCMPGTB
          xmm_lo[dst]=pcmpgtb(xmm_lo[dst],sl); xmm_hi[dst]=pcmpgtb(xmm_hi[dst],sh); return next;
        }
        if( b1==0xE0 ) { // PAVGB
          xmm_lo[dst]=pavgb(xmm_lo[dst],sl); xmm_hi[dst]=pavgb(xmm_hi[dst],sh); return next;
        }
        if( b1==0x73 ) { // PSLLDQ/PSRLDQ (shift 128-bit by imm8 bytes) via Grp14
          int grp=mrm_reg, imm=mem.load8(next)&0xFF; next++;
          if(grp==7){ // PSLLDQ xmm, imm8 (shift left logical by bytes)
            if(imm>=16){xmm_lo[src]=0;xmm_hi[src]=0;}
            else if(imm>=8){xmm_hi[src]=xmm_lo[src]<<((imm-8)*8);xmm_lo[src]=0;}
            else if(imm>0){xmm_hi[src]=(xmm_hi[src]<<(imm*8))|(xmm_lo[src]>>>(64-imm*8));xmm_lo[src]<<=imm*8;}
          } else if(grp==3){ // PSRLDQ xmm, imm8 (shift right logical by bytes)
            if(imm>=16){xmm_lo[src]=0;xmm_hi[src]=0;}
            else if(imm>=8){xmm_lo[src]=xmm_hi[src]>>>((imm-8)*8);xmm_hi[src]=0;}
            else if(imm>0){xmm_lo[src]=(xmm_lo[src]>>>(imm*8))|(xmm_hi[src]<<(64-imm*8));xmm_hi[src]>>>=imm*8;}
          } return next;
        }
        process.println("Cpu64: unsupported SSE2 66 0F "+Integer.toHexString(b1)+" at 0x"+Long.toHexString(pc));
        process.set_exit_flag(); return pc;
      }
      if( b1==0x16 ) { // MOVLHPS xmm, xmm2 (mod=3) / MOVHPS xmm, m64 (mod!=3)
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        if(mrm_mod==3) xmm_hi[dst]=xmm_lo[src];
        else           xmm_hi[dst]=mem.load64(mrm_ea);
        return next;
      }
      if( b1==0x17 ) { // MOVHPS m64, xmm (store high 64 bits to memory)
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg;
        mem.store64(mrm_ea,xmm_hi[dst]);
        return next;
      }
      if( b1==0x12 ) { // MOVHLPS xmm, xmm2 (mod=3) / MOVLPS xmm, m64 (mod!=3)
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        if(mrm_mod==3) xmm_lo[dst]=xmm_hi[src];
        else           xmm_lo[dst]=mem.load64(mrm_ea);
        return next;
      }
      if( b1==0x10 ) { // MOVUPS xmm, xmm/m128
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        if(mrm_mod==3){xmm_lo[dst]=xmm_lo[src];xmm_hi[dst]=xmm_hi[src];}
        else{xmm_lo[dst]=mem.load64(mrm_ea);xmm_hi[dst]=mem.load64(mrm_ea+8);}
        return next;
      }
      if( b1==0x11 ) { // MOVUPS xmm/m128, xmm
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        if(mrm_mod==3){xmm_lo[src]=xmm_lo[dst];xmm_hi[src]=xmm_hi[dst];}
        else{mem.store64(mrm_ea,xmm_lo[dst]);mem.store64(mrm_ea+8,xmm_hi[dst]);}
        return next;
      }
      if( b1==0x28 ) { // MOVAPS xmm, xmm/m128
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        if(mrm_mod==3){xmm_lo[dst]=xmm_lo[src];xmm_hi[dst]=xmm_hi[src];}
        else{xmm_lo[dst]=mem.load64(mrm_ea);xmm_hi[dst]=mem.load64(mrm_ea+8);}
        return next;
      }
      if( b1==0x29 ) { // MOVAPS xmm/m128, xmm
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        if(mrm_mod==3){xmm_lo[src]=xmm_lo[dst];xmm_hi[src]=xmm_hi[dst];}
        else{mem.store64(mrm_ea,xmm_lo[dst]);mem.store64(mrm_ea+8,xmm_hi[dst]);}
        return next;
      }
      if( b1==0x57 ) { // XORPS xmm, xmm/m128 (= bitwise XOR; よくゼロクリアに使う)
        long next=decodeModRM(pc+2,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
        int dst=mrm_reg, src=mrm_rm;
        long sl, sh;
        if(mrm_mod==3){ sl=xmm_lo[src]; sh=xmm_hi[src]; }
        else { sl=mem.load64(mrm_ea); sh=mem.load64(mrm_ea+8); }
        xmm_lo[dst]^=sl; xmm_hi[dst]^=sh;
        return next;
      }
      process.println("Cpu64: unsupported 0F "+Integer.toHexString(b1)+" at 0x"+Long.toHexString(pc));
      process.set_exit_flag(); return pc;
    }

    // NOP / XCHG rAX, r8 (REX.B 付きで xchg rax, r8、それ以外は NOP)
    if( b0==0x90 ) {
      // 90 単独 → NOP。REX.B 付きなら xchg rAX, r8。
      if( rex_b ) {
        long t = r64[R_RAX];
        r64[R_RAX] = r64[8];
        r64[8] = t;
        if( !rex_w ) {
          // 32bit 版: 上位 32bit はゼロ
          r64[R_RAX] &= 0xFFFFFFFFL;
          r64[8]     &= 0xFFFFFFFFL;
        }
      }
      return pc+1;
    }

    // HLT (F4) — treat as exit(0) for init process
    if( b0==0xF4 ) { process.set_exit_flag(); return pc+1; }

    // PUSH r64 (50+rd)
    if( b0>=0x50 && b0<=0x57 ) { push64(r64[(b0&7)|(rex_b?8:0)]); return pc+1; }
    // POP r64 (58+rd)
    if( b0>=0x58 && b0<=0x5F ) { r64[(b0&7)|(rex_b?8:0)]=pop64(); return pc+1; }

    // PUSH imm8 (6A)
    if( b0==0x6A ) { push64((long)(byte)mem.load8(pc+1)); return pc+2; }
    // PUSH imm32 (68)
    if( b0==0x68 ) { push64((long)(int)loadImm32u(pc+1)); return pc+5; }

    // LEAVE (C9)
    if( b0==0xC9 ) { r64[R_RSP]=r64[R_RBP]; r64[R_RBP]=pop64(); return pc+1; }
    // RET (C3)
    if( b0==0xC3 ) { return pop64(); }
    // RET imm16 (C2) — pop + skip bytes
    if( b0==0xC2 ) { long a=pop64(); r64[R_RSP]+=(loadImm16(pc+1)&0xFFFFL); return a; }

    // CALL rel32 (E8)
    if( b0==0xE8 ) { int rel32=(int)loadImm32u(pc+1); long next=pc+5; push64(next); return next+rel32; }
    // JMP rel8 (EB)
    if( b0==0xEB ) { byte rel8=mem.load8(pc+1); return pc+2+rel8; }
    // JMP rel32 (E9)
    if( b0==0xE9 ) { int rel32=(int)loadImm32u(pc+1); return pc+5+rel32; }
    // Jcc rel8 (70-7F)
    if( b0>=0x70 && b0<=0x7F ) { byte rel8=mem.load8(pc+1); return evalCond(b0&0xF)?pc+2+rel8:pc+2; }

    // CDQE / CWDE (98)
    if( b0==0x98 ) { if(rex_w) r64[R_RAX]=(long)(int)r64[R_RAX]; else r64[R_RAX]=(long)(short)(r64[R_RAX]&0xFFFFL)&0xFFFFFFFFL; return pc+1; }
    // CQO / CDQ (99)
    if( b0==0x99 ) { if(rex_w) r64[R_RDX]=(r64[R_RAX]<0)?-1L:0L; else r64[R_RDX]=((int)r64[R_RAX]<0)?0xFFFFFFFFL:0L; return pc+1; }

    // --- ALU r/m ← r (64/32) ---

    // 0x01: ADD r/m, r
    if( b0==0x01 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long src=r64[mrm_reg], dst=rex_w?readRM64():readRM32();
      long res=dst+src;
      if(rex_w){setFlags64Add(dst,src);writeRM64(res);}
      else{dst&=0xFFFFFFFFL;src&=0xFFFFFFFFL;setFlags32Add(dst,src);writeRM32(res&0xFFFFFFFFL);}
      return next;
    }
    // 0x03: ADD r, r/m
    if( b0==0x03 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long src=rex_w?readRM64():readRM32(), dst=rex_w?r64[mrm_reg]:(r64[mrm_reg]&0xFFFFFFFFL);
      long res=dst+src;
      if(rex_w){setFlags64Add(dst,src);r64[mrm_reg]=res;}
      else{setFlags32Add(dst,src);r64[mrm_reg]=res&0xFFFFFFFFL;}
      return next;
    }
    // 0x09: OR r/m, r
    if( b0==0x09 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long res=(rex_w?readRM64():readRM32())|r64[mrm_reg];
      if(rex_w){writeRM64(res);zf=(res==0)?1:0;sf=(res<0)?1:0;}
      else{res&=0xFFFFFFFFL;writeRM32(res);zf=(res==0)?1:0;sf=(int)(res>>31)&1;}
      of=0;cf=0; return next;
    }
    // 0x0B: OR r, r/m
    if( b0==0x0B ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long res=r64[mrm_reg]|(rex_w?readRM64():readRM32());
      if(rex_w){r64[mrm_reg]=res;zf=(res==0)?1:0;sf=(res<0)?1:0;}
      else{res&=0xFFFFFFFFL;r64[mrm_reg]=res;zf=(res==0)?1:0;sf=(int)(res>>31)&1;}
      of=0;cf=0; return next;
    }
    // 0x11: ADC r/m, r (CF 込み, CF は省略して ADD 扱い)
    if( b0==0x11 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long src=r64[mrm_reg]+cf, dst=rex_w?readRM64():readRM32();
      long res=dst+src;
      if(rex_w){writeRM64(res);}else{writeRM32(res&0xFFFFFFFFL);}
      return next;
    }
    // 0x13: ADC r, r/m
    if( b0==0x13 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long res=r64[mrm_reg]+(rex_w?readRM64():readRM32())+cf;
      if(rex_w) r64[mrm_reg]=res; else r64[mrm_reg]=res&0xFFFFFFFFL;
      return next;
    }
    // 0x19: SBB r/m, r
    if( b0==0x19 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long src=r64[mrm_reg]+cf, dst=rex_w?readRM64():readRM32();
      long res=dst-src;
      if(rex_w){writeRM64(res);}else{writeRM32(res&0xFFFFFFFFL);}
      return next;
    }
    // 0x1B: SBB r, r/m
    if( b0==0x1B ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long res=r64[mrm_reg]-((rex_w?readRM64():readRM32())+cf);
      if(rex_w) r64[mrm_reg]=res; else r64[mrm_reg]=res&0xFFFFFFFFL;
      return next;
    }
    // 0x21: AND r/m, r
    if( b0==0x21 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long res=(rex_w?readRM64():readRM32())&r64[mrm_reg];
      if(rex_w){writeRM64(res);zf=(res==0)?1:0;sf=(res<0)?1:0;}
      else{res&=0xFFFFFFFFL;writeRM32(res);zf=(res==0)?1:0;sf=(int)(res>>31)&1;}
      of=0;cf=0; return next;
    }
    // 0x23: AND r, r/m
    if( b0==0x23 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long res=r64[mrm_reg]&(rex_w?readRM64():readRM32());
      if(rex_w){r64[mrm_reg]=res;zf=(res==0)?1:0;sf=(res<0)?1:0;}
      else{res&=0xFFFFFFFFL;r64[mrm_reg]=res;zf=(res==0)?1:0;sf=(int)(res>>31)&1;}
      of=0;cf=0; return next;
    }
    // 0x29: SUB r/m, r
    if( b0==0x29 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long src=r64[mrm_reg], dst=rex_w?readRM64():readRM32();
      long res=dst-src;
      if(rex_w){setFlags64Sub(dst,src);writeRM64(res);}
      else{dst&=0xFFFFFFFFL;src&=0xFFFFFFFFL;setFlags32Sub(dst,src);writeRM32(res&0xFFFFFFFFL);}
      return next;
    }
    // 0x2B: SUB r, r/m
    if( b0==0x2B ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long src=rex_w?readRM64():readRM32(), dst=rex_w?r64[mrm_reg]:(r64[mrm_reg]&0xFFFFFFFFL);
      long res=dst-src;
      if(rex_w){setFlags64Sub(dst,src);r64[mrm_reg]=res;}
      else{setFlags32Sub(dst,src);r64[mrm_reg]=res&0xFFFFFFFFL;}
      return next;
    }
    // 0x39: CMP r/m, r
    if( b0==0x39 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long src=r64[mrm_reg], dst=rex_w?readRM64():readRM32();
      if(rex_w) setFlags64Sub(dst,src);
      else { setFlags32Sub(dst&0xFFFFFFFFL, src&0xFFFFFFFFL); }
      return next;
    }
    // 0x3B: CMP r, r/m
    if( b0==0x3B ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long src=rex_w?readRM64():readRM32(), dst=rex_w?r64[mrm_reg]:(r64[mrm_reg]&0xFFFFFFFFL);
      if(rex_w) setFlags64Sub(dst,src);
      else { setFlags32Sub(dst&0xFFFFFFFFL, src&0xFFFFFFFFL); }
      return next;
    }

    // --- 8-bit ALU ---

    // 0x30: XOR r/m8, r8
    if( b0==0x30 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long res=(readRM8()^readReg8(mrm_reg))&0xFF;
      writeRM8(res); zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0;cf=0; return next;
    }
    // 0x32: XOR r8, r/m8
    if( b0==0x32 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long res=(readReg8(mrm_reg)^readRM8())&0xFF;
      writeReg8(mrm_reg, res); zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=0;cf=0; return next;
    }
    // 0x38: CMP r/m8, r8
    if( b0==0x38 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long dst=readRM8(), src=readReg8(mrm_reg), res=(dst-src)&0xFF;
      zf=(res==0)?1:0;sf=(int)(res>>7)&1;
      of=(int)(((dst^src)&(dst^res))>>7)&1;cf=Long.compareUnsigned(dst,src)<0?1:0; return next;
    }
    // 0x3A: CMP r8, r/m8
    if( b0==0x3A ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long dst=readReg8(mrm_reg), src=readRM8(), res=(dst-src)&0xFF;
      zf=(res==0)?1:0;sf=(int)(res>>7)&1;
      of=(int)(((dst^src)&(dst^res))>>7)&1;cf=Long.compareUnsigned(dst,src)<0?1:0; return next;
    }
    // 0x08: OR r/m8, r8
    if( b0==0x08 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long res=(readRM8()|readReg8(mrm_reg))&0xFF;
      writeRM8(res); zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=0;cf=0; return next;
    }
    // 0x0A: OR r8, r/m8
    if( b0==0x0A ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long res=(readReg8(mrm_reg)|readRM8())&0xFF;
      writeReg8(mrm_reg, res); zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=0;cf=0; return next;
    }
    // 0x20: AND r/m8, r8
    if( b0==0x20 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long res=(readRM8()&readReg8(mrm_reg))&0xFF;
      writeRM8(res); zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=0;cf=0; return next;
    }
    // 0x22: AND r8, r/m8
    if( b0==0x22 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long res=(readReg8(mrm_reg)&readRM8())&0xFF;
      writeReg8(mrm_reg, res); zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=0;cf=0; return next;
    }
    // 0x28: SUB r/m8, r8
    if( b0==0x28 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long dst=readRM8(), src=readReg8(mrm_reg), res=(dst-src)&0xFF;
      writeRM8(res); zf=(res==0)?1:0;sf=(int)(res>>7)&1;
      of=(int)(((dst^src)&(dst^res))>>7)&1;cf=Long.compareUnsigned(dst,src)<0?1:0; return next;
    }
    // 0x2A: SUB r8, r/m8
    if( b0==0x2A ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long dst=readReg8(mrm_reg), src=readRM8(), res=(dst-src)&0xFF;
      writeReg8(mrm_reg, res); zf=(res==0)?1:0;sf=(int)(res>>7)&1;
      of=(int)(((dst^src)&(dst^res))>>7)&1;cf=Long.compareUnsigned(dst,src)<0?1:0; return next;
    }
    // 0x02: ADD r8, r/m8
    if( b0==0x02 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long res=(readReg8(mrm_reg)+readRM8())&0xFF;
      writeReg8(mrm_reg, res); return next;
    }
    // 0x00: ADD r/m8, r8
    if( b0==0x00 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long res=(readRM8()+readReg8(mrm_reg))&0xFF;
      writeRM8(res); return next;
    }

    // --- ALU accumulator,imm8 short forms (8-bit) ---
    // 04=ADD  0C=OR  14=ADC  1C=SBB  24=AND  2C=SUB  34=XOR  3C=CMP
    if( b0==0x04||b0==0x0C||b0==0x14||b0==0x1C||b0==0x24||b0==0x2C||b0==0x34||b0==0x3C ) {
      long imm=mem.load8(pc+1)&0xFFL;
      long al=r64[R_RAX]&0xFFL;
      long res;
      if(b0==0x04||b0==0x14){ res=(al+imm)&0xFF; zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=0;cf=0;r64[R_RAX]=(r64[R_RAX]&~0xFFL)|res; }
      else if(b0==0x0C){ res=(al|imm)&0xFF; zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=0;cf=0;r64[R_RAX]=(r64[R_RAX]&~0xFFL)|res; }
      else if(b0==0x24){ res=(al&imm)&0xFF; zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=0;cf=0;r64[R_RAX]=(r64[R_RAX]&~0xFFL)|res; }
      else if(b0==0x2C||b0==0x1C){ res=(al-imm)&0xFF; cf=Long.compareUnsigned(al,imm)<0?1:0;zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=0;r64[R_RAX]=(r64[R_RAX]&~0xFFL)|res; }
      else if(b0==0x34){ res=(al^imm)&0xFF; zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=0;cf=0;r64[R_RAX]=(r64[R_RAX]&~0xFFL)|res; }
      else{ res=(al-imm)&0xFF; cf=Long.compareUnsigned(al,imm)<0?1:0;zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=0; } // CMP (no write)
      return pc+2;
    }

    // --- ALU accumulator,imm short forms ---
    // 05=ADD  0D=OR  15=ADC  1D=SBB  25=AND  2D=SUB  35=XOR  3D=CMP
    // 66 prefix: 16-bit operand (imm16, AX); REX.W: 64-bit; default: 32-bit (EAX, imm32)
    if( b0==0x05||b0==0x0D||b0==0x15||b0==0x1D||b0==0x25||b0==0x2D||b0==0x35||b0==0x3D ) {
      long imm; long next;
      if( op66 && !rex_w ) { imm = (long)(short)mem.load16(pc+1); next = pc+3; }
      else                 { imm = (long)(int)loadImm32u(pc+1);   next = pc+5; }
      long a = rex_w ? r64[R_RAX]
              : op66 ? (r64[R_RAX]&0xFFFFL) : (r64[R_RAX]&0xFFFFFFFFL);
      long res;
      long mask = rex_w ? -1L : op66 ? 0xFFFFL : 0xFFFFFFFFL;
      int signbit = rex_w ? 63 : op66 ? 15 : 31;
      if(b0==0x05||b0==0x15){
        res=(a+imm)&mask;
        if(rex_w) setFlags64Add(a,imm);
        else if(op66){ a&=0xFFFFL; imm&=0xFFFFL; long r2=a+imm; cf=((r2>>16)&1)==1?1:0; zf=((r2&0xFFFFL)==0)?1:0; sf=(int)(r2>>15)&1; of=(int)(((a^imm^0xFFFFL)&(a^r2))>>15)&1; }
        else setFlags32Add(a,imm);
      }
      else if(b0==0x0D){ res=(a|imm)&mask; of=cf=0; zf=(res==0)?1:0; sf=(int)(res>>signbit)&1; }
      else if(b0==0x25){ res=(a&imm)&mask; of=cf=0; zf=(res==0)?1:0; sf=(int)(res>>signbit)&1; }
      else if(b0==0x2D||b0==0x1D){
        res=(a-imm)&mask;
        if(rex_w) setFlags64Sub(a,imm);
        else if(op66){ a&=0xFFFFL; imm&=0xFFFFL; long r2=(a-imm)&0xFFFFFFFFL; cf=Long.compareUnsigned(a,imm)<0?1:0; zf=((r2&0xFFFFL)==0)?1:0; sf=(int)(r2>>15)&1; of=(int)(((a^imm)&(a^r2))>>15)&1; }
        else setFlags32Sub(a,imm);
      }
      else if(b0==0x35){ res=(a^imm)&mask; of=cf=0; zf=(res==0)?1:0; sf=(int)(res>>signbit)&1; }
      else { /* 0x3D CMP */ res=(a-imm)&mask;
        if(rex_w) setFlags64Sub(a,imm);
        else if(op66){ a&=0xFFFFL; imm&=0xFFFFL; long r2=(a-imm)&0xFFFFFFFFL; cf=Long.compareUnsigned(a,imm)<0?1:0; zf=((r2&0xFFFFL)==0)?1:0; sf=(int)(r2>>15)&1; of=(int)(((a^imm)&(a^r2))>>15)&1; }
        else setFlags32Sub(a,imm);
        res=a; /* CMP doesn't write */
      }
      if(b0!=0x3D){
        if(rex_w)      r64[R_RAX]=res;
        else if(op66)  r64[R_RAX]=(r64[R_RAX]&~0xFFFFL)|(res&0xFFFFL);
        else           r64[R_RAX]=res&0xFFFFFFFFL;
      }
      return next;
    }

    // --- TEST accumulator,imm ---
    // 0xa8: TEST AL, imm8
    if( b0==0xA8 ) {
      int imm=(int)mem.load8(pc+1)&0xFF;
      long res=(r64[R_RAX]&0xFF)&imm; zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=0;cf=0; return pc+2;
    }
    // 0xa9: TEST EAX/RAX, imm32
    if( b0==0xA9 ) {
      long imm=(long)(int)loadImm32u(pc+1);
      long res=(rex_w?r64[R_RAX]:r64[R_RAX]&0xFFFFFFFFL)&imm;
      if(rex_w){zf=(res==0)?1:0;sf=(res<0)?1:0;}
      else{res&=0xFFFFFFFFL;zf=(res==0)?1:0;sf=(int)(res>>31)&1;}
      of=0;cf=0; return pc+5;
    }

    // --- TEST ---
    // 0x84: TEST r/m8, r8
    if( b0==0x84 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long res=readRM8()&readReg8(mrm_reg);
      zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=0;cf=0; return next;
    }
    // 0x85: TEST r/m, r
    if( b0==0x85 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long res=(rex_w?readRM64():readRM32())&r64[mrm_reg];
      if(rex_w){zf=(res==0)?1:0;sf=(res<0)?1:0;}
      else{res&=0xFFFFFFFFL;zf=(res==0)?1:0;sf=(int)(res>>31)&1;}
      of=0;cf=0; return next;
    }

    // --- MOV ---
    // 0x89: MOV r/m, r
    if( b0==0x89 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      if(rex_w) writeRM64(r64[mrm_reg]); else writeRM32(r64[mrm_reg]);
      return next;
    }
    // 0x8B: MOV r, r/m
    if( b0==0x8B ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      if(rex_w) r64[mrm_reg]=readRM64(); else r64[mrm_reg]=readRM32();
      return next;
    }
    // 0x88: MOV r/m8, r8
    if( b0==0x88 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      writeRM8(readReg8(mrm_reg)); return next;
    }
    // 0x8A: MOV r8, r/m8
    if( b0==0x8A ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      writeReg8(mrm_reg, readRM8()); return next;
    }
    // 0x8D: LEA r, m
    if( b0==0x8D ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false);
      fixEA(next,false);  // resolve RIP-relative; no FS for LEA
      r64[mrm_reg]=mrm_ea;
      return next;
    }
    // 0xB0+rb: MOV r8, imm8
    if( b0>=0xB0 && b0<=0xB7 ) {
      int idx = (b0 & 7) | (rex_b ? 8 : 0);
      long imm = mem.load8( pc + 1 ) & 0xFFL;
      // REX 無しで idx 4-7 は AH/CH/DH/BH。writeReg8 ヘルパが処理する。
      writeReg8( idx, imm );
      return pc + 2;
    }
    // 0xB8+rd: MOV r32/r64, imm
    if( b0>=0xB8 && b0<=0xBF ) {
      int rd=(b0&7)|(rex_b?8:0);
      if(rex_w){r64[rd]=loadImm64(pc+1);return pc+9;}
      else{r64[rd]=loadImm32u(pc+1);return pc+5;}
    }
    // 0xC7 /0: MOV r/m64/32/16, imm — 66 prefix で 16-bit operand
    if( b0==0xC7 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false);
      long imm;
      if( op66 && !rex_w ) {
        imm = mem.load16( next ) & 0xFFFFL;  // imm16
        next += 2;
      } else {
        imm = (long)(int)loadImm32u(next);   // imm32 (sign-extended for 64bit)
        next += 4;
      }
      fixEA(next,fs_prefix);
      if(mrm_reg==0){
        if( rex_w )           writeRM64(imm);
        else if( op66 )       writeRM16((short)imm);
        else                  writeRM32(imm);
      }
      return next;
    }
    // 0xC6 /0: MOV r/m8, imm8
    if( b0==0xC6 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false);
      long imm=mem.load8(next)&0xFFL;next++;
      fixEA(next,fs_prefix);
      if(mrm_reg==0) writeRM8(imm);
      return next;
    }

    // --- XOR r/m, r / r, r/m ---
    if( b0==0x31 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long res=readRM64()^r64[mrm_reg];
      if(rex_w)writeRM64(res);else writeRM32(res);
      zf=(res==0)?1:0;sf=(res<0)?1:0;of=0;cf=0; return next;
    }
    if( b0==0x33 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long res=r64[mrm_reg]^readRM64();
      r64[mrm_reg]=rex_w?res:(res&0xFFFFFFFFL);
      zf=(res==0)?1:0;sf=(res<0)?1:0;of=0;cf=0; return next;
    }

    // --- MOVSXD (63) ---
    if( b0==0x63 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long val=readRM32();
      r64[mrm_reg]=rex_w?(long)(int)val:(val&0xFFFFFFFFL);
      return next;
    }

    // --- IMUL r, r/m, imm ---
    if( b0==0x69 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false);
      long imm=(long)(int)loadImm32u(next);next+=4;
      fixEA(next,fs_prefix);
      long src=rex_w?readRM64():readRM32();
      long res=src*imm;
      if(rex_w)r64[mrm_reg]=res;else r64[mrm_reg]=res&0xFFFFFFFFL;
      of=0;cf=0; return next;
    }
    if( b0==0x6B ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false);
      long imm=(long)(byte)mem.load8(next);next++;
      fixEA(next,fs_prefix);
      long src=rex_w?readRM64():readRM32();
      long res=src*imm;
      if(rex_w)r64[mrm_reg]=res;else r64[mrm_reg]=res&0xFFFFFFFFL;
      of=0;cf=0; return next;
    }

    // --- Grp1 imm forms (81, 83) ---
    // fixEA への引数は「命令全体の次のアドレス」(RIP-relative の基点) でなければならない。
    // immediate を読んだ後の next を fixEA に渡す。
    if( b0==0x81 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false);
      long imm; if(op66){imm=(long)(short)loadImm16(next);next+=2;}else{imm=(long)(int)loadImm32u(next);next+=4;}
      fixEA(next,fs_prefix);
      return execGrp1(imm,rex_w,op66,next);
    }
    if( b0==0x83 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false);
      long imm=(long)(byte)mem.load8(next);next++;
      fixEA(next,fs_prefix);
      return execGrp1(imm,rex_w,op66,next);
    }
    // Grp1 8-bit imm (80)
    if( b0==0x80 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false);
      long imm=mem.load8(next)&0xFFL; next++;
      fixEA(next,fs_prefix);
      long dst=readRM8(), res;
      switch(mrm_reg){
        case 0: res=(dst+imm)&0xFF; writeRM8(res); break;
        case 1: res=(dst|imm)&0xFF; writeRM8(res); zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=0;cf=0; return next;
        case 4: res=(dst&imm)&0xFF; writeRM8(res); zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=0;cf=0; return next;
        case 5: res=(dst-imm)&0xFF; writeRM8(res); break;
        case 6: res=(dst^imm)&0xFF; writeRM8(res); zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=0;cf=0; return next;
        case 7: res=(dst-imm)&0xFF; // CMP
                zf=(res==0)?1:0;sf=(int)(res>>7)&1;
                of=(int)(((dst^imm)&(dst^res))>>7)&1;cf=Long.compareUnsigned(dst,imm)<0?1:0; return next;
        default: res=dst;
      }
      // ADD/SUB 8-bit flags simplified
      zf=(res==0)?1:0; sf=(int)(res>>7)&1; of=0; cf=0;
      return next;
    }

    // --- Grp3 (F6/F7) ---
    if( b0==0xF6 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false);
      long val,res,imm;
      // TEST (mrm_reg==0) has imm8 after ModRM — fixEA after reading imm
      if(mrm_reg==0){ imm=mem.load8(next)&0xFFL; next++; fixEA(next,fs_prefix); res=readRM8()&imm;
                      zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=0;cf=0; return next; }
      fixEA(next,fs_prefix);
      switch(mrm_reg){
        case 0: imm=0;res=0; break; // already handled above
        case 2: writeRM8((~readRM8())&0xFF); break;
        case 3: val=readRM8(); res=(-val)&0xFF; writeRM8(res);
                cf=(val!=0)?1:0;zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=(val==0x80)?1:0; break;
        default:
          process.println("Cpu64: unsupported F6 /"+mrm_reg+" at 0x"+Long.toHexString(pc));
          process.set_exit_flag();
      }
      return next;
    }
    if( b0==0xF7 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false);
      long val,res,imm;
      // TEST (mrm_reg==0) has imm32 after ModRM — fixEA after reading imm
      if(mrm_reg==0){ imm=(long)(int)loadImm32u(next);next+=4; fixEA(next,fs_prefix); val=rex_w?readRM64():readRM32(); res=val&imm;
                      if(rex_w){zf=(res==0)?1:0;sf=(res<0)?1:0;}else{res&=0xFFFFFFFFL;zf=(res==0)?1:0;sf=(int)(res>>31)&1;}
                      of=0;cf=0; return next; }
      fixEA(next,fs_prefix);
      switch(mrm_reg){
        case 0: imm=0;res=0;val=0; break; // already handled above
        case 2: if(rex_w)writeRM64(~readRM64());else writeRM32((~readRM32())&0xFFFFFFFFL); break;
        case 3: val=rex_w?readRM64():readRM32(); res=-val;
                if(rex_w){setFlags64Sub(0,val);writeRM64(res);}
                else{val&=0xFFFFFFFFL;setFlags32Sub(0,val);writeRM32(res&0xFFFFFFFFL);} break;
        case 4: val=rex_w?readRM64():readRM32();
                if(rex_w){long a=r64[R_RAX],b=val; long hi=Math.multiplyHigh(a,b); if(a<0)hi+=b; if(b<0)hi+=a; r64[R_RDX]=hi; r64[R_RAX]=a*b; cf=of=(hi!=0)?1:0;}
                else{long p=(r64[R_RAX]&0xFFFFFFFFL)*(val&0xFFFFFFFFL);r64[R_RDX]=(p>>32)&0xFFFFFFFFL;r64[R_RAX]=p&0xFFFFFFFFL;cf=of=(r64[R_RDX]!=0)?1:0;} break;
        case 5: val=rex_w?readRM64():(long)(int)readRM32();
                if(rex_w){long a=r64[R_RAX],b=val; r64[R_RDX]=Math.multiplyHigh(a,b); r64[R_RAX]=a*b; cf=of=(r64[R_RDX]!=(r64[R_RAX]>>63))?1:0;}
                else{long p=(long)(int)r64[R_RAX]*(long)(int)val;r64[R_RDX]=(p>>32)&0xFFFFFFFFL;r64[R_RAX]=p&0xFFFFFFFFL;cf=of=0;} break;
        case 6: val=rex_w?readRM64():readRM32();
                if(val==0){process.println("Cpu64: DIV/0");process.set_exit_flag();break;}
                if(rex_w){long q=Long.divideUnsigned(r64[R_RAX],val);r64[R_RDX]=Long.remainderUnsigned(r64[R_RAX],val);r64[R_RAX]=q;}
                else{long d=((r64[R_RDX]&0xFFFFFFFFL)<<32)|(r64[R_RAX]&0xFFFFFFFFL);long v=val&0xFFFFFFFFL;r64[R_RAX]=Long.divideUnsigned(d,v)&0xFFFFFFFFL;r64[R_RDX]=Long.remainderUnsigned(d,v)&0xFFFFFFFFL;} break;
        case 7: val=rex_w?readRM64():(long)(int)readRM32();
                if(val==0){process.println("Cpu64: IDIV/0");process.set_exit_flag();break;}
                if(rex_w){long q=r64[R_RAX]/val;r64[R_RDX]=r64[R_RAX]%val;r64[R_RAX]=q;}
                else{long d=(long)(int)r64[R_RAX];r64[R_RAX]=(d/(long)(int)val)&0xFFFFFFFFL;r64[R_RDX]=(d%(long)(int)val)&0xFFFFFFFFL;} break;
        default:
          process.println("Cpu64: unsupported F7 /"+mrm_reg+" at 0x"+Long.toHexString(pc));
          process.set_exit_flag();
      }
      return next;
    }

    // --- Grp4 (FE) — INC/DEC r/m8. CF は変えない ---
    if( b0==0xFE ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long val=readRM8();
      switch(mrm_reg){
        case 0: { long r=(val+1)&0xFF; writeRM8(r);
                  zf=(r==0)?1:0; sf=(int)(r>>7)&1; of=(val==0x7F)?1:0; break; }
        case 1: { long r=(val-1)&0xFF; writeRM8(r);
                  zf=(r==0)?1:0; sf=(int)(r>>7)&1; of=(val==0x80)?1:0; break; }
        default:
          process.println("Cpu64: unsupported FE /"+mrm_reg+" at 0x"+Long.toHexString(pc));
          process.set_exit_flag();
      }
      return next;
    }

    // --- Grp5 (FF) ---
    if( b0==0xFF ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      switch(mrm_reg){
        case 0: { long v=rex_w?readRM64():readRM32();long r=v+1;if(rex_w){setFlags64Add(v,1);writeRM64(r);}else{setFlags32Add(v,1);writeRM32(r&0xFFFFFFFFL);} break; }
        case 1: { long v=rex_w?readRM64():readRM32();long r=v-1;if(rex_w){setFlags64Sub(v,1);writeRM64(r);}else{setFlags32Sub(v,1);writeRM32(r&0xFFFFFFFFL);} break; }
        case 2: { long tgt=readRM64(); push64(next); return tgt; }
        case 4: return readRM64();
        case 6: push64(readRM64()); break;
        default:
          process.println("Cpu64: unsupported FF /"+mrm_reg+" at 0x"+Long.toHexString(pc));
          process.set_exit_flag();
      }
      return next;
    }

    // --- Grp2 shift/rotate (D0/D1/D2/D3/C0/C1) ---
    if( b0==0xD1 || b0==0xD3 || b0==0xC1 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false);
      int count;
      if(b0==0xD1) count=1;
      else if(b0==0xD3) count=(int)(r64[R_RCX]&(rex_w?0x3F:0x1F));
      else{count=mem.load8(next)&(rex_w?0x3F:0x1F);next++;}
      fixEA(next,fs_prefix);
      long val=rex_w?readRM64():readRM32();
      long res=val;
      switch(mrm_reg){
        case 4: res=rex_w?(val<<count):((val<<count)&0xFFFFFFFFL); cf=(count>0)?(int)(val>>(rex_w?64-count:32-count))&1:cf; break; // SHL
        case 5: res=rex_w?(val>>>count):((val&0xFFFFFFFFL)>>>count); if(count>0)cf=(int)(val>>(count-1))&1; break; // SHR
        case 7: res=rex_w?(val>>count):((long)(int)val>>count); if(count>0)cf=(int)(val>>(count-1))&1; break; // SAR
        case 0: // ROL
          if(rex_w){res=(val<<count)|(val>>>(64-count));}else{val&=0xFFFFFFFFL;res=((val<<count)|(val>>>(32-count)))&0xFFFFFFFFL;}
          cf=(int)res&1; break;
        case 1: // ROR
          if(rex_w){res=(val>>>count)|(val<<(64-count));}else{val&=0xFFFFFFFFL;res=((val>>>count)|(val<<(32-count)))&0xFFFFFFFFL;}
          cf=(rex_w?(int)(res>>63):(int)(res>>31))&1; break;
        default:
          process.println("Cpu64: unsupported Grp2 /"+mrm_reg+" at 0x"+Long.toHexString(pc));
          process.set_exit_flag();
      }
      if(rex_w){zf=(res==0)?1:0;sf=(res<0)?1:0;writeRM64(res);}
      else{res&=0xFFFFFFFFL;zf=(res==0)?1:0;sf=(int)(res>>31)&1;writeRM32(res);}
      of=0; return next;
    }
    if( b0==0xD0 || b0==0xD2 || b0==0xC0 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false);
      int count;
      if(b0==0xD0) count=1;
      else if(b0==0xD2) count=(int)(r64[R_RCX]&0x1F);
      else{count=mem.load8(next)&0x1F;next++;}
      fixEA(next,fs_prefix);
      long val=readRM8()&0xFF, res=val;
      switch(mrm_reg){
        case 4: res=(val<<count)&0xFF; cf=(count>0)?(int)(val>>(8-count))&1:cf; break;
        case 5: res=(val>>>count)&0xFF; if(count>0)cf=(int)(val>>(count-1))&1; break;
        case 7: res=(long)((byte)val>>count)&0xFF; if(count>0)cf=(int)(val>>(count-1))&1; break;
        default:
          process.println("Cpu64: unsupported Grp2b /"+mrm_reg+" at 0x"+Long.toHexString(pc));
          process.set_exit_flag();
      }
      writeRM8(res); zf=(res==0)?1:0;sf=(int)(res>>7)&1;of=0; return next;
    }

    // --- XCHG (86/87) ---
    if( b0==0x86 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long tmp=readRM8(); writeRM8(readReg8(mrm_reg));
      writeReg8(mrm_reg, tmp); return next;
    }
    if( b0==0x87 ) {
      long next=decodeModRM(pc+1,rex_r,rex_b,rex_x,false); fixEA(next,fs_prefix);
      long tmp=rex_w?readRM64():readRM32();
      if(rex_w){writeRM64(r64[mrm_reg]);r64[mrm_reg]=tmp;}
      else{writeRM32(r64[mrm_reg]&0xFFFFFFFFL);r64[mrm_reg]=tmp&0xFFFFFFFFL;}
      return next;
    }

    // XCHG rAX, r (91-97 / REX.B: 91-97 with extended reg)
    if( b0>=0x91 && b0<=0x97 ) {
      int reg=(b0&7)|(rex_b?8:0);
      if(rex_w){long t=r64[R_RAX];r64[R_RAX]=r64[reg];r64[reg]=t;}
      else{long t=r64[R_RAX]&0xFFFFFFFFL;r64[R_RAX]=r64[reg]&0xFFFFFFFFL;r64[reg]=t;}
      return pc+1;
    }
    // INC/DEC r32 (40-4F) — in 64-bit mode these are REX prefixes (handled above)

    process.println("Cpu64: unknown opcode 0x"+Integer.toHexString(b0)+" at rip=0x"+Long.toHexString(pc));
    process.set_exit_flag();
    return pc;
  }

  // Grp1: ADD(0) OR(1) ADC(2) SBB(3) AND(4) SUB(5) XOR(6) CMP(7)
  private long execGrp1( long imm, boolean is64, boolean is16, long next_pc ) {
    long val, res;
    if(is64)      val=readRM64();
    else if(is16) val=readRM16();
    else          val=readRM32();
    switch(mrm_reg){
      case 0: res=val+imm;
              if(is64){setFlags64Add(val,imm);writeRM64(res);}
              else if(is16){res&=0xFFFFL;zf=(res==0)?1:0;sf=(int)(res>>15)&1;of=0;cf=0;writeRM16(res);}
              else{setFlags32Add(val&0xFFFFFFFFL,imm&0xFFFFFFFFL);writeRM32(res&0xFFFFFFFFL);} break;
      case 1: res=val|imm;
              if(is64){writeRM64(res);zf=(res==0)?1:0;sf=(res<0)?1:0;}
              else if(is16){res&=0xFFFFL;writeRM16(res);zf=(res==0)?1:0;sf=(int)(res>>15)&1;}
              else{res&=0xFFFFFFFFL;writeRM32(res);zf=(res==0)?1:0;sf=(int)(res>>31)&1;}
              of=0;cf=0; break;
      case 2: res=val+imm+cf; // ADC
              if(is64)writeRM64(res);else if(is16)writeRM16(res&0xFFFFL);else writeRM32(res&0xFFFFFFFFL); break;
      case 3: res=val-imm-cf; // SBB
              if(is64)writeRM64(res);else if(is16)writeRM16(res&0xFFFFL);else writeRM32(res&0xFFFFFFFFL); break;
      case 4: res=val&imm;
              if(is64){writeRM64(res);zf=(res==0)?1:0;sf=(res<0)?1:0;}
              else if(is16){res&=0xFFFFL;writeRM16(res);zf=(res==0)?1:0;sf=(int)(res>>15)&1;}
              else{res&=0xFFFFFFFFL;writeRM32(res);zf=(res==0)?1:0;sf=(int)(res>>31)&1;}
              of=0;cf=0; break;
      case 5: res=val-imm;
              if(is64){setFlags64Sub(val,imm);writeRM64(res);}
              else if(is16){res&=0xFFFFL;zf=(res==0)?1:0;sf=(int)(res>>15)&1;of=0;cf=Long.compareUnsigned(val&0xFFFFL,imm&0xFFFFL)<0?1:0;writeRM16(res);}
              else{setFlags32Sub(val&0xFFFFFFFFL,imm&0xFFFFFFFFL);writeRM32(res&0xFFFFFFFFL);} break;
      case 6: res=val^imm;
              if(is64){writeRM64(res);zf=(res==0)?1:0;sf=(res<0)?1:0;}
              else if(is16){res&=0xFFFFL;writeRM16(res);zf=(res==0)?1:0;sf=(int)(res>>15)&1;}
              else{res&=0xFFFFFFFFL;writeRM32(res);zf=(res==0)?1:0;sf=(int)(res>>31)&1;}
              of=0;cf=0; break;
      case 7: // CMP
              if(is64)setFlags64Sub(val,imm);
              else if(is16){long r=(val-imm)&0xFFFFL;zf=(r==0)?1:0;sf=(int)(r>>15)&1;of=0;cf=Long.compareUnsigned(val&0xFFFFL,imm&0xFFFFL)<0?1:0;}
              else setFlags32Sub(val&0xFFFFFFFFL,imm&0xFFFFFFFFL); break;
      default:
        process.println("Cpu64: unsupported Grp1 /"+mrm_reg+" at 0x"+Long.toHexString(next_pc));
        process.set_exit_flag();
    }
    return next_pc;
  }

  // SYSCALL
  private long exec_syscall( long next_pc ) {
    r64[R_RCX] = next_pc;
    long result = syscall64.call_amd64(
        r64[R_RAX], r64[R_RDI], r64[R_RSI], r64[R_RDX],
        r64[10],    r64[8],     r64[9] );
    r64[R_RAX] = result;
    interrupt_done = true;
    return next_pc;
  }

  // --- 即値ロードユーティリティ ---

  private long loadImm32u( long addr ) {
    return ((mem.load8(addr  )&0xFFL)    )
         | ((mem.load8(addr+1)&0xFFL)<< 8)
         | ((mem.load8(addr+2)&0xFFL)<<16)
         | ((mem.load8(addr+3)&0xFFL)<<24);
  }

  private long loadImm64( long addr ) { return loadImm32u(addr)|(loadImm32u(addr+4)<<32); }

  private long loadImm16( long addr ) { return (mem.load8(addr)&0xFFL)|((mem.load8(addr+1)&0xFFL)<<8); }

  // --- デバッグ文字列 ---

  private static final String[] REG_NAMES = {
    "rax","rcx","rdx","rbx","rsp","rbp","rsi","rdi",
    "r8","r9","r10","r11","r12","r13","r14","r15"
  };

  @Override
  public String reg_str() {
    StringBuilder sb = new StringBuilder();
    for(int i=0;i<NREGS;i++) sb.append(REG_NAMES[i]).append('=').append(Long.toHexString(r64[i])).append(' ');
    return sb.toString();
  }

  @Override public String ip_str()    { return "rip="+Long.toHexString(rip)+" "; }
  @Override public String flag_str()  { return "zf="+zf+" sf="+sf+" of="+of+" cf="+cf+" "; }
  @Override public String disasm_str(long a) { return "0x"+Long.toHexString(a); }
}
