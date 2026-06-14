# Third-Party Licenses (Emulin Distribution)

This document lists the third-party software bundled in this Emulin
distribution, with their licenses and source URLs. For the COMPLETE
upstream copyright text of each package, see
`rootfs/usr/share/doc/<package>/copyright` in the unpacked distribution.

For the high-level license obligations (GPL source availability, etc.),
see `NOTICE.txt` in this distribution.

## Emulin core + bundled JRE

| Component | Version | License | Source |
|-----------|---------|---------|--------|
| Emulin | 0.5.0 | GPL-2 | https://github.com/kiyoka/emulin |
| Microsoft Build of OpenJDK | 25 | GPL-2 + Classpath Exception | https://github.com/microsoft/openjdk |
| BusyBox | (host) | GPL-2 | https://busybox.net/ |
| JLine 3 | (compiled in) | BSD-3-Clause | https://github.com/jline/jline3 |
| ASM | (compiled in) | BSD-3-Clause | https://asm.ow2.io/ |

## Bundled Linux ELF binaries (rootfs/)

These are Debian 13 (trixie)-derived binaries executed by the Emulin emulator
(not the host OS). Full corresponding source code is available from
`https://deb.debian.org/debian/pool/main/`.

### GPL-3+ (GNU General Public License v3 or later)

GNU coreutils, bash, gawk, sed, grep, gpg suite (gpg, gpg-agent,
dirmngr, gpgsm, gpgconf, gpg-wks-server, gnupg-utils), nano, less,
diffutils, coreutils, plocate, pinentry.

Each package's exact copyright text: `rootfs/usr/share/doc/<pkg>/copyright`

### GPL-2 / GPL-2+ (GNU General Public License v2)

git, e2fsprogs, libcom-err2, libkeyutils1, acl, bsdextrautils,
libpcre2-posix3 (with linking exception).

### LGPL (Lesser General Public License)

gpgrt-tools (LGPL-2.1+), libc6 / glibc family (LGPL-2.1+),
libgcc-s1 (GPL-3 with linking exception).

### BSD / MIT / Apache style (permissive)

| Package | License |
|---------|---------|
| dash | BSD-3-Clause |
| dos2unix | BSD-2-Clause |
| p11-kit | BSD-3-clause |
| ncurses-bin / libncurses | MIT/X11 |
| sasl2-bin | BSD-3-Clause-Attribution |
| psl | MIT |
| liburing2 | MIT |
| file | BSD-2-Clause-alike |
| bzip2 | BSD-variant |
| zlib1g | Zlib |
| nettle-bin | LGPL-3+ / GPL-2+ dual |

### Special: project-specific licenses

| Package | License | Notes |
|---------|---------|-------|
| openssh-client / openssh-server | OpenSSH | BSD-style with optional |
| curl | curl | MIT/X11-like |
| vim / vim-common / vim-runtime | Vim | Charity-ware (see vim copyright) |
| perl / perl-base / perl-modules-5.38 | Artistic + GPL-1+ dual | Standard Perl license |
| python3 (if INCLUDE_PYTHON=1) | PSF | Python Software Foundation License |
| emacs-nox (if INCLUDE_EMACS=1) | GPL-3+ | GNU |
| tig (if INCLUDE_TIG=1) | GPL-2+ | git history browser |
| jq (if INCLUDE_JQ=1) | MIT | + libonig (BSD-2-Clause) |
| sqlite3 (if INCLUDE_SQLITE=1) | Public Domain | + libsqlite3 |
| nano (if INCLUDE_NANO=1) | GPL-3+ | GNU nano editor |
| tree (if INCLUDE_TREE=1) | GPL-2+ | directory lister |
| patch (if INCLUDE_PATCH=1) | GPL-3+ | GNU patch |
| zip / unzip (if INCLUDE_ZIP=1) | Info-ZIP | BSD-like; + xz-utils (PD / GPL mixed) |
| rsync (if INCLUDE_RSYNC=1) | GPL-3+ | file sync (issue #130 Tier 2) |
| make (if INCLUDE_MAKE=1) | GPL-3+ | GNU make |

## Per-package detail location

```
rootfs/
└── usr/
    └── share/
        └── doc/
            ├── acl/copyright
            ├── bash/copyright
            ├── busybox-static/copyright
            ├── coreutils/copyright
            ├── curl/copyright
            ├── ... (62 packages typical, depending on INCLUDE_* options)
            ├── openssh-client/copyright
            ├── openssh-server/copyright
            ├── perl/copyright
            ├── vim/copyright
            └── zlib1g/copyright
```

Each `copyright` file follows the Debian DEP-5 machine-readable format:
https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/

Example excerpt (bash):
```
Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/
Files: *
Copyright: (C) 1987-2022 Free Software Foundation, Inc.
License: GPL-3+
```

## Source code availability

For all GPL/LGPL components bundled in this distribution, the
corresponding source code can be obtained via:

1. **Debian archive** (for rootfs/ binaries):
   - https://deb.debian.org/debian/pool/main/
   - or `apt-get source <pkg>` on a Debian system

2. **Upstream repositories** (for core components):
   - Emulin: https://github.com/kiyoka/emulin
   - OpenJDK: https://github.com/openjdk/jdk25u
   - BusyBox: https://busybox.net/downloads/
   - JLine: https://github.com/jline/jline3

3. **Written offer** (GPL §3(b) / §6(b)): The maintainer of this
   distribution offers to provide, for at least 3 years from the release
   date, a complete machine-readable copy of the corresponding source
   for any GPL/LGPL package, at the cost of physical media and shipping.
   Contact via the GitHub repository above.

## Disclaimers

- Emulin and its bundled components are provided AS-IS without warranty.
- Trademarks: "Linux" is a trademark of Linus Torvalds. "Java" is a
  trademark of Oracle. "Git", "OpenSSH", etc. are trademarks of their
  respective owners.
- This distribution is NOT affiliated with the Debian Project, the
  GNU project, Eclipse Foundation, or the upstream projects of the
  bundled binaries.
