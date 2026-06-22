// ----------------------------------------
//  Emulin WinCaseMap (issue #349)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

// issue #349: Windows NTFS は既定で case-insensitive なので、Linux package が持つ
//   大小文字違いの同名 file (manpages-dev の regular `_exit.2.gz` と、それを指す
//   symlink `_Exit.2.gz`) が同一 directory に共存できず、dpkg unpack が失敗する。
//
//   per-directory case-sensitivity (NtfsEnableDirCaseSensitivity) は registry +
//   管理者権限 + 再起動を要求し配布上の弱点になるため使わず、emulin 自身が
//   衝突した file 名だけを可逆エンコードして host FS 上で別名にする。
//   既存の予約文字スキーム (CygSymlink.encodeReservedPath、`:`→U+F03A) と同流儀で
//   ASCII letter [A-Za-z] を U+F000+byte の private-use area へ escape する。
//   (予約文字 : * ? " < > | \ の U+F0xx とはコード範囲が重ならないので両者は併用可。)
//
//   方針 (衝突したものだけ encode):
//     - create (open O_CREAT / symlink / rename dst / mkdir) 時に「異 case の sibling が
//       既に host 上に在る」ことを検出したら、その新規 leaf の全 letter を escape した
//       別名で作成し、(親 dir, 元 leaf) → on-disk 名 を in-memory map に登録する。
//     - read 系 path 解決 (Mount.native_path_raw) は (a) 登録済みの leaf を on-disk 名へ置換し、
//       (b) leaf が「既に create した異 case sibling」と衝突するなら encode 名 (未作成 path) へ
//       redirect する。後者は dpkg が symlink 作成前に行う lstat/unlink が plain 名で regular file に
//       alias して content を壊すのを防ぐ (ENOENT 化)。create が 1 件も無ければ完全 no-op (hot path)。
//     - getdents は on-disk の encode 名を decode して guest に元名で見せ、同時に map 登録。
//   pre-install の rootfs は内部に case 衝突を持たない (zip 展開できている) ので encode
//   対象にならず、既存 file は素の名前のまま見える。非 Windows (CygSymlink 無効) は全 no-op。
//
//   既知の限界: encode 済み file を「一度も getdents/create していない別プロセス」で
//   いきなり絶対 path open する場合は map が空で plain 解決され、NTFS が別 case の file へ
//   alias する (man page を index 経由で直接開く稀な経路)。install 完走と同一プロセス内の
//   read/一覧は完全に正しい。必要なら将来 dir 単位 lazy scan を足す。
class WinCaseMap {
  static final boolean TRACE = System.getenv( "EMULIN_TRACE_CASEMAP" ) != null;
  // EMULIN_NO_CASEMAP=1 で case-collision encode 全体を無効化 (A/B / escape hatch)。
  private static final boolean DISABLED = System.getenv( "EMULIN_NO_CASEMAP" ) != null;

