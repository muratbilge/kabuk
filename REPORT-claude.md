# REPORT: B9 — leftovers (for release 0.8.5)

Worker: Claude (Opus), branch `claude/leftovers`, code commit 515ddde. No version bump, release or push.
Gate `JAVA_HOME=~/tools/jdk-17.0.19+10 ./gradlew -q testDebugUnitTest assembleDebug`: exit 0, 170 tests
(7 skipped as before), 0 failures (log `~/.cache/kabuk-tools/b9-gate.log`).

Emulator: copy of `avd-b8` as `~/.cache/kabuk-tools/avd-b9` (removed afterwards), `-read-only`, port 5584, in docker
`paperscanner-emulator-test:bookworm` with `--device /dev/kvm` (container `kabuk-b9-emu`, removed afterwards). APK
built with `-Pkabuk.logsinkToken=` after `scripts/build-tools.sh` (jniLibs): `~/.cache/kabuk-tools/b9-apk/b9-debug.apk`.
eSpeak NG (the F-Droid APK B8 used) installed only into the throwaway emulator, set as default engine. Commands ran
as the app via `adb shell run-as app.kabuk env -i HOME=… PATH=files/bin:… KABUK_OPEN_DIR=… files/bin/sh -c '…'`
(busybox sh, same request channel) or in the Phone tab. Evidence: `~/.cache/kabuk-tools/b9-shots/`.

## Built
1. **termux-tts-speak: stopping one leaves the others** (`TermuxApi.kt`). `SpeechQueue` records every piece handed
   to the engine (utterance id → request and text, in order). `abandon(job)` (Ctrl-C, cap, engine error, refused
   text) removes the job's pieces, calls `tts.stop()`, and queues the other requests' pieces again at once with new
   ids and each request's own voice (captured after `-l`), pitch, rate and stream. Late `onStop` for stopped ids is
   ignored (unknown id). A piece another request was speaking at that moment starts over (one line).
   Found while testing: the cap counted from enqueue, so a request waiting behind a long text was capped while
   waiting, and that stop restarted the other's text. Now the cap is re-armed at the request's first `onStart`; if
   it fires while the request still waits behind another request that did start, it waits another round. A stuck
   engine (nothing starts) still ends requests at their caps (head first).
2. **"+" menu** (`TerminalScreen.kt`): "Phone (busybox)" when `TermuxEnv.offersBusyboxTab` (usr installed or
   damaged; not when absent, as before). Also the Servers screen ⋮ (`HostsScreen.kt`): it was **empty** for a
   damaged install although the row says "⋮ → Repair"; it now shows "Phone (busybox)" and "Repair Termux packages…".
3. **Mirrors**: checked, no change (see below).
Docs: `docs/LOCAL-TOOLS.md` "Leftovers (0.8.5)" (+ a note in the 0.8.4 keep-alive paragraph), README History 0.8.5.

## Verified
**Unit tests** (`TermuxApiTest`): `speechQueueCancel` (pure queue), `ctrlCStopsOnlyItsOwnSpeech` (Robolectric
ShadowTextToSpeech: two requests, cancel the first → answer 1, the second's text spoken again under a new id, old
ids' onStop ignored, second answered 0 on onDone; a lone request → engine stopped, nothing re-queued; cap does not
fire while waiting behind a started long text, fires 30.75 s after its own onStart; stuck engine → both capped).
`TermuxBootstrapTest.busyboxTabOffered` (absent/damaged/installed).

**Point 1 on the emulator** (`b9-1a..d.txt`):
- 1a: A = 456 chars `-r 0.8` speaking, B short queued 1 s later, `kill -TERM` A at 6 s:
  ```
  A rc=143 after 6 s
  B rc=0 after 11 s
  I Kabuk.CMD: termux-tts-speak: the program ended before the answer (Ctrl-C?)
  I Kabuk.CMD: speech of 1 other request(s) queued again
  I Kabuk.CMD: termux-tts-speak: speech stopped (the program ended)
  I Kabuk.CMD: termux-tts-speak: spoken (28 chars)
  ```
