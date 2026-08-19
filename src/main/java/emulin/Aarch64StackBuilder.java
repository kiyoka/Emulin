// ----------------------------------------
//  Minimal Linux AArch64 initial stack (issue #951 Phase 1)
// ----------------------------------------
package emulin;

import java.nio.charset.StandardCharsets;

final class Aarch64StackBuilder {
  private Aarch64StackBuilder() {}

  static long build( MemoryBackend memory, long stackTop, String[] args, String[] envs ) {
    long sp = stackTop - 64;
    long[] argv = new long[ args.length ];
    long[] envp = new long[ envs.length ];

    for( int i = args.length - 1; i >= 0; i-- ) {
      byte[] bytes = (args[i] + "\0").getBytes( StandardCharsets.ISO_8859_1 );
      sp -= bytes.length;
      for( int j = 0; j < bytes.length; j++ ) memory.store8( sp + j, bytes[j] );
      argv[i] = sp;
    }
    for( int i = envs.length - 1; i >= 0; i-- ) {
      byte[] bytes = (envs[i] + "\0").getBytes( StandardCharsets.ISO_8859_1 );
      sp -= bytes.length;
      for( int j = 0; j < bytes.length; j++ ) memory.store8( sp + j, bytes[j] );
      envp[i] = sp;
    }
    sp &= ~0xfL;

    int words = 1 + args.length + 1 + envs.length + 1 + 2;
    if( (words & 1) != 0 ) sp -= 8;

    sp = push( memory, sp, 0 ); // AT_NULL value
    sp = push( memory, sp, 0 ); // AT_NULL type
    sp = push( memory, sp, 0 ); // envp terminator
    for( int i = envp.length - 1; i >= 0; i-- ) sp = push( memory, sp, envp[i] );
    sp = push( memory, sp, 0 ); // argv terminator
    for( int i = argv.length - 1; i >= 0; i-- ) sp = push( memory, sp, argv[i] );
    sp = push( memory, sp, args.length );
    return sp;
  }

  private static long push( MemoryBackend memory, long sp, long value ) {
    sp -= 8;
    memory.store64( sp, value );
    return sp;
  }
}
