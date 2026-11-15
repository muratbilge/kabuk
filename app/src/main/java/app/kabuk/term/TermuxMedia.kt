package app.kabuk.term

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import app.kabuk.log.KLog
import app.kabuk.service.SessionService
import app.kabuk.term.KabukCommands.Answer
import app.kabuk.term.TermuxDevices.JObj
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * termux-microphone-record and termux-media-player, with Termux:API's messages. One recording and one track at a
 * time, held in this process; while recording, Kabuk's session notification says so and has "Stop recording", and
 * the session service keeps running even with no tab open ([TabManager.hold]). Main thread. Logs: the command and
 * the outcome only, never file names.
 */
object TermuxMedia {
    private const val TAG = "CMD"
    /** Termux:API's default limit: 15 min */
    private const val DEFAULT_LIMIT_MS = 15 * 60 * 1000
    private const val MIN_LIMIT_MS = 1000

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    val recording get() = recorder != null

    /** encoder → (MediaRecorder encoder, output format, file extension) */
    private fun encoding(encoder: String?): Triple<Int, Int, String> = when (encoder) {
        "amr_nb" -> Triple(MediaRecorder.AudioEncoder.AMR_NB, MediaRecorder.OutputFormat.THREE_GPP, ".3gp")
        "amr_wb" -> Triple(MediaRecorder.AudioEncoder.AMR_WB, MediaRecorder.OutputFormat.THREE_GPP, ".3gp")
        "opus" -> if (Build.VERSION.SDK_INT >= 29) Triple(MediaRecorder.AudioEncoder.OPUS, MediaRecorder.OutputFormat.OGG, ".ogg")
            else Triple(MediaRecorder.AudioEncoder.AAC, MediaRecorder.OutputFormat.MPEG_4, ".m4a")
        else -> Triple(MediaRecorder.AudioEncoder.AAC, MediaRecorder.OutputFormat.MPEG_4, ".m4a")
    }

    /** Termux:API's limit handling: 0 or less = unlimited, else at least 1 s. */
    internal fun limitMs(requested: Int?): Int = (requested ?: DEFAULT_LIMIT_MS).let { if (it in 1 until MIN_LIMIT_MS) MIN_LIMIT_MS else it }

    internal fun startedText(path: String, limitMs: Int) =
        "Recording started: $path \nMax Duration: ${if (limitMs <= 0) "unlimited" else TermuxDevices.timeString(limitMs / 1000)}\n"

    internal fun infoJson(recording: Boolean, path: String?) =
        TermuxDevices.pretty(JObj(listOfNotNull("isRecording" to recording, if (recording && path != null) "outputFile" to path else null))) + "\n"

