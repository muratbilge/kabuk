# REPORT: B8 — polish (for release 0.8.4)

Worker: Claude (Opus), branch `claude/polish`. No version bump, release or push. Gate
`JAVA_HOME=~/tools/jdk-17.0.19+10 ./gradlew -q testDebugUnitTest assembleDebug` (with `-Pkabuk.logsinkToken=`):
green, 166 tests (7 skipped as before), 0 failures.

Emulator: AVD copy `~/.cache/kabuk-tools/avd-b8/hardio-e2e.avd`, `-read-only`, port 5584, in docker
`paperscanner-emulator-test:bookworm` with `--device /dev/kvm` (container `kabuk-b8-emu`, stopped afterwards).
APK `~/.cache/kabuk-tools/b8-apk/b8-debug.apk`. Mirror switched to packages-cf.termux.dev before the first
`pkg update` (as allowed). Commands ran as the app via `run-as app.kabuk env -i … bash -c '<cmd>'` (same request
channel) or typed into the Phone tab. Evidence: `~/.cache/kabuk-tools/b8-shots/` (`b8-1.txt`, `b8-2a..e.txt`,
`b8-3a..d.txt`, `b8-4.txt`, `b8-logs.txt`, `p1-headsup.png`, `p3-tab.png`, `p4-*.png`).
`scripts/build-tools.sh` was run in the worktree (jniLibs; refreshed the kabuk-open cache entries to v4).

## Built
- `TermuxApi`: channel `scripts-high` "Scripts (important)" (IMPORTANCE_HIGH, silent), `channelFor(priority)`;
  `termux-tts-speak` answers when spoken (UtteranceProgressListener, `speechPieces` splits into engine-sized lines).
- `kabuk-open.c` v4: sends `kabuk-req 2`; `<base>.wait` = keep-alive (deleted by the program, resets its 10 s).
  `KabukCommands`: `Request(…, keepAlive)`, `Call` (one per running request: `.wait` every 3 s until answered,
  max 1 h; stops and drops the answer when `kill(pid,0)` says ESRCH). `kabuk-req 1` still accepted.
- `TermuxEnv`: `usr/libexec/kabuk/termux-api-sync` (dpkg-divert of termux-api's bin files Kabuk answers → links to
  files/kabuk/bin; files in usr/libexec → stub, exit 1; undone when the package is gone); apt `DPkg::Post-Invoke`;
  dpkg wrapper runs it after install/remove/purge/configure; hook files rewritten at tab start when they differ,
  sync run at tab start when needed (`termuxApiNeedsSync`). `installed` = login and bash present, `broken` = usr
  without them.
- Installer: `InstallState.OfferRepair` (once per process), sheet "Termux packages are damaged" (Not now / Repair),
  Repair instead of Install in the Phone tab menu and the Servers ⋮, Servers subtitle for the broken state.
- Tests: decode v1/v2 + pidOf, channelFor, speechPieces, sync script text + needsSync, apt hook (Post-Invoke,
  idempotent), usable/broken, OfferRepair state machine. Docs: LOCAL-TOOLS "Polish (0.8.4)", README + History 0.8.4.

## Verified per brief point
1. `--priority high`/`max` → `channel=scripts-high importance=4` (dumpsys), heads-up over the home screen
   (`p1-headsup.png`); default → `channel=scripts importance=3`, no heads-up event.
2. `apt-get install termux-api` → "kabuk: 60 termux-api files now run Kabuk's commands"; `$PREFIX/bin/termux-toast -s hi`
   rc 0 (toast logged), `$PREFIX/bin/termux-battery-status` 0.06 s, `$PREFIX/libexec/termux-api` and
   `libexec/termux-api-broadcast` → stub message, rc 1. `--reinstall` and an upgrade to a repacked 0.60.1 via
   `dpkg -i` (wrapper): links stay, works; `dpkg -V termux-api` prints nothing. `apt-get remove` → "60 diversions
   undone", 0 diversions, `termux-toast` by name works; same with `dpkg -r`. Pre-0.8.4 install simulated (no
   Post-Invoke, no sync script, termux-api installed): next Phone tab → "apt hooks updated", "synced at tab start
   (exit 0 …60…)", full path works.
3. eSpeak NG installed into the emulator only: short text 1.2 s rc 0; 540 chars 39 s rc 0 (past 10 s);
   piped lines rc 0; Phone tab Ctrl-C after 14 s ends it, "program ended before the answer", `files/open` empty;
   `termux-media-scan -r` of 30 000 files 1.3 s rc 0.
4. bash renamed → new tab: Repair sheet + log "bash missing"; Not now → busybox tab (termux-toast works); next tab no
   sheet; menus show "Repair Termux packages…"; Servers subtitle "Termux packages damaged…"; Repair from menu →
   repaired. login renamed, fresh process → sheet, its Repair → repaired, Restart shell → bash + second stage.
Logs: no test text in any `Kabuk.*` logcat line.

## Unverified
- Everything on the phone (arm64); real audio (the emulator ran `-no-audio`, eSpeak still reported completion);
  a real upstream termux-api upgrade (simulated with a repacked 0.60.1); `media-scan`/`download` long enough to need
  keep-alives (only tts exceeded 10 s; the mechanism is generic).

## Open questions / notes
- Follow-up (coordinator's decision): termux-tts-speak is capped at 30 s + 150 ms/char (max 10 min) → `tts.stop()`,
  exit 1 "speech did not finish (engine stuck?)"; Ctrl-C (program gone, seen by the keep-alive) stops the speech.
  General keep-alive limit stays 1 h. Unit test for the cap formula; not rerun on the emulator. `tts.stop()` also
  cuts other queued termux-tts-speak text (they answer exit 1).
- `usr/lib/libtermux-api.so` (programs linking Termux:API directly) still targets the missing app.
- The "+" menu doesn't list "Phone (busybox)" while broken (plain "Phone shell" is busybox then).
