package app.kabuk.term

import java.io.File

/**
 * Which URL the Phone tab's "Open link" chip offers (pure, unit-tested in ScreenLinksTest). A program that wants a
 * browser (rclone authorize) prints its URL and then waits: so only a URL in the bottom rows of the output counts,
 * and only once the screen has been quiet a moment ([ChipGate]). Package-manager output is never offered: pkg/apt
 * print mirror URLs by the dozen (`[*] (1) https://mirror…: ok`, `Get:1 https://…`), and 0.8.1 offered one of
 * them on the phone during the first `pkg update`.
 */
object ScreenLinks {
    /** the URL must be within this many rows above the last non-blank row */
    const val BOTTOM_ROWS = 8
    /** quiet this long before a new link is offered */
    const val QUIET_MS = 1500L

    /** A logical line with one of these is package-manager output (apt, pkg, dpkg). */
    private val MARKERS = listOf(
        "Get:", "Hit:", "Ign:", "Err:", "Picking mirror", "Mirror", "Fetched", "Reading package lists",
        "Failed to fetch", "Release file", "InRelease",
    )
    /** pkg's mirror test lines: `[*] (1) https://host/termux/termux-main: ok` */
    private val MIRROR_TEST = Regex("""^\s*\[\*]\s*(\(\d+\)\s*)?https?://""")
    private val SCHEME = Regex("https?://", RegexOption.IGNORE_CASE)
    private val HOST = Regex("""^https?://(?:[^/?#@\s]*@)?([^/?#:\s]+)""", RegexOption.IGNORE_CASE)

    fun host(url: String): String? = HOST.find(url)?.groupValues?.get(1)?.lowercase()

    fun isPackageManagerLine(text: String) = MIRROR_TEST.containsMatchIn(text) || MARKERS.any { it in text }

    /** packages.termux.dev, packages-cf.termux.dev, … are Termux's own repository hosts */
    fun isTermuxRepoHost(host: String) = host.endsWith(".termux.dev") && host.startsWith("packages")

    /**
     * The lowest acceptable http(s) URL among the bottom [BOTTOM_ROWS] rows above the last non-blank one of a
     * [rows] x [width] screen; URLs on package-manager lines or of [skipHost] hosts are passed over.
     */
    fun pick(rows: Int, width: Int, skipHost: (String) -> Boolean, line: (Int) -> Pair<String, Boolean>): String? {
        var bottom = rows - 1
        while (bottom >= 0 && line(bottom).first.isBlank()) bottom--
        if (bottom < 0) return null
        val top = (bottom - BOTTOM_ROWS + 1).coerceAtLeast(0)
        for (row in bottom downTo top) {
            val hits = SCHEME.findAll(line(row).first).toList()
            for (h in hits.asReversed()) {
                val url = linkIn(row, h.range.first, 0, width - 1, top, bottom, line) ?: continue
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) continue
                val logical = wrappedRows(row, 0, width - 1, top, bottom, line).joinToString("") { line(it).first.trimEnd() }
                if (isPackageManagerLine(logical)) continue
                val host = host(url) ?: continue
                if (isTermuxRepoHost(host) || skipHost(host)) continue
                return url
            }
        }
        return null
    }

    /** characters that can continue a URL (so `https://a.b` is not "in" `https://a.b/c`) */
    private val URL_CHAR = Regex("""[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]""")

    /**
     * True when [url] appears as a whole in [before]: the text a session printed before its first input (the login
     * motd with https://termux.dev and the wiki/community links, the first `pkg update`). The chip never offers those.
     * A URL followed by sentence punctuation (`https://termux.dev.`) still counts.
     */
    fun printedBefore(before: String, url: String): Boolean {
        if (url.isEmpty()) return false
        var i = before.indexOf(url)
        while (i >= 0) {
            val next = before.getOrNull(i + url.length)
            val after = before.getOrNull(i + url.length + 1)
            val ends = next == null || !URL_CHAR.matches(next.toString()) ||
                (next in ".,;:!?)" && (after == null || after.isWhitespace()))
            if (ends) return true
            i = before.indexOf(url, i + 1)
        }
        return false
    }

    /** Hosts of the configured package mirrors: apt's sources and pkg's mirror lists under [prefix]/etc. */
    fun mirrorHosts(prefix: File): Set<String> {
        val files = mutableListOf<File>()
        File(prefix, "etc/apt/sources.list").takeIf { it.isFile }?.let(files::add)
        File(prefix, "etc/apt/sources.list.d").listFiles()?.filter { it.isFile }?.let(files::addAll)
        File(prefix, "etc/termux/mirrors").walkTopDown().maxDepth(2).filter { it.isFile }.forEach(files::add)
        val url = Regex("""https?://[^\s"']+""", RegexOption.IGNORE_CASE)
        return files.flatMap { f ->
            runCatching { if (f.length() > 65536) emptyList() else url.findAll(f.readText()).mapNotNull { host(it.value) }.toList() }
                .getOrDefault(emptyList())
        }.toSet()
    }

    /**
     * Shows a link only after the screen was quiet for [QUIET_MS]; a link already shown stays while it's still the
     * one found (no flicker while the program redraws). Fed every poll with the screen's signature and the candidate.
     */
    class ChipGate(private val quietMs: Long = QUIET_MS) {
        private var signature: Int? = null
        private var changedAt = 0L
        var shown: String? = null; private set

        fun update(now: Long, screenSignature: Int, candidate: String?): String? {
            if (screenSignature != signature) { signature = screenSignature; changedAt = now }
            val quiet = now - changedAt >= quietMs
            shown = when {
                candidate == null -> null
                candidate == shown -> candidate
                quiet -> candidate
                else -> null
            }
            return shown
        }
    }
}
