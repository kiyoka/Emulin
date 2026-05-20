// ----------------------------------------
//  CygMode — POSIX mode を拡張属性 (xattr) に永続化
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  issue #68 Phase 2: Windows NTFS は POSIX 9-bit permission を保持しない
//  (Java の PosixFilePermissions が UnsupportedOperationException)。その
//  ため chmod が no-op になり、ssh が秘密鍵を "too open" と拒否する等の
//  問題があった (issue #9 では .ssh/* を stat で 0600 偽装して回避)。
//
//  本 helper は chmod した mode を「拡張属性 (UserDefinedFileAttributeView)」
//  に保存し、stat で読み戻す。これにより chmod が実際に効くようになる。
//
//  なぜ NTFS ACL でなく xattr か:
//    - emulin は「自分が書いた mode を自分が読む」だけで Windows native
//      tool との interop は不要 → ACL の複雑な SID mapping は過剰
//    - xattr は Windows NTFS では Alternate Data Stream、Linux では
//      user.* xattr にマップされ、Java の同一 API で両対応
//    - ACL は Linux ext4 で未サポート (テスト不可) だが xattr は両対応
//      → EMULIN_FORCE_CYGWIN_SYMLINK=1 で Linux でもテストできる
//
//  属性名: "emulin.mode" (Windows ADS / Linux では user.emulin.mode)
//  値: 低 12-bit mode (setuid/setgid/sticky + rwxrwxrwx) を 8 進文字列で。
// ----------------------------------------
package emulin;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;

public final class CygMode {

  private static final String ATTR = "emulin.mode";
  private static final int MODE_MASK = 07777;   // setuid|setgid|sticky|rwxrwxrwx

  // CygSymlink と同じ gate (Windows host or EMULIN_FORCE_CYGWIN_SYMLINK)。
  public static boolean enabled() { return CygSymlink.enabled(); }

  private static UserDefinedFileAttributeView view( String nativePath ) {
    try {
      return Files.getFileAttributeView(
          Paths.get( nativePath ), UserDefinedFileAttributeView.class );
    } catch( Throwable t ) {
      return null;
    }
  }

  // mode (低 12-bit) を xattr に保存。成功なら true。
  public static boolean setMode( String nativePath, int mode ) {
    UserDefinedFileAttributeView v = view( nativePath );
    if( v == null ) return false;
    try {
      byte[] data = Integer.toString( mode & MODE_MASK, 8 )
                           .getBytes( StandardCharsets.US_ASCII );
      v.write( ATTR, ByteBuffer.wrap( data ) );
      return true;
    } catch( Throwable t ) {
      return false;
    }
  }

  // xattr から mode を読む。保存されていなければ -1。
  public static int getMode( String nativePath ) {
    UserDefinedFileAttributeView v = view( nativePath );
    if( v == null ) return -1;
    try {
      // list() に ATTR が無ければ未保存 (size() の例外コストを避ける)
      if( !v.list().contains( ATTR ) ) return -1;
      int sz = v.size( ATTR );
      if( sz <= 0 || sz > 8 ) return -1;
      ByteBuffer buf = ByteBuffer.allocate( sz );
      v.read( ATTR, buf );
      buf.flip();
      byte[] b = new byte[ buf.remaining() ];
      buf.get( b );
      String s = new String( b, StandardCharsets.US_ASCII ).trim();
      if( s.isEmpty() ) return -1;
      return Integer.parseInt( s, 8 ) & MODE_MASK;
    } catch( Throwable t ) {
      return -1;
    }
  }
}
