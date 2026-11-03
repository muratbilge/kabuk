# Kabuk

**Kabuk** (Turkish for "shell") is an Android SSH terminal made for running Claude Code and other coding agents on your dev server from a phone.
It's a Termux replacement for that job: the same terminal engine, with better keyboard input, copy and tabs.

## Features

### orchtool, updater, live log (0.5)
- **0.5.7 — native workspace bar.** On an orch server a slim bar under the tab strip shows orchd's workspaces as
  chips (the agents' most urgent status as a dot, the one on screen in the TUI lit) with that workspace's tabs after
  it; tap = show it in the TUI (`orch ws focus`, `orch tab focus`), long-press = new tab (shell, Claude, Codex),
  rename, close (confirmed: closing kills the panes), + = a new workspace in a folder. The layout comes with the
  watch line (`workspaces`, orchtool 1f30b5f); tabs are addressed by id, so a tab closed meanwhile is "gone" (exit
  14), never another tab. Hidden on herdr servers and while orchd is down.
- **0.5.6 — real Claude Code dialogs.** While a permission box waits, Claude Code pulses the bullet of the pending
  tool line every second, so the whole-screen hash orchd checks keeps changing and the four lines Kabuk
  fingerprints could too. The fingerprint now ignores bullets and ticking spinner lines (real screens in the test
  fixtures `claude-*-box-*.txt`); a send refused as "changed" is retried with a fresh read while the dialog is
  still the same; after a real change the inbox shows the dialog that is actually on screen at once.
- **0.5.5 — one orch call per answer.** The watch line carries each blocked pane's screen hash (orchtool
  e3a0257); when the dialog on the phone still matches the stream, the answer is a single `orch send --expect`
  without rereading the pane. Older orch, or a stale dialog: reread as before.
- **0.5.4 — fixes from orchtool's review of Kabuk.** The orch TUI and agents started from the phone run through a
  login shell (real PATH and profile). A herdr pane's dialog is never retried (no answered-once memory there); a
  screen that changed after a silent try is "Unsure", not "changed"; the answer ledger survives stream gaps and is
  kept where a retap could type twice. An orch server without orch (or with one too old for `orch watch`) falls back
  to herdr for the agents too. One notification per `ask_human` (no extra "needs you"). Notification buttons name the
  server and are dropped when stale; old notifications are cleared at start. Unchanged heartbeat lines are skipped.
- **0.5.3 — honest contract with orchtool.** Every answer reports what orch said (exit-code table in orchtool's
  `notes/kabuk-contract-v2.md`): the screen changed, answered already, the agent is gone or not taking input,
  orchd not running, or Kabuk and orch disagree (update one of them). No reply in time → one retry (orchd answers
  each screen once, so a landed first try is recognised), then "Unsure". The watch line's `v` is checked: a newer
  orch shows "update Kabuk" in the agents sheet. Reply chips on an orch server answer the dialog's own pane
  through `orch send --expect`, never whatever pane the TUI shows. A malformed watch line is logged without its
  text. Test: `OrchContractTest` (shared fixture `orch-watch-v1.json`, the exit codes through a real `sh`).
- **Start with: orch.** The server's orchtool TUI runs in the tab; agents, their dialogs, the questions they ask
  you (`ask_human`) and the message timeline come live from `orch watch --json`. The agents sheet shows "Asks you"
  with the agent's options and Reply…, and Comms with Message… to one agent or all. A dialog is answered with
  `orch send --expect <screen hash>`: orchd sends only if the screen is still what the phone read, and answers each
  dialog once (first answer wins, from any client). Falls back like Auto when orch isn't installed.
- **In-app updater** (Settings → Update): `update.json` is signed with a key whose public half is compiled in; the
  APK must match its size, SHA-256, package and signing certificate before Android's installer is asked.
  Publishing: `scripts/release.sh` (see Build).
- **Live log** (Settings → Diagnostics, on by default while the app is young): connection, agent and crash lines go
  in HMAC-signed batches to `scripts/logsink` on the DevBox; read them with `scripts/livelog`. Never passwords or
  prompts.

