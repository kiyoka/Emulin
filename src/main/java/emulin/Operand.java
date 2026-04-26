// ----------------------------------------
//  Operand Information
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 1999/09/14 16:55:23 $ 
//  $Id: Operand.java,v 1.14 1999/09/14 16:55:23 kiyoka Exp $
// ----------------------------------------
package emulin;

import java.lang.*;
import java.io.*;
import emulin.*;

public class Operand
{
  static String reg_name_str[] = {
    "eax",
    "ecx",
    "edx",
    "ebx",
    "esp",
    "ebp",
    "esi",
    "edi"
  };
  static String hreg_name_str[] = {
    "al",
    "cl",
    "dl",
    "bl",
    "ah",
    "ch",
    "dh",
    "bh"
  };
  static int NONE = 0;
  static int REG  = 1;
  static int RREG = 2;
  static int DISP = 3;
  static int EA   = 4;
  static int REA  = 5;
  static int IMM  = 6;
  static int MEM  = 7;
  static int IREG = 8;
  static int HREG = 9;

  int kind;
  int reg_no;
  int disp;
  int imm;
  int adrs;

  boolean ea_disp_flag;
  int scale;
  boolean base_is_reg;
  int base_reg;
  int base_val;
  int index_reg;

  public void Operand( ) {
    init( );
  }

  public void init( ) {
    kind = NONE;
    base_is_reg = false;
    scale = 0;
    ea_disp_flag = false;
    disp = 0;
    imm = 0;
  }

  public Operand duplicate( ) {
    Operand _ret = new Operand( );

    _ret.kind          = kind;
    _ret.reg_no        = reg_no;
    _ret.disp          = disp;
    _ret.imm           = imm;
    _ret.adrs          = adrs;
    _ret.ea_disp_flag  = ea_disp_flag;
    _ret.scale         = scale;
    _ret.base_is_reg   = base_is_reg;
    _ret.base_reg      = base_reg;
    _ret.base_val      = base_val;
    _ret.index_reg     = index_reg;
    return( _ret );
  }

  // オペランド文字列を返す
  public String operand_str( int address ) {
    String sbuf = "";
    if( kind == REG ) {
      return( reg_name( reg_no ));
    }
    if( kind == HREG ) {
      return( hreg_name( reg_no ));
    }
    if( kind == RREG ) {
      if( disp != 0 ) {
	sbuf += "0x" + Util.hexstr( disp, 0 );
      }
      sbuf += "(" + reg_name( reg_no ) + ")";
      return( sbuf );
    }
    if( kind == IREG ) {
      sbuf += "*" + reg_name( reg_no );
      return( sbuf );
    }
    if( kind == DISP ) {
      return( "0x" + Util.hexstr( address + disp, 8 ));
    }
    if( kind == IMM ) {
      return( "0x" + Util.hexstr( imm, 0 ));
    }
    if( kind == MEM ) {
      return( "$0x" + Util.hexstr( adrs, 0 ));
    }
    if( (kind == EA) || (kind == REA ) ) {
      if( ea_disp_flag ) {
	sbuf += "0x" + Util.hexstr( disp, 0 );
      }
      sbuf += "(";
      if( base_is_reg ) {
	sbuf += reg_name( base_reg ) + ",";
      }
      else {
	sbuf += "0x" + Util.hexstr( base_val, 0 ) + ",";
      }
      if( index_reg != Cpu.SP ) {
	sbuf += reg_name( index_reg ) + ",";
      }
      sbuf += "0x" + Util.hexstr( scale, 0 );
      sbuf += ")";

      if( kind == REA ) {
	sbuf = "*" + sbuf;
      }
      return( sbuf );
    }
    return( "" );
  }

  public static String reg_name( int reg_no ) {
    return( "%" + reg_name_str[ reg_no ] );
  }

  public static String hreg_name( int reg_no ) {
    return( "%" + hreg_name_str[ reg_no ] );
  }
}
