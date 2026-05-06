// ----------------------------------------
//  IA-32 Instruction Information
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.lang.*;
import java.io.*;
import emulin.*;

public class Instruction extends XInstruction {
  int id;
  byte opecode[];
  byte mask[];
  int opebytes;
  char operand_key;
  String inst_name;
  byte d[];
  byte w[];
  byte W[];
  byte s[];
  byte r[];
  byte c[];
  byte D[];

  public void set_info(  String _name, int inst_id, int _opecodes,
		  byte _d0, byte _w0, byte _W0, byte _s0, byte _r0, byte _c0, byte _D0, 
		  byte _d1, byte _w1, byte _W1, byte _s1, byte _r1, byte _c1, byte _D1,
		  byte _opecode0, byte _opecode1, byte _mask0, byte _mask1,  char _operand_key ) {
    // メモリ確保
    id = inst_id;
    opecode = new byte[2];
    mask = new byte[2];
    d = new byte[2];
    w = new byte[2];
    W = new byte[2];
    s = new byte[2];
    r = new byte[2];
    c = new byte[2];
    D = new byte[2];

    // 値のセット
    opecode[0] = _opecode0;
    opecode[1] = _opecode1;
    mask[0] = _mask0;
    mask[1] = _mask1;
    opebytes = _opecodes;
    operand_key = _operand_key;
    inst_name = _name;
    d[0] = _d0;
    w[0] = _w0;
    W[0] = _W0;
    s[0] = _s0;
    r[0] = _r0;
    c[0] = _c0;
    D[0] = _D0;
    d[1] = _d1;
    w[1] = _w1;
    W[1] = _W1;
    s[1] = _s1;
    r[1] = _r1;
    c[1] = _c1;
    D[1] = _D1;
  }
}
