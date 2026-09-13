package emulin;

// --------------------------------------------------------------------
//  issue #1046: 「guest に見せる host パス」の設定画面 (#732 の allowlist)。
//
//  ★ **空 = 無制限**。この機能で一番危ないのは「1 件も無い状態」を「全部塞いだ」と
//    読み違えることなので、画面の一番上で **制限なし / 制限あり (N 件)** を言い切る。
//    最後の 1 件を消した瞬間に赤字へ変わるようにしてある (消したことに気付ける)。
//
//  ★ **次に起こす guest から効く。** 許可集合は guest が動き出す前に確定していなければ
//    境界にならないので (#1010 の凍結)、稼働中のインスタンスには反映されない。画面で言う。
//
//  ★ **設定ファイル自身を許可範囲に入れない。** 入れると guest が
//    `~/.emulin/fs-allow.txt` を書き換えて、次回の制限を自分で広げられる。検出して警告する。
//
//  ★ **env は使わない。** ここが書く `~/.emulin/fs-allow.txt` を guest 側 (`FsPolicy`) が
//    起動時に直接読む。env で渡す形は `Open terminal` (wt.exe 経由) で落ちて、
//    **そこだけ無制限の guest が起きる**。
//
//  ★ ここは view。保存・判定は **FsAllow** を通す (CredDialog と同じ取り決め。
//    UI 側に保存を書くと「CLI では書くのに UI では書かない」型が入る)。
// --------------------------------------------------------------------

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;

public final class FsAllowDialog extends JDialog {

  private static final Color BG    = new Color( 0x1e, 0x1e, 0x1e );
  private static final Color PANEL = new Color( 0x25, 0x25, 0x25 );
  private static final Color FG    = new Color( 0xe6, 0xe6, 0xe6 );
  private static final Color DIM   = new Color( 0x9a, 0x9a, 0x9a );
  private static final Color WARN  = new Color( 0xff, 0x9f, 0x43 );
  private static final Color OKC   = new Color( 0x6f, 0xcf, 0x97 );

  private final DefaultListModel<String> model = new DefaultListModel<>();
  private final JList<String> list = new JList<>( model );
  private final JLabel banner = new JLabel();
  private final JTextArea notes = new JTextArea();
  private final Consumer<String> log;

  public FsAllowDialog( Window owner, Consumer<String> log ) {
    super( owner, "Guest file access", ModalityType.APPLICATION_MODAL );
    this.log = ( log != null ) ? log : s -> { };

    JPanel root = new JPanel( new BorderLayout( 0, 10 ) );
    root.setBackground( BG );
    root.setBorder( new EmptyBorder( 14, 16, 14, 16 ) );

    banner.setFont( banner.getFont().deriveFont( Font.BOLD, 14f ) );
    banner.setBorder( new EmptyBorder( 0, 0, 4, 0 ) );
    root.add( banner, BorderLayout.NORTH );

    list.setSelectionMode( ListSelectionModel.SINGLE_SELECTION );
    list.setBackground( PANEL );
    list.setForeground( FG );
    list.setFont( LauncherApp.mono( 12f ) );
    JScrollPane sp = new JScrollPane( list );
    sp.setBorder( BorderFactory.createEmptyBorder() );
    sp.setPreferredSize( new Dimension( 640, 190 ) );
    root.add( sp, BorderLayout.CENTER );

    JPanel south = new JPanel( new BorderLayout( 0, 8 ) );
    south.setOpaque( false );
    south.add( buttons(), BorderLayout.NORTH );

    notes.setEditable( false );
    notes.setLineWrap( true );
    notes.setWrapStyleWord( true );
    notes.setBackground( BG );
    notes.setForeground( DIM );
    notes.setFont( notes.getFont().deriveFont( 11f ) );
    notes.setRows( 7 );
    south.add( notes, BorderLayout.CENTER );
    root.add( south, BorderLayout.SOUTH );

    setContentPane( root );
    reload();
    pack();
    setLocationRelativeTo( owner );
  }

