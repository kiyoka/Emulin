// ----------------------------------------
//  FileSystem Mount Manager
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.io.*;
import java.lang.*;
import java.util.*;
import emulin.*;

class MountInfo {
  String _virtual;
  String _native;

  MountInfo( String __virtual, String __native ) {
    _virtual = __virtual; // 仮想マウントポイント
    _native  = __native;  // マウントパス
  }
}

// マウントポイントの管理を行う
public class Mount extends RootSysinfo {
  Vector mounts;      // マウントポイント
  String root;        // ルートポイント
  String native_sep;  // そのOSのファイルセパレータ

  // issue #401: host の ~/.emulin (実 credential file / CA 秘密鍵) への guest からの
  //   アクセス (Windows drive mount `/mnt/c` 越し等) を遮断する。EMULIN_EGRESS_MITM
  //   有効時のみ。0=未判定 (root/user.home 未確定で再試行), 1=有効, 2=無効。
  private int     denyState = 0;
  private String  denyRoot;       // ~/.emulin の native path (末尾セパレータ無し)
  private String  denySentinel;   // deny 時に返す非存在 path (親 dir も無いので create も失敗)
  private boolean denyIgnoreCase;

  Mount( ) {
    mounts = new Vector( );
    native_sep = System.getProperty( "file.separator" );
  }

  // deny guard の遅延初期化。root (set_root) と user.home が揃うまで denyState=0 で再試行。
  private void ensureDeny( ) {
    if( denyState != 0 ) return;
    if( !Egress.enabled( ) ) { denyState = 2; return; }
    String home = System.getProperty( "user.home", null );
    if( home == null || root == null ) return;   // まだ計算不可、次回再試行
    denyIgnoreCase = native_sep.charAt( 0 ) == '\\';   // Windows path は case 非依存
    denyRoot     = new java.io.File( home, ".emulin" ).getPath();  // native sep
    // issue #767: 親 dir 名に process 毎のランダムを混ぜ、guest から推測・先行作成できないようにする。
    //   固定パスだと guest が `mkdir -p <rootfs>/.emulin-denied/... ` を先に作れてしまい、deny が
    //   ENOENT でなく guest 自身のファイル内容を返し得た (実 credential は漏れないが ENOENT 契約破れ)。
    denySentinel = root + native_sep + ".emulin-denied-"
        + Long.toHexString( new java.security.SecureRandom().nextLong() )
        + native_sep + "denied";
    denyState = 1;
  }

  // ルートポイントを設定する
  public void set_root( String _root ) {
    int index = 0;
    if( native_sep.charAt( 0 ) == _root.charAt( _root.length( ) -1 )) { index = 1; }
    root = new String( _root.substring( 0, _root.length( ) -index ));
    if( verbose( )) {  kernel.println( " root = " + root );    }
    // issue #369: build 時に case-collision を pre-encode した bundle は rootfs 直下に marker/manifest
    //   `.emulin-casemap` を持つ。検出したら (方式C) まず ASCII payload から PUA 名の本体を Java NIO で配置
    //   (bsdtar は PUA 名を NTFS に作れないので初回起動時に emulin が補完する) し、続いて read 経路の dir
    //   単位 lazy scan を有効化して、直接 open でも encode 名を元名で解決できるようにする (CygSymlink 有効時のみ)。
    if( CygSymlink.enabled() && new java.io.File( root + native_sep + ".emulin-casemap" ).exists() ) {
      WinCaseMap.bootstrapFromPayload( root );
      WinCaseMap.enableReadScan();
    }
  }

  // マウントポイントを追加する。
  void add_mountpoint( String _mountpoint, String _nativepath ) {
    MountInfo mountinfo = new MountInfo( _mountpoint, _nativepath );
    mounts.addElement( (Object)mountinfo );
    CygSymlink.dentryClear();   // issue #495: virtual→native 対応が変わるのでキャッシュ全破棄
  }

