// ----------------------------------------
//  WhpGpaBacking chunk ロジック smoke (issue #304 WHP lazy commit)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// WhpGpaBacking の chunk index 計算 (WHP API 非依存の純関数) を検証する。
// allocPt/allocData/enableMmu/duplicate が ensure(poolOffset, len) で commit+map すべき chunk を
// 正しく算出するかが lazy commit の正しさの核。VirtualAlloc/WHvMapGpaRange は Windows でしか動かない
// が、chunk 算出ロジックは Linux で単体検証できる (この smoke)。
//
// 起動: java -cp target/classes emulin.WhpGpaBackingSmoke   (KVM/WHP 不要、純 Java)
package emulin;

public class WhpGpaBackingSmoke {

  private static int failures = 0;
  private static void eq( String name, long got, long want ) {
    if( got != want ) { System.err.println( "  FAIL " + name + ": got=" + got + " want=" + want ); failures++; }
    else System.out.println( "  ok " + name + " = " + got );
  }

  public static void main( String[] args ) {
    final long CH = 0x200000L;   // 2MB を明示 (CHUNK の env 依存を排除して決定的に検証)
    System.out.println( "[WhpGpaBackingSmoke] CHUNK(default/env) = 0x" + Long.toHexString( WhpGpaBacking.CHUNK )
        + " (テストは固定 CH=0x" + Long.toHexString( CH ) + " で算出を検証)" );

    // chunkOf: pool offset → chunk index
    eq( "chunkOf(0)",            WhpGpaBacking.chunkOf( 0,          CH ), 0 );
    eq( "chunkOf(PML4=0x1000)",  WhpGpaBacking.chunkOf( 0x1000,     CH ), 0 );
    eq( "chunkOf(2MB-1)",        WhpGpaBacking.chunkOf( CH - 1,     CH ), 0 );
    eq( "chunkOf(2MB)",          WhpGpaBacking.chunkOf( CH,         CH ), 1 );
    eq( "chunkOf(8MB=DATA_BASE)",WhpGpaBacking.chunkOf( 0x800000,   CH ), 4 );

    // lastChunkOf: [off, off+len) が跨ぐ最後の chunk (inclusive)
    eq( "last(PML4 1page)",      WhpGpaBacking.lastChunkOf( 0x1000, 0x1000,   CH ), 0 );   // [0x1000,0x2000)
    eq( "last(0,2MB)",           WhpGpaBacking.lastChunkOf( 0,      CH,       CH ), 0 );   // [0,2MB) は chunk0 のみ
    eq( "last(0,2MB+1)",         WhpGpaBacking.lastChunkOf( 0,      CH + 1,   CH ), 1 );   // 1byte 越えで chunk1 へ
    eq( "last(chunk0 末page)",   WhpGpaBacking.lastChunkOf( CH-0x1000, 0x1000,CH ), 0 );   // chunk0 の最終 page
    eq( "last(0,8MB)",           WhpGpaBacking.lastChunkOf( 0,      0x800000, CH ), 3 );   // [0,8MB)=chunks0-3 (duplicate prefix 相当)

    // chunkOffset
    eq( "chunkOffset(0)",        WhpGpaBacking.chunkOffset( 0, CH ), 0 );
    eq( "chunkOffset(4)",        WhpGpaBacking.chunkOffset( 4, CH ), 0x800000 );

    // chunkLen: 通常は CHUNK、末尾 chunk は pool 端で切り詰め
    final long pool = 5L * CH + 0x1000;   // 5 full chunk + 1 page (= 非 chunk 倍数の pool)
    eq( "chunkLen(0,full)",      WhpGpaBacking.chunkLen( 0, CH, pool ), CH );
    eq( "chunkLen(4,full)",      WhpGpaBacking.chunkLen( 4, CH, pool ), CH );
    eq( "chunkLen(5,partial)",   WhpGpaBacking.chunkLen( 5, CH, pool ), 0x1000 );   // 末尾 partial = 1 page

    // 現実シナリオ: 8GB pool で 2MB chunk = 4096 chunk、最終 offset の chunk index
    final long pool8g = 8L * 1024 * 1024 * 1024;
    eq( "chunkOf(8GB-1page, 8GB pool)", WhpGpaBacking.chunkOf( pool8g - 0x1000, CH ), 4095 );
    eq( "chunkLen(4095, 8GB pool)",     WhpGpaBacking.chunkLen( 4095, CH, pool8g ), CH );

    if( failures == 0 ) { System.out.println( "WhpGpaBacking smoke OK" ); System.exit( 0 ); }
    System.err.println( "WhpGpaBacking smoke FAIL (" + failures + " 件)" ); System.exit( 1 );
  }
}
