# Emulin

**English** | [日本語](README.ja.md)

**A 32/64-bit Linux ELF emulator that runs on Java**

GNU General Public License v2 (see `COPYING` for details)

---

## Overview

Emulin is an emulator that runs Linux x86 (32-bit) / x86-64 (64-bit) ELF
binaries on Java. Because it is pure Java, you can run Linux binaries on
Windows / macOS.

It can run real Linux binaries (git / curl / openssl / Python 3.12 / vim 9.1 /
emacs-nox / GNU coreutils, etc.).

It also has a **native execution backend using Windows Hypervisor Platform
(WHP) on Windows / KVM on Linux**: where available, the guest runs on a real
vCPU for a large speedup (it falls back to pure-Java execution automatically
when unavailable).

## Get started

Download a release zip from [Releases](https://github.com/kiyoka/Emulin/releases)
(or build one with `dist/build-release.sh`) and unzip it anywhere. A JRE is
bundled, so **you don't need to install Java**.

## Features

- Written entirely in Java (pure Java, no JNI)
- Runs both 32-bit ELF (i386) and 64-bit ELF (x86-64)
- Runs dynamically linked real binaries (PIE / ld.so / libc / pthread support)
- **Debian 13 (trixie) base-equivalent bundle + `apt` / `dpkg`** — packages can
  be added on top of emulin via `apt-get install` / `dpkg -i`, complete with
  GPG signature verification
  ([Adding Debian packages](#adding-debian-packages-apt--dpkg))
- Full AES-NI / PCLMULQDQ instruction implementation (matches the FIPS-197 host)
- Full pthread support (clone+futex / per-thread signal mask / mutex contention)
- Full TLS 1.3 support (via gnutls, including cert verify)
- AF_INET6 (IPv6) socket support — client TCP / UDP + server (accept4); AF_UNIX
  also supported
- JLine 3 for common raw mode / Ctrl-C / SIGWINCH support across
  Linux/macOS/Windows
- **Interactive bash / vim / emacs editing in Windows cmd.exe / Windows Terminal**
- **Basic-block JIT translator (optional)**: with `EMULIN_USE_JIT=1`, x86-64
  instructions are translated to Java bytecode. AES-NI / PCLMULQDQ are also
  supported, giving -13~14% speedup on HTTPS (off by default)
- **Native execution backend (Hyper-V WHP / KVM)**: runs the guest on a real
  vCPU and traps only syscalls into emulin. ~200x faster for compute-bound work,
  and byte-identical to software
  ([Native execution](#native-execution-for-speed-hyper-v--kvm))
- **SSH server support**: `emulin sshd` starts OpenSSH sshd so external SSH
  clients can connect ([Using as an SSH server](#using-as-an-ssh-server-emulin-sshd))
- **AI coding agents**: the Node.js build of Claude Code (2.1.112) and Codex
  run interactive coding sessions on top of Emulin
  ([Running AI coding agents](#running-ai-coding-agents-claude-code--codex))

## Real binaries that work (examples)

- **GNU coreutils 30+** (cat / ls / cp / mv / sort / find / grep, etc.)
- **bash 5.2 + line edit** (history / cursor / Tab, via JLine raw mode)
- **vim 9.1** — `vim -e -s` ex mode + interactive editing in cmd.exe (insert / `:wq`)
- **emacs-nox 29.3** (interactive editing)
- **Python 3.12 stdlib** (re / json / collections / enum / functools / math /
  datetime, etc.)
- **OpenSSL 3.0.13** (TLS 1.3, AES-GCM, HTTPS handshake)
- **curl / wget HTTPS** (HTTP/1.1 / HTTP/2, multi-site: github / cloudflare /
  google / iana / raw.githubusercontent, etc.)
- **git**: init / add / commit / log / status / diff / clone
  (git:// / file:// / https:// all supported, including `--depth` / templates /
  hardlinks)
- **less 643** (vt100 keybindings, SIGWINCH support)
- **Claude Code 2.1.112 (Node.js build) / Codex** — interactive AI coding
  sessions ([Running AI coding agents](#running-ai-coding-agents-claude-code--codex))

## Runtime environment

| Item | Details |
|------|---------|
| JDK / JRE | **25 or later** (developed and tested on OpenJDK 25 LTS; uses the Java FFM API, #221) |
| OS | Linux (primary) / Windows 11 Home or later / macOS |

## Quick start

### Getting started on Windows (no Java required)

A JRE (Microsoft Build of OpenJDK 25) is bundled, so **you do not need to
install Java separately**. Just unzip and run.

1. **Enable the Windows Hypervisor Platform (WHP)** (first time only, recommended)
   With WHP enabled, emulin runs the guest on a real vCPU for a large speedup,
   which is recommended for almost all use. (It still runs without WHP as pure
   Java, but slower.) In an **Administrator PowerShell**:
   ```powershell
   dism /Online /Enable-Feature /FeatureName:HypervisorPlatform /All
   ```
   Or, via "Control Panel → Programs → Turn Windows features on or off", check
   **"Windows Hypervisor Platform"**.
   **Reboot Windows after enabling.** (Available on Windows 11 Home or later;
   coexists with WSL2.)

2. **Download the distribution zip**
   Get `debian-emulin-0.7.0-windows.zip` from
   [Releases](https://github.com/kiyoka/Emulin/releases) (or build one locally
   with `dist/build-release.sh`). It is a Debian 13 (trixie) base with `apt` /
   `dpkg`, bundling git / curl / wget / openssl / python3 / vim / emacs, etc.

3. **Unzip anywhere**
   e.g. `C:\Tools\debian-emulin-0.7.0-windows\` (paths with Japanese characters
   or spaces work, but an ASCII path is recommended where possible).

4. **Start the bash interactive shell**
   In the unzip directory, double-click `emulin.bat` in Explorer, or run it from
   cmd / Windows Terminal:
   ```cmd
   cd C:\Tools\debian-emulin-0.7.0-windows
   emulin.bat
   ```
   ```
   # echo hello
   hello
   # uname -m
   x86_64
   # exit
   ```
   (The first run unpacks the bundled rootfs, so it takes a moment.)

5. **Single-command mode / running real binaries**
   `debian-emulin-0.7.0-windows` bundles git / curl / openssl / python3, etc.,
   so you can run them right after unzipping:
   ```cmd
   emulin.bat ls /
   emulin.bat /usr/bin/git --version
   emulin.bat /usr/bin/git clone --depth=1 https://github.com/octocat/Hello-World.git /tmp/cloned
   ```

To add packages with `apt`, see
[Adding Debian packages](#adding-debian-packages-apt--dpkg).

> **Notes**:
> - The bundled JRE is the Microsoft Build of OpenJDK 25 (GPLv2 + Classpath Exception). See the bundled `NOTICE.txt` for details.
> - `emulin.bat` invokes the bundled JRE (`jre\bin\java.exe`) internally, so it works even if Java is not on PATH.
> - With no arguments, `emulin.bat` opens an interactive bash shell in Windows Terminal (`set EMULIN_NO_WT=1` for the plain console).

### Build from source

```bash
mvn package -DskipTests   # -> target/emulin-<version>-all.jar
```

The fat jar you build is invoked internally by the `emulin.bat` / `emulin.sh`
launchers in a distribution zip. To build a Debian-based bundle locally, use
`dist/build-release.sh`.

## Adding Debian packages (apt / dpkg)

`debian-emulin-0.7.0-windows.zip` is built on a rootfs that is
**equivalent to a Debian 13 (trixie) base**, and bundles `apt` / `dpkg` along
with apt's prerequisites (`/etc/apt/sources.list.d/debian.sources` +
`debian-archive-keyring` signing keys). As a result, adding packages with
`apt-get` works end-to-end on top of emulin, **complete with GPG signature
verification** (trixie main / trixie-security from deb.debian.org).

```bash
# Fetch the package index
./emulin.sh /usr/bin/apt-get update </dev/null

# Add a package (e.g. GNU hello)
./emulin.sh /usr/bin/apt-get install -y hello </dev/null

# Run / verify the added binary
./emulin.sh /usr/bin/hello
./emulin.sh /usr/bin/dpkg-query -W hello
```

On Windows use `emulin.bat /usr/bin/apt-get ...`; for direct execution from
source, read it as
`java -XX:-DontCompileHugeMethods -jar emulin-*-all.jar <rootfs> /usr/bin/apt-get ...`.
A local rootfs with `apt` can also be created with
`dist/build-debian-base.sh <rootfs>`. Local install via `dpkg -i <pkg>.deb`
works the same way.

### Operational notes

- **Add `</dev/null` or `-y`** — `apt-get` reads standard input (fd 0). When
  stdin is blocked (e.g. via a script with no terminal), it waits at the
  confirmation prompt and appears to "hang". For non-interactive use, add
  **`-y` + `</dev/null`** as in `apt-get install -y <pkg> </dev/null` (not
  needed when running interactively from a terminal).

- **Increase the timeout for Japanese input with mozc in emacs** — When using
  mozc.el for Japanese conversion you may hit `mozc.el: No response from the
  server` / `Failed to start a new session`. This is because mozc.el's default
  response timeout (`mozc-helper-process-timeout-sec` = 1 second) is too short
  for how long mozc_emacs_helper takes to start under Emulin (loading many shared
  libraries plus mozc_server initialization / dictionary loading takes a few
  seconds). Add the following to your emacs init to lengthen the timeout:

  ```elisp
  (with-eval-after-load 'mozc
    (setq mozc-helper-process-timeout-sec 15))   ; default 1s -> 15s
  ```

  This is a one-time startup cost; it does not affect steady-state conversion
  speed (raise it to 20-25s if needed, as a Windows host can be even slower).

## Using as an SSH server (`emulin sshd`)

You can start OpenSSH **sshd** on top of emulin and connect from an external SSH
client (OpenSSH `ssh` / Tera Term, etc.) to interactively operate bash / vim /
emacs. Because it goes through a real SSH client, it avoids the Windows console
key limitations (Ctrl+Space, etc.).

> **The daemon does not start automatically.** emulin is a single-process
> emulator with no init/systemd. The user starts sshd explicitly with
> `emulin sshd`.

```bash
# 1. You need a bundle that includes sshd (release/full bundle, or build with INCLUDE_SSHD=1)
# 2. Register the connecting SSH client's public key in authorized_keys
#    (rootfs/root/.ssh/authorized_keys inside the bundle)
cat ~/.ssh/id_ed25519.pub >> <bundle>/rootfs/root/.ssh/authorized_keys

# 3. Start sshd (when port is omitted: 2222, listens on 127.0.0.1, user=root, publickey auth)
./emulin.sh sshd            # or: ./emulin.sh sshd 2222   (on Windows, emulin.bat sshd)

# 4. Connect from another terminal
ssh -p 2222 root@127.0.0.1
#   Tera Term: Host=localhost / TCP port=2222 / User=root / Auth=publickey
```

The host key is automatically `chmod 600`'d at startup. Stop with Ctrl-C. Host
environment variables are inherited by the guest (issue #228).

## Running AI coding agents (Claude Code / Codex)

The headline feature of 0.7.0: **practical AI coding agents run on top of
Emulin**. The Node.js build of Claude Code and Codex both support interactive
coding sessions. On Windows, the WHP native backend is strongly recommended
([Native execution](#native-execution-for-speed-hyper-v--kvm)).

### Claude Code (Node.js build, 2.1.112)

> **Important — the newest usable version is 2.1.112.** It is the last release
> shipped as a pure-Node.js CLI (`cli.js`). From 2.1.113 onward the npm package
> ships a Bun native binary whose event loop does not service stdin on Emulin,
> so keyboard input never arrives (issue #422). Pin the version and disable the
> auto-updater so it never upgrades itself into an unusable build.

```bash
# 1. Node.js + npm (once; Debian trixie packages)
apt-get update && apt-get install -y nodejs npm </dev/null

# 2. Claude Code, pinned to the last Node.js build
npm install -g @anthropic-ai/claude-code@2.1.112

# 3. Start it (auto-updater disabled)
DISABLE_AUTOUPDATER=1 claude
```

Authenticate with `/login` (Claude subscription OAuth, or an API key) and start
coding. On Windows, a small launcher `.bat` placed next to `emulin.bat` gives a
one-click session:

```bat
@echo off
setlocal
set EMULIN_NATIVE_POOL_MB=1024
set DISABLE_AUTOUPDATER=1
set TERM=xterm-256color
call "%~dp0emulin.bat" /usr/local/bin/claude %*
```

### Codex

```bash
apt-get update && apt-get install -y nodejs npm </dev/null
npm install -g @openai/codex
```

Emulin's rootfs is itself the isolation boundary, and the OS-level sandbox
that codex tries to set up inside the guest (Landlock + seccomp) is not
supported (codex would panic trying to install it). Disable it in
`~/.codex/config.toml` before the first run:

```toml
sandbox_mode = "danger-full-access"
```

Then run `codex` and authenticate (ChatGPT account or API key).

### Known limitations (AI agents)

| Limitation | Details / workaround |
|---|---|
| Claude Code: usable up to **2.1.112** (Node.js build) | 2.1.113+ ship a Bun-only binary; keyboard input does not work on Emulin (#422). Pin the version and set `DISABLE_AUTOUPDATER=1`. |
| Claude Code `/quit` takes a while | Shutdown runs npm, which opens many files; much improved (#696) but still tens of seconds. Just wait (#695). |
| Not usable inside tmux | Agent TUIs under tmux are unverified/unstable (#694). Run them outside tmux. |
| Occasional input freeze (Windows Terminal) | Rarely the WT/conpty layer stops delivering keystrokes, including Ctrl-C (#709). **Resize the terminal window once** — pending input flushes and the session continues. |
| Slow startup on large repos under `/mnt/c` | Workspace scanning (`git ls-files` / `rg --files`) over the host mount is much slower than inside the rootfs. Prefer cloning into the rootfs, e.g. `git clone file:///mnt/c/dev/repo ~/repo`. |
| Codex built-in sandbox is unavailable | `sandbox_mode = "danger-full-access"` is required; Emulin's rootfs remains the isolation boundary (user-namespace emulation for bwrap is planned in #497). |

## Native execution for speed (Hyper-V / KVM)

In environments where Windows **Hyper-V (WHP)** / Linux **KVM** is available,
you can use the **native backend**, which runs the guest on a real vCPU and
traps only syscalls into emulin. This greatly speeds up compute-bound work
(~200x for sort / grep / sha256sum, etc.; even large git clones run at a
practical speed).

The launchers (`emulin.sh` / `emulin.bat`) set `EMULIN_BACKEND=auto` by default,
so they **use native when HW virtualization is available and fall back to
software automatically otherwise**. The startup banner shows the current
backend:

```
[backend=native (auto, KVM detected (/dev/kvm OK))]   <- running on native
[backend=software]                                    <- running on software
```

**Requirements:**

- **Windows**: Enable the "**Windows Hypervisor Platform**" from Windows
  Features (can coexist with WSL2).
- **Linux**: Access to `/dev/kvm` (join the `kvm` group, or
  `sudo chmod 666 /dev/kvm`).

**Switching / tuning (environment variables):**

| Variable | Default (launcher) | Description |
|------|------|------|
| `EMULIN_BACKEND` | `auto` | `auto` (auto-detect HW virtualization) / `native` (force) / `software` (force) |
| `EMULIN_NATIVE_POOL_MB` | `2048` | Guest physical pool (MB) for the native backend. Increase for large git clones, etc. |

> The software backend is the **canonical (reference) for correctness** and is
> always maintained. The regression suite always passes on software, and native
> is **byte-identical** to software (verified by native-oracle). When in
> trouble, or for mremap-heavy workloads like `apt` (issue #304), you can run
> reliably with `EMULIN_BACKEND=software`. macOS's Hypervisor.framework (HVF) is
> planned for the future (issue #306).

## How to build

```bash
git clone https://github.com/kiyoka/emulin.git
cd emulin
mvn package -DskipTests
```

Artifacts:
- `target/emulin-<version>-all.jar` (fat jar, JLine bundled)

## Testing

```bash
make -C tests/binaries        # build the x86 / x86-64 test binaries
tests/scripts/run-fast.sh     # lightweight subset (~27s, excludes real-* / dist, 146 cases)
tests/scripts/run-all.sh      # all tests (~4m, 230 cases)
tests/scripts/run-network.sh  # network-related only (~3m, includes HTTPS clone)
```

Under parallel load, 1-3 timing flakes occasionally appear, but all PASS
standalone.

## Performance

### `-XX:-DontCompileHugeMethods` (required)

When running real binaries, always add **`-XX:-DontCompileHugeMethods`**:

```bash
java -XX:-DontCompileHugeMethods -jar emulin-*-all.jar ...
```

Without this flag, the emulator's core dispatch loop (`Cpu64::decode_and_exec`,
20K+ bytecode) is rejected for JIT C2 compilation by the JVM's `HugeMethodLimit`
(default 8000 bytes) and runs in interpreter mode. The flag gives a 28% speedup
on git clone over HTTPS (14.4s -> 10.4s).

The `emulin.sh` / `emulin.bat` launchers add this flag automatically.

### `EMULIN_USE_JIT=1` (optional, Phase 34-A3/A5)

A built-in basic-block JIT translates x86-64 instructions to Java bytecode at
runtime. It is off by default, but gives a speedup on crypto workloads:

| Workload | no JIT | with JIT | Effect |
|----------|-------:|---------:|------|
| curl https://example.com  | 9.3s | 8.1s | -14% |
| curl https://github.com (570KB) | 10.4s | 9.1s | -13% |
| sha256sum 5MB             | 2.4s | 2.3s | -5%  |

For short cold-start workloads such as launching vim, it is neutral to slightly
unfavorable (offset by JIT compile cost). It is effective for HTTPS / SIMD-heavy
workloads:

```bash
EMULIN_USE_JIT=1 java -XX:-DontCompileHugeMethods -jar emulin-*-all.jar ...
```

## Known limitations

- Some Python 3 syscalls (signalfd4 / pidfd_open, etc.) are unsupported (these
  are optional paths, so it normally works)
- The **software backend** runs much slower than the host (~100x for curl HTTPS,
  ~13x for git clone). Where HW virtualization is available, the **native
  backend (Hyper-V / KVM, default auto)** speeds up compute by ~200x
- WSL DrvFs (`/mnt/c/...`) has slow I/O -> place the sandbox under Linux /tmp etc.
- AI-agent–specific limitations (Claude Code version ceiling, tmux, etc.):
  see [Known limitations (AI agents)](#known-limitations-ai-agents)

## Directory layout

```
src/main/java/emulin/        Emulin core
  Cpu.java (i386), Cpu64.java (x86-64), AbstractCpu.java
  Syscall.java, SyscallI386.java, SyscallAmd64.java
  Elf.java, ElfCache.java, Segment.java, Section.java, Memory.java
  Process.java, Kernel.java, Thread64.java, FutexManager.java
  device/Console.java, StdConsole.java, JLineConsole.java
  jit/Translator.java, jit/CompiledInsn.java  (Phase 34-A3/A5 JIT)

dist/
  build-dist.sh             distribution zip build script
  build-sandbox.sh          sandbox build script
  launchers/emulin.sh / .bat startup launchers
  README.txt                README for the distribution zip

tests/
  binaries/src/             x86 / x86-64 test ELF sources
  scripts/                  regression test runner scripts
  expected/                 expected output (stdout / exit / argv / stdin)
```

## History

`.claude/CLAUDE.md` contains a per-phase development log (summaries of each phase
of modernization + 64-bit extension + real-binary support, and the cumulative
patterns of known bugs).

## Contact

- Bugs, requests, questions: <kiyokasumibi@gmail.com>
- GitHub Issues: https://github.com/kiyoka/emulin/issues
