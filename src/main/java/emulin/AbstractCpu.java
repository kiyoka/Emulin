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
  // Phase 34-A3 step 7: emulin.jit 生成 class が直接 mem.load64/store64 を
  // 呼ぶため public に。
  public Memory mem;
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

  // issue #233 (Step 3/3 of #221 refactor): signal 配信は既存の set_signal_handler
  //   が abstract API として既に存在し、Cpu64 (software amd64) / Cpu (software
  //   i386) が個別に実装している = AbstractCpu 層が既に「software/native 共存可能」
  //   な契約になっている。将来 NativeCpuBackend (#221 Phase 0+) はこの API を
  //   実装するだけで Kernel.kill → process.cpu.set_signal_handler 経路に plug-in
  //   できる。本 step では signature 変更や rename は行わない (red zone / SA_SIGINFO
  //   / SA_RESTART / per-thread mask / pending signal 判定 #225 等の細かい挙動を
  //   触らないため)。将来の native 化で必要になった瞬間に signature を見直す。
  public abstract void set_signal_handler( long _ip, long goto_adrs );
  public abstract boolean is_interrupt_done();

  public abstract long   eval();
  public abstract void   fetch( long address, byte buf[] );
  public abstract void   connect_devices( Memory _mem, Syscall _syscall );

  // issue #233 (Step 3/3 of #221 refactor): pthread / CLONE_THREAD の子 vCPU 生成
  //   を CpuBackend 層に集約する。default 実装は「未対応」を意味する例外。
  //   software backend では Cpu64 のみが override 実装 (i386 経路は sys_clone を
  //   実装していないため default のまま)。native backend (#221 Phase 0+) は同一
  //   partition 内に追加 vCPU を作成し guest 物理メモリを共有する実装になる。
  //
  // 旧実装は SyscallAmd64.amd64_clone_thread 内で `new Cpu64(...)` を直接呼んで
  //   いたが、本 step で Cpu64.spawnVCpu に移動し、amd64_clone_thread は
  //   parent_cpu 選択 (issue #113 の本丸 fix で確立した「呼び出し thread の Cpu64
  //   = main なら process.cpu、worker なら Thread64.cpu」) + spawnVCpu 呼び出しの
  //   wrapper になる。本 step は「移動だけ」で logic 変更ゼロを ship gate とする。
  //
  // @param flags        clone flags (CLONE_SETTLS / CLONE_PARENT_SETTID / CLONE_CHILD_CLEARTID)
  // @param child_stack  子の RSP (0 = 親と同じ)
  // @param ptid         CLONE_PARENT_SETTID で親が tid を書き込むアドレス
  // @param ctid         CLONE_CHILD_*TID 用 (CLEARTID なら子終了時にここを 0 化)
  // @param tls          CLONE_SETTLS のとき子の TLS base (Cpu64 では fs_base)
  // @return 子の tid。-EAGAIN (-11) など失敗は負値で返す。
  public long spawnVCpu( long flags, long child_stack, long ptid, long ctid, long tls ) {
    throw new UnsupportedOperationException(
        "spawnVCpu not supported by this CPU backend "
        + "(only x86-64 software backend implements pthread/CLONE_THREAD; "
        + "see Cpu64.spawnVCpu / SyscallAmd64.amd64_clone_thread, issue #221/#233)" );
  }

  public abstract String reg_str();
  public abstract String ip_str();
  public abstract String flag_str();
  public abstract String disasm_str( long address );
}
