// ----------------------------------------
//  Elf ( support ELF format (32bit for i[34]86))
//
//  Copyright (C) 1999  Kiyoka Nishiyama
//
//  $Date: 1999/11/30 17:23:08 $ 
//  $Id: Elf.java,v 1.24 1999/11/30 17:23:08 kiyoka Exp $
// ----------------------------------------
package emulin;

//
// ELFヘッダ
//
// typedef struct elf32_hdr{
//   EI_NIDENT = 16;
//   char	e_ident[EI_NIDENT];      :  e_ident[0...3] = '\x7f' "ELF" であること
//   Elf32_Half	e_type;                  :  ET_EXEC であること
//   Elf32_Half	e_machine;               :  EM_386 であること (将来 EM_486サポートする)
//   Elf32_Word	e_version;
//   Elf32_Addr	e_entry;  /* Entry point */
//   Elf32_Off	e_phoff;
//   Elf32_Off	e_shoff;
//   Elf32_Word	e_flags;
//   Elf32_Half	e_ehsize;
//   Elf32_Half	e_phentsize;
//   Elf32_Half	e_phnum;
//   Elf32_Half	e_shentsize;
//   Elf32_Half	e_shnum;
//   Elf32_Half	e_shstrndx;
// } Elf32_Ehdr;

// Emulin のセグメントの扱い
//  1. stack
//     アドレス ~0 から 適当なメモリを確保する。
//     ELFのなかに stackセグメントは無いので,Elfクラスで追加生成する。
//  2. それ以外のセグメント
//     セグメントのWRX属性にしたがって,アクセス制限を行う。
//     当然 entry ポイントは X属性のセグメントでなければエラーとする。

import java.lang.*;
import java.io.*;
import emulin.*;

public class Elf
{
  Process process;

  // e_ident[EI_CLASS]: ELF クラス識別
  static final int EI_CLASS    = 4;
  static final byte ELFCLASS32 = 1;   /* 32-bit objects */
  static final byte ELFCLASS64 = 2;   /* 64-bit objects */

  static short ET_NONE = 0;		/* No file type */
  static short ET_REL  = 1;		/* Relocatable file */
  static short ET_EXEC = 2;		/* Executable file */
  static short ET_DYN  = 3;		/* Shared object file */
  static short ET_CORE = 4;		/* Core file */
  static short ET_NUM  = 5;		/* Number of defined types.  */

  /* These constants define the various ELF target machines */
  static short EM_386    = 3;
  static short EM_486    = 6;    /* Perhaps disused */
  static short EM_X86_64 = 62;  /* AMD/Intel x86-64 */

  byte  e_ident[] = new byte[16];           //   :  e_ident[0...3] = '\x7f' "ELF" であること
  short	e_type;
  short	e_machine;
  int e_version;
  long e_entry;             // Entry point
  long e_phoff;
  long e_shoff;
  int e_flags;
  short	e_ehsize;
  short	e_phentsize;
  short	e_phnum;
  short e_shentsize;
  short e_shnum;
  short e_shstrndx;
  String symbol[];
  long symadrs[];
  int symbols;
  Segment[] segment;       // セグメント
  int segments;            // 総セグメント数
  Section[] section;       // セクション
  int sections;            // 総セクション数
  long brk;                // 現在の brk アドレス
  int brk_segment_no;      // brkの存在する セグメント番号
  // Phase 24 step 1a: PT_INTERP (動的リンカ) のパス。
  // 動的リンク ELF (PT_INTERP=3 を持つ) のときだけセットされる。
  // 静的リンクの ELF では null。
  public String interp_path = null;
  // Phase 24 step 1b/1c: load_interp が成功したらセットされる
  // 動的リンカの load base (auxv AT_BASE で参照)。
  public long interp_base = 0;
  Sysinfo sysinfo;


