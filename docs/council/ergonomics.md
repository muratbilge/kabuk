# Ergonomics, interaction, perf, a11y: ranked findings

Base: 412x~890dp; status bar about 24dp, nav bar 48dp, IME about 300dp. Paths are relative to `app/src/main/java/app/kabuk/`.

## 1. An empty Send approves the highlighted Claude option. Effort S
`PromptBox.kt:30-32`: `send(true)` with a blank draft still sends `\r`. `sendBlock` then writes Enter (`TerminalCanvasView.kt` sendBlock). If a permission menu is open, this picks "❯ 1. Yes". The 48dp send button sits 4dp from the mic/expand icon (`PromptBox.kt:117`), and the mic is about 48dp wide, so a missed tap lands on Send.
**Fix:** when the draft is blank, disable Send (`enabled = draft.isNotBlank()`, plus a muted container). Enter is still on the key bar ⏎.

## 2. Closing things is a one-tap action next to the target, with no undo. Effort S
- **Tab ×:** a 34dp `IconButton` inside every 36dp-tall tab chip (`TerminalScreen.kt:185-198`). It overlaps the tab's own tap area and calls `TabManager.close` right away (`TabManager.kt:96`). On a plain SSH or local tab that kills the session.
- **"Close all tabs":** sits directly under "Settings" in the ⋮ menu (`TerminalScreen.kt:228`) with no confirmation.

**Fix:** show × only on the selected tab. Give it `Modifier.minimumInteractiveComponentSize()`, which makes 48dp, and separate it from the label by 8dp or more. Show a `SnackbarHost` "Tab closed · Undo" by deferring `t.close()` for about 4s. Put "Close all" behind an `AlertDialog`.

## 3. Quick-reply chips put Esc right next to "1 Yes", and the chips are too short. Effort S
`AgentUi.kt:126-148`: chips are 38dp tall with a 6dp gap. The red-text Esc (cancel or reject) comes first, flush against the filled "1 Yes". These are opposite answers, one thumb-width apart. Taps give no haptic.
**Fix:**
- Order the row as primary first: `1 Yes`, `2 …`, then a spacer, then Esc at the far end as an outlined chip.
- Use `heightIn(min = 48.dp)` and 8dp spacing.
- Add `view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)` (API 30+, else `VIRTUAL_KEY`) on tap.
- Note: `approval.png` does not show the chip row at all. Add a Robolectric shot with `ui.choices` filled so the council can judge the real layout.

## 4. Every chrome change resizes the PTY, so the agent redraws. Effort M
`TerminalScreen.kt:146-148`: QuickReplyBar, KeyBar and PromptBox all sit in the Column. The terminal is `weight(1f)`, so all of these resize it, then `updateSize()`, `t.resize()`, SIGWINCH, and a full Claude/herdr redraw:
- the chip bar appearing (+48dp)
- the IME opening (about 300dp)
- expanding the prompt
- the "Expand" icon appearing as soon as the draft is non-empty

`updateSize()` also calls `clearSelection()` (`TerminalCanvasView.kt:129`). Opening the keyboard therefore kills a selection. That is the brief's open "bar changes terminal height" problem.
**Fix:**
- Draw the chip bar as an overlay: `Box { AndroidView; QuickReplyBar(Modifier.align(BottomCenter)) }`. The terminal can scroll its view up by one bar height while the bar is visible.
- Debounce `resize` by about 150 ms after IME animation end. Use `WindowInsetsAnimationCompat.Callback` and resize only in `onEnd`.
- Keep the selection when only rows change.

## 5. The expanded prompt plus the IME can squeeze the terminal to zero. Effort S
`PromptBox.kt:74`: `maxExpanded = screenHeightDp * 0.45` comes to about 400dp. `screenHeightDp` ignores the IME.
Total with the IME up: 400 + IME 300 + KeyBar 94 + tabs 46 + bars 72 = about 912dp, which is more than the 890dp screen. The terminal ends up at about 0dp (cols/rows clamp to 4).
**Fix:** size the field from what is really left. Use `BoxWithConstraints` around the whole Column, or `WindowInsets.ime.getBottom()`. Example: `max = (availableHeight - 160.dp).coerceAtLeast(120.dp)`. Also hide the KeyBar while the expanded editor is focused (`onFocusChanged`); its keys act on the terminal, not on the field, which is confusing anyway.

## 6. Tapping the terminal always pops the keyboard. Effort S
`TerminalCanvasView.kt:519-531`: `tapAt` sends a mouse click and then calls `showKeyboard()`. Tapping a herdr tab or button, or just tapping to read, opens a 300dp IME. That triggers the #4 resize cascade, so the layout jumps on every tap.
**Fix:**
- When `isMouseTrackingActive`, don't show the IME. Show it only from ⌨ or when tapping the cursor row.
- Or make it a setting ("Tap opens keyboard").

