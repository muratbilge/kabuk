package app.kabuk

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kabuk.data.Host
import app.kabuk.data.Hosts
import app.kabuk.term.Prompt
import app.kabuk.term.SshTab
import app.kabuk.term.Status
import app.kabuk.term.TabManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * The real SshTab against a throwaway server (see SshIntegrationTest for the docker command):
 * host key question → password → typing reaches the server and its output reaches the screen →
 * a dropped connection comes back by itself into the same tmux session.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SshTabEndToEndTest {
    private val target = System.getenv("KABUK_TEST_SSH")
    private var tab: SshTab? = null

    @After fun tearDown() { tab?.let { TabManager.close(it) } }

    /** runs main-thread work and answers questions until [cond] holds */
    private fun until(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            TabManager.prompts.value.forEach { p ->
                when (p) {
                    is Prompt.Confirm -> p.answer.complete(!p.title.startsWith("Log in without"))
                    is Prompt.Password -> p.answer.complete("kabuktest" to false)
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $what (status ${tab?.status?.value})")
    }

    private fun screen() = tab!!.emulator.screen.transcriptText

    @Test fun connectTypeReconnect() {
        assumeTrue("set KABUK_TEST_SSH", target != null)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val h = Host(id = "e2e", name = "e2e", host = target!!.substringBefore(':'), port = target.substringAfter(':').toInt(),
            user = "test", tmuxSession = "e2e", keyInstalled = true)
        Hosts.save(h)
        tab = TabManager.openSsh(h) as SshTab
        until("connected") { tab!!.status.value == Status.Connected }
        tab!!.resize(100, 30)

        tab!!.write("echo kabuk-\$((6*7))\r")
        until("command output on screen") { screen().contains("kabuk-42") }

        // drop the connection: the tab must come back by itself, into the same tmux session
        tab!!.networkChanged()
        until("noticed the drop") { tab!!.status.value !is Status.Connected }
        until("reconnected", 40_000) { tab!!.status.value == Status.Connected }
        tab!!.write("echo again-\$((2+2))\r")
        until("output after reconnect") { screen().contains("again-4") }
        // tmux redraws the old screen on reattach, so the first command is still visible
        assertTrue(screen().contains("kabuk-42"))

        var killed: Boolean? = null
        tab!!.killRemoteSession { killed = it }
        until("tmux session ended") { killed != null }
        assertEquals(true, killed)
    }
}
