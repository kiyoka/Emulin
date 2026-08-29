package emulin;

// --------------------------------------------------------------------
//  issue #968: credential の画面 — 「一覧 (provider) + 詳細」。
//
//  ★ 左は **provider = 操作の単位** で固定。保存されている項目 (11 件) をそのまま並べる
//    表は #968 で却下した: `CODEX_*` 4 件のように**単独では操作しない**項目が並ぶと、
//    「どれを触ればよいか」が読み取れず、[registered] の列が読み飛ばされる。
//    保存の形は詳細の「内訳」に畳んで、困ったときだけ開く。
//  ★ 操作 (取り込み / 貼り付け / 検証 / 削除) は**選んだ provider の分だけ**出す。
//    どの provider に効くのかが曖昧にならない。以前は選択と無関係な 5 ボタンが並んでいた。
//  ★ 値は 1 文字も出さない (#401)。出すのは状態・出所・日時・送り先・内訳の**名前**だけ。
//  ★ ここは view。探索・判定・保存・削除は **CredAdmin** を通す。UI 側に保存を書くと
//    「CLI では meta を書くのに UI では書かない」型がすぐ入る (#968 の取り決め)。
// --------------------------------------------------------------------

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;

final class CredDialog extends JDialog {

  private final Consumer<String> log;        // ランチャーのログ欄へ (★ 値は流さない)
  private final Runnable         onChanged;  // ダッシュボード側の再描画

  private final DefaultListModel<String> rows      = new DefaultListModel<>();
  private final JList<String>            providers = new JList<>( rows );
  private final JPanel                   detail    = new JPanel();
  private boolean                        breakdown;   // 内訳を開いているか
  private List<String>                   distros;     // wsl.exe -l -q の結果 (1 回だけ)

  CredDialog( Window owner, Consumer<String> log, Runnable onChanged ) {
    super( owner, "Credentials", ModalityType.APPLICATION_MODAL );
    this.log = log;
    this.onChanged = onChanged;

    providers.setSelectionMode( ListSelectionModel.SINGLE_SELECTION );
    providers.setFont( LauncherApp.mono( 12f ) );
    providers.setBackground( LauncherApp.PANEL );
    providers.setForeground( LauncherApp.FG );
    providers.setSelectionBackground( LauncherApp.BTN );
    providers.setSelectionForeground( LauncherApp.BTN_FG );
    providers.setBorder( new EmptyBorder( 10, 12, 10, 12 ) );
    providers.addListSelectionListener( e -> { if( !e.getValueIsAdjusting() ) showDetail(); } );

    detail.setLayout( new BoxLayout( detail, BoxLayout.Y_AXIS ) );
    detail.setBackground( LauncherApp.BG );
    detail.setBorder( new EmptyBorder( 14, 18, 14, 18 ) );

    JScrollPane left = new JScrollPane( providers );
    left.setBorder( null );
    left.getViewport().setBackground( LauncherApp.PANEL );
    JScrollPane right = new JScrollPane( detail );
    right.setBorder( null );
    right.getViewport().setBackground( LauncherApp.BG );

    JSplitPane split = new JSplitPane( JSplitPane.HORIZONTAL_SPLIT, left, right );
    split.setBorder( null );
    split.setBackground( LauncherApp.BG );
    split.setDividerSize( 8 );

    JPanel root = new JPanel( new BorderLayout( 0, 10 ) );
    root.setBackground( LauncherApp.BG );
    root.setBorder( new EmptyBorder( 14, 16, 14, 16 ) );
    JLabel head = new JLabel( "Keys stay on this host. The guest only ever gets placeholders,"
                            + " which the sandbox swaps back on the wire." );
    head.setForeground( LauncherApp.DIM );
    head.setFont( LauncherApp.mono( 11f ) );
    root.add( head, BorderLayout.NORTH );
    root.add( split, BorderLayout.CENTER );

    JPanel south = new JPanel( new FlowLayout( FlowLayout.RIGHT, 8, 0 ) );
    south.setOpaque( false );
    south.add( btn( "Close", false, e -> dispose() ) );
    root.add( south, BorderLayout.SOUTH );
    setContentPane( root );

    // ★ 固定 px にしない (LauncherApp と同じ理由)。4K/200% では論理 px が半分になる。
    Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
    setSize( Math.max( 900, Math.min( 1240, scr.width  * 3 / 5 ) ),
             Math.max( 560, Math.min(  880, scr.height * 2 / 3 ) ) );
    setMinimumSize( new Dimension( 780, 520 ) );
    setLocationRelativeTo( owner );
    split.setDividerLocation( 320 );
    reload( 0 );
  }

