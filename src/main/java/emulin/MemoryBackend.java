// ----------------------------------------
//  Memory backend interface (issue #232 / Step 2 of #221 refactor)
//
//  Copyright (C) 1998-2026  Kiyoka Nishiyama
// ----------------------------------------
//
// 目的: emulin の Memory 抽象を「software emulator が抱える per-byte cache /
//   chunks / alloclist / globalStoreEpoch を持つ実装」と、将来追加予定の
//   「HW 仮想化 (WHP/KVM/HVF) で guest 物理にマップされた byte[] を持つ実装」の
//   **どちらも嵌まる契約**として明示する。
//
// 設計原則 (#221 §1 / docs/backend-abstraction.md §0 参照):
//   1. software emulator (= 現 Memory class) は恒久維持。本 step では interface
//      宣言を足し implements を 1 行付けるだけで、Memory class の body は触らない。
//   2. behavior change ゼロ。Memory が唯一の impl のうちは JIT が monomorphic に
//      解決して inline するので、interface 経由でも hot path 性能は劣化しない
//      (実測で確認: claude --version / sha256sum 5MB の interleaved A/B ±5% 以内)。
//   3. native backend (= 将来追加の NativeMemoryBackend、#221 Phase 0+) は本
//      interface を実装するだけで Cpu64 / Syscall 層に plug-in できる。
//
// 本 step (#232) では interface 宣言 + Memory implements MemoryBackend + 一部の
// holder 型 widening (AbstractCpu.mem / connect_devices) までを行う。
// Memory class の中身を新規 SoftwareMemoryBackend クラスに「移動」する案も
// あったが、Memory.java は 1589 行で per-byte hot path を抱えるため、移動による
// 性能 regression リスクが極端に高い (load8/store8 が claude 起動時間の ~30%、
// #190 / [[claude-perf-exploration]])。「Memory class そのものが SoftwareMemory
// Backend である」という解釈で、抽象化の goal (= 将来 NativeMemoryBackend を
// plug-in 可能) は同等に達成しつつリスク最小化を選択した。
//
// 範囲外 (= 本 step では触らない):
//   - Process.mem / Syscall.mem / Thread64.mem の型 widening (software-only
//     state である brk / ELF section / fork duplicate を直接触るため、本 step で
//     interface に出さない選択。Step 3 = #233 で再検討)
//   - 実 NativeMemoryBackend 実装 (= #221 Phase 0+)
//   - Memory class 内部の structure 変更 (= behavior change が出る可能性あり、
//     別 issue)
//
// 注意: 本 interface のメソッドは Memory class の現 signature (load*/store*/
//   bulk*/...) をそのまま列挙する。read8/write8 等の Linux 慣用名に rename
//   すると全 callers (load8 ~114 callsite、store8 ~44 callsite) を touch する
//   必要があり behavior change と区別がつかない PR になる。rename は別 issue で。
package emulin;

import java.lang.*;

public interface MemoryBackend {

  // === Linear memory: per-byte / multi-byte read / write ===
  //
  //   load* / store* は CPU の hot path で 1 命令毎に呼ばれる (#190、claude 起動
  //   時間の ~30%)。software backend では Memory.java が per-byte cache + chunks
  //   で最適化。native backend では guest 物理にマップされた byte[] を直接 index。

  /** 1 byte 読み。返り値は signed byte。 */
  byte    load8 ( long address );
  /** 2 byte 読み (little-endian)。 */
  short   load16( long address );
  /** 4 byte 読み (little-endian)。 */
  int     load32( long address );
  /** 8 byte 読み (little-endian)。 */
  long    load64( long address );
  /** 1 byte 書き。返り値: 書き込み成功なら true。 */
  boolean store8 ( long address, int   data  );
  /** 2 byte 書き (little-endian)。 */
  void    store16( long address, short value );
  /** 4 byte 書き (little-endian)。 */
  void    store32( long address, int   value );
  /** 8 byte 書き (little-endian)。 */
  void    store64( long address, long  value );


