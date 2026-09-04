package emulin;

import java.awt.*;
import java.io.File;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

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
//  ★ ターミナルの**中身**は emulin.bat に委ねる。あれは wt-setup.ps1 の適用 (#124) と
//    ログインユーザ選択を持っている。**同じロジックを 2 箇所に書かない** (#919 で
//    「launcher が 2 系統あり出荷側を検証していなかった」を踏んでいる)。
//  ★ ただし**窓の作り方だけは launcher が持つ** (#976)。wt.exe を直接起こして、中継の
//    cmd が作る黒い窓を出さない。bat 側の「wt.exe で自分を起動し直す」経路は
//    emulin.bat を直接叩く利用者のために残す (WT の中では WT_SESSION が立つので、
//    launcher 経由ではそこを通らない = 二重に起動しない)。
//
//  起動: emulin.bat app  /  emulin-app.bat (ダブルクリック)
// --------------------------------------------------------------------
public final class LauncherApp {

  // 配色 (FlatLaf は Apache-2.0 で GPLv2 と非互換なので使えない。自前で持つ)
  static final Color BG    = new Color( 0x14, 0x16, 0x1b );
  static final Color PANEL = new Color( 0x1b, 0x1e, 0x26 );
  static final Color FG    = new Color( 0xe6, 0xe6, 0xe6 );
  static final Color DIM   = new Color( 0x8b, 0x93, 0xa1 );
  static final Color ACC   = new Color( 0x8a, 0xb4, 0xf8 );
  // ★ 主操作の塗り。以前は ACC (淡い青) を**枠なしで横幅いっぱい**に敷いていたが、
  //   窓の title bar と見分けが付かず、利用者が窓を動かそうとして掴み = 押してしまった。
  //   濃い青 + 白文字にして「面」ではなく「押せるもの」に見せる。
  static final Color BTN   = new Color( 0x1a, 0x73, 0xe8 );
  static final Color BTN_FG= new Color( 0xff, 0xff, 0xff );
  static final Color BTN_ED= new Color( 0x0f, 0x50, 0xa8 );
  static final Color EDGE  = new Color( 0x36, 0x3c, 0x49 );
  static final Color WARN  = new Color( 0xf2, 0xb8, 0xb5 );
  static final Color OK    = new Color( 0x9a, 0xe6, 0xb4 );

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
  // ★ issue #988: JLabel を並べる形をやめ、面ごと 1 つにして選択・コピーできるようにした。
  private final StyledText status = new StyledText( PANEL );

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
    // ★ **WEST (文字幅) にする。CENTER で横幅いっぱいに伸ばすと窓の帯に見える**
    //   (利用者が窓枠と誤認して掴んだ)。1 行に 1 個なので折り返しは起きない。
    top.add( button( "Open terminal", true, e -> openTerminal() ), BorderLayout.WEST );
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
    // ★ 入力欄も枠なしだと「表示だけ」に見える。ボタンと同じ枠を付ける。
    sshdPort.setBorder( BorderFactory.createCompoundBorder(
                          new LineBorder( EDGE, 1, true ), new EmptyBorder( 5, 8, 5, 8 ) ) );
    row.add( sshdPort );
    sshdBtn.setText( "Start" );
    style( sshdBtn, false );
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
  //  credential (issue #968)
  //
  //  ★ 画面は **独立した窓** (CredDialog: 一覧 + 詳細)。ランチャー本体に混ぜない —
  //    ここは インスタンス / guest / SSH / 導入状況 / ログ が同居しているので、
  //    credential の詳細まで入れると窓の縦を食い合う (#968 で「表 + 下に詳細」を
  //    却下した理由がそのまま当てはまる)。
  //  ★ 判定・保存・削除は CredAdmin。ランチャーも CredDialog も CLI も同じ道を通る。
  // ------------------------------------------------------------------
  private void setupCredentials() {
    new CredDialog( frame, this::append, this::refresh ).setVisible( true );
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
    warnOthers( othersHere(), "Starting sshd" );
    int port = enteredPort();
    sshd.start( port, m -> SwingUtilities.invokeLater( () -> { append( m ); refresh(); } ) );
    refresh();
  }

  // ------------------------------------------------------------------
  //  issue #955: 稼働中の rootfs に **もう 1 つ Emulin を起こさない**
  //
  //  ★ 起動のたびに guest の credential placeholder が書き直される (#824 で意図的)。
  //    稼働中インスタンスの claude / codex はそれを知らない値として送るので素通しになり、
  //    401 -> ログアウトする。**向こうは何も操作していないのに壊れる**。
  //  ★ guest を起こす経路は 3 つ (detect / install / sshd)。判定と文面をここに寄せる
  //    (#919 の教訓: 同じ規則を 2 か所に書くと片方だけ直る)。
  // ------------------------------------------------------------------
  private java.util.List<InstanceRegistry.Instance> othersHere() {
    return InstanceRegistry.othersOnSameRootfs( GuestLaunch.rootfs( home ).getPath() );
  }

