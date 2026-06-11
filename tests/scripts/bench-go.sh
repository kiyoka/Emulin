#!/usr/bin/env bash
# bench-go.sh — issue #221: Go 言語の「コンパイル + 実行ファイル実行」を native で実証する。
#
# Go の実行ファイルは独自ランタイム (goroutine スケジューラ / GC / signal-based preempt / 多数の M
# スレッドを clone で生成 / futex 協調) を持つ静的バイナリで、emulin の signal / clone / futex /
# mmap 基盤を広範にストレスする。3d-2c-37 で 3 バグを修正して Go runtime が動くようになった:
#   (1) rt_sigaction が RT signal (SIGRTMIN=34 等) を EINVAL → Go の initsig が "sigaction failed"
#       で即死 → SIGNALS=32→65 に拡大
#   (2) native の clone(thread) 子が親 GPR を継承せず → Go の runtime.clone は r12=mstart を register
#       で渡すため triple fault → 子に親の全 GPR を継承 (Linux clone と同じ semantics)
#   (3) interpreter (software) が RCL/RCR (Grp2 /2 /3) 未実装 → Go の div-by-const magic で停止 → 実装
#
# 本スクリプトは (A) host 製の Go 実行ファイルを emulin で実行 (native==software==host)、
#   (B) emulin native で `go build` を実行 (= コンパイラ toolchain を fork+exec で動かす) を試す。
#
# host に go が必要。GOROOT は go env で自動検出 (apt の golang-1.22-go 等)。go/kvm が無い環境は SKIP。
# 計測値を出すだけ (pass/fail でない、host 依存なので CI 外)。
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes
ASM=$(find "$HOME/.m2" -name "asm-9.6.jar" 2>/dev/null | head -1)
CP="$CLASSES${ASM:+:$ASM}"
JOPT="--enable-native-access=ALL-UNNAMED -XX:-UsePerfData -Xmx6g"

[ -f "$CLASSES/emulin/Emulin.class" ] || { echo "SKIP : Emulin not built"; exit 2; }
GO=$(command -v go) || GO=${GOROOT_BIN:-}
[ -x "$GO" ] || { echo "SKIP : host go not found (apt install golang-go / GOROOT_BIN=...)"; exit 2; }
GOROOT=$("$GO" env GOROOT 2>/dev/null)
[ -d "$GOROOT" ] || { echo "SKIP : GOROOT not resolvable"; exit 2; }
HAVE_KVM=0; [ -r /dev/kvm ] && [ -w /dev/kvm ] && HAVE_KVM=1
[ "$HAVE_KVM" = 1 ] || { echo "SKIP : /dev/kvm not accessible (native backend 不可)"; exit 2; }

SB=$(mktemp -d -t emulin-go.XXXXXX); trap 'rm -rf "$SB"' EXIT
mkdir -p "$SB"/{bin,tmp,dev,etc,proj,gocache,gopath} "$SB/usr/lib"
: > "$SB/etc/emulin.cnf"

# テスト用 Go プログラム (goroutine + channel + map + sort = runtime/GC を動かす、決定的出力)
cat > "$SB/proj/prog.go" <<'GOEOF'
package main

import (
	"fmt"
	"sort"
	"sync"
)

func main() {
	var wg sync.WaitGroup
	res := make([]int, 6)
	for i := 0; i < 6; i++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			s := 0
			for j := 0; j < 20000; j++ {
				s += (j * id) % 97
			}
			res[id] = s
		}(i)
	}
	wg.Wait()
	sort.Ints(res)
	m := map[string]int{}
	for i := 0; i < 3000; i++ {
		m[fmt.Sprintf("k%d", i%53)]++
	}
	tot := 0
	for _, r := range res {
		tot += r
	}
	fmt.Printf("go-bench: tot=%d lo=%d hi=%d mapsz=%d\n", tot, res[0], res[5], len(m))
}
GOEOF

# host build (ground truth)
HOSTOUT=$( cd "$SB/proj" && GOROOT="$GOROOT" GOCACHE="$SB/gocache" GOPATH="$SB/gopath" \
    CGO_ENABLED=0 "$GO" build -o "$SB/bin/prog" prog.go >/dev/null 2>&1 && "$SB/bin/prog" )
[ -n "$HOSTOUT" ] || { echo "SKIP : host go build failed"; exit 2; }
echo "host ground truth: $HOSTOUT"
NREF=$(echo "$HOSTOUT")

run() {  # run <backend> <path> [args...] → stdout (go-* 行), 秒は stderr
    local be=$1; shift; local t0 o; t0=$(date +%s)
    o=$( cd "$SB" && timeout 1200 env HOME=/proj GOROOT=/usr/lib/goroot GOCACHE=/gocache GOPATH=/gopath \
        GOTOOLCHAIN=local CGO_ENABLED=0 EMULIN_NATIVE_POOL_MB=8192 EMULIN_BACKEND=$be \
        java $JOPT -cp "$CP" emulin.Emulin "$SB" "$@" < /dev/null 2>/dev/null )
    printf '%ds' "$(( $(date +%s) - t0 ))" >&2
    printf '%s' "$o"
}

echo "===== issue #221: Go (A) 実行ファイル実行 / (B) go build native ====="
echo "(A) host 製 Go 実行ファイルを emulin で実行:"
so=$( run software /bin/prog 2>/tmp/_bt | grep go-bench ); echo "    software: $so [$(cat /tmp/_bt)]"
no=$( run native   /bin/prog 2>/tmp/_bt | grep go-bench ); echo "    native  : $no [$(cat /tmp/_bt)]"
[ "$so" = "$NREF" ] && [ "$no" = "$NREF" ] && echo "    => native==software==host ✅" || echo "    => 不一致"

# (B) go build を emulin native で。GOROOT (toolchain + std src) を sandbox に置き GOCACHE を
#     host で pre-warm (std lib をコンパイル済 = gcc の crt/headers bundle 相当)、user package の
#     compile+link を emulin native で走らせる。go → compile → link の fork+exec チェーン。
echo "(B) ★ emulin native で go build (toolchain を fork+exec):"
# ★ GOROOT/src は Debian/Ubuntu 版だと ../../share/go-1.x/src への symlink。-rL で symlink を
#   実体化して sandbox を自己完結にする (dangling だと go が std lib 未発見で spin する)。
cp -rL "$GOROOT" "$SB/usr/lib/goroot" 2>/dev/null
( cd "$SB/proj" && GOROOT="$GOROOT" GOCACHE="$SB/gocache" GOPATH="$SB/gopath" CGO_ENABLED=0 \
    "$GO" build -o /dev/null prog.go >/dev/null 2>&1 )  # GOCACHE pre-warm (std lib)
rm -f "$SB/proj/built"
bo=$( run native /usr/lib/goroot/bin/go build -o /proj/built /proj/prog.go 2>/tmp/_bt )
if [ -x "$SB/proj/built" ]; then
    # native でビルドした実行ファイルを native で実行して host と一致するか
    eo=$( run native /proj/built 2>/dev/null | grep go-bench )
    echo "    go build [$(cat /tmp/_bt)] => $( [ -s "$SB/proj/built" ] && echo OK ) / 生成 binary 実行: $eo"
    [ "$eo" = "$NREF" ] && echo "    => emulin が生成した Go binary が host と一致 ✅" || echo "    => 生成 binary の出力不一致"
else
    echo "    go build [$(cat /tmp/_bt)] => ❌ FAIL (binary 未生成)"
    [ -n "${BENCH_GO_VERBOSE:-}" ] && { echo "    --- build log ---"; cat /tmp/_bt; }
fi
rm -f /tmp/_bt
