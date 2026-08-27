package emulin;

import java.awt.*;
import java.io.File;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

// --------------------------------------------------------------------
//  LauncherApp — Emulin のランチャー兼ダッシュボード (issue #948)
//
//  ★ なぜ Web ではなくスタンドアロンか:
//    中心機能は「**emulin.bat 相当を起動する = Windows Terminal が開く**」こと。
//    ブラウザのページからプロセスは起動できない (サンドボックス)。回避するには
//    「HTTP で起動要求を受ける」= ブラウザで開いただけのページが任意のプロセスを
//    起動できる状態になり、#949 で塞いだ穴と同じ性質を自分で開けることになる。
//    URL スキーム登録はインストーラが要り、「zip 展開だけ」という性質が壊れる。
//
//  ★ なぜ Swing か (Rust+Tauri / C# / Electron ではなく):
//    **ツールチェーンを増やさない**ため。リリースは mvn package -> build-release.sh の
//    1 本道で回っており (#939)、Rust/.NET を入れると二重管理になる。
//    代償は jlink の +15MB (42MB -> 57MB を実測)。配布 zip 274MB に対しては小さい。
//
//  ★ ターミナル起動は **emulin.bat に委ねる**。あれは既に「wt.exe で自分を起動し直す」
//    処理 (#121) と wt-setup.ps1 の適用、ログインユーザ選択を持っている。
//    **同じロジックを 2 箇所に書かない** (#919 で「launcher が 2 系統あり出荷側を
//    検証していなかった」を踏んでいる)。
//
//  起動: emulin.bat app  /  emulin-app.bat (ダブルクリック)
// --------------------------------------------------------------------
public final class LauncherApp {

  // 配色 (FlatLaf は Apache-2.0 で GPLv2 と非互換なので使えない。自前で持つ)
  private static final Color BG    = new Color( 0x14, 0x16, 0x1b );
  private static final Color PANEL = new Color( 0x1b, 0x1e, 0x26 );
  private static final Color FG    = new Color( 0xe6, 0xe6, 0xe6 );
  private static final Color DIM   = new Color( 0x8b, 0x93, 0xa1 );
  private static final Color ACC   = new Color( 0x8a, 0xb4, 0xf8 );
  private static final Color WARN  = new Color( 0xf2, 0xb8, 0xb5 );
  private static final Color OK    = new Color( 0x9a, 0xe6, 0xb4 );

  // ★ agent は**保持する**。installAgent のたびに new すると detect の結果 (done) が
  //   捨てられ、画面に「導入状況」を出せない。
  private final AgentInstall.Agent codex  = AgentInstall.codex();
  private final AgentInstall.Agent claude = AgentInstall.claude();

  private final File   home;          // 配布ディレクトリ (emulin.bat がある場所)
  private SshdService  sshd;          // issue #963
  private final JTextField sshdPort = new JTextField( String.valueOf( SshdService.DEFAULT_PORT ), 5 );
  private final JButton sshdBtn = new JButton();
  private final JFrame frame = new JFrame( "Emulin" );
  private final JTextArea log = new JTextArea();
  private final JPanel  status = new JPanel();

  private LauncherApp( File home ) { this.home = home; this.sshd = new SshdService( home ); }

  public static void main( String[] args ) {
    File h = ( args.length > 0 ) ? new File( args[0] ) : new File( "." ).getAbsoluteFile();
    SwingUtilities.invokeLater( () -> new LauncherApp( h ).show() );
  }

