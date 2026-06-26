// ----------------------------------------
//  DnsSnoop — issue #401 Phase 1/2: 中継 DNS 応答から ip↔hostname を構築
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  emulin は既に UDP:53 を中継しているので、その応答を覗き見て ip→hostname を
//   学習すれば、connect 時に hostname を無設定で復元でき、EgressPolicy の host 判定や
//   MITM の SNI 推定に使える。read-only な観測のみ (中継動作は変えない)。
// ----------------------------------------
package emulin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DnsSnoop {

  // ip 文字列 ("a.b.c.d") → 直近に解決した hostname
  private final Map<String,String> ipToHost = new ConcurrentHashMap<>();

  // 中継した DNS 応答 (UDP:53 の payload) を観測する。malformed は黙って無視。
  public void observe( byte[] resp, int len ) {
    try { parse( resp, len ); } catch( Exception ignore ) {}
  }

  public String hostFor( String ip ) { return ipToHost.get( ip ); }

  // DNS response を最小 parse: question の QNAME を取り、A(1)/AAAA(28) answer の
  //   RDATA(ip) → QNAME を map する。name 圧縮 (0xC0 ポインタ) を辿る。
  private void parse( byte[] b, int len ) {
    if( len < 12 ) return;
    int flags = ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    if( (flags & 0x8000) == 0 ) return;           // response bit が無ければ query → 無視
    int qd = ((b[4] & 0xFF) << 8) | (b[5] & 0xFF);
    int an = ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);
    int[] pos = { 12 };
    String qname = null;
    for( int i = 0; i < qd; i++ ) {
      String nm = readName( b, len, pos );
      pos[0] += 4;                                // QTYPE(2)+QCLASS(2)
      if( qname == null ) qname = nm;             // 先頭 question を hostname とみなす
    }
    if( qname == null || qname.isEmpty() ) return;
    for( int i = 0; i < an && pos[0] < len; i++ ) {
      readName( b, len, pos );                    // answer NAME (圧縮ポインタを skip)
      if( pos[0] + 10 > len ) return;
      int type = ((b[pos[0]] & 0xFF) << 8) | (b[pos[0]+1] & 0xFF);
      int rdlen = ((b[pos[0]+8] & 0xFF) << 8) | (b[pos[0]+9] & 0xFF);
      int rd = pos[0] + 10;
      if( type == 1 && rdlen == 4 && rd + 4 <= len ) {           // A
        String ip = (b[rd]&0xFF)+"."+(b[rd+1]&0xFF)+"."+(b[rd+2]&0xFF)+"."+(b[rd+3]&0xFF);
        ipToHost.put( ip, qname );
      } else if( type == 28 && rdlen == 16 && rd + 16 <= len ) { // AAAA
        StringBuilder sb = new StringBuilder();
        for( int k = 0; k < 16; k += 2 ) {
          if( k > 0 ) sb.append( ':' );
          sb.append( Integer.toHexString( ((b[rd+k]&0xFF) << 8) | (b[rd+k+1]&0xFF) ) );
        }
        ipToHost.put( sb.toString(), qname );
      }
      pos[0] = rd + rdlen;
    }
  }

  // DNS name を読む (圧縮ポインタ 0xC0 対応)。pos[0] を消費後の位置へ進める。
  private String readName( byte[] b, int len, int[] pos ) {
    StringBuilder sb = new StringBuilder();
    int p = pos[0];
    boolean jumped = false;
    int safety = 0;
    while( p < len && safety++ < 128 ) {
      int c = b[p] & 0xFF;
      if( c == 0 ) { p++; break; }
      if( (c & 0xC0) == 0xC0 ) {                  // 圧縮ポインタ
        if( p + 1 >= len ) break;
        int ptr = ((c & 0x3F) << 8) | (b[p+1] & 0xFF);
        if( !jumped ) pos[0] = p + 2;             // 元の stream は ポインタの次へ
        p = ptr; jumped = true;
        continue;
      }
      if( p + 1 + c > len ) break;
      if( sb.length() > 0 ) sb.append( '.' );
      for( int i = 0; i < c; i++ ) sb.append( (char)(b[p+1+i] & 0xFF) );
      p += 1 + c;
    }
    if( !jumped ) pos[0] = p;
    return sb.toString().toLowerCase();
  }
}