  // ------------------------------------------------------------------
  //  左: provider の一覧 (● 登録済み / ○ 未登録)
  // ------------------------------------------------------------------
  private void reload( int select ) {
    int keep = ( select >= 0 ) ? select : providers.getSelectedIndex();
    List<CredAdmin.Entry> all = CredAdmin.list();
    rows.clear();
    for( SetCred.Provider p : SetCred.SETTABLE ) {
      boolean reg = false;
      for( CredAdmin.Entry e : all )
        if( e.registered && CredAdmin.prefixOf( p.env ).equals( CredAdmin.prefixOf( e.name ) ) ) reg = true;
      rows.addElement( ( reg ? "●  " : "○  " ) + p.label );
    }
    if( !rows.isEmpty() )
      providers.setSelectedIndex( ( keep >= 0 && keep < rows.size() ) ? keep : 0 );
    showDetail();
  }

  // ------------------------------------------------------------------
  //  右: 選んだ provider の詳細
  // ------------------------------------------------------------------
  private void showDetail() {
    detail.removeAll();
    int i = providers.getSelectedIndex();
    if( i < 0 || i >= SetCred.SETTABLE.length ) { detail.revalidate(); detail.repaint(); return; }
    SetCred.Provider p = SetCred.SETTABLE[i];
    String prefix = CredAdmin.prefixOf( p.env );

    List<CredAdmin.Entry> items = new ArrayList<>();
    for( CredAdmin.Entry e : CredAdmin.list() )
      if( prefix.equals( CredAdmin.prefixOf( e.name ) ) ) items.add( e );

    boolean reg = false, warn = false;
    String origin = "", saved = "", age = "";
    for( CredAdmin.Entry e : items ) {
      if( !e.registered ) continue;
      reg = true;
      warn |= e.warn;
      if( origin.isEmpty() ) origin = e.origin;
      if( saved.isEmpty()  ) saved  = e.savedAt;
      if( age.isEmpty()    ) age    = e.note;
    }

    title( p.label );
    kv( "State",   reg ? "registered" : "not set", reg ? LauncherApp.OK : LauncherApp.DIM );
    if( reg ) {
      kv( "Source",  origin.isEmpty() ? "(not recorded - imported before this was added)" : origin,
          LauncherApp.FG );
      kv( "Saved",   saved.isEmpty() ? "(unknown)" : saved, LauncherApp.FG );
      kv( "Expiry",  expiry( p, age ), warn ? LauncherApp.WARN : LauncherApp.DIM );
    }
    kv( "Sent to", CredentialStore.hostFor( p.env ), reg ? LauncherApp.FG : LauncherApp.DIM );

    // --- 取り込み元の候補 (ファイルから取る provider だけ) ---
    List<CredAdmin.Source> src = sources( p );
    if( fileBased( p ) ) {
      gap();
      sub( "Host logins found (" + src.size() + ")" );
      if( src.isEmpty() )
        line( "  none - log in on the host first (see the steps below)", LauncherApp.DIM );
      for( CredAdmin.Source s : src )
        line( "  " + ( s.reject == null ? "[ use  ] " : "[ skip ] " ) + s.label
            + "   - " + ( s.reject != null ? s.reject : s.note ),
              s.reject != null ? LauncherApp.DIM : s.warn ? LauncherApp.WARN : LauncherApp.FG );
    }

    // --- 操作 (★ この provider にだけ効く) ---
    gap();
    JPanel ops = new JPanel( new FlowLayout( FlowLayout.LEFT, 8, 0 ) );
    ops.setOpaque( false );
    ops.setAlignmentX( Component.LEFT_ALIGNMENT );
    if( fileBased( p ) ) {
      ops.add( btn( "Import from a host login...", true,  e -> importFromSource( p ) ) );
      ops.add( btn( "Choose a file...",            false, e -> importFromFile( p ) ) );
      // ★ WSL2 がある host だけ。無い所に出すと「押せるのに何も起きない」になる。
      if( !wslDistros().isEmpty() )
        ops.add( btn( "Choose a file from WSL2...", false, e -> importFromWsl( p ) ) );
    } else {
      ops.add( btn( reg ? "Replace the key..." : "Paste a key...", true, e -> paste( p ) ) );
    }
    JButton verify = btn( "Verify", false, e -> verify( p ) );
    verify.setEnabled( reg && hasProbe( p ) );
    ops.add( verify );
    JButton remove = btn( "Remove", false, e -> remove( p, prefix ) );
    remove.setEnabled( reg );
    ops.add( remove );
    detail.add( ops );

    // --- 警告 (★ 一覧の行に詰め込むと読まれないので、詳細側に置く) ---
    gap();
    if( reg && !hasProbe( p ) )
      line( "! Verify is not available for a subscription login: there is no probe endpoint,"
          + " so it is checked on the wire when the guest first uses it.", LauncherApp.DIM );
    if( p.fromClaudeCredentialsJson )
      line( "! Log in with a dedicated config dir:  CLAUDE_CONFIG_DIR=~/.claude-emulin claude auth login"
          + "  - OAuth refresh tokens rotate, so sharing your everyday login logs the other"
          + " session out.", LauncherApp.DIM );
    String rn = CredAdmin.restartNote();
    line( rn != null ? "! " + rn
                     : "! Credentials are read once, when Emulin starts. Changing them here does"
                     + " not affect an instance that is already running.",
          rn != null ? LauncherApp.WARN : LauncherApp.DIM );

    // --- 内訳 (畳んでおく) ---
    gap();
    JCheckBox cb = new JCheckBox( "Show the stored items (" + items.size() + ")", breakdown );
    cb.setOpaque( false );
    cb.setForeground( LauncherApp.DIM );
    cb.setFont( LauncherApp.mono( 11f ) );
    cb.setAlignmentX( Component.LEFT_ALIGNMENT );
    cb.addActionListener( e -> { breakdown = cb.isSelected(); showDetail(); } );
    detail.add( cb );
    if( breakdown )
      for( CredAdmin.Entry e : items )
        line( "    " + ( e.registered ? "[registered] " : "[  not set ] " ) + e.name
            + ( e.savedAt.isEmpty() ? "" : "   " + e.savedAt ),
              e.registered ? LauncherApp.FG : LauncherApp.DIM );

    // --- 取り方 (provider ごとの手順。貼り付けの窓にも同じものを出す) ---
    gap();
    sub( "How to get it" );
    for( String l : p.howto ) line( "  " + l, LauncherApp.DIM );

    detail.revalidate();
    detail.repaint();
  }