  // マウントポイントを外す。
  void remove_mountpoint( String _name ) {
    // issue #497: guest の不正/NULL 引数 (umount2(NULL) 等) や _native/_virtual が
    //   NULL の登録で emulator を落とさない。null は indexOf で NPE になるため防御する。
    if( _name == null ) { return; }
    int i;
    // mountポイントかnativeパスに _nameがマッチしたらそのエントリを外す
    for( i = 0 ; i < mounts.size( ) ; i++ ) {
      int len;
      MountInfo mountinfo = (MountInfo)mounts.elementAt( i );
      if( mountinfo._native != null ) {
        len = _name.indexOf( mountinfo._native );
        if( 0 == len ) {mounts.removeElementAt( i );break;}
      }
      if( mountinfo._virtual != null ) {
        len = _name.indexOf( mountinfo._virtual );
        if( 0 == len ) {mounts.removeElementAt( i );break;}
      }
    }
    CygSymlink.dentryClear();   // issue #495: virtual→native 対応が変わるのでキャッシュ全破棄
  }

  // Nativeパスから仮想パスに変換する
  String get_virtual_path( String _native_path ) {
    int len, no = -1;
    String ret = null;
    String _root = root;
    if( '<' == _native_path.charAt( 0 )) {
      return( _native_path );
    }
    // ルートポイントからのマッチング
    len = _native_path.indexOf( _root );
    // Mountポイントからのマッチング
    if( -1 == len ) {
      int i;
      // mountポイント指定にマッチした場合そのパスを返す。
      for( i = 0 ; i < mounts.size( ) ; i++ ) {
	MountInfo mountinfo = (MountInfo)mounts.elementAt( i );
	len = _native_path.indexOf( mountinfo._native );
	if( 0 == len ) { // マッチした
	  no = i;
	  ret = joinVirtual( mountinfo._virtual,
			     _native_path.substring( mountinfo._native.length( )));
	  break;
	}
      }
    } else {
      ret = joinVirtual( "", _native_path.substring( _root.length( )));
    }
    if( -1 == len ) {
      System.err.println( "Emulin error : current path is out of virtual path area [" + _native_path + "]" );
      if( System.getenv("EMULIN_TRACE_VPATH") != null ) new Throwable("vpath-abort").printStackTrace();
      System.exit( 1 );
    }
    ret = ret.replace( native_sep.charAt( 0 ), '/' );
    if( verbose( )) {
      kernel.println( " get_virtual_path( " + _native_path + " )" );
      kernel.println( "   virtual_path = " + ret );
    }
    return( ret );
  }

  // 仮想マウントポイントからNativeパスに変換する。
  //   issue #68: Cygwin 式 symlink マジックファイル mode (CygSymlink.enabled)
  //   では path 中の symlink component を emulin 自身で追従する (host は
  //   マジックファイルを regular file としか見ないため)。最終 component も
  //   追従する (open/stat/access 等の通常 path 解決)。
  //   normal mode (= 非 Windows / force 無し) では resolve_cyg_symlinks が
  //   即 vpath を返すので、従来と完全に同一動作 (no-op)。
  String get_native_path( String _virtual_path ) {
    if( _virtual_path != null && !_virtual_path.isEmpty()
        && _virtual_path.charAt( 0 ) != '<' ) {
      if( CygSymlink.enabled() ) {
        _virtual_path = resolve_cyg_symlinks( _virtual_path, true );
      } else {
        _virtual_path = resolve_real_symlinks( _virtual_path, true );
      }
    }
    return native_path_raw( _virtual_path );
  }

  // symlink 追従なし版 (lstat / readlink / unlink / symlink 作成用)。
  //   最終 component の symlink は追従せず、中間 component のみ追従する。
  String get_native_path_nofollow( String _virtual_path ) {
    if( _virtual_path != null && !_virtual_path.isEmpty()
        && _virtual_path.charAt( 0 ) != '<' ) {
      if( CygSymlink.enabled() ) {
        _virtual_path = resolve_cyg_symlinks( _virtual_path, false );
      } else {
        _virtual_path = resolve_real_symlinks( _virtual_path, false );
      }
    }
    return native_path_raw( _virtual_path );
  }

