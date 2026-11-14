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
