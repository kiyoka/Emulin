package emulin;

import java.io.File;

// --------------------------------------------------------------------
//  CredAdminSmoke — issue #968: 取り込み元の判定と期限の見せ方を検証する。
//
//  ★ 何を守るテストか:
//    1. **期限を見ずに黙って取り込む**ことを防ぐ。2026-08-25 に、10 日前に期限切れに
//       なっていた `.credentials.json` をそのまま取り込めてしまい往復した。
//       `expiresAt` は最初からファイルに書かれていて、ただ誰も見ていなかった。
//    2. **名前ではなく中身で判定する**。`.credentials.json` という名前でも中身が別物
//       (setup-token など) のことがある。#964 で `.pub` という名前の秘密鍵に当たったのと同じ形。
//    3. **共有ログインを見逃さない**。普段使いの `.claude` を取り込むと、refresh token の
//       回転で**もう片方のセッションがログアウトされる** (#954 / #970 で実際に踏んだ)。
//    4. ★ **値を画面に出さない** (#401 の不変条件)。判定の結果に実トークンが混ざらないこと。
//
//  ネットワークも guest も要らない (純 Java・一時ディレクトリだけ)。
// --------------------------------------------------------------------
public final class CredAdminSmoke {

  private static int failures = 0;

  private static void check( boolean ok, String what ) {
    System.out.println( ( ok ? "  ok   " : "  FAIL " ) + what );
    if( !ok ) failures++;
  }

  static final String ACCESS  = "sk-ant-oat01-SMOKE-ACCESS-DO-NOT-SHOW-0000";
  static final String REFRESH = "sk-ant-ort01-SMOKE-REFRESH-DO-NOT-SHOW-000";
  static final String FULL    = "user:inference user:profile user:sessions:claude_code";