  // issue #745: native root と vpath 残余を、区切りが二重にならないよう連結する。
  //   Windows の drive mount (`/mnt/c` → `C:\`) は _native が区切りで終わるため、
  //   素の連結だと `C:\` + `/dev/...` → `C:\\dev\...` と二重区切りになる。これは
  //   host としては同じファイルを指すが、`java.nio.file.Path.toString()` は単一区切りに
  //   正規化するため、rename (FileAccess) が呼ぶ InodeCache.invalidateWithParent の
  //   キーだけが `C:\dev\...` になり、stat が登録した `C:\\dev\...` の entry を消せない。
  //   結果、claude (Bun) の atomic write (temp + rename) 後の検証 stat が TTL の間ずっと
  //   編集前の st_size を返し、"N bytes on disk, expected M" の誤警告になっていた。
  //   ※ 残余が空 (vpath がマウントポイントそのもの) のときは `C:\` のまま返す。
  //     drive root は末尾の区切りが必須 (`C:` はカレントディレクトリ相対の意味になる)。
  private static String joinNative( String root, String rest ) {
    if( rest.isEmpty( )) return root;
    char r0 = rest.charAt( 0 );
    if(( r0 == '/' || r0 == '\\' ) && !root.isEmpty( )) {
      char last = root.charAt( root.length( ) - 1 );
      if( last == '/' || last == '\\' ) return root + rest.substring( 1 );
    }
    return root + rest;
  }

  // issue #745: joinNative の逆。native root を剥がした残余を仮想 root に繋ぐ。
  //   joinNative が区切りを 1 個に畳むようになったため、残余が区切りで始まらない
  //   ケース (`C:\dev\x` - `C:\` → `dev\x`) が生じる。ここで補わないと
  //   `/mnt/c` + `dev\x` = `/mnt/cdev/x` になる。二重区切りの旧形式が来ても
  //   (`\dev\x`) そのまま正しく繋がるようにしてある。
  private static String joinVirtual( String vroot, String rest ) {
    if( rest.isEmpty( )) return vroot;
    char r0 = rest.charAt( 0 );
    boolean restSep = ( r0 == '/' || r0 == '\\' );
    boolean rootSep = !vroot.isEmpty( ) && vroot.charAt( vroot.length( ) - 1 ) == '/';
    if( !restSep && !rootSep ) return vroot + "/" + rest;
    if( restSep && rootSep )   return vroot + rest.substring( 1 );
    return vroot + rest;
  }