  // 指定インスタンスの情報で自分をアップデートする。
  public void update_info( Elf _elf ) {
    int i;
    System.arraycopy( _elf.e_ident, 0, e_ident, 0, _elf.e_ident.length );
    e_type       = _elf.e_type       ;
    e_machine    = _elf.e_machine    ;
    e_version    = _elf.e_version    ;
    e_entry      = _elf.e_entry      ;
    e_phoff      = _elf.e_phoff      ;
    e_shoff      = _elf.e_shoff      ;
    e_flags      = _elf.e_flags      ;
    e_ehsize     = _elf.e_ehsize     ;
    e_phentsize  = _elf.e_phentsize  ;
    e_phnum      = _elf.e_phnum      ;
    e_shentsize  = _elf.e_shentsize  ;
    e_shnum      = _elf.e_shnum      ;
    e_shstrndx   = _elf.e_shstrndx   ;
    // シンボルのコピー
    symbols      = _elf.symbols      ;
    symbol       = new String[ _elf.symbols ];
    symadrs      = new long[ _elf.symbols ];
    if( sysinfo.debug( )) {
      process.println( "  Elf.update_info : symbols = [ " + symbols + " ] " );
    }
    for( i = 0 ; i < symbols ; i++ ) {
      if( sysinfo.debug( )) {
	process.println( "  Elf.update_info : symbol[ " + i + " ]  = " + _elf.symbol[i] + " " );
      }
      if( null != _elf.symbol[i] ) {
	symbol[i]  = new String( _elf.symbol[i] );
	symadrs[i] = _elf.symadrs[i];
      }
    }
    if( sysinfo.verbose( )) {
      process.println( "  Elf.update_info : Symbol copyied 1" );
    }
    // セグメント
    segments = _elf.segments;
    segment  = new Segment[ segments ];
    for( i = 0 ; i < segments ; i++ ) {
      segment[i] = _elf.segment[i].duplicate( );
    }
    // セクション
    sections = _elf.sections;
    section  = new Section[ sections ];
    for( i = 0 ; i < segments ; i++ ) {
      section[i] = _elf.section[i].duplicate( );
    }
    brk            = _elf.brk;               // 現在の brk アドレス
    brk_segment_no = _elf.brk_segment_no;    // brkの存在する セグメント番号
  }

  public boolean load_symbol( String filename ) {
    String buf = "";
    String adrs_str = "";
    int i;
    RandomAccessFile in;
    try { in = new RandomAccessFile( sysinfo.get_native_path( filename ), "r" ); }
    catch ( IOException m ) {
      if( sysinfo.debug( )) {
	process.println( "Can't file open :" + filename );
      }
      symbols = 0;
      return( false );   
    }
    // 行数の確認
    for( i = 0 ; null != buf ; i++ ) {
      try { buf = in.readLine( ); }
      catch ( IOException m ) {  process.println( "File read error" ); return( false ); }
    }
    symbol  = new String[i];
    symadrs = new long[i];
    // 読み込み
    try{ in.seek( 0 ); }
    catch ( IOException m ) {  process.println( "Seek Failed :" + filename ); return( false ); }    

    buf = "";
    for( i = 0 ; null != buf ; i++ ) {
      try { buf = in.readLine( ); }
      catch ( IOException m ) {  process.println( "File read error" ); return( false ); }
      if( buf != null ) {
	adrs_str = buf.substring( 0, 8 );
	if( ! adrs_str.equals( "        " )) {
	  symadrs[ i ] = Long.parseLong( adrs_str, 16);
	  symbol[ i ] = buf.substring( 11 );
	  if( sysinfo.debug( )) {
	    //	    process.println( "adrs = " + Util.hexstr( symadrs[i], 8 ) + "  symbol = " + symbol[i] );
	  }
	}
      }
    }
    symbols = i;
    return( true );
  }

  public String get_symbol( long address ) {
    int i;
    String ret = null;
    for( i = 0 ; i < symbols ; i++ ) {
      if( address == symadrs[i] ) {
	ret = symbol[i];
      }
    }
    return( ret );
  }

  // エントリーアドレスを返す
  public long get_entry( ) {
    return( e_entry );
  }

  // brk値(データセグメントの最後のアドレス)を返す
  public long get_curbrk( ) {
    return( brk );
  }

  // brk値を更新する
  public boolean set_curbrk( long _brk ) {
    if( segment[ brk_segment_no ].expand_memory( _brk )) {
      brk = _brk;
    }
    return( true );
  }

