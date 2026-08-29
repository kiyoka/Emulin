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

  /** codex の auth.json を作る。access_token は **exp 入りの JWT** (署名は不要)。 */
  private static File writeCodex( File root, String dirName, long expMs ) throws Exception {
    File dir = new File( root, dirName );
    dir.mkdirs();
    File f = new File( dir, "auth.json" );
    java.nio.file.Files.write( f.toPath(), codexJson( expMs ).getBytes( "UTF-8" ) );
    return f;
  }

  private static String codexJson( long expMs ) {
    return "{\"auth_mode\":\"chatgpt\",\"tokens\":{"
         + "\"id_token\":\"" + jwt( expMs ) + "\","
         + "\"access_token\":\"" + jwt( expMs ) + "\","
         + "\"refresh_token\":\"SMOKE-CODEX-REFRESH-DO-NOT-SHOW\","
         + "\"account_id\":\"acct-smoke\"}}";
  }

  /** 署名しない JWT (payload の exp だけが要る。CredAdmin も検証しない)。 */
  private static String jwt( long expMs ) {
    java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
    String h = b64.encodeToString( "{\"alg\":\"none\"}".getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
    String p = b64.encodeToString( ( "{\"exp\":" + ( expMs / 1000 ) + "}" )
                                   .getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
    return h + "." + p + ".sig";
  }

  private static SetCred.Provider pick( java.util.List<SetCred.Provider> ps, String env ) {
    for( SetCred.Provider p : ps ) if( env.equals( p.env ) ) return p;
    return null;
  }

  private static String readAll( File f ) throws Exception {
    return new String( java.nio.file.Files.readAllBytes( f.toPath() ),
                       java.nio.charset.StandardCharsets.UTF_8 );
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

    // --- 取り込み (段取り 2) ---------------------------------------------
    //  ★ 保存先は一時ディレクトリ。**本物の ~/.emulin/credentials.json は絶対に触らない**。
    {
      File dir  = new File( root, "store" );
      File cred = new File( dir, "credentials.json" );
      File src  = writeLogin( root, ".claude-emulin", now + 3600_000L, FULL );

      CredAdmin.Import imp = CredAdmin.importClaudeLogin( src, dir, cred, now );
      check( imp.ok && imp.saved == 2, "Claude のログインを 2 件保存する (saved=" + imp.saved + ")" );
      String json = readAll( cred );
      check( json.contains( "CLAUDE_ACCESS_TOKEN" ) && json.contains( "CLAUDE_REFRESH_TOKEN" ),
             "credentials に両方の名前が入る" );
      check( json.contains( "CLAUDE_SOURCE" ) && json.contains( "CLAUDE_SCOPES" )
             && json.contains( "CLAUDE_SUBSCRIPTION_TYPE" ),
             "取り込み元・scope・プランを meta に残す (#968 / #935)" );
      // ★ #401: 取り込みの**結果**に値が混ざらないこと。ここはログ欄にそのまま出る。
      check( !String.join( " ", imp.notes ).contains( ACCESS )
             && !String.join( " ", imp.notes ).contains( REFRESH ),
             "取り込み結果に実トークンが載らない (画面/ログに出る文字列)" );
      check( String.join( " ", imp.notes ).contains( "read once" )
             || String.join( " ", imp.notes ).contains( "restart" ),
             "再起動しないと反映されないことを必ず言う (#944)" );

      // ★ codex を続けて入れても **claude の meta が消えない**。render は毎回ファイル全体を
      //   作り直すので、ここを踏むと meta が黙って消える (#935 で実際に踏んだ形)。
      File cx = writeCodex( root, "codex-ok", now + 1800_000L );
      CredAdmin.Import ci = CredAdmin.importCodexAuth( cx, dir, cred, now );
      check( ci.ok && ci.saved == 4, "codex のトークンを 4 件保存する (saved=" + ci.saved + ")" );
      String json2 = readAll( cred );
      check( json2.contains( "CLAUDE_SCOPES" ) && json2.contains( "CLAUDE_SOURCE" ),
             "別 provider を保存しても既存の meta が消えない (#935 の回帰)" );
      check( json2.contains( "CODEX_ACCESS_TOKEN" ) && json2.contains( "CODEX_SOURCE" ),
             "codex 側も名前と取り込み元が入る" );
    }

    // --- ★ 名前ではなく中身で provider を決める ---------------------------
    {
      File dir  = new File( root, "store2" );
      File cred = new File( dir, "credentials.json" );
      // codex の auth.json を **`.credentials.json` という名前**で置く。
      File d = new File( root, "misnamed" ); d.mkdirs();
      File f = new File( d, ".credentials.json" );
      java.nio.file.Files.write( f.toPath(), codexJson( now + 1800_000L ).getBytes( "UTF-8" ) );
      CredAdmin.Import imp = CredAdmin.importAny( f, dir, cred, now );
      check( imp.ok && readAll( cred ).contains( "CODEX_ACCESS_TOKEN" ),
             "名前が .credentials.json でも、中身が codex なら codex として取り込む" );
    }
    {
      File dir  = new File( root, "store3" );
      File cred = new File( dir, "credentials.json" );
      File d = new File( root, "junk" ); d.mkdirs();
      File f = new File( d, "auth.json" );
      java.nio.file.Files.write( f.toPath(), "{\"hello\":1}".getBytes( "UTF-8" ) );
      CredAdmin.Import imp = CredAdmin.importAny( f, dir, cred, now );
      // ★ 「読めません」だけだと、利用者は何を選び直せばよいか分からない。両方の理由を出す。
      check( !imp.ok && imp.error.contains( "Claude" ) && imp.error.contains( "codex" ),
             "どちらとしても読めなければ、両方の理由を返す" );
      check( !cred.exists(), "取り込めなかったときは credentials.json を作らない" );
    }

    // --- 貼り付け (段取り 3) ---------------------------------------------
    //  ★ 疎通テスト (verify=true) はネットワークを使うのでここでは呼ばない。
    {
      java.util.List<SetCred.Provider> ps = CredAdmin.pasteProviders();
      // ★ ファイルから読む形の provider (Claude のブラウザ認証 / codex) を貼り付けの
      //   選択肢に出してはいけない。貼らせても保存できず、利用者は理由が分からない。
      boolean hasFileOnly = false;
      for( SetCred.Provider p : ps )
        if( "CLAUDE_ACCESS_TOKEN".equals( p.env ) || "CODEX_ACCESS_TOKEN".equals( p.env ) )
          hasFileOnly = true;
      check( !ps.isEmpty() && !hasFileOnly,
             "貼り付けの選択肢に、ファイルからしか登録できない provider を出さない ("
             + ps.size() + " 件)" );

      SetCred.Provider gem = pick( ps, "GEMINI_API_KEY" );
      check( gem != null, "GEMINI_API_KEY が貼り付けで登録できる" );
      if( gem != null ) {
        CredAdmin.Check bad = CredAdmin.checkPasted( gem, "not-a-gemini-key", false );
        check( !bad.prefixOk && bad.needsConfirm() && bad.message.contains( "AIza" ),
               "prefix が違えば確認を求める: " + bad.message );
        CredAdmin.Check ok = CredAdmin.checkPasted( gem, "AIzaSyTESTKEY0000000000000", false );
        check( ok.prefixOk && !ok.needsConfirm() && !ok.verified,
               "prefix が合っていて verify しなければ、そのまま保存に進める" );

        File dir  = new File( root, "store4" );
        File cred = new File( dir, "credentials.json" );
        CredAdmin.Import imp = CredAdmin.savePasted( gem, "AIzaSyTESTKEY0000000000000", dir, cred );
        String json = readAll( cred );
        check( imp.ok && json.contains( "GEMINI_API_KEY" ), "貼り付けた値を保存する" );
        check( json.contains( "GEMINI_SOURCE" ),
               "貼り付けも取り込み元を残す (ファイル取り込みだけ記録すると半分しか追えない)" );
        check( !String.join( " ", imp.notes ).contains( "AIzaSyTESTKEY" ),
               "貼り付けの結果にも値が載らない (#401)" );
      }
    }

    // --- 削除 (段取り 3) -------------------------------------------------
    {
      File dir  = new File( root, "store5" );
      File cred = new File( dir, "credentials.json" );
      CredAdmin.importClaudeLogin( writeLogin( root, "del-claude", now + 3600_000L, FULL ),
                                   dir, cred, now );
      CredAdmin.importCodexAuth( writeCodex( root, "del-codex", now + 1800_000L ), dir, cred, now );

      CredAdmin.Import rm = CredAdmin.removeProvider( "CLAUDE", dir, cred );
      String json = readAll( cred );
      check( rm.ok && rm.saved == 2, "provider の credential をまとめて消す (" + rm.saved + " 件)" );
      // ★ ここが要点。片方だけ残ると、guest には解決できない placeholder が入り、
      //   画面上は「登録済み」に見えたまま 401 になる (#955 と同じ形)。
      check( !json.contains( "CLAUDE_ACCESS_TOKEN" ) && !json.contains( "CLAUDE_REFRESH_TOKEN" ),
             "OAuth の片割れを残さない" );
      check( !json.contains( "CLAUDE_SCOPES" ) && !json.contains( "CLAUDE_SOURCE" ),
             "その provider の meta も一緒に消す" );
      check( json.contains( "CODEX_ACCESS_TOKEN" ) && json.contains( "CODEX_SOURCE" ),
             "他の provider は残す" );
      check( String.join( " ", rm.notes ).contains( "restart" )
             || String.join( " ", rm.notes ).contains( "read once" ),
             "削除にも再起動が要ることを言う (#944)" );

      CredAdmin.Import none = CredAdmin.removeProvider( "NOSUCH", dir, cred );
      check( !none.ok && none.error.contains( "NOSUCH" ), "登録の無い provider は理由を返す" );
      check( readAll( cred ).contains( "CODEX_ACCESS_TOKEN" ),
             "空振りの削除でファイルを壊さない" );
    }

    // --- 検証 (段取り 4: 詳細ペインの Verify) ------------------------------
    //  ★ 実際に投げる経路はネットワークを使うのでここでは呼ばない。**投げる前に返る 2 つ**
    //    (probe が無い / 未登録) だけを見る。ここが「無効だった」と読める文言になると、
    //    利用者は生きているサブスクのログインを消しにかかる。
    {
      SetCred.Provider sub = pick( java.util.Arrays.asList( SetCred.SETTABLE ), "CLAUDE_ACCESS_TOKEN" );
      SetCred.Provider gem = pick( java.util.Arrays.asList( SetCred.SETTABLE ), "GEMINI_API_KEY" );
      File dir  = new File( root, "store5" );
      File cred = new File( dir, "credentials.json" );

      CredAdmin.Check c1 = CredAdmin.checkRegistered( sub, cred );
      check( !c1.verified && !c1.rejected && c1.message.contains( "no probe endpoint" ),
             "サブスクのログインは「検証できない」と言う (「無効」とは言わない)" );

      CredAdmin.Check c2 = CredAdmin.checkRegistered( gem, cred );
      check( !c2.verified && !c2.rejected && c2.message.contains( "not registered" ),
             "未登録なら、投げる前に「未登録」と返す" );

      CredAdmin.savePasted( gem, "AIzaSyTESTKEY0000000000000", dir, cred );
      String probe = c1.message + " " + c2.message;
      check( !probe.contains( "AIza" ) && !probe.contains( ACCESS ),
             "検証の結果にも値が載らない (#401)" );
    }

    if( failures == 0 ) { System.out.println( "CredAdmin smoke OK" ); System.exit( 0 ); }
    System.out.println( "CredAdmin smoke FAILED (" + failures + ")" );
    System.exit( 1 );
  }
}
