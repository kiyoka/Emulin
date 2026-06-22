# issue #392: 戦略B (`EMULIN_NATIVE_PF`) を default ON にするかの分析

作成: 2026-06-22 / 対象: native(KVM/WHP) backend の demand paging を既定で有効にするかの意思決定。

## 0. TL;DR（結論）

- **推奨: フル default-on でも opt-in 据置でもなく、サイズ閾値ハイブリッドを既定にする**。
  すなわち #PF 基盤は常時構築し、anon mmap は **小さい予約は eager（従来の枯れた高速経路）/ 大きい sparse 予約 (alloc_huge ≥2GB 等) だけ reserve-only + demand paging** にする。これで:
  - 典型ワークロード（compute / coreutils / git）は **PF オーバーヘッド ~0** で枯れた eager 経路のまま。
  - V8/Bun の巨大 cage（claude）は demand paging で **小 pool 起動可**（戦略B の本来目的）。
  - 新しめの #PF 経路を「それを必要とする大 sparse 予約」だけに限定 = リスク最小。
- フル default-on（全 anon mmap を reserve-only）は **dense-large-touch で実測 ~2-3x 遅く**（下表）、native backend の存在意義（性能、#221）と相反するので非推奨。
- いずれの場合も **env で eager 強制に戻せる escape hatch** を必須にする（A/B・回帰の保険）。

## 1. 現状（gate の意味）

`EMULIN_NATIVE_PF`（default OFF）が ON のとき:
1. anon mmap (`anonMmap`) を **reserve-only**（PTE not-present、#PF で fault-in）に。
2. `alloc_huge`（≥2GB）を ENOMEM → reserve-only に。
3. #PF 配送基盤（guest GDT/IDT/PF_STUB/per-vCPU TSS+kstack）を構築。

OFF（既定）は全 anon mmap を **eager**（mmap 時に全ページ map）に割当て、≥2GB は ENOMEM。

## 2. 実測データ（KVM nested、この WSL2 機、interleaved A/B 各 4 回 min）

| ワークロード | eager | NATIVE_PF | 差 | 解釈 |
|---|---:|---:|---:|---|
| compute (bench64、mmap 僅少) | 1499ms | 1483ms | **±0**（誤差内） | #PF storm 無し → PF は無コスト |
| page-touch 512MB (131072 ページ first-touch、dense) | 1491ms | 3493ms | **+2002ms** | #PF 往復 = **~15µs/page**（VM-exit + readGprs + getCr2 + faultIn + iretq） |

- JVM 起動 baseline ≈ 1.5s（両者共通）。
- **per-page faultIn コスト ≈ 15µs**（KVM nested L1）。bare-metal/WHP では VM-exit round-trip がより小さい可能性（#221 計測で WHP 非 nested ~5.8µs の trap latency、ただし faultIn は readGprs+getCr2+mapPage を含むので実コストは上乗せ）。
- 含意: **dense に N ページ触る workload は PF で N×15µs 余分**。例: 4GB を dense touch = 1M ページ = **+15 秒**。一方 **触らないページは 0 コスト**（sparse の利点）。

### メモリ面（定性）
- sparse-huge（V8 128GB cage 等）: PF は **触れたページだけ commit** → claude --version が **default 512MB pool で完走**（このセッションで実証）。eager は cage を ENOMEM（alloc_huge=-12）か巨大 pool 必須。
- typical: eager も PF も commit 量は「実際に触ったページ」に近い（eager も host mmap は demand-zero、ただし guest 物理プールの bump は eager に消費）。差が出るのは **予約 ≫ 使用** のとき（= sparse-huge）。

## 3. 正当性・成熟度

- **byte-identical 検証済**: `native-oracle.sh`（eager）/ `native-pf-oracle.sh`（PF 3-way+2-way）で tested binary は software と byte 一致。claude --version も両 backend 完走。
- **新しめの経路**: 戦略B は #392（2026-06）で導入。code review (max effort) で latent bug 15 件→PR #397 で修正済だが、eager 経路ほど実バイナリで枯れていない。
- **WHP 未検証部分**: #1 修正で露見した cage/guest-stack VA 衝突の **stack 帯保護 (inStackRegion) は KVM のみ検証**。default-on（または hybrid）にするなら WHP 実機で stack 保護 + demand paging の再検証が要る（4f 本体は whp-oracle で PASS 済だが、本セッションの review fix は KVM のみ）。

## 4. 選択肢

| 案 | 内容 | 長所 | 短所 |
|---|---|---|---|
| **A. opt-in 据置（現状）** | 既定 OFF | リスク 0、eager は枯れている | claude 等の大 app が既定で動かない（ENOMEM/枯渇）。戦略B の価値が既定で出ない |
| **B. フル default-on** | 全 anon mmap reserve-only | claude 含め全部 demand paging、実装単純（gate 反転） | **dense-large-touch ~2-3x 遅い**、新経路を全 workload に強制（リスク） |
| **★C. サイズ閾値ハイブリッド（推奨）** | #PF 基盤常時 + 小=eager / 大(≥閾値) sparse=reserve-only | 典型は eager で無コスト・枯れたまま、大 sparse は demand paging。新経路を必要箇所に限定 | anonMmap に閾値分岐を追加（中程度の実装 + 回帰）。dense-large-touch は依然 PF コスト（ただし稀） |
| D. 段階 default-on | 既定を ON にし env で opt-OUT、soak 後に確定 | 単純、戦略B の価値が既定で出る | dense workload 回帰を受容、soak 期間の不安定 |