  /** 期限の行。★ **登録済みの access token の残り時間は書けない**: 回転は wire 上で起き、
   *  新しい期限は store に書き戻らない (#824 の設計)。ここで言えるのは「登録からの経過」と
   *  refresh token の寿命 (約 1 週間) との関係だけ。分からないものを「あと N 時間」と
   *  書くと、それ自体が誤診の材料になる。取り込み**元ファイル**の残り時間は、上の
   *  「Host logins found」に出る (そちらは `expiresAt` がファイルに書いてある)。 */
  private String expiry( SetCred.Provider p, String age ) {
    if( !fileBased( p ) ) return age.isEmpty() ? "API keys do not expire" : age;
    return ( age.isEmpty() ? "registered" : age )
         + "   (the access token rotates on the wire; its remaining time is not knowable here)";
  }

  private static boolean fileBased( SetCred.Provider p ) {
    return p.fromClaudeCredentialsJson || p.fromCodexAuthJson;
  }

  private static boolean hasProbe( SetCred.Provider p ) {
    return p.probe != null && !p.probe.isEmpty();
  }

  /** この provider の取り込み元候補。★ 探索は SetCred.findHostLogins 1 本 (#968)。 */
  private static List<CredAdmin.Source> sources( SetCred.Provider p ) {
    if( p.fromClaudeCredentialsJson ) return CredAdmin.claudeSources();
    if( p.fromCodexAuthJson )         return CredAdmin.codexSources();
    return new ArrayList<>();
  }

