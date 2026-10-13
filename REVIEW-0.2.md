# Kabuk 0.2: review results

Two Codex reviews (full code: `REVIEW-0.2-codex.md`; the 0.2 changes: second pass, summarised below) plus my own.

## Fixed

| Finding | Fix |
|---|---|
| Key bar could type into the previous tab | newest action via `rememberUpdatedState`; gesture restarts when the tab changes, held key repeat always stops |
| Delayed Enter could hit another tab / a new connection | Enter is bound to the tab *and* its connection generation |
| Password dialog could reuse another server's password | dialog state keyed by the prompt |
| Prompt queue lost requests, prompts outlived their tab | atomic updates; prompts cancelled on close, reconnect and disconnect; refused for closed tabs |
| Reconnect cleanup could kill the new connection | one `Conn` per attempt, cleanup only closes its own; publishing vs. closing under one lock |
| Selection + resize crash | resize clears the selection; bounds clamped safely |
| Unbounded output queue | bounded buffer (2 MB) with a main-thread drain of max 256 KB per frame; old connection's output dropped |
| Send when offline lost the text | draft kept, toast "Not connected" |
| Rejected host key / cancelled login retried forever | stays stopped until you tap Reconnect; network changes only wake automatic retries |
| Helper commands without a deadline | one deadline over open + run + read, output capped, hung commands get TERM and close in the background |
| Resize during connect lost | window size pushed again once the channel is ready |
| Activity leak through the tab callback | callback attached/detached with the view's window |
| IME forward delete, hardware modifiers eaten | forward delete sent as such; sticky modifiers only consumed when used |
| Short commands joined by Copy | joining only happens when lines come near the pane width |
| `|` tables taken for pane borders | ASCII `|` ignored; a border must run unbroken ≥ 4 rows through the row; "Wide" button to drop the limit |
| Copy text took the wrong source silently | herdr pane list parsed as JSON; the source and "last 4000 lines" are shown |
| OSC 52 from background tabs | only the tab on screen may set the clipboard |
| Prompt history in plain text | encrypted with the Keystore; switch to turn it off; Clear button |
| Key install could report success without working | permissions repaired, newline ensured, then a real key login is tried before the password is dropped |
| Stale host saves | field-wise `Hosts.update` against the latest saved record |
| No "agent needs you" notification | herdr agent polling + notifications (new feature) |
| Other apps could make Kabuk connect | shortcuts carry a secret token |
| End session killed without asking | confirmation; the tab stays if it fails |
| 2nd review: SSH thread started before fields were ready | thread starts at the end of the constructor |
| 2nd review: quick replies could answer another pane | only the pane with the cursor, only on the live screen |
| 2nd review: notifications suppressed while in Settings | "watching" = terminal visible; blocked agents are notified once you leave |
| 2nd review: shared picture lost on launch / upload into wrong draft | uses the selected tab directly; path goes into the uploading tab's draft |

## Not changed, on purpose

- **Copy joins wrapped lines by default.** Codex suggested exact copy as the default. The user asked for joined
  text, so it stays the default; "As shown" is one tap away, and short lines are no longer joined.
- **Tap = mouse click in herdr/tmux.** Codex suggested separate read/mouse modes. Links now win over clicks,
  long-press still selects; a mode switch would add a button for little gain. Revisit after real use.
- **Process death restores nothing automatically.** The server list offers "Reopen last session" instead of
  reconnecting by itself (no surprise logins).
- **Mosh**: not added; tmux + auto-reconnect covers the phone use, Mosh would be a large addition.

## Tests

32 tests: unwrap rules, quick-reply detection, herdr JSON (real output), pane selection, mouse taps,
SSH against a throwaway server (key install incl. missing newline, exec timeout, tmux reattach) and an
end-to-end test of the real SshTab: host key → password → typing → drop → automatic reconnect → end session.
Robolectric screenshots for the terminal, approval chips, agents panel and server list.
