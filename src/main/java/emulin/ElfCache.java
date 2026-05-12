package emulin;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 34-A4: 同一 binary を繰り返し execve したときの ELF parse + file I/O
 * を cache する。1 つの JVM session 内で vim を 5 回起動するような典型 use
 * case で execve cost (Phase 27 で 84ms/call) を大幅削減する。
 *
 * Cache key: canonical path + file mtime + file size。mtime/size が変われば
 * cache miss として扱う (binary が更新された case)。
 *
 * Cache value: 64-bit ELF (ELFCLASS64) の parsed state スナップショット。
 *   - ELF header field
 *   - 各 segment の header + body byte[] (PT_LOAD のみ buf 持ち)
 *   - section header の brk 情報
 *   - interp_path
 *
 * Read-only segment (PF_X & !PF_W) の buf は cache → 各 exec instance で
 * reference share (Phase 32 と同じ思想)。Writable segment は exec ごとに
 * 新 byte[] を確保 + System.arraycopy で初期化する (caller が走らせると
 * すぐに書き換わるため)。
 *
 * Disabled: EMULIN_DISABLE_ELF_CACHE=1 で off。問題切り分け用。
 */
public final class ElfCache {
  public static final boolean ENABLED =
    System.getenv("EMULIN_DISABLE_ELF_CACHE") == null;

  // 1 section の cache データ (Section の field のみ、buf なし)
  public static final class SectionSnap {
    public final int  sh_name, sh_type;
    public final long sh_flags, sh_addr, sh_offset, sh_size;
    public final int  sh_link, sh_info;
    public final long sh_addralign, sh_entsize;
    SectionSnap( int sh_name, int sh_type, long sh_flags, long sh_addr,
                 long sh_offset, long sh_size, int sh_link, int sh_info,
                 long sh_addralign, long sh_entsize ) {
      this.sh_name = sh_name; this.sh_type = sh_type;
      this.sh_flags = sh_flags; this.sh_addr = sh_addr;
      this.sh_offset = sh_offset; this.sh_size = sh_size;
      this.sh_link = sh_link; this.sh_info = sh_info;
      this.sh_addralign = sh_addralign; this.sh_entsize = sh_entsize;
    }
  }

  // 1 segment の cache データ (header + 内容 byte[])
  public static final class SegmentSnap {
    public final int  p_type, p_flags;
    public final long p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align;
    /**
     * PT_LOAD の body byte[] (それ以外は null)。
     * Read-only (PF_X && !PF_W): full alloc_size (原 process と share)
     * Writable: 「データ部分 (filesz_ext) だけ」trim 済み。zero tail (bss) は
     *   loadFromCache 時に new byte[allocSize] の zero-init 任せで省略する。
     */
    public final byte[] body;
    /** writable 時に確保すべき buf 全体サイズ (= alloc_size、page-aligned)。
     *  read-only 時は body.length と同じ。 */
    public final int allocSize;
    SegmentSnap( int p_type, int p_flags, long p_offset, long p_vaddr, long p_paddr,
                 long p_filesz, long p_memsz, long p_align,
                 byte[] body, int allocSize ) {
      this.p_type   = p_type;   this.p_flags  = p_flags;
      this.p_offset = p_offset; this.p_vaddr  = p_vaddr;
      this.p_paddr  = p_paddr;  this.p_filesz = p_filesz;
      this.p_memsz  = p_memsz;  this.p_align  = p_align;
      this.body     = body;
      this.allocSize = allocSize;
    }
  }