  // 仮想パス → native パスの素変換 (symlink 解決なし)。
  String native_path_raw( String _virtual_path ) {
    int i;
    String ret = null;
    int index;
    int len, no = -1;
    String _root = root;
    if( _virtual_path.isEmpty() ) { return( _root ); }
    if( '<' == _virtual_path.charAt( 0 )) { // 内部ファイルパスなので,なにもしない
      return( _virtual_path );
    }
    // issue #322: Windows (CygSymlink モード) では NTFS が filename に使えない
    //   予約文字 (: * ? " < > | \) を Cygwin 式に U+F000+c へ encode してから
    //   host path を組み立てる。dpkg multiarch の <pkg>:<arch>.list 等が該当。
    //   path 区切り `/` は encode しない。getdents で d_name を decode して
    //   guest には元の `:` で見せる。Linux (CygSymlink 無効) では no-op。
    if( CygSymlink.enabled() ) {
      _virtual_path = CygSymlink.encodeReservedPath( _virtual_path );
    }
    if( verbose( )) {
      kernel.println( " get_native_path( " + _virtual_path + " )" );
    }
    ret = joinNative( _root, _virtual_path );
    if( true ) {
      // mountポイント指定にマッチした場合そのパスを返す。
      for( i = 0 ; i < mounts.size( ) ; i++ ) {
	MountInfo mountinfo = (MountInfo)mounts.elementAt( i );
	index = _virtual_path.indexOf( mountinfo._virtual );
	if( 0 == index ) { // マッチした
	  no = i;
	  _root = mountinfo._native;
	  ret = joinNative( _root, _virtual_path.substring( mountinfo._virtual.length( )));
	  break;
	}
      }
    }
    // ルートポイントをNativeパスに書き換える
    ret = ret.replace( '/', native_sep.charAt( 0 ));
    // issue #349: case 衝突で別名 encode 済みの component を on-disk 名へ置換する。
    //   衝突が一切登録されていなければ no-op (hot path)。
    ret = WinCaseMap.mapPath( ret );
    if( verbose( )) {
      kernel.println( "   native_path( " + no +  " ) = " + ret );
    }
    // issue #401: guest が host の ~/.emulin (実 credential / CA 秘密鍵) を drive mount
    //   越しに読むのを遮断する。中身は byte 単位で漏れてはならない (env 方式より安全に
    //   する条件)。非存在 sentinel (親 dir も無い) へ差し替えるので guest の read/stat/
    //   open は ENOENT、O_CREAT も親不在で失敗する。emulin 自身の ~/.emulin アクセスは
    //   直接 File I/O で Mount を通らないため影響しない。`..` は get_native_path の namei
    //   で畳み済み。regionMatches で allocation なしの prefix 判定 (hot path)。
    ensureDeny( );
    if( denyState == 1 ) {
      int rl = denyRoot.length( );
      if( ret.length( ) >= rl
          && ret.regionMatches( denyIgnoreCase, 0, denyRoot, 0, rl )
          && ( ret.length( ) == rl || ret.charAt( rl ) == native_sep.charAt( 0 ) ) ) {
        if( verbose( )) kernel.println( "   [egress] deny guest access to host ~/.emulin: " + ret );
        return denySentinel;
      }
    }
    return ret;
  }

  // issue #68: vpath の各 component を walk し、Cygwin マジックファイル
  // symlink を target に追従する (namei)。followFinal=false なら最終
  // component は追従しない (lstat / readlink / unlink 用)。
  //   絶対 vpath 前提 (emulin は full path で呼ぶ)。ELOOP は 40 回で打ち切り。
  String resolve_cyg_symlinks( String vpath, boolean followFinal ) {
    int[] loops = { 0 };
    return resolve_cyg_rec( vpath, followFinal, loops );
  }

  private String resolve_cyg_rec( String vpath, boolean followFinal, int[] loops ) {
    String[] parts = vpath.split( "/" );
    java.util.ArrayList<String> out = new java.util.ArrayList<>();
    for( int idx = 0; idx < parts.length; idx++ ) {
      String comp = parts[idx];
      if( comp.isEmpty() || comp.equals( "." ) ) continue;
      if( comp.equals( ".." ) ) {
        if( !out.isEmpty() ) out.remove( out.size() - 1 );
        continue;
      }
      boolean isFinal = ( idx == parts.length - 1 );
      out.add( comp );
      if( isFinal && !followFinal ) break;   // 最終は追従しない
      String cand = "/" + String.join( "/", out );
      String tgt;
      // issue #495: readCached (dentry cache) — 全 component の毎回 probe (NTFS stat+open) が
      //   openat ~765µs の主因だった。無効化は CygSymlink.write / FileAccess.unlink/rename が行う。
      try { tgt = CygSymlink.readCached( native_path_raw( cand ) ); }
      catch( Throwable t ) { tgt = null; }
      if( tgt != null ) {
        if( ++loops[0] > 40 ) break;          // ELOOP guard
        out.remove( out.size() - 1 );          // symlink component を外す
        String base;
        if( tgt.startsWith( "/" ) ) {
          out.clear();
          base = tgt;
        } else {
          base = "/" + String.join( "/", out ) + "/" + tgt;
        }
        // target を解決 (中間として扱うので followFinal=true) して prefix に。
        String resolvedBase = resolve_cyg_rec( base, true, loops );
        out.clear();
        for( String tp : resolvedBase.split( "/" ) ) {
          if( !tp.isEmpty() ) out.add( tp );
        }
      }
    }
    return "/" + String.join( "/", out );
  }

