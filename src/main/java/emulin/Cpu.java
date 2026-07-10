// ----------------------------------------
//  Cpu Emulator
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.lang.*;
import java.io.*;
import emulin.*;

public class Cpu extends AbstractCpu
{

  public Cpu( Sysinfo _sysinfo, Process _process ) {
    sysinfo = _sysinfo;
    process = _process;
    init( );
  }

  // 自分の複製を返す。
  @Override
  public AbstractCpu duplicate( Process _process ) {
    Cpu _cpu = new Cpu( sysinfo, _process );
    System.arraycopy( reg, 0, _cpu.reg, 0, reg.length );
    _cpu.ip       = ip;              // 命令ポインタ
    _cpu.next_ip  = next_ip;         // 次の命令のアドレス
    _cpu.of       = of ;
    _cpu.df       = df ;
    _cpu.sf       = sf ;
    _cpu.zf       = zf ;
    _cpu.af       = af ;
    _cpu.pf       = pf ;
    _cpu.cf       = cf ;
    _cpu.nest     = nest ;
    return( _cpu );
  }

  // 初期化
  public void init( ) {
    int i;
    reg = new int[MAX_REG];
    for( i = 0 ; i < MAX_REG ; i++ ) {
      reg[i] = 0x0;
    }
    of = 0;
    df = 0;
    sf = 0;
    zf = 0;
    af = 0;
    pf = 0;
    cf = 0;
    nest = 0;
    interrupt_done = false;
  }

  // Instruction Pointer の設定
  public void set_ip( long _ip ) {
    ip = _ip;
  }

  // ax レジスタを更新する。
  public void set_ax( int value ) {
    reg[AX] = value;
  }

  // Instruction Pointer を返す
  public long get_ip( ) {
    return( ip );
  }

  // Stack Pointer の設定
  public void set_sp( long sp ) {
    reg[SP] = (int)sp;
  }

  // Stack Pointer の取得
  public long get_sp( ) {
    return( (long)reg[SP] & 0xFFFFFFFFL );
  }

  public long pushString( String str ) {
    reg[SP] -= str.length( ) + 1;
    mem.storeString( get_sp( ), str );
    return( get_sp( ) );
  }

  // Signalハンドラに制御を移す
  public void set_signal_handler( long _ip, long goto_adrs ) {
    push32( _ip );
    ip = goto_adrs;
  }

  // システムコールが終了した直後か？
  public boolean is_interrupt_done( ) {
    return( interrupt_done );
  }

  // 命令を実行する
  public long eval( ) {
    boolean done = false;
    next_ip = ip + dinfo.inst_len;
    interrupt_done = false;
    if( dinfo.inst_id == Instruction.ADD )     {   done = true; add( 0 ); }
    if( dinfo.inst_id == Instruction.OR )      {   done = true; or( );  }
    if( dinfo.inst_id == Instruction.ADC )     {   done = true; adc( ); }
    if( dinfo.inst_id == Instruction.SBB )     {   done = true; sbb( ); }
    if( dinfo.inst_id == Instruction.AND )     {   done = true; and( ); }
    if( dinfo.inst_id == Instruction.SUB )     {   done = true; sub( 0, true ); }
    if( dinfo.inst_id == Instruction.XOR )     {   done = true; xor( ); }
    if( dinfo.inst_id == Instruction.CMP )     {   done = true; cmp( ); }
    if( dinfo.inst_id == Instruction.INC )     {   done = true; inc( ); }
    if( dinfo.inst_id == Instruction.DEC )     {   done = true; dec( ); }
    if( dinfo.inst_id == Instruction.PUSH )    {   done = true; push( ); }
    if( dinfo.inst_id == Instruction.PUSHF )   {   done = true; pushf( ); }
    if( dinfo.inst_id == Instruction.J )       {   done = true; j( ); }
    if( dinfo.inst_id == Instruction.POP )     {   done = true; pop( ); }
    if( dinfo.inst_id == Instruction.POPF )    {   done = true; popf( ); }
    if( dinfo.inst_id == Instruction.LEAVE )   {   done = true; leave( ); }
    if( dinfo.inst_id == Instruction.TEST )    {   done = true; test( ); }
    if( dinfo.inst_id == Instruction.NOT )     {   done = true; not( ); }
    if( dinfo.inst_id == Instruction.NEG )     {   done = true; neg( ); }
    if( dinfo.inst_id == Instruction.HLT )     {   done = true; hlt( ); }
    if( dinfo.inst_id == Instruction.MOV )     {   done = true; mov( ); }
    if( dinfo.inst_id == Instruction.MOVS )    {   done = true; movs( S_MOVS ); }
    if( dinfo.inst_id == Instruction.STOS )    {   done = true; movs( S_STOS ); }
    if( dinfo.inst_id == Instruction.LODS )    {   done = true; movs( S_LODS ); }
    if( dinfo.inst_id == Instruction.SCAS )    {   done = true; scas_cmps( true  ); }
    if( dinfo.inst_id == Instruction.CMPS )    {   done = true; scas_cmps( false ); }
    if( dinfo.inst_id == Instruction.MOVZX )   {   done = true; movzx( ); }
    if( dinfo.inst_id == Instruction.MOVSX )   {   done = true; movsx( ); }
    if( dinfo.inst_id == Instruction.LEA )     {   done = true; lea( ); }
    if( dinfo.inst_id == Instruction.STD )     {   done = true; std( ); }
    if( dinfo.inst_id == Instruction.CLD )     {   done = true; cld( ); }
    if( dinfo.inst_id == Instruction.DIV )     {   done = true; div( false ); }
    if( dinfo.inst_id == Instruction.IDIV )    {   done = true; div( true  ); }
    if( dinfo.inst_id == Instruction.IMUL )    {   done = true; imul( ); }
    if( dinfo.inst_id == Instruction.MUL )     {   done = true; mul( ); }
    if( dinfo.inst_id == Instruction.SHL )     {   done = true; shl( ); }
    if( dinfo.inst_id == Instruction.SHR )     {   done = true; shr( ); }
    if( dinfo.inst_id == Instruction.SAR )     {   done = true; sar( ); }
    if( dinfo.inst_id == Instruction.ROL )     {   done = true; rol( ); }
    if( dinfo.inst_id == Instruction.ROR )     {   done = true; ror( ); }
    if( dinfo.inst_id == Instruction.RCL )     {   done = true; rcl( ); }
    if( dinfo.inst_id == Instruction.SHLD )    {   done = true; shld( ); }
    if( dinfo.inst_id == Instruction.SHRD )    {   done = true; shrd( ); }
    if( dinfo.inst_id == Instruction.SET )     {   done = true; inst_set( ); }
    if( dinfo.inst_id == Instruction.INT  )    {   done = true; interrupt( ); interrupt_done = true; }
    if( dinfo.inst_id == Instruction.CALL )    {   done = true; call( ); }
    if( dinfo.inst_id == Instruction.JMP )     {   done = true; jmp( ); }
    if( dinfo.inst_id == Instruction.RETN )    {   done = true; retn( ); }
    if( dinfo.inst_id == Instruction.NOP )     {   done = true; nop( ); }
    if( dinfo.inst_id == Instruction.CWD )     {   done = true; cwd( ); }
    if( dinfo.inst_id == Instruction.XCHG )    {   done = true; xchg( ); }
    if( dinfo.inst_id == Instruction.FNSTCW )  {   done = true; fnstcw( ); }
    if( dinfo.inst_id == Instruction.FLDCW )   {   done = true; fldcw( ); }
    if( dinfo.inst_id == Instruction.BT )      {   done = true; bt( 0 ); }
    if( dinfo.inst_id == Instruction.BTS )     {   done = true; bt( Instruction.BTS ); }
    if( dinfo.inst_id == Instruction.BTR )     {   done = true; bt( Instruction.BTR ); }
    if( dinfo.inst_id == Instruction.BSF )     {   done = true; bsX( Instruction.BSF  ); }
    if( dinfo.inst_id == Instruction.BSR )     {   done = true; bsX( Instruction.BSR ); }
    if( dinfo.inst_id == Instruction.CBW )     {   done = true; cbw( ); }
    if( dinfo.inst_id == Instruction.FLD )     {   done = true; fld( ); }
    if( dinfo.inst_id == Instruction.FST )     {   done = true; fst( Instruction.FST  ); }
    if( dinfo.inst_id == Instruction.FSTP )    {   done = true; fst( Instruction.FSTP ); }
    if( dinfo.inst_id == Instruction.FILD )    {   done = true; fild( ); }
    if( dinfo.inst_id == Instruction.FISTP )   {   done = true; fistp( ); }
    if( dinfo.inst_id == Instruction.FCHS )    {   done = true; fchs( ); }
    if( dinfo.inst_id == Instruction.FXCH )    {   done = true; fxch( ); }
    if( dinfo.inst_id == Instruction.FADD )    {   done = true; farith( Instruction.FADD  ); }
    if( dinfo.inst_id == Instruction.FSUB )    {   done = true; farith( Instruction.FSUB  ); }
    if( dinfo.inst_id == Instruction.FSUBR )   {   done = true; farith( Instruction.FSUBR ); }
    if( dinfo.inst_id == Instruction.FMUL )    {   done = true; farith( Instruction.FMUL  ); }
    if( dinfo.inst_id == Instruction.FDIV )    {   done = true; farith( Instruction.FDIV  ); }
    if( dinfo.inst_id == Instruction.FDIVR )   {   done = true; farith( Instruction.FDIVR ); }
    if( dinfo.inst_id == Instruction.FSQRT )   {   done = true; fsqrt( ); }
    if( dinfo.inst_id == Instruction.FABS )    {   done = true; fabsx( ); }
    if( dinfo.inst_id == Instruction.FLD1 )    {   done = true; fld1( ); }
    if( dinfo.inst_id == Instruction.FLDZ )    {   done = true; fldz( ); }
    if( dinfo.inst_id == Instruction.FLDPI )   {   done = true; fldpi( ); }
    if( dinfo.inst_id == Instruction.Unknown ) {   done = true; unsupported( ); }

    if( !done ) {
      unsupported( );
    }
    ip = next_ip;
    process.inc_evals( );
    return( ip );
  }

