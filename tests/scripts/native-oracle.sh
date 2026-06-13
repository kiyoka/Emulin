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
# pushf64: PUSHFQ/POPFQ (0x9C/0x9D、3d-2c-32)。node/V8 が使用、software は旧 unknown opcode 0x9d。
#   RFLAGS の architectural layout (整数 ALU 由来は CF/ZF/SF/OF、PF は ucomisd 経路) を実 CPU と照合。
oracle_one pushf64 "popf:11000,00110";       r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
# mmap64: anonymous mmap (2 ページ確保 + read/write + munmap) の検証 (3d-2c-7)。
oracle_one mmap64             "mmap: MAPZ";  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
# rcr64: RCL/RCR (Grp2 /2 /3 = rotate-through-carry、3d-2c-37)。Go runtime の div-by-const magic
#   が rcr を使うが interpreter 未実装で停止していた回帰固定。64/32-bit + by-1/by-CL を実 CPU と照合。
oracle_one rcr64 "rcrcl:0xe00000000000000f,1"; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
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
# sa_mask (issue #309): handler 実行中に sa_mask で block され pending になった signal が、rt_sigreturn
#   の mask 復元「直後」(被中断 context が次の命令を実行する前) に配信されることを検証。旧 native は
#   次の syscall 完了後まで配信が遅れ、出力順が software とズレていた (in_usr2 が end の後)。
oracle_one sys_sa_mask64         "in_usr2";         r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one sys_rt_sigaction64    "ret=0";           r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
# FPU-in-signal (3d-2c-21): handler が XMM を破壊しても被中断側の live XMM が保たれる。native は
#   KVM_GET/SET_FPU で x87/XMM を退避復元、software は sigSavedFrames。両者 xmm_preserved=1 で一致。
oracle_one sys_sig_fpu64         "xmm_preserved=1";  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
# pwrite64(#18)/pread64(#17)/fdatasync(#75) の positioned I/O + durability (3d-2c-43): sqlite (cov13) が
#   DB page の positioned write + commit 同期に使う。pwrite は file position 不変、fdatasync は成功(0)。
oracle_one sys_pwrite64          "content=AAAXYZAAAAZZ"; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1

