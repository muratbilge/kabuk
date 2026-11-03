# Transport & protocol — Kabuk ↔ orchd (council, 2026-11-03)

## 1. Verdict
Kabuk is native at the data model (`orch watch` is a JSON contract written for the phone) and shell-mediated at
the transport: actions are `sh` + `orch` + exit status; the terminal is the ratatui TUI piped through the phone's
PTY. Nativeness pays now only for **actions**: one persistent `orch proxy` channel = one round trip per action
instead of ~3 plus two spawns, and typed error codes instead of exit 0/1 (~300 Kotlin lines). **Data** stays
server-side: `orch watch` does merging, herdr liveness, dialog screens and "orchd down" signalling the phone
cannot do natively. A native **terminal** (~1000 lines, a third client of a week-old protocol) buys chrome and
background control, not correctness; the pane-size tug-of-war with the laptop is policy, not transport.

## 2. Findings (ranked)

**F1 — Actions pay channel + process cost per call and lose the error code.** `Orch.kt` 25, 121–135: each action
opens an SSH channel (`SshTab.kt` 581–613), runs `sh`, `command -v`, `orch`; `answerScreen` does it twice. orchd
distinguishes `changed` / `answered` / `exited` / `input_full` (`server.rs` 187–222) but the CLI exits 1 for all
(`main.rs` 218), so Kabuk says "screen changed or answered already" (`Orch.kt` 134). `ask.answer` returns `won` +
the existing answer (`api.rs` 96–98, `server.rs` 300–306); Kabuk sees only the exit status (`Orch.kt` 138).
Cost: ~6 RTT + 2 spawns per answer (est. 250–500 ms on mobile; orchd itself: 0.03–0.13 ms, `orch ping`).

**F2 — Pane size follows whoever typed last; the laptop is affected.** The TUI attaches every visible pane with
`Control` at its own rect (`tui.rs` 592–631), Takeover + Resize when its rect changes (557–567), Takeover on any
keystroke (686–690); orchd resizes the PTY for the new controller (`server.rs` 682–690, `pane.rs` 204–225). Each
phone keyboard open/close (150 ms debounce, `TerminalCanvasView.kt` 157–173) → SIGWINCH → every visible pane
resized. A laptop TUI on that pane becomes observer of a 46-column clipped view (`render.rs` 8–9, 61–63) until
someone types there; after the phone detaches, control is `None` (`pane.rs` 301–311), nothing resizes back.
Native attach would behave identically.

**F3 — `focused` is not what the phone shows.** watch derives it from `session.raised` (`watch.rs` 202–213), set
only by `UiOp::Raise` (orchd `ui.rs` 246–255); workspace switches inside the TUI are local `ws_id` (`tui.rs` 733,
1138, 1608, 2372–2377). After one, the agents list and the exact reply-chip path (`TerminalScreen.kt` 149–151)
point at the wrong pane; the 350 ms scrape fallback hides it.

**F4 — Screen-off traffic.** The foreground service keeps SSH; orchd sends a frame every 40 ms while a visible pane
changes (`server.rs` 19, 639–655) — a working agent's spinner never stops. `TabManager.foreground` only pauses
the chip scan (`TerminalScreen.kt` 143–144); the TUI has no idle-detach.

**F5 — Chrome on 46 columns.** No sidebar under 60 cols (`tui.rs` 2038–2040); status bar + a title row per pane
(508–510) = 2 of ~20 rows; pane switching and scrollback are TUI keystrokes (895–909). Native gives Compose tabs
and a full-height pane, but Termux's scrollback stays empty (rows rewritten in place, `render.rs` 67–76):
history still needs `pane.read`.

**F6 — The data layer is already the right shape.** `orch watch` coalesces 150 ms, refreshes 15 s, heartbeats
60 s, reads blocked panes' screens (`watch.rs` 125–161) and prints `{"error"}` when orchd is down (50; shown,
`SshTab.kt` 381). A native `events.subscribe` client gets `Event::Panes` within 500 ms of a status change (`ui.rs`
325–340) but no dialog text (`api.rs` 204–207) → own `pane.read` per blocked pane, own merge (`watch.rs`
194–292), no herdr liveness (needs the herdr binary, 332–341; two live agents are in herdr panes now), and
"orchd down" = a dead proxy. Gain ≈ 150 ms.

**F7 — herdr panes bypass orchd:** `orch read/send herdr:…` run the herdr binary CLI-side (`main.rs` 548–590).

## 3. Options

**(c) Native request channel — recommended.** `agents/OrchLink.kt`: one exec `orch proxy` (no mux: that is for
parallel streams), JSON-lines `{"version":1,"id","body"}` (`lib.rs` 16–22), id-matched replies, typed
`OrchError(code)`, one request at a time (`server.rs` 389–396 "busy"), reopen on EOF. `Orch.kt` → `pane.read` /
`pane.send{expect, expect_lines:60}` / `ask.answer` / `msg.send` / `ui.op raise`. Keep exec for `herdr:` panes
(F7) and `orch new` (the CLI captures the shell env; bare `pane.new` gets orchd's — PATH/LANG risk). Surface
codes in `AnswerOutcome`. ~150 lines link, ~120 changed, ~80 tests. Risk: low.

**(a) Native attach — later, if ever.** `OrchPaneTab : TermTab`: `Stream::Screen.data` → `TerminalEmulator.append`,
`base != rev` → `resnapshot` (`attach.rs` 98–106), `Role`, `Exit`; base64 `input`, `resize`, Takeover-on-type;
mux client (9-byte frames, `mux.rs` 6–7, 26–54) or one proxy channel per pane; workspace model from `ui.get` +
`Event::Session`; Compose pane strip; history via `pane.read`. ~900–1200 lines, 3–5 sessions. Risks: terminal
fidelity (renderer modes/cursor style; `attach.rs` 43 lists what panes leave behind), resize storms unchanged,
background policy, protocol churn. Gains F3–F5; does not fix F2.

**(b) Native events — no** (F6).

**Rust alternatives (cheap, same owner).** F4: a TUI key that detaches all views, reattaching on the next key (~40
Rust, ~20 Kotlin to send it on background/foreground). F2: a controller change only shrinks, never grows, the
pane (~30 lines) — or accept it: one user, typing side wins. After (a) only: watch's snapshot inside orchd as
`watch.subscribe` → `{"stream":"watch", …today's body…}`.

## 4. Recommendation + sequencing
1. (c) now — removes a real per-action cost and gives honest outcomes.
2. Cheap: chips prefer Kabuk's own scrape over `focused` (F3, ~5 lines); TUI sleep key (F4).
3. Leave (b) as is.
4. (a) only after a measured week on the phone (livelog: reconnects, bytes/hour screen-off); if F4/F5 still
   hurt, do (a) over mux, then fold watch into orchd.

## 5. Not verified
No latency measured (`orch latency` needs a new echo pane; read-only brief) — RTT figures are estimates.
`pane.new` error for a missing cwd. Termux's emulator fed with vt100 `rows_formatted` output. orchd's env vs the
SSH shell's.