  // ADD命令
  void add( int plus ) {
    // 幅対応 (旧実装は 32bit 固定で ival を width マスクせず ZF/CF/OF が r8/r16 で誤り。
    //   sub() 同様に width で処理する)。
    int size = calc_operand_size( );
    long wm  = (size == 1) ? 0xFFL : (size == 2) ? 0xFFFFL : 0xFFFFFFFFL;
    int d = (int)ref_expand( dinfo.dst );         // 符号拡張 (sub() と同様)
    int s = (int)ref_expand( dinfo.src );
    long ssum = (long)d + (long)s + (long)plus;   // 符号付き和 (OF 用)
    int ival = (int)ssum;
    af = ((d ^ s ^ ival) >> 4) & 1;               // AF = bit3→bit4 の桁上げ (旧実装は AF 未計算)
    overflow_eval( ssum );
    if( size == 1 ) { ival &= 0xFF; }
    if( size == 2 ) { ival &= 0xFFFF; }
    flag_eval( ival );
    set( dinfo.dst, ival );
    cf = 0;
    if( ((long)d & wm) + ((long)s & wm) + ((long)plus & wm) > wm ) { cf = 1; }  // width 桁上げ
  }

  // OR命令
  void or( ) {
    int ival;
    int d = ref( dinfo.dst );
    int s = ref( dinfo.src );
    ival = s | d;
    flag_eval( ival );
    set( dinfo.dst, ival );
    of = 0;
    cf = 0;
  }

  // ADC命令
  void adc( ) {  add( cf );  }

  // SBB命令
  void sbb( ) {  sub( cf, true ); }

  // AND命令
  void and( ) {
    int ival;
    int d = ref( dinfo.dst );
    int s = ref( dinfo.src );
    ival = s & d;
    flag_eval( ival );
    set( dinfo.dst, ival );
    of = 0;
    cf = 0;
  }

  // SUB命令
  void sub( int minus, boolean set_flag ) {
    int ival;
    int size = calc_operand_size( );
    int d = (int)ref_expand( dinfo.dst );
    int s = (int)ref_expand( dinfo.src );
    ival = d - ( s + minus );
    af = ((d ^ s ^ ival) >> 4) & 1;   // AF = bit4 への借り (旧実装は AF 未計算)
    overflow_eval( (long)d - ( (long)s + (long)minus  ));
    if( size == 1 ) { ival &= 0xFF; }
    if( size == 2 ) { ival &= 0xFFFF; }
    flag_eval( ival );
    if( set_flag ) {   set( dinfo.dst, ival ); }
    cf = 0;
    if( sysinfo.debug( )) {
      process.println( "  CMP " + Util.hexstr( s, 8 ) + "," + Util.hexstr( d, 8 )   + " -> " + Util.hexstr( ival, 8 ));
    }
    // CF (borrow): d < s + minus。s は width マスク済み、minus(=SBB の CF)は 0/1。
    //   旧実装は (s+minus) を width マスクしており、s=0xFFFF..+1 が 0 に wrap して
    //   SBB の borrow-in 時に CF を取りこぼしていた。マスクは s だけに掛ける。
    if( size == 1 ) { if( ((long)d & 0xFFL)       < (((long)s & 0xFFL) + minus) )       { cf = 1; } }
    if( size == 2 ) { if( ((long)d & 0xFFFFL)     < (((long)s & 0xFFFFL) + minus) )     { cf = 1; } }
    if( size == 4 ) { if( ((long)d & 0xFFFFFFFFL) < (((long)s & 0xFFFFFFFFL) + minus) ) { cf = 1; } }
  }

  // XOR命令
  void xor( ) {
    int ival;
    int orig = ref( dinfo.dst );
    ival = ref( dinfo.src ) ^ orig;
    flag_eval( ival );
    set( dinfo.dst, ival );
    of = 0;
    cf = 0;
  }

  // CMP命令
  void cmp( ) {  sub( 0, false );  }

  // INC命令
  void inc( ) {
    int s = ref( dinfo.src );
    int ival = s + 1;
    overflow_eval( (long)s + (long)1 );
    flag_eval( ival );
    set( dinfo.src, ival );
  }

  // DEC命令
  void dec( ) {
    int s = ref( dinfo.src );
    int ival = s - 1;
    overflow_eval( (long)s - 1 );
    flag_eval( ival );
    set( dinfo.src, ival );
  }

  // PUSH命令
  void push( ) {
    if( dinfo.s_flag && (0 != dinfo.s_val)) { push32( Util.expand_sign( ref( dinfo.src ), 1 )); }
    else {                        push32( ref( dinfo.src )); }
  }

  // PUSHF命令
  void pushf( ) {
      // 実 x86 EFLAGS のビット位置: CF=0 PF=2 AF=4 ZF=6 SF=7 DF=10 OF=11。
      //   旧実装は独自コンパクト配置 (bit0-6) で pack しており、guest が pushf で
      //   実 EFLAGS ビットを読むと総崩れした (Jcc/SETcc は zf/sf 変数を直接使うので
      //   無症状だったが、pushf/popf/フラグ検査コードは非適合)。bit1 は予約=常に 1。
      int val = 0x2;
      val |= cf << 0;
      val |= pf << 2;
      val |= af << 4;
      val |= zf << 6;
      val |= sf << 7;
      val |= df << 10;
      val |= of << 11;
      push32( val );
  }

