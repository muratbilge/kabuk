package app.kabuk.term

import android.content.Context
import app.kabuk.agents.Agent
import app.kabuk.agents.Herdr
import app.kabuk.data.Host
import app.kabuk.data.Hosts
import app.kabuk.data.SecretBox
import app.kabuk.data.Settings
import app.kabuk.data.shellQuote
import app.kabuk.ssh.HostKeys
import app.kabuk.ssh.KeyPairStore
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import kotlinx.coroutines.flow.MutableStateFlow
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.AndroidConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordFinder
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.min

class SshTab(id: Long, private val ctx: Context, hostInit: Host) : TermTab(id, hostInit.name, hostInit.color) {
    @Volatile private var host = hostInit
    val hostId get() = host.id
    val hostName get() = host.name
    /** "End session" kills a tmux session; only when tmux was chosen explicitly (herdr's would stop all agents) */
    val usesTmux get() = host.session == Host.TMUX

    /** One connection attempt. Cleanup only ever touches the attempt it belongs to. */
    private class Conn(val client: SSHClient) {
        @Volatile var channel: SessionChannel? = null
        @Volatile var out: OutputStream? = null
    }

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "ssh-write-$id") }
    private val lock = Object()
    @Volatile private var closing = false
    @Volatile private var conn: Conn? = null
    @Volatile private var cols = 80
    @Volatile private var rows = 24
    /** poked by "Reconnect now" and by the network coming back */
    @Volatile private var wakeUp = false
    /** the user said no (host key, login): don't retry by ourselves */
    @Volatile private var userStopped = false
    @Volatile private var gen = 0
    /** changes with every new connection: delayed input checks it is still talking to the same one */
    override val generation get() = gen
    override val closed get() = closing

    /** herdr agents on this server (empty when herdr isn't running there) */
    val agents = MutableStateFlow<List<Agent>>(emptyList())
    /** the open question of each waiting agent, by pane id (from herdr's live updates) */
    val questions = MutableStateFlow<Map<String, app.kabuk.text.Pending>>(emptyMap())
    /** agents come live from herdr's event stream (false: asked every few seconds) */
    @Volatile var liveAgents = false
        private set

    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) { send(data.copyOfRange(offset, offset + count)) }
        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            title.value = newTitle?.takeIf { it.isNotBlank() } ?: host.name
        }
        override fun onCopyTextToClipboard(text: String) = TabManager.remoteClipboard(this@SshTab, text)
        override fun onPasteTextFromClipboard() { TabManager.clipboardText()?.let { emulator.paste(it) } }
        override fun onBell() = TabManager.bell(this@SshTab)
        override fun onColorsChanged() = screenChanged()
    }

    override val emulator: TerminalEmulator = TerminalEmulator(output, cols, rows, Settings.current.scrollbackLines, QuietSessionClient)

    override val canSend get() = conn?.out != null && !closing
    override val redrawing get() = sync.inside

    /** false when not connected: the caller keeps what the user typed */
    private fun send(bytes: ByteArray): Boolean {
        val o = conn?.out ?: return false
        if (closing) return false
        return runCatching { writer.execute { runCatching { o.write(bytes); o.flush() } } }.isSuccess
    }

    override fun write(data: ByteArray, offset: Int, count: Int) { send(data.copyOfRange(offset, offset + count)) }

    override fun resize(columns: Int, rows: Int) {
        if (columns == cols && rows == this.rows) return
        cols = columns; this.rows = rows
        emulator.resize(columns, rows)
        pushWindowSize(conn)
        screenChanged()
    }

    private fun pushWindowSize(c: Conn?) {
        val ch = c?.channel ?: return
        if (closing) return
        runCatching { writer.execute { runCatching { ch.changeWindowDimensions(cols, rows, 0, 0) } } }
    }

    // ---------------------------------------------------------------- output pump

    /**
     * Output from the server is collected here and handed to the emulator in batches on the main thread.
     * The reader thread waits when the screen falls far behind, so a flood of output can't eat the memory
     * or delay what you type (^C still goes out immediately through the writer).
     */
    private val pending = ByteArrayOutputStream()
    private val pendingLock = Object()
    private var drainPosted = false
    private val maxPending = 2 * 1024 * 1024

    private fun received(buf: ByteArray, n: Int, c: Conn) {
        synchronized(pendingLock) {
            while (pending.size() > maxPending && conn === c && !closing) pendingLock.wait(200)
            if (conn !== c || closing) return
            pending.write(buf, 0, n)
            if (!drainPosted) { drainPosted = true; TabManager.main.post(::drain) }
        }
    }

    private fun drain() {
        val chunk: ByteArray
        synchronized(pendingLock) {
            val all = pending.toByteArray()
            // at most 256 KB per frame keeps the UI responsive; the rest follows in the next frame
            val n = min(all.size, 256 * 1024)
            chunk = all.copyOf(n)
            pending.reset()
            if (all.size > n) pending.write(all, n, all.size - n)
            drainPosted = pending.size() > 0
            if (drainPosted) TabManager.main.post(::drain)
            pendingLock.notifyAll()
        }
        if (chunk.isNotEmpty() && !closing) {
            emulator.append(chunk, chunk.size)
            sync.feed(chunk)
            if (!sync.inside) {
                TabManager.main.removeCallbacks(syncTimeout)
                syncTimeoutPosted = false
                screenChanged()
            } else if (!syncTimeoutPosted) {
                // a redraw that never finishes (program killed mid-frame) is shown anyway
                syncTimeoutPosted = true
                TabManager.main.postDelayed(syncTimeout, 150)
            }
        }
    }

    /** main thread only: whether the program is in the middle of a synchronized redraw */
    private val sync = SyncScanner()
    private var syncTimeoutPosted = false
    private val syncTimeout = Runnable { syncTimeoutPosted = false; if (!closing) screenChanged() }

    /** status line written straight into our own screen (dim), never sent to the server */
    private fun note(msg: String) = TabManager.main.post {
        val b = "\r\n\u001b[2m[$msg]\u001b[0m\r\n".toByteArray()
        emulator.append(b, b.size)
        screenChanged()
    }

    // ---------------------------------------------------------------- connection loop

    private fun loop() {
        var attempt = 0
        while (!closing) {
            host = Hosts.get(host.id) ?: host
            userStopped = false
            status.value = Status.Connecting(if (attempt == 0) "connecting" else "reconnecting")
            note("${if (attempt == 0) "connecting to" else "reconnecting to"} ${host.label}")
            val c = Conn(newClient())
            synchronized(pendingLock) {
                if (closing) return
                conn = c; gen++
            }
            val result = runCatching { session(c) { attempt = 0 } }
            synchronized(pendingLock) {
                if (conn === c) conn = null
                // output still queued from this connection is dropped (tmux redraws on reattach)
                pending.reset()
                pendingLock.notifyAll()
            }
            // a redraw cut off by the drop must not hold back the next connection's screen
            TabManager.main.post { sync.reset(); screenChanged() }
            TabManager.cancelPrompts(this)
            runCatching { c.client.disconnect() }
            agents.value = emptyList()
            questions.value = emptyMap()
            TabManager.agentsChanged(this)
            if (closing) break
            val err = result.exceptionOrNull()
            when {
                err == null -> {
                    // the remote program ended (exit, or tmux detach): don't reconnect by itself
                    status.value = Status.Ended("session ended")
                    note("session ended - tap Reconnect, or close the tab")
                    waitFor(Long.MAX_VALUE)
                    attempt = 0
                }
                userStopped || err is LoginCancelled || err is UserAuthException || !host.autoReconnect -> {
                    val why = if (userStopped) "server not trusted" else err.reason()
                    status.value = Status.Disconnected(why, null)
                    note("disconnected: $why")
                    waitFor(Long.MAX_VALUE)
                    attempt = 0
                }
                else -> {
                    attempt++
                    val delay = min(30_000L, 1_000L shl min(attempt, 5))
                    val why = err.reason()
                    status.value = Status.Disconnected(why, System.currentTimeMillis() + delay)
                    note("connection lost: $why - retrying in ${delay / 1000}s")
                    waitFor(delay)
                }
            }
        }
    }

    private fun Throwable.reason(): String =
        (message ?: javaClass.simpleName).lineSequence().first().take(120)

    private fun waitFor(ms: Long) = synchronized(lock) {
        val end = if (ms == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + ms
        while (!wakeUp && !closing) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) break
            lock.wait(min(left, 60_000L))
        }
        wakeUp = false
    }

    private fun newClient(): SSHClient {
        val config = AndroidConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
        return SSHClient(config).apply {
            connectTimeout = 10_000
            // the host key question waits for the user; don't let the key exchange time out meanwhile
            transport.timeoutMs = 120_000
            addHostKeyVerifier(HostKeys(ctx) { t, m, danger ->
                TabManager.confirm(this@SshTab, t, m, danger).also { if (!it) userStopped = true }
            })
        }
    }

    /** Connects, logs in, runs the shell and pumps its output until it ends. Throws when the connection fails. */
    private fun session(c: Conn, onConnected: () -> Unit) {
        val client = c.client
        client.connect(host.host, host.port)
        client.connection.keepAlive.keepAliveInterval = 5
        (client.connection.keepAlive as? KeepAliveRunner)?.maxAliveCount = 4
        val usedPassword = login(client)

        val s = client.startSession() as SessionChannel
        s.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap())
        val cmd = host.remoteCommand()
        val io: Session.Shell? = if (cmd == null) s.startShell() else null
        val ex: Session.Command? = if (cmd != null) s.exec(cmd) else null
        c.channel = s
        c.out = io?.outputStream ?: ex!!.outputStream
        val input = io?.inputStream ?: ex!!.inputStream
        // the keyboard may have opened while we were connecting
        pushWindowSize(c)
        status.value = Status.Connected
        onConnected()
        if (usedPassword && !host.keyInstalled) thread(name = "ssh-key-$id") { offerKeyInstall() }
        thread(name = "herdr-$id", isDaemon = true) { watchAgents(c) }

        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            received(buf, n, c)
        }
        // EOF: a clean end only if the server reported how the program finished
        if (s.exitStatus == null && s.exitSignal == null && !closing) throw java.io.IOException("connection closed")
    }

    /** key first, then a saved or typed password. Returns true when the password was used. */
    private fun login(client: SSHClient): Boolean {
        val key = runCatching {
            client.loadKeys(KeyPairStore.privateKey(ctx), null as String?, null as PasswordFinder?)
        }.getOrNull()
        if (key != null && runCatching { client.authPublickey(host.user, key) }.isSuccess) return false

        var saved = host.passwordEnc.takeIf { it.isNotEmpty() }?.let { SecretBox.decrypt(it) }
        repeat(3) { i ->
            val (pw, remember) = saved?.let { it to false } ?: TabManager.askPassword(
                this, "Password for ${host.label}", if (i == 0) "" else "Wrong password, try again.",
            ) ?: throw LoginCancelled()
            try {
                client.authPassword(host.user, pw)
                if (remember) Hosts.update(host.id) { it.copy(passwordEnc = SecretBox.encrypt(pw)) }
                return true
            } catch (e: UserAuthException) {
                saved = null
            }
        }
        throw UserAuthException("login failed")
    }

    private fun offerKeyInstall() {
        val yes = TabManager.confirm(
            this,
            "Log in without password?",
            "Install this phone's SSH key on ${host.name}? Next time you're logged in with the key, " +
                "no password needed. (Adds one line to ~/.ssh/authorized_keys.)",
            false,
        )
        if (!yes) { Hosts.update(host.id) { it.copy(keyInstalled = true) }; return }
        val c = conn ?: return
        val installed = runCatching { installKey(c.client, KeyPairStore.publicKey(ctx)) }.getOrDefault(false)
        // only trust it after a real key login worked
        val works = installed && runCatching { verifyKeyLogin() }.getOrDefault(false)
        if (works) {
            Hosts.update(host.id) { it.copy(keyInstalled = true, passwordEnc = "") }
            TabManager.toast("Key installed on ${host.name} - saved password removed")
        } else TabManager.toast("Could not set up key login on ${host.name}; the password still works")
    }

    private fun verifyKeyLogin(): Boolean {
        val c = newClient()
        try {
            c.connect(host.host, host.port)
            c.authPublickey(host.user, c.loadKeys(KeyPairStore.privateKey(ctx), null as String?, null as PasswordFinder?))
            return c.isAuthenticated
        } finally {
            runCatching { c.disconnect() }
        }
    }

    // ---------------------------------------------------------------- herdr agents

    private fun watchAgents(c: Conn) {
        val active = { conn === c && !closing }
        // live: herdr's event stream through a small watcher on the server; changes arrive at once
        while (active()) {
            val end = runCatching {
                Herdr.stream(c.client, active) { u ->
                    if (!active()) return@stream
                    liveAgents = true
                    agents.value = u?.agents ?: emptyList()
                    questions.value = u?.questions ?: emptyMap()
                    TabManager.agentsChanged(this)
                }
            }.getOrDefault(Herdr.StreamEnd.ENDED)
            liveAgents = false
            if (end == Herdr.StreamEnd.UNAVAILABLE) break
            try { Thread.sleep(3_000) } catch (_: InterruptedException) { return }
        }
        // no python3 on the server: ask herdr every few seconds instead
        while (active()) {
            val list = runCatching { Herdr.agents(c.client) }.getOrNull()
            if (!active()) break
            agents.value = list ?: emptyList()
            questions.value = emptyMap()
            TabManager.agentsChanged(this)
            // herdr not there: look again once a minute
            try { Thread.sleep(if (list == null) 60_000 else 3_000) } catch (_: InterruptedException) { break }
        }
    }

    /** answer a waiting agent's question without bringing it on screen; false if the question changed meanwhile */
    fun answerAgent(paneId: String, choice: app.kabuk.text.Choice, expect: String, done: (Boolean) -> Unit) {
        val c = conn ?: return done(false)
        // each question is answered once: a double tap (or notification + inbox) must not send twice
        synchronized(answered) {
            if (!answered.add("$paneId\u0000$expect")) return done(false)
            if (answered.size > 200) answered.remove(answered.first())
        }
        thread {
            val ok = runCatching { Herdr.answer(c.client, paneId, choice.key, expect) }.getOrDefault(false)
            // not sent (question changed, connection down): this question may be answered again
            if (!ok) synchronized(answered) { answered.remove("$paneId\u0000$expect") }
            TabManager.main.post { done(ok) }
        }
    }
    private val answered = LinkedHashSet<String>()

    /** done(null) = started, else what went wrong */
    fun startAgent(dir: String, tool: String, done: (String?) -> Unit) {
        val c = conn ?: return done("Not connected")
        thread {
            val err = try {
                if (Herdr.startAgent(c.client, dir, tool)) null else "Couldn't start $tool"
            } catch (e: java.io.FileNotFoundException) {
                "There is no folder $dir on ${host.name}"
            } catch (e: Exception) {
                "Couldn't start $tool: ${e.message}"
            }
            TabManager.main.post { done(err) }
        }
    }

    fun focusAgent(a: Agent, done: (Boolean) -> Unit) {
        val c = conn ?: return done(false)
        thread {
            val ok = runCatching { Herdr.focus(c.client, a) }.getOrDefault(false)
            TabManager.main.post { done(ok) }
        }
    }

    /** the focused herdr pane, else the tmux pane history, else our own scrollback; plus where it came from */
    override fun captureHistory(done: (String, String) -> Unit) {
        val c = conn ?: return super.captureHistory(done)
        thread {
            val herdr = runCatching { Herdr.readFocused(c.client) }.getOrNull()?.takeIf { it.second.isNotBlank() }
            val tmux = if (herdr == null && host.session != Host.SHELL) runCatching {
                exec(c.client, "tmux capture-pane -p -J -S -20000 -t ${shellQuote(host.tmuxSession)}").second
            }.getOrNull()?.takeIf { it.isNotBlank() } else null
            TabManager.main.post {
                when {
                    herdr != null -> done(herdr.second.trimEnd() + "\n", "herdr · ${herdr.first}")
                    tmux != null -> done(tmux.trimEnd() + "\n", "tmux · ${host.tmuxSession}")
                    else -> super.captureHistory(done)
                }
            }
        }
    }

    /** puts a file into ~/.cache/kabuk/ on the server (SFTP) and returns its full path */
    fun upload(bytes: ByteArray, name: String, done: (String?) -> Unit) {
        val c = conn ?: return done(null)
        thread {
            val path = runCatching {
                c.client.newSFTPClient().use { sftp ->
                    val dir = sftp.canonicalize(".") + "/.cache/kabuk"
                    sftp.mkdirs(dir)
                    val path = "$dir/$name"
                    sftp.put(object : net.schmizz.sshj.xfer.InMemorySourceFile() {
                        override fun getName() = name
                        override fun getLength() = bytes.size.toLong()
                        override fun getInputStream() = bytes.inputStream()
                    }, path)
                    path
                }
            }.getOrNull()
            TabManager.main.post { done(path) }
        }
    }

    /** ends the tmux session on the server too (Claude and everything else in it stops) */
    fun killRemoteSession(done: (Boolean) -> Unit) {
        val c = conn
        thread {
            val ok = c != null && c.client.isConnected &&
                runCatching { exec(c.client, "tmux kill-session -t ${shellQuote(host.tmuxSession)}").first == 0 }.getOrDefault(false)
            // killing the session also ends our shell, and the cleanup of that can cut off the command's own
            // answer: then "the session ended" is the proof it worked
            var ended = ok
            val until = System.currentTimeMillis() + 3000
            while (!ended && c != null && System.currentTimeMillis() < until) {
                ended = status.value is Status.Ended
                Thread.sleep(100)
            }
            TabManager.main.post { done(ended) }
        }
    }

    override fun reconnect() {
        TabManager.cancelPrompts(this)
        val c = conn
        thread { runCatching { c?.client?.disconnect() } }
        synchronized(lock) { wakeUp = true; lock.notifyAll() }
    }

    /** the phone switched networks: an open connection is dead, a waiting one should retry now */
    fun networkChanged() {
        when (val s = status.value) {
            is Status.Connected -> { val c = conn; thread { runCatching { c?.client?.disconnect() } } }
            // only automatic retries; a stopped tab waits for the user
            is Status.Disconnected -> if (s.retryAt != null) synchronized(lock) { wakeUp = true; lock.notifyAll() }
            else -> {}
        }
    }

    override fun close() {
        val c: Conn?
        synchronized(pendingLock) {
            closing = true
            c = conn
            pending.reset()
            pendingLock.notifyAll()
        }
        synchronized(lock) { lock.notifyAll() }
        TabManager.cancelPrompts(this)
        thread { runCatching { c?.client?.disconnect() } }
        writer.shutdown()
    }

    // last in the class: every field above must be initialised before the connection thread runs
    init {
        thread(name = "ssh-$id", isDaemon = true) { loop() }
    }

    class LoginCancelled : Exception("login cancelled")

    companion object {
        /** run one command on an existing connection: (exit status, stdout). Gives up after [timeoutS]. */
        fun exec(c: SSHClient, cmd: String, timeoutS: Long = 15, maxBytes: Int = 8 * 1024 * 1024): Pair<Int?, String> {
            // one deadline for everything: opening the channel, running, reading
            val pool = Executors.newSingleThreadExecutor()
            var session: Session? = null
            var command: Session.Command? = null
            val f = pool.submit<Pair<Int?, String>> {
                val s = c.startSession().also { session = it }
                val ch = s.exec(cmd).also { command = it }
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                val inp = ch.inputStream
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    if (out.size() < maxBytes) out.write(buf, 0, min(n, maxBytes - out.size()))
                }
                ch.join(2, TimeUnit.SECONDS)
                ch.exitStatus to out.toString(Charsets.UTF_8.name())
            }
            try {
                return f.get(timeoutS, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                runCatching { command?.signal(net.schmizz.sshj.connection.channel.direct.Signal.TERM) }
                throw java.io.IOException("'${cmd.take(40)}' took longer than ${timeoutS}s")
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            } finally {
                pool.shutdownNow()
                // the server confirms closing a hung command only once it stopped: don't wait for that
                val s = session
                if (s != null) thread(isDaemon = true) { runCatching { s.close() } }
            }
        }

        fun installKey(c: SSHClient, pub: String): Boolean {
            val k = shellQuote(pub.trim())
            val f = "~/.ssh/authorized_keys"
            val cmd = "k=$k; mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch $f && chmod 600 $f && " +
                // make sure the last existing key ends with a newline before appending
                "{ [ ! -s $f ] || [ -z \"\$(tail -c 1 $f)\" ] || echo >> $f; } && " +
                "(grep -qxF \"\$k\" $f || printf '%s\\n' \"\$k\" >> $f)"
            return exec(c, cmd).first == 0
        }
    }
}
