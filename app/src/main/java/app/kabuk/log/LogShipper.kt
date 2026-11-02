package app.kabuk.log

import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * In-memory queue of JSON log lines waiting for the live-log receiver. Lines carry consecutive sequence numbers; when
 * the queue is full the oldest are dropped (and counted), so a long offline period cannot eat the phone's memory.
 */
class LogOutbox(private val cap: Int = 2000, val stream: String = java.util.UUID.randomUUID().toString().take(8)) {
    class Batch(val stream: String, val first: Long, val last: Long, val lines: List<String>, val dropped: Int)

    private val queue = ArrayDeque<String>()
    private var firstSeq = 0L          // seq of queue.first()
    private var dropped = 0

    @Synchronized fun append(line: String) {
        queue.addLast(line)
        while (queue.size > cap) { queue.removeFirst(); firstSeq++; dropped++ }
    }

    @Synchronized fun size() = queue.size

    @Synchronized fun clear() { firstSeq += queue.size; queue.clear(); dropped = 0 }

    @Synchronized fun peek(max: Int = 200): Batch? {
        if (queue.isEmpty()) return null
        val lines = queue.take(max)
        return Batch(stream, firstSeq, firstSeq + lines.size - 1, lines, dropped)
    }

    /** Removes the lines up to and including [last] after the receiver stored them. */
    @Synchronized fun acknowledge(last: Long, droppedReported: Int) {
        while (queue.isNotEmpty() && firstSeq <= last) { queue.removeFirst(); firstSeq++ }
        dropped = (dropped - droppedReported).coerceAtLeast(0)
    }
}

enum class ShipResult { SENT, NOTHING, DISABLED, REJECTED, OFFLINE }

/** HTTP status, or null when the receiver is unreachable. */
fun interface Poster { fun post(url: String, headers: Map<String, String>, body: ByteArray): Int? }

private class HttpPoster : Poster {
    override fun post(url: String, headers: Map<String, String>, body: ByteArray): Int? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = 8000
            c.setFixedLengthStreamingMode(body.size)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body) }
            c.responseCode
        } finally {
            c.disconnect()
        }
    } catch (_: java.io.IOException) {
        null
    }
}

/**
 * Sends one batch per [shipOnce] with the receiver's protocol (scripts/logsink/logsink.py): HMAC-SHA256 over
 * "<timestamp>\n<body>" with the shared token (the token itself is never sent), X-Stream/X-Seq-* so a resent batch is
 * stored once. Lines leave the queue only after a 204.
 */
class LogShipper(
    private val url: String,
    private val token: String,
    private val device: String,
    private val outbox: LogOutbox,
    private val enabled: () -> Boolean,
    private val poster: Poster = HttpPoster(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun shipOnce(): ShipResult {
        if (!enabled()) { outbox.clear(); return ShipResult.DISABLED }
        if (url.isBlank() || token.isBlank()) return ShipResult.DISABLED
        val batch = outbox.peek() ?: return ShipResult.NOTHING
        val body = batch.lines.joinToString("\n").toByteArray(Charsets.UTF_8)
        val ts = (now() / 1000).toString()
        val headers = mapOf(
            "X-Timestamp" to ts, "X-Signature" to hmac(ts, body), "X-Device" to device,
            "X-Stream" to batch.stream, "X-Seq-First" to batch.first.toString(), "X-Seq-Last" to batch.last.toString(),
            "X-Dropped" to batch.dropped.toString(),
        )
        return when (poster.post(url.trimEnd('/') + "/log", headers, body)) {
            204 -> ShipResult.SENT.also { outbox.acknowledge(batch.last, batch.dropped) }
            null -> ShipResult.OFFLINE
            else -> ShipResult.REJECTED
        }
    }

    private fun hmac(ts: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(token.toByteArray(), "HmacSHA256")) }
        mac.update(ts.toByteArray()); mac.update('\n'.code.toByte())
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }
}
