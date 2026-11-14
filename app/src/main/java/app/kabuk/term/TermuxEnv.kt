package app.kabuk.term

import android.content.Context
import android.os.Build
import android.system.Os
import app.kabuk.log.KLog
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Termux's own packages in the Phone tab, relocated from `/data/data/com.termux` to `/data/data/app.kabuk`
 * ([Relocator]). Exec from the data dir needs targetSdk 28 (W^X applies from 29). The bootstrap is downloaded only
 * after the user agreed ([TermuxInstaller]); this object holds the paths, the install itself and the Phone tab's
 * environment. See docs/LOCAL-TOOLS.md, "Phase B".
 */
object TermuxEnv {
    /** github.com/termux/termux-packages release; refresh with scripts/termux-bootstrap-pin.sh. */
    const val BOOTSTRAP_TAG = "bootstrap-2026.11.08-r1+apt.android-7"
    val SHA256 = mapOf(
        "aarch64" to "ad0df6ac4e8524684ac0b25ccf41459b0aaa515da9557a238d497b44f9034e46",
        "x86_64" to "41453bd2ec3f9690e2474085fbfe8d3406476b3cdd9627502d0ae4fb0d3eecb0",
    )
    /** for the consent sheet (the aarch64 zip is 33.1 MB, extracted ~97 MB) */
    const val DOWNLOAD_MB = 33
    const val INSTALLED_MB = 100
    /** refuse to start below this much free space in the data dir (bootstrap + zip + a little room) */
    private const val MIN_FREE_BYTES = 250L shl 20
    /** pkg's mirror group (etc/termux/mirrors/<group>): Europe instead of a random worldwide pick */
    const val MIRROR_GROUP = "europe"

