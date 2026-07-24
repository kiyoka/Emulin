// ----------------------------------------
//  EgressPolicy — issue #401 Phase 1/2: egress の許可判定 + MITM 対象判定
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
//
//  amd64_connect で dest (host/ip/port) を評価する。
//   - Phase 1: allowlist host の :443 を MITM (TLS 終端 + credential swap)、それ以外は
//     ALLOW (生中継、従来動作)。= 既存挙動を壊さず MITM 対象だけ横取り。
//   - Phase 2 (将来): default-deny + allowlist で通信サンドボックス化 (DENY を足す)。
//   host は connect の sockaddr に直接は無いので DnsSnoop.hostFor(ip) で復元する。
// ----------------------------------------
package emulin;

import java.util.*;

public class EgressPolicy {

  public enum Decision { ALLOW, MITM, DENY }

  private final Set<String> mitmHosts = new HashSet<>();
  private boolean defaultDeny = false;   // Phase 2 で true 化
  private final DnsSnoop dns;

  public EgressPolicy( DnsSnoop dns, String[] mitmAllowlist ) {
    this.dns = dns;
    if( mitmAllowlist != null )
      for( String h : mitmAllowlist ) if( h != null && !h.isEmpty() ) mitmHosts.add( h.toLowerCase() );
  }

  public void setDefaultDeny( boolean v ) { defaultDeny = v; }

  // dest を評価。host が分からなければ DnsSnoop で ip→host を復元して判定する。
  public Decision evaluate( String host, String ip, int port ) {
    String h = (host != null && !host.isEmpty()) ? host.toLowerCase()
             : (ip != null && dns != null ? hostOrNull( dns.hostFor( ip ) ) : null);
    if( port == 443 && h != null && isMitmHost( h ) ) return Decision.MITM;
    if( defaultDeny ) {
      // Phase 2: allowlist 外は DENY (MITM host 以外も将来 allowlist 化)。
      return (h != null && isMitmHost( h )) ? Decision.ALLOW : Decision.DENY;
    }
    return Decision.ALLOW;
  }

  // allowlist は exact または suffix (".anthropic.com" 等) 一致を許す。
  private boolean isMitmHost( String h ) {
    if( mitmHosts.contains( h ) ) return true;
    for( String m : mitmHosts ) if( m.startsWith(".") && (h.equals( m.substring(1) ) || h.endsWith( m )) ) return true;
    return false;
  }

  private static String hostOrNull( String s ) { return (s == null || s.isEmpty()) ? null : s.toLowerCase(); }
}
