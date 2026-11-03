package app.kabuk

import app.kabuk.agents.AnswerLedger
import app.kabuk.text.Choice
import app.kabuk.text.Pending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerLedgerTest {
    private val yesNo = listOf(Choice("1", "Yes", true), Choice("2", "No", false))
    private val build = Pending("Bash command\n./gradlew test\nDo you want to proceed?", yesNo)
    private val clean = Pending("Bash command\nrm -rf build\nDo you want to proceed?", yesNo)

    @Test fun secondTapIsRefusedUntilReleased() {
        val l = AnswerLedger()
        val k = l.screenKey("orch:p1", build.fingerprint)
        assertTrue(l.claim(k))
        assertFalse(l.claim(k))
        l.release(k)
        assertTrue(l.claim(k))
    }

    /** the same approval asked again later (identical text) must be answerable again from the notification */
    @Test fun dialogThatLeftTheScreenIsForgotten() {
        val l = AnswerLedger()
        val k = l.screenKey("orch:p1", build.fingerprint)
        assertTrue(l.claim(k))
        l.prune(mapOf("orch:p1" to build))          // still on screen: stays answered
        assertFalse(l.claim(k))
        l.prune(emptyMap())                          // gone (the agent took the answer)
        assertTrue("asked again later", l.claim(k))
        l.prune(mapOf("orch:p1" to clean))           // a different dialog in that pane
        assertTrue(l.claim(k))
    }

    @Test fun busQuestionsAreNeverPruned() {
        val l = AnswerLedger()
        assertTrue(l.claim(l.questionKey(7)))
        l.prune(emptyMap())
        assertFalse(l.claim(l.questionKey(7)))
        assertTrue(l.claim(l.questionKey(8)))
    }

    @Test fun capKeepsTheNewest() {
        val l = AnswerLedger()
        repeat(201) { assertTrue(l.claim(l.questionKey(it.toLong()))) }
        assertEquals(200, l.size())
        assertTrue("the oldest was dropped", l.claim(l.questionKey(0)))
        assertFalse(l.claim(l.questionKey(200)))
    }
}
