package app.kabuk.term

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import app.kabuk.log.KLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * A transparent activity for Termux:API commands that need the user ([TermuxDevices]): asks for runtime permissions
 * or shows the system's file picker (termux-storage-get), hands the outcome to the waiting callback and finishes.
 * Started only while Kabuk is on screen (Android blocks activity starts from the background). Not exported.
 * If it goes away without an answer (killed, back pressed), the callback gets "refused" / no file.
 */
class ApiActivity : Activity() {
    private var id = -1
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getIntExtra(EXTRA_ID, -1)
        // recreated (rotation) while the system's dialog is up: its answer still comes to this instance
        if (savedInstanceState != null) return
        if (id !in waiting) { finish(); return }
        when (intent.action) {
            ACTION_PERMISSIONS -> requestPermissions(intent.getStringArrayExtra(EXTRA_PERMISSIONS) ?: emptyArray(), id)
            ACTION_PICK -> try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), id)
            } catch (e: Exception) {
                KLog.w("CMD", "no file picker (${e.javaClass.simpleName})")
                deliver(null)
            }
            else -> deliver(null)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        deliver(grantResults.any { it == PackageManager.PERMISSION_GRANTED })
    }

    @Deprecated("the plain Activity result API is enough here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        deliver(if (resultCode == RESULT_OK) data?.data else null)
    }

    override fun onDestroy() {
        // gone without an answer: a waiting command must not hang
        if (isFinishing && !delivered) deliver(null, finish = false)
        super.onDestroy()
    }

    /** [result]: Boolean for permissions, Uri? for the picker; null = refused / nothing chosen */
    private fun deliver(result: Any?, finish: Boolean = true) {
        if (delivered) return
        delivered = true
        waiting.remove(id)?.invoke(result)
        if (finish) finish()
    }

    companion object {
        private const val ACTION_PERMISSIONS = "app.kabuk.api.PERMISSIONS"
        private const val ACTION_PICK = "app.kabuk.api.PICK"
        private const val EXTRA_ID = "id"
        private const val EXTRA_PERMISSIONS = "permissions"
        private val ids = AtomicInteger()
        /** request id → what to do with the outcome (main thread) */
        private val waiting = HashMap<Int, (Any?) -> Unit>()

        private fun start(ctx: Context, action: String, extras: Intent.() -> Unit, cb: (Any?) -> Unit) {
            val id = ids.incrementAndGet() and 0xffff
            waiting[id] = cb
            try {
                ctx.startActivity(Intent(ctx, ApiActivity::class.java).setAction(action).putExtra(EXTRA_ID, id).apply(extras)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } catch (e: Exception) {
                KLog.w("CMD", "could not ask (${e.javaClass.simpleName})")
                waiting.remove(id)?.invoke(null)
            }
        }

        /** Main thread: [cb] gets true when at least one of [perms] was granted. */
        fun requestPermissions(ctx: Context, perms: Array<String>, cb: (Boolean) -> Unit) =
            start(ctx, ACTION_PERMISSIONS, { putExtra(EXTRA_PERMISSIONS, perms) }) { cb(it == true) }

        /** Main thread: [cb] gets the document the user picked, or null. */
        fun pickDocument(ctx: Context, cb: (Uri?) -> Unit) = start(ctx, ACTION_PICK, {}) { cb(it as? Uri) }
    }
}
