package app.kabuk.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Host(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    /** encrypted with [SecretBox]; empty = ask when connecting */
    val passwordEnc: String = "",
    /**
     * What keeps programs running when the connection drops: [AUTO] = herdr if the server has it, else tmux,
     * else a plain shell; [TMUX]; [SHELL] = nothing (plain login shell or [startupCommand]).
     */
    val session: String = AUTO,
    val tmuxSession: String = "main",
    /** run instead of a plain shell (only with [SHELL]) */
    val startupCommand: String = "",
    val autoReconnect: Boolean = true,
    /** the phone's key has been installed on this server */
    val keyInstalled: Boolean = false,
    val color: Long = 0xFF7AA2F7,
) {
    val label get() = "$user@$host" + if (port != 22) ":$port" else ""

    fun remoteCommand(): String? {
        val tmux = "command -v tmux >/dev/null 2>&1 && exec tmux -u new-session -A -s ${shellQuote(tmuxSession.ifBlank { "main" })} \\; set-option mouse on; "
        // herdr's own server keeps the agents running; `herdr` attaches to its persistent session
        // (often only in ~/.local/bin, which a non-login shell doesn't have on PATH)
        val herdr = "H=\$(command -v herdr || { [ -x \"\$HOME/.local/bin/herdr\" ] && echo \"\$HOME/.local/bin/herdr\"; }); " +
            "[ -n \"\$H\" ] && exec \"\$H\"; "
        // servers without either (Proxmox, routers) get a normal login shell instead of closing right away
        val shell = "printf '\\033[2m[kabuk] no herdr or tmux here: plain shell, it ends when the connection drops]\\033[0m\\r\\n'; " +
            "exec \"\${SHELL:-/bin/sh}\" -l"
        // wrapped in sh so it works whatever the login shell is (bash, zsh, fish)
        return when (session) {
            AUTO -> "sh -c " + shellQuote(herdr + tmux + shell)
            TMUX -> "sh -c " + shellQuote(tmux + shell)
            else -> startupCommand.ifBlank { null }
        }
    }

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("host", host); put("port", port); put("user", user)
        put("passwordEnc", passwordEnc); put("session", session); put("tmuxSession", tmuxSession)
        put("startupCommand", startupCommand); put("autoReconnect", autoReconnect)
        put("keyInstalled", keyInstalled); put("color", color)
    }

    companion object {
        const val AUTO = "auto"
        const val TMUX = "tmux"
        const val SHELL = "shell"

        fun fromJson(o: JSONObject) = Host(
            id = o.getString("id"), name = o.getString("name"), host = o.getString("host"),
            port = o.optInt("port", 22), user = o.getString("user"),
            passwordEnc = o.optString("passwordEnc"),
            // before 0.3.3 there was only "use tmux": on = Auto now (herdr first), off = plain shell
            session = o.optString("session", "").ifEmpty { if (o.optBoolean("useTmux", true)) AUTO else SHELL },
            tmuxSession = o.optString("tmuxSession", "main"), startupCommand = o.optString("startupCommand"),
            autoReconnect = o.optBoolean("autoReconnect", true), keyInstalled = o.optBoolean("keyInstalled"),
            color = o.optLong("color", 0xFF7AA2F7),
        )
    }
}

fun shellQuote(s: String) = "'" + s.replace("'", "'\"'\"'") + "'"

object Hosts {
    private lateinit var prefs: SharedPreferences
    private val _all = MutableStateFlow<List<Host>>(emptyList())
    val all: StateFlow<List<Host>> = _all

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("hosts", Context.MODE_PRIVATE)
        val raw = prefs.getString("list", null)
        _all.value = if (raw == null) {
            // first start: the server list offers "Add server"
            emptyList()
        } else {
            val arr = JSONArray(raw)
            List(arr.length()) { Host.fromJson(arr.getJSONObject(it)) }
        }
    }

    fun get(id: String) = _all.value.firstOrNull { it.id == id }

    /** change some fields of the latest saved version (safe against concurrent edits) */
    @Synchronized
    fun update(id: String, f: (Host) -> Host) {
        val cur = get(id) ?: return
        save(f(cur))
    }

    @Synchronized
    fun save(h: Host) {
        val list = _all.value.toMutableList()
        val i = list.indexOfFirst { it.id == h.id }
        if (i >= 0) list[i] = h else list += h
        persist(list)
    }

    @Synchronized
    fun delete(id: String) = persist(_all.value.filterNot { it.id == id })

    private fun persist(list: List<Host>) {
        _all.value = list
        prefs.edit().putString("list", JSONArray(list.map { it.toJson() }).toString()).apply()
    }
}