  // === Bulk transfer (syscall buf、命令 fetch) ===
  //
  //   bulkLoadFromMem / bulkStoreToMem は syscall 引数 buf を host byte[] と
  //   往復するための API。fetch は命令フェッチで Cpu64.refillInsnBuf が呼ぶ。

  /** address から len byte を buf[0..len-1] に読み込む (Cpu64.bulkLoadFromMem 経由)。 */
  void    bulkLoad       ( long address, byte[] buf, int len );
  /** address から len byte を dst[dstOff..dstOff+len-1] に読み込む (syscall buf 用)。 */
  void    bulkLoadFromMem( long srcAddr, byte[] dst, int dstOff, int len );
  /** src[srcOff..srcOff+len-1] を dstAddr から len byte 書き込む (syscall buf 用)。 */
  void    bulkStoreToMem ( long dstAddr, byte[] src, int srcOff, int len );
  /** dstAddr から len byte を 0 で埋める (mmap MAP_ANON / Cpu64 vector clear 等)。 */
  void    bulkZero       ( long dstAddr, int len );
  /** address から buf.length byte を命令として読む。成功で true。 */
  boolean fetch          ( long address, byte[] buf );


  // === Virtual memory management (mmap / mremap / munmap 等) ===
  //
  //   syscall 層 (mmap/munmap/mremap/brk) と Process 起動 (stack/heap 配置) が
  //   呼ぶ。software backend は Java byte[] chunk を alloclist で管理、native
  //   backend では WHvMapGpaRange / KVM_SET_USER_MEMORY_REGION で guest 物理に
  //   map することになる (= 本 interface の契約は同じ、実装が違う)。

  /** anonymous な領域を size byte 確保し、guest 仮想 address を返す。 */
  long    alloc        ( long adrs, int size );
  /** mmap の中核。fd >= 0 で file backed、< 0 で anonymous。prot は次の overload。 */
  long    alloc_and_map( long adrs, int size, int fd, long offset );
  /** prot 指定 ver。PROT_READ / PROT_WRITE / PROT_EXEC を反映 (software は記録のみ)。 */
  long    alloc_and_map( long adrs, int size, int fd, long offset, int prot );
  /** flags 指定 ver。MAP_FIXED の有無で adrs の意味が変わる (FIXED=その仮想に必ず map、
   *  無し=hint。Linux は hint 範囲が塞がっていると別の場所を選ぶ)。default は flags を
   *  無視して従来挙動 (software backend は既存の byte-identical 挙動を維持)。 */
  default long alloc_and_map( long adrs, int size, int fd, long offset, int prot, long flags ) {
    return alloc_and_map( adrs, size, fd, offset, prot );
  }
  /** 大きな anonymous 領域を一気に確保 (huge page emulation、glibc malloc 大物用)。 */
  long    alloc_huge   ( long addr, long fullAlignedSize, int prot, boolean fixed );
  /** issue #527: file-backed の ≥2GiB mmap。alloc_and_map の int size では表現できない長さを
   *  64bit のまま受け、対応 backend (native の demand paging) だけが override する。
   *  hostPath は fd の host 実パス (呼出側 = amd64_mmap が Fileinfo から解決して渡す。backend が
   *  guest fd の寿命・seek 位置と独立に自前の read チャネルを開くため)。
   *  default は -12 (ENOMEM): 旧来の (int) 切り詰め (負長 → NegativeArraySizeException で
   *  guest thread 死亡) の代わりに明示的な失敗を guest に返す。 */
  default long alloc_huge_file( long addr, long fullAlignedSize, int fd, long offset, int prot,
                                boolean fixed, String hostPath ) { return -12L; }
  /** mremap: old_address の領域を size に伸縮。0 = 失敗。 */
  int     realloc      ( long old_address, int size );
  /** munmap。返り値は影響を受けた領域サイズ (debug)。 */
  //   issue #392 review #1: size は long。≥2GB 領域 (alloc_huge の V8 cage 等) の munmap で
  //   (int) 切り詰めにより size<=0 となり no-op 化するのを防ぐ。
  int     free         ( long address, long size );
  /** address が現 mapping のどこかに含まれているか (range check)。 */
  boolean in           ( long address );


