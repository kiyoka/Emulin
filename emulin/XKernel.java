// ----------------------------------------
//  Emulin XKernel
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 1999/05/14 17:06:53 $ 
//  $Id: XKernel.java,v 1.9 1999/05/14 17:06:53 kiyoka Exp $
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import java.util.*;
import emulin.*;
import emulin.device.*;

public class XKernel extends Thread {
  Vector  ptable;
  Sysinfo sysinfo;
  emulin.device.Console console;  // コンソールデバイス

  // console への表示(改行あり)
  static public void println( String str ) {
    System.out.println( "Kernel : " + str );
  }

  // console への表示( 1バイト)
  public void write( int data ) {
    System.out.write( data );
  }

  // 親プロセスの id を求める。
  public int search_ppid( Process process ) {
    int i;
    int ppid = 1;
    if( sysinfo.verbose( )) {
      println( " search_ppid( " + process + " );   ptable.size( ) = " + ptable.size( ));
    }
    for( i = 0 ; i < ptable.size( ) ; i++ ) {
      ProcessInfo pinfo = (ProcessInfo)ptable.elementAt( i );
      if( pinfo.process != null ) {
	if( sysinfo.verbose( )) {
	  println( " search_ppid : i = " + i + " pinfo.process = " + pinfo.process );
	}
	if( pinfo.process == process ) {
	  ppid = pinfo.ppid;
	}
      }
    }
    return( ppid );
  }
}
