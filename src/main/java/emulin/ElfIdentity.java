// ----------------------------------------
//  Minimal immutable ELF identity (issue #951 Phase 0)
// ----------------------------------------
package emulin;

/** Selection metadata read before the authoritative full ELF parse. */
public record ElfIdentity( int elfClass, int machine, GuestArch arch ) {
  public ElfIdentity {
    if( arch == null ) throw new IllegalArgumentException( "guest architecture is null" );
    GuestArch derived = GuestArch.fromElf( elfClass, machine );
    if( derived != arch ) {
      throw new IllegalArgumentException(
          "guest architecture does not match ELF identity: " + arch + " != " + derived );
    }
  }

  static ElfIdentity fromHeader( int elfClass, int machine ) {
    return new ElfIdentity( elfClass, machine, GuestArch.fromElf( elfClass, machine ) );
  }
}
