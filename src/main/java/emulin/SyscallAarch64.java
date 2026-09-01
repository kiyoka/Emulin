// ----------------------------------------
//  Linux AArch64 syscall dispatcher and 64-bit ABI adapters (issue #951)
// ----------------------------------------
package emulin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class SyscallAarch64 extends Syscall {
  private static final boolean TRACE_PROCESS =
      System.getenv( "EMULIN_TRACE_HVF" ) != null;
  private static final int AT_FDCWD = -100;
  private static final int AT_SYMLINK_NOFOLLOW = 0x100;
  private static final int AT_EMPTY_PATH = 0x1000;
  private static final long UTIME_NOW = 0x3fffffffL;
  private static final long UTIME_OMIT = 0x3ffffffeL;
  private static final int GUEST_BUFFER_MAX = 1 << 20;
  private final Aarch64SyscallTable table = new Aarch64SyscallTable();

  SyscallAarch64( Sysinfo sysinfo, Process process ) {
    super( sysinfo, process );
  }

  @Override public Syscall duplicate( Process child ) {
    SyscallAarch64 result = new SyscallAarch64( sysinfo, child );
    result.mem = mem;
    result.update_info( this );
    return result;
  }

  @Override protected String unameMachine() { return "aarch64"; }

  @Override protected boolean useCygwinFilesystem() { return false; }

  long callAarch64( int number, long x0, long x1, long x2,
                    long x3, long x4, long x5 ) {
    long result;
    boolean previousFaultMode = Memory.FAULT_AS_EFAULT.get();
    Memory.FAULT_AS_EFAULT.set( Boolean.TRUE );
    try {
      result = table.dispatch( this, number, x0, x1, x2, x3, x4, x5 );
    } catch( Memory.SegfaultException error ) {
      result = EFAULT;
    } catch( OutOfMemoryError error ) {
      result = ENOMEM;
    } finally {
      Memory.FAULT_AS_EFAULT.set( previousFaultMode );
    }
    if( traceSysEnabled() ) {
      traceSys( process.pid, process.pid, number, x0, x1, x2, x3, x4, x5, result );
    }
    return result;
  }

  long aarch64Read( long fd, long address, long count ) {
    if( count < 0 ) return EINVAL;
    return sys_read( fd, address, Math.min( count, GUEST_BUFFER_MAX ), 0, 0 );
  }

  long aarch64Write( long fd, long address, long count ) {
    if( count < 0 ) return EINVAL;
    return sys_write( fd, address, Math.min( count, GUEST_BUFFER_MAX ), 0, 0 );
  }

  long aarch64Readv( long fd, long vectors, long count ) {
    if( count < 0 || count > 1024 ) return EINVAL;
    long total = 0;
    for( int index = 0; index < (int)count; index++ ) {
      long base = mem.load64( vectors + index * 16L );
      long length = mem.load64( vectors + index * 16L + 8 );
      if( length <= 0 ) continue;
      long result = aarch64Read( fd, base, length );
      if( result < 0 ) return total == 0 ? result : total;
      total += result;
      if( result < length ) break;
    }
    return total;
  }

  long aarch64Writev( long fd, long vectors, long count ) {
    if( count < 0 || count > 1024 ) return EINVAL;
    long total = 0;
    for( int index = 0; index < (int)count; index++ ) {
      long base = mem.load64( vectors + index * 16L );
      long length = mem.load64( vectors + index * 16L + 8 );
      if( length <= 0 ) continue;
      long result = aarch64Write( fd, base, length );
      if( result < 0 ) return total == 0 ? result : total;
      total += result;
      if( result < length ) break;
    }
    return total;
  }

  long aarch64Pselect6( long nfdsValue, long readfds, long writefds,
                        long exceptfds, long timeout, long signalMaskArgument ) {
    if( nfdsValue < 0 || nfdsValue > 1024 ) return EINVAL;
    int nfds = (int)nfdsValue;
    int words = (nfds + 63) / 64;
    long[] requestedRead = new long[ words ];
    long[] requestedWrite = new long[ words ];
    long[] requestedExcept = new long[ words ];
    for( int word = 0; word < words; word++ ) {
      if( readfds != 0 ) requestedRead[ word ] = mem.load64( readfds + word * 8L );
      if( writefds != 0 ) requestedWrite[ word ] = mem.load64( writefds + word * 8L );
      if( exceptfds != 0 ) requestedExcept[ word ] = mem.load64( exceptfds + word * 8L );
    }
    for( int fd = 0; fd < nfds; fd++ ) {
      long bit = 1L << (fd & 63);
      int word = fd >>> 6;
      if( ((requestedRead[ word ] | requestedWrite[ word ]
          | requestedExcept[ word ]) & bit) != 0 && get_finfo( fd ) == null ) {
        return EBADF;
      }
    }

    long deadlineNanos = Long.MAX_VALUE;
    if( timeout != 0 ) {
      long seconds = mem.load64( timeout );
      long nanos = mem.load64( timeout + 8 );
      if( seconds < 0 || nanos < 0 || nanos >= 1_000_000_000L ) return EINVAL;
      long duration;
      try {
        duration = Math.addExact( Math.multiplyExact( seconds, 1_000_000_000L ), nanos );
        deadlineNanos = Math.addExact( System.nanoTime(), duration );
      } catch( ArithmeticException overflow ) {
        deadlineNanos = Long.MAX_VALUE;
      }
    }

    while( true ) {
      long[] readyRead = new long[ words ];
      long[] readyWrite = new long[ words ];
      int ready = 0;
      for( int fd = 0; fd < nfds; fd++ ) {
        int word = fd >>> 6;
        long bit = 1L << (fd & 63);
        Fileinfo info = get_finfo( fd );
        if( (requestedRead[ word ] & bit) != 0 && aarch64ReadReady( info ) ) {
          readyRead[ word ] |= bit;
          ready++;
        }
        if( (requestedWrite[ word ] & bit) != 0 ) {
          readyWrite[ word ] |= bit;
          ready++;
        }
      }
      if( ready > 0 || System.nanoTime() >= deadlineNanos ) {
        for( int word = 0; word < words; word++ ) {
          if( readfds != 0 ) mem.store64( readfds + word * 8L, readyRead[ word ] );
          if( writefds != 0 ) mem.store64( writefds + word * 8L, readyWrite[ word ] );
          if( exceptfds != 0 ) mem.store64( exceptfds + word * 8L, 0 );
        }
        return ready;
      }
      if( process.psig_actionable() >= 0 ) return EINTR;
      try {
        Thread.sleep( 1 );
      } catch( InterruptedException interrupted ) {
        Thread.currentThread().interrupt();
        return EINTR;
      }
    }
  }

  private boolean aarch64ReadReady( Fileinfo info ) {
    if( info == null ) return false;
    if( info.peekBuf != null && info.peekLen > 0 ) return true;
    if( info.is_pipe( true ) ) {
      return sysinfo.kernel.pipe_available( info.pipe_no ) > 0
          || !sysinfo.kernel.is_pipe_connected( info.pipe_no );
    }
    if( info.isSTD() || info.isERR() ) return sysinfo.kernel.console.Available();
    if( !info.isSOCKET() ) return true;
    if( info.socketEof ) return true;
    if( info.conn != null ) {
      if( info.connectPending ) {
        info.takeConnectPending();
        return false;
      }
      try {
        return info.conn.getInputStream().available() > 0;
      } catch( java.io.IOException error ) {
        info.socketEof = true;
        return true;
      }
    }
    if( info.dgram != null ) {
      if( info.cachedDatagram != null ) return true;
      synchronized( info.sockLock ) {
        if( info.cachedDatagram != null ) return true;
        try {
          int previous = info.dgram.getSoTimeout();
          byte[] bytes = new byte[65535];
          java.net.DatagramPacket packet = new java.net.DatagramPacket( bytes, bytes.length );
          try {
            info.dgram.setSoTimeout( 1 );
            info.dgram.receive( packet );
            info.cachedDatagram = packet;
            return true;
          } catch( java.net.SocketTimeoutException noData ) {
            return false;
          } finally {
            info.dgram.setSoTimeout( previous );
          }
        } catch( java.io.IOException error ) {
          return false;
        }
      }
    }
    return false;
  }

  private boolean aarch64WriteReady( Fileinfo info ) {
    if( info == null ) return false;
    if( info.is_pipe( false ) ) {
      int pipe = info.pipe_write_no >= 0 ? info.pipe_write_no : info.pipe_no;
      return sysinfo.kernel.pipe_space( pipe ) > 0;
    }
    if( info.isSOCKET() ) info.noteConnectObserved();
    return true;
  }

  long aarch64Pipe2( long arrayAddress, long flagsValue ) {
    final int O_NONBLOCK = 0x800;
    final int O_DIRECT = 0x4000;
    final int O_CLOEXEC = 0x80000;
    int flags = (int)flagsValue;
    if( (flags & ~(O_NONBLOCK | O_DIRECT | O_CLOEXEC)) != 0 ) return EINVAL;

    int readFd = FileOpen( "<pipe>", "r", O_RDONLY );
    if( readFd < 0 ) return readFd;
    int writeFd = FileOpen( "<pipe>", "rw", O_WRONLY );
    if( writeFd < 0 ) {
      FileClose( readFd );
      return writeFd;
    }

    int pipeNumber = sysinfo.kernel.connect_pipe();
    set_pipe( pipeNumber, readFd );
    set_pipe( pipeNumber, writeFd );
    if( (flags & O_NONBLOCK) != 0 ) {
      Fileinfo readInfo = get_finfo( readFd );
      Fileinfo writeInfo = get_finfo( writeFd );
      if( readInfo != null ) readInfo.nonBlock = true;
      if( writeInfo != null ) writeInfo.nonBlock = true;
    }
    if( (flags & O_CLOEXEC) != 0 ) {
      set_cloexec( readFd, true );
      set_cloexec( writeFd, true );
    }
    mem.store32( arrayAddress, readFd );
    mem.store32( arrayAddress + 4, writeFd );
    return 0;
  }

  long aarch64Dup3( long oldFdValue, long newFdValue, long flagsValue ) {
    final int O_CLOEXEC = 0x80000;
    int oldFd = (int)oldFdValue;
    int newFd = (int)newFdValue;
    int flags = (int)flagsValue;
    if( oldFd == newFd || (flags & ~O_CLOEXEC) != 0 ) return EINVAL;
    long result = sys_dup2( oldFd, newFd, 0, 0, 0 );
    if( result >= 0 && (flags & O_CLOEXEC) != 0 ) {
      set_cloexec( newFd, true );
    }
    return result;
  }

  long aarch64Fsync( long fdValue ) {
    Fileinfo info = get_finfo( (int)fdValue );
    if( info == null ) return EBADF;
    if( info.isPIPE() || info.isSOCKET() ) return EINVAL;
    return 0;
  }

  long aarch64Ioctl( long fdValue, long requestValue, long address ) {
    int fd = (int)fdValue;
    int request = (int)requestValue;
    Fileinfo finfo = get_finfo( fd );
    if( finfo == null ) return EBADF;
    if( request == 0xc020660b ) return ENOTTY; // FS_IOC_FIEMAP: use read/write fallback
    if( request == 0x80045430 ) { // TIOCGPTN
      if( !finfo.pty_master || finfo.pty_ptn < 0 ) return ENOTTY;
      mem.store32( address, finfo.pty_ptn );
      return 0;
    }
    if( request == 0x40045431 ) { // TIOCSPTLCK
      return finfo.pty_master ? 0 : ENOTTY;
    }
    if( request == FIONREAD ) {
      int available = 0;
      if( finfo.is_pipe( true ) ) {
        available = Math.max( 0, sysinfo.kernel.pipe_available( finfo.pipe_no ) );
      } else if( finfo.conn != null ) {
        try { available = finfo.conn.getInputStream().available(); }
        catch( java.io.IOException ignored ) {}
      } else if( finfo.dgram != null ) {
        aarch64ReadReady( finfo );
        if( finfo.cachedDatagram != null ) available = finfo.cachedDatagram.getLength();
      } else if( finfo.peekBuf != null ) {
        available = finfo.peekLen;
      }
      mem.store32( address, available );
      return 0;
    }
    if( request == TIOCSCTTY || request == 0x5422 ) { // TIOCSCTTY/TIOCNOTTY
      return (finfo.pty_master || finfo.pty_slave || isSTD( fd ) || isERR( fd ))
          ? 0 : ENOTTY;
    }
    if( request == 0x5403 || request == 0x5404 ) requestValue = TCSETS;
    if( request == TCGETS ) {
      boolean pty = finfo.pty_master || finfo.pty_slave;
      if( !isSTD( fd ) && !isERR( fd ) && !pty ) return ENOTTY;
    }
    if( request != TIOCGPGRP && request != TIOCSPGRP ) {
      return sys_ioctl( fdValue, requestValue, address, 0, 0 );
    }
    if( isSTD( fd ) || isERR( fd ) ) {
      int mode = finfo.get_mode_bit() & 3;
      int hostFd = mode == 0 ? 0 : (isERR( fd ) ? 2 : 1);
      if( !sysinfo.host_std_is_tty( hostFd ) ) return ENOTTY;
    }
    if( request == TIOCGPGRP ) {
      int foreground = -1;
      if( finfo.pty_ptn >= 0 ) {
        foreground = sysinfo.kernel.pty.get_fg_pgrp( finfo.pty_ptn );
      } else if( finfo.tty_fg_pgrp >= 0 ) {
        foreground = finfo.tty_fg_pgrp;
      }
      if( foreground < 0 ) foreground = (int)aarch64Getpgid( 0 );
      mem.store32( address, foreground );
    } else {
      int foreground = mem.load32( address );
      if( finfo.pty_ptn >= 0 ) {
        sysinfo.kernel.pty.set_fg_pgrp( finfo.pty_ptn, foreground );
      } else {
        finfo.tty_fg_pgrp = foreground;
      }
    }
    return 0;
  }

  long aarch64Setpgid( long pidValue, long pgidValue ) {
    int pid = (int)pidValue;
    int pgid = (int)pgidValue;
    if( pgid < 0 ) return EINVAL;
    Process target = (pid == 0 || pid == process.pid)
        ? process : sysinfo.kernel.find_process( pid );
    if( target == null ) return ESRCH;
    int newPgid = pgid == 0 ? target.pid : pgid;
    if( newPgid != target.pid && !sysinfo.kernel.pgrp_exists( newPgid ) ) {
      return EPERM;
    }
    target.pgrp = newPgid;
    return 0;
  }

  long aarch64Getpgid( long pidValue ) {
    int pid = (int)pidValue;
    Process target = (pid == 0 || pid == process.pid)
        ? process : sysinfo.kernel.find_process( pid );
    if( target == null ) return ESRCH;
    return target.pgrp >= 0 ? target.pgrp : target.pid;
  }

  long aarch64Getcwd( long address, long size ) {
    String current = process.get_curdir();
    if( current == null || current.isEmpty() ) current = "/";
    byte[] value = (current + "\0").getBytes( StandardCharsets.UTF_8 );
    if( size < value.length ) return ERANGE;
    mem.bulkStoreToMem( address, value, 0, value.length );
    return value.length;
  }

  long aarch64Openat( long dirfdValue, long pathAddress, long flags, long mode ) {
    int dirfd = (int)dirfdValue;
    String path = mem.loadString( pathAddress );
    long validation = validateAtPath( dirfd, path );
    if( validation != 0 ) return validation;
    String resolved = resolveAt( dirfd, path );
    boolean existed = inode( resolved ).isExists();
    long result = open_resolved( resolved, translateOpenFlags( (int)flags ) );
    if( result >= 0 && !existed && (((int)flags & O_CREAT) != 0) ) {
      do_chmod( resolved, ((int)mode & 07777) & ~process.get_umask() );
    }
    return result;
  }

  long aarch64Mkdirat( long dirfdValue, long pathAddress, long modeValue ) {
    int dirfd = (int)dirfdValue;
    String path = mem.loadString( pathAddress );
    long validation = validateAtPath( dirfd, path );
    if( validation != 0 ) return validation;
    return mkdir_resolved( resolveAt( dirfd, path ), (int)modeValue );
  }

  long aarch64Unlinkat( long dirfdValue, long pathAddress, long flagsValue ) {
    final int AT_REMOVEDIR = 0x200;
    int flags = (int)flagsValue;
    if( (flags & ~AT_REMOVEDIR) != 0 ) return EINVAL;
    int dirfd = (int)dirfdValue;
    String path = mem.loadString( pathAddress );
    long validation = validateAtPath( dirfd, path );
    if( validation != 0 ) return validation;
    String resolved = resolveAt( dirfd, path );
    long typeError = enotdir_if_requires_dir( path, resolved );
    if( typeError != 0 ) return typeError;
    if( (flags & AT_REMOVEDIR) != 0 ) return rmdir_resolved( resolved );
    if( inode( resolved ).isDirectory() ) {
      String nativePath = nativePathNoFollow( resolved );
      if( !Files.isSymbolicLink( Paths.get( nativePath ) ) ) return EISDIR;
    }
    return unlink_resolved( resolved );
  }

  long aarch64Symlinkat( long targetAddress, long dirfdValue, long pathAddress ) {
    String target = mem.loadString( targetAddress );
    if( target == null || target.isEmpty() ) return ENOENT;
    int dirfd = (int)dirfdValue;
    String path = mem.loadString( pathAddress );
    long validation = validateAtPath( dirfd, path );
    if( validation != 0 ) return validation;
    return symlink_resolved( target, resolveAt( dirfd, path ) );
  }

  long aarch64Linkat( long oldDirfdValue, long oldPathAddress,
                      long newDirfdValue, long newPathAddress, long flagsValue ) {
    final int AT_SYMLINK_FOLLOW = 0x400;
    int flags = (int)flagsValue;
    if( (flags & ~AT_SYMLINK_FOLLOW) != 0 ) return EINVAL;
    int oldDirfd = (int)oldDirfdValue;
    int newDirfd = (int)newDirfdValue;
    String oldPath = mem.loadString( oldPathAddress );
    String newPath = mem.loadString( newPathAddress );
    long validation = validateAtPath( oldDirfd, oldPath );
    if( validation != 0 ) return validation;
    validation = validateAtPath( newDirfd, newPath );
    if( validation != 0 ) return validation;
    return link_resolved(
        resolveAt( oldDirfd, oldPath ), resolveAt( newDirfd, newPath ),
        (flags & AT_SYMLINK_FOLLOW) != 0 );
  }

  long aarch64Renameat( long oldDirfdValue, long oldPathAddress,
                        long newDirfdValue, long newPathAddress ) {
    int oldDirfd = (int)oldDirfdValue;
    int newDirfd = (int)newDirfdValue;
    String oldPath = mem.loadString( oldPathAddress );
    String newPath = mem.loadString( newPathAddress );
    long validation = validateAtPath( oldDirfd, oldPath );
    if( validation != 0 ) return validation;
    validation = validateAtPath( newDirfd, newPath );
    if( validation != 0 ) return validation;
    return rename_resolved(
        resolveAt( oldDirfd, oldPath ), resolveAt( newDirfd, newPath ) );
  }

  long aarch64Renameat2( long oldDirfdValue, long oldPathAddress,
                         long newDirfdValue, long newPathAddress, long flagsValue ) {
    int oldDirfd = (int)oldDirfdValue;
    int newDirfd = (int)newDirfdValue;
    String oldPath = mem.loadString( oldPathAddress );
    String newPath = mem.loadString( newPathAddress );
    long validation = validateAtPath( oldDirfd, oldPath );
    if( validation != 0 ) return validation;
    validation = validateAtPath( newDirfd, newPath );
    if( validation != 0 ) return validation;
    return renameat2_resolved(
        resolveAt( oldDirfd, oldPath ), resolveAt( newDirfd, newPath ),
        (int)flagsValue );
  }

  long aarch64Faccessat( long dirfdValue, long pathAddress, long modeValue,
                         long flagsValue, boolean faccessat2 ) {
    final int AT_SYMLINK_NOFOLLOW = 0x100;
    final int AT_EACCESS = 0x200;
    int mode = (int)modeValue;
    int flags = (int)flagsValue;
    if( (mode & ~7) != 0 ) return EINVAL;
    if( !faccessat2 && flags != 0 ) return EINVAL;
    if( faccessat2 && (flags & ~(AT_SYMLINK_NOFOLLOW | AT_EACCESS)) != 0 ) {
      return EINVAL;
    }
    int dirfd = (int)dirfdValue;
    String path = mem.loadString( pathAddress );
    long validation = validateAtPath( dirfd, path );
    if( validation != 0 ) return validation;
    String resolved = resolveAt( dirfd, path );
    long typeError = enotdir_if_requires_dir( path, resolved );
    if( typeError != 0 ) return typeError;
    if( faccessat2 && (flags & AT_SYMLINK_NOFOLLOW) != 0 ) {
      return exists_nofollow( resolved ) ? 0 : ENOENT;
    }
    return access_resolved( resolved, mode );
  }

  long aarch64Fchmodat( long dirfdValue, long pathAddress,
                        long modeValue, long flagsValue ) {
    final int AT_SYMLINK_NOFOLLOW = 0x100;
    final int AT_EMPTY_PATH = 0x1000;
    int flags = (int)flagsValue;
    if( (flags & ~(AT_SYMLINK_NOFOLLOW | AT_EMPTY_PATH)) != 0 ) return EINVAL;
    int dirfd = (int)dirfdValue;
    String path = mem.loadString( pathAddress );
    if( path.isEmpty() ) {
      return (flags & AT_EMPTY_PATH) != 0
          ? sys_fchmod( dirfd, modeValue, 0, 0, 0 ) : ENOENT;
    }
    long validation = validateAtPath( dirfd, path );
    if( validation != 0 ) return validation;
    return fchmodat_resolved(
        resolveAt( dirfd, path ), (int)modeValue,
        (flags & AT_SYMLINK_NOFOLLOW) != 0 );
  }

  long aarch64Fchownat( long dirfdValue, long pathAddress,
                        long uidValue, long gidValue, long flagsValue ) {
    final int AT_SYMLINK_NOFOLLOW = 0x100;
    final int AT_EMPTY_PATH = 0x1000;
    int flags = (int)flagsValue;
    if( (flags & ~(AT_SYMLINK_NOFOLLOW | AT_EMPTY_PATH)) != 0 ) return EINVAL;
    int dirfd = (int)dirfdValue;
    int uid = (int)uidValue;
    int gid = (int)gidValue;
    String path = mem.loadString( pathAddress );
    if( path.isEmpty() ) {
      return (flags & AT_EMPTY_PATH) != 0
          ? fchown_resolved( dirfd, uid, gid ) : ENOENT;
    }
    long validation = validateAtPath( dirfd, path );
    if( validation != 0 ) return validation;
    return chown_resolved(
        resolveAt( dirfd, path ), uid, gid,
        (flags & AT_SYMLINK_NOFOLLOW) != 0 );
  }

  long aarch64Fchown( long fdValue, long uidValue, long gidValue ) {
    return fchown_resolved( (int)fdValue, (int)uidValue, (int)gidValue );
  }

  long aarch64Statfs( long pathAddress, long bufferAddress ) {
    if( pathAddress == 0 || bufferAddress == 0 ) return EFAULT;
    String path = mem.loadString( pathAddress );
    if( path == null ) return EFAULT;
    if( path.isEmpty() ) return ENOENT;
    String resolved = sysinfo.get_full_path( process.get_curdir(), path );
    if( isProcPath( resolved ) ) {
      return storeStatfs( bufferAddress, nativePath( "/" ), 0x9fa0L );
    }
    Inode inode = inode( resolved );
    if( !inode.isExists() ) return missingPathError( resolved );
    return storeStatfs( bufferAddress, nativePath( resolved ), 0xef53L );
  }

  long aarch64Fstatfs( long fdValue, long bufferAddress ) {
    int fd = (int)fdValue;
    if( get_finfo( fd ) == null ) return EBADF;
    if( bufferAddress == 0 ) return EFAULT;
    String name = get_name( fd );
    if( name == null || name.startsWith( "<" ) ) {
      return storeStatfs( bufferAddress, nativePath( "/" ), 0xef53L );
    }
    String resolved = sysinfo.get_full_path( process.get_curdir(), name );
    boolean proc = isProcPath( resolved );
    return storeStatfs(
        bufferAddress, nativePath( proc ? "/" : resolved ),
        proc ? 0x9fa0L : 0xef53L );
  }

  long aarch64Truncate( long pathAddress, long length ) {
    if( pathAddress == 0 ) return EFAULT;
    String path = mem.loadString( pathAddress );
    if( path == null ) return EFAULT;
    if( path.isEmpty() ) return ENOENT;
    if( length < 0 ) return EINVAL;
    String resolved = sysinfo.get_full_path( process.get_curdir(), path );
    Inode inode = inode( resolved );
    if( !inode.isExists() ) return missingPathError( resolved );
    return truncate_resolved( resolved, length );
  }

  private static boolean isProcPath( String path ) {
    return path != null && (path.equals( "/proc" ) || path.startsWith( "/proc/" ));
  }

  private long storeStatfs( long address, String nativePath, long type ) {
    final long blockSize = 4096L;
    long blocks;
    long blocksFree;
    long blocksAvailable;
    try {
      java.nio.file.FileStore store = Files.getFileStore( Paths.get( nativePath ) );
      blocks = Math.max( 1L, store.getTotalSpace() / blockSize );
      blocksFree = Math.max( 1L, store.getUnallocatedSpace() / blockSize );
      blocksAvailable = Math.max( 1L, store.getUsableSpace() / blockSize );
    } catch( Exception ignored ) {
      blocks = 1L << 30;
      blocksFree = 1L << 29;
      blocksAvailable = blocksFree;
    }
    Aarch64StructCodec.storeStatfs(
        mem, address, type, blockSize, blocks, blocksFree, blocksAvailable,
        1L << 20, 1L << 19, 255, blockSize, 0 );
    return 0;
  }

  private long missingPathError( String path ) {
    String parent = path;
    int slash;
    while( (slash = parent.lastIndexOf( '/' )) > 0 ) {
      parent = parent.substring( 0, slash );
      Inode inode = inode( parent );
      if( inode.isExists() ) return inode.isDirectory() ? ENOENT : ENOTDIR;
    }
    return ENOENT;
  }

  long aarch64Utimensat( long dirfdValue, long pathAddress,
                         long timesAddress, long flagsValue ) {
    int flags = (int)flagsValue;
    if( (flags & ~AT_SYMLINK_NOFOLLOW) != 0 ) return EINVAL;

    long atimeSeconds = 0;
    long atimeNanoseconds = UTIME_NOW;
    long mtimeSeconds = 0;
    long mtimeNanoseconds = UTIME_NOW;
    if( timesAddress != 0 ) {
      atimeSeconds = mem.load64( timesAddress );
      atimeNanoseconds = mem.load64( timesAddress + 8 );
      mtimeSeconds = mem.load64( timesAddress + 16 );
      mtimeNanoseconds = mem.load64( timesAddress + 24 );
      if( !utimeNanosecondsValid( atimeNanoseconds )
          || !utimeNanosecondsValid( mtimeNanoseconds ) ) return EINVAL;
      // Linux returns success before resolving the pathname when both fields
      // request UTIME_OMIT.
      if( atimeNanoseconds == UTIME_OMIT
          && mtimeNanoseconds == UTIME_OMIT ) return 0;
    }

    String resolved;
    if( pathAddress == 0 ) {
      int fd = (int)dirfdValue;
      if( get_finfo( fd ) == null ) return EBADF;
      String name = get_name( fd );
      if( name == null || name.startsWith( "<" ) ) return EINVAL;
      resolved = sysinfo.get_full_path( process.get_curdir(), name );
    } else {
      String path = mem.loadString( pathAddress );
      long validation = validateAtPath( (int)dirfdValue, path );
      if( validation != 0 ) return validation;
      resolved = resolveAt( (int)dirfdValue, path );
      if( resolved == null ) return EBADF;
      long typeError = enotdir_if_requires_dir( path, resolved );
      if( typeError != 0 ) return typeError;
    }

    boolean nofollow = (flags & AT_SYMLINK_NOFOLLOW) != 0;
    String nativePath = nofollow ? nativePathNoFollow( resolved )
                                 : nativePath( resolved );
    java.nio.file.Path hostPath = Paths.get( nativePath );
    java.nio.file.LinkOption[] options = nofollow
        ? new java.nio.file.LinkOption[]{ java.nio.file.LinkOption.NOFOLLOW_LINKS }
        : new java.nio.file.LinkOption[]{};
    if( !Files.exists( hostPath, options ) && !Files.isSymbolicLink( hostPath ) ) {
      return ENOENT;
    }

    long nowMilliseconds = System.currentTimeMillis();
    java.nio.file.attribute.FileTime atime = fileTime(
        atimeSeconds, atimeNanoseconds, nowMilliseconds );
    java.nio.file.attribute.FileTime mtime = fileTime(
        mtimeSeconds, mtimeNanoseconds, nowMilliseconds );
    try {
      Files.getFileAttributeView(
          hostPath, java.nio.file.attribute.BasicFileAttributeView.class, options )
          .setTimes( mtime, atime, null );
    } catch( Exception ignored ) {
      // Some hosts cannot set timestamps on a symlink itself. Linux callers
      // such as dpkg still need AT_SYMLINK_NOFOLLOW to complete successfully.
    }
    InodeCache.invalidate( nativePath );
    return 0;
  }

  private static boolean utimeNanosecondsValid( long nanoseconds ) {
    return nanoseconds == UTIME_NOW || nanoseconds == UTIME_OMIT
        || (nanoseconds >= 0 && nanoseconds < 1_000_000_000L);
  }

  private static java.nio.file.attribute.FileTime fileTime(
      long seconds, long nanoseconds, long nowMilliseconds ) {
    if( nanoseconds == UTIME_OMIT ) return null;
    if( nanoseconds == UTIME_NOW ) {
      return java.nio.file.attribute.FileTime.fromMillis( nowMilliseconds );
    }
    return java.nio.file.attribute.FileTime.from(
        seconds * 1_000_000_000L + nanoseconds,
        java.util.concurrent.TimeUnit.NANOSECONDS );
  }

  // asm-generic/AArch64 and x86 use different bits for DIRECTORY, NOFOLLOW,
  // and DIRECT.  The shared file layer uses the x86 values internally.
  private int translateOpenFlags( int flags ) {
    final int AARCH64_O_DIRECTORY = 0x4000;
    final int AARCH64_O_NOFOLLOW = 0x8000;
    final int AARCH64_O_DIRECT = 0x10000;
    final int INTERNAL_O_DIRECT = 0x4000;
    final int INTERNAL_O_DIRECTORY = 0x10000;
    final int INTERNAL_O_NOFOLLOW = 0x20000;
    int translated = flags
        & ~(AARCH64_O_DIRECTORY | AARCH64_O_NOFOLLOW | AARCH64_O_DIRECT);
    if( (flags & AARCH64_O_DIRECTORY) != 0 ) translated |= INTERNAL_O_DIRECTORY;
    if( (flags & AARCH64_O_NOFOLLOW) != 0 ) translated |= INTERNAL_O_NOFOLLOW;
    if( (flags & AARCH64_O_DIRECT) != 0 ) translated |= INTERNAL_O_DIRECT;
    return translated;
  }

  long aarch64Readlinkat( long dirfdValue, long pathAddress, long bufferAddress,
                          long bufferSize ) {
    if( bufferSize <= 0 ) return EINVAL;
    String path = mem.loadString( pathAddress );
    String resolved = resolveAt( (int)dirfdValue, path );
    if( resolved == null ) return EBADF;
    String target;
    if( "/proc/self/exe".equals( resolved ) ) {
      target = process.exec_path;
    } else {
      try {
        String nativePath = nativePathNoFollow( resolved );
        target = Files.readSymbolicLink( Paths.get( nativePath ) ).toString();
      } catch( Exception error ) {
        return EINVAL;
      }
    }
    byte[] bytes = target.getBytes( StandardCharsets.UTF_8 );
    int copied = (int)Math.min( bufferSize, bytes.length );
    mem.bulkStoreToMem( bufferAddress, bytes, 0, copied );
    return copied;
  }

  long aarch64Fstat( long fdValue, long address ) {
    int fd = (int)fdValue;
    Fileinfo file = get_finfo( fd );
    if( file == null ) return EBADF;
    if( isSTD( fd ) || isERR( fd ) ) {
      Aarch64StructCodec.storeSpecialStat( mem, address, 0020000 | 0666, 0x400, 0 );
      return 0;
    }
    if( isPIPE( fd ) ) {
      Aarch64StructCodec.storeSpecialStat( mem, address, 0010000 | 0600, 0, 0 );
      return 0;
    }
    String name = get_name( fd );
    if( name == null || "<noname>".equals( name ) ) return EBADF;
    name = sysinfo.get_full_path( process.get_curdir(), name );
    Inode inode = inode( name );
    if( !inode.isExists() ) return ENOENT;
    Aarch64StructCodec.storeStat( mem, address, inode );
    return 0;
  }

  long aarch64Newfstatat( long dirfdValue, long pathAddress, long address,
                          long flagsValue ) {
    int flags = (int)flagsValue;
    if( (flags & ~(AT_SYMLINK_NOFOLLOW | AT_EMPTY_PATH | 0x800)) != 0 ) return EINVAL;
    String path = pathAddress == 0 ? "" : mem.loadString( pathAddress );
    if( path.isEmpty() ) {
      return (flags & AT_EMPTY_PATH) != 0 ? aarch64Fstat( dirfdValue, address ) : ENOENT;
    }
    String resolved = resolveAt( (int)dirfdValue, path );
    if( resolved == null ) return EBADF;
    if( (flags & AT_SYMLINK_NOFOLLOW) != 0 ) {
      try {
        String nativePath = nativePathNoFollow( resolved );
        java.nio.file.Path hostPath = Paths.get( nativePath );
        if( Files.isSymbolicLink( hostPath ) ) {
          String target = Files.readSymbolicLink( hostPath ).toString();
          Aarch64StructCodec.storeSpecialStat(
              mem, address, 0120000 | 0777, 0,
              target.getBytes( StandardCharsets.UTF_8 ).length );
          return 0;
        }
      } catch( Exception error ) {
        return ENOENT;
      }
    }
    Inode inode = inode( resolved );
    if( !inode.isExists() ) return ENOENT;
    Aarch64StructCodec.storeStat( mem, address, inode );
    return 0;
  }

  long aarch64Mmap( long address, long length, long protection, long flags,
                    long fdValue, long offset ) {
    final long page = 0x1000L;
    if( length <= 0 ) return EINVAL;
    if( (flags & 3) == 0 ) return EINVAL;
    if( (flags & 0x10) != 0 && (address & (page - 1)) != 0 ) return EINVAL;
    boolean anonymous = (flags & 0x20) != 0;
    int fd = anonymous ? -1 : (int)fdValue;
    if( fd >= 0 && get_finfo( fd ) == null ) return EBADF;
    if( fd >= 0 && (offset & (page - 1)) != 0 ) return EINVAL;
    long aligned = (length + page - 1) & ~(page - 1);
    if( aligned <= 0 || aligned > 0x7fffffffL ) return ENOMEM;
    final long taskSize = 0x1000000000000L;
    if( address < 0 || address >= taskSize || address + aligned < address
        || address + aligned > taskSize ) {
      if( (flags & (0x10L | 0x100000L)) != 0 ) return ENOMEM;
      address = 0;
    }
    if( (flags & 0x100000L) != 0 && address != 0
        && mem.isRangeMapped( address, aligned ) ) return EEXIST;
    return mem.alloc_and_map( address, (int)aligned, fd, offset,
                              (int)protection, flags );
  }

  long aarch64Madvise( long address, long length, long advice ) {
    if( (address & 0xfffL) != 0 ) return EINVAL;
    if( length < 0 ) return EINVAL;
    if( advice < 0 || advice > 25 ) return EINVAL;
    if( length != 0 ) {
      long aligned = (length + 0xfffL) & ~0xfffL;
      if( aligned <= 0 || !mem.isRangeMapped( address, aligned ) ) return ENOMEM;
    }
    return 0;
  }

  long aarch64Msync( long address, long length, long flagsValue ) {
    final int MS_ASYNC = 1;
    final int MS_INVALIDATE = 2;
    final int MS_SYNC = 4;
    int flags = (int)flagsValue;
    if( (flags & ~(MS_ASYNC | MS_INVALIDATE | MS_SYNC)) != 0
        || (flags & (MS_ASYNC | MS_SYNC)) == (MS_ASYNC | MS_SYNC)
        || (address & 0xfffL) != 0 ) return EINVAL;
    if( length < 0 ) return ENOMEM;
    long aligned = (length + 0xfffL) & ~0xfffL;
    if( aligned == 0 ) return 0;
    if( !mem.isRangeMapped( address, aligned ) ) return ENOMEM;
    mem.msyncFlush( address, aligned );
    return 0;
  }

  long aarch64ClockGettime( long clock, long address ) {
    if( clock < 0 || clock > 11 ) return EINVAL;
    if( address == 0 ) return EFAULT;
    long seconds;
    long nanoseconds;
    if( clock == 1 || clock == 6 ) {
      long now = System.nanoTime();
      seconds = now / 1_000_000_000L;
      nanoseconds = now % 1_000_000_000L;
    } else {
      long now = System.currentTimeMillis();
      seconds = now / 1000L;
      nanoseconds = (now % 1000L) * 1_000_000L;
    }
    Aarch64StructCodec.storeTimespec( mem, address, seconds, nanoseconds );
    return 0;
  }

  long aarch64ClockGetres( long clock, long address ) {
    if( clock < 0 || clock > 11 ) return EINVAL;
    if( address != 0 ) Aarch64StructCodec.storeTimespec( mem, address, 0, 1_000_000 );
    return 0;
  }

  long aarch64SetTidAddress( long address ) {
    return aarch64Gettid();
  }

  long aarch64Gettid() {
    Thread current = Thread.currentThread();
    return current instanceof GuestThread guest ? guest.guestTid() : process.pid;
  }

  long aarch64RtSigaction( long signum, long actionAddress,
                           long oldActionAddress, long sigsetSize ) {
    int signal = (int)signum;
    if( sigsetSize != 8 || signal <= 0 || signal >= Signal.SIGNALS ) return EINVAL;
    if( actionAddress != 0
        && (signal == Signal.SIGKILL || signal == Signal.SIGSTOP) ) return EINVAL;
    if( oldActionAddress != 0 ) {
      mem.store64( oldActionAddress, process.get_func_adrs( signal ) );
      mem.store64( oldActionAddress + 8, process.get_sa_flags( signal ) );
      mem.store64( oldActionAddress + 16, 0 );
      mem.store64( oldActionAddress + 24, process.get_sa_mask( signal ) );
    }
    if( actionAddress != 0 ) {
      process.set_sigaction( signal, mem.load64( actionAddress ) );
      process.set_sa_flags( signal, mem.load64( actionAddress + 8 ) );
      process.set_sa_mask( signal, mem.load64( actionAddress + 24 ) );
    }
    return 0;
  }

  long aarch64RtSigprocmask( long how, long setAddress,
                             long oldSetAddress, long sigsetSize ) {
    if( sigsetSize != 8 ) return EINVAL;
    long current = process.get_signal_mask_bits();
    if( oldSetAddress != 0 ) mem.store64( oldSetAddress, current );
    if( setAddress == 0 ) return 0;
    if( how < 0 || how > 2 ) return EINVAL;
    long requested = mem.load64( setAddress );
    long updated = how == 0 ? current | requested
        : how == 1 ? current & ~requested : requested;
    // SIGKILL and SIGSTOP cannot be blocked.
    updated &= ~(1L << (Signal.SIGKILL - 1));
    updated &= ~(1L << (Signal.SIGSTOP - 1));
    process.set_signal_mask_bits( updated );
    return 0;
  }

  long aarch64Kill( long pidValue, long signalValue ) {
    int pid = (int)pidValue;
    int signal = (int)signalValue;
    if( signal < 0 || signal >= Signal.SIGNALS ) return EINVAL;
    if( pid > 0 ) {
      Process target = sysinfo.kernel.find_process( pid );
      if( target == null ) return ESRCH;
      if( signal != 0 ) target.recv( signal );
      return 0;
    }
    if( pid == 0 ) {
      if( signal != 0 ) process.recv( signal );
      return 0;
    }
    return ENOSYS;
  }

  long aarch64Tgkill( long tgidValue, long tidValue, long signalValue ) {
    int tgid = (int)tgidValue;
    int tid = (int)tidValue;
    int signal = (int)signalValue;
    if( tgid <= 0 || tid <= 0 || signal < 0 || signal >= Signal.SIGNALS ) return EINVAL;
    Process target = sysinfo.kernel.find_process( tgid );
    if( target == null || !sysinfo.kernel.tid_ever_allocated( tid ) ) return ESRCH;
    if( signal != 0 ) target.recv_to_thread( tid, signal );
    return 0;
  }

  long aarch64Clone( long flags, long childStack, long parentTid,
                     long tls, long childTid ) {
    if( TRACE_PROCESS ) {
      System.err.println( "[aarch64-hvf] pid=" + process.pid
          + " clone flags=0x" + Long.toHexString( flags ) );
    }
    long result;
    if( (flags & 0x10100L) == 0x10100L ) { // CLONE_VM | CLONE_THREAD
      if( Memory.FORCE_ST ) return EAGAIN;
      Thread current = Thread.currentThread();
      GuestCpu parent = current instanceof GuestThread guest
          ? guest.guestCpu() : process.cpu;
      result = parent.spawnVcpu( flags, childStack, parentTid, childTid, tls );
    } else if( (flags & 0x4100L) == 0x4100L ) { // CLONE_VM | CLONE_VFORK
      result = sysinfo.kernel.vfork( process, childStack );
    } else {
      result = sysinfo.kernel.fork( process, childStack, flags );
    }
    if( TRACE_PROCESS ) {
      System.err.println( "[aarch64-hvf] pid=" + process.pid
          + " clone -> " + result );
    }
    return result;
  }

  long aarch64Execve( long pathAddress, long argvAddress, long envpAddress ) {
    String name = mem.loadString( pathAddress );
    if( name == null || name.isEmpty() ) return ENOENT;
    if( !"/proc/self/exe".equals( name ) ) {
      String full = sysinfo.get_full_path( process.get_curdir(), name );
      Inode executable = new Inode( full, sysinfo );
      if( !executable.isExists() ) return ENOENT;
      if( executable.isDirectory() || !executable.isExecutable() ) return EACCES;
    }

    java.util.ArrayList<String> arguments = new java.util.ArrayList<>();
    java.util.ArrayList<String> environment = new java.util.ArrayList<>();
    if( argvAddress != 0 ) {
      for( int index = 0; ; index++ ) {
        long pointer = mem.load64( argvAddress + index * 8L );
        if( pointer == 0 ) break;
        arguments.add( mem.loadStringRaw( pointer ) );
      }
    }
    if( envpAddress != 0 ) {
      for( int index = 0; ; index++ ) {
        long pointer = mem.load64( envpAddress + index * 8L );
        if( pointer == 0 ) break;
        environment.add( mem.loadStringRaw( pointer ) );
      }
    }
    if( arguments.isEmpty() ) arguments.add( name );

    if( TRACE_PROCESS ) {
      System.err.println( "[aarch64-hvf] pid=" + process.pid
          + " execve " + name );
    }

    Process old = process;
    sysinfo.kernel.exec( old.pid, name,
        arguments.toArray( new String[0] ), environment.toArray( new String[0] ) );
    old.vfork_signal_parent();
    old.set_exit_flag();
    return 0;
  }

  long aarch64Wait4( long pidValue, long statusAddress, long optionsValue,
                     long rusageAddress ) {
    final int WNOHANG = 1;
    final int VALID_OPTIONS = 1 | 2 | 8 | 0x20000000 | 0x40000000 | 0x80000000;
    int pid = (int)pidValue;
    int options = (int)optionsValue;
    if( (options & ~VALID_OPTIONS) != 0 ) return EINVAL;
    if( TRACE_PROCESS ) {
      System.err.println( "[aarch64-hvf] pid=" + process.pid
          + " wait4(" + pid + ",0x" + Integer.toHexString( options ) + ")" );
    }

    int result;
    while( true ) {
      if( pid == -1 ) {
        result = sysinfo.kernel.is_child_exited( process.pid );
        if( result > 0 ) break;
        if( result == 0 ) return ECHILD;
      } else if( pid > 0 ) {
        ProcessInfo child = sysinfo.kernel.get_pinfo( pid );
        if( child == null || child.ppid != process.pid || child.process == null ) {
          return ECHILD;
        }
        Process childProcess = child.process;
        if( childProcess.exit_flag && !childProcess.exec_replacing ) {
          child.exit_code = childProcess.exit_code;
          child.term_sig = childProcess.term_sig;
          child.process = null;
          result = pid;
          break;
        }
        result = -1;
      } else {
        return ECHILD;
      }
      if( (options & WNOHANG) != 0 ) return 0;
      Thread.yield();
      try {
        Thread.sleep( 5L );
      } catch( InterruptedException interrupted ) {
        Thread.currentThread().interrupt();
        return EINTR;
      }
      int signal = process.psig();
      if( signal != -1 && signal != Signal.SIGCHLD ) return EINTR;
    }

    if( statusAddress != 0 ) {
      int status = 0;
      ProcessInfo child = sysinfo.kernel.get_pinfo( result );
      if( child != null ) {
        status = child.term_sig != 0
            ? child.term_sig & 0x7f : (child.exit_code & 0xff) << 8;
      }
      mem.store32( statusAddress, status );
    }
    if( rusageAddress != 0 ) {
      for( int offset = 0; offset < 144; offset += 8 ) {
        mem.store64( rusageAddress + offset, 0 );
      }
    }
    if( TRACE_PROCESS ) {
      System.err.println( "[aarch64-hvf] pid=" + process.pid
          + " wait4 -> " + result );
    }
    return result;
  }

  long aarch64Exit( long code, boolean group ) {
    if( TRACE_PROCESS ) {
      System.err.println( "[aarch64-hvf] pid=" + process.pid
          + (group ? " exit_group " : " exit ") + code );
    }
    process.vfork_signal_parent();
    if( !group && Thread.currentThread() instanceof GuestThread ) {
      throw new GuestThreadExitException( (int)code );
    }
    if( !group && process.active_thread_count.get() > 0 ) {
      synchronized( process.active_thread_count ) {
        while( process.active_thread_count.get() > 0 ) {
          try {
            process.active_thread_count.wait( 100 );
          } catch( InterruptedException interrupted ) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }
    }
    return sys_exit( code, 0, 0, 0, 0 );
  }

  long aarch64Futex( long address, long operation, long value, long timeoutAddress,
                     long secondAddress, long value3 ) {
    int op = (int)operation & FutexManager.FUTEX_OP_MASK;
    int expected = (int)value;
    boolean shared = (operation & FutexManager.FUTEX_PRIVATE_FLAG) == 0
        && mem.isMapShared( address );
    if( op == FutexManager.FUTEX_CMP_REQUEUE ) {
      if( mem.load32( address ) != (int)value3 ) return EAGAIN;
      return FutexManager.requeue( address, expected, (int)timeoutAddress,
                                   secondAddress, mem, shared );
    }
    if( op == FutexManager.FUTEX_REQUEUE ) {
      return FutexManager.requeue( address, expected, (int)timeoutAddress,
                                   secondAddress, mem, shared );
    }
    if( op == FutexManager.FUTEX_WAIT || op == FutexManager.FUTEX_WAIT_BITSET ) {
      long timeoutMillis = -1;
      if( timeoutAddress != 0 ) {
        long seconds = mem.load64( timeoutAddress );
        long nanoseconds = mem.load64( timeoutAddress + 8 );
        if( seconds < 0 || nanoseconds < 0 || nanoseconds >= 1_000_000_000L ) return EINVAL;
        timeoutMillis = seconds * 1000L + nanoseconds / 1_000_000L;
        if( op == FutexManager.FUTEX_WAIT_BITSET ) {
          long now = (operation & FutexManager.FUTEX_CLOCK_REALTIME) != 0
              ? System.currentTimeMillis() : System.nanoTime() / 1_000_000L;
          timeoutMillis = Math.max( 0, timeoutMillis - now );
        }
      }
      int bitset = op == FutexManager.FUTEX_WAIT_BITSET
          ? (int)value3 : FutexManager.FUTEX_BITSET_MATCH_ANY;
      if( bitset == 0 ) return EINVAL;
      return FutexManager.wait( address, expected, timeoutMillis, mem,
          () -> process.psig_actionable() >= 0 || process.is_exited(), shared, bitset );
    }
    if( op == FutexManager.FUTEX_WAKE || op == FutexManager.FUTEX_WAKE_BITSET ) {
      int bitset = op == FutexManager.FUTEX_WAKE_BITSET
          ? (int)value3 : FutexManager.FUTEX_BITSET_MATCH_ANY;
      if( bitset == 0 ) return EINVAL;
      return FutexManager.wake( address, expected, mem, shared, bitset );
    }
    return ENOSYS;
  }

  long aarch64Prlimit64( long pid, long resourceValue, long newAddress,
                         long oldAddress ) {
    int resource = (int)resourceValue;
    if( pid != 0 && pid != process.pid ) return ESRCH;
    if( resource < 0 || resource >= 16 ) return EINVAL;
    if( oldAddress != 0 ) {
      long current = -1;
      long maximum = -1;
      if( resource == 3 ) current = 8L * 1024 * 1024;
      if( resource == 7 ) {
        current = rlim_nofile_cur;
        maximum = rlim_nofile_max;
      }
      Aarch64StructCodec.storeRlimit( mem, oldAddress, current, maximum );
    }
    if( newAddress != 0 && resource == 7 ) {
      long current = mem.load64( newAddress );
      long maximum = mem.load64( newAddress + 8 );
      if( current == -1 ) current = FileAccess.NR_OPEN_MAX;
      if( maximum == -1 ) maximum = FileAccess.NR_OPEN_MAX;
      if( current < 0 || maximum < 0 || current > maximum
          || maximum > FileAccess.NR_OPEN_MAX ) return EINVAL;
      int effectiveUid = process.euid >= 0 ? process.euid : process.uid;
      if( maximum > rlim_nofile_max && effectiveUid != 0 ) return EPERM;
      rlim_nofile_cur = current;
      rlim_nofile_max = maximum;
    }
    return 0;
  }

  long aarch64Getrandom( long address, long length, long flags ) {
    if( length < 0 ) return EINVAL;
    if( (flags & ~7L) != 0 ) return EINVAL;
    int count = (int)Math.min( length, GUEST_BUFFER_MAX );
    byte[] bytes = new byte[ count ];
    SyscallAmd64.fillRandom( bytes );
    mem.bulkStoreToMem( address, bytes, 0, count );
    return count;
  }

  long aarch64Socket( long domainValue, long typeValue, long protocolValue ) {
    int domain = (int)domainValue;
    int type = (int)typeValue;
    int socketType = type & 0xff;
    if( socketType != EmuSocket.SOCK_STREAM && socketType != EmuSocket.SOCK_DGRAM ) {
      return EINVAL;
    }
    if( domain != EmuSocket.AF_INET && domain != EmuSocket.AF_INET6
        && domain != EmuSocket.AF_UNIX ) return EAFNOSUPPORT;
    if( domain == EmuSocket.AF_UNIX && socketType == EmuSocket.SOCK_DGRAM ) {
      return EOPNOTSUPP;
    }
    int fd = socket( domain == EmuSocket.AF_INET6 ? EmuSocket.AF_INET : domain,
                     socketType, (int)protocolValue );
    if( System.getenv( "EMULIN_TRACE_NET" ) != null ) {
      System.err.println( "AARCH64-SOCKET domain=" + domain + " type=" + socketType
          + " protocol=" + protocolValue + " fd=" + fd );
    }
    if( fd < 0 ) return EAFNOSUPPORT;
    Fileinfo info = get_finfo( fd );
    if( info != null ) {
      info.family_v6 = domain == EmuSocket.AF_INET6;
      info.nonBlock = (type & 0x800) != 0;
    }
    if( (type & 0x80000) != 0 ) set_cloexec( fd, true );
    return fd;
  }

  long aarch64Socketpair( long domainValue, long typeValue,
                          long protocolValue, long arrayAddress ) {
    if( (int)domainValue != EmuSocket.AF_UNIX ) return EOPNOTSUPP;
    int socketType = (int)typeValue & 0xf;
    if( socketType != EmuSocket.SOCK_STREAM && socketType != EmuSocket.SOCK_DGRAM
        && socketType != EmuSocket.SOCK_SEQPACKET ) return EINVAL;
    int fd0 = FileOpen( "<pipe>", "r", O_RDWR );
    if( fd0 < 0 ) return fd0;
    int fd1 = FileOpen( "<pipe>", "r", O_RDWR );
    if( fd1 < 0 ) {
      FileClose( fd0 );
      return fd1;
    }
    int pipeA = sysinfo.kernel.connect_pipe();
    int pipeB = sysinfo.kernel.connect_pipe();
    Fileinfo file0 = get_finfo( fd0 );
    Fileinfo file1 = get_finfo( fd1 );
    file0.set_pipe_pair( pipeB, pipeA );
    file1.set_pipe_pair( pipeA, pipeB );
    if( socketType != EmuSocket.SOCK_STREAM ) {
      Pipeinfo a = sysinfo.kernel.pipe_at( pipeA );
      Pipeinfo b = sysinfo.kernel.pipe_at( pipeB );
      if( a != null ) a.setDatagramMode();
      if( b != null ) b.setDatagramMode();
    }
    if( (((int)typeValue) & 0x800) != 0 ) {
      file0.nonBlock = true;
      file1.nonBlock = true;
    }
    if( (((int)typeValue) & 0x80000) != 0 ) {
      set_cloexec( fd0, true );
      set_cloexec( fd1, true );
    }
    mem.store32( arrayAddress, fd0 );
    mem.store32( arrayAddress + 4, fd1 );
    return 0;
  }

  long aarch64Connect( long fdValue, long address, long length ) {
    Fileinfo info = get_finfo( (int)fdValue );
    if( info == null ) return EBADF;
    if( !info.isSOCKET() ) return ENOTSOCK;
    if( address == 0 || length < 2 ) return EINVAL;
    int family = mem.load16( address ) & 0xffff;
    int port = networkPort( address + 2 );
    if( family == EmuSocket.AF_INET && length >= 16 ) {
      int ip = Util.swap32( mem.load32( address + 4 ) );
      if( !info.isSTREAM() ) {
        info.set_ip_address( ip );
        info.set_port( port );
        return 0;
      }
      if( info.conn != null ) return -106; // EISCONN
      if( !HostLoopbackPolicy.allowConnect( ip, port ) ) return ECONNREFUSED;
      if( !info.client_socket( ip, port ) ) return ECONNREFUSED;
      if( info.nonBlock ) {
        info.connectPending = true;
        return -115; // EINPROGRESS
      }
      return 0;
    }
    if( family == EmuSocket.AF_INET6 && length >= 28 ) {
      byte[] ip = new byte[16];
      mem.bulkLoadFromMem( address + 8, ip, 0, ip.length );
      if( !info.isSTREAM() ) {
        info.connected_v6_addr = ip;
        info.connected_v6_port = port;
        return 0;
      }
      if( info.conn != null ) return -106;
      if( !info.client_socket_v6( ip, port ) ) return -101; // ENETUNREACH
      if( info.nonBlock ) {
        info.connectPending = true;
        return -115;
      }
      return 0;
    }
    return EAFNOSUPPORT;
  }

  long aarch64Bind( long fdValue, long address, long length ) {
    Fileinfo info = get_finfo( (int)fdValue );
    if( info == null ) return EBADF;
    if( !info.isSOCKET() ) return ENOTSOCK;
    if( address == 0 || length < 16 ) return EINVAL;
    int family = mem.load16( address ) & 0xffff;
    if( family != EmuSocket.AF_INET ) return EAFNOSUPPORT;
    int port = networkPort( address + 2 );
    int ip = Util.swap32( mem.load32( address + 4 ) );
    return bind( (int)fdValue, ip, port ) ? 0 : -98; // EADDRINUSE
  }

  long aarch64Listen( long fdValue, long backlogValue ) {
    Fileinfo info = get_finfo( (int)fdValue );
    if( info == null ) return EBADF;
    if( !info.isSOCKET() ) return ENOTSOCK;
    if( !info.isSTREAM() ) return EOPNOTSUPP;
    return listen( (int)fdValue, Math.max( 0, (int)backlogValue ) ) ? 0 : EINVAL;
  }

  long aarch64Accept4( long fdValue, long address, long lengthAddress,
                       long flagsValue ) {
    int flags = (int)flagsValue;
    if( (flags & ~(0x800 | 0x80000)) != 0 ) return EINVAL;
    Fileinfo listener = get_finfo( (int)fdValue );
    if( listener == null ) return EBADF;
    if( !listener.isSOCKET() || !listener.isSTREAM() ) return ENOTSOCK;
    int accepted = accept( (int)fdValue );
    if( accepted < 0 ) return listener.nonBlock ? EAGAIN : ECONNRESET;
    Fileinfo info = get_finfo( accepted );
    if( info != null ) info.nonBlock = (flags & 0x800) != 0;
    if( (flags & 0x80000) != 0 ) set_cloexec( accepted, true );
    if( address != 0 ) {
      storeIpv4Address( address, get_partner_port( accepted ),
                        get_partner_ip_address( accepted ) );
      if( lengthAddress != 0 ) mem.store32( lengthAddress, 16 );
    }
    return accepted;
  }

  long aarch64Sendto( long fdValue, long bufferAddress, long lengthValue,
                      long flagsValue, long destinationAddress,
                      long addressLength ) {
    int length = checkedGuestLength( lengthValue );
    if( length < 0 ) return EINVAL;
    Fileinfo info = get_finfo( (int)fdValue );
    if( info == null ) return EBADF;
    if( !info.isSOCKET() && !(info.is_pipe( true ) && info.pipe_write_no >= 0) ) {
      return ENOTSOCK;
    }
    byte[] bytes = new byte[length];
    mem.bulkLoadFromMem( bufferAddress, bytes, 0, length );
    boolean written;
    if( destinationAddress != 0 ) {
      int family = mem.load16( destinationAddress ) & 0xffff;
      int port = networkPort( destinationAddress + 2 );
      if( family == EmuSocket.AF_INET && addressLength >= 16 ) {
        int ip = Util.swap32( mem.load32( destinationAddress + 4 ) );
        written = sendto( (int)fdValue, bytes, (int)flagsValue, ip, port );
      } else if( family == EmuSocket.AF_INET6 && addressLength >= 28 ) {
        byte[] ip = new byte[16];
        mem.bulkLoadFromMem( destinationAddress + 8, ip, 0, ip.length );
        written = info.sendto_v6( bytes, ip, port );
      } else {
        return EAFNOSUPPORT;
      }
    } else {
      written = FileWrite( (int)fdValue, bytes );
    }
    return written ? length : EPIPE;
  }

  long aarch64Recvfrom( long fdValue, long bufferAddress, long lengthValue,
                        long flagsValue, long sourceAddress,
                        long addressLengthAddress ) {
    int length = checkedGuestLength( lengthValue );
    if( length < 0 ) return EINVAL;
    Fileinfo info = get_finfo( (int)fdValue );
    if( info == null ) return EBADF;
    if( !info.isSOCKET() && !(info.is_pipe( true ) && info.pipe_write_no >= 0) ) {
      return ENOTSOCK;
    }
    byte[] bytes = new byte[length];
    int result;
    if( info.is_pipe( true ) && info.pipe_write_no >= 0 ) {
      result = sysinfo.kernel.pipe_read( info.pipe_no, bytes,
          info.nonBlock || (((int)flagsValue & 0x40) != 0) );
      if( result == -2 ) return EAGAIN;
      if( sourceAddress != 0 && addressLengthAddress != 0 ) {
        mem.store32( addressLengthAddress, 0 );
      }
    } else if( info.isSTREAM() ) {
      result = (((int)flagsValue & 2) != 0) ? info.Peek( bytes ) : info.Read( bytes );
      if( result == -2 ) return EAGAIN;
    } else if( info.family_v6 ) {
      byte[] ip = new byte[16];
      int[] port = new int[1];
      result = info.recvfrom_v6( bytes, ip, port,
          info.nonBlock || (((int)flagsValue & 0x40) != 0) );
      if( result == -2 ) return EAGAIN;
      if( result >= 0 && sourceAddress != 0 ) {
        storeIpv6Address( sourceAddress, port[0], ip );
        if( addressLengthAddress != 0 ) mem.store32( addressLengthAddress, 28 );
      }
    } else {
      int[] peer = new int[2];
      result = info.recvfrom( bytes, peer,
          info.nonBlock || (((int)flagsValue & 0x40) != 0) );
      if( result == -2 ) return EAGAIN;
      if( result >= 0 && sourceAddress != 0 ) {
        storeIpv4Address( sourceAddress, peer[1], peer[0] );
        if( addressLengthAddress != 0 ) mem.store32( addressLengthAddress, 16 );
      }
    }
    if( result < 0 ) return ECONNRESET;
    if( result > 0 ) mem.bulkStoreToMem( bufferAddress, bytes, 0, result );
    return result;
  }

  long aarch64Sendmsg( long fd, long messageAddress, long flags ) {
    long name = mem.load64( messageAddress );
    long nameLength = mem.load32( messageAddress + 8 ) & 0xffffffffL;
    long vectors = mem.load64( messageAddress + 16 );
    long vectorCount = mem.load64( messageAddress + 24 );
    if( vectorCount < 0 || vectorCount > 1024 ) return EINVAL;
    long total = 0;
    for( int i = 0; i < (int)vectorCount; i++ ) {
      long partLength = mem.load64( vectors + i * 16L + 8 );
      if( partLength < 0 || total + partLength > GUEST_BUFFER_MAX ) return EINVAL;
      total += partLength;
    }
    byte[] joined = new byte[(int)total];
    int offset = 0;
    for( int i = 0; i < (int)vectorCount; i++ ) {
      long base = mem.load64( vectors + i * 16L );
      int partLength = (int)mem.load64( vectors + i * 16L + 8 );
      mem.bulkLoadFromMem( base, joined, offset, partLength );
      offset += partLength;
    }
    long scratch = 0;
    if( joined.length > 0 ) {
      scratch = mem.alloc( 0, joined.length );
      mem.bulkStoreToMem( scratch, joined, 0, joined.length );
    }
    return aarch64Sendto( fd, scratch, joined.length, flags, name, nameLength );
  }

  long aarch64Recvmsg( long fd, long messageAddress, long flags ) {
    long name = mem.load64( messageAddress );
    long vectors = mem.load64( messageAddress + 16 );
    long vectorCount = mem.load64( messageAddress + 24 );
    if( vectorCount < 0 || vectorCount > 1024 ) return EINVAL;
    long capacity = 0;
    for( int i = 0; i < (int)vectorCount; i++ ) {
      long partLength = mem.load64( vectors + i * 16L + 8 );
      if( partLength < 0 || capacity + partLength > GUEST_BUFFER_MAX ) return EINVAL;
      capacity += partLength;
    }
    long scratch = mem.alloc( 0, Math.max( 1, (int)capacity ) );
    long lengthAddress = mem.alloc( 0, 4 );
    mem.store32( lengthAddress, mem.load32( messageAddress + 8 ) );
    long result = aarch64Recvfrom( fd, scratch, capacity, flags, name, lengthAddress );
    if( result < 0 ) return result;
    byte[] bytes = new byte[(int)result];
    if( result > 0 ) mem.bulkLoadFromMem( scratch, bytes, 0, (int)result );
    int offset = 0;
    for( int i = 0; i < (int)vectorCount && offset < result; i++ ) {
      long base = mem.load64( vectors + i * 16L );
      int partLength = (int)Math.min( mem.load64( vectors + i * 16L + 8 ), result - offset );
      mem.bulkStoreToMem( base, bytes, offset, partLength );
      offset += partLength;
    }
    mem.store32( messageAddress + 8, mem.load32( lengthAddress ) );
    mem.store64( messageAddress + 40, 0 );
    mem.store32( messageAddress + 48, 0 );
    return result;
  }

  long aarch64Sendmmsg( long fd, long messages, long countValue, long flags ) {
    if( countValue < 0 || countValue > 1024 ) return EINVAL;
    int completed = 0;
    for( int i = 0; i < (int)countValue; i++ ) {
      long entry = messages + i * 64L;
      long result = aarch64Sendmsg( fd, entry, flags );
      if( result < 0 ) return completed == 0 ? result : completed;
      mem.store32( entry + 56, (int)result );
      completed++;
    }
    return completed;
  }

  long aarch64Recvmmsg( long fd, long messages, long countValue,
                        long flags, long timeoutAddress ) {
    if( countValue < 0 || countValue > 1024 ) return EINVAL;
    int completed = 0;
    for( int i = 0; i < (int)countValue; i++ ) {
      long entry = messages + i * 64L;
      long result = aarch64Recvmsg( fd, entry, flags | (completed == 0 ? 0 : 0x40) );
      if( result < 0 ) {
        if( result == EAGAIN && completed > 0 ) break;
        return completed == 0 ? result : completed;
      }
      mem.store32( entry + 56, (int)result );
      completed++;
    }
    return completed;
  }

  long aarch64Setsockopt( long fdValue, long levelValue, long optionValue,
                          long valueAddress, long valueLength ) {
    Fileinfo info = get_finfo( (int)fdValue );
    if( info == null ) return EBADF;
    if( !socketOrPair( info ) ) return ENOTSOCK;
    int level = (int)levelValue;
    if( level != 0 && level != 1 && level != 6 && level != 17 && level != 41 ) {
      return ENOPROTOOPT;
    }
    int value = valueAddress == 0 || valueLength < 4 ? 0 : mem.load32( valueAddress );
    if( level == 1 && (int)optionValue == 2 ) info.so_reuseaddr = value != 0;
    if( level == 6 && (int)optionValue == 1 && info.conn != null ) {
      try { info.conn.setTcpNoDelay( value != 0 ); } catch( Exception ignored ) {}
    }
    return 0;
  }

  long aarch64Getsockopt( long fdValue, long levelValue, long optionValue,
                          long valueAddress, long lengthAddress ) {
    Fileinfo info = get_finfo( (int)fdValue );
    if( info == null ) return EBADF;
    if( !socketOrPair( info ) ) return ENOTSOCK;
    int level = (int)levelValue;
    int option = (int)optionValue;
    if( level == 0 && option == 4 ) {
      if( lengthAddress != 0 ) mem.store32( lengthAddress, 0 );
      return 0;
    }
    int value = 0;
    if( level == 1 && option == 2 ) value = info.so_reuseaddr ? 1 : 0;
    if( level == 1 && option == 3 ) {
      value = info.is_pipe( true ) || info.isSTREAM()
          ? EmuSocket.SOCK_STREAM : EmuSocket.SOCK_DGRAM;
    }
    if( level == 1 && option == 4 ) info.noteConnectObserved();
    if( valueAddress != 0 ) mem.store32( valueAddress, value );
    if( lengthAddress != 0 ) mem.store32( lengthAddress, 4 );
    return 0;
  }

  long aarch64Getsockname( long fdValue, long address, long lengthAddress ) {
    Fileinfo info = get_finfo( (int)fdValue );
    if( info == null ) return EBADF;
    if( !socketOrPair( info ) ) return ENOTSOCK;
    if( info.is_pipe( true ) ) {
      mem.store16( address, (short)EmuSocket.AF_UNIX );
      if( lengthAddress != 0 ) mem.store32( lengthAddress, 2 );
      return 0;
    }
    if( info.family_v6 ) {
      byte[] local = new byte[16];
      // glibc getaddrinfo source-address sorting connects an AF_INET6 UDP
      // socket to IPv4-mapped destinations and requires getsockname() to
      // return an IPv4-mapped local address as a real dual-stack kernel does.
      if( isIpv4Mapped( info.connected_v6_addr )
          || (info.dgram != null && !info.isSTREAM()) ) {
        local[10] = (byte)0xff;
        local[11] = (byte)0xff;
      }
      storeIpv6Address( address, info.get_local_port(), local );
      if( lengthAddress != 0 ) mem.store32( lengthAddress, 28 );
    } else {
      storeIpv4Address( address, info.get_local_port(), info.get_ip_address() );
      if( lengthAddress != 0 ) mem.store32( lengthAddress, 16 );
    }
    return 0;
  }

  long aarch64Getpeername( long fdValue, long address, long lengthAddress ) {
    Fileinfo info = get_finfo( (int)fdValue );
    if( info == null ) return EBADF;
    if( !socketOrPair( info ) ) return ENOTSOCK;
    if( info.is_pipe( true ) ) {
      mem.store16( address, (short)EmuSocket.AF_UNIX );
      if( lengthAddress != 0 ) mem.store32( lengthAddress, 2 );
      return 0;
    }
    if( info.conn == null ) return ENOTCONN;
    storeIpv4Address( address, info.get_partner_port(), info.get_partner_ip_address() );
    if( lengthAddress != 0 ) mem.store32( lengthAddress, 16 );
    return 0;
  }

  long aarch64Shutdown( long fdValue, long howValue ) {
    Fileinfo info = get_finfo( (int)fdValue );
    if( info == null ) return EBADF;
    if( !socketOrPair( info ) ) return ENOTSOCK;
    int how = (int)howValue;
    if( how < 0 || how > 2 ) return EINVAL;
    if( info.conn == null ) return info.is_pipe( true ) ? 0 : ENOTCONN;
    try {
      if( how == 0 || how == 2 ) info.conn.shutdownInput();
      if( how == 1 || how == 2 ) info.conn.shutdownOutput();
    } catch( Exception ignored ) {}
    return 0;
  }

  long aarch64Ppoll( long descriptors, long countValue, long timeoutAddress,
                     long signalMaskAddress, long signalSetSize ) {
    if( countValue < 0 || countValue > 1024 ) return EINVAL;
    if( signalMaskAddress != 0 && signalSetSize != 8 ) return EINVAL;
    long deadline = Long.MAX_VALUE;
    if( timeoutAddress != 0 ) {
      long seconds = mem.load64( timeoutAddress );
      long nanoseconds = mem.load64( timeoutAddress + 8 );
      if( seconds < 0 || nanoseconds < 0 || nanoseconds >= 1_000_000_000L ) return EINVAL;
      long duration;
      try {
        duration = Math.addExact( Math.multiplyExact( seconds, 1_000_000_000L ), nanoseconds );
        deadline = Math.addExact( System.nanoTime(), duration );
      } catch( ArithmeticException overflow ) {
        deadline = Long.MAX_VALUE;
      }
    }
    while( true ) {
      int ready = 0;
      for( int i = 0; i < (int)countValue; i++ ) {
        long entry = descriptors + i * 8L;
        int fd = mem.load32( entry );
        int events = mem.load16( entry + 4 ) & 0xffff;
        int returned = 0;
        Fileinfo info = fd < 0 ? null : get_finfo( fd );
        if( fd >= 0 && info == null ) returned = 0x20; // POLLNVAL
        else if( info != null ) {
          if( (events & 0x43) != 0 && aarch64ReadReady( info ) ) returned |= events & 0x43;
          if( (events & 0x104) != 0 && aarch64WriteReady( info ) ) returned |= events & 0x104;
          if( info.socketEof ) returned |= 0x10; // POLLHUP
        }
        mem.store16( entry + 6, (short)returned );
        if( returned != 0 ) ready++;
      }
      if( ready > 0 || System.nanoTime() >= deadline ) return ready;
      if( process.psig_actionable() >= 0 ) return EINTR;
      try { Thread.sleep( 1 ); }
      catch( InterruptedException interrupted ) {
        Thread.currentThread().interrupt();
        return EINTR;
      }
    }
  }

  private int checkedGuestLength( long length ) {
    return length < 0 ? -1 : (int)Math.min( length, GUEST_BUFFER_MAX );
  }

  private static boolean socketOrPair( Fileinfo info ) {
    return info.isSOCKET() || (info.is_pipe( true ) && info.pipe_write_no >= 0);
  }

  private static int networkPort( long address, MemoryBackend memory ) {
    int network = memory.load16( address ) & 0xffff;
    return ((network & 0xff) << 8) | ((network >>> 8) & 0xff);
  }

  private int networkPort( long address ) {
    return networkPort( address, mem );
  }

  private void storeIpv4Address( long address, int port, int ip ) {
    mem.store16( address, (short)EmuSocket.AF_INET );
    mem.store16( address + 2, (short)(((port & 0xff) << 8) | ((port >>> 8) & 0xff)) );
    mem.store32( address + 4, Util.swap32( ip ) );
    mem.store64( address + 8, 0 );
  }

  private void storeIpv6Address( long address, int port, byte[] ip ) {
    mem.store16( address, (short)EmuSocket.AF_INET6 );
    mem.store16( address + 2, (short)(((port & 0xff) << 8) | ((port >>> 8) & 0xff)) );
    mem.store32( address + 4, 0 );
    mem.bulkStoreToMem( address + 8, ip, 0, 16 );
    mem.store32( address + 24, 0 );
  }

  private static boolean isIpv4Mapped( byte[] address ) {
    if( address == null || address.length != 16 ) return false;
    for( int index = 0; index < 10; index++ ) {
      if( address[index] != 0 ) return false;
    }
    return address[10] == (byte)0xff && address[11] == (byte)0xff;
  }

  private String resolveAt( int dirfd, String path ) {
    if( path.startsWith( "/" ) ) return path;
    if( dirfd == AT_FDCWD ) return sysinfo.get_full_path( process.get_curdir(), path );
    Fileinfo directory = get_finfo( dirfd );
    if( directory == null ) return null;
    String base = get_name( dirfd );
    if( base == null || "<noname>".equals( base ) ) return null;
    return sysinfo.get_full_path( base, path );
  }

  private long validateAtPath( int dirfd, String path ) {
    if( path == null ) return EFAULT;
    if( path.isEmpty() ) return ENOENT;
    if( dirfd == AT_FDCWD || path.startsWith( "/" ) ) return 0;
    Fileinfo directory = get_finfo( dirfd );
    if( directory == null ) return EBADF;
    String base = get_name( dirfd );
    if( base == null || base.startsWith( "<" ) || directory.f != null ) return ENOTDIR;
    Inode inode = inode( base );
    return inode.isExists() && inode.isDirectory() ? 0 : ENOTDIR;
  }
}
