// ----------------------------------------
//  IA-32 Cpu Decoder (no support 16bit code)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.lang.*;
import java.io.*;
import emulin.*;

public class XDecoder
{
  Process process;
  int inst_num;
  Instruction inst[];

  static byte OperandSizePrefix = 0x66;
  static byte AddressSizePrefix = 0x67;
  static byte REPNZ = (byte)0xF2;
  static byte REPZ  = (byte)0xF3;
  static String cond_str[] = {
    "O",    "NO",    "B",    "AE",    "E",    "NE",    "BE",    "A",    "S",    "NS",    "P",
    "NP",   "L",     "GE",   "LE",    "G"
  };
  static int JO  = 0;
  static int JNO = 1;
  static int JB  = 2;
  static int JAE = 3;
  static int JE  = 4;
  static int JNE = 5;
  static int JBE = 6;
  static int JA  = 7;
  static int JS  = 8;
  static int JNS = 9;
  static int JP  = 10;
  static int JNP = 11;
  static int JL  = 12;
  static int JGE = 13;
  static int JLE = 14;
  static int JG  = 15;

  short inst_search_list[];
  Sysinfo sysinfo;         // システム情報 ( Cpuクラスの生成時に初期化される )

  Decodeinfo dinfo;        // デコード結果情報
  Decodeinfo dcache[];     // デコードキャッシュ
  short cached_address[];  // キャッシュされたアドレス ( アドレスの上位16bit )
  int hits;                // キャッシュにヒットした回数
  int trys;                // キャッシュが使えるかチェックした回数

  public XDecoder( ) {
    int i;
    inst_num = 256;
    inst = new Instruction[inst_num];

    for( i = 0 ; i < inst_num ; i++ ) {
      inst[i] = new Instruction( );
    }
    dinfo = new Decodeinfo( );
    dinfo.src = new Operand( );
    dinfo.dst = new Operand( );
    dinfo.fst = new Operand( );

    //    dcache         = new Decodeinfo[0x10000];
    //    cached_address = new short[0x10000];
    hits = 0;
    trys = 0;
  }

  // 命令データの追加
  void _add_inst( int opecode_index, int inst_id, String name, int opecodes,
		  byte d0, byte w0, byte W0, byte s0, byte r0, byte c0, byte D0,
		  byte d1, byte w1, byte W1, byte s1, byte r1, byte c1, byte D1,
		  byte opecode0, byte opecode1, byte mask0, byte mask1,  char operand_key ) {
    inst[opecode_index].set_info( name, inst_id, opecodes, 
				d0, w0, W0, s0, r0, c0, D0,
				d1, w1, W1, s1, r1, c1, D1,
				opecode0, opecode1, mask0, mask1, operand_key );
  }

  // デコードした命令コードを返す
  public int get_inst_id( ) {
    return( dinfo.inst_id );
  }

  // キャッシュにヒットするかチェックする。
  public boolean cache_check( long ip ) {
    // キャッシュにヒットするかチェックする。
    //    short high = (short)((ip >> 16) & 0xFFFF);
    //    int low = ip & 0xFFFF;
    boolean ret = false;
    //    if( sysinfo.cache( )) {
    //      if( cached_address[low] == high ) {
    //	//      hits++;
    //	ret = true;
    //      }
    //    }
    //    trys++;
    
    //    if( 0 == ( trys % 10000 )) {
    //      process.println( " hits/trys = (" + hits + "/" + trys + ") : " + ((double)hits*100.0) / (double)trys + "%" );
    //    }
    return( ret );
  }

  // キャッシュを破棄する
  public void cache_expire( ) {
    int i;
    //    for( i = 0 ; i < 0x10000 ; i++ ) {
    //      dcache[i] = null;
    //    }
    //    dcache = null;
    //    cached_address = null;
  }

