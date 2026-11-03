# Purity lens: one source of truth? (2026-11-03)

## 1. Verdict

orchd owns the right things (panes, screen classification, bus, atomic answer-once) but exports a flattened view, so Kabuk rebuilds the one piece orchd keeps private: the **dialog**. The phone re-parses screen text with a herdr-era regex parser, re-reads the pane before every answer, keeps its own ledger and liveness rule, and still ships a second backend (herdr) that orchtool already bridges. The TUI and Telegram cannot answer a screen dialog at all: the model sits in the wrong program. Bus questions, messages, start-agent and history are pure. One orchd addition (`dialog` + `expect_dialog`) and one deletion (herdr) fix most of it.

## 2. Responsibility table

| Responsibility | Truth today | Kabuk duplicates? | TUI / Telegram | Change |
|---|---|---|---|---|
| Agent list, identity | bus + panes, **joined in `orch watch`** (watch.rs:194-292) | parse only; `Agent.name`=kind, `.bus`=name (Orch.kt:44-50) | TUI joins again (tui.rs:1991-1995) | join in orchd (`agent.list` row with pane-alive, workspace, registered) |
| Status | bus + screen detect (pane.rs:322-328, detect.rs:59-81), 500 ms detector (ui.rs:321-343); "screen wins" rule in watch.rs:230-235 | no | pane status only | rule into orchd |
| Dialog detection | detect.rs:39-56, 14-line tail of **visible** text → `blocked` only | re-detects on **recent-60** text (Orch.kt:55-56) | TUI: "waits for a decision" (tui.rs:1575-1583); TG: nothing (telegram.rs:6) | orchd exports `dialog` |
| Options (menu, labels, prompt) | none | QuickReplies.scan (QuickReplies.kt:59-96), herdr sidebar regexes (17-25) | none | detect.rs |
| Answer-once | (pane, screen hash), 30 s (server.rs:23, 201-209) | AnswerLedger (pane, fingerprint) + prune (AnswerLedger.kt:30-49) | cannot answer | orchd keys on dialog id, no window; phone keeps a double-tap guard |
| On screen / focus | global `raised` (ui.rs:246-256) | `focused` from watch.rs:206-208; `orch focus` = Raise (Orch.kt:159) | TUI local `ws_id`; its Enter uses `Focus` (tui.rs:1630) | per-client view, targeted raise |
| Bus questions | ask.*, first wins (api.rs:96-98) | `answer --literal`, ledger by id | TUI tui.rs:1744-1766; TG telegram.rs:346, 405 | pure |
| Messages, timeline | msg.* | `orch msg`, tail 40 | TUI comms; TG forwards | pure |
| Start agent | spawn + placement (server.rs:159-163) | `orch new` + `orch focus` (Orch.kt:147-155) | UiOp spawn | pure, bar the global focus |
| History | scrollback | `orch read --lines 4000` (Orch.kt:163-166) | attach | pure (herdr panes bridged, watch.rs:325-329) |
| Notifications | TG: questions + messages (telegram.rs:246-300) | AgentNotifier: dialogs, questions, finished | — | a bus question alerts twice |
| Liveness | bus: none (bus.rs only bumps `last_seen`) | watch.rs: pane-gone dropped (238), pane-less > 6 h (24, 252) | TUI, `orch agents`: none | orchd |

Live, 16:13: `orch agents` lists 9 (`instaposter-claude` "working", no pane, seen 06:06; four names whose orch panes are gone); `orch watch --once` gave the phone 3. Three clients, three answers to "who exists".

## 3. Findings

**F1. The dialog model lives only on the phone.** orchd knows a pane is blocked, exports no choices; Kabuk re-derives them from a different input (recent-60 vs visible tail). Claude's trust dialog (`❯ No, exit / Yes, I trust`) and `(y/n)` prompts are `Blocked` (detect.rs:45, 53) but have no numbered menu, so `Pending` is null and the notification degrades to "Waiting for your answer" without buttons (AgentNotifier.kt:62-63). TUI only jumps to the pane (tui.rs:1626-1632); Telegram never sees dialogs. Cost: every Claude/Codex UI change is chased in Kotlin regexes; two of three clients have no one-tap approval.

**F2. Redundant re-read.** The watch line carries exactly what `orch read --lines 60` returns (watch.rs:158-160 = server.rs:182-184) minus the hash, so `answerScreen` spends a second SSH exec on `read --hash` (Orch.kt:122-127) and a third on `send`. Kabuk could hash it itself (FNV-1a, api.rs:364-371). The re-read's only value is tolerating spinner ticks via the fingerprint, and that still fails between re-read and send (review 0.5 #3; multi-agent finding 1). Cost: two extra channel opens per answer over mobile, spurious "question changed".