  private JComponent buttons() {
    JPanel p = new JPanel( new FlowLayout( FlowLayout.LEFT, 8, 0 ) );
    p.setOpaque( false );
    p.add( mk( "Add folder...", true,  e -> addFolder() ) );
    p.add( mk( "Remove",        false, e -> removeSelected() ) );
    p.add( mk( "Close",         false, e -> dispose() ) );
    return p;
  }

  private JButton mk( String text, boolean primary, java.awt.event.ActionListener a ) {
    JButton b = new JButton( text );
    LauncherApp.style( b, primary );
    b.addActionListener( a );
    return b;
  }

  // ------------------------------------------------------------------
  private void addFolder() {
    JFileChooser fc = new JFileChooser();
    fc.setFileSelectionMode( JFileChooser.DIRECTORIES_ONLY );
    fc.setDialogTitle( "Folder the guest may see" );
    if( fc.showOpenDialog( this ) != JFileChooser.APPROVE_OPTION ) return;
    File dir = fc.getSelectedFile();
    if( dir == null ) return;
    String path = dir.getAbsolutePath();
    if( FsAllow.invalid( path ) ) {
      // 黙って捨てず、なぜ足せないかを言う。
      JOptionPane.showMessageDialog( this, "This path cannot be used.\n" + path,
          "Cannot add", JOptionPane.WARNING_MESSAGE );
      return;
    }
    if( model.contains( path ) ) return;
    model.addElement( path );
    store();
  }

  private void removeSelected() {
    int i = list.getSelectedIndex();
    if( i < 0 ) return;
    model.remove( i );
    store();
  }

  private void store() {
    List<String> entries = new ArrayList<>();
    for( int i = 0; i < model.size(); i++ ) entries.add( model.get( i ) );
    try {
      FsAllow.save( entries );
      log.accept( entries.isEmpty()
          ? "Guest file access: no restriction (the guest can see the host)."
          : "Guest file access: " + entries.size() + " folder(s) allowed." );
    } catch( Exception e ) {
      JOptionPane.showMessageDialog( this, "Could not save:\n" + e,
          "Error", JOptionPane.ERROR_MESSAGE );
    }
    reload();
  }

  private void reload() {
    List<String> entries = FsAllow.load();
    model.clear();
    for( String e : entries ) model.addElement( e );

    if( !entries.isEmpty() ) {
      banner.setText( "Restricted — " + entries.size() + " folder(s) visible to the guest" );
      banner.setForeground( OKC );
    } else {
      banner.setText( "No restriction — the guest can see the whole host filesystem" );
      banner.setForeground( WARN );
    }
    notes.setText( notesText( entries ) );
  }

  /** 画面に出す注意書き。★ 文面を組み立てる所を分けてあるのは検査のため
   *  (FsAllowSmoke が「空のときに『制限なし』と言うか」を文字列で確かめられる)。 */
  static String notesText( List<String> entries ) {
    StringBuilder b = new StringBuilder();
    if( entries.isEmpty() ) {
      b.append( "* The list is empty, which means NO restriction. The guest (and anything "
              + "it runs) can read and write your files.\n" );
    } else {
      b.append( "* Only the folders above are visible. Everything else on the host is "
              + "reported as \"does not exist\".\n" );
    }
    b.append( "* Takes effect for guests started from now on. Already running instances "
            + "keep the setting they were started with.\n" );
    b.append( "* The guest's own root filesystem is always allowed; it is not affected.\n" );
    b.append( "* Write the path as the host spells it (e.g. C:\\dev\\work). Guest paths such "
            + "as /mnt/c/dev/work also work when that drive is mounted at startup.\n" );
    b.append( "* Saved in " + FsAllow.configFile().getPath() + " and read by every guest "
            + "that starts, including ones you start from a command prompt.\n" );
    if( FsAllow.selfExposed( entries ) )
      b.append( "! One of these folders contains " + FsAllow.configFile().getName()
              + ", so the guest could rewrite this list and widen its own access.\n" );
    return b.toString();
  }
}
