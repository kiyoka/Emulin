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
SHA-256, AES-CTR, and ChaCha20.  The AArch64 backend is still experimental and
is developed for the post-1.0 macOS release line.

From the source tree, run the corresponding gates with:

```sh
dist/run-debian-arm64-phase6-local-smoke.sh
dist/run-debian-arm64-dns-smoke.sh
dist/run-debian-arm64-crypto-smoke.sh
dist/run-debian-arm64-apt-smoke.sh
```

The DNS and apt gates require external network access.  A Debian OpenSSL TLS
1.3 connection reaches encrypted record processing, but its first protected
record currently fails authentication.  Full HTTPS is therefore a documented
remaining issue rather than part of the passing Phase 6 gate; the deterministic
certificate and crypto-primitives gate above protects the completed portion.