**F3. herdr backend is dead weight.** orchtool bridges herdr panes itself (watch.rs:143-147, 241-249, 325-341; two live today), so the phone already sees them via `orch watch`. Kabuk's own path adds a non-atomic read→send (Herdr.kt:124-132, review #2), a Python watcher, and "Auto = herdr first" as the **default** for new hosts (Hosts.kt:23, 49; HostsScreen.kt:251). Residue in the orch path: `Herdr.StreamEnd` (Orch.kt:74-110), `Agent` in Herdr.kt with `terminalId`, thread `"herdr-$id"` (SshTab.kt:277), `herdrQ` (TerminalScreen.kt:149), comments in SshTab.kt:62-69, 342-368, AgentUi.kt:53, 86, 166, 301, AgentNotifier.kt:17, SettingsScreen.kt:97, SessionService.kt:79, README 21-31, 70-77, 110.

**F4. Two "needs you" sources, and `focused` is wrong by construction.** TerminalScreen.kt:149-162 takes the stream's dialog for the agent flagged `focused`, else scrapes Kabuk's own emulator. `focused` derives from the global `raised`, which the phone's TUI does not update when Murat switches workspaces by key. After Kabuk raised A and the TUI moved to B, chips for A appear and `ui.answer` writes the key into the PTY (TerminalScreen.kt:97-100), i.e. into B. Not reproduced; nothing prevents it.

**F5. `orch focus` is global** (api.rs:195-198, ui.rs:254-255, tui.rs:815-823): tapping an agent on the phone switches the laptop TUI.

**F6. Liveness in the wrong layer**: "working" bus agents without a pane stay on the phone up to 6 h, in the TUI forever.

**F7.** Bus questions alert twice (Telegram and Kabuk). Policy.

## 4. Options

**A. orchd exports the dialog (F1, F2, half of F4).** `PaneInfo.dialog` from detect.rs on the visible text:
```json
{"id":"<fnv of prompt+choices>","prompt":"Bash command\n./gradlew test\nDo you want to proceed?",
 "choices":[{"key":"1","label":"Yes","selected":true},{"key":"3","label":"No, and tell Claude…","selected":false}],
 "kind":"menu"}
```
`pane.send` gets `expect_dialog`, checked under the terminal lock like `expect` (server.rs:193-209); answered-set keyed on `(pane, dialog.id)` until the dialog changes (the 500 ms detector sees that). `orch watch` sends `agents[].dialog` instead of `screens{}`; `orch send <pane> <key> --expect-dialog <id>`. Rust ~150 (port QuickReplies.scan + its 12 fixtures), Kotlin −80, TUI +60 for choices in needs-you, Telegram +40 optional (buttons only). Risk: parser parity, covered by the ported fixtures.

**B. Interim:** `hash` next to each `screens` text (watch.rs:159, 3 lines), drop the re-read. Still spinner-brittle; only if A slips.

**C. Retire herdr in Kabuk.** Delete `agents/Herdr.kt` (move `Agent` to Orch.kt; `name→kind`, `bus→name`, drop `terminalId`), `assets/herdr_watch.py`, SshTab.kt herdr branches (344-370, 436, 461, 475, 487-488), Hosts.kt `herdr` string and ORCH mode (Auto = orch → tmux → shell; `fromJson` maps `"orch"`→AUTO), HostsScreen copy, HerdrParseTest, HerdrLiveTest (its 3 QuickReplies cases move to QuickRepliesTest), RemoteCommandTest 62-85, `herdr-agents/workspaces.json`. Keep: tmux command and capture (Hosts.kt:35, SshTab.kt:489-491), emulator scrape + `ui.answer` for daemon-less hosts (TerminalScreen.kt:150-162, 97-109), `QuickReplies.find` with border handling (the orch TUI draws borders too), `herdr-claude-menu-*.txt` as scrape fixtures. No Settings keys (grep empty). ~450 lines out, ~40 in. Risk: a herdr-only host, which no longer exists.

**D. Per-client view + targeted raise (F4, F5).** Kabuk starts `orch ui --client kabuk-<tab>`; TUI reports its focused pane (`ui.op {op:"client_view",client,pane}`); `Raised{seq,workspace,pane,target:Option<String>}`, TUIs ignore other targets; watch adds `agents[].on_screen:["kabuk-…"]`. Rust ~60, Kotlin ~20. Stop-gap first: on orch hosts chips answer via `orch send --expect-dialog` on the stream's pane, never via the PTY (Kotlin ~10).

**E. Liveness in orchd (F6):** prune registrations whose pane exited, pane-less after N h; `AgentRow.live`. Rust ~30.

## 5. Recommendation

1. **C** now: Kabuk-only, pure deletion, releasable alone; closes review #2 and #6.
2. **A** next, both repos together (Kabuk accepts `dialog`, keeps `screens` for one release).
3. **D stop-gap** with A, then **D** and **E** on the next orchd pass. Skip B unless A slips. F7: one line of policy.

## 6. Not verified

Nothing built or run. F4's wrong-pane keypress was reasoned from code, not reproduced. `orch send --expect` on a `herdr:` pane looks client-side and non-atomic (main.rs:275), not exercised. Dialog buttons in Telegram hinge on Murat's "terminal contents are never sent" rule (telegram.rs:6).
