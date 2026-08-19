// ----------------------------------------
//  Minimal ELF identity probe (issue #951 Phase 0)
// ----------------------------------------
package emulin;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Reads only ELF identification and {@code e_machine}. The full {@link Elf}
 * loader remains authoritative and verifies this result before using it.
 */
public final class ElfProbe {
  private static final int IDENTITY_BYTES = 20;

  private ElfProbe() {}

  public static ElfIdentity probe( String filename ) throws IOException {
    byte[] header = new byte[ IDENTITY_BYTES ];
    try( RandomAccessFile in = new RandomAccessFile( filename, "r" ) ) {
      in.readFully( header );
    }

    if( header[0] != 0x7f || header[1] != 'E'
        || header[2] != 'L' || header[3] != 'F' ) {
      throw new IOException( "not an ELF file: " + filename );
    }
    int elfClass = header[ Elf.EI_CLASS ] & 0xff;
    if( elfClass != Elf.ELFCLASS32 && elfClass != Elf.ELFCLASS64 ) {
      throw new IOException( "unsupported ELF class " + elfClass + ": " + filename );
    }
    int data = header[ Elf.EI_DATA ] & 0xff;
    if( data != Elf.ELFDATA2LSB ) {
      throw new IOException( "unsupported ELF byte order " + data + ": " + filename );
    }
    int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);
    try {
      return ElfIdentity.fromHeader( elfClass, machine );
    } catch( IllegalArgumentException e ) {
      throw new IOException( e.getMessage() + ": " + filename, e );
    }
  }
}
