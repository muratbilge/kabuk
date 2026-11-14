package app.kabuk

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kabuk.term.KabukCommands
import app.kabuk.term.KabukCommands.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Termux app's commands as Kabuk understands them (am, termux-open, wake lock, reload settings). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class KabukCommandsTest {
    private val home = "/data/data/app.kabuk/files/home"
    private fun am(vararg a: String) = KabukCommands.parse("am", a.toList(), home)

    @Test fun requestFileFormat() {
        val bytes = "kabuk-req 1\u0000/x/y\u0000am\u0000start\u0000-a\u0000a b\u0000".toByteArray()
        assertEquals(KabukCommands.Request("/x/y", "am", listOf("start", "-a", "a b"), keepAlive = false), KabukCommands.decode(bytes))
        assertEquals(KabukCommands.Request("/", "termux-wake-lock", emptyList(), keepAlive = false), KabukCommands.decode("kabuk-req 1\u0000/\u0000termux-wake-lock\u0000".toByteArray()))
        // kabuk-open 4: the same request, and it understands keep-alives
        assertEquals(KabukCommands.Request("/", "am", emptyList(), keepAlive = true), KabukCommands.decode("kabuk-req 2\u0000/\u0000am\u0000".toByteArray()))
        assertNull(KabukCommands.decode("kabuk-req 3\u0000/\u0000am\u0000".toByteArray()))
        assertNull(KabukCommands.decode("kabuk-req 1\u0000/\u0000am".toByteArray()))
        assertEquals(1234, KabukCommands.pidOf("1731620000000-1234"))
        assertNull(KabukCommands.pidOf("garbage"))
    }

    /** What termux-tools' own scripts send (termux-wake-lock, termux-open, termux-reload-settings, termux-open-url). */
    @Test fun termuxToolsScriptsMapToKabuk() {
        assertEquals(Action.WakeLock(true), am("startservice", "--user", "0", "-a", "com.termux.service_wake_lock", "com.termux/com.termux.app.TermuxService"))
        assertEquals(Action.WakeLock(false), am("startservice", "--user", "0", "-a", "com.termux.service_wake_unlock", "com.termux/com.termux.app.TermuxService"))
        assertEquals(Action.ReloadSettings, am("broadcast", "--user", "0", "-a", "com.termux.app.reload_style", "com.termux"))
        assertEquals(
            Action.Open("$home/notes.txt", send = true, chooser = true, type = "text/plain"),
            am("broadcast", "--user", "0", "-a", "android.intent.action.SEND", "-n", "com.termux/com.termux.app.TermuxOpenReceiver",
                "--ez", "chooser", "true", "--es", "content-type", "text/plain", "-d", "notes.txt"),
        )
        assertEquals(Action.Open("https://example.com/a?b=c"), am("start", "--user", "0", "-a", "android.intent.action.VIEW", "-d", "https://example.com/a?b=c"))
    }

    @Test fun plainIntents() {
        val a = am("start", "-a", "android.intent.action.VIEW", "-d", "geo:0,0?q=Berlin", "-t", "x/y", "-c", "android.intent.category.BROWSABLE",
            "-n", "com.example/.Main", "-f", "0x10000000", "--es", "k", "v", "--ei", "n", "3", "--ez", "b", "false")
        a as Action.Start
        val s = a.intent
        assertEquals("com.example", s.pkg); assertEquals("com.example.Main", s.cls)
        assertEquals(0x10000000, s.flags)
        assertEquals(listOf(KabukCommands.Extra("k", "v"), KabukCommands.Extra("n", 3), KabukCommands.Extra("b", false)), s.extras)
        val i = KabukCommands.intent(s)
        assertEquals("geo", i.data!!.scheme); assertEquals("x/y", i.type); assertEquals(3, i.getIntExtra("n", 0))
        assertTrue(i.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals("Intent { act=android.intent.action.VIEW dat=geo:… typ=x/y cmp=com.example/com.example.Main " +
            "cat=[android.intent.category.BROWSABLE] (3 extras) }", KabukCommands.describe(s))
        val b = am("broadcast", "-a", "com.example.PING") as Action.Broadcast
        assertEquals("com.example.PING", b.intent.action)
    }

    @Test fun unsupportedGetsAClearError() {
        for (args in listOf(
            arrayOf("force-stop", "com.example"), arrayOf("start", "-S", "-a", "x"), arrayOf("startservice", "-n", "com.example/.Svc"),
            arrayOf("start", "-n", "com.termux/.app.TermuxActivity"), arrayOf("broadcast", "-a", "x", "-n", "com.termux.api/.Recv"),
            arrayOf("start", "--user", "10", "-a", "x"), arrayOf("start", "-a"),
        )) {
            val r = am(*args)
            assertTrue(args.joinToString(" ") + " → $r", r is Action.Reply && r.code != 0)
        }
        assertTrue((am("force-stop", "x") as Action.Reply).text.contains("not supported in Kabuk"))
        assertTrue((am("start", "-n", "com.termux.api/.X") as Action.Reply).text.contains("Termux:API"))
        assertEquals(0, (am("--help") as Action.Reply).code)
    }

    @Test fun termuxOpen() {
        val p = { a: List<String> -> KabukCommands.parse("termux-open", a, "$home/dir") }
        assertEquals(Action.Open("$home/dir/a b.pdf"), p(listOf("a b.pdf")))
        assertEquals(Action.Open("$home/x.png", send = true), p(listOf("--send", "../x.png")))
        assertEquals(Action.Open("/sdcard/Download/f.zip", type = "application/zip"), p(listOf("--content-type", "application/zip", "/sdcard/Download/f.zip")))
        assertEquals(Action.Open("https://example.com"), p(listOf("https://example.com")))
        assertEquals(Action.Open("mailto:a@b.c", chooser = true), p(listOf("--chooser", "mailto:a@b.c")))
        assertEquals(Action.Open("$home/dir/-dash"), p(listOf("--", "-dash")))
        assertEquals(1, (p(emptyList()) as Action.Reply).code)
        assertEquals(1, (p(listOf("--bogus", "f")) as Action.Reply).code)
        assertEquals(0, (p(listOf("--help")) as Action.Reply).code)
    }

    @Test fun wakeLockAndReloadTakeNoArguments() {
        assertEquals(Action.WakeLock(true), KabukCommands.parse("termux-wake-lock", emptyList(), home))
        assertEquals(Action.WakeLock(false), KabukCommands.parse("termux-wake-unlock", emptyList(), home))
        assertTrue(KabukCommands.parse("termux-wake-lock", listOf("x"), home) is Action.Reply)
        assertEquals(Action.ReloadSettings, KabukCommands.parse("termux-reload-settings", emptyList(), home))
    }

    @Test fun logSafeUris() {
        assertEquals("https://host.example…", KabukCommands.safeUri("https://user:pw@host.example/p?code=secret"))
        assertEquals("mailto:…", KabukCommands.safeUri("mailto:someone@example.com"))
        assertEquals("…", KabukCommands.safeUri("/not/a/uri"))
    }
}
