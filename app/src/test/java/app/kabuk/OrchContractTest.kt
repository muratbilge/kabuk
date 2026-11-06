package app.kabuk

import app.kabuk.agents.AnswerOutcome
import app.kabuk.agents.Orch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The contract with orchtool (notes/kabuk-contract-v2.md there): the watch line's version fields, the shared
 * fixture, orch's exit-code table, and that those codes reach Kabuk through its own sh wrapper.
 */
class OrchContractTest {
    private fun res(n: String) = javaClass.classLoader!!.getResource(n).readText()

    @Test fun versionFields() {
        val u = Orch.parseUpdate(res("orch-watch-v1.json").trim())!!
        assertEquals(1, u.v)
        assertEquals("0.1.0", u.orch)
        assertEquals("webshop-claude", u.agents.single().bus)
        // orchtool 0.1.0 sent neither field: the first format, no version string
        val old = Orch.parseUpdate("""{"agents":[],"questions":[],"screens":{},"timeline":[]}""")!!
        assertEquals(Orch.V, old.v)
        assertEquals("", old.orch)
        // a newer format still parses (unknown fields are ignored); the caller tells the user to update
        assertEquals(3, Orch.parseUpdate("""{"v":3,"orch":"9.9.9","agents":[],"extra":1}""")!!.v)
    }

    @Test fun exitCodeTable() {
        assertEquals(AnswerOutcome.SENT, Orch.outcomeOf(0))
        assertEquals(AnswerOutcome.CHANGED, Orch.outcomeOf(10))
        assertEquals(AnswerOutcome.TAKEN, Orch.outcomeOf(11))
        assertEquals(AnswerOutcome.GONE, Orch.outcomeOf(12))
        assertEquals(AnswerOutcome.STUCK, Orch.outcomeOf(13))
        assertEquals(AnswerOutcome.GONE, Orch.outcomeOf(14))
        assertEquals(AnswerOutcome.DAEMON, Orch.outcomeOf(20))
        assertEquals(AnswerOutcome.DAEMON, Orch.outcomeOf(3))
        assertEquals(AnswerOutcome.MISMATCH, Orch.outcomeOf(2))
        assertEquals(AnswerOutcome.UNSURE, Orch.outcomeOf(null))
        // an orch from before the table says 1 for everything: not a guess
        assertEquals(AnswerOutcome.FAILED, Orch.outcomeOf(1))
        assertEquals(AnswerOutcome.FAILED, Orch.outcomeOf(99))
    }

    private fun shOut(cmd: String, home: File): Pair<Int, String> {
        val p = ProcessBuilder("/bin/sh", "-c", cmd).redirectErrorStream(true)
        p.environment().apply { clear(); put("PATH", "/usr/bin:/bin"); put("HOME", home.absolutePath) }
        val proc = p.start()
        val out = proc.inputStream.readBytes().decodeToString()
        return proc.waitFor() to out
    }

    /** the new-workspace dialog asks the server about the folder first: exists, missing (and ~/x → /x), the listing */
    @Test fun folders() {
        val home = createTempDir()
        File(home, "a").mkdirs(); File(home, ".hidden").mkdirs()
        fun check(d: String) = shOut(Orch.checkFolderCommand(d), home).let { (c, o) -> Orch.parseFolderCheck(c, o) }
        assertEquals(Orch.FolderCheck.Exists(File(home, "a").absolutePath), check("~/a"))
        assertEquals(Orch.FolderCheck.Exists(File(home, "a").absolutePath), check("a"))
        assertEquals(Orch.FolderCheck.Exists(home.absolutePath), check("~"))
        // ~/usr isn't there, /usr is: suggested (the ~/projects/x → /projects/x case)
        assertEquals(Orch.FolderCheck.Missing("${home.absolutePath}/usr", "/usr"), check("~/usr"))
        assertEquals(Orch.FolderCheck.Missing("${home.absolutePath}/no such", null), check("~/no such"))
        assertEquals(Orch.FolderCheck.Missing("/no/such/dir", null), check("/no/such/dir"))
        assertTrue(Orch.parseFolderCheck(null, "") is Orch.FolderCheck.Failed)
        val (code, out) = shOut(Orch.LIST_FOLDERS, home)
        assertEquals(0, code)
        val listed = out.lines()
        assertTrue(home.absolutePath in listed)
        assertTrue(File(home, "a").absolutePath in listed)
        assertFalse(listed.any { it.endsWith("/.hidden") })
        home.deleteRecursively()
    }

