// ----------------------------------------
//  Emulin ProcessInfo
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  $Date: 1999/04/05 16:30:21 $ 
//  $Id: KernelCore.java,v 1.2 1999/04/05 16:30:21 kiyoka Exp kiyoka $
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import emulin.*;

public class ProcessInfo {
  Process process;
  int ppid;
  int exit_code;     // process が null 化される直前にコピーされる終了コード (wait4 用)
}
