package app.kabuk.term

/**
 * Follows DEC synchronized output (mode 2026) through the byte stream, also across chunk boundaries.
 * herdr and Claude Code wrap every redraw in ESC[?2026h ... ESC[?2026l; the screen must not be shown
 * in between, or half-drawn frames make it flicker and jump while scrolling.
 */
class SyncScanner {
    /** inside a redraw that hasn't finished yet */
    var inside = false
        private set
    private var k = 0   // bytes of PREFIX matched so far

    fun feed(b: ByteArray, n: Int = b.size) {
        for (i in 0 until n) {
            val c = b[i].toInt()
            if (k == PREFIX.size) {
                when (c) {
                    'h'.code -> inside = true
                    'l'.code -> inside = false
                }
                k = if (c == 0x1b) 1 else 0
                continue
            }
            k = when {
                c == PREFIX[k].toInt() -> k + 1
                c == 0x1b -> 1
                else -> 0
            }
        }
    }

    fun reset() { inside = false; k = 0 }

    private companion object {
        val PREFIX = "\u001b[?2026".toByteArray()
    }
}
