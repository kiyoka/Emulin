// ----------------------------------------
//  Apple Hypervisor.framework AArch64 VM (issue #973)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/** Initial single-process Hypervisor.framework VM implementation. */
public final class HvfAarch64Vm implements Aarch64HvVm {
  // A process-wide HVF VM can be created by one Linux process thread and
  // destroyed by the last surviving process thread.
  private final Arena control = Arena.ofShared();
  private final List<long[]> mappings = new ArrayList<>();
  private boolean closed;

  public HvfAarch64Vm() throws Throwable {
    try {
      Aarch64HvBindings.check( Aarch64HvBindings.vmCreate(), "hv_vm_create" );
    } catch( Throwable t ) {
      control.close();
      throw t;
    }
  }

  public static MemorySegment allocateGuestRam( long sizeBytes ) throws Throwable {
    MemorySegment memory = Aarch64HvBindings.allocateGuestRam( sizeBytes );
    LeakCheck.poolAllocated( sizeBytes );
    return memory;
  }

  public static void freeGuestRam( MemorySegment memory, long sizeBytes ) throws Throwable {
    Aarch64HvBindings.freeGuestRam( memory, sizeBytes );
    LeakCheck.poolFreed( sizeBytes );
  }

  @Override
  public void mapGuestRam( MemorySegment hostMemory, long ipa, long sizeBytes ) throws Throwable {
    ensureOpen();
    int page = Aarch64HvBindings.pageSize();
    Aarch64HvBindings.requireAligned( hostMemory.address(), page, "host address" );
    Aarch64HvBindings.requireAligned( ipa, page, "guest IPA" );
    Aarch64HvBindings.requireAligned( sizeBytes, page, "guest RAM size" );
    if( sizeBytes > hostMemory.byteSize() ) {
      throw new IllegalArgumentException( "mapping exceeds host memory segment" );
    }
    Aarch64HvBindings.check( Aarch64HvBindings.vmMap( hostMemory, ipa, sizeBytes,
        Aarch64HvBindings.HV_MEMORY_READ | Aarch64HvBindings.HV_MEMORY_WRITE
            | Aarch64HvBindings.HV_MEMORY_EXEC ), "hv_vm_map" );
    mappings.add( new long[]{ ipa, sizeBytes } );
  }

  @Override
  public void unmapGuestRam( long ipa, long sizeBytes ) throws Throwable {
    ensureOpen();
    Aarch64HvBindings.check( Aarch64HvBindings.vmUnmap( ipa, sizeBytes ), "hv_vm_unmap" );
    mappings.removeIf( m -> m[0] == ipa && m[1] == sizeBytes );
  }

  @Override public Aarch64HvVcpu createVcpu() throws Throwable {
    ensureOpen();
    return new HvfAarch64Vcpu();
  }

  @Override public int maxVcpus() throws Throwable {
    ensureOpen();
    MemorySegment out = control.allocate( ValueLayout.JAVA_INT );
    Aarch64HvBindings.check( Aarch64HvBindings.vmGetMaxVcpuCount( out ),
        "hv_vm_get_max_vcpu_count" );
    return out.get( ValueLayout.JAVA_INT, 0L );
  }

  @Override public void close() {
    if( closed ) return;
    Throwable failure = null;
    for( int i = mappings.size() - 1; i >= 0; i-- ) {
      long[] mapping = mappings.get( i );
      try {
        Aarch64HvBindings.check( Aarch64HvBindings.vmUnmap( mapping[0], mapping[1] ),
            "hv_vm_unmap" );
      } catch( Throwable t ) {
        failure = t;
      }
    }
    mappings.clear();
    try {
      Aarch64HvBindings.check( Aarch64HvBindings.vmDestroy(), "hv_vm_destroy" );
    } catch( Throwable t ) {
      if( failure == null ) failure = t;
    }
    closed = true;
    control.close();
    if( failure != null ) throw new IllegalStateException( "failed to close AArch64 HVF VM", failure );
  }

  private void ensureOpen() {
    if( closed ) throw new IllegalStateException( "AArch64 HVF VM is closed" );
  }
}
