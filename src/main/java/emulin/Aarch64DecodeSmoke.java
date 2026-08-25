// ----------------------------------------
//  AArch64 decoder vector harness (issue #951)
// ----------------------------------------
package emulin;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs assembler-derived decode vectors without exposing decoder internals. */
public final class Aarch64DecodeSmoke {
  private Aarch64DecodeSmoke() {}

  public static void main( String[] args ) throws Exception {
    int count = args.length == 0 ? runBuiltIn() : runManifest( Path.of( args[0] ) );
    System.out.println( "AArch64 decode smoke OK (" + count + " vectors)" );
  }

  static int runBuiltIn() {
    Aarch64Decoder decoder = new Aarch64Decoder();
    Aarch64DecodedInsn out = new Aarch64DecodedInsn();
    checkOperation( decoder.decode( 0xd2800020, out ), "MOVZ" );
    require( out.dataSize == 64 && out.rd == 0 && out.immediate == 1,
        "MOVZ operands" );
    checkOperation( decoder.decode( 0x100000e1, out ), "ADR" );
    require( out.rd == 1 && out.immediate == 28, "ADR operands" );
    checkOperation( decoder.decode( 0x91048c20, out ), "ADD_IMMEDIATE" );
    require( out.rd == 0 && out.rn == 1 && out.immediate == 0x123,
        "ADD immediate operands" );
    checkOperation( decoder.decode( 0x1400000a, out ), "B" );
    require( out.immediate == 40, "B immediate" );
    checkOperation( decoder.decode( 0xf9000c20, out ), "STR" );
    require( out.accessSize == 8 && out.immediate == 24,
        "STR unsigned immediate operands" );
    try {
      decoder.decode( 0xffffffff, out );
      throw new AssertionError( "unallocated encoding was accepted" );
    } catch( UnsupportedOperationException expected ) {
      // expected
    }
    return 5;
  }

  private static int runManifest( Path path ) throws Exception {
    Aarch64Decoder decoder = new Aarch64Decoder();
    Aarch64DecodedInsn out = new Aarch64DecodedInsn();
    int count = 0;
    try( BufferedReader reader = Files.newBufferedReader( path, StandardCharsets.UTF_8 ) ) {
      String line;
      int lineNumber = 0;
      while( (line = reader.readLine()) != null ) {
        lineNumber++;
        line = line.strip();
        if( line.isEmpty() || line.startsWith( "#" ) ) continue;
        String[] parts = line.split( "\\s+" );
        if( parts.length < 2 ) fail( path, lineNumber, "missing opcode/operation" );
        int raw = (int)Long.parseUnsignedLong( parts[0], 16 );
        try {
          decoder.decode( raw, out );
          checkOperation( out, parts[1] );
          for( int i = 2; i < parts.length; i++ ) {
            int equals = parts[i].indexOf( '=' );
            if( equals <= 0 ) fail( path, lineNumber, "invalid field: " + parts[i] );
            checkField( out, parts[i].substring( 0, equals ),
                        parts[i].substring( equals + 1 ) );
          }
        } catch( RuntimeException | AssertionError e ) {
          throw new AssertionError( path + ":" + lineNumber + ": " + e.getMessage(), e );
        }
        count++;
      }
    }
    return count;
  }

  private static void checkOperation( Aarch64DecodedInsn out, String expected ) {
    require( out.operation != null && out.operation.name().equals( expected ),
        "operation expected=" + expected + " actual=" + out.operation );
  }

  private static void checkField( Aarch64DecodedInsn out, String key, String expected ) {
    switch( key ) {
      case "dataSize" -> numeric( key, out.dataSize, expected );
      case "accessSize" -> numeric( key, out.accessSize, expected );
      case "rd" -> numeric( key, out.rd, expected );
      case "rn" -> numeric( key, out.rn, expected );
      case "rm" -> numeric( key, out.rm, expected );
      case "ra" -> numeric( key, out.ra, expected );
      case "rt2" -> numeric( key, out.rt2, expected );
      case "immediate" -> numeric( key, out.immediate, expected );
      case "immr" -> numeric( key, out.immr, expected );
      case "imms" -> numeric( key, out.imms, expected );
      case "shiftAmount" -> numeric( key, out.shiftAmount, expected );
      case "condition" -> numeric( key, out.condition, expected );
      case "bitIndex" -> numeric( key, out.bitIndex, expected );
      case "shiftType" -> text( key, out.shiftType.name(), expected );
      case "extendType" -> text( key, out.extendType.name(), expected );
      case "addressMode" -> text( key, out.addressMode.name(), expected );
      case "setsFlags" -> text( key, Boolean.toString( out.setsFlags ), expected );
      default -> throw new AssertionError( "unknown expected field " + key );
    }
  }

  private static void numeric( String key, long actual, String expectedText ) {
    long expected;
    if( expectedText.startsWith( "-0x" ) ) {
      expected = -Long.parseUnsignedLong( expectedText.substring( 3 ), 16 );
    } else if( expectedText.startsWith( "0x" ) ) {
      expected = Long.parseUnsignedLong( expectedText.substring( 2 ), 16 );
    } else {
      expected = Long.parseLong( expectedText );
    }
    require( actual == expected, key + " expected=" + expectedText
        + " actual=0x" + Long.toUnsignedString( actual, 16 ) );
  }

  private static void text( String key, String actual, String expected ) {
    require( actual.equals( expected ), key + " expected=" + expected + " actual=" + actual );
  }

  private static void fail( Path path, int line, String message ) {
    throw new AssertionError( path + ":" + line + ": " + message );
  }

  private static void require( boolean condition, String message ) {
    if( !condition ) throw new AssertionError( message );
  }
}