## 5. 推奨と根拠

**案 C（サイズ閾値ハイブリッド）を既定**にし、env で eager 強制（例 `EMULIN_NATIVE_PF=eager`）と全 reserve-only 強制（`=all`）を残す。

- 根拠: 実測で **typical=無コスト / sparse-huge=PF 必須 / dense=PF が重い**。これを満たす唯一の案が「小さい予約は eager・大きい sparse だけ demand-paging」。Linux/OS の overcommit も近い直感（実使用に比例して物理確保）。
- 閾値の目安: **§7 の実測で claude の anon mmap は明確な bimodal（arena ≤128MB / cage ≥1GB、間にギャップ）**と判明。`RESERVE_THRESHOLD = 256MB`（128MB と 1GB の谷）を推奨 — cage 5 本（273GB）だけ demand-paging、arena 22 本（367MB）は eager。alloc_huge（≥2GB）は常に reserve-only。
- 実装スケッチ: `anonMmap(reserveOnly)` を `reserveOnly = NATIVE_PF_MODE==ALL || (NATIVE_PF_MODE==HYBRID && len >= RESERVE_THRESHOLD)` に。`alloc_huge` は常に reserve-only（≥2GB は eager 不可能）。#PF 基盤は HYBRID/ALL で常時構築。
- **前提条件（default 化の門）**: ① WHP 実機で hybrid + stack 保護を再検証（whp-oracle + 実 binary）② 実バイナリ soak（native-oracle full + 主要 demo binary を hybrid 既定で byte-identical 確認）③ dense-large-touch の閾値チューニング。これらが揃うまでは **opt-in 据置（案A）が安全**。

## 6. 次アクション

1. （計測）✅ claude の mmap サイズ分布を採取し **RESERVE_THRESHOLD=256MB を導出（§7）**。残: 各 region の touch 密度計測（faultIn region 別カウント）で 32–128MB arena の dense/sparse を確認 → 閾値の微調整。node も採れば確証が増す。
2. （実装）`NATIVE_PF` を 3 値（off/hybrid/all）化し anonMmap に閾値分岐。回帰 `native-pf-oracle` に hybrid ケース追加。
3. （検証）WHP 実機で hybrid + stack 保護 + 主要 binary を再確認。
4. （判断）soak がクリーンなら hybrid を既定化。

## 7. mmap サイズ分布の実測（claude --version、2026-06-22 KVM）

`EMULIN_TRACE_MMAP=1` で claude --version の anonymous mmap（fd<0 + alloc_huge）を採取（計 27 本）:

```
全サイズ(昇順): 4K×5, 8K×2, 12K, 20K, 36K, 51.6K, 64K, 516K, 2.2M, 4M, 8M, 32M×3, 64M×2, 128M, 1G, 8G×2, 128G×2
```

| bucket | 本数 | reserved 合計 |
|---|---:|---:|
| <64KB | 11 | 155.6KB |
| 64KB–1MB | 2 | 580KB |
| 1–16MB | 3 | 14.3MB |
| 16–256MB | 6 | 352MB |
| 256MB–2GB | 1 | 1.0GB |
| **≥2GB** | **4** | **272GB** |

**★明確な bimodal**: **arena（≤128MB、22 本/計 367MB = V8 heap space / Bun arena、比較的 dense に使う）** と **cage（≥1GB、5 本/計 273GB = pointer-compression cage、sparse）** の間に **128MB↔1GB のギャップ**がある。

閾値候補別（demand-paged 化 vs eager 維持）:

| RESERVE_THRESHOLD | demand-paged | eager 維持 |
|---|---|---|
| 1MB | 14 本 / 273.4GB | 13 本 / 736KB |
| 16MB | 11 本 / 273.3GB | 16 本 / 15MB |
| 64MB | 8 本 / 273.3GB | 19 本 / 111MB |
| **256MB（推奨）** | **5 本 / 273GB** | **22 本 / 367MB** |
| 2GB | 4 本 / 272GB | 23 本 / 1.4GB |

→ **`RESERVE_THRESHOLD = 256MB`** が cage と arena を綺麗に分離する（ギャップの谷）。cage 5 本（273GB の予約）だけ demand-paging、arena 22 本（367MB）は eager。これで「巨大 sparse は必ず demand / 一般 heap は eager で高速」を両立。

**未計測（follow-up）**: 各 region の **touch 密度**（faultIn を region 別にカウント）。32–128MB の arena が実セッション（--version でなく対話）で dense なら eager が正解（閾値据置で OK）、sparse なら閾値を下げる余地。ギャップが広い（128MB↔1GB）ので 256MB はどちらに転んでも安全側。typical な coreutils/git 等は anon mmap が全て ≤数MB（cage 無し）なので **閾値以下＝全 eager＝PF コスト 0**（hybrid の最大の利点）。

## 関連
- #392 本体（戦略B Phase 4 + alloc_huge、PR #396/#397 merged）。
- 計測手法メモ: 直接 binary 起動 + interleaved A/B（`bash -c` 経由は不正確）。`bc` 不在環境では `date +%s%3N` 整数 ms。
