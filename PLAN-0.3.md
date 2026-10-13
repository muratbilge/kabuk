# Kabuk 0.3: design council plan

Four reviews of 0.2.7 (Material 3 expert with the material-3 skill, agent-workflow UX designer,
ergonomics/accessibility specialist, Codex). Full reports: `docs/council/`. This is the merged plan.

## Where everyone agreed

1. **Nothing may change the terminal's height by surprise.** Every bar that appears shrinks the terminal,
   the agent redraws, and that is what made the quick replies vanish. → Reply chips take the place of the
   key bar's top row (same height). Resizes after the keyboard opens are debounced; pinch resizes once.
2. **Dangerous taps.** Empty Send sends Enter (= picks "1. Yes" in a Claude menu); Esc sits right next to
   "1 Yes"; every tab has a × that closes instantly; "Close all" has no confirmation.
3. **The theme is half defined**, so Material's default purple leaks into switches, sliders and chips; colors
   are hardcoded; subtitles get a 24sp line height (the "double-spaced" look).
4. **Tapping the terminal always opens the keyboard**, so reading in herdr makes the layout jump.

## 0.3.0 (now)

| # | Change | From |
|---|---|---|
| 1 | Reply chips replace key-bar row 1 while a menu is open: `1 Yes` filled, others tonal, Esc outlined at the far end, a keys button to get the normal row back; 48dp, haptic, "✓ Sent: 1 Yes" snackbar | all four |
| 2 | "No…" chip answers and opens the message box focused, "Tell Claude what to do instead…" | UX |
| 3 | Send disabled while the message is empty (Enter stays on the key bar) | ergonomics, Codex |
| 4 | Tab × only on the selected tab, 48dp; closing shows "Closed DevBox · Undo" (reopens); Close all asks first | all |
| 5 | Tap in herdr/tmux (mouse programs) no longer opens the keyboard; ⌨ and the message box do | ergonomics, Codex |
| 6 | Server resize debounced while the keyboard animates; pinch zoom resizes once at the end | ergonomics |
| 7 | Expanded message box sized from the space that's really left (keyboard included); mic always there; ⚡ inside the field | all |
| 8 | Complete M3 dark scheme: logo peach = primary, blue = secondary, logo pink = tertiary ("needs you"); typography roles fix the line height; M3 shapes | M3 |
| 9 | Snackbars instead of toasts | M3 |
| 10 | Key bar: whole cell is the touch target (no dead gaps), hint labels 10sp and readable, keys stand out from the bar, real keyboard haptics | ergonomics |
| 11 | Disconnected banner in error colors with Reconnect as the main button; status not by color alone | UX, ergonomics, M3 |
| 12 | Quick-reply detection only runs while the app is visible | ergonomics |
| 13 | Hosts: top app bar with logo, "Add server" FAB, list items; Settings: segmented buttons, list rows; host edit: 48dp color dots with check | M3 |
| 14 | Agents chip: 48dp target, logo pink when someone needs you | M3, ergonomics |

## Later (0.4, after the user tries 0.3)

- **Answer from the notification**: question text + Yes / Always / No buttons (unlock required); tapping it
  reconnects if the tab is gone. Biggest win for "pocket loop" (UX #2/#3).
- **Agents sheet as an inbox**: question excerpt + chips per blocked agent.
- Key bar: tmux/herdr prefix key, "more keys" sheet, long-press alternates with preview, one row while the
  keyboard is up, swipe to switch tabs.
- Copy text: search, "Copy last reply", bottom toolbar.
- TalkBack for keys and terminal, landscape layout, font-scale-safe heights.

## Not doing

- Quick replies as an overlay on the terminal (M3 proposal): it would cover the menu it answers.
- Dynamic (wallpaper) colors: a terminal benefits from a stable palette; may come later as an opt-in.