# --- 対話 (pty / 制御端末 / SIGWINCH) hermetic 検証 (3d-2c-44) ---
#   ssh/emacs/vim/bash の対話に必須の経路を -nostdlib static binary で hermetic に検証する。各 test は
#   内部で /dev/ptmx (master) を open → fork → child が /dev/pts/N (slave) を扱う構成なので、real TTY
#   無し (stdin=/dev/null) でも完走する。native は fork (別 GPA pool) + pty + signal 配信 を実 vCPU で
#   実行し、software (PtyManager) と byte 一致する。これで「対話 binary が native で動く」ことを回帰化。
#   ★sys_pty_winch_64 は当初 native のみ FAIL したが、原因は test 側の aggregate-init({0}) が生成する
#     movaps (16-byte aligned store) を -nostdlib _start の非 16-align RSP 上で実行し実 CPU が #GP する
#     test artifact (software は alignment 非強制で偶然 PASS)。明示 field 代入 (movq) に修正済。
oracle_one sys_pty_64            "s2m=Stom?";              r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one sys_devtty_ctty_64    "tty_read=FROM-MASTER";   r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one sys_devtty_input_64   "master_recv=GOT:abc";    r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one sys_devtty_close_64   "after_devtty_close=ALIVE"; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one sys_pty_fionread_64   "master_side=2";          r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one sys_pty_winsize_64    "master_get=50x160";      r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one sys_pty_winch_64      "winch_delivered=1";      r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
oracle_one sys_pty_onlcr_64      "multi_len=6";            r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1

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
    # ★ 実行中スレッドへの async signal 配信 (3d-2c-39): worker が syscall-free tight-loop で spin 中に
    #   main が SIGUSR1 を pthread_kill。native は host SIG_KICK で KVM_RUN を EINTR 脱出させ被中断点で
    #   handler を起動 (#258 の syscall 境界配信制約を撤廃)。修正前は worker が永久 spin して hang した。
    oracle_dyn async_signal_dyn64 "async: delivered=1 onworker=1"; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # ★ SA_ONSTACK + sigaltstack(2) の代替スタック配信 (3d-2c-41): handler を登録済 alt stack 上で
    #   起動することを検証する。旧実装は sigaltstack を no-op stub にし SA_ONSTACK を無視して handler を
    #   割込み点 stack で走らせていた → Go runtime が adjustSignalStack→needm→lockextra 無限 spin
    #   (go env / go build の netpoller hang)。修正後は native==software で handler が alt stack 上で走る。
    oracle_dyn sigaltstack_dyn64 "sigaltstack: query=1 handled=1 onalt=1"; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # ★ 並列 open/read/close の fd table 競合 (3d-2c-42): 6 thread が共有ファイル群を激しく
    #   open→read→verify→close。旧 FileAccess は search_empty_fd→addElement/setElementAt が
    #   非アトミックで、並列 open が同スロット二重確保 / index ずれ → read が EBADF/誤内容
    #   (go build の並列 goroutine が "bad file descriptor" で落ちる真因)。fdLock で直列化して解消。
    oracle_dyn concurrent_fd_dyn64 "concurrent-fd: open_err=0 ebadf=0 mismatch=0"; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # ★ fstatat 空 path の errno (3d-2c-42): 旧実装は空 path を無条件に fstat(dirfd) 扱いにして
    #   Go の os.Stat("")=fstatat(AT_FDCWD,"",0) を EBADF にしていた。Linux は空 path+AT_EMPTY_PATH
    #   無し→ENOENT。Go は ENOENT を os.IsNotExist で握るので、EBADF だと go build が落ちる。
    oracle_dyn statat_empty_dyn64 "statat-empty: noflag_errno=2 emptypath_ok=1"; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1

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
    # cov7 (3d-2c-29): ★ grep -P = PCRE2 JIT。PCRE2 は regex を実行時に machine code へ JIT compile して
    #   実行する → native では guest が生成した executable code を実 vCPU で走らせる (guest runtime code-gen +
    #   executable page。EFER.NXE off で全 page 実行可)。backref/lookbehind は PCRE2 専用機能で JIT 経路を踏む。
    oracle_real "apple" grep -P '(\w)\1';                         r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_real "nana"  grep -oP '(?<=ba)nana';                   r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_real "26"      gawk '{s+=length($0)} END{print s}';    r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # sed = stream regex (genProcSelfMaps 非依存だが実 GNU sed の native 実証)。
    oracle_real "BANANA"  sed 's/banana/BANANA/';                 r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # ★ perl = 実用 interpreter (重い動的リンク + 大量 syscall)。算術で version 非依存。
    oracle_real "5050"    perl -e 'my $s=0; $s+=$_ for (1..100); print "$s\n"';  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # cov5: ★ managed runtime (Perl) から native fork+waitpid。git/make/shell の fork とは別に「interpreter
    #   runtime が fork する」経路 (child は Perl interpreter 内で継続→exit、parent が wait で reap) を検証。
    oracle_real "perl-fork:7" perl -e 'my $p=fork(); if(!defined $p){die "nofork"} elsif($p==0){exit 7} else { waitpid($p,0); printf "perl-fork:%d\n", $?>>8 }';  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # cov7 (3d-2c-29): ★ emacs --version = 実用エディタ (host の emacs-gtk、108 lib 動的リンク) の startup。
    #   --version は GUI/init 前に version を出して exit するが、ld.so は 108 個の DT_NEEDED lib を全 map する
    #   (GTK/X11/cairo/pango 等 + 各 ELF constructor)。重量級 binary の多 lib 動的リンク startup を native で
    #   検証。version 非依存に "GNU Emacs" を expect。実測 native==software (native 1s / software 8s)。
    oracle_real "GNU Emacs" emacs --version;  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # cov7 (3d-2c-29): ★★ claude (Claude Code CLI = bun/JSC、247MB ELF + JS JIT) の --version。
    #   JSC は起動時に >2GB の sparse な mmap (gigacage/JS heap) を予約するので、native の eager-backed
    #   pool は既定 512MB では枯渇する → EMULIN_NATIVE_POOL_MB=8192 (8GB pool + 64MB PT 領域) で解消。
    #   ★ software backend は 247MB JSC の emulation が重く ~78s かかるため、ここでは native(KVM) 出力を
    #   host の claude --version と直接比較する (native==host。software も byte 一致するが遅いので毎回は
    #   回さない)。native は JSC の JIT machine code を実 CPU で実行するので ~3s = software の 26x 高速
    #   (= #221 の compute-bound native 優位の好例)。version 非依存に host の実 version + "Claude Code" で照合。
    if command -v claude >/dev/null 2>&1; then
        _chost=$( claude --version 2>/dev/null )
        # native==host 比較なので emulin 自身の起動メッセージで stdout を汚さない: emulin.cnf を用意する
        #   (oracle の他 test は native==software で対称なので不要だが、host 比較は非対称)。
        mkdir -p "$SB/etc"; : > "$SB/etc/emulin.cnf"
        _cbin=$( readlink -f "$(command -v claude)" ); mkdir -p "$SB/usr/bin"; cp "$_cbin" "$SB/usr/bin/claude" 2>/dev/null
        ldd "$_cbin" 2>/dev/null | grep -oE '/(lib|usr/lib)[^ ]*\.so[^ ]*' | sort -u | while read l; do d="$SB$(dirname "$l")"; mkdir -p "$d"; cp "$l" "$d/" 2>/dev/null; done
        _cnat=$( cd "$SB" && env HOME=/root EMULIN_NATIVE_POOL_MB=8192 EMULIN_BACKEND=native java $JOPT -cp "$CP" emulin.Emulin "$SB" /usr/bin/claude --version < /dev/null 2>/dev/null )
        if [ -n "$_chost" ] && [ "$_cnat" = "$_chost" ] && printf '%s' "$_cnat" | grep -qF "Claude Code"; then
            echo "  ok claude --version : native(KVM,ring3,pool=8G)==host ('$_chost'、JSC JIT を実 CPU 実行で高速)"; ran=1
        else
            echo "FAIL $NAME/claude : native!=host [host='$_chost'] [nat='$(printf '%s' "$_cnat"|head -1)']"; fail=1
        fi
    else
        echo "  SKIP $NAME/claude : host に claude 無し"
    fi
    # cov8 (3d-2c-30): ★★ gcc で C をコンパイル+実行 = 実 toolchain チェーン。gcc driver が cc1(C→.s)/
    #   as(.s→.o)/collect2→ld(.o→PIE) を fork+exec する multi-process tree + 生成 PIE executable を libc に
    #   対して実行 (sh -c で 1 process に)。実開発ツールチェーンが native で動く実証。cc1(30MB)+crt+libc+
    #   headers(/usr/include) を bundle。default pool で動作。native==software ("gcc-sum=5050"、native ~4s /
    #   software ~17s = cc1/as/ld を実 CPU 実行で ~4x 高速)。gcc+dash 在host時のみ (KVM 無 CI は oracle 自体 SKIP)。
    if command -v gcc >/dev/null 2>&1 && command -v dash >/dev/null 2>&1; then
        _gv=$( gcc -dumpversion 2>/dev/null ); _gt=x86_64-linux-gnu
        _gcpd(){ [ -d "$1" ] && { mkdir -p "$SB$1"; cp -rL --no-preserve=mode "$1"/. "$SB$1/" 2>/dev/null; }; }
        _gcpb(){ local r; r=$( readlink -f "$1" ); [ -n "$r" ] && { mkdir -p "$SB$(dirname "$2")"; cp "$r" "$SB$2" 2>/dev/null; ldd "$r" 2>/dev/null|grep -oE '/(lib|usr/lib)[^ ]*\.so[^ ]*'|sort -u|while read l; do mkdir -p "$SB$(dirname "$l")"; cp "$l" "$SB$(dirname "$l")/" 2>/dev/null; done; }; }
        _gcpb /usr/bin/gcc /usr/bin/gcc
        _gcpb "$( gcc -print-prog-name=cc1 )"      "/usr/libexec/gcc/$_gt/$_gv/cc1"
        _gcpb "$( gcc -print-prog-name=collect2 )" "/usr/libexec/gcc/$_gt/$_gv/collect2"
        cp "/usr/libexec/gcc/$_gt/$_gv/"lto-wrapper "/usr/libexec/gcc/$_gt/$_gv/"liblto_plugin.so "$SB/usr/libexec/gcc/$_gt/$_gv/" 2>/dev/null
        _gcpb /usr/bin/x86_64-linux-gnu-as /usr/bin/as; _gcpb /usr/bin/x86_64-linux-gnu-ld.bfd /usr/bin/ld
        _gcpd "/usr/lib/gcc/$_gt/$_gv"
        for _gb in cc1 collect2; do ldd "/usr/libexec/gcc/$_gt/$_gv/$_gb" 2>/dev/null|grep -oE '/(lib|usr/lib)[^ ]*\.so[^ ]*'|sort -u|while read l; do mkdir -p "$SB$(dirname "$l")"; cp "$l" "$SB$(dirname "$l")/" 2>/dev/null; done; done
        mkdir -p "$SB/usr/lib/$_gt"; for _gf in Scrt1.o crti.o crtn.o libc.so libc.so.6 libc_nonshared.a libm.so libm.so.6 libgcc_s.so.1; do cp "/usr/lib/$_gt/$_gf" "$SB/usr/lib/$_gt/" 2>/dev/null; done
        _gcpd /usr/include
        _gcpb "$( command -v dash )" /bin/sh
        printf '#include <stdio.h>\nint main(void){ long s=0; for(int i=1;i<=100;i++) s+=i; printf("gcc-sum=%%ld\\n", s); return 0; }\n' > "$SB/tmp/hello.c"
        _gsoft=$( cd "$SB" && env HOME=/root PATH=/usr/bin:/bin EMULIN_BACKEND=software java $JOPT -cp "$CP" emulin.Emulin "$SB" /bin/sh -c 'gcc -O2 /tmp/hello.c -o /tmp/hbin && /tmp/hbin' < /dev/null 2>/dev/null )
        _gnat=$(  cd "$SB" && env HOME=/root PATH=/usr/bin:/bin EMULIN_BACKEND=native   java $JOPT -cp "$CP" emulin.Emulin "$SB" /bin/sh -c 'gcc -O2 /tmp/hello.c -o /tmp/hbinN && /tmp/hbinN' < /dev/null 2>/dev/null )
        if [ "$_gsoft" = "$_gnat" ] && printf '%s' "$_gnat" | grep -qF "gcc-sum=5050"; then
            echo "  ok gcc compile+run : native(KVM,ring3)==software ('gcc-sum=5050'、gcc→cc1→as→ld の multi-process + 生成 PIE 実行、native 実 CPU で ~4x 高速)"; ran=1
        else
            echo "FAIL $NAME/gcc : native!=software [soft='$(printf '%s' "$_gsoft"|tr '\n' ' ')'] [nat='$(printf '%s' "$_gnat"|tr '\n' ' ')']"; fail=1
        fi
    else
        echo "  SKIP $NAME/gcc : host に gcc/dash 無し"
    fi
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

    # --- 実 GNU テキスト/演算/ビルドツール (cov3, 3d-2c-25) ---
    #   make/xargs = fork+exec を git とは別 workload で stress (make は recipe を /bin/sh + 直接 exec /bin/echo に、
    #   xargs は引数を batch して /bin/echo に exec)。bc = 任意精度演算インタプリタ。find = ディレクトリ走査
    #   (getdents64 + stat + path matching)。diff = 差分アルゴリズム。同一 host binary を両 backend で実行する
    #   ので version 非依存 (native==software が invariant)。bc -q / 固定入力 / 固定ツリーで deterministic。
    #   make/xargs は子 binary (/bin/sh=dash, /bin/echo) も bundle する (recipe / exec 先)。
    #   oracle_cov3 は exit code も native==software で検証する (diff/cmp の非ゼロ rc を許容、両者一致が invariant)。
    _cpbin() {  # _cpbin <hostpath> <destpath>: binary + ldd 依存 .so を sandbox に bundle
        local r; r=$(readlink -f "$1"); [ -n "$r" ] || return; mkdir -p "$SB$(dirname "$2")"; cp "$r" "$SB$2" 2>/dev/null
        ldd "$r" 2>/dev/null | grep -oE '/(lib|usr/lib)[^ ]*\.so[^ ]*' | sort -u | while read l; do
            mkdir -p "$SB$(dirname "$l")"; cp "$l" "$SB$(dirname "$l")/" 2>/dev/null; done
    }
    COV3IN=/dev/null
    oracle_cov3() {  # oracle_cov3 <expect> <host-cmd> <args...>  (stdin = $COV3IN、rc は soft==nat を検証)
        local expect=$1 host=$2; shift 2
        local realbin; realbin=$(command -v "$host" 2>/dev/null); realbin=$(readlink -f "$realbin" 2>/dev/null)
        [ -n "$realbin" ] && [ -x "$realbin" ] || { echo "  SKIP cov3 $host : host に無し"; return 2; }
        _cpbin "$realbin" "/usr/bin/$(basename "$host")"
        local bp="/usr/bin/$(basename "$host")" soft nat sc nc
        soft=$( cd "$SB" && EMULIN_BACKEND=software java $JOPT -cp "$CP" emulin.Emulin "$SB" "$bp" "$@" < "$COV3IN" 2>/dev/null ); sc=$?
        nat=$(  cd "$SB" && EMULIN_BACKEND=native   java $JOPT -cp "$CP" emulin.Emulin "$SB" "$bp" "$@" < "$COV3IN" 2>/dev/null ); nc=$?
        if [ "$sc" != "$nc" ] || [ "$soft" != "$nat" ]; then
            echo "FAIL $NAME/cov3-$host : sc=$sc nc=$nc native!=software"
            echo "    soft: $(printf '%s' "$soft" | head -3 | tr '\n' '|')"
            echo "    nat : $(printf '%s' "$nat"  | head -3 | tr '\n' '|')"; return 1; fi
        if ! printf '%s' "$nat" | grep -qF "$expect"; then echo "FAIL $NAME/cov3-$host : '$expect' 無し (両 backend 失敗?)"; return 1; fi
        echo "  ok cov3 $host $* : native(KVM,ring3)==software (rc=$nc, '$expect')"; return 0
    }
    # 子 binary + データ
    _sh=$(command -v dash || command -v sh); _cpbin "$_sh" /bin/sh; _cpbin /bin/echo /bin/echo
    printf '2^64\n12345*6789\n' > "$SB/tmp/bc.in"
    printf 'a b c d e\n'        > "$SB/tmp/x.in"
    printf 'all:\n\t@echo built-by-make\n\t@echo line2\n' > "$SB/tmp/Makefile"
    mkdir -p "$SB/tmp/ftree/sub"; echo a > "$SB/tmp/ftree/a.txt"; echo b > "$SB/tmp/ftree/sub/b.txt"; echo c > "$SB/tmp/ftree/c.log"
    printf 'banana\napple\ncherry\n' > "$SB/tmp/d1.txt"; printf 'banana\nALMOND\ncherry\n' > "$SB/tmp/d2.txt"
    # ★ make = recipe (@echo) を fork+exec (git とは別 fork workload)。bc = 任意精度 (2^64)。
    COV3IN=/dev/null;          oracle_cov3 "built-by-make"          make  -s -f /tmp/Makefile;  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    COV3IN="$SB/tmp/x.in";     oracle_cov3 "a b"                    xargs -n2 /bin/echo;        r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    COV3IN="$SB/tmp/bc.in";    oracle_cov3 "18446744073709551616"  bc    -q;                    r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    COV3IN=/dev/null;          oracle_cov3 "ftree/a.txt"           find  /tmp/ftree -type f;    r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    COV3IN=/dev/null;          oracle_cov3 "ALMOND"                diff  /tmp/d1.txt /tmp/d2.txt; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1

    # --- 多プロセス pipeline / 圧縮 / crypto / compute / archive (cov4, 3d-2c-26) ---
    #   実 host binary を software/native 両 backend で実行し stdout byte + exit code 一致を検証 (同一 host binary
    #   両 backend=version 非依存 invariant)。triage workflow で 43 候補を determinism + x87-long-double-safe で
    #   adversarial verify し、カテゴリ多様性で 12 件に curate (全件 serial KVM scout で native==software 確認済)。
    #   ★A=多プロセス shell pipeline (fork+exec+pipe+wait4 を git clone とは別の hermetic workload で stress、
    #     oracle 初の shell pipeline カバレッジ)。dash 4 段 (cat|tr|rev|tac) + bash for-loop|tac (初の bash)。
    #   B=圧縮 roundtrip (bzip2 BWT+MTF+Huffman / xz LZMA2+range coder = zlib deflate と別アルゴリズム族、
    #     pipe で原文復元)。C=crypto (openssl 実 AES-NI[EVP] / cksum CRC[PCLMULQDQ] / sha512 64-bit lane =
    #     native が実 silicon crypto を実行し software emulation と byte 一致=crypto path の cross-validation)。
    #   D=compute (factor GMP 素因数分解 22 桁 / bc -l 超越関数 π を 50 桁 任意精度)。E=comm (sorted set 交差)。
    #   F=patch (unified diff 適用) / cpio (newc archive roundtrip、tar と別 layout)。
    #   pipeline の子 binary も bundle。oracle_cov4 は exit code も native==software 検証 (cov3 同様 sc==nc)。
    _cpbin /usr/bin/dash /usr/bin/dash; _cpbin /usr/bin/dash /bin/sh
    _cpbin /usr/bin/bash /usr/bin/bash
    for _c in cat tr rev tac comm patch cksum sha512sum factor bc cpio openssl; do _cpbin "/usr/bin/$_c" "/usr/bin/$_c"; done
    _cpbin /bin/bzip2 /bin/bzip2; _cpbin /bin/xz /bin/xz
    [ -f /usr/lib/ssl/openssl.cnf ] && { mkdir -p "$SB/usr/lib/ssl"; cp /usr/lib/ssl/openssl.cnf "$SB/usr/lib/ssl/" 2>/dev/null; }
    # setup files
    printf 'EMULIN-ROUNDTRIP-MARKER\nThe quick brown fox jumps over the lazy dog. 0123456789\nThe quick brown fox jumps over the lazy dog. 0123456789\nPack my box with five dozen liquor jugs. ABCDEFGHIJKLMNOPQRSTUVWXYZ\nLorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod.\nThe quick brown fox jumps over the lazy dog. 0123456789\nEMULIN-ROUNDTRIP-MARKER\n' > "$SB/tmp/rt_in.txt"
    printf 'apple\nbanana\ncherry\n' > "$SB/tmp/comm_a.txt"; printf 'apple\ncherry\ndate\n' > "$SB/tmp/comm_b.txt"
    printf 'one\ntwo\nthree\nfour\nfive\n' > "$SB/tmp/orig.txt"
    printf -- '--- orig.txt\n+++ new.txt\n@@ -1,5 +1,6 @@\n one\n-two\n+TWO\n three\n four\n five\n+six\n' > "$SB/tmp/fixu.patch"
    printf 'alpha line one\nalpha line two\nalpha line three\n' > "$SB/tmp/cpa.txt"
    printf 'beta first\nbeta second\n' > "$SB/tmp/cpb.txt"; printf 'gamma only line\n' > "$SB/tmp/cpc.txt"
    # oracle_cov4 <label> <reqs(空白区切り host cmd)> <expect> <exp_rc> <stdin:-|\b付きtext> -- argv...
    oracle_cov4() {
        local label=$1 reqs=$2 expect=$3 erc=$4 sin=$5; shift 5; shift   # 末尾 shift で '--' を捨てる
        local q; for q in $reqs; do command -v "$q" >/dev/null 2>&1 || { echo "  SKIP cov4 $label : host に $q 無し"; return 2; }; done
        local rin=/dev/null; [ "$sin" != "-" ] && { rin="$SB/tmp/.cin.$label"; printf '%b' "$sin" > "$rin"; }
        local soft nat sc nc
        soft=$( cd "$SB" && env HOME=/root EMULIN_BACKEND=software java $JOPT -cp "$CP" emulin.Emulin "$SB" "$@" < "$rin" 2>/dev/null ); sc=$?
        nat=$(  cd "$SB" && env HOME=/root EMULIN_BACKEND=native   java $JOPT -cp "$CP" emulin.Emulin "$SB" "$@" < "$rin" 2>/dev/null ); nc=$?
        if [ "$sc" != "$nc" ] || [ "$soft" != "$nat" ]; then
            echo "FAIL $NAME/cov4-$label : sc=$sc nc=$nc native!=software"
            echo "    soft: $(printf '%s' "$soft" | head -3 | tr '\n' '|')"
            echo "    nat : $(printf '%s' "$nat"  | head -3 | tr '\n' '|')"; return 1; fi
        if [ "$nc" != "$erc" ]; then echo "FAIL $NAME/cov4-$label : rc=$nc want=$erc"; return 1; fi
        if ! printf '%s' "$nat" | grep -qF "$expect"; then echo "FAIL $NAME/cov4-$label : '$expect' 無し (両 backend 失敗?)"; return 1; fi
        echo "  ok cov4 $label : native(KVM,ring3)==software (rc=$nc, '$expect')"; return 0
    }
    # ★A 多プロセス pipeline (fork+exec+pipe+wait4)
    oracle_cov4 A-dash-pipe "dash cat tr rev tac" "AMMAG" 0 - -- /usr/bin/dash -c 'printf "alpha\nbeta\ngamma\n" | /usr/bin/cat | /usr/bin/tr a-z A-Z | /usr/bin/rev | /usr/bin/tac'; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_cov4 A-bash-loop "bash tac" "line 3" 0 - -- /usr/bin/bash -c 'for i in 1 2 3; do echo "line $i"; done | /usr/bin/tac'; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # B 圧縮 roundtrip
    oracle_cov4 B-bzip2 "dash bzip2" "EMULIN-ROUNDTRIP-MARKER" 0 - -- /bin/sh -c '/bin/bzip2 -c /tmp/rt_in.txt | /bin/bzip2 -dc'; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_cov4 B-xz "dash xz" "EMULIN-ROUNDTRIP-MARKER" 0 - -- /bin/sh -c '/bin/xz -c /tmp/rt_in.txt | /bin/xz -dc'; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # C crypto (native 実 silicon AES-NI / CRC / SHA512 を software emulation と byte 一致検証)
    oracle_cov4 C-openssl "openssl" "2XTaDMkb2GHzjYom2fhTmxyFnFyEEI3A9kr3pWG7XIg=" 0 "emulin native oracle\n" -- /usr/bin/openssl enc -aes-256-cbc -nosalt -K 000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f -iv 000102030405060708090a0b0c0d0e0f -base64; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_cov4 C-cksum "cksum" "2474615839 20" 0 "banana\napple\ncherry\n" -- /usr/bin/cksum; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_cov4 C-sha512 "sha512sum" "5182dffd30dad9f07c09fdcc93301fe34e51b5d806e9029e6bf01fce6aa6e699" 0 "emulin\n" -- /usr/bin/sha512sum; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # D compute
    oracle_cov4 D-factor "factor" "9999999999999999999999: 3 3 11 11 23 4093 8779 21649 513239" 0 - -- /usr/bin/factor 600851475143 1234567890123456789 9999999999999999999999; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_cov4 D-bc-l "bc" "3.141592653589793238462643383279502884197169399375" 0 "scale=50\n4*a(1)\ne(1)\nl(2)\nquit\n" -- /usr/bin/bc -lq; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # E comm / F patch / cpio
    oracle_cov4 E-comm "comm" "apple" 0 - -- /usr/bin/comm -12 /tmp/comm_a.txt /tmp/comm_b.txt; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_cov4 F-patch "patch" "TWO" 0 - -- /usr/bin/patch -s -o - /tmp/orig.txt /tmp/fixu.patch; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_cov4 F-cpio "dash cpio" "cpb.txt" 0 - -- /bin/sh -c 'cd /tmp && printf "%s\n" cpa.txt cpb.txt cpc.txt | /usr/bin/cpio -o -H newc 2>/dev/null | /usr/bin/cpio -t 2>/dev/null'; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1

    # --- git local ops (object DB) / b2sum / m4 (cov6, 3d-2c-28) ---
    #   ★ git local ops = git clone (network/TLS/pack 受信) とは別 code path = local object DB
    #     (zlib inflate + sha1 + commit/tree/blob parse) + diff アルゴリズム。固定 date/author で
    #     deterministic な repo を host で構築し sandbox に置く (commit SHA も content hash なので version
    #     非依存=同一 host binary 両 backend で invariant)。b2sum=BLAKE2b (SHA/CRC と別 hash 族)。
    #     m4=マクロ処理系 (新 tool 種別)。oracle_cov4 を再利用 (reqs gate で host に無ければ SKIP)。
    _cpbin /usr/bin/git /usr/bin/git; _cpbin /usr/bin/b2sum /usr/bin/b2sum; _cpbin /usr/bin/m4 /usr/bin/m4
    if command -v git >/dev/null 2>&1; then
        ( cd "$SB/tmp" && rm -rf repo && mkdir repo && cd repo && git init -q \
          && git config user.name T && git config user.email t@e \
          && printf 'one\ntwo\n' > f.txt && git add f.txt \
          && GIT_AUTHOR_DATE='2020-01-01T00:00:00 +0000' GIT_COMMITTER_DATE='2020-01-01T00:00:00 +0000' git commit -q -m first \
          && printf 'one\nTWO\nthree\n' > f.txt && git add f.txt \
          && GIT_AUTHOR_DATE='2020-01-02T00:00:00 +0000' GIT_COMMITTER_DATE='2020-01-02T00:00:00 +0000' git commit -q -m second ) >/dev/null 2>&1
    fi
    printf 'BLAKE2-INPUT-emulin\n0123456789\n'      > "$SB/tmp/h.in"
    printf 'define(`G'\'',`hello-m4'\'')dnl\nG world\n' > "$SB/tmp/m.in"
    # git: log (commit 走査+object DB) / cat-file (blob inflate) / diff (差分アルゴリズム)。safe.directory=* で
    #   ownership check を無効化、--no-color/--no-pager で出力を deterministic に。
    oracle_cov4 git-log     "git" "second"        0 - -- /usr/bin/git -c 'safe.directory=*' -C /tmp/repo log --format=%s; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_cov4 git-catfile "git" "TWO"           0 - -- /usr/bin/git -c 'safe.directory=*' -C /tmp/repo cat-file -p HEAD:f.txt; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_cov4 git-diff    "git" "+TWO"          0 - -- /usr/bin/git -c 'safe.directory=*' -C /tmp/repo --no-pager diff --no-color HEAD~1 HEAD; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    # b2sum (BLAKE2b、固定 file 内容) / m4 (マクロ展開)
    oracle_cov4 b2sum "b2sum" "bdd16e8ede8c2710" 0 - -- /usr/bin/b2sum /tmp/h.in; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    oracle_cov4 m4    "m4"    "hello-m4 world"   0 - -- /usr/bin/m4 /tmp/m.in; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1

    # --- 公開鍵暗号 (RSA sign+verify / ECDSA verify) + XML/libxml2 (xmllint) (cov9, 3d-2c-31) ---
    #   ★ 公開鍵暗号 = cov4 の symmetric crypto (AES-NI/PCLMULQDQ/SHA) を asymmetric へ拡張する crypto
    #     cross-validation。native は実 silicon で bignum modular-exponentiation (RSA) / 楕円曲線点演算
    #     (ECDSA P-256) を実 CPU 実行し software emulation と byte 一致。RSA は sign(秘密鍵 CRT modexp)+
    #     verify(公開鍵 modexp) を 1 process (sh -c) で roundtrip = 署名/検証 両方向の bignum を emulin 上で
    #     駆動。PKCS#1 v1.5 は決定的 (padding に RNG 不要) なので native==software が成立。ECDSA sign は
    #     nonce 乱数を使い非決定的なので host で事前署名し、emulin 上は verify (点演算) のみ実行する。鍵は
    #     setup 時に host openssl で生成 (RSA fresh key は run 毎に変わるが、同一 run 内で両 backend が同鍵を
    #     使うので native==software 不変、期待値 "Verified OK" も鍵非依存)。openssl は cov4 で実証済 binary。
    #   ★ xmllint = libxml2 (XML parser + XPath engine、未カバレッジの巨大 C library / 構造化文書 category)。
    #     XPath 評価 (//book[@id]/title) + --format 整形 serializer を native で検証。同一 host binary 両
    #     backend なので libxml2 version 非依存 (native==software が invariant)。oracle_cov4 を再利用。
    if command -v openssl >/dev/null 2>&1; then
        _cpbin /usr/bin/openssl /usr/bin/openssl
        [ -f /usr/lib/ssl/openssl.cnf ] && { mkdir -p "$SB/usr/lib/ssl"; cp /usr/lib/ssl/openssl.cnf "$SB/usr/lib/ssl/" 2>/dev/null; }
        _sh9=$(command -v dash || command -v sh); _cpbin "$_sh9" /bin/sh
        printf 'emulin native oracle public-key cross-validation\n' > "$SB/tmp/pk.dat"
        openssl genrsa -out "$SB/tmp/pk.rsa.pem" 2048 2>/dev/null
        openssl rsa -in "$SB/tmp/pk.rsa.pem" -pubout -out "$SB/tmp/pk.rsa.pub" 2>/dev/null
        openssl ecparam -name prime256v1 -genkey -noout -out "$SB/tmp/pk.ec.pem" 2>/dev/null
        openssl ec -in "$SB/tmp/pk.ec.pem" -pubout -out "$SB/tmp/pk.ec.pub" 2>/dev/null
        openssl dgst -sha256 -sign "$SB/tmp/pk.ec.pem" -out "$SB/tmp/pk.ec.sig" "$SB/tmp/pk.dat" 2>/dev/null
        # RSA-2048: sign(秘密鍵 CRT modexp)+verify(公開鍵 modexp) を emulin 上で roundtrip
        oracle_cov4 pk-rsa   "openssl" "Verified OK" 0 - -- /bin/sh -c 'openssl dgst -sha256 -sign /tmp/pk.rsa.pem -out /tmp/pk.rsa.sig /tmp/pk.dat && openssl dgst -sha256 -verify /tmp/pk.rsa.pub -signature /tmp/pk.rsa.sig /tmp/pk.dat'; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
        # ECDSA P-256: verify (楕円曲線点演算)。署名は host (nonce 乱数のため)、emulin 上は検証のみ。
        oracle_cov4 pk-ecdsa "openssl" "Verified OK" 0 - -- /usr/bin/openssl dgst -sha256 -verify /tmp/pk.ec.pub -signature /tmp/pk.ec.sig /tmp/pk.dat; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    fi
    if command -v xmllint >/dev/null 2>&1; then
        _cpbin /usr/bin/xmllint /usr/bin/xmllint
        printf '<?xml version="1.0"?>\n<catalog>\n  <book id="b1"><title>Alpha</title><price>10</price></book>\n  <book id="b2"><title>Beta</title><price>25</price></book>\n  <book id="b3"><title>Gamma</title><price>7</price></book>\n</catalog>\n' > "$SB/tmp/cat.xml"
        # XPath: id=b2 の title text を抽出 / --format: 整形 serializer (parse→tree→出力)
        oracle_cov4 xml-xpath  "xmllint" "Beta"                 0 - -- /usr/bin/xmllint --xpath '//book[@id="b2"]/title/text()' /tmp/cat.xml; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
        oracle_cov4 xml-format "xmllint" "<title>Alpha</title>" 0 - -- /usr/bin/xmllint --format /tmp/cat.xml; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
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
        # cov5: ★ CPython runtime から native の fork+exec(subprocess) / 生 fork(os.fork) / pthread(threading→
        #   multi-vCPU + futex lock) を 1 script で駆動。git/make/shell とは別の「managed runtime が fork/
        #   thread する」経路を検証 (subprocess の exec 先 /bin/echo は cov3 で bundle 済)。multi-line なので file。
        oracle_pyf() {  # oracle_pyf <expect> <guest-py-path>
            local expect=$1 gp=$2 soft nat sc nc
            soft=$( cd "$SB" && env HOME=/root PYTHONHOME=/usr PYTHONDONTWRITEBYTECODE=1 EMULIN_BACKEND=software java $JOPT -cp "$CP" emulin.Emulin "$SB" /usr/bin/python3.12 "$gp" < /dev/null 2>/dev/null ); sc=$?
            nat=$(  cd "$SB" && env HOME=/root PYTHONHOME=/usr PYTHONDONTWRITEBYTECODE=1 EMULIN_BACKEND=native   java $JOPT -cp "$CP" emulin.Emulin "$SB" /usr/bin/python3.12 "$gp" < /dev/null 2>/dev/null ); nc=$?
            if [ "$sc" != 0 ] || [ "$nc" != 0 ] || [ "$soft" != "$nat" ]; then
                echo "FAIL $NAME/python-mp : sc=$sc nc=$nc native!=software"
                echo "    soft: $(printf '%s' "$soft" | head -3 | tr '\n' '|')"
                echo "    nat : $(printf '%s' "$nat"  | head -3 | tr '\n' '|')"; return 1; fi
            if ! printf '%s' "$nat" | grep -qF "$expect"; then echo "FAIL $NAME/python-mp : '$expect' 無し"; return 1; fi
            echo "  ok python-mp ($gp) : native(KVM,ring3)==software ('$expect')"; return 0
        }
        cat > "$SB/tmp/cov5_pyproc.py" <<'PYEOF'
import os, subprocess, threading
print("subprocess:", subprocess.run(["/bin/echo", "sub-ok"], capture_output=True).stdout.decode().strip())
pid = os.fork()
if pid == 0:
    os._exit(7)
_, st = os.waitpid(pid, 0)
print("fork:", os.WEXITSTATUS(st))
c = [0]
lock = threading.Lock()
def w():
    for _ in range(1000):
        with lock:
            c[0] += 1
ts = [threading.Thread(target=w) for _ in range(4)]
for t in ts: t.start()
for t in ts: t.join()
print("threads:", c[0])
PYEOF
        oracle_pyf "threads: 4000" /tmp/cov5_pyproc.py;  r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    else
        echo "  SKIP $NAME/python : host に python3.12 + stdlib 無し"
    fi

    # --- cov10 (3d-2c-32): node (V8) の JS 実行 = 第 2 の JS JIT ---
    #   claude (bun/JSC) に続く V8。JS parse / regexp / JSON / arrow fn を 1 行で通し、V8 の
    #   JIT machine code を実 vCPU で実行する。★V8 は brk heap 近傍の hint 付き mmap
    #   (MAP_FIXED 無し、heap top の 512KB 切下げアドレス) を発行する — 旧 native は hint を
    #   無条件 MAP_FIXED 扱いして live malloc heap を bulkZero し、glibc sysmalloc assertion
    #   (malloc.c:2599) で死んでいた (3d-2c-32 で Linux の hint 意味論 = 空いていれば使う/
    #   塞がっていれば relocate に修正。本 oracle はその回帰固定)。pool は claude 同様
    #   EMULIN_NATIVE_POOL_MB=8192 (V8 が 512MB code range 等を eager 予約)。
    #   出力は文字列連結 (node は数値単体だと TTY 色エスケープを混ぜうるが文字列は plain)。
    #   host に node 無い CI は SKIP。
    if command -v node >/dev/null 2>&1; then
        _nbin=$( readlink -f "$(command -v node)" )
        mkdir -p "$SB/usr/bin"; cp "$_nbin" "$SB/usr/bin/node" 2>/dev/null
        ldd "$_nbin" 2>/dev/null | grep -oE '/(lib|usr/lib)[^ ]*\.so[^ ]*' | sort -u | while read l; do
            d="$SB$(dirname "$l")"; mkdir -p "$d"; cp "$l" "$d/" 2>/dev/null; done
        _njs='const a=[1,2,3].map(x=>x*x); console.log("node-oracle:"+(6*7)+":"+"banana".replace(/(an)+/g,"X")+":"+JSON.stringify(a))'
        _nexp='node-oracle:42:bXa:[1,4,9]'
        _nsoft=$( cd "$SB" && env EMULIN_BACKEND=software java $JOPT -cp "$CP" emulin.Emulin "$SB" /usr/bin/node -e "$_njs" < /dev/null 2>/dev/null ); _nsrc=$?
        _nnat=$(  cd "$SB" && env EMULIN_NATIVE_POOL_MB=8192 EMULIN_BACKEND=native java $JOPT -cp "$CP" emulin.Emulin "$SB" /usr/bin/node -e "$_njs" < /dev/null 2>/dev/null ); _nnrc=$?
        if [ "$_nsrc" = 0 ] && [ "$_nnrc" = 0 ] && [ -n "$_nnat" ] && [ "$_nsoft" = "$_nnat" ] && printf '%s' "$_nnat" | grep -qF "$_nexp"; then
            echo "  ok cov10 node : native(KVM,ring3,pool=8G)==software ('$_nexp'、V8 JIT を実 CPU 実行)"; ran=1
        else
            echo "FAIL $NAME/cov10-node : sc=$_nsrc nc=$_nnrc native!=software"
            echo "    soft: $(printf '%s' "$_nsoft" | head -2 | tr '\n' '|')"
            echo "    nat : $(printf '%s' "$_nnat"  | head -2 | tr '\n' '|')"; fail=1
        fi
    else
        echo "  SKIP $NAME/cov10-node : host に node 無し"
    fi

    # --- cov11 (3d-2c-33): ruby (CRuby/YARV) ---
    #   ★ruby 3.2 は初期化で ~1MB の単一巨大 stack frame を使い、旧 1MB main stack mapping の
    #   底を踏んで software backend で SIGSEGV していた (Linux は main stack を RLIMIT_STACK=8MB
    #   まで自動成長させるが emulin は固定 mapping)。stack_size64=8MB 化 (3d-2c-33) の回帰固定。
    #   perl/python/node に続く第 4 の実用 interpreter (YARV bytecode VM)。hash/block/regexp/
    #   Range#reduce を 1 行で通す。host に ruby 無い CI は SKIP。
    if command -v ruby >/dev/null 2>&1; then
        _rbin=$( readlink -f "$(command -v ruby)" )
        mkdir -p "$SB/usr/bin"; cp "$_rbin" "$SB/usr/bin/ruby" 2>/dev/null
        ldd "$_rbin" 2>/dev/null | grep -oE '/(lib|usr/lib)[^ ]*\.so[^ ]*' | sort -u | while read l; do
            d="$SB$(dirname "$l")"; mkdir -p "$d"; cp "$l" "$d/" 2>/dev/null; done
        [ -d /usr/lib/ruby ] && mkdir -p "$SB/usr/lib" && cp -r /usr/lib/ruby "$SB/usr/lib/" 2>/dev/null
        _rjs='h={"a"=>1,"b"=>2}; puts h.map{|k,v| "#{k}#{v*10}"}.join(","); puts "banana".gsub(/an/,"X"); puts (1..100).reduce(:+)'
        _rsoft=$( cd "$SB" && env EMULIN_BACKEND=software java $JOPT -cp "$CP" emulin.Emulin "$SB" /usr/bin/ruby --disable-gems -e "$_rjs" < /dev/null 2>/dev/null ); _rsrc=$?
        _rnat=$(  cd "$SB" && env EMULIN_BACKEND=native   java $JOPT -cp "$CP" emulin.Emulin "$SB" /usr/bin/ruby --disable-gems -e "$_rjs" < /dev/null 2>/dev/null ); _rnrc=$?
        if [ "$_rsrc" = 0 ] && [ "$_rnrc" = 0 ] && [ -n "$_rnat" ] && [ "$_rsoft" = "$_rnat" ] && printf '%s' "$_rnat" | grep -qF "5050" && printf '%s' "$_rnat" | grep -qF "a10,b20"; then
            echo "  ok cov11 ruby : native(KVM,ring3)==software ('a10,b20|bXXa|5050'、YARV VM)"; ran=1
        else
            echo "FAIL $NAME/cov11-ruby : sc=$_rsrc nc=$_rnrc native!=software"
            echo "    soft: $(printf '%s' "$_rsoft" | head -3 | tr '\n' '|')"
            echo "    nat : $(printf '%s' "$_rnat"  | head -3 | tr '\n' '|')"; fail=1
        fi
    else
        echo "  SKIP $NAME/cov11-ruby : host に ruby 無し"
    fi

    # --- cov12 (3d-2c-34): node/V8 の重い workload ---
    #   ★この cov 選定 scout で software backend の 3 バグを発見・修正した、その回帰固定:
    #   (1) IMUL の CF/OF 未設定 (0F AF は of=0;cf=0 固定、6B/69 は未設定、F7 /5 32-bit) →
    #       V8 の smi 乗算 overflow deopt (`imul; jo`) が不発で 46341*46341 が負値 (silent
    #       wrong answer)。(2) x87 FPREM が silent no-op → V8 Float64Mod (fprem C2 ループ) が
    #       dividend をそのまま返す。(1)(2) は hermetic には mulmod64 も固定している。
    #   (3) brk 成長が V8 の hint mmap (512MB) を貫通し brk Segment と AllocInfo が同一仮想を
    #       alias → InstructionStream の code field への store が別 backing に行き、読み手が 0 を
    #       見て RelocIterator(code=0) で SEGV (Memory.set_curbrk の Linux 同様 fail で解消)。
    #   a = tier-up/OSR + deopt + GC churn + JSON + regexp + WASM (V8 の主要 subsystem 一括)、
    #   c = crypto (node 静的リンク OpenSSL: chained sha256/pbkdf2/hmac)。
    #   期待値は JS 仕様で決定的 (数値演算/hash/JSON 整形は node version 非依存)。
    #   ★worker_threads (multi-isolate + SharedArrayBuffer/Atomics) は native では完走するが
    #   software backend は libuv の eventfd cross-thread 通知 (worker init の MessagePort 配信)
    #   が deadlock する別件 pre-existing バグのため oracle 化を見送り (single worker でも hang。
    #   native==software が成立せず。docs §4.4ff の follow-up に記録)。
    if command -v node >/dev/null 2>&1 && [ -x "$SB/usr/bin/node" ]; then
        cat > "$SB/tmp/cov12a.js" <<'C12A'
function f(n){let s=0;for(let i=0;i<n;i++){s=(s+i*i)%1000003}return s}
let t=0;for(let k=0;k<3;k++)t=(t+f(60000))%1000003;
function add(a,b){return a+b}
let s=0;for(let i=0;i<20000;i++)s+=add(i,1);
let st="";for(let i=0;i<100;i++)st=add("x","y");
for(let i=0;i<20000;i++)s+=add(i,2);
let keep=[];let h=0;
for(let i=0;i<60000;i++){const o={a:i,b:"s"+(i%97),c:[i,i+1]};h=(h*31+o.b.length+o.c[0])%1000003;if(i%1000===0)keep.push(o);if(keep.length>20)keep.shift();}
const o={items:[]};for(let i=0;i<4000;i++)o.items.push({id:i,name:"item"+i,tags:["a","b"],v:i*1.5});
const j=JSON.stringify(o);const p=JSON.parse(j);
let jh=0;for(const it of p.items)jh=(jh*33+it.id+it.name.length)%1000003;
let cnt=0;const re=/(\w+)@(\w+)\.(com|org)/g;const hay=[...Array(500)].map((_,i)=>"u"+i+"@h"+i+".com").join(" ");
while(re.exec(hay))cnt++;
const wb=new Uint8Array([0,97,115,109,1,0,0,0,1,7,1,96,2,127,127,1,127,3,2,1,0,7,7,1,3,97,100,100,0,0,10,9,1,7,0,32,0,32,1,106,11]);
const wi=new WebAssembly.Instance(new WebAssembly.Module(wb),{});
let ws=0;for(let i=0;i<20000;i++)ws=wi.exports.add(ws,1)|0;
console.log("cov12a:"+t+":"+s+":"+st.length+":"+h+":"+keep.length+":"+j.length+":"+jh+":"+cnt+":"+wi.exports.add(20,22)+":"+ws);
C12A
        cat > "$SB/tmp/cov12c.js" <<'C12C'
const c=require("crypto");
let h=Buffer.from("seed");
for(let i=0;i<300;i++){h=c.createHash("sha256").update(h).digest();}
const pb=c.pbkdf2Sync("pw","salt",500,16,"sha256").toString("hex");
const hm=c.createHmac("sha512","key").update("emulin").digest("hex").slice(0,16);
console.log("cov12c:"+h.toString("hex").slice(0,16)+":"+pb+":"+hm);
C12C
        cat > "$SB/tmp/cov12b.js" <<'C12B'
const {Worker}=require("worker_threads");
const sab=new SharedArrayBuffer(8);const a=new Int32Array(sab);
const code='const {parentPort,workerData}=require("worker_threads");const a=new Int32Array(workerData.sab);for(let i=0;i<20000;i++)Atomics.add(a,0,1);let s=0;for(let i=0;i<30000;i++)s+=i%7;parentPort.postMessage(s+workerData.id);';
const ps=[];for(let i=0;i<4;i++)ps.push(new Promise((res,rej)=>{const w=new Worker(code,{eval:true,workerData:{sab,id:i}});w.on("message",res);w.on("error",rej);}));
Promise.all(ps).then(vs=>{vs.sort((x,y)=>x-y);console.log("cov12b:"+vs.join(",")+":"+Atomics.load(a,0))}).catch(e=>console.log("cov12b:ERR:"+e.message));
C12B
        # $4 = software 側に足す追加 java opt (worker_threads は複数 V8 isolate を作るので
        #   production launcher 同等の大きめ Java heap が要る。native は off-heap KVM pool 使用で
        #   default heap のままで良い)。
        oracle_node12() {
            local label=$1 expect=$2 script=$3 softextra=${4:-}
            local s n sc nc
            s=$( cd "$SB" && env EMULIN_BACKEND=software java $JOPT $softextra -cp "$CP" emulin.Emulin "$SB" /usr/bin/node "$script" < /dev/null 2>/dev/null ); sc=$?
            n=$( cd "$SB" && env EMULIN_NATIVE_POOL_MB=8192 EMULIN_BACKEND=native java $JOPT -cp "$CP" emulin.Emulin "$SB" /usr/bin/node "$script" < /dev/null 2>/dev/null ); nc=$?
            if [ "$sc" = 0 ] && [ "$nc" = 0 ] && [ -n "$n" ] && [ "$s" = "$n" ] && printf '%s' "$n" | grep -qF "$expect"; then
                echo "  ok cov12 $label : native(KVM,ring3,pool=8G)==software ('$expect')"; return 0
            fi
            echo "FAIL $NAME/cov12-$label : sc=$sc nc=$nc native!=software"
            echo "    soft: $(printf '%s' "$s" | head -2 | tr '\n' '|')"
            echo "    nat : $(printf '%s' "$n" | head -2 | tr '\n' '|')"; return 1
        }
        oracle_node12 vm-wasm "cov12a:48144:400040000:2:218447:20:225050:978035:500:42:20000" /tmp/cov12a.js; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
        oracle_node12 crypto "cov12c:6aaf487cd6352480:b7cde30bb4fa1f3968b33815c685f58c:33c8adb47b6caacb" /tmp/cov12c.js; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
        # ★cov12 workers (worker_threads = multi-isolate + SharedArrayBuffer/Atomics)。3d-2c-35 で
        #   「software で deadlock」は libuv バグでなく **Java heap 不足の OOM artifact** と判明 (worker は
        #   2 つ目以降の V8 isolate を作り、software は isolate の予約 mmap に実 byte[] を確保するので
        #   default 2GB heap を超える)。production launcher は -Xmx8g なので実ユーザは元から動作。
        #   software 側だけ大きめ heap で走らせると native==software (両 backend で 4 worker が完走)。
        oracle_node12 workers "cov12b:89995,89996,89997,89998:80000" /tmp/cov12b.js "-Xmx${EMULIN_ORACLE_XMX:-6g}"; r=$?; [ "$r" = 1 ] && fail=1; [ "$r" = 0 ] && ran=1
    else
        echo "  SKIP $NAME/cov12 : host に node 無し"
    fi

    # --- cov13 (3d-2c-43): sqlite3 (ファイル DB の B-tree + rollback journal) ---
    #   ★sqlite は file-backed DB の page を pwrite64(18) で positioned write し、commit 時に
    #   fdatasync(75) で durability を取る。どちらも未実装(ENOSYS)で「disk I/O error」になって
    #   create table から失敗していた → 両 syscall を実装 (3d-2c-43)。fcntl の byte-range advisory
    #   lock (F_SETLK) も経由する (single-process なので no-op success で十分)。
    #   create→insert(BEGIN/COMMIT=rollback journal+fdatasync)→index(B-tree)→集計(SUM/COUNT/
    #   GROUP BY)→ORDER BY/LIMIT を 1 file DB で通す。host に sqlite3 が無ければ apt-get download
    #   で取得 (非 sudo、build-release.sh と同じ流儀)、それも不可なら SKIP。
    _sq=$(command -v sqlite3 2>/dev/null)
    if [ -z "$_sq" ]; then
        _sqd=$(mktemp -d); ( cd "$_sqd" && apt-get download sqlite3 >/dev/null 2>&1 && dpkg-deb -x sqlite3_*.deb x >/dev/null 2>&1 )
        _sq=$(find "$_sqd" -name sqlite3 -type f 2>/dev/null | head -1)
    fi
    if [ -n "$_sq" ] && [ -x "$_sq" ]; then
        mkdir -p "$SB/usr/bin"; cp "$_sq" "$SB/usr/bin/sqlite3" 2>/dev/null
        ldd "$_sq" 2>/dev/null | grep -oE '/(lib|usr/lib)[^ ]*\.so[^ ]*' | sort -u | while read l; do
            d="$SB$(dirname "$l")"; mkdir -p "$d"; cp "$l" "$d/" 2>/dev/null; done
        cat > "$SB/tmp/cov13.sql" <<'C13'
PRAGMA journal_mode=DELETE;
CREATE TABLE t(id INTEGER PRIMARY KEY, name TEXT, val INTEGER);
BEGIN;
INSERT INTO t(name,val) VALUES ('alpha',10),('beta',20),('gamma',30),('alpha',5),('beta',7);
COMMIT;
CREATE INDEX idx_name ON t(name);
SELECT name, SUM(val), COUNT(*) FROM t GROUP BY name ORDER BY name;
SELECT 'total', SUM(val), MAX(val), MIN(val) FROM t;
SELECT name FROM t WHERE val > 8 ORDER BY val DESC LIMIT 3;
C13
        rm -f "$SB/tmp/cov13.db" "$SB/tmp/cov13.db-journal"
        _ssoft=$( cd "$SB" && env EMULIN_BACKEND=software java $JOPT -cp "$CP" emulin.Emulin "$SB" /usr/bin/sqlite3 /tmp/cov13.db ".read /tmp/cov13.sql" < /dev/null 2>/dev/null ); _ssrc=$?
        rm -f "$SB/tmp/cov13.db" "$SB/tmp/cov13.db-journal"
        _snat=$(  cd "$SB" && env EMULIN_BACKEND=native   java $JOPT -cp "$CP" emulin.Emulin "$SB" /usr/bin/sqlite3 /tmp/cov13.db ".read /tmp/cov13.sql" < /dev/null 2>/dev/null ); _snrc=$?
        if [ "$_ssrc" = 0 ] && [ "$_snrc" = 0 ] && [ -n "$_snat" ] && [ "$_ssoft" = "$_snat" ] \
           && printf '%s' "$_snat" | grep -qF "alpha|15|2" && printf '%s' "$_snat" | grep -qF "total|72|30|5"; then
            echo "  ok cov13 sqlite3 : native(KVM,ring3)==software ('alpha|15|2 .. total|72|30|5'、file DB B-tree+rollback journal、pwrite64/fdatasync)"; ran=1
        else
            echo "FAIL $NAME/cov13-sqlite3 : sc=$_ssrc nc=$_snrc native!=software"
            echo "    soft: $(printf '%s' "$_ssoft" | head -3 | tr '\n' '|')"
            echo "    nat : $(printf '%s' "$_snat"  | head -3 | tr '\n' '|')"; fail=1
        fi
    else
        echo "  SKIP $NAME/cov13-sqlite3 : host に sqlite3 無し (apt-get download も不可)"
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
echo "PASS $NAME : static (16 [★pushf64=PUSHFQ/POPFQ + ★rcr64=RCL/RCR] + 7 signal[FPU-in-signal/sa_mask 含む]、execve + fork×3 含む) + ★8 pty/対話 (制御端末/SIGWINCH/FIONREAD/ONLCR、ssh/emacs/vim 対話経路) + dynamic glibc (hello/printf/regex/mmap/nested/pie/zlib/cpp/dirlist + pthread basic/mutex/sigmask + integ _dyn64) + 実 GNU dynamic (grep/gawk/sed/perl/sha256sum/tar + ★perl-fork + ★grep-P PCRE2-JIT + ★emacs/claude --version + ★gcc compile+run[実 toolchain]) + cov3(make/xargs/bc/find/diff) + cov4 (★shell pipeline dash/bash + bzip2/xz + openssl-AESNI/cksum/sha512 + factor/bc-l + comm/patch/cpio) + cov6 (★git local log/cat-file/diff + b2sum/m4) + cov9 (★公開鍵暗号 RSA sign+verify/ECDSA-P256 verify[asymmetric crypto cross-validation] + XML libxml2 xmllint xpath/format) + ★python3.12 (json/hashlib/re + ★cov5 CPython fork/exec/threading) + ★cov10 node (V8 JIT=第2の JS JIT、hint mmap 意味論の回帰) + ★cov11 ruby (YARV、main stack 8MB 化の回帰) + ★cov12 node 重 workload (tier-up/deopt/GC/JSON/regexp/WASM + OpenSSL crypto + ★worker_threads 4-isolate/Atomics = IMUL-OF/FPREM/brk-alias 3 バグ修正の回帰) + busybox (8 applet) native(KVM,ring3)==software"
exit 0
