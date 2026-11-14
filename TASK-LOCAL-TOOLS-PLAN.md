# Local tools in the Phone tab: plan and briefs (orchestrator, 2026-11-14)

Source request: TASK-LOCAL-TOOLS.md (Murat chose option A: built-in toolset). This file holds the decisions
and the brief for each step. Workers: read both files, then only the step you were given.

## Decisions (made by the orchestrator; change one only after asking)

- **Exec path.** Tools ship as `app/src/main/jniLibs/<abi>/lib<name>.so` with
  `packaging { jniLibs { useLegacyPackaging = true } }`, so the installer extracts them to
  `applicationInfo.nativeLibraryDir`, the one place a targetSdk-35 app may exec its own files.
  Nothing is ever exec'd from filesDir/cacheDir.
- **ABIs.** Tools are built for `arm64-v8a` (Murat's phone, OnePlus CPH2449, Android 16) and `x86_64`
  (the emulator, AVD `hardio-e2e`, android-35 x86_64; the only test device on DevBox). The **release** APK is
  filtered to `arm64-v8a` (`ndk.abiFilters` in the release build type) to keep the size down; debug keeps both.
- **Binaries are built, not committed.** `scripts/build-tools.sh` downloads pinned source tarballs (version +
  SHA-256 in the script), builds them reproducibly with the SDK's NDK (`~/Android/Sdk/ndk/29.0.14206865`; zig or a
  musl toolchain only if the NDK can't do it, say why), caches in `~/.cache/kabuk-tools/<tool>-<ver>-<abi>/` and
  copies into `app/src/main/jniLibs/<abi>/` (gitignored). `scripts/release.sh` runs it first (no-op when cached) and
  fails if a tool is missing for arm64-v8a.
- **Applets / names.** On every Phone-tab start `LocalTools` (new, `term/LocalTools.kt`) makes
  `filesDir/bin/` with symlinks `<name> -> <nativeLibraryDir>/lib<name>.so` (busybox: one symlink per applet from
  `busybox --list`, never shadowing a name that a later Kabuk tool provides). Rebuilt when the app's versionCode
  changes (nativeLibraryDir moves on update). Symlinks, not copies: the exec'd inode stays in nativeLibraryDir.
- **Environment in LocalTab.** `PATH=<filesDir>/bin:/system/bin:/system/xbin`, `HOME=<filesDir>/home`,
  `TMPDIR=<cacheDir>/tmp` (created), `SHELL=<filesDir>/bin/sh`, `PREFIX=<filesDir>`, plus the existing TERM/LANG/
  ANDROID_*. Shell: busybox `ash` (`-l`, a small `$HOME/.profile` made once, never overwritten) when busybox is
  present, else `/system/bin/sh` as today.
- **Self-test into the live log.** When `LocalTools` sets up the bin dir it runs each tool's version command once
  (ProcessBuilder, 5 s timeout) and logs one line per tool, tag `TOOLS`: `busybox: ok (BusyBox v1.37.0 ...)` or
  `busybox: FAILED (exit 126 / error ...)`, plus `abi=..., nativeLibraryDir=...`. This is how exec on Murat's
  phone is verified: he opens a Phone tab after the update, the orchestrator reads `debug/CPH2449.log`.
- **Opening URLs (step 3).** `xdg-open`, `open`, `termux-open-url` on PATH are a tiny sh script (in filesDir/bin,
  run by busybox sh, so not exec'd itself) that writes the URL into `filesDir/open/<time>.url`; `LocalTab` watches that
  dir (FileObserver) and starts an `ACTION_VIEW` intent (http/https only, no `am`). Fallback: when the Phone tab
  prints an `http(s)://` URL, a small "Open link" chip/snackbar offers it.
- **Process stays alive while the browser is in front:** `SessionService` (specialUse foreground service) already
  runs while any tab is open (TabManager.tabsChanged), Phone tab included; check this holds so rclone's
  127.0.0.1:53682 listener isn't frozen while Chrome has the screen.
- **rclone (step 3)** is built from source with `GOOS=android GOARCH=<arm64|amd64> CGO_ENABLED=1 CC=<NDK clang>`
  (Go is at `~/.local/go/bin/go`), so DNS goes through bionic. The official `linux-arm64` static build is NOT
  used: a static linux Go binary on Android has no /etc/resolv.conf and its DNS fails.
- **Licences.** busybox GPL-2.0: ship its licence text and a written source offer (exact version + tarball URL +
  SHA-256 + our build script) in `assets/licenses/` and a "Licences" entry in Settings → About (or wherever the font
  licence is shown); rclone MIT text likewise. README "What it does" gets a "Phone tab" section and the History an
  entry per release.
- **Size budget:** release APK today 15 MB; target ≤ 45 MB with busybox + rclone (arm64 only).

## Steps and releases

| Step | Content | Release |
|------|---------|---------|
| 1 | Design note `docs/LOCAL-TOOLS.md`: the decisions above as verified facts (what was tried on the emulator) | with 2 |
| 2 | busybox (arm64 + x86_64) + LocalTools + env + self-test + licences + tests | 0.7.0 |
| 3 | rclone + xdg-open helper + URL chip; `rclone authorize` flow checked on the emulator up to the browser | 0.7.1 |
| 4 | ssh client (dropbear or OpenSSH, static), maybe git | later, after 2-3 work on the phone |

## Rules for every worker
- Work only in your worktree (`/projects/kabuk/.worktrees/<agent>`, branch `<agent>/local-tools`), commit there
  with your prefix (`[claude]`, `[codex]`), never push, never release, never touch `~/apk-share`, never bump the
  version (the orchestrator does releases).
- Gate before you report: `JAVA_HOME=~/tools/jdk-17.0.19+10 ./gradlew -q testDebugUnitTest assembleDebug` green.
- Emulator: `adb devices` first. If an emulator is already running it belongs to someone else: don't kill it or
  wipe it. Start your own read-only instance instead:
  `~/Android/Sdk/emulator/emulator -avd hardio-e2e -read-only -no-window -no-snapshot -no-audio -port 5584 &`
  and use `adb -s emulator-5584`. Kill only that one when done. Kabuk's package: see `applicationId` in
  `app/build.gradle.kts`; run commands as the app with `adb shell run-as <pkg> ...` where it helps.
- Never log terminal contents or URLs with secrets (an OAuth URL's `state`/`code` are secrets: log only the host).
- Write `REPORT-<agent>.md` in the worktree: what you built, what you verified (commands + output excerpts),
  what is unverified, open questions.

## Brief: step 1 + 2 (busybox) — first worker
1. Read LocalTab.kt, TabManager.kt (tabsChanged/SessionService), build.gradle.kts, scripts/release.sh, README.
2. `scripts/build-tools.sh` for busybox 1.37.0 (or the newest stable 1.36/1.37 that builds): static, arm64-v8a +
   x86_64, Android-friendly config (no applets that need setuid/root or are useless here; keep at least sh/ash, ls,
   cp, mv, rm, mkdir, cat, less, vi, grep, sed, awk, find, xargs, tar, gzip, unzip, wget, nc, ps, top, du, df,
   which, env, diff, head, tail, sort, uniq, wc, md5sum, sha256sum, base64, date, sleep, hexdump, stty, clear,
   reset). Note that musl/bionic static DNS may not work for wget: test and document, don't fix in this step.
3. Gradle: useLegacyPackaging, release abiFilters arm64-v8a, `.gitignore` the jniLibs tool outputs.
4. `LocalTools.kt` + LocalTab env/shell as decided; self-test log lines.
5. Unit tests (Robolectric/JVM) for the bin-dir logic (symlink set from a `--list`, rebuild on versionCode change,
   no overwrite of .profile), and an emulator check: install the debug APK, open a Phone tab (or run the same
   commands via `run-as`), show that `ls`, `grep`, `vi --help`, `tar`, `wget --help` exec from the bin dir, and
   that the TOOLS self-test lines appear in logcat.
6. Licences (assets + Settings entry), README section, `docs/LOCAL-TOOLS.md` (step 1 note, verified facts).
7. Commit(s) `[claude] ...`, write REPORT-claude.md, report back.

## Brief: step 3 (rclone + opening URLs) — second worker
Step 2 is merged and released as 0.7.0 (busybox, `LocalTools`, `scripts/build-tools.sh`, docs/LOCAL-TOOLS.md:
read them, extend them; don't restructure). Emulator on DevBox: the user is not in the `kvm` group, so run the AVD
inside Paperscanner's docker image `paperscanner-emulator-test:bookworm` with `--device /dev/kvm` as
`/projects/paperscanner/scripts/test-emulator` does (read-only AVD hardio-e2e, own port; the box is often loaded:
OOM kills happened, retry).
1. build-tools.sh: rclone (latest stable v1.x, pinned tag + source tarball SHA-256) built from source with
   `GOOS=android GOARCH=arm64|amd64 CGO_ENABLED=1 CC=<NDK clang --target=<triple>26>` (dynamic against bionic, so DNS
   works through Android's resolver), `-trimpath -ldflags "-s -w"`, reproducible, cached like busybox; output
   `librclone.so`. Go module downloads go into ~/.cache/kabuk-tools/gomod (GOMODCACHE), GOFLAGS=-mod=mod is fine.
   Add rclone to LocalTools.TOOLS (self-test: `rclone version`), licence (MIT + its third-party notices if rclone
   ships them) in assets/licenses + Settings → Licences.
2. URL opening: helpers `xdg-open`, `open`, `termux-open-url` in filesDir/bin as `#!/system/bin/sh` scripts (they
   are run by the kernel via the shebang interpreter in /system/bin, so not exec'd from app data — verify this works
   under W^X on the emulator; if not, make them busybox-ash functions/aliases in .profile-independent form, e.g. a
   tiny multi-call mode in a Kabuk lib, and say what you chose). They write the URL into `filesDir/open/<ts>.url`;
   LocalTab watches the dir (FileObserver) and starts ACTION_VIEW for http/https only. Reserve those names in BinDir.
   Fallback: when the Phone tab's screen shows an http(s) URL, offer "Open link" (chip or snackbar; keep it simple,
   one at a time, dismissable). Never log the URL beyond scheme+host.
3. Verify on the emulator: `rclone version`; DNS works (`rclone lsjson --http-url https://downloads.rclone.org :http:`
   or similar public listing); `xdg-open https://example.com` from the Phone tab opens the browser (screenshot or
   `dumpsys activity` showing the browser on top); `rclone authorize "pcloud"` (or another OAuth backend) starts its
   listener on 127.0.0.1:53682 and the browser is opened on it; a request to `http://127.0.0.1:53682/` from outside
   the app (e.g. `adb shell` curl/toybox nc, or the browser) reaches rclone while Kabuk is in the background
   (SessionService keeps it alive). The real pCloud login can't be completed on the emulator — Murat does that.
4. Release APK size after rclone (arm64 only) — report it; budget ≤ 45 MB.
5. README Phone tab section + History (label 0.7.1), docs/LOCAL-TOOLS.md, unit tests (URL file → intent filter
   http/https only, URL detection on screen text, BinDir reserved names). Gate green, commits `[claude] ...`, no push/
   release/version bump. Report back as text (subagents can't write report files).

## Brief: step 3b (0.7.2) — fixes from Murat's first use on the phone (2026-11-14)
0.7.1 runs on the phone (TOOLS: busybox, rclone, kabuk-open ok). Murat then hit:
- `ping google.com` → `ping: bad address 'google.com'` (static bionic busybox has no DNS: Android resolves via netd).
- `ping 1.1.1.1` → `ping: permission denied (are you root?)` (busybox ping wants a raw socket; Android's
  /system/bin/ping uses ICMP "ping sockets", allowed for apps, and resolves names).
- The prompt shows `/data/data/app.kabuk/files/home $` instead of `~ $` (HOME is filesDir = /data/user/0/...,
  the shell's cwd resolves to /data/data/..., so ash's \w doesn't abbreviate).
Fix:
1. busybox linked **dynamically** against bionic (NDK clang `--target=<triple>28`, not static), so getaddrinfo goes
   through netd and DNS works for wget/nc/ping/telnet etc. API 28 because glob/getrandom/syncfs need it: the tools
   then need Android 9+ (the app's minSdk 26 stays; on 8.x the self-test reports FAILED — document). Keep the
   source edits only if still needed. Check `readelf -d` (NEEDED libc.so/libm.so/libdl.so only, interpreter
   /system/bin/linker64), size, and that the cache stamp forces a rebuild.
2. Applets that Android's /system/bin does better are not linked: at least ping, ping6 (and traceroute/traceroute6
   if /system/bin has them on the emulator; check `ls /system/bin`). A small explicit list in LocalTools
   (`PREFER_SYSTEM`), unit-tested.
3. HOME and the shell's start dir use the same canonical path (`File.canonicalPath` of filesDir/home, or set cwd to
   exactly HOME's string) so the prompt shows `~`. Unit-test what can be tested.
4. Emulator check (docker as before): `wget -O- http://example.com | head -3` (DNS ok), `nc -z example.com 80`,
   `ping -c1 google.com` (→ /system/bin/ping, ok or at least not "bad address"/"are you root"), prompt shows `~ $`,
   TOOLS self-test lines ok, `which ping` → /system/bin/ping.
5. README/docs (History 0.7.2), gate green, commits `[claude]`, report as text.

## Phase B: Kabuk becomes Termux (Murat's decision, 2026-11-14: "i want all functions" → "Kabuk becomes Termux")
Goal: Termux parity inside Kabuk's Phone tab: `pkg install` of the full Termux package collection (python, git,
openssh, nodejs, clang …), bash, /sdcard access (termux-setup-storage), later Termux:API-like features. Requires
targetSdk 28 (untrusted_app_27 SELinux domain may exec app_data_file; W^X applies from targetSdk 29).

### Brief: B0 feasibility spike (worker: Opus) — no release, a report and a working prototype on a branch
1. **targetSdk 28 audit of Kabuk** (read the code + Android behaviour changes 29..35): what changes or breaks when
   targetSdk drops from 35 to 28 while compileSdk stays: SessionService (foregroundServiceType specialUse is a
   targetSdk-34 rule: still fine?), POST_NOTIFICATIONS (33+: auto-granted/prompted differently), PendingIntent
   mutability flags, package visibility (<queries>), background activity starts, scoped storage (28 = legacy
   /sdcard access with READ/WRITE_EXTERNAL_STORAGE: good for termux-setup-storage), the in-app updater
   (PackageInstaller session, REQUEST_INSTALL_PACKAGES), edge-to-edge/insets, Play Protect risk (secondbrain
   stack/android.md has two Play Protect observations: read them), Android 16's minimum installable targetSdk.
   Make the change on the branch (targetSdk 28) and fix what the audit finds, unit tests green.
2. **Relocation spike (preferred design).** Termux's official packages hardcode `/data/data/com.termux/files/...`.
   `com.termux` → `app.kabuk/` gives `/data/data/app.kabuk//files/...`, same byte length, valid path. Prototype:
   - bootstrap: download Termux's official bootstrap zip for x86_64 (emulator) and aarch64 (phone) from
     github.com/termux/termux-packages releases (pin + SHA-256), rewrite `/data/data/com.termux/` →
     `/data/data/app.kabuk//` in every file (binary-safe, same length; ELF strings, RUNPATH, shebangs, scripts,
     SYMLINKS.txt targets), install to filesDir/usr like Termux does (symlinks file, permissions), start
     `usr/bin/login`/bash as the Phone tab's shell when present (busybox setup stays the fallback).
   - apt: Termux's apt with a `DPkg::Pre-Install-Pkgs` hook (or an equivalent wrapper) that rewrites each
     downloaded .deb before dpkg sees it: member paths `./data/data/com.termux/...` and file contents, same-length.
     Also the dpkg/apt config paths in the bootstrap itself.
   - prove on the emulator (targetSdk-28 debug build, docker emulator as before): bash starts; `pkg update`;
     `pkg install python git openssh curl` succeed; `python3 -c 'import ssl,sqlite3;print(1)'`, `git --version`,
     `curl -sI https://example.com`, `ssh -V` run; `./script.sh` runs directly (W^X gone at targetSdk 28);
     `termux-setup-storage`-equivalent: /sdcard readable after granting storage (prototype the permission request).
   - list what breaks under relocation (grep for leftover `com.termux` strings in installed files that aren't the
     prefix: termux-am/intents to com.termux.*, compressed data, length-dependent checksums) and how bad it is.
3. **Fallback assessment (don't build it):** effort to rebuild termux-packages with TERMUX_APP__PACKAGE_NAME=app.kabuk
   (their docker builder), bootstrap + top packages, hosting an apt repo (GitHub releases/our relay), update cadence.
4. Sizes (APK: the bootstrap is downloaded at first start or bundled? recommend), first-start UX (progress, offline).
Report as text: verdict relocate-vs-rebuild with evidence, the audit table, what the prototype shows (commands +
output), risks, and a step plan B1..Bn for the real implementation. Commits `[claude]` on branch claude/termux,
gate green; no push/release/version bump.

### B0 result (2026-11-14): relocation works (spike commit 0eeb425 on branch claude/termux, kept as reference)
Termux's official bootstrap + `pkg install python git openssh curl` ran relocated on the emulator at targetSdk 28;
fallback rebuild rejected. Step plan B1..B7 as in the worker's report (summarised in docs later, B7).

### Brief: B1 — targetSdk 28 alone (release 0.8.0)
Take from 0eeb425 ONLY: targetSdk 28 + lint disables, the explicit dark system bars (SystemBarStyle.dark), the
notification handling for a legacy target. NOT TermuxEnv, StorageAccess, storage permissions or
requestLegacyExternalStorage, LocalTools/LocalTab changes (busybox stays exactly as in 0.7.2 — check it still
works at 28: links in files/bin still point into nativeLibraryDir; at 28 exec from data would also work, but keep it).
Fix the open audit item: below targetSdk 33 Android shows the notification permission prompt only when a channel is
created while an activity is in front, and Kabuk creates channels at process start, so on a fresh install
notifications stay off silently. Make it reliable: create channels from MainActivity after it is shown (or
otherwise trigger the system prompt), and if notifications are still off, a one-time visible note with "Turn on"
(opens the app's notification settings). Upgrading phones (Murat's) already have the permission granted: verify an
upgrade 0.7.2 (target 35) → B1 build (target 28) keeps it (emulator: install 0.7.2 debug, grant, upgrade).
Verify on the emulator: fresh install (prompt or note appears, screenshot), upgrade path, SessionService
foreground + its notification visible, Phone tab busybox ok, in-app updater code path (at least that
PackageInstaller session install of a newer debug APK works from the app, if you can drive it; else say so),
system bars dark. Gate green; docs/README History (0.8.0: "targetSdk 28, groundwork for Termux packages").

### B1 released as 0.8.0 (36), 2026-11-14 (targetSdk 28, verified on the emulator incl. the in-app updater)

### Brief: B2 — Termux packages installer (release 0.8.1)
Productise the spike's bootstrap install (branch claude/termux, 0eeb425: `term/TermuxEnv.kt`, `StorageAccess.kt`,
TermuxRelocatorTest; `git show 0eeb425`), on top of main (0.8.0, targetSdk 28). Include the relocation hook
(`relocate-debs`) and the storage flow (`termux-setup-storage`, READ/WRITE_EXTERNAL_STORAGE,
requestLegacyExternalStorage) since pkg is useless without them; B3 hardens, B4 adds am/wake-lock.
1. **Consent, not auto-install.** First Phone-tab open after the update (and from a Phone-tab menu item "Install
   Termux packages…" later): a sheet "Install Termux packages: ~33 MB download, ~100 MB on the phone; then `pkg
   install` works like in Termux" [Install] [Not now]. Not now = busybox shell as today, asked again only from the
   menu. While installing: determinate progress (download bytes, then extract/relocate count), cancellable;
   failure/offline: message + Retry, busybox stays. Done: the next Phone tab (and a "Restart shell" offer for the
   open one) starts Termux's login/bash; busybox stays reachable as a "Phone (busybox)" fallback entry for
   recovery (e.g. a long-press menu or the Servers screen shell row).
2. **Pin refresh:** `scripts/termux-bootstrap-pin.sh` prints the newest bootstrap tag + SHA-256 per arch from
   github.com/termux/termux-packages releases, to update the constants; the app checks the SHA-256 before use.
3. **Streaming install:** download to cacheDir, verify, extract + relocate entry by entry (no second full copy),
   staging dir + atomic rename (as the spike), cleanup of partial state on failure/cancel; run on a background
   thread/coroutine with the SessionService keeping the process alive.
4. **Mirror:** default the pkg mirror to the Europe group (or a single reliable EU mirror) instead of a random
   worldwide one; first `pkg update` after install happens in the new shell's motd/second stage, as Termux does.
5. **Logs:** TERMUX tag, counts/sizes/durations/errors only (no file contents, no terminal text).
6. **Tests:** relocator (binary same-length, text clean path, symlink targets, conffile md5 recompute), installer
   state machine (consent/not now/progress/failure/retry/done) as JVM tests; emulator end to end on a fresh install
   AND an upgrade from 0.8.0: consent sheet → install → bash → `pkg install python git` → run them; Not now path;
   offline failure + retry (turn networking off in the emulator); storage flow.
7. README Phone tab section (Termux packages, the arm64 note, sizes), docs/LOCAL-TOOLS.md phase B section,
   History 0.8.1; licences note: packages are downloaded by the user from Termux's repositories, not shipped.

### B2 released as 0.8.1 (37), 2026-11-14. Notes for B3 from review/report:
- `prefix.deleteRecursively()` / staging cleanup use Kotlin deleteRecursively, which follows symlinks to dirs:
  before any reinstall/repair action, switch to a non-following delete (Files.walkFileTree without FOLLOW_LINKS).
- No reinstall/repair menu yet (recovery = "Phone (busybox)"); process kill mid-install leaves usr-staging (cleaned
  on next use, untested); conffile prompts on `pkg upgrade` untested on device; "Open link" chip offers mirror URLs
  during the first pkg update (noisy); first pkg update tests ~23 EU mirrors (~20 s).

### Phone result 0.8.1 (2026-11-14 20:15, CPH2449 arm64): bootstrap 32 MB in 2.5 s, extract 3483 files in 1.75 s,
no errors. Then the "Open link" chip opened a mirror URL (https://ro.mirror.flokinet.net) during the first pkg update.

### Brief: B3+B4 — hardening and Termux app commands (release 0.8.2)
B3 (hardening):
1. "Open link" chip: never for package-manager output (lines with `Get:`, `Hit:`, `Ign:`, `Err:`, `Picking mirror`,
   `Mirror`, `Fetched`, `Reading package lists`, and URLs of the configured mirrors / packages.termux.dev hosts), and
   only after the screen has been quiet ~1.5 s with the URL in the bottom 8 lines. Unit tests with real pkg output.
2. Non-following delete for usr/staging/cleanup (Files.walkFileTree without FOLLOW_LINKS; a symlink is unlinked,
   never descended). Test: a symlink inside the tree to a dir outside survives with its contents.
3. Phone-tab menu "Repair Termux packages…" (re-runs the bootstrap install over a fresh staging dir, keeps $HOME,
   confirms first: "installed packages are removed, your home folder stays") and stale usr-staging removed at app start.
4. `dpkg -i` by hand: relocate too (a dpkg wrapper via dpkg-divert or a `DPkg::Pre-Invoke`-independent wrapper in
   usr/bin that relocates .deb arguments then execs the real dpkg). Test on the emulator with a downloaded Termux deb.
5. Conffile prompts: on the emulator run `pkg upgrade` without `yes` after installing a few packages (openssh,
   nano) and a later reinstall; there must be no "modified conffile" questions caused by relocation.
B4 (Termux app commands inside Kabuk):
6. `termux-wake-lock` / `termux-wake-unlock`: SessionService takes/releases a partial wake lock (+ the notification
   says so). `termux-open <file|url>`: URLs as xdg-open; files via a FileProvider content:// share/view intent.
   `termux-open-url` exists already.
7. `am` / `termux-am`: support the common subset scripts use (`am start -a ACTION -d URI [-t TYPE] [-n comp]`,
   `am broadcast` minimal) through the same file-drop channel as kabuk-open (or a local socket), started by Kabuk;
   unsupported options → clear error. Check what termux-tools' scripts (termux-open, termux-reload-settings, …)
   call and make those work or fail with a clear "not supported in Kabuk" message.
8. `termux-reload-settings`: re-read ~/.termux/termux.properties for the few props Kabuk supports (extra-keys may
   be ignored; say so), `termux-info` prints Kabuk's info instead of failing.
Emulator evidence per point; README/docs, History 0.8.2; gate green; commits [claude]; report as text.