  // POPF命令
  void popf( ) {
      int val = pop32( );
      cf = (val >> 0)  & 1;
      pf = (val >> 2)  & 1;
      af = (val >> 4)  & 1;
      zf = (val >> 6)  & 1;
      sf = (val >> 7)  & 1;
      df = (val >> 10) & 1;
      of = (val >> 11) & 1;
  }


  // J命令
  void j( ) {
    if( _condition( )) {
      next_ip = ref( dinfo.src );
    }
  }

  // SET命令
  void inst_set( ) {
    byte value = 0;
    if( _condition( )) { value = 1; }
    _set( dinfo.src, value , 1 );
  }

  // J命令
  boolean _condition( ) {
    boolean support = false;
    boolean result = false;
    if(dinfo.c_val == JE) {
      support = true;
      if(zf == 1) { result = true; }
    }
    if(dinfo.c_val == JNE) {
      support = true;
      if(zf == 0) { result = true; }
    }
    if(dinfo.c_val == JAE) {
      support = true;
      if(cf == 0) { result = true; }
    }
    if(dinfo.c_val == JA) {
      support = true;
      if((zf == 0) && (cf == 0)) { result = true; }
    }
    if(dinfo.c_val == JBE) {
      support = true;
      if((zf == 1) || (cf == 1)) { result = true; }
    }
    if(dinfo.c_val == JB) {
      support = true;
      if(cf == 1) { result = true; }
    }
    if(dinfo.c_val == JO) {
      support = true;
      if(of == 1) { result = true; }
    }
    if(dinfo.c_val == JNO) {
      support = true;
      if(of == 0) { result = true; }
    }
    if(dinfo.c_val == JG) {
      support = true;
      if((zf == 0)&&(sf==of)) { result = true; }
    }
    if(dinfo.c_val == JGE) {
      support = true;
      if(sf == of) { result = true; }
    }
    if(dinfo.c_val == JL) {
      support = true;
      if(sf != of) { result = true; }
    }
    if(dinfo.c_val == JLE) {
      support = true;
      if((zf==1)||(sf != of)) { result = true; }
    }
    if(dinfo.c_val == JS) {
      support = true;
      if(sf==1) { result = true; }
    }
    if(dinfo.c_val == JNS) {
      support = true;
      if(sf==0) { result = true; }
    }
    if(dinfo.c_val == JP) {
      support = true;
      if(pf==1) { result = true; }
    }
    if(dinfo.c_val == JNP) {
      support = true;
      if(pf==0) { result = true; }
    }
    if( !support ) {
      raiseSig( Signal.SIGILL, "unsupported condition code" );
    }
    return( result );
  }

  // CALL命令
  void call( ) {
    push32( next_ip );
    next_ip = ref( dinfo.src );
    if( sysinfo.debug( )) {
      int i;
      for( i = 1 ; i < 6 ; i++ ) {
	process.println( "  arg" + i + "=" + Util.hexstr( mem.load32( get_sp( ) + i*4 ), 8 ));
      }
    }
    nest++;
  }

  // JMP命令
  void jmp( )  {
    next_ip = ref( dinfo.src );
  }

  // RETN命令
  void retn( ) {  
    next_ip = pop32( );  nest--;
    if( dinfo.src.kind == Operand.IMM ) {
      reg[SP] += dinfo.src.imm;
    }
  }

  // NOP命令
  void nop( )  { }

  // CWD命令
  void cwd( ) {
    if( 0 == (reg[AX] & 0x8000 )) {
      reg[DX] = 0;
    }
    else {
      reg[DX] = 0xFFFF;
    }
  }

  // XCHG命令
  void xchg( ) {
    int s = ref( dinfo.src );
    int d = 0;
    if( dinfo.dst.kind == Operand.NONE ) {
      dinfo.dst.kind = Operand.REG;
      dinfo.dst.reg_no = AX;
    }
    d = ref( dinfo.dst );
    // 値の交換
    set( dinfo.src, d );
    set( dinfo.dst, s );
  }

  // INT命令
  void interrupt( ) {
    reg[ AX ] = (int)syscall.call( reg[ AX ], reg[BX], reg[CX], reg[DX], reg[SI], reg[DI] );
  }

  // POP命令
  void pop( ) {  set( dinfo.src, pop32( )); }

  // LEAVE命令
  void leave( ) {  
    reg[ SP ] = reg[ BP ];
    reg[ BP ] = pop32( );
  }

  // HLT命令: CPL3 では #GP -> SIGSEGV (旧実装は System.exit(0))。
  void hlt( ) {
    raiseSig( Signal.SIGSEGV, "HLT (privileged) in user mode" );
  }

  // MOV命令
  void mov( ) { set( dinfo.dst, ref( dinfo.src ));  }

  // MOVS命令
  void movs( int select ) {
    long i;
    long times = 1;
    int size = 4;
    int data = 0;
    if( dinfo.repz_flag || dinfo.repnz_flag ) {
      times = (long)reg[CX] & 0xFFFFFFFFL;
    }
    if( dinfo.o16_flag )   { size = 2; }
    else {
      if( dinfo.W_flag && ( dinfo.W_val == 0 )) { size = 1; }
    }
    for( i = 0 ; i < times ; i++ ) {
      if( S_STOS == select ) {
	data =  reg[AX];
      }
      else {
	if( size == 1 ) {	data = (int)mem.load8(  (long)reg[SI] & 0xFFFFFFFFL )    &   0xFF; }
	if( size == 2 ) {	data = (int)mem.load16( (long)reg[SI] & 0xFFFFFFFFL )   & 0xFFFF; }
	if( size == 4 ) {	data = mem.load32( (long)reg[SI] & 0xFFFFFFFFL );                 }
      }
      if( size == 1 ) {	mem.store8(  (long)reg[DI] & 0xFFFFFFFFL, (byte) data ); }
      if( size == 2 ) {	mem.store16( (long)reg[DI] & 0xFFFFFFFFL, (short)data ); }
      if( size == 4 ) {	mem.store32( (long)reg[DI] & 0xFFFFFFFFL,        data ); }
      if( sysinfo.debug( )) {
	if( size == 1 ) {	process.println( "   (" + Util.hexstr( reg[DI], 8 ) + ") <- "
						    + "[" + Util.hexstr( data & 0xFF, 2 ) + "]"
						    + "(" + Util.hexstr( reg[SI], 8 ) + ")" );
	}
	if( size == 2 ) {	process.println( "   (" + Util.hexstr( reg[DI], 8 ) + ") <- "
						    + "[" + Util.hexstr( data & 0xFFFF, 4 ) + "]"
						    + "(" + Util.hexstr( reg[SI], 8 ) + ")" );
	}
	if( size == 4 ) {	process.println( "   (" + Util.hexstr( reg[DI], 8 ) + ") <- "
						    + "[" + Util.hexstr( data, 8 ) + "]"
						    + "(" + Util.hexstr( reg[SI], 8 ) + ")" );
	}
      }
      if( df == 1 ) {
	if( !(S_LODS == select) ) { reg[DI] -= size; }
	if( !(S_STOS == select) ) { reg[SI] -= size; }
      }
      else {
	if( !(S_LODS == select) ) { reg[DI] += size; }
	if( !(S_STOS == select) ) { reg[SI] += size; }
      }
    }
  }

