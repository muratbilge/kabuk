# REPORT: B5+B6 — Termux:API commands and Phone-tab polish (for release 0.8.3)

Worker: Claude (Opus), branch `claude/termux-api`. No version bump, release or push. Gate
`JAVA_HOME=~/tools/jdk-17.0.19+10 ./gradlew -q testDebugUnitTest assembleDebug` (with `-Pkabuk.logsinkToken=`): green.

Emulator: AVD copy `~/.cache/kabuk-tools/avd-b5/hardio-e2e.avd`, `-read-only`, port 5584, in docker
`paperscanner-emulator-test:bookworm` with `--device /dev/kvm` (container `kabuk-b5-emu`, stopped afterwards).
APK `~/.cache/kabuk-tools/b5-apk/b5-debug.apk`. Commands ran either as the app via `run-as app.kabuk env -i …
KABUK_OPEN_DIR=…/files/open bash -c '<cmd>'` (same request channel) or typed into the Phone tab (tty, chip, tabs).
Evidence: `~/.cache/kabuk-tools/b5-shots/` (`b6-1..6.txt`, screenshots).

## Built
- `term/TermuxApi.kt`: 14 Termux:API commands (parse pure, execute on main), unsupported list, Termux:API usage
  texts + JSON, `ScriptActionReceiver` (notification actions), channel "Scripts".
- `kabuk-open.c` v3: every non-URL name is a request; stdin hand-over (`stdin`/`stdin-if-piped` → `<base>.in`,
  ≤ 1 MiB); answers up to 1 MiB.
- `KabukCommands`: `NAMES`, `Action.Api`, `Action.NeedStdin`, pending-stdin map, `handleStdin`; `UrlOpener` watches `.in`.
- `TermuxEnv`/`LocalTools`: names linked in `files/kabuk/bin` and busybox bin (layout 4); `KABUK_VERSION=<versionName>`,
  `KABUK_VERSION_CODE`; termux-info lists the commands.
- B5 chip: `LocalTab.typed`/`beforeInput` + `ScreenLinks.printedBefore`.
- Manifest: `ACCESS_WIFI_STATE` (normal), `ScriptActionReceiver` (not exported). Docs + README History 0.8.3.
- Notification actions: `sh -c` in the Phone tab's env, background, output discarded (simpler than a new session;
  only Kabuk's immutable explicit PendingIntents reach the non-exported receiver).

## Verified per brief point
1. set/get round trip (args, pipe, tty + Ctrl-D); background get → "Android lets only the app on screen read the
   clipboard…", exit 1.
2. toast (screenshot `p9-env-toast.png`), vibrate (logged), torch on/off (logged); tts: error path only (AVD has no
   engine) → "the text-to-speech engine did not start", exit 1.
3. notification with title/content/id/button (`n3-button.png`); button ran `termux-toast` (logged, exit 0); tap ran
   `echo tapped > ~/tapped.txt`; piped content; on a tty without -c returns at once; remove → none left.
4. share: stdin text → share sheet "Sharing text / Subject" (`share2.png`); file send → chooser; `-d` view → HTMLViewer;
   missing file → exit 1.
5. battery JSON (PLUGGED_AC), wifi JSON (`ip 10.0.2.16`, `<unknown ssid>`), volume list/set/bad stream, media-scan
   `-r -v` (2 files; link not followed), download → `/sdcard/Download/downloadfile.bin` (577 bytes).
6. sms-send, location, brightness, notification-list → "… is not supported in Kabuk", exit 1. After
   `pkg install termux-api` 0.60.0 all 57 `bin/` names resolve to `files/kabuk/bin`.
7. chip: motd URL at the bottom → no chip before/after first keystroke; `echo https://example.com/kabuk-test` →
   "Open link · example.com"; `clear; echo https://termux.dev/issues` → no chip.
8. three tabs = three bash; closing the middle killed only its bash. login+bash renamed → "Phone shell" starts busybox
   ash (Termux:API names work), open Termux tabs keep running; restored.
9. `TV=0.118.3 KV=0.8.2 KVC=38`.
10. CMD logs: name + outcome only; no test string (clipboard/notification/share/toast text, paths) in any `Kabuk.*` line.

## Unverified
- TTS speaking, real vibration/torch light; `--sound`, `--vibrate` pattern, `--image-path`, `--on-delete`, `--ongoing`
  on a device (unit-tested parsing only); `termux-download -p`; volume under DND; everything on the phone (arm64).

## Open questions / notes
- `$PREFIX/libexec/termux-api` and `usr/bin/termux-*` by full path still hang (wait for the Termux:API app); only PATH
  lookups get Kabuk's. Replacing the dpkg-owned libexec binary was left out.
- tts answers when queued, not when spoken (10 s answer limit).
- One silent default-importance "Scripts" channel; `--priority high` can't give heads-up. A second channel is easy.
- Emulator's first `pkg update` failed on mirror termux.librehat.com (404 for x86_64 Packages); used
  packages-cf.termux.dev for the termux-api install. Possibly x86_64-only; worth a look.
- With usr/bin/login missing, plain "Phone shell" falls back to busybox; B2's install offer may show for a broken
  (not absent) install — Repair is the right action there.
