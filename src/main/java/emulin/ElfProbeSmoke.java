// ----------------------------------------
//  ElfProbe regression smoke (issue #951 Phase 0)
// ----------------------------------------
package emulin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class ElfProbeSmoke {
  private ElfProbeSmoke() {}

  public static void main( String[] args ) throws Exception {
    checkIdentity( Elf.ELFCLASS32, Elf.EM_386, GuestArch.I386 );
    checkIdentity( Elf.ELFCLASS64, Elf.EM_X86_64, GuestArch.X86_64 );
    checkIdentity( Elf.ELFCLASS64, Elf.EM_AARCH64, GuestArch.AARCH64 );
    checkRejected( Elf.ELFCLASS32, Elf.EM_AARCH64, Elf.ELFDATA2LSB );
    checkRejected( Elf.ELFCLASS64, Elf.EM_X86_64, 2 );
    checkTruncated();
    checkFactorySelection();
    checkAarch64Decoder();
    if( args.length == 1 ) writeExecutionFixtures( Path.of( args[0] ) );
    System.out.println( "ElfProbe smoke OK" );
  }

  private static void checkIdentity( int elfClass, int machine, GuestArch expected )
      throws Exception {
    Path file = writeHeader( elfClass, machine, Elf.ELFDATA2LSB );
    try {
      ElfIdentity identity = ElfProbe.probe( file.toString() );
      require( identity.elfClass() == elfClass, "ELF class mismatch" );
      require( identity.machine() == machine, "ELF machine mismatch" );
      require( identity.arch() == expected, "guest architecture mismatch" );
    } finally {
      Files.deleteIfExists( file );
    }
  }

  private static void checkRejected( int elfClass, int machine, int data ) throws Exception {
    Path file = writeHeader( elfClass, machine, data );
    try {
      try {
        ElfProbe.probe( file.toString() );
        throw new AssertionError( "invalid ELF identity was accepted" );
      } catch( IOException expected ) {
        // expected
      }
    } finally {
      Files.deleteIfExists( file );
    }
  }

  private static void checkTruncated() throws Exception {
    Path file = Files.createTempFile( "emulin-elf-probe-truncated-", ".bin" );
    try {
      Files.write( file, new byte[]{ 0x7f, 'E', 'L', 'F' } );
      try {
        ElfProbe.probe( file.toString() );
        throw new AssertionError( "truncated ELF identity was accepted" );
      } catch( IOException expected ) {
        // expected
      }
    } finally {
      Files.deleteIfExists( file );
    }
  }

  private static Path writeHeader( int elfClass, int machine, int data ) throws IOException {
    byte[] header = new byte[20];
    header[0] = 0x7f;
    header[1] = 'E';
    header[2] = 'L';
    header[3] = 'F';
    header[ Elf.EI_CLASS ] = (byte)elfClass;
    header[ Elf.EI_DATA ] = (byte)data;
    header[6] = 1; // EV_CURRENT
    header[18] = (byte)machine;
    header[19] = (byte)(machine >>> 8);
    Path file = Files.createTempFile( "emulin-elf-probe-", ".bin" );
    Files.write( file, header );
    return file;
  }

  private static void require( boolean condition, String message ) {
    if( !condition ) throw new AssertionError( message );
  }

  private static void writeExecutionFixtures( Path directory ) throws IOException {
    Files.createDirectories( directory );
    Files.write( directory.resolve( "hello-i386" ), minimalI386Elf() );
    Files.write( directory.resolve( "hello-x86_64" ), minimalX8664Elf() );
    Files.write( directory.resolve( "hello-aarch64" ), minimalAarch64Elf() );
  }

  private static void checkAarch64Decoder() {
    require( Aarch64DecodeSmoke.runBuiltIn() == 5, "AArch64 decoder vectors" );

    Aarch64Cpu cpu = new Aarch64Cpu( null, null );
    cpu.setPc( 0x1000 );
    cpu.advancePastSyscall();
    require( cpu.getPc() == 0x1004, "AArch64 syscall instruction width" );
  }

  private static void checkFactorySelection() {
    require( GuestFactory.abiFor( GuestArch.I386 ).arch() == GuestArch.I386,
        "i386 ABI selection" );
    require( GuestFactory.abiFor( GuestArch.X86_64 ).arch() == GuestArch.X86_64,
        "x86-64 ABI selection" );
    require( GuestFactory.abiFor( GuestArch.AARCH64 ).arch() == GuestArch.AARCH64,
        "AArch64 ABI selection" );
    require( GuestRunner.forArch( GuestArch.I386 ) == LegacyI386Runner.INSTANCE,
        "i386 runner selection" );
    require( GuestRunner.forArch( GuestArch.AARCH64 ) == SelfContainedRunner.INSTANCE,
        "AArch64 runner selection" );
  }

  private static byte[] minimalAarch64Elf() {
    byte[] message = "aarch64\n".getBytes( StandardCharsets.US_ASCII );
    int[] code = new int[]{
      0xd2800020, // movz x0, #1 (stdout)
      0x100000e1, // adr  x1, +28 (message)
      0xd2800102, // movz x2, #8 (length)
      0xd2800808, // movz x8, #64 (SYS_write)
      0xd4000001, // svc  #0
      0xd2800000, // movz x0, #0 (status)
      0xd2800ba8, // movz x8, #93 (SYS_exit)
      0xd4000001  // svc  #0
    };
    int codeOffset = 64 + 56;
    int size = codeOffset + code.length * 4 + message.length;
    ByteBuffer out = ByteBuffer.allocate( size ).order( ByteOrder.LITTLE_ENDIAN );
    putIdent( out, Elf.ELFCLASS64 );
    out.putShort( (short)2 ).putShort( Elf.EM_AARCH64 ).putInt( 1 );
    out.putLong( 0x400000L + codeOffset ).putLong( 64 ).putLong( 0 );
    out.putInt( 0 ).putShort( (short)64 ).putShort( (short)56 ).putShort( (short)1 );
    out.putShort( (short)64 ).putShort( (short)0 ).putShort( (short)0 );
    out.putInt( 1 ).putInt( 5 ).putLong( 0 ).putLong( 0x400000L ).putLong( 0 );
    out.putLong( size ).putLong( size ).putLong( 0x1000 );
    for( int instruction : code ) out.putInt( instruction );
    out.put( message );
    return out.array();
  }

  private static byte[] minimalX8664Elf() {
    byte[] message = "x86_64\n".getBytes( StandardCharsets.US_ASCII );
    byte[] code = new byte[]{
      (byte)0xb8, 1, 0, 0, 0,                  // mov eax, SYS_write
      (byte)0xbf, 1, 0, 0, 0,                  // mov edi, stdout
      0x48, (byte)0x8d, 0x35, 0x10, 0, 0, 0,  // lea rsi, [rip + 16]
      (byte)0xba, (byte)message.length, 0, 0, 0,
      0x0f, 0x05,                              // syscall
      (byte)0xb8, 60, 0, 0, 0,                 // mov eax, SYS_exit
      0x31, (byte)0xff,                         // xor edi, edi
      0x0f, 0x05                               // syscall
    };
    int codeOffset = 64 + 56;
    int size = codeOffset + code.length + message.length;
    ByteBuffer out = ByteBuffer.allocate( size ).order( ByteOrder.LITTLE_ENDIAN );
    putIdent( out, Elf.ELFCLASS64 );
    out.putShort( (short)2 ).putShort( Elf.EM_X86_64 ).putInt( 1 );
    out.putLong( 0x400000L + codeOffset ).putLong( 64 ).putLong( 0 );
    out.putInt( 0 ).putShort( (short)64 ).putShort( (short)56 ).putShort( (short)1 );
    out.putShort( (short)64 ).putShort( (short)0 ).putShort( (short)0 );
    out.putInt( 1 ).putInt( 5 ).putLong( 0 ).putLong( 0x400000L ).putLong( 0 );
    out.putLong( size ).putLong( size ).putLong( 0x1000 );
    out.put( code ).put( message );
    return out.array();
  }

  private static byte[] minimalI386Elf() {
    byte[] message = "i386\n".getBytes( StandardCharsets.US_ASCII );
    int codeOffset = 52 + 32;
    int messageAddress = 0x08048000 + codeOffset + 31;
    byte[] code = new byte[]{
      (byte)0xb8, 4, 0, 0, 0,                   // mov eax, SYS_write
      (byte)0xbb, 1, 0, 0, 0,                   // mov ebx, stdout
      (byte)0xb9, (byte)messageAddress, (byte)(messageAddress >>> 8),
          (byte)(messageAddress >>> 16), (byte)(messageAddress >>> 24),
      (byte)0xba, (byte)message.length, 0, 0, 0,
      (byte)0xcd, (byte)0x80,                    // int 0x80
      (byte)0xb8, 1, 0, 0, 0,                   // mov eax, SYS_exit
      0x31, (byte)0xdb,                          // xor ebx, ebx
      (byte)0xcd, (byte)0x80                     // int 0x80
    };
    int size = codeOffset + code.length + message.length;
    ByteBuffer out = ByteBuffer.allocate( size ).order( ByteOrder.LITTLE_ENDIAN );
    putIdent( out, Elf.ELFCLASS32 );
    out.putShort( (short)2 ).putShort( Elf.EM_386 ).putInt( 1 );
    out.putInt( 0x08048000 + codeOffset ).putInt( 52 ).putInt( 0 ).putInt( 0 );
    out.putShort( (short)52 ).putShort( (short)32 ).putShort( (short)1 );
    out.putShort( (short)40 ).putShort( (short)0 ).putShort( (short)0 );
    out.putInt( 1 ).putInt( 0 ).putInt( 0x08048000 ).putInt( 0 );
    out.putInt( size ).putInt( size ).putInt( 5 ).putInt( 0x1000 );
    out.put( code ).put( message );
    return out.array();
  }

  private static void putIdent( ByteBuffer out, int elfClass ) {
    out.put( (byte)0x7f ).put( (byte)'E' ).put( (byte)'L' ).put( (byte)'F' );
    out.put( (byte)elfClass ).put( Elf.ELFDATA2LSB ).put( (byte)1 );
    while( out.position() < 16 ) out.put( (byte)0 );
  }
}
