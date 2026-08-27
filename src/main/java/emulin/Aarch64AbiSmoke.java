// ----------------------------------------
//  AArch64 syscall-number and structure-layout smoke (issue #951)
// ----------------------------------------
package emulin;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public final class Aarch64AbiSmoke {
  public static void main( String[] args ) {
    require( Aarch64SyscallTable.SYS_READ == 63, "read syscall number" );
    require( Aarch64SyscallTable.SYS_WRITE == 64, "write syscall number" );
    require( Aarch64SyscallTable.SYS_GETDENTS64 == 61, "getdents64 syscall number" );
    require( Aarch64SyscallTable.SYS_MKDIRAT == 34, "mkdirat syscall number" );
    require( Aarch64SyscallTable.SYS_UNLINKAT == 35, "unlinkat syscall number" );
    require( Aarch64SyscallTable.SYS_SYMLINKAT == 36, "symlinkat syscall number" );
    require( Aarch64SyscallTable.SYS_LINKAT == 37, "linkat syscall number" );
    require( Aarch64SyscallTable.SYS_RENAMEAT == 38, "renameat syscall number" );
    require( Aarch64SyscallTable.SYS_FACCESSAT == 48, "faccessat syscall number" );
    require( Aarch64SyscallTable.SYS_FCHMODAT == 53, "fchmodat syscall number" );
    require( Aarch64SyscallTable.SYS_FCHOWNAT == 54, "fchownat syscall number" );
    require( Aarch64SyscallTable.SYS_STATFS == 43, "statfs syscall number" );
    require( Aarch64SyscallTable.SYS_FSTATFS == 44, "fstatfs syscall number" );
    require( Aarch64SyscallTable.SYS_TRUNCATE == 45, "truncate syscall number" );
    require( Aarch64SyscallTable.SYS_UTIMENSAT == 88, "utimensat syscall number" );
    require( Aarch64SyscallTable.SYS_FACCESSAT2 == 439, "faccessat2 syscall number" );
    require( Aarch64SyscallTable.SYS_RENAMEAT2 == 276, "renameat2 syscall number" );
    require( Aarch64SyscallTable.SYS_FCHMODAT2 == 452, "fchmodat2 syscall number" );
    require( Aarch64SyscallTable.SYS_CLONE == 220, "clone syscall number" );
    require( Aarch64SyscallTable.SYS_MMAP == 222, "mmap syscall number" );
    require( Aarch64SyscallTable.SYS_PRLIMIT64 == 261, "prlimit64 syscall number" );

    Image image = new Image();
    MemoryBackend memory = image.backend();
    Aarch64StructCodec.storeTimespec( memory, 0x1000, 12, 34 );
    require( image.read( 0x1000, 8 ) == 12 && image.read( 0x1008, 8 ) == 34,
        "timespec layout" );

    Aarch64StructCodec.storeRlimit( memory, 0x1100, 1024, 4096 );
    require( image.read( 0x1100, 8 ) == 1024 && image.read( 0x1108, 8 ) == 4096,
        "rlimit layout" );

    Aarch64StructCodec.storeSpecialStat(
        memory, 0x1200, 0020000 | 0666, 0x400, 123 );
    require( image.read( 0x1210, 4 ) == (0020000 | 0666), "stat st_mode offset" );
    require( image.read( 0x1220, 8 ) == 0x400, "stat st_rdev offset" );
    require( image.read( 0x1230, 8 ) == 123, "stat st_size offset" );
    require( image.read( 0x1240, 8 ) == 1, "stat st_blocks offset" );

    Aarch64StructCodec.storeStatfs(
        memory, 0x1300, 0xef53, 4096, 100, 40, 30, 20, 10, 255, 4096, 1 );
    require( image.read( 0x1300, 8 ) == 0xef53, "statfs f_type offset" );
    require( image.read( 0x1308, 8 ) == 4096, "statfs f_bsize offset" );
    require( image.read( 0x1310, 8 ) == 100, "statfs f_blocks offset" );
    require( image.read( 0x1340, 8 ) == 255, "statfs f_namelen offset" );
    require( image.read( 0x1350, 8 ) == 1, "statfs f_flags offset" );
    System.out.println( "AArch64 ABI layout smoke OK" );
  }

  private static void require( boolean condition, String message ) {
    if( !condition ) throw new AssertionError( message );
  }

  private static final class Image {
    private final Map<Long,Byte> bytes = new HashMap<>();

    MemoryBackend backend() {
      return (MemoryBackend)Proxy.newProxyInstance(
          MemoryBackend.class.getClassLoader(), new Class<?>[]{ MemoryBackend.class },
          (proxy, method, args) -> switch( method.getName() ) {
            case "load8" -> (byte)read( (long)args[0], 1 );
            case "load16" -> (short)read( (long)args[0], 2 );
            case "load32" -> (int)read( (long)args[0], 4 );
            case "load64" -> read( (long)args[0], 8 );
            case "store8" -> { write( (long)args[0], (int)args[1], 1 ); yield true; }
            case "store16" -> { write( (long)args[0], (short)args[1], 2 ); yield null; }
            case "store32" -> { write( (long)args[0], (int)args[1], 4 ); yield null; }
            case "store64" -> { write( (long)args[0], (long)args[1], 8 ); yield null; }
            case "bulkZero" -> {
              for( int i = 0; i < (int)args[1]; i++ ) bytes.put( (long)args[0] + i, (byte)0 );
              yield null;
            }
            case "toString" -> "AArch64AbiSmoke.Image";
            default -> throw new UnsupportedOperationException( method.getName() );
          } );
    }

    long read( long address, int size ) {
      long value = 0;
      for( int i = 0; i < size; i++ ) {
        value |= (long)(bytes.getOrDefault( address + i, (byte)0 ) & 0xff) << (i * 8);
      }
      return value;
    }

    void write( long address, long value, int size ) {
      for( int i = 0; i < size; i++ ) bytes.put( address + i, (byte)(value >>> (i * 8)) );
    }
  }
}
