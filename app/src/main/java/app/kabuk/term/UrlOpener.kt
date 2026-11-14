package app.kabuk.term

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.FileObserver
import app.kabuk.log.KLog
import java.io.File
import java.util.Collections

/**
 * Opens URLs for the Phone tab's programs. `xdg-open`, `open` and `termux-open-url` are links to libkabuk-open.so
 * (scripts/tools/kabuk-open.c), which writes the URL to `filesDir/open/<time>-<pid>.url`; this watches that dir and
 * starts the browser (ACTION_VIEW, http/https only). That is how `rclone authorize` reaches the phone's browser.
 * URLs carry OAuth state/codes: only scheme + host are ever logged.
 */
object UrlOpener {
    private const val TAG = "OPEN"
    private const val MAX_URL = 8192
    private var observer: FileObserver? = null
    /** URLs a program already opened: the "Open link" chip doesn't offer them again (bounded, newest last) */
    private val handled: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    /** Starts watching [dir] (once per process); files left from an earlier process are stale and removed unopened. */
    @Synchronized fun start(dir: File) {
        if (observer != null) return
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        @Suppress("DEPRECATION") // the File constructor is API 29+, minSdk is 26
        observer = object : FileObserver(dir.absolutePath, MOVED_TO or CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null || !path.endsWith(".url")) return
                handle(File(dir, path)) { url -> TabManager.main.post { open(url, fromProgram = true) } }
            }
        }.also { it.startWatching() }
    }

    /** One file from kabuk-open: read, delete, and pass the URL on only when [accept] allows it. */
    internal fun handle(f: File, open: (String) -> Unit) {
        val text = runCatching { if (f.length() > MAX_URL + 1) "" else f.readText() }.getOrNull() ?: return
        f.delete()
        val url = accept(text)
        if (url == null) KLog.w(TAG, "refused a non-http(s) URL from a program") else open(url)
    }

    internal fun intentFor(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The URL a program handed over, if it may be opened: http(s) with a host, no spaces or control characters. */
    internal fun accept(text: String): String? {
        val url = text.trim()
        if (url.length > MAX_URL || url.any { it.isWhitespace() || it.isISOControl() }) return null
        val m = HTTP.find(url) ?: return null
        return url.takeIf { m.groupValues[2].substringAfterLast('@').isNotEmpty() }
    }

    /** scheme://host[:port] of an http(s) URL, without user info, path or query: what may be logged or shown. */
    internal fun origin(url: String): String {
        val m = HTTP.find(url) ?: return "?"
        return "${m.groupValues[1].lowercase()}://${m.groupValues[2].substringAfterLast('@')}"
    }

    fun wasHandled(url: String) = url in handled

    private fun remember(url: String) = synchronized(handled) {
        handled.remove(url); handled.add(url)
        while (handled.size > 20) handled.remove(handled.first())
    }

    /**
     * Main thread. Starts the browser when Kabuk is on screen; in the background Android blocks starting an
     * activity, so then a snackbar with "Open" waits for the user to come back.
     */
    fun open(url: String, fromProgram: Boolean = false) {
        remember(url)
        val where = origin(url)
        if (!TabManager.foreground) {
            KLog.i(TAG, "${if (fromProgram) "xdg-open" else "link"} while in the background: $where (offered)")
            TabManager.note("A program wants to open $where", "Open") { open(url) }
            return
        }
        try {
            TabManager.app.startActivity(intentFor(url))
            KLog.i(TAG, "${if (fromProgram) "xdg-open" else "link"}: $where")
        } catch (e: ActivityNotFoundException) {
            KLog.w(TAG, "no browser for $where")
            TabManager.toast("No app on this phone opens $where")
        }
    }

    private val HTTP = Regex("""^(https?)://([^/?#\s]*)""", RegexOption.IGNORE_CASE)
}
