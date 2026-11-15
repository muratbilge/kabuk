package app.kabuk

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.kabuk.term.KabukCommands
import app.kabuk.term.KabukCommands.Action
import app.kabuk.term.KabukCommands.Answer
import app.kabuk.term.TermuxApi
import app.kabuk.term.TermuxCamera
import app.kabuk.term.TermuxDevices
import app.kabuk.term.TermuxDevices.DialogResult
import app.kabuk.term.TermuxDevices.DialogSpec
import app.kabuk.term.TermuxDevices.Req
import app.kabuk.term.TermuxMedia
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.TimeZone

/** B10: Termux:API commands with permissions, dialogs and streams — options, output format, the stream protocol. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TermuxDevicesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val home = "/data/data/app.kabuk/files/home"
    private fun p(name: String, vararg a: String) = KabukCommands.parse(name, a.toList(), home)
    private fun dev(r: Req) = Action.Device(r)
    private fun reply(a: Action) = a as Action.Reply

    @Test fun namesAreSupportedNow() {
        for (n in TermuxDevices.NAMES) {
            assertTrue(n, n in TermuxApi.SUPPORTED)
            assertFalse(n, n in TermuxApi.UNSUPPORTED)
            assertTrue(n, n in TermuxApi.NAMES)
        }
        // the set of linked names did not change (no BinDir relayout needed)
        assertEquals(TermuxApi.NAMES.size, TermuxApi.NAMES.toSet().size)
        assertTrue("termux-saf-ls" in TermuxApi.UNSUPPORTED && "termux-sms-send" in TermuxApi.UNSUPPORTED)
    }

    @Test fun location() {
        assertEquals(dev(Req.Location("gps", "once", 5000)), p("termux-location"))
        assertEquals(dev(Req.Location("network", "updates", 2000)), p("termux-location", "-p", "network", "-r", "updates", "-d", "2000"))
        assertEquals(dev(Req.Location("passive", "last", 5000)), p("termux-location", "-ppassive", "-rlast"))
        assertEquals("termux-location: Unsupported provider: 'wifi'\n", reply(p("termux-location", "-p", "wifi")).text)
        assertEquals("termux-location: Unsupported request: 'always'\n", reply(p("termux-location", "-r", "always")).text)
        assertEquals(1, reply(p("termux-location", "x")).code)
        assertEquals(1, reply(p("termux-location", "-d", "soon")).code)
        assertEquals(1, reply(p("termux-location", "-x")).code)
        assertEquals(0, reply(p("termux-location", "-h")).code)
    }

    @Test fun locationJsonLikeTermuxApi() {
        val f = TermuxDevices.Fix(52.52, 13.405, 34.0, 12.5f, 3.0f, 0f, 0f, 1234, "gps")
        val pretty = TermuxDevices.locationJson(f)
        assertEquals("""{
  "latitude": 52.52,
  "longitude": 13.405,
  "altitude": 34.0,
  "accuracy": 12.5,
  "vertical_accuracy": 3.0,
  "bearing": 0.0,
  "speed": 0.0,
  "elapsedMs": 1234,
  "provider": "gps"
}""", pretty)
        // -r updates: one object per line
        val line = TermuxDevices.locationJson(f, compact = true)
        assertFalse(line.contains('\n'))
        assertEquals(52.52, JSONObject(line).getDouble("latitude"), 0.0)
        assertEquals("Failed to get location", JSONObject(TermuxDevices.locationJson(null)).getString("API_ERROR"))
    }

    @Test fun dialogParsing() {
        assertEquals(dev(Req.Dialog(DialogSpec("text"))), p("termux-dialog"))
        assertEquals(dev(Req.Dialog(DialogSpec("text", title = "Name", hint = "your name", password = true))),
            p("termux-dialog", "text", "-t", "Name", "-i", "your name", "-p"))
        // options without a widget: text
        assertEquals(dev(Req.Dialog(DialogSpec("text", multiLine = true))), p("termux-dialog", "-m"))
        assertEquals(dev(Req.Dialog(DialogSpec("confirm", hint = "Sure?"))), p("termux-dialog", "confirm", "-i", "Sure?"))
        assertEquals(dev(Req.Dialog(DialogSpec("radio", values = listOf("a", "b,c", "d")))), p("termux-dialog", "radio", "-v", "a, b\\,c ,d"))
        assertEquals(dev(Req.Dialog(DialogSpec("counter", range = listOf(1, 10, 3)))), p("termux-dialog", "counter", "-r", "1,10,3"))
        assertEquals(dev(Req.Dialog(DialogSpec("date", dateFormat = "dd-MM-yyyy"))), p("termux-dialog", "date", "-d", "dd-MM-yyyy"))
        for (w in listOf("checkbox", "sheet", "spinner", "time"))
            assertTrue(w, p("termux-dialog", w, *(if (w == "time") emptyArray() else arrayOf("-v", "x,y"))) is Action.Device)
        // the script's checks
        assertTrue(reply(p("termux-dialog", "text", "-m", "-n")).text.startsWith("ERROR: Invalid option(s) detected for 'text'"))
        assertTrue(reply(p("termux-dialog", "date", "-i", "x")).text.startsWith("ERROR: Invalid option(s) detected for 'date'"))
        assertTrue(reply(p("termux-dialog", "radio")).text.startsWith("ERROR: 'radio' must be called with"))
        assertTrue(reply(p("termux-dialog", "bogus")).text.startsWith("ERROR: Illegal argument bogus"))
        assertTrue(reply(p("termux-dialog", "text", "extra")).text.startsWith("ERROR: Too many arguments!"))
        assertEquals(1, reply(p("termux-dialog", "counter", "-r", "1,2")).code)
        assertTrue("not supported in Kabuk" in reply(p("termux-dialog", "speech")).text)
        assertTrue(reply(p("termux-dialog", "-l")).text.contains("spinner - Pick a single value from a dropdown spinner"))
        assertEquals(0, reply(p("termux-dialog", "-h")).code)
    }

    @Test fun dialogJsonLikeTermuxApi() {
        assertEquals("{\n  \"code\": -1,\n  \"text\": \"yes\"\n}\n", TermuxDevices.dialogJson(DialogResult(-1, "yes")))
        val cb = JSONObject(TermuxDevices.dialogJson(DialogResult(-1, "[a, c]", values = listOf(0 to "a", 2 to "c"))))
        assertEquals("[a, c]", cb.getString("text"))
        assertEquals(2, cb.getJSONArray("values").getJSONObject(1).getInt("index"))
        assertFalse(cb.has("index"))
        val sheet = JSONObject(TermuxDevices.dialogJson(DialogResult(0, "b", index = 1)))
        assertEquals(0, sheet.getInt("code")); assertEquals(1, sheet.getInt("index"))
        assertEquals("x", JSONObject(TermuxDevices.dialogJson(DialogResult(-2, "", error = "x"))).getString("error"))
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("15-11-2026", TermuxDevices.dateText(2026, 10, 15, "dd-MM-yyyy", utc))
        assertTrue(TermuxDevices.dateText(2026, 10, 15, null).startsWith("Sun Nov 15 00:00:00"))
        assertEquals("07:05", TermuxDevices.timeText(7, 5))
    }

    @Test fun cameraAndStorage() {
        assertEquals(dev(Req.CameraInfo), p("termux-camera-info"))
        assertEquals(dev(Req.CameraPhoto(null, "$home/p.jpg")), p("termux-camera-photo", "p.jpg"))
        assertEquals(dev(Req.CameraPhoto("1", "/sdcard/DCIM/x.jpg")), p("termux-camera-photo", "-c", "1", "/sdcard/DCIM/x.jpg"))
        assertTrue(reply(p("termux-camera-photo")).text.startsWith("termux-camera-photo: missing file argument"))
        assertTrue(reply(p("termux-camera-photo", "a", "b")).text.startsWith("termux-camera-photo: too many arguments"))
        assertEquals(dev(Req.StorageGet("$home/in.pdf")), p("termux-storage-get", "in.pdf"))
        assertTrue(reply(p("termux-storage-get")).text.startsWith("termux-storage-get: no output file specified"))
        val json = TermuxCamera.infoJson(listOf(TermuxCamera.CameraDesc("0", 1, listOf(640 to 480), listOf(4.38f), listOf(1),
            3.6f to 2.7f, listOf(0))))
        val cam = JSONArray(json).getJSONObject(0)
        assertEquals("back", cam.getString("facing"))
        assertEquals(480, cam.getJSONArray("jpeg_output_sizes").getJSONObject(0).getInt("height"))
        assertEquals("CONTROL_AE_MODE_ON", cam.getJSONArray("auto_exposure_modes").getString(0))
        assertEquals("backward_compatible", cam.getJSONArray("capabilities").getString(0))
        assertEquals(90, TermuxCamera.jpegOrientation(90, 0, front = false))
        assertEquals(0, TermuxCamera.jpegOrientation(90, 90, front = false))
        assertEquals(180, TermuxCamera.jpegOrientation(270, 270, front = true))
    }

    @Test fun sensors() {
        assertTrue(reply(p("termux-sensor")).text.startsWith("No arguments supplied!"))
        assertEquals(dev(Req.SensorList), p("termux-sensor", "-l"))
        assertEquals(dev(Req.Sensors(listOf("accel", "light"), false, 500, 3)), p("termux-sensor", "-s", "accel,light", "-d", "500", "-n", "3"))
        assertEquals(dev(Req.Sensors(emptyList(), true, 1000, 0)), p("termux-sensor", "-a"))
        assertEquals(Action.Reply(0, "Sensor cleanup unnecessary\n"), p("termux-sensor", "-c"))
        assertTrue(reply(p("termux-sensor", "-c", "-l")).text.startsWith("ERROR: No other options should be specified with -c cleanup!"))
        assertTrue(reply(p("termux-sensor", "-d", "100")).text.startsWith("ERROR: Use -s <sensors> or -a option when using -d delay!"))
        assertTrue(reply(p("termux-sensor", "-a", "-s", "x")).text.startsWith("ERROR: Listed sensors will be ignored"))
        assertTrue(reply(p("termux-sensor", "-a", "-d", "fast")).text.startsWith("ERROR: Illegal argument! -d delay"))
        // shortest name containing the part, case ignored
        val avail = listOf("BMI160 Accelerometer", "Accelerometer", "Light Sensor", "Linear Acceleration")
        assertEquals(listOf("Accelerometer", "Light Sensor"), TermuxDevices.pickSensors(avail, { it }, listOf("accel", "LIGHT"), false))
        assertEquals(avail.sorted(), TermuxDevices.pickSensors(avail, { it }, emptyList(), true))
        assertEquals(emptyList<String>(), TermuxDevices.pickSensors(avail, { it }, listOf("gyro"), false))
        val out = TermuxDevices.sensorJson(linkedMapOf("Accelerometer" to floatArrayOf(0f, 9.81f, 1f)))
        assertEquals("{\n  \"Accelerometer\": {\n    \"values\": [\n      0,\n      9.8100004196167,\n      1\n    ]\n  }\n}\n", out)
    }

    @Test fun microphoneAndPlayer() {
        assertEquals(0, reply(p("termux-microphone-record")).code)
        assertEquals(dev(Req.MicRecord(null, null, null, null, null, null)), p("termux-microphone-record", "-d"))
        assertEquals(dev(Req.MicRecord("$home/a.m4a", 30_000, "opus", 64_000, 48_000, 1)),
            p("termux-microphone-record", "-f", "a.m4a", "-l", "30", "-e", "opus", "-b", "64", "-r", "48000", "-c", "1"))
        assertEquals(dev(Req.MicInfo), p("termux-microphone-record", "-i"))
        assertEquals(dev(Req.MicQuit), p("termux-microphone-record", "-q"))
        assertTrue(reply(p("termux-microphone-record", "-q", "-d")).text.startsWith("ERROR: No other options should be specified with -q!"))
        assertEquals(1, reply(p("termux-microphone-record", "-e", "mp3")).code)
        assertEquals(1, reply(p("termux-microphone-record", "-l", "ten")).code)
        assertEquals(15 * 60_000, TermuxMedia.limitMs(null))
        assertEquals(1000, TermuxMedia.limitMs(200))
        assertEquals(0, TermuxMedia.limitMs(0))
        assertEquals("Recording started: /sdcard/a.m4a \nMax Duration: 15:00\n", TermuxMedia.startedText("/sdcard/a.m4a", 15 * 60_000))
        assertEquals("Recording started: /x \nMax Duration: unlimited\n", TermuxMedia.startedText("/x", 0))
        assertEquals("{\n  \"isRecording\": false\n}\n", TermuxMedia.infoJson(false, null))
        assertEquals("01:01:05", TermuxDevices.timeString(3665))
        assertEquals(dev(Req.Player("play", "$home/song.mp3")), p("termux-media-player", "play", "song.mp3"))
        assertEquals(dev(Req.Player("play", null)), p("termux-media-player", "play"))
        assertEquals(dev(Req.Player("info", null)), p("termux-media-player", "info"))
        assertEquals("Error! 'pause' takes no arguments!\n", reply(p("termux-media-player", "pause", "x")).text)
        assertEquals(1, reply(p("termux-media-player", "play", "a", "b")).code)
        assertTrue(reply(p("termux-media-player", "rewind")).text.startsWith("termux-media-player: Invalid cmd: 'rewind'"))
        assertEquals(0, reply(p("termux-media-player")).code)
    }

    private fun waitFor(f: File) { repeat(200) { if (f.exists()) return; Thread.sleep(10) } }

    /** kabuk-open 5's stream: pieces `<base>.o1`, `.o2` … in order, the answer after them; older programs get the first piece. */
    @Test fun streamedOutput() {
        val dir = tmp.newFolder("open")
        val s = KabukCommands.Call(dir, "1-99999", "termux-sensor", streaming = true)
        assertTrue(s.emit("one\n")); assertTrue(s.emit("two\n"))
        assertEquals("one\n", File(dir, "1-99999.o1").readText())
        assertEquals("two\n", File(dir, "1-99999.o2").readText())
        s.answer(Answer(0, ""))
        waitFor(File(dir, "1-99999.res"))
        assertEquals("0\n", File(dir, "1-99999.res").readText())
        assertFalse(s.emit("late\n"))
        assertFalse(File(dir, "1-99999.o3").exists())
        // a program that doesn't read (stopped): at most 64 unread pieces, the rest is dropped
        val slow = KabukCommands.Call(dir, "2-99999", "termux-sensor", streaming = true)
        repeat(100) { assertTrue(slow.emit("x\n")) }
        assertEquals(64, dir.listFiles()!!.count { it.name.startsWith("2-99999.o") })
        // reading one makes room for one more
        File(dir, "2-99999.o1").delete()
        slow.emit("y\n")
        assertEquals("y\n", File(dir, "2-99999.o65").readText())
        // kabuk-open 4 and older: the first piece is the answer, then the command stops
        val old = KabukCommands.Call(dir, "3-99999", "termux-location", streaming = false)
        assertFalse(old.emit("{\"latitude\":1}\n"))
        waitFor(File(dir, "3-99999.res"))
        assertEquals("0\n{\"latitude\":1}\n", File(dir, "3-99999.res").readText())
        assertFalse(File(dir, "3-99999.o1").exists())
    }
}