    fun record(ctx: Context, name: String, req: TermuxDevices.Req.MicRecord): Answer {
        val (encoder, format, ext) = encoding(req.encoder)
        @Suppress("DEPRECATION")
        val file = File(req.file ?: (Environment.getExternalStorageDirectory().absolutePath + "/KabukAudioRecording_" +
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date()) + ext))
        if (file.exists()) return Answer(1, "File: ${file.name} already exists! Please specify a different filename\n")
        if (recorder != null) return Answer(1, "Recording already in progress!\n")
        val dir = file.parentFile
        if (dir == null || !dir.isDirectory || !dir.canWrite()) {
            KLog.i(TAG, "$name: folder not writable")
            return Answer(1, "$name: cannot write to ${dir ?: file}" +
                (if (req.file == null) " (shared storage: run termux-setup-storage first, or give -f file)" else "") + "\n")
        }
        val limit = limitMs(req.limitMs)
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(format)
            r.setAudioEncoder(encoder)
            r.setOutputFile(file.absolutePath)
            r.setMaxDuration(limit)
            req.bitrate?.takeIf { it > 0 }?.let(r::setAudioEncodingBitRate)
            req.sampleRate?.takeIf { it > 0 }?.let(r::setAudioSamplingRate)
            req.channels?.takeIf { it > 0 }?.let(r::setAudioChannels)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)
                    TabManager.main.post { if (recorder === r) stopRecording(ctx, "limit reached") }
            }
            r.setOnErrorListener { _, what, _ -> TabManager.main.post { if (recorder === r) stopRecording(ctx, "recorder error $what") } }
            r.prepare()
            r.start()
            recorder = r
            recordFile = file
            TabManager.hold("recording", true)
            SessionService.refresh()
            KLog.i(TAG, "$name: recording started (${req.encoder ?: "aac"}, limit ${if (limit <= 0) "none" else "${limit / 1000} s"})")
            Answer(0, startedText(file.absolutePath, limit))
        } catch (e: Exception) {
            runCatching { r.release() }
            // MediaRecorder creates the file in prepare(): don't leave an empty one behind
            if (file.length() == 0L) file.delete()
            KLog.w(TAG, "$name: recording failed to start (${e.javaClass.simpleName})")
            Answer(1, "Recording start error:\n${e.javaClass.name}: ${e.message}\n")
        }
    }

    fun recordInfo(): Answer = Answer(0, infoJson(recorder != null, recordFile?.absolutePath))

    fun recordQuit(ctx: Context, name: String): Answer {
        val f = recordFile
        if (recorder == null || f == null) return Answer(0, "No recording to stop\n")
        stopRecording(ctx, "stopped by $name")
        return Answer(0, "Recording finished: ${f.absolutePath}\n")
    }

    /** Main thread: ends the recording (termux-microphone-record -q, the limit, the notification's button). */
    fun stopRecording(ctx: Context, why: String) {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }.onFailure { KLog.w(TAG, "termux-microphone-record: stop failed (${it.javaClass.simpleName}: too short?)") }
        runCatching { r.release() }
        TabManager.hold("recording", false)
        SessionService.refresh()
        KLog.i(TAG, "termux-microphone-record: recording ended ($why)")
    }

    // ---- termux-media-player ----

    private var player: MediaPlayer? = null
    private var hasTrack = false
    private var trackName = ""

    private fun position(p: MediaPlayer) = TermuxDevices.timeString(p.currentPosition / 1000) + " / " + TermuxDevices.timeString(p.duration / 1000)

    private fun mediaPlayer(ctx: Context): MediaPlayer = player ?: MediaPlayer().also { p ->
        p.setWakeMode(ctx.applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
        p.setOnCompletionListener { hasTrack = false; it.reset(); TabManager.hold("player", false); KLog.i(TAG, "termux-media-player: track finished") }
        p.setOnErrorListener { mp, what, _ ->
            KLog.w(TAG, "termux-media-player: playback error $what")
            hasTrack = false; mp.reset(); TabManager.hold("player", false); true
        }
        player = p
    }

    fun player(ctx: Context, name: String, req: TermuxDevices.Req.Player): Answer {
        val p = mediaPlayer(ctx)
        return when (req.cmd) {
            "info" -> Answer(0, if (hasTrack) "Status: ${if (p.isPlaying) "Playing" else "Paused"}\nTrack: $trackName\nCurrent Position: ${position(p)}\n"
                else "No track currently!\n")
            "pause" -> Answer(0, when {
                !hasTrack -> "No track to pause\n"
                p.isPlaying -> { p.pause(); TabManager.hold("player", false); KLog.i(TAG, "$name: paused"); "Paused playback\n" }
                else -> "Playback already paused\n"
            })
            "stop" -> if (hasTrack) {
                p.stop(); p.reset(); hasTrack = false; TabManager.hold("player", false)
                KLog.i(TAG, "$name: stopped")
                Answer(0, "Stopped playback\nTrack cleared\n")
            } else Answer(0, "No track to stop\n")
            else -> if (req.file == null) {
                if (!hasTrack) Answer(0, "No previous track to resume!\nPlease supply a new media file\n")
                else if (p.isPlaying) Answer(0, "Already playing track!\nTrack: $trackName\nCurrent Position: ${position(p)}\n")
                else { p.start(); TabManager.hold("player", true); KLog.i(TAG, "$name: resumed"); Answer(0, "Resumed playback\nTrack: $trackName\nCurrent Position: ${position(p)}\n") }
            } else {
                val f = File(req.file)
                if (!f.isFile) return Answer(1, "Error: '${req.file}' is not a file!\n")
                if (hasTrack) { p.stop(); p.reset(); hasTrack = false }
                try {
                    p.setDataSource(f.canonicalPath)
                    p.prepare()
                } catch (e: Exception) {
                    p.reset()
                    KLog.w(TAG, "$name: cannot play (${e.javaClass.simpleName})")
                    return Answer(1, "${e.message ?: e.javaClass.simpleName}\n")
                }
                p.start()
                hasTrack = true
                trackName = f.name
                TabManager.hold("player", true)
                KLog.i(TAG, "$name: playing (${p.duration / 1000} s)")
                Answer(0, "Now Playing: $trackName\n")
            }
        }
    }
}