  /** ★ 「何が起きるか」まで書く。「別のが動いています」だけでは、なぜ困るのかが伝わらない。
   *  @param whatItWouldDo 動名詞句 ("Installing" 等) */
  private void warnOthers( java.util.List<InstanceRegistry.Instance> others, String whatItWouldDo ) {
    if( others.isEmpty() ) return;
    StringBuilder pids = new StringBuilder();
    for( InstanceRegistry.Instance i : others ) {
      if( pids.length() > 0 ) pids.append( ", " );
      pids.append( i.pid );
      if( !i.label().isEmpty() ) pids.append( " [" ).append( i.label() ).append( "]" );
    }
    append( "! Another Emulin is running on this rootfs (pid " + pids + ")." );
    append( "  " + whatItWouldDo + " would start one more Emulin, rewriting the credential"
          + " placeholders and logging out the claude / codex running there (#955)." );
  }

  private JButton button( String text, boolean primary, java.awt.event.ActionListener a ) {
    JButton b = new JButton( text );
    style( b, primary );
    b.addActionListener( a );
    return b;
  }

  /** ボタンの見た目。★ **枠 (LineBorder) を必ず付ける**。塗りつぶしだけの矩形は窓の
   *  一部に見え、実際に利用者が Open terminal を窓枠と誤認して**掴んで動かそうとした**
   *  (= 押してしまい terminal が起動した)。枠と余白があると「押せるもの」に見える。
   *  ★ 見た目は 1 箇所に閉じる。sshd の Start ボタンだけ別に書いていたので、そこも通す。 */
  static void style( JButton b, boolean primary ) {
    b.setFocusPainted( false );
    b.setBorder( BorderFactory.createCompoundBorder(
                   new LineBorder( primary ? BTN_ED : EDGE, 1, true ),
                   new EmptyBorder( 8, 17, 8, 17 ) ) );
    b.setBackground( primary ? BTN : PANEL );
    b.setForeground( primary ? BTN_FG : FG );
    b.setFont( b.getFont().deriveFont( primary ? Font.BOLD : Font.PLAIN, 13f ) );
    b.setCursor( Cursor.getPredefinedCursor( Cursor.HAND_CURSOR ) );
  }

  static String pad( String s, int n ) {
    StringBuilder b = new StringBuilder( s == null ? "" : s );
    while( b.length() < n ) b.append( ' ' );
    return b.toString();
  }

  static Font mono( float size ) {
    return new Font( Font.MONOSPACED, Font.PLAIN, (int) size );
  }

  // ------------------------------------------------------------------
  //  ターミナル起動 (issue #976)
  //
  //  ★ 以前は `cmd /c start "" <bat>` だった。**start は最初のトークンが .bat のとき
  //    `cmd /K` で開く** (実測: 起きた窓のコマンドラインが `/K` だった)。/K は bat が
  //    終わっても閉じないので、bat が wt.exe へ委譲して `exit /b 0` したあとも
  //    **空の黒い窓が残り続ける**。利用者の報告はこれ。
  //  ★ そこで **wt.exe を直接起こす**。wt.exe は GUI subsystem なので console が
  //    割り当てられず、黒い窓が一瞬も出ない。中継の cmd も無くなる。
  //  ★ WT の中では WT_SESSION が立つので、emulin.bat は再委譲せずそのまま guest を
  //    起動し、wt-setup.ps1 (#124) も bat 側の WT_SESSION 分岐が呼ぶ。**「wt を使うか」の
  //    判定を launcher 側にコピーしない** (#919 の 2 系統を作らない)。
  //  ★ wt が無い環境は `where wt` を書かず、**起こしてみて失敗したら落とす**。存在確認を
  //    別に書くと、それ自体が 2 系統目の規則になる。
  // ------------------------------------------------------------------
  private void openTerminal() {
    File bat = new File( home, "emulin.bat" );
    File sh  = new File( home, "emulin.sh" );
    if( bat.isFile() ) {
      if( start( "wt.exe", "--", "cmd", "/c", bat.getAbsolutePath() ) ) return;
      // ★ wt が無い環境。start の対象を **cmd /c** にする。bat をそのまま渡すと cmd /K で
      //   開き、guest が終わったあとも空の窓が残る (#976 の元の形)。
      if( start( "cmd", "/c", "start", "", "cmd", "/c", bat.getAbsolutePath() ) ) return;
      append( "could not open a terminal" );
      return;
    }
    if( sh.isFile() ) {
      // ★ emulin.sh は bash script (配列を使う)。/bin/sh が dash だと即 syntax error。
      String shell = new File( "/bin/bash" ).canExecute() ? "/bin/bash" : "/bin/sh";
      if( !start( shell, sh.getAbsolutePath() ) ) append( "could not open a terminal" );
      return;
    }
    append( "emulin.bat / emulin.sh not found: " + home );
  }

