package app.kabuk

import app.kabuk.agents.AnswerOutcome
import app.kabuk.agents.Orch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The contract with orchtool (notes/kabuk-contract-v2.md there): the watch line's version fields, the shared
 * fixture, orch's exit-code table, and that those codes reach Kabuk through its own sh wrapper.
 */
class OrchContractTest {
    private fun res(n: String) = javaClass.classLoader!!.getResource(n).readText()

    @Test fun versionFields() {
        val u = Orch.parseUpdate(res("orch-watch-v1.json").trim())!!
        assertEquals(1, u.v)
        assertEquals("0.1.0", u.orch)
        assertEquals("webshop-claude", u.agents.single().bus)
        // orchtool 0.1.0 sent neither field: the first format, no version string
        val old = Orch.parseUpdate("""{"agents":[],"questions":[],"screens":{},"timeline":[]}""")!!
        assertEquals(Orch.V, old.v)
        assertEquals("", old.orch)
        // a newer format still parses (unknown fields are ignored); the caller tells the user to update
        assertEquals(3, Orch.parseUpdate("""{"v":3,"orch":"9.9.9","agents":[],"extra":1}""")!!.v)
    }

    @Test fun exitCodeTable() {
        assertEquals(AnswerOutcome.SENT, Orch.outcomeOf(0))
        assertEquals(AnswerOutcome.CHANGED, Orch.outcomeOf(10))
        assertEquals(AnswerOutcome.TAKEN, Orch.outcomeOf(11))
        assertEquals(AnswerOutcome.GONE, Orch.outcomeOf(12))
        assertEquals(AnswerOutcome.STUCK, Orch.outcomeOf(13))
        assertEquals(AnswerOutcome.GONE, Orch.outcomeOf(14))
        assertEquals(AnswerOutcome.DAEMON, Orch.outcomeOf(20))
        assertEquals(AnswerOutcome.DAEMON, Orch.outcomeOf(3))
        assertEquals(AnswerOutcome.MISMATCH, Orch.outcomeOf(2))
        assertEquals(AnswerOutcome.UNSURE, Orch.outcomeOf(null))
        // an orch from before the table says 1 for everything: not a guess
        assertEquals(AnswerOutcome.FAILED, Orch.outcomeOf(1))
        assertEquals(AnswerOutcome.FAILED, Orch.outcomeOf(99))
    }

    private fun sh(cmd: String, home: File): Int {
        val p = ProcessBuilder("/bin/sh", "-c", cmd).redirectErrorStream(true)
        p.environment().apply { clear(); put("PATH", "/usr/bin:/bin"); put("HOME", home.absolutePath) }
        val proc = p.start()
        proc.inputStream.readBytes()
        return proc.waitFor()
    }

    /** the exact command Kabuk sends, through a real sh, against a fake orch that checks its arguments */
    @Test fun exitCodesPassThroughTheWrapper() {
        val home = createTempDir()
        File(home, ".local/bin").mkdirs()
        val orch = File(home, ".local/bin/orch")
        orch.writeText(
            "#!/bin/sh\n" +
                "[ \"\$1\" = send ] && [ \"\$2\" = 'orch:p1' ] && [ \"\$3\" = 1 ] && [ \"\$4\" = --expect ] && [ \"\$5\" = abc ] || exit 99\n" +
                "exit 11\n",
        )
        orch.setExecutable(true)
        assertEquals(11, sh(Orch.sendCommand("orch:p1", "1", "abc"), home))
        // Esc is a key press, never the escape byte as text
        val esc = Orch.sendCommand("orch:p1", Orch.ESC, "abc")
        assertTrue(esc, esc.contains(" --key Escape "))
        assertFalse(esc.contains("\u001b"))
        assertEquals(99, sh(esc, home))
        // no orch on the server at all: the wrapper's own 3
        orch.delete()
        assertEquals(3, sh(Orch.sendCommand("orch:p1", "1", "abc"), home))
        home.deleteRecursively()
    }
}