  // 親 dir native path (末尾 separator 無し) → ( 元 leaf [reserved-encoded, case 未encode] → on-disk leaf )
  private static final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> enc =
      new ConcurrentHashMap<>();
  // 親 dir → ( lowercase leaf → 最初にその case-fold slot を占有した実 leaf )。
  //   emulin 自身が create した名前の履歴。異 case の 2 つ目が来たら衝突と判定する
  //   (disk 状態に依存しない = open 中 file や NTFS case probe の不確実性を回避)。
  private static final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> seen =
      new ConcurrentHashMap<>();
  // 1 件でも衝突が登録されたら true。read 経路 (mapPath) の gate。
  static volatile boolean anyCollisions = false;
  // emulin が file を 1 つでも create したら true (seen 非空)。mapPath の seen-redirect 有効化 gate。
  //   pure-read プロセス (create 無し) では false のまま → mapPath 完全 no-op。
  static volatile boolean anyCreates = false;
  // issue #369: build 時に case-collision を pre-encode した bundle (Mount が marker 検出で有効化) では、
  //   getdents/create を経ない直接 open (dpkg -L/-V / gcc が header を絶対 path open / man 等) でも encode 名を
  //   解決するため、read 経路 (mapPath) で leaf の親 dir を dir 単位 lazy scan する。marker 無し (= pre-encode
  //   していない通常 bundle) では false のまま → 従来どおり read は無コスト (File.list を一切しない)。
  static volatile boolean readScan = false;
  static void enableReadScan() {
    readScan = true;
    // issue #369 review #4: pre-encoded bundle (marker あり) では encode 名の decode が casemap に依存する。
    //   EMULIN_NO_CASEMAP=1 (DISABLED) で起動すると mapPath/getdents が no-op になり、衝突 file の片方
    //   (PUA encode 名) が一切読めなくなる (#349 の「素に戻る」安全動作と違い破壊的)。誤設定を明示警告。
    if( DISABLED )
      System.err.println( "[casemap] WARN: pre-encoded bundle ですが EMULIN_NO_CASEMAP=1 で decode 無効 → "
          + "case 衝突 file の encode 側が読めません (EMULIN_NO_CASEMAP を外すか非 pre-encoded bundle を使用)" );
    else if( TRACE )
      System.err.println( "[casemap] read-scan enabled (pre-encoded bundle marker 検出)" );
  }
  // issue #394: dir 単位 disk-prime 済みフラグ (resolveCreate の prime を dir ごと 1 回に絞る)。
  private static final java.util.Set<String> primed = ConcurrentHashMap.newKeySet();

  private static boolean on( ) { return CygSymlink.enabled() && !DISABLED; }
  private static final char SEP = File.separatorChar;

  // ---- case encode / decode (ASCII letter ⇔ U+F000+byte) ----
  static String encodeCase( String s ) {
    if( s == null ) return null;
    int n = s.length();
    int i = 0;
    for( ; i < n; i++ ) { char c = s.charAt( i ); if( ( c >= 'A' && c <= 'Z' ) || ( c >= 'a' && c <= 'z' ) ) break; }
    if( i == n ) return s;                          // letter 無し (ほぼ無い) → 無 alloc
    StringBuilder sb = new StringBuilder( n );
    sb.append( s, 0, i );
    for( ; i < n; i++ ) {
      char c = s.charAt( i );
      sb.append( ( ( c >= 'A' && c <= 'Z' ) || ( c >= 'a' && c <= 'z' ) ) ? (char)( 0xF000 + c ) : c );
    }
    return sb.toString();
  }

  private static boolean isEncodedChar( char c ) {
    return ( c >= 0xF041 && c <= 0xF05A ) || ( c >= 0xF061 && c <= 0xF07A );
  }

  static boolean isCaseEncoded( String s ) {
    if( s == null ) return false;
    for( int i = 0; i < s.length(); i++ ) if( isEncodedChar( s.charAt( i ) ) ) return true;
    return false;
  }

  static String decodeCase( String s ) {
    if( s == null ) return null;
    int n = s.length();
    int i = 0;
    for( ; i < n; i++ ) if( isEncodedChar( s.charAt( i ) ) ) break;
    if( i == n ) return s;                          // encode 済み無し → 無 alloc
    StringBuilder sb = new StringBuilder( n );
    sb.append( s, 0, i );
    for( ; i < n; i++ ) {
      char c = s.charAt( i );
      sb.append( isEncodedChar( c ) ? (char)( c - 0xF000 ) : c );
    }
    return sb.toString();
  }

  // ---- map 操作 ----
  private static void register( String dir, String leaf, String onDisk ) {
    enc.computeIfAbsent( dir, k -> new ConcurrentHashMap<>() ).put( leaf, onDisk );
    anyCollisions = true;
    if( TRACE ) System.err.println( "[casemap] register " + dir + " : " + leaf + " -> " + onDisk );
  }