- 1b: B = 8 piped lines speaking, A short waiting behind, TERM to A → `B rc=0 after 19 s`, "spoken (264 chars)".
- 1c (cap): A 456 chars `-r 0.8`, B short behind it, nothing killed → `A rc=0 after 35 s`, `B rc=0 after 37 s`.
  The same run with the first build (cap from enqueue) gave `B rc=1 after 35 s` ("speech did not finish") and
  `A rc=0 after 71 s` (A restarted): the reason for the cap change.
- 1d: Phone tab `sh long.sh` (tts `-r 0.4`), B queued from a script, key bar ^C → "the program ended before the
  answer", "queued again", B `rc=0 after 6 s`.
- No spoken text in any `Kabuk.*` logcat line (grep for the test phrases: 0).
(`kill -INT` on a background job of a non-interactive sh does nothing, SIGINT is ignored there, hence TERM.)

**Point 2 on the emulator** (`b9-2a..d.txt`, `p2*.png`):
- no usr: "+" → `Phone shell`, `Servers…` (unchanged).
- `files/usr/bin` without login/bash: "+" → `Phone shell`, `Phone (busybox)`, `Servers…`; tapping it opens a tab
  "Phone (busybox)" without the Repair sheet; `/proc/<pid>/exe -> …/lib/x86_64/libbusybox.so`,
  `SHELL=/data/user/0/app.kabuk/files/bin/sh`.
- Servers row "Termux packages damaged: busybox for now (⋮ → Repair)", ⋮ → `Phone (busybox)`,
  `Repair Termux packages…`; "+" → Phone shell (first of the process) → sheet "Termux packages are damaged".
- placeholder `usr/bin/login` + `bash` (= installed): "+" still shows `Phone (busybox)` (no tab opened).

**Point 3 (mirrors)**: pkg (`bin/pkg` of the pinned bootstrap, termux-tools 1.45.0) runs `curl --fail
<MAIN>/dists/stable/Release` for every mirror of the chosen group in parallel and picks only from those that pass;
it does not retry another mirror when `apt update` fails afterwards. Group `europe` is identical in the x86_64 and
aarch64 bootstraps (23 files). From DevBox, 2026-11-15 (InRelease + first 100 bytes of
`main/binary-<arch>/Packages.gz`):
- 15 mirrors: 200/206/206 (both arches fine).
- 8 no answer at all: no DNS (mirror.polido.pt, termux.mentality.rip, mirrors.cfe.re, mirrors.medzik.dev), timeout
  (mirror.termux.dev, mirror.leitecastro.com, termux.cdn.lumito.net), TLS failure (md.mirrors.hacktegic.com);
  pkg's own test drops these.
- `termux.librehat.com InRelease=200 x86_64=404 aarch64=206`: only x86_64 is missing, and pkg's Release test passes,
  so on x86_64 a pick of it fails `pkg update` (a rerun picks again; weight 1 of 26).
Nothing is dead for both arches in a way pkg misses → group unchanged, per the brief.

## Unverified
- Hearing it: the emulator runs `-no-audio`; "spoken"/rc come from eSpeak's onDone. Whether a re-queued piece is
  audible after the stop, and how the restart of an interrupted line sounds, needs the phone.
- A real Termux install with a real tab: only placeholder login/bash for the menu check.
- Google TTS (the phone's engine) with `setVoice` on re-queue; eSpeak only.

## Open questions
- librehat on x86_64: drop it only for x86_64 (would need `chosen_mirrors` as a directory of copied mirror files,
  which go stale on termux-tools upgrades)? Left as is; affects emulators/x86_64 devices only.
- Ctrl-C on a request that is still *waiting* restarts the line the other request is speaking (stop() is all or
  nothing). A deferred stop (only when the cancelled request's first piece starts) would avoid that at the cost of
  a short blip of its text; kept the simple variant.
- Voice leakage between requests (a request without `-l` uses the voice the previous one set) is unchanged from 0.8.4.
