# B11 — ~/.termux settings in Phone tabs

Worktree `/projects/kabuk/.worktrees/codex`, branch `codex/termux-props`.
No push, release, version bump, or apk-share access.

## Built

- Phone-only settings snapshots at shell startup and on `termux-reload-settings`, including busybox fallback.
  Existing SSH preferences and the saved global settings are not changed.
- Termux JSON-ish extra-key rows, single quotes/unquoted object keys, continued properties lines, display labels,
  popup swipe-up actions, macros, sticky FN, all requested special keys and five display styles. Invalid layouts
  fall back to Kabuk's bar with a reload error; custom layouts retain their rows instead of automatic reply chips.
- Independent foreground/background/cursor/ANSI color overrides, custom font loading with fallback, live renderer
  metrics refresh, cursor shape and timed blink with view-lifecycle cleanup.
- Black Phone tab/key-bar chrome; hide keyboard on first display/reload; Phone-only bell/volume behavior.
  Scrollback capacity still applies to new sessions. Unsupported fullscreen/shortcut.* settings are reported.
- Updated command help, README and LOCAL-TOOLS documentation. Error messages omit values; no new content logging.

## Verification

Required command:

```
JAVA_HOME=~/tools/jdk-17.0.19+10 ./gradlew -q testDebugUnitTest assembleDebug
```

The sandbox initially blocked Gradle's local socket setup:
`Could not determine a usable wildcard IP for this machine.`
The build was rerun with approved sandbox escalation. Final gate exited **0** (quiet Gradle output):
**177 tests, 0 failures, 0 errors, 8 skipped**; debug APK assembled successfully.

Regression tests cover documented row and object examples, popup macros, all requested keys and styles,
invalid layouts with redacted errors, continuation lines, settings-file removal, color parsing, ignored
properties, macro output bytes/modifier restoration, and font fallback/loading. A byte-output regression caught
SPACE needing literal text rather than a terminal special-key code; corrected before the final gate.

`git diff --check`: clean.

## Unverified / handoff

- Emulator checks were skipped under the brief's explicit sandbox exception. `adb devices` failed to start its
  server (`Operation not permitted`); `docker ps` failed with permission denied on `/var/run/docker.sock`.
  No emulator was started, killed or modified.
- Orchestrator should check on the emulator: two Phone tabs and an SSH tab; reload changed/removed key layouts,
  palette and font; swipe popup, FN, macro and DRAWER/KEYBOARD; cursor style/blink and IME startup behavior;
  malformed layout/font fallback with reload diagnostics. Hardware typography, IME and cursor animation remain
  unverified here. Robolectric font tests cover empty-file rejection and loading the bundled valid TTF.
- Supported colors are #RGB and #RRGGBB. Black UI affects Phone tab-strip/key-bar chrome. DRAWER maps to Servers.
  Files are bounded (256 KiB properties/colors, 32 MiB font); layouts allow at most 16 rows, 32 keys per row and
  256 macro tokens. No new product decisions need approval.

## Second brain

Read `/projects/secondbrain/AGENTS.md`, skimmed INDEX.md, searched `termux`, and consulted Android/build-limit
notes. No shared brain files changed: the task explicitly limits writes to this worktree. Reusable lesson for
later promotion to `stack/android.md`: Termux's `TerminalColors.COLOR_SCHEME` is process-wide; per-session theme
settings must overlay each emulator's `mCurrentColors` after resets instead of changing the global scheme.
