# Kabuk 0.2: plan

Goal: driving coding agents from the phone should be fast. That means knowing when an agent needs you,
answering in one tap, and getting text, images and voice in without fighting the keyboard.

Research (2026): the apps built for agent use (Moshi, Melta, Claudette Echo) all centre on the same things:
- push notifications when an agent blocks on a question or approval
- one-tap approve / reject
- a list of agents grouped Needs you / Working / Done, instead of multiplexer key chords
- image paste into prompts, voice input, an extra key row, surviving disconnects

Moshi and Melta need a helper installed on the server. herdr's own CLI already gives agent status, focus and
pane text, so Kabuk can do it over the existing SSH connection with nothing to install.

## A. Agents (herdr)
- While an SSH tab is connected, poll `herdr agent list` + `workspace list` every 3 s on a side channel
  (stop quietly when herdr isn't there; check again every minute).
- Status chip in the top bar: "2 working · 1 needs you" (red when something is blocked).
- Agents sheet: grouped **Needs you / Working / Idle**, showing workspace, folder and agent. Tap → `herdr agent focus`.
- Notifications: an agent becomes *blocked* → high-priority "needs you". *working → idle* → "finished"
  (can be turned off). Tap opens the app on that agent. Nothing when you're already looking at it.

## B. Quick replies
- Look at the bottom of the screen for a numbered choice menu (Claude Code / Codex approvals, pickers).
- Show chips above the key bar, e.g. `1 Yes` `2 Yes, always` `3 No` `Esc`. One tap sends the number.

## C. Prompt box
- 🎤 voice input (Android speech recognizer).
- 📎 image: pick a photo/screenshot → upload over SFTP to `~/.cache/kabuk/` → its path is inserted into the
  prompt, which Claude Code can read. Images shared into Kabuk do the same.
- ⚡ snippets: /clear, /compact, /model, /resume, /review … plus your own.

## D. Review fixes
Findings from my review and the Codex review (see REVIEW-0.2.md), fixed by priority.

## E. Smaller UX
- Back keeps the app running instead of closing the screen.
- Notification shows the agent summary.
- Tests for every parser (quick replies, herdr JSON) and screenshots of the new UI.