    @Test fun folderSuggestions() {
        val listed = listOf("/home/murat", "/home/murat/bin", "/projects/kabuk", "/projects/orchtool", "/projects/paperscanner")
        val known = listOf("/projects/kabuk", "/home/murat")
        assertEquals(listOf("/projects/kabuk", "~", "~/bin", "/projects/orchtool", "/projects/paperscanner"),
            app.kabuk.ui.folderSuggestions(known, listed, ""))
        // typed with ~/ in front, as if the projects were in home: still found
        assertEquals(listOf("/projects/kabuk"), app.kabuk.ui.folderSuggestions(known, listed, "~/projects/kab"))
        assertEquals(listOf("/projects/orchtool"), app.kabuk.ui.folderSuggestions(known, listed, "ORCH"))
        // what is typed already isn't offered again; starts-with first
        assertEquals(listOf("/projects/paperscanner"), app.kabuk.ui.folderSuggestions(known, listed, "/projects/pa"))
        assertTrue(app.kabuk.ui.folderSuggestions(known, listed, "/projects/kabuk").isEmpty())
    }

    private fun sh(cmd: String, home: File): Int {
        val p = ProcessBuilder("/bin/sh", "-c", cmd).redirectErrorStream(true)
        p.environment().apply { clear(); put("PATH", "/usr/bin:/bin"); put("HOME", home.absolutePath) }
        val proc = p.start()
        proc.inputStream.readBytes()
        return proc.waitFor()
    }

    /** the exact command Kabuk sends, through a real sh, against a fake orch that checks its arguments */
    @Test fun exitCodesPassThroughTheWrapper() {
        val home = createTempDir()
        File(home, ".local/bin").mkdirs()
        val orch = File(home, ".local/bin/orch")
        orch.writeText(
            "#!/bin/sh\n" +
                "[ \"\$1\" = send ] && [ \"\$2\" = 'orch:p1' ] && [ \"\$3\" = 1 ] && [ \"\$4\" = --expect ] && [ \"\$5\" = abc ] || exit 99\n" +
                "exit 11\n",
        )
        orch.setExecutable(true)
        assertEquals(11, sh(Orch.sendCommand("orch:p1", "1", "abc"), home))
        // Esc is a key press, never the escape byte as text
        val esc = Orch.sendCommand("orch:p1", Orch.ESC, "abc")
        assertTrue(esc, esc.contains(" --key Escape "))
        assertFalse(esc.contains("\u001b"))
        assertEquals(99, sh(esc, home))
        // no orch on the server at all: the wrapper's own 3
        orch.delete()
        assertEquals(3, sh(Orch.sendCommand("orch:p1", "1", "abc"), home))
        home.deleteRecursively()
    }

    /** after a silent first try, "answered" means it landed and "changed" may mean the same */
    @Test fun retryPolicy() {
        assertEquals(AnswerOutcome.SENT, Orch.sendOutcome(0, retried = false).outcome)
        assertEquals(AnswerOutcome.SENT, Orch.sendOutcome(0, retried = true).outcome)
        assertEquals(AnswerOutcome.SENT, Orch.sendOutcome(11, retried = true).outcome)
        assertEquals(AnswerOutcome.TAKEN, Orch.sendOutcome(11, retried = false).outcome)
        assertEquals(AnswerOutcome.UNSURE, Orch.sendOutcome(10, retried = true).outcome)
        assertEquals(AnswerOutcome.CHANGED, Orch.sendOutcome(10, retried = false).outcome)
        assertEquals(AnswerOutcome.UNSURE, Orch.sendOutcome(null, retried = true).outcome)
        assertEquals(AnswerOutcome.UNSURE, Orch.sendOutcome(null, retried = false).outcome)
        assertEquals(AnswerOutcome.DAEMON, Orch.sendOutcome(20, retried = true).outcome)
        assertEquals("won", Orch.sendOutcome(0, retried = false, what = "won").why)
    }

    /** orchtool e3a0257: the screen hash of every blocked pane rides along, so an answer is one call */
    @Test fun screenHashes() {
        val u = Orch.parseUpdate(res("orch-watch-v1.json").trim())!!
        // orchtool 054d83c: the hash is over the normalized screen text
        assertEquals("67956e90aae93038", u.hashes["orch:p1"])
        assertEquals(u.screens.keys, u.hashes.keys)
        // an orch without the field: no hashes, the answer path rereads the pane
        assertTrue(Orch.parseUpdate("""{"agents":[],"questions":[],"screens":{},"timeline":[]}""")!!.hashes.isEmpty())
    }

