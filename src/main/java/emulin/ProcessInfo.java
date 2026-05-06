// ----------------------------------------
//  Emulin ProcessInfo
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
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
