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

  private final File   home;          // 配布ディレクトリ (emulin.bat がある場所)
  private final JFrame frame = new JFrame( "Emulin" );
  private final JTextArea log = new JTextArea();
  private final JPanel  status = new JPanel();

  private LauncherApp( File home ) { this.home = home; }

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
    frame.setSize( 760, 620 );
    frame.setLocationRelativeTo( null );
    frame.setVisible( true );
    refresh();
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
    JPanel p = new JPanel();
    p.setLayout( new BoxLayout( p, BoxLayout.Y_AXIS ) );
    p.setOpaque( false );

    // --- 操作 ---
    JPanel row = new JPanel( new FlowLayout( FlowLayout.LEFT, 10, 0 ) );
    row.setOpaque( false );
    row.setAlignmentX( Component.LEFT_ALIGNMENT );
    row.add( button( "ターミナルを開く", true, e -> openTerminal() ) );
    row.add( button( "認証を設定する", false, e -> runLauncher( "setcred" ) ) );
    p.add( row );
    p.add( Box.createVerticalStrut( 14 ) );

    // --- 状態 ---
    status.setLayout( new BoxLayout( status, BoxLayout.Y_AXIS ) );
    status.setBackground( PANEL );
    status.setBorder( new EmptyBorder( 12, 14, 12, 14 ) );
    JScrollPane sp = new JScrollPane( status );
    sp.setBorder( null );
    sp.getViewport().setBackground( PANEL );
    sp.setAlignmentX( Component.LEFT_ALIGNMENT );
    sp.setPreferredSize( new Dimension( 700, 300 ) );
    p.add( sp );
    p.add( Box.createVerticalStrut( 12 ) );

    // --- ログ ---
    log.setEditable( false );
    log.setBackground( PANEL );
    log.setForeground( DIM );
    log.setFont( mono( 11f ) );
    log.setBorder( new EmptyBorder( 8, 10, 8, 10 ) );
    JScrollPane lp = new JScrollPane( log );
    lp.setBorder( null );
    lp.setAlignmentX( Component.LEFT_ALIGNMENT );
    lp.setPreferredSize( new Dimension( 700, 120 ) );
    p.add( lp );
    return p;
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
        cmd.add( "/bin/sh" ); cmd.add( sh.getAbsolutePath() );
      } else {
        append( "emulin.bat / emulin.sh が見つかりません: " + home );
        return;
      }
      if( sub != null ) cmd.add( sub );
      new ProcessBuilder( cmd ).directory( home ).start();
      append( "起動しました: " + String.join( " ", cmd ) );
    } catch( Exception ex ) {
      append( "起動に失敗しました: " + ex );
    }
  }

  private void append( String s ) {
    log.append( s + "\n" );
    log.setCaretPosition( log.getDocument().getLength() );
  }

  // ------------------------------------------------------------------
  //  状態表示 — 収集は EmulinStatus 側 (表示と分離してある)
  // ------------------------------------------------------------------
  private void refresh() {
    status.removeAll();

    java.util.List<EmulinStatus.Instance> inst = EmulinStatus.instances();
    section( "Emulin インスタンス (" + inst.size() + ")" );
    if( inst.size() > 1 )
      note( "★ 複数の Emulin が動いています。同じ credential を共有するため、"
          + "OAuth の token 回転が衝突して片方がログアウトされることがあります (#943)。", WARN );
    if( inst.isEmpty() ) note( "(稼働中の Emulin はありません)", DIM );
    for( EmulinStatus.Instance i : inst )
      note( "pid " + i.pid + ( i.self ? " (この画面)" : "" ) + "   " + i.version
          + "   " + i.backend + "   " + i.rootfs, FG );

    java.util.List<EmulinStatus.GuestProc> ps = EmulinStatus.guestProcesses();
    if( !ps.isEmpty() ) {
      section( "guest プロセス (" + ps.size() + ")" );
      for( EmulinStatus.GuestProc g : ps )
        note( "pid " + g.pid + "  ppid " + g.ppid + "  " + g.name + "  " + g.cwd, FG );
    }

    java.util.List<EmulinStatus.Cred> cs = EmulinStatus.credentials();
    if( !cs.isEmpty() ) {
      section( "credential  (値は表示しません)" );
      for( EmulinStatus.Cred c : cs )
        note( ( c.registered ? "[登録済み] " : "[ 未設定 ] " ) + c.name
            + "   -> " + c.host + ( c.savedAt.isEmpty() ? "" : "   " + c.savedAt ),
            c.registered ? OK : DIM );
      note( "★ credential は Emulin の起動時に一度だけ読まれます。"
          + "setcred で更新しても、稼働中のインスタンスには反映されません。", DIM );
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
