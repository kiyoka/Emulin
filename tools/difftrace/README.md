# difftrace — host x86 vs emulin 命令レベル差分トレーサ (issue #84)

emulin が**実装済命令を silent に誤実行**する型のバグ
(syscall エラーや segfault に現れず、結果だけ静かに狂う) を、
host の実 x86 実行と emulin の実行を **guest RIP 列**レベルで突き合わせ、
最初に制御フローが食い違う命令を特定するツール。

## 構成

| 部品 | 役割 |
|------|------|
| emulin RIP trace (`Cpu64`, env-gated) | emulin が実行した guest RIP を 8-byte LE で逐次出力 |
| `hosttrace.c` | ptrace single-step で host (実 x86) の RIP 列を同形式で取得 |
| `difftrace.py` | 2 trace を突き合わせ、benign 差を吸収して発散点を nm シンボル付き報告 |

非 PIE バイナリ (node 等) は ASLR off で host も emulin も同じ固定アドレスに
load されるため、RIP が直接比較できる。

## 使い方

### 1. emulin 側 trace
```sh
EMULIN_TRACE_RIP_FILE=/tmp/emu.bin \
EMULIN_TRACE_RIP_LO=dfd000 EMULIN_TRACE_RIP_HI=3061000 \
  java -Xmx8g -XX:-DontCompileHugeMethods -cp <cp> emulin.Emulin <sandbox> \
  /usr/bin/node --jitless -e 'console.log(6*7)'
```
`_LO`/`_HI` は対象バイナリの .text 範囲 (`readelf -S` 等で確認)。env 未設定なら no-overhead。

### 2. host 側 trace
```sh
cc -O2 -o hosttrace hosttrace.c
./hosttrace /tmp/host.bin dfd000 3061000 -- /path/to/node --jitless -e 'console.log(6*7)'
```

### 3. diff
```sh
python3 difftrace.py /tmp/host.bin /tmp/emu.bin --sym /path/to/node [--all] [--maxskip W] [--ctx N]
```
- 既定: 最初の rejoin しない発散だけ報告。
- `--all`: 途中で rejoin する benign 分岐も全て列挙してから本物の発散で停止。
- `--maxskip W`: resync の最大シフト幅 (既定 8)。benign 分岐を跨ぐには大きめ (例 200)。

## benign 差の吸収 (重要)

host と emulin は**実行環境が違う**ため、本物のバグ以外に多数の benign な
発散が出る。これらを潰さないと本物に到達できない。

1. **CPUID 差**: emulin は honest な subset (SSE3/AES/PCLMUL まで、AVX/leaf7 無し)
   を返す。host を同じ feature に揃える:
   ```sh
   OPENSSL_ia32cap=0x02000003178bfbff:0x0
   node ... --no-enable-sse4-1 --no-enable-sse4-2 --no-enable-avx --no-enable-avx2 \
            --no-enable-fma3 --no-enable-bmi1 --no-enable-bmi2 --no-enable-lzcnt --no-enable-popcnt
   ```
2. **rep prefix の粒度差**: host の single-step は `rep stos/movs/cmps` の各反復を
   別 RIP として記録、emulin は 1 命令で記録 → `difftrace.py` が連続同一 RIP を
   collapse して吸収。
3. **環境由来の制御フロー分岐**: argv[0] / execPath(`/proc/self/exe`) / stdio の
   handle 種別(tty/pipe) / ICU timezone / OpenSSL cert・config ファイルなどが
   host とサンドボックスで違うと、文字列長や fstat 結果で分岐が割れる。多くは
   その後 rejoin するので `--maxskip` を上げた resync で吸収できる
   (`--all` で benign と確認できる)。

## 既知の限界

- **host トレーサが single-step で遅い** (~21万命令/分)。発散が数千万命令地点だと
  到達に時間がかかる。後段の発散には `PTRACE_SINGLEBLOCK` (BB 単位) や DBI
  (DynamoRIO/Pin) ベースの高速トレーサが望ましい (将来拡張)。
- **環境差の benign 発散が多い**: node の bootstrap は argv/stdio/cert/ICU 等で
  host と emulin が細かく分岐する。本物のバグまで届かせるにはこれらの環境を
  揃えるか、`--all --maxskip` で benign を仕分けして読み進める必要がある。

## issue #98 進捗 (node --jitless `require is not defined`)

2 回目の調査で原因を大きく絞り込んだ (まだ命令単位の特定は未達):

