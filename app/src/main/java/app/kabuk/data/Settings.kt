package app.kabuk.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Prefs(
    val fontSizeSp: Float = 13f,
    val theme: String = "Kabuk",
    val keepScreenOn: Boolean = true,
    val vibrateKeys: Boolean = true,
    val bellVibrate: Boolean = true,
    /** Termux-style: Volume Down = Ctrl, Volume Up = Fn (W/A/S/D arrows, E Esc, T Tab, ...) */
    val volumeKeys: Boolean = false,
    /** some keyboards (Samsung) misbehave with raw input; this uses a "visible password" field instead */
    val compatKeyboard: Boolean = false,
    /** the message box starts hidden; the ✎ key opens it */
    val showPromptBox: Boolean = false,
    val scrollbackLines: Int = 5000,
    /** rows scrolled per row the finger moves: 1 Normal, 2 Fast, 3 Fastest */
    val scrollSpeed: Int = 2,
    /** keep sent prompts for the ↺ list (stored encrypted) */
    val rememberPrompts: Boolean = true,
    /** notify when an agent finishes (needs-you notifications are always on) */
    val notifyFinished: Boolean = true,
)

object Settings {
    private lateinit var sp: SharedPreferences
    private val _prefs = MutableStateFlow(Prefs())
    val prefs: StateFlow<Prefs> = _prefs
    val current get() = _prefs.value

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val d = Prefs()
        _prefs.value = Prefs(
            fontSizeSp = sp.getFloat("fontSizeSp", d.fontSizeSp),
            theme = sp.getString("theme", d.theme) ?: d.theme,
            keepScreenOn = sp.getBoolean("keepScreenOn", d.keepScreenOn),
            vibrateKeys = sp.getBoolean("vibrateKeys", d.vibrateKeys),
            bellVibrate = sp.getBoolean("bellVibrate", d.bellVibrate),
            volumeKeys = sp.getBoolean("volumeKeys", d.volumeKeys),
            compatKeyboard = sp.getBoolean("compatKeyboard", d.compatKeyboard),
            // own key since 0.4.2: until then "show" was the default and got saved on every phone
            showPromptBox = sp.getBoolean("promptBoxOpen", d.showPromptBox),
            scrollbackLines = sp.getInt("scrollbackLines", d.scrollbackLines),
            scrollSpeed = sp.getInt("scrollSpeed", d.scrollSpeed),
            rememberPrompts = sp.getBoolean("rememberPrompts", d.rememberPrompts),
            notifyFinished = sp.getBoolean("notifyFinished", d.notifyFinished),
        )
        sp.edit().remove("promptHistory").remove("showPromptBox").apply()   // 0.1.x history in plain text; old default
        Hosts.init(ctx)
    }

    fun update(f: (Prefs) -> Prefs) {
        val p = f(_prefs.value)
        _prefs.value = p
        sp.edit()
            .putFloat("fontSizeSp", p.fontSizeSp).putString("theme", p.theme)
            .putBoolean("keepScreenOn", p.keepScreenOn).putBoolean("vibrateKeys", p.vibrateKeys)
            .putBoolean("bellVibrate", p.bellVibrate).putBoolean("volumeKeys", p.volumeKeys)
            .putBoolean("compatKeyboard", p.compatKeyboard).putBoolean("promptBoxOpen", p.showPromptBox)
            .putInt("scrollbackLines", p.scrollbackLines).putInt("scrollSpeed", p.scrollSpeed)
            .putBoolean("rememberPrompts", p.rememberPrompts).putBoolean("notifyFinished", p.notifyFinished)
            .apply()
    }

    /** prompt-box history, newest first (encrypted with the Keystore) */
    fun history(): List<String> {
        val enc = sp.getString("promptHistoryEnc", null) ?: return emptyList()
        return (SecretBox.decrypt(enc) ?: "").split('\u0000').filter { it.isNotEmpty() }
    }

    fun addHistory(s: String) {
        if (!current.rememberPrompts) return
        val h = (listOf(s) + history().filterNot { it == s }).take(50)
        sp.edit().putString("promptHistoryEnc", SecretBox.encrypt(h.joinToString("\u0000"))).apply()
    }

    fun clearHistory() = sp.edit().remove("promptHistoryEnc").apply()

    /** quick inserts for the ⚡ menu */
    val defaultSnippets = listOf("/clear", "/compact", "/model", "/resume", "/review", "/status", "/cost", "continue", "commit this")

    fun snippets(): List<String> =
        sp.getString("snippets", null)?.split('\u0000')?.filter { it.isNotEmpty() } ?: defaultSnippets

    fun saveSnippets(l: List<String>) = sp.edit().putString("snippets", l.joinToString("\u0000")).apply()

    /** secret that home-screen shortcuts carry, so other apps can't make Kabuk connect */
    fun shortcutToken(): String = sp.getString("shortcutToken", null) ?: java.util.UUID.randomUUID().toString()
        .also { sp.edit().putString("shortcutToken", it).apply() }

    fun saveLastTabs(hostIds: List<String>) = sp.edit().putString("lastTabs", hostIds.joinToString(",")).apply()
    fun lastTabs(): List<String> = sp.getString("lastTabs", "")!!.split(',').filter { it.isNotEmpty() }
}
