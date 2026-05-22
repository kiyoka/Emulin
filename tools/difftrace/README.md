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
