// ----------------------------------------
//  SyscallAmd64: x86-64 Linux ABI ディスパッチ層
//
//  x86-64 ABI 固有部分:
//    - システムコール番号テーブル (amd64 syscall table 準拠)
//    - call_amd64() ディスパッチ (RAX=sysno, RDI/RSI/RDX/R10/R8/R9=引数)
//    - duplicate() (SyscallAmd64 インスタンスを複製)
//
//  共通の sys_* 実装は親クラス Syscall に残る。
// ----------------------------------------
package emulin;

public class SyscallAmd64 extends Syscall
{
  SyscallAmd64( Sysinfo _sysinfo, Process _process ) {
    super( _sysinfo, _process );
  }

  @Override
  public Syscall duplicate( Process _process ) {
    SyscallAmd64 _syscall = new SyscallAmd64( sysinfo, _process );
    _syscall.mem = mem;
    _syscall.update_info( (FileAccess)this );
    return( _syscall );
  }

  // x86-64 ABI: RAX=sysno, RDI=a1, RSI=a2, RDX=a3, R10=a4, R8=a5, R9=a6
  // 戻り値は RAX に書き戻す (呼び出し側で設定)
  public long call_amd64( long sysno, long a1, long a2, long a3, long a4, long a5, long a6 ) {
    if( sysno ==  1 ) return sys_write64( a1, a2, a3 );   // write(fd, buf, count)
    if( sysno ==  3 ) return sys_read64(  a1, a2, a3 );   // read(fd, buf, count)
    if( sysno == 60 ) return sys_exit64(  a1 );            // exit(code)
    process.println( "Emulin Error : Unsupported amd64 syscall sysno=[" + sysno + "]" );
    sys_exit( 1, 0, 0, 0, 0 );
    return 0;
  }

  private long sys_write64( long fd, long addr, long count ) {
    int len = (int)count;
    byte[] buf = new byte[len];
    for( int i = 0; i < len; i++ ) {
      buf[i] = mem.load8( addr + i );
    }
    if( isSTD( (int)fd ) || isERR( (int)fd ) ) {
      sysinfo.kernel.console.write( buf, isERR( (int)fd ) );
    } else {
      if( !FileWrite( (int)fd, buf ) ) return -EPIPE;
    }
    return len;
  }

  private long sys_read64( long fd, long addr, long count ) {
    int len = (int)count;
    if( isSTD( (int)fd ) || isERR( (int)fd ) ) {
      byte[] buf = new byte[len];
      len = sysinfo.kernel.console.read( buf, process );
      for( int i = 0; i < len; i++ ) {
        mem.store8( addr + i, buf[i] );
      }
    } else {
      byte[] buf = new byte[len];
      len = FileRead( (int)fd, buf );
      if( len < 0 ) return EBADF;
      for( int i = 0; i < len; i++ ) {
        mem.store8( addr + i, buf[i] );
      }
    }
    return len;
  }

  private long sys_exit64( long code ) {
    sysinfo.kernel.last_exit_code = (int)code;
    process.set_exit_flag();
    return 0;
  }
}
