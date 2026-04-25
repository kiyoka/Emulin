# Phase 1 引き継ぎメモ — 現代 javac でのビルド試行ログ

> Phase 0 の一部として、Emulin 本体を**変更せず** OpenJDK 25 で
> ビルドできるかを試した結果を記録する。Phase 1 で実際に修正する
> 際の出発点として参照する。

## 試験環境

- macOS 25.3.0 (Darwin)
- OpenJDK 25.0.1 (Homebrew, 64-Bit Server VM)
- 作業ブランチ: `phase0/regression-tests`

## ビルドコマンド

```bash
javac -Xlint:none -d /tmp/emulin-build emulin/*.java emulin/device/*.java
```

## 観測された問題 (修正は Phase 1 で行う)

### 1. ソースファイルの文字コードが SJIS / EUC-JP / JIS の混在

- `file emulin/*.java` で見ると "ISO-8859 text" と "ASCII text with
  escape sequences" が混在
- `Emulin.java`, `Memory.java`, `Sysinfo.java` などの古いファイルは
  JIS (ESC `$B` ... ESC `(B` シーケンス入り) の様子
- `Cpu.java`, `Process.java`, `Signal.java`, `Decoder.java` 等は
  EUC-JP に近い 8bit
- **暫定回避**: `javac -encoding EUC-JP` を渡すと多くは通るが、JIS が
  混じったファイルでは別のエラーが出る可能性あり
- **Phase 1 アクション**: 全ファイルを UTF-8 に正規化する。
  `nkf -w --overwrite emulin/**/*.java` 等

### 2. `Console` クラス名の衝突 (ハードエラー、2 件)

```
emulin/XKernel.java:20: エラー: Consoleの参照はあいまいです
  Console console;
  emulin.device.Console と java.io.Console の両方が一致します

emulin/Kernel.java:34: エラー: Consoleの参照はあいまいです
    console = new Console( sysinfo );
```

- `java.io.Console` は Java 6 で追加された
- 当時のソースは `import emulin.device.*;` のみで衝突は無かった
- **Phase 1 アクション**: `XKernel.java` / `Kernel.java` で
  `emulin.device.Console` と完全修飾名を書くか、変数を別名で
  import する

### 3. `Thread.stop()` が削除予定 (削除警告、3 ファイル × 計 4 箇所)

```
emulin/Kernel.java:126   pinfo.process.stop();
emulin/Kernel.java:142   pinfo.process.stop();    // exec 時
emulin/Syscall.java:771  process.stop();          // sys_execve
emulin/Fileinfo.java:360 subprocess.stop();
```

- Java 21 以降 `Thread.stop()` は `UnsupportedOperationException` を
  常に投げるよう変更されている
- 単に `-source 1.8` 等で逃げてもランタイムで死ぬ
- **Phase 1 アクション**: 設計通り、`volatile boolean exit_flag` を
  プロセスの実行ループ (`Process.run()`) で観測して自然終了させる
  形に作り替える。`Kernel.exec` は子の終了を待ってから新しい
  Process を生成する流れにする

### 4. `Socket(String, int, boolean)` 廃止

```
emulin/Fileinfo.java:101: 警告: [removal] Socket(String,int,boolean)
```

- 1.0 時代のコンストラクタ、廃止予定
- **Phase 1 アクション**: 通常の `new Socket(host, port)` で代替、
  `boolean stream` は SOCK_STREAM/SOCK_DGRAM の判定で持っている
  `stream_flag` から既に区別されているので不要

### 5. その他 (要確認)

- `unchecked` 警告多数: `Vector` / 生コレクション利用。動作には影響
  しないが Phase 2 で `ArrayList<Fileinfo>` 等にする
- `JFrame.show()`: 廃止扱い。`setVisible(true)` に変更 (`Kernel.java:66`)
- `Thread.suspend() / resume()` は使われていない (確認済み)

## エラー / 警告のサマリ

```
$ javac -encoding EUC-JP -Xlint:deprecation,removal -d /tmp/emulin-build \
        emulin/*.java emulin/device/*.java
  → エラー 2 件 (Console 曖昧)
    警告 7 件 (Thread.stop ×4, Socket ×1, ほか)
```

エンコード問題と Console 曖昧解消だけで「コンパイル通過」までは
たどり着くが、実行時に `Thread.stop()` で死ぬので Phase 1 完了基準
には不足。

## Phase 1 着手時の最小チェックリスト

1. [ ] 全ソースを UTF-8 に変換、`javac -encoding UTF-8` でビルド可
2. [ ] `Console` 曖昧を解消、ビルドエラー 0
3. [ ] `Thread.stop()` を排除し協調終了 (`exit_flag`) に統一
4. [ ] `JFrame.show()` → `setVisible(true)`
5. [ ] `Socket(String, int, boolean)` を新 API に
6. [ ] `tests/scripts/run-all.sh` で Phase 0 の全テストが PASS
7. [ ] Gradle/Maven、もしくは新しい Makefile を整備

## 補足: テストバイナリのビルドについて

このメモを書いた macOS 環境にも i386 クロスコンパイラが入って
いなかったため、`tests/binaries/bin/` は生成していない。
Phase 1 着手時は Linux ホストか Docker で `make -C tests/binaries`
してから `tests/scripts/run-all.sh` を回すこと。