  // デコードを行う
  public int decode( long ip, byte buf[], boolean use_cache ) {
    int len = 0;
    //    short high = (short)((ip >> 16) & 0xFFFF);
    int low = (int)(ip & 0xFFFFL);

    if( use_cache ) {
      dinfo = dcache[low].duplicate( );
    }
    else {
      dinfo.d_flag = false;               // dist flag
      dinfo.w_flag = false;               // width flag
      dinfo.W_flag = false;               // width flag ( B/Wサフィックス付き )
      dinfo.s_flag = false;               // 符号拡張フラグ
      dinfo.r_flag = false;               // レジスタフラグ
      dinfo.c_flag = false;               // 条件
      dinfo.D_flag = false;               // 条件

      dinfo.src.init( );                  // srcオペランド初期化
      dinfo.dst.init( );                  // dstオペランド初期化
      dinfo.fst.init( );
      
      // 命令の決定
      dinfo.inst_index = _decide_inst_index( buf );
      // オペランドの解析
      if( dinfo.o16_flag ) { len++; }
      if( dinfo.repnz_flag ) { len++; }
      if( dinfo.repz_flag ) { len++; }
      len += dinfo.seg_pfx_len;                    // segment-override / address-size prefix 分
      // オペコード中のフラグの解析
      inst_flag_analyze( buf, inst[dinfo.inst_index].opebytes, len );
      len = operand( buf, inst[dinfo.inst_index].opebytes+len );
      
      dinfo.inst_id = inst[dinfo.inst_index].id;
      dinfo.inst_len = len;

      //      if( sysinfo.cache( )) {
      //	if( dinfo.inst_len >= 2 ) {
      //	  dcache[low]         = dinfo.duplicate( );
      //	  cached_address[low] = high;
      //	}
      //      }
    }
    return( dinfo.inst_len );
  }

  // 命令中のフラグの解析
  void inst_flag_analyze( byte buf[], int opebytes, int len ) {
    int i;
    for( i = 0 ; i < opebytes ; i++ ) {
      if( 0 != inst[dinfo.inst_index].r[i] ) {
	dinfo.r_val = (inst[dinfo.inst_index].r[i] & buf[i+len]);
	dinfo.r_flag = true;
	dinfo.r_index = i;
      }
      if( 0 != inst[dinfo.inst_index].w[i] ) {
	dinfo.w_val = (inst[dinfo.inst_index].w[i] & buf[i+len]);
	dinfo.w_flag = true;
	dinfo.w_index = i;
      }
      if( 0 != inst[dinfo.inst_index].W[i] ) {
	dinfo.W_val = (inst[dinfo.inst_index].W[i] & buf[i+len]);
	dinfo.W_flag = true;
	dinfo.W_index = i;
      }
      if( 0 != inst[dinfo.inst_index].c[i] ) {
	dinfo.c_val = (inst[dinfo.inst_index].c[i] & buf[i+len]);
	dinfo.c_flag = true;
	dinfo.c_index = i;
      }
      if( 0 != inst[dinfo.inst_index].d[i] ) {
	dinfo.d_val = (inst[dinfo.inst_index].d[i] & buf[i+len]);
	dinfo.d_flag = true;
	dinfo.d_index = i;
      }
      if( 0 != inst[dinfo.inst_index].s[i] ) {
	dinfo.s_val = (inst[dinfo.inst_index].s[i] & buf[i+len]);
	dinfo.s_flag = true;
	dinfo.s_index = i;
      }
      if( 0 != inst[dinfo.inst_index].D[i] ) {
	dinfo.D_val = (inst[dinfo.inst_index].D[i] & buf[i+len]);
	dinfo.D_flag = true;
	dinfo.D_index = i;
      }
    }

    // ソースオペランド暫定
    if( dinfo.r_flag ) {
      dinfo.src.kind = Operand.REG;
      dinfo.src.reg_no = dinfo.r_val;
    }
  }