  // SCAS命令
  void scas_cmps( boolean ax_flag ) {
    long i;
    long times = 1;
    int size = 4;
    String str = "";
    if( dinfo.repz_flag || dinfo.repnz_flag ) {
      times = (long)reg[CX] & 0xFFFFFFFFL;
    }
    if( dinfo.o16_flag )   { size = 2; }
    else {
      if( dinfo.W_flag && ( dinfo.W_val == 0 )) { size = 1; }
    }
    for( i = 0 ; i < times ; i++ ) {
      boolean equal = false;
      int left_val = reg[AX];
      int right_val = mem.load32( (long)reg[DI] & 0xFFFFFFFFL );
      if( ! ax_flag ) {
	left_val = mem.load32( (long)reg[SI] & 0xFFFFFFFFL );
      }
      // 適合サイズに削って,符号拡張する。
      left_val  = Util.expand_sign( left_val,  size );
      right_val = Util.expand_sign( right_val, size );

      // イコール比較
      if( (left_val )         == ( right_val          )) { equal = true; }
      // オーバーフローチェック
      overflow_eval( (long)left_val - (long)right_val );
      // 大小比較
      cf = 0;
      if( (long)left_val < (long)right_val ) { cf = 1; }

      if( sysinfo.debug( )) {
	process.println( " scas,cmps :  left_val = " + Util.hexstr( left_val, 8 ) + " right_val = " + Util.hexstr( right_val, 8 ));
      }

      if( sysinfo.debug( )) {
	if( ax_flag ) {
	  str += "  SCAS ";
	}
	else {
	  str += "  CMPS ";
	}
	str += Util.hexstr( left_val, size*2 ) + "," + Util.hexstr( right_val, size*2 );
      }
      if( df == 1 ) {	reg[DI] -= size; }
      else {            reg[DI] += size; }
      if( ! ax_flag ) {
	if( df == 1 ) {	reg[SI] -= size; }
	else {          reg[SI] += size; }
      }
      reg[CX] -= 1;
      if( dinfo.repnz_flag && equal  ) { zf = 1; break; }
      if( dinfo.repz_flag  && !equal ) { zf = 0; break; }
    }
  }

  // MOVZX命令
  void movzx( ) {
    int size = calc_operand_size( );
    if( dinfo.dst.kind == Operand.HREG ) { dinfo.dst.kind = Operand.REG; } // 例外処理
    if( size <= 2 ) {	 _set( dinfo.dst, (int)_ref( dinfo.src ) & 0xFF  , 4 );  }
    else { /* 4 */       _set( dinfo.dst, (int)_ref( dinfo.src ) & 0xFFFF, 4 );  }
  }

  // MOVSX命令
  void movsx( ) {
    int ival = ref( dinfo.src );
    int size = calc_operand_size( );
    // 符号拡張する
    if( size <= 2 ) { ival = Util.expand_sign( ival, 1 ); }
    if( size == 4 ) { ival = Util.expand_sign( ival, 2 ); }
    if( dinfo.dst.kind == Operand.HREG ) { dinfo.dst.kind = Operand.REG; } // 例外処理
    _set( dinfo.dst, ival, 4 );
  }

  // LEA命令
  void lea( ) {  set( dinfo.dst, (int)ea( dinfo.src ));  }

  // STD命令
  void std( ) {  df = 1; }

  // CLD命令
  void cld( ) {  df = 0; }

  // DIV命令
  void div( boolean sign_flag ) {
    // DIV/IDIV: dividend = {AX / DX:AX / EDX:EAX}、divisor = r/m。商→{AL/AX/EAX}、
    //   余→{AH/DX/EDX}。旧実装は dividend を reg[AX] のみ (上位語 DX/EDX 無視)、8bit の
    //   AH 未格納、idiv の符号拡張なしで誤っていた。#DE (0 除算・商溢れ) はテスト対象外。
    int size = calc_operand_size( );
    if( size == 1 ) {
      long dividend = sign_flag ? (int)(short)(reg[AX] & 0xFFFF) : (reg[AX] & 0xFFFFL);
      long divisor  = sign_flag ? (int)(byte)(ref( dinfo.src ) & 0xFF) : (ref( dinfo.src ) & 0xFFL);
      long q = dividend / divisor, r = dividend % divisor;
      reg[AX] = (int)((reg[AX] & ~0xFFFF) | ((r & 0xFF) << 8) | (q & 0xFF));   // AH:AL
    } else if( size == 2 ) {
      long lo = reg[AX] & 0xFFFFL, hi = reg[DX] & 0xFFFFL;
      long dd = (hi << 16) | lo;                                    // DX:AX (32bit)
      long dividend = sign_flag ? (int)dd : dd;
      long divisor  = sign_flag ? (int)(short)(ref( dinfo.src ) & 0xFFFF) : (ref( dinfo.src ) & 0xFFFFL);
      long q = dividend / divisor, r = dividend % divisor;
      reg[AX] = (int)((reg[AX] & ~0xFFFF) | (q & 0xFFFF));
      reg[DX] = (int)((reg[DX] & ~0xFFFF) | (r & 0xFFFF));
    } else {
      long dd = ((long)reg[DX] << 32) | ((long)reg[AX] & 0xFFFFFFFFL);  // EDX:EAX (64bit)
      if( sign_flag ) {
        long divisor = (long)ref( dinfo.src );                     // int → 符号拡張
        reg[AX] = (int)(dd / divisor);
        reg[DX] = (int)(dd % divisor);
      } else {
        long divisor = ((long)ref( dinfo.src )) & 0xFFFFFFFFL;
        reg[AX] = (int)Long.divideUnsigned( dd, divisor );
        reg[DX] = (int)Long.remainderUnsigned( dd, divisor );
      }
    }
  }

  // IMUL命令
  // 幅 wb bit の値 v (下位 wb bit のみ有効) を符号拡張して long にする。
  private static long sxw( long v, int wb ) { long m = 1L << (wb - 1); return (v ^ m) - m; }

  void imul( ) {
    // 符号あり乗算。1-op (F6/F7 /5): {AH:AL/DX:AX/EDX:EAX}=ACC*r/m。2-op (0F AF):
    //   dst=dst*src (下位半分のみ)。CF=OF=1 iff 積が下位半分の符号拡張に収まらない。
    //   旧実装は ref (符号なし) で符号拡張せず、>>32 固定、OF が signed overflow_eval で誤り。
    int size = calc_operand_size( );
    long wm = (size == 1) ? 0xFFL : (size == 2) ? 0xFFFFL : 0xFFFFFFFFL;
    int wb  = size * 8;
    boolean use_dx = (dinfo.dst.kind == Operand.NONE);
    boolean three_op = (dinfo.fst.kind == Operand.IMM);   // 3-op (69/6B): dst = src * imm
    if( use_dx ) { dinfo.dst.kind = Operand.REG; dinfo.dst.reg_no = AX; }
    long a, b;
    if( use_dx )        { a = sxw( ((long)reg[AX]) & wm, wb );        b = sxw( ((long)ref( dinfo.src )) & wm, wb ); }
    else if( three_op ) { a = sxw( ((long)ref( dinfo.src )) & wm, wb ); b = sxw( ((long)dinfo.fst.imm) & wm, wb ); }
    else                { a = sxw( ((long)ref( dinfo.dst )) & wm, wb ); b = sxw( ((long)ref( dinfo.src )) & wm, wb ); }
    long prod = a * b;
    long lo = prod & wm;
    if( use_dx ) {
      long hi = (prod >>> wb) & wm;
      if( size == 1 )      { reg[AX] = (int)((reg[AX] & ~0xFFFF) | (hi << 8) | lo); }
      else if( size == 2 ) { reg[AX] = (int)((reg[AX] & ~0xFFFF) | lo);
                             reg[DX] = (int)((reg[DX] & ~0xFFFF) | hi); }
      else                 { reg[AX] = (int)lo; reg[DX] = (int)hi; }
    } else {
      set( dinfo.dst, (int)lo );
    }
    cf = of = (sxw( lo, wb ) != prod) ? 1 : 0;
  }