  // ロードする: e_ident を読んで ELF32 / ELF64 に分岐する
  public boolean load( String filename ) {
    RandomAccessFile in;
    try { in = new RandomAccessFile( sysinfo.get_native_path( filename ), "r" ); }
    catch ( IOException m ) {  process.println( "Can't file open :" + filename );  return( false ); }

    LoadUtil.bytes( in, e_ident, sysinfo.kernel );

    if( !((e_ident[0] == 0x7F) && (e_ident[1] == 'E') && (e_ident[2] == 'L') && (e_ident[3] == 'F')) ) {
      process.println( "Not Elf Format :" + filename ); return( false );
    }
    if( e_ident[EI_CLASS] == ELFCLASS32 ) {
      return( load32( in, filename ) );
    }
    else if( e_ident[EI_CLASS] == ELFCLASS64 ) {
      return( load64( in, filename ) );
    }
    else {
      process.println( "Unknown ELF class " + e_ident[EI_CLASS] + ": " + filename ); return( false );
    }
  }

  // ELF32 ロード本体 (e_ident 読み取り済みの続き)
  private boolean load32( RandomAccessFile in, String filename ) {
    int i;
    e_type        =   LoadUtil.little16( in, sysinfo.kernel );
    e_machine     =   LoadUtil.little16( in, sysinfo.kernel );
    e_version     =        LoadUtil.little32( in, sysinfo.kernel );
    e_entry       = (long) LoadUtil.little32( in, sysinfo.kernel ) & 0xFFFFFFFFL;
    e_phoff       = (long) LoadUtil.little32( in, sysinfo.kernel ) & 0xFFFFFFFFL;
    e_shoff       = (long) LoadUtil.little32( in, sysinfo.kernel ) & 0xFFFFFFFFL;
    e_flags       =        LoadUtil.little32( in, sysinfo.kernel );
    e_ehsize      =   LoadUtil.little16( in, sysinfo.kernel );
    e_phentsize   =   LoadUtil.little16( in, sysinfo.kernel );
    e_phnum       =   LoadUtil.little16( in, sysinfo.kernel );
    e_shentsize   =   LoadUtil.little16( in, sysinfo.kernel );
    e_shnum       =   LoadUtil.little16( in, sysinfo.kernel );
    e_shstrndx    =   LoadUtil.little16( in, sysinfo.kernel );

    if( sysinfo.debug( )) {
      process.println( "File [" + filename + "] (ELF32)" );
      process.println( "----- Elf32 Header -----" );
      process.println( "e_type        : " + Integer.toString( e_type,      16));
      process.println( "e_machine     : " + Integer.toString( e_machine,   16));
      process.println( "e_version     : " + Integer.toString( e_version,   16));
      process.println( "e_entry       : " + Long.toString(    e_entry,     16));
      process.println( "e_phentsize   : " + Integer.toString( e_phentsize, 16));
      process.println( "e_phnum       : " + Integer.toString( e_phnum,     16));
      process.println( "e_phoff       : " + Long.toString(    e_phoff,     16));
      process.println( "e_shnum       : " + Integer.toString( e_shnum,     16));
      process.println( "e_shoff       : " + Long.toString(    e_shoff,     16));
    }

    if( e_type != ET_EXEC ) {
      process.println( "Not Executable Format :" + filename ); return( false );
    }
    if( e_machine != EM_386 ) {
      process.println( "Not Match CPU Type :" + filename ); return( false );
    }

    // プログラムヘッダを読み込む
    try{ in.seek( e_phoff ); }
    catch ( IOException m ) {  process.println( "Seek Failed :" + filename ); return( false ); }

    segments = e_phnum + 1;
    segment = new Segment[ segments ];
    for( i = 0 ; i < segments ; i++ ) {
      segment[i] = new Segment( sysinfo, process );
      if( i < e_phnum ) {
        segment[i].load_ph( in );
      }
      else {
        segment[i].stack( sysinfo.get_stack_bottom(), Sysinfo.stack_size );
      }
    }
    for( i = 0 ; i < e_phnum ; i++ ) {
      segment[i].load_body( in );
    }

    // セクションヘッダを読み込む
    try{ in.seek( e_shoff ); }
    catch ( IOException m ) {  process.println( "Seek Failed :" + filename ); return( false ); }
    sections = e_shnum;
    section = new Section[ sections ];
    for( i = 0 ; i < sections ; i++ ) {
      section[i] = new Section( sysinfo, process );
      section[i].load( in );
      if( section[i].isbss( )) {
        brk = section[i].get_brk( );
      }
    }

    // brk アドレスが含まれるセグメントを探す
    for( i = 0 ; i < segments ; i++ ) {
      if( brk == segment[i].segment_end( )) {
        brk_segment_no = i;
      }
    }

    if( sysinfo.debug( )) {
      process.println( " ----- BRK ----- " );
      process.println( "   brk adrs       = " + Util.hexstr( brk, 8 ));
      process.println( "   brk segment no = " + Util.hexstr( brk_segment_no, 8 ));
    }
    return( true );
  }