    fun arch(): String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"; "x86_64" -> "x86_64"; else -> null
    }

    /** `/data/data/app.kabuk/files`: Termux's packages only work under exactly this path (user 0). */
    fun root(ctx: Context) = File("/data/data/${ctx.packageName}/files")
    fun prefix(ctx: Context) = File(root(ctx), "usr")
    fun staging(ctx: Context) = File(root(ctx), "usr-staging")
    /** the previous prefix during the swap at the end of an install/repair */
    fun old(ctx: Context) = File(root(ctx), "usr-old")
    /** leftovers of an install the process didn't finish (killed): removed at app start */
    fun stale(ctx: Context) = staging(ctx).exists() || old(ctx).exists()
    /** usr is in place with its login shell: Phone tabs start Termux's login (bash). */
    fun installed(ctx: Context) = usable(prefix(ctx))
    /** usr is there but its login or bash is missing: Phone tabs fall back to busybox, the UI offers Repair. */
    fun broken(ctx: Context) = broken(prefix(ctx))

    internal fun usable(prefix: File) = File(prefix, "bin/login").isFile && File(prefix, "bin/bash").isFile
    internal fun broken(prefix: File) = prefix.isDirectory && !usable(prefix)
    /**
     * New-tab menus offer "Phone (busybox)" next to "Phone shell" when usr is there, installed or damaged (plain
     * "Phone shell" on a damaged install brings up the Repair sheet); not when absent (that already is busybox).
     */
    fun offersBusyboxTab(ctx: Context) = offersBusyboxTab(prefix(ctx))
    internal fun offersBusyboxTab(prefix: File) = usable(prefix) || broken(prefix)
    fun zipFile(ctx: Context, arch: String) = File(File(ctx.cacheDir, "termux"), "bootstrap-$arch.zip")

    /**
     * Relocation works only when the package name has the length of `com.termux` minus one (the slash fills it),
     * the app's files dir really is /data/data/<pkg>/files (not a secondary user or work profile), the target allows
     * exec from the data dir, and Termux builds for the CPU.
     */
    fun supported(ctx: Context) = ctx.packageName.length == 9 && arch() != null &&
        ctx.applicationInfo.targetSdkVersion <= 28 &&
        // same inode, not same canonical path: /data/user/0 and /data/data are separate mounts of the same dir
        runCatching { java.nio.file.Files.isSameFile(root(ctx).toPath(), ctx.filesDir.toPath()) }.getOrDefault(false)

    /** The Phone tab's shell + environment when the Termux prefix is installed. */
    fun setup(ctx: Context, open: File): LocalTools.Setup {
        val root = root(ctx)
        val prefix = File(root, "usr")
        val home = File(root, "home").apply { mkdirs() }
        File(prefix, "tmp").mkdirs()
        val kabukBin = writeKabukBin(ctx, root)
        val pass = System.getenv().filterKeys {
            it.startsWith("ANDROID_") || it in setOf("BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "SYSTEMSERVERCLASSPATH", "EXTERNAL_STORAGE")
        }
        val env = pass.map { "${it.key}=${it.value}" } + listOf(
            "TERM=xterm-256color", "COLORTERM=truecolor", "LANG=en_US.UTF-8",
            "HOME=${home.path}", "PREFIX=${prefix.path}", "TMPDIR=${prefix.path}/tmp",
            "PATH=${kabukBin.path}:${prefix.path}/bin",
            "TERMUX_VERSION=0.118.3", "TERMUX_APP__PACKAGE_NAME=${ctx.packageName}",
            "TERMUX_APP__DATA_DIR=/data/data/${ctx.packageName}", "TERMUX__ROOTFS=${root.path}",
            "TERMUX__PREFIX=${prefix.path}", "TERMUX__HOME=${home.path}",
            "TERMUX_MAIN_PACKAGE_FORMAT=debian", "TERMUX_APP_PACKAGE_MANAGER=apt",
            "KABUK_OPEN_DIR=${open.path}", "BROWSER=xdg-open",
            "KABUK_VERSION=${app.kabuk.BuildConfig.VERSION_NAME}", "KABUK_VERSION_CODE=${app.kabuk.BuildConfig.VERSION_CODE}",
        )
        refreshHooks(prefix, env)
        return LocalTools.Setup(File(prefix, "bin/login").path, env.toTypedArray(), home, arrayOf("-login"), termux = true)
    }

    /**
     * Names of libkabuk-open.so in `files/kabuk/bin` (scripts/tools/kabuk-open.c): URLs go to [UrlOpener], the rest
     * are requests [KabukCommands] answers. They shadow termux-tools' and termux-api's scripts of the same names,
     * which talk to the Termux app or the Termux:API app (`am broadcast … com.termux/…`).
     */
    val OPENER_NAMES = listOf("xdg-open", "termux-open-url", "kabuk-open") + KabukCommands.NAMES

    /**
     * `files/kabuk/bin`, first on PATH: Kabuk's replacements for termux-tools commands that talk to the Termux app
     * ([OPENER_NAMES], `termux-setup-storage`, `termux-info`) and the `dpkg` wrapper that relocates .debs installed by
     * hand. Not owned by a package, so upgrades don't touch it; rewritten at every tab start.
     */
    private fun writeKabukBin(ctx: Context, root: File): File {
        val dir = File(root, "kabuk/bin").apply { mkdirs() }
        File(dir, "termux-setup-storage").apply {
            writeText(
                """
                |#!${root.path}/usr/bin/sh
                |# Kabuk: asks Android for storage access; Kabuk then links ~/storage/{shared,downloads,dcim,...}
                |: > "${'$'}KABUK_OPEN_DIR/.storage.tmp" && mv "${'$'}KABUK_OPEN_DIR/.storage.tmp" "${'$'}KABUK_OPEN_DIR/storage.req"
                |echo "Kabuk asks Android for storage access; ~/storage appears once it is granted."
                |""".trimMargin()
            )
            setExecutable(true, true)
        }
        File(dir, "termux-info").apply { writeText(TERMUX_INFO.replace("@ROOT@", root.path)); setExecutable(true, true) }
        File(dir, "dpkg").apply { writeText(DPKG_WRAPPER.replace("@ROOT@", root.path)); setExecutable(true, true) }
        val opener = File(ctx.applicationInfo.nativeLibraryDir, "libkabuk-open.so")
        for (name in OPENER_NAMES) {
            // nativeLibraryDir moves on every app update: always re-link
            val l = File(dir, name)
            l.delete()
            if (opener.isFile) runCatching { Os.symlink(opener.path, l.path) }
        }
        return dir
    }

    /** Result of [install], for the log. */
    internal class Report(val downloaded: Long, val downloadMs: Long, val stats: Bootstrap.Stats, val checksums: Int, val extractMs: Long)

    /**
     * Downloads (resuming a partial download), checks the SHA-256, then extracts + relocates entry by entry into a
     * staging dir that replaces `files/usr` in one rename. Blocking; [cancelled] is polled throughout. On failure the
     * staging dir is gone; a partial download stays for Retry (call [cleanup] to drop it too).
     */
    internal fun install(
        ctx: Context, fs: FsOps, cancelled: () -> Boolean,
        onDownload: (Long, Long) -> Unit, onVerify: () -> Unit, onExtract: (Int, Int) -> Unit,
    ): Report {
        val arch = arch() ?: error("Termux has no packages for ${Build.SUPPORTED_ABIS.firstOrNull()}")
        val root = root(ctx)
        val free = root.usableSpace
        if (free in 1 until MIN_FREE_BYTES) throw IOException("Not enough free space: ${free shr 20} MB free, ${MIN_FREE_BYTES shr 20} MB needed")
        val zip = zipFile(ctx, arch)
        zip.parentFile!!.mkdirs()
        val want = SHA256.getValue(arch)
        val t0 = System.currentTimeMillis()
        var downloaded = 0L
        if (!zip.isFile || Bootstrap.sha256(zip) != want) {
            zip.delete()
            val url = "https://github.com/termux/termux-packages/releases/download/" +
                BOOTSTRAP_TAG.replace("+", "%2B") + "/bootstrap-$arch.zip"
            downloaded = download(url, zip, cancelled, onDownload)
            onVerify()
            val got = Bootstrap.sha256(zip)
            if (got != want) { zip.delete(); throw ChecksumException() }
        } else onVerify()
        val downloadMs = System.currentTimeMillis() - t0
        if (cancelled()) throw Bootstrap.Cancelled()
        val t1 = System.currentTimeMillis()
        val staging = staging(ctx)
        SafeDelete.tree(staging)
        staging.mkdirs()
        val stats = Bootstrap.extract(zip, staging, fs, onExtract, cancelled)
        val sums = Bootstrap.fixDpkgChecksums(staging)
        val prefix = prefix(ctx)
        writeAptHook(staging, prefix, fs)
        writeMirrorChoice(staging, fs)
        writeFirstUpdate(staging, prefix)
        if (cancelled()) throw Bootstrap.Cancelled()
        // swap by renames (a repair replaces a working prefix: never leave none behind), then drop the old one
        val old = old(ctx)
        SafeDelete.tree(old)
        if (prefix.exists() && !prefix.renameTo(old)) throw IOException("cannot move the old prefix away")
        if (!staging.renameTo(prefix)) {
            old.renameTo(prefix)
            throw IOException("cannot move the staging dir into place")
        }
        SafeDelete.tree(old)
        zip.delete() // 33 MB: only needed again for a reinstall
        return Report(downloaded, downloadMs, stats, sums, System.currentTimeMillis() - t1)
    }

    class ChecksumException : IOException("The download was damaged (SHA-256 mismatch)")

    /** Removes everything a failed or cancelled install left (staging dir; with [download] also the partial zip). */
    fun cleanup(ctx: Context, download: Boolean) {
        SafeDelete.tree(staging(ctx))
        SafeDelete.tree(old(ctx))
        if (download) SafeDelete.tree(File(ctx.cacheDir, "termux"))
    }

    /**
     * apt's hooks: Pre-Install-Pkgs relocates every .deb before dpkg sees it (usr/libexec/kabuk/relocate-debs);
     * Post-Invoke makes the termux-api package's files Kabuk's (usr/libexec/kabuk/termux-api-sync). Written at
     * install and checked at every tab start (installs from before 0.8.4 lack the Post-Invoke part). True when a
     * file changed.
     */
    internal fun writeAptHook(staging: File, prefix: File, fs: FsOps): Boolean {
        val root = prefix.parentFile!!
        var changed = false
        fun put(f: File, text: String, mode: Int? = null) {
            if (f.isFile && runCatching { f.readText() }.getOrNull() == text) return
            f.parentFile!!.mkdirs()
            f.writeText(text)
            mode?.let { fs.chmod(f, it) }
            changed = true
        }
        put(File(staging, "libexec/kabuk/relocate-debs"), RELOCATE_DEBS.replace("@PREFIX@", prefix.path), "700".toInt(8))
        put(File(staging, "libexec/kabuk/termux-api-sync"), termuxApiSync(prefix.path, File(root, "kabuk/bin").path), "700".toInt(8))
        put(
            File(staging, "etc/apt/apt.conf.d/99kabuk-relocate"),
            "// Kabuk: Termux packages are built for /data/data/com.termux; rewrite each .deb for this app first\n" +
                "DPkg::Pre-Install-Pkgs { \"${prefix.path}/libexec/kabuk/relocate-debs\"; };\n" +
                "// Kabuk answers termux-api's commands itself: its scripts by full path too (no Termux:API app)\n" +
                "DPkg::Post-Invoke { \"[ ! -x ${prefix.path}/libexec/kabuk/termux-api-sync ] || ${prefix.path}/libexec/kabuk/termux-api-sync || true\"; };\n" +
                "// relocated .debs are stored uncompressed (fast to repack): don't keep them in cacheDir\n" +
                "APT::Keep-Downloaded-Packages \"false\";\nBinary::apt::APT::Keep-Downloaded-Packages \"false\";\n",
        )
        return changed
    }

    /**
     * Tab start: the hooks are current, and when the termux-api package's files aren't Kabuk's yet (it was installed
     * before 0.8.4, or removed since) the sync runs once in the background. Never throws.
     */
    private fun refreshHooks(prefix: File, env: List<String>) {
        runCatching { if (writeAptHook(prefix, prefix, AndroidFs)) KLog.i("TERMUX", "apt hooks updated") }
            .onFailure { KLog.w("TERMUX", "apt hooks not written: ${it.javaClass.simpleName}") }
        if (!termuxApiNeedsSync(prefix) || !syncing.compareAndSet(false, true)) return
        Thread({
            try {
                val r = LocalTools.run(listOf(File(prefix, "bin/sh").path, File(prefix, "libexec/kabuk/termux-api-sync").path), timeoutSec = 60, env = env.toTypedArray())
                KLog.i("TERMUX", "termux-api package synced at tab start (${r.describe()})")
            } finally { syncing.set(false) }
        }, "termux-api-sync").start()
    }

    private val syncing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The package is installed but libexec/termux-api isn't Kabuk's stub, or it is gone but Kabuk's diversions stayed. */
    internal fun termuxApiNeedsSync(prefix: File): Boolean {
        val listed = File(prefix, "var/lib/dpkg/info/termux-api.list").isFile
        val stub = runCatching { File(prefix, "libexec/termux-api").readText().contains(STUB_MARK) }.getOrDefault(false)
        val diverted = runCatching { File(prefix, "var/lib/dpkg/diversions").readText().contains("/libexec/kabuk/termux-api/") }.getOrDefault(false)
        return if (listed) !stub else diverted
    }

    private const val STUB_MARK = "Kabuk answers termux-* commands itself"

    /** For tests and [writeAptHook]: the sync script for [prefix] with Kabuk's commands in [kabukBin]. */
    internal fun termuxApiSync(prefix: String, kabukBin: String) = TERMUX_API_SYNC
        .replace("@PREFIX@", prefix).replace("@KBIN@", kabukBin).replace("@NAMES@", TermuxApi.NAMES.joinToString(" "))
        .replace("@MARK@", STUB_MARK)

    /** `etc/termux/chosen_mirrors` → `mirrors/<group>`, what `termux-change-repo` makes; pkg picks among those. */
    internal fun writeMirrorChoice(staging: File, fs: FsOps) {
        val dir = File(staging, "etc/termux")
        if (!File(dir, "mirrors/$MIRROR_GROUP").isDirectory) return
        val link = File(dir, "chosen_mirrors")
        link.delete()
        fs.symlink("mirrors/$MIRROR_GROUP", link)
    }

    /**
     * `etc/profile.d/zz-kabuk-first-update.sh`: the first interactive login fetches the package lists (`pkg update`,
     * which also picks a mirror from the chosen group), after Termux's own second stage (01-…) ran. Removes itself
     * once it worked; offline it says so and tries again in the next new shell.
     */
    internal fun writeFirstUpdate(staging: File, prefix: File) {
        File(staging, "etc/profile.d").mkdirs()
        File(staging, "etc/profile.d/$FIRST_UPDATE").writeText(FIRST_UPDATE_SH.replace("@PREFIX@", prefix.path))
    }

    const val FIRST_UPDATE = "zz-kabuk-first-update.sh"

    private val FIRST_UPDATE_SH = """
        |# Kabuk: made at install, removes itself after the first successful `pkg update`.
        |if [ -t 0 ] && [ -t 1 ]; then
        |  echo "Kabuk: first start, fetching the package lists (pkg update)..."
        |  if pkg update; then
        |    rm -f "@PREFIX@/etc/profile.d/$FIRST_UPDATE"
        |    echo "Kabuk: ready. Install packages with: pkg install <name>"
        |  else
        |    echo "Kabuk: pkg update failed (offline?). It runs again in the next new shell, or type: pkg update"
        |  fi
        |fi
        |""".trimMargin()

    /** Streams [url] into [to] via `<to>.part`, resuming a partial file with a Range request. Returns bytes fetched. */
    private fun download(url: String, to: File, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): Long {
        val part = File(to.path + ".part")
        var u = URL(url)
        var redirects = 0
        var restarted = false
        while (true) {
            val have = part.length()
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Kabuk")
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            try {
                when (val code = c.responseCode) {
                    in 300..399 -> {
                        if (++redirects > 5) throw IOException("too many redirects")
                        u = URL(u, c.getHeaderField("Location") ?: throw IOException("redirect without a location"))
                        continue
                    }
                    200, 206 -> {
                        val append = code == 206 && have > 0
                        val len = c.contentLengthLong
                        val total = if (len < 0) -1 else if (append) have + len else len
                        var got = if (append) have else 0L
                        progress(got, total)
                        RandomAccessFile(part, "rw").use { out ->
                            out.setLength(got); out.seek(got)
                            c.inputStream.use { inp ->
                                val buf = ByteArray(1 shl 16)
                                var lastReport = 0L
                                while (true) {
                                    if (cancelled()) throw Bootstrap.Cancelled()
                                    val n = inp.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    got += n
                                    val now = System.currentTimeMillis()
                                    if (now - lastReport >= 100) { lastReport = now; progress(got, total) }
                                }
                            }
                        }
                        progress(got, total)
                        if (total >= 0 && got != total) throw IOException("download incomplete")
                        if (!part.renameTo(to)) throw IOException("cannot store the download")
                        return got - (if (append) have else 0)
                    }
                    416 -> { // the partial file is bigger than the server's: start over once
                        if (restarted) throw IOException("HTTP 416 from ${u.host}")
                        restarted = true; part.delete(); continue
                    }
                    else -> throw IOException("HTTP $code from ${u.host}")
                }
            } finally {
                c.disconnect()
            }
        }
    }

    /**
     * Runs as apt's `DPkg::Pre-Install-Pkgs` hook (version 1: one .deb path per line on stdin). Unpacks each .deb with
     * dpkg-deb, moves `data/data/com.termux` to `data/data/app.kabuk`, rewrites file contents (binary: same length,
     * text: clean path) into the same inode (sed -i would reset modes), symlink targets, regenerates md5sums, makes
     * maintainer scripts 0755 (dpkg-deb -b refuses others) and repacks uncompressed.
     */
    private val RELOCATE_DEBS = """
        |#!@PREFIX@/bin/sh
        |# Kabuk: rewrites Termux .debs (built for /data/data/com.termux) for /data/data/app.kabuk before dpkg installs them.
        |set -e
        |export LC_ALL=C
        |while read -r deb; do
        |  case "${'$'}deb" in *.deb) ;; *) continue ;; esac
        |  [ -f "${'$'}deb" ] || continue
        |  dpkg-deb -c "${'$'}deb" | grep 'data/data/com\.termux' > /dev/null || continue
        |  w=${'$'}(mktemp -d "${'$'}{TMPDIR:-@PREFIX@/tmp}/reloc.XXXXXX")
        |  dpkg-deb -R "${'$'}deb" "${'$'}w/p"
        |  if [ -d "${'$'}w/p/data/data/com.termux" ]; then
        |    mv "${'$'}w/p/data/data/com.termux" "${'$'}w/p/data/data/app.kabuk"
        |  fi
        |  find "${'$'}w/p" -type f -exec grep -laF 'data/data/com.termux' {} + 2>/dev/null | while IFS= read -r f; do
        |    # binary (has a NUL byte): same length; text: the clean path. Written back into the same inode (keeps mode).
        |    if grep -qaP '\x00' "${'$'}f"; then to='data/data/app.kabuk/'; else to='data/data/app.kabuk'; fi
        |    sed "s|data/data/com\.termux|${'$'}to|g" "${'$'}f" > "${'$'}w/sed.tmp"
        |    m=${'$'}(stat -c %a "${'$'}f"); chmod u+w "${'$'}f"
        |    cat "${'$'}w/sed.tmp" > "${'$'}f"
        |    chmod "${'$'}m" "${'$'}f"
        |  done
        |  find "${'$'}w/p" -type l | while IFS= read -r l; do
        |    t=${'$'}(readlink "${'$'}l")
        |    case "${'$'}t" in *data/data/com.termux*) ln -sfn "${'$'}(printf %s "${'$'}t" | sed 's|data/data/com\.termux|data/data/app.kabuk|g')" "${'$'}l" ;; esac
        |  done
        |  if [ -f "${'$'}w/p/DEBIAN/md5sums" ] && [ -d "${'$'}w/p/data" ]; then
        |    (cd "${'$'}w/p" && find data -type f -exec md5sum {} + > DEBIAN/md5sums)
        |  fi
        |  # some Termux .debs ship maintainer scripts as 644 or 700; dpkg runs them fine, but dpkg-deb -b would refuse
        |  for s in preinst postinst prerm postrm config; do [ -f "${'$'}w/p/DEBIAN/${'$'}s" ] && chmod 0755 "${'$'}w/p/DEBIAN/${'$'}s"; done
        |  rm -f "${'$'}deb.kabuk"
        |  dpkg-deb --nocheck -Znone -b "${'$'}w/p" "${'$'}deb.kabuk" 2>&1 >/dev/null | grep -v 'not checking contents' >&2 || true
        |  [ -s "${'$'}deb.kabuk" ] || { echo "kabuk: repacking ${'$'}{deb##*/} failed" >&2; exit 1; }
        |  mv "${'$'}deb.kabuk" "${'$'}deb"
        |  rm -rf "${'$'}w"
        |  echo "kabuk: relocated ${'$'}{deb##*/}"
        |done
        |""".trimMargin()

    /**
     * apt's `DPkg::Post-Invoke` (and the dpkg wrapper, and tab start when needed): termux-api's `usr/bin/termux-*`
     * scripts and `usr/libexec/termux-api` wait forever for the Termux:API app when called by full path. Each of the
     * package's bin files Kabuk answers (TermuxApi.NAMES) is diverted (`dpkg-divert --local --rename`) to
     * usr/libexec/kabuk/termux-api/ and replaced by a link to Kabuk's command in files/kabuk/bin; each file it ships
     * directly in usr/libexec becomes a stub that says so (exit 1). Upgrades unpack into the diverted names, so
     * this survives them; once the package is gone the links and diversions go too. Idempotent, quiet when there is
     * nothing to do.
     */
    private val TERMUX_API_SYNC = """
        |#!@PREFIX@/bin/sh
        |# Kabuk: makes the termux-api package's commands Kabuk's (no Termux:API app); see TermuxEnv.kt.
        |P=@PREFIX@
        |K=@KBIN@
        |D=${'$'}P/libexec/kabuk/termux-api
        |export LC_ALL=C
        |listed=${'$'}("${'$'}P/bin/dpkg-query" -L termux-api 2>/dev/null | grep -v '^locally diverted to: \|^diverted by ') || listed=
        |ours=${'$'}("${'$'}P/bin/dpkg-divert" --list 2>/dev/null | grep -F " to ${'$'}D/") || ours=
        |[ -z "${'$'}listed" ] && [ -z "${'$'}ours" ] && exit 0
        |ships() { printf '%s\n' "${'$'}listed" | grep -qxF "${'$'}1"; }
        |diverted() { printf '%s\n' "${'$'}ours" | grep -qxF "local diversion of ${'$'}1 to ${'$'}2"; }
        |n_div=0; n_undo=0
        |# ${'$'}1 file, ${'$'}2 diverted name, ${'$'}3 link target (empty: a stub)
        |sync() {
        |  if ships "${'$'}1"; then
        |    if ! diverted "${'$'}1" "${'$'}2"; then
        |      mkdir -p "${'$'}D"
        |      "${'$'}P/bin/dpkg-divert" --local --rename --divert "${'$'}2" --add "${'$'}1" > /dev/null || return 1
        |      n_div=${'$'}((n_div + 1))
        |    fi
        |    if [ -n "${'$'}3" ]; then
        |      [ -L "${'$'}1" ] && [ "${'$'}(readlink "${'$'}1")" = "${'$'}3" ] || ln -sfn "${'$'}3" "${'$'}1"
        |    elif ! grep -qF '@MARK@' "${'$'}1" 2>/dev/null; then
        |      printf '#!%s/bin/sh\necho "%s: @MARK@ (no Termux:API app needed); call them by name, e.g. termux-toast hi" >&2\nexit 1\n' \
        |        "${'$'}P" "${'$'}{1##*/}" > "${'$'}1.kabuk-tmp" && chmod 755 "${'$'}1.kabuk-tmp" && mv -f "${'$'}1.kabuk-tmp" "${'$'}1"
        |    fi
        |  elif diverted "${'$'}1" "${'$'}2"; then
        |    # the package is gone: Kabuk's link or stub, then the diversion
        |    if [ -L "${'$'}1" ] || grep -qF '@MARK@' "${'$'}1" 2>/dev/null; then rm -f "${'$'}1"; fi
        |    "${'$'}P/bin/dpkg-divert" --local --rename --remove "${'$'}1" > /dev/null || return 1
        |    n_undo=${'$'}((n_undo + 1))
        |  fi
        |}
        |rc=0
        |for n in @NAMES@; do
        |  sync "${'$'}P/bin/${'$'}n" "${'$'}D/${'$'}n" "${'$'}K/${'$'}n" || rc=1
        |done
        |# files directly in usr/libexec: the package's (libexec/termux-api, ...) and ones diverted earlier
        |for f in ${'$'}( { printf '%s\n' "${'$'}listed" | grep -x "${'$'}P/libexec/[^/]*"
        |    printf '%s\n' "${'$'}ours" | sed -n "s|^local diversion of \(${'$'}P/libexec/[^/]*\) to .*|\1|p"; } | sort -u); do
        |  [ -d "${'$'}f" ] && continue
        |  sync "${'$'}f" "${'$'}D/libexec-${'$'}{f##*/}" "" || rc=1
        |done
        |[ "${'$'}n_div" -gt 0 ] && echo "kabuk: ${'$'}n_div termux-api files now run Kabuk's commands (no Termux:API app needed)"
        |[ "${'$'}n_undo" -gt 0 ] && echo "kabuk: termux-api removed; Kabuk's termux-* commands stay (${'$'}n_undo diversions undone)"
        |exit ${'$'}rc
        |""".trimMargin()

    /** `termux-info` for Kabuk: Termux's asks the Termux app for its details; this prints Kabuk's. */
    private val TERMUX_INFO = """
        |#!@ROOT@/usr/bin/sh
        |# Kabuk: termux-info (Termux's own asks the Termux app, which Kabuk replaces)
        |P=@ROOT@/usr
        |echo "Kabuk ${'$'}{KABUK_VERSION:-?} (${'$'}{KABUK_VERSION_CODE:-?}): Termux packages relocated to ${'$'}P"
        |echo "Packages CPU architecture:"
        |dpkg --print-architecture 2>/dev/null
        |echo "Subscribed repositories:"
        |grep -hE '^[[:space:]]*deb[[:space:]]' "${'$'}P/etc/apt/sources.list" "${'$'}P"/etc/apt/sources.list.d/*.list 2>/dev/null
        |echo "Chosen mirror group: ${'$'}(readlink "${'$'}P/etc/termux/chosen_mirrors" 2>/dev/null || echo none)"
        |echo "Updatable packages:"
        |apt list --upgradable 2>/dev/null | tail -n +2
        |echo "termux-tools version:"
        |dpkg-query -W -f '${'$'}{Version}
' termux-tools 2>/dev/null
        |echo "Installed packages: ${'$'}(dpkg-query -W -f '.
' 2>/dev/null | wc -l)"
        |echo "Android version:"
        |/system/bin/getprop ro.build.version.release
        |echo "Kernel build information:"
        |uname -a
        |echo "Device manufacturer:"
        |/system/bin/getprop ro.product.manufacturer
        |echo "Device model:"
        |/system/bin/getprop ro.product.model
        |echo "Supported ABIs:"
        |/system/bin/getprop ro.product.cpu.abilist
        |echo "LD_PRELOAD: ${'$'}{LD_PRELOAD:-}"
        |echo "Termux app commands in Kabuk: am (subset), termux-open, termux-open-url, termux-wake-lock,"
        |echo "  termux-wake-unlock, termux-reload-settings, termux-setup-storage."
        |echo "Termux:API commands in Kabuk (no Termux:API app needed): termux-clipboard-get/-set, termux-toast,"
        |echo "  termux-vibrate, termux-torch, termux-tts-speak, termux-notification(-remove), termux-share,"
        |echo "  termux-battery-status, termux-wifi-connectioninfo, termux-volume, termux-media-scan, termux-download."
        |echo "  The others (sms, camera, location, sensors, dialogs, ...) say they are not supported. No plugins."
        |""".trimMargin()

    /**
     * `dpkg` in files/kabuk/bin: `dpkg -i/--install/--unpack x.deb` by hand gets the same relocation as apt's hook
     * (relocate-debs on a copy, the user's .deb stays as it is), then runs the real dpkg. apt calls usr/bin/dpkg by
     * its full path and has the hook anyway; relocate-debs skips .debs that are already relocated.
     */
    private val DPKG_WRAPPER = """
        |#!@ROOT@/usr/bin/sh
        |# Kabuk: relocates .debs installed by hand (built for /data/data/com.termux), then runs the real dpkg.
        |real=@ROOT@/usr/bin/dpkg
        |hook=@ROOT@/usr/libexec/kabuk/relocate-debs
        |sync=@ROOT@/usr/libexec/kabuk/termux-api-sync
        |install=0; change=0
        |for a in "${'$'}@"; do
        |  case "${'$'}a" in
        |    --install|--unpack|-i|-[!-]*i*) install=1; change=1 ;;
        |    -R|--recursive|-[!-]*R*) echo "kabuk: dpkg -R does not relocate; install the .debs by name or with apt install ./x.deb" >&2 ;;
        |    -r|--remove|-P|--purge|--configure|-[!-]*[rP]*) change=1 ;;
        |  esac
        |done
        |if [ "${'$'}install" = 0 ] || [ ! -x "${'$'}hook" ]; then
        |  { [ "${'$'}change" = 1 ] && [ -x "${'$'}sync" ]; } || exec "${'$'}real" "${'$'}@"
        |  # dpkg -r/-P/--configure by hand: termux-api's files may have changed (Post-Invoke covers apt only)
        |  "${'$'}real" "${'$'}@"; rc=${'$'}?
        |  "${'$'}sync" || true
        |  exit ${'$'}rc
        |fi
        |w=${'$'}(mktemp -d "${'$'}{TMPDIR:-@ROOT@/usr/tmp}/kabuk-dpkg.XXXXXX") || exit 1
        |trap 'rm -rf "${'$'}w"' EXIT
        |n=${'$'}#; i=0
        |for a in "${'$'}@"; do
        |  case "${'$'}a" in
        |    *.deb) if [ -f "${'$'}a" ]; then
        |        c="${'$'}w/${'$'}i-${'$'}{a##*/}"
        |        cp "${'$'}a" "${'$'}c" || exit 1
        |        printf '%s
' "${'$'}c" >> "${'$'}w/list"
        |        a=${'$'}c
        |      fi ;;
        |  esac
        |  set -- "${'$'}@" "${'$'}a"; i=${'$'}((i + 1))
        |done
        |shift "${'$'}n"
        |if [ -s "${'$'}w/list" ]; then "${'$'}hook" < "${'$'}w/list" || { echo "kabuk: relocating the .deb failed" >&2; exit 1; }; fi
        |"${'$'}real" "${'$'}@"; rc=${'$'}?
        |[ ! -x "${'$'}sync" ] || "${'$'}sync" || true
        |exit ${'$'}rc
        |""".trimMargin()

    /** For tests: the dpkg wrapper as written for [root] (files dir). */
    internal fun dpkgWrapper(root: String) = DPKG_WRAPPER.replace("@ROOT@", root)

    /** For tests: the hook script as written for [prefix]. */
    internal fun relocateDebsScript(prefix: String) = RELOCATE_DEBS.replace("@PREFIX@", prefix)
}

/** android.system.Os for the extractor on the phone. */
internal object AndroidFs : FsOps {
    override fun chmod(f: File, mode: Int) = Os.chmod(f.path, mode)
    override fun symlink(target: String, link: File) = Os.symlink(target, link.path)
}
