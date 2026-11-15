package app.kabuk.term

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.WindowManager
import app.kabuk.log.KLog
import app.kabuk.term.KabukCommands.Answer
import app.kabuk.term.TermuxDevices.JObj
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * termux-camera-info and termux-camera-photo (Camera2, no preview on screen: like Termux:API, a dummy preview surface
 * runs half a second for focus and exposure, then one JPEG still at the largest size). Logs: the command and the
 * outcome, never the file path.
 */
object TermuxCamera {
    private const val TAG = "CMD"
    private const val PHOTO_TIMEOUT_MS = 20_000L

    private val AE_MODES = mapOf(
        CameraMetadata.CONTROL_AE_MODE_OFF to "CONTROL_AE_MODE_OFF", CameraMetadata.CONTROL_AE_MODE_ON to "CONTROL_AE_MODE_ON",
        CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH to "CONTROL_AE_MODE_ON_ALWAYS_FLASH",
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH to "CONTROL_AE_MODE_ON_AUTO_FLASH",
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE to "CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE",
        CameraMetadata.CONTROL_AE_MODE_ON_EXTERNAL_FLASH to "CONTROL_AE_MODE_ON_EXTERNAL_FLASH",
    )
    private val CAPABILITIES = mapOf(
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE to "backward_compatible",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE to "burst_capture",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO to "constrained_high_speed_video",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT to "depth_output",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA to "logical_multi_camera",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING to "manual_post_processing",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR to "manual_sensor",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME to "monochrome",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING to "motion_tracking",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING to "private_reprocessing",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW to "raw",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS to "read_sensor_settings",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING to "yuv_reprocessing",
    )

    /** One camera as termux-camera-info prints it. */
    data class CameraDesc(
        val id: String, val facing: Int?, val jpegSizes: List<Pair<Int, Int>>, val focalLengths: List<Float>,
        val aeModes: List<Int>, val physicalSize: Pair<Float, Float>?, val capabilities: List<Int>,
    )

    internal fun infoJson(cams: List<CameraDesc>): String = TermuxDevices.pretty(cams.map { c ->
        JObj(listOfNotNull(
            "id" to c.id,
            "facing" to when (c.facing) { CameraMetadata.LENS_FACING_FRONT -> "front"; CameraMetadata.LENS_FACING_BACK -> "back"; null -> null; else -> c.facing },
            "jpeg_output_sizes" to c.jpegSizes.map { (w, h) -> JObj("width" to w, "height" to h) },
            "focal_lengths" to c.focalLengths,
            "auto_exposure_modes" to c.aeModes.map { AE_MODES[it] ?: it },
            c.physicalSize?.let { (w, h) -> "physical_size" to JObj("width" to w, "height" to h) },
            "capabilities" to c.capabilities.map { CAPABILITIES[it] ?: it },
        ))
    }) + "\n"