1. **cpuid/SSE4.x は無罪**: `EMULIN_CPUID_ECX=0` (SSE3/SSSE3/SSE4.1/SSE4.2/POPCNT/AES
   を全部 off = pre-SSE4.2 baseline) でも同じ `require is not defined`。最近の
   SSE4.2 実装 (PCMPISTRI 等) は原因ではない。baseline 命令の silent 誤り。
   (`Cpu64` の cpuid leaf-1 ECX を `EMULIN_CPUID_ECX=<hex>` で上書きして検証。)
2. **`--jitless` 固有**: `node -e 'console.log(6*7)'` (**JIT 有効**) は完走する
   (`42` 出力)。つまり emulin は **V8 が実行時生成する機械語 (JIT) を正しく実行**
   できており、バグは **Ignition interpreter (`--jitless`) のハンドラ機械語**に
   silent に存在する。
3. **eval_string bootstrap 経路**: `node --jitless --version` は完走 (`v22.15.0`)、
   `node --jitless -e ...` / `-p ...` は `node:internal/main/eval_string:15` の
   `require('internal/process/pre_execution')` で `require is not defined`。共通
   bootstrap は正常で、eval_string main-module 実行 (C++→JS 呼び出しで require
   引数が undefined になる) 周辺が壊れている。
4. **発散の深さ = ~79M 命令** (.text 内、`node --jitless -e ''` を crash まで
   `EMULIN_TRACE_RIP_FILE` で計測)。single-step (~2M/分) では到達に時間がかかる。
5. **高速 host トレーサ = FF mode** (hosttrace.c に実装済): WSL2 では
   `PTRACE_SINGLEBLOCK` が **single-step に fallback** して速くならない (記録列が
   連続=BB圧縮されない、と確認)。代わりに `HOSTTRACE_FF_RIP=<hex>` で
   **PTRACE_CONT で指定 RIP まで native 速度で飛ばし、そこから single-step**。
   emulin trace で「1 回だけ実行される late RIP」を anchor にして二分探索する。
