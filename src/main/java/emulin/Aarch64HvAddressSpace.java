// ----------------------------------------
//  AArch64 HVF Stage-1 address space (issue #973)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/** A compact IPA pool with 48-bit EL0 virtual addresses and 4 KiB pages. */
final class Aarch64HvAddressSpace implements AutoCloseable {
  private static final long PAGE = 0x1000L;
  private static final long PAGE_MASK = PAGE - 1L;
  private static final long ADDRESS_MASK = 0x0000_ffff_ffff_f000L;
  private static final long TABLE_DESCRIPTOR = 0x3L;
  private static final long PAGE_DESCRIPTOR = 0x3L;
  private static final long ACCESS_FLAG = 1L << 10;
  private static final long INNER_SHAREABLE = 3L << 8;
  private static final long EL0_READ_WRITE = 1L << 6;
  private static final long DATA_BASE = 0x10_0000L;

  // 48-bit VA, 4 KiB TG0, inner-shareable WBWA, TTBR1 disabled.
  private static final long TCR_EL1 = 16L | (1L << 8) | (1L << 10)
      | (3L << 12) | (1L << 23);
  private static final long MAIR_EL1 = 0xffL; // AttrIdx0: normal WBWA memory.
  private static final long SCTLR_EL1 = 0x30d0_1805L; // RES1 + MMU/cache/I-cache.

  private final MemorySegment ram;
  private final long size;
  private final long rootTable = 0x4000L;
  private long nextTable = rootTable + PAGE;
  private long nextData = DATA_BASE;
  private boolean closed;

  Aarch64HvAddressSpace( long requestedSize ) throws Throwable {
    int hostPage = Aarch64HvBindings.pageSize();
    size = alignUp( requestedSize, hostPage );
    if( size <= DATA_BASE ) throw new IllegalArgumentException( "AArch64 HVF pool too small" );
    ram = HvfAarch64Vm.allocateGuestRam( size );
  }

  long sizeBytes() { return size; }
  MemorySegment backing() { return ram; }

  void mapInto( Aarch64HvVm vm ) throws Throwable {
    ensureOpen();
    vm.mapGuestRam( ram, 0L, size );
  }

  void installTranslation( Aarch64HvVcpu vcpu ) throws Throwable {
    ensureOpen();
    vcpu.setSystemRegister( Aarch64HvBindings.HV_SYS_REG_MAIR_EL1, MAIR_EL1 );
    vcpu.setSystemRegister( Aarch64HvBindings.HV_SYS_REG_TCR_EL1, TCR_EL1 );
    vcpu.setSystemRegister( Aarch64HvBindings.HV_SYS_REG_TTBR0_EL1, rootTable );
    vcpu.setSystemRegister( Aarch64HvBindings.HV_SYS_REG_SCTLR_EL1, SCTLR_EL1 );
  }

  void mapZeroed( long virtualAddress, long length ) {
    mapZeroed( virtualAddress, length, true );
  }

  void mapPrivilegedZeroed( long virtualAddress, long length ) {
    mapZeroed( virtualAddress, length, false );
  }

  private void mapZeroed( long virtualAddress, long length, boolean user ) {
    if( length < 0 || virtualAddress < 0 || virtualAddress + length < virtualAddress ) {
      throw new IllegalArgumentException( "invalid AArch64 virtual range" );
    }
    if( virtualAddress + length > (1L << 48) ) {
      throw new IllegalArgumentException( "AArch64 virtual range exceeds TTBR0 48-bit VA" );
    }
    if( length == 0 ) return;
    long first = virtualAddress & ~PAGE_MASK;
    long last = alignUp( virtualAddress + length, PAGE );
    for( long page = first; page < last; page += PAGE ) ensurePage( page, user );
  }

