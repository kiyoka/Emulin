#!/usr/bin/env bash
# native-oracle.sh — issue #221 step 3d-2 / 3d-2c: KVM native backend の equivalence oracle
#
# -nostdlib 静的 ELF を software backend と native (KVM) backend の両方で実行し、stdout が
# byte 一致することを検証する。native backend は #221 の核心: guest を実 vCPU (ring-3) で
# 走らせ syscall だけ emulin の call_amd64 にトラップする。
#
#   - hello64     : write/exit のみ (3d-2、ring-0→3d-2c-1 で ring-3)
#   - argvdump64  : argc/argv/envp を stack から読んで dump (3d-2c-2 の初期 stack 構築検証)
#   - simd64      : SSE (movdqu/paddd) で CR4.OSFXSR を検証 (3d-2c-3)
#   - hello_static64 : ★初の実 glibc 静的 binary (brk/arch_prctl/mprotect 等、3d-2c-4)
#   - ctype/fgetc/varexp/bb_decode/aesni/sse_audit_static64 : static-glibc スイート全体
#     (3d-2c-5)。aesni/sse_audit は実 CPU の AES-NI/SSE が emulin emulation と一致する cross-validation
#
# KVM が無い環境 (/dev/kvm 不可、bare-metal でない CI 等) では SKIP。
# 終了コード: 0=PASS, 1=FAIL, 2=SKIP。
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
PROJECT=$(cd "$ROOT/.." && pwd -P)
CLASSES=$PROJECT/target/classes
NAME=native-oracle

if [ ! -f "$CLASSES/emulin/Emulin.class" ]; then
    echo "SKIP $NAME : Emulin not built"
    exit 2
fi
# KVM 可用性 (read+write)
if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
    echo "SKIP $NAME : /dev/kvm not accessible (KVM 無し / kvm group 未加入)"
    exit 2
fi

# ASM jar (JIT 用、native では不要だが classpath 維持)
ASM=$(find "$HOME/.m2" -name "asm-9.6.jar" 2>/dev/null | head -1)
CP="$CLASSES${ASM:+:$ASM}"

SB=$(mktemp -d -t emulin-native-oracle.XXXXXX)
trap 'rm -rf "$SB"' EXIT
mkdir -p "$SB/bin" "$SB/tmp"   # tmp は mmap_dyn64 (/tmp/mtest.dat を作る file-mmap テスト) 用

JOPT="--enable-native-access=ALL-UNNAMED -XX:-UsePerfData"

# oracle_one <binname> <expect_substr> [guest args...]
#   tests/binaries/bin/<binname> を software と native で実行し byte 一致 + 期待値を検証。
oracle_one() {
    local bin=$1 expect=$2; shift 2
    local src="$ROOT/binaries/bin/$bin"
    if [ ! -f "$src" ]; then
        echo "SKIP $NAME/$bin : not built (run 'make -C tests/binaries')"
        return 2
    fi
    cp "$src" "$SB/bin/$bin"

    local soft soft_rc nat nat_rc
    # stdin は /dev/null で固定 (fgetc_static64 等の stdin 読みを deterministic な EOF に)
    soft=$( cd "$SB" && EMULIN_BACKEND=software java $JOPT -cp "$CP" emulin.Emulin "$SB" "/bin/$bin" "$@" < /dev/null 2>/dev/null )
    soft_rc=$?
    nat=$(  cd "$SB" && EMULIN_BACKEND=native   java $JOPT -cp "$CP" emulin.Emulin "$SB" "/bin/$bin" "$@" < /dev/null 2>/dev/null )
    nat_rc=$?

    if [ "$soft_rc" != 0 ]; then echo "FAIL $NAME/$bin : software rc=$soft_rc"; return 1; fi
    if [ "$nat_rc"  != 0 ]; then echo "FAIL $NAME/$bin : native rc=$nat_rc";   return 1; fi
    if [ "$soft" != "$nat" ]; then
        echo "FAIL $NAME/$bin : native stdout != software stdout"
        echo "--- software ---"; printf '%s\n' "$soft" | head -12
        echo "--- native ---";   printf '%s\n' "$nat"  | head -12
        return 1
    fi
    if ! printf '%s' "$nat" | grep -qF "$expect"; then
        echo "FAIL $NAME/$bin : output に '$expect' 無し"
        return 1
    fi
    echo "  ok $bin : native(KVM,ring3)==software (byte 一致、'$expect')"
    return 0
}