  private void show() {
    frame.setDefaultCloseOperation( JFrame.EXIT_ON_CLOSE );
    JPanel root = new JPanel( new BorderLayout( 0, 12 ) );
    root.setBackground( BG );
    root.setBorder( new EmptyBorder( 16, 18, 16, 18 ) );

    root.add( header(), BorderLayout.NORTH );
    root.add( center(), BorderLayout.CENTER );

    frame.setContentPane( root );
    // ★ 固定 px にしない。4K/200% では論理 px が実寸の半分になり、行がはみ出す。
    //   画面の比率で決め、下限だけ置く (どちらの倍率でも読める大きさになる)。
    Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
    frame.setSize( Math.max( 820, Math.min( 1180, scr.width  * 3 / 5 ) ),
                   Math.max( 620, Math.min(  980, scr.height * 3 / 4 ) ) );
    frame.setMinimumSize( new Dimension( 720, 540 ) );
    frame.setLocationRelativeTo( null );
    frame.setVisible( true );
    refresh();
    detectAll();                       // ★ 開いた時点で「何が入っているか」を出す
    new Timer( 5000, e -> refresh() ).start();
  }

  private JComponent header() {
    JPanel p = new JPanel( new BorderLayout() );
    p.setOpaque( false );
    JLabel t = new JLabel( "Emulin " + Version.get_version() );
    t.setForeground( FG );
    t.setFont( t.getFont().deriveFont( Font.BOLD, 17f ) );
    JLabel s = new JLabel( home.getAbsolutePath() );
    s.setForeground( DIM );
    s.setFont( mono( 11f ) );
    JPanel left = new JPanel( new GridLayout( 2, 1 ) );
    left.setOpaque( false );
    left.add( t ); left.add( s );
    p.add( left, BorderLayout.WEST );
    return p;
  }

  private JComponent center() {
    // ★ BorderLayout にして「状態は伸びる / ログは行数で決まる」にする。
    //   BoxLayout + setPreferredSize(px) だと拡大時に行がはみ出したまま伸びない。
    JPanel p = new JPanel( new BorderLayout( 0, 12 ) );
    p.setOpaque( false );

    p.add( buttons(), BorderLayout.NORTH );

    // --- 状態 (伸びる) ---
    status.setLayout( new BoxLayout( status, BoxLayout.Y_AXIS ) );
    status.setBackground( PANEL );
    status.setBorder( new EmptyBorder( 12, 14, 12, 14 ) );
    JScrollPane sp = new JScrollPane( status );
    sp.setBorder( null );
    sp.getViewport().setBackground( PANEL );
    p.add( sp, BorderLayout.CENTER );

    // --- ログ (行数で高さが決まる = 倍率に追従する) ---
    log.setEditable( false );
    log.setRows( 7 );
    log.setBackground( PANEL );
    log.setForeground( DIM );
    log.setFont( mono( 11f ) );
    log.setBorder( new EmptyBorder( 8, 10, 8, 10 ) );
    JScrollPane lp = new JScrollPane( log );
    lp.setBorder( null );
    p.add( lp, BorderLayout.SOUTH );
    return p;
  }

  /** 操作ボタン。★ FlowLayout 1 行だと倍率が上がったとき折り返して**下が切れる**ので、
   *  主操作を 1 行、副操作を等幅 3 列にして「絶対に折り返さない」形にする。 */
  private JComponent buttons() {
    JPanel p = new JPanel( new BorderLayout( 0, 8 ) );
    p.setOpaque( false );
    JPanel top = new JPanel( new BorderLayout() );
    top.setOpaque( false );
    // ★ ラベルで「Windows Terminal」と約束しない。wt.exe を使うかは emulin.bat 側の
    //   判定 (`where wt`) で決まり、起動元の PATH 次第で conhost になる (実測)。
    top.add( button( "Open terminal", true, e -> openTerminal() ), BorderLayout.CENTER );
    p.add( top, BorderLayout.NORTH );
    JPanel sub = new JPanel( new GridLayout( 1, 0, 10, 0 ) );
    sub.setOpaque( false );
    sub.add( button( "Set up credentials", false, e -> setupCredentials() ) );   // issue #968
    sub.add( button( "Install Codex CLI", false, e -> installAgent( codex ) ) );
    sub.add( button( "Install Claude Code", false, e -> installAgent( claude ) ) );
    p.add( sub, BorderLayout.CENTER );
    p.add( sshdRow(), BorderLayout.SOUTH );
    return p;
  }