## 7. The most-used actions sit in the top reach dead zone. Effort M
On a 6.5" phone, the top 46dp strip is the hardest place for a one-handed thumb to reach. It holds:
- the **agents chip** (the "needs you" entry point)
- **+**, plus **⋮**, which holds Copy screen, Select, Paste and font size
- the **tab switcher**

The agents chip is also too small. It has a 13sp label with 7dp vertical padding, about 32dp tall (`AgentUi.kt:53-57`, no minimum size).
**Fix:**
- Add a horizontal swipe on the key bar to switch tabs: `detectHorizontalDragGestures` on the KeyBar Column, using the swipe-up axis that is already free.
- Put a small "N needs you" pill at the left end of the QuickReplyBar or PromptBox row that opens the AgentsSheet.
- Put Select/Copy screen in a long-press on PASTE.
- Keep the top strip for rare actions only.
- Set `minimumInteractiveComponentSize()` on the chip.

## 8. Key bar touch targets are 40dp tall with dead gaps. Effort S
`KeyBar.kt:122,154`: the row is 44dp, and `padding(2.dp)` is applied before `pointerInput`. The hit box is therefore about 47.5x40dp, with 4dp dead strips between keys; taps in a gap do nothing.
**Fix:** move `pointerInput` before `.padding(2.dp)` so the whole 51.5x44 cell is live, and draw the inset visually only.

Also, `^C` sits between the auto-repeating `↑` and `⏎` (`KeyBar.kt:66-68`), so a slip interrupts the agent. Put ^C at the row end or behind swipe-up.

## 9. A pinch storms the renderer and the server. Effort S
`TerminalCanvasView.kt:443-445` and `101-107`: every `onScale` event builds a new `TerminalRenderer` and calls `updateSize()` and `t.resize()`. That sends `changeWindowDimensions` on every frame, so herdr/Claude reflow 30-60 times per pinch.
**Fix:** during the pinch, `canvas.scale()` the old bitmap or renderer. Build the renderer and resize once in `onScaleEnd`.

## 10. The 350 ms polling loop runs forever, on the main thread, even in the background. Effort S
`TerminalScreen.kt:113-130`: `LaunchedEffect` is not lifecycle-aware, so it keeps running after `onStop` while the service keeps the process alive. Each tick runs `focusedPaneLines()` and regex `QuickReplies.find` on the UI thread. That is about 10k wakeups an hour, a battery cost.
**Fix:**
- Wrap the loop in `repeatOnLifecycle(STARTED)` (`LocalLifecycleOwner`).
- Better: trigger the scan from `onScreenUpdated()`, debounced to 200 ms, and drop the polling.
- Run the regex scan on `Dispatchers.Default` with a row snapshot.

## 11. Recomposition scope is the whole screen. Effort S
`TerminalScreen.kt:132-151`: `ui.choices`, `ui.selecting`, `ui.atBottom` and `ui.showPrompt` are all read in the top-level `TerminalScreen`. Any change recomposes TabStrip, which collects three flows per tab chip, plus KeyBar (16 keys) and PromptBox.
The `KeyButton`s each read `mods.ctrl`/`mods.alt` (`KeyBar.kt:133-138`), so they recompose on every modifier tap.
**Fix:**
- Move each read into its own small composable, e.g. `ChipsSlot(ui)` and `FabSlot(ui)`.
- Pass lambdas (`{ ui.choices }`).
- Hoist `modState` into a `derivedStateOf` per key.
- Check with Layout Inspector recomposition counts.

## 12. TalkBack: the terminal and key bar are invisible. Effort M-L
- **Terminal view:** no `contentDescription` and no `AccessibilityNodeProvider`. `@SuppressLint("ClickableViewAccessibility")` and no `performClick()` (`TerminalCanvasView.kt:503-516`).
- **Keys:** they are `Box` + raw `pointerInput` with no semantics, so TalkBack cannot focus or activate them. ⏎, ✎ and ⌨ would be read as symbols.

**Fix:**
- Give keys `Modifier.semantics { role = Role.Button; contentDescription = "Escape, swipe up: tilde"; onClick { … } }`, plus `customActions` for the swipe-up key.
- On the canvas view: `contentDescription = "Terminal"`. Make it a live region announcing new lines on idle (`announceForAccessibility` of the last changed line, throttled), and expose `visibleText()` through `AccessibilityNodeInfo.text`.
- Tab chips need `stateDescription` for status. The dot color alone means nothing.

## 13. Status is conveyed by color only. Effort S
Tab status dots are 8dp red/green/amber with no shape (`TerminalScreen.kt:244-260`), and the agent-sheet dots are 10dp. Red/green is the most common color-blind confusion.
**Fix:** use different glyphs, e.g. ● connected, ◌ connecting (spinning), ✕/outlined ring disconnected, ⏸ ended. Also add the `stateDescription` from #12.

## 14. Contrast (WCAG, computed)
Text pairs mostly pass:

