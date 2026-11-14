# Local tools in the Phone tab

Design note for the Phone tab's built-in toolset (request: `TASK-LOCAL-TOOLS.md`, decisions:
`TASK-LOCAL-TOOLS-PLAN.md`). Everything marked *verified* was run on the emulator (AVD `hardio-e2e`,
Android 15 / API 35, x86_64) on 2026-11-14; Murat's phone (OnePlus CPH2449, Android 16, arm64) is verified by the
live log after the 0.7.0 update.

## Why tools live in nativeLibraryDir

Kabuk targets SDK 35. Since Android 10 an app with targetSdk >= 29 cannot exec a file it wrote into its own data
dir (W^X; SELinux denies `execute` on `app_data_file` for `untrusted_app`). Termux avoids this only by staying on
targetSdk 28. The files the package installer extracts to `applicationInfo.nativeLibraryDir` (`/data/app/…/lib/<abi>`,
label `apk_data_file`) can be exec'd. So:

- every tool is a static executable shipped as `app/src/main/jniLibs/<abi>/lib<name>.so`;
- `packaging.jniLibs.useLegacyPackaging = true` makes the installer extract them (otherwise they stay inside the APK);
- `filesDir/bin/<name>` is a **symlink** to `<nativeLibraryDir>/lib<name>.so`: exec follows the link, the inode that
  is exec'd stays in nativeLibraryDir. Nothing is ever exec'd from filesDir or cacheDir.

*Verified* in the app's own SELinux domain (`u:r:untrusted_app:s0:c150,…`, a script typed into the Phone tab):
`ls`, `grep`, `vi --help`, `tar` (create + list a .tgz), `wget --help`, `awk`, `less`, `ps`, `df` all exec through
`files/bin`; a byte-identical **copy** of busybox in `cache/tmp` fails with `Permission denied` (exit 126). Note
that `adb shell run-as` is not proof either way: it runs in a different domain.

## ABIs and size

| | debug APK | release APK |
|---|---|---|
| ABIs with tools | arm64-v8a, x86_64 (emulator) | arm64-v8a only (`ndk.abiFilters` in the release build type) |
| busybox | 1.24 MB per ABI, uncompressed | stored compressed in the APK (legacy packaging) |

APK sizes are in `REPORT-claude.md`; budget for busybox + rclone (step 3): release ≤ 45 MB.

## busybox build (`scripts/build-tools.sh`)

- Source: `https://busybox.net/downloads/busybox-1.37.0.tar.bz2`,
  SHA-256 `3311dff32e746499f4df0d5df04d7eb396382d7e108bb9250e7b519b837043a4` (checked on every build).
- Toolchain: NDK 29.0.14206865 clang, `--target=<aarch64|x86_64>-linux-android35`, `CONFIG_STATIC=y` against the
  NDK's bionic `libc.a`. A static binary carries its libc, so API 35 only selects headers (glob, getrandom, syncfs
  are declared from API 28) and does not raise the app's minSdk 26. No zig/musl needed.
- Config: `defconfig`, then off: applets that don't build against bionic (hush, mount, swapon, tc, ifconfig/arp,
  ipcs, minix fs, utmp/wtmp users), need root/setuid or a console (whole `console-tools` except `clear`/`reset`,
  `init`, `loginutils`, `modutils`, `sysklogd`, `runit`, `selinux`, servers like httpd/telnetd/ntpd/crond),
  `nslookup` (needs libresolv), x86 SHA-NI code (doesn't build for arm64 in 1.37.0), and busybox's own
  passwd/group reader (bionic's `getpwuid` knows Android ids: `id` → `uid=10150(u0_a150)`, *verified*).
  Result: 264 applets, all of the requested list among them.
- Two source edits, applied by the script: busybox's own `getsid`/`sethostname`/`adjtimex`
  (`libbb/missing_syscalls.c`) and `strchrnul` (`include/platform.h`) are limited to old Android API levels, because
  bionic's static libc defines them too (duplicate symbols at link time).
- Cache: `~/.cache/kabuk-tools/busybox-1.37.0-<abi>/` with `libbusybox.so`, the final `busybox.config` and a stamp
  (hash of the script); a re-run is a copy, an edited script rebuilds. `scripts/release.sh` runs the script first
  and refuses to release when a tool is missing for arm64-v8a.

busybox decides which applet to run from the basename of `argv[0]`, so `libbusybox.so` called by its own name
answers "applet not found". The app always runs it through `files/bin/busybox` or an applet link.

## Bin dir, shell and environment (`term/LocalTools.kt`)

- On every Phone-tab start `LocalTools.prepare` checks `files/bin/.stamp` = `<versionCode> <nativeLibraryDir>`.
  The path is part of the stamp because nativeLibraryDir changes on every install, also a reinstall with the same
  versionCode (*verified*: `adb install -r` → new `/data/app/~~…/` path → bin rebuilt, links point to the new dir).
- Rebuild: `bin.new/` gets `busybox -> lib…/libbusybox.so`, then `bin.new/busybox --list` (5 s limit) and one
  link per applet; names of later Kabuk tools and helper scripts are never taken by an applet. Then `bin.new`
  replaces `bin`.
- Shell: `files/bin/sh` (busybox ash) with argv[0] `-sh` = login shell. `~/.profile` is written once (PS1, two
  aliases) and never overwritten (*verified*). Without busybox in the APK: `/system/bin/sh` as before.
- Environment: `PATH=<files>/bin:/system/bin:/system/xbin`, `HOME=<files>/home`, `TMPDIR=<cache>/tmp` (created),
  `SHELL=<files>/bin/sh`, `PREFIX=<files>`, `TERM=xterm-256color`, `COLORTERM=truecolor`, `LANG=en_US.UTF-8`,
  `ANDROID_DATA`, `ANDROID_ROOT`.

## Self-test in the live log

Once per process, when the first Phone tab opens, a background thread logs (tag `TOOLS`, paths and versions only):

```
TOOLS: abi=x86_64, nativeLibraryDir=/data/app/~~…==/app.kabuk-…==/lib/x86_64, bin=268 links
TOOLS: busybox: ok (BusyBox v1.37.0 () multi-call binary.)
```

or `busybox: FAILED (exit 126 / Permission denied …)` / `busybox: missing (not in this APK)`. This is how exec on
Murat's phone is verified: he opens a Phone tab after the update, the orchestrator reads `debug/CPH2449.log`.

## Network: DNS in static binaries

*Verified* on the emulator: busybox `wget http://example.com` → `bad address 'example.com'`, while
`wget http://1.1.1.1/` connects (and then fails on the redirect to a host name). Android has no `/etc/resolv.conf`;
apps resolve names through netd (`/dev/socket/dnsproxyd`), which a dynamically linked bionic program reaches via
`libnetd_client.so`. A static binary does not load it. Not fixed in this step. Options for later: a tiny dynamically
linked helper, or tools that link bionic dynamically (rclone is planned as `GOOS=android` + cgo for exactly this
reason). busybox wget also has no TLS certificate validation ("TLS certificate validation not implemented").

## Licences

BusyBox is GPL-2.0: `assets/licenses/busybox-GPL-2.0.txt` (busybox's own LICENSE file) and
`assets/licenses/busybox-SOURCE.txt` (version, tarball URL, SHA-256, the build script and a written offer), shown
in Settings → Licences together with the font's OFL. busybox is a separate program run by the Phone tab; Kabuk's
own code stays Apache-2.0.
