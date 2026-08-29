// ----------------------------------------
//  macOS Hypervisor.framework AArch64 FFM bindings (issue #973)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Minimal, lazy Hypervisor.framework surface needed by the Phase 7 smoke. */
public final class Aarch64HvBindings {
  public static final int HV_SUCCESS = 0;

  public static final long HV_MEMORY_READ  = 1L;
  public static final long HV_MEMORY_WRITE = 2L;
  public static final long HV_MEMORY_EXEC  = 4L;

  public static final int HV_EXIT_REASON_CANCELED         = 0;
  public static final int HV_EXIT_REASON_EXCEPTION        = 1;
  public static final int HV_EXIT_REASON_VTIMER_ACTIVATED = 2;
  public static final int HV_EXIT_REASON_UNKNOWN          = 3;

  public static final int HV_REG_X0   = 0;
  public static final int HV_REG_X30  = 30;
  public static final int HV_REG_PC   = 31;
  public static final int HV_REG_FPCR = 32;
  public static final int HV_REG_FPSR = 33;
  public static final int HV_REG_CPSR = 34;
  public static final int HV_SIMD_FP_REG_Q0  = 0;
  public static final int HV_SIMD_FP_REG_Q31 = 31;

  public static final int HV_SYS_REG_TPIDR_EL0 = 0xde82;
  public static final int HV_SYS_REG_SPSR_EL1  = 0xc200;
  public static final int HV_SYS_REG_ELR_EL1   = 0xc201;
  public static final int HV_SYS_REG_SP_EL0    = 0xc208;
  public static final int HV_SYS_REG_ESR_EL1   = 0xc290;
  public static final int HV_SYS_REG_VBAR_EL1  = 0xc600;

  // Darwin mmap(2) values.
  private static final int PROT_READ = 1, PROT_WRITE = 2;
  private static final int MAP_PRIVATE = 2, MAP_ANON = 0x1000;

  private static final String FRAMEWORK =
      "/System/Library/Frameworks/Hypervisor.framework/Hypervisor";

  private static volatile Boolean available;
  private static String unavailableReason = "not probed";
  private static Linker linker;
  private static SymbolLookup hv;
  private static SymbolLookup simdShim;
  private static SymbolLookup libc;

  private static MethodHandle mhVmCreate, mhVmDestroy, mhVmMap, mhVmUnmap,
      mhVmGetMaxVcpuCount, mhVcpuCreate, mhVcpuDestroy, mhVcpuGetReg,
      mhVcpuSetReg, mhVcpuGetSimdFpReg, mhVcpuSetSimdFpReg,
      mhVcpuGetSysReg, mhVcpuSetSysReg, mhVcpuRun,
      mhVcpusExit, mhMmap, mhMunmap, mhGetPageSize, mhSysctlByName;

  private Aarch64HvBindings() {}

  /**
   * Checks host architecture, the documented kern.hv_support sysctl, and all
   * symbols used by the initial backend.  VM creation is intentionally not a
   * probe because Hypervisor.framework permits only one VM per process.
   */
  public static synchronized boolean probe() {
    if( available != null ) return available;
    String os = System.getProperty( "os.name", "" ).toLowerCase( Locale.ROOT );
    String arch = System.getProperty( "os.arch", "" ).toLowerCase( Locale.ROOT );
    if( !os.contains( "mac" ) || !(arch.equals( "aarch64" ) || arch.equals( "arm64" )) ) {
      unavailableReason = "requires Apple Silicon macOS";
      available = false;
      return false;
    }
    try {
      linker = Linker.nativeLinker();
      hv = SymbolLookup.libraryLookup( FRAMEWORK, Arena.global() );
      simdShim = SymbolLookup.libraryLookup( simdShimPath(), Arena.global() );
      libc = linker.defaultLookup();
      linkAll();
      if( !hardwareSupported() ) {
        unavailableReason = "kern.hv_support is unavailable or false";
        available = false;
        return false;
      }
      unavailableReason = "";
      available = true;
      return true;
    } catch( Throwable t ) {
      unavailableReason = t.getClass().getSimpleName() + ": " + String.valueOf( t.getMessage() );
      available = false;
      return false;
    }
  }

