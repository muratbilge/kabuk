# Kabuk — design council brief

Kabuk is a native Android SSH terminal (Kotlin, Jetpack Compose + Material3 1.3 via compose-bom 2024.12.01,
minSdk 26, target 35). Code: /projects/kabuk (app/src/main/java/app/kabuk/...). One user, who uses it on
their phone (Samsung-class, ~412dp wide, 46 terminal columns at default font) to SSH into his DevBox and drive
coding agents (Claude Code, Codex) inside herdr (a terminal workspace manager for agents) and tmux.

Main job of the app: supervise and steer AI coding agents from a phone. Typical loop: get a "needs you"
notification -> open -> read what the agent asks -> approve with one tap (quick-reply chips) or type/voice a
prompt -> pocket the phone. Also: read long output, copy text, scroll history, switch between agents/tabs.

The user's feedback so far: Termux's keyboard and copy/paste were the worst pain; they want it "fast and modern",
Material Design; he liked: smoother scrolling, nautilus logo, compact message field with expand + hide.
Open problem right now: quick-reply chips used to vanish when the message field was open (fixed in 0.2.7 by
making them sticky; the bar still changes terminal height when it appears).

Screens (Robolectric renders, 412x~890dp, in shots/):
- terminal.png / terminal-disconnected.png: tab strip (agents chip, +, ⋮), terminal, 2-row key bar, message box
- approval.png: quick-reply chips row above key bar during a Claude permission prompt; agents.png: agents sheet
- prompt-compact.png / prompt-expanded.png: message box (hide ⌄, ⚡ snippets/history, field with picture/mic/expand, send)
- hosts.png, host-edit.png, settings.png, text-mode.png (copy text screen)
Key files: ui/TerminalScreen.kt, ui/KeyBar.kt, ui/PromptBox.kt, ui/AgentUi.kt, ui/Theme.kt, ui/HostsScreen.kt,
ui/SettingsScreen.kt, ui/TextModeScreen.kt, ui/Dialogs.kt, term/TerminalCanvasView.kt (touch, selection), MainActivity.kt.

A Material 3 skill (guidelines + component catalog) is installed at ~/.claude/skills/material-3/
(SKILL.md + references/*.md). Use it as the design-system reference.

Constraints: no web views, keep it fast (no heavy animation on the terminal path), dark-first (terminal),
keep the terminal area as large as possible, everything must work one-handed with the soft keyboard up.
