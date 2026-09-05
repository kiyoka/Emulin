# リリース手順 (issue #939)

**公開を最後にする。** 3 リリース連続で「公開した後に手順が通らないと気づく」形になったので、
順序を変えた。

| リリース | 公開**後**に判明 | 結果 |
|---|---|---|
| 0.8.1 | #919 launcher に `setcred` が無く、README どおりの手順が失敗 | zip 差し替え |
| 0.8.2 | #932 非 ASCII の argv 破壊で `apt install` が 7 パッケージ失敗 | zip 差し替え |
| 0.8.3 | WSL2 で `claude auth login` すると `setcred` から見えない | 次版で対応 |

★ 3 回とも **「テストは全部緑なのに利用者の手順が通らない」**。300 を超える回帰テストは
syscall とエミュレータを検証しているが、**README に書いた手順そのものは誰も実行していなかった**。
そして 3 回とも、**出荷物 (zip) を対象に手順を通していれば公開前に落ちていた**。

---

## 手順

### 1. 版を上げる

`pom.xml` / `Version.java` / `dist/THIRD-PARTY-LICENSES.md` / `README.md` / `README.ja.md`。

★ **測定条件の記述は据え置く** (「実測値は Emulin 0.8.2」等は履歴であって版ではない)。
★ 「0.8.0 はこれを構造的に解決します」のような**由来の記述も据え置く**。

### 2. ビルド

```bash
sudo apt-get update                     # ★ 先に実行する (下記)
mvn clean package -DskipTests           # ★ clean 必須
PLATFORMS="windows-x64" dist/build-release.sh
```

> **★ なぜ先に `apt-get update` が要るか**: rootfs の構築は **host の apt** で package を
> 集める。package list が古いと、list に載っている版が pool から消えていて
> **修復 download が 404 になり build が止まる**。
>
> ```
>   [deb-check] status DB に未解決依存: libexpat1  -> package-managed で追加を試みる
> ERROR: rootfs の dpkg status に未解決の依存が残っている。
>        E: Failed to fetch .../libexpat1_2.8.2-1~deb13u1_amd64.deb  404  Not Found
> ```
>
> 2026-09-01 に踏んだ。list が 7 月のままで、security 更新で差し替わった
> `libexpat1 2.8.2-1~deb13u1` が消えていた。**リリースは数週間〜数か月空くので必ず起きる**。
>
> ★ ここで止まるのは #867 (dpkg status が壊れた bundle を出荷し、guest の `apt install` が
> **全部**失敗した) の再発防止ゲートで、**正しい挙動**。黙って壊れた zip が出るより良い。

> **なぜ `clean` が必須か (#929)**: `target` に旧版の jar が残っていると、版を上げた直後の
> ビルドが**辞書順で旧版を掴み**、zip 名も中身も揃って旧版になる。0.8.2 で 2 箇所踏んだ。
>
> ★ `build-release.sh` は**内部でも `mvn clean` を行う**ので、失敗すると `target` が空のまま
> 残る。`work/` に配ったコピーは無事なので、慌てて配布物を戻さないこと。

### 3. 全テスト

```bash
bash tests/scripts/run-all.sh           # 314 PASS / 0 FAIL / 9 SKIP (0.8.3 時点)
```

★ **テスト中に `mvn` を走らせない** (`target/classes` が書き換わり偽 FAIL になる)。

### 4. ★ draft で上げる (まだ誰にも見えない)

```bash
git tag -a vX.Y.Z -m "Emulin X.Y.Z" main && git push origin refs/tags/vX.Y.Z
gh release create vX.Y.Z target/debian-emulin-X.Y.Z-windows-x64.zip \
   --draft --title "Emulin X.Y.Z" --notes-file docs/RELEASE-X.Y.Z.md
```

### 5. ★ **公開物を落として**検査する

```bash
gh release download vX.Y.Z --pattern "*.zip" --dir /tmp/rv
bash tests/scripts/release-verify.sh /tmp/rv/debian-emulin-X.Y.Z-windows-x64.zip
FULL=1 bash tests/scripts/release-verify.sh /tmp/rv/...zip     # apt install まで (約 10 分)
sha256sum /tmp/rv/*.zip target/debian-emulin-X.Y.Z-windows-x64.zip   # 一致を確認
```

**手元のビルドではなく、利用者が受け取るバイト列を検査する。**
0.8.2 では SHA256 の突合はしていたが、**突合しただけで中身の手順は通していなかった**。

`release-verify.sh` の各検査は、過去の実害と 1 対 1 で対応している:

| 検査 | 防ぐ事故 |
|---|---|
| 版の一貫性 (zip 名 / ディレクトリ名 / jar 名 / Version = pom) | #929 |
| README が書く launcher サブコマンドが出荷 launcher に実在するか | #919 |
| 出荷 jar + 出荷 rootfs で guest が起動するか | — |
| **非 ASCII の argv が exec 経由で壊れないか** | #932 |
| `setcred` のメニューが README の記述と一致するか | 0.8.3 |
| 診断が `EMULIN_TRACE_FILE` に落ち、画面に出ないか | #934 |
| (FULL=1) `apt-get install -y nodejs npm` が完走するか | #932 |

### 6. 実機で手順をなぞる (Windows / WHP)

**README に書いた順に、書いたとおりに実行する。** 少なくとも:

- zip を展開 → `emulin.bat` で起動 → guest が上がる
- README が「新しくなった」と書いている手順 (今回なら `setcred` → 認証 → agent 起動)

### 7. 公開する

```bash
gh release edit vX.Y.Z --draft=false
```

公開後に SHA256 をリリースノートへ記載する (差し替えが起きたとき見分けられるように)。

---

## 検査を足すときの原則

★ **負のコントロールを必ず取る。** 「壊れている物に当てたら落ちるか」を確認していない検査は、
検査になっていない。実際 `release-verify.sh` を作った初回に 2 件の欠陥が見つかった:

1. `setcred` の検査が **0.8.2 でも PASS した** — 旧 setup-token のラベルが
   `Claude (Pro/Max subscription)` で**名前が同じで中身が別物**だった。
   → 新実装だけが持つ「`.credentials.json` を読む」注記で判定するよう修正。
2. 非 ASCII の検査が **#932 修正前の jar でも PASS した** — **dash の二重引用符は `\305` を
   解釈しない**ので、ファイル名が純 ASCII のままだった (非 ASCII を一度も通していない)。
   → `printf` で組み立て、**exec 経由** (`/bin/cat "$f"`) で判定するよう修正。

★ **検査には「何を防ぐためにあるか」を issue 番号つきで書く。** 理由が消えた検査は、
いずれ「無駄だから」と外される。