  /** issue #963: SSH サーバの起動 / 停止と port 指定。 */
  private JComponent sshdRow() {
    JPanel row = new JPanel( new FlowLayout( FlowLayout.LEFT, 8, 0 ) );
    row.setOpaque( false );
    JLabel l = new JLabel( "SSH server    port" );
    l.setForeground( DIM );
    row.add( l );
    sshdPort.setColumns( 5 );
    sshdPort.setBackground( PANEL );
    sshdPort.setForeground( FG );
    sshdPort.setCaretColor( FG );
    sshdPort.setBorder( new EmptyBorder( 6, 8, 6, 8 ) );
    row.add( sshdPort );
    sshdBtn.setText( "Start" );
    sshdBtn.setFocusPainted( false );
    sshdBtn.setBorder( new EmptyBorder( 9, 18, 9, 18 ) );
    sshdBtn.setBackground( PANEL );
    sshdBtn.setForeground( FG );
    sshdBtn.setCursor( Cursor.getPredefinedCursor( Cursor.HAND_CURSOR ) );
    sshdBtn.addActionListener( e -> toggleSshd() );
    row.add( sshdBtn );
    row.add( button( "Add public key", false, e -> installPubKey() ) );   // issue #964
    return row;
  }

  // ------------------------------------------------------------------
  //  公開鍵の登録 (issue #964)
  //    ★ 秘密鍵を絶対に登録させない。#401 の不変条件が破れる。
  // ------------------------------------------------------------------
  private void installPubKey() {
    java.util.List<SshKeys.PubKey> keys = SshKeys.find();
    java.util.Set<String> already = SshKeys.installed( home );
    DefaultListModel<String> model = new DefaultListModel<>();
    for( SshKeys.PubKey k : keys )
      model.addElement( ( already.contains( k.fingerprint ) ? "[installed] " : "[   new    ] " )
          + k.type + "   " + k.fingerprint
          + "   " + ( k.comment.isEmpty() ? "(no comment)" : k.comment )
          + "   — " + k.path.getPath() );      // ★ 実際のパスを出す (利用者の指摘)
    JList<String> list = new JList<>( model );
    list.setSelectionMode( ListSelectionModel.SINGLE_SELECTION );
    list.setFont( mono( 11f ) );
    if( !model.isEmpty() ) list.setSelectedIndex( 0 );

    JPanel panel = new JPanel( new BorderLayout( 0, 8 ) );
    panel.add( new JLabel( keys.isEmpty()
        ? "No public key (*.pub) found. Use \"Choose a file...\" to pick one."
        : "Choose the public key to add to the guest's authorized_keys:" ), BorderLayout.NORTH );
    JScrollPane sp = new JScrollPane( list );
    sp.setPreferredSize( new Dimension( 720, 220 ) );
    panel.add( sp, BorderLayout.CENTER );

    Object[] options = { "Add", "Choose a file...", "Cancel" };
    int r = JOptionPane.showOptionDialog( frame, panel, "Add a public key",
        JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0] );
    if( r == 1 ) { installPubKeyFromFile(); return; }
    if( r != 0 ) return;
    int i = list.getSelectedIndex();
    if( i < 0 || i >= keys.size() ) { append( "No key selected." ); return; }
    append( SshKeys.install( home, keys.get( i ) ) );
    refresh();
  }

  /** 一覧に出ない場所の鍵を選ぶ。★ 同じ検証を通す (.pub という名前の秘密鍵もあり得る)。 */
  private void installPubKeyFromFile() {
    JFileChooser fc = new JFileChooser( new File( System.getProperty( "user.home", "." ), ".ssh" ) );
    fc.setDialogTitle( "Choose a public key file (*.pub)" );
    if( fc.showOpenDialog( frame ) != JFileChooser.APPROVE_OPTION ) return;
    File f = fc.getSelectedFile();
    try {
      String text = new String( java.nio.file.Files.readAllBytes( f.toPath() ),
                                java.nio.charset.StandardCharsets.UTF_8 );
      String ng = SshKeys.rejectReason( text );
      if( ng != null ) { append( "★ " + f.getName() + ": " + ng ); return; }
    } catch( Exception e ) { append( "! cannot read: " + e ); return; }
    SshKeys.PubKey k = SshKeys.parse( f, "chosen" );
    if( k == null ) { append( "! not a public key: " + f ); return; }
    append( SshKeys.install( home, k ) );
    refresh();
  }

  // ------------------------------------------------------------------
  //  credential の登録 (issue #968)
  //
  //  ★ 値は 1 文字も画面に出さない (#401)。出すのは provider 名・置き場所・期限だけ。
  //  ★ 判定と保存は CredAdmin に一本化してある。ここは**選ばせるだけ**。
  //    UI 側に保存を書くと「CLI では meta を書くのに UI では書かない」型がすぐ入る。
  // ------------------------------------------------------------------
  private void setupCredentials() {
    java.util.List<CredAdmin.Source> found = new java.util.ArrayList<>();
    found.addAll( CredAdmin.claudeSources() );
    found.addAll( CredAdmin.codexSources() );

    DefaultListModel<String> model = new DefaultListModel<>();
    for( CredAdmin.Source s : found )
      model.addElement( ( s.reject == null ? "[ use  ] " : "[ skip ] " )
          + pad( s.kind, 7 ) + s.label
          + "   — " + s.path                       // ★ 実際のパスを出す (#964 の指摘)
          + ( s.reject != null ? "   (" + s.reject + ")" : "   " + s.note ) );
    JList<String> list = new JList<>( model );
    list.setSelectionMode( ListSelectionModel.SINGLE_SELECTION );
    list.setFont( mono( 11f ) );
    for( int i = 0; i < found.size(); i++ )
      if( found.get( i ).reject == null ) { list.setSelectedIndex( i ); break; }

    JPanel panel = new JPanel( new BorderLayout( 0, 8 ) );
    JPanel head = new JPanel( new GridLayout( 0, 1 ) );
    head.add( new JLabel( found.isEmpty()
        ? "No login found. Log in on the host first, then use \"Choose a file...\"."
        : "Choose the login to import. The real tokens stay on the host;"
          + " the guest only ever gets placeholders." ) );
    head.add( new JLabel( "Log in with a dedicated config dir:"
        + "  CLAUDE_CONFIG_DIR=~/.claude-emulin claude auth login"
        + "   /   codex login" ) );
    panel.add( head, BorderLayout.NORTH );
    JScrollPane sp = new JScrollPane( list );
    sp.setPreferredSize( new Dimension( 860, 220 ) );
    panel.add( sp, BorderLayout.CENTER );

    Object[] options = { "Import", "Choose a file...", "Paste a key (CLI)...", "Close" };
    int r = JOptionPane.showOptionDialog( frame, panel, "Set up credentials",
        JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0] );
    if( r == 1 ) { importCredFromFile(); return; }
    // ★ 貼り付けと API キーはまだ CLI にしかない。**UI から辿れる**ようにしておく
    //   (辿れないと「ランチャーでは登録できない provider がある」ことに気付けない)。
    if( r == 2 ) { runLauncher( "setcred" ); return; }
    if( r != 0 ) return;
    int i = list.getSelectedIndex();
    if( i < 0 || i >= found.size() ) { append( "No login selected." ); return; }
    CredAdmin.Source s = found.get( i );
    if( s.reject != null ) { append( "★ cannot use this one: " + s.reject ); return; }
    // ★ 共有ログインは**押す前に**止めて確認する。押したあとで警告しても、
    //   そのときにはもう片方のセッションを落とす取り込みが済んでいる (#954 / #970)。
    if( s.sharedLogin && !confirmSharedLogin( s ) ) { append( "Cancelled." ); return; }
    runImport( CredAdmin.importAny( new File( s.path ) ), s.path );
  }

  /** 一覧に出ない場所のファイルを選ぶ。★ 同じ判定を通す (中身で provider を決める)。 */
  private void importCredFromFile() {
    JFileChooser fc = new JFileChooser( new File( System.getProperty( "user.home", "." ) ) );
    fc.setDialogTitle( "Choose a login file (.credentials.json / auth.json)" );
    if( fc.showOpenDialog( frame ) != JFileChooser.APPROVE_OPTION ) return;
    File f = fc.getSelectedFile();
    CredAdmin.Source s = CredAdmin.inspect( "chosen", f, System.currentTimeMillis() );
    if( s.reject == null && s.sharedLogin && !confirmSharedLogin( s ) ) { append( "Cancelled." ); return; }
    runImport( CredAdmin.importAny( f ), f.getPath() );
  }

  /** ★ 普段使いのログインを取り込むと、refresh の回転で**もう片方が落ちる** (#954 / #970)。 */
  private boolean confirmSharedLogin( CredAdmin.Source s ) {
    int yn = JOptionPane.showConfirmDialog( frame,
        "This looks like your everyday Claude login (" + s.path + ").\n\n"
      + "OAuth refresh tokens rotate: if the guest shares one login with another\n"
      + "Claude Code session, whichever refreshes first keeps working and the other\n"
      + "is logged out. A dedicated config dir avoids this:\n\n"
      + "    CLAUDE_CONFIG_DIR=~/.claude-emulin  claude auth login\n\n"
      + "Import it anyway?",
        "Shared login", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE );
    return yn == JOptionPane.YES_OPTION;
  }

  /** 取り込み結果をログ欄へ。★ notes に値は入らない (CredAdmin 側の約束)。 */
  private void runImport( CredAdmin.Import imp, String path ) {
    if( !imp.ok ) { append( "★ " + path + ": " + imp.error ); return; }
    append( "imported from " + path );
    for( String n : imp.notes ) append( "  " + n );
    refresh();
  }

  private static String pad( String s, int n ) {
    StringBuilder b = new StringBuilder( s == null ? "" : s );
    while( b.length() < n ) b.append( ' ' );
    return b.toString();
  }

  private int enteredPort() {
    try { return Integer.parseInt( sshdPort.getText().trim() ); }
    catch( Exception e ) { return SshdService.DEFAULT_PORT; }
  }

  private void toggleSshd() {
    if( sshd.isRunning() ) { sshd.stop( this::append ); refresh(); return; }
    // ★ 押す前に前提を出す。authorized_keys が無いと起動はするが**誰も繋げない**。
    java.util.List<String> ng = sshd.preflight();
    if( !ng.isEmpty() ) {
      for( String m : ng ) append( "★ " + m );
      if( ng.size() == 1 && ng.get( 0 ).startsWith( "no public key" ) ) {
        append( "  Add a public key first, then press the button again." );
      }
      return;
    }
    // ★ #955: 同じ rootfs で別の Emulin が動いていると、その guest の claude / codex の
    //   認証が切れる。sshd は Emulin をもう 1 つ増やすので、押す前に言う。
    java.util.List<InstanceRegistry.Instance> others =
        InstanceRegistry.othersOnSameRootfs( GuestLaunch.rootfs( home ).getPath() );
    if( !others.isEmpty() ) {
      append( "! Another Emulin is running on the same rootfs (pid " + others.get( 0 ).pid + ")。" );
      append( "  Starting sshd would break the credentials of the claude / codex running there (#955)." );
    }
    int port = enteredPort();
    sshd.start( port, m -> SwingUtilities.invokeLater( () -> { append( m ); refresh(); } ) );
    refresh();
  }

  private JButton button( String text, boolean primary, java.awt.event.ActionListener a ) {
    JButton b = new JButton( text );
    b.setFocusPainted( false );
    b.setBorder( new EmptyBorder( 9, 18, 9, 18 ) );
    b.setBackground( primary ? ACC : PANEL );
    b.setForeground( primary ? BG : FG );
    b.setFont( b.getFont().deriveFont( Font.PLAIN, 13f ) );
    b.setCursor( Cursor.getPredefinedCursor( Cursor.HAND_CURSOR ) );
    b.addActionListener( a );
    return b;
  }

  private Font mono( float size ) {
    return new Font( Font.MONOSPACED, Font.PLAIN, (int) size );
  }

  // ------------------------------------------------------------------
  //  ターミナル起動 — ★ emulin.bat に委ねる (wt.exe の判定も launcher が持っている)
  // ------------------------------------------------------------------
  private void openTerminal() { runLauncher( null ); }

  private void runLauncher( String sub ) {
    File bat = new File( home, "emulin.bat" );
    File sh  = new File( home, "emulin.sh" );
    try {
      java.util.List<String> cmd = new java.util.ArrayList<>();
      if( bat.isFile() ) {
        // cmd /c start で**新しいコンソールを開く**。emulin.bat 側が wt.exe へ委譲する。
        cmd.add( "cmd" ); cmd.add( "/c" ); cmd.add( "start" ); cmd.add( "" );
        cmd.add( bat.getAbsolutePath() );
      } else if( sh.isFile() ) {
        // ★ emulin.sh は bash script (配列を使う)。/bin/sh が dash だと即 syntax error。
        cmd.add( new File( "/bin/bash" ).canExecute() ? "/bin/bash" : "/bin/sh" );
        cmd.add( sh.getAbsolutePath() );
      } else {
        append( "emulin.bat / emulin.sh not found: " + home );
        return;
      }
      if( sub != null ) cmd.add( sub );
      new ProcessBuilder( cmd ).directory( home ).start();
      append( "launched: " + String.join( " ", cmd ) );
    } catch( Exception ex ) {
      append( "failed to launch: " + ex );
    }
  }

  // ------------------------------------------------------------------
  //  agent の導入 — ★ 「どのユーザーで実行するか」を UI が肩代わりする
  //    (Codex は root で入れて非 root で設定、Claude は非 root で入れる)。
  // ------------------------------------------------------------------
  private volatile boolean busy = false;

  private void installAgent( AgentInstall.Agent agent ) {
    if( busy ) { append( "Another task is running. Please wait for it to finish." ); return; }
    busy = true;
    append( "==== " + agent.name + " ====" );
    new SwingWorker<Void,String>() {
      @Override protected Void doInBackground() {
        // ★ まず現状を判定する。判定しないと 8 分の apt install を無駄に繰り返す。
        publish( "Checking what is already installed..." );
        AgentInstall.detect( home, java.util.Collections.singletonList( agent ), progressOf( 0 ) );
        for( AgentInstall.Step st : agent.steps ) {
          if( Boolean.TRUE.equals( st.done ) ) { publish( "[done] " + st.title ); continue; }
          publish( "[run ] " + st.title + "   (" + st.userLabel() + ")" );
          GuestJob job = st.toJob();
          job.run( home, progressOf( System.currentTimeMillis() ) );
          if( job.state == GuestJob.State.DONE ) {
            publish( "[ ok ] " + st.title );
          } else {
            // ★ 画面には要約 (末尾 15 行)、全文はファイル。#932 の実害は末尾に出ていた。
            publish( "[FAIL] " + st.title + "  (exit=" + job.exitCode + ")" );
            for( String l : job.tailLines() ) publish( "    " + l );
            if( job.logFile != null ) publish( "  full log: " + job.logFile.getAbsolutePath() );
            publish( "Stopping here (the next step would fail without this one)." );
            return null;
          }
        }
        publish( agent.name + " is ready." );
        return null;
      }
      /** guest の出力を**間引いて** 1 行の進捗として出す。
       *
       *  ★ 間引きが要る理由: apt は 1 秒に数十行出す。全部 publish すると EDT が
       *    描画で埋まり、画面が重くなる (しかも読めない)。
       *  ★ 経過時間を付ける理由: nodejs/npm は約 8 分かかる。止まっているのか
       *    進んでいるのかが**時計でしか分からない**場面がある。 */
      private java.util.function.Consumer<GuestJob> progressOf( final long startedAt ) {
        final long[] lastPub = { 0 };
        return j -> {
          long now = System.currentTimeMillis();
          if( now - lastPub[0] < 500 ) return;
          lastPub[0] = now;
          java.util.List<String> t = j.tailLines();
          if( t.isEmpty() ) return;
          String last = t.get( t.size() - 1 ).trim();
          if( last.isEmpty() ) return;
          if( last.length() > 100 ) last = last.substring( 0, 100 ) + "…";
          String el = "";
          if( startedAt > 0 ) {
            long sec = ( now - startedAt ) / 1000;
            el = String.format( "[%d:%02d] ", sec / 60, sec % 60 );
          }
          publish( PROG + el + last );
        };
      }

      @Override protected void process( java.util.List<String> chunks ) {
        for( String c : chunks ) {
          if( c.startsWith( PROG ) ) appendProgress( c.substring( PROG.length() ) );
          else                       append( c );
        }
      }
      @Override protected void done() { busy = false; detectAll(); }
    }.execute();
  }

  /** 開いた時点 / 導入後に「何が入っているか」を判定する。
   *
   *  ★ guest を 1 回起動するので、5 秒ごとの refresh からは呼ばない (起動が積み上がる)。
   *  ★ 判定しないと利用者は「もう入っているのに 8 分の apt をもう一度」踏む。 */
  private void detectAll() {
    if( busy ) return;
    busy = true;
    new SwingWorker<Void,Void>() {
      @Override protected Void doInBackground() {
        AgentInstall.detect( home, java.util.Arrays.asList( codex, claude ) );
        return null;
      }
      @Override protected void done() { busy = false; refresh(); }
    }.execute();
  }

  /** 進捗行の開始位置 (-1 = 進捗行は出ていない)。 */
  private int progressStart = -1;
  /** 進捗であることを示す内部 marker (SwingWorker の publish は String 1 種類しか運べない)。 */
  private static final String PROG = "\u0000";

  private void append( String s ) {
    progressStart = -1;                     // 通常行が入ったら進捗行は確定させる
    log.append( s + "\n" );
    log.setCaretPosition( log.getDocument().getLength() );
  }

  /** ★ 進捗は**行を増やさず書き換える**。
   *  apt は 1 秒に数十行 (`49% [140 libllvm19 ...]`) 出すので、append すると画面が流れて
   *  直前の工程名が見えなくなる。1 行を上書きし続ける形にする。 */
  private void appendProgress( String s ) {
    String line = "    " + s + "\n";
    int end = log.getDocument().getLength();
    if( progressStart >= 0 && progressStart <= end ) {
      log.replaceRange( line, progressStart, end );
    } else {
      progressStart = end;
      log.append( line );
    }
    log.setCaretPosition( log.getDocument().getLength() );
  }

  // ------------------------------------------------------------------
  //  状態表示 — 収集は EmulinStatus 側 (表示と分離してある)
  // ------------------------------------------------------------------
  private void refresh() {
    status.removeAll();

    java.util.List<InstanceRegistry.Instance> inst = EmulinStatus.instances();
    section( "Emulin instances (" + inst.size() + ")" );
    if( inst.size() > 1 )
      note( "! Multiple Emulin instances are running. They share one credential store, so "
          + "an OAuth token rotation can collide and log one of them out (#943).", WARN );
    if( inst.isEmpty() ) note( "(no Emulin instance is running)", DIM );
    for( InstanceRegistry.Instance i : inst )
      note( "pid " + i.pid + ( i.self ? " (this window)" : "" ) + "   " + i.version
          + "   " + i.backend + "   " + i.rootfs, FG );

    java.util.List<EmulinStatus.GuestProc> ps = EmulinStatus.guestProcesses();
    if( !ps.isEmpty() ) {
      section( "guest processes (" + ps.size() + ")" );
      for( EmulinStatus.GuestProc g : ps )
        note( "pid " + g.pid + "  ppid " + g.ppid + "  " + g.name + "  " + g.cwd, FG );
    }

    section( "SSH server" );
    if( sshd.isRunning() ) {
      long ext = sshd.externalPid( sshd.port() );
      note( "[running] 127.0.0.1:" + sshd.port()
            + ( ext > 0 ? "   (pid " + ext + ", started by another window)" : "" ), OK );
      for( String h : sshd.connectHints() ) note( "      " + h, FG );
      sshdBtn.setText( "Stop" );
    } else {
      note( "[stopped]", DIM );
      // ★ 画面に入っている port で判定する (既定値ではなく)。使用中ならここで見える。
      java.util.List<String> ng = sshd.preflight( enteredPort() );
      for( String m : ng ) note( "      ★ " + m, WARN );
      java.util.Set<String> fps = SshKeys.installed( home );
      if( fps.isEmpty() ) note( "      public key: none (use \"Add public key\")", DIM );
      else for( String fp : fps ) note( "      public key: " + fp, OK );
      sshdBtn.setText( "Start" );
    }

    section( "Agents" );
    for( AgentInstall.Agent a : new AgentInstall.Agent[]{ codex, claude } ) {
      boolean unknown = false, allDone = true;
      for( AgentInstall.Step st : a.steps ) {
        if( st.done == null ) unknown = true;
        else if( !st.done )   allDone = false;
      }
      note( ( unknown ? "[checking ] " : allDone ? "[installed] " : "[   none  ] " ) + a.name, 
            unknown ? DIM : allDone ? OK : DIM );
      if( !unknown && !allDone )
        for( AgentInstall.Step st : a.steps )
          note( "      " + ( Boolean.TRUE.equals( st.done ) ? "done " : "todo " ) + st.title, DIM );
    }

    java.util.List<CredAdmin.Entry> cs = CredAdmin.list();
    if( !cs.isEmpty() ) {
      section( "Credentials  (values are never shown)" );
      for( CredAdmin.Entry c : cs ) {
        note( ( c.registered ? "[registered] " : "[  not set ] " ) + c.name
            + "   -> " + c.host + ( c.savedAt.isEmpty() ? "" : "   " + c.savedAt ),
            c.registered ? OK : DIM );
        // ★ issue #968: 「登録済み」だけでは足りない。**いつのログインか**が見えないと、
        //   期限切れの取り込み元に気付けない (2026-08-25 に 10 日前のもので往復した)。
        if( !c.note.isEmpty() ) note( "      " + c.note, c.warn ? WARN : DIM );
        if( !c.origin.isEmpty() ) note( "      from " + c.origin, DIM );
      }
      String rn = CredAdmin.restartNote();
      note( rn != null ? "! " + rn
                       : "! Credentials are read once, when Emulin starts. "
                       + "Updating them does not affect an instance that is already running.",
            rn != null ? WARN : DIM );
    }

    status.revalidate();
    status.repaint();
  }

  private void section( String title ) {
    if( status.getComponentCount() > 0 ) status.add( Box.createVerticalStrut( 12 ) );
    JLabel l = new JLabel( title );
    l.setForeground( ACC );
    l.setFont( l.getFont().deriveFont( Font.BOLD, 12f ) );
    l.setAlignmentX( Component.LEFT_ALIGNMENT );
    status.add( l );
    status.add( Box.createVerticalStrut( 4 ) );
  }

  private void note( String text, Color color ) {
    JLabel l = new JLabel( text );
    l.setForeground( color );
    l.setFont( mono( 11f ) );
    l.setAlignmentX( Component.LEFT_ALIGNMENT );
    status.add( l );
  }
}
