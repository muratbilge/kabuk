package app.kabuk

import app.kabuk.log.KLog
import app.kabuk.log.LogOutbox
import app.kabuk.log.LogShipper
import app.kabuk.log.Poster
import app.kabuk.log.ShipResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LiveLogTest {
    @Test fun lineIsOneJsonLineWithStack() {
        val l = KLog.line(5L, 'E', "SSH", "bad\nthing\u0001", IllegalStateException("boom"))
        assertFalse(l.contains('\n'))
        val j = JSONObject(l)
        assertEquals("E", j.getString("l")); assertEquals("SSH", j.getString("tag")); assertEquals("bad thing ", j.getString("msg"))
        assertTrue(j.getString("stack").contains("IllegalStateException"))
    }

    @Test fun outboxDropsOldestAndKeepsSequence() {
        val o = LogOutbox(cap = 3, stream = "s")
        (1..5).forEach { o.append("l$it") }
        val b = o.peek()!!
        assertEquals(listOf("l3", "l4", "l5"), b.lines)
        assertEquals(2L, b.first); assertEquals(4L, b.last); assertEquals(2, b.dropped)
        o.acknowledge(b.last, b.dropped)
        assertNull(o.peek()); o.append("l6"); assertEquals(5L, o.peek()!!.first)
    }

    @Test fun shipperSignsAndOnlyDropsLinesAfter204() {
        val o = LogOutbox(stream = "st"); o.append("{\"a\":1}"); o.append("{\"a\":2}")
        var status: Int? = null
        var seen: Map<String, String> = emptyMap(); var body = ByteArray(0); var url = ""
        val s = LogShipper("http://h:1/", "tok", "dev", o, { true }, Poster { u, h, b -> url = u; seen = h; body = b; status }, { 1_000_000L })
        status = null; assertEquals(ShipResult.OFFLINE, s.shipOnce()); assertEquals(2, o.size())
        status = 500; assertEquals(ShipResult.REJECTED, s.shipOnce()); assertEquals(2, o.size())
        status = 204; assertEquals(ShipResult.SENT, s.shipOnce()); assertEquals(0, o.size())
        assertEquals("http://h:1/log", url)
        assertEquals("dev", seen["X-Device"]); assertEquals("st", seen["X-Stream"])
        assertEquals("0", seen["X-Seq-First"]); assertEquals("1", seen["X-Seq-Last"])
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("tok".toByteArray(), "HmacSHA256")); update("1000\ndev\nst\n0\n1\n0\n\n".toByteArray()) }
        assertEquals(mac.doFinal(body).joinToString("") { "%02x".format(it) }, seen["X-Signature"])
        assertEquals(ShipResult.NOTHING, s.shipOnce())
    }

    @Test fun disabledOrUnconfiguredShipsNothing() {
        val o = LogOutbox(); o.append("x")
        assertEquals(ShipResult.DISABLED, LogShipper("", "", "d", o, { true }, Poster { _, _, _ -> 204 }).shipOnce())
        assertEquals(ShipResult.DISABLED, LogShipper("http://h", "t", "d", o, { false }, Poster { _, _, _ -> 204 }).shipOnce())
        assertEquals(0, o.size())
    }
}