6. **★ 決定化が必須**: emulin / node は run ごとに実行経路が変わるため、素の
   diff は非決定ノイズだらけになる (#84 が難航した一因)。以下を全て揃えて
   **emulin が run 間で完全一致 (RIP 列 byte 同一) する**ことを確認済:
   - node flags: `--jitless --single-threaded --predictable --random-seed=1
     --v8-pool-size=0 --no-enable-sse3 …(SSE2 only)`
   - `--v8-pool-size=0` が決定打 (V8 worker thread 生成 `WorkerThreadsTaskRunner`
     が非決定の主因。これが無いと ~5.7M 命令地点で割れる)。
   - `--random-seed=1` で V8 hash seed 固定 (NameDictionary rehash 経路の seed
     依存 benign 発散を消す)。
   - getrandom は `--v8-pool-size=0` 下では制御フローに無影響 (検証済)。一応
     `EMULIN_DET_RANDOM=1` で固定 seed 化も可。
   - host は同 flags + `OPENSSL_ia32cap=…:0x0` + sandbox の .so を `LD_LIBRARY_PATH`。
7. **★ 残る最大の障害 = heap アドレスのずれ**: 決定化しても emu と host で V8
   heap の base が ~4KB (0xFF0) ずれる。V8 はポインタ key の hash map
   (NameDictionary / ConstantArrayBuilder / scanner の各所) を多用するため、
   ポインタ値が違うと probe 順が変わり **大量の benign 制御フロー発散**が出る。
   FF 二分探索で見える発散 (Rehash / GetToken / ConstantArrayBuilder の
   resize 判定など) は調べると host と emu で **データ (cap/occ 等) が一致**し
   ポインタ (r14 等) だけ違う = この heap ずれ由来の benign。本物の命令バグは
   この中に埋もれている。

### 次の一手
- **heap base を揃える**のが本丸。emu の V8 heap mmap base を host と一致させる
  (early の 1 allocation 差を詰める or pointer 比較を相対化) と benign 発散が
  消え、本物の命令誤りが diff のトップに出るはず。
- もしくは **値レベル trace** (特定 RIP での register/memory を host(gdb) と
  emu(EMULIN_WATCH_GPR) で突き合わせ) で、データが食い違う最初の店を直接探す。
- 対象は `node --jitless --single-threaded --predictable --random-seed=1
  --v8-pool-size=0 …(SSE2) -e ''` (決定化済の最小 repro)。emu は `EMULIN_CPUID_ECX=0`。

## issue #98 進捗 (調査3: 環境を揃えて V8 内部まで到達)

決定化 (上記) に加え、host と emu の **環境差 (benign 発散) を順に潰す**ことで
発散点を環境ノイズの外 (V8 codegen) まで前進させた。手順:

1. **argv[0] を揃える** — `HOSTTRACE_ARGV0=/usr/bin/node` (emu の guest argv[0] と
   一致)。これが無いと `uv_get_process_title` で process title 長が割れる。
2. **pid を揃える** — `LD_PRELOAD=detenv.so` (getpid→2, getppid→1。emu の pid=2 と
   一致)。これが無いと `std::to_string(pid)` で割れる。
3. **cpuid を揃える** — `HOSTTRACE_CPUID=1 HOSTTRACE_CPUID_ECX=0` (hosttrace が
   host の cpuid 結果を emu (Cpu64) の値で上書き)。simdutf / V8 は生 cpuid で
   SIMD を直接検出するため V8 flags / OPENSSL_ia32cap では揃わない。これが
   無いと `simdutf::detect_best_supported` で割れる。

これら全部 + 決定化フラグを付けて FF + difftrace すると、最初の非 rejoin 発散は
`v8::internal::interpreter::BytecodeArrayWriter::EmitBytecode` 内 (生成中の
bytecode の operand 数が emu<host) まで進む。= 環境差ではなく **V8 が生成する
bytecode 自体が emu と host で食い違っている** = parser/codegen の手前のどこかで
emu が silent に誤実行している。根本はさらに前。

### 残課題 / 次の一手
- hosttrace の cpuid intercept は cpuid 直後の 1 命令を record し損ねる軽微な
  artifact がある (difftrace.py の resync が吸収するので発散判定には無害)。
- まだ benign 発散が残っている可能性 (heap ポインタ key の hash 等)。EmitBytecode
  の operand 数差が「本物の最初の発散」か、さらに手前があるかの切り分けが必要。
- BytecodeArrayWriter まで来たので、生成 bytecode の食い違いを起点に
  「どの AST ノード / parse 結果が違うか」を追うと根本の命令に迫れるはず。

### 決定化 + 環境整合の完全コマンド
emu:
```
EMULIN_CPUID_ECX=0  java -jar emulin.jar /tmp/node-sb /usr/bin/node \
  --jitless --single-threaded --predictable --random-seed=1 --v8-pool-size=0 \
  --no-enable-sse3 …(SSE2) -e ''
```
host (FF + 全整合):
```
LD_PRELOAD=detenv.so OPENSSL_ia32cap=0x00000003078bfbff:0x0 UV_THREADPOOL_SIZE=1 \
HOSTTRACE_FF_RIP=<once-hit RIP> HOSTTRACE_ARGV0=/usr/bin/node \
HOSTTRACE_CPUID=1 HOSTTRACE_CPUID_ECX=0 \
  ./hosttrace out.bin e00000 3200000 -- /tmp/node-sb/usr/bin/node <同 flags> -e ''
```

## issue #98 進捗 (調査4: V8 codegen のデータ差まで到達 / RIP-diff の限界)

env (argv0/pid/cpuid) を全部揃えた決定的 diff の **最初の非 rejoin 発散** は
`v8::internal::interpreter::BytecodeArrayWriter::EmitBytecode` の operand 出力
ループ (`cmp r15,r12; je`)。emu は r15==r12 (operand 終端、je 成立)、host は
r15>r12 (operand 残あり、je 不成立) = **emu が生成した bytecode の operand 数が
host より少ない = opcode/codegen の出力が食い違っている**。env 差ではなく V8 が
生成する bytecode 自体が違う。

### RIP-diff の限界 (重要な知見)
ここまでで分かったのは、根本バグは **値 (データ) を壊す命令**で、その壊れた値が
**分岐に使われるまで RIP-diff には見えない**ということ。EmitBytecode の発散は
「壊れた opcode/operand 数」が初めて制御フローに現れた地点であって、値を壊した
命令はさらに手前 (parser/codegen のどこか) にある。RIP 列の比較だけでは原因命令を
直接は指せない。

### 次の一手 (データフロー追跡)
- EmitBytecode の opcode/operand 数の差を起点に、その値を**書いた store / 計算した
  命令**へ遡る (BytecodeGenerator が opcode を決める箇所)。
- そのために host/emu の **register/memory を同地点で突き合わせる**: hosttrace に
  `HOSTTRACE_DUMP_RIP=<hex> HOSTTRACE_DUMP_N=<k>` を追加済 (指定 RIP の k 回目で
  r12/r13/r15/rsi を dump、env 整合済)。emu 側は `EMULIN_WATCH_GPR`。
  ※ FF を発散直前の once-hit RIP に寄せると dump が速く出る (full FF は ~4M 命令で
    cpuid intercept の PEEKTEXT overhead 込みだと数百秒かかる)。
- 値レベルで「最初にデータが食い違う地点」を二分探索すれば原因命令に届くはず。

## issue #98 進捗 (調査5: EmitBytecode の operand loop まで局所化)

調査4 の発散 (BytecodeArrayWriter::EmitBytecode の operand 出力ループ
`cmp r15,r12; je`) を掘った結果:

- 発散する EmitBytecode の呼び出しは **opcode=0x13, scale=1** の bytecode
  (emu 側 `EMULIN_WATCH_GPR=14e2954 EMULIN_WATCH_EVAL_LO/HI` で divergent call を
  特定。eval~70050660 直前の entry が opcode 0x13)。
- emu は operand iterator (r12) が終端 (r15) に**早く到達**し je 成立 (operand 数
  少)、host は r15>r12 (operand 残あり)。
- **EmitBytecode が「最初の」発散** = それより手前は emu/host 一致 = この
  EmitBytecode に渡る **BytecodeNode は両者で同じはず**。よってバグは
  (a) EmitBytecode (0x14e2930〜0x14e2c48) の operand loop の実行を emu が誤る、
  または (b) その直前の node 生成 (BytecodeArrayBuilder) で operand/type 配列を
  誤格納、のどちらか。**35MB の .text からこの小領域まで絞れた**。

### ツール追加
- `EMULIN_WATCH_EVAL_LO/HI`: `EMULIN_WATCH_GPR` の dump を eval (命令数) 範囲で
  gate (深い hot path の特定 call だけ dump)。
- `HOSTTRACE_DUMP_RIP/N`: env 整合済 host の指定 RIP N 回目の register dump。

### 次の一手 / 既知の障害
- (a)/(b) の切り分けには、divergent EmitBytecode entry での **node の operand/type
  配列を emu/host で突き合わせる**。同じなら EmitBytecode 実行のバグ (この関数を
  逆アセンブルして emu の該当命令を精査)、違えば node 生成のバグ。
- **障害**: host の整合 register/memory を深い地点 (63.36M) で取るのが難しい。
  FF (PTRACE_CONT) は cpuid intercept が効かず、simdutf cpuid (63.03M) より後に
  FF すると host が AVX 経路に逸れて FF 先 RIP に到達しない。simdutf cpuid の
  **手前**から FF + single-step が必要だが、そこから divergent call まで ~33万命令を
  cpuid intercept 付き single-step (PEEKTEXT overhead 込み ~8k/s) すると時間がかかる
  (timeout 調整 or cpuid faulting (arch_prctl ARCH_SET_CPUID) で FF 中も intercept、が
  次の改善案)。

## issue #98 進捗 (調査6: EmitBytecode は無罪、BytecodeGenerator の opcode 選択の差)

調査5 の divergent EmitBytecode 呼び出しを register dump (`EMULIN_WATCH_GPR=14e29df
EMULIN_WATCH_EVAL_LO/HI`) で精査:

- divergent call: `operand_count = node->[0x18] = 1`、setup 後 `r15 - r12 = 4 = count*4`
  = **EmitBytecode の r15 (operand 終端) 計算は正しい**。emu の node は opcode=0x13・
  operand 1 個。
- host はこの bytecode で operand が 2 個以上 (loop が継続) = **host は別の opcode**
  (operand 数の多い bytecode) を emit している。
- → バグは EmitBytecode の実行ではなく、その手前の **BytecodeGenerator が emit する
  bytecode の種別 (opcode) の決定**が emu と host で食い違っている。emu は operand
  1 個の短い bytecode を、host は operand 複数の bytecode を選んだ。

### つまり根本は codegen の判断
emu が AST を bytecode に落とす際の opcode 選択 (BytecodeGenerator の visit / 
BytecodeArrayBuilder の Output 呼び出し) で、何らかの値を誤って、host と違う
bytecode 種別を選んでいる。EmitBytecode (調査5) も EmitBytecode の operand loop も
無罪。発散はさらに手前の opcode 決定。

### 次の一手
- emu/host が emit する bytecode 列 (opcode の並び) を BytecodeArrayBuilder::Output*
  の呼び出しで突き合わせ、最初に opcode が食い違う Output を特定。
- その Output を呼んだ BytecodeGenerator の visit 箇所で、分岐に使った値
  (register/memory) を emu/host で比較 → 誤った値を計算した命令へ遡る。
- これは RIP 列でなく**値の差分追跡**。RIP-diff が指せる「最初の制御フロー発散」
  (EmitBytecode) より手前の、値だけが静かに食い違う地点を、register/memory dump の
  二分探索で詰める必要がある。
