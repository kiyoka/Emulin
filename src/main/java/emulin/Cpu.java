// ----------------------------------------
//  Cpu Emulator
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 2000/01/23 11:16:10 $ 
//  $Id: Cpu.java,v 1.69 2000/01/23 11:16:10 kiyoka Exp $
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
    if( dinfo.inst_id == Instruction.FCHS )    {   done = true; fchs( ); }
    if( dinfo.inst_id == Instruction.FXCH )    {   done = true; fxch( ); }
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
    int ival;
    int d = ref( dinfo.dst );
    int s = ref( dinfo.src );
    ival = s + d + plus;
    flag_eval( ival );
    set( dinfo.dst, ival );
    cf = 0;
    overflow_eval( (long)s + (long)d + (long)plus);
    if( (((long)d & 0xFFFFFFFFL) + ((long)s & 0xFFFFFFFFL) + ((long)plus & 0xFFFFFFFFL)) >= 0x100000000L ) {  cf = 1; }
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
    overflow_eval( (long)d - ( (long)s + (long)minus  ));
    if( size == 1 ) { ival &= 0xFF; }
    if( size == 2 ) { ival &= 0xFFFF; }
    flag_eval( ival );
    if( set_flag ) {   set( dinfo.dst, ival ); }
    cf = 0;
    if( sysinfo.debug( )) {
      process.println( "  CMP " + Util.hexstr( s, 8 ) + "," + Util.hexstr( d, 8 )   + " -> " + Util.hexstr( ival, 8 ));
    }
    if( size == 1 ) { if( ((long)d & 0xFFL) < (((long)s + (long)minus) & 0xFFL ) ) { cf = 1; } }
    if( size == 2 ) { if( ((long)d & 0xFFFFL) < (((long)s + (long)minus) & 0xFFFFL ) ) { cf = 1; } }
    if( size == 4 ) { if( ((long)d & 0xFFFFFFFFL) < (((long)s + (long)minus) & 0xFFFFFFFFL ) ) { cf = 1; } }
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
      int val = 0;
      val |= of << 6;
      val |= df << 5;
      val |= sf << 4;
      val |= zf << 3;
      val |= af << 2;
      val |= pf << 1;
      val |= cf << 0;
      push32( val );
  }

  // POPF命令
  void popf( ) {
      int val = 0;
      val = pop32( );
      of = (val >> 6) & 1;
      df = (val >> 5) & 1;
      sf = (val >> 4) & 1;
      zf = (val >> 3) & 1;
      af = (val >> 2) & 1;
      pf = (val >> 1) & 1;
      cf = (val >> 0) & 1;
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
      process.println( "Unsupported Condition ... \n" );
      System.exit( 0 );
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

  // HLT命令
  void hlt( ) {
    process.println( "Application is halted ... \n" );
    System.exit( 0 );
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
    long d = (long)reg[AX];
    long s = (long)ref( dinfo.src );
    if( ! sign_flag ) {
      d &= 0xFFFFFFFFL;
      s &= 0xFFFFFFFFL;
    }
    reg[AX] = (int)(d / s);
    reg[DX] = (int)(d % s);
  }

  // IMUL命令
  void imul( ) {
    long lval;
    boolean use_dx = false;
    if( dinfo.dst.kind == Operand.NONE ) {
      dinfo.dst.kind   = Operand.REG;
      dinfo.dst.reg_no = AX;
      use_dx     = true;
    }
    long d = (long)ref( dinfo.dst );
    long s = (long)ref( dinfo.src );
    lval = (d * s);
    set( dinfo.dst, (int)lval );
    if( use_dx ) { reg[DX] = (int)(lval >> 32); }
    overflow_eval( lval );
    flag_eval( (int)lval );
  }

  // MUL命令 ( 符号なし乗算 )
  void mul( ) {
    int size = calc_operand_size( );
    boolean use_dx = false;
    long lval;
    long s = (long)ref( dinfo.src ) & 0xFFFFFFFFL;
    long d = 0;
    // オペランドが 1つだけなら, DST を AX として扱う( 但し、上位 32bit は DX へ )
    if( dinfo.dst.kind == Operand.NONE ) {
      dinfo.dst.kind   = Operand.REG;
      dinfo.dst.reg_no = AX;
      use_dx           = true;
    }
    d = (long)ref( dinfo.dst ) & 0xFFFFFFFFL; /* reg[AX] 固定 */

    lval = (d * s);
    set( dinfo.dst, (int)lval );
    overflow_eval( lval );
    flag_eval( (int)lval );
    cf = 0;
    if( 0xFFFFFFFFL < lval ) { cf = 1; }
    if( use_dx ) {
      if( size == 2 ) {
	reg[DX] = (int)(lval >> 16) & 0xFFFF; /* 上位 16bit */
      }
      if( size == 4 ) {
	reg[DX] = (int)(lval >> 32);          /* 上位 32bit */
      }
    }
  }

  // SHL命令
  void shl( ) {
    int s = ref( dinfo.src ) & 0x1F;
    int d = ref( dinfo.dst );
    int ival = d << s;
    overflow_eval( (long)d << s );
    flag_eval( ival );
    set( dinfo.dst, ival );
  }

  // SHLD命令
  void shld( ) {
    int f = ref( dinfo.fst ) & 0x1F;
    int s = ref( dinfo.src );
    int d = ref( dinfo.dst );
    long lval = ((long)d & 0xFFFFFFFFL) << 32;
    int ival;
    lval |= (long)s & 0xFFFFFFFFL;
    lval <<= f;
    ival = (int)lval;
    overflow_eval( (long)lval << f );
    flag_eval( ival );
    set( dinfo.dst, ival );
  }

  // SHRD命令
  void shrd( ) {
    int f = ref( dinfo.fst ) & 0x1F;
    int s = ref( dinfo.src );
    int d = ref( dinfo.dst );
    long lval = ((long)d & 0xFFFFFFFFL);
    int ival;
    lval |= ((long)s & 0xFFFFFFFFL) << 32;
    lval >>= f;
    ival = (int)lval;
    overflow_eval( (long)lval >> f );
    flag_eval( ival );
    set( dinfo.dst, ival );
  }

  // SHR命令
  void shr( ) {
    int s = ref( dinfo.src ) & 0x1F;
    int d = ref( dinfo.dst );
    int ival = d >>> s;
    overflow_eval( (long)ival >>> d );
    flag_eval( ival );
    set( dinfo.dst, ival );
  }

  // SAR命令
  void sar( ) {
    int s = ref( dinfo.src ) & 0x1F;
    int d = ref( dinfo.dst );
    int ival = d >> s;
    overflow_eval( (long)ival >> d );
    flag_eval( ival );
    set( dinfo.dst, ival );
  }

  // ROL命令
  void rol( ) {
    int s = ref( dinfo.src ) & 0x1F;
    int d = ref( dinfo.dst );
    int ival = d;
    int i;
    int size = calc_operand_size( );
    int ror_val = 0;
    if( size == 1 ) { ror_val = 0x80; }
    if( size == 2 ) { ror_val = 0x8000; }
    if( size == 4 ) { ror_val = 0x80000000; }
    for( i = 0 ; i < s ; i++ ) {
      if( 0 != ( ival & ror_val    )) {	ival = ival << 1 | 1; cf = 1;}
      else                            { ival = ival << 1    ; cf = 0;}
    }
    set( dinfo.dst, ival );
  }

  // ROR命令
  void ror( ) {
    int s = ref( dinfo.src ) & 0x1F;
    int d = ref( dinfo.dst );
    int ival = d;
    int i;
    int size = calc_operand_size( );
    int ror_val = 0;
    if( size == 1 ) { ror_val = 0x80; }
    if( size == 2 ) { ror_val = 0x8000; }
    if( size == 4 ) { ror_val = 0x80000000; }
    for( i = 0 ; i < s ; i++ ) {
      if( 0 != ( ival & 0x1        )) {	ival = ival >> 1 | ror_val   ; cf = 1;}
      else                            { ival = ival >> 1             ; cf = 0;}
    }
    set( dinfo.dst, ival );
  }

  // RCL命令
  void rcl( ) {
    int s = ref( dinfo.src ) & 0x1F;
    long d = ref( dinfo.dst );
    long ror_val = 0;
    long ival = d;
    int i;
    int size = calc_operand_size( );
    if( size == 1 ) { ival &= 0xFFL;       ror_val = 0x100L;       }
    if( size == 2 ) { ival &= 0xFFFFL;     ror_val = 0x10000L;     }
    if( size == 4 ) { ival &= 0xFFFFFFFFL; ror_val = 0x100000000L; }
    if( 1 == cf ) { ival |= ror_val; }
    for( i = 0 ; i < s ; i++ ) {
      if( 0 != ( ival & ror_val    )) {	ival = ival << 1 | 1; cf = 1;}
      else                            { ival = ival << 1    ; cf = 0;}
    }
    cf = 0;
    if( size == 1 ) { if( 0 != (        0x100L  & ival )) { cf = 1;  }}
    if( size == 2 ) { if( 0 != (      0x10000L  & ival )) { cf = 1;  }}
    if( size == 4 ) { if( 0 != (  0x100000000L  & ival )) { cf = 1;  }}
    set( dinfo.dst, (int)ival );
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
    int s = ref( dinfo.src ) & 0x1F;
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
    int s = ref( dinfo.src );
    int d = ref( dinfo.dst );
    zf = 0;
    if( s == 0 ) { zf = 1; }
    else {
      int i;
      int b = 0;
      for( i = 0 ; i < 32 ; i++ ) {
	if( 1 == (1 & s)) { b = i; }
	s >>= 1;
	if( Instruction.BSF == inst_id ) {
	  break;
	}
      }
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
  void fld( ) {
    float_stack = ref64( dinfo.src );
  }

  // FST 命令
  void fst( int inst_id ) {
    int size = calc_operand_size( );
    if( size == 4 ) {   set( dinfo.src, (int)float_stack ); }
    else            { set64( dinfo.src,      float_stack ); }
  }

  // FILD 命令
  void fild( ) {
    int ival = ref( dinfo.src );
    float_stack = 0;
  }

  // FCHS 命令
  void fchs( ) {
  }

  // FXCH 命令
  void fxch( ) {
  }

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
    if( size == 1 ) {
      if( result > 0x7FL )  { of = 1; }
      if( result < -0x7FL ) { of = 1; }
    }
    if( size == 2 ) {
      if( result > 0x7FFFL )  { of = 1; }
      if( result < -0x7FFFL ) { of = 1; }
    }
    if( size == 4 ) {
      if( result > 0x7FFFFFFFL )  { of = 1; }
      if( result < -0x7FFFFFFFL ) { of = 1; }
    }
  }

  void fnstcw( ) {
  }

  void fldcw( ) {
  }

  // 未サポート命令
  void unsupported( ) {
    process.println( "Unsupported Instruction ... [" + disasm_str( next_ip ) + "]" );
    mem.dump( ip, 16 );
    System.exit( 0 );
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
