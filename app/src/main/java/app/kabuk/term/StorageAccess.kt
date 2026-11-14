package app.kabuk.term

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.system.Os
import app.kabuk.MainActivity
import app.kabuk.log.KLog
import java.io.File

/**
 * `termux-setup-storage` for Kabuk: the Phone tab's script drops `files/open/storage.req`, [UrlOpener]
 * sees it and calls [request]. At targetSdk 28 READ/WRITE_EXTERNAL_STORAGE give the legacy /sdcard view, as in Termux.
 * Once granted, `~/storage/` gets the same links Termux makes.
 */
object StorageAccess {
    private const val TAG = "STORAGE"
    val PERMISSIONS = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    fun granted(ctx: Context) = PERMISSIONS.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Main thread. Links right away when granted, else brings Kabuk forward to ask. */
    fun request(ctx: Context) {
        if (granted(ctx)) { link(ctx); return }
        KLog.i(TAG, "asking for storage access")
        ctx.startActivity(Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_STORAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** `~/storage/{shared,downloads,dcim,pictures,music,movies,documents}` like termux-setup-storage. */
    fun link(ctx: Context) {
        val home = File(TermuxEnv.root(ctx), "home")
        val dir = File(home, "storage")
        dir.deleteRecursively() // only links live here; deleteRecursively doesn't follow them
        dir.mkdirs()
        @Suppress("DEPRECATION") val shared = Environment.getExternalStorageDirectory()
        val links = mapOf("shared" to shared, "downloads" to File(shared, "Download"), "dcim" to File(shared, "DCIM"),
            "pictures" to File(shared, "Pictures"), "music" to File(shared, "Music"), "movies" to File(shared, "Movies"),
            "documents" to File(shared, "Documents"))
        links.forEach { (name, target) -> runCatching { Os.symlink(target.path, File(dir, name).path) } }
        KLog.i(TAG, "storage linked: ${links.size} links, shared readable=${shared.canRead()}")
        TabManager.toast("Storage linked in ~/storage")
    }
}