fail=0 ran=0
# hello64: write/exit
oracle_one hello64 "hello world";          r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
# argvdump64: 初期 stack (argc/argv/envp) の検証。引数を渡して argc/argv を確認。
oracle_one argvdump64 "argv[1]=foo" foo bar; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
# simd64: SSE (paddd) の検証。CR4.OSFXSR 未設定だと native で #UD→triple fault。
oracle_one simd64 "simd:42,42";            r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
# hello_static64: ★初の実 glibc 静的 binary。brk/arch_prctl/mprotect/getrandom 等の
#   glibc 起動 syscall + page_offset コピー修正の総合検証。
oracle_one hello_static64 "hello static";  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
# static-glibc スイート (3d-2c-5): #249 の修正で全て native==software。glibc の多様な使い方
#   (ctype 表 / fgetc+stdin / printf / base64 decode / ★AES-NI / ★SSE) を網羅。aesni/sse_audit は
#   実 CPU の AES-NI/SSE が emulin の emulation と byte 一致する cross-validation。
oracle_one ctype_static64     "alnum";       r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one fgetc_static64     "read 0 chars";r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one varexp_repro64     "plain_loop";  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one bb_decode_static64 "chars";       r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one aesni_static64     "state_in";    r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one sse_audit64        "in_a";        r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
# bench64: compute-heavy ループの correctness (small N)。性能測定は bench-native.sh (large N)。
oracle_one bench64            "bench n=10000";r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
# syscall_storm64: go/no-go の syscall-heavy worst case (getpid storm)。correctness 回帰 (small N)。
oracle_one syscall_storm64    "storm n=10000";r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
# mmap64: anonymous mmap (2 ページ確保 + read/write + munmap) の検証 (3d-2c-7)。
oracle_one mmap64             "mmap: MAPZ";  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
# execve (3d-2c-19): fork 無しで自プロセスが /bin/hello64 を execve し置換 = native の execve 経路
#   (kernel.exec→新 native Process→新 guestMem 構築) を fork 抜きで検証。hello64 が先に sandbox へ
#   copy 済 (oracle 冒頭) なので exec 対象は存在する。出力は hello64 の "hello world"。
oracle_one sys_execve_self64  "hello world"; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
# fork (3d-2c-20): native multi-process = git clone の最後の blocker。子は専用 VM + 複製 guestMem を
#   持つ別 process。NativeMemoryBackend.duplicate が page table+data を子プールに verbatim copy する
#   (pool-relative 物理 offset なので別プールで valid)。3 段で検証:
#   - sys_fork64          : fork→子 write→親 wait4 reap (基本: fork 戻り値 0/pid、出力順序)
#   - sys_fork_isolation64: 子の .data 書込みが親に波及しない (★アドレス空間分離 = 別 VM の証明)
#   - sys_fork_exec64     : fork→子 execve(/bin/hello64)→親 wait4 (★git の fork+exec パターン)
oracle_one sys_fork64           "parent_saw_child=1"; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one sys_fork_isolation64 "parent:g=1";         r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one sys_fork_exec64      "parent_after_wait";  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
# signal 配信 (3d-2c-13): syscall 境界で pending signal を guest handler に配信し rt_sigreturn で
#   復帰する。delivery (handler 発火) / regsave (GPR 保存復元) / siginfo (SA_SIGINFO の siginfo/
#   ucontext) / sigmask (block/unblock) / rt_sigaction を網羅。-nostdlib 静的。
oracle_one sys_signal_delivery64 "flag=1";          r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one sys_signal_regsave64  "rax=0";           r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one sys_sa_siginfo64      "ucontext_nonnull=1"; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one sys_sigmask64         "handler sig=10";  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one sys_rt_sigaction64    "ret=0";           r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
# FPU-in-signal (3d-2c-21): handler が XMM を破壊しても被中断側の live XMM が保たれる。native は
#   KVM_GET/SET_FPU で x87/XMM を退避復元、software は sigSavedFrames。両者 xmm_preserved=1 で一致。
oracle_one sys_sig_fpu64         "xmm_preserved=1";  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1

