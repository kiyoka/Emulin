package emulin;

import java.nio.charset.StandardCharsets;

// --------------------------------------------------------------------
//  GuestStr — guest に渡す「バイト列としての文字列」の表現を 1 箇所に決める。
//
//  guest (Linux) にとって argv / envp / path は **バイト列**であって文字列ではない。
//  Java の String は char 列なので、guest との間で必ず encode/decode が挟まる。
//  ここで踏む事故は「読みと書きで別の charset を使う」= 対の片方だけを直すこと。
//
//  issue #932: #921 で **読み**だけを raw (ISO-8859-1) に変えたが、初期スタックへの
//    **書き** (Process.buildInitialStack64) が既定 charset (UTF-8) のまま残っていた。
//    非 ASCII の argv が 1 バイトずつ UTF-8 で再エンコードされ (0xc5 0x91 →
//    0xc3 0x85 0xc2 0x91)、guest から見て存在しないパスになる。
//    実害: apt の ca-certificates postinst が Hungarian 名の cert を開けず失敗し、
//    それに依存する 6 パッケージが芋づるで configure 不能になった。
//
//  約束事 (これを崩さないこと):
//    - **guest 由来**の argv / env は raw 表現 (1 byte = 1 char) で持ち回る。
//      Memory.loadStringRaw / storeStringRaw が対。
//    - **host 由来**の Java String (真の Unicode。起動時の argv、System.getenv の値) は
//      guest へ渡す直前に fromHost() で raw 表現へ変換する。**境界で 1 回だけ**変換する。
//    - path は従来どおり loadString / storeString (UTF-8) の対で扱う。raw と混ぜない。
// --------------------------------------------------------------------
public final class GuestStr {

  private GuestStr( ) { }

  /**
   *  host 由来の Java String を guest の raw 表現へ変換する。
   *
   *  guest は UTF-8 ロケール (Kernel が LANG を C.UTF-8 等に正規化する) なので、
   *  host の文字を UTF-8 バイト列にし、それを 1 byte = 1 char で持つ形に写す。
   *  こうしておけば書き出し (ISO-8859-1) でそのままのバイト列が guest に届く。
   *
   *  ASCII だけなら結果は同じ String になる (大多数のケースで no-op)。
   */
  public static String fromHost( String s ) {
    if( s == null ) return null;
    // ASCII のみなら変換不要 (hot path ではないが、無駄な配列確保を避ける)。
    boolean ascii = true;
    for( int i = 0; i < s.length( ); i++ ) {
      if( s.charAt( i ) > 0x7f ) { ascii = false; break; }
    }
    if( ascii ) return s;
    return new String( s.getBytes( StandardCharsets.UTF_8 ), StandardCharsets.ISO_8859_1 );
  }

  /** 配列版。null 要素はそのまま通す。 */
  public static String[] fromHost( String[] a ) {
    if( a == null ) return null;
    String[] r = new String[ a.length ];
    for( int i = 0; i < a.length; i++ ) r[i] = fromHost( a[i] );
    return r;
  }

  /**
   *  raw 表現の String を host の Java String へ戻す (fromHost の逆)。
   *
   *  guest から受け取ったバイト列を host の API (ファイル名・ログ表示) に渡すときに使う。
   *  UTF-8 として不正なバイトが混ざっていれば置換文字になるので、**バイト列のまま
   *  運ぶべき経路では使わないこと**。
   */
  public static String toHost( String raw ) {
    if( raw == null ) return null;
    boolean ascii = true;
    for( int i = 0; i < raw.length( ); i++ ) {
      if( raw.charAt( i ) > 0x7f ) { ascii = false; break; }
    }
    if( ascii ) return raw;
    return new String( raw.getBytes( StandardCharsets.ISO_8859_1 ), StandardCharsets.UTF_8 );
  }
}
