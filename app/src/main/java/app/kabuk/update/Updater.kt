package app.kabuk.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import app.kabuk.BuildConfig
import app.kabuk.log.KLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

/**
 * In-app updater: fetch update.json from [BuildConfig.UPDATE_URL], verify its signature with the compiled-in key,
 * download the APK, check size + SHA-256 + that it is this app signed by the same certificate, then hand it to the
 * package installer (Android still asks the user to confirm). All methods block: call them off the main thread.
 */
class Updater(private val ctx: Context) {
    private val base = BuildConfig.UPDATE_URL.let { if (it.endsWith("/")) it else "$it/" }
    private val dir = File(ctx.noBackupFilesDir, "updater")

    val installedCode: Long get() = packageInfo(ctx.packageName, ctx.packageManager).versionCodeCompat()
    private val installedCert: String get() = certsOf(packageInfo(ctx.packageName, ctx.packageManager)).single()

    private val verifier by lazy {
        ManifestVerifier(Base64.getDecoder().decode(BuildConfig.UPDATE_PUBLIC_KEY), ctx.packageName, installedCert)
    }

    /** The offered build, or null when the installed one is current. */
    fun check(): UpdateManifest? {
        KLog.i(TAG, "check: installed $installedCode, asking $base")
        return try {
            val m = verifier.verify(get("update.json", ManifestVerifier.MAX_MANIFEST_BYTES))
            if (m.minSdk > Build.VERSION.SDK_INT) throw UpdateException("update needs Android ${m.minSdk}")
            KLog.i(TAG, "check: offered ${m.versionName} (${m.versionCode})")
            m.takeIf { it.versionCode > installedCode }
        } catch (e: Exception) {
            KLog.e(TAG, "check failed", e); throw e
        }
    }

    /** Downloads and fully verifies the APK; returns the staged file. */
    fun download(m: UpdateManifest): File {
        try {
            if (m.versionCode <= installedCode) throw UpdateException("not newer than the installed version")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, m.apk)
            val http = open(base + m.apk)
            try {
                if (http.responseCode != 200) throw UpdateException("download failed (HTTP ${http.responseCode})")
                val md = MessageDigest.getInstance("SHA-256")
                var total = 0L
                http.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > m.size) throw UpdateException("download is bigger than announced")
                            md.update(buf, 0, n); out.write(buf, 0, n)
                        }
                    }
                }
                if (total != m.size || md.digest().hex() != m.sha256) throw UpdateException("download is damaged (size or SHA-256 differs)")
            } finally {
                http.disconnect()
            }
            val info = ctx.packageManager.getPackageArchiveInfo(target.absolutePath, signingFlags())
                ?: throw UpdateException("downloaded file is not an APK")
            info.applicationInfo?.let { it.sourceDir = target.absolutePath; it.publicSourceDir = target.absolutePath }
            if (info.packageName != ctx.packageName || info.versionCodeCompat() != m.versionCode) {
                throw UpdateException("downloaded APK is a different app or version")
            }
            if (certsOf(info) != setOf(installedCert)) throw UpdateException("downloaded APK is signed by another certificate")
            KLog.i(TAG, "download: ${m.apk} verified (${m.size} bytes)")
            return target
        } catch (e: Exception) {
            KLog.e(TAG, "download failed", e); throw e
        }
    }

    /** False when the user must first allow installs from Kabuk (the settings page was opened). */
    fun install(apk: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            KLog.w(TAG, "install: permission 'install unknown apps' missing, opening settings")
            ctx.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        params.setSize(apk.length())
        val id = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(id).use { s ->
                s.openWrite("update.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                val pi = PendingIntent.getBroadcast(
                    ctx, id, Intent(ctx, StatusReceiver::class.java).setPackage(ctx.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                s.commit(pi.intentSender)
                committed = true
            }
        } finally {
            if (!committed) installer.abandonSession(id)
        }
        KLog.i(TAG, "install: session $id committed, waiting for the user's confirmation")
        return true
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 20000
            setRequestProperty("Connection", "close"); useCaches = false
        }

    private fun get(name: String, max: Int): ByteArray {
        val http = open(base + name)
        try {
            if (http.responseCode != 200) throw UpdateException("update server answered HTTP ${http.responseCode}")
            val bytes = http.inputStream.use { it.readNBytes(max + 1) }
            if (bytes.size > max) throw UpdateException("update.json is too big")
            return bytes
        } finally {
            http.disconnect()
        }
    }

    /** Receives the installer's status: opens Android's confirm dialog, logs the outcome. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                KLog.i(TAG, "install status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            }
        }
    }

    private companion object {
        const val TAG = "UPDATE"

        fun signingFlags() =
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

        fun packageInfo(pkg: String, pm: PackageManager): PackageInfo = pm.getPackageInfo(pkg, signingFlags())

        @Suppress("DEPRECATION")
        fun PackageInfo.versionCodeCompat(): Long = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

        @Suppress("DEPRECATION")
        fun certsOf(info: PackageInfo): Set<String> {
            val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return sigs.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
        }

        fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