### herdr native (new in 0.4)
- **Live:** a small watcher (Python, `assets/herdr_watch.py`) runs on the server over the SSH connection and
  listens to herdr's event stream (`events.subscribe`). Status changes arrive at once instead of polling every 3 s
  (falls back to polling when the server has no python3).
- **Answer from the notification:** "claude needs you" shows the question and up to 3 answer buttons (unlock
  required). The answer is checked against the question that is still open, then sent with `herdr pane send-text`,
  without opening the app. "No, and tell…" opens the message box for the reason.
- **Agents inbox:** waiting agents show their question with answer buttons in the agents panel.
- **New agent:** start Claude or Codex in a folder (new herdr workspace, folder checked first).
- Install herdr's integrations for exact status: `herdr integration install claude` / `codex`.

### For agents (new in 0.2)
- **Agents panel:** if herdr runs on the server, Kabuk reads its agent list every 3 s over the existing SSH
  connection; nothing extra to install. A chip in the tab bar shows "1 needs you" / "2 working". Tap it for
  all agents grouped Needs you / Working / Idle; tap an agent to bring it on screen (`herdr agent focus`).
- **Notifications:** "claude needs you · notes-app" when an agent waits for an answer, and (optional)
  "finished" when it goes from working to idle. Tapping one opens that agent. Works with the screen off,
  because the foreground service keeps the connection alive.
- **Quick replies:** when a numbered choice menu is on screen (Claude Code / Codex approvals), its answers take
  the place of the key bar's top row (`1 Yes` `2 Yes, and…` `3 No…` `Esc` ⌨), so the terminal never changes size.
  One tap answers, with a "Sent" confirmation; "No, and tell Claude…" opens the message box for the reason.
- **Prompt box:** ⚡ snippets (/clear, /compact, /model, … editable in Settings) and earlier prompts; 🎤 voice;
  🖼 picture → shrunk, uploaded to `~/.cache/kabuk/` over SFTP, and its path is put in the prompt.
  Screenshots shared into Kabuk do the same. Each tab has its own draft; text is kept when you're offline.

### Design (0.3)
Material 3 dark theme built from the logo (peach primary, blue secondary, pink for "needs you"), snackbars with
Undo/Reconnect, 48dp touch targets, readable key hints, keyboard haptics. Reviewed by a design council: see
`PLAN-0.3.md` and `docs/council/`.

### Terminal

- **Tabs:** the selected tab has a × button (closing an SSH tab offers Undo). Long-press a tab for Rename, Reconnect, Copy text, Move, Duplicate,
  Close, and End session on server (kills the tmux session). Tabs with new output get a dot.
- **Prompt box:** a normal text field with autocorrect, swipe typing and voice input; two lines, ⌃⌄ for a big
  editor, ⌄ hides it (the lit ✎ key brings it back). Send pastes the text as one block and presses Enter; it is
  off while the field is empty. Long-press Send to insert without Enter. The last 50 prompts are kept, encrypted.