  // MUL命令 ( 符号なし乗算 )
  void mul( ) {
    // 符号なし: {AH:AL / DX:AX / EDX:EAX} = ACC * r/m。CF=OF=1 iff 上位語が非ゼロ。
    //   SF/ZF/PF/AF は undefined。旧実装は 32bit 積が signed long で溢れ (>>32 算術シフト・
    //   CF 判定 0xFFFFFFFF 固定)、8bit の AH 未格納、OF が signed overflow_eval で誤っていた。
    int size = calc_operand_size( );
    long wm = (size == 1) ? 0xFFL : (size == 2) ? 0xFFFFL : 0xFFFFFFFFL;
    int wb  = size * 8;
    long s = ((long)ref( dinfo.src )) & wm;
    long a = ((long)reg[AX]) & wm;                 // ACC = AL/AX/EAX
    long prod = a * s;                             // 符号なし積 (2^64 未満、ビット正確)
    long lo = prod & wm, hi = (prod >>> wb) & wm;
    if( size == 1 )      { reg[AX] = (int)((reg[AX] & ~0xFFFF) | (hi << 8) | lo); }   // AX = AH:AL
    else if( size == 2 ) { reg[AX] = (int)((reg[AX] & ~0xFFFF) | lo);
                           reg[DX] = (int)((reg[DX] & ~0xFFFF) | hi); }
    else                 { reg[AX] = (int)lo; reg[DX] = (int)hi; }
    cf = of = (hi != 0) ? 1 : 0;
  }

  // SHL命令
  // SHL(0)/SHR(1)/SAR(2) 共通。count は 0x1F でマスク。CF=最後にはみ出した bit、
  //   OF は count=1 のみ defined、count=0 は no-op (フラグ不変)。SF/ZF/PF は結果から、
  //   AF は非ゼロ count で undefined。旧実装は CF を全く設定せず OF が signed
  //   overflow_eval・width マスク無しで総崩れだった (insn_model._shift1 の写し)。
  void shiftOp( int op ) {
    int size = calc_operand_size( );
    int width = size * 8;
    long M = (size == 1) ? 0xFFL : (size == 2) ? 0xFFFFL : 0xFFFFFFFFL;
    int c = ref( dinfo.src ) & 0x1F;
    long dst = ((long)ref( dinfo.dst )) & M;
    if( c == 0 ) { set( dinfo.dst, (int)dst ); return; }   // count=0: フラグ不変
    long res;
    if( op == 0 )      { res = (dst << c) & M; }
    else if( op == 1 ) { res = dst >>> c; }
    else {                                                 // SAR
      long sign = (dst >> (width - 1)) & 1;
      if( c >= width ) res = (sign != 0) ? M : 0;
      else { res = dst >>> c; if( sign != 0 ) res |= (M >>> c) ^ M; }
    }
    res &= M;
    set( dinfo.dst, (int)res );
    flag_eval( (int)res );                                 // SF/ZF/PF
    if( op == 0 )      { if( c < width ) cf = (int)((dst >> (width - c)) & 1); }
    else if( op == 1 ) { if( c < width ) cf = (int)((dst >> (c - 1)) & 1); }
    else               { cf = (c <= width) ? (int)((dst >> (c - 1)) & 1)
                                           : (int)((dst >> (width - 1)) & 1); }
    if( c == 1 ) {                                          // OF は count=1 のみ
      if( op == 0 )      of = (int)((((res >> (width - 1)) & 1)) ^ (cf & 1));
      else if( op == 1 ) of = (int)((dst >> (width - 1)) & 1);
      else               of = 0;
    }
  }
  void shl( ) { shiftOp( 0 ); }

  // SHLD命令
  // SHLD(left=true)/SHRD: double precision shift。count=dinfo.fst を 0x1F マスク。
  //   dst を c bit シフトし、空いた側を src2 の反対端から埋める。CF=最後にはみ出した
  //   dst の bit、OF は count=1 のみ (符号変化)、SF/ZF/PF は結果から、AF undefined。
  //   count>width (16bit 形のみ) は結果/フラグ undefined。count=0 は全フラグ不変。
  //   旧実装は 64bit 連結を誤方向にシフトし width/CF/OF が全滅だった (insn_model._dshift)。
  void dshiftOp( boolean left ) {
    int size = calc_operand_size( );
    int width = size * 8;
    long M = (size == 1) ? 0xFFL : (size == 2) ? 0xFFFFL : 0xFFFFFFFFL;
    int c = ref( dinfo.fst ) & 0x1F;
    long dst  = ((long)ref( dinfo.dst )) & M;
    long src2 = ((long)ref( dinfo.src )) & M;
    if( c == 0 ) { set( dinfo.dst, (int)dst ); return; }
    if( c > width ) { return; }                            // undefined (テスト対象外)
    long res;
    int cfbit;
    if( left ) {
      res = (c < width) ? (((dst << c) | (src2 >>> (width - c))) & M) : src2;
      cfbit = (int)((dst >> (width - c)) & 1);
    } else {
      res = (c < width) ? (((dst >>> c) | (src2 << (width - c))) & M) : src2;
      cfbit = (int)((dst >> (c - 1)) & 1);
    }
    res &= M;
    set( dinfo.dst, (int)res );
    flag_eval( (int)res );
    cf = cfbit;
    if( c == 1 ) of = (int)(((res >> (width - 1)) & 1) ^ ((dst >> (width - 1)) & 1));
  }
  void shld( ) { dshiftOp( true ); }

  // SHRD命令
  void shrd( ) { dshiftOp( false ); }

  // SHR命令
  void shr( ) { shiftOp( 1 ); }

  // SAR命令
  void sar( ) { shiftOp( 2 ); }

  // ROL命令
  // ROL(0)/ROR(1) 共通。count は 0x1F マスク、実回転量は cm%width。CF=回転後の
  //   LSB(rol)/MSB(ror)、OF は masked count=1 のみ、SF/ZF/PF/AF は不変 (SDM: rotate は
  //   CF/OF のみ)。masked count=0 は全フラグ不変。旧実装は多倍 count・width マスク・
  //   OF が誤りだった (insn_model._rotate の写し)。
  void rotateOp( int op ) {
    int size = calc_operand_size( );
    int width = size * 8;
    long M = (size == 1) ? 0xFFL : (size == 2) ? 0xFFFFL : 0xFFFFFFFFL;
    int cm = ref( dinfo.src ) & 0x1F;
    long dst = ((long)ref( dinfo.dst )) & M;
    if( cm == 0 ) { set( dinfo.dst, (int)dst ); return; }
    int rc = cm % width;
    long res = (op == 0)
        ? (rc == 0 ? dst : (((dst << rc) | (dst >>> (width - rc))) & M))
        : (rc == 0 ? dst : (((dst >>> rc) | (dst << (width - rc))) & M));
    res &= M;
    set( dinfo.dst, (int)res );
    cf = (op == 0) ? (int)(res & 1) : (int)((res >> (width - 1)) & 1);
    if( cm == 1 ) {
      if( op == 0 ) of = (int)(((res >> (width - 1)) & 1) ^ (res & 1));
      else          of = (int)(((res >> (width - 1)) & 1) ^ ((res >> (width - 2)) & 1));
    }
  }
  void rol( ) { rotateOp( 0 ); }

  // ROR命令
  void ror( ) { rotateOp( 1 ); }

  // RCL命令 (rotate through carry left)。tempCount = 8bit:cm%9 / 16bit:cm%17 / 32bit:cm。
  //   CF は carry を通した最終値、OF は count=1 のみ、SF/ZF/PF/AF 不変。
  void rcl( ) {
    int size = calc_operand_size( );
    int width = size * 8;
    long M = (size == 1) ? 0xFFL : (size == 2) ? 0xFFFFL : 0xFFFFFFFFL;
    int cm = ref( dinfo.src ) & 0x1F;
    long dst = ((long)ref( dinfo.dst )) & M;
    if( cm == 0 ) { set( dinfo.dst, (int)dst ); return; }
    int tc = (width == 8) ? cm % 9 : (width == 16) ? cm % 17 : cm;
    long cfl = cf & 1;
    long res = dst;
    for( int i = 0; i < tc; i++ ) {
      long new_cf = (res >> (width - 1)) & 1;
      res = ((res << 1) & M) | cfl;
      cfl = new_cf;
    }
    set( dinfo.dst, (int)(res & M) );
    int of_v = (int)(((res >> (width - 1)) & 1) ^ cfl);
    cf = (int)cfl;
    if( cm == 1 ) of = of_v;
  }

