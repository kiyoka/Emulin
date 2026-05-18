// ----------------------------------------
//  Emulin boot up ( main )
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import emulin.*;

class Emulin {
  public static void main(String _args[]) {
    // Phase 33-19: Windows JVM が Ctrl-C で ExitProcess を呼ぶ default
    // handler が JLine の Signals.register より前に発火する症状の回避。
    // sun.misc.Signal を main 最初で直接 hook して default を上書きする。
    // 実装は no-op (= signal を完全 ignore)。Ctrl-C は byte 0x03 として
    // stdin に届くので bash readline / vim 側が処理する。
    try {
      sun.misc.Signal.handle( new sun.misc.Signal( "INT" ), sig -> {
        // intentionally empty: prevent JVM default exit behavior
      } );
    } catch( Throwable t ) {
      System.err.println("Emulin: SIGINT handler install failed: " + t);
    }

    Kernel kernel;
    int i, ip;
    byte buf[] = new byte[16];
    boolean disasm  = false;
    boolean dump    = false;
    boolean execute = true;
    int address = 0;
    int size = 0;
    int arg_index = -1;
    Sysinfo sysinfo = new Sysinfo( 0, false );
    
    test( ); // Javaのテスト用

    // スイッチの解析
    for( i = 1 ; i < _args.length ; i++ ) {
      if( '-' == _args[i].charAt( 0 )) {
	if( 'l' == _args[i].charAt( 1 )) {
	  address = Integer.parseInt( _args[i+1], 16 );
	  size    = Integer.parseInt( _args[i+2], 16 );
	  i+=2;
	  disasm  = true;
	  execute = false;
	}
	if( 'd' == _args[i].charAt( 1 )) {
	  address = Integer.parseInt( _args[i+1], 16 );
	  size    = Integer.parseInt( _args[i+2], 16 );
	  i+=2;
	  dump = true;
	  execute = false;
	}
	if( _args[i].equals( "-V" ))  { // Verbose Level 1
	  sysinfo.verbose_set( 1 );
	}
	if( _args[i].equals( "-V2" )) { // Verbose Level 2
	  sysinfo.verbose_set( 2 );
	}
	if( _args[i].equals( "-V3" )) { // Verbose Level 3
	  sysinfo.verbose_set( 3 );
	}
	if( _args[i].equals( "-D" ))  { // Debug mode
	  sysinfo.debug_on( );
	}
	if( _args[i].equals( "-CW" )) { // AWT Window Console
	  sysinfo.set_console_type( Sysinfo.CONSOLE_AWT );
	}
	if( _args[i].equals( "-CJ" )) { // JLine Console (Phase 22 step 3b)
	  sysinfo.set_console_type( Sysinfo.CONSOLE_JLINE );
	}
      }
      else {
	arg_index = i;
	break;
      }
    }

    if( _args.length < 1 ) {
      usage( );
    }

    // カーネルの生成
    kernel = new Kernel( sysinfo );
    sysinfo.kernel = kernel;

    // ルートパスを設定する。
    sysinfo.set_root( _args[0] );

    // issue #61: Windows host で C: D: 等の実在 drive を /mnt/c, /mnt/d, ...
    // に auto-mount (WSL 互換)。sandbox 内 bash / vim / git から Windows 側
    // file を直接編集可能になる。
    //   - host が Windows でのみ enable (Linux/macOS では noop)
    //   - drive root が実在 file system か (java.io.File.isDirectory()) で判定
    //   - EMULIN_NO_HOST_MOUNT=1 で opt-out
    //   - 注: /mnt/c 以下は host file system に直接反映 — 危険操作
    //     (rm -rf 等) の影響範囲が host にも拡大するので user 責任
    if( System.getProperty("os.name", "").toLowerCase().startsWith("windows")
        && System.getenv("EMULIN_NO_HOST_MOUNT") == null ) {
      for( char drive = 'a'; drive <= 'z'; drive++ ) {
        String winRoot = "" + Character.toUpperCase(drive) + ":\\";
        try {
          java.io.File f = new java.io.File(winRoot);
          if( f.exists() && f.isDirectory() ) {
            sysinfo.add_mountpoint("/mnt/" + drive, winRoot);
          }
        } catch( SecurityException se ) {
          // permission 等で probe 不可、skip
        }
      }
    }

    // emulin.cnf をロードする。
    if( sysinfo.verbose( )) { System.out.println( "load : emulin.cnf" ); }
    sysinfo.load_config( "/etc/emulin.cnf" );

    if( arg_index < 0 ) {
      usage( );
    }
    
    // 引数リストの生成
    int len       = _args.length - arg_index;
    String args[] = new String[ len ];
    for( i = 0 ; i < len ; i++ ) {
      args[i] = _args[i + arg_index];
    }

    // カーネルの実行
    title( );
    kernel.boot( args, System.getProperty( "user.dir" ));
    kernel.start( );
  }
  
  public static void usage( ) {
    title( );
    System.out.println( "  usage  : emulin.Emulin <rootpath> [switch] <elfbin>" );
    System.out.println( "  switch : -D  ... debug" );
    System.out.println( "           -V  ... verbose" );
    System.out.println( "           -CJ ... JLine Console (raw / line editing)" );
    System.exit( 1 );
  }

  public static void title( ) {
    System.err.println( "Emulin ver " + Version.get_version( ) + " Copyright (C) 1998-2026 Kiyoka Nishiyama" );
    System.err.println( "(java based EMUlation technology for Linux IA-32 / x86-64 Native application)" );
  }

  public static void test( ) {
      //      String _list[];
      //      String vpath = "D:\\emulin\\root\\\\. ";
      //      File file = new File( vpath );
      //      _list = file.list( );
      //      System.out.println( "DEBUG:::  file = [" + file + "] _list = " + _list );
  }
}