- **Key bar:** two rows tuned for Claude Code (ESC, ⇧TAB, /, ⚡ quick messages (swipe up: @), arrows, ^C, ⏎, PASTE, TAB, CTRL, ALT, ✎ prompt box, ⌨ keyboard).
  - Swipe a key up for the small label shown on top (e.g. `/`→`\`, `↑`→PgUp, `^C`→`^D`, `⏎`→newline).
  - CTRL and ALT: tap once for the next key only, double-tap to lock.
  - Arrow keys repeat while held.
- **Copy and paste**
  - Long-press selects a word. Drag the big handles; the finger sits below the text, and dragging at the edge scrolls.
    Copy joins wrapped lines.
  - "Copy text…" opens the whole history as normal Android text. For tmux tabs it comes from the server
    (`tmux capture-pane`), so it includes everything that scrolled by.
  - Copy screen, Paste, Ctrl+Shift+C / V on hardware keyboards. Programs can copy to the phone clipboard (OSC 52).
  - Tap a link to open or copy it.
- **Sessions that survive**
  - "Start with" per server: **Auto** (default) = herdr if the server has it (its own server keeps the agents
    running, no tmux layer in between), else tmux (`tmux new -A -s main`), else a plain shell; or always tmux;
    or a plain shell.
  - Automatic reconnect after a drop-out or a Wi-Fi ⇄ mobile switch.
  - A foreground service keeps connections open in the background. Its notification has a wake lock toggle and Exit.
- **Reading:** new output doesn't throw you to the bottom while you're reading history; a ⇊ button jumps back down.
  Fling scrolling. In tmux, vim and less, scrolling sends mouse wheel events. In herdr/tmux a tap is a click and
  only opens the keyboard when you tap where you type (or press ⌨).
- **Login**
  - The phone's own Ed25519 key, encrypted with the Android Keystore.
  - After the first password login, the app offers to install its key on the server.
  - Saved passwords are encrypted. Host keys are checked like known_hosts (trust on first use, loud warning if one changes).
- **Termux extras:** a phone shell (`/system/bin/sh`), volume keys as Ctrl / Fn (optional), pinch to zoom,
  5 color themes, bell vibration, keep screen on, and text shared from other apps lands in the prompt box.
  Long-press the app icon for per-server shortcuts.

Not included: Termux's package system (`pkg install`). Your server is the Linux machine.

## Build

```sh
scripts/release.sh                     # build + sign + verify → ~/apks/kabuk/kabuk-<ver>.apk
./gradlew :app:testDebugUnitTest       # screenshot tests → app/build/outputs/roborazzi/*.png
```

The SSH integration test needs a throwaway server:

```sh
docker run -d --name kabuk-sshd-test -p 127.0.0.1:2222:2222 -e USER_NAME=test -e USER_PASSWORD=kabuktest \
  -e PASSWORD_ACCESS=true lscr.io/linuxserver/openssh-server && docker exec kabuk-sshd-test apk add tmux
KABUK_TEST_SSH=127.0.0.1:2222 ./gradlew :app:testDebugUnitTest --tests app.kabuk.SshIntegrationTest
docker rm -f kabuk-sshd-test
```

## Architecture

```
term/   TermTab (one tab) → SshTab (sshj, reconnect loop) / LocalTab (Termux TerminalSession)
        TerminalCanvasView: draws with Termux's TerminalRenderer; own touch, selection and keyboard handling
        TabManager: all tabs, prompts for background threads, clipboard; Themes
agents/ Herdr (herdr CLI over SSH), Orch (orchtool: watch stream, answers, bus), AnswerLedger (answer once),
        AgentNotifier (needs-you / finished / asks-you notifications with answer buttons)
text/   Unwrap (re-join wrapped lines), QuickReplies (numbered menus on screen)
ssh/    KeyPairStore (Ed25519 via BouncyCastle), HostKeys (trust on first use)
data/   Hosts, Settings, SecretBox (Keystore AES-GCM)
update/ Updater (download, verify, PackageInstaller), UpdateManifest (signed update.json)
log/    KLog (logger + crash handler), LogShipper (HMAC-signed batches to scripts/logsink)
service/SessionService: foreground service + wake lock
ui/     Compose screens: terminal, key bar, servers, settings, copy text
```

Libraries: Termux terminal-emulator / terminal-view (Apache 2.0), sshj, BouncyCastle, Jetpack Compose.
Font: JetBrains Mono (OFL, see FONT-LICENSE-OFL.txt).

## License

Apache License 2.0, see `LICENSE`. Uses Termux's terminal-emulator and terminal-view (Apache 2.0), sshj
(Apache 2.0), Bouncy Castle (MIT) and the JetBrains Mono font (SIL OFL 1.1, `FONT-LICENSE-OFL.txt`).

`scripts/release.sh` signs with Android's debug key, which is fine for your own phone. To hand the app to
others, sign it with your own release key.