| Pair | Ratio |
|---|---|
| Text E6E9EF on Bg | 15.6 |
| Key label on Surface2 | 12.8 |
| Muted 7F8AA3 on Surface1 | 5.0 |
| Placeholder on Bg | 5.5 |
| Accent on Surface1 | 6.9 |
| Bg on Accent (send, "1 Yes") | 7.5 |
| Esc red on Surface2 | 5.9 |
| "needs you" on 22% red | 10.0 |
| Bolt amber | 8.7 |
| Mod-ONCE text on 3D59A1 | 5.5 |

Failures:
- **Key sublabels:** Muted on Surface2 = **4.48** at **8sp** (`KeyBar.kt:158`). Fails AA 4.5, and 8sp is below any legibility floor. Fix: 10sp and `#9AA3B8` (about 6:1).
- **Message field unfocused border:** `2E3440` on Bg = **1.51**, on Surface1 = **1.40**. Fails the 3:1 non-text rule, so the field outline is nearly invisible. Fix: use `outline` `#414868` or lighter, about `#565F89`, which gives about 3:1.
- **Key fill vs bar:** 1F2430 on 161A22 = **1.12**. Key shapes are barely distinguishable. Fix: raise the key fill to about `#272D3B`, or rely on the full-cell hit area from #8.

Effort S.

## 15. Font scaling clips text. Effort S-M
These heights are fixed while the text is in `sp`:
- tab strip `height(46.dp)` with 14sp (`TerminalScreen.kt:185`)
- tab chip `height(36.dp)`
- key rows `height(44.dp)` with 15sp, a 9dp top offset and `maxLines = 1`
- chips `height(38.dp)`

At 1.3x-2.0x font scale, labels clip or overlap the sublabel. The terminal itself uses deprecated `scaledDensity` (`TerminalCanvasView.kt:92`), so it reflows to fewer columns at large system font (46 cols becomes about 35).
**Fix:**
- Use `heightIn(min=…)` instead of `height`.
- Size key labels with `fontSize = 15.sp.nonScaled` (divide by `LocalDensity.current.fontScale`), or clamp the scale at 1.3x for the chrome.
- Base the terminal on its own in-app size, using `density` and not `scaledDensity`.

## 16. Selection handles conflict with the back gesture. Effort S
Handles are 11dp-radius circles (`TerminalCanvasView.kt:79`). At column 0 or the last column they sit inside the 24dp system-gesture edge, so dragging the start handle left triggers Back. Back also clears the selection (`MainActivity.kt:121`).
**Fix:** while `selecting`, call `setSystemGestureExclusionRects(listOf(handleRects))` (API 29+). The handle hit "reach" of 2.6×11 = about 29dp is fine.

## 17. Haptics are weak and inconsistent. Effort S
- Every key down uses `HapticFeedbackType.TextHandleMove` (`KeyBar.kt:158`), which is barely perceptible on Samsung.
- Swipe-up uses `LongPress`.
- Chips, Send and tab close give no haptic.

**Fix:**
- Key bar: `view.performHapticFeedback(KEYBOARD_TAP)`, and `KEYBOARD_RELEASE` on swipe commit.
- Send/approve: `CONFIRM`.
- Destructive actions: `REJECT`.
- Respect the system "Touch feedback" setting by using the View API without `FLAG_IGNORE_GLOBAL_SETTING`.

## 18. Edge-to-edge and landscape. Effort M
- `enableEdgeToEdge()` + `systemBarsPadding().imePadding()` on the root is correct. The nav-bar strip is painted Bg instead of Surface1, so the prompt row "floats". Fix: let the prompt row's background draw under the nav bar, with `windowInsetsPadding` on the content only.
- `configChanges` covers orientation and size. Fine for no recreation, but there is no landscape layout. In landscape (about 412dp tall), chrome takes 46 + 94 + 56 = 196dp. With the IME, the terminal falls to 0. Fix: with `currentWindowAdaptiveInfo()` at compact height:
  - collapse the key bar to 1 row
  - merge the tab strip into the key-bar row
  - hide the prompt box unless it is focused
- On foldables at expanded width, consider an agents list pane (`ListDetailPaneScaffold`).

## 19. Perceived speed. Effort S
On connect, the only feedback is a 2dp `LinearProgressIndicator` at the top (`TerminalScreen.kt:321`), outside the thumb's line of sight. The disconnected banner covers the top two terminal rows. In the screenshot it shows no Reconnect action, only Close; the Close button is muted-gray and 1 thumb-hop away from nothing else.
**Fix:** show "Connecting to DevBox…" with host and elapsed seconds, centered in the empty terminal. Make "Reconnect now" a primary `FilledTonalButton`.

## 20. Minor sizes. Effort S
- PromptBox hide ⌄ and ⚡ are **36x48dp** (`PromptBox.kt:101,105`), under 48dp wide. Use 48dp or merge them into one overflow.
- Tab-chip close icon is 16dp inside 34dp.
- The jump-to-bottom FAB (40dp) sits at BottomEnd, right over the cursor area. Fine, but give it `minimumInteractiveComponentSize`.