  /** 起こせたら true。★ 実行ファイルが無ければ ProcessBuilder.start() が IOException を
   *  投げるので、それを「この経路は使えない」の合図に使う (存在確認を別に書かない)。 */
  private boolean start( String... cmd ) {
    try {
      terminalBuilder( home, cmd ).start();
      append( "launched: " + String.join( " ", cmd ) );
      return true;
    } catch( Exception ex ) {
      append( "  (" + cmd[0] + " not available: " + ex.getMessage() + ")" );
      return false;
    }
  }

  /** 端末を起こす ProcessBuilder を作る。
   *
   *  ★ **起動と分けてある理由は検査のため** (SshdService.sshdBuilder と同じ)。検査側が
   *    素の `new ProcessBuilder(...)` を組み立てると、ここが元に戻っても**緑のまま通る**。
   *    検査は必ずこのメソッドを通すこと。 */
  static ProcessBuilder terminalBuilder( File home, String... cmd ) {
    ProcessBuilder pb = new ProcessBuilder( cmd ).directory( home );
    applySessionPool( pb.environment() );
    return pb;
  }

  /** issue #985: 端末セッションの native pool を **sshd 経路と同じ 1024** にする。
   *
   *  ★ 以前はここで何もしていなかったので、Open terminal だけが `emulin.bat` の既定
   *    2048 で動いていた。エージェントは自分の他にシェル / ツールを並行して起こすため、
   *    2048 のままだと 32GB 窓に入りきらず software backend に落ちて極端に遅くなる
   *    (#379)。しかも**画面には何も出ない**ので、利用者には「Emulin が遅い」としか
   *    見えない。
   *
   *  ★ **利用者が明示した値は尊重する**。それが可能なのは、`emulin.bat app` /
   *    `emulin.sh app` が **自分の既定 2048 をランチャーに渡さない**ようにしてあるから
   *    (dist/build-demo-bundle.sh)。そこが戻ると「未設定」と「利用者が 2048 を指定」を
   *    区別できなくなり、この判定は黙って効かなくなる。 */
  static void applySessionPool( java.util.Map<String,String> env ) {
    String cur = env.get( "EMULIN_NATIVE_POOL_MB" );
    if( cur == null || cur.trim().isEmpty() )
      env.put( "EMULIN_NATIVE_POOL_MB", String.valueOf( GuestLaunch.AGENT_POOL_MB ) );
  }

  // ------------------------------------------------------------------
  //  agent の導入 — ★ 「どのユーザーで実行するか」を UI が肩代わりする
  //    (Codex は root で入れて非 root で設定、Claude は非 root で入れる)。
  // ------------------------------------------------------------------
  private volatile boolean busy = false;
  /** #955 で判定を見送ったか。★ 見送ったまま "[checking ]" を出し続けると**嘘になる**。 */
  private volatile boolean detectSkipped = false;

