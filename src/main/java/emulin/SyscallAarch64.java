// ----------------------------------------
//  Linux AArch64 syscall dispatcher and 64-bit ABI adapters (issue #951)
// ----------------------------------------
package emulin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class SyscallAarch64 extends Syscall {
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

  long aarch64Ioctl( long fdValue, long requestValue, long address ) {
    int fd = (int)fdValue;
    int request = (int)requestValue;
    if( request != TIOCGPGRP && request != TIOCSPGRP ) {
      return sys_ioctl( fdValue, requestValue, address, 0, 0 );
    }
    Fileinfo finfo = get_finfo( fd );
    if( finfo == null ) return EBADF;
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
    return open_resolved( resolved, translateOpenFlags( (int)flags ) );
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
    if( (flags & 0x10100L) == 0x10100L ) { // CLONE_VM | CLONE_THREAD
      if( Memory.FORCE_ST ) return EAGAIN;
      Thread current = Thread.currentThread();
      GuestCpu parent = current instanceof GuestThread guest
          ? guest.guestCpu() : process.cpu;
      return parent.spawnVcpu( flags, childStack, parentTid, childTid, tls );
    }
    if( (flags & 0x4100L) == 0x4100L ) { // CLONE_VM | CLONE_VFORK
      return sysinfo.kernel.vfork( process, childStack );
    }
    return sysinfo.kernel.fork( process, childStack, flags );
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
    return result;
  }

  long aarch64Exit( long code, boolean group ) {
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
