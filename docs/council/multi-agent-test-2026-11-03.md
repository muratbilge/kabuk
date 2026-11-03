# Multi-agent test of the orch backend — Kabuk 0.5.1 (23), 2026-11-03

Three fake agents (bash scripts named `claude`, so orchd detects them as Claude panes; screens mimic Claude
Code's idle / working / permission-dialog footers) in their own orch panes and workspaces (alpha, beta, gamma),
plus bus questions created with `orch ask --from`. Murat drove the phone (CPH2449); results were verified from
the fake agents' key logs, `orch questions --all`, `orch tail` and Kabuk's live log. No LLM usage.

| # | Scenario | Result |
|---|----------|--------|
| 1 | Two agents waiting at once (alpha + beta dialogs); answer one from the notification, one from the inbox | ✔ each got exactly one key; a double tap on alpha was deduped (`already sent from this phone`) |
| 2 | The *identical* dialog again on alpha, answered from the notification | ✔ `sent` (0.5.0 refused this; fixed in 0.5.1 by `AnswerLedger`) |
| 3 | Another client (DevBox CLI, `orch send --expect`) answers gamma; the phone's notification | ✔ withdrawn as soon as the agent left "blocked"; no second key |
| 4 | Three bus questions from three agents: notification button, Reply… free text, one taken by the CLI first | ✔ #2 by kabuk (button), #3 by kabuk (free text), #4 by devbox-cli; nothing sent twice |
| 5 | Phone answers first, CLI tries the same screen 1 s later (fake agent holds the dialog 120 s) | ✔ orchd: `answered: this screen was already answered`; one key only |
| 5a | CLI answers first, phone taps the unchanged screen **31 s** later | ✘ second key delivered: orchd's answered-once memory lasts 30 s (`ANSWERED_FOR`, orchd/src/server.rs:23). Design choice in orchtool (a screen unchanged for 30 s after an answer may mean the key never arrived); real agents redraw within ms, so the window is academic — noted for orchtool |
| 6 | working → idle transition ("finished" notification) | not confirmed by the user (quiet notification) |
| 7 | Message… → all from the agents sheet | ✔ `murat → all` on the bus; but the entry point was hard to find (see below) |

## Findings for Kabuk (0.5.2)

1. **"Not sent: the question changed" is shown when the phone itself already answered that dialog.** With an
   agent that is slow to redraw, the inbox keeps offering the buttons and every further tap shows the misleading
   toast (12 such taps logged at 09:55:43–09:56:00). `answerAgent` should report "already answered from this
   phone" as its own outcome, and the inbox row should show it.
2. **Message… is at the bottom of the agents sheet** (under "Comms", below every agent group): with 8 agents it is
   off-screen and the user did not find it. Put a Message… entry next to "New agent" at the top (keep the timeline
   where it is).
3. **Unregistered agents are offered as message recipients.** The dialog lists every agent's `name` from the
   watch line; pane agents without a bus registration (e.g. a Codex pane without hooks, or these fakes) make
   `orch msg` fail with `no agent named …`. Offer only `registered: true` agents plus `all`.

## For orchtool

- `ANSWERED_FOR = 30 s`: consider a longer window, or keying the memory on (pane, hash) with the pane's input
  state, so a screen that really did not change cannot be answered twice. Low priority (real agents redraw).

## Harness

`scratchpad/magents/mt.sh` (start | dialog | slowdialog | work | ask | keys | status | stop) and the fake `claude`
script, from this session; worth keeping under `scripts/fake-agents/` if the test is to be repeated.