  /** claude の `.credentials.json` を作る。dirName で「専用/普段使い」を作り分ける。 */
  private static File writeLogin( File root, String dirName, long expiresAtMs, String scopes )
      throws Exception {
    File dir = new File( root, dirName );
    dir.mkdirs();
    String json = "{\"claudeAiOauth\":{"
        + "\"accessToken\":\"" + ACCESS + "\","
        + "\"refreshToken\":\"" + REFRESH + "\","
        + "\"expiresAt\":" + expiresAtMs + ","
        + "\"scopes\":[" + quoteList( scopes ) + "],"
        + "\"subscriptionType\":\"max\"}}";
    File f = new File( dir, ".credentials.json" );
    java.nio.file.Files.write( f.toPath(), json.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
    return f;
  }

  private static String quoteList( String spaceSep ) {
    StringBuilder b = new StringBuilder();
    for( String s : spaceSep.split( " " ) ) {
      if( s.isEmpty() ) continue;
      if( b.length() > 0 ) b.append( "," );
      b.append( "\"" ).append( s ).append( "\"" );
    }
    return b.toString();
  }

  public static void main( String[] args ) throws Exception {
    System.out.println( "=== #968 credential の状況表示 ===" );
    File root = java.nio.file.Files.createTempDirectory( "credadmin" ).toFile();
    root.deleteOnExit();
    final long now = 1_756_000_000_000L;             // 固定時刻 (テストを日付に依存させない)
    final long DAY = 24L * 3600 * 1000;

    // --- 期限の読み取り ------------------------------------------------
    // ★ 秒とミリ秒を取り違えると「1970 年に期限切れ」や「55000 年まで有効」になるが、
    //   画面としては成立してしまうので**目で見て気付けない**。桁で受けることを検査する。
    check( CredAdmin.parseEpoch( "1756000000000" ) == 1_756_000_000_000L, "epoch ミリ秒を読む" );
    check( CredAdmin.parseEpoch( "1756000000" )    == 1_756_000_000_000L, "epoch 秒も同じ時刻として読む" );
    check( CredAdmin.parseEpoch( "" ) == 0 && CredAdmin.parseEpoch( null ) == 0
           && CredAdmin.parseEpoch( "nope" ) == 0, "読めない値は 0 (期限不明として扱う)" );

    // --- 生きているログイン --------------------------------------------
    {
      File f = writeLogin( root, ".claude-emulin", now + 3 * 3600_000L, FULL );
      CredAdmin.Source s = CredAdmin.inspect( "test", f, now );
      check( s.reject == null && !s.expired && !s.warn, "生きているログインは取り込める" );
      check( s.note.contains( "valid for" ) && s.note.contains( "3 h" ),
             "残り時間を出す: " + s.note );
      check( !s.sharedLogin, "専用の config dir は共有ログイン扱いにしない" );
      // ★ #401: 判定の結果に実トークンが 1 文字も混ざらないこと。
      check( !s.note.contains( ACCESS ) && !s.note.contains( REFRESH )
             && !s.toString().contains( ACCESS ),
             "判定の結果に実トークンが載らない (#401 の不変条件)" );
    }

    // --- 期限切れ (まだ refresh は生きている見込み) ----------------------
    {
      File f = writeLogin( root, "expired-2d", now - 2 * DAY, FULL );
      CredAdmin.Source s = CredAdmin.inspect( "test", f, now );
      check( s.expired && s.note.contains( "expired 2 days ago" ), "期限切れを言う: " + s.note );
      // ★ 期限切れ = 使えない、ではない。Emulin は wire 上で refresh を回す。
      //   ここを「使えません」と書くと、正しい取り込み元まで避けさせてしまう。
      check( !s.warn && s.note.contains( "refresh it on first use" ),
             "1 週間以内なら「使えない」とは言わない (Emulin が回す)" );
    }

    // --- 期限切れ (refresh も切れている見込み) --------------------------
    {
      File f = writeLogin( root, "expired-10d", now - 10 * DAY, FULL );
      CredAdmin.Source s = CredAdmin.inspect( "test", f, now );
      check( s.expired && s.warn && s.note.contains( "log in again" ),
             "1 週間を超えた期限切れは再ログインを促す: " + s.note );
    }

    // --- scope 不足 ----------------------------------------------------
    {
      File f = writeLogin( root, "narrow", now + DAY, "user:inference" );
      CredAdmin.Source s = CredAdmin.inspect( "test", f, now );
      check( s.warn && s.note.contains( "Remote Control" ),
             "full scope が無いことを取り込む前に見せる (#935)" );
    }

    // --- 共有ログイン (普段使いの .claude) -------------------------------
    {
      File f = writeLogin( root, ".claude", now + DAY, FULL );
      CredAdmin.Source s = CredAdmin.inspect( "test", f, now );
      check( s.sharedLogin && s.warn, "普段使いの .claude は共有ログインとして警告する" );
      check( s.note.contains( "rotate" ) && s.note.contains( ".claude-emulin" ),
             "回転で片方が落ちることと、専用 config dir を勧めることを書く (#954/#970)" );
    }

    // --- ★ 名前ではなく中身で判定する ------------------------------------
    {
      File dir = new File( root, "setup-token" );
      dir.mkdirs();
      File f = new File( dir, ".credentials.json" );   // 名前は本物と同じ
      java.nio.file.Files.write( f.toPath(),
          "{\"primaryApiKey\":\"sk-ant-api03-SOMETHING\"}".getBytes( "UTF-8" ) );
      CredAdmin.Source s = CredAdmin.inspect( "test", f, now );
      check( s.reject != null, "名前が .credentials.json でも中身が違えば弾く: " + s.reject );
    }
    {
      File dir = new File( root, "broken" );
      dir.mkdirs();
      File f = new File( dir, ".credentials.json" );
      java.nio.file.Files.write( f.toPath(), "not json at all".getBytes( "UTF-8" ) );
      CredAdmin.Source s = CredAdmin.inspect( "test", f, now );
      check( s.reject != null, "JSON でないファイルで例外を投げず、理由を返す" );
    }
    {
      CredAdmin.Source s = CredAdmin.inspect( "test", new File( root, "nope/.credentials.json" ), now );
      check( s.reject != null, "存在しないファイルでも落ちない" );
    }

    // --- 登録済み credential の説明 --------------------------------------
    //  ★ list() は実際の ~/.emulin/credentials.json を読むので、テストからは
    //    判定部 (describe) を直接呼ぶ。開発機の本物の store を読ませない。
    {
      CredAdmin.Entry e = new CredAdmin.Entry();
      e.registered = true; e.name = "CLAUDE_REFRESH_TOKEN";
      e.savedAt = java.time.Instant.ofEpochMilli( now - 2 * DAY ).toString();
      CredAdmin.describe( e, now );
      check( !e.warn && e.note.contains( "2 days ago" ), "登録からの経過を出す: " + e.note );

      CredAdmin.Entry old = new CredAdmin.Entry();
      old.registered = true; old.name = "CLAUDE_REFRESH_TOKEN";
      old.savedAt = java.time.Instant.ofEpochMilli( now - 10 * DAY ).toString();
      CredAdmin.describe( old, now );
      check( old.warn && old.note.contains( "refresh token may have expired" ),
             "1 週間を超えた OAuth は期限切れの可能性を知らせる: " + old.note );

      // ★ API キーには期限が無い。同じ「10 日前」でも警告してはいけない
      //   (毎回出る警告は読まれなくなる = 本当に効かせたいときに効かない)。
      CredAdmin.Entry api = new CredAdmin.Entry();
      api.registered = true; api.name = "ANTHROPIC_API_KEY";
      api.savedAt = java.time.Instant.ofEpochMilli( now - 100 * DAY ).toString();
      CredAdmin.describe( api, now );
      check( !api.warn, "API キーは古くても警告しない (期限が無い)" );

      CredAdmin.Entry none = new CredAdmin.Entry();
      none.registered = false; none.name = "CLAUDE_ACCESS_TOKEN";
      CredAdmin.describe( none, now );
      check( none.note.isEmpty(), "未登録には何も書かない" );
    }

    if( failures == 0 ) { System.out.println( "CredAdmin smoke OK" ); System.exit( 0 ); }
    System.out.println( "CredAdmin smoke FAILED (" + failures + ")" );
    System.exit( 1 );
  }
}
