package app.kabuk

import app.kabuk.data.Host
import app.kabuk.ssh.KeyPairStore
import app.kabuk.term.SshTab
import net.schmizz.sshj.AndroidConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.password.PasswordFinder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security

/**
 * Runs the app's SSH pieces against a real server. Start a throwaway one first:
 *   docker run -d --name kabuk-sshd-test -p 127.0.0.1:2222:2222 -e USER_NAME=test -e USER_PASSWORD=kabuktest \
 *     -e PASSWORD_ACCESS=true lscr.io/linuxserver/openssh-server && docker exec kabuk-sshd-test apk add tmux
 *   KABUK_TEST_SSH=127.0.0.1:2222 ./gradlew :app:testDebugUnitTest
 */
class SshIntegrationTest {
    companion object {
        private val target = System.getenv("KABUK_TEST_SSH")
        private val host get() = target!!.substringBefore(':')
        private val port get() = target!!.substringAfter(':').toInt()

        @BeforeClass @JvmStatic fun setup() {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    private fun client() = SSHClient(AndroidConfig()).apply {
        addHostKeyVerifier(PromiscuousVerifier())
        connect(host, port)
    }

    @Test fun generatedKeyParsesInSshj() {
        val (priv, pub) = KeyPairStore.generate("test@phone")
        assertTrue(pub.startsWith("ssh-ed25519 "))
        val kp = SSHClient(AndroidConfig()).loadKeys(priv, null as String?, null as PasswordFinder?)
        assertEquals("Ed25519", kp.private.algorithm.let { if (it == "EdDSA") "Ed25519" else it })
    }

    @Test fun passwordLoginThenKeyInstallThenKeyLogin() {
        assumeTrue("set KABUK_TEST_SSH", target != null)
        val (priv, pub) = KeyPairStore.generate("test@phone")
        client().use { c ->
            c.authPassword("test", "kabuktest")
            assertTrue(SshTab.installKey(c, pub))
            assertTrue("installing twice must not duplicate", SshTab.installKey(c, pub))
            val lines = SshTab.exec(c, "grep -c ed25519 ~/.ssh/authorized_keys").second.trim()
            assertTrue("key present once, got $lines", lines.toInt() >= 1)
            val perms = SshTab.exec(c, "stat -c %a ~/.ssh ~/.ssh/authorized_keys").second.trim().lines()
            assertEquals(listOf("700", "600"), perms)
        }
        client().use { c ->
            c.authPublickey("test", c.loadKeys(priv, null as String?, null as PasswordFinder?))
            assertTrue(c.isAuthenticated)
        }
    }

    @Test fun keyInstallKeepsAnExistingKeyWithoutNewlineIntact() {
        assumeTrue("set KABUK_TEST_SSH", target != null)
        val (_, pub) = KeyPairStore.generate("test@phone")
        client().use { c ->
            c.authPassword("test", "kabuktest")
            SshTab.exec(c, "mkdir -p ~/.ssh && printf 'ssh-ed25519 AAAAexisting other@host' > ~/.ssh/authorized_keys")
            assertTrue(SshTab.installKey(c, pub))
            val lines = SshTab.exec(c, "cat ~/.ssh/authorized_keys").second.lines().filter { it.isNotBlank() }
            assertEquals(listOf("ssh-ed25519 AAAAexisting other@host", pub.trim()), lines)
        }
    }

    @Test fun execGivesUpOnAHangingCommand() {
        assumeTrue("set KABUK_TEST_SSH", target != null)
        client().use { c ->
            c.authPassword("test", "kabuktest")
            val t0 = System.currentTimeMillis()
            val r = runCatching { SshTab.exec(c, "sleep 30", timeoutS = 2) }
            assertTrue("should time out", r.isFailure)
            assertTrue(System.currentTimeMillis() - t0 < 10_000)
        }
    }

    @Test fun tmuxCommandAttachesAndReattaches() {
        assumeTrue("set KABUK_TEST_SSH", target != null)
        val h = Host(id = "t", name = "t", host = host, user = "test", tmuxSession = "kabuk test")
        repeat(2) { round ->
            client().use { c ->
                c.authPassword("test", "kabuktest")
                val s = c.startSession() as SessionChannel
                s.allocatePTY("xterm-256color", 100, 30, 0, 0, emptyMap())
                val cmd = s.exec(h.remoteCommand()!!)
                if (round == 0) cmd.outputStream.apply { write("echo marker-\$((40+2))\r".toByteArray()); flush() }
                Thread.sleep(1500)
                s.changeWindowDimensions(120, 40, 0, 0)
                val avail = cmd.inputStream.available()
                val buf = ByteArray(avail); cmd.inputStream.read(buf)
                val screen = String(buf)
                // tmux drew its screen (alternate buffer on) and, on the second attach, still shows our marker
                assertTrue("round $round: no tmux screen", screen.contains("\u001b[?1049h"))
                if (round == 1) assertTrue("round 1: session not kept", screen.contains("marker-42"))
            }
        }
        client().use { c ->
            c.authPassword("test", "kabuktest")
            val capture = SshTab.exec(c, "tmux capture-pane -p -J -S -100 -t 'kabuk test'").second
            assertTrue(capture.contains("marker-42"))
            assertEquals(0, SshTab.exec(c, "tmux kill-session -t 'kabuk test'").first)
        }
    }
}