  // 1 binary 全体の cache データ (ELF64 のみ)
  public static final class Entry {
    // identity
    public final long fileSize;
    public final long mtimeMs;
    // ELF header
    public final byte[] e_ident;
    public final short e_type, e_machine;
    public final int   e_version, e_flags;
    public final short e_ehsize, e_phentsize, e_phnum, e_shentsize, e_shnum, e_shstrndx;
    public final long  e_entry, e_phoff, e_shoff;
    // segments (snapshot of parsed segment[])。stack segment は含まず (exec ごとに作る)
    public final SegmentSnap[] segments;
    // sections (Process.dynamic_link で RELA scan 等に使われる)
    public final SectionSnap[] sections;
    // brk info (.bss section から計算した結果)
    public final long brk;
    public final boolean bss_found;
    // PT_INTERP path (動的 link なら non-null)
    public final String interp_path;

    public Entry( long fileSize, long mtimeMs,
                  byte[] e_ident, short e_type, short e_machine, int e_version, int e_flags,
                  short e_ehsize, short e_phentsize, short e_phnum,
                  short e_shentsize, short e_shnum, short e_shstrndx,
                  long e_entry, long e_phoff, long e_shoff,
                  SegmentSnap[] segments, SectionSnap[] sections,
                  long brk, boolean bss_found, String interp_path ) {
      this.fileSize    = fileSize; this.mtimeMs    = mtimeMs;
      this.e_ident     = e_ident;
      this.e_type      = e_type;     this.e_machine = e_machine;
      this.e_version   = e_version;  this.e_flags   = e_flags;
      this.e_ehsize    = e_ehsize;   this.e_phentsize = e_phentsize;
      this.e_phnum     = e_phnum;    this.e_shentsize = e_shentsize;
      this.e_shnum     = e_shnum;    this.e_shstrndx  = e_shstrndx;
      this.e_entry     = e_entry;    this.e_phoff     = e_phoff;
      this.e_shoff     = e_shoff;
      this.segments    = segments;
      this.sections    = sections;
      this.brk         = brk;
      this.bss_found   = bss_found;
      this.interp_path = interp_path;
    }
  }

  // path -> Entry
  private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();

  // 統計 (shutdown hook で dump)
  public static final java.util.concurrent.atomic.AtomicLong hits   = new java.util.concurrent.atomic.AtomicLong();
  public static final java.util.concurrent.atomic.AtomicLong misses = new java.util.concurrent.atomic.AtomicLong();
  static {
    if( ENABLED && System.getenv("EMULIN_PROFILE_ELFCACHE") != null ) {
      Runtime.getRuntime().addShutdownHook( new Thread( () -> {
        long h = hits.get(), m = misses.get();
        long tot = h + m;
        double pct = tot > 0 ? 100.0 * h / tot : 0.0;
        System.err.println( "===== EMULIN_PROFILE_ELFCACHE =====" );
        System.err.println( String.format(
          "ELF load: hits=%d misses=%d (%.1f%% hit rate, %d cached binaries)",
          h, m, pct, CACHE.size() ) );
        System.err.println( "===================================" );
      }, "EmulinElfCacheStats" ) );
    }
  }

  /**
   * Cache lookup。native path から canonical key を作って検索。
   * file が更新されている (mtime/size 不一致) 場合は cache 無効化して null。
   */
  public static Entry lookup( String nativePath ) {
    if( !ENABLED ) return null;
    File f = new File( nativePath );
    String key;
    try { key = f.getCanonicalPath(); }
    catch( java.io.IOException e ) { key = nativePath; }
    Entry e = CACHE.get( key );
    if( e == null ) {
      misses.incrementAndGet();
      return null;
    }
    // 整合性チェック
    if( f.length() != e.fileSize || f.lastModified() != e.mtimeMs ) {
      CACHE.remove( key );
      misses.incrementAndGet();
      return null;
    }
    hits.incrementAndGet();
    return e;
  }

  public static void put( String nativePath, Entry e ) {
    if( !ENABLED ) return;
    File f = new File( nativePath );
    String key;
    try { key = f.getCanonicalPath(); }
    catch( java.io.IOException ex ) { key = nativePath; }
    CACHE.put( key, e );
  }

  /** 単体テスト / 衛生対応用。通常 path では呼ばない。 */
  public static void clear() {
    CACHE.clear();
  }
}
