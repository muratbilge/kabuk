# Kabuk ↔ orchtool: how native, how pure? — council review (2026-11-03)

Kabuk 0.5.2 (24) against orchtool 0.1.0, read by three reviewers with one lens each: transport/native-ness
(`orch-transport.md`), responsibility split/purity (`orch-purity.md`), contract & operations (`orch-contract.md`).
Chair: Claude. Nothing was built or run beyond read-only `orch` commands; the chair re-verified the claims the
plan rests on (marked ✔ below). Line counts are estimates.

## Verdict

Kabuk is **native at the data model and shell-mediated at the transport**. `orch watch --json` is a JSON contract
written for the phone and is the right shape (server-side merge, herdr liveness, dialog screens, "orchd down"
line); the three reviewers agree it should stay. Everything else goes through `sh` + `orch` + an exit status, and
the terminal is the ratatui TUI piped through the phone's PTY.

The **impurity is one object: the dialog.** orchd knows a pane is blocked (`detect.rs`) but exports no choices, so
the phone rebuilds the menu from raw screen text with a herdr-era regex parser (`QuickReplies`), re-reads the pane
before every answer, keeps its own answer ledger, and is the only client that can approve a dialog remotely: the
TUI can only jump to the pane, Telegram never sees dialogs. ✔ orchd classifies the *visible* screen while the
watch line ships the *recent 60 lines*, so the two sides even judge from different inputs.

The **contract is unwritten and skew-blind.** `"v":1` is sent and never read; every `orch` error is exit 1 ✔, so a
renamed flag would show Murat "already answered" / "the question changed" on every tap. One malformed watch line
would ship dialog text to the logsink through the exception stack ✔ (Android's `org.json` puts the whole input in
the message). The herdr backend in Kabuk is dead weight: orchtool already bridges herdr panes, and "Auto" still
prefers herdr over orch for new servers.

## Findings (merged, ranked)

