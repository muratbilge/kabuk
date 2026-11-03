# Council — Kabuk ↔ orchtool as a versioned contract (2026-11-03)

## 1. Verdict

A de-facto contract: one JSON line shape plus five `sh` one-liners, written down nowhere current
(`notes/kabuk-contract.md` is herdr-era). `"v":1` is emitted (watch.rs:169) and never read (Orch.kt:36–71);
`Frame.version` (orch-proto lib.rs:12, 66–69) only guards `orch`↔`orchd` on the DevBox — the phone never sees it.
The data path tolerates skew by accident (`opt*` defaults); the action path does not: every `orch` failure is
exit 1 (main.rs:216–219), clap errors exit 2, so a renamed flag reads to Murat as "already answered" or "the
question changed". Timing windows compose without double keys for real agents, but an SSH timeout on `send` shows
"Not sent" even when the key landed. Fix: hello line + distinct exit codes in orchtool, `v` check + outcome
mapping in Kabuk, one shared fixture. One small release each side.

## 2. Findings (ranked)

1. **high — skew shows up as a wrong semantic outcome.** `orch answer`/`send` collapse every error to exit 1
   (main.rs:216–219); the `code: message` goes to stderr (client.rs:43), which `SshTab.exec` drops (SshTab.kt:586–593).
   Kabuk maps non-zero to `TAKEN` "Not sent: already answered" (SshTab.kt:404–412) or `CHANGED` "refused by orchd
   (exit $sent: …)" (Orch.kt:134). clap 4.6.7 exits 2 on an unknown `--literal`/`--expect`: after a rename every
   tap says that, and Murat stops answering a live agent.
2. **high — no version check on the stream.** A retyped field (`id` → string) throws in `parseUpdate`
   (Orch.kt:61, 67) → `bad orch watch line` + agents cleared on every line (Orch.kt:101–103, SshTab.kt:383) → "No
   agents found" (AgentUi.kt:166), a log flood, no "update Kabuk" hint.
3. **medium — SSH timeout ≠ changed.** `exec` throws after 8 s (SshTab.kt:600–604) → `ledger.release` + `CHANGED`
   (SshTab.kt:436–441). The key may have landed; a retap is refused `answered` (server.rs:201–209) → "the question
   changed" again. Needs an `UNSURE` outcome that keeps the ledger entry.
4. **medium — first-answer-wins skips herdr panes.** `send herdr:… --expect` re-reads then types, non-atomic,
   no `answered` record (main.rs:261–283). Live now: 2 of the 3 agents the phone shows are `herdr:`.
5. **medium — secrecy on the skew path (verify on device).** Orch.kt:102 logs the `JSONException` as throwable;
   KLog.kt:90 ships `stackTraceToString()` (6000 chars). Android's `org.json` `JSONTokener.syntaxError` appends
   " at character N of <whole input>": a truncated watch line would ship `screens` (dialog text), questions and
   timeline to the logsink. Tests use `org.json:json` (build.gradle.kts:90), which doesn't. Otherwise clean: answers
   log pane + key + reason only (SshTab.kt:407, 410, 430, 438; Orch.kt:123–134); `sendBus` and chip taps log nothing.
6. **medium — tests can't catch a break.** Kabuk's fixture is a hand-copied line (e2b2b6a; keys still match live
   `--once`, checked); `AnswerLedgerTest` is phone-only; `RemoteCommandTest` covers the login command, not
   `FIND`/exit 3/4 or the `hash:` parse (Orch.kt:124). orchtool: `merge` test builds rows with `json!`
   (watch.rs:396–442); panes.rs:261 covers changed/answered; nothing checks CLI exit codes or the `read --hash`
   format; `check.sh` never runs Kabuk. Renaming `screens` ships green on both sides.
