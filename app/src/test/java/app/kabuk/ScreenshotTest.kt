package app.kabuk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kabuk.term.QuietSessionClient
import app.kabuk.term.Status
import app.kabuk.term.TabManager
import app.kabuk.term.TermTab
import app.kabuk.term.TerminalCanvasView
import app.kabuk.ui.Bg
import app.kabuk.ui.HostsScreen
import app.kabuk.ui.KabukTheme
import app.kabuk.ui.ModifierState
import app.kabuk.ui.TerminalScreen
import app.kabuk.ui.TerminalUi
import com.github.takahirom.roborazzi.captureRoboImage
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A tab with canned output instead of a connection. */
class FakeTab(id: Long, name: String, text: String, st: Status = Status.Connected) : TermTab(id, name, 0xFF7AA2F7) {
    private val out = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {}
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }
    override val emulator = TerminalEmulator(out, 80, 24, 2000, QuietSessionClient)
    init {
        status.value = st
        val b = text.replace("\n", "\r\n").toByteArray(); emulator.append(b, b.size)
    }
    override fun write(data: ByteArray, offset: Int, count: Int) {}
    override fun resize(columns: Int, rows: Int) = emulator.resize(columns, rows)
    override val canSend get() = true
    override fun close() {}
}

const val E = "\u001b"
val claudeLike = """
$E[38;5;208m╭───────────────────────────────────────────╮$E[0m
$E[38;5;208m│$E[0m $E[1m✻ Welcome to Claude Code!$E[0m                  $E[38;5;208m│$E[0m
$E[38;5;208m│$E[0m   cwd: /home/dev/projects/kabuk         $E[38;5;208m│$E[0m
$E[38;5;208m╰───────────────────────────────────────────╯$E[0m

$E[2m>$E[0m fix the tab close button

$E[38;5;114m●$E[0m I'll look at the tab strip first.

$E[38;5;114m●$E[0m $E[1mRead$E[0m(app/src/main/java/app/kabuk/ui/TerminalScreen.kt)
  ⎿  Read 412 lines

$E[38;5;114m●$E[0m $E[1mUpdate$E[0m(TerminalScreen.kt)
  ⎿  Updated with $E[32m3 additions$E[0m and $E[31m1 removal$E[0m

$E[38;5;114m●$E[0m Done. See https://github.com/termux/termux-app for details.

$E[2m╭─────────────────────────────────────────────╮$E[0m
$E[2m│$E[0m > ${E}[7m $E[0m                                           $E[2m│$E[0m
$E[2m╰─────────────────────────────────────────────╯$E[0m
  $E[2m⏵⏵ accept edits on (shift+tab to cycle)$E[0m
""".trimIndent()

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun ui(): TerminalUi {
        val mods = ModifierState()
        // the screenshots show the message box too (it starts hidden in the app)
        return TerminalUi(TerminalCanvasView(compose.activity, mods), mods).apply { showPrompt = true }
    }

    @Test fun terminal() {
        TabManager.add(FakeTab(100, "DevBox", claudeLike))
        TabManager.add(FakeTab(101, "prod", "$ uptime\n 00:12 up 9 days", Status.Disconnected("timeout", System.currentTimeMillis() + 8000)))
        TabManager.select(100)
        app.kabuk.term.Themes.apply("Kabuk", TabManager.tabs.value)
        val ui = ui()
        compose.setContent { KabukTheme { Box(Modifier.fillMaxSize().background(Bg)) { TerminalScreen(ui, {}, {}, {}) } } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/terminal.png")
        // a long prompt: compact (two lines) and expanded
        ui.draft = (1..8).joinToString("\n") { "line $it of a long prompt for Claude, with enough words to wrap" }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/prompt-compact.png")
        ui.promptExpanded = true
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/prompt-expanded.png")
        ui.draft = ""; ui.promptExpanded = false
        TabManager.select(101)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/terminal-disconnected.png")
        TabManager.closeAll()
    }

    private val approval = """
$E[38;5;114m●$E[0m $E[1mBash$E[0m(./gradlew assembleRelease)
  ⎿  Running…

$E[38;5;208m╭──────────────────────────────────────╮$E[0m
$E[38;5;208m│$E[0m $E[1mBash command$E[0m                         $E[38;5;208m│$E[0m
$E[38;5;208m│$E[0m   ./gradlew assembleRelease          $E[38;5;208m│$E[0m
$E[38;5;208m│$E[0m Do you want to proceed?              $E[38;5;208m│$E[0m
$E[38;5;208m│$E[0m $E[36m❯ 1. Yes$E[0m                             $E[38;5;208m│$E[0m
$E[38;5;208m│$E[0m   2. Yes, and don't ask again        $E[38;5;208m│$E[0m
$E[38;5;208m│$E[0m   3. No, and tell Claude what to do  $E[38;5;208m│$E[0m
$E[38;5;208m╰──────────────────────────────────────╯$E[0m
""".trimIndent()

    private fun fakeAgents(): app.kabuk.term.SshTab {
        // a real SshTab pointed at a closed port; only its agent list matters here
        val t = TabManager.openSsh(app.kabuk.data.Host(id = "shot", name = "DevBox", host = "127.0.0.1", port = 1, user = "x", autoReconnect = false))
            as app.kabuk.term.SshTab
        // wait until its connection attempt failed (that clears the list), then fill in our agents
        val end = System.currentTimeMillis() + 10_000
        while (t.status.value !is app.kabuk.term.Status.Disconnected && System.currentTimeMillis() < end) Thread.sleep(50)
        fun a(n: String, st: String, ws: String, cwd: String, f: Boolean = false) =
            app.kabuk.agents.Agent("term_$ws", "p_$ws", n, st, cwd, ws, 1, f)
        t.agents.value = listOf(
            a("claude", "blocked", "notes-app", "/projects/notes-app"),
            a("claude", "working", "kabuk", "/projects/kabuk", true),
            a("codex", "idle", "~", "/home/dev"),
            a("claude", "idle", "webshop", "/docker/webshop"),
        )
        t.questions.value = mapOf("p_notes-app" to app.kabuk.text.Pending(
            "Bash command\nsudo systemctl restart wg-quick@wg0\nDo you want to proceed?",
            listOf(app.kabuk.text.Choice("1", "Yes", true), app.kabuk.text.Choice("2", "Yes, and don't ask again", false),
                app.kabuk.text.Choice("3", "No, and tell Claude…", false)),
        ))
        TabManager.agentsChanged(t)
        return t
    }

    @Test fun approvalWithAgents() {
        fakeAgents()
        TabManager.add(FakeTab(200, "kabuk", approval))
        app.kabuk.term.Themes.apply("Kabuk", TabManager.tabs.value)
        val ui = ui()
        // the detector runs only while the app is on screen; here the menu is found from the same text directly
        ui.autoDetect = false
        ui.choices = app.kabuk.text.QuickReplies.find(approval.replace(Regex("\u001b\\[[0-9;]*m"), "").lines())
        org.junit.Assert.assertEquals(3, ui.choices.size)
        compose.setContent { KabukTheme { Box(Modifier.fillMaxSize().background(Bg)) { TerminalScreen(ui, {}, {}, {}) } } }
        compose.waitForIdle()
        Thread.sleep(500); compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/approval.png")
        ui.showAgents = true
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/agents.png")
        ui.showAgents = false
        ui.showQuick = true
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/quick.png")
        TabManager.closeAll()
    }

    @Test fun hosts() {
        compose.setContent { KabukTheme { HostsScreen({}, {}, {}, null) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/hosts.png")
    }

    @Test fun settings() {
        compose.setContent { KabukTheme { app.kabuk.ui.SettingsScreen {} } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings.png")
    }

    @Test fun hostEdit() {
        compose.setContent { KabukTheme { app.kabuk.ui.HostEditScreen(null) {} } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/host-edit.png")
    }

    @Test fun textMode() {
        val t = FakeTab(102, "DevBox", claudeLike)
        compose.setContent { KabukTheme { app.kabuk.ui.TextModeScreen(t) {} } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/text-mode.png")
    }
}
