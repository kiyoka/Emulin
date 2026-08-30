// ----------------------------------------
//  AArch64 HVF syscall memory backend (issue #973)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.nio.charset.StandardCharsets;

/**
 * Keeps the existing Memory object as the VMA/ELF metadata authority while
 * exposing the coherent HVF guest pages to SyscallAarch64.
 */
final class Aarch64HvMemoryBackend implements MemoryBackend {
  private static final int COPY_CHUNK = 1 << 20;

  private final Aarch64HvAddressSpace space;
  private final Memory metadata;

  Aarch64HvMemoryBackend( Aarch64HvAddressSpace space, Memory metadata ) {
    this.space = space;
    this.metadata = metadata;
  }

  void importInitialImage() {
    for( int index = 0; index < metadata.segments; index++ ) {
      Segment segment = metadata.segment[index];
      if( segment == null || segment.p_type != 1 || segment.buf == null ) continue;
      importBytes( segment.p_vaddr, segment.buf, 0, segment.buf.length );
    }
  }

  private void importBytes( long address, byte[] bytes, int offset, int length ) {
    if( length == 0 ) return;
    space.mapZeroed( address, length );
    space.storeMapped( address, bytes, offset, length );
  }

  private void importMetadataRange( long address, int length ) {
    if( length <= 0 ) return;
    space.mapZeroed( address, length );
    byte[] buffer = new byte[ Math.min( COPY_CHUNK, length ) ];
    int done = 0;
    while( done < length ) {
      int count = Math.min( buffer.length, length - done );
      metadata.bulkLoadFromMem( address + done, buffer, 0, count );
      space.storeMapped( address + done, buffer, 0, count );
      done += count;
    }
  }

  private Memory.SegfaultException fault( long address ) {
    return new Memory.SegfaultException( address );
  }

  @Override public byte load8( long address ) {
    try { return space.load8( address ); }
    catch( IllegalStateException error ) { throw fault( address ); }
  }

  @Override public short load16( long address ) {
    return (short)((load8( address ) & 0xff) | ((load8( address + 1 ) & 0xff) << 8));
  }

  @Override public int load32( long address ) {
    return (load16( address ) & 0xffff) | ((load16( address + 2 ) & 0xffff) << 16);
  }

  @Override public long load64( long address ) {
    return Integer.toUnsignedLong( load32( address ) )
        | (Integer.toUnsignedLong( load32( address + 4 ) ) << 32);
  }

  @Override public boolean store8( long address, int value ) {
    try { space.store8( address, value ); return true; }
    catch( IllegalStateException error ) { throw fault( address ); }
  }

  @Override public void store16( long address, short value ) {
    store8( address, value ); store8( address + 1, value >>> 8 );
  }

  @Override public void store32( long address, int value ) {
    store16( address, (short)value ); store16( address + 2, (short)(value >>> 16) );
  }

  @Override public void store64( long address, long value ) {
    store32( address, (int)value ); store32( address + 4, (int)(value >>> 32) );
  }

  @Override public void bulkLoad( long address, byte[] target, int length ) {
    bulkLoadFromMem( address, target, 0, length );
  }

  @Override public void bulkLoadFromMem( long address, byte[] target, int offset, int length ) {
    try { space.load( address, target, offset, length ); }
    catch( IllegalStateException error ) { throw fault( address ); }
  }

  @Override public void bulkStoreToMem( long address, byte[] source, int offset, int length ) {
    try { space.storeMapped( address, source, offset, length ); }
    catch( IllegalStateException error ) { throw fault( address ); }
  }

  @Override public void bulkZero( long address, int length ) {
    try { space.zero( address, length ); }
    catch( IllegalStateException error ) { throw fault( address ); }
  }

  @Override public boolean fetch( long address, byte[] target ) {
    try { space.load( address, target, 0, target.length ); return true; }
    catch( IllegalStateException error ) { return false; }
  }

  @Override public long alloc( long address, int size ) {
    long result = metadata.alloc( address, size );
    if( result > 0 ) importMetadataRange( result, size );
    return result;
  }

  @Override public long alloc_and_map( long address, int size, int fd, long offset ) {
    long result = metadata.alloc_and_map( address, size, fd, offset );
    if( result > 0 ) importMetadataRange( result, size );
    return result;
  }

  @Override public long alloc_and_map( long address, int size, int fd, long offset, int prot ) {
    long result = metadata.alloc_and_map( address, size, fd, offset, prot );
    if( result > 0 ) importMetadataRange( result, size );
    return result;
  }

  @Override public long alloc_and_map( long address, int size, int fd, long offset,
                                      int prot, long flags ) {
    long result = metadata.alloc_and_map( address, size, fd, offset, prot, flags );
    if( result > 0 ) importMetadataRange( result, size );
    return result;
  }