  // === brk (data segment、Elf parent から expose) ===
  //
  //   brk(2) syscall が呼ぶ。software では Elf.brk + segment expand、native では
  //   別の方法 (mmap-style 拡張 等) になる予定。

  /** 現在の brk 値 (data segment 終端) を返す。 */
  long    get_curbrk();
  /** brk を _brk に伸縮。返り値は成功可否。 */
  boolean set_curbrk( long _brk );


  // === signal trampoline (sigreturn frame、issue #205) ===

  /** sigreturn 用 ARM 命令 page を確保し、その address を返す。一度確保後は cache。 */
  long    ensureSigtramp();


  // === /proc/self/maps & mmap path tracking ===
  //
  //   guest が /proc/self/maps を read する経路。mmap 時に path を覚え、ここから
  //   再現する。ddskk crash (#204) で必要になった。

  /** mmap した address に file path を関連付ける (/proc/self/maps 表示用)。 */
  void    set_map_path  ( long addr, String path );
  /** /proc/self/maps の合成文字列を返す。 */
  String  genProcSelfMaps();


  // === file-backed range tracking (issue #403) ===
  //
  //   madvise(MADV_DONTNEED) は anonymous を次アクセスで zero、file-backed を file
  //   内容に再フォールトさせる。emulin は file 再フォールト機構が無く対象を一律
  //   bulkZero するため、file-backed page (fd>=0 mmap / ELF PT_LOAD) を zero 化して
  //   しまう (claude/Bun の埋め込み JS ソース破壊 = #403)。これを防ぐため file-backed
  //   な VA 範囲を記録し、madvise が該当 page の zero 化を skip する。
  //   default は no-op (記録しない / 常に false) なので madvise は従来どおり全 zero 化。

  /** [addr, addr+len) を file-backed として記録する。 */
  default void    registerFileBacked  ( long addr, long len ) {}
  /** addr が file-backed 範囲に属するか (true なら madvise DONTNEED で zero 化しない)。 */
  default boolean isFileBacked        ( long addr ) { return false; }
  /** [addr, addr+len) を file-backed 記録から除去する (munmap / anon 再 map 時、#113 回帰防止)。 */
  default void    unregisterFileBacked( long addr, long len ) {}

  // issue #517: msync/mlock の Linux 準拠 errno 用。default は寛容側
  //   (常に mapped 扱い / flush no-op) = 従来挙動維持。software backend (Memory)
  //   が override して実判定/書き戻しする。

  /** [addr, addr+len) が全域 map 済みか (msync/mlock の ENOMEM 判定)。 */
  default boolean isRangeMapped( long addr, long len ) { return true; }
  /** 範囲と重なる file-backed MAP_SHARED mapping を backing file へ書き戻す (msync)。 */
  default void    msyncFlush   ( long addr, long len ) {}


  // === ELF symbol lookup (debug 表示用、Elf parent から expose) ===
  //
  //   Cpu (i386 disassembly trace) が address → 関数名を引くのに使う。
  //   native backend では Elf 情報を持たない可能性があり、その場合は null 可。

  /** address に対応する ELF symbol を返す (無ければ null)。 */
  String  get_symbol( long address );


  // === 文字列 helper ===
  //
  //   C string (NUL 終端) の往復。syscall 引数の path / env / argv で多用。

  /** str を UTF-8 + NUL 終端 で address から書き込み、**NUL の次アドレス**
   *  (address + バイト数 + 1) を返す (chained-write 契約、Memory.storeString と同じ)。 */
  long    storeString( long address, String str );
  /** address から NUL 終端まで読んで String を返す。 */
  String  loadString ( long address );


  // === lifecycle ===

  /** プロセス終了時に確保した buffer を解放する (GC ヒント / FD close)。 */
  void    release_buffers();


  // === debug ===

  /** address から len byte を hex で dump (debug 用)。 */
  void    dump( long address, int len );
}