  private void installAgent( AgentInstall.Agent agent ) {
    if( busy ) { append( "Another task is running. Please wait for it to finish." ); return; }
    // ★ #955: こちらは**押して起こす**経路なので、頭ごなしに止めず確認を出す。
    //   既定は No (稼働中セッションを壊す方を既定にしない)。
    java.util.List<InstanceRegistry.Instance> others = othersHere();
    if( !others.isEmpty() ) {
      warnOthers( others, "Installing" );
      int yn = JOptionPane.showConfirmDialog( frame,
          "Another Emulin is running on this rootfs.\n\n"
        + "Installing starts one more Emulin. That rewrites the credential\n"
        + "placeholders and logs out the claude / codex running there (#955).\n\n"
        + "Close the other Emulin first. Install anyway?",
          "Another Emulin is running", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE );
      if( yn != JOptionPane.YES_OPTION ) { append( "Cancelled." ); return; }
    }
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
    // ★ #955: これは**窓を開いただけで自動で走る**経路。押してもいないのに稼働中の
    //   セッションを壊すのが最悪の形なので、ここは警告ではなく**見送る**。
    //   (押して起こす install / sshd は確認を出したうえで進める余地がある)
    java.util.List<InstanceRegistry.Instance> others = othersHere();
    if( !others.isEmpty() ) {
      detectSkipped = true;
      warnOthers( others, "Checking what is installed" );
      append( "  Skipped the check. Close the other Emulin, then reopen this window." );
      refresh();
      return;
    }
    detectSkipped = false;
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
    // ★ issue #988: 選択中は作り直さない。5 秒ごとの再描画が**選択を消す**と、
    //   コピーしようとしている最中に消えることになり、一番困る形になる。
    //   選択を外せば次の tick から再開する。
    if( status.hasSelection() ) return;
    // ★ 読んでいる途中で先頭へ飛ばさない。作り直すと viewport が 0 に戻るため、
    //   位置を控えて復元する (5 秒ごとに起きるので体感に直結する)。
    JViewport vp = (JViewport) SwingUtilities.getAncestorOfClass( JViewport.class, status );
    Point pos = ( vp != null ) ? vp.getViewPosition() : null;

    status.clear();

    java.util.List<InstanceRegistry.Instance> inst = EmulinStatus.instances();
    section( "Emulin instances (" + inst.size() + ")" );
    if( inst.size() > 1 )
      note( "! Multiple Emulin instances are running. They share one credential store, so "
          + "an OAuth token rotation can collide and log one of them out (#943).", WARN );
    if( inst.isEmpty() ) note( "(no Emulin instance is running)", DIM );
    for( InstanceRegistry.Instance i : inst )
      // ★ 役割 ([sshd:2222] 等) を出す (#963)。pid だけだと「どれを止めればよいか」が
      //   画面から決められない (実機で困った)。分からないものには何も付けない。
      note( "pid " + i.pid + ( i.self ? " (this window)" : "" )
          + ( i.label().isEmpty() ? "" : "  [" + i.label() + "]" )
          + "   " + i.version + "   " + i.backend + "   " + i.rootfs, FG );

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
      // ★ 判定を見送った (#955) ときに "[checking ]" のままにしない。押しても進まない
      //   ものを「確認中」と出し続けるのは嘘で、利用者は待ってしまう。
      note( ( unknown ? ( detectSkipped ? "[unchecked] " : "[checking ] " )
                      : allDone ? "[installed] " : "[   none  ] " ) + a.name,
            unknown ? DIM : allDone ? OK : DIM );
      if( unknown && detectSkipped )
        note( "      not checked: another Emulin is running on this rootfs (#955)", WARN );
      if( !unknown && !allDone )
        for( AgentInstall.Step st : a.steps )
          note( "      " + ( Boolean.TRUE.equals( st.done ) ? "done " : "todo " ) + st.title, DIM );
    }

    // ★ issue #968: ここは **provider 単位 (操作の単位) の要約**だけにする。
    //   以前は保存項目 11 行をそのまま並べていたが、CODEX_* 4 行のように単独では
    //   操作しない行が並ぶと読み飛ばされる (#968 のコメントで表形式を却下した理由)。
    //   内訳と操作は CredDialog (一覧 + 詳細) が持つ。
    java.util.List<CredAdmin.Entry> cs = CredAdmin.list();
    if( !cs.isEmpty() ) {
      section( "Credentials  (values are never shown - press \"Set up credentials\" to change)" );
      for( SetCred.Provider p : SetCred.SETTABLE ) {
        String prefix = CredAdmin.prefixOf( p.env );
        int items = 0; boolean reg = false, warn = false; String age = "", host = "";
        for( CredAdmin.Entry c : cs ) {
          if( !prefix.equals( CredAdmin.prefixOf( c.name ) ) ) continue;
          items++;
          if( !c.registered ) continue;
          reg = true;
          warn |= c.warn;
          if( host.isEmpty() ) host = c.host;
          if( age.isEmpty() && !c.note.isEmpty() ) age = c.note;
        }
        note( ( reg ? "[registered] " : "[  not set ] " ) + pad( p.label, 36 )
            + ( reg ? "-> " + host + "   (" + items + " item" + ( items == 1 ? "" : "s" ) + ")" : "" ),
              reg ? OK : DIM );
        if( reg && !age.isEmpty() ) note( "      " + age, warn ? WARN : DIM );
      }
      String rn = CredAdmin.restartNote();
      note( rn != null ? "! " + rn
                       : "! Credentials are read once, when Emulin starts. "
                       + "Updating them does not affect an instance that is already running.",
            rn != null ? WARN : DIM );
    }

    status.revalidate();
    status.repaint();
    if( vp != null && pos != null ) {
      final JViewport v = vp; final Point pp = pos;
      SwingUtilities.invokeLater( () -> v.setViewPosition( pp ) );
    }
  }

  private void section( String title ) {
    if( !status.isEmpty() ) status.append( "", DIM, false, 11f, true );   // 区切りの空行
    status.append( title, ACC, true, 12f, false );
  }

  private void note( String text, Color color ) {
    status.append( text, color, false, 11f, true );
  }
}
