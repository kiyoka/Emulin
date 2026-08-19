// ----------------------------------------
//  Consistent guest construction result (issue #951 Phase 0)
// ----------------------------------------
package emulin;

/** Prevents CPU/syscall/runner pairs from being selected independently. */
public record GuestComponents( GuestAbi abi, Syscall syscall,
                               GuestCpu cpu, GuestRunner runner ) {
  public GuestComponents {
    if( abi == null || syscall == null || cpu == null || runner == null ) {
      throw new IllegalArgumentException( "guest component is null" );
    }
  }
}