    /** contract §2, since orchtool 7c47764: an agent paused on its usage limit, and orchd's per-pane auto-continue switch */
    @Test fun limitedAgent() {
        val line = """{"v":1,"human":"murat","agents":[{"name":"kabuk-codex","kind":"codex","status":"limited","status_text":"usage limit · continues 08:54",""" +
            """"pane":"orch:p3","cwd":"/w","workspace":"w","workspace_number":1,"focused":false,"seen_ms":null,"registered":true,""" +
            """"limit_reset":1762243980000,"auto_continue":true}],"questions":[],"screens":{},"timeline":[]}"""
        val a = Orch.parseUpdate(line)!!.agents.single()
        assertTrue(a.limited)
        assertFalse(a.needsYou || a.working || a.resting)
        assertEquals(1762243980000L, a.limitReset)
        assertEquals("orchd continues a minute after the reset", 1762244040000L, a.resumesAt)
        assertTrue(a.autoContinue)
        assertEquals("usage limit · continues 08:54", a.statusText)
        // switched off, and no reset time on its screen
        val off = Orch.parseUpdate(line.replace("\"auto_continue\":true", "\"auto_continue\":false").replace("1762243980000", "null"))!!.agents.single()
        assertFalse(off.autoContinue)
        assertEquals(0L, off.limitReset)
        assertEquals(0L, off.resumesAt)
        // an orch from before the fields (the fixture): not paused, auto-continue on
        val old = Orch.parseUpdate(res("orch-watch-v1.json").trim())!!.agents.single()
        assertFalse(old.limited)
        assertEquals(0L, old.limitReset)
        assertTrue(old.autoContinue)
    }

    /** orchtool 1f30b5f: the layout rides along for the native workspace bar; tabs are known by id */
    @Test fun workspaces() {
        val u = Orch.parseUpdate(res("orch-watch-v1.json").trim())!!
        val w = u.workspaces.single()
        assertEquals("w1", w.id)
        assertEquals(1, w.number)
        assertEquals("proj", w.label)
        assertEquals("proj", w.place)
        assertEquals("blocked", w.status)
        assertTrue(w.shown)
        assertEquals(0, w.activeTab)
        val t = w.tabs.single()
        assertEquals("t1", t.id)
        assertEquals(0, t.index)
        assertEquals(listOf("orch:p1"), t.panes)
        assertEquals("orch:p1", t.focus)
        assertEquals("blocked", t.status)
        assertFalse(t.zoomed)
        // the agent in that tab is the one the stream lists
        assertEquals(u.agents.single().paneId, t.focus)
        // an orch without the field: no bar
        assertTrue(Orch.parseUpdate("""{"agents":[],"questions":[],"screens":{},"timeline":[]}""")!!.workspaces.isEmpty())
        // null status and focus (no agent, an empty tab) are "", never the string "null"; no label = the folder's name
        val bare = Orch.parseUpdate(
            """{"v":1,"agents":[],"questions":[],"screens":{},"timeline":[],"workspaces":[{"id":"w2","number":1,"label":"",""" +
                """"cwd":"/home/x/proj","status":null,"shown":false,"active_tab":0,"tabs":[{"index":0,"id":"t3","label":"1",""" +
                """"panes":[],"focus":null,"zoomed":false,"status":null}]}]}""",
        )!!.workspaces.single()
        assertEquals("", bare.status)
        assertEquals("proj", bare.place)
        assertEquals("", bare.tabs.single().focus)
        assertEquals("", bare.tabs.single().status)
    }

