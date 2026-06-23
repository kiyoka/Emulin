# issue #392: 戦略B (`EMULIN_NATIVE_PF`) を default ON にするかの分析

作成: 2026-06-22 / 対象: native(KVM/WHP) backend の demand paging を既定で有効にするかの意思決定。

## 0. TL;DR（結論）

> **★2026-06-23 決定: 案B（フル default-on）を採用・実装済 — PR #402（branch `issue-392-default-on-flip`）。**
> `EMULIN_NATIVE_PF` を default ON に反転（eager 強制の escape hatch = `EMULIN_NO_NATIVE_PF=1`）。
> 技術 gate は全クリア: KVM（native-pf-oracle 2/2 + 代表 8/8 default-PF==software）+ **WHP 実機（flipped jar で whp-oracle ok=56/FAIL=0 = 全 hermetic が default-PF==software）** + 実 binary（claude --version を WHP+default-PF+512MB pool で完走、`2.1.185 (Claude Code)`）。
> これで claude（V8 128GB cage）等が箱出し・小 pool で起動可能になった。以下は採用に至った分析。

★2026-06-22 改訂: 当初ハイブリッドを第一推奨にしたが、§7.1 の実測でその前提が崩れたため**ハイブリッド非推奨**に訂正。

- **本当の選択肢は「A: opt-in 据置」か「B: フル default-on」の二択**。サイズ閾値ハイブリッドは複雑さに見合う利点が無く非推奨。
  - **A. opt-in 据置（現状、`EMULIN_NATIVE_PF=1` で明示有効化）**: 最も単純・リスク 0。ただし claude 等の大 app は手で env を要する。
  - **B. フル default-on（全 anon mmap を reserve-only）**: claude が箱出し・小 pool で動く。**実測上 perf 問題なし**（typical=0 / claude ~170ms、§2・§7.1）。メモリも最小。前提は WHP 実機 soak。
- **ハイブリッドを当初推したのは誤り**。動機の「dense-large-touch の #PF 罰（~15µs/page）を避ける」は **§7.1 で前提が崩れる**: その罰は **合成ベンチでのみ**発生し、emulin の実ワークロード（coreutils/git/vim/emacs/claude）には存在しない（claude の arena は ≤30% dense・cage ~0%・総 faulted 44.5M=~170ms）。守る相手がいない。ハイブリッドの唯一の残る利点は「新しい #PF 経路を大領域だけに限定する成熟度の保険」だが、#PF 経路は #397 review + 回帰固め (sys_pf_demand64 D 等) で枯れてきたため複雑さ（3 値 gate + 閾値 tuning + test 増）に見合わず、メモリも B より悪い（小 mmap を eager で抱える）。
- いずれの案も **env で eager 強制に戻せる escape hatch** を残す（A/B・回帰の保険）。
- 判断: **claude を箱出しで動かすなら B（WHP soak 後）**、**とにかくリスク 0 なら A 据置**。

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
| **★A. opt-in 据置（現状）** | 既定 OFF | リスク 0、eager は枯れている、実装ゼロ | claude 等の大 app が既定で動かない（手で `EMULIN_NATIVE_PF=1`）。戦略B の価値が既定で出ない |
| **★B. フル default-on** | 全 anon mmap reserve-only | claude 含め箱出しで動く、実装単純（gate 反転）、メモリ最小。**実測 perf 問題なし**（typical=0/claude ~170ms） | 新 #PF 経路を全 workload に強制（成熟度リスク、ただし #397+回帰で低減）。dense-large-touch は遅いが**実 workload に無い（合成ベンチのみ）** |
| ~~C. サイズ閾値ハイブリッド~~（非推奨） | #PF 基盤常時 + 小=eager / 大(≥閾値) sparse=reserve-only | 新経路を大領域に限定（成熟度の保険） | **複雑（3 値 gate+閾値 tuning+test 増）に見合う利点が無い**: 守る相手の dense-large-touch が実 workload に無い（§7.1）、メモリは B より悪い（小 mmap を eager で抱える） |
| D. 段階 default-on | B を env opt-OUT 付きで soak 後確定 | B の安全な導入手順 | (= B の運用方法) |

## 5. 推奨と根拠（★2026-06-22 改訂）

**A（opt-in 据置）か B（フル default-on）の二択。ハイブリッド（C）は非推奨。**

- **なぜハイブリッドを落としたか**: 当初 C を推した根拠は「dense で PF が重い」だったが、§7.1 の region 別実測で **emulin の実ワークロードには dense-large-touch が存在しない**と判明（claude の arena は ≤30% dense、cage ~0%、総 faulted 44.5M=~170ms）。dense の +2-3x（§2）は **512MB を 100% 触る合成ベンチ限定**。守る相手がいないので、C の複雑さ（3 値 gate + 閾値 tuning + test 増、§7 の bimodal/閾値議論は学術的興味に留まる）は正当化されない。C の唯一残る利点「新 #PF 経路を大領域に限定する成熟度の保険」も、#PF 経路が #397 review + 回帰固め（sys_pf_demand64 シナリオ D 等）で枯れたため価値が低下。メモリも C は B より悪い（小 mmap を eager で抱える）。
- **B（フル default-on）を採るなら**: gate を反転（default ON）+ `EMULIN_NO_NATIVE_PF=1`（仮）で eager 強制の escape hatch を残すだけ。実装は小さい。perf は実測上問題なし。**前提（default 化の門）= ① WHP 実機で stack 保護 + demand paging を再検証（whp-oracle + 実 binary、本セッションの review fix は KVM のみ）② 実バイナリ soak（native-oracle full + 主要 demo binary を default-on で byte-identical 確認）**。これが揃えば B。
- **A（据置）を採るなら**: 何もしない。claude を動かす人だけ `EMULIN_NATIVE_PF=1`。リスク 0。default-on の判断を先送りしたいとき。
- どちらでも **env で eager⇔reserve-only を切替えられる escape hatch** は維持（A/B test・回帰の保険）。

