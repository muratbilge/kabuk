# Kabuk 0.5.0 (22) — whole-project review (Claude, 2026-11-03)

Verified: 66 unit tests green (6 skipped = SSH integration without a docker server); `orch` has `read --hash`,
`send --expect`, `answer --literal`, `watch --json` (60 s heartbeat, flushed lines); the published `kabuk-22.apk`
is byte-identical to the local build.

Codex's 0.4 review: #1 (exported activity) fixed with the shortcut token, #2 (prompt in fingerprint) fixed,
#3 (read→send race) fixed for orch via `--expect` + once-only on orchd — **not for herdr**, #4 (PendingIntent
identity) fixed, #5 (API < 31 lock screen) fixed via the activity path, #6 (reason to wrong pane) fixed,
#7 (answered() over a newer question) fixed.

## Findings

| # | Severity | Where | Finding | Status |
|---|----------|-------|---------|--------|
| 1 | high | `SshTab.answerAgent` | The answered-set was keyed by pane + fingerprint and never forgotten, so an identical approval asked again later ("Bash(./gradlew test)") could not be answered from the notification or inbox ("Not sent: the question changed"). | fixed in 0.5.1: `AnswerLedger`, pruned when the dialog leaves the screen; test `AnswerLedgerTest` |
| 2 | medium | `Herdr.answer` | herdr backend still reads then sends with nothing atomic in between (two clients, or a dialog that changes in the gap, can double-answer). | open — decide: retire herdr or port `--expect` |
| 3 | medium | `SshTab.answerAgent`, `Orch.answerScreen` | Screen answers logged nothing; a spurious "dialog changed" (e.g. ticking screen between read and send) was invisible from the DevBox. | fixed in 0.5.1: `answerScreen`/`answer` return the reason, logged as `AGENT answer <pane> <key>: sent / dialog changed / refused by orchd …` |
| 4 | medium | `SshTab.watchOrch`, `AgentNotifier` | An orchd restart or SSH reconnect cleared all agents/questions and re-alerted every open "needs you" when they came back (3× on 2026-11-03 morning). | fixed in 0.5.1: a vanished "needs you" is cancelled after a 45 s grace; the same question coming back inside it is not re-posted |
| 5 | low | `MainActivity.handleIntent` | Any installed app can send an explicit `ACTION_SEND image/*` and have the picture SFTP-uploaded to `~/.cache/kabuk/` (path lands in the draft only). | open |
| 6 | low | `herdr_watch.py`, `SshTab.watchAgents` | "Auto" servers with python3 but no herdr keep a watcher polling a missing socket every 5 s for the connection's lifetime. | open |
| 7 | low | release | Two different builds were both 0.5.0 (22). `LOGSINK_TOKEN` is a plain string in the APK. | fixed in 0.5.1: `release.sh` refuses an already published versionCode; token: accepted (private LAN sink) |
| 8 | low | docs/code | README stale for 0.5; `SshTab.liveAgents` dead; `SecretBox.key()` first-use race (theoretical); no test covered the answer dedupe. | README + dead field + test done in 0.5.1 |