  @Override public long alloc_huge( long address, long size, int prot, boolean fixed ) {
    return -12L; // MVP: do not reserve an unbackable multi-gigabyte HVF range.
  }

  @Override public int realloc( long address, int size ) {
    int result = metadata.realloc( address, size );
    if( result == 0 ) importMetadataRange( address, size );
    return result;
  }

  @Override public int free( long address, long size ) {
    int result = metadata.free( address, size );
    if( result == 0 ) space.unmap( address, size );
    return result;
  }

  @Override public boolean in( long address ) {
    return metadata.in( address ) && space.isMapped( address );
  }

  @Override public long get_curbrk() { return metadata.get_curbrk(); }

  @Override public boolean set_curbrk( long value ) {
    long previous = metadata.get_curbrk();
    boolean result = metadata.set_curbrk( value );
    if( result && value > previous ) importMetadataRange( previous, (int)(value - previous) );
    return result;
  }

  @Override public long ensureSigtramp() {
    long address = metadata.ensureAarch64Sigtramp();
    if( address > 0 ) importMetadataRange( address, 4096 );
    return address;
  }

  @Override public void set_map_path( long address, String path ) {
    metadata.set_map_path( address, path );
  }

  @Override public String genProcSelfMaps() { return metadata.genProcSelfMaps(); }
  @Override public void registerFileBacked( long address, long length ) {
    metadata.registerFileBacked( address, length );
  }
  @Override public boolean isFileBacked( long address ) { return metadata.isFileBacked( address ); }
  @Override public void unregisterFileBacked( long address, long length ) {
    metadata.unregisterFileBacked( address, length );
  }
  @Override public boolean isSharedMapped( long address ) { return metadata.isSharedMapped( address ); }
  @Override public boolean isMapShared( long address ) { return metadata.isMapShared( address ); }
  @Override public void setProtection( long address, long length, int prot ) {
    metadata.setProtection( address, length, prot );
  }
  @Override public boolean isRangeMapped( long address, long length ) {
    return metadata.isRangeMapped( address, length );
  }
  @Override public void msyncFlush( long address, long length ) {
    if( length <= Integer.MAX_VALUE ) {
      byte[] bytes = new byte[(int)length];
      bulkLoadFromMem( address, bytes, 0, bytes.length );
      metadata.bulkStoreToMem( address, bytes, 0, bytes.length );
    }
    metadata.msyncFlush( address, length );
  }
  @Override public void restoreFileBackedPrivate( long address, long length ) {
    metadata.restoreFileBackedPrivate( address, length );
    if( length <= Integer.MAX_VALUE ) importMetadataRange( address, (int)length );
  }
  @Override public boolean mayHaveSharedFileMaps() { return metadata.mayHaveSharedFileMaps(); }
  @Override public void propagateWriteToSharedMaps( String path, long offset,
                                                    byte[] data, int length ) {
    metadata.propagateWriteToSharedMaps( path, offset, data, length );
  }
  @Override public void updateFileMapEof( String path, long size ) {
    metadata.updateFileMapEof( path, size );
  }

  @Override public String get_symbol( long address ) { return metadata.get_symbol( address ); }

  @Override public long storeString( long address, String value ) {
    byte[] bytes = value.getBytes( StandardCharsets.UTF_8 );
    bulkStoreToMem( address, bytes, 0, bytes.length );
    store8( address + bytes.length, 0 );
    return address + bytes.length + 1;
  }

  @Override public String loadString( long address ) {
    return loadStringWithCharset( address, StandardCharsets.UTF_8 );
  }

  @Override public String loadStringRaw( long address ) {
    return loadStringWithCharset( address, StandardCharsets.ISO_8859_1 );
  }

  private String loadStringWithCharset( long address, java.nio.charset.Charset charset ) {
    byte[] bytes = new byte[ LOADSTRING_MAX ];
    int length = 0;
    while( length < bytes.length ) {
      byte value = load8( address + length );
      if( value == 0 ) break;
      bytes[length++] = value;
    }
    return new String( bytes, 0, length, charset );
  }

  @Override public long storeStringRaw( long address, String value ) {
    byte[] bytes = value.getBytes( StandardCharsets.ISO_8859_1 );
    bulkStoreToMem( address, bytes, 0, bytes.length );
    store8( address + bytes.length, 0 );
    return address + bytes.length + 1;
  }

  @Override public void release_buffers() { /* owned and closed by Aarch64HvCpu */ }

  @Override public void dump( long address, int length ) {
    byte[] bytes = new byte[length];
    bulkLoadFromMem( address, bytes, 0, length );
    for( int index = 0; index < length; index++ ) {
      if( (index & 15) == 0 ) System.err.printf( "%n%016x: ", address + index );
      System.err.printf( "%02x ", bytes[index] & 0xff );
    }
    System.err.println();
  }
}
