// ----------------------------------------
//  Real AArch64 ELF execution through HVF (issue #973)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Loads PT_LOAD segments, enters the ELF at EL0, and dispatches getpid. */
public final class Aarch64HvElfSmoke {
  private static final int ESR_EC_HVC64 = 0x16;
  private static final int ESR_EC_SVC64 = 0x15;
  private static final long VECTOR_BASE = 0x1_0000L;
  private static final long LOWER_A64_SYNC_VECTOR = VECTOR_BASE + 0x400L;
  private static final long STACK_TOP = 0x7fff_ffff_f000L;
  private static final int EXPECTED_PID = 0x2345;

  private Aarch64HvElfSmoke() {}

  public static void main( String[] args ) throws Throwable {
    if( args.length != 1 ) {
      throw new IllegalArgumentException( "usage: Aarch64HvElfSmoke <AArch64 ELF>" );
    }
    if( !Aarch64HvBindings.probe() ) {
      System.err.println( "[Aarch64HvElfSmoke] " + Aarch64HvBindings.describeAvailability() );
      System.exit( 2 );
    }

    ElfImage image = ElfImage.read( Path.of( args[0] ) );
    try( Aarch64HvAddressSpace memory = new Aarch64HvAddressSpace( 32L << 20 );
         Aarch64HvVm vm = new HvfAarch64Vm() ) {
      for( LoadSegment segment : image.segments ) {
        memory.mapZeroed( segment.virtualAddress, segment.memorySize );
        memory.store( segment.virtualAddress, image.bytes,
            Math.toIntExact( segment.fileOffset ), Math.toIntExact( segment.fileSize ) );
      }
      memory.mapZeroed( STACK_TOP - 0x1_0000L, 0x1_0000L );
      memory.mapPrivilegedZeroed( VECTOR_BASE, 0x1000L );
      memory.store32( LOWER_A64_SYNC_VECTOR, 0xd4000002 );     // hvc #0
      memory.store32( LOWER_A64_SYNC_VECTOR + 4, 0xd69f03e0 ); // eret
      memory.mapInto( vm );

      try( Aarch64HvVcpu vcpu = vm.createVcpu() ) {
        memory.installTranslation( vcpu );
        vcpu.setSystemRegister( Aarch64HvBindings.HV_SYS_REG_VBAR_EL1, VECTOR_BASE );
        Aarch64State initial = new Aarch64State();
        initial.pc = image.entry;
        initial.sp = STACK_TOP - 16L;
        Aarch64HvStateSync.load( initial, vcpu );

        Aarch64HvVcpu.Exit first = runWithTimeout( vcpu );
        requireHvcFromSvc( vcpu, first, 0 );
        Sysinfo sysinfo = new Sysinfo( 0, false );
        new Kernel( sysinfo );
        Process process = new Process( EXPECTED_PID, sysinfo );
        SyscallAarch64 syscall = new SyscallAarch64( sysinfo, process );
        Aarch64HvSyscallBridge.Dispatch dispatch =
            new Aarch64HvSyscallBridge().dispatch( vcpu, first, syscall );
        require( dispatch.number() == Aarch64SyscallTable.SYS_GETPID
                && dispatch.result() == EXPECTED_PID,
            "real ELF getpid dispatch mismatch: " + dispatch );

        Aarch64HvVcpu.Exit second = runWithTimeout( vcpu );
        requireHvcFromSvc( vcpu, second, 1 );
        require( vcpu.getRegister( Aarch64HvBindings.HV_REG_X0 ) == 0x51L
                && vcpu.getRegister( Aarch64HvBindings.HV_REG_X0 + 1 ) == EXPECTED_PID,
            "real ELF completion registers mismatch" );
      }
    }
    System.out.println( "AArch64 HVF ELF smoke OK: PT_LOAD + 48-bit stack + getpid/ERET passed" );
  }

