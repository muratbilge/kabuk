package app.kabuk

import app.kabuk.data.Host
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs the command Kabuk sends after login through a real sh, the way sshd does. */
class RemoteCommandTest {
    private fun run(cmd: String, path: String, home: String = "/nonexistent"): String {
        val p = ProcessBuilder("/bin/sh", "-c", cmd).redirectErrorStream(true)
        p.environment().apply { clear(); put("PATH", path); put("SHELL", "/bin/echo"); put("HOME", home) }
        val proc = p.start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return out
    }

    @Test fun noTmuxGivesPlainShell() {
        val cmd = Host(id = "x", name = "pve", host = "h", user = "root").remoteCommand()!!
        // a PATH with sh but no tmux: a note, then the login shell (/bin/echo here, which prints its "-l")
        val dir = createTempDir()
        java.nio.file.Files.createSymbolicLink(File(dir, "sh").toPath(), File("/bin/sh").toPath())
        val out = run(cmd, dir.absolutePath)
        dir.deleteRecursively()
        assertTrue(out, out.contains("no herdr or tmux here"))
        assertEquals("-l", out.lines().last { it.isNotBlank() })
    }

    @Test fun tmuxGetsTheSessionArguments() {
        val dir = createTempDir()
        File(dir, "tmux").apply { writeText("#!/bin/sh\nfor a in \"\$@\"; do echo \"[\$a]\"; done\n"); setExecutable(true) }
        val cmd = Host(id = "x", name = "dev", host = "h", user = "u", tmuxSession = "my 'work'").remoteCommand()!!
        val out = run(cmd, dir.absolutePath + ":/usr/bin:/bin")
        assertEquals(
            listOf("[-u]", "[new-session]", "[-A]", "[-s]", "[my 'work']", "[;]", "[set-option]", "[mouse]", "[on]"),
            out.lines().filter { it.isNotBlank() },
        )
        dir.deleteRecursively()
    }

    private fun fake(dir: File, name: String) =
        File(dir, name).apply { parentFile!!.mkdirs(); writeText("#!/bin/sh\necho \"$name \$*\"\n"); setExecutable(true) }

    @Test fun herdrComesFirst() {
        val dir = createTempDir()
        fake(dir, "herdr"); fake(dir, "tmux")
        val out = run(Host(id = "x", name = "dev", host = "h", user = "u").remoteCommand()!!, dir.absolutePath + ":/usr/bin:/bin")
        assertEquals("herdr", out.trim())
        dir.deleteRecursively()
    }

    @Test fun herdrInLocalBinWithoutPath() {
        // DevBox: herdr lives in ~/.local/bin, which a non-login shell doesn't have on PATH
        val home = createTempDir()
        fake(File(home, ".local/bin"), "herdr")
        val out = run(Host(id = "x", name = "dev", host = "h", user = "u").remoteCommand()!!, "/usr/bin:/bin", home.absolutePath)
        assertEquals("herdr", out.trim())
        home.deleteRecursively()
    }

    @Test fun tmuxModeSkipsHerdr() {
        val dir = createTempDir()
        fake(dir, "herdr"); fake(dir, "tmux")
        val out = run(Host(id = "x", name = "dev", host = "h", user = "u", session = Host.TMUX).remoteCommand()!!, dir.absolutePath + ":/usr/bin:/bin")
        assertTrue(out, out.startsWith("tmux -u new-session -A -s main ; set-option mouse on"))
        dir.deleteRecursively()
    }

    @Test fun oldSettingsMigrate() {
        fun from(json: String) = Host.fromJson(org.json.JSONObject(json))
        val base = """"id":"a","name":"n","host":"h","user":"u""""
        assertEquals(Host.AUTO, from("{$base,\"useTmux\":true}").session)
        assertEquals(Host.SHELL, from("{$base,\"useTmux\":false}").session)
        assertEquals(Host.TMUX, from("{$base,\"session\":\"tmux\"}").session)
    }
}