## 6. 次アクション（★2026-06-23 改訂: 案B 採用・実装完了）

1. （計測）✅ 完了。perf（§2）+ mmap サイズ分布（§7）+ region 別 touch 密度（§7.1）。結論: 実 workload に dense-large-touch 無し → **ハイブリッド不要**、判断は A/B。
2. **（B）WHP 実機 soak**: ✅ 完了。whp-oracle.ps1 を flipped jar で再走 **ok=56/FAIL=0**（全 hermetic が default-PF==software）+ 実 binary claude --version を WHP+default-PF+512MB pool で完走。
3. **（B）gate 反転**: ✅ 完了。`NATIVE_PF` を default ON に + `EMULIN_NO_NATIVE_PF=1`（or `EMULIN_NATIVE_PF=0`）で eager 強制。**PR #402**（NativeCpuBackend/NativeMemoryBackend + native-pf-oracle.sh）。
4. ~~（A 据置）~~ 不採用（B 採用済）。
5. （任意・将来）node 等の追加計測 / dense-large workload が実際に出たら C（閾値ハイブリッド）を再検討（§7 の閾値議論が出発点）。escape hatch `EMULIN_NO_NATIVE_PF=1` で常に eager に戻せる。

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

typical な coreutils/git 等は anon mmap が全て ≤数MB（cage 無し）なので **閾値以下＝全 eager＝PF コスト 0**（hybrid の最大の利点）。

### 7.1 region 別 touch 密度の実測（faultIn を region 別カウント、claude --version、NATIVE_PF）

faultIn を一時計装し reserve 領域ごとに faulted ページ数を測定（計測後 revert）:

| region (reserved) | faulted | density | 判定 |
|---|---:|---:|---|
| 8GB cage (trim 後 4G) | 1.3M | **0.032%** | 激 sparse |
| 1GB | 2.8M | **0.275%** | 激 sparse |
| **128GB cage ×2** | **0**（fault 皆無=非掲載） | **~0%** | 完全 sparse |
| 128M arena | 156K | **0.12%** | sparse |
| 64M arena | 36K / 17.5M | **0.055% / 27.3%** | sparse / 中 |
| 32M arena ×3 | — | **18.3% / 30.3% / 21.7%** | 中 |
| 8M | 36K | **0.44%** | sparse |
| ≤2.3M (多数) | — | 0.4〜100% | 小・概ね dense |
| **合計** | **faulted 44.5M** | reserved 5.4G(+cage 273GB) | **触ったのは 44.5M だけ** |

**★当初仮定の訂正**: 「arena (32–128MB) は dense だから eager」は **誤り**。実測では arena も **≤30%（多くは <1%）**、cage は **~0%**。claude --version の総 faulted は **44.5M のみ**（reserve は 5.4G + 273GB）。

含意:
- **arena も demand-paging すると 70〜99% メモリ節約**でき、PF コストは arena 全部でも合計 ~44.5M faulted ≈ **数百 ms 以内**（15µs/page）。
- 逆に **256MB 閾値で arena を eager にすると ~317MB を無駄に commit**（reserve 367MB のうち実 touch は ~50MB）し小 pool 化を阻害（claude が full-PF では 512MB pool で動くのに、256MB 閾値だと arena eager だけで pool を圧迫）。
- → **閾値はむしろ低め（16〜64MB）が V8/claude にはメモリ最適**（cage + sparse arena を demand-paging、≤16MB の小・dense だけ eager）。
- ただし **dense-large-touch（§2 の +2s/512MB）の worst case は「16MB+ を高密度に触る」workload**。claude は非該当（最大 30%）だが、メモリ帯域 bench / 大データ全ロード等は該当しうる → そういう workload は eager（高閾値 or off）が有利。

**この density データが導いた結論（★2026-06-22）**: arena すら ≤30% dense（多くは <1%）= demand-paging が全域で純利得 ⇒ 「小 mmap は eager に残す」というハイブリッド（C）の前提が消える。**ゆえに閾値で arena を eager に守る意味が無く、C は不要**（§0/§5 で非推奨に訂正）。全 demand-paging（B）が claude では ~170ms の PF コストでメモリ最小。仮に将来 dense-large workload を実際に走らせる必要が出たら、C を再検討する出発点としてこの bimodal（arena ≤128MB / cage ≥1GB、谷は 256MB 付近）が使える。

## 関連
- #392 本体（戦略B Phase 4 + alloc_huge、PR #396/#397 merged）。
- 計測手法メモ: 直接 binary 起動 + interleaved A/B（`bash -c` 経由は不正確）。`bc` 不在環境では `date +%s%3N` 整数 ms。