  // TEST命令
  void test( ) {
    int ival;
    int orig = ref( dinfo.dst );
    ival = ref( dinfo.src ) & orig;
    flag_eval( ival );
    of = 0;
    cf = 0;
  }

  // BT命令
  void bt( int inst_id ) {
    // register operand の bit offset は operand-size で mod (r16=&0xF, r32=&0x1F)。
    //   旧実装は 0x1F 固定で r16 の BT/BTS/BTR が誤っていた。
    int size = calc_operand_size( );
    int s = ref( dinfo.src ) & ((size == 2) ? 0xF : 0x1F);
    int d = ref( dinfo.dst );
    cf = (d >> s) & 1;
    if( inst_id == Instruction.BTS ) {
      set( dinfo.dst, (d | (1 << s)));
    }
    if( inst_id == Instruction.BTR ) {
      set( dinfo.dst, (d & (~(1 << s))));
    }
  }

  // BS?命令
  void bsX( int inst_id ) {
    // BSF=最下位、BSR=最上位の 1 bit の index。src==0 で ZF=1 (dst は undefined)。
    //   旧実装は BSF が 1 反復で break し常に 0、BSR は算術シフト (>>=) で高位ビットで
    //   壊れていた。numberOfTrailing/LeadingZeros で正しく求める。
    int size = calc_operand_size( );
    long wm = (size == 2) ? 0xFFFFL : 0xFFFFFFFFL;
    int s = (int)(((long)ref( dinfo.src )) & wm);
    zf = 0;
    if( s == 0 ) { zf = 1; }
    else {
      int b;
      if( Instruction.BSF == inst_id ) { b = Integer.numberOfTrailingZeros( s ); }
      else { b = 31 - Integer.numberOfLeadingZeros( s ); }   // BSR = 最上位 set bit index
      set( dinfo.dst, b );
    }
  }

  // CBW命令
  void cbw( ) {
    int size = calc_operand_size( );
    if( size == 2 ) { reg[AX] = Util.expand_sign( reg[AX], 1 ); }
    if( size == 4 ) { reg[AX] = Util.expand_sign( reg[AX], 2 ); }
  }

  // FLD 命令
  // x87 FPU: 8 段レジスタスタック (double)。80-bit 拡張精度は持たず double で近似する
  //   (i386 最小サブセット、issue #24 Phase 3 i386-2)。旧実装は単一 float_stack スカラで
  //   FLD/FST の copy のみ・算術は全て空だった。
  private final double[] fpu_st = new double[8];
  private int fpu_top = 0;
  private void   fpuPush( double v ) { fpu_top = (fpu_top - 1) & 7; fpu_st[fpu_top] = v; }
  private double fpuPop( )           { double v = fpu_st[fpu_top]; fpu_top = (fpu_top + 1) & 7; return v; }
  private double fpuSt( int i )      { return fpu_st[(fpu_top + i) & 7]; }
  private void   fpuSetSt( int i, double v ) { fpu_st[(fpu_top + i) & 7] = v; }
  // メモリオペランドを double 値として読む (m32=float / m64=double)。
  private double fpuMemRead( ) {
    return (calc_operand_size( ) == 4)
        ? (double)Float.intBitsToFloat( (int)ref( dinfo.src ) )
        : Double.longBitsToDouble( ref64( dinfo.src ) );
  }

  void fld( ) {
    fpuPush( fpuMemRead( ) );                          // push m32/m64
  }

  // FST / FSTP 命令: st(0) を m32/m64 へ格納 (FSTP は pop)。
  void fst( int inst_id ) {
    double v = fpuSt( 0 );
    if( calc_operand_size( ) == 4 ) set( dinfo.src, Float.floatToRawIntBits( (float)v ) );
    else                            set64( dinfo.src, Double.doubleToRawLongBits( v ) );
    if( inst_id == Instruction.FSTP ) fpuPop( );
  }

  // FADD/FSUB/FSUBR/FMUL/FDIV/FDIVR (st(0) op= mem)。
  void farith( int op ) {
    double b = fpuMemRead( );
    double a = fpuSt( 0 );
    double r;
    if      ( op == Instruction.FADD )  r = a + b;
    else if ( op == Instruction.FSUB )  r = a - b;
    else if ( op == Instruction.FSUBR ) r = b - a;
    else if ( op == Instruction.FMUL )  r = a * b;
    else if ( op == Instruction.FDIV )  r = a / b;
    else                                r = b / a;      // FDIVR
    fpuSetSt( 0, r );
  }

  void fsqrt( ) { fpuSetSt( 0, Math.sqrt( fpuSt( 0 ) ) ); }
  void fabsx( ) { fpuSetSt( 0, Math.abs( fpuSt( 0 ) ) ); }
  void fld1( )  { fpuPush( 1.0 ); }
  void fldz( )  { fpuPush( 0.0 ); }
  void fldpi( ) { fpuPush( Math.PI ); }

  // FILD 命令: int32 → double を push。
  void fild( ) {
    fpuPush( (double)ref( dinfo.src ) );
  }

  // FISTP 命令: st(0) を pop し int32 (RN 丸め) で格納。
  void fistp( ) {
    set( dinfo.src, (int)Math.rint( fpuPop( ) ) );
  }

  // FCHS 命令
  void fchs( ) { fpuSetSt( 0, -fpuSt( 0 ) ); }

  // FXCH 命令: st(0) と st(1) を入替 (最小: st(1) 固定)。
  void fxch( ) { double t = fpuSt( 0 ); fpuSetSt( 0, fpuSt( 1 ) ); fpuSetSt( 1, t ); }

  // NOT命令
  void not( ) {
    set( dinfo.src, ~ ref( dinfo.src ));
  }

  // NEG命令
  void neg( ) {
    set( dinfo.src, (~ ref( dinfo.src ))+1 );  
  }

  // 値の結果により,フラグを変化させる
  void flag_eval( int result ) {
    int i;
    int size = calc_operand_size( );
    pf = 1; // パリティーフラグ
    for( i = 0 ; i < 8 ; i++ ) {
      pf += (result >> i) & 1;
    }
    pf &= 1;
    
    if( 0 == result ) { zf = 1; } else { zf = 0; }
    sf = 1;
    if( size == 1 ) { if( 0 == (       0x80 & result )) { sf = 0; } }
    if( size == 2 ) { if( 0 == (     0x8000 & result )) { sf = 0; } }
    if( size == 4 ) { if( 0 == ( 0x80000000 & result )) { sf = 0; } }
  }

  // 演算の結果オーバーフローしたかどうか調べ,フラグ変化させる
  void overflow_eval( long result ) {
    of = 0;
    int size = calc_operand_size( );
    // 符号付き結果が width に収まらなければ OF。負側の下限は MIN_INT (=-2^(w-1))。
    //   旧実装は下限を -0x7F.. (=-(2^(w-1)-1)) にしており INT_MIN 等で OF を誤設定していた。
    if( size == 1 ) {
      if( result > 0x7FL || result < -0x80L )  { of = 1; }
    }
    if( size == 2 ) {
      if( result > 0x7FFFL || result < -0x8000L )  { of = 1; }
    }
    if( size == 4 ) {
      if( result > 0x7FFFFFFFL || result < -0x80000000L )  { of = 1; }
    }
  }

  void fnstcw( ) {
  }

  void fldcw( ) {
  }

