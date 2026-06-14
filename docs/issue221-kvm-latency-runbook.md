# bare-metal Linux KVM trap latency 計測 runbook (issue #221 step 3f / #304 残件)

native backend (#221) の go/no-go の最後の不確定値 = **bare-metal Linux KVM の
ring-3 syscall-trap round-trip latency 絶対値**を実機で測るための手順。

## 背景・なぜ測るか

native backend の限界コストは「guest の `syscall` を VM-exit で Java 層にトラップする
round-trip latency `T_trap`」だけ。便益は syscall 以外の全命令が実 CPU 速度になること
(#251 で compute 215x 実測)。よって損益分岐は一定閾値で決まる:

```
break-even (命令/syscall) ≈ T_trap / per-instruction-savings
per-instruction-savings ≈ 0.052µs/insn  (software 19.3M insn/s vs native 4.16G insn/s, #251)
```

`T_trap` が小さいほど break-even が下がり、native がさらに有利になる。

### これまでの計測値

| 環境 | trap latency median (A: KVM_RUN round-trip) | break-even | 出典 |
|---|---|---|---|
| WSL2 nested KVM (Hyper-V L2、dev host i5-11400F) | **~5.9–6.0µs** | ~114 命令/syscall | KvmSyscallSmoke 実測 |
| Windows WHP (Hyper-V L1、非 nested) | ~5.8µs | ~110 | WhpSyscallSmoke64 実測 (2026-06-09) |
| **bare-metal Linux KVM (非 nested)** | **? (未測、推定 1–3µs)** | **推定 ~39–58** | ← 本 runbook で測る |

nested (WSL2/WHP) は VM-exit が L1↔L0 を経由するため inflated。bare-metal は
KVM_RUN round-trip が ~1500–6000 cycle (≈0.6–2.5µs@2.4GHz) と見込まれ、break-even を
~39–58 まで下げるはず。実 workload は最も syscall-heavy な `sort` でも ~5000 命令/syscall、
`sha256sum` は ~3.4M なので、bare-metal でも nested でも native は桁違いに勝つ (結論は不変)
が、**絶対値を埋めて go/no-go の最後の仮定を消す**のが目的。

## 計測ツール

- **`src/main/java/emulin/KvmSyscallSmoke.java`** — ring-3 で `mov eax,sysno; syscall` を実行し、
  LSTAR の `hlt` スタブで `KVM_EXIT_HLT` にトラップ → `sysretq` で ring-3 復帰、を 1 `KVM_RUN` =
  1 完全 round-trip として 200,000 回計測。min/median/mean/p99/max を出す。実行環境 (bare-metal /
  nested) を `/proc/cpuinfo` の hypervisor flag と `/proc/sys/kernel/osrelease` から自動判定して
  出力に明記する。
- **`tests/scripts/kvm-latency.sh`** — 上を環境自己文書化付きで走らせるラッパー (推奨)。

## 必要なもの (bare-metal Linux 側)

1. **bare-metal な Linux** (VM/WSL2 でない物理マシン)。CPU 仮想化 (VT-x / AMD-V) を BIOS で ON。
2. `/dev/kvm` に read/write 権限 (`sudo chmod 666 /dev/kvm`、または `sudo usermod -aG kvm $USER` +再ログイン)。
3. JDK 21+ (Microsoft Build of OpenJDK / Temurin)。`java -version` で確認。
4. Maven (初回 `mvn compile` 用)。
5. 本 repo の clone。

### bare-metal 環境の入手 (推奨順)

- **(最良) dev host の i5-11400F を native Linux で起動** — WSL2 で median ~6.0µs を出した
  のと同一 CPU なので、live USB (Ubuntu/Debian) で起動して測れば **同一ハードでの
  nested→bare-metal 差分**がそのまま出る。live USB でも `apt install default-jdk maven git` で測定可。
- (代替) VT-x/AMD-V 付きの予備 PC / ワークステーションに Linux。
- **クラウド VM は原則 nested** (hypervisor flag が立つ) なので bare-metal 値にはならない。
  ベアメタルインスタンス (AWS `*.metal` 等) なら可。スクリプトが nested を検出したら警告する。

## 手順

```bash
# bare-metal Linux で:
git clone https://github.com/kiyoka/Emulin.git   # 既にあれば git pull
cd Emulin
tests/scripts/kvm-latency.sh
```

出力全体をそのまま貼ること。スクリプトが冒頭で環境 (bare-metal か nested か) を判定し、
末尾に `(A) ... median=` を出す。

## 合否・読み方

- `[env] >>> 判定: bare-metal (非 nested) ...` と出ていること (nested なら値は無効、環境を変える)。
- **`(A) KVM_RUN round-trip ... median=N ns`** が bare-metal の trap latency 絶対値。
- スクリプト/smoke が `break-even (命令/syscall) ≈ median/0.052µs = X` を併記する。
  - 期待: WSL2 の ~114 から **~40–60 前後**へ下がる (= 推定 ~39–58 を裏取り)。
- 結論: 実 workload (sort ~5000 / sha256sum ~3.4M 命令/syscall) が break-even を桁違いに
  上回るので native 圧勝 = go/no-go の bare-metal 絶対値による最終確認。

測定後は `docs/backend-abstraction.md` の latency 表とこの runbook の表に実測値を追記し、
issue #221 step 3f / #304 のチェックを埋める。

## 参考
- `src/main/java/emulin/KvmSyscallSmoke.java` (計測本体) / `WhpSyscallSmoke64.java` (WHP 版)
- `docs/backend-abstraction.md` §4.4c (break-even / go-no-go の分析)
- `tests/scripts/bench-native.sh` (compute throughput 215x の方の bench、latency とは別物)
