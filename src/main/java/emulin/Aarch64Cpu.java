// ----------------------------------------
//  Software AArch64 CPU (issue #951 Phase 1)
// ----------------------------------------
package emulin;

public final class Aarch64Cpu implements GuestCpu {
  private final Sysinfo sysinfo;
  private final Process process;
  private Aarch64State state;
  private final Aarch64Decoder decoder = new Aarch64Decoder();
  private final Aarch64DecodedInsn decoded = new Aarch64DecodedInsn();
  private final Aarch64Executor executor = new Aarch64Executor();
  private Memory memory;
  private SyscallAarch64 syscall;

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
      int raw = memory.load32( state.pc );
      decoder.decode( raw, decoded );
      try {
        state.pc = executor.execute( state, decoded, syscall, memory );
      } catch( Memory.SegfaultException fault ) {
        System.err.println( "AARCH64_SEGV pc=" + hex( state.pc )
            + " insn=0x" + String.format( "%08x", raw ) + " " + registerString() );
        throw fault;
      }
      executed++;
      process.evals = executed;
    }
    return executed;
  }

  @Override public void setSignalHandler( long pc, long handler ) {
    throw new UnsupportedOperationException(
        "AArch64 signal frames start after issue #951 Phase 1" );
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
}
