# Emulin experimental AArch64 Debian bundle

This bundle is the Issue 951 Phase 6 development image for macOS arm64.  It
contains the Emulin Java executable and a pinned Debian 13 arm64 rootfs.  POSIX
symbolic links and modes are preserved; no Cygwin link conversion is applied.

Run from the unpacked directory:

```sh
./emulin.sh /bin/bash
```

The image is built from `dist/debian-arm64-bash-rootfs.manifest`.  Its local
Phase 6 gate covers Debian bash, apt update/install/execute, clocks, pipes,
ppoll, socketpair, UDP/TCP sockets, DNS, POSIX ptys, X.509 certificate parsing,
SHA-256, AES-CTR, ChaCha20, and a verified TLS 1.3 connection using Debian's
default X25519MLKEM768 hybrid key exchange.  The AArch64 backend is still
experimental and is developed for the post-1.0 macOS release line.

From the source tree, run the corresponding gates with:

```sh
dist/run-debian-arm64-phase6-local-smoke.sh
dist/run-debian-arm64-dns-smoke.sh
dist/run-debian-arm64-crypto-smoke.sh
dist/run-debian-arm64-tls-smoke.sh
dist/run-debian-arm64-apt-smoke.sh
```

The DNS, TLS, and apt gates require external network access.  The TLS gate pins
the ISRG Root X1 trust anchor, verifies the `deb.debian.org` hostname, requires
TLS 1.3, and checks that Debian's default X25519MLKEM768 group was negotiated.
