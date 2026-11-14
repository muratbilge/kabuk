package app.kabuk

import android.os.BatteryManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kabuk.term.KabukCommands
import app.kabuk.term.KabukCommands.Action
import app.kabuk.term.TermuxApi
import app.kabuk.term.TermuxApi.Req
import app.kabuk.term.TermuxEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Termux:API's commands as Kabuk understands them: options, stdin, output format. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TermuxApiTest {
    @get:Rule val tmp = TemporaryFolder()
    private val home = "/data/data/app.kabuk/files/home"
    private fun p(name: String, vararg a: String, stdin: String? = null) = KabukCommands.parse(name, a.toList(), home, stdin)
    private fun api(r: Req) = Action.Api(r)
    private fun replyCode(a: Action) = (a as Action.Reply).code

    @Test fun clipboard() {
        assertEquals(api(Req.ClipboardGet), p("termux-clipboard-get"))
        assertEquals(1, replyCode(p("termux-clipboard-get", "x")))
        assertEquals(api(Req.ClipboardSet("hello world")), p("termux-clipboard-set", "hello", "world"))
        // no text argument: the program's stdin, read like cat (also from a terminal)
        assertEquals(Action.NeedStdin(onlyIfPiped = false), p("termux-clipboard-set"))
        assertEquals(api(Req.ClipboardSet("line1\nline2\n")), p("termux-clipboard-set", stdin = "line1\nline2\n"))
        assertEquals(0, replyCode(p("termux-clipboard-set", "-h")))
    }

    @Test fun toast() {
        assertEquals(api(Req.Toast("hi there", short = false, gravity = "middle")), p("termux-toast", "hi", "there"))
        assertEquals(api(Req.Toast("x", short = true, gravity = "top")), p("termux-toast", "-s", "-g", "top", "x"))
        // bundled short options and an attached value, like getopts
        assertEquals(api(Req.Toast("x", short = true, gravity = "bottom")), p("termux-toast", "-sgbottom", "x"))
        // colours are accepted and ignored
        assertEquals(api(Req.Toast("x", short = false, gravity = "middle")), p("termux-toast", "-b", "red", "-c", "#fff", "x"))
        assertEquals(Action.NeedStdin(false), p("termux-toast", "-s"))
        assertEquals(api(Req.Toast("from stdin\n", short = true, gravity = "middle")), p("termux-toast", "-s", stdin = "from stdin\n"))
        // options end at the first operand: "-s" after the text is text
        assertEquals(api(Req.Toast("a -s", short = false, gravity = "middle")), p("termux-toast", "a", "-s"))
        val bad = p("termux-toast", "-x", "a") as Action.Reply
        assertEquals(1, bad.code); assertTrue(bad.text.startsWith("termux-toast: illegal option -x\nUsage: termux-toast"))
        assertEquals(1, replyCode(p("termux-toast", "-g", "left", "a")))
        assertEquals(1, replyCode(p("termux-toast", "-g")))
        assertEquals(0, replyCode(p("termux-toast", "-h")))
    }

    @Test fun vibrateTorchVolume() {
        assertEquals(api(Req.Vibrate(1000, false)), p("termux-vibrate"))
        assertEquals(api(Req.Vibrate(300, true)), p("termux-vibrate", "-d", "300", "-f"))
        assertEquals(1, replyCode(p("termux-vibrate", "-d", "abc")))
        assertEquals(1, replyCode(p("termux-vibrate", "-d", "0")))
        assertEquals(api(Req.Torch(true)), p("termux-torch", "on"))
        assertEquals(api(Req.Torch(false)), p("termux-torch", "off"))
        assertEquals(1, replyCode(p("termux-torch")))
        assertEquals(1, replyCode(p("termux-torch", "maybe")))
        assertEquals(api(Req.Volume(null, null)), p("termux-volume"))
        assertEquals(api(Req.Volume("music", 7)), p("termux-volume", "music", "7"))
        assertEquals(1, replyCode(p("termux-volume", "radio", "7")))
        assertEquals(1, replyCode(p("termux-volume", "music", "-1")))
        assertEquals(1, replyCode(p("termux-volume", "music")))
    }

    @Test fun speak() {
        assertEquals(api(Req.Speak("hello", null, null, null, null, 1f, 1f, 5)), p("termux-tts-speak", "hello"))
        assertEquals(
            api(Req.Speak("guten tag", "com.google.android.tts", "de", "DE", null, 1.2f, 0.8f, 3)),
            p("termux-tts-speak", "-e", "com.google.android.tts", "-l", "de", "-n", "DE", "-p", "1.2", "-r", "0.8", "-s", "music", "guten", "tag"),
        )
        assertEquals(Action.NeedStdin(false), p("termux-tts-speak", "-r", "2"))
        assertEquals(1, replyCode(p("termux-tts-speak", "-s", "LOUD", "x")))
        assertEquals(1, replyCode(p("termux-tts-speak", "-p", "0", "x")))
    }

    @Test fun notification() {
        val n = (p("termux-notification", "-t", "Backup", "-c", "done", "--id", "b1", "--ongoing", "--priority", "high",
            "--sound", "--vibrate", "100,200,100", "--action", "termux-toast tapped",
            "--button1", "Again", "--button1-action", "~/bin/backup.sh", "--button2", "Nothing",
            "--on-delete", "echo gone", "--led-color", "ff0000", "--icon", "event") as Action.Api).req as Req.Notify
        val s = n.n
        assertEquals("b1", s.id); assertEquals("Backup", s.title); assertEquals("done", s.content)
        assertTrue(s.ongoing); assertTrue(s.sound); assertEquals("high", s.priority)
        assertEquals(listOf(100L, 200L, 100L), s.vibrate!!.toList())
        assertEquals("termux-toast tapped", s.action); assertEquals("echo gone", s.onDelete)
        assertEquals(listOf(TermuxApi.Button("Again", "~/bin/backup.sh"), TermuxApi.Button("Nothing", null)), s.buttons)
        // --content=… and -i; content from stdin only when it is piped (a terminal is not read)
        assertEquals("x", ((p("termux-notification", "--content=x", "-i", "7") as Action.Api).req as Req.Notify).n.content)
        assertEquals(Action.NeedStdin(onlyIfPiped = true), p("termux-notification", "-t", "T"))
        assertEquals("piped", ((p("termux-notification", "-t", "T", stdin = "piped\n") as Action.Api).req as Req.Notify).n.content)
        assertEquals("$home/pic.png", ((p("termux-notification", "-c", "x", "--image-path", "pic.png") as Action.Api).req as Req.Notify).n.image)
        assertEquals(1, replyCode(p("termux-notification", "--priority", "urgent", "-c", "x")))
        assertEquals(1, replyCode(p("termux-notification", "--vibrate", "a,b", "-c", "x")))
        assertEquals(1, replyCode(p("termux-notification", "--type", "media", "-c", "x")))
        assertEquals(1, replyCode(p("termux-notification", "--bogus", "-c", "x")))
        assertEquals(1, replyCode(p("termux-notification", "--title")))
        assertEquals(0, replyCode(p("termux-notification", "--help-actions")))
        assertEquals(api(Req.NotifyRemove("b1")), p("termux-notification-remove", "b1"))
        assertEquals(1, replyCode(p("termux-notification-remove")))
    }

    @Test fun share() {
        assertEquals(api(Req.Share("$home/a.pdf", null, "view", null, true, null)), p("termux-share", "a.pdf"))
        assertEquals(api(Req.Share("/sdcard/x.txt", null, "send", "text/plain", false, "T")),
            p("termux-share", "-a", "send", "-c", "text/plain", "-d", "-t", "T", "/sdcard/x.txt"))
        assertEquals(Action.NeedStdin(false), p("termux-share", "-a", "send"))
        assertEquals(api(Req.Share(null, "some text", "send", null, true, null)), p("termux-share", "-a", "send", stdin = "some text"))
        assertEquals(1, replyCode(p("termux-share", "-a", "print", "x")))
        assertEquals(1, replyCode(p("termux-share", "a", "b")))
    }

    @Test fun downloadAndMediaScan() {
        assertEquals(api(Req.Download("https://example.com/f.zip", "T", "D", null)),
            p("termux-download", "-t", "T", "-d", "D", "https://example.com/f.zip"))
        assertEquals(api(Req.Download("https://example.com/f.zip", null, null, "$home/f.zip")),
            p("termux-download", "-p", "f.zip", "https://example.com/f.zip"))
        assertEquals(1, replyCode(p("termux-download", "file:///etc/passwd")))
        assertEquals(1, replyCode(p("termux-download")))
        assertEquals(api(Req.MediaScan(listOf("$home/a.jpg", "/sdcard/DCIM"), recursive = true, verbose = false)),
            p("termux-media-scan", "-r", "a.jpg", "/sdcard/DCIM"))
        assertEquals(1, replyCode(p("termux-media-scan", "-v")))
    }

    @Test fun unsupportedCommandsSaySo() {
        for (name in listOf("termux-sms-send", "termux-camera-photo", "termux-location", "termux-sensor", "termux-dialog",
                "termux-brightness", "termux-notification-list", "termux-saf-ls", "termux-job-scheduler")) {
            val r = p(name, "-h") as Action.Reply
            assertEquals(name, 1, r.code)
            assertTrue(r.text, r.text.startsWith("$name: ") && "is not supported in Kabuk" in r.text)
        }
        // every name is linked in files/kabuk/bin and answered (never "unknown command")
        for (name in TermuxApi.NAMES) assertTrue(name, name in TermuxEnv.OPENER_NAMES)
        for (name in TermuxApi.NAMES) assertTrue(name, (p(name, "--definitely-bad") as? Action.Reply)?.code != 2)
        assertEquals(2, replyCode(p("termux-nonsense")))
    }

    @Test fun batteryJsonLikeTermuxApi() {
        val json = TermuxApi.batteryJson(TermuxApi.BatteryInfo(
            present = true, technology = "Li-ion", health = BatteryManager.BATTERY_HEALTH_GOOD, plugged = 0,
            status = BatteryManager.BATTERY_STATUS_DISCHARGING, temperatureTenths = 263, voltage = 3870, level = 65, scale = 100,
            current = -385000, currentAverage = null, chargeCounter = 2900000, energy = null, cycle = 112,
        ))
        assertEquals("""
            {
              "present": true,
              "technology": "Li-ion",
              "health": "GOOD",
              "plugged": "UNPLUGGED",
              "status": "DISCHARGING",
              "temperature": 26.3,
              "voltage": 3870,
              "current": -385000,
              "current_average": null,
              "percentage": 65,
              "level": 65,
              "scale": 100,
              "charge_counter": 2900000,
              "energy": null,
              "cycle": 112
            }
        """.trimIndent() + "\n", json)
        val charging = TermuxApi.batteryJson(TermuxApi.BatteryInfo(true, null, BatteryManager.BATTERY_HEALTH_OVERHEAT,
            BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_CHARGING, 400, 4200, 50, 200, null, null, null, null, null))
        assertTrue(charging, "\"plugged\": \"PLUGGED_USB\"" in charging && "\"status\": \"CHARGING\"" in charging &&
            "\"health\": \"OVERHEAT\"" in charging && "\"percentage\": 25" in charging && "cycle" !in charging)
    }

    @Test fun wifiJsonLikeTermuxApi() {
        val json = TermuxApi.wifiJson(TermuxApi.WifiState(
            bssid = "02:00:00:00:00:00", frequency = 2447, ip = 0x0f02000a, linkSpeed = 72, mac = "02:00:00:00:00:00",
            networkId = 0, rssi = -50, ssid = null, hidden = false, supplicant = "COMPLETED",
        ))
        assertTrue(json, json.startsWith("{\n  \"bssid\": \"02:00:00:00:00:00\",\n  \"frequency_mhz\": 2447,\n  \"ip\": \"10.0.2.15\","))
        assertTrue(json, "\"ssid\": \"<unknown ssid>\"" in json && "\"supplicant_state\": \"COMPLETED\"\n}\n" in json)
        // Android reports a known SSID in quotes; Termux:API prints it without
        val named = TermuxApi.wifiJson(TermuxApi.WifiState(null, 5180, 0, 0, null, 3, -60, "\"Home\"", true, "COMPLETED"))
        assertTrue(named, "\"ssid\": \"Home\"" in named && "\"ssid_hidden\": true" in named)
        assertTrue(named, "\"bssid\": null" in named)
    }

    @Test fun volumeJsonLikeTermuxApi() {
        assertEquals("""
            [
              {
                "stream": "call",
                "volume": 4,
                "max_volume": 5
              },
              {
                "stream": "music",
                "volume": 0,
                "max_volume": 25
              }
            ]
        """.trimIndent() + "\n", TermuxApi.volumeJson(listOf(Triple("call", 4, 5), Triple("music", 0, 25))))
        assertEquals(listOf("call", "system", "ring", "music", "alarm", "notification"), TermuxApi.STREAMS.keys.toList())
    }

    @Test fun jsonEscapes() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", TermuxApi.quote("a\"b\\c\nd\u0001"))
        assertEquals("{}", TermuxApi.json(emptyList()))
    }

    /** -r walks the tree; links are never followed (a link to /sdcard inside home must not scan all of it). */
    @Test fun mediaScanFilesDoNotFollowLinks() {
        val root = tmp.newFolder("scan")
        File(root, "a.jpg").writeText("x")
        File(root, "sub").mkdirs(); File(root, "sub/b.mp3").writeText("x")
        val outside = tmp.newFolder("outside"); File(outside, "secret.jpg").writeText("x")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "flink.jpg").toPath(), File(outside, "secret.jpg").toPath())
        val flat = TermuxApi.mediaFiles(listOf(root.path), recursive = false).map { File(it).name }.sorted()
        assertEquals(listOf("a.jpg"), flat)
        val deep = TermuxApi.mediaFiles(listOf(root.path), recursive = true).map { File(it).relativeTo(root).path }.sorted()
        assertEquals(listOf("a.jpg", "sub/b.mp3"), deep)
        assertEquals(listOf(File(root, "a.jpg").path), TermuxApi.mediaFiles(listOf(File(root, "a.jpg").path, File(root, "missing").path), false))
    }
}