  // ELF64 ロード本体 (e_ident 読み取り済みの続き)
  private boolean load64( RandomAccessFile in, String filename ) {
    int i;
    // ELF64 ヘッダ: e_entry/e_phoff/e_shoff が 8 バイト
    e_type        =        LoadUtil.little16( in, sysinfo.kernel );
    e_machine     =        LoadUtil.little16( in, sysinfo.kernel );
    e_version     =        LoadUtil.little32( in, sysinfo.kernel );
    e_entry       =        LoadUtil.little64( in, sysinfo.kernel );
    e_phoff       =        LoadUtil.little64( in, sysinfo.kernel );
    e_shoff       =        LoadUtil.little64( in, sysinfo.kernel );
    e_flags       =        LoadUtil.little32( in, sysinfo.kernel );
    e_ehsize      =        LoadUtil.little16( in, sysinfo.kernel );
    e_phentsize   =        LoadUtil.little16( in, sysinfo.kernel );
    e_phnum       =        LoadUtil.little16( in, sysinfo.kernel );
    e_shentsize   =        LoadUtil.little16( in, sysinfo.kernel );
    e_shnum       =        LoadUtil.little16( in, sysinfo.kernel );
    e_shstrndx    =        LoadUtil.little16( in, sysinfo.kernel );

    if( sysinfo.debug( )) {
      process.println( "File [" + filename + "] (ELF64)" );
      process.println( "----- Elf64 Header -----" );
      process.println( "e_type        : " + Integer.toString( e_type,      16));
      process.println( "e_machine     : " + Integer.toString( e_machine,   16));
      process.println( "e_version     : " + Integer.toString( e_version,   16));
      process.println( "e_entry       : " + Long.toString(    e_entry,     16));
      process.println( "e_phentsize   : " + Integer.toString( e_phentsize, 16));
      process.println( "e_phnum       : " + Integer.toString( e_phnum,     16));
      process.println( "e_phoff       : " + Long.toString(    e_phoff,     16));
      process.println( "e_shnum       : " + Integer.toString( e_shnum,     16));
      process.println( "e_shoff       : " + Long.toString(    e_shoff,     16));
    }

    if( e_type != ET_EXEC ) {
      process.println( "Not Executable Format :" + filename ); return( false );
    }
    if( e_machine != EM_X86_64 ) {
      process.println( "Not Match CPU Type (expected x86-64) :" + filename ); return( false );
    }

    // ELF64 プログラムヘッダを読み込む (Elf64_Phdr = 56 バイト)
    try{ in.seek( e_phoff ); }
    catch ( IOException m ) {  process.println( "Seek Failed :" + filename ); return( false ); }

    segments = e_phnum + 1;
    segment = new Segment[ segments ];
    for( i = 0 ; i < segments ; i++ ) {
      segment[i] = new Segment( sysinfo, process );
      if( i < e_phnum ) {
        segment[i].load_ph64( in );
      }
      else {
        segment[i].stack( sysinfo.get_stack_bottom_64(), Sysinfo.stack_size );
      }
    }
    // Phase 24 step 1b 関連: PT_LOAD (= 1) のみ実メモリにマップする。
    // 以前は全 program header を load_body していたため、PT_PHDR (vaddr=0x400040)
    // 等が先頭近くにあるダイナミックバイナリで PT_LOAD と同じページに重なり、
    // Memory.peekb 走査で PHDR セグメントの未初期化バッファを誤って返していた
    // (例: hello_dyn_nopie で 0x400480 の verneed を読んで全 0 になる)。
    // PT_PHDR / PT_INTERP / PT_DYNAMIC / PT_NOTE / PT_GNU_* 等はメモリ
    // マップ不要で、メタデータとして p_vaddr などのフィールドだけ残せば十分。
    for( i = 0 ; i < e_phnum ; i++ ) {
      if( segment[i].p_type == 1 /* PT_LOAD */ ) {
        segment[i].load_body( in );
      }
    }

    // Phase 24 step 1a: PT_INTERP (= 3) を探して動的リンカパスを読む。
    // PT_INTERP セグメントは ELF ファイル内の NUL 終端文字列で、
    // 通常 "/lib64/ld-linux-x86-64.so.2"。動的ロードはまだ未実装で、
    // ここでは検出してフィールドに保存・printlnするだけ。
    for( i = 0 ; i < e_phnum ; i++ ) {
      if( segment[i].p_type != 3 /* PT_INTERP */ ) continue;
      try {
        in.seek( segment[i].p_offset );
        int sz = (int)segment[i].p_filesz;
        if( sz <= 0 || sz > 4096 ) break;  // sanity check
        byte[] buf = new byte[sz];
        in.readFully( buf );
        // NUL 終端を切り捨てる
        int len = sz;
        while( len > 0 && buf[len - 1] == 0 ) len--;
        interp_path = new String( buf, 0, len, java.nio.charset.StandardCharsets.UTF_8 );
        if( sysinfo.verbose( ) ) {
          process.println( "ELF interpreter (PT_INTERP): " + interp_path );
        }
      } catch( IOException m ) {
        process.println( "PT_INTERP read failed: " + m.getMessage() );
      }
      break;  // 最初の PT_INTERP のみ採用 (通常 1 つ)
    }

    // ELF64 セクションヘッダを読み込む (Elf64_Shdr = 64 バイト)
    try{ in.seek( e_shoff ); }
    catch ( IOException m ) {  process.println( "Seek Failed :" + filename ); return( false ); }
    sections = e_shnum;
    section = new Section[ sections ];
    boolean bss_found = false;
    for( i = 0 ; i < sections ; i++ ) {
      section[i] = new Section( sysinfo, process );
      section[i].load64( in );
      if( section[i].isbss( )) {
        brk = section[i].get_brk( );
        bss_found = true;
      }
    }

    // .bss が無い ELF (-nostdlib のシンプルなテストバイナリ等) では
    // 一番高い LOAD プログラムヘッダの末尾を brk の初期値とする。
    // (stack セグメントは index e_phnum なので除外。)
    if( !bss_found ) {
      long max_end = 0;
      int  max_idx = 0;
      for( i = 0; i < e_phnum; i++ ) {
        if( segment[i].p_type != 1 /* PT_LOAD */ ) continue;
        long end = segment[i].segment_end( );
        if( end > max_end ) { max_end = end; max_idx = i; }
      }
      brk = max_end;
      brk_segment_no = max_idx;
    }
    else {
      // brk アドレスが含まれるセグメントを探す (.bss 末尾の生アドレスで照合)
      for( i = 0 ; i < segments ; i++ ) {
        if( brk == segment[i].segment_end( )) {
          brk_segment_no = i;
        }
      }
    }

    // Linux カーネルは ELF ロード時に brk をページ境界に切り上げる。
    // glibc malloc は初期 brk がページ境界揃えであることを前提に top chunk を
    // 配置するため、これを再現しないと「corrupted top size」検出で abort する。
    final long PAGE = 0x1000L;
    long brk_aligned = (brk + PAGE - 1) & ~(PAGE - 1);
    if( brk_aligned != brk ) {
      segment[ brk_segment_no ].expand_memory( brk_aligned );
      brk = brk_aligned;
    }

    if( sysinfo.debug( )) {
      process.println( " ----- BRK ----- " );
      process.println( "   brk adrs       = " + Util.hexstr( brk, 16 ));
      process.println( "   brk segment no = " + Util.hexstr( brk_segment_no, 8 ));
    }
    return( true );
  }