    /**
     * The layout commands as sent, through a real sh against a fake orch: a label with spaces stays one argument,
     * exit 14 (gone) reaches Kabuk, `new` runs through the login shell, focuses the pane it printed and passes
     * orch's own code on, and a missing folder is the wrapper's 4.
     */
    @Test fun layoutCommands() {
        val home = createTempDir()
        File(home, ".local/bin").mkdirs()
        val orch = File(home, ".local/bin/orch")
        orch.writeText(
            "#!/bin/sh\n" +
                "if [ \"\$1\" = tab ] && [ \"\$2\" = rename ]; then [ \"\$3\" = w1 ] && [ \"\$4\" = t1 ] && [ \"\$5\" = -- ] && [ \"\$6\" = 'my tab' ] && [ \$# = 6 ] || exit 99; exit 14; fi\n" +
                "if [ \"\$1\" = tab ] && [ \"\$2\" = new ]; then [ \"\$3\" = w1 ] && [ \"\$4\" = -- ] && [ \"\$5\" = claude ] && [ \$# = 5 ] || exit 99; echo p9; exit 0; fi\n" +
                "if [ \"\$1\" = ws ] && [ \"\$2\" = new ] && [ \"\$6\" = made ]; then [ \"\$4\" = \"\$HOME/new/x\" ] || exit 99; echo p9; exit 0; fi\n" +
                "if [ \"\$1\" = ws ] && [ \"\$2\" = new ]; then [ \"\$3\" = --cwd ] && [ \"\$4\" = \"\$HOME\" ] && [ \"\$5\" = --label ] && [ \"\$6\" = 'my ws' ] && [ \$# = 6 ] || exit 99; exit 14; fi\n" +
                "if [ \"\$1\" = focus ]; then [ \"\$2\" = p9 ] || exit 98; exit 0; fi\n" +
                "if [ \"\$1\" = auto-continue ]; then [ \"\$2\" = orch:p3 ] && [ \"\$3\" = off ] && [ \$# = 3 ] || exit 99; exit 0; fi\n" +
                "if [ \"\$1\" = question ] && [ \"\$2\" = close ]; then [ \"\$3\" = 7 ] && [ \$# = 3 ] || exit 99; exit 11; fi\n" +
                "if [ \"\$1\" = read ]; then [ \"\$2\" = orch:p3 ] && [ \"\$3\" = --unwrapped ] && [ \"\$4\" = --lines ] && [ \"\$5\" = 4000 ] && [ \$# = 5 ] || exit 99; exit 0; fi\n" +
                "exit 97\n",
        )
        orch.setExecutable(true)
        assertEquals(14, sh(Orch.orchCommand("tab", "rename", "w1", "t1", "--", "my tab"), home))
        assertEquals(97, sh(Orch.orchCommand("tab", "close", "w1", "t1"), home))
        assertEquals(0, sh(Orch.newTabCommand("w1", "claude"), home))
        assertEquals(14, sh(Orch.newWorkspaceCommand(home.absolutePath, "my ws", ""), home))
        assertEquals(4, sh(Orch.newWorkspaceCommand(File(home, "no-such-folder").absolutePath, "", "codex"), home))
        // orchtool 7c47764 (contract §8): the pause switch, dismissing a question, the unwrapped copy (a herdr pane as it is)
        assertEquals(0, sh(Orch.orchCommand("auto-continue", "orch:p3", "off"), home))
        assertEquals(11, sh(Orch.orchCommand("question", "close", "7"), home))
        assertEquals(0, sh(Orch.readCommand("orch:p3", true), home))
        assertFalse(Orch.readCommand("herdr:abc", false).contains("--unwrapped"))
        assertEquals("That question was answered already", Orch.layoutError(11, "question"))
        assertEquals("That question is gone", Orch.layoutError(14, "question"))
        // a missing folder is made first when asked (mkdir -p), then the workspace starts in it
        assertEquals(4, sh(Orch.newWorkspaceCommand("~/new/x", "made", ""), home))
        assertEquals(0, sh(Orch.newWorkspaceCommand("~/new/x", "made", "", create = true), home))
        assertTrue(File(home, "new/x").isDirectory)
        assertEquals("There is no folder ~/projects/x on the server", Orch.layoutError(4, "~/projects/x"))
        assertEquals("Couldn't create /srv/x on the server", Orch.layoutError(5, "/srv/x"))
        assertEquals("That tab is gone", Orch.layoutError(14, "tab"))
        assertEquals("There is no such folder on the server", Orch.layoutError(4, "folder"))
        assertNull(Orch.layoutError(0, "workspace"))
        assertEquals("No reply from orch", Orch.layoutError(null, "workspace"))
        home.deleteRecursively()
    }