  // オペランドの解析を行う
  int operand( byte buf[], int len ) {
    Operand temp;
    if( inst[dinfo.inst_index].operand_key == '-' ) {
      len = _operand_slash( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'W' ) {
      len = _operand_W( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'M' ) {
      len = _operand_M( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 't' ) {
      len = _operand_t( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'T' ) {
      len = _operand_T( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'm' ) {
      len = _operand_m( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'p' ) {
      len = _operand_p( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 's' ) {
      len = _operand_s( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'd' ) {
      len = _operand_d( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'V' ) {
      len = _operand_V( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'D' ) {
      len = _operand_D( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'e' ) {
      len = _operand_e( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'E' ) {
      len = _operand_E( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'n' ) {
      len = _operand_n( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'N' ) {
      len = _operand_N( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'o' ) {
      len = _operand_o( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'I' ) {
      len = _operand_I( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'i' ) {
      len = _operand_i( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'J' ) {
      len = _operand_J( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == '1' ) {
      len = _operand_1( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'C' ) {
      len = _operand_C( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'Y' ) {
      len = _operand_Y( buf, len );
    }
    if( inst[dinfo.inst_index].operand_key == 'v' ) {
      len = _operand_v( buf, len );
    }

    // src,dst を逆にする
    if( dinfo.d_flag && ( dinfo.d_val != 0 )) {
      temp = dinfo.src;
      dinfo.src = dinfo.dst;
      dinfo.dst = temp;
    }
    return( len );
  }

  // オペランド記号 -の解析を行う
  int _operand_slash( byte buf[], int len ) {
    return( len );
  }

  // オペランド記号 Mの解析を行う
  int _operand_M( byte buf[], int len ) {
    len = _amb_analyze( buf, len );
    return( len );
  }

  int _operand_W( byte buf[], int len ) {
    Operand temp;
    len = _operand_M( buf, len );
    temp = dinfo.src;
    dinfo.src = dinfo.dst;
    dinfo.dst = temp;
    return( len );
  }

  // オペランド記号 Y: 3-op IMUL (69/6B) 用。W (dst=reg, src=r/m) の後に immediate を
  //   dinfo.fst へ読む。s_flag (opcode 6B の bit1) が立っていれば imm8 (符号拡張)、
  //   さもなくば o16 で imm16 / それ以外 imm32。
  int _operand_Y( byte buf[], int len ) {
    len = _operand_W( buf, len );
    dinfo.fst.init( );
    dinfo.fst.kind = Operand.IMM;
    if( dinfo.s_flag && ( dinfo.s_val != 0 ) ) {
      dinfo.fst.imm = (int)buf[len];              // imm8 (Java byte=符号付きで符号拡張)
      len += 1;
    } else if( dinfo.o16_flag ) {
      dinfo.fst.imm = (int)Util.to16( buf, len ); // imm16
      len += 2;
    } else {
      dinfo.fst.imm = Util.to32( buf, len );      // imm32
      len += 4;
    }
    return( len );
  }

  // オペランド記号 v: ENTER imm16,imm8。src=frame size(imm16), fst=nesting level(imm8)。
  int _operand_v( byte buf[], int len ) {
    dinfo.src.init( );
    dinfo.src.kind = Operand.IMM;
    dinfo.src.imm  = ((int)Util.to16( buf, len )) & 0xFFFF;
    dinfo.fst.init( );
    dinfo.fst.kind = Operand.IMM;
    dinfo.fst.imm  = buf[len+2] & 0xFF;
    return( len + 3 );
  }

  // オペランド記号 tの解析を行う
  int _operand_t( byte buf[], int len ) {
    len = _amb_analyze( buf, len );
    dinfo.fst.kind = Operand.IMM;
    dinfo.fst.imm = buf[len];
    return( len+1 );
  }

  // オペランド記号 Tの解析を行う
  int _operand_T( byte buf[], int len ) {
    len = _amb_analyze( buf, len );
    dinfo.fst.kind = Operand.REG;
    dinfo.fst.reg_no = Cpu.CX;
    return( len );
  }

  // オペランド記号 mの解析を行う
  int _operand_m( byte buf[], int len ) {
    len = _amb_analyze( buf, len );
    dinfo.src = dinfo.dst;
    dinfo.dst = new Operand( );
    return( len );
  }

  // オペランド記号 pの解析を行う
  int _operand_p( byte buf[], int len ) {
    len = _amb_analyze( buf, len );
    dinfo.src = dinfo.dst;
    dinfo.dst = new Operand( );
    dinfo.dst.kind = Operand.REG;
    dinfo.dst.reg_no = Cpu.AX;
    return( len );
  }

  // オペランド記号 sの解析を行う
  int _operand_s( byte buf[], int len ) {
    len = _amb_analyze( buf, len );
    dinfo.src = dinfo.dst;
    dinfo.dst = new Operand( );
    return( len );
  }

  // オペランド記号 dの解析を行う
  int _operand_d( byte buf[], int len ) {
    dinfo.src.kind = Operand.DISP;
    dinfo.src.disp = (int)buf[len];
    return( len+1 );
  }

  // オペランド記号 Vの解析を行う
  int _operand_V( byte buf[], int len ) {
    dinfo.src.kind = Operand.IMM;
    dinfo.src.imm = ((int)buf[len] & 0xFF);
    return( len+1 );
  }

  // オペランド記号 Dの解析を行う
  int _operand_D( byte buf[], int len ) {
    dinfo.src.kind = Operand.DISP;
    dinfo.src.disp = Util.to32( buf, len );
    return( len+4 );
  }

  // オペランド記号 eの解析を行う
  int _operand_e( byte buf[], int len ) {
    dinfo.dst.kind    = Operand.REG;
    dinfo.dst.reg_no  = Cpu.AX;
    dinfo.src.kind    = Operand.MEM;
    dinfo.src.adrs    = Util.to32( buf, len );
    return( len+4 );
  }

  // オペランド記号 Eの解析を行う
  int _operand_E( byte buf[], int len ) {
    dinfo.src.kind    = Operand.REG;
    dinfo.src.reg_no  = Cpu.AX;
    dinfo.dst.kind    = Operand.MEM;
    dinfo.dst.adrs    = Util.to32( buf, len );
    return( len+4 );
  }

  // オペランド記号 nの解析を行う
  int _operand_n( byte buf[], int len ) {
    len = _amb_analyze( buf, len );
    dinfo.src = dinfo.dst;
    if( Operand.EA == dinfo.src.kind ) {
      dinfo.src.kind = Operand.REA;
    }
    if( Operand.REG == dinfo.src.kind ) {
      dinfo.src.kind = Operand.IREG;
    }
    dinfo.dst = new Operand( );
    return( len );
  }

  // オペランド記号 Nの解析を行う
  int _operand_N( byte buf[], int len ) {
    len = _amb_analyze( buf, len );
    len = _imm_analyze( buf, len );
    return( len );
  }

  // オペランド記号 oの解析を行う
  int _operand_o( byte buf[], int len ) {
    len = _amb_analyze( buf, len );
    dinfo.src.kind = Operand.IMM;
    dinfo.src.imm = ((int)buf[len] & 0xFF);
    return( len+1 );
  }

  // オペランド記号 oの解析を行う
  int _operand_1( byte buf[], int len ) {
    len = _amb_analyze( buf, len );
    dinfo.src.kind = Operand.IMM;
    dinfo.src.imm = 1;
    return( len );
  }

  // オペランド記号 oの解析を行う
  int _operand_C( byte buf[], int len ) {
    len = _amb_analyze( buf, len );
    dinfo.src.kind = Operand.REG;
    dinfo.src.reg_no = Cpu.CX;
    return( len );
  }

  // オペランド記号 Iの解析を行う
  int _operand_I( byte buf[], int len ) {
    len = _imm_analyze( buf, len );
    dinfo.dst.init( );
    // デストオペランド設定
    dinfo.dst.kind = Operand.REG;
    dinfo.dst.reg_no = Cpu.AX;
    if( dinfo.r_flag ) {
      dinfo.dst.kind = Operand.REG;
      dinfo.dst.reg_no = dinfo.r_val;
    }
    return( len );
  }

  // オペランド記号 iの解析を行う
  int _operand_i( byte buf[], int len ) {
    dinfo.o16_flag = true;
    len = _imm_analyze( buf, len );
    dinfo.dst.init( );
    // デストオペランド設定
    dinfo.dst.kind = Operand.REG;
    dinfo.dst.reg_no = Cpu.AX;
    if( dinfo.r_flag ) {
      dinfo.dst.kind = Operand.REG;
      dinfo.dst.reg_no = dinfo.r_val;
    }
    return( len );
  }

  // オペランド記号 Jの解析を行う
  int _operand_J( byte buf[], int len ) {
    len = _imm_analyze( buf, len );
    // ソースオペランド accum 決定
    dinfo.dst.kind = Operand.REG;
    dinfo.dst.reg_no = Cpu.AX;
    return( len );
  }

  int _imm_analyze( byte buf[], int len ) {
    if( (dinfo.s_flag && ( dinfo.s_val != 0 ))
       ||
	(dinfo.w_flag && ( dinfo.w_val == 0 ))
  	 ) {
      dinfo.src.kind = Operand.IMM;
      dinfo.src.imm = (int)buf[len];
      len += 1;
    }
    else {
      if( dinfo.o16_flag ) {
        dinfo.src.kind = Operand.IMM; 
        dinfo.src.imm = (int)Util.to16( buf, len );
        len += 2;
      }
      else {
        dinfo.src.kind = Operand.IMM;
        dinfo.src.imm = Util.to32( buf, len );
        len += 4;
      }
    }
    return( len );
  }

  // レジスタフールドの解析
  void reg_analyze( Operand src, int reg ) {
    dinfo.src.reg_no = reg;
    dinfo.src.kind = Operand.REG;
    if(( dinfo.w_flag )&&(dinfo.w_val == 0)) {   dinfo.src.kind = Operand.HREG;  }
  }

  // アドレッシングモードバイトの解析
  // 解析したバイト数を返す
  int _amb_analyze( byte buf[], int len ) {
    dinfo.mod = (buf[len] >> 6) & 0x3;
    dinfo.reg = (buf[len] >> 3) & 0x7;
    dinfo.rpm = buf[len] & 0x7;

    // ソースレジスタ
    reg_analyze( dinfo.src, dinfo.reg );

    // ディストネーションメモリ
    if( 3 == dinfo.mod ) {
      if(( dinfo.w_flag )&&(dinfo.w_val == 0)) {
        dinfo.dst.kind = Operand.HREG;
        dinfo.dst.reg_no = dinfo.rpm;
        len += 1;
      }
      else {
        dinfo.dst.kind = Operand.REG;
        dinfo.dst.reg_no = dinfo.rpm;
        len += 1;
      }
    }
    if( 2 == dinfo.mod ) {
      if( 4 == dinfo.rpm ) {
	len = _sib_analyze( buf, len+1, dinfo.mod );
	dinfo.dst.disp = Util.to32( buf, len );
	dinfo.dst.ea_disp_flag = true;
	len += 4;
      }
      else {
	dinfo.dst.kind = Operand.RREG;
	dinfo.dst.reg_no = dinfo.rpm;
	dinfo.dst.disp = Util.to32( buf, len+1 );
	len += 5;
      }
    }
    if( 1 == dinfo.mod ) {
      if( 4 == dinfo.rpm ) {
	len = _sib_analyze( buf, len+1, dinfo.mod );
	dinfo.dst.disp = (int)buf[len];
	dinfo.dst.ea_disp_flag = true;
	len += 1;
      }
      else {
	dinfo.dst.kind = Operand.RREG;
	dinfo.dst.reg_no = dinfo.rpm;
	dinfo.dst.disp = (int)buf[len+1];
	len += 2;
      }
    }
    if( 0 == dinfo.mod ) {
      if( 4 == dinfo.rpm ) {
	len = _sib_analyze( buf, len+1, dinfo.mod );
      }
      else {
	if( 5 == dinfo.rpm ) {
	  dinfo.dst.kind = Operand.MEM;
	  dinfo.dst.adrs = Util.to32( buf, len+1 );
	  len += 5;
	}
	else {
	  dinfo.dst.kind = Operand.RREG;
	  dinfo.dst.reg_no = dinfo.rpm;
	  len += 1;
	}
      }
    }
    return( len );
  }

  // アドレッシングモードバイトの解析
  int _sib_analyze( byte buf[], int len, int mod ) {
    dinfo.scale = (buf[len] >> 6) & 0x3;
    dinfo.index = (buf[len] >> 3) & 0x7;
    dinfo.base  =  buf[len] & 0x7;

    if( sysinfo.debug( )) {
      System.out.println(  " base =" + dinfo.base + " index =" + dinfo.index + " scale =" + dinfo.scale + " mod =" + dinfo.mod );
    }

    dinfo.dst.kind = Operand.EA;
    dinfo.dst.scale = 1 << dinfo.scale;
    dinfo.dst.base_is_reg = false;
    if( 5 == dinfo.base ) {
      if( mod == 0 ) {
        dinfo.dst.base_val = Util.to32( buf, len+1 );
        len += 5;
      }
      else {
	dinfo.dst.base_is_reg = true;
        dinfo.dst.base_reg = Cpu.BP;
        len += 1;
      }
    }
    else {
      dinfo.dst.base_is_reg = true;
      dinfo.dst.base_reg = dinfo.base;
      len += 1;
    }
    dinfo.dst.index_reg = dinfo.index;
    return( len );
  }

  // 命令を決定する
  int _decide_inst_index( byte buf[] ) {
    int i;
    int len = 0;
    dinfo.o16_flag = false;
    dinfo.repnz_flag = false;
    dinfo.repz_flag = false;
    dinfo.seg_pfx_len = 0;
    dinfo.seg_override = 0;
    // prefix は任意順で連続しうる (例: gcc の `rep movsw` = 66 F3 A5 は 0x66 が F3 の前)。
    //   固定順の逐次判定だと 0x66 が先だと F3 を opcode 扱いして誤デコードするため loop で吸収。
    //   segment-override (2E/26/36/3E/64/65) と address-size (67) も消費する。flat model では
    //   DS/ES/SS/CS override はアドレス計算に無影響 (no-op)。FS/GS の base 適用は未対応 (TODO)。
    boolean _more_prefix = true;
    while( _more_prefix ) {
      int b = buf[len] & 0xFF;
      if( buf[len] == REPNZ )                  { len += 1; dinfo.repnz_flag = true; }
      else if( buf[len] == REPZ )              { len += 1; dinfo.repz_flag = true; }
      else if( buf[len] == OperandSizePrefix ) { len += 1; dinfo.o16_flag = true; }
      else if( b == 0x2E || b == 0x26 || b == 0x36 || b == 0x3E || b == 0x64 || b == 0x65 ) {
        dinfo.seg_override = b; dinfo.seg_pfx_len += 1; len += 1;
      }
      else if( b == 0x67 ) { dinfo.seg_pfx_len += 1; len += 1; }   // address-size (a16)
      else if( b == 0xF0 ) { dinfo.seg_pfx_len += 1; len += 1; }   // LOCK (単一スレッド interp では命令単位で atomic → no-op)
      else { _more_prefix = false; }
    }

    if( -1 != ( i = inst_search_list[ (int)buf[len] & 0xFF ] )) {
      return( i );
    }
    for( i = 0 ; i < inst_num ; i++ ) {
      // set_info 未呼びの空スロットは opecode==null。従来は Unknown catch-all を
      //   最終実エントリの直後 (index 143) に置き loop がそこで必ず止まるため空スロットに
      //   到達しなかった。x87 追加で Unknown を高 index へ移したので明示スキップする。
      if( inst[i].opecode == null ) continue;
      if(
	 ( inst[i].opecode[0] == ( inst[i].mask[0] & buf[len] ))
	  &&
	 ( inst[i].opecode[1] == ( inst[i].mask[1] & buf[len+1] ))
	   ) {
	return( i );
      }
    }
    return( i );
  }
}

