// ----------------------------------------
//  Process-wide Apple HVF VM and IPA slots (issue #973)
// ----------------------------------------
package emulin;

/** Hypervisor.framework permits one VM per host process; Linux processes use IPA slots. */
final class Aarch64HvVmPool {
  private static final long SLOT_STRIDE = 4L << 30;
  private static final int MAX_SLOTS = 128;
  private static final boolean[] used = new boolean[MAX_SLOTS];
  private static Aarch64HvVm vm;
  private static int leases;

  private Aarch64HvVmPool() {}

  static synchronized Lease acquire() throws Throwable {
    if( vm == null ) vm = new HvfAarch64Vm();
    for( int slot = 0; slot < used.length; slot++ ) {
      if( used[ slot ] ) continue;
      used[ slot ] = true;
      leases++;
      return new Lease( slot, (long)slot * SLOT_STRIDE, vm );
    }
    throw new IllegalStateException( "AArch64 HVF IPA slot limit reached" );
  }

  static final class Lease implements AutoCloseable {
    private final int slot;
    private final long ipaBase;
    private final Aarch64HvVm leasedVm;
    private long mappedSize;
    private boolean closed;

    private Lease( int slot, long ipaBase, Aarch64HvVm vm ) {
      this.slot = slot;
      this.ipaBase = ipaBase;
      this.leasedVm = vm;
    }

    long ipaBase() { return ipaBase; }
    Aarch64HvVm vm() { return leasedVm; }

    void map( Aarch64HvAddressSpace space ) throws Throwable {
      synchronized( Aarch64HvVmPool.class ) {
        if( closed ) throw new IllegalStateException( "closed AArch64 HVF VM lease" );
        space.mapInto( leasedVm );
        mappedSize = space.sizeBytes();
      }
    }

    @Override public void close() {
      synchronized( Aarch64HvVmPool.class ) {
        if( closed ) return;
        Throwable failure = null;
        if( mappedSize != 0 ) {
          try { leasedVm.unmapGuestRam( ipaBase, mappedSize ); }
          catch( Throwable t ) { failure = t; }
        }
        used[ slot ] = false;
        leases--;
        closed = true;
        if( leases == 0 ) {
          try { leasedVm.close(); }
          catch( Throwable t ) { if( failure == null ) failure = t; }
          vm = null;
        }
        if( failure != null ) {
          throw new IllegalStateException( "failed to release AArch64 HVF VM lease", failure );
        }
      }
    }
  }
}