  // issue #394: create 直前に親 dir を 1 回だけ on-disk スキャンし、既存 leaf を seen/enc に先取り登録する。
  //   emulin 起動前に launcher の Windows tar が NTFS へ直書きした plain file (coreutils head 等) は seen に
  //   居らず、後発の異 case create (perl HEAD = libwww-perl が apt 依存で入れる lwp-request symlink) を衝突と
  //   判定できず、NTFS が同一 case-fold slot へ fold して実体を上書きしていた (上記「既知の限界」)。plain leaf に
  //   case-fold slot を占有させて実体を勝たせ、既存 encode 名 (PUA) は別 NTFS file なので slot は占有させず
  //   read 解決にのみ登録する。on() かつ create path でのみ走り、pure-read プロセスは無コスト。
  private static void primeDir( String parent ) {
    if( !primed.add( parent ) ) return;                 // dir ごと 1 回だけ
    String[] kids = new File( parent ).list();
    if( kids == null ) return;
    ConcurrentHashMap<String, String> sm = seen.computeIfAbsent( parent, k -> new ConcurrentHashMap<>() );
    for( String k : kids ) {
      if( isCaseEncoded( k ) ) register( parent, decodeCase( k ), k );  // 既存 encode 名 → read 解決 (plain slot 非占有)
      else                     sm.putIfAbsent( k.toLowerCase(), k );    // plain leaf が case-fold slot を先取り
    }
  }

  // issue #369: read 経路 (mapPath、複数 vCPU から並行) 専用の dir scan。primeDir (create 専用) を read から
  //   流用すると不具合があった: ① primed.add を register より前に立てるため、並行 caller が prime 途中で enc
  //   空のまま skip して encode 名を plain 解決 → NTFS 別 case alias/ENOENT。② seen を全 scanned dir 分 populate
  //   して mapPath の seen-redirect が非衝突 dir の sloppy-case open (Makefile↔makefile) まで ENOENT 化。
  //   read には enc 登録だけで足りるので seen は触らず、readScanned へのマークは register 完了「後」に行う
  //   (並行 caller は冗長 scan か完了済を見るが、いずれも自スレッドで register 済 → enc は必ず populate 済)。
  //   create 用 primed とは別 set なので、後続の create 経路 primeDir(seen 占有) を阻害しない。
  private static final java.util.Set<String> readScanned = ConcurrentHashMap.newKeySet();
  private static void scanForRead( String parent ) {
    if( readScanned.contains( parent ) ) return;        // 完了済 dir は skip (再 list しない)
    String[] kids = new File( parent ).list();
    if( kids != null )
      for( String k : kids )
        if( isCaseEncoded( k ) ) register( parent, decodeCase( k ), k );   // enc-only (read 解決、seen は触らない)
    readScanned.add( parent );                          // ★ register 完了後に mark (race 回避)
  }