  // 未定義/未実装命令 → #UD → SIGILL、特権命令 → #GP → SIGSEGV。旧実装は System.exit(0)
  //   で「静かな exit(0)」にしていた (fork 子が clean exit に見え未実装を検出できず、
  //   Cpu64 の #645 と同クラス)。term_sig を set し SegfaultException で run ループを
  //   抜ける (fork 子は親が wait4 で WIFSIGNALED、main process は 128+sig)。i386 は
  //   signal ハンドラ配送インフラが無いので終了のみ (配送は将来対応)。
  private void raiseSig( int sig, String what ) {
    process.println( "Cpu: " + what + " at 0x" + Util.hexstr( ip, 8 )
                     + " -> " + (sig == Signal.SIGILL ? "SIGILL" : "SIGSEGV") );
    process.term_sig = sig;
    throw new Memory.SegfaultException( ip, 0, sig );
  }

  // 未サポート命令
  void unsupported( ) {
    raiseSig( Signal.SIGILL, "unsupported instruction [" + disasm_str( next_ip ) + "]" );
  }

  // オペランドサイズを計算する
  int calc_operand_size( ) {
    int size = 4;
    if( dinfo.o16_flag )   { size = 2; }
    else {
      if( dinfo.W_flag && ( dinfo.W_val == 0 )) { size = 1; }
      if( dinfo.w_flag && ( dinfo.w_val == 0 )) { size = 1; }
    }
    if( dinfo.D_flag && (dinfo.D_val != 0)) { size = 8; }
    return( size );
  }

  // Operand 型を解析し,値を取り出す (符号拡張も行う)
  long ref_expand( Operand ope ) {
    long ret = 0;
    int size = calc_operand_size( );
    if( size == 1 ) {	ret = _ref( ope ) & 0xFFL       ;   }
    if( size == 2 ) {	ret = _ref( ope ) & 0xFFFFL     ;   }
    if( size == 4 ) {	ret = _ref( ope ) & 0xFFFFFFFFL ;   }
    if( size == 8 ) {	ret = _ref( ope )               ;   }
    if( size == 1 ) {   if( 0 != ( ret &       0x80L )) { ret |= 0xFFFFFFFFFFFFFF00L; }}
    if( size == 2 ) {   if( 0 != ( ret &     0x8000L )) { ret |= 0xFFFFFFFFFFFF0000L; }}
    if( size == 4 ) {   if( 0 != ( ret & 0x80000000L )) { ret |= 0xFFFFFFFF00000000L; }}
    return( ret );
  }
  // Operand 型を解析し,値を取り出す (32bit)
  int ref( Operand ope ) {
    long ret = 0;
    int size = calc_operand_size( );
    if( size == 1 ) {	ret = _ref( ope ) & 0xFFL       ;   }
    if( size == 2 ) {	ret = _ref( ope ) & 0xFFFFL     ;   }
    if( size == 4 ) {	ret = _ref( ope ) & 0xFFFFFFFFL ;   }
    if( size == 8 ) {	ret = _ref( ope )               ;   }
    return( (int)ret );
  }
  // Operand 型を解析し,値を取り出す (64bit)
  long ref64( Operand ope ) {
    long ret = 0;
    int size = calc_operand_size( );
    if( size == 1 ) {	ret = _ref( ope ) & 0xFFL       ;   }
    if( size == 2 ) {	ret = _ref( ope ) & 0xFFFFL     ;   }
    if( size == 4 ) {	ret = _ref( ope ) & 0xFFFFFFFFL ;   }
    if( size == 8 ) {	ret = _ref( ope )               ;   }
    return( ret );
  }
  long _ref( Operand ope ) {
    long ret = 0;
    int size = calc_operand_size( );
    if(( ope.kind == Operand.REG ) || ( ope.kind == Operand.IREG )) {
      ret = (long)reg[ ope.reg_no ];
    }
    if( ope.kind == Operand.HREG ) {
      if( ope.reg_no == AL ) { ret = (long)(0xFF & reg[ AX ]); }
      if( ope.reg_no == CL ) { ret = (long)(0xFF & reg[ CX ]); }
      if( ope.reg_no == DL ) { ret = (long)(0xFF & reg[ DX ]); }
      if( ope.reg_no == BL ) { ret = (long)(0xFF & reg[ BX ]); }
      if( ope.reg_no == AH ) { ret = (long)(0xFF & (reg[ AX ] >> 8)); }
      if( ope.reg_no == CH ) { ret = (long)(0xFF & (reg[ CX ] >> 8)); }
      if( ope.reg_no == DH ) { ret = (long)(0xFF & (reg[ DX ] >> 8)); }
      if( ope.reg_no == BH ) { ret = (long)(0xFF & (reg[ BX ] >> 8)); }
    }
    if( ope.kind == Operand.RREG ) {
      ret = loadby_size( ea( ope ), size );
    }
    if( ope.kind == Operand.DISP ) {
      ret = (long)(next_ip + ope.disp);
    }
    if( ope.kind == Operand.IMM ) {
      ret = (long)ope.imm;
    }
    if( ope.kind == Operand.MEM ) {
      ret = loadby_size( ope.adrs,  size );
    }
    if( ope.kind == Operand.EA ) {
      ret = loadby_size( ea( ope ), size );
    }
    if( ope.kind == Operand.REA ) {
      ret = loadby_size( ea( ope ), size );
    }
    return( ret );
  }

  // オペランドがメモリか？ (未デバッグ)
  //  boolean is_MEM( Operand ope ) {
  //    boolean ret = false;
  //    if( ope.kind == Operand.RREG ) { ret = true; }
  //    if( ope.kind == Operand.MEM )  { ret = true; }
  //    if( ope.kind == Operand.EA )   { ret = true; }
  //    if( ope.kind == Operand.REA )  { ret = true; }
  //  }

  // 指定サイズでメモリリードする
  long loadby_size( long address, int size ) {
    long ret = 0;
    if( size == 1 ) { ret = (long)mem.load8 ( address ); }
    if( size == 2 ) { ret = (long)mem.load16( address ); }
    if( size == 4 ) { ret = (long)mem.load32( address ); }
    if( size == 8 ) { ret = (long)mem.load64( address ); }
    return( ret );
  }

  // ope のEffective Address を返す
  long ea( Operand ope ) {
    long ret = 0;
    if( ope.kind == Operand.RREG ) {
      ret = (long)ope.disp + ((long)reg[ ope.reg_no ] & 0xFFFFFFFFL);
    }
    if( (ope.kind == Operand.EA) || (ope.kind == Operand.REA ) ) {
      if( ope.base_is_reg ) {
	ret = (long)reg[ ope.base_reg ] & 0xFFFFFFFFL;
      }
      else {
	ret = (long)ope.base_val & 0xFFFFFFFFL;
      }
      if( ope.index_reg != Cpu.SP ) {
	ret += ((long)reg[ ope.index_reg ] & 0xFFFFFFFFL) * ope.scale;
      }
      if( ope.ea_disp_flag ) {
	ret += ope.disp;
      }
    }
    return( ret );
  }

