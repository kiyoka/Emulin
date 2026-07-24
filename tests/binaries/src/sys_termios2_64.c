/* sys_termios2_64.c — TCGETS2/TCSETS2 (termios2) の round-trip を検証 (issue #427)。
 *
 * OpenAI Codex (Rust) の raw-mode 設定は termios2 (TCGETS2=0x802c542a /
 * TCSETS2=0x402c542b) を使う。emulin は従来 TCGETS/TCSETS (36 byte termios) のみで
 * TCGETS2/TCSETS2 (44 byte = termios + c_ispeed/c_ospeed) を "Unsupported ioctl" として
 * いたため、codex が raw mode に入れず login menu の入力 (矢印/Enter) が効かなかった。
 *
 * 対象は「実端末」でなければならない (codex の stdin は pty)。fd 0 は harness では
 * /dev/null にリダイレクトされ、実 Linux では TCGETS2 が ENOTTY を返す
 * (isatty(0)=false)。そこで /dev/ptmx を開いて pty master (= 実 tty) に対して検証する。
 *
 * 検証: /dev/ptmx (pty master) に対して
 *   1. TCGETS2 で現在の termios2 を取得 (成功=0)
 *   2. c_lflag の ICANON を落として TCSETS2 (成功=0)
 *   3. TCGETS2 で読み戻し、ICANON が落ちていることを確認
 * 期待: get1=0 set=0 get2=0 icanon=0
 *   ※ c_ispeed/c_ospeed の値は実 pty が設定を無視し既定速度を返す (実 Linux で
 *      115200 を設定しても 38400 が読み戻る) ため、特定値は assert しない。
 */
#include "sys64.h"

#define TCGETS2  0x802c542a
#define TCSETS2  0x402c542b
#define ICANON   0x0002
#define O_RDWR   2
#define O_NOCTTY 0400

/* termios2 は 44 byte: c_iflag/c_oflag/c_cflag/c_lflag(各4) + c_line(1) + c_cc[19] +
 * c_ispeed/c_ospeed(各4)。c_cc[19] の直後 offset 36 は 4 境界なので padding 無し。 */
struct termios2 {
    unsigned int  c_iflag, c_oflag, c_cflag, c_lflag;
    unsigned char c_line;
    unsigned char c_cc[19];
    unsigned int  c_ispeed, c_ospeed;
};

void _start(void) {
    struct termios2 t, t2;

    long fd = sys_open("/dev/ptmx", O_RDWR | O_NOCTTY, 0);

    long g1 = sys_ioctl(fd, TCGETS2, &t);
    t.c_lflag &= ~ICANON;       /* raw mode */
    long s = sys_ioctl(fd, TCSETS2, &t);
    long g2 = sys_ioctl(fd, TCGETS2, &t2);

    put("get1=");   put_dec(g1);
    put(" set=");   put_dec(s);
    put(" get2=");  put_dec(g2);
    put(" icanon="); put_dec((t2.c_lflag & ICANON) ? 1 : 0);
    put("\n");

    sys_exit(0);
}