    fun info(ctx: Context, name: String): Answer {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return Answer(1, "$name: this phone has no camera service\n")
        return try {
            val cams = cm.cameraIdList.map { id ->
                val c = cm.getCameraCharacteristics(id)
                CameraDesc(
                    id = id, facing = c.get(CameraCharacteristics.LENS_FACING),
                    jpegSizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG)
                        ?.map { it.width to it.height }.orEmpty(),
                    focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
                    aeModes = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.toList().orEmpty(),
                    physicalSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { it.width to it.height },
                    capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty(),
                )
            }
            KLog.i(TAG, "$name: ${cams.size} camera(s)")
            Answer(0, infoJson(cams))
        } catch (e: Exception) {
            KLog.w(TAG, "$name: failed (${e.javaClass.simpleName})")
            Answer(1, "$name: could not read the cameras: ${e.message}\n")
        }
    }

    /** JPEG orientation for the sensor and the screen's rotation (degrees), as Termux:API computes it. */
    internal fun jpegOrientation(sensorOrientation: Int, deviceRotationDegrees: Int, front: Boolean): Int =
        ((if (front) sensorOrientation + deviceRotationDegrees else sensorOrientation - deviceRotationDegrees) + 360) % 360

    /** Main thread: opens the camera on its own thread, writes the JPEG to [req].file and answers. */
    @Suppress("MissingPermission")
    fun photo(ctx: Context, name: String, req: TermuxDevices.Req.CameraPhoto, s: TermuxDevices.Session) {
        val out = File(req.file)
        val dir = out.parentFile
        if (dir == null || !dir.isDirectory) return s.answer(Answer(1, "$name: ${dir ?: req.file}: No such directory\n"))
        if (!dir.canWrite() || (out.exists() && !out.canWrite())) return s.answer(Answer(1, "$name: ${req.file}: Permission denied\n"))
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return s.answer(Answer(1, "$name: this phone has no camera service\n"))
        val (id, chars) = try {
            val ids = cm.cameraIdList.toList()
            val id = req.camera ?: ids.firstOrNull { it == "0" }
                ?: ids.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK }
                ?: ids.firstOrNull() ?: return s.answer(Answer(1, "$name: this phone has no camera\n"))
            if (id !in ids) return s.answer(Answer(1, "$name: no camera with id $id (see termux-camera-info)\n"))
            id to cm.getCameraCharacteristics(id)
        } catch (e: Exception) {
            return s.answer(Answer(1, "$name: could not read the camera: ${e.message}\n"))
        }
        @Suppress("DEPRECATION")
        val rotation = when (ctx.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation) {
            Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
        }
        val thread = HandlerThread("cmd-camera").apply { start() }
        val h = Handler(thread.looper)
        val finished = AtomicBoolean(false)
        var device: CameraDevice? = null
        var reader: ImageReader? = null
        val surfaces = mutableListOf<Surface>()
        var texture: SurfaceTexture? = null
        fun finish(a: Answer, why: String) {
            if (!finished.compareAndSet(false, true)) return
            h.post {
                runCatching { device?.close() }
                runCatching { reader?.close() }
                surfaces.forEach { runCatching { it.release() } }
                runCatching { texture?.release() }
                thread.quitSafely()
            }
            if (a.code == 0) KLog.i(TAG, "$name: $why") else KLog.w(TAG, "$name: $why")
            s.answer(a)
        }
        h.postDelayed({ finish(Answer(1, "$name: the camera did not deliver a picture within ${PHOTO_TIMEOUT_MS / 1000} s\n"), "timed out") }, PHOTO_TIMEOUT_MS)
        s.onGone { finish(Answer(1, "$name: interrupted\n"), "stopped (the program ended)") }
        val ae = if (chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.contains(CameraMetadata.CONTROL_AE_MODE_ON) == true)
            CameraMetadata.CONTROL_AE_MODE_ON else CameraMetadata.CONTROL_AE_MODE_OFF
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
        val af = if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE else null
        val size = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG)
            ?.maxByOrNull { it.width.toLong() * it.height } ?: return finish(Answer(1, "$name: this camera has no JPEG output\n"), "no JPEG size")
        val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        val orientation = jpegOrientation(chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0, rotation, front)
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    if (finished.get()) { camera.close(); return }
                    try {
                        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).also { reader = it }
                        r.setOnImageAvailableListener({ rd ->
                            val a = runCatching {
                                rd.acquireNextImage().use { img ->
                                    val buf = img.planes[0].buffer
                                    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                                    out.writeBytes(bytes)
                                    bytes.size
                                }
                            }
                            a.fold({ n -> finish(Answer(0, ""), "photo taken (${size.width}x${size.height}, $n bytes)") },
                                { e -> finish(Answer(1, "$name: could not write the picture: ${e.message}\n"), "write failed (${e.javaClass.simpleName})") })
                        }, h)
                        texture = SurfaceTexture(1).apply { setDefaultBufferSize(640, 480) }
                        val preview = Surface(texture)
                        surfaces += r.surface; surfaces += preview
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(r.surface, preview), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    val p = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    p.addTarget(preview)
                                    af?.let { p.set(CaptureRequest.CONTROL_AF_MODE, it) }
                                    p.set(CaptureRequest.CONTROL_AE_MODE, ae)
                                    session.setRepeatingRequest(p.build(), null, h)
                                    // half a second of preview for focus and exposure, then the still
                                    h.postDelayed({
                                        if (finished.get()) return@postDelayed
                                        try {
                                            session.stopRepeating()
                                            val j = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                            j.addTarget(r.surface)
                                            af?.let { j.set(CaptureRequest.CONTROL_AF_MODE, it) }
                                            j.set(CaptureRequest.CONTROL_AE_MODE, ae)
                                            j.set(CaptureRequest.JPEG_ORIENTATION, orientation)
                                            session.capture(j.build(), null, h)
                                        } catch (e: Exception) {
                                            finish(Answer(1, "$name: the capture failed: ${e.message}\n"), "capture failed (${e.javaClass.simpleName})")
                                        }
                                    }, 500)
                                } catch (e: Exception) {
                                    finish(Answer(1, "$name: the preview failed: ${e.message}\n"), "preview failed (${e.javaClass.simpleName})")
                                }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) =
                                finish(Answer(1, "$name: the camera could not be set up\n"), "configure failed")
                        }, h)
                    } catch (e: Exception) {
                        finish(Answer(1, "$name: the camera could not be set up: ${e.message}\n"), "setup failed (${e.javaClass.simpleName})")
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    device = camera
                    finish(Answer(1, "$name: the camera was taken by another app\n"), "disconnected")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    device = camera
                    finish(Answer(1, "$name: the camera could not be opened (error $error; in use, or Kabuk in the background?)\n"), "open error $error")
                }
            }, h)
        } catch (e: Exception) {
            finish(Answer(1, "$name: the camera could not be opened: ${e.message}\n"), "open failed (${e.javaClass.simpleName})")
        }
    }
}