  // opeで示す場所に data をセットする
  void set( Operand ope, int data ) {
    int size = calc_operand_size( );
    _set( ope, (int)data, size );
  }
  // opeで示す場所に data をセットする
  void set64( Operand ope, long data ) {
    int size = calc_operand_size( );
    _set( ope, data, size );
  }
  void _set( Operand ope, long ldata, int size ) {
    int data = (int)ldata;
    if( ope.kind == Operand.REG ) {
      if( sysinfo.debug( )) {
	process.println( "_set data = " + Util.hexstr( data, 8 ));
      }
      if( size == 1 ) {
	reg[ ope.reg_no ] = (0xFFFFFF00 & reg[ ope.reg_no ]) | data;
      }
      if( size == 2 ) {
	reg[ ope.reg_no ] = (0xFFFF0000 & reg[ ope.reg_no ]) | data;
      }
      if( size == 4 ) {
	reg[ ope.reg_no ] = data;
      }
      if( size == 8 ) {
	reg[ ope.reg_no ] = data;
      }
    }
    if( ope.kind == Operand.HREG ) {
      if( size == 1 ) {
	if( ope.reg_no == AL ) { reg[ AX ] = ((0xFFFFFF00 & reg[ AX ]) | (data & 0xFF)); }
	if( ope.reg_no == CL ) { reg[ CX ] = ((0xFFFFFF00 & reg[ CX ]) | (data & 0xFF)); }
	if( ope.reg_no == DL ) { reg[ DX ] = ((0xFFFFFF00 & reg[ DX ]) | (data & 0xFF)); }
	if( ope.reg_no == BL ) { reg[ BX ] = ((0xFFFFFF00 & reg[ BX ]) | (data & 0xFF)); }
	if( ope.reg_no == AH ) { reg[ AX ] = ((0xFFFF00FF & reg[ AX ]) | ((data & 0xFF) << 8)); }
	if( ope.reg_no == CH ) { reg[ CX ] = ((0xFFFF00FF & reg[ CX ]) | ((data & 0xFF) << 8)); }
	if( ope.reg_no == DH ) { reg[ DX ] = ((0xFFFF00FF & reg[ DX ]) | ((data & 0xFF) << 8)); }
	if( ope.reg_no == BH ) { reg[ BX ] = ((0xFFFF00FF & reg[ BX ]) | ((data & 0xFF) << 8)); }
      }
      if( size == 2 ) {
	process.println( "   Operand.HREG:  unsupported size  size=2 " );
	System.exit( 1 );
      }
      if( size == 4 || size == 8 ) {
	if( ope.reg_no == AL ) { reg[ AX ] = data; }
	if( ope.reg_no == CL ) { reg[ CX ] = data; }
	if( ope.reg_no == DL ) { reg[ DX ] = data; }
	if( ope.reg_no == BL ) { reg[ BX ] = data; }
      }
    }
    if( ope.kind == Operand.RREG ) {
      long adrs = (long)ope.disp + ((long)reg[ ope.reg_no ] & 0xFFFFFFFFL);
      if( size == 1 ) {  mem.store8( adrs, data & 0xFF   ); }
      if( size == 2 ) { mem.store16( adrs, (short)(data & 0xFFFF) ); }
      if( size == 4 ) { mem.store32( adrs, (int)data     ); }
      if( size == 8 ) { mem.store64( adrs, ldata         ); }
    }
    if( ope.kind == Operand.MEM ) {
      long adrs = (long)ope.adrs & 0xFFFFFFFFL;
      if( size == 1 ) {  mem.store8( adrs, data & 0xFF   ); }
      if( size == 2 ) { mem.store16( adrs, (short)(data & 0xFFFF) ); }
      if( size == 4 ) { mem.store32( adrs, (int)data     ); }
      if( size == 8 ) { mem.store64( adrs, ldata         ); }
    }
    if( ope.kind == Operand.EA ) {
      long adrs = 0;
      if( ope.base_is_reg ) {
	adrs = (long)reg[ ope.base_reg ] & 0xFFFFFFFFL;
      }
      else {
	adrs = (long)ope.base_val & 0xFFFFFFFFL;
      }
      if( ope.index_reg != Cpu.SP ) {
	adrs += ((long)reg[ ope.index_reg ] & 0xFFFFFFFFL) * ope.scale;
      }
      if( ope.ea_disp_flag ) {
	adrs += ope.disp;
      }
      if( size == 1 ) {  mem.store8( adrs, data & 0xFF   ); }
      if( size == 2 ) { mem.store16( adrs, (short)(data & 0xFFFF) ); }
      if( size == 4 ) { mem.store32( adrs, data          ); }
      if( size == 8 ) { mem.store64( adrs, ldata         ); }
    }
  }

  // メモリ等のシステムを接続する
  public void connect_devices( Memory _mem, Syscall _syscall ) {
    mem = _mem;
    syscall = _syscall;
    syscall.connect_mem( mem );
  }

  // フェッチする
  public void fetch( long address, byte buf[] ) {
    mem.fetch( address, buf );
  }

  // フェッチする(code)
  //  public void codefetch( int address, byte buf[] ) {
  //    mem.codefetch( address, buf );
  //  }

  // 値をPUSHする
  public void push32( long value ) {
    reg[SP] -= 4;
    mem.store32( get_sp( ), (int)value );
    if( sysinfo.debug( )) {
      long address = get_sp( );
      mem.dump( address, 16 );
    }
  }

  // 値をPOPする
  public int pop32( ) {
    int value;
    value = mem.load32( get_sp( ) );
    reg[SP] += 4;
    return( value );
  }

  // CPUの内部情報(register) の文字列を返す
  public String reg_str( ) {
    int i;
    String ret = "";
    for( i = 0 ; i < MAX_REG ; i++ ) {
      ret += Operand.reg_name( i ) + "=" + Util.hexstr( reg[i], 8 ) + " ";
    }
    return( ret );
  }

  // CPUの内部情報(ip) の文字列を返す
  public String ip_str( ) {
    return( "ip=" + Util.hexstr( ip, 8 ) + " " );
  }

  // CPUの内部情報(flag)  の文字列を返す
  public String flag_str( ) {
    String ret = "";
    ret += "of=" + Util.hexstr( of, 1 ) + " ";
    ret += "df=" + Util.hexstr( df, 1 ) + " ";
    ret += "sf=" + Util.hexstr( sf, 1 ) + " ";
    ret += "zf=" + Util.hexstr( zf, 1 ) + " ";
    ret += "af=" + Util.hexstr( af, 1 ) + " ";
    ret += "pf=" + Util.hexstr( pf, 1 ) + " ";
    ret += "cf=" + Util.hexstr( cf, 1 ) + " ";
    return( ret );
  }


  // アセンブル文字列を返す
  public String disasm_str( long address ) {
    int i;
    String ret = "";
    String sym;
    int size = calc_operand_size( );
    if( dinfo.repnz_flag ) { ret += "REPNZ "; }
    if( dinfo.repz_flag )  { ret += "REPZ "; }
    if( (inst[dinfo.inst_index].id == Instruction.CALL) || (inst[dinfo.inst_index].id == Instruction.RETN) ) {
      int ten = 0;
      int n = nest;
      if( inst[dinfo.inst_index].id == Instruction.RETN ) {
	n--;
      }
      ret += "("+n+")";
      if( n >= 10 ) {
	ret += "+";
	ten++;
      }
      for( i = 0 ; i < (n - ten*10); i++ ) {
	ret += " ";
      }
    }
    ret += inst[dinfo.inst_index].inst_name;
    if( dinfo.D_flag ) {
      if( size == 4 )  { ret += "W"; }
      if( size == 8 )  { ret += "L"; }
    }
    else {
      if( size == 1 )  { ret += "B"; }
      if( size == 2 )  { ret += "W"; }
      //    if( size == 4 )  { ret += "L"; }
    }
    if( dinfo.c_flag )     { ret += cond_str[dinfo.c_val]; }
    ret += "    ";

    if( dinfo.fst.kind != Operand.NONE ) {
      ret += dinfo.fst.operand_str( address ) + ",";
    }
    ret += dinfo.src.operand_str( address );
    if( dinfo.src.kind == Operand.DISP ) {
      sym = mem.get_symbol( address + dinfo.src.disp );
      if( null != sym ) {
	ret += "<" + sym + ">";
      }
    }
    if( dinfo.src.kind == Operand.IREG ) {
      sym = mem.get_symbol( reg[ dinfo.src.reg_no ] );
      if( null != sym ) {
	ret += "<" + sym + ">";
      }
    }
    if( dinfo.dst.kind != Operand.NONE ) {
      ret += "," + dinfo.dst.operand_str( address );
    }
    if(( Instruction.CALL == dinfo.inst_id )|| ( Instruction.RETN == dinfo.inst_id )) {
      ret += " { esp = " + Util.hexstr( get_sp( ), 8 ) + " ; evals = " + process.evals( ) + " } ";
    }
    return( ret );
  }
}
