// ----------------------------------------
//  Software AArch64 CPU (issue #951 Phase 1)
// ----------------------------------------
package emulin;

import java.util.ArrayDeque;

public final class Aarch64Cpu implements GuestCpu {
  private final Sysinfo sysinfo;
  private final Process process;
  private Aarch64State state;
  private final Aarch64Decoder decoder = new Aarch64Decoder();
  private final Aarch64DecodedInsn decoded = new Aarch64DecodedInsn();
  private final Aarch64Executor executor = new Aarch64Executor();
  private Memory memory;
  private SyscallAarch64 syscall;
  private final ArrayDeque<SignalFrame> signalFrames = new ArrayDeque<>();
  private long signalTrampoline;
  private static final boolean TRACE_RING =
      System.getenv( "EMULIN_AARCH64_TRACE_RING" ) != null;
  private static final boolean TRACE_PROGRESS =
      System.getenv( "EMULIN_AARCH64_TRACE_PROGRESS" ) != null;
  private static final boolean TRACE_COVERAGE =
      System.getenv( "EMULIN_AARCH64_TRACE_COVERAGE" ) != null;
  private final long[] tracePc = TRACE_RING ? new long[ 32 ] : null;
  private final int[] traceRaw = TRACE_RING ? new int[ 32 ] : null;
  private final long[][] traceX = TRACE_RING ? new long[ 32 ][ 5 ] : null;
  private int tracePosition;
  private final java.util.EnumMap<Aarch64DecodedInsn.Operation,Long> coverage =
      TRACE_COVERAGE ? new java.util.EnumMap<>( Aarch64DecodedInsn.Operation.class ) : null;

  private static final class SignalFrame {
    final Aarch64State state;
    final long signalMask;

    SignalFrame( Aarch64State state, long signalMask ) {
      this.state = state;
      this.signalMask = signalMask;
    }
  }

  Aarch64Cpu( Sysinfo sysinfo, Process process ) {
    this.sysinfo = sysinfo;
    this.process = process;
    this.state = new Aarch64State();
  }

  @Override public GuestCpu duplicate( Process child ) {
    Aarch64Cpu result = new Aarch64Cpu( sysinfo, child );
    result.state = state.copy();
    return result;
  }

  @Override public void setPc( long pc ) { state.pc = pc; }
  @Override public long getPc() { return state.pc; }
  @Override public void setSp( long sp ) { state.sp = sp; }
  @Override public long getSp() { return state.sp; }
  @Override public void setReturnValue( long value ) { state.writeX( 0, value ); }
  @Override public void advancePastSyscall() { state.pc += 4; }
  @Override public void setFsBase( long base ) { state.tpidrEl0 = base; }
  @Override public long getFsBase() { return state.tpidrEl0; }

  @Override
  public long spawnVcpu( long flags, long childStack, long parentTid,
                         long childTid, long tls ) {
    final long CLONE_PARENT_SETTID  = 0x100000L;
    final long CLONE_CHILD_CLEARTID = 0x200000L;
    final long CLONE_CHILD_SETTID   = 0x1000000L;
    final long CLONE_SETTLS         = 0x80000L;

    Aarch64Cpu child = new Aarch64Cpu( sysinfo, process );
    child.state = state.copy();
    // execute() updates the parent's PC only after the syscall returns.  A
    // cloned child must resume after the four-byte SVC instruction.
    child.state.pc = state.pc + 4;
    child.state.writeX( 0, 0 );
    if( childStack != 0 ) child.state.sp = childStack;
    if( (flags & CLONE_SETTLS) != 0 ) child.state.tpidrEl0 = tls;
    child.connectDevices( memory, syscall );

    int tid = sysinfo.kernel.next_tid();
    long clearTid = (flags & CLONE_CHILD_CLEARTID) != 0 ? childTid : 0;
    long parentMask = process.get_signal_mask_bits();
    Aarch64Thread thread = new Aarch64Thread(
        process, child, tid, memory, clearTid, parentMask );

    if( (flags & CLONE_PARENT_SETTID) != 0 && parentTid != 0 ) {
      memory.store32( parentTid, tid );
    }
    if( (flags & CLONE_CHILD_SETTID) != 0 && childTid != 0 ) {
      memory.store32( childTid, tid );
    }
    thread.start();
    return tid;
  }

  @Override public void connectDevices( Memory memory, Syscall syscall ) {
    if( !(syscall instanceof SyscallAarch64 aarch64Syscall) ) {
      throw new IllegalArgumentException( "AArch64Cpu requires SyscallAarch64" );
    }
    this.memory = memory;
    this.syscall = aarch64Syscall;
    syscall.connect_mem( memory );
  }

