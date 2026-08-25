// ----------------------------------------
//  Linux AArch64 argc/argv/envp/auxv initial stack (issue #951)
// ----------------------------------------
package emulin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class Aarch64StackBuilder {
  private Aarch64StackBuilder() {}

  static long build( Process process, long stackTop, String[] args, String[] envs ) {
    MemoryBackend memory = process.mem;
    Memory elf = process.mem;
    long sp = stackTop - 64;
    long[] argv = new long[ args.length ];
    long[] envp = new long[ envs.length ];

    for( int i = args.length - 1; i >= 0; i-- ) {
      sp = storeString( memory, sp, args[i] );
      argv[i] = sp;
    }
    for( int i = envs.length - 1; i >= 0; i-- ) {
      sp = storeString( memory, sp, envs[i] );
      envp[i] = sp;
    }
    sp &= ~0xfL;

    sp -= 16;
    long randomPointer = sp;
    byte[] random = new byte[ 16 ];
    SyscallAmd64.fillRandom( random );
    memory.bulkStoreToMem( randomPointer, random, 0, random.length );

    sp = storeString( memory, sp, "aarch64" );
    long platformPointer = sp;
    sp &= ~0xfL;

    long elfBase = 0;
    for( int i = 0; i < elf.segments; i++ ) {
      if( elf.segment[i] != null && elf.segment[i].p_offset == 0 ) {
        elfBase = elf.segment[i].p_vaddr;
        break;
      }
    }
    long programHeaders = elfBase + elf.e_phoff;
    long executableName = argv.length == 0 ? 0 : argv[0];
    long effectiveUid = process.euid >= 0 ? process.euid : process.uid;
    long effectiveGid = process.egid >= 0 ? process.egid : process.gid;

    List<Aux> auxv = new ArrayList<>();
    auxv.add( new Aux( 3, programHeaders ) );                   // AT_PHDR
    auxv.add( new Aux( 4, elf.e_phentsize & 0xffffL ) );        // AT_PHENT
    auxv.add( new Aux( 5, elf.e_phnum & 0xffffL ) );            // AT_PHNUM
    auxv.add( new Aux( 6, 4096 ) );                             // AT_PAGESZ
    auxv.add( new Aux( 7, elf.interp_base ) );                  // AT_BASE
    auxv.add( new Aux( 8, 0 ) );                                // AT_FLAGS
    auxv.add( new Aux( 9, elf.e_entry ) );                      // AT_ENTRY
    auxv.add( new Aux( 11, process.uid ) );                     // AT_UID
    auxv.add( new Aux( 12, effectiveUid ) );                    // AT_EUID
    auxv.add( new Aux( 13, process.gid ) );                     // AT_GID
    auxv.add( new Aux( 14, effectiveGid ) );                    // AT_EGID
    auxv.add( new Aux( 15, platformPointer ) );                 // AT_PLATFORM
    auxv.add( new Aux( 16, 0 ) );                               // AT_HWCAP
    auxv.add( new Aux( 17, 100 ) );                             // AT_CLKTCK
    auxv.add( new Aux( 23, 0 ) );                               // AT_SECURE
    auxv.add( new Aux( 25, randomPointer ) );                   // AT_RANDOM
    auxv.add( new Aux( 26, 0 ) );                               // AT_HWCAP2
    auxv.add( new Aux( 31, executableName ) );                  // AT_EXECFN
    auxv.add( new Aux( 0, 0 ) );                                // AT_NULL

    int words = 1 + argv.length + 1 + envp.length + 1 + auxv.size() * 2;
    if( (words & 1) != 0 ) sp -= 8;

    for( int i = auxv.size() - 1; i >= 0; i-- ) {
      sp = push( memory, sp, auxv.get( i ).value );
      sp = push( memory, sp, auxv.get( i ).type );
    }
    sp = push( memory, sp, 0 );
    for( int i = envp.length - 1; i >= 0; i-- ) sp = push( memory, sp, envp[i] );
    sp = push( memory, sp, 0 );
    for( int i = argv.length - 1; i >= 0; i-- ) sp = push( memory, sp, argv[i] );
    sp = push( memory, sp, args.length );
    return sp;
  }

  private static long storeString( MemoryBackend memory, long sp, String value ) {
    byte[] bytes = (value + "\0").getBytes( StandardCharsets.ISO_8859_1 );
    sp -= bytes.length;
    for( int i = 0; i < bytes.length; i++ ) memory.store8( sp + i, bytes[i] );
    return sp;
  }

  private static long push( MemoryBackend memory, long sp, long value ) {
    sp -= 8;
    memory.store64( sp, value );
    return sp;
  }

  private record Aux( long type, long value ) { }
}
