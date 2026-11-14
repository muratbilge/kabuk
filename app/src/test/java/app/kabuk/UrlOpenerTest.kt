package app.kabuk

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kabuk.term.UrlOpener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What `xdg-open` (kabuk-open) hands to the app, and what the app opens from it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UrlOpenerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val oauth = "http://127.0.0.1:53682/auth?state=s3cr3t"

    @Test fun onlyHttpAndHttpsWithAHost() {
        assertEquals(oauth, UrlOpener.accept(oauth))
        assertEquals("https://example.com", UrlOpener.accept("  https://example.com\n"))
        assertEquals("HTTPS://Example.com/x", UrlOpener.accept("HTTPS://Example.com/x"))
        listOf(
            "file:///data/data/app.kabuk/files/home/.config/rclone/rclone.conf", "intent://x#Intent;end",
            "javascript:alert(1)", "content://x/y", "market://details?id=x", "https://", "http:///path",
            "https://a.com/x y", "https://a.com/\u0007", "ftp://example.com", "", "https://a.com/" + "x".repeat(9000),
        ).forEach { assertNull(it, UrlOpener.accept(it)) }
    }

    @Test fun originHasNoPathQueryOrUserInfo() {
        assertEquals("http://127.0.0.1:53682", UrlOpener.origin(oauth))
        assertEquals("https://my.pcloud.com", UrlOpener.origin("HTTPS://user:pw@my.pcloud.com/oauth2/authorize?code=x"))
    }

    @Test fun urlFileBecomesAViewIntentOnlyForHttp() {
        val opened = mutableListOf<String>()
        val good = tmp.newFile("1-1.url").apply { writeText(oauth) }
        UrlOpener.handle(good) { opened += it }
        val bad = tmp.newFile("2-1.url").apply { writeText("file:///etc/hosts") }
        UrlOpener.handle(bad) { opened += it }
        assertEquals(listOf(oauth), opened)
        assertFalse("files are removed once read", good.exists() || bad.exists())

        val i = UrlOpener.intentFor(oauth)
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(oauth, i.dataString)
        assertTrue(i.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
