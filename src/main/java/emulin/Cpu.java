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
    // LOCK prefix は memory destination のみ有効。register 宛先 (例: lock add %eax,%eax) は #UD。
    if( dinfo.lock_flag && ( dinfo.dst.kind == Operand.REG || dinfo.dst.kind == Operand.HREG ) ) {
      raiseSig( Signal.SIGILL, "LOCK with register destination (#UD)" );
    }
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
    if( dinfo.inst_id == Instruction.ENTER )   {   done = true; enter( ); }
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
    if( dinfo.inst_id == Instruction.CLC )     {   done = true; cf = 0; }
    if( dinfo.inst_id == Instruction.STC )     {   done = true; cf = 1; }
    if( dinfo.inst_id == Instruction.CMC )     {   done = true; cf ^= 1; }
    if( dinfo.inst_id == Instruction.LAHF )    {   done = true; lahf( ); }
    if( dinfo.inst_id == Instruction.SAHF )    {   done = true; sahf( ); }
    if( dinfo.inst_id == Instruction.XLAT )    {   done = true; xlat( ); }
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
    if( dinfo.inst_id == Instruction.CMOV )    {   done = true; cmov( ); }
    if( dinfo.inst_id == Instruction.BSWAP )   {   done = true; bswap( ); }
    if( dinfo.inst_id == Instruction.PUSHA )   {   done = true; pusha( ); }
    if( dinfo.inst_id == Instruction.POPA )    {   done = true; popa( ); }
    if( dinfo.inst_id == Instruction.DAA )     {   done = true; daa_das( true ); }
    if( dinfo.inst_id == Instruction.DAS )     {   done = true; daa_das( false ); }
    if( dinfo.inst_id == Instruction.AAA )     {   done = true; aaa_aas( true ); }
    if( dinfo.inst_id == Instruction.AAS )     {   done = true; aaa_aas( false ); }
    if( dinfo.inst_id == Instruction.AAM )     {   done = true; aam( ); }
    if( dinfo.inst_id == Instruction.AAD )     {   done = true; aad( ); }
    if( dinfo.inst_id == Instruction.LOOP )    {   done = true; loopx( Instruction.LOOP ); }
    if( dinfo.inst_id == Instruction.LOOPE )   {   done = true; loopx( Instruction.LOOPE ); }
    if( dinfo.inst_id == Instruction.LOOPNE )  {   done = true; loopx( Instruction.LOOPNE ); }
    if( dinfo.inst_id == Instruction.JECXZ )   {   done = true; jecxz( ); }
    if( dinfo.inst_id == Instruction.XADD )    {   done = true; xadd( ); }
    if( dinfo.inst_id == Instruction.CMPXCHG ) {   done = true; cmpxchg( ); }
    if( dinfo.inst_id == Instruction.CMPXCHG8B ){  done = true; cmpxchg8b( ); }
    if( dinfo.inst_id == Instruction.MOVSREG ) {   done = true; movsreg_store( ); }
    if( dinfo.inst_id == Instruction.MOVSREGLD ){  done = true; movsreg_load( ); }
    if( dinfo.inst_id == Instruction.PUSHSEG ) {   done = true; pushseg( ); }
    if( dinfo.inst_id == Instruction.POPSEG )  {   done = true; popseg( ); }
    if( dinfo.inst_id == Instruction.INT  )    {   done = true; interrupt( ); interrupt_done = true; }
    if( dinfo.inst_id == Instruction.CALL )    {   done = true; call( ); }
    if( dinfo.inst_id == Instruction.JMP )     {   done = true; jmp( ); }
    if( dinfo.inst_id == Instruction.RETN )    {   done = true; retn( ); }
    if( dinfo.inst_id == Instruction.NOP )     {   done = true; nop( ); }
    if( dinfo.inst_id == Instruction.WAIT )    {   done = true; }   // FWAIT: 例外未モデル→no-op
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
    if( dinfo.inst_id == Instruction.FILD )    {   done = true; fildw( 4 ); }
    if( dinfo.inst_id == Instruction.FILD16 )  {   done = true; fildw( 2 ); }
    if( dinfo.inst_id == Instruction.FILD64 )  {   done = true; fildw( 8 ); }
    if( dinfo.inst_id == Instruction.FISTP )   {   done = true; fistw( 4, true ); }
    if( dinfo.inst_id == Instruction.FISTP16 ) {   done = true; fistw( 2, true ); }
    if( dinfo.inst_id == Instruction.FISTP64 ) {   done = true; fistw( 8, true ); }
    if( dinfo.inst_id == Instruction.FIST )    {   done = true; fistw( 4, false ); }
    if( dinfo.inst_id == Instruction.FIST16 )  {   done = true; fistw( 2, false ); }
    if( dinfo.inst_id == Instruction.FCMOVB )  {   done = true; fcmov( Instruction.FCMOVB ); }
    if( dinfo.inst_id == Instruction.FCMOVE )  {   done = true; fcmov( Instruction.FCMOVE ); }
    if( dinfo.inst_id == Instruction.FCMOVBE ) {   done = true; fcmov( Instruction.FCMOVBE ); }
    if( dinfo.inst_id == Instruction.FCMOVU )  {   done = true; fcmov( Instruction.FCMOVU ); }
    if( dinfo.inst_id == Instruction.FCMOVNB ) {   done = true; fcmov( Instruction.FCMOVNB ); }
    if( dinfo.inst_id == Instruction.FCMOVNE ) {   done = true; fcmov( Instruction.FCMOVNE ); }
    if( dinfo.inst_id == Instruction.FCMOVNBE ){   done = true; fcmov( Instruction.FCMOVNBE ); }
    if( dinfo.inst_id == Instruction.FCMOVNU ) {   done = true; fcmov( Instruction.FCMOVNU ); }
    if( dinfo.inst_id == Instruction.FFREE )   {   done = true; ffree( ); }
    if( dinfo.inst_id == Instruction.FINCSTP ) {   done = true; fincstp( ); }
    if( dinfo.inst_id == Instruction.FDECSTP ) {   done = true; fdecstp( ); }
    if( dinfo.inst_id == Instruction.FNINIT )  {   done = true; fninit( ); }
    if( dinfo.inst_id == Instruction.INT3 )    {   done = true; raiseSig( Signal.SIGTRAP, "INT3 (breakpoint)" ); }
    if( dinfo.inst_id == Instruction.INTO )    {   done = true; into( ); }
    if( dinfo.inst_id == Instruction.CLI )     {   done = true; priv_gp( ); }
    if( dinfo.inst_id == Instruction.STI )     {   done = true; priv_gp( ); }
    if( dinfo.inst_id == Instruction.PRIVGP )  {   done = true; priv_gp( ); }
    if( dinfo.inst_id == Instruction.BOUND )   {   done = true; bound( ); }
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
    if( dinfo.inst_id == Instruction.FCOM )    {   done = true; fcom( 0, 0 ); }
    if( dinfo.inst_id == Instruction.FCOMP )   {   done = true; fcom( 1, 0 ); }
    if( dinfo.inst_id == Instruction.FCOMPP )  {   done = true; fcompp( ); }
    if( dinfo.inst_id == Instruction.FUCOM )   {   done = true; fcom( 0, 0 ); }
    if( dinfo.inst_id == Instruction.FUCOMP )  {   done = true; fcom( 1, 0 ); }
    if( dinfo.inst_id == Instruction.FUCOMPP ) {   done = true; fcompp( ); }
    if( dinfo.inst_id == Instruction.FTST )    {   done = true; ftst( ); }
    if( dinfo.inst_id == Instruction.FXAM )    {   done = true; fxam( ); }
    if( dinfo.inst_id == Instruction.FICOM )   {   done = true; fcom( 0, 4 ); }
    if( dinfo.inst_id == Instruction.FICOMP )  {   done = true; fcom( 1, 4 ); }
    if( dinfo.inst_id == Instruction.FICOM16 ) {   done = true; fcom( 0, 2 ); }
    if( dinfo.inst_id == Instruction.FICOMP16 ){   done = true; fcom( 1, 2 ); }
    if( dinfo.inst_id == Instruction.FCOMI )   {   done = true; fcomi( false ); }
    if( dinfo.inst_id == Instruction.FCOMIP )  {   done = true; fcomi( true ); }
    if( dinfo.inst_id == Instruction.FUCOMI )  {   done = true; fcomi( false ); }
    if( dinfo.inst_id == Instruction.FUCOMIP ) {   done = true; fcomi( true ); }
    if( dinfo.inst_id == Instruction.FNSTSW )  {   done = true; fnstsw( dinfo.src.kind == Operand.NONE ); }
    if( dinfo.inst_id == Instruction.FLDL2T )  {   done = true; fldl2t( ); }
    if( dinfo.inst_id == Instruction.FLDL2E )  {   done = true; fldl2e( ); }
    if( dinfo.inst_id == Instruction.FLDLG2 )  {   done = true; fldlg2( ); }
    if( dinfo.inst_id == Instruction.FLDLN2 )  {   done = true; fldln2( ); }
    if( dinfo.inst_id == Instruction.FRNDINT ) {   done = true; frndint( ); }
    if( dinfo.inst_id == Instruction.FSCALE )  {   done = true; fscale( ); }
    if( dinfo.inst_id == Instruction.FXTRACT ) {   done = true; fxtract( ); }
    if( dinfo.inst_id == Instruction.FPREM )   {   done = true; fprem( false ); }
    if( dinfo.inst_id == Instruction.FPREM1 )  {   done = true; fprem( true ); }
    if( dinfo.inst_id == Instruction.F2XM1 )   {   done = true; f2xm1( ); }
    if( dinfo.inst_id == Instruction.FYL2X )   {   done = true; fyl2x( false ); }
    if( dinfo.inst_id == Instruction.FYL2XP1 ) {   done = true; fyl2x( true ); }
    if( dinfo.inst_id == Instruction.FPTAN )   {   done = true; fptanx( ); }
    if( dinfo.inst_id == Instruction.FPATAN )  {   done = true; fpatan( ); }
    if( dinfo.inst_id == Instruction.FSIN )    {   done = true; fsinx( Instruction.FSIN ); }
    if( dinfo.inst_id == Instruction.FCOS )    {   done = true; fsinx( Instruction.FCOS ); }
    if( dinfo.inst_id == Instruction.FSINCOS ) {   done = true; fsinx( Instruction.FSINCOS ); }
    if( dinfo.inst_id == Instruction.FNCLEX )  {   done = true; fnclex( ); }
    if( dinfo.inst_id == Instruction.FNSTENV ) {   done = true; fnstenv( ); }
    if( dinfo.inst_id == Instruction.FLDENV )  {   done = true; fldenv( ); }
    if( dinfo.inst_id == Instruction.FNSAVE )  {   done = true; fnsave( ); }
    if( dinfo.inst_id == Instruction.FRSTOR )  {   done = true; frstor( ); }
    if( dinfo.inst_id == Instruction.FLD80 )   {   done = true; fld80( ); }
    if( dinfo.inst_id == Instruction.FSTP80 )  {   done = true; fstp80( ); }
    if( dinfo.inst_id == Instruction.FBLD )    {   done = true; fbld( ); }
    if( dinfo.inst_id == Instruction.FBSTP )   {   done = true; fbstp( ); }
    if( dinfo.inst_id == Instruction.FFREEP )  {   done = true; ffreep( ); }
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
  // INC: operand+1。CF は保存 (INC/DEC は CF 不変)。旧実装は AF 未計算・width マスク無しで
  //   AF/ZF が誤っていた。add() と同じ idiom で OF/SF/ZF/AF/PF を計算。
  void inc( ) {
    int size = calc_operand_size( );
    int s = (int)ref_expand( dinfo.src );
    long ssum = (long)s + 1L;
    int ival = (int)ssum;
    af = ((s ^ 1 ^ ival) >> 4) & 1;
    overflow_eval( ssum );
    if( size == 1 ) { ival &= 0xFF; }
    if( size == 2 ) { ival &= 0xFFFF; }
    flag_eval( ival );
    set( dinfo.src, ival );          // cf は触らない (保存)
  }

  // DEC命令: operand-1。CF 保存。
  void dec( ) {
    int size = calc_operand_size( );
    int s = (int)ref_expand( dinfo.src );
    long sdiff = (long)s - 1L;
    int ival = (int)sdiff;
    af = ((s ^ 1 ^ ival) >> 4) & 1;
    overflow_eval( sdiff );
    if( size == 1 ) { ival &= 0xFF; }
    if( size == 2 ) { ival &= 0xFFFF; }
    flag_eval( ival );
    set( dinfo.src, ival );          // cf は触らない (保存)
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
    // CWD (o16): AX の符号を DX へ (DX 下位 16bit のみ)。CDQ (o32): EAX の符号を EDX 全体へ。
    //   旧実装は常に 16bit CWD で CDQ を処理できていなかった。
    if( calc_operand_size( ) == 2 ) {
      int hi = ( 0 != (reg[AX] & 0x8000) ) ? 0xFFFF : 0;
      reg[DX] = (reg[DX] & ~0xFFFF) | hi;
    } else {
      reg[DX] = ( 0 != (reg[AX] & 0x80000000) ) ? 0xFFFFFFFF : 0;
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
    // INT n: 0x80=Linux syscall。3 (CD 03) は breakpoint → SIGTRAP。その他の vector は
    //   ユーザモードでは #GP → SIGSEGV (旧実装は全 vector を syscall として実行していた)。
    int n = dinfo.src.imm & 0xFF;
    if( n == 0x80 ) {
      reg[ AX ] = (int)syscall.call( reg[ AX ], reg[BX], reg[CX], reg[DX], reg[SI], reg[DI] );
    } else if( n == 3 ) {
      raiseSig( Signal.SIGTRAP, "INT 3 (breakpoint)" );
    } else {
      raiseSig( Signal.SIGSEGV, "INT " + n + " (#GP)" );
    }
  }

  // POP命令
  void pop( ) {  set( dinfo.src, pop32( )); }

  // ENTER imm16,imm8: フレーム確保 (最小: nesting level 0)。push EBP; EBP=ESP; ESP-=frame。
  void enter( ) {
    int frame = dinfo.src.imm & 0xFFFF;
    push32( reg[BP] );
    reg[BP] = reg[SP];
    reg[SP] = reg[SP] - frame;
  }

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
      if( S_LODS == select ) {
	// LODS は accumulator (AL/AX/EAX) へロード (旧実装は誤って [DI] に store し segfault)。
	if( size == 1 )      { reg[AX] = (reg[AX] & ~0xFF)   | (data & 0xFF);   }
	else if( size == 2 ) { reg[AX] = (reg[AX] & ~0xFFFF) | (data & 0xFFFF); }
	else                 { reg[AX] = data;                                 }
      } else {
	if( size == 1 ) {	mem.store8(  (long)reg[DI] & 0xFFFFFFFFL, (byte) data ); }
	if( size == 2 ) {	mem.store16( (long)reg[DI] & 0xFFFFFFFFL, (short)data ); }
	if( size == 4 ) {	mem.store32( (long)reg[DI] & 0xFFFFFFFFL,        data ); }
      }
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
    // REP MOVS/STOS/LODS は完走で ECX=0 (旧実装は ECX を書き戻さず残していた)。
    if( dinfo.repz_flag || dinfo.repnz_flag ) { reg[CX] = 0; }
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

      // CMPS/SCAS は left-right を CMP と同じ規則で全フラグ評価する。旧実装は SF/ZF/PF/AF を
      //   設定せず (ZF は rep break のみ)、CF も符号付き比較で誤っていた。left_val/right_val は
      //   size に符号拡張済み。
      int diff = left_val - right_val;
      af = ((left_val ^ right_val ^ diff) >> 4) & 1;         // bit4 借り
      overflow_eval( (long)left_val - (long)right_val );      // OF (符号付き)
      int masked = diff;
      if( size == 1 ) { masked &= 0xFF; }
      if( size == 2 ) { masked &= 0xFFFF; }
      flag_eval( masked );                                    // SF/ZF/PF
      equal = ( zf == 1 );
      // CF = 符号なし借り (size 幅の符号なし比較)。
      long lu, ru;
      if( size == 1 )      { lu = left_val & 0xFFL;       ru = right_val & 0xFFL;       }
      else if( size == 2 ) { lu = left_val & 0xFFFFL;     ru = right_val & 0xFFFFL;     }
      else                 { lu = left_val & 0xFFFFFFFFL; ru = right_val & 0xFFFFFFFFL; }
      cf = ( lu < ru ) ? 1 : 0;

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
      // ECX 減算・ZF 早期終了は rep 形のみ (非 rep 単発の CMPS/SCAS は ECX 不変)。
      if( dinfo.repz_flag || dinfo.repnz_flag ) {
	reg[CX] -= 1;
	if( dinfo.repnz_flag && equal  ) { zf = 1; break; }
	if( dinfo.repz_flag  && !equal ) { zf = 0; break; }
      }
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

  // CMOVcc: 条件成立時のみ src→dst (dinfo.c_val の cc を _condition で評価)。
  void cmov( ) {  if( _condition( ) ) { set( dinfo.dst, ref( dinfo.src ) ); }  }

  // BSWAP r32: reg のバイト順を反転 (register は src.reg_no)。
  void bswap( ) {  reg[ dinfo.src.reg_no ] = Integer.reverseBytes( reg[ dinfo.src.reg_no ] );  }

  // ---- BCD / LOOP / atomic RMW (issue #24 Phase 3 i386) ----------------------
  // 8bit 結果から SF/ZF/PF を設定 (BCD 系用。flag_eval は operand size 依存のため使えない)。
  private void szp8( int al ) {
    al &= 0xFF;
    zf = (al == 0) ? 1 : 0;
    sf = (al >> 7) & 1;
    pf = 1;
    for( int i = 0 ; i < 8 ; i++ ) { pf += (al >> i) & 1; }
    pf &= 1;
  }

  // DAA/DAS (SDM pseudocode 通り。第2比較は元の AL/CF を使う。第1段の桁上げ/借りを
  //   CF に OR、第2 IF に else CF=0 は無い=冒頭の CF=0 が既定)。
  void daa_das( boolean add ) {
    int al = reg[AX] & 0xFF;
    int oldAl = al, oldCf = cf;
    cf = 0;
    if( ((al & 0xF) > 9) || af == 1 ) {
      if( add ) { int sum = al + 6; cf = ( oldCf == 1 || sum > 0xFF ) ? 1 : 0; al = sum & 0xFF; }
      else      { cf = ( oldCf == 1 || al < 6 ) ? 1 : 0; al = (al - 6) & 0xFF; }
      af = 1;
    } else { af = 0; }
    if( (oldAl > 0x99) || oldCf == 1 ) {
      al = ( add ? (al + 0x60) : (al - 0x60) ) & 0xFF;
      cf = 1;
    }
    reg[AX] = (reg[AX] & ~0xFF) | al;
    szp8( al );
  }

  // AAA/AAS: AX±0x106 (AL の桁上げ/借りが AH へ伝播する 16bit 演算)、AL&=0xF。SZP は未定義。
  void aaa_aas( boolean add ) {
    int ax = reg[AX] & 0xFFFF;
    if( ((ax & 0xF) > 9) || af == 1 ) {
      ax = ( add ? (ax + 0x106) : (ax - 0x106) ) & 0xFFFF;
      af = 1; cf = 1;
    } else { af = 0; cf = 0; }
    ax = (ax & 0xFF00) | (ax & 0x000F);
    reg[AX] = (reg[AX] & ~0xFFFF) | ax;
  }

  // AAM imm8: AH=AL/imm, AL=AL%imm。SZP は新 AL から。
  void aam( ) {
    int imm = dinfo.src.imm & 0xFF;
    int al = reg[AX] & 0xFF;
    int q = al / imm, r = al % imm;
    reg[AX] = (reg[AX] & ~0xFFFF) | ((q & 0xFF) << 8) | (r & 0xFF);
    szp8( r );
  }
  // AAD imm8: AL=(AL+AH*imm)&0xFF, AH=0。SZP は新 AL から。
  void aad( ) {
    int imm = dinfo.src.imm & 0xFF;
    int ax = reg[AX] & 0xFFFF;
    int al = ((ax & 0xFF) + ((ax >> 8) & 0xFF) * imm) & 0xFF;
    reg[AX] = (reg[AX] & ~0xFFFF) | al;
    szp8( al );
  }

  // LOOP/LOOPE/LOOPNE: ECX を減算し、ECX!=0 (かつ ZF 条件) で rel8 分岐。フラグ不変。
  void loopx( int kind ) {
    reg[CX] = reg[CX] - 1;
    boolean br = ( reg[CX] != 0 );
    if( kind == Instruction.LOOPE )  { br = br && (zf == 1); }
    if( kind == Instruction.LOOPNE ) { br = br && (zf == 0); }
    if( br ) { next_ip = ref( dinfo.src ); }   // DISP → next_ip+disp
  }
  // JECXZ: ECX==0 で分岐 (ECX は変更しない)。
  void jecxz( ) {  if( reg[CX] == 0 ) { next_ip = ref( dinfo.src ); }  }

  // XADD: TEMP=SRC+DEST; SRC=DEST; DEST=TEMP (フラグは ADD と同じ)。dst 書込を最後に
  //   することで same-operand (xadd %eax,%eax) でも SDM 順序通り TEMP が残る。
  void xadd( ) {
    int size = calc_operand_size( );
    int d = (int)ref_expand( dinfo.dst );
    int s = (int)ref_expand( dinfo.src );
    long ssum = (long)d + (long)s;
    int ival = (int)ssum;
    af = ((d ^ s ^ ival) >> 4) & 1;
    overflow_eval( ssum );
    if( size == 1 ) { ival &= 0xFF; }
    if( size == 2 ) { ival &= 0xFFFF; }
    flag_eval( ival );
    long wm = (size == 1) ? 0xFFL : (size == 2) ? 0xFFFFL : 0xFFFFFFFFL;
    cf = ( ((long)d & wm) + ((long)s & wm) > wm ) ? 1 : 0;
    set( dinfo.src, (int)((long)d & wm) );   // ref_expand の符号拡張を width で落とす
    set( dinfo.dst, ival );
  }

  // CMPXCHG: フラグ=CMP(acc,dst)。等しければ dst=src (ZF=1)、さもなくば acc=dst (ZF=0)。
  void cmpxchg( ) {
    int size = calc_operand_size( );
    long wm = (size == 1) ? 0xFFL : (size == 2) ? 0xFFFFL : 0xFFFFFFFFL;
    int acc, d = (int)ref_expand( dinfo.dst );
    if( size == 1 )      { acc = (byte)reg[AX]; }
    else if( size == 2 ) { acc = (short)reg[AX]; }
    else                 { acc = reg[AX]; }
    // CMP(acc, d) 相当の全フラグ (sub と同じ idiom)
    int diff = acc - d;
    af = ((acc ^ d ^ diff) >> 4) & 1;
    overflow_eval( (long)acc - (long)d );
    int masked = diff;
    if( size == 1 ) { masked &= 0xFF; }
    if( size == 2 ) { masked &= 0xFFFF; }
    flag_eval( masked );
    cf = ( ((long)acc & wm) < ((long)d & wm) ) ? 1 : 0;
    if( zf == 1 ) { set( dinfo.dst, ref( dinfo.src ) ); }
    else          { reg[AX] = (int)(( (long)reg[AX] & ~wm ) | ((long)d & wm)); }
  }

  // LAHF: SF/ZF/AF/PF/CF (+bit1=1) を AH へ。SAHF: AH から下位フラグを復元 (OF/DF は不変)。
  void lahf( ) {
    int ah = (sf << 7) | (zf << 6) | (af << 4) | (pf << 2) | 0x02 | cf;
    reg[AX] = (reg[AX] & ~0xFF00) | (ah << 8);
  }
  void sahf( ) {
    int ah = (reg[AX] >> 8) & 0xFF;
    sf = (ah >> 7) & 1; zf = (ah >> 6) & 1; af = (ah >> 4) & 1; pf = (ah >> 2) & 1; cf = ah & 1;
  }
  // XLAT: AL = [EBX + zero-ext AL]。
  void xlat( ) {
    long adrs = (((long)reg[BX] & 0xFFFFFFFFL) + (reg[AX] & 0xFF)) & 0xFFFFFFFFL;
    reg[AX] = (reg[AX] & ~0xFF) | ((int)mem.load8( adrs ) & 0xFF);
  }

  // CMPXCHG8B m64: EDX:EAX と比較、等しければ m64=ECX:EBX (ZF=1)、さもなくば EDX:EAX=m64。
  void cmpxchg8b( ) {
    long mem = ref64( dinfo.src );
    long cmp = ((long)reg[DX] << 32) | ((long)reg[AX] & 0xFFFFFFFFL);
    if( mem == cmp ) {
      zf = 1;
      set64( dinfo.src, ((long)reg[CX] << 32) | ((long)reg[BX] & 0xFFFFFFFFL) );
    } else {
      zf = 0;
      reg[AX] = (int)mem;
      reg[DX] = (int)(mem >> 32);
    }
  }

  // セグメントセレクタ (issue #24 Phase 3 i386 segment)。flat model なのでアドレス計算には
  //   使わず、MOV Sreg / PUSH・POP Sreg の観測値としてのみ保持。index は ModRM reg field の
  //   Sreg 番号 (0=ES,1=CS,2=SS,3=DS,4=FS,5=GS)。初期値は Linux i386 の標準 layout。
  private final int[] seg_sel = { 0x2b, 0x23, 0x2b, 0x2b, 0, 0 };
  // MOV r/m32, Sreg (8C /r register 形): 16bit selector を zero-extend で格納。
  void movsreg_store( ) {  set( dinfo.src, seg_sel[ (dinfo.c_val >> 3) & 7 ] );  }
  // MOV Sreg, r/m32 (8E /r register 形): 下位 16bit を selector へ。
  void movsreg_load( )  {  seg_sel[ (dinfo.c_val >> 3) & 7 ] = ref( dinfo.src ) & 0xFFFF;  }
  // PUSH Sreg (06/0E/16/1E): c0=0x18 で opcode から Sreg 番号を抽出。
  void pushseg( ) {  push32( seg_sel[ (dinfo.c_val >> 3) & 7 ] );  }
  // POP Sreg (07/17/1F)。
  void popseg( )  {  seg_sel[ (dinfo.c_val >> 3) & 7 ] = pop32( ) & 0xFFFF;  }

  // PUSHA: EAX,ECX,EDX,EBX,ESP(元),EBP,ESI,EDI の順に push。
  void pusha( ) {
    int esp = reg[SP];
    push32( reg[AX] ); push32( reg[CX] ); push32( reg[DX] ); push32( reg[BX] );
    push32( esp );     push32( reg[BP] ); push32( reg[SI] ); push32( reg[DI] );
  }
  // POPA: EDI,ESI,EBP,(ESP スロットは捨てる),EBX,EDX,ECX,EAX の順に pop。
  void popa( ) {
    reg[DI] = pop32( ); reg[SI] = pop32( ); reg[BP] = pop32( );
    pop32( );                                  // ESP スロットは読み捨て
    reg[BX] = pop32( ); reg[DX] = pop32( ); reg[CX] = pop32( ); reg[AX] = pop32( );
  }

  // STD命令
  void std( ) {  df = 1; }

  // CLD命令
  void cld( ) {  df = 0; }

  // DIV命令
  void div( boolean sign_flag ) {
    // DIV/IDIV: dividend = {AX / DX:AX / EDX:EAX}、divisor = r/m。商→{AL/AX/EAX}、
    //   余→{AH/DX/EDX}。旧実装は dividend を reg[AX] のみ (上位語 DX/EDX 無視)、8bit の
    //   AH 未格納、idiv の符号拡張なしで誤っていた。0 除算・商溢れは #DE → SIGFPE
    //   (旧実装は Java ArithmeticException で emulator ごと死んでいた)。
    int size = calc_operand_size( );
    if( size == 1 ) {
      long dividend = sign_flag ? (int)(short)(reg[AX] & 0xFFFF) : (reg[AX] & 0xFFFFL);
      long divisor  = sign_flag ? (int)(byte)(ref( dinfo.src ) & 0xFF) : (ref( dinfo.src ) & 0xFFL);
      if( divisor == 0 ) { raiseSig( Signal.SIGFPE, "divide by zero (#DE)" ); }
      long q = dividend / divisor, r = dividend % divisor;
      if( sign_flag ? (q < -128 || q > 127) : (q > 0xFF) ) { raiseSig( Signal.SIGFPE, "divide overflow (#DE)" ); }
      reg[AX] = (int)((reg[AX] & ~0xFFFF) | ((r & 0xFF) << 8) | (q & 0xFF));   // AH:AL
    } else if( size == 2 ) {
      long lo = reg[AX] & 0xFFFFL, hi = reg[DX] & 0xFFFFL;
      long dd = (hi << 16) | lo;                                    // DX:AX (32bit)
      long dividend = sign_flag ? (int)dd : dd;
      long divisor  = sign_flag ? (int)(short)(ref( dinfo.src ) & 0xFFFF) : (ref( dinfo.src ) & 0xFFFFL);
      if( divisor == 0 ) { raiseSig( Signal.SIGFPE, "divide by zero (#DE)" ); }
      long q = dividend / divisor, r = dividend % divisor;
      if( sign_flag ? (q < -32768 || q > 32767) : (q > 0xFFFF) ) { raiseSig( Signal.SIGFPE, "divide overflow (#DE)" ); }
      reg[AX] = (int)((reg[AX] & ~0xFFFF) | (q & 0xFFFF));
      reg[DX] = (int)((reg[DX] & ~0xFFFF) | (r & 0xFFFF));
    } else {
      long dd = ((long)reg[DX] << 32) | ((long)reg[AX] & 0xFFFFFFFFL);  // EDX:EAX (64bit)
      if( sign_flag ) {
        long divisor = (long)ref( dinfo.src );                     // int → 符号拡張
        if( divisor == 0 ) { raiseSig( Signal.SIGFPE, "divide by zero (#DE)" ); }
        long q = dd / divisor;
        if( q < Integer.MIN_VALUE || q > Integer.MAX_VALUE ) { raiseSig( Signal.SIGFPE, "divide overflow (#DE)" ); }
        reg[AX] = (int)q;
        reg[DX] = (int)(dd % divisor);
      } else {
        long divisor = ((long)ref( dinfo.src )) & 0xFFFFFFFFL;
        if( divisor == 0 ) { raiseSig( Signal.SIGFPE, "divide by zero (#DE)" ); }
        long q = Long.divideUnsigned( dd, divisor );
        if( Long.compareUnsigned( q, 0xFFFFFFFFL ) > 0 ) { raiseSig( Signal.SIGFPE, "divide overflow (#DE)" ); }
        reg[AX] = (int)q;
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
  // x87 環境系 (issue #753) の状態: tag 導出用の空レジスタ bitmap (bit=1 で empty、
  // push/pop/FFREE/FNINIT/FLDENV で更新) と SW 例外 bit 記憶 (bits 0-7 + B(15)。
  // FLDENV/FRSTOR で載り FNCLEX/FNINIT で消える。演算による蓄積は未対応)。
  private int fpu_empty = 0xFF;
  private int fpu_sw_exc = 0;
  private void   fpuPush( double v ) { fpu_top = (fpu_top - 1) & 7; fpu_st[fpu_top] = v; fpu_empty &= ~(1 << fpu_top); }
  private double fpuPop( )           { double v = fpu_st[fpu_top]; fpu_empty |= (1 << fpu_top); fpu_top = (fpu_top + 1) & 7; return v; }
  private double fpuSt( int i )      { return fpu_st[(fpu_top + i) & 7]; }
  private void   fpuSetSt( int i, double v ) { fpu_st[(fpu_top + i) & 7] = v; }
  // メモリオペランドを double 値として読む (m32=float / m64=double)。
  private double fpuMemRead( ) {
    return (calc_operand_size( ) == 4)
        ? (double)Float.intBitsToFloat( (int)ref( dinfo.src ) )
        : Double.longBitsToDouble( ref64( dinfo.src ) );
  }

  void fld( ) {
    if( dinfo.src.kind == Operand.NONE ) fpuPush( fpuSt( dinfo.c_val ) );  // FLD st(i)
    else                                 fpuPush( fpuMemRead( ) );          // FLD m32/m64
  }

  // FST / FSTP 命令: st(0) を m32/m64 または st(i) へ格納 (FSTP は最後に pop)。
  void fst( int inst_id ) {
    double v = fpuSt( 0 );
    if( dinfo.src.kind == Operand.NONE ) {             // FST/FSTP st(i)
      fpuSetSt( dinfo.c_val, v );
    } else if( calc_operand_size( ) == 4 ) {           // m32
      set( dinfo.src, Float.floatToRawIntBits( (float)v ) );
    } else {                                           // m64
      set64( dinfo.src, Double.doubleToRawLongBits( v ) );
    }
    if( inst_id == Instruction.FSTP ) fpuPop( );
  }

  // FADD/FSUB/FSUBR/FMUL/FDIV/FDIVR (memory + register 形統一)。
  //   form は c0=0x06 で判別: register 形 (src.kind==REG, r1=0x7 で st index) は
  //   c_val=0(D8: dest=st0, src=st(i)) / 4(DC: dest=st(i), src=st0) / 6(DE: DC+pop)。
  //   memory 形は c_val=2(DA m32int) / 6(DE m16int) / それ以外 float (size は D flag)。
  //   統一セマンティクス: FSUB=dest-src / FSUBR=src-dest / FDIV=dest/src / FDIVR=src/dest
  //   (SDM の DC/DE opcode 反転は Decoder のエントリ対応で吸収済み)。
  void farith( int op ) {
    double dest, s;
    int sti = 0;
    boolean toSti = false, pop = false;
    if( dinfo.src.kind == Operand.REG ) {              // register 形
      sti = dinfo.src.reg_no;
      toSti = ( dinfo.c_val == 4 || dinfo.c_val == 6 );
      pop   = ( dinfo.c_val == 6 );
      dest = toSti ? fpuSt( sti ) : fpuSt( 0 );
      s    = toSti ? fpuSt( 0 )   : fpuSt( sti );
    } else {                                           // memory 形
      if( dinfo.c_val == 2 )      { s = (double)ref( dinfo.src ); }          // m32int
      else if( dinfo.c_val == 6 ) { s = (double)(short)ref( dinfo.src ); }   // m16int
      else                        { s = fpuMemRead( ); }                     // m32/m64 float
      dest = fpuSt( 0 );
    }
    double r;
    if      ( op == Instruction.FADD )  r = dest + s;
    else if ( op == Instruction.FSUB )  r = dest - s;
    else if ( op == Instruction.FSUBR ) r = s - dest;
    else if ( op == Instruction.FMUL )  r = dest * s;
    else if ( op == Instruction.FDIV )  r = dest / s;
    else                                r = s / dest;   // FDIVR
    fpuSetSt( toSti ? sti : 0, r );
    if( pop ) fpuPop( );
  }

  void fsqrt( ) { fpuSetSt( 0, Math.sqrt( fpuSt( 0 ) ) ); }
  void fabsx( ) { fpuSetSt( 0, Math.abs( fpuSt( 0 ) ) ); }
  void fld1( )  { fpuPush( 1.0 ); }
  void fldz( )  { fpuPush( 0.0 ); }
  void fldpi( ) { fpuPush( Math.PI ); }

  // FILD 命令: m16/m32/m64 int → double を push (width は inst_id 別)。
  void fildw( int w ) {
    if( w == 2 )      { fpuPush( (double)(short)ref( dinfo.src ) ); }
    else if( w == 8 ) { fpuPush( (double)ref64( dinfo.src ) ); }
    else              { fpuPush( (double)ref( dinfo.src ) ); }
  }

  // FIST/FISTP: st(0) を RN 丸めで整数格納 (FISTP は pop)。範囲外/NaN は indefinite。
  void fistw( int w, boolean pop ) {
    double r = Math.rint( fpuSt( 0 ) );
    if( w == 2 ) {
      int out = ( Double.isNaN( r ) || r < -32768.0 || r > 32767.0 ) ? 0x8000 : ((int)r & 0xFFFF);
      _set( dinfo.src, out, 2 );
    } else if( w == 8 ) {
      long out = ( Double.isNaN( r ) || r < -9.223372036854776E18 || r >= 9.223372036854776E18 )
                 ? 0x8000000000000000L : (long)r;
      set64( dinfo.src, out );
    } else {
      int out = ( Double.isNaN( r ) || r < -2147483648.0 || r > 2147483647.0 ) ? 0x80000000 : (int)r;
      _set( dinfo.src, out, 4 );
    }
    if( pop ) fpuPop( );
  }

  // FCMOVcc: 条件成立で st0 = st(i) (CF/ZF/PF から評価)。
  void fcmov( int kind ) {
    boolean c;
    if      ( kind == Instruction.FCMOVB )   c = ( cf == 1 );
    else if ( kind == Instruction.FCMOVE )   c = ( zf == 1 );
    else if ( kind == Instruction.FCMOVBE )  c = ( cf == 1 || zf == 1 );
    else if ( kind == Instruction.FCMOVU )   c = ( pf == 1 );
    else if ( kind == Instruction.FCMOVNB )  c = ( cf == 0 );
    else if ( kind == Instruction.FCMOVNE )  c = ( zf == 0 );
    else if ( kind == Instruction.FCMOVNBE ) c = ( cf == 0 && zf == 0 );
    else                                     c = ( pf == 0 );   // FCMOVNU
    if( c ) { fpuSetSt( 0, fpuSt( dinfo.src.reg_no ) ); }
  }

  // FFREE st(i): 空レジスタ読出しの観測値 (real indefinite) を書き、tag を empty に。
  void ffree( ) {
    fpuSetSt( dinfo.src.reg_no, Double.longBitsToDouble( 0xFFF8000000000000L ) );
    fpu_empty |= 1 << ((fpu_top + dinfo.src.reg_no) & 7);
  }
  void fincstp( ) { fpu_top = (fpu_top + 1) & 7; }
  void fdecstp( ) { fpu_top = (fpu_top - 1) & 7; }
  // FNINIT: TOP=0・condition code クリア・CW=0x037F・全 tag empty・例外 bit クリア。
  void fninit( ) {
    fpu_top = 0; fpu_c0 = 0; fpu_c1 = 0; fpu_c2 = 0; fpu_c3 = 0; fpu_cw = 0x037F;
    fpu_empty = 0xFF; fpu_sw_exc = 0;
  }

  // FCHS 命令
  void fchs( ) { fpuSetSt( 0, -fpuSt( 0 ) ); }

  // FXCH 命令: st(0) と st(i) を入替 (entry 142 は r1=0x7 で src.reg_no=i)。
  void fxch( ) {
    int i = ( dinfo.src.kind == Operand.REG ) ? dinfo.src.reg_no : 1;
    double t = fpuSt( 0 ); fpuSetSt( 0, fpuSt( i ) ); fpuSetSt( i, t );
  }

  // ---- x87 拡張 (issue #749: i386-2 x87-ext) --------------------------------
  // 定数ロード残り 4 種: 80bit 定数の double への 1 回丸め値を bit 直書き (Cpu64 と同値)。
  void fldl2t( ) { fpuPush( Double.longBitsToDouble( 0x400A934F0979A371L ) ); }   // log2(10)
  void fldl2e( ) { fpuPush( Double.longBitsToDouble( 0x3FF71547652B82FEL ) ); }   // log2(e)
  void fldlg2( ) { fpuPush( Double.longBitsToDouble( 0x3FD34413509F79FFL ) ); }   // log10(2)
  void fldln2( ) { fpuPush( Double.longBitsToDouble( 0x3FE62E42FEFA39EFL ) ); }   // ln(2)

  // FRNDINT: CW の RC (bit11:10) に従い st0 を整数化。NaN/Inf は不変、±0 の符号保存。
  void frndint( ) {
    double v = fpuSt( 0 );
    if( Double.isNaN( v ) || Double.isInfinite( v ) ) return;
    int rc = (fpu_cw >> 10) & 3;
    double r;
    if      ( rc == 0 ) r = Math.rint( v );                                // RN (half-to-even)
    else if ( rc == 1 ) r = Math.floor( v );                               // RD
    else if ( rc == 2 ) r = Math.ceil( v );                                // RU
    else                r = ( v < 0 ) ? Math.ceil( v ) : Math.floor( v );  // RZ (chop)
    if( r == 0.0 ) r = Math.copySign( 0.0, v );
    fpuSetSt( 0, r );
  }

  // x87 2 オペランド NaN 伝播: 仮数の大きい方を quiet 化して返す (SDM Table 4-7)。
  private double x87NanProp( double a, double b ) {
    long ab = Double.doubleToRawLongBits( a ), bb = Double.doubleToRawLongBits( b );
    long w;
    if( Double.isNaN( a ) && Double.isNaN( b ) )
      w = ((ab & 0xFFFFFFFFFFFFFL) >= (bb & 0xFFFFFFFFFFFFFL)) ? ab : bb;
    else
      w = Double.isNaN( a ) ? ab : bb;
    return Double.longBitsToDouble( w | 0x8000000000000L );
  }

  // FSCALE: st0 = st0 * 2^trunc(st1)、st1 不変 (特殊値は SDM Table 8-9)。
  void fscale( ) {
    double a = fpuSt( 0 ), b = fpuSt( 1 );
    double r;
    if( Double.isNaN( a ) || Double.isNaN( b ) ) r = x87NanProp( a, b );
    else if( Double.isInfinite( a ) )
      r = ( b == Double.NEGATIVE_INFINITY ) ? Double.longBitsToDouble( 0xFFF8000000000000L ) : a;
    else if( a == 0.0 )
      r = ( b == Double.POSITIVE_INFINITY ) ? Double.longBitsToDouble( 0xFFF8000000000000L ) : a;
    else if( b == Double.POSITIVE_INFINITY ) r = Math.copySign( Double.POSITIVE_INFINITY, a );
    else if( b == Double.NEGATIVE_INFINITY ) r = Math.copySign( 0.0, a );
    else {
      long n = (long)b;                       // trunc ((long) キャストは範囲外を飽和)
      if( n > 5000 ) n = 5000; else if( n < -5000 ) n = -5000;   // scalb の int 範囲へ
      r = Math.scalb( a, (int)n );
    }
    fpuSetSt( 0, r );
  }

  // 正規化済み指数 (frexp 相当 - 1)。subnormal は 2^60 倍で正規化して真指数。0 は対象外。
  private int x87Exponent( double v ) {
    int e = Math.getExponent( v );
    if( e == Double.MIN_EXPONENT - 1 ) e = Math.getExponent( v * 0x1p60 ) - 60;
    return e;
  }

  // FXTRACT: st0 を分解して ST1=指数 (double)・ST0=仮数 (1.m×2^0)。
  //   st0=±0 は #Z (masked): ST1=-inf・ST0=±0。±inf は ST1=+inf・ST0=±inf。NaN は両方 NaN。
  void fxtract( ) {
    double v = fpuSt( 0 );
    if( Double.isNaN( v ) )      { fpuPush( v ); return; }
    if( Double.isInfinite( v ) ) { fpuSetSt( 0, Double.POSITIVE_INFINITY ); fpuPush( v ); return; }
    if( v == 0.0 )               { fpuSetSt( 0, Double.NEGATIVE_INFINITY ); fpuPush( v ); return; }
    int e = x87Exponent( v );
    fpuSetSt( 0, (double)e );
    fpuPush( Math.scalb( v, -e ) );
  }

  // ---- x87 環境/状態 save-restore (issue #753: i386-2 x87-env) --------------
  // 32-bit protected mode layout: env 28 bytes (+0 CW/+4 SW/+8 TW/+12 FIP/
  // +16 FCS/+20 FDP/+24 FDS)、FNSAVE=env + ST0..ST7 の 80-bit ×8 (ST 順)。
  // FIP 系はトラップハンドラ向け情報のため 0 を書く (未追跡)。

  // FNCLEX: SW の例外 bit (0-7) + B(15) をクリア (C bits/TOP は保存)。
  void fnclex( ) { fpu_sw_exc = 0; }

  // phys reg i の tag (00=valid/01=zero/10=special/11=empty) を導出。
  private int fpuTagOf( int i ) {
    if( (fpu_empty & (1 << i)) != 0 ) return 3;
    double v = fpu_st[i];
    if( v == 0.0 ) return 1;
    if( Double.isNaN( v ) || Double.isInfinite( v ) || Math.abs( v ) < Double.MIN_NORMAL ) return 2;
    return 0;
  }

  private void fpuStoreEnv( long a ) {
    int tw = 0;
    for( int i = 0; i < 8; i++ ) tw |= fpuTagOf( i ) << (2 * i);
    mem.store32( a,      fpu_cw & 0xFFFF );
    mem.store32( a + 4,  fpuStatusWord( ) & 0xFFFF );
    mem.store32( a + 8,  tw );
    mem.store32( a + 12, 0 );   // FIP
    mem.store32( a + 16, 0 );   // FCS + opcode
    mem.store32( a + 20, 0 );   // FDP
    mem.store32( a + 24, 0 );   // FDS
  }

  private void fpuLoadEnv( long a ) {
    fpu_cw = mem.load32( a ) & 0xFFFF;
    int sw = mem.load32( a + 4 ) & 0xFFFF;
    fpu_top = (sw >> 11) & 7;
    fpu_c0 = (sw >> 8) & 1; fpu_c1 = (sw >> 9) & 1;
    fpu_c2 = (sw >> 10) & 1; fpu_c3 = (sw >> 14) & 1;
    fpu_sw_exc = sw & 0x80FF;
    int tw = mem.load32( a + 8 ) & 0xFFFF;
    fpu_empty = 0;
    for( int i = 0; i < 8; i++ ) if( ((tw >> (2 * i)) & 3) == 3 ) fpu_empty |= 1 << i;
  }

  // FNSTENV: env store 後に全例外をマスク (CW|=0x3F)。FLDENV: env ロード。
  void fnstenv( ) { fpuStoreEnv( ea( dinfo.src ) & 0xFFFFFFFFL ); fpu_cw |= 0x3F; }
  void fldenv( )  { fpuLoadEnv( ea( dinfo.src ) & 0xFFFFFFFFL ); }

  // double -> 80-bit ext (正確変換)。ret[0]=sig64、ret[1]=sign|bexp。
  private long[] dblToExt( double v ) {
    long b = Double.doubleToRawLongBits( v );
    long sign = (b >>> 63) << 15;
    int e = (int)((b >>> 52) & 0x7FF);
    long frac = b & 0xFFFFFFFFFFFFFL;
    long sig, bexp;
    if( e == 0 ) {
      if( frac == 0 ) { sig = 0; bexp = 0; }                       // ±0
      else {                                                       // denormal -> 正規化
        int nlz = Long.numberOfLeadingZeros( frac );
        sig = frac << nlz;
        bexp = 15372 - nlz;   // = (-1074 + (63-nlz)) + 16383
      }
    } else if( e == 0x7FF ) { sig = 0x8000000000000000L | (frac << 11); bexp = 0x7FFF; }
    else                    { sig = 0x8000000000000000L | (frac << 11); bexp = (e - 1023) + 16383; }
    return new long[]{ sig, sign | bexp };
  }

  // 80-bit ext -> double (RN 丸め。範囲外は ±inf / denormal 域は scalb に委譲)。
  private double extToDbl( long sig, int se ) {
    int sign = (se >> 15) & 1;
    int bexp = se & 0x7FFF;
    double s = (sign == 1) ? -1.0 : 1.0;
    if( bexp == 0 && sig == 0 ) return s * 0.0;
    if( bexp == 0x7FFF ) {
      long frac = (sig & 0x7FFFFFFFFFFFFFFFL) >>> 11;
      return Double.longBitsToDouble( ((long)sign << 63) | 0x7FF0000000000000L | frac );
    }
    int e = bexp - 16383;
    if( (sig & 0x8000000000000000L) == 0 )                        // 非正規化 sig (pseudo 系)
      return s * Math.scalb( (double)sig, e - 63 );
    long keep = sig >>> 11;                                       // 53bit (整数 bit 込み)
    long rem = sig & 0x7FF;
    if( rem > 0x400 || (rem == 0x400 && (keep & 1) == 1) ) {
      keep++;
      if( (keep >>> 53) != 0 ) { keep >>>= 1; e++; }
    }
    int de = e + 1023;
    if( de >= 0x7FF ) return s * Double.POSITIVE_INFINITY;
    if( de <= 0 )     return s * Math.scalb( (double)keep, e - 52 );   // denormal 域
    return Double.longBitsToDouble( ((long)sign << 63) | ((long)de << 52) | (keep & 0xFFFFFFFFFFFFFL) );
  }

  // FNSAVE: env + ST0..ST7 (80-bit, ST 順) を store し FNINIT。FRSTOR: 逆。
  void fnsave( ) {
    long a = ea( dinfo.src ) & 0xFFFFFFFFL;
    fpuStoreEnv( a );
    for( int i = 0; i < 8; i++ ) {
      long[] x = dblToExt( fpuSt( i ) );
      mem.store64( a + 28 + 10 * i, x[0] );
      mem.store16( a + 36 + 10 * i, (short)x[1] );
    }
    fninit( );
  }
  void frstor( ) {
    long a = ea( dinfo.src ) & 0xFFFFFFFFL;
    fpuLoadEnv( a );
    for( int i = 0; i < 8; i++ ) {
      long sig = mem.load64( a + 28 + 10 * i );
      int se = (int)mem.load16( a + 36 + 10 * i ) & 0xFFFF;
      fpu_st[(fpu_top + i) & 7] = extToDbl( sig, se );
    }
  }

  // ---- x87 80-bit フォーム/BCD (issue #755: i386-2 x87-m80) -----------------

  // FLD m80: ext -> double (RN 丸め、extToDbl)。SNaN は load 時に quiet 化
  // (レジスタ内 SNaN 非保持の既存モデルと整合。m64 観測では実 CPU の
  //  「FSTP m64 変換時 quiet 化」と同じ値になる)。
  void fld80( ) {
    long a = ea( dinfo.src ) & 0xFFFFFFFFL;
    double v = extToDbl( mem.load64( a ), (int)mem.load16( a + 8 ) & 0xFFFF );
    long b = Double.doubleToRawLongBits( v );
    if( Double.isNaN( v ) ) v = Double.longBitsToDouble( b | 0x8000000000000L );
    fpuPush( v );
  }

  // FSTP m80: double -> ext (dblToExt、正確変換) + pop。
  void fstp80( ) {
    long a = ea( dinfo.src ) & 0xFFFFFFFFL;
    long[] x = dblToExt( fpuSt( 0 ) );
    mem.store64( a, x[0] );
    mem.store16( a + 8, (short)x[1] );
    fpuPop( );
  }

  // FBLD: packed BCD 18 桁 + sign -> push (整数値は long で正確、double 化は RN)。
  void fbld( ) {
    long a = ea( dinfo.src ) & 0xFFFFFFFFL;
    long val = 0;
    for( int i = 8; i >= 0; i-- ) {
      int by = (int)mem.load8( a + i ) & 0xFF;
      val = val * 100 + ((by >> 4) & 0xF) * 10 + (by & 0xF);
    }
    boolean neg = ((int)mem.load8( a + 9 ) & 0x80) != 0;
    double v = (double)val;
    if( neg ) v = -v;                       // val=0 でも -0.0 (符号保存)
    fpuPush( v );
  }

  // FBSTP: RN で整数化し packed BCD store + pop。範囲外/NaN は BCD indefinite
  // (FFFFC000000000000000h)。-0 は符号保存。RC は未反映 (RN 固定)。
  void fbstp( ) {
    long a = ea( dinfo.src ) & 0xFFFFFFFFL;
    double v = fpuSt( 0 );
    double r = Math.rint( v );
    // 範囲判定は >= 1e18 (18 桁上限 999999999999999999 は double 非表現で、
    // リテラルに書くと 1e18 へ丸まり境界を取りこぼす)。
    if( Double.isNaN( r ) || Math.abs( r ) >= 1.0e18 ) {
      mem.store64( a, 0xC000000000000000L );
      mem.store16( a + 8, (short)0xFFFF );
    } else {
      long iv = (long)Math.abs( r );
      for( int i = 0; i < 9; i++ ) {
        int lo = (int)(iv % 10); iv /= 10;
        int hi = (int)(iv % 10); iv /= 10;
        mem.store8( a + i, (lo | (hi << 4)) & 0xFF );
      }
      boolean neg = (Double.doubleToRawLongBits( v ) >>> 63) != 0;
      mem.store8( a + 9, neg ? 0x80 : 0x00 );
    }
    fpuPop( );
  }

  // FFREEP st(i) (DF C0+i、gcc/glibc の i387 後始末が出す実命令): FFREE + pop。
  void ffreep( ) {
    fpuSetSt( dinfo.src.reg_no, Double.longBitsToDouble( 0xFFF8000000000000L ) );
    fpu_empty |= 1 << ((fpu_top + dinfo.src.reg_no) & 7);
    fpuPop( );
  }

  // ---- x87 超越関数 (issue #751: i386-2 x87-trans) --------------------------
  // double スタブ (80bit 精度なし)。特殊値 (±0 符号・恒等・indefinite・C2) は
  // SDM + 実 CPU 実測に合わせ、数値は Java Math (expm1/log1p 必須: pow/log の
  // 素朴式は小引数で桁落ち)。
  private static final double X87_LN2 = 0.6931471805599453;                       // ln(2)
  private static final double X87_INDEF = Double.longBitsToDouble( 0xFFF8000000000000L );

  // FSIN/FCOS/FPTAN/FSINCOS 共通: |st0| >= 2^63 は縮約せず C2=1・st0 不変。
  private boolean x87TransTooBig( double v ) {
    if( !Double.isNaN( v ) && Math.abs( v ) >= 0x1p63 && !Double.isInfinite( v ) ) {
      fpu_c2 = 1; return true;
    }
    fpu_c2 = 0; return false;
  }

  // F2XM1: st0 = 2^st0 - 1 (定義域 -1..+1)。±1 は正確値、±0 は符号保存。
  void f2xm1( ) {
    double v = fpuSt( 0 );
    if( Double.isNaN( v ) )  { fpuSetSt( 0, x87NanProp( v, v ) ); return; }
    if( v == 1.0 )           { fpuSetSt( 0, 1.0 ); return; }
    if( v == -1.0 )          { fpuSetSt( 0, -0.5 ); return; }
    fpuSetSt( 0, Math.expm1( v * X87_LN2 ) );
  }

  // FYL2X (p1=false): st1 = st1 * log2(st0), pop。
  // FYL2XP1 (p1=true): st1 = st1 * log2(1+st0), pop (|st0| < 1-sqrt(2)/2 が保証域)。
  void fyl2x( boolean p1 ) {
    double x = fpuSt( 0 ), y = fpuSt( 1 );
    double r;
    if( Double.isNaN( x ) || Double.isNaN( y ) ) r = x87NanProp( x, y );
    else if( p1 ) {
      r = y * ( Math.log1p( x ) / X87_LN2 );
      if( Double.isNaN( r ) ) r = X87_INDEF;      // x<-1 (log1p NaN) / 0*inf
    }
    else if( x < 0.0 ) r = X87_INDEF;             // -inf 含む (#IA)
    else if( x == 0.0 ) {
      if( y == 0.0 ) r = X87_INDEF;                                        // #IA
      else r = ( y > 0.0 ) ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;   // #Z
    }
    else if( x == 1.0 ) {
      if( Double.isInfinite( y ) ) r = X87_INDEF;                          // 0*inf
      else r = Math.copySign( 0.0, y );
    }
    else if( x == Double.POSITIVE_INFINITY ) {
      if( y == 0.0 ) r = X87_INDEF;                                        // inf*0
      else r = ( y > 0.0 ) ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
    }
    else if( Double.isInfinite( y ) ) {
      r = ( (x > 1.0) == (y > 0.0) ) ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
    }
    else {
      int e = Math.getExponent( x );
      if( x == Math.scalb( 1.0, e ) ) r = y * (double)e;                   // 2 の冪は正確
      else r = y * ( Math.log( x ) / X87_LN2 );
    }
    fpuPop( );
    fpuSetSt( 0, r );
  }

  // FPATAN: st1 = atan2(st1, st0), pop。IEEE 特殊値表は Math.atan2 が包含。
  void fpatan( ) {
    double x = fpuSt( 0 ), y = fpuSt( 1 );
    double r = ( Double.isNaN( x ) || Double.isNaN( y ) ) ? x87NanProp( x, y )
                                                          : Math.atan2( y, x );
    fpuPop( );
    fpuSetSt( 0, r );
  }

  // FPTAN: st0 = tan(st0)、1.0 を push。NaN/#IA 時は push 値も NaN/indefinite と
  // 同値 (実 CPU 実測)。|st0|>=2^63 は C2=1・push 無し。
  void fptanx( ) {
    double v = fpuSt( 0 );
    if( x87TransTooBig( v ) ) return;
    if( Double.isNaN( v ) )      { double q = x87NanProp( v, v ); fpuSetSt( 0, q ); fpuPush( q ); return; }
    if( Double.isInfinite( v ) ) { fpuSetSt( 0, X87_INDEF ); fpuPush( X87_INDEF ); return; }
    fpuSetSt( 0, Math.tan( v ) );
    fpuPush( 1.0 );
  }

  // FSIN / FCOS / FSINCOS (FSINCOS は sin を st0 に置いて cos を push)。
  void fsinx( int kind ) {
    double v = fpuSt( 0 );
    if( x87TransTooBig( v ) ) return;
    if( Double.isNaN( v ) || Double.isInfinite( v ) ) {
      double q = Double.isNaN( v ) ? x87NanProp( v, v ) : X87_INDEF;
      fpuSetSt( 0, q );
      if( kind == Instruction.FSINCOS ) fpuPush( q );
      return;
    }
    if( kind == Instruction.FSIN )      { fpuSetSt( 0, Math.sin( v ) ); }
    else if( kind == Instruction.FCOS ) { fpuSetSt( 0, Math.cos( v ) ); }
    else { fpuSetSt( 0, Math.sin( v ) ); fpuPush( Math.cos( v ) ); }
  }

  // FPREM (ieee=false) / FPREM1 (ieee=true): st0 = st0 の st1 による部分剰余。
  //   指数差 <64 は完全 reduction: C2=0 + quotient bits (C0=Q2/C3=Q1/C1=Q0)。
  //   >=64 は N=63 相当で縮約し C2=1 (SDM: N は実装依存 32..63、縮約後の値も実装依存)。
  void fprem( boolean ieee ) {
    double a = fpuSt( 0 ), b = fpuSt( 1 );
    if( Double.isNaN( a ) || Double.isNaN( b ) ) {
      fpuSetSt( 0, x87NanProp( a, b ) ); fpu_c2 = 0; return;
    }
    if( Double.isInfinite( a ) || b == 0.0 ) {    // #IA (masked) -> real indefinite
      fpuSetSt( 0, Double.longBitsToDouble( 0xFFF8000000000000L ) ); fpu_c2 = 0; return;
    }
    if( Double.isInfinite( b ) || a == 0.0 ) {    // st0 不変・Q=0
      fpu_c0 = 0; fpu_c3 = 0; fpu_c1 = 0; fpu_c2 = 0; return;
    }
    int d = x87Exponent( a ) - x87Exponent( b );
    if( d >= 64 ) {                               // 部分 reduction
      fpuSetSt( 0, a % Math.scalb( b, d - 63 ) );
      fpu_c2 = 1; return;
    }
    double rt = a % b;                            // 切捨て剰余 (正確)
    // |q| mod 8: a % (8b) との差は (q mod 8)*b の正確な倍数 (8b が inf に飽和する縮退では
    // |q|<8 なので直接商)。
    long qm;
    double b8 = Math.scalb( b, 3 );
    if( Double.isInfinite( b8 ) ) qm = (long)Math.abs( a / b );
    else                          qm = Math.round( Math.abs( (a % b8 - rt) / b ) );
    double r = rt;
    if( ieee ) {
      // 最近接 (ties-to-even) 商への補正: |rt| が |b|/2 を超えるか、tie で切捨て商が奇数なら +1。
      double half = Math.abs( b ) / 2, rta = Math.abs( rt );
      if( rta > half || ( rta == half && (qm & 1) == 1 ) ) qm = (qm + 1) & 7;
      r = Math.IEEEremainder( a, b );
    }
    if( r == 0.0 ) r = Math.copySign( 0.0, a );
    fpuSetSt( 0, r );
    fpu_c2 = 0;
    fpu_c0 = (int)((qm >> 2) & 1); fpu_c3 = (int)((qm >> 1) & 1); fpu_c1 = (int)(qm & 1);
  }

  // ---- x87 比較 (issue #24 Phase 3 x87 拡張) --------------------------------
  // FPU status word の condition code。FCOM 系が設定し FNSTSW/FSTSW で観測する。
  private int fpu_c0, fpu_c1, fpu_c2, fpu_c3;
  // status word を組む: bit14=C3, bit11-13=TOP, bit10=C2, bit9=C1, bit8=C0。
  private int fpuStatusWord( ) {
    return ((fpu_c3 & 1) << 14) | ((fpu_top & 7) << 11)
         | ((fpu_c2 & 1) << 10) | ((fpu_c1 & 1) << 9) | ((fpu_c0 & 1) << 8)
         | (fpu_sw_exc & 0x80FF);
  }
  // st(0)=a と b の比較で C3/C2/C0 を設定 (Intel SDM FCOM)。C1 は clear。
  private void fpuCompareSet( double a, double b ) {
    if( Double.isNaN( a ) || Double.isNaN( b ) ) { fpu_c3 = 1; fpu_c2 = 1; fpu_c0 = 1; }
    else if( a > b ) { fpu_c3 = 0; fpu_c2 = 0; fpu_c0 = 0; }
    else if( a < b ) { fpu_c3 = 0; fpu_c2 = 0; fpu_c0 = 1; }
    else             { fpu_c3 = 1; fpu_c2 = 0; fpu_c0 = 0; }   // equal
    fpu_c1 = 0;
  }
  // 比較オペランドの取得: register 形 (src.kind==NONE) は st(c_val)、それ以外は memory。
  //   intWidth: 0=浮動小数 (m32/m64)、2=m16int (符号拡張)、4=m32int。
  private double fpuCmpOperand( int intWidth ) {
    if( dinfo.src.kind == Operand.NONE ) return fpuSt( dinfo.c_val );
    if( intWidth == 2 ) return (double)(short)ref( dinfo.src );   // m16int
    if( intWidth == 4 ) return (double)ref( dinfo.src );          // m32int (ref size4=符号付き)
    return fpuMemRead( );
  }

  // FCOM/FCOMP/FCOMPP (m32/m64/st(i))。pops = pop 回数。intWidth=FICOM/FICOMP の整数幅。
  void fcom( int pops, int intWidth ) {
    fpuCompareSet( fpuSt( 0 ), fpuCmpOperand( intWidth ) );
    for( int k = 0; k < pops; k++ ) fpuPop( );
  }
  // FCOMPP/FUCOMPP: st(0) と st(1) を比較し 2 回 pop (固定形)。
  void fcompp( ) { fpuCompareSet( fpuSt( 0 ), fpuSt( 1 ) ); fpuPop( ); fpuPop( ); }
  // FTST: st(0) と 0.0 を比較。
  void ftst( ) { fpuCompareSet( fpuSt( 0 ), 0.0 ); }
  // FXAM: st(0) を分類して C3/C2/C1/C0 を設定 (C1=符号)。empty tag は未モデル化。
  void fxam( ) {
    double v = fpuSt( 0 );
    fpu_c1 = ( Double.doubleToRawLongBits( v ) < 0 ) ? 1 : 0;   // 符号 (-0.0 含む)
    if( Double.isNaN( v ) )        { fpu_c3 = 0; fpu_c2 = 0; fpu_c0 = 1; }   // NaN
    else if( Double.isInfinite( v ) ) { fpu_c3 = 0; fpu_c2 = 1; fpu_c0 = 1; }   // Inf
    else if( v == 0.0 )            { fpu_c3 = 1; fpu_c2 = 0; fpu_c0 = 0; }   // Zero
    else if( Math.abs( v ) < Double.MIN_NORMAL ) { fpu_c3 = 1; fpu_c2 = 1; fpu_c0 = 0; } // Denormal
    else                           { fpu_c3 = 0; fpu_c2 = 1; fpu_c0 = 0; }   // Normal
  }
  // FCOMI/FCOMIP/FUCOMI/FUCOMIP: EFLAGS ZF=C3, PF=C2, CF=C0 を直接設定。
  void fcomi( boolean pop ) {
    double a = fpuSt( 0 ), b = fpuSt( dinfo.c_val );
    if( Double.isNaN( a ) || Double.isNaN( b ) ) { zf = 1; pf = 1; cf = 1; }
    else if( a > b ) { zf = 0; pf = 0; cf = 0; }
    else if( a < b ) { zf = 0; pf = 0; cf = 1; }
    else             { zf = 1; pf = 0; cf = 0; }
    of = 0; sf = 0; af = 0;
    if( pop ) fpuPop( );
  }
  // FNSTSW AX (toAx) / FNSTSW m16。
  void fnstsw( boolean toAx ) {
    int sw = fpuStatusWord( );
    if( toAx ) reg[AX] = (reg[AX] & ~0xFFFF) | (sw & 0xFFFF);
    else       _set( dinfo.src, sw & 0xFFFF, 2 );   // m16 store (set() は size4 で過剰書込)
  }

  // NOT命令
  void not( ) {
    set( dinfo.src, ~ ref( dinfo.src ));
  }

  // NEG命令: result = 0 - operand。CF=(operand!=0)、OF/SF/ZF/AF/PF は sub(0,operand) と同じ。
  //   旧実装はフラグを一切設定していなかった。
  void neg( ) {
    int size = calc_operand_size( );
    int s = (int)ref_expand( dinfo.src );
    long sdiff = 0L - (long)s;
    int ival = (int)sdiff;
    af = ((s ^ ival) >> 4) & 1;                 // 0 ^ s ^ ival = s ^ ival
    overflow_eval( sdiff );
    long wm = (size == 1) ? 0xFFL : (size == 2) ? 0xFFFFL : 0xFFFFFFFFL;
    if( size == 1 ) { ival &= 0xFF; }
    if( size == 2 ) { ival &= 0xFFFF; }
    flag_eval( ival );
    cf = ((s & wm) != 0) ? 1 : 0;               // CF = operand != 0
    set( dinfo.src, ival );
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

  // FPU control word。演算は常に double (PC=53 相当) なので値は観測用に保持するのみ。
  //   store は m16 (set() の size4 だと 2 byte 余分に書くため _set で 2 byte 固定)。
  private int fpu_cw = 0x037F;
  void fnstcw( ) {  _set( dinfo.src, fpu_cw & 0xFFFF, 2 );  }
  void fldcw( )  {  fpu_cw = ref( dinfo.src ) & 0xFFFF;  }

  // 未定義/未実装命令 → #UD → SIGILL、特権命令 → #GP → SIGSEGV。旧実装は System.exit(0)
  //   で「静かな exit(0)」にしていた (fork 子が clean exit に見え未実装を検出できず、
  //   Cpu64 の #645 と同クラス)。term_sig を set し SegfaultException で run ループを
  //   抜ける (fork 子は親が wait4 で WIFSIGNALED、main process は 128+sig)。i386 は
  //   signal ハンドラ配送インフラが無いので終了のみ (配送は将来対応)。
  private void raiseSig( int sig, String what ) {
    String nm = (sig == Signal.SIGILL)  ? "SIGILL"
              : (sig == Signal.SIGTRAP) ? "SIGTRAP"
              : (sig == Signal.SIGFPE)  ? "SIGFPE"
              : "SIGSEGV";
    process.println( "Cpu: " + what + " at 0x" + Util.hexstr( ip, 8 ) + " -> " + nm );
    process.term_sig = sig;
    throw new Memory.SegfaultException( ip, 0, sig );
  }

  // 特権命令 (CPL3 で #GP → SIGSEGV)。CLI/STI/IN/OUT/MOV CR・DR/LGDT 系/RDMSR 系/CLTS。
  void priv_gp( ) {
    raiseSig( Signal.SIGSEGV, "privileged instruction (#GP) [" + disasm_str( next_ip ) + "]" );
  }
  // INTO: OF=1 なら #OF → SIGSEGV (Linux)、OF=0 なら no-op。
  void into( ) {
    if( of == 1 ) { raiseSig( Signal.SIGSEGV, "INTO with OF=1 (#OF)" ); }
  }
  // BOUND r32, m32&32: index が [lower, upper] 外なら #BR → SIGSEGV。
  void bound( ) {
    int idx = reg[ dinfo.src.reg_no ];
    long a = ea( dinfo.dst );
    int lower = mem.load32( a & 0xFFFFFFFFL );
    int upper = mem.load32( (a + 4) & 0xFFFFFFFFL );
    if( idx < lower || idx > upper ) { raiseSig( Signal.SIGSEGV, "BOUND range exceeded (#BR)" ); }
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
    if( ope.kind == Operand.MEM ) {                 // mod=00 rm=101 の絶対 disp32
      ret = (long)ope.adrs & 0xFFFFFFFFL;
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