  // Phase 24 step 1b: 動的リンカ (PT_INTERP) を `base` に load する。
  //
  //   - interp は通常 ET_DYN (e.g. ld-linux-x86-64.so.2)。 PT_LOAD の
  //     p_vaddr は 0 起点なので、ロード時に base を加算する。
  //   - 読み込んだ各 PT_LOAD セグメントを既存 segment[] 末尾に追記する
  //     (Memory はインデックスを順に走査して該当を探すので追加するだけで
  //     アクセス可能になる)。
  //   - 戻り値: 成功なら interp の絶対 entry point (base + e_entry)、
  //     失敗なら 0。
  //
  // path はホスト側の絶対パスを渡す前提 (sandbox 解決は呼び出し側責務)。
  // step 1b 時点では auxv 連携は未実装。step 1c で AT_BASE / AT_PHDR /
  // AT_ENTRY を整備する。
  public long load_interp( String path, long base ) {
    java.io.RandomAccessFile in = null;
    try {
      in = new java.io.RandomAccessFile( path, "r" );
    } catch( IOException e ) {
      process.println( "load_interp: open failed: " + path );
      return 0;
    }
    try {
      // ELF64 ヘッダを読む (我々が呼ぶのは PT_INTERP セグメントが指す
      // 動的リンカで、必ず ELF64 / x86-64 のはず)
      byte[] ident = new byte[16];
      in.readFully( ident );
      if( ident[0] != 0x7F || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F' ) {
        process.println( "load_interp: not an ELF: " + path );
        return 0;
      }
      // ELF64 ヘッダ残り
      LoadUtil.little16( in, sysinfo.kernel );             // e_type (DYN/EXEC どちらでも進む)
      int    interp_machine = LoadUtil.little16( in, sysinfo.kernel );
      LoadUtil.little32( in, sysinfo.kernel );             // e_version
      long   interp_entry   = LoadUtil.little64( in, sysinfo.kernel );
      long   interp_phoff   = LoadUtil.little64( in, sysinfo.kernel );
      LoadUtil.little64( in, sysinfo.kernel );             // e_shoff
      LoadUtil.little32( in, sysinfo.kernel );             // e_flags
      LoadUtil.little16( in, sysinfo.kernel );             // e_ehsize
      LoadUtil.little16( in, sysinfo.kernel );             // e_phentsize
      int    interp_phnum   = LoadUtil.little16( in, sysinfo.kernel );
      // 残り (e_shentsize / e_shnum / e_shstrndx) は使わないので読み飛ばし不要
      if( interp_machine != EM_X86_64 ) {
        process.println( "load_interp: machine != x86-64: " + path );
        return 0;
      }

      // 既存 segment[] を拡張するための新配列を作る。stack セグメント
      // (index e_phnum) は最後に来る前提なので、interp セグメントは
      // その手前に挿入する必要がある。具体的には:
      //   旧 [0..e_phnum-1] = 本体 PT_LOAD 群
      //   旧 [e_phnum]      = stack
      //   新 [0..e_phnum-1]      = 本体 PT_LOAD
      //   新 [e_phnum..e_phnum+N-1] = interp の PT_LOAD (N 個)
      //   新 [e_phnum+N]    = stack
      java.util.ArrayList<Segment> interp_loads = new java.util.ArrayList<>();
      in.seek( interp_phoff );
      // 各 PT_LOAD を読み込む
      for( int i = 0; i < interp_phnum; i++ ) {
        Segment s = new Segment( sysinfo, process );
        s.load_ph64( in );
        if( s.p_type != 1 /* PT_LOAD */ ) continue;
        // base を加算
        s.p_vaddr += base;
        s.p_paddr += base;
        interp_loads.add( s );
      }
      // load_body は in.seek/read で本体をコピーする
      for( Segment s : interp_loads ) s.load_body( in );

      // 既存 segment[] と stack の間に interp_loads を割り込ませる
      Segment stack_seg = segment[ segments - 1 ]; // 末尾は stack
      Segment[] merged = new Segment[ segments + interp_loads.size() ];
      System.arraycopy( segment, 0, merged, 0, segments - 1 );  // 本体 PT_LOAD
      for( int i = 0; i < interp_loads.size(); i++ ) {
        merged[ segments - 1 + i ] = interp_loads.get( i );
      }
      merged[ merged.length - 1 ] = stack_seg;
      segment  = merged;
      segments = merged.length;

      long abs_entry = base + interp_entry;
      interp_base = base;     // auxv AT_BASE 用に保持
      if( sysinfo.verbose( ) ) {
        process.println( "  load_interp: " + path + " base=0x" + Long.toHexString( base )
                         + " entry=0x" + Long.toHexString( abs_entry )
                         + " (" + interp_loads.size() + " PT_LOAD)" );
      }
      return abs_entry;
    } catch( IOException e ) {
      process.println( "load_interp: I/O error: " + e.getMessage() );
      return 0;
    } finally {
      try { in.close(); } catch( IOException ignore ) {}
    }
  }
}
