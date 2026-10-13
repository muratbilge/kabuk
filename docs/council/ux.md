# Kabuk: mobile UX review (one hand, on a bus)

The test scenario: the user's phone buzzes, they answer the agent, and the phone goes back in their pocket. Today that is **unlock + tap notification + wait for herdr focus + up to ~1.2 s chip detection + tap chip = 3 actions and ~2 s**. During that time the terminal shrinks when the chip row appears, and Claude redraws. Recommendations are ranked by impact.

## 1. Quick replies replace key-bar row 1 (decision) — S/M
**Problem:** `QuickReplyBar` is an extra row between the terminal and the KeyBar (TerminalScreen.kt). When it appears, the terminal loses about 48 dp. That triggers a SIGWINCH and Claude redraws, which is why the sticky/`redrawing`/`repliedAt` workarounds were needed. Note that `approval.png` shows *no* chips at all, so the render test does not cover this.
**Options considered:**
- An overlay on the terminal would cover the menu it answers.
- Putting the chips inside the message box hides them when the box is closed.
- **Swapping the chips in for key-bar row 1 has a fixed height.** The rows are 44 dp either way, so nothing resizes and nothing redraws. It works with the box open or closed, and row 2 keeps ← ↓ → and modifiers for menus with more than 3 options.
```
normal:   [ESC][⇧TAB][ / ][ @ ][ ↑ ][^C ][ ⏎ ][PASTE]
prompt:   [Esc][ 1 Yes ][ 2 Always ][ 3 No… ][⌨︎keys]   ← same 44dp row
          [TAB][CTRL][ALT][ ← ][ ↓ ][ → ][ ✎ ][ ⌨ ]
```
- Chips get `weight` and fill the width, which makes the targets bigger. "1" is filled (primary) and "No" is tinted red.
- The trailing "keys" chip brings the normal row back. It stays manual until the menu disappears.
- Animate with a cross-fade only, never a height change.
- With this layout the 1.2 s/1.5 s hysteresis can be relaxed, because a flicker no longer costs a redraw.

