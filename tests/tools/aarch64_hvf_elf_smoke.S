// Real ELF entry smoke for Issue #973. Built natively in the Debian arm64 VM.
.global _start
.type _start, %function

_start:
    mov x8, #172       // getpid
    svc #0
    mov x1, x0         // preserve the dispatcher result
    mov x0, #0x51      // recognizable completion value
    svc #1             // host-side completion marker
1:  b 1b
