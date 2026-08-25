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
