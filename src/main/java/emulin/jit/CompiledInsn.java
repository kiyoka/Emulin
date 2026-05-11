package emulin.jit;

import emulin.Cpu64;

/**
 * 翻訳された x86-64 命令の実行 interface。
 * 1 命令 = 1 generated class が実装し、execute() で副作用を Cpu64 に適用、
 * 次の RIP を返す。
 *
 * Phase 34-A3 の出発点。HotSpot の type profile が同一 RIP では同一 class
 * になることを検知して INVOKEINTERFACE を devirtualize → inline できる
 * 設計。
 */
public interface CompiledInsn {
  long execute( Cpu64 cpu );
}
