// ----------------------------------------
//  Emulin ShareDeleteFile (issue #355)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;

// issue #355: guest が open した file を Linux 同様に「open 中でも unlink/rename できる」
//   ようにするための RandomAccessFile 代替。
//
//   java.io.RandomAccessFile は Windows で CreateFile を FILE_SHARE_DELETE 無しで呼ぶため、
//   その file が open されている間は NTFS が DeleteFile / MoveFile を ERROR_SHARING_VIOLATION
//   で拒否する。結果、emulin の unlink/rename が EPERM を返し、dpkg --unpack が open 中の
//   trigger file を消せず `apt install systemd` 等が失敗していた (Linux は open 中 unlink を
//   許す)。
//
//   NIO FileChannel.open は Windows で FILE_SHARE_READ|WRITE|DELETE で開くので、open 中でも
//   delete/rename できる。本 wrapper は Fileinfo が使う RandomAccessFile の API
//   (read(byte[]) / write(byte[]) / seek / getFilePointer / length / setLength / close) を
//   そのまま提供し、内部は FileChannel に委譲する。Linux でも挙動は不変。
class ShareDeleteFile {
  private final FileChannel ch;

  // mode は RandomAccessFile 互換 ("r" / "rw" / "rws" / "rwd")。
  //   失敗時は RandomAccessFile と同様に IOException を投げる (Fileinfo.open が catch)。
  ShareDeleteFile( String name, String mode ) throws IOException {
    java.util.EnumSet<StandardOpenOption> opts;
    if( mode != null && mode.indexOf( 'w' ) >= 0 ) {
      // RandomAccessFile "rw" は「無ければ作成、既存は truncate せず read/write」。
      opts = java.util.EnumSet.of( StandardOpenOption.READ,
                                   StandardOpenOption.WRITE,
                                   StandardOpenOption.CREATE );
      if( mode.indexOf( 's' ) >= 0 ) opts.add( StandardOpenOption.SYNC );
      if( mode.indexOf( 'd' ) >= 0 ) opts.add( StandardOpenOption.DSYNC );
    } else {
      opts = java.util.EnumSet.of( StandardOpenOption.READ );
    }
    try {
      ch = FileChannel.open( Paths.get( name ), opts );
    } catch( IOException e ) {
      throw e;
    } catch( RuntimeException e ) {
      // InvalidPathException / UnsupportedOperationException 等を IOException 化
      //   (RandomAccessFile は FileNotFoundException(=IOException) を投げるので parity)。
      throw new IOException( e );
    }
  }

  // RandomAccessFile.read(byte[]) 互換: EOF で -1、それ以外は読めた byte 数。
  int read( byte[] b ) throws IOException {
    return ch.read( ByteBuffer.wrap( b ) );
  }

  // RandomAccessFile.write(byte[]) 互換: 全 byte を書く (FileChannel は短書きし得るので loop)。
  void write( byte[] b ) throws IOException {
    ByteBuffer bb = ByteBuffer.wrap( b );
    while( bb.hasRemaining( ) ) {
      ch.write( bb );
    }
  }

  void seek( long pos ) throws IOException { ch.position( pos ); }
  long getFilePointer( ) throws IOException { return ch.position( ); }
  long length( ) throws IOException { return ch.size( ); }

  // RandomAccessFile.setLength 互換: 縮小は truncate、拡大は末尾に 0 を 1 byte 書いて伸ばす。
  void setLength( long newLength ) throws IOException {
    if( newLength <= ch.size( ) ) {
      ch.truncate( newLength );
    } else if( newLength > 0 ) {
      long save = ch.position( );
      ch.position( newLength - 1 );
      ch.write( ByteBuffer.wrap( new byte[]{ 0 } ) );
      ch.position( save );
    }
  }

  void close( ) throws IOException { ch.close( ); }
}