  private static Aarch64HvVcpu.Exit runWithTimeout( Aarch64HvVcpu vcpu )
      throws Throwable {
    AtomicBoolean finished = new AtomicBoolean();
    Thread watchdog = new Thread( () -> {
      try {
        Thread.sleep( 2000L );
        if( !finished.get() ) vcpu.requestExit();
      } catch( Throwable t ) {
        if( !finished.get() ) t.printStackTrace( System.err );
      }
    }, "aarch64-hvf-elf-watchdog" );
    watchdog.setDaemon( true );
    watchdog.start();
    Aarch64HvVcpu.Exit exit;
    try {
      exit = vcpu.run();
    } finally {
      finished.set( true );
      watchdog.interrupt();
      watchdog.join( 1000L );
    }
    if( exit.reason() == Aarch64HvVcpu.ExitReason.CANCELED ) {
      throw new AssertionError( "AArch64 ELF execution timed out: PC=0x"
          + Long.toHexString( vcpu.getRegister( Aarch64HvBindings.HV_REG_PC ) )
          + " ESR_EL1=0x" + Long.toHexString( vcpu.getSystemRegister(
              Aarch64HvBindings.HV_SYS_REG_ESR_EL1 ) )
          + " FAR_EL1=0x" + Long.toHexString( vcpu.getSystemRegister(
              Aarch64HvBindings.HV_SYS_REG_FAR_EL1 ) )
          + " ELR_EL1=0x" + Long.toHexString( vcpu.getSystemRegister(
              Aarch64HvBindings.HV_SYS_REG_ELR_EL1 ) )
          + " SCTLR_EL1=0x" + Long.toHexString( vcpu.getSystemRegister(
              Aarch64HvBindings.HV_SYS_REG_SCTLR_EL1 ) )
          + " TCR_EL1=0x" + Long.toHexString( vcpu.getSystemRegister(
              Aarch64HvBindings.HV_SYS_REG_TCR_EL1 ) )
          + " TTBR0_EL1=0x" + Long.toHexString( vcpu.getSystemRegister(
              Aarch64HvBindings.HV_SYS_REG_TTBR0_EL1 ) ) );
    }
    return exit;
  }

  private static void requireHvcFromSvc( Aarch64HvVcpu vcpu,
                                         Aarch64HvVcpu.Exit exit,
                                         int immediate ) throws Throwable {
    long esr = vcpu.getSystemRegister( Aarch64HvBindings.HV_SYS_REG_ESR_EL1 );
    require( exit.reason() == Aarch64HvVcpu.ExitReason.EXCEPTION
            && exit.exceptionClass() == ESR_EC_HVC64
            && ((esr >>> 26) & 0x3f) == ESR_EC_SVC64
            && (esr & 0xffffL) == immediate,
        "unexpected real ELF SVC exit: exit=" + exit
            + " ESR_EL1=0x" + Long.toHexString( esr ) );
  }

  private static void require( boolean condition, String message ) {
    if( !condition ) throw new AssertionError( message );
  }

  private record LoadSegment( long fileOffset, long virtualAddress,
                              long fileSize, long memorySize ) {}

  private static final class ElfImage {
    final byte[] bytes;
    final long entry;
    final LoadSegment[] segments;

    private ElfImage( byte[] bytes, long entry, LoadSegment[] segments ) {
      this.bytes = bytes;
      this.entry = entry;
      this.segments = segments;
    }

    static ElfImage read( Path path ) throws Exception {
      byte[] bytes = Files.readAllBytes( path );
      if( bytes.length < 64 || bytes[0] != 0x7f || bytes[1] != 'E'
          || bytes[2] != 'L' || bytes[3] != 'F'
          || bytes[4] != 2 || bytes[5] != 1 ) {
        throw new IllegalArgumentException( "not a little-endian ELF64 file: " + path );
      }
      ByteBuffer elf = ByteBuffer.wrap( bytes ).order( ByteOrder.LITTLE_ENDIAN );
      int machine = Short.toUnsignedInt( elf.getShort( 18 ) );
      if( machine != 183 ) throw new IllegalArgumentException( "ELF is not AArch64: " + machine );
      long entry = elf.getLong( 24 );
      long programHeaderOffset = elf.getLong( 32 );
      int programHeaderSize = Short.toUnsignedInt( elf.getShort( 54 ) );
      int programHeaderCount = Short.toUnsignedInt( elf.getShort( 56 ) );
      java.util.ArrayList<LoadSegment> loads = new java.util.ArrayList<>();
      for( int index = 0; index < programHeaderCount; index++ ) {
        long headerOffset = programHeaderOffset + (long)index * programHeaderSize;
        if( headerOffset < 0 || headerOffset + 56 > bytes.length ) {
          throw new IllegalArgumentException( "invalid ELF program-header table" );
        }
        int offset = Math.toIntExact( headerOffset );
        if( elf.getInt( offset ) != 1 ) continue; // PT_LOAD
        long fileOffset = elf.getLong( offset + 8 );
        long virtualAddress = elf.getLong( offset + 16 );
        long fileSize = elf.getLong( offset + 32 );
        long memorySize = elf.getLong( offset + 40 );
        if( fileOffset < 0 || fileSize < 0 || fileSize > Integer.MAX_VALUE
            || memorySize < fileSize || fileOffset > bytes.length - fileSize ) {
          throw new IllegalArgumentException( "invalid ELF PT_LOAD range" );
        }
        loads.add( new LoadSegment( fileOffset, virtualAddress, fileSize, memorySize ) );
      }
      if( loads.isEmpty() ) throw new IllegalArgumentException( "ELF has no PT_LOAD segments" );
      return new ElfImage( bytes, entry, loads.toArray( new LoadSegment[0] ) );
    }
  }
}
