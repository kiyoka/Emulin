// ----------------------------------------
//  IA-32 Cpu Decode info 
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  $Date: 1999/09/14 16:55:23 $ 
//  $Id: Decodeinfo.java,v 1.2 1999/09/14 16:55:23 kiyoka Exp $
// ----------------------------------------
package emulin;

import java.lang.*;
import java.io.*;
import emulin.*;

public class Decodeinfo
{
  int inst_id;             // 命令 ID
  int inst_index;          // 命令データ配列への index
  int inst_len;            // 命令データバイト数
  boolean o16_flag;	   // オペランド16bitフラグ
  boolean a16_flag;        // アドレス16bitフラグ
  boolean repnz_flag;      // repne フラグ
  boolean repz_flag;       // repe フラグ
  boolean d_flag;          // dist flag
  boolean w_flag;          // width flag
  boolean W_flag;          // width flag ( B/Wサフィックス付き )
  boolean s_flag;          // 符号拡張フラグ
  boolean r_flag;          // レジスタフラグ
  boolean c_flag;          // 条件
  boolean D_flag;          // 条件
  int d_val;               // dist flag
  int w_val;               // width flag
  int W_val;               // width flag ( B/Wサフィックス付き )
  int s_val;               // 符号拡張フラグ
  int r_val;               // レジスタフラグ
  int c_val;               // 条件
  int D_val;               // doubleフラグ
  int d_index;             // dist flag
  int w_index;             // width flag
  int W_index;             // width flag ( B/Wサフィックス付き )
  int s_index;             // 符号拡張フラグ
  int r_index;             // レジスタフラグ
  int c_index;             // 条件
  int D_index;             // doubleフラグ

  // アドレッシングモードバイトの値
  int mod;                 // mod フィールド
  int reg;                 // reg フィールド
  int rpm;                 // r/m フィールド

  // SIBの値
  int scale;               // scale フィールド
  int index;               // index フィールド
  int base;                // base  フィールド

  Operand src;             // ソースオペランド
  Operand dst;             // デストオペランド
  Operand fst;             // 1番目のオペランド( 2オペランドの時は、未使用となる )


  public Decodeinfo duplicate( ) {
    Decodeinfo _ret = new Decodeinfo( );

    _ret.inst_id    =     inst_id;             // 命令 ID
    _ret.inst_index =     inst_index;          // 命令データ配列への index
    _ret.inst_len   =     inst_len;            // 命令データバイト数
    _ret.o16_flag   =     o16_flag;	       // オペランド16bitフラグ
    _ret.a16_flag   =     a16_flag;            // アドレス16bitフラグ
    _ret.repnz_flag =     repnz_flag;          // repne フラグ
    _ret.repz_flag  =     repz_flag;           // repe フラグ
    _ret.d_flag     =     d_flag;              // dist flag
    _ret.w_flag     =     w_flag;              // width flag
    _ret.W_flag     =     W_flag;              // width flag ( B/Wサフィックス付き )
    _ret.s_flag     =     s_flag;              // 符号拡張フラグ
    _ret.r_flag     =     r_flag;              // レジスタフラグ
    _ret.c_flag     =     c_flag;              // 条件
    _ret.D_flag     =     D_flag;              // 条件
    _ret.d_val      =     d_val;               // dist flag
    _ret.w_val      =     w_val;               // width flag
    _ret.W_val      =     W_val;               // width flag ( B/Wサフィックス付き )
    _ret.s_val      =     s_val;               // 符号拡張フラグ
    _ret.r_val      =     r_val;               // レジスタフラグ
    _ret.c_val      =     c_val;               // 条件
    _ret.D_val      =     D_val;               // doubleフラグ
    _ret.d_index    =     d_index;             // dist flag
    _ret.w_index    =     w_index;             // width flag
    _ret.W_index    =     W_index;             // width flag ( B/Wサフィックス付き )
    _ret.s_index    =     s_index;             // 符号拡張フラグ
    _ret.r_index    =     r_index;             // レジスタフラグ
    _ret.c_index    =     c_index;             // 条件
    _ret.D_index    =     D_index;             // doubleフラグ
    _ret.mod        =     mod;                 // mod フィールド
    _ret.reg        =     reg;                 // reg フィールド
    _ret.rpm        =     rpm;                 // r/m フィールド
    _ret.scale      =     scale;               // scale フィールド
    _ret.index      =     index;               // index フィールド
    _ret.base       =     base;                // base  フィールド
    _ret.src        =     src.duplicate( );    // ソースオペランド
    _ret.dst        =     dst.duplicate( );    // デストオペランド
    _ret.fst        =     fst.duplicate( );    // 1番目のオペランド( 2オペランドの時は、未使用となる )
    return( _ret );
  }
}