  // ------------------------------------------------------------------
  //  操作
  // ------------------------------------------------------------------
  private void importFromSource( SetCred.Provider p ) {
    List<CredAdmin.Source> src = sources( p );
    if( src.isEmpty() ) { log.accept( "No host login found for " + p.label + "." ); return; }
    DefaultListModel<String> m = new DefaultListModel<>();
    for( CredAdmin.Source s : src )
      m.addElement( ( s.reject == null ? "[ use  ] " : "[ skip ] " ) + s.label
          + "   - " + s.path + "   " + ( s.reject != null ? "(" + s.reject + ")" : s.note ) );
    JList<String> list = new JList<>( m );
    list.setSelectionMode( ListSelectionModel.SINGLE_SELECTION );
    list.setFont( LauncherApp.mono( 11f ) );
    for( int i = 0; i < src.size(); i++ )
      if( src.get( i ).reject == null ) { list.setSelectedIndex( i ); break; }
    JScrollPane sp = new JScrollPane( list );
    sp.setPreferredSize( new Dimension( 880, 200 ) );

    Object[] options = { "Import", "Cancel" };
    int r = JOptionPane.showOptionDialog( this, sp, "Import a login for " + p.label,
        JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0] );
    if( r != 0 ) return;
    int i = list.getSelectedIndex();
    if( i < 0 ) { log.accept( "No login selected." ); return; }
    CredAdmin.Source s = src.get( i );
    if( s.reject != null ) { log.accept( "★ cannot use this one: " + s.reject ); return; }
    // ★ 共有ログインは**押す前に**止める。押したあとで警告しても、そのときにはもう
    //   片方のセッションを落とす取り込みが済んでいる (#954 / #970)。
    if( s.sharedLogin && !confirmShared( s ) ) { log.accept( "Cancelled." ); return; }
    run( CredAdmin.importAny( new File( s.path ) ), s.path );
  }

  /** 一覧に出ない場所のファイルを選ぶ (Windows のホーム側から)。 */
  private void importFromFile( SetCred.Provider p ) {
    chooseAndImport( p, new File( System.getProperty( "user.home", "." ) ),
                     "Choose a login file for " + p.label );
  }

  /** WSL2 のホームから選ぶ (利用者の要望・2026-08-29 実機)。
   *
   *  ★ **これが無いと WSL2 のログインにファイル選択から辿り着けない**。JFileChooser は
   *    `C:\Users\...` から始まり、`\\wsl.localhost\...` へ行く手段が画面に無い。UNC も
   *    dotfile も Windows からは普通に見える (実測: Test-Path 通過・隠し属性なし) ので、
   *    足りなかったのは**入口**だけだった。
   *  ★ distro 名は `SetCred.wslDistros()` から取る (探索と同じ 1 箇所。ここで
   *    `\\wsl.localhost\Ubuntu` のような決め打ちを書くと、実機の distro 名と合わない)。 */
  private void importFromWsl( SetCred.Provider p ) {
    List<String> distros = wslDistros();
    if( distros.isEmpty() ) { log.accept( "No WSL2 distribution found." ); return; }
    String distro = distros.get( 0 );
    if( distros.size() > 1 ) {
      Object pick = JOptionPane.showInputDialog( this, "Which WSL2 distribution?",
          "Choose a file from WSL2", JOptionPane.PLAIN_MESSAGE, null,
          distros.toArray( new String[ 0 ] ), distros.get( 0 ) );
      if( pick == null ) return;
      distro = String.valueOf( pick );
    }
    File home = new File( "\\\\wsl.localhost\\" + distro + "\\home" );
    if( !home.isDirectory() ) {
      // ★ distro が停止していると 9P の共有が生えていないことがある。理由を出して止める
      //   (黙って Windows のホームから開くと、選んだつもりの場所と違う所を見てしまう)。
      log.accept( "★ " + home.getPath() + " is not reachable."
                + " Start the distribution once (e.g. run `wsl -d " + distro + " true`) and retry." );
      return;
    }
    chooseAndImport( p, home, "Choose a login file for " + p.label + "  (WSL2 " + distro + ")" );
  }

  /** ファイルを選ばせて取り込む。★ 判定は**選んでいる provider のもの**を通す (名前ではなく
   *  中身で見る #964)。codex を選んでいるのに Claude のログインを黙って取り込む、を防ぐ。 */
  private void chooseAndImport( SetCred.Provider p, File startAt, String title ) {
    JFileChooser fc = new JFileChooser( startAt );
    // ★ dotfile を隠さない。`.claude-emulin` / `.codex` が見えないと辿り着けない。
    fc.setFileHidingEnabled( false );
    fc.setDialogTitle( title );
    if( fc.showOpenDialog( this ) != JFileChooser.APPROVE_OPTION ) return;
    File f = fc.getSelectedFile();
    long now = System.currentTimeMillis();
    CredAdmin.Source s = p.fromCodexAuthJson ? CredAdmin.inspectCodex( "chosen", f, now )
                                             : CredAdmin.inspect( "chosen", f, now );
    if( s.reject != null ) {
      log.accept( "★ " + f.getPath() + ": " + s.reject );
      log.accept( "  (" + p.label + " is selected; pick the file that provider writes)" );
      return;
    }
    if( s.sharedLogin && !confirmShared( s ) ) { log.accept( "Cancelled." ); return; }
    run( CredAdmin.importAny( f ), f.getPath() );
  }

  /** ★ `wsl.exe -l -q` は毎回 process を起こすので 1 回だけ引いて覚える
   *  (provider を選び直すたびに走らせない)。 */
  private List<String> wslDistros() {
    if( distros == null ) distros = SetCred.wslDistros();
    return distros;
  }

  /** ★ 普段使いのログインを取り込むと、refresh の回転で**もう片方が落ちる** (#954 / #970)。 */
  private boolean confirmShared( CredAdmin.Source s ) {
    int yn = JOptionPane.showConfirmDialog( this,
        "This looks like your everyday Claude login (" + s.path + ").\n\n"
      + "OAuth refresh tokens rotate: if the guest shares one login with another\n"
      + "Claude Code session, whichever refreshes first keeps working and the other\n"
      + "is logged out. A dedicated config dir avoids this:\n\n"
      + "    CLAUDE_CONFIG_DIR=~/.claude-emulin  claude auth login\n\n"
      + "Import it anyway?",
        "Shared login", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE );
    return yn == JOptionPane.YES_OPTION;
  }

  /** 貼り付けで登録する。★ 入力欄は JPasswordField。画面にもログにも 1 文字も出さない (#401)。
   *  ★ provider は**左で選んだもの**に決まっている (以前はここに combo があり、選択と
   *    操作がずれていた)。 */
  private void paste( SetCred.Provider p ) {
    JTextArea howto = new JTextArea( 8, 76 );
    howto.setEditable( false );
    howto.setFont( LauncherApp.mono( 11f ) );
    StringBuilder b = new StringBuilder();
    for( String l : p.howto ) b.append( l ).append( '\n' );
    b.append( "\n-> stored as " ).append( p.env )
     .append( "   (sent only to " ).append( CredentialStore.hostFor( p.env ) ).append( ")" );
    howto.setText( b.toString() );
    howto.setCaretPosition( 0 );

    JPasswordField field = new JPasswordField( 48 );
    JCheckBox verify = new JCheckBox( "Verify before saving (sends one request)", true );

    JPanel panel = new JPanel( new BorderLayout( 0, 8 ) );
    panel.add( new JScrollPane( howto ), BorderLayout.CENTER );
    JPanel bottom = new JPanel( new BorderLayout( 0, 6 ) );
    JPanel row = new JPanel( new BorderLayout( 8, 0 ) );
    row.add( new JLabel( "Key:" ), BorderLayout.WEST );
    row.add( field, BorderLayout.CENTER );
    bottom.add( row, BorderLayout.NORTH );
    bottom.add( verify, BorderLayout.CENTER );
    // ★ 「値は出さない」ことを画面でも約束しておく。利用者が貼るのを躊躇う場所なので。
    bottom.add( new JLabel( "The key is stored on this host only and is never shown again"
                          + " - not in this window, not in the log." ), BorderLayout.SOUTH );
    panel.add( bottom, BorderLayout.SOUTH );

    Object[] options = { "Save", "Cancel" };
    int r = JOptionPane.showOptionDialog( this, panel, "Paste a key for " + p.label,
        JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0] );
    char[] chars = field.getPassword();
    String token = new String( chars ).trim();
    java.util.Arrays.fill( chars, '\0' );            // ★ Swing の内部バッファを残さない
    if( r != 0 ) return;
    if( token.isEmpty() ) { log.accept( "Nothing pasted." ); return; }

    CredAdmin.Check c = CredAdmin.checkPasted( p, token, verify.isSelected() );
    if( !c.message.isEmpty() ) log.accept( "  " + p.env + ": " + c.message );
    if( c.needsConfirm() ) {
      int yn = JOptionPane.showConfirmDialog( this,
          ( c.rejected ? "The server rejected this key.\n\n" : "" )
        + c.message + "\n\nSave it anyway?",
          "Save " + p.env + "?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE );
      if( yn != JOptionPane.YES_OPTION ) { log.accept( "Cancelled." ); return; }
    }
    run( CredAdmin.savePasted( p, token ), p.env );
  }

  /** 登録済みのものを実際に 1 本投げて確かめる。★ 値はここには来ない (CredAdmin の中だけ)。 */
  private void verify( SetCred.Provider p ) {
    setCursor( java.awt.Cursor.getPredefinedCursor( java.awt.Cursor.WAIT_CURSOR ) );
    try {
      CredAdmin.Check c = CredAdmin.checkRegistered( p );
      log.accept( p.env + ": " + c.message );
      JOptionPane.showMessageDialog( this, c.message,
          c.rejected ? "Rejected by the server" : "Checked " + p.label,
          c.rejected ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE );
    } finally {
      setCursor( java.awt.Cursor.getDefaultCursor() );
    }
  }

  /** ★ **provider 単位でしか消せない**。OAuth は access と refresh の組で意味を持ち、
   *  片方だけ消すと guest には解決できない placeholder が残り、画面は「登録済み」の
   *  ままで 401 になる (#955 と同じ形)。理由は CredAdmin.removeProvider を参照。 */
  private void remove( SetCred.Provider p, String prefix ) {
    int yn = JOptionPane.showConfirmDialog( this,
        "Remove every credential of " + p.label + " from this host?\n\n"
      + "(OAuth tokens come in pairs - removing only one would leave the guest with a\n"
      + " placeholder that cannot be resolved, which looks like a working setup but 401s.)",
        "Remove " + prefix, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE );
    if( yn != JOptionPane.YES_OPTION ) return;
    run( CredAdmin.removeProvider( prefix ), prefix );
  }

  /** 結果をログ欄へ流し、両方の画面を作り直す。★ notes に値は入らない (CredAdmin の約束)。 */
  private void run( CredAdmin.Import imp, String what ) {
    if( !imp.ok ) { log.accept( "★ " + what + ": " + imp.error ); return; }
    log.accept( "done: " + what );
    for( String n : imp.notes ) log.accept( "  " + n );
    onChanged.run();
    reload( -1 );
  }

  // ------------------------------------------------------------------
  //  詳細ペインの部品
  // ------------------------------------------------------------------
  private void title( String text ) {
    JLabel l = new JLabel( text );
    l.setForeground( LauncherApp.FG );
    l.setFont( l.getFont().deriveFont( Font.BOLD, 15f ) );
    l.setAlignmentX( Component.LEFT_ALIGNMENT );
    detail.add( l );
    detail.add( Box.createVerticalStrut( 10 ) );
  }

  private void sub( String text ) {
    JLabel l = new JLabel( text );
    l.setForeground( LauncherApp.ACC );
    l.setFont( l.getFont().deriveFont( Font.BOLD, 12f ) );
    l.setAlignmentX( Component.LEFT_ALIGNMENT );
    detail.add( l );
    detail.add( Box.createVerticalStrut( 4 ) );
  }

  private void kv( String key, String value, java.awt.Color c ) {
    line( LauncherApp.pad( key, 10 ) + value, c );
  }

  private void line( String text, java.awt.Color c ) {
    JLabel l = new JLabel( text );
    l.setForeground( c );
    l.setFont( LauncherApp.mono( 11f ) );
    l.setAlignmentX( Component.LEFT_ALIGNMENT );
    detail.add( l );
  }

  private void gap() { detail.add( Box.createVerticalStrut( 12 ) ); }

  private JButton btn( String text, boolean primary, java.awt.event.ActionListener a ) {
    JButton b = new JButton( text );
    LauncherApp.style( b, primary );      // ★ 見た目は LauncherApp と 1 系統
    b.addActionListener( a );
    return b;
  }
}