    /** orchtool P6A: the task queues in the watch line (the shared fixture has one queued task), all fields, and an older line without them */
    @Test fun tasks() {
        val fx = Orch.parseUpdate(res("orch-watch-v1.json").trim())!!
        val q = fx.tasks.single()
        assertEquals(1L, q.id); assertEquals("proj", q.target); assertEquals("queued", q.state); assertEquals("Review the change", q.text)
        assertEquals("murat", q.createdBy); assertEquals(0L, q.after); assertEquals("", q.pane); assertEquals("", q.error); assertTrue(q.open)
        val u = Orch.parseUpdate(
            """{"v":1,"agents":[],"tasks":[""" +
                """{"id":5,"target":"kabuk@t3","text":"fix it\nthen test","state":"failed","created_by":"murat","created_ms":1,"started_ms":2,"finished_ms":3,"pane":"p4","after":null,"error":"pane exited","saw_working":true},""" +
                """{"id":4,"target":"kabuk","text":"one","state":"running","created_by":"murat","created_ms":1,"started_ms":2,"finished_ms":null,"pane":"p3","after":2,"error":null,"saw_working":false}]}""",
        )!!
        assertEquals(listOf(4L, 5L), u.tasks.map { it.id })
        val (running, failed) = u.tasks
        assertEquals("kabuk", failed.workspace); assertEquals("fix it", failed.firstLine); assertEquals("pane exited", failed.error)
        assertFalse(failed.open); assertEquals(3L, failed.finishedMs); assertEquals("p4", failed.pane)
        assertEquals(2L, running.after); assertTrue(running.open); assertEquals(0L, running.finishedMs)
        // the workspace bar counts a workspace's tasks by its label (tabs included); an unnamed one can't be a target
        val ws = fx.workspaces.single()
        assertEquals(listOf(1L), app.kabuk.ui.tasksOf(ws, fx.tasks).map { it.id })
        assertEquals(listOf(4L, 5L), app.kabuk.ui.tasksOf(ws.copy(label = "kabuk"), u.tasks).map { it.id })
        assertTrue(app.kabuk.ui.tasksOf(ws.copy(label = ""), fx.tasks).isEmpty())
        // before P6A: no tasks key
        assertTrue(Orch.parseUpdate("""{"agents":[]}""")!!.tasks.isEmpty())
        // orchd's task notes arrive on the bus with their thread
        assertEquals("tasks", Orch.parseUpdate("""{"agents":[],"timeline":[{"id":9,"ts_ms":1,"from":"orchd","to":"murat","kind":"msg","text":"task #4 done","thread":"tasks"}]}""")!!.timeline.single().thread)
        assertEquals("", fx.timeline.single().thread)
    }

    /** `orch task add/cancel/done` through a real sh: the text stays one argument whatever it holds, the id comes back, the exit codes become words */
    @Test fun taskCommands() {
        val home = createTempDir()
        File(home, ".local/bin").mkdirs()
        val orch = File(home, ".local/bin/orch")
        orch.writeText(
            "#!/bin/sh\n" +
                "if [ \"\$1\" = task ] && [ \"\$2\" = add ]; then printf '%s\\0' \"\$@\" > \"\$HOME/args\"; echo 42; exit 0; fi\n" +
                "if [ \"\$1\" = task ] && [ \"\$2\" = cancel ]; then [ \"\$3\" = 7 ] && [ \$# = 3 ] || exit 99; exit 14; fi\n" +
                "if [ \"\$1\" = task ] && [ \"\$2\" = done ]; then [ \"\$3\" = 8 ] && [ \$# = 3 ] || exit 99; exit 1; fi\n" +
                "exit 97\n",
        )
        orch.setExecutable(true)
        val text = "-v first line with 'quotes', \"double\", \$HOME and `date`\nsecond line; exit 3"
        val (code, out) = shOut(Orch.addTaskCommand("kabuk@t3", text, 12), home)
        assertEquals(0, code); assertEquals("42", out.trim())
        assertEquals(listOf("task", "add", "--after", "12", "--", "kabuk@t3", text), File(home, "args").readText().split('\u0000').dropLast(1))
        shOut(Orch.addTaskCommand("kabuk", "one"), home)
        assertEquals(listOf("task", "add", "--", "kabuk", "one"), File(home, "args").readText().split('\u0000').dropLast(1))
        assertEquals(14, sh(Orch.orchCommand("task", "cancel", "7"), home))
        assertEquals(1, sh(Orch.orchCommand("task", "done", "8"), home))
        assertEquals("orch knows no such workspace or tab, or no task #12", Orch.taskError(14, "add", 12))
        assertEquals("orch knows no such workspace or tab", Orch.taskError(14, "add"))
        assertEquals("That task is gone", Orch.taskError(14, "cancel"))
        assertEquals("That task isn't running", Orch.taskError(1, "done"))
        assertEquals("That task has finished already", Orch.taskError(1, "cancel"))
        assertEquals("orch on the server doesn't know that command (too old for this Kabuk): update orchtool", Orch.taskError(2, "add"))
        assertNull(Orch.taskError(0, "add"))
        home.deleteRecursively()
    }
}