  @Override public long eval() {
    long executed = 0;
    while( !process.is_exited() ) {
      if( restoreSignalFrame() ) continue;
      checkPendingSignal();
      if( process.is_exited() ) break;
      int raw = memory.load32( state.pc );
      decoder.decode( raw, decoded );
      if( TRACE_COVERAGE ) coverage.merge( decoded.operation, 1L, Long::sum );
      if( TRACE_RING ) recordTrace( raw );
      try {
        state.pc = executor.execute( state, decoded, syscall, memory );
      } catch( Memory.SegfaultException fault ) {
        System.err.println( "AARCH64_SEGV pc=" + hex( state.pc )
            + " insn=0x" + String.format( "%08x", raw ) + " " + registerString() );
        if( TRACE_RING ) dumpTrace();
        throw fault;
      }
      executed++;
      process.evals = executed;
      if( TRACE_PROGRESS && executed % 5_000_000L == 0 ) {
        int nextRaw = memory.load32( state.pc );
        Aarch64DecodedInsn next = decoder.decode( nextRaw, new Aarch64DecodedInsn() );
        System.err.println( "AARCH64_PROGRESS pid=" + process.pid
            + " evals=" + executed + " " + pcString()
            + " raw=0x" + String.format( "%08x", nextRaw )
            + " op=" + next.operation + " region=" + memory.regionLabel( state.pc )
            + " " + registerString() );
      }
    }
    if( TRACE_COVERAGE ) System.err.println( "AARCH64_COVERAGE " + coverage );
    return executed;
  }

  @Override public void setSignalHandler( long pc, long handler ) {
    state.pc = pc;
    enterSignalHandler( 0, handler );
  }

  @Override public boolean isInterruptDone() { return true; }

  @Override public String registerString() {
    return "x0=" + hex( state.readX( 0 ) ) + " x1=" + hex( state.readX( 1 ) )
        + " x2=" + hex( state.readX( 2 ) ) + " x8=" + hex( state.readX( 8 ) )
        + " sp=" + hex( state.sp );
  }

  @Override public String pcString() { return "pc=" + hex( state.pc ); }
  @Override public String flagString() { return "nzcv=0x" + Integer.toHexString( state.nzcv ); }

  @Override public String disassemble( long address ) {
    Aarch64DecodedInsn insn = decoder.decode( memory.load32( address ), new Aarch64DecodedInsn() );
    return switch( insn.operation ) {
      case MOVZ -> "movz " + (insn.dataSize == 64 ? "x" : "w") + insn.rd
          + ", #0x" + Long.toHexString( insn.immediate )
          + (insn.shiftAmount == 0 ? "" : ", lsl #" + insn.shiftAmount);
      case ADR -> "adr x" + insn.rd + ", " + (insn.immediate >= 0 ? "+" : "") + insn.immediate;
      case SVC -> "svc #" + insn.immediate;
      default -> insn.operation.name().toLowerCase( java.util.Locale.ROOT );
    };
  }

  private static String hex( long value ) { return "0x" + Long.toHexString( value ); }

  private void recordTrace( int raw ) {
    int slot = tracePosition++ & 31;
    tracePc[ slot ] = state.pc;
    traceRaw[ slot ] = raw;
    traceX[ slot ][ 0 ] = state.readX( 0 );
    traceX[ slot ][ 1 ] = state.readX( 1 );
    traceX[ slot ][ 2 ] = state.readX( 2 );
    traceX[ slot ][ 3 ] = state.readX( 7 );
    traceX[ slot ][ 4 ] = state.readX( 10 );
  }

  private void dumpTrace() {
    int count = Math.min( tracePosition, 32 );
    System.err.println( "AARCH64_TRACE_RING last=" + count );
    for( int i = count; i > 0; i-- ) {
      int slot = (tracePosition - i) & 31;
      System.err.printf(
          "  pc=%s raw=%08x x0=%s x1=%s x2=%s x7=%s x10=%s%n",
          hex( tracePc[ slot ] ), traceRaw[ slot ], hex( traceX[ slot ][ 0 ] ),
          hex( traceX[ slot ][ 1 ] ), hex( traceX[ slot ][ 2 ] ),
          hex( traceX[ slot ][ 3 ] ), hex( traceX[ slot ][ 4 ] ) );
    }
  }

  private void checkPendingSignal() {
    int signal = process.psig();
    if( signal < 0 ) return;
    long handler = process.get_func_adrs( signal );
    process.consume_one( signal );
    if( handler == Siginfo.SIG_IGN ) return;
    if( handler == Siginfo.SIG_DFL ) {
      if( process.get_action_type( signal ) == Signal.SIGACTION_EXIT ) {
        process.term_sig = signal;
        process.exit_code = 128 + signal;
        process.set_exit_flag();
      }
      return;
    }
    enterSignalHandler( signal, handler );
  }

  private void enterSignalHandler( int signal, long handler ) {
    long savedMask = process.get_signal_mask_bits();
    signalFrames.push( new SignalFrame( state.copy(), savedMask ) );

    long newMask = savedMask;
    if( signal > 0 ) {
      newMask |= process.get_sa_mask( signal );
      if( !process.has_sa_nodefer( signal ) ) newMask |= 1L << (signal - 1);
    }
    process.set_signal_mask_bits( newMask );

    signalTrampoline = memory.ensureAarch64Sigtramp();
    if( signalTrampoline <= 0 ) throw new OutOfMemoryError( "AArch64 sigtramp" );
    state.exclusiveAddress = -1;
    state.writeX( 0, signal );
    state.writeX( 30, signalTrampoline );
    state.pc = handler;
  }

  private boolean restoreSignalFrame() {
    if( signalTrampoline == 0 || state.pc != signalTrampoline ) return false;
    SignalFrame frame = signalFrames.pollFirst();
    if( frame == null ) return false;
    state = frame.state;
    process.set_signal_mask_bits( frame.signalMask );
    return true;
  }
}
