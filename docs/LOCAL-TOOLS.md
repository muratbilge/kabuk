# Local tools in the Phone tab

Design note for the Phone tab's built-in toolset (request: `TASK-LOCAL-TOOLS.md`, decisions:
`TASK-LOCAL-TOOLS-PLAN.md`). Everything marked *verified* was run on the emulator (AVD `hardio-e2e`,
Android 15 / API 35, x86_64) on 2026-11-14; Murat's phone (OnePlus CPH2449, Android 16, arm64) is verified by the
live log after the 0.7.0 update.

## Why tools live in nativeLibraryDir

Kabuk targeted SDK 35 until 0.7.2 and targets SDK 28 since 0.8.0 (groundwork for Termux packages, phase B of
TASK-LOCAL-TOOLS-PLAN.md); the tools stay in nativeLibraryDir regardless. Since Android 10 an app with targetSdk >= 29 cannot exec a file it wrote into its own data
dir (W^X; SELinux denies `execute` on `app_data_file` for `untrusted_app`). Termux avoids this only by staying on
targetSdk 28. The files the package installer extracts to `applicationInfo.nativeLibraryDir` (`/data/app/…/lib/<abi>`,
label `apk_data_file`) can be exec'd. So:

- every tool is an executable (linked against bionic) shipped as `app/src/main/jniLibs/<abi>/lib<name>.so`;
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
| busybox | 0.71 MB per ABI (dynamic since 0.7.2; 1.24 MB static before), uncompressed | 0.44 MB compressed in the APK (legacy packaging) |
| rclone | 88.6 MB arm64 / 120.9 MB x86_64, uncompressed | 28.3 MB compressed in the APK |
| kabuk-open | 7 KB | |

Budget for busybox + rclone: release ≤ 45 MB. Step 3 (rclone): release APK 43.9 MB (43,947,973 bytes, unsigned Gradle output; was 15.5 MB with busybox
only); debug APK with both ABIs ~96 MB. On the phone the installer extracts the libraries, so the installed app takes
~90 MB more than the APK. Step 3b (dynamic busybox): release APK 43.7 MB (43,675,497 bytes, unsigned Gradle output).

## busybox build (`scripts/build-tools.sh`)

- Source: `https://busybox.net/downloads/busybox-1.37.0.tar.bz2`,
  SHA-256 `3311dff32e746499f4df0d5df04d7eb396382d7e108bb9250e7b519b837043a4` (checked on every build).
- Toolchain: NDK 29.0.14206865 clang, `--target=<aarch64|x86_64>-linux-android28`, linked **dynamically** against
  bionic (`CONFIG_STATIC` off; the script refuses a static config). Only a dynamic bionic program loads
  `libnetd_client.so` and so resolves names through netd (see "Network" below); 0.7.0/0.7.1 shipped a static
  build that had no DNS. API 28 because busybox uses glob, getrandom and syncfs (bionic API 28): **busybox needs
  Android 9+**. The app's minSdk stays 26; on Android 8.x the linker refuses the binary, the self-test logs
  `busybox: FAILED (…)`, `LocalTools` does not use it as the shell (`BUSYBOX_MIN_SDK`) and the Phone tab is
  `/system/bin/sh` as without busybox. No zig/musl needed.
  `readelf -d`: NEEDED `libm.so`, `libdl.so`, `libc.so` only; interpreter `/system/bin/linker64`; FLAGS BIND_NOW,
  PIE (both ABIs, *verified*).