7. **low — windows.** orchd: 30 s per pane by hash (server.rs:23, 201–209). Phone: ledger pruned whenever the
   dialog is absent from `screens` (SshTab.kt:450–454, AnswerLedger.kt:44–49) — also on a stream gap (SshTab.kt:393)
   and a blocked→working flicker (watch.rs:152). After a gap a re-send is refused within 30 s (shown as CHANGED,
   see 3), accepted after (test 5a). Heartbeat 60 / watchdog 150 / keepalive 5 s×4 (SshTab.kt:260–261) / grace 45 s
   (AgentNotifier.kt:31) / snapshot 10 s compose; a reconnect > 45 s re-posts an open question, not a phantom.
8. **low — hygiene.** `orch agents` lists 9, the phone 3: watch.rs:238 drops dead orch panes, :252 pane-less
   after 6 h; TUI/CLI filter nothing, bus.rs never deletes, `-2/-3` = conflict renaming (bus.rs:161–163).
9. **low — cross-client.** `focus` = `Raise` (ui.rs:246–256) switches every TUI (tui.rs:815–818); the phone TUI
   attaches `Control` (tui.rs:626), controller size wins (server.rs:613–614, 686–689). Moot: no laptop binary.
10. **low — install skew.** install.sh:9–15 swaps binaries under a running orchd → new `orch` vs old `orchd` →
    `bad_request` (server.rs:356–366) → finding 1. Not the case now.

## 3. Options

- **A. orchtool, ~40 Rust lines.** Hello line `{"v":1,"orch":"0.1.0"}`. main.rs maps `ClientError::Remote.code`:
  10 `changed`, 11 `answered`/`won:false`, 12 `exited`, 13 `input_full`, 14 `no_pane`/`not_placed`, 20 orchd
  unreachable; clap keeps 2. Compatible with 0.5.2 (any non-zero = failure).
- **B. Kabuk 0.5.3, ~80 Kotlin lines.** `v` check (>1 → one log line + note "orch is newer — update Kabuk");
  codes → `CHANGED`/`TAKEN`/`GONE`/`OFFLINE`, 2 → "Kabuk and orch disagree — update", timeout → `UNSURE`;
  Orch.kt:102 logs class + 60 chars, no throwable.
- **C. Shared fixture, ~30 lines + script.** `crates/orch/tests/fixtures/watch-v1.json`, test asserts `snapshot()`
  keys ⊇ fixture; script copies it to Kabuk's test resources; Kabuk exit-code test with a fake `orch` through real
  `sh` (RemoteCommandTest pattern).
- **D. Optional.** herdr `--expect` refuses (exit 14) or the doc states the gap; `answered` kept until the hash
  changes (~10 lines); observe-mode flag for the phone TUI.

## 4. Recommendation

1. Write `notes/kabuk-contract-v2.md`, freeze today's `--once` line as fixture (C). 2. orchtool release with A.
3. Kabuk 0.5.3 with B + test. 4. D later. Codex-sized; Claude reviews the exit-code table.

**Outline `notes/kabuk-contract-v2.md`:** (1) Transport: SSH exec, `sh -c` + FIND; exit 3 no orch, 4 no folder,
2 CLI mismatch. (2) Stream: hello line; a line = full state, never a delta; fields/types/defaults; `error` line;
heartbeat 60 s, snapshot timeout 10 s, retry 5 s, stale rule. (3) Actions: `read --hash` format, `send --expect
--lines 60`, `answer --by --literal`, `msg`, `new`+`focus`, `focus`; exit-code table, which codes mean "certainly
not sent". (4) Guarantees: atomic check-and-send (orch panes only), answered-once 30 s/pane, first-answer-wins,
timeline 40, screens = 60 lines of blocked agents. (5) Versioning: `v` bumps on any removed/renamed/retyped field
or changed exit code, never on additions; Kabuk accepts unknown fields and `v` ≤ its own. (6) Fixture: lives in
orchtool, regenerated on every change, copied into Kabuk the same day, asserted by both suites. (7) Logging: no
screen/question/message text on either side.

## 5. Not verified

Android `org.json` message text on the device (5); laptop TUI use (9); origin of the `-2/-3` agents. Nothing
built or run beyond `orch ping/agents/watch --once/--help`.
