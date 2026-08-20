// ----------------------------------------
//  Linux AArch64 userspace structure layouts (asm-generic ABI)
// ----------------------------------------
package emulin;

final class Aarch64StructCodec {
  static final int STAT_SIZE = 128;
  static final int TIMESPEC_SIZE = 16;
  static final int RLIMIT_SIZE = 16;

  private Aarch64StructCodec() {}

  static void storeTimespec( MemoryBackend memory, long address, long seconds,
                             long nanoseconds ) {
    memory.store64( address, seconds );
    memory.store64( address + 8, nanoseconds );
  }

  static void storeRlimit( MemoryBackend memory, long address, long current,
                           long maximum ) {
    memory.store64( address, current );
    memory.store64( address + 8, maximum );
  }

  static void storeStat( MemoryBackend memory, long address, Inode inode ) {
    clear( memory, address, STAT_SIZE );
    memory.store64( address, inode.st_dev & 0xffffL );
    memory.store64( address + 8, inode.st_ino & 0xffffffffL );
    memory.store32( address + 16, inode.st_mode & 0xffff );
    memory.store32( address + 20, inode.st_nlink & 0xffff );
    memory.store32( address + 24, inode.st_uid & 0xffff );
    memory.store32( address + 28, inode.st_gid & 0xffff );
    memory.store64( address + 32, inode.st_rdev & 0xffffL );
    memory.store64( address + 48, inode.st_size );
    memory.store32( address + 56, inode.st_blksize );
    memory.store64( address + 64, inode.st_blocks );
    storeTimespec( memory, address + 72, inode.st_atime, inode.st_atime_nsec );
    storeTimespec( memory, address + 88, inode.st_mtime, inode.st_mtime_nsec );
    storeTimespec( memory, address + 104, inode.st_ctime, inode.st_ctime_nsec );
  }

  static void storeSpecialStat( MemoryBackend memory, long address, int mode,
                                long device, long size ) {
    clear( memory, address, STAT_SIZE );
    memory.store64( address, 1 );
    memory.store64( address + 8, 1 );
    memory.store32( address + 16, mode );
    memory.store32( address + 20, 1 );
    memory.store64( address + 32, device );
    memory.store64( address + 48, size );
    memory.store32( address + 56, 4096 );
    memory.store64( address + 64, (size + 511) / 512 );
    long now = System.currentTimeMillis() / 1000L;
    storeTimespec( memory, address + 72, now, 0 );
    storeTimespec( memory, address + 88, now, 0 );
    storeTimespec( memory, address + 104, now, 0 );
  }

  private static void clear( MemoryBackend memory, long address, int bytes ) {
    memory.bulkZero( address, bytes );
  }
}
