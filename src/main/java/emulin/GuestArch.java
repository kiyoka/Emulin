// ----------------------------------------
//  Guest architecture identity (issue #951 Phase 0)
// ----------------------------------------
package emulin;

/** Linux guest ISA/ABI selected from both ELF class and {@code e_machine}. */
public enum GuestArch {
  I386,
  X86_64,
  AARCH64;

  static GuestArch fromElf( int elfClass, int machine ) {
    if( elfClass == Elf.ELFCLASS32 && machine == Elf.EM_386 ) return I386;
    if( elfClass == Elf.ELFCLASS64 && machine == Elf.EM_X86_64 ) return X86_64;
    if( elfClass == Elf.ELFCLASS64 && machine == Elf.EM_AARCH64 ) return AARCH64;
    throw new IllegalArgumentException(
        "unsupported ELF class/machine combination: class=" + elfClass
        + " machine=" + machine );
  }
}
