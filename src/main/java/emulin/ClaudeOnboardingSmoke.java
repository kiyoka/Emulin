package emulin;

import java.util.HashMap;
import java.util.Map;

// --------------------------------------------------------------------
//  ClaudeOnboardingSmoke — issue #876 の onboarding seed が、issue #935 で切り替わった
//  現行の認証方式 (CLAUDE_ACCESS_TOKEN / ブラウザ OAuth) でも発動することを確かめる。
//
//  ★ 何を守るテストか: `Egress.claudeCredentialConfigured()` は「Claude の credential が
//    何か 1 つでも設定されていれば true」であるべき。ここが漏れると、guest 側の claude
//    初回起動が onboarding (テーマ → ログイン選択) に落ち、対話なら実トークンが sandbox に
//    書き込まれる危険 (#876 が防いだもの) が、非対話なら永久ハングという形で再発する。
//
//  ★ 実際に 2026-08-30 の実機確認で、CLAUDE_ACCESS_TOKEN しか判定していなかった旧版
//    (CLAUDE_CODE_OAUTH_TOKEN / ANTHROPIC_API_KEY のみ) がこれで抜けていた。
//    #935 (setup-token → ブラウザ OAuth への切り替え) のときに判定が追随していなかった。
//
//  ★ Sysinfo (Mount) 一式を組まずに済むよう、判定だけを static メソッドへ切り出して検査する。
// --------------------------------------------------------------------
public final class ClaudeOnboardingSmoke {

  private static int failures = 0;

  private static void check( boolean ok, String what ) {
    System.out.println( ( ok ? "  ok   " : "  FAIL " ) + what );
    if( !ok ) failures++;
  }

  private static CredentialStore storeWith( String... envPairs ) {
    Map<String,String> env = new HashMap<>();
    for( int i = 0; i < envPairs.length; i += 2 ) env.put( envPairs[i], envPairs[i + 1] );
    CredentialStore cs = new CredentialStore();
    cs.discoverFrom( env );
    return cs;
  }

  public static void main( String[] args ) {
    System.out.println( "=== #876/#935 claude onboarding seed の判定 ===" );

    check( !Egress.claudeCredentialConfigured( storeWith() ),
           "何も設定されていなければ false (guest に home が無い等でも安全に何もしない)" );

    // ★ これが今回の実害だった経路: ブラウザ OAuth (#935 以降の現行方式)。
    check( Egress.claudeCredentialConfigured( storeWith(
               "EMULIN_CRED_CLAUDE_ACCESS_TOKEN",  "sk-ant-oat01-TEST-0000000000000000",
               "EMULIN_CRED_CLAUDE_REFRESH_TOKEN", "sk-ant-ort01-TEST-000000000000000" ) ),
           "現行のブラウザ OAuth (CLAUDE_ACCESS_TOKEN) でも true になる ★これが漏れていた" );

    // ★ deprecated だが、既存の登録済み利用者を無視しない。
    check( Egress.claudeCredentialConfigured( storeWith(
               "EMULIN_CRED_CLAUDE_CODE_OAUTH_TOKEN", "sk-ant-oat01-TEST-DEPRECATED-0000" ) ),
           "deprecated な setup-token でも true (旧方式の利用者を無視しない)" );

    check( Egress.claudeCredentialConfigured( storeWith(
               "EMULIN_CRED_ANTHROPIC_API_KEY", "sk-ant-api03-TEST-0000000000000000" ) ),
           "Console API key でも true" );

    // ★ 他 provider (Claude と無関係) だけでは true にしない。
    check( !Egress.claudeCredentialConfigured( storeWith(
               "EMULIN_CRED_CODEX_ACCESS_TOKEN", "codex-test-token" ) ),
           "Claude 以外の credential だけでは true にしない" );

    if( failures == 0 ) { System.out.println( "ClaudeOnboarding smoke OK" ); System.exit( 0 ); }
    System.out.println( "ClaudeOnboarding smoke FAILED (" + failures + ")" );
    System.exit( 1 );
  }
}
