// ----------------------------------------
//  MiniJson — 最小 JSON パーサ/ライタ (issue #774)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  bundle JRE は java.base のみ (Jackson/Gson 無し) なので、~/.emulin/credentials.json の
//  ような浅い固定スキーマを読み書きするための依存ゼロの最小実装。標準 JSON を
//  Map<String,Object> / List<Object> / String / Long / Double / Boolean / null に parse し、
//  quote() で文字列を JSON string literal にエスケープする。
// ----------------------------------------
package emulin;

import java.util.*;

final class MiniJson {
  private final String s;
  private int i;
  private MiniJson( String s ) { this.s = s; }

  // 標準 JSON をパースする。壊れていれば RuntimeException。
  static Object parse( String s ) {
    MiniJson p = new MiniJson( s );
    p.ws();
    Object v = p.value();
    p.ws();
    if( p.i != s.length() ) throw p.err( "trailing data" );
    return v;
  }

  private Object value() {
    ws();
    if( i >= s.length() ) throw err( "unexpected end" );
    char c = s.charAt( i );
    switch( c ) {
      case '{': return object();
      case '[': return array();
      case '"': return string();
      case 't': case 'f': return bool();
      case 'n': lit( "null" ); return null;
      default:  return number();
    }
  }

  private Map<String,Object> object() {
    Map<String,Object> m = new LinkedHashMap<>();
    expect( '{' ); ws();
    if( peekChar() == '}' ) { i++; return m; }
    while( true ) {
      ws();
      String k = string(); ws(); expect( ':' );
      m.put( k, value() ); ws();
      char c = next();
      if( c == '}' ) return m;
      if( c != ',' ) throw err( "expected ',' or '}'" );
    }
  }

  private List<Object> array() {
    List<Object> a = new ArrayList<>();
    expect( '[' ); ws();
    if( peekChar() == ']' ) { i++; return a; }
    while( true ) {
      a.add( value() ); ws();
      char c = next();
      if( c == ']' ) return a;
      if( c != ',' ) throw err( "expected ',' or ']'" );
    }
  }

  private String string() {
    ws(); expect( '"' );
    StringBuilder b = new StringBuilder();
    while( true ) {
      if( i >= s.length() ) throw err( "unterminated string" );
      char c = s.charAt( i++ );
      if( c == '"' ) return b.toString();
      if( c == '\\' ) {
        if( i >= s.length() ) throw err( "bad escape" );
        char e = s.charAt( i++ );
        switch( e ) {
          case '"':  b.append( '"'  ); break;
          case '\\': b.append( '\\' ); break;
          case '/':  b.append( '/'  ); break;
          case 'b':  b.append( '\b' ); break;
          case 'f':  b.append( '\f' ); break;
          case 'n':  b.append( '\n' ); break;
          case 'r':  b.append( '\r' ); break;
          case 't':  b.append( '\t' ); break;
          case 'u':  b.append( (char)Integer.parseInt( s.substring( i, i + 4 ), 16 ) ); i += 4; break;
          default:   throw err( "bad escape \\" + e );
        }
      } else b.append( c );
    }
  }

  private Object number() {
    int start = i;
    while( i < s.length() && "+-0123456789.eE".indexOf( s.charAt( i ) ) >= 0 ) i++;
    String n = s.substring( start, i );
    if( n.isEmpty() ) throw err( "invalid value" );
    if( n.indexOf( '.' ) >= 0 || n.indexOf( 'e' ) >= 0 || n.indexOf( 'E' ) >= 0 )
      return Double.parseDouble( n );
    return Long.parseLong( n );
  }

  private Boolean bool() {
    if( s.charAt( i ) == 't' ) { lit( "true" );  return Boolean.TRUE; }
    lit( "false" ); return Boolean.FALSE;
  }

  private void lit( String w ) {
    if( !s.regionMatches( i, w, 0, w.length() ) ) throw err( "expected '" + w + "'" );
    i += w.length();
  }

  private void ws() { while( i < s.length() && Character.isWhitespace( s.charAt( i ) ) ) i++; }
  private char peekChar() { ws(); return i < s.length() ? s.charAt( i ) : '\0'; }
  private char next() { if( i >= s.length() ) throw err( "unexpected end" ); return s.charAt( i++ ); }
  private void expect( char c ) { char g = next(); if( g != c ) throw err( "expected '" + c + "' got '" + g + "'" ); }
  private RuntimeException err( String m ) { return new RuntimeException( "JSON: " + m + " at offset " + i ); }

  // 文字列を JSON string literal (前後の " 込み) にエスケープする。
  static String quote( String s ) {
    StringBuilder b = new StringBuilder( s.length() + 2 );
    b.append( '"' );
    for( int k = 0; k < s.length(); k++ ) {
      char c = s.charAt( k );
      switch( c ) {
        case '"':  b.append( "\\\"" ); break;
        case '\\': b.append( "\\\\" ); break;
        case '\n': b.append( "\\n"  ); break;
        case '\r': b.append( "\\r"  ); break;
        case '\t': b.append( "\\t"  ); break;
        case '\b': b.append( "\\b"  ); break;
        case '\f': b.append( "\\f"  ); break;
        default:
          if( c < 0x20 ) b.append( String.format( "\\u%04x", (int)c ) );
          else           b.append( c );
      }
    }
    return b.append( '"' ).toString();
  }
}
