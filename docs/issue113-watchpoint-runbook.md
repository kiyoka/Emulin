# issue #113 — store watchpoint で破壊書き込みを特定する runbook

emacs `M-x package-list-packages` の segfault (#113) は「lispsym (XSYMBOL) 配列の
symbol 構造体 field が `0x40000000_xxxxxxxx` 高タグ値に破壊され、deref で落ちる」。
本 runbook は **破壊している書き込み命令の真の RIP を一発で特定する**ための手順。

## 計装 (Memory.java, 2026-05-30 強化)

全 store 経路 (`store8/16/32/64` + `bulkStoreToMem`) に統一 watchpoint を実装。
debug 専用・env で off の間は hot path 無影響。

| env | 意味 |
|-----|------|
| `EMULIN_WATCH_STORE_VAL=<hex>`      | 監視する 64bit 値 |
| `EMULIN_WATCH_STORE_VAL_MASK=<hex>` | 値マスク。`(store値 & MASK)==(VAL & MASK)` で照合。**低位が run 毎に変わる破壊値を高位だけで捕捉**。未指定=完全一致 |
| `EMULIN_WATCH_STORE_ADDR=<hex>`     | 監視するアドレス (範囲の起点) |
| `EMULIN_WATCH_STORE_LEN=<hex/dec>`  | 監視範囲長。未指定=8byte 窓 |
| `EMULIN_WATCH_EVAL_LO` / `_HI`      | この eval 範囲の store だけ記録 (dump cap 枯渇回避) |
| `EMULIN_TRACK_INSN_RIP=1`           | 真の命令 RIP を出す (**必須**) |

**ADDR と VAL を両方指定すると AND** (= 監視範囲内 **かつ** マスク値一致)。片方のみは単独。
dump 上限 2000。出力例:
```
DBG_WSTORE[s64/addrval] addr=0x... size=8 val=0x40000000... rip=0x... eval=... | region=seg[N]+0x... | rip_region=...
```

## アドレス基準値 (emacs-nox.bin, PIE)

- load base = `0x555555554000` (emulin の PIE 固定 base、毎回同じ)
- lispsym file vaddr = `0x78df40` → **runtime lispsym base = `0x555555CE1F40`**
- 破壊値パターン: 高位 32bit = `0x40000000` (bit 62 = `0x4000000000000000`)

> 注: 次回 crash dump の `r14=` 値で lispsym runtime base を再確認できる。
> もし `0x555555CE1F40` と違えば、その r14 値を `WATCH_STORE_ADDR` に使う。

## 手順 (Windows 実環境 = 毎回再現)

```bat
cd work\emulin-demo-0.4.0-windows

rem --- パスA: 破壊値を「どこに書いても」捕捉 (アドレス不要・最初に試す) ---
set EMULIN_TRACK_INSN_RIP=1
set EMULIN_WATCH_STORE_VAL=4000000000000000
set EMULIN_WATCH_STORE_VAL_MASK=ffffffff00000000
emulin.bat -CJ /usr/bin/emacs-nox 2> wstore.log
```
emacs 起動後 `M-x package-list-packages` を実行 → crash。
`wstore.log` の **最後の `DBG_WSTORE` 行 (crash 直前の eval)** が破壊書き込み。
`rip=` と `rip_region=` がその命令の所在 (library + offset)。

ノイズが多い / cap が早く埋まる場合は **パスB (AND で lispsym に絞る)**:
```bat
set EMULIN_TRACK_INSN_RIP=1
set EMULIN_WATCH_STORE_ADDR=555555CE1F40
set EMULIN_WATCH_STORE_LEN=80000
set EMULIN_WATCH_STORE_VAL=4000000000000000
set EMULIN_WATCH_STORE_VAL_MASK=ffffffff00000000
emulin.bat -CJ /usr/bin/emacs-nox 2> wstore.log
```
= 「lispsym 配列に高タグ破壊値が書かれる瞬間」だけを記録 (ほぼノイズ無し)。

crash window を絞りたい場合は `set EMULIN_WATCH_EVAL_LO=7000000000` 等を併用。

## 読み方

- `rip_region=seg[1]+0x...` (emacs text) → emacs 自身が壊れた値を書いている
  = emulin のある命令の emulation が高位 bit を誤生成している疑い (shift/SIMD/mul 等)。
  その file offset を `objdump -d emacs-nox.bin` で逆引きすれば原因命令が判る。
- `rip_region=mmap[...] <libc.so 等>` → libc 経由 (memcpy/memmove)。
  `how=bulk/...` なら bulkStoreToMem (SSE/memcpy) 経路。
- `val=` の高位が `0x40000000` で低位がゴミ → 破壊確定。`addr=` が deref で落ちた
  symbol field (lispsym base + symbol_index*sizeof(Lisp_Symbol) + 0x10)。

得られた RIP を issue #113 に貼れば原因命令を確定できる。

## ハントを確実にするコツ (diff レビュー助言)

- **`EMULIN_FORCE_SINGLE_THREAD=1` を併用**。#113 は single-thread でも再現するので、
  RIP/命令の帰属が曖昧にならず、watchpoint dump cap の race も無くなる。
- mask は本文の `ffffffff00000000` (高位 dword が厳密に 0x40000000) が最も specific。
  高位 dword が run で揺れる場合は **`c000000000000000`** (bit62=1 かつ bit63=0) に緩めると
  負の fixnum/pointer を除外しつつ bit62 系破壊値を広く拾える。

## 静的監査の事前所見 (2026-05-30, 6-agent workflow)

「emulin のどの命令 emulation が高位 bit 62 (`0x40000000_`) を誤生成し store しうるか」を
shift / per-element SIMD shift / mul・除算 / sign-zero拡張 / 論理マスク / JIT の各レンズで監査:

- **scalar shift・SIMD shift (PSRLQ/PSLLQ/PSRAD 等)・mul/div (64bit signed→unsigned hi 補正含む)・
  MOVSXD/CQO/CDQE・LEA・MOVZX/MOVSX・writeRM32 zero-ext は読了し CLEAN** (count clamp、
  `1L<<64` footgun 回避、32bit dest の上位ゼロ拡張すべて正しい)。→ 確認対象に格下げ。
- **唯一の未監査・emacs hot・bit62 生成可能クラス = GP↔XMM / lane move: `MOVD/MOVQ (0F 6E/7E,
  F3 0F 7E)`, `PINSRD/PEXTRD`, `PUNPCKLDQ/PUNPCKHDQ (0F 62/6A)`** (Cpu64.java)。
  検証ポイント: MOVD r32←xmm が GPR の bits 63:32 を**ゼロ拡張**するか / MOVD xmm←r32 が xmm の
  bits 127:32 を**ゼロ**にするか / PUNPCK*DQ の lane 選択が SDM 通りか。MOVD の上位ゼロ拡張漏れは
  本コードベースの再発バグ系統 (CLAUDE.md「LEA r32 zero-ext」「MOVZX r16」)。
  → watchpoint の `rip_region` が emacs text を指し、その RIP の命令が `0F 6E/7E/62/6A` なら本命。
- (副産物) Grp12/13/14 imm shift に **dead duplicate handler** (Cpu64.java 4406 vs 4478, 4433 vs
  4498。2 つ目は到達不能)。#113 原因ではないが drift hazard なので別途 cleanup 候補。

**結論: empirical watchpoint が決定打。静的探索はこれ以上広げない** (synthesis 判断)。