  // issue #322: Linux (非 Cygwin) 版の symlink namei。resolve_cyg_rec と同じロジックだが
  //   Cygwin マジックファイルでなく real host symlink (Files.isSymbolicLink/readSymbolicLink)
  //   を読む。emulin は guest path を「rootfs prefix」だけで host path に変換し、symlink の
  //   追従は host FS に委ねていたが、guest 内で作られた「絶対パスをターゲットにする symlink」
  //   (例 apt staging の /tmp/apt-dpkg-install-XXX/NN-pkg.deb -> /var/cache/apt/archives/pkg.deb)
  //   は host が host 絶対パスと解釈して rootfs を脱出 → ENOENT になる。emulin 自身が namei で
  //   絶対ターゲットを rootfs 下へ再 root する。followFinal=false なら最終 component は追従しない
  //   (lstat / readlink / unlink / symlink 作成用)。絶対 vpath 前提。ELOOP は 40 回で打ち切り。
  String resolve_real_symlinks( String vpath, boolean followFinal ) {
    int[] loops = { 0 };
    return resolve_real_rec( vpath, followFinal, loops );
  }

  private String resolve_real_rec( String vpath, boolean followFinal, int[] loops ) {
    String[] parts = vpath.split( "/" );
    java.util.ArrayList<String> out = new java.util.ArrayList<>();
    for( int idx = 0; idx < parts.length; idx++ ) {
      String comp = parts[idx];
      if( comp.isEmpty() || comp.equals( "." ) ) continue;
      if( comp.equals( ".." ) ) {
        if( !out.isEmpty() ) out.remove( out.size() - 1 );
        continue;
      }
      boolean isFinal = ( idx == parts.length - 1 );
      out.add( comp );
      if( isFinal && !followFinal ) break;   // 最終は追従しない
      String cand = "/" + String.join( "/", out );
      String tgt = null;
      try {
        java.nio.file.Path hp = java.nio.file.Paths.get( native_path_raw( cand ) );
        if( java.nio.file.Files.isSymbolicLink( hp ) ) {
          tgt = java.nio.file.Files.readSymbolicLink( hp ).toString();
          // Windows host で readSymbolicLink が \ 区切りを返す場合に備えて正規化
          tgt = tgt.replace( '\\', '/' );
        }
      } catch( Throwable t ) { tgt = null; }
      if( tgt != null ) {
        if( ++loops[0] > 40 ) break;          // ELOOP guard
        out.remove( out.size() - 1 );          // symlink component を外す
        String base;
        if( tgt.startsWith( "/" ) ) {
          out.clear();
          base = tgt;
        } else {
          base = "/" + String.join( "/", out ) + "/" + tgt;
        }
        // target を解決 (中間として扱うので followFinal=true) して prefix に。
        String resolvedBase = resolve_real_rec( base, true, loops );
        out.clear();
        for( String tp : resolvedBase.split( "/" ) ) {
          if( !tp.isEmpty() ) out.add( tp );
        }
      }
    }
    return "/" + String.join( "/", out );
  }

  // フルパス名を返す
  //   _curdir は仮想パス
  public String get_full_path( String _curdir, String name ) {
    if( 0 == name.length( )) {
      name = _curdir;
      return( name );
    }
    if( '<' == name.charAt( 0 )) {
      return( name );
    }
    if( verbose( )) {
      kernel.println( " get_full_path( " + name + " )" );
    }
    if( '/' != name.charAt( 0 )) {
      name = _curdir + "/" + name;
    }
    if( verbose( )) {
      kernel.println( "   full_path = " + name );
    }
    name = Util.realname( name );
    return( name );
  }
}
