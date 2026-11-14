# Kabuk: tools in the Phone tab (Termux abilities) — request from Murat, 2026-11-14

Handed over by the homelab-watch Claude session. Murat's words: "Kabuk should behave like a phone terminal, we
should have that", then "we should build Kabuk first", then "inform the Kabuk workspace and run agents".

## Why
Murat needed `rclone authorize "pcloud"` on the phone (a CLI login that opens the phone's browser) and was told to
use Termux. He wants Kabuk to replace Termux for this kind of job too, not only as an SSH terminal.

## What exists
Kabuk 0.6.0 already has `LocalTab` ("Phone" tab, `app/src/main/java/app/kabuk/term/LocalTab.kt`): Android's
`/system/bin/sh` via Termux's `TerminalSession`, PATH `/system/bin:/system/xbin`, no extra programs.

## The constraint
targetSdk 35: since Android 10 (W^X) an app with targetSdk >= 29 cannot exec files it writes into its own data dir.
Termux avoids this only by staying on targetSdk 28 (and is off Play for that reason). Files in the app's
`nativeLibraryDir` (shipped as `jniLibs/<abi>/lib*.so`, extracted at install with `useLegacyPackaging = true`) CAN be
executed.

## Options presented to Murat (he answered "run agents"; option A was the recommendation)
- **A. Built-in toolset (recommended):** ship static arm64 binaries as `lib<name>.so` in jniLibs: busybox (applets via
  a bin dir of symlinks or wrapper scripts in the app's files dir, put on the Phone tab's PATH), `rclone`, an ssh
  client (dropbear or OpenSSH static), maybe `git`. +~70 MB APK. New tools come with app updates, no `pkg install`.
- B. Termux-style package manager: needs targetSdk 28 and Termux packages rebuilt for Kabuk's prefix. Very large.
- C. Drive Termux from Kabuk (RUN_COMMAND intents). Small, but Termux stays installed.
If you see a reason against A, ask Murat (orch ask_human) before building; otherwise build A.

## Wanted (A), in steps, each released through Kabuk's in-app updater when green
1. Short design note: which binaries, their sources/licences (busybox GPL-2.0: ship source offer/notice like the
   font licence), exact static builds for arm64-v8a (verify they run under Android's seccomp/bionic-free static
   linking), how PATH/HOME/TMPDIR are set in `LocalTab`, size budget, how `exec` from nativeLibraryDir is verified
   on Murat's phone (OnePlus CPH2449, Android 16).
2. busybox in the Phone tab (`ls`, `grep`, `vi`, `wget`, `tar` ...), with a test that the applets exec.
3. `rclone` with a working `rclone authorize` flow: when a tool prints/opens an http(s) URL, Kabuk offers to open it
   in the browser (an `xdg-open`/`termux-open-url`-style helper on PATH that sends an Android VIEW intent), and the
   OAuth redirect to `http://127.0.0.1:53682/` must reach rclone's local listener (same app process network = fine;
   check it).
4. ssh client and anything else only after 1-3 work.
README "What it does" gets a Phone tab section.

## Rules
Kabuk's own AGENTS/CLAUDE rules, the global ones (Codex first; Codex is busy/limited today -> Opus subagent as
fallback; visible agents as tabs of this workspace; worktree in `.worktrees/<agent>`; agents in auto-approval mode with
a Haiku watcher; releases only through the in-app updater; the live log for diagnosis). Tell Murat on the bus
(to: murat) when a step is released.
