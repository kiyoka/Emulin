package emulin;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

// --------------------------------------------------------------------
//  StyledText — 選択してコピーできるテキスト面 (issue #988)
//
//  ★ もとは 1 行 1 個の JLabel を並べていたので **1 文字も選択できなかった**。
//    特に credential の "How to get it" は取得手順と URL が書いてあるのに、
//    コピーできないので手で打ち直すしかなく、実際に打ち間違えて 404 を踏んだ
//    (2026-09-01 利用者の報告)。
//
//  ★ **1 行 1 コンポーネント (JTextArea) にはしない**。それだと行をまたいで
//    選択できず、「手順を丸ごとコピーする」という肝心の用途を満たせない。
//    面ごと 1 つの JTextPane にして、色と太さは文字属性で付ける。
//
//  ★ 値は元々出していない (#401 / #968。入力欄は JPasswordField)。選択可能に
//    しても新たに漏れるものは無いが、「値を出さない」規律は崩さないこと。
// --------------------------------------------------------------------
final class StyledText extends JTextPane {

  StyledText( Color bg ) {
    setEditable( false );
    setBackground( bg );
    setBorder( null );
    // ★ 暗い配色なので選択色を明示する。既定のままだと選択部分が読めなくなる。
    setSelectionColor( LauncherApp.BTN );
    setSelectedTextColor( LauncherApp.BTN_FG );
  }

  /** ★ 折り返さない (JLabel だったときと同じ見え方を保つ)。JTextPane は既定で
   *  viewport 幅に合わせて折り返すので、内容の方が広いときだけ false を返して
   *  横スクロールバーに出させる。 */
  @Override public boolean getScrollableTracksViewportWidth() {
    java.awt.Container parent = getParent();
    if( parent == null ) return true;
    return getUI().getPreferredSize( this ).width <= parent.getWidth();
  }

  /** ★ 上の getScrollableTracksViewportWidth が false を返すと、既定の実装は
   *  preferred size を viewport に合わせて縮めない。最小幅も preferred に揃える。 */
  @Override public Dimension getPreferredSize() {
    return getUI().getPreferredSize( this );
  }

  /** ★ **高さを内容ちょうどに固定する**。JTextPane の既定の最大高さは事実上無限で、
   *  BoxLayout(Y_AXIS) に置くと余った縦幅ぶん引き伸ばされ、塊と塊の間に大きな空白が
   *  空く (実測: ボタンの上下に 100px 近い隙間)。横は伸びてよいので幅だけ開けておく。 */
  @Override public Dimension getMaximumSize() {
    return new Dimension( Integer.MAX_VALUE, getPreferredSize().height );
  }

  void clear() { setText( "" ); }

  boolean isEmpty() { return getDocument().getLength() == 0; }

  /** 選択中か。★ 呼び出し側はこれを見て**再描画を止める**。5 秒ごとの refresh が
   *  選択を消すと、コピーしようとしている最中に消える = 一番困る形になる。 */
  boolean hasSelection() { return getSelectionStart() != getSelectionEnd(); }

  /** 1 行追加する (末尾に改行を付ける)。 */
  void append( String text, Color fg, boolean bold, float size, boolean mono ) {
    StyledDocument d = getStyledDocument();
    SimpleAttributeSet a = new SimpleAttributeSet();
    StyleConstants.setForeground( a, fg );
    StyleConstants.setBold( a, bold );
    StyleConstants.setFontSize( a, Math.round( size ) );
    StyleConstants.setFontFamily( a, mono ? Font.MONOSPACED : getFont().getFamily() );
    try {
      d.insertString( d.getLength(), text + "\n", a );
    } catch( BadLocationException e ) {
      // 末尾への挿入なので起こらない。起きても表示が欠けるだけなので落とさない。
    }
  }
}