  public static String describeAvailability() {
    return probe() ? "Apple Silicon HVF detected"
        : "Apple Silicon HVF not available (" + unavailableReason + ")";
  }

  static int pageSize() throws Throwable {
    requireAvailable();
    return (int) mhGetPageSize.invoke();
  }

  static MemorySegment allocateGuestRam( long sizeBytes ) throws Throwable {
    requireAvailable();
    int page = pageSize();
    requireAligned( sizeBytes, page, "guest RAM size" );
    MemorySegment result = (MemorySegment) mhMmap.invoke( MemorySegment.NULL, sizeBytes,
        PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0L );
    if( result.address() == 0L || result.address() == -1L ) {
      throw new IllegalStateException( "mmap guest RAM failed" );
    }
    return result.reinterpret( sizeBytes );
  }

  static void freeGuestRam( MemorySegment memory, long sizeBytes ) throws Throwable {
    if( memory == null || memory.address() == 0L ) return;
    int rc = (int) mhMunmap.invoke( memory, sizeBytes );
    if( rc != 0 ) throw new IllegalStateException( "munmap guest RAM rc=" + rc );
  }

  static int vmCreate() throws Throwable {
    requireAvailable();
    return (int) mhVmCreate.invoke( MemorySegment.NULL );
  }
  static int vmDestroy() throws Throwable { return (int) mhVmDestroy.invoke(); }
  static int vmMap( MemorySegment host, long ipa, long size, long flags ) throws Throwable {
    return (int) mhVmMap.invoke( host, ipa, size, flags );
  }
  static int vmUnmap( long ipa, long size ) throws Throwable {
    return (int) mhVmUnmap.invoke( ipa, size );
  }
  static int vmGetMaxVcpuCount( MemorySegment out ) throws Throwable {
    return (int) mhVmGetMaxVcpuCount.invoke( out );
  }
  static int vcpuCreate( MemorySegment vcpuOut, MemorySegment exitOut ) throws Throwable {
    return (int) mhVcpuCreate.invoke( vcpuOut, exitOut, MemorySegment.NULL );
  }
  static int vcpuDestroy( long vcpu ) throws Throwable { return (int) mhVcpuDestroy.invoke( vcpu ); }
  static int vcpuGetReg( long vcpu, int reg, MemorySegment out ) throws Throwable {
    return (int) mhVcpuGetReg.invoke( vcpu, reg, out );
  }
  static int vcpuSetReg( long vcpu, int reg, long value ) throws Throwable {
    return (int) mhVcpuSetReg.invoke( vcpu, reg, value );
  }
  static int vcpuGetSimdFpReg( long vcpu, int reg, MemorySegment low,
                               MemorySegment high ) throws Throwable {
    return (int) mhVcpuGetSimdFpReg.invoke( vcpu, reg, low, high );
  }
  static int vcpuSetSimdFpReg( long vcpu, int reg, long low, long high ) throws Throwable {
    return (int) mhVcpuSetSimdFpReg.invoke( vcpu, reg, low, high );
  }
  static int vcpuGetSysReg( long vcpu, int reg, MemorySegment out ) throws Throwable {
    return (int) mhVcpuGetSysReg.invoke( vcpu, (char) reg, out );
  }
  static int vcpuSetSysReg( long vcpu, int reg, long value ) throws Throwable {
    return (int) mhVcpuSetSysReg.invoke( vcpu, (char) reg, value );
  }
  static int vcpuRun( long vcpu ) throws Throwable { return (int) mhVcpuRun.invoke( vcpu ); }
  static int vcpusExit( MemorySegment vcpus, int count ) throws Throwable {
    return (int) mhVcpusExit.invoke( vcpus, count );
  }

  static void check( int rc, String operation ) {
    if( rc != HV_SUCCESS ) {
      throw new IllegalStateException( operation + " failed: hv_return_t=0x"
          + Integer.toUnsignedString( rc, 16 )
          + (rc == 0xfae94007 ? " (HV_DENIED: com.apple.security.hypervisor entitlement required)" : "") );
    }
  }

  static void requireAligned( long value, long alignment, String name ) {
    if( value < 0L || (value & (alignment - 1L)) != 0L ) {
      throw new IllegalArgumentException( name + " must be aligned to " + alignment + ": " + value );
    }
  }