  void store( long virtualAddress, byte[] source, int offset, int length ) {
    if( offset < 0 || length < 0 || offset + length > source.length ) {
      throw new IndexOutOfBoundsException( "invalid source range" );
    }
    int done = 0;
    while( done < length ) {
      long address = virtualAddress + done;
      long physical = ensurePage( address & ~PAGE_MASK, true ) + (address & PAGE_MASK);
      int count = (int)Math.min( length - done, PAGE - (address & PAGE_MASK) );
      MemorySegment.copy( MemorySegment.ofArray( source ), offset + done,
          ram, physical, count );
      done += count;
    }
  }

  void store32( long virtualAddress, int value ) {
    long physical = translate( virtualAddress, 4 );
    ram.set( ValueLayout.JAVA_INT_UNALIGNED, physical, value );
  }

  private long translate( long virtualAddress, int bytes ) {
    if( (virtualAddress & PAGE_MASK) + bytes > PAGE ) {
      throw new IllegalArgumentException( "access crosses an AArch64 guest page" );
    }
    long table = rootTable;
    for( int level = 0; level < 3; level++ ) {
      int shift = 39 - level * 9;
      long entry = get64( table + (((virtualAddress >>> shift) & 0x1ffL) << 3) );
      if( (entry & TABLE_DESCRIPTOR) != TABLE_DESCRIPTOR ) {
        throw new IllegalStateException( "unmapped AArch64 virtual address: 0x"
            + Long.toHexString( virtualAddress ) );
      }
      table = entry & ADDRESS_MASK;
    }
    long leaf = get64( table + (((virtualAddress >>> 12) & 0x1ffL) << 3) );
    if( (leaf & PAGE_DESCRIPTOR) != PAGE_DESCRIPTOR ) {
      throw new IllegalStateException( "unmapped AArch64 virtual address: 0x"
          + Long.toHexString( virtualAddress ) );
    }
    return (leaf & ADDRESS_MASK) + (virtualAddress & PAGE_MASK);
  }

  private long ensurePage( long virtualPage, boolean user ) {
    long table = rootTable;
    for( int level = 0; level < 3; level++ ) {
      int shift = 39 - level * 9;
      long entryAddress = table + (((virtualPage >>> shift) & 0x1ffL) << 3);
      long entry = get64( entryAddress );
      if( (entry & TABLE_DESCRIPTOR) != TABLE_DESCRIPTOR ) {
        long child = allocateTable();
        put64( entryAddress, child | TABLE_DESCRIPTOR );
        table = child;
      } else {
        table = entry & ADDRESS_MASK;
      }
    }
    long leafAddress = table + (((virtualPage >>> 12) & 0x1ffL) << 3);
    long leaf = get64( leafAddress );
    if( (leaf & PAGE_DESCRIPTOR) == PAGE_DESCRIPTOR ) return leaf & ADDRESS_MASK;
    long physical = allocateData();
    put64( leafAddress, physical | PAGE_DESCRIPTOR | ACCESS_FLAG
        | INNER_SHAREABLE | (user ? EL0_READ_WRITE : 0L) );
    return physical;
  }

  private long allocateTable() {
    long result = nextTable;
    nextTable += PAGE;
    if( nextTable > DATA_BASE ) throw new OutOfMemoryError( "AArch64 HVF translation tables" );
    return result;
  }

  private long allocateData() {
    long result = nextData;
    nextData += PAGE;
    if( nextData > size ) throw new OutOfMemoryError( "AArch64 HVF guest RAM" );
    return result;
  }

  private long get64( long physical ) {
    return ram.get( ValueLayout.JAVA_LONG_UNALIGNED, physical );
  }

  private void put64( long physical, long value ) {
    ram.set( ValueLayout.JAVA_LONG_UNALIGNED, physical, value );
  }

  private static long alignUp( long value, long alignment ) {
    return Math.addExact( value, alignment - 1L ) & -alignment;
  }

  private void ensureOpen() {
    if( closed ) throw new IllegalStateException( "AArch64 HVF address space is closed" );
  }

  @Override public void close() {
    if( closed ) return;
    try {
      HvfAarch64Vm.freeGuestRam( ram, size );
    } catch( Throwable t ) {
      throw new IllegalStateException( "failed to free AArch64 HVF address space", t );
    } finally {
      closed = true;
    }
  }
}