# --- 動的リンク (dynamic glibc) — 3d-2c-10: anonymous mmap の zero-fill 修正で完走 ---
#   ld.so が libc.so.6 を file-backed mmap でロードし、.bss を MAP_ANON|MAP_FIXED で zero 化する
#   経路を検証する (これらは software backend でも回帰スイートで常時 PASS している実 glibc 動的
#   binary)。host の ld.so + libc.so.6 を sandbox に置く (run-test.sh と同じ方式)。host に無い CI
#   環境では動的セクションを丸ごと SKIP (static は既に検証済)。pthread_*_dyn64 は multi-vCPU 未対応
#   のためここには含めない (Phase 3)。
DYN_INTERP=/lib64/ld-linux-x86-64.so.2
DYN_LIBDIR=/lib/x86_64-linux-gnu
if [ -f "$DYN_INTERP" ] && [ -f "$DYN_LIBDIR/libc.so.6" ]; then
    mkdir -p "$SB/lib64" "$SB/lib/x86_64-linux-gnu"
    cp "$DYN_INTERP"             "$SB/lib64/"
    cp "$DYN_LIBDIR/libc.so.6"   "$SB/lib/x86_64-linux-gnu/"

    # oracle_dyn <bin> <expect> [extra_lib...]: extra_lib が host に無ければ SKIP (FAIL でなく)。
    oracle_dyn() {
        local bin=$1 expect=$2; shift 2
        local lib
        for lib in "$@"; do
            if [ ! -f "$DYN_LIBDIR/$lib" ]; then
                echo "SKIP $NAME/$bin : host に $lib 無し"
                return 2
            fi
            cp "$DYN_LIBDIR/$lib" "$SB/lib/x86_64-linux-gnu/"
        done
        oracle_one "$bin" "$expect"
    }

    oracle_dyn hello_dyn64  "hello dynamic";       r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_dyn printf_dyn64 "nan: -nan";           r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_dyn regex_dyn64  "match num='123'";     r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_dyn mmap_dyn64   "mapped: abcdefghij";  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_dyn nested_dyn64 "result=42";           r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_dyn pie_dyn64    "hello pie";           r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_dyn zlib_dyn64   "compress rc=0" libz.so.1;  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_dyn cpp_dyn64    "apple" libm.so.6 libstdc++.so.6 libgcc_s.so.1; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # coreutils 風総合 (3d-2c-12): getcwd/mkdir/getdents64(readdir)/stat/read/unlink/rmdir + malloc/qsort。
    #   既存 _dyn64 が触らない directory 列挙 + stat の実 syscall surface を native で検証。
    oracle_dyn dirlist_dyn64 "entries: a.txt b.txt c.txt"; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # ★ multi-vCPU (pthread): worker を別 vCPU + 別 Java thread で走らせ、共有メモリ上の atomic は
    #   実 CPU が vCPU 間で実行、futex の slow path だけ trap する (3d-2c-11)。pthread_mutex は
    #   4 worker が共有 counter を mutex 下で 1000 回 ++ = race だと <4000、正しければ 4000。
    oracle_dyn pthread_basic_dyn64 "joined value=42" libm.so.6; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_dyn pthread_mutex_dyn64 "counter=4000"   libm.so.6; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # multi-thread signal (3d-2c-13): per-thread mask + pthread_kill。worker vCPU が自分の syscall
    #   境界で signal を配信、main は unblock 後に pending を配信。current_tid の GuestThread 判定
    #   (3d-2c-11) で worker 宛 signal が正しく届く。
    oracle_dyn pthread_sigmask_dyn64 "parent_handler_fired" libm.so.6; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # ★総合 (3d-2c-15): pthread(8 worker) + mutex/futex + signal(SIGUSR1×3) + file I/O を 1 binary で
    #   組合せ、機能間の連携 (worker 並走中の futex 競合、syscall 境界での signal 配信) を検証。
    #   8 worker × 8MB stack = 64MB を要するので lazy mmap pool (512MB) の容量も実証する。
    oracle_dyn integ_dyn64 "counter=8000" libm.so.6; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1

    # --- 実 GNU dynamic binary の native validation (3d-2c-23) ---
    #   host の実 GNU grep/sed/gawk/perl/coreutils を software/native 両 emulin で走らせ byte 一致を検証。
    #   同一 host binary を両 backend で実行するので version 非依存 (native==software が invariant)。
    #   ★grep/gawk は pcre2/glibc 経由で /proc/self/maps を読むので、NativeMemoryBackend.genProcSelfMaps
    #   の native 実装 (3d-2c-23、page table walk) が無いと native で busy-hang していた。実用 interpreter
    #   (perl/gawk) + regex (grep/sed) が native で動く実証。host に対象 binary 無しは個別 SKIP。
    REALDATA="$SB/tmp/real.txt"; printf 'banana\napple\ncherry\napple\nband\n' > "$REALDATA"
    # oracle_real <expect> <hostpath> <args...>  (stdin = REALDATA)
    oracle_real() {
        local expect=$1 host=$2; shift 2
        local realbin; realbin=$(command -v "$host" 2>/dev/null); realbin=$(readlink -f "$realbin" 2>/dev/null)
        [ -n "$realbin" ] && [ -x "$realbin" ] || { echo "  SKIP real $(basename "$host") : host に無し"; return 2; }
        local bp="/usr/bin/$(basename "$host")"; mkdir -p "$SB/usr/bin"; cp "$realbin" "$SB$bp" 2>/dev/null
        ldd "$realbin" 2>/dev/null | grep -oE '/(lib|usr/lib)[^ ]*\.so[^ ]*' | sort -u | while read l; do
            local d="$SB$(dirname "$l")"; mkdir -p "$d"; cp "$l" "$d/" 2>/dev/null; done
        local soft nat sc nc
        soft=$( cd "$SB" && EMULIN_BACKEND=software java $JOPT -cp "$CP" emulin.Emulin "$SB" "$bp" "$@" < "$REALDATA" 2>/dev/null ); sc=$?
        nat=$(  cd "$SB" && EMULIN_BACKEND=native   java $JOPT -cp "$CP" emulin.Emulin "$SB" "$bp" "$@" < "$REALDATA" 2>/dev/null ); nc=$?
        if [ "$sc" != 0 ] || [ "$nc" != 0 ] || [ "$soft" != "$nat" ]; then
            echo "FAIL $NAME/real-$(basename "$host") : sc=$sc nc=$nc native!=software"
            echo "    soft: $(printf '%s' "$soft" | head -3 | tr '\n' '|')"
            echo "    nat : $(printf '%s' "$nat"  | head -3 | tr '\n' '|')"; return 1; fi
        if ! printf '%s' "$nat" | grep -qF "$expect"; then echo "FAIL $NAME/real-$(basename "$host") : '$expect' 無し"; return 1; fi
        echo "  ok real $(basename "$host") $* : native(KVM,ring3)==software ('$expect')"; return 0
    }
    # ★ grep / gawk = genProcSelfMaps の native 実装が要る (pcre2 が /proc/self/maps を読む)。
    oracle_real "2:apple" grep -n apple;                          r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_real "26"      gawk '{s+=length($0)} END{print s}';    r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # sed = stream regex (genProcSelfMaps 非依存だが実 GNU sed の native 実証)。
    oracle_real "BANANA"  sed 's/banana/BANANA/';                 r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # ★ perl = 実用 interpreter (重い動的リンク + 大量 syscall)。算術で version 非依存。
    oracle_real "5050"    perl -e 'my $s=0; $s+=$_ for (1..100); print "$s\n"';  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # 実 GNU coreutils (busybox とは別実装)。sha256sum は固定内容のハッシュで version 非依存。
    oracle_real "28df0aa777108726884173e0b4c6c4fa500068d3e0088a422e7bba873a21fadf" sha256sum;  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # tar (3d-2c-24): 固定 tar を host で作成 (mtime/owner/sort 固定で deterministic) → list / extract を
    #   native で検証。実用 archive ツールの read 経路 (header parse + 抽出) を通す。
    if command -v tar >/dev/null 2>&1; then
        _twd=$(mktemp -d)
        printf 'apple\nbanana\n' > "$_twd/a.txt"; printf 'cherry\n' > "$_twd/b.txt"
        tar -cf "$SB/tmp/test.tar" --mtime='2020-01-01 UTC' --owner=0 --group=0 --sort=name -C "$_twd" a.txt b.txt 2>/dev/null
        rm -rf "$_twd"
        oracle_real "a.txt" tar -tf  /tmp/test.tar;        r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
        oracle_real "apple" tar -xOf /tmp/test.tar a.txt;  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    fi

    # --- python3.12 (実用 interpreter、stdlib バンドル、3d-2c-24) ---
    #   ★ full CPython が native で動く実証 (C 拡張 json/hashlib/re 込み)。stdlib (~42MB、test/idlelib/
    #   tkinter 除外) を sandbox に bundle し PYTHONHOME=/usr。算術/json/hashlib/re で version 非依存。
    #   host に python3.12 + stdlib 無しは SKIP。
    PYBIN=$(command -v python3.12 2>/dev/null)
    if [ -n "$PYBIN" ] && [ -d /usr/lib/python3.12 ]; then
        cp "$(readlink -f "$PYBIN")" "$SB/usr/bin/python3.12" 2>/dev/null
        ldd "$(readlink -f "$PYBIN")" 2>/dev/null | grep -oE '/(lib|usr/lib)[^ ]*\.so[^ ]*' | sort -u | while read l; do
            d="$SB$(dirname "$l")"; mkdir -p "$d"; cp "$l" "$d/" 2>/dev/null; done
        mkdir -p "$SB/usr/lib/python3.12"
        cp -r --no-preserve=mode /usr/lib/python3.12/* "$SB/usr/lib/python3.12/" 2>/dev/null
        rm -rf "$SB/usr/lib/python3.12/test" "$SB/usr/lib/python3.12/idlelib" "$SB/usr/lib/python3.12/tkinter" 2>/dev/null
        # oracle_py <expect> <python-program>
        oracle_py() {
            local expect=$1 prog=$2 soft nat sc nc
            soft=$( cd "$SB" && env HOME=/root PYTHONHOME=/usr PYTHONDONTWRITEBYTECODE=1 EMULIN_BACKEND=software java $JOPT -cp "$CP" emulin.Emulin "$SB" /usr/bin/python3.12 -c "$prog" < /dev/null 2>/dev/null ); sc=$?
            nat=$(  cd "$SB" && env HOME=/root PYTHONHOME=/usr PYTHONDONTWRITEBYTECODE=1 EMULIN_BACKEND=native   java $JOPT -cp "$CP" emulin.Emulin "$SB" /usr/bin/python3.12 -c "$prog" < /dev/null 2>/dev/null ); nc=$?
            if [ "$sc" != 0 ] || [ "$nc" != 0 ] || [ "$soft" != "$nat" ]; then
                echo "FAIL $NAME/python : sc=$sc nc=$nc native!=software"
                echo "    soft: $(printf '%s' "$soft" | head -2 | tr '\n' '|')"
                echo "    nat : $(printf '%s' "$nat"  | head -2 | tr '\n' '|')"; return 1; fi
            if ! printf '%s' "$nat" | grep -qF "$expect"; then echo "FAIL $NAME/python : '$expect' 無し"; return 1; fi
            echo "  ok python (${prog:0:36}…) : native(KVM,ring3)==software ('$expect')"; return 0
        }
        oracle_py "4950" 'print(sum(range(100)))';  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
        oracle_py "75be099094b9a80c64ad2e2b" 'import json,hashlib,re; print(json.dumps({"s":sum(range(10))})); print(hashlib.sha256(b"emulin").hexdigest()[:24]); print(re.findall(r"\d+","a12b345c6789"))';  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    else
        echo "  SKIP $NAME/python : host に python3.12 + stdlib 無し"
    fi
else
    echo "SKIP $NAME : host に ld.so/libc.so.6 無し (動的セクション)"
fi

# --- busybox (実用静的 glibc multi-applet binary、3d-2c-14) ---
#   host の busybox を software/native 両 emulin で走らせ byte 一致を検証する。同一 binary を両
#   backend で実行するので busybox version 非依存 (native==software が invariant)。88 applet を
#   持つ実 glibc 静的 binary (~2MB) が native で動く実証 = テスト用の小さな自作 binary でなく実用
#   binary。host に静的 busybox が無い CI は SKIP。
BB=""
for cand in /usr/bin/busybox /bin/busybox "$ROOT/sandbox/bin/busybox"; do
    [ -x "$cand" ] && { BB="$cand"; break; }
done
if [ -n "$BB" ] && file "$BB" 2>/dev/null | grep -q "statically linked"; then
    cp "$BB" "$SB/bin/busybox"
    printf 'banana\napple\ncherry\n' > "$SB/tmp/bb.txt"
    printf 'banana 3\napple 1\ncherry 7\n' > "$SB/tmp/bb2.txt"   # awk 用 (数値列、sum=11)
    # oracle_bb <expect_substr> <busybox-args...>: software/native で実行し byte 一致 + expect 検証。
    oracle_bb() {
        local expect=$1; shift
        local soft nat sc nc
        soft=$( cd "$SB" && EMULIN_BACKEND=software java $JOPT -cp "$CP" emulin.Emulin "$SB" /bin/busybox "$@" < /dev/null 2>/dev/null ); sc=$?
        nat=$(  cd "$SB" && EMULIN_BACKEND=native   java $JOPT -cp "$CP" emulin.Emulin "$SB" /bin/busybox "$@" < /dev/null 2>/dev/null ); nc=$?
        if [ "$sc" != 0 ] || [ "$nc" != 0 ] || [ "$soft" != "$nat" ]; then
            echo "FAIL $NAME/busybox-$1 : soft_rc=$sc nat_rc=$nc native!=software"; return 1
        fi
        if ! printf '%s' "$nat" | grep -qF "$expect"; then echo "FAIL $NAME/busybox-$1 : '$expect' 無し"; return 1; fi
        echo "  ok busybox $* : native(KVM,ring3)==software ('$expect')"; return 0
    }
    oracle_bb "hello bb"  echo hello bb;            r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_bb "42"        expr 6 \* 7;              r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_bb "apple"     sort /tmp/bb.txt;         r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_bb "banana"    grep an /tmp/bb.txt;      r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # sha256sum = file 内容のハッシュ (banana\napple\ncherry\n) なので busybox version 非依存に決定的。
    oracle_bb "64112a2c204881f4aac7da9ffd84a2b0412a193ae9b3773cbab04ff947d2b92c" sha256sum /tmp/bb.txt; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_bb "62 61 6e"  od -An -tx1 /tmp/bb.txt;  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # awk/sed = busybox 内の本格インタプリタ/正規表現エンジン。複雑な単一プロセス applet の検証。
    oracle_bb "sum=11"    awk '{s+=$2} END{print "sum="s}' /tmp/bb2.txt; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_bb "APPLE"     sed 's/apple/APPLE/g' /tmp/bb.txt;            r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    echo "SKIP $NAME : host busybox (static) 無し (busybox セクション)"
fi

if [ "$fail" = 1 ]; then echo "FAIL $NAME"; exit 1; fi
if [ "$ran"  = 0 ]; then echo "SKIP $NAME : 対象 binary 未ビルド"; exit 2; fi
echo "PASS $NAME : static (14 + 6 signal[FPU-in-signal 含む]、execve + fork×3 含む) + dynamic glibc (hello/printf/regex/mmap/nested/pie/zlib/cpp/dirlist + pthread basic/mutex/sigmask + integ _dyn64) + 実 GNU dynamic (grep/gawk/sed/perl/sha256sum/tar) + ★python3.12 (json/hashlib/re) + busybox (8 applet) native(KVM,ring3)==software"
exit 0
