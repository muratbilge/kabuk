package app.kabuk.agents

import app.kabuk.text.Pending

/** What came of an answer sent from this phone; [text] is what the user is told when it wasn't sent. */
enum class AnswerOutcome(val text: String) {
    SENT("Sent"),
    /** this phone answered that very dialog already (a double tap, or the agent hasn't redrawn yet) */
    ALREADY("Already answered from this phone"),
    /** the dialog changed meanwhile, or another client answered it first */
    CHANGED("Not sent: the question changed"),
    /** a bus question that was answered already (by Telegram, the TUI, …) */
    TAKEN("Not sent: already answered"),
    OFFLINE("Not sent: not connected"),
}

/**
 * Which questions were already answered from this phone, so a double tap (or the notification and the inbox)
 * can't send the same answer twice. A dialog on an agent's screen is keyed by pane + fingerprint and forgotten
 * as soon as that dialog has left the screen: the same approval asked again later is a new question and may be
 * answered again. A question on the orchtool bus is keyed by its id (unique; the server lets the first answer win).
 */
class AnswerLedger {
    private val keys = LinkedHashMap<String, String>()   // key -> what was sent (a choice key, an answer)

    fun screenKey(pane: String, fingerprint: String) = "s:$pane\u0000$fingerprint"
    fun questionKey(id: Long) = "q:$id"

    /** true when nobody claimed [key] yet (the caller may send [what]); false when an answer is already on its way */
    @Synchronized fun claim(key: String, what: String = ""): Boolean {
        if (keys.containsKey(key)) return false
        keys[key] = what
        if (keys.size > CAP) keys.remove(keys.keys.first())
        return true
    }

    /** the send didn't happen: the question may be answered again */
    @Synchronized fun release(key: String) { keys.remove(key) }

    /** what this phone sent for [key], or null when it didn't (yet) */
    @Synchronized fun what(key: String): String? = keys[key]

    /** [open] = the dialogs on screen right now, by pane; the entries of dialogs that left or changed are forgotten */
    @Synchronized fun prune(open: Map<String, Pending>) {
        keys.keys.removeAll { k ->
            val sep = k.indexOf('\u0000')
            k.startsWith("s:") && sep >= 0 && open[k.substring(2, sep)]?.fingerprint != k.substring(sep + 1)
        }
    }

    @Synchronized fun size() = keys.size

    private companion object { const val CAP = 200 }
}
