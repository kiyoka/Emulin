# issue #113 — store watchpoint で破壊書き込みを特定する runbook

emacs `M-x package-list-packages` の segfault (#113) は「lispsym (XSYMBOL) 配列の
symbol 構造体 field が `0x40000000_xxxxxxxx` 高タグ値に破壊され、deref で落ちる」。
本 runbook は **破壊している書き込み命令の真の RIP を一発で特定する**ための手順。

## 計装 (Memory.java, PR #167)

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
DBG_WSTORE[s64/addrval] addr=0x... size=8 val=0x... rip=0x... eval=... | region=seg[N]+0x... | rip_region=...
```

## アドレス基準値 (emacs-nox.bin, PIE)

- load base = `0x555555554000` (emulin の PIE 固定 base、毎回同じ)
- lispsym file vaddr = `0x78df40` → **runtime lispsym base = `0x555555CE1F40`**
- 注: 次回 crash dump の `r14=` 値で lispsym runtime base を再確認できる。
  もし `0x555555CE1F40` と違えば、その r14 値を `WATCH_STORE_ADDR` に使う。

## 事前準備 (必須): bundle の jar を PR #167 入りに更新

watchpoint は **jar 側のコード**。bundle の jar が古いと何も出ない
(出荷中の `work/emulin-demo-0.4.0-windows/lib/emulin-0.4.0-all.jar` は PR #167 前で
watchpoint 無し)。WSL/Linux 側で:

```sh
cd <repo>
mvn -q clean package -DskipTests          # fat jar (jline 同梱) を build
cp target/emulin-0.4.0-all.jar work/emulin-demo-0.4.0-windows/lib/
```

確認: 差し替えた jar が ~960KB で jline class を内包 (`unzip -l ... | grep -ci jline`
が数百)、かつ起動して `DBG_WSTORE` が出ること。**`-o` (offline) build でも jline は
同梱されるが、不安なら online の `clean package` を使う**。rootfs は静的なので jar 差し替え
だけで最新化できる (再構築不要)。

## 手順 (Windows 実環境 = 毎回再現)

`emulin.bat` は引数に **`/bin/busybox` を前置する** (launcher 仕様) ので emacs を直接
渡せない。**対話 shell を起動 → その中で emacs を実行**する。env は親 cmd で `set`
すれば JVM (= watchpoint) に継承される。stderr をログに落とすため WT 自動再起動を
無効化 (`EMULIN_NO_WT=1`)。

```bat
cd work\emulin-demo-0.4.0-windows

rem 再現に必須の env (cyg-magic symlink) + 真の RIP + WT 自動再起動抑止
set EMULIN_FORCE_CYGWIN_SYMLINK=1
set EMULIN_TRACK_INSN_RIP=1
set EMULIN_NO_WT=1

rem --- パスA: AND で lispsym 配列に絞る (推奨・ほぼノイズ無し) ---
set EMULIN_WATCH_STORE_ADDR=555555CE1F40
set EMULIN_WATCH_STORE_LEN=80000
set EMULIN_WATCH_STORE_VAL=4000000000000000
set EMULIN_WATCH_STORE_VAL_MASK=ffffffff00000000

rem stderr だけログへ (stdout/stdin は対話のままコンソールに残る)
emulin.bat 2> %USERPROFILE%\wstore.log
```
起動した busybox ash のプロンプトで emacs を起動:
```
emacs-nox
```
(= sandbox 内の `/usr/bin/emacs-nox` を exec。melpa 設定済みの init.el =
`rootfs/root/.emacs.d/init.el` が必要)。emacs で `M-x package-list-packages` → crash。

`wstore.log` の **最後の `DBG_WSTORE` 行 (crash 直前の eval)** が破壊書き込み。
`rip=` と `rip_region=` がその命令の所在 (library + offset)。

**パスB: 破壊値を「どこに書いても」捕捉** (パスA が空振り = 破壊が lispsym 配列の外で
起きている場合)。ADDR/LEN を外し値だけで照合:
```bat
set EMULIN_WATCH_STORE_ADDR=
set EMULIN_WATCH_STORE_LEN=
set EMULIN_WATCH_STORE_VAL=4000000000000000
set EMULIN_WATCH_STORE_VAL_MASK=ffffffff00000000
```
emacs は bit62 を正規 tag にも使うため値だけだと flood しうる。その場合は
`set EMULIN_WATCH_EVAL_LO=7000000000` で crash window (~7.7B eval) 付近に絞る。

得られた RIP を issue #113 に貼れば原因命令を確定できる。

## ハントを確実にするコツ (diff レビュー助言)

- **`EMULIN_FORCE_SINGLE_THREAD=1` を併用**。#113 は single-thread でも再現するので、
  RIP/命令の帰属が曖昧にならず、watchpoint dump cap の race も無くなる
  (crash しなくなったら外す)。
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
