// ----------------------------------------
//  Invariant — 内部不変条件の明文化 (issue #109)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
package emulin;

/** 内部不変条件を assert で明文化し、**壊れた瞬間**に捕まえるための補助。
 *
 *  <p>狙いは「症状が出た場所」ではなく「壊れた瞬間」で落とすこと。過去のバグの多くは
 *  遠く離れた場所で AV crash / silent 誤動作として現れ、追跡に時間がかかった:
 *  <ul>
 *    <li>公開 #723 — fork 子の pool 縮小で DATA_BASE が親と食い違い、孫世代の fork が
 *        未 commit 領域を読んで JVM が EXCEPTION_ACCESS_VIOLATION で死んだ</li>
 *    <li>公開 #820 — file-backed mmap の EOF 境界が fork で失われ、EOF 越えのゴミを
 *        読んで**先へ進んで**しまった (落ちないので気づけない)</li>
 *    <li>公開 #785 — bump ポインタが負に wrap し、以後すべての mmap が負値を返した</li>
 *  </ul>
 *  いずれも不変条件を書いていれば壊れた瞬間に落ちた類。
 *
 *  <h2>使い方</h2>
 *  <pre>
 *    assert cond : Invariant.mark( "dataNext <= usedTop", dataNext + " > " + usedTop );
 *  </pre>
 *  Java の {@code assert} なので <b>{@code -ea} を付けたときだけ</b>評価される
 *  (製品実行は無効 = 性能影響なし。SQLite と同じ方針)。
 *  <p>★ 条件が偽のときだけ message 式が評価されるので、文字列連結のコストは
 *  失敗時にしか掛からない。
 *
 *  <h2>★ なぜ marker を stderr に出すのか</h2>
 *  Emulin には「emulator を絶対に落とさない/固めない」ための安全網が何段もある
 *  (公開 #781 の syscall ディスパッチの最後の砦、#804 の起動経路、#709 の worker crash →
 *  thread group kill)。これらは {@code catch( Throwable )} なので
 *  <b>{@code AssertionError} も飲み込む</b>。飲まれると「不変条件が壊れた」事実が
 *  テスト側から見えなくなり、assert を入れた意味が無くなる。
 *  <p>そこで throw する前に <b>{@code EMULIN_ASSERT} 行を stderr に出す</b>。
 *  安全網に飲まれても痕跡が残り、テストランナーが grep で検出できる。
 */
final class Invariant {

  private Invariant() { }

  /** assert が有効か (-ea が付いているか)。ホットな箇所で条件式の評価自体を避けたいときに使う。 */
  static final boolean ON;
  static {
    boolean e = false;
    assert e = true;   // assert が有効なときだけ副作用が起きる古典的イディオム
    ON = e;
  }

  /** 破れた不変条件を stderr に記録し、assert の message として返す。
   *
   *  @param what 不変条件の名前 (何が成り立つべきか)
   *  @param detail 実際の値 (なぜ破れたか)
   *  @return assert の message にする文字列 */
  static String mark( String what, String detail ) {
    String msg = what + " | " + detail;
    // ★ throw より先に出す。安全網 (catch(Throwable)) に飲まれても痕跡が残る。
    System.err.println( "EMULIN_ASSERT " + msg );
    StackTraceElement[] st = Thread.currentThread().getStackTrace();
    // getStackTrace / mark 自身を飛ばして、破った側から数フレーム出す
    for( int i = 2, n = 0; i < st.length && n < 4; i++, n++ )
      System.err.println( "EMULIN_ASSERT   at " + st[i] );
    return msg;
  }

  /** detail を持たない版。 */
  static String mark( String what ) { return mark( what, "-" ); }
}
