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
