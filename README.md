# Kabuk

**Kabuk** (Turkish for "shell") is an Android SSH terminal made for running Claude Code and other coding agents on your dev server from a phone.
It's a Termux replacement for that job: the same terminal engine, with better keyboard input, copy and tabs.

## Features

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
agents/ Herdr (agent list / focus / pane read over SSH), AgentNotifier
text/   Unwrap (re-join wrapped lines), QuickReplies (numbered menus on screen)
ssh/    KeyPairStore (Ed25519 via BouncyCastle), HostKeys (trust on first use)
data/   Hosts, Settings, SecretBox (Keystore AES-GCM)
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
