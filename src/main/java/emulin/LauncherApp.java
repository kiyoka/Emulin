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
    top.add( button( "ターミナルを開く", true, e -> openTerminal() ), BorderLayout.CENTER );
    p.add( top, BorderLayout.NORTH );
    JPanel sub = new JPanel( new GridLayout( 1, 0, 10, 0 ) );
    sub.setOpaque( false );
    sub.add( button( "認証を設定する", false, e -> runLauncher( "setcred" ) ) );
    sub.add( button( "Codex CLI を入れる", false, e -> installAgent( codex ) ) );
    sub.add( button( "Claude Code を入れる", false, e -> installAgent( claude ) ) );
    p.add( sub, BorderLayout.CENTER );
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
        // ★ emulin.sh は bash script (配列を使う)。/bin/sh が dash だと即 syntax error。
        cmd.add( new File( "/bin/bash" ).canExecute() ? "/bin/bash" : "/bin/sh" );
        cmd.add( sh.getAbsolutePath() );
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

  // ------------------------------------------------------------------
  //  agent の導入 — ★ 「どのユーザーで実行するか」を UI が肩代わりする
  //    (Codex は root で入れて非 root で設定、Claude は非 root で入れる)。
  // ------------------------------------------------------------------
  private volatile boolean busy = false;

  private void installAgent( AgentInstall.Agent agent ) {
    if( busy ) { append( "ほかの処理が動いています。終わるまでお待ちください。" ); return; }
    busy = true;
    append( "==== " + agent.name + " ====" );
    new SwingWorker<Void,String>() {
      @Override protected Void doInBackground() {
        // ★ まず現状を判定する。判定しないと 8 分の apt install を無駄に繰り返す。
        publish( "現状を確認しています…" );
        AgentInstall.detect( home, java.util.Collections.singletonList( agent ), progressOf( 0 ) );
        for( AgentInstall.Step st : agent.steps ) {
          if( Boolean.TRUE.equals( st.done ) ) { publish( "[済] " + st.title ); continue; }
          publish( "[実行] " + st.title + "   (" + st.userLabel() + ")" );
          GuestJob job = st.toJob();
          job.run( home, progressOf( System.currentTimeMillis() ) );
          if( job.state == GuestJob.State.DONE ) {
            publish( "[完了] " + st.title );
          } else {
            // ★ 画面には要約 (末尾 15 行)、全文はファイル。#932 の実害は末尾に出ていた。
            publish( "[失敗] " + st.title + "  (exit=" + job.exitCode + ")" );
            for( String l : job.tailLines() ) publish( "    " + l );
            if( job.logFile != null ) publish( "  全文: " + job.logFile.getAbsolutePath() );
            publish( "ここで中断します (この工程が通らないと次は失敗します)" );
            return null;
          }
        }
        publish( agent.name + " の導入が完了しました。" );
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
    section( "Emulin インスタンス (" + inst.size() + ")" );
    if( inst.size() > 1 )
      note( "★ 複数の Emulin が動いています。同じ credential を共有するため、"
          + "OAuth の token 回転が衝突して片方がログアウトされることがあります (#943)。", WARN );
    if( inst.isEmpty() ) note( "(稼働中の Emulin はありません)", DIM );
    for( InstanceRegistry.Instance i : inst )
      note( "pid " + i.pid + ( i.self ? " (この画面)" : "" ) + "   " + i.version
          + "   " + i.backend + "   " + i.rootfs, FG );

    java.util.List<EmulinStatus.GuestProc> ps = EmulinStatus.guestProcesses();
    if( !ps.isEmpty() ) {
      section( "guest プロセス (" + ps.size() + ")" );
      for( EmulinStatus.GuestProc g : ps )
        note( "pid " + g.pid + "  ppid " + g.ppid + "  " + g.name + "  " + g.cwd, FG );
    }

    section( "導入状況" );
    for( AgentInstall.Agent a : new AgentInstall.Agent[]{ codex, claude } ) {
      boolean unknown = false, allDone = true;
      for( AgentInstall.Step st : a.steps ) {
        if( st.done == null ) unknown = true;
        else if( !st.done )   allDone = false;
      }
      note( ( unknown ? "[ 確認中 ] " : allDone ? "[導入済み] " : "[ 未導入 ] " ) + a.name, 
            unknown ? DIM : allDone ? OK : DIM );
      if( !unknown && !allDone )
        for( AgentInstall.Step st : a.steps )
          note( "      " + ( Boolean.TRUE.equals( st.done ) ? "済 " : "未 " ) + st.title, DIM );
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