| # | Sev | Finding | Evidence | From |
|---|-----|---------|----------|------|
| 1 | high | The dialog model lives only on the phone: options parsed from raw text; TUI/Telegram cannot answer screen dialogs; trust dialogs and `(y/n)` are `blocked` without a menu → notification without buttons | `detect.rs:39-81`, `Orch.kt:55-56`, `QuickReplies.kt:59-96`, `tui.rs:1626-1632`, `telegram.rs:6`, `AgentNotifier.kt:62-63` | purity |
| 2 | high | All `orch` errors exit 1; orchd's `changed`/`answered`/`exited`/`input_full` and `won:false` never reach the phone; a CLI flag rename (clap exit 2) reads as a semantic outcome | `main.rs:216-219` ✔, `SshTab.kt:404-412`, `Orch.kt:134` | contract, transport |
| 3 | high | No version check on the stream; a retyped field → parse error on every line → agents cleared, "No agents found", log flood, no "update Kabuk" hint | `watch.rs:169`, `Orch.kt:36-71, 101-103` | contract |
| 4 | medium | Secrecy: a malformed watch line is logged with its throwable; the stack carries the input line (screens, questions) to the logsink | `Orch.kt:102` ✔, `KLog.kt:90` ✔ | contract |
| 5 | medium | SSH timeout on `send` is reported as "Not sent: the question changed" and the ledger entry is released, although the key may have landed; a retap is then refused by orchd | `SshTab.kt:436-441`, `server.rs:201-209` | contract |
| 6 | medium | `focused` = last `orch focus` (global `raised`), not what the phone's TUI shows: a workspace switch inside the TUI is local ✔; stream-dialog chips can type into the pane on screen, which may be another agent | `watch.rs:202-213`, `ui.rs:246-256` ✔, `tui.rs:733,1138`, `TerminalScreen.kt:149-162, 97-100` | purity, transport |
| 7 | medium | Redundant re-read: the watch line already carries the 60 lines that `orch read --hash` hashes; `answerScreen` spends two extra SSH channels per answer (~6 RTT + 2 process spawns) | `watch.rs:158-160` = `server.rs:182-184`, `Orch.kt:122-127` | purity, transport |
| 8 | medium | herdr backend is dead weight and residue: `Herdr.kt`, `herdr_watch.py`, non-atomic read→send (review 0.5 #2), "Auto = herdr first" default, `Herdr.StreamEnd`/`Update` reused by the orch path, `Agent.name`=kind / `.bus`=name, thread `herdr-$id`, README wording | `Hosts.kt:23,49`, `Herdr.kt:124-132`, `Orch.kt:74-110`, `SshTab.kt:277` | purity |
| 9 | medium | First-answer-wins does not cover `herdr:` panes (`send --expect` re-reads and types client-side); 2 of the 3 agents on the phone today are herdr panes | `main.rs:261-283`, `watch.rs:325-341` | contract, transport |
| 10 | medium | Tests cannot catch a break: hand-copied fixture, no exit-code test, no `hash:` format test, `check.sh` never runs Kabuk — renaming `screens` ships green on both sides | `OrchParseTest.kt`, `watch.rs:396-442` | contract |
| 11 | low | Screen-off traffic: the TUI keeps its panes attached (frames every 40 ms while an agent's spinner runs); backgrounding only pauses the chip scan | `server.rs:19,639-655`, `TerminalScreen.kt:143-144` | transport |
| 12 | low | `orch focus` raises the workspace in every TUI; the phone TUI attaches with `Control` at phone size, a laptop view would be clipped to 46 columns. Moot while there is no laptop binary | `tui.rs:592-631,815-823`, `server.rs:682-690` | all |
| 13 | low | Liveness decided three ways: `orch agents` 9, the phone 3 (`STALE_MS` 6 h, dead panes dropped only in watch), TUI unfiltered; `-2/-3` names from conflict renaming | `watch.rs:238,252`, `bus.rs:161-163` | purity, contract |
| 14 | low | A bus question alerts twice (Telegram and Kabuk) | `telegram.rs:246-300`, `AgentNotifier.kt` | purity |

## Where the reviewers disagreed, and the chair's call

- **Fix the action path with exit codes (contract) or a native request channel (transport)?** Both. The CLI
  path keeps `orch new` (it captures the login shell's environment; bare `pane.new` would get orchd's) and
  `herdr:` panes, so distinct exit codes (~40 Rust lines) are needed anyway and ship first. The native channel
  (one `orch proxy` exec channel, JSON lines, id-matched replies, orchd's own error codes and `won`, ~300 Kotlin
  lines) is the real answer to "native" for actions and comes after the dialog work, not before it: the dialog
  object is what removes the re-read, the phone-side parser and the window problem; the channel only makes the
  remaining calls cheaper and honest.
- **Native terminal (attach streams) now?** No. ~1000 Kotlin lines, a third client of a week-old protocol, and
  it fixes chrome and `focused`, not the pane-size tug-of-war (finding 12). Revisit after a measured week.
- **Retire herdr in Kabuk now?** Yes, if Murat agrees (decision 1): Kabuk-only, pure deletion, closes review
  0.5 #2 and #6, makes "Auto" mean orch → tmux → shell. Kabuk still sees herdr panes through `orch watch`.

## Plan

**Phase 1 — honest contract (Kabuk 0.5.3 + orchtool patch; ~40 Rust, ~100 Kotlin, docs).**
1. orchtool: distinct exit codes (`changed` 10, `answered`/`won:false` 11, `exited` 12, `input_full` 13,
   `no_pane` 14, orchd unreachable 20; clap stays 2); a hello line `{"v":1,"orch":"0.1.0"}` at the top of
   `orch watch`; `notes/kabuk-contract-v2.md` (outline in `orch-contract.md` §4); the live `--once` line frozen as
   a fixture in orchtool, copied into Kabuk's test resources and asserted by both suites.
2. Kabuk: read `v` (newer → one log line + "orch is newer, update Kabuk" in the agents sheet); map exit codes to
   `AnswerOutcome` (`CHANGED`, `TAKEN`, `GONE`, `OFFLINE`, "Kabuk and orch disagree, update"); a timeout becomes
   `UNSURE` and keeps the ledger entry; log the parse failure as class + 60 chars, never the throwable (finding 4);
   stop-gap for finding 6: on orch hosts reply chips answer through `orch send --expect` on the stream's pane,
   never through the PTY; exit-code test with a fake `orch` through real `sh`.
3. Kabuk: retire the herdr backend (decision 1): delete `agents/Herdr.kt` (move `Agent` to `Orch.kt`, rename
   `name→kind`, `bus→name`, drop `terminalId`), `assets/herdr_watch.py`, the herdr branches in `SshTab`, the
   herdr string in `Hosts.remoteCommand`, `HerdrParseTest`, `HerdrLiveTest` (its QuickReplies cases move),
   `RemoteCommandTest` 62-85, the two herdr JSON fixtures; keep tmux attach + capture, the emulator scrape and
   `QuickReplies.find` for daemon-less hosts. ~450 lines out.

**Phase 2 — the dialog becomes orchd's (both repos together; ~150 Rust + 60 TUI, −80 Kotlin).**
orchd parses the menu where it already classifies (`detect.rs`, on the visible text) and exports per pane
`dialog: {id, prompt, choices: [{key, label, selected}], kind}`; `pane.send` gains `expect_dialog` checked under
the terminal lock, answered-once keyed on `(pane, dialog.id)` until the dialog changes (no 30 s window);
`orch send <pane> <key> --expect-dialog <id>`; `orch watch` sends `agents[].dialog` and keeps `screens` for one
release. Kabuk drops the re-read and `QuickReplies.pending` on the orch path (fingerprint = dialog id). The TUI's
needs-you overlay gets one-tap approvals; Telegram gets buttons only if decision 2 allows. Also here: liveness in
orchd (prune registrations whose pane exited, pane-less after N h; `AgentRow.live`, ~30 Rust).

**Phase 3 — native actions (Kabuk; ~150 link + 120 changed + 80 tests).**
`agents/OrchLink.kt`: one persistent `orch proxy` exec channel, JSON lines `{"version":1,"id","body"}`, one
request at a time, reopen on EOF; `pane.send`, `ask.answer` (uses `won`), `msg.send`, `ui.op raise`, `pane.read`
go through it with orchd's error codes; `orch new` and anything `herdr:` stay on the CLI until herdr is gone.
`Frame.version` then reaches the phone for free.

**Cheap wins alongside:** a TUI "sleep" key that detaches its views, sent by Kabuk on background and undone on
foreground (~40 Rust, ~20 Kotlin; finding 11). A controller change that only shrinks, never grows, a pane — or
accept the tug-of-war (one user; finding 12).

**Deferred:** native attach (transport option a); per-client view + targeted raise (purity D) unless finding 6
still bites after the stop-gap; folding `orch watch` into orchd as `watch.subscribe` after native attach.

## Decisions for Murat

1. Retire the herdr backend in Kabuk (phase 1.3)? Kabuk would still show herdr panes via orchtool's bridge.
2. May a dialog's text (the command being approved) leave the DevBox as Telegram buttons? Today's rule is "no
   terminal contents to Telegram"; without it, Telegram keeps answering bus questions only.
3. Native terminal: agree to defer until a week of live-log data on the phone (reconnects, bytes per hour with
   the screen off)?
4. Double alerts for bus questions (Telegram + phone): keep both, or phone only while Kabuk is connected?
5. Who does phase 1: Codex-sized work; Claude reviews the exit-code table and the contract note.

**Answers (Murat, 2026-11-03):** (1) herdr stays for now: it is the way to reach Claude when orchd is broken,
retire it later. (2) No Telegram at all: the Telegram integration is removed from orchtool (decision 4 is
thereby moot). (3) Yes, defer the native terminal. (5) Claude starts phase 1 now (Codex's limit is exhausted).
Phase 1 therefore has no herdr retirement and adds the Telegram removal on the orchtool side.

## Verified / not verified

✔ by the chair in code: only `UiOp::Raise` sets `raised` and the TUI's own switches do not; orchd classifies
`visible_text()` while watch ships `recent_text(60)`; `orch` exits 1 on every error; `Orch.kt:102` logs the
throwable and `KLog` ships 6000 chars of stack. Not verified by anyone: the wrong-pane keypress (finding 6) was
reasoned from code, not reproduced; latency figures are estimates (no `orch latency` run); Android's exact
`JSONException` text on the device; `pane.new` behaviour for a missing cwd. Reports: `orch-transport.md`,
`orch-purity.md`, `orch-contract.md` (reviewer line numbers are from today's trees: Kabuk `f265ac6`, orchtool
`359282c`).