- Config: `defconfig`, then off: applets that don't build against bionic (hush, mount, swapon, tc, ifconfig/arp,
  ipcs, minix fs, utmp/wtmp users), need root/setuid or a console (whole `console-tools` except `clear`/`reset`,
  `init`, `loginutils`, `modutils`, `sysklogd`, `runit`, `selinux`, servers like httpd/telnetd/ntpd/crond),
  `nslookup` (needs libresolv), x86 SHA-NI code (doesn't build for arm64 in 1.37.0), and busybox's own
  passwd/group reader (bionic's `getpwuid` knows Android ids: `id` → `uid=10150(u0_a150)`, *verified*).
  Result: 264 applets, all of the requested list among them.
- No source edits. (The static build needed two, because busybox's own `getsid`/`sethostname`/`adjtimex`/`strchrnul`
  clashed with bionic's `libc.a`; against `libc.so` the duplicates are harmless and the edits were dropped.)
- Cache: `~/.cache/kabuk-tools/busybox-1.37.0-<abi>/` with `libbusybox.so`, the final `busybox.config` and a stamp
  (hash of `build_busybox` and the busybox settings, `tool_stamp` in the script; every tool has its own stamp, so
  a busybox change does not rebuild rclone); a re-run is a copy, an edited busybox part rebuilds busybox. `scripts/release.sh` runs the script first
  and refuses to release when a tool is missing for arm64-v8a.

busybox decides which applet to run from the basename of `argv[0]`, so `libbusybox.so` called by its own name
answers "applet not found". The app always runs it through `files/bin/busybox` or an applet link.

## rclone build (`scripts/build-tools.sh`)

- Source: rclone 1.76.0, the release's own tarballs from `https://downloads.rclone.org/v1.76.0/`:
  `rclone-v1.76.0.tar.gz` (SHA-256 `fa2b2e4ae56c4f1b91102285b736596e2a028be77cb2aaa5a0df53f3a7fc5902`) and
  `rclone-v1.76.0-vendor.tar.gz` (all Go modules, SHA-256 `7706e9d6b11971a096406a1e876db3946c73f11a64327a7457545443b13f4cc0`),
  both listed in the signed `SHA256SUMS` of that release. With the vendor tree the build is offline
  (`-mod=vendor`, `GOPROXY=off`, `GOTOOLCHAIN=local`): no module cache, nothing fetched at build time.
- Toolchain: Go 1.27.1 (`~/.local/go/bin/go`, rclone 1.76 needs ≥ 1.26), `GOOS=android GOARCH=arm64|amd64
  CGO_ENABLED=1 CC="<NDK clang> --target=<triple>26"`, `-trimpath -buildvcs=false -ldflags "-s -w -buildid=
  -X github.com/rclone/rclone/fs.Version=v1.76.0"`. The result is a PIE linked **dynamically** against bionic
  (`NEEDED libc.so libdl.so liblog.so`, interpreter `/system/bin/linker64`, "for Android 26"): name lookups go
  through bionic's `getaddrinfo` → netd, which is exactly what the static busybox lacks. API 26 = the app's
  minSdk. The official `linux-arm64` build is not used: a static Linux Go binary looks for `/etc/resolv.conf`,
  which Android doesn't have.
- `rclone version` on the emulator (*verified*): `rclone v1.76.0 / os/type: android / os/arch: amd64 /
  go/version: go1.27.1 / go/linking: dynamic / go/tags: none`.
- *Verified*: DNS + TLS work from the Phone tab: `rclone lsf --http-url https://downloads.rclone.org :http:` lists
  the site (Go reads Android's CA store in `/system/etc/security/cacerts`).
- Reproducibility: a second run of the script (after an edit of the script, so everything was rebuilt, with a warm
  Go build cache) gave byte-identical `librclone.so` and `libbusybox.so` for both ABIs. A cold-cache rebuild was
  not compared.
- Cache: `~/.cache/kabuk-tools/rclone-1.76.0-<abi>/` (+ `gocache/` for Go's build cache). The first build takes
  ~6 min for both ABIs on DevBox, a cached run is a copy.

## Opening URLs: `xdg-open`, `open`, `termux-open-url`

CLI tools that want a browser run `xdg-open <url>` (rclone's OAuth helper does exactly that, through
`github.com/skratchdot/open-golang`). On Android a program cannot start an activity, and the app cannot run a
script it wrote:

- *Verified*: a `#!/system/bin/sh` script in `files/home` (mode 755, label `app_data_file`) run from the Phone tab
  fails with `Permission denied` (exit 126): the kernel checks `execute` on the script file itself, which W^X
  denies like for any other file in the app's data dir. So the helper cannot be a script.
- Instead `scripts/tools/kabuk-open.c` (~50 lines, Apache-2.0, built by `scripts/build-tools.sh` with the NDK as a
  7 KB dynamic PIE, `libkabuk-open.so`) is a small program in nativeLibraryDir. `files/bin/xdg-open`, `open`,
  `termux-open-url` and `kabuk-open` link to it (`LocalTools.ALIASES`; busybox applets never take those names).
  It accepts exactly one `http://` or `https://` URL (no control characters, ≤ 8 KB), writes it to
  `$KABUK_OPEN_DIR/.<ms>-<pid>.tmp` and renames it to `<ms>-<pid>.url`, exit 0. Anything else: a message on stderr
  and exit 1 (`KABUK_OPEN_DIR` unset: exit 3).
- `UrlOpener` (app side) watches `files/open` with a `FileObserver` (MOVED_TO/CLOSE_WRITE, started with the first
  Phone tab, leftovers of an earlier process are deleted unopened), reads + deletes each `.url`, checks it again
  (http/https with a host, no whitespace or control characters) and starts `ACTION_VIEW` +
  `CATEGORY_BROWSABLE` + `FLAG_ACTIVITY_NEW_TASK`. No `am`, no other schemes (`file:`, `intent:`, `javascript:`,
  `content:` are refused, *verified*: `xdg-open file:///etc/hosts` → exit 1; a hand-written `javascript:` file →
  `OPEN: refused a non-http(s) URL from a program`).
- In the background Android blocks starting an activity (BAL); then a snackbar "A program wants to open
  https://host · Open" waits for the user (*verified*: `sleep 6; xdg-open https://example.net/bg`, home pressed →
  `OPEN: xdg-open while in the background: https://example.net (offered)`, the snackbar is there on return).
- The environment has `KABUK_OPEN_DIR=<files>/open` and `BROWSER=xdg-open` (for tools that read `$BROWSER`).
- Live log: tag `OPEN`, only scheme + host (`OPEN: xdg-open: http://127.0.0.1:53682`); OAuth state/code never.
- *Verified*: `xdg-open https://example.com/some/path?token=secret` → `ActivityTaskManager: START …VIEW…
  cmp=org.chromium.webview_shell/.WebViewBrowserActivity … (BAL_ALLOW_VISIBLE_WINDOW)`, the browser is
  `topResumedActivity`, the log line says only `https://example.com`.

Fallback, for programs that only print a URL: while a Phone tab is on screen, the lowest `http(s)://` URL on the
screen (joined across wrapped rows, like tapping a link) is offered as a chip "Open link · host ×" at the top right,
one at a time; tapping opens it like above, × dismisses it for good. URLs a program already opened through
`xdg-open` are not offered (*verified*: `rclone authorize` shows no chip; `echo https://www.iana.org/…` shows
"Open link · www.iana.org", tapping it starts the browser). Tapping a link in the terminal still opens the existing
link dialog.

## `rclone authorize` on the phone

*Verified on the emulator* (the real login is Murat's):

1. `rclone authorize "pcloud"` in the Phone tab starts its listener on `127.0.0.1:53682` (`netstat -ltn`) and
   runs `xdg-open http://127.0.0.1:53682/auth?state=…` → Kabuk starts the browser (`OPEN: xdg-open:
   http://127.0.0.1:53682`), the browser loads rclone's `/auth`, rclone redirects it to
   `https://my.pcloud.com/oauth2/authorize…`: pCloud's "Connect rclone.org to your account" page is shown.
2. Kabuk in the background (home screen on top, process state 4 = foreground service, `SessionService`
   `isForeground=true`): `printf 'GET /?state=x&code=y HTTP/1.0\r\n\r\n' | nc 127.0.0.1 53682` from `adb shell`
   gets rclone's answer (`HTTP/1.0 400 Bad Request`, "Auth state doesn't match"), and the browser opening
   `http://localhost:53682/?state=bogus&code=y` (the shape of pCloud's real redirect, `localhost`, not 127.0.0.1)
   reaches rclone too: it prints `Auth state doesn't match … got "bogus"`. So pCloud's redirect will reach the
   listener while the browser has the screen; with the right state rclone exchanges the code and prints the token.
3. The process stays alive because `SessionService` (specialUse foreground service) runs while any tab is open,
   Phone tab included (`TabManager.tabsChanged`).

Note: rclone's failure page for a wrong state shows the expected state; harmless (only the phone itself can reach
127.0.0.1:53682), but don't paste that page anywhere.

## Bin dir, shell and environment (`term/LocalTools.kt`)

- On every Phone-tab start `LocalTools.prepare` checks `files/bin/.stamp` = `<versionCode> <nativeLibraryDir>
  <BinDir.LAYOUT>` (LAYOUT is bumped when the set of links changes).
  The path is part of the stamp because nativeLibraryDir changes on every install, also a reinstall with the same
  versionCode (*verified*: `adb install -r` → new `/data/app/~~…/` path → bin rebuilt, links point to the new dir).
- Rebuild: `bin.new/` gets `busybox -> lib…/libbusybox.so`, then `bin.new/busybox --list` (5 s limit) and one
  link per applet; names of later Kabuk tools and helper scripts are never taken by an applet. Then `bin.new`
  replaces `bin`.
- Applets Android does better are not linked (`LocalTools.PREFER_SYSTEM`: `ping`, `ping6`, `traceroute6`), so PATH
  finds `/system/bin/…`. busybox ping wants a raw socket (`ping: permission denied (are you root?)` on the phone);
  Android's ping uses ICMP "ping sockets", which apps may open. Android 15 has `ping`, `ping6`, `traceroute6`,
  `tracepath` in `/system/bin` but no IPv4 `traceroute`, so busybox keeps that one (it needs root too).
  *Verified*: `which ping` → `/system/bin/ping`; `ping -c1 google.com` → 1 received, exit 0; `ping -c1 1.1.1.1` ok.
- Shell: `files/bin/sh` (busybox ash) with argv[0] `-sh` = login shell. `~/.profile` is written once (PS1, two
  aliases) and never overwritten (*verified*). Without busybox in the APK: `/system/bin/sh` as before.
- `<files>` is `filesDir.canonicalFile`: `filesDir` is `/data/user/0/app.kabuk/files`, a path through a
  symlink; the shell's cwd read back with getcwd is `/data/data/app.kabuk/files/home`, and ash's `\w` only shows `~`
  when HOME is that same string. 0.7.1 showed `/data/data/app.kabuk/files/home $`; now `~ $` (*verified*:
  `HOME=PWD=pwd=/data/data/app.kabuk/files/home`).
- Environment: `PATH=<files>/bin:/system/bin:/system/xbin`, `HOME=<files>/home`, `TMPDIR=<cache>/tmp` (created),
  `SHELL=<files>/bin/sh`, `PREFIX=<files>`, `TERM=xterm-256color`, `COLORTERM=truecolor`, `LANG=en_US.UTF-8`,
  `ANDROID_DATA`, `ANDROID_ROOT`.

## Self-test in the live log

Once per process, when the first Phone tab opens, a background thread logs (tag `TOOLS`, paths and versions only):

```
TOOLS: abi=x86_64, nativeLibraryDir=/data/app/~~…==/app.kabuk-…==/lib/x86_64, bin=268 links
TOOLS: busybox: ok (BusyBox v1.37.0 () multi-call binary.)
TOOLS: rclone: ok (rclone v1.76.0)
TOOLS: kabuk-open: ok (kabuk-open 1)
```

The self-test runs each tool with the Phone tab's environment (without `HOME`, rclone's first line is an error
about the missing home directory).

or `busybox: FAILED (exit 126 / Permission denied …)` / `busybox: missing (not in this APK)`. This is how exec on
Murat's phone is verified: he opens a Phone tab after the update, the orchestrator reads `debug/CPH2449.log`.

## Network: DNS

Android has no `/etc/resolv.conf`; apps resolve names through netd (`/dev/socket/dnsproxyd`), which a dynamically
linked bionic program reaches via `libnetd_client.so`. A static binary does not load it: the static busybox of
0.7.0/0.7.1 answered `wget http://example.com` with `bad address 'example.com'` (on the phone also
`ping google.com`). Since 0.7.2 busybox is linked dynamically and resolves names like rclone. *Verified* in the
Phone tab (emulator, 0.7.2 debug build): `wget -O- http://example.com | head -3` → `Connecting to example.com
(172.66.147.243:80)` and the page; `nc -z example.com 80` → exit 0; `ping -c1 google.com` (→ `/system/bin/ping`)
→ 1 received. `ping6` has no IPv6 route on the emulator (100% loss, no error). busybox wget also has no TLS certificate validation ("TLS certificate validation not implemented").

## Licences

BusyBox is GPL-2.0: `assets/licenses/busybox-GPL-2.0.txt` (busybox's own LICENSE file) and
`assets/licenses/busybox-SOURCE.txt` (version, tarball URL, SHA-256, the build script and a written offer), shown
in Settings → Licences together with the font's OFL. busybox is a separate program run by the Phone tab; Kabuk's
own code stays Apache-2.0.

rclone is MIT: `assets/licenses/rclone-MIT.txt` (its COPYING), `rclone-SOURCE.txt` (version, both tarball URLs +
SHA-256, the build script, a written offer) and `rclone-THIRD-PARTY.txt`: the licence/notice files of the 315 Go
modules in its vendor tree (193 distinct texts; byte-identical texts listed once with all their modules), made by
`scripts/tools/third-party-notices.py` from the vendor tarball on every `build-tools.sh` run (deterministic, so the
committed file only changes with a new rclone). Among them are MPL-2.0 and one LGPL-3.0 module
(`github.com/cloudsoda/sddl`); their source is in the vendor tarball named in `rclone-SOURCE.txt`, and the build
script lets anyone relink. The Licences dialog shows long texts paragraph by paragraph in a lazy list (the notices
are ~500 KB).

## Phase B: Termux packages (0.8.1, `term/TermuxEnv.kt`, `TermuxInstaller.kt`, `TermuxBootstrap.kt`)

Kabuk targets SDK 28 since 0.8.0, so the SELinux domain is `untrusted_app_27`, which may exec files in the app's
data dir (W^X starts at target 29). That lets Termux's own packages run from `files/usr`, unchanged except for
their prefix. Everything below is *verified* on the emulator (android-35 x86_64, 0.8.1 debug build, 2026-11-14),
both on a fresh install and on an upgrade from a 0.8.0 build.

**Relocation.** Termux builds every package for `/data/data/com.termux/files/usr`. `com.termux` (10 bytes) and
`app.kabuk/` have the same length, so binaries are rewritten in place: `data/data/com.termux` →
`data/data/app.kabuk/` (`/data/data/app.kabuk//files/usr` is a valid path; ELF strings, RUNPATH, .pyc keep their
offsets). Text files (no NUL byte) get the clean `data/data/app.kabuk`. `SYMLINKS.txt` targets likewise. dpkg's
conffile MD5s (`var/lib/dpkg/status`) and `info/*.md5sums` are recomputed from the relocated files, otherwise every
upgrade would ask about "modified" conffiles (510 checksums in the 2026.11.08 bootstrap). Only works for user 0
(`/data/data/app.kabuk/files` must be the same inode as `filesDir`: on Android 15 `/data/user/0` and `/data/data`
are separate mounts, so the canonical paths differ and `Files.isSameFile` is the check) and for arm64/x86_64.

**Bootstrap.** Pinned in `TermuxEnv.BOOTSTRAP_TAG` + `SHA256` per arch (`bootstrap-2026.11.08-r1+apt.android-7`,
aarch64 33.1 MB, x86_64 33.0 MB; 3780 entries, 3484 files, 88 MB + 1215 links). `scripts/termux-bootstrap-pin.sh`
prints the newest tag and the digests from the GitHub API (`VERIFY=1` downloads and checks them) as Kotlin lines.
The install (a thread; `TabManager.keepAlive` keeps `SessionService` running even with no tab open):
1. free space ≥ 250 MB, else a message;
2. download `github.com/termux/termux-packages/releases/download/<tag>/bootstrap-<arch>.zip` into
   `cacheDir/termux/…zip.part`, resuming with `Range` after a broken connection (*verified*: cut at 28 MB, Retry
   fetched 4856 KB), then SHA-256; a mismatch deletes it;
3. extract entry by entry (`ZipFile`, one entry in memory at a time) with relocation into `files/usr-staging`;
   programs (`bin/`, `libexec`, apt's methods/helper) 0700 as the Termux app does;
4. dpkg checksums, the apt hook, the mirror choice and the first-update script into the staging dir;
5. one `rename` to `files/usr`; the zip is deleted.
Cancel (polled per 64 KB and per entry) or a failure removes `usr-staging`; Cancel and non-network failures also the
partial download, network failures keep it for Retry. A process killed mid-install leaves `usr-staging`, removed the
next time the installer is used. Emulator timings: download 32 MB 7.7 s, extract + relocate 12-29 s (DevBox under load).

**apt hook.** `etc/apt/apt.conf.d/99kabuk-relocate`: `DPkg::Pre-Install-Pkgs` runs
`usr/libexec/kabuk/relocate-debs` on every .deb before dpkg: `dpkg-deb -R`, move `data/data/com.termux` →
`data/data/app.kabuk`, rewrite contents (binary same length, text clean) back into the same inode (`sed -i` would
reset modes), symlink targets, regenerate md5sums, maintainer scripts 0755 (`dpkg-deb -b` refuses others), repack
uncompressed. `APT::Keep-Downloaded-Packages "false"`: the repacked .debs are uncompressed and would fill the cache
(after `pkg install python git`: `cache/` 5.3 MB). The hook is unit-tested against a real `dpkg-deb` on DevBox
(`TermuxBootstrapTest.relocateDebsHook`).

**Mirror and first login.** `etc/termux/chosen_mirrors` → `mirrors/europe` (what `termux-change-repo` makes): pkg
tests that group and picks by weight (B0 without it got Chinese mainland mirrors). `etc/profile.d/
zz-kabuk-first-update.sh` runs after Termux's own second stage (`01-termux-bootstrap-second-stage-fallback.sh`) on
the first interactive login, does `pkg update` and removes itself when that worked; offline it says so and tries
again in the next shell. *Verified*: "Picking mirror: … chosen_mirrors/packages.termux.dev" / "termux.3san.dev",
"Kabuk: ready".

**The Phone tab.** With `files/usr/bin/login` present a Phone tab runs `login` (argv0 `-login`, cwd `files/home`,
the same HOME as busybox, so `~` survives the switch) with Termux's environment (`PREFIX`, `TMPDIR=$PREFIX/tmp`,
`TERMUX_*`, `LD_PRELOAD` from termux-exec set by login) and `PATH=files/kabuk/bin:$PREFIX/bin`. `files/kabuk/bin`
(not owned by a package) holds `termux-setup-storage` (a script) and `xdg-open`/`termux-open-url`/`kabuk-open`,
links to `libkabuk-open.so`, re-linked on every tab start because nativeLibraryDir moves on updates. busybox stays:
"Phone (busybox)" (+ menu, Servers screen ⋮) opens the old shell; the busybox self-test still runs.
*Verified*: `bash 5.3.20`, `u:r:untrusted_app_27`, `pkg install python git` (with their dependencies),
`python3 -c 'import ssl,sqlite3'` → 3.14.10, `git init/commit`.

**Consent and UI.** `InstallMachine` (pure, `TermuxInstallStateTest`): the first Phone tab (pref `termux/offered`)
→ sheet Install / Not now; Not now = Idle, asked again only from "Install Termux packages…"; Install →
Downloading(bytes,total) → Verifying → Extracting(done,total) → Done | Failed(message) → Retry; Cancel → Idle;
progress after a cancel is ignored. `ui/TermuxUi.kt` shows it as a bottom sheet over every screen; Hide lets it run
in the background, the result sheet comes anyway. Done offers "Restart shell" (closes busybox Phone tabs, opens a
Termux one) or "Open Phone tab".

**Storage.** `READ/WRITE_EXTERNAL_STORAGE` + `requestLegacyExternalStorage`; at target 28 that is the legacy /sdcard
view. `termux-setup-storage` drops `files/open/storage.req`; `UrlOpener` sees it and `StorageAccess` brings Kabuk
forward with the system dialog ("Allow Kabuk to access photos, videos, music, audio, and other files"), then makes
`~/storage/{shared,downloads,dcim,pictures,music,movies,documents}`. *Verified*: read a file adb put in Download,
wrote one back.

**Logs.** Tag `TERMUX`: offered / not now / install / retry / cancel, the result (`installed: downloaded 32263 KB in
7750 ms; 3484 files (88 MB, 695 relocated, 336 binary), 1215 links, 510 dpkg checksums fixed, extract 12005 ms`),
failures with the exception class (`install failed: UnknownHostException …`); tag `STORAGE`. Never file contents,
never terminal text.

## Phase B hardening and Termux app commands (0.8.2)

*Verified* on the emulator (android-35 x86_64, debug build of 0.8.2's code, 2026-11-14) unless marked otherwise.

**Deletes never follow symlinks (`term/SafeDelete.kt`).** Kotlin's `File.deleteRecursively` decides "directory?"
with `File.isDirectory`, which follows links. In 0.8.1 `StorageAccess.link` cleared `~/storage` with it before
re-linking, so a *second* `termux-setup-storage` walked through `~/storage/shared` and deleted everything on shared
storage the app could write (reproduced on the emulator with the 0.8.1 code: `/sdcard` was left with only
`Android/`). `SafeDelete.tree` uses `Files.walkFileTree` without FOLLOW_LINKS: a symlink is unlinked, never
entered; read-only dirs are made writable first. Used for `usr`, `usr-staging`, `usr-old`, the download cache,
`~/storage` and busybox's `files/bin`. *Verified*: `termux-setup-storage` twice, then `/sdcard/Download/victim.txt`
and `/sdcard/DCIM/p.jpg` still there.

**Repair and leftovers.** "Repair Termux packages…" (Phone tab ⋮ once installed; Servers screen ⋮ on the Phone
shell row) asks first ("installed packages are removed, your home folder stays"), then runs the same install over a
fresh `usr-staging`; the swap is `usr` → `usr-old`, `usr-staging` → `usr` (rolled back if the second rename fails),
then `usr-old` is deleted, so a failed repair leaves the old prefix working. `InstallMachine.repairing` changes the
sheet's texts and makes "Restart shell" restart the open Termux tabs too. At app start
(`TermuxInstaller.removeStale`) a leftover `usr-staging`/`usr-old` from a killed install is removed in the
background; an install started meanwhile waits for it. *Verified*: home marker kept, `tree`/`ssh` gone, `nano`/`bash`
back; a planted `usr-staging` with a link to `/sdcard/Download` and `usr-old` with a link to DCIM were removed at the
next start (`removed a stale staging dir from an unfinished install (17 ms)`), the targets intact.

**"Open link" chip (`term/ScreenLinks.kt`).** Only a URL within the 8 rows above the last non-blank row, only once
the screen (text + cursor) has been unchanged for 1.5 s (`ChipGate`; a chip already shown stays while it is still
the one found), and never on a package-manager line (`Get:`, `Hit:`, `Ign:`, `Err:`, `Picking mirror`, `Mirror`,
`Fetched`, `Reading package lists`, `Failed to fetch`, `InRelease`, pkg's `[*] (n) https://…: ok` lines) or for a
host of the configured mirrors (`etc/apt/sources.list*`, `etc/termux/mirrors/**`) or `packages*.termux.dev`.
*Verified*: no chip at any time during and after the first `pkg update` (polled every second), and
`echo https://example.com/kabuk-test` gets "Open link · example.com".

**`dpkg -i` by hand.** `files/kabuk/bin/dpkg` (first on PATH, not owned by a package): for `-i`/`--install`/
`--unpack` it copies each `.deb` argument into a temp dir, runs `relocate-debs` on the copies and calls the real
`usr/bin/dpkg` with them (the user's file stays as downloaded); everything else is passed straight through. apt
calls `usr/bin/dpkg` by its full path and relocates through its hook anyway. Not via `dpkg-divert`: kabuk/bin
survives package upgrades without touching dpkg's database. `dpkg -R` prints a hint (not relocated).
*Verified*: `apt download tree` then `dpkg -i tree_*.deb` → `kabuk: relocated 1-tree_2.3.2_x86%5f64.deb`,
`tree v2.3.2`, `dpkg -L tree` shows `/data/data/app.kabuk/files/usr/bin/tree`.

**Conffiles.** After `pkg install openssh`, `pkg upgrade -y` (4 packages: ca-certificates, command-not-found,
liblz4, p11-kit) and `pkg reinstall -y openssh nano`: no "Configuration file … modified" question; `dpkg --verify`
lists only `etc/apt/sources.list`, which pkg itself rewrites when it picks a mirror (Termux does the same; not
caused by relocation, but `apt` may ask about it when a new `apt` ships a changed sources.list).

**Termux app commands.** libkabuk-open.so (scripts/tools/kabuk-open.c, version 2) is linked in `files/kabuk/bin`
as `am`, `termux-am`, `termux-open`, `termux-wake-lock`, `termux-wake-unlock`, `termux-reload-settings` (plus the
URL names as before); `xdg-open`/`open` with something that isn't an http(s) URL work like `termux-open`. These
names write `<time>-<pid>.req` into `files/open` ("kabuk-req 1", cwd, name, args; NUL-terminated); `UrlOpener`'s
watcher hands it to `term/KabukCommands.kt`, which parses it (pure, `KabukCommandsTest`), runs it on the main thread
and writes `<time>-<pid>.res` (exit code, then text); the program prints it and exits with that code (no answer in
10 s: exit 1). termux-tools' own scripts in `usr/bin` call `am …com.termux…`, which lands in Kabuk's `am` and is
mapped: `startservice -a com.termux.service_wake_lock|unlock` → wake lock, `broadcast -n
com.termux/com.termux.app.TermuxOpenReceiver` → termux-open, `broadcast -a com.termux.app.reload_style` → reload.
- `termux-wake-lock`/`-unlock`: `SessionService.setWakeLock` takes/releases the partial wake lock + Wi-Fi lock of the
  notification's button; the notification says "wake lock on (CPU stays awake)". *Verified*: `dumpsys power` shows
  `PARTIAL_WAKE_LOCK 'kabuk:sessions'` after it and `REL kabuk:sessions` after unlock, also through Termux's own
  `usr/bin/termux-wake-lock` script.
- `termux-open [--send|--view] [--chooser] [--content-type T] <file|url>`: http(s) through `UrlOpener` (also from the
  background, as a snackbar); mailto/tel/sms/geo/market as VIEW; files as `content://app.kabuk.files/…`
  (androidx FileProvider, `res/xml/file_paths.xml`: filesDir and shared storage; a path under
  `/data/data/app.kabuk/files` is mapped onto filesDir first, the provider compares canonical paths and on
  Android 15 `/data/user/0` is a separate mount) with a read grant; MIME type from the extension. *Verified*:
  `termux-open pic.png` opened Gallery with the content URI, `--send` the share sheet, an https URL the browser.
- `am`/`termux-am`: `start`, `broadcast` with `-a -d -t -c -n -f --es/-e --esn --ez --ei --el --ef --eu --esa --user
  0|current --grant-read-uri-permission -W`; other subcommands/options, other services, `com.termux*` targets
  (Termux:API) → exit 1 with "… is not supported in Kabuk". Real `am` (app_process) is shadowed.
- `termux-reload-settings`: `~/.termux/termux.properties` (or `~/.config/termux/`): `terminal-transcript-rows`
  (100..50000 → scrollback of new tabs), `bell-character` (vibrate|ignore), `volume-keys` (virtual|volume) go into
  Kabuk's settings; the other keys are listed as ignored (extra-keys: Kabuk has its own key bar; colors.properties,
  font.ttf: Settings → Theme). *Verified*: "Applied: terminal-transcript-rows=12000 (new tabs), bell-character=ignore /
  Ignored (not supported in Kabuk): extra-keys, use-black-ui".
- `termux-info` (a script in kabuk/bin): Kabuk's version (`KABUK_VERSION`), architecture, repositories, mirror group,
  upgradable packages, termux-tools version, package count, Android/kernel/device, LD_PRELOAD.
- busybox's shell gets `termux-open`, `termux-wake-lock`, `termux-wake-unlock` too (not `am`: `/system/bin/am`
  stays reachable there; BinDir layout 3). *Verified*: `termux-wake-lock`/`-unlock` in a "Phone (busybox)" tab.
Logs: tag `CMD`, the command and the intent's action / scheme + host (`dat=geo:…`), never full URIs, paths or extras.
