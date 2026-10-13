I read the README, all application source and tests, and the build/release configuration. No files were changed. This is a static review; I did not run Gradle because it writes build artifacts.

The highest risks are **input going to the wrong session, password state crossing server prompts, reconnect races, and a reproducible selection crash**. I found no supported critical-severity issue.

The emulator threading is better than the question suggests: SSH output is copied and posted to the main thread at `SshTab.kt:164`; resizing, rendering, and normal input also run there. I did not find a direct reader-thread-versus-renderer race. Preserve that confinement when fixing the issues below.

1. **High — Ctrl+C and mode switching can target the previous tab.**  
   [KeyBar.kt:154](/projects/kabuk/app/src/main/java/app/kabuk/ui/KeyBar.kt:154), [TerminalScreen.kt:105](/projects/kabuk/app/src/main/java/app/kabuk/ui/TerminalScreen.kt:105), [TerminalScreen.kt:115](/projects/kabuk/app/src/main/java/app/kabuk/ui/TerminalScreen.kt:115)  
   `pointerInput(key)` retains its captured `onAction`; switching tabs does not change `key`. Open A, use the key bar, switch to B, then tap **^C**: the captured callback can still write to A. Shift+Tab, Ctrl+D, and newline have the same problem because `Raw` uses the captured `tab`; ordinary arrows use the current view, making behavior inconsistent.  
   **Fix:** use `rememberUpdatedState(onAction)` and resolve the destination consistently. Cancel held gestures when the selected tab changes. This follows Compose’s documented [captured-state handling for pointerInput](https://developer.android.com/reference/kotlin/androidx/compose/ui/input/pointer/pointerInput.modifier).

2. **High — Delayed Enter can execute or approve something in another tab.**  
   [TerminalCanvasView.kt:644](/projects/kabuk/app/src/main/java/app/kabuk/term/TerminalCanvasView.kt:644)  
   `sendBlock()` pastes into the current emulator, then schedules `tab?.write("\r")` 30–120 ms later. Switching tabs during that interval sends Enter to the new tab. That can submit an unfinished shell command or accept an agent approval there.  
   **Fix:** capture the originating tab and connection generation; enqueue paste and submission against that destination. Cancel pending submission when that connection closes or changes. A fixed delay is also not evidence that the remote application has finished handling the paste.

3. **High — Password dialogs can reuse another server’s password.**  
   [Dialogs.kt:48](/projects/kabuk/app/src/main/java/app/kabuk/ui/Dialogs.kt:48)  
   Password and “Remember” state use unkeyed `remember`. If two password prompts are queued, replacing the first with the second can preserve both values. The second server then shows a populated masked field; tapping Log in sends the first server’s password and may save it for the second.  
   **Fix:** key the entire dialog by prompt identity, or use `remember(p)` for every prompt-local value, including focus state.

4. **High — Prompt queue updates can lose requests, and closing a tab cannot cancel its prompts.**  
   [TabManager.kt:111](/projects/kabuk/app/src/main/java/app/kabuk/term/TabManager.kt:111), [SshTab.kt:261](/projects/kabuk/app/src/main/java/app/kabuk/term/SshTab.kt:261)  
   Multiple SSH threads perform non-atomic `prompts.value = prompts.value + p`. Two simultaneous connections can overwrite one another’s requests, leaving an invisible prompt whose thread waits forever. `runBlocking { d.await() }` has no timeout or tab cancellation; Exit/close only wakes the reconnect lock. The key-install question also blocks before terminal output pumping starts.  
   **Fix:** serialize prompt creation/removal, associate requests with tab and attempt IDs, and cancel their deferred answers on close/disconnect. Use `finally` cleanup and a bounded synchronous bridge where sshj requires one. Run optional key installation independently of terminal reading. These callers are background threads, so the demonstrated problem is stranded work, not a direct main-thread `runBlocking` ANR.

5. **High — Reconnect cleanup can disconnect the new connection or lose ownership of one.**  
   [SshTab.kt:241](/projects/kabuk/app/src/main/java/app/kabuk/term/SshTab.kt:241), [SshTab.kt:255](/projects/kabuk/app/src/main/java/app/kabuk/term/SshTab.kt:255)  
   Reconnect spawns a cleanup thread and immediately wakes the loop. While disconnected, the loop can create a new `ssh` before cleanup runs; cleanup then disconnects that new client. Multiple cleanup callers also race across `val c = ssh; ssh = null`. `@Volatile` does not make this compound operation atomic.  
   **Fix:** give one connection controller ownership of each attempt. Cleanup should close a specific captured client and clear shared state only if it still belongs to that generation. Serialize reconnect, close, and network-change events.

6. **High — Resizing a pane selection can crash the app.**  
   [TerminalCanvasView.kt:130](/projects/kabuk/app/src/main/java/app/kabuk/term/TerminalCanvasView.kt:130), [TerminalCanvasView.kt:336](/projects/kabuk/app/src/main/java/app/kabuk/term/TerminalCanvasView.kt:336)  
   Select in a right-hand pane whose `paneL` is 60, then rotate into a narrower terminal or increase the font until there are 40 columns. Selection bounds survive resizing. Dragging a handle calls `coerceIn(60, 39)`, which throws `IllegalArgumentException`.  
   **Fix:** clear or explicitly remap selections and pane bounds before resizing; validate both column and row ranges afterward.

7. **High — Fast output has an unbounded queue ahead of the UI.**  
   [SshTab.kt:159](/projects/kabuk/app/src/main/java/app/kabuk/term/SshTab.kt:159)  
   Every read allocates another byte array and posts another main-thread runnable without backpressure. A large build log or runaway output can outpace emulation, consume memory, and delay the interrupt button behind queued work. Closing a tab does not invalidate already queued chunks.  
   **Fix:** use a bounded byte queue and a coalesced main-thread drain with a processing budget. Apply backpressure without dropping arbitrary terminal bytes, and reject chunks from closed generations.

8. **Medium — Disconnected Send silently discards the prompt.**  
   [SshTab.kt:58](/projects/kabuk/app/src/main/java/app/kabuk/term/SshTab.kt:58), [TerminalScreen.kt:324](/projects/kabuk/app/src/main/java/app/kabuk/ui/TerminalScreen.kt:324)  
   After the phone sleeps or connectivity drops, Send remains available. `send()` returns when `out` is null, but the prompt box clears anyway. Writer exceptions are also swallowed. A delayed Enter may survive until reconnection even though the prompt body was discarded.  
   **Fix:** return an explicit local enqueue result, retain the draft when input cannot be accepted, and show connection-aware sending state. Never automatically replay uncertain commands after reconnect.

9. **Medium — Host-key rejection and login cancellation do not remain stopped.**  
   [HostKeys.kt:25](/projects/kabuk/app/src/main/java/app/kabuk/ssh/HostKeys.kt:25), [SshTab.kt:101](/projects/kabuk/app/src/main/java/app/kabuk/term/SshTab.kt:101), [SshTab.kt:247](/projects/kabuk/app/src/main/java/app/kabuk/term/SshTab.kt:247)  
   Rejecting a host key produces a connection failure that falls into automatic retry, repeatedly asking the same trust question. Separately, `networkChanged()` wakes every disconnected tab, including cancelled login attempts and hosts with automatic reconnect disabled.  
   **Fix:** distinguish transient transport failures from explicit cancellation, authentication failure, and rejected trust. Only the transient state should resume automatically. Repeated trust prompts encourage an eventual accidental acceptance.

10. **Medium — Remote helper commands have no effective ten-second deadline.**  
    [SshTab.kt:280](/projects/kabuk/app/src/main/java/app/kabuk/term/SshTab.kt:280)  
    `readText()` waits for EOF before `join(10, …)` runs. A stuck `herdr pane read` or remote command that never closes stdout can leave Copy text spinning indefinitely. Reopening the screen starts additional threads; output size is unbounded and stderr is not drained.  
    **Fix:** impose a deadline covering execution and both streams, close the channel on cancellation, bound captured bytes, and surface failures to the UI.

11. **Medium — A resize during connection setup can be lost permanently.**  
    [SshTab.kt:66](/projects/kabuk/app/src/main/java/app/kabuk/term/SshTab.kt:66), [SshTab.kt:148](/projects/kabuk/app/src/main/java/app/kabuk/term/SshTab.kt:148)  
    The PTY is allocated before `channel` is published. If the keyboard opens during that interval, the local emulator changes dimensions but no remote resize is sent. Future calls with those same dimensions return early, leaving tmux laid out for the wrong size.  
    **Fix:** synchronize dimension snapshots and publication, then send the latest dimensions after the channel becomes ready.

12. **Medium — Session and draft recovery depends entirely on the process surviving.**  
    [TabManager.kt:39](/projects/kabuk/app/src/main/java/app/kabuk/term/TabManager.kt:39), [MainActivity.kt:62](/projects/kabuk/app/src/main/java/app/kabuk/MainActivity.kt:62), [SessionService.kt:47](/projects/kabuk/app/src/main/java/app/kabuk/service/SessionService.kt:47)  
    Process death loses the tab list, names, selected tab, and drafts; the service returns `START_NOT_STICKY`. Activity recreation alone recreates `TerminalUi` and loses its draft. Server-side tmux can survive, but Kabuk forgets the workspace needed to return to it. Additionally, one global draft follows the user between tabs, making accidental submission to another agent easy.  
    **Fix:** persist lightweight tab/session descriptors and per-tab drafts, restore them on launch, and reconnect without replaying input. Handle foreground-service startup failure visibly instead of swallowing it in `syncService()`.

13. **Medium — The terminal callback retains a finished activity.**  
    [TerminalCanvasView.kt:64](/projects/kabuk/app/src/main/java/app/kabuk/term/TerminalCanvasView.kt:64), [MainActivity.kt:66](/projects/kabuk/app/src/main/java/app/kabuk/MainActivity.kt:66)  
    The process-global tab stores a callback capturing the view; the view holds its activity and listener. There is no lifecycle cleanup. Finishing the activity while SSH remains open retains the activity and keeps delivering updates to its detached view until another binding replaces it.  
    **Fix:** attach and detach the callback explicitly with the UI lifecycle, removing only the callback owned by that view. Cancel delayed input and scrolling work when releasing it.

14. **Medium — IME deletion and hardware modifiers can send incorrect input.**  
    [TerminalCanvasView.kt:529](/projects/kabuk/app/src/main/java/app/kabuk/term/TerminalCanvasView.kt:529), [TerminalCanvasView.kt:596](/projects/kabuk/app/src/main/java/app/kabuk/term/TerminalCanvasView.kt:596), [TerminalCanvasView.kt:626](/projects/kabuk/app/src/main/java/app/kabuk/term/TerminalCanvasView.kt:626)  
    `deleteSurroundingText(0, 1)` sends Backspace rather than forward Delete; deletion of composing text also sends deletion into the remote terminal before adjusting the local composing buffer. Hardware character handling first tries `sendKeyCode()`, which consumes one-shot modifiers even if no special-key encoding exists; a sticky Ctrl can disappear before the letter is handled. Dead accents are simply discarded at line 631.  
    **Fix:** separate composing-buffer edits from committed terminal edits; implement deletion directions correctly. Resolve modifiers once per event and consume them only when dispatching it. Add dead-key composition handling.

15. **Medium — Default copying changes valid code and commands.**  
    [Unwrap.kt:16](/projects/kabuk/app/src/main/java/app/kabuk/text/Unwrap.kt:16), [TerminalCanvasView.kt:289](/projects/kabuk/app/src/main/java/app/kabuk/term/TerminalCanvasView.kt:289)  
    `Unwrap.unwrap("echo one\necho two")` joins these separate commands into `echo one echo two`: the longest line is treated as the wrap width. The heuristic also expands tabs and removes common indentation; selection adds `trimStart()`. Copying agent-generated commands or Python can therefore change their meaning.  
    **Fix:** make faithful copying the default, preserving indentation and using terminal wrap metadata. Keep heuristic joining as an explicit prose operation with a preview; do not apply it automatically to already unwrapped server captures.

16. **Medium — Pane detection can copy adjacent content or truncate ordinary text.**  
    [TerminalCanvasView.kt:237](/projects/kabuk/app/src/main/java/app/kabuk/term/TerminalCanvasView.kt:237)  
    A border must cover half the entire viewport. A vertical split inside a short horizontal split fails that test, so selection can include the neighboring pane. Conversely, a Markdown table containing aligned `|` characters can be mistaken for pane boundaries. No vertical pane bounds are recorded.  
    **Fix:** prefer actual pane geometry from tmux/herdr. For visual fallback, detect connected boundaries around the selected row, include top/bottom bounds, and provide a full-width override.

17. **Medium — Copy history can return a different agent’s output and silently truncates it.**  
    [SshTab.kt:215](/projects/kabuk/app/src/main/java/app/kabuk/term/SshTab.kt:215), [SshTab.kt:272](/projects/kabuk/app/src/main/java/app/kabuk/term/SshTab.kt:272), [TextModeScreen.kt:55](/projects/kabuk/app/src/main/java/app/kabuk/ui/TextModeScreen.kt:55)  
    Every SSH tab tries the globally focused herdr pane first, with no association to that tab’s tmux session. Copying a different session can therefore return unrelated output. The JSON parsing also assumes exact compact formatting. Finally, “whole history” is silently reduced to 4,000 lines, even after requesting 20,000 from tmux.  
    **Fix:** track session/window/pane identity, parse structured output properly, and label the capture source. Provide paging, search, and explicit truncation indicators.

18. **Medium — Reading gestures can operate the agent instead of scrolling history.**  
    [TerminalCanvasView.kt:367](/projects/kabuk/app/src/main/java/app/kabuk/term/TerminalCanvasView.kt:367), [TerminalCanvasView.kt:465](/projects/kabuk/app/src/main/java/app/kabuk/term/TerminalCanvasView.kt:465)  
    In an alternate-screen program without mouse tracking, dragging sends arrow keys. Attempting to read an approval screen can change its selected option. When mouse tracking is enabled, taps always become remote clicks, so link opening is unavailable in the default mouse-enabled tmux setup. Dragging cannot resize remote panes because it becomes wheel events.  
    **Fix:** expose distinct Read/select and Remote mouse modes, with a visible indicator. Provide deliberate pane dragging and a link action that remains available in mouse mode.

19. **Medium — OSC 52 allows unattended clipboard replacement.**  
    [SshTab.kt:46](/projects/kabuk/app/src/main/java/app/kabuk/term/SshTab.kt:46), [LocalTab.kt:22](/projects/kabuk/app/src/main/java/app/kabuk/term/LocalTab.kt:22)  
    Output containing OSC 52 can overwrite the phone clipboard, including output from an unselected tab. A malicious file printed by a command could replace a command the user intended to paste elsewhere.  
    **Fix:** add per-host clipboard-write permission and foreground-tab restrictions, with clear source attribution or confirmation. I verified that the pinned [Termux OSC 52 implementation](https://raw.githubusercontent.com/termux/termux-app/v0.118.1/terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java) implements clipboard writes; this is not a demonstrated clipboard-read exfiltration issue.

20. **Medium — Prompt history bypasses the protection used for passwords.**  
    [Settings.kt:60](/projects/kabuk/app/src/main/java/app/kabuk/data/Settings.kt:60), [TerminalScreen.kt:327](/projects/kabuk/app/src/main/java/app/kabuk/ui/TerminalScreen.kt:327)  
    Every nonblank prompt is saved in plaintext preferences. Entering a token, password, or sensitive code through the prompt box leaves it in persistent history, with no clear-history or no-history option.  
    **Fix:** provide disable/clear/private-send controls and encrypt retained history. The existing encrypted password/private-key storage and `allowBackup="false"` are good foundations, but do not protect this separate path.

21. **Medium — Key installation can report success without producing usable key login.**  
    [SshTab.kt:207](/projects/kabuk/app/src/main/java/app/kabuk/term/SshTab.kt:207), [SshTab.kt:289](/projects/kabuk/app/src/main/java/app/kabuk/term/SshTab.kt:289)  
    `umask` does not repair permissions on existing `.ssh` or `authorized_keys`. Appending to a file lacking a final newline can also concatenate the new key onto an existing line. The shell command can succeed, after which Kabuk deletes the saved password and marks the key installed, although authentication still fails.  
    **Fix:** ensure appropriate permissions and line separation, then verify public-key authentication on a new connection before removing the password.

22. **Medium — Background authentication can overwrite server edits.**  
    [Hosts.kt:76](/projects/kabuk/app/src/main/java/app/kabuk/data/Hosts.kt:76), [SshTab.kt:189](/projects/kabuk/app/src/main/java/app/kabuk/term/SshTab.kt:189), [SshTab.kt:209](/projects/kabuk/app/src/main/java/app/kabuk/term/SshTab.kt:209)  
    SSH threads save a complete, potentially stale `Host`. Editing its address or session while a login/key-install prompt is pending can be undone when authentication finishes. Concurrent saves also race over the whole host list.  
    **Fix:** serialize storage mutations and apply field-specific updates against the latest record, checking that the connection identity still matches before saving credentials.

23. **Medium — There is no reliable “agent needs you” notification.**  
    [TabManager.kt:143](/projects/kabuk/app/src/main/java/app/kabuk/term/TabManager.kt:143), [SessionService.kt:62](/projects/kabuk/app/src/main/java/app/kabuk/service/SessionService.kt:62)  
    An agent can wait for approval indefinitely while the phone is locked. The notification only reports open sessions; bells produce a short vibration, and unread dots are visible only inside the app. With vibration disabled, `bell()` returns before marking unread. Authentication prompts likewise have no actionable background notification.  
    **Fix:** consume explicit agent/herdr attention events where available, offer a bell fallback, and notify with a deep link to the exact tab/pane. Keep unread tracking independent of vibration and distinguish waiting-for-input from ordinary output.

24. **Low — Exported shortcut handling allows another app to initiate a saved connection.**  
    [AndroidManifest.xml:22](/projects/kabuk/app/src/main/AndroidManifest.xml:22), [MainActivity.kt:151](/projects/kabuk/app/src/main/java/app/kabuk/MainActivity.kt:151)  
    Any app can explicitly start the exported activity with `app.kabuk.CONNECT` and a known host ID; the default `devbox` ID is predictable. Kabuk can authenticate and execute that profile’s configured startup command without a fresh in-app action. This does not allow arbitrary command injection, but exposes a credential-backed action.  
    **Fix:** separate launcher/share handling from authenticated shortcut dispatch, using an appropriately protected internal path or an explicit user connection action for untrusted requests.

25. **Low — Frequent and destructive controls are awkwardly placed for phone use.**  
    [KeyBar.kt:120](/projects/kabuk/app/src/main/java/app/kabuk/ui/KeyBar.kt:120), [TerminalScreen.kt:226](/projects/kabuk/app/src/main/java/app/kabuk/ui/TerminalScreen.kt:226), [TerminalScreen.kt:241](/projects/kabuk/app/src/main/java/app/kabuk/ui/TerminalScreen.kt:241)  
    Eight keys per row leave narrow targets; newline, page navigation, and text capture rely on undiscoverable swipes. Tab switching sits at the top, and a small adjacent close button has no undo. “End session on server” immediately kills the agent and closes the tab even if the operation fails.  
    **Fix:** offer a configurable bottom action strip with prominent interrupt, approval navigation, and mode-switch controls. Add close undo, confirmation for remote termination, and retain the tab on termination failure.

On shell security, the current `shellQuote()` correctly escapes embedded single quotes, and the generated tmux/key-install commands use it. I did not find a demonstrated shell-injection path through those fields. `startupCommand` is intentionally executable shell text. The herdr capture problem is primarily incorrect targeting and brittle parsing.

The tests cover useful happy paths—mouse taps, one pane layout, prose unwrapping, and basic SSH operations—but not these transitions. The SSH integration tests bypass production host verification with `PromiscuousVerifier`, and screenshot tests record images rather than establish behavioral regression coverage. Prioritize tests that switch tabs, queue prompts, interrupt connection setup, resize active selections, and flood output.

The first ten changes I would make, in order:

1. Fix stale key-bar callbacks and bind delayed submission to its originating connection.
2. Isolate password-dialog state by prompt identity.
3. Make prompt queues atomic, cancellable, and owned by connection attempts.
4. Replace competing reconnect/cleanup threads with one connection controller.
5. Clear or remap selections safely during resizing.
6. Bound terminal-output buffering while keeping all emulator access on the main thread.
7. Preserve drafts on failed sends and store drafts per tab.
8. Make rejected trust and cancelled authentication remain stopped.
9. Make faithful copying the default and bind history capture to the correct pane.
10. Add workspace restoration and actionable notifications when an agent needs input.