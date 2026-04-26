// ----------------------------------------
//  Abstract CPU base class
//
//  Cpu extends Decoder の全体から「フィールドと公開インターフェース」を
//  切り出したスーパークラス。
//
//  将来 Cpu32 / Cpu64 を分割する際に Process 側の変更を不要にするための
//  構造的準備 (Phase 2)。命令実装は Cpu に残す。
// ----------------------------------------
package emulin;

public abstract class AbstractCpu extends Decoder
{
  // 汎用レジスタ番号定数
  static int AX = 0;
  static int CX = 1;
  static int DX = 2;
  static int BX = 3;
  static int SP = 4;
  static int BP = 5;
  static int SI = 6;
  static int DI = 7;
  static int AL = 0;
  static int CL = 1;
  static int DL = 2;
  static int BL = 3;
  static int AH = 4;
  static int CH = 5;
  static int DH = 6;
  static int BH = 7;
  static int MAX_REG = 8;
  // ストリング命令の選択
  static int S_MOVS = 0;
  static int S_STOS = 1;
  static int S_LODS = 2;

  // レジスタ・フラグ・状態フィールド
  int reg[];           // 汎用レジスタ
  long ip;             // 命令ポインタ
  long next_ip;        // 次の命令のアドレス
  int of;
  int df;
  int sf;
  int zf;
  int af;
  int pf;
  int cf;
  int nest;
  long float_stack;
  Memory mem;
  Syscall syscall;
  boolean interrupt_done;

  // --- 公開抽象インターフェース ---

  public abstract void init();
  public abstract AbstractCpu duplicate( Process _process );

  public abstract void set_ip( long _ip );
  public abstract long get_ip();

  public abstract void set_sp( long sp );
  public abstract long get_sp();

  public abstract void set_ax( int value );

  public abstract long pushString( String str );
  public abstract void push32( long value );
  public abstract int  pop32();

  public abstract void set_signal_handler( long _ip, long goto_adrs );
  public abstract boolean is_interrupt_done();

  public abstract long   eval();
  public abstract void   fetch( long address, byte buf[] );
  public abstract void   connect_devices( Memory _mem, Syscall _syscall );

  public abstract String reg_str();
  public abstract String ip_str();
  public abstract String flag_str();
  public abstract String disasm_str( long address );
}
