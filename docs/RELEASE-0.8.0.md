# Emulin 0.8.0 Release Notes

## ダウンロード

| ファイル | 対象 | サイズ |
|---|---|---|
| `debian-emulin-0.8.0-windows-x64.zip` | Windows x64 | 277 MB |

**JRE を同梱しているので Java のインストールは不要**です。解凍して `emulin.bat` を
実行するだけで、Debian 13 (trixie) 相当の環境 (apt / dpkg / git / curl / python3 /
vim / emacs 等) が立ち上がります。

> 0.8.0 のビルド済み zip は **Windows 用のみ**公開しています。Linux / macOS では
> `PLATFORMS="linux-x64" dist/build-release.sh` 等でローカルビルドしてください。

導入手順は [README](https://github.com/kiyoka/Emulin/blob/v0.8.0/README.ja.md) を参照。
**Windows Hypervisor Platform (WHP) の有効化を強く推奨**します (guest を実 vCPU で
実行して大幅に高速化)。

---

Java で動く 32/64-bit Linux ELF エミュレータ。0.7.0 (「AI コーディングエージェントが動く」)
を土台に、**0.8.0 の目玉は「エージェントに API キーを渡さずに使える」こと** (issue #401 / #773):

1. **通信サンドボックス化** — guest には**プレースホルダだけ**を置き、実際の API キーは
   host 側にとどめたまま、TLS を張り替える中継 (MITM) が通信の瞬間だけ差し替える。
   guest 側のプロセスがファイルや環境変数を読んでも**本物の鍵は出てこない**。
2. **credential 管理 UI** — `emulin.bat setcred` の対話ウィザードで、Claude / OpenAI /
   Gemini の認証情報を host 側に登録できる。起動時に何が設定済みかを一覧表示する。

加えて、**i386 の x87 浮動小数点を完結** (真の 80-bit 精度まで) させ、
エミュレータ core の堅牢性 (シグナル・メモリ・fd・エラー処理) を広く強化した。

### 実機での動作確認 (Windows 11 / WHP native backend)

配布 zip を展開した**素の状態から README の手順どおりに**進めて確認した。

| | |
|---|---|
| **Claude Code** (現行の Bun ネイティブ版) | 対話コーディング。credential 登録済みなら `/login` 不要。バージョン固定も自動更新の無効化も不要 |
| **OpenAI Codex 0.146** | 対話コーディング (ChatGPT サブスクリプション認証) |
| **Gemini / OpenAI (API キー)** | 疎通確認 |

`apt-get install -y nodejs npm` (依存 270 パッケージ) の完走も確認している。

---

## ハイライト

### 1. 通信サンドボックス化 — API キーを guest に渡さない (issue #401)

AI コーディングエージェントを動かすには API キーが要るが、
**エージェント自身が任意のコードを実行する**以上、鍵を guest 内に置けば
その鍵は guest から読める。0.8.0 はこれを構造的に解決する。

```
  guest (sandbox)                  host
  ┌──────────────────────┐         ┌─────────────────────────┐
  │ claude / codex       │         │ 実 credential           │
  │  ANTHROPIC_API_KEY   │         │  ~/.emulin/             │
  │   = sk-ant-emph01-…  │  TLS    │    credentials.json     │
  │      (プレースホルダ) │ ──────► │                         │
  └──────────────────────┘         │  MITM proxy が          │
                                   │  wire 上で実キーに置換  │
                                   └──────────┬──────────────┘
                                              ▼  api.anthropic.com
```

- **既定で有効**。`EMULIN_EGRESS_MITM=0` で明示的に切れる。
- 置換は **HTTP header と request body の両方向**で行う。
  OAuth の refresh は token を **body** に入れて POST するので、header だけでは足りない。
- **応答も書き換える**: refresh が成功して新しい実トークンが返ってきたら、
  それを **host 側の credential に取り込み**、guest には placeholder を返す。
  ★ placeholder は据え置きにして**指す先の実キーだけ**差し替えるので、
  guest 側の設定ファイルを触る必要が無い。**トークンのローテーションが自動で回る**。
- **横取りするのは credential の送り先だけ**。それ以外 (claude.ai / statsig 等) は
  素通しするので、余計なプロトコルの相性問題を持ち込まない。
- WebSocket の upgrade は raw passthrough。
- CA は初回に自作し (`~/.emulin/emulin-ca.p12`)、guest の ca-bundle と
  `NODE_EXTRA_CA_CERTS` に流し込む。
- credential が 1 つも設定されていなければ**何もしない** (no-op)。

**実機での確認**: Claude Code の Pro/Max サブスクリプションで、
コーディングセッションが最後まで動作することを確認済み。

この実現までに芋づる式に 5 件の問題を解消した:

| | |
|---|---|
| IPv6 素通り | `connect` の横取り判定が AF_INET しか見ておらず、IPv6 で接続されると素通りしていた |
| RSA 証明書が software backend で不成立 | RSA-PSS のパディング検証に失敗する経路があり、**ECDSA (P-256)** に変更 (実 API も ECDSA) |
| CA の二重化で間欠的な検証失敗 | 旧 RSA CA が ca-bundle に残ると chain builder が旧鍵を選び `CERT_SIGNATURE_FAILURE` になる。追記時に旧ブロックを全除去 |
| メモリ逼迫 | `EMULIN_NATIVE_POOL_MB` で all-native を維持 |
| 非 root の ssh 環境変数 | `/home/<user>/.ssh/environment` にもプレースホルダを配置 |

関連の堅牢化も同時に入れた (#764 keystore の鍵アルゴリズム検証 / #765 trust store 書き込みの
アトミック化 / #766 IPv6 と ALPN のエッジ / #767 guest 側からガードを破る入力の遮断)。

### 2. credential 管理 (issue #773 / #774)

`emulin.bat setcred` の対話ウィザードで 5 種類を登録できる。

```
  [1] Claude (Pro/Max subscription)
  [2] Claude (Console API key)
  [3] OpenAI Codex (ChatGPT subscription)  -- reads ~/.codex/auth.json
  [4] OpenAI (API key)
  [5] Gemini (API key)
```

- **provider 単位でまとめ、各 provider 内は「定額サブスク → 従量 API キー」の順**。
  サブスク契約者が従量課金のキーを誤って選ばないようにした。
- 保存先は `~/.emulin/credentials.json`。**登録日時 (savedAt) も記録**し、
  起動時に「どれが設定済みで、いつ入れたか」を一覧表示する (#774)。
- **プレースホルダは provider ごとに実キーの「形」に合わせる** (#807)。
  `sk-ant-…` / `sk-…` / `AIza…` / JWT / UUID。形が違うとアプリ側の入力検証で
  弾かれてしまうため。
- 登録前に**疎通確認**を行い、鍵が無効ならその場で分かる。
- Codex の ChatGPT サブスクだけは「1 個の文字列を貼る」形ではなく、
  JWT 3 種 + account_id の組なので、host 側の `~/.codex/auth.json` を読み取る (#809)。

### 3. i386 x87 浮動小数点の完結 (issue #749 / #751 / #753 / #755 / #757)

i386 バイナリの浮動小数点まわりを 5 段階で埋め、**真の 80-bit 拡張精度**まで到達した。

- **decode の穴を塞いだ** — D9 帯 / DD 帯 / DF 帯の未実装 register 形が
  別命令 (`FLDCW`/`FNSTCW`/`FST m32`/`FNSTSW m16`/`FILD m16`) に**誤マッチして
  黙って別の動作をしていた**。SIGILL より質が悪い (#749 / #753 / #755)。
- **超越関数 8 命令**を実装 (`F2XM1`/`FYL2X`/`FPTAN`/`FPATAN`/`FYL2XP1`/
  `FSINCOS`/`FSIN`/`FCOS`)。i386 の glibc libm が直撃していた (#751)。
- **FPU 環境系 5 命令** (`FNCLEX`/`FNSTENV`/`FLDENV`/`FNSAVE`/`FRSTOR`) —
  `fenv.h` が直撃 (#753)。
- **m80 / BCD** (`FLD`/`FSTP m80`、`FBLD`/`FBSTP`) — `long double` (`%Lf`) が直撃 (#755)。
- ★ **真の 80-bit 精度** (#757): 従来は double で近似していたため、Linux 既定の
  制御ワード (PC=64) で**二重丸めによる 1ulp のずれ**が出て、`2^2000` が `inf` に
  なっていた。仮数 64bit / 指数 15bit の softfloat に置き換え、
  丸め制御 (PC × RC) も実装した。

### 4. 実アプリで踏んだ不具合の修正

- **`gh` (Go バイナリ) が native backend で動くようになった** (#742) — 5 件の連鎖:
  `clone(child_stack==0)` で子の RSP が 0 になる triple fault、`epoll_ctl` の
  無ロック更新による ConcurrentModificationException、connected UDP の
  `read()` が常に 0、UDP の epoll readiness 欠落、TCP の `EPOLLET` が
  drain 前に消費されて永久抑制される問題。
- **SSH 越しの Enter が改行になる** (#742/#744) — pty slave の `ICRNL` 判定が
  read する fd 側を見ており、`dup` した fd では device が raw でも CR→LF 変換されていた。
  device 単位の判定に修正。
- **`/quit` が終了しない** (#759) — `/dev/tty` の open が fd0 と Fileinfo を共有する
  実装で `O_NONBLOCK` を捨てており、「読み切って EAGAIN」を前提にした実装が永久に待っていた。
- **Write verification の誤警告** (#745) — drive mount の native path が二重区切りになり、
  rename 時の cache 無効化とキーが食い違って stale な `st_size` を返していた。
- **mozc_emacs_helper が 100% CPU を食う** (#730) — `CMP/SUB AL,imm8` が OF を
  計算しておらず `jl` が誤成立して無限ループになっていた。
- **非 tty の stdin を対話端末と誤認する** (#768) — リダイレクトやパイプの stdin を
  tty 扱いしており、CR の欠落や `bc` の対話バナーが出ていた。
- **診断・警告メッセージを英語化** (#771)。

### 5. エミュレータの堅牢性 — 「落ちない・固まらない・嘘をつかない」

検証を強化する過程で見つかった不具合をまとめて修正した。
**guest がどんな引数を渡してもエミュレータが道連れにならない**ことを目標にしている。

#### 例外が漏れてハングしない (#779 / #777 / #778 / #783 / #804)

guest 由来のポインタや fd が未検証のまま使われ、Java 例外が
エミュレータ本体に漏れて**プロセスごと固まる**経路があった。

- syscall ディスパッチに**最後の砦**を置いた (`RuntimeException`→`EFAULT`、
  `OutOfMemoryError`→`ENOMEM`)。★ ただし制御フロー用の例外
  (スレッド終了など) は砦の**手前で rethrow** しないと無限ループになる。
- 砦に頼らず個々の入口で検証するようにし、**砦の発火を 16 → 0** にした (#782)。
- 壊れた ELF でも同様 (#804): ヘッダ表を未検証で使う / ロード失敗でもスレッドを起動する /
  起動経路に砦が無く JVM が終了しない、の 3 点を修正。

#### 資源が尽きても Linux と同じ縮退をする (#785 / #786 / #776)

- **失敗すべき巨大 `mmap` がアドレス空間を壊していた** (#785) — length 未検証で
  bump ポインタが負に wrap し、**以後すべての `mmap` が負の値を返す**状態になっていた。
  「失敗すること」だけでなく「**失敗した後に復帰できること**」まで見て初めて露見した。
- **`RLIMIT_NOFILE` が保存も強制もされていなかった** (#786) — `setrlimit` が無視され、
  fd をいくつ開いても `EMFILE` にならなかった。
- **pty が close しても解放されず単調増加していた** (#776)。
  `EMULIN_LEAKCHECK=1` で終了時のリソースリークを計測できるようにした (#780)。

#### エラーの伝え方を Linux に合わせる (#790〜#794 / #800)

- ★ **`open(2)` の作成判断がアクセスモード依存だった** (#790) —
  `O_CREAT` 無しの `O_WRONLY`/`O_RDWR` がファイルを作り、
  `O_RDONLY|O_CREAT` が作れなかった。
- 読めないファイルの `open` が `EACCES` でなく `ENOENT` を返していた (#791) —
  **権限エラーが「ファイルが無い」に化けていた**。
- `/dev/zero` と `/dev/full` が無い / `/dev/urandom` への write が `EPIPE` (#792)。
- pty master の read が slave 全 close 後に `EOF` を返していた (正しくは `EIO`) (#793)。
- `statfs(2)` の errno (#800)。
- テスト用の I/O 失敗注入フックを追加 (`EMULIN_IO_FAIL_NTH` / `_EVERY`、既定 off) (#794)。

#### シグナルとタイマ (#796 / #797 / #815 / #813)

- ★ **native backend でシグナルハンドラの `ucontext` 書き換えが無視されていた** (#796) —
  ハンドラが `ucontext.rip` を書き換えて復帰する手法 (Go / V8 / JVM の trap 復帰の中核) が
  syscall 境界での配送では効かなかった。
- **POSIX タイマがほぼ機能していなかった** (#797) — disarm / delete しても
  **幽霊の `SIGALRM` が飛ぶ**、周期が無視される、id が衝突する、`sigev_signo` が無視される。
  世代番号による取り消しで作り直した。
- ★ **リアルタイムシグナルがキューイングされていなかった** (#815) — 同じ signal を
  n 回積んでも siginfo が単一スロットで上書きされ、**値がすべて最後のものに潰れて**いた。
  さらに調べると、ハンドラ実行中の**自己マスクが signal 31 までで切られており**、
  RT シグナルは `SA_NODEFER` 無しでも**ハンドラが自分自身に再入**していた
  (結果、pending が LIFO で消化され配送順が逆になる)。両方を修正。
- **`rt_tgsigqueueinfo` が誤った syscall 番号に配線されていた** (#813) —
  正しくは 297 だが 240 (= `mq_open`) に繋がっており、
  (a) 297 は `ENOSYS`、(b) `mq_open` を呼ぶと別の syscall が走る、の二重の誤りだった。

#### パスと fd の扱い (#811 / #812 / #814 / #820 / #818)

- ★ **空文字列のパスが「そのディレクトリ自身」に解決されていた** (#811) —
  `rmdir("")` が**カレントディレクトリを実際に削除**し、`chmod("")` が
  その mode を書き換えていた。変更系の syscall 全体にガードを入れた。
- **at 系 syscall が無効な dirfd を `EBADF` にできなかった** (#812) —
  ヘルパが無効 fd に `null` ではなく番兵文字列を返すため、
  エラー判定が**決して成立しない**状態だった。
- `truncate(2)` を実装 (#814。`ftruncate` はあったが path 版が無かった)。
- ★ **file-backed mmap の EOF 境界が効いていなかった** (#820) —
  縮小後に EOF を越えたページを読んでも `SIGBUS` にならず、
  **ゴミデータを読んで先へ進んで**いた。照合キーが呼び出し経路で食い違うことと、
  `fork` した子に境界が引き継がれないことの 2 つが原因。
- **`clone` が `CLONE_CHILD_SETTID` を確認せず `child_tidptr` に tid を書いていた** (#818) —
  「番兵を入れて `FUTEX_WAIT` で子の終了を待つ」定石が空振りする。
  `pthread_join` が依存する機構なので間欠ハングの温床だった。

#### 通信サンドボックスの穴 (#764〜#767 / #824)

- keystore の鍵アルゴリズムを検証していなかった (#764) / trust store 書き込みが
  非アトミックで非 ASCII で無言スキップしていた (#765) / IPv6 と ALPN のエッジ (#766) /
  ★ **guest 側からの入力でガードを破れた** (#767: deny sentinel の先行作成と
  `read_nonroot_user` の `..` 素通り)。
- ★ **refresh の応答で実トークンが guest に落ちていた** (#824)。
  応答を placeholder 化し、実キーの更新は host 側だけで行うようにした。

#### その他 (#733〜#735 / #737 / #741 / #788 / #799 / #802 / #817 / #746)

`openat` の errno、pty の `O_NONBLOCK` 取りこぼし、TCP write の `O_NONBLOCK` 無視、
futex のアドレス空間分離とその回帰、`SOCK_DGRAM` socketpair の境界保持、
io_uring の引数検証、メモリ逼迫下の初回クラスロード死 など。

### 6. リリース検証で見つけて直した不具合

配布 zip を作り、**素の状態から README どおりに Claude Code と Codex を
セットアップする**作業を実際に行った。その過程で 7 件の不具合が見つかり、
すべて本リリースに含めた。**リリース作業そのものが最後のテストになった**。

| # | 内容 |
|---|---|
| **#886** | ★ **終了したゲストプロセスが解放されず OOM** — `apt-get install nodejs npm` (依存 270 パッケージ) が 268 個目で `Java heap space`。6.9GB のダンプを解析すると、実際に走っているプロセスは 7 個なのに 1084 個分が到達可能だった。原因は 2 つで、(a) `FutexManager` の待機表が **static なのに一度も削除されず**、`Key` が握る `MemoryBackend` ごとプロセスを pin していた (b) `NativeCpuBackend.forkParent` が複製後も残り、子が**自分の祖先すべて**を pin していた |
| **#880** | ★ **stale TLB で guest のヒープが壊れる** (WHP) — codex が起動直後に `#GP` で死ぬ。`hlt` = musl の `a_crash()` = mallocng のアサーション失敗だった。cross-vCPU の TLB shootdown が無いため、`mmap`/`munmap` で再利用された VA への書き込みが**別の物理ページに着弾**していた。syscall 境界での self-flush を WHP で既定 ON にした |
| **#879** | **WHP の同時 vCPU 上限 64 がハードコード**で、thread を多用する guest が突然 thread group ごと SIGSEGV になっていた。既定 256 + `EMULIN_WHP_MAX_VCPUS`、拒否されたら段階的に下げて retry |
| **#881** | **`LDDQU` (SSE3) が software backend に無かった** — musl/Rust の SIMD 文字列走査で普通に出る命令。native では実 CPU が実行するので **software でだけ落ちる**形になっており、backend 比較を静かに無効化していた |
| **#876** | **Claude Code の初回 onboarding が credential を無視してログイン選択を出す** — 利用者がそこで OAuth を完了させると**実トークンが sandbox 内に書き込まれ**、#401 の意味が無くなる。`~/.claude.json` に onboarding 済みを seed する (codex の `auth.json` と対称) |
| **#874** | **リリース build のゲートが「通った」ことをログに残さない** — `build-release.sh` が stdout を捨てていたため、dpkg 整合検査と TLS smoke の結果が消え、「通った」と「そもそも走らなかった」を区別できなかった。結果を stderr に出し、skip を明示するようにした |
| **#878** | **`bind()` の要求アドレスが無視され常に `0.0.0.0` になる** — `sshd_config` の `ListenAddress 127.0.0.1` が効かない。**仕様として受容**し (rootfs 自体が隔離境界・publickey 限定・LAN から使える利点がある)、README の記述を実態に合わせた |


---

## 既知の制限

- **`EMULIN_TLB_FLUSH_SYSCALL` は緩和であって本来の TLB shootdown ではない** (#885)。
  WHP で既定 ON にしたのは「自 vCPU を syscall 境界で flush する」対処で、
  page table を書き換えた瞬間に他 vCPU が持つ stale entry は、その vCPU が次の
  syscall に到達するまで残る。実用上は塞がっているが、flush 回数を減らす
  MMU 世代カウンタ方式を 0.8.1 で追う。
- **孤児プロセスが刈り取られない** (#889)。親が `wait` せずに終了した子が
  プロセス表に残り続ける。実 Linux は init が reparent して回収する。
  通常のシェル操作や `apt` は正しく `wait` するので踏みにくい。
- **native backend の vCPU id が再利用されない** (#843)。生涯 4096 スレッドで
  プロセスが死ぬ。thread を作っては捨てる guest を長時間動かすと到達し得る。
- **claude の remote-control (teleport) はサンドボックスと両立しない** (#769、not planned)。
  setup-token のスコープが推論専用のため。
- **稀にセッションが凍結する事象が残っている** (#740)。
- **ssh 経由の codex TUI で CPR (カーソル位置問い合わせ) が失敗することがある** (#588)。

---

## アップグレード時の注意

- credential ファイルが `~/.emulin/credentials` から
  **`~/.emulin/credentials.json`** に変わった (#774)。
  旧ファイルがある場合は `emulin.bat setcred` で入れ直すこと。
- 通信サンドボックス化は**既定で有効**。切りたい場合は `EMULIN_EGRESS_MITM=0`。
- Windows では credential ファイルは `C:\Users\<ユーザ名>\.emulin\` に置かれる
  (WSL のホームディレクトリとは**別**なので注意)。
- **Windows (WHP) では TLB の self-flush が既定 ON になった** (#880)。
  これを切ると guest のヒープが壊れることがあるので、`EMULIN_TLB_FLUSH_SYSCALL=0` は
  性能比較などの目的に限ること。
- **同時 vCPU 数の上限が 64 → 256 になった** (#879、`EMULIN_WHP_MAX_VCPUS` で調整可)。
- **AI エージェントを動かすときは `EMULIN_NATIVE_POOL_MB=1024` を設定する**。
  ランチャ既定の 2048 のままだと 32GB の窓が先に埋まり、収まらないプロセスが
  software backend に落ちて極端に遅くなる (README に記載)。
- guest の `~/.codex/auth.json` は**起動ごとに現在の placeholder で書き直される**。
  guest 内で `codex login` して本物のトークンを入れている場合はそのまま尊重されるが、
  その場合サンドボックスの外に鍵を置く意味が無くなる点に注意。

---

## リリース手順 (チェックリスト)

- [x] `pom.xml` の version を 0.8.0 に更新
- [x] README を 0.8.0 向けに更新 (#888 で実機検証の結果を反映)
- [x] Windows zip を作成し、成果物そのものを検証
      (dpkg 整合 clean / 267 packages / CA root 10 / TLS 9 URL 検証 /
       同梱 jar に #879 #880 #881 #886 が入っていること)
- [x] 素の zip から README の手順で Claude Code と Codex 0.146 の動作を実機確認
- [ ] 本ファイルの内容を GitHub Release 本文にする
- [ ] タグ `v0.8.0` を打ち、zip をアップロードする
