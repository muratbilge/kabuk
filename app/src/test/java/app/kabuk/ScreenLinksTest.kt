package app.kabuk

import app.kabuk.term.ScreenLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The "Open link" chip: never for pkg/apt output, only URLs at the bottom of a quiet screen. */
class ScreenLinksTest {
    @get:Rule val tmp = TemporaryFolder()

    /** [lines] as the terminal shows them: long lines soft-wrap at [width], the screen has [rows] rows. */
    private fun pick(lines: List<String>, width: Int = 80, rows: Int = 24, skip: Set<String> = emptySet()): String? {
        val screen = mutableListOf<Pair<String, Boolean>>()
        for (l in lines) {
            var rest = l
            while (rest.length > width) { screen += rest.substring(0, width) to true; rest = rest.substring(width) }
            screen += rest to false
        }
        val shown = screen.takeLast(rows)
        val padded = shown + List((rows - shown.size).coerceAtLeast(0)) { "" to false }
        return ScreenLinks.pick(rows, width, { it in skip }) { padded[it].first.padEnd(width) to padded[it].second }
    }

    /** The first `pkg update` of 0.8.1 on the phone (format from Termux's pkg and apt; mirror from Murat's log). */
    private val firstUpdate = listOf(
        "Kabuk: first start, fetching the package lists (pkg update)...",
        "Testing the available mirrors:",
        "[*] (1) https://ftp.agdsn.de/termux/termux-main: ok",
        "[*] (1) https://ftp.fau.de/termux/termux-main: ok",
        "[*] (10) https://ro.mirror.flokinet.net/termux/termux-main: ok",
        "[*] (1) https://mirror.mwt.me/termux/main: bad",
        "Picking mirror: (5) /data/data/app.kabuk/files/usr/etc/termux/chosen_mirrors/ro.mirror.flokinet.net",
        "Get:1 https://ro.mirror.flokinet.net/termux/termux-main stable InRelease [14.0 kB]",
        "Get:2 https://ro.mirror.flokinet.net/termux/termux-main stable/main x86_64 Packages [512 kB]",
        "Hit:3 https://packages-cf.termux.dev/apt/termux-main stable InRelease",
        "Ign:4 https://ftp.fau.de/termux/termux-main stable/main all Packages",
        "Err:5 https://ftp.agdsn.de/termux/termux-main stable InRelease",
        "Fetched 526 kB in 1s (430 kB/s)",
        "Reading package lists... Done",
        "Building dependency tree... Done",
        "Reading state information... Done",
        "All packages are up to date.",
        "Kabuk: ready. Install packages with: pkg install <name>",
        "~ \$ ",
    )

    @Test fun neverForThePackageManager() {
        assertNull(pick(firstUpdate))
        // narrow phone screen: every URL wraps over two rows
        assertNull(pick(firstUpdate, width = 40))
        // while the mirror test is still running (the [*] lines at the very bottom)
        assertNull(pick(firstUpdate.take(6)))
        // apt's warnings
        assertNull(pick(listOf("W: Failed to fetch https://ftp.fau.de/termux/termux-main/dists/stable/InRelease  Could not resolve host", "~ \$ ")))
    }

    @Test fun mirrorAndTermuxRepoHostsAreSkippedOnAnyLine() {
        val lines = listOf("see https://ro.mirror.flokinet.net/termux/termux-main", "~ \$ ")
        assertEquals("https://ro.mirror.flokinet.net/termux/termux-main", pick(lines))
        assertNull(pick(lines, skip = setOf("ro.mirror.flokinet.net")))
        assertNull(pick(listOf("deb https://packages.termux.dev/apt/termux-main stable main", "~ \$ ")))
    }

    @Test fun rcloneAuthorizeIsOffered() {
        val url = "http://127.0.0.1:53682/auth?state=Qx9wZ2k3cGFzc3dvcmQ"
        val lines = listOf(
            "~ \$ rclone authorize \"drive\"",
            "2026/11/14 20:00:00 NOTICE: Make sure your Redirect URL is set to \"http://127.0.0.1:53682/\" in your custom config.",
            "2026/11/14 20:00:00 NOTICE: If your browser doesn't open automatically go to the following link: $url",
            "2026/11/14 20:00:00 NOTICE: Log in and authorize rclone for access",
            "2026/11/14 20:00:00 NOTICE: Waiting for code...",
        )
        assertEquals(url, pick(lines))
        assertEquals(url, pick(lines, width = 42))
    }

    @Test fun onlyInTheBottomRows() {
        val lines = listOf("open https://example.com/x") + List(8) { "line $it" }
        assertNull(pick(lines))
        assertEquals("https://example.com/x", pick(listOf("open https://example.com/x") + List(7) { "line $it" }))
        // the bottom is the last non-blank row, not the screen's last row
        assertEquals("https://example.com/x", pick(listOf("open https://example.com/x", "~ \$ "), rows = 40))
    }

    @Test fun mirrorHostsFromTheConfig() {
        val usr = tmp.newFolder("usr")
        File(usr, "etc/apt").mkdirs()
        File(usr, "etc/apt/sources.list").writeText("deb https://packages-cf.termux.dev/apt/termux-main/ stable main\n")
        File(usr, "etc/termux/mirrors/europe").mkdirs()
        File(usr, "etc/termux/mirrors/europe/ro.mirror.flokinet.net").writeText(
            "WEIGHT=10\nMAIN=\"https://ro.mirror.flokinet.net/termux/termux-main\"\nROOT=\"https://ro.mirror.flokinet.net/termux/termux-root\"\n")
        File(usr, "etc/termux/mirrors/default").writeText("MAIN=\"https://packages-cf.termux.dev/apt/termux-main\"\n")
        assertEquals(setOf("packages-cf.termux.dev", "ro.mirror.flokinet.net"), ScreenLinks.mirrorHosts(usr))
    }

    @Test fun chipWaitsForAQuietScreen() {
        val g = ScreenLinks.ChipGate(1500)
        val u = "https://example.com/a"
        assertNull(g.update(0, 1, u))
        assertNull("still printing", g.update(500, 2, u))
        assertNull(g.update(1000, 2, u))
        assertNull(g.update(1900, 2, u))
        assertEquals("quiet 1.5 s", u, g.update(2000, 2, u))
        assertEquals("a shown link stays while the screen changes", u, g.update(2100, 3, u))
        assertNull("a new link waits for quiet again", g.update(2200, 4, "https://example.com/b"))
        assertEquals("https://example.com/b", g.update(3800, 4, "https://example.com/b"))
        assertNull("gone from the screen", g.update(3900, 4, null))
    }

    @Test fun packageManagerLineDetection() {
        assertTrue(ScreenLinks.isPackageManagerLine("[*] (10) https://ro.mirror.flokinet.net/termux/termux-main: ok"))
        assertTrue(ScreenLinks.isTermuxRepoHost("packages-cf.termux.dev"))
        assertEquals("ro.mirror.flokinet.net", ScreenLinks.host("https://user@ro.mirror.flokinet.net:443/x"))
    }
}