  // read 経路: native path の各 component を on-disk 名へ解決する。
  //   create が 1 件も無ければ即返し (hot path; pure-read プロセスは無コスト)。
  static String mapPath( String nativePath ) {
    if( !on() || nativePath == null ) return nativePath;
    if( !readScan && !anyCollisions && !anyCreates ) return nativePath;   // readScan=pre-encoded bundle (issue #369)
    if( nativePath.indexOf( SEP ) < 0 ) return nativePath;
    // separator 単位で walk し、component を解決する (regex 不使用)。
    //   cur は常に末尾 separator 無しの親 path に保つ (登録キーと一致させる)。
    StringBuilder cur = new StringBuilder( nativePath.length() + 16 );
    boolean changed = false;
    int n = nativePath.length();
    int start = 0;
    while( start <= n ) {
      int next = nativePath.indexOf( SEP, start );
      int end = ( next < 0 ) ? n : next;
      String comp = nativePath.substring( start, end );
      String parentKey = cur.toString();          // ここまでの親 path (末尾 sep 無し)
      boolean mapped = false;
      // issue #369: pre-encoded bundle では leaf の親 dir を lazy scan し、on-disk の encode 名を enc に登録する
      //   (encode 名は file leaf のみ=dir は encode しないので leaf の親だけ scan すれば足りる、dir ごと 1 回)。
      //   ★read 専用の race-free scanForRead を使う (create 用 primeDir の流用は並行 race + seen-redirect 退行を招く)。
      if( readScan && next < 0 ) scanForRead( parentKey );
      ConcurrentHashMap<String, String> m = enc.get( parentKey );
      if( m != null ) {
        String od = m.get( comp );
        if( od != null ) { comp = od; changed = true; mapped = true; }   // create 済みの encode 名 → on-disk 名
      }
      // leaf (最終 component) のみ: まだ encode 登録されていないが、emulin が既に create した
      //   「異 case の sibling」と衝突する名前なら、encode 名 (= 未作成 path) へ redirect する。
      //   これにより dpkg が symlink 作成前に行う lstat/unlink が plain 名で regular file に
      //   alias して content を壊すのを防ぐ (stat/unlink は ENOENT、実 create は resolveCreate が encode)。
      if( !mapped && next < 0 ) {
        ConcurrentHashMap<String, String> sm = seen.get( parentKey );
        if( sm != null ) {
          String owner = sm.get( comp.toLowerCase() );
          if( owner != null && !owner.equals( comp ) ) { comp = encodeCase( comp ); changed = true; }
        }
      }
      if( start > 0 ) cur.append( SEP );   // ★ 直前の separator を再現 (先頭 '/' = Linux 絶対 path も保持)
      cur.append( comp );
      if( next < 0 ) break;
      start = next + 1;
    }
    return changed ? cur.toString() : nativePath;
  }

  // create 経路: 新規作成しようとしている native path について、同 dir に異 case で同名の
  //   leaf を emulin が既に create 済みなら、全 letter escape した別名 path を返し map 登録する。
  //   衝突無しは素返し (= plain)。disk probe はせず emulin の create 履歴 (seen) で判定するので、
  //   open 中の file や NTFS の case-insensitive probe の不確実性に左右されない。
  static String resolveCreate( String nativePath ) {
    if( !on() || nativePath == null ) return nativePath;
    int sep = nativePath.lastIndexOf( SEP );
    if( sep <= 0 ) return nativePath;
    String parent = nativePath.substring( 0, sep );
    String leaf = nativePath.substring( sep + 1 );
    if( leaf.isEmpty() ) return nativePath;
    if( isCaseEncoded( leaf ) ) return nativePath;   // 既に on-disk encode 名 (mapPath 済) → そのまま
    // 1. 既に encode 登録済みの leaf (再 create / O_TRUNC reopen) → その on-disk 名へ
    ConcurrentHashMap<String, String> m = enc.get( parent );
    if( m != null ) { String od = m.get( leaf ); if( od != null ) return parent + SEP + od; }
    // 2. create 履歴で case-fold slot を占有。初回は plain、異 case の 2 つ目以降は衝突 → encode。
    primeDir( parent );    // ★ issue #394: 起動前 tar が置いた既存 disk leaf (coreutils head 等) を seen に先取り
    ConcurrentHashMap<String, String> sm = seen.computeIfAbsent( parent, k -> new ConcurrentHashMap<>() );
    anyCreates = true;
    String lower = leaf.toLowerCase();
    String prior = sm.putIfAbsent( lower, leaf );    // atomic にスロット確保
    if( prior == null || prior.equals( leaf ) ) return nativePath;   // 初占有 / 同名再 create → plain
    // 異 case の sibling が既に create 済み → 衝突 → 全 letter escape して別名で作る
    String onDisk = encodeCase( leaf );
    register( parent, leaf, onDisk );
    return parent + SEP + onDisk;
  }

  // getdents: host 上の leaf が case-encode 名なら map 登録する (read 経路で解決できるように)。
  //   dir は末尾 separator 無しの親 native path。
  static void registerFromReaddir( String dirNoSep, String hostLeaf ) {
    if( !on() || hostLeaf == null ) return;
    if( !isCaseEncoded( hostLeaf ) ) return;
    register( dirNoSep, decodeCase( hostLeaf ), hostLeaf );
  }
}