  private static boolean hardwareSupported() throws Throwable {
    try( Arena arena = Arena.ofConfined() ) {
      MemorySegment name = arena.allocateFrom( "kern.hv_support" );
      MemorySegment value = arena.allocate( ValueLayout.JAVA_INT );
      MemorySegment size = arena.allocate( ValueLayout.JAVA_LONG );
      size.set( ValueLayout.JAVA_LONG, 0L, 4L );
      int rc = (int) mhSysctlByName.invoke( name, value, size, MemorySegment.NULL, 0L );
      return rc == 0 && size.get( ValueLayout.JAVA_LONG, 0L ) == 4L
          && value.get( ValueLayout.JAVA_INT, 0L ) != 0;
    }
  }

  private static void requireAvailable() {
    if( !probe() ) throw new IllegalStateException( describeAvailability() );
  }

  private static String simdShimPath() {
    String configured = System.getProperty( "emulin.hvf.simd-shim", "" );
    Path path = configured.isEmpty()
        ? Path.of( "target", "native", "libemulin-hvf-simd.dylib" )
        : Path.of( configured );
    path = path.toAbsolutePath().normalize();
    if( !Files.isRegularFile( path ) ) {
      throw new IllegalStateException( "missing AArch64 HVF SIMD shim: " + path );
    }
    return path.toString();
  }

  private static void linkAll() {
    mhVmCreate = downcall( hv, "hv_vm_create", FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.ADDRESS ) );
    mhVmDestroy = downcall( hv, "hv_vm_destroy", FunctionDescriptor.of( ValueLayout.JAVA_INT ) );
    mhVmMap = downcall( hv, "hv_vm_map", FunctionDescriptor.of( ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG ) );
    mhVmUnmap = downcall( hv, "hv_vm_unmap", FunctionDescriptor.of( ValueLayout.JAVA_INT,
        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG ) );
    mhVmGetMaxVcpuCount = downcall( hv, "hv_vm_get_max_vcpu_count", FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.ADDRESS ) );
    mhVcpuCreate = downcall( hv, "hv_vcpu_create", FunctionDescriptor.of( ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS ) );
    mhVcpuDestroy = downcall( hv, "hv_vcpu_destroy", FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG ) );
    mhVcpuGetReg = downcall( hv, "hv_vcpu_get_reg", FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS ) );
    mhVcpuSetReg = downcall( hv, "hv_vcpu_set_reg", FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG ) );
    mhVcpuGetSimdFpReg = downcall( simdShim, "emulin_hv_vcpu_get_simd_fp_reg",
        FunctionDescriptor.of( ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS ) );
    mhVcpuSetSimdFpReg = downcall( simdShim, "emulin_hv_vcpu_set_simd_fp_reg",
        FunctionDescriptor.of( ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG ) );
    mhVcpuGetSysReg = downcall( hv, "hv_vcpu_get_sys_reg", FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_CHAR, ValueLayout.ADDRESS ) );
    mhVcpuSetSysReg = downcall( hv, "hv_vcpu_set_sys_reg", FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_CHAR, ValueLayout.JAVA_LONG ) );
    mhVcpuRun = downcall( hv, "hv_vcpu_run", FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG ) );
    mhVcpusExit = downcall( hv, "hv_vcpus_exit", FunctionDescriptor.of(
        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT ) );
    mhMmap = downcall( libc, "mmap", FunctionDescriptor.of( ValueLayout.ADDRESS,
        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG ) );
    mhMunmap = downcall( libc, "munmap", FunctionDescriptor.of( ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG ) );
    mhGetPageSize = downcall( libc, "getpagesize", FunctionDescriptor.of( ValueLayout.JAVA_INT ) );
    mhSysctlByName = downcall( libc, "sysctlbyname", FunctionDescriptor.of( ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG ) );
  }

  private static MethodHandle downcall( SymbolLookup lookup, String symbol,
                                        FunctionDescriptor descriptor ) {
    MemorySegment address = lookup.find( symbol ).orElseThrow(
        () -> new UnsatisfiedLinkError( "missing native symbol: " + symbol ) );
    return linker.downcallHandle( address, descriptor );
  }
}