## 2. Answer from the notification itself — M
**Problem:** The notification says only "claude needs you · kabuk / Waiting for your answer on DevBox". It has no question and no actions (AgentNotifier.build).
**Proposal:**
- When status turns `blocked`, run `herdr pane read` on that agent's pane (it already exists in `Herdr.readFocused`, generalise it to a pane id). Then feed the result through `QuickReplies.find`.
- Build a `BigTextStyle` from the last ~6 lines: "Bash: ./gradlew assembleRelease — Do you want to proceed?"
- Add up to 3 `Notification.Action`s: **Yes**, **Always**, **No…**. Yes and Always write the key to that pane through the SSH connection without opening the app. Use `setAuthenticationRequired(true)` on API 31+ so they cannot fire from a locked pocket.
- "No…" opens the app with the message box focused (see #5).

This brings the common case to **1 tap**. Happy Coder and Moshi both sell exactly this: push for permission requests plus one-tap approve/reject ([Happy](https://apps.apple.com/app/id6748571505), [Moshi](https://getmoshi.app/articles/best-ios-terminal-app-coding-agent)).

## 3. A tapped notification always lands on a ready answer — M
**Problem:** If the process was killed or the tab is gone, `ACTION_OPEN_AGENT` finds no tab and silently stays on Hosts. If the tab exists, the chips wait for focus → redraw → the 350 ms poll.
**Proposal:**
- Put `hostId` in the intent. If no tab exists, open one, connect and focus the agent, with a "Connecting to DevBox → kabuk…" banner.
- Pre-seed `ui.choices` from the herdr pane read done for #2, so the chips are there the moment the screen appears.

## 4. Agents sheet becomes an inbox — M
**Problem:** The sheet (`agents.png`) shows name, agent and cwd but not *what* a blocked agent asks. To answer, the user has to switch the whole screen.
**Proposal:** Blocked rows show a 2-line question excerpt and inline chips. Working rows show the last status line ("Running gradlew… 2m").
```
NEEDS YOU
● notes-app  claude · ~/projects/notes-app
  Bash: systemctl restart wg0 — proceed?
  [1 Yes] [2 Always] [3 No…]          [Open ›]
WORKING
◐ kabuk      Running ./gradlew assembleRelease · 2m
```

## 5. "No, and tell Claude what to do" is one step — S
**Problem:** Today this takes: tap 3, open ✎, type, send.
**Proposal:**
- A chip whose label starts with "No" sends its key, opens and focuses the message box, and sets the placeholder to "Tell Claude what to do instead…".
- Also: if the user sends a draft while a menu is showing, ask "Answer 3 (No) and send this?" instead of typing text into the menu.

## 6. Confirm every remote answer — S
**Problem:** After a chip tap, the chips just vanish, so the user can't tell whether the answer was sent before they pocket the phone.
**Proposal:** Show a 2 s snackbar, "✓ kabuk: 1 Yes", plus haptic `Confirm`. Do the same for notification actions (update the notification to "Answered: Yes").

## 7. Voice is always one tap away — S
**Problem:** The mic is replaced by the expand icon as soon as the draft is non-empty (PromptBox `trailingIcon`), so the user can't dictate a second sentence.
**Proposal:**
- Keep the mic permanently as the trailing icon.
- Move expand to a small drag handle / ⤢ on the field's top edge.
- Auto-grow the compact field from 2 to 4 lines before expand is needed.

## 8. Make the message-box actions honest — S
**Problems:**
- Snippets *append* but history items *replace* the draft without warning, and both live in one dropdown.
- Long-press = "insert without Enter" is invisible.
- ⌄ (hide) duplicates the lit ✎ key.

**Proposal:**
- Make ⚡ a bottom sheet with two tabs, Snippets | Recent. Both insert. If the draft is non-empty, Recent offers "Replace" / "Append".
- Turn Send into a split button: tapping the arrow sends; a small ▾ on long-press opens "Insert only" and "Send + Esc first".
- Drop ⌄ and give its 36 dp to the field.

## 9. Key bar: the right 16 keys for Claude + herdr + tmux — S
**Problem:**
- `@` and `/` are cheap on the IME and rarely needed when the IME is down.
- ⌨ duplicates "tap terminal", the existing gesture to show the keyboard.
- There is no single-tap tmux/herdr prefix, so ^B takes two taps (CTRL, b).
- Missing for Claude are ^R (history search), ^O (transcript), and a double-Esc (rewind).

**Proposal:**
```
[ESC ~][⇧TAB |][ / \ ][ ↑ PGUP][^C ^D][ ⏎ NL][PFX ^B][PASTE TEXT]
[TAB -][CTRL  ][ALT ⇧][ ← HOME][ ↓ PGDN][→ END][ ✎   ][ ⋯ more ]
```
- PFX sends the configured prefix (tmux ^B, herdr's prefix). Swipe-up on PFX gives the other one.
- "⋯" opens a sheet of rarer keys: @ # ^R ^O Esc-Esc F1–F12 ⌨. Keys stay editable in Settings, as in Blink's configurable keyboard ([docs](https://docs.blink.sh/basics/customize)).

**Discoverability:**
- The 8 sp grey corner labels are fine once learned, but nothing teaches the swipe.
- Also accept **long-press** for the alternate on non-repeating keys, with a Gboard-style popup bubble showing what will be sent.
- Show a one-time coach mark the first time the bar appears.

## 10. Collapse the key bar to one row while the IME is up — M
**Problem:** With the IME (~280 dp) up, the chrome takes ~470 dp: tab strip 46, keys 94, box 56. That leaves Claude about 14 rows at 46 columns.
**Proposal:**
- While the IME is visible, show a single row: ESC ⇧TAB ↑ ↓ ^C ⏎ ✎ ⋯. A resize is already happening because the IME moved, so collapsing costs no extra redraw.
- In the "prompt" state that row is the chip row from #1.

## 11. Tab strip: no accidental closes; colours that mean one thing — S
**Problems:**
- Every tab carries a 34 dp × right next to the tap target. On a bus, that is how tabs die.
- Red/amber/green mean *connection* on tabs and *agent status* on agents. In `terminal.png`, "prod" has a red dot (disconnected) that reads as "needs you".
- In `approval.png` the agents chip pushes the DevBox tab half off-screen.

**Proposal:**
- Show × only on the selected tab. Close otherwise via long-press, or by swipe-down on a tab.
- Show connection as an icon or dimming (cloud-off, greyed title), not a coloured dot. Reserve colour for agent state.
- Move the agents chip to a fixed slot at the left edge, so it is never scrolled away and always under the thumb on the notification path.

## 12. Agents as first-class tabs (follow-up to #11) — L
When herdr is present, the user thinks in agents, not SSH connections. Optionally, the tab strip could list agents (status dot + place), with hosts grouped behind a host switcher. Horizontal swipe on the tab strip cycles agents and calls `herdr agent focus`. Do this after #4 proves the inbox model.

## 13. Disconnected state keeps the screen readable — S
**Problem:** `terminal-disconnected.png` is a black screen. The banner offers only "Close" (Reconnect is missing in the render, so check the fake tab), and Close sits on the banner's thumb side.
**Proposal:**
- Keep the last frame, dimmed to 60%, with a "stale" label, so the user can still read what Claude said.
- Make **Reconnect** a filled primary button. Put Close in the overflow menu.
- Reconnect immediately on `onStart` and on network change instead of waiting for the countdown.
- Keep the draft (already done). Moshi's whole pitch is surviving network switches ([compare](https://getmoshi.app/compare/termius)). Mosh support is a longer-term L.

## 14. Connecting state that says something — S
**Problem:** Connecting is shown only by a 2 dp progress line over a blank terminal.
**Proposal:** Show a banner, "Connecting to DevBox · attaching tmux main…", with Cancel. After 10 s, add "Still trying (Wi-Fi → mobile?)".

## 15. Reading and copying long output — M
**Good already:** text mode reads the herdr pane unwrapped and opens at the bottom.
**Problems:**
- There are three hidden entry points: ⋮ menu, swipe-up on PASTE, and the tab long-press.
- There is no search.
- Selecting one Claude reply in a 4000-line Text means long-press-dragging.

**Proposal:**
- When the terminal is scrolled up, show a "Read ☰" FAB beside the jump-to-bottom FAB.
- In text mode add 🔍 find, plus **"Copy last reply"**: the last `●` block before the `>` prompt.
- Add a top/bottom fast-scroll thumb.

## 16. Safer approvals on a bumpy ride — S
**Problem:** "2 Yes, and don't ask again" is permanent and sits next to "1".
**Proposal:**
- Make chips ≥ 48 dp tall with 8 dp gaps.
- Give "Always…" a 400 ms press-and-hold, with a fill animation on the chip only (not on the terminal path).
- Keep Esc on the far left, away from the right thumb.

## 17. First run — S
**Problem:** An empty Hosts screen offers only "Add server", and Password is the only visible auth path in the form.
**Proposal:**
- Add a 3-step first-run card: 1) add DevBox, 2) "Use a key" generates one, with a Copy/Share public key button (`ssh-copy-id` one-liner), 3) "Install herdr notifications?" with a test notification.
- After a successful first connect, offer to pin a home-screen shortcut (the shortcuts already exist).

## 18. Settings wording — S
Settings has "Notify when an agent finishes" while "needs you" is always on. Add a **"Send a test 'needs you'"** button, and a link to the system channel settings so the user can let it bypass Do Not Disturb.

**Suggested order:** #1, #6, #7, #11, #5 (all S, this week) → #2, #3, #4 (the notification-to-answer pipeline) → #9, #10, #13–#15 → #12.
