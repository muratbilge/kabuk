package app.kabuk.term

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Scroller
import androidx.core.content.res.ResourcesCompat
import app.kabuk.R
import app.kabuk.data.Settings
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import com.termux.view.TerminalRenderer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One-shot / locked modifier keys from the on-screen key bar (and the volume keys). */
interface Modifiers {
    /** read and clear a one-shot modifier (a locked one stays on) */
    fun takeCtrl(): Boolean
    fun takeAlt(): Boolean
    fun takeShift(): Boolean
    /** Volume Up held: Termux's "Fn" layer (W/A/S/D arrows, E Esc, T Tab, 1-0 F1-F10, ...) */
    fun fn(): Boolean = false
}

/** lines one mouse-wheel step scrolls in herdr (mouse_scroll_lines) and most programs */
private const val WHEEL_ROWS = 3

/**
 * Draws a [TermTab] with Termux's renderer and handles touch and keyboard input.
 *
 * Differences to Termux's TerminalView:
 *  - new output does not yank you to the bottom while you're reading history
 *  - selection: long-press selects a word, handles are big and dragged with an offset so the finger
 *    doesn't hide the text, auto-scroll at the edges, and copying joins wrapped lines
 *  - tapping a link asks to open or copy it
 */
@SuppressLint("ViewConstructor")
class TerminalCanvasView(context: Context, private val modifiers: Modifiers) : View(context) {

    interface Listener {
        fun onSelectionChanged(active: Boolean)
        fun onLinkTapped(url: String)
        fun onScrolledChanged(atBottom: Boolean)
        fun onFontSizeChanged(sp: Float)
        fun onKeyTyped()
    }

    var listener: Listener? = null

    private val screenCallback: () -> Unit = { onScreenUpdated() }

    var tab: TermTab? = null
        set(value) {
            if (field === value) return
            field?.let { if (it.onScreenUpdate === screenCallback) it.onScreenUpdate = null }
            field = value
            if (isAttachedToWindow) value?.onScreenUpdate = screenCallback
            topRow = 0
            clearSelection()
            updateSize()
            invalidate()
        }

    private val emulator: TerminalEmulator? get() = tab?.emulator

    private val typeface: Typeface =
        ResourcesCompat.getFont(context, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
    private var fontSizeSp = Settings.current.fontSizeSp
    private var renderer = makeRenderer()
    private val density = resources.displayMetrics.density
    private val pad = (4 * density).roundToInt()

    /** 0 = live screen, negative = scrolled back into history */
    private var topRow = 0
        set(v) {
            val was = field == 0
            field = v
            if (was != (v == 0)) listener?.onScrolledChanged(v == 0)
        }

    // selection, in the emulator's external row coordinates (negative = history); x2 is inclusive
    var selecting = false
        private set
    private var selX1 = 0; private var selY1 = 0; private var selX2 = 0; private var selY2 = 0
    /** columns of the pane the selection lives in (herdr/tmux splits); whole width when there are no borders */
    private var paneL = 0; private var paneR = Int.MAX_VALUE
    private val paneMode get() = paneL > 0 || paneR < (emulator?.mColumns ?: 0) - 1
    private val selPaint = Paint().apply { color = 0x667AA2F7 }
    internal var dragging = 0          // 0 none, 1 start handle, 2 end handle
    private val handleR = 11 * density
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7AA2F7.toInt() }
    private val bgPaint = Paint()
    private val scrollPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x667AA2F7 }

    private val scroller = Scroller(context)
    private var scrollRemainder = 0f
    /** rows dragged but not yet sent as a wheel step */
    private var wheelRemainder = 0
    /** fling over a mouse program (herdr, tmux): the scroller runs from 0, [flingRow] is what was sent */
    private var flingVirtual = false
    private var flingRow = 0

    private fun makeRenderer() = TerminalRenderer((fontSizeSp * resources.displayMetrics.scaledDensity).roundToInt(), typeface)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ------------------------------------------------------------------ size & font

    fun setFontSize(sp: Float) {
        val v = sp.coerceIn(6f, 32f)
        if (abs(v - fontSizeSp) < 0.01f) return
        fontSizeSp = v
        renderer = makeRenderer()
        updateSize()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateSize()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        tab?.onScreenUpdate = screenCallback
        invalidate()
    }

    override fun onDetachedFromWindow() {
        // don't keep the activity alive through the process-wide tab
        tab?.let { if (it.onScreenUpdate === screenCallback) it.onScreenUpdate = null }
        scroller.forceFinished(true)
        super.onDetachedFromWindow()
    }

    /**
     * Every resize makes the program on the server redraw. While the keyboard slides in or a pinch is going on
     * the size changes every frame, so only the size it settles on is sent.
     */
    private fun updateSize() {
        removeCallbacks(applySize)
        if (tab !== sizedTab) applySize.run() else postDelayed(applySize, 150)
    }

    private var sizedTab: TermTab? = null
    private val applySize = Runnable {
        val t = tab ?: return@Runnable
        if (width == 0 || height == 0) return@Runnable
        val cols = max(4, ((width - 2 * pad) / renderer.fontWidth).toInt())
        val rows = max(4, (height - 2 * pad) / renderer.fontLineSpacing)
        val em = t.emulator
        if (t === sizedTab && em != null && em.mColumns == cols && em.mRows == rows) return@Runnable
        sizedTab = t
        // selection columns and pane bounds mean nothing after a resize
        clearSelection()
        t.resize(cols, rows)
    }

    // ------------------------------------------------------------------ drawing

    private fun onScreenUpdated() {
        val em = emulator ?: return
        val history = em.screen.activeTranscriptRows
        val shift = em.scrollCounter
        if (topRow != 0 || selecting) {
            // stay where the user is reading: move along with the scrolled-out lines
            if (-topRow + shift > history) {
                if (selecting) clearSelection()
                topRow = 0
            } else {
                topRow -= shift
                if (selecting) { selY1 -= shift; selY2 -= shift }
            }
        }
        if (topRow < -history) topRow = -history
        em.clearScrollCounter()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val em = emulator
        val bg = em?.mColors?.mCurrentColors?.get(TextStyle.COLOR_INDEX_BACKGROUND) ?: 0xFF0E1116.toInt()
        // fill only our own bounds (drawColor ignores them when composed inside Compose)
        bgPaint.color = bg
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (em == null) return
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.save()
        canvas.translate(pad.toFloat(), pad.toFloat())
        if (selecting && !paneMode) renderer.render(em, canvas, topRow, selY1, selY2, selX1, selX2)
        else renderer.render(em, canvas, topRow, -1, -1, -1, -1)
        if (selecting && paneMode) {
            // the renderer only knows full-width selections: highlight the pane part ourselves
            val fw = renderer.fontWidth; val lh = renderer.fontLineSpacing.toFloat()
            for (row in max(selY1, topRow)..min(selY2, topRow + em.mRows - 1)) {
                val a = if (row == selY1) selX1 else paneL
                val b = if (row == selY2) selX2 else paneR
                val y = (row - topRow) * lh
                canvas.drawRect(a * fw, y, (b + 1) * fw, y + lh, selPaint)
            }
        }
        canvas.restore()

        if (topRow < 0) {
            // thin scroll position indicator on the right
            val total = em.screen.activeTranscriptRows + em.mRows
            val h = height * em.mRows.toFloat() / total
            val y = (height - h) * (total - em.mRows + topRow).toFloat() / max(1, total - em.mRows)
            canvas.drawRoundRect(width - 4 * density, y, width - 1 * density, y + h, 2 * density, 2 * density, scrollPaint)
        }
        if (selecting) {
            handleAt(selX1, selY1, true)?.let { (x, y) -> drawHandle(canvas, x, y, true) }
            handleAt(selX2, selY2, false)?.let { (x, y) -> drawHandle(canvas, x, y, false) }
        }
        canvas.restore()
    }

    /** screen position of a selection handle (bottom of the cell), or null when that row is off screen */
    private fun handleAt(col: Int, row: Int, start: Boolean): Pair<Float, Float>? {
        val em = emulator ?: return null
        val r = row - topRow
        if (r < 0 || r >= em.mRows) return null
        val x = pad + (if (start) col else col + 1) * renderer.fontWidth
        val y = pad + (r + 1) * renderer.fontLineSpacing.toFloat()
        return x to y
    }

    private fun drawHandle(c: Canvas, x: Float, y: Float, start: Boolean) {
        // a teardrop: a circle hanging below the line, with its corner pointing at the selection edge
        val cx = if (start) x - handleR else x + handleR
        val cy = y + handleR
        c.drawCircle(cx, cy, handleR, handlePaint)
        if (start) c.drawRect(cx, y, x, cy, handlePaint) else c.drawRect(x, y, cx, cy, handlePaint)
    }

    // ------------------------------------------------------------------ coordinates

    private fun colAt(x: Float): Int {
        val em = emulator ?: return 0
        return ((x - pad) / renderer.fontWidth).toInt().coerceIn(0, em.mColumns - 1)
    }

    private fun rowAt(y: Float): Int {
        val em = emulator ?: return 0
        val r = ((y - pad) / renderer.fontLineSpacing).toInt().coerceIn(0, em.mRows - 1)
        return r + topRow
    }

    private fun cellText(col: Int, row: Int): String =
        emulator?.screen?.getSelectedText(col, row, col, row) ?: ""

    // ------------------------------------------------------------------ selection

    private val borderChars = "│┃║┆┇┊┋▏▕"

    /** finds the vertical pane borders around [col] (herdr's sidebar, tmux/herdr splits) */
    private fun detectPane(col: Int) {
        val (l, r) = paneBounds(col, selY1)
        paneL = l; paneR = r
    }

    /** first and last column of the pane around ([col], [row]); the whole width when there are no borders */
    private fun paneBounds(col: Int, row: Int): Pair<Int, Int> {
        val em = emulator ?: return 0 to 0
        var paneL = 0; var paneR = em.mColumns - 1
        fun border(c: Int, r: Int) = cellText(c, r).let { it.length == 1 && it[0] in borderChars }
        // a pane border is an unbroken vertical line through this row, at least 4 rows long
        // (a table's │ is cut by its ┼ separator lines; text never lines up like that)
        val isBorder = { c: Int ->
            if (!border(c, row)) false else {
                var n = 1
                var r = row - 1
                while (r >= topRow && border(c, r)) { n++; r-- }
                r = row + 1
                while (r < topRow + em.mRows && border(c, r)) { n++; r++ }
                n >= 4
            }
        }
        if (isBorder(col)) return paneL to paneR
        for (c in col - 1 downTo 0) if (isBorder(c)) { paneL = c + 1; break }
        for (c in col + 1 until em.mColumns) if (isBorder(c)) { paneR = c - 1; break }
        return paneL to paneR
    }

    internal fun startSelection(x: Float, y: Float) {
        val em = emulator ?: return
        val col = colAt(x); val row = rowAt(y)
        selY1 = row
        detectPane(col)
        // grow to the whole word (non-blank run), inside the pane
        var a = col; var b = col
        if (cellText(col, row).isNotBlank()) {
            while (a > paneL && cellText(a - 1, row).isNotBlank()) a--
            while (b < min(paneR, em.mColumns - 1) && cellText(b + 1, row).isNotBlank()) b++
        }
        selX1 = a; selY1 = row; selX2 = b; selY2 = row
        selecting = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        listener?.onSelectionChanged(true)
        invalidate()
    }

    fun clearSelection() {
        if (!selecting) return
        selecting = false
        dragging = 0
        listener?.onSelectionChanged(false)
        invalidate()
    }

    val inPane get() = selecting && paneMode

    /** drop the pane limit for this selection (for tables or when detection guessed wrong) */
    fun selectFullWidth() {
        val em = emulator ?: return
        paneL = 0; paneR = em.mColumns - 1
        invalidate()
    }

    fun selectAll() {
        val em = emulator ?: return
        paneL = 0; paneR = em.mColumns - 1
        selX1 = 0; selY1 = -em.screen.activeTranscriptRows
        selX2 = em.mColumns - 1; selY2 = em.mRows - 1
        selecting = true
        listener?.onSelectionChanged(true)
        invalidate()
    }

    /** selected text with lines that programs wrapped themselves joined back (see [app.kabuk.text.Unwrap]) */
    fun selectedText(): String {
        // a selection that starts mid-line: put the missing start back as spaces so the first line
        // counts as full-length when deciding which lines were wrapped
        val offset = (selX1 - if (paneMode) paneL else 0).coerceAtLeast(0)
        val available = if (paneMode) paneR - paneL + 1 else emulator?.mColumns ?: 80
        return app.kabuk.text.Unwrap.unwrap(" ".repeat(offset) + selectedTextRaw(), available = available).trimStart()
    }

    /** selected text exactly as shown: terminal-wrapped lines joined, trailing blanks removed */
    fun selectedTextRaw(): String {
        val em = emulator ?: return ""
        if (!selecting) return ""
        if (paneMode) {
            // only the pane's columns of each row
            return (selY1..selY2).joinToString("\n") { row ->
                val a = if (row == selY1) selX1 else paneL
                val b = if (row == selY2) selX2 else paneR
                em.screen.getSelectedText(a, row, b, row).trimEnd()
            }.trimEnd('\n')
        }
        return em.screen.getSelectedText(selX1, selY1, selX2, selY2, true, false)
            .lines().joinToString("\n") { it.trimEnd() }.trimEnd('\n')
    }

    /**
     * The live screen rows of the pane the cursor is in (the one you type into), so a question shown in
     * another pane never produces answer buttons for this one. Empty while you're scrolled back.
     */
    fun focusedPaneLines(): List<String> {
        val em = emulator ?: return emptyList()
        if (topRow != 0) return emptyList()
        val (l, r) = paneBounds(em.cursorCol.coerceIn(0, em.mColumns - 1), em.cursorRow)
        return (0 until em.mRows).map { em.screen.getSelectedText(l, it, r, it) }
    }

    /** the rows on screen as text, top to bottom */
    fun screenLines(): List<String> {
        val em = emulator ?: return emptyList()
        return (topRow until topRow + em.mRows).map { em.screen.getSelectedText(0, it, em.mColumns - 1, it) }
    }

    fun visibleText(): String = app.kabuk.text.Unwrap.unwrap(visibleTextRaw())

    fun visibleTextRaw(): String {
        val em = emulator ?: return ""
        return em.screen.getSelectedText(0, topRow, em.mColumns - 1, topRow + em.mRows - 1, true, false)
            .lines().joinToString("\n") { it.trimEnd() }.trim('\n')
    }

    /** screen point at the middle of a cell (tests) */
    internal fun cellCenter(col: Int, row: Int) =
        pad + (col + 0.5f) * renderer.fontWidth to pad + (row - topRow + 0.5f) * renderer.fontLineSpacing

    /** where a finger must be for [dragHandle] to put the end handle on a cell (tests) */
    internal fun endHandleFinger(col: Int, row: Int) = cellCenter(col, row).let { (x, y) ->
        x + handleR to y + handleR + renderer.fontLineSpacing / 2f
    }

    internal fun dragHandle(x: Float, y: Float) {
        val em = emulator ?: return
        // the finger sits below the handle, which sits below the line: aim one line above the finger
        val aimY = y - handleR - renderer.fontLineSpacing / 2f
        // auto-scroll when dragging over the top or bottom edge
        if (aimY < pad && topRow > -em.screen.activeTranscriptRows) topRow--
        if (y > height - pad && topRow < 0) topRow++
        val right = min(paneR, em.mColumns - 1)
        val col = colAt(if (dragging == 1) x + handleR else x - handleR).coerceIn(min(paneL, right), right)
        val row = rowAt(aimY)
        if (dragging == 1) { selX1 = col; selY1 = row } else { selX2 = col; selY2 = row }
        // keep start <= end, swapping which handle is being dragged when they cross
        if (selY1 > selY2 || (selY1 == selY2 && selX1 > selX2)) {
            val tx = selX1; val ty = selY1
            selX1 = selX2; selY1 = selY2; selX2 = tx; selY2 = ty
            dragging = if (dragging == 1) 2 else 1
        }
        invalidate()
    }

    private fun handleHit(x: Float, y: Float): Int {
        val reach = handleR * 2.6f
        handleAt(selX1, selY1, true)?.let { (hx, hy) ->
            if (abs(x - (hx - handleR)) < reach && abs(y - (hy + handleR)) < reach) return 1
        }
        handleAt(selX2, selY2, false)?.let { (hx, hy) ->
            if (abs(x - (hx + handleR)) < reach && abs(y - (hy + handleR)) < reach) return 2
        }
        return 0
    }

    // ------------------------------------------------------------------ scrolling

    fun scrollToBottom() {
        scroller.forceFinished(true)
        topRow = 0
        invalidate()
    }

    private fun scrollRows(rows: Int, x: Float, y: Float) {
        val em = emulator ?: return
        if (rows == 0) return
        when {
            em.isMouseTrackingActive -> {
                // tmux with mouse on, vim, less --mouse...: send wheel events
                // one wheel step scrolls WHEEL_ROWS lines there (herdr's mouse_scroll_lines): follow the finger
                wheelRemainder += rows
                val steps = wheelRemainder / WHEEL_ROWS
                wheelRemainder -= steps * WHEEL_ROWS
                val btn = if (steps < 0) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON
                repeat(abs(steps)) { em.sendMouseEvent(btn, colAt(x) + 1, rowAt(y) - topRow + 1, true) }
            }
            em.isAlternateBufferActive -> {
                // full-screen programs without mouse: arrow keys
                val code = if (rows < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
                repeat(abs(rows)) { sendKeyCode(code, 0) }
            }
            else -> {
                topRow = (topRow + rows).coerceIn(-em.screen.activeTranscriptRows, 0)
                invalidate()
            }
        }
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        val target = scroller.currY / renderer.fontLineSpacing
        val em = emulator ?: return
        val rows = if (flingVirtual) (target - flingRow).also { flingRow = target }
                   else target - (topRow + em.screen.activeTranscriptRows)
        if (rows != 0) scrollRows(rows, width / 2f, height / 2f)
        if (!scroller.isFinished) postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ touch

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            setFontSize(fontSizeSp * d.scaleFactor)
            return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) {
            listener?.onFontSizeChanged(fontSizeSp)
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            scrollRemainder = 0f
            wheelRemainder = 0
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            tapAt(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (scale.isInProgress) return
            startSelection(e.x, e.y)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale.isInProgress || dragging != 0) return true
            scrollRemainder += dy / renderer.fontLineSpacing * Settings.current.scrollSpeed
            val rows = scrollRemainder.toInt()
            scrollRemainder -= rows
            scrollRows(rows, e2.x, e2.y)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val em = emulator ?: return true
            if (scale.isInProgress || dragging != 0) return true
            val lh = renderer.fontLineSpacing
            val speed = Settings.current.scrollSpeed
            if (em.isMouseTrackingActive) {
                // herdr, tmux: momentum as wheel steps, capped at a few screens
                flingVirtual = true; flingRow = 0
                val reach = em.mRows * 4 * speed * lh
                scroller.fling(0, 0, 0, (-vy * 0.5f * speed).toInt(), 0, 0, -reach, reach)
                postInvalidateOnAnimation()
                return true
            }
            // arrow keys in full-screen programs would move their cursor/history: plain drags only
            if (em.isAlternateBufferActive) return true
            flingVirtual = false
            // the scroller runs in pixels over the whole history: 0 = oldest line
            val cur = (topRow + em.screen.activeTranscriptRows) * lh
            scroller.fling(0, cur, 0, (-vy * 0.5f * speed).toInt(), 0, 0, 0, em.screen.activeTranscriptRows * lh)
            postInvalidateOnAnimation()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (selecting) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> dragging = handleHit(e.x, e.y)
                MotionEvent.ACTION_MOVE -> if (dragging != 0) { dragHandle(e.x, e.y); return true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging != 0) { dragging = 0; return true }
            }
            if (dragging != 0) return true
        }
        scale.onTouchEvent(e)
        gestures.onTouchEvent(e)
        return true
    }

    /** a tap: ends a selection, opens a link, clicks in mouse-aware programs (herdr, tmux, vim), shows the keyboard */
    fun tapAt(x: Float, y: Float) {
        if (selecting) { clearSelection(); return }
        linkAt(x, y)?.let { listener?.onLinkTapped(it); return }
        val em = emulator
        if (em != null && em.isMouseTrackingActive) {
            // a real click, so herdr's tabs and buttons work
            val col = colAt(x) + 1
            val row = rowAt(y) - topRow + 1
            em.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, col, row, true)
            em.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, col, row, false)
            // tapping around in herdr/tmux is reading and clicking: the keyboard (which shrinks the terminal and
            // makes everything redraw) only comes when tapping where you type, or with the ⌨ key
            if (abs(rowAt(y) - topRow - em.cursorRow) > 1) return
        }
        showKeyboard()
    }

    private val urlRegex = Regex("""(https?://|www\.)[^\s<>"'`]+[^\s<>"'`.,;:)\]}!?]""")

    /** a link under the finger; looks at the whole wrapped line so long URLs work */
    private fun linkAt(x: Float, y: Float): String? {
        val em = emulator ?: return null
        val row = rowAt(y); val col = colAt(x)
        var start = row
        while (start > -em.screen.activeTranscriptRows && em.screen.getLineWrap(start - 1)) start--
        var end = row
        while (end < em.mRows - 1 && em.screen.getLineWrap(end)) end++
        val text = em.screen.getSelectedText(0, start, em.mColumns - 1, end, true, false).replace("\n", "")
        val offset = (row - start) * em.mColumns + col
        return urlRegex.findAll(text).firstOrNull { offset in it.range }?.value
            ?.let { if (it.startsWith("www.")) "https://$it" else it }
    }

    // ------------------------------------------------------------------ keyboard

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = if (Settings.current.compatKeyboard)
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        else InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                sendText(editable!!)
                editable!!.clear()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                sendText(editable!!)
                editable!!.clear()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // Samsung keyboards send several at once; "after" is a forward delete
                repeat(beforeLength) { sendKeyCode(KeyEvent.KEYCODE_DEL, 0) }
                repeat(afterLength) { sendKeyCode(KeyEvent.KEYCODE_FORWARD_DEL, 0) }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
        }
    }

    private fun sendText(text: CharSequence) {
        var i = 0
        while (i < text.length) {
            var cp = Character.codePointAt(text, i)
            i += Character.charCount(cp)
            if (cp == '\n'.code) cp = '\r'.code
            inputCodePoint(cp, false, false)
        }
    }

    /** a typed character, with Ctrl/Alt from the key bar applied */
    fun inputCodePoint(codePoint: Int, ctrlDown: Boolean, altDown: Boolean) {
        val t = tab ?: return
        if (modifiers.fn() && fnKey(codePoint)) return
        var cp = codePoint
        val ctrl = ctrlDown || modifiers.takeCtrl()
        val alt = altDown || modifiers.takeAlt()
        if (modifiers.takeShift()) cp = Character.toUpperCase(cp)
        if (ctrl) cp = when (cp) {
            in 'a'.code..'z'.code -> cp - 'a'.code + 1
            in 'A'.code..'Z'.code -> cp - 'A'.code + 1
            ' '.code, '2'.code -> 0
            '['.code, '3'.code -> 27
            '\\'.code, '4'.code -> 28
            ']'.code, '5'.code -> 29
            '^'.code, '6'.code -> 30
            '_'.code, '7'.code, '/'.code -> 31
            '8'.code -> 127
            else -> cp
        }
        // Bluetooth keyboards send "spacing modifier" variants of these
        cp = when (cp) { 0x02DC -> '~'.code; 0x02CB -> '`'.code; 0x02C6 -> '^'.code; else -> cp }
        afterInput()
        t.writeCodePoint(alt, cp)
    }

    private fun fnKey(cp: Int): Boolean {
        val c = Character.toLowerCase(cp).toChar()
        val code = when (c) {
            'w' -> KeyEvent.KEYCODE_DPAD_UP; 'a' -> KeyEvent.KEYCODE_DPAD_LEFT
            's' -> KeyEvent.KEYCODE_DPAD_DOWN; 'd' -> KeyEvent.KEYCODE_DPAD_RIGHT
            'p' -> KeyEvent.KEYCODE_PAGE_UP; 'n' -> KeyEvent.KEYCODE_PAGE_DOWN
            'e' -> KeyEvent.KEYCODE_ESCAPE; 't' -> KeyEvent.KEYCODE_TAB
            'i' -> KeyEvent.KEYCODE_INSERT; 'x' -> KeyEvent.KEYCODE_FORWARD_DEL
            in '1'..'9' -> KeyEvent.KEYCODE_F1 + (c - '1'); '0' -> KeyEvent.KEYCODE_F10
            else -> null
        }
        if (code != null) return sendKeyCode(code, 0)
        val text = when (c) { 'h' -> "~"; 'u' -> "_"; 'l' -> "|"; '.' -> "\u001c"; else -> return false }
        afterInput()
        tab?.write(text)
        return true
    }

    /** special keys (arrows, F-keys, Enter...) as the right escape sequence for the current mode */
    fun sendKeyCode(keyCode: Int, keyMod: Int): Boolean {
        val t = tab ?: return false
        val em = emulator ?: return false
        var mod = keyMod
        if (modifiers.takeCtrl()) mod = mod or KeyHandler.KEYMOD_CTRL
        if (modifiers.takeAlt()) mod = mod or KeyHandler.KEYMOD_ALT
        if (modifiers.takeShift()) mod = mod or KeyHandler.KEYMOD_SHIFT
        val code = KeyHandler.getCode(keyCode, mod, em.isCursorKeysApplicationMode, em.isKeypadApplicationMode) ?: return false
        afterInput()
        t.write(code)
        return true
    }

    private fun afterInput() {
        if (selecting) clearSelection()
        if (topRow != 0) scrollToBottom()
        listener?.onKeyTyped()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isSystem && keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        // hardware keyboard shortcuts: Ctrl+Shift+C / V copy & paste
        if (event.isCtrlPressed && event.isShiftPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_C -> { if (selecting) TabManager.copyToClipboard(selectedText()); clearSelection(); return true }
                KeyEvent.KEYCODE_V -> { TabManager.clipboardText()?.let { emulator?.paste(it) }; return true }
            }
        }
        var mod = 0
        if (event.isCtrlPressed) mod = mod or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed) mod = mod or KeyHandler.KEYMOD_ALT
        if (event.isShiftPressed) mod = mod or KeyHandler.KEYMOD_SHIFT
        if (event.isNumLockOn) mod = mod or KeyHandler.KEYMOD_NUM_LOCK
        val em = emulator
        if (!event.isFunctionPressed && em != null &&
            KeyHandler.getCode(keyCode, mod, em.isCursorKeysApplicationMode, em.isKeypadApplicationMode) != null &&
            sendKeyCode(keyCode, mod)) return true

        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv()
        val ch = event.getUnicodeChar(meta)
        if (ch == 0) return super.onKeyDown(keyCode, event)
        if (ch and KeyCharacterMap.COMBINING_ACCENT != 0) return true
        inputCodePoint(ch, event.isCtrlPressed, event.isAltPressed)
        return true
    }

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && event.characters != null) {
            sendText(event.characters); return true
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    /** used by the prompt box: paste as one block (bracketed paste) and optionally press Enter */
    fun sendBlock(text: String, enter: Boolean): Boolean {
        val t = tab ?: return false
        val em = t.emulator ?: return false
        if (!t.canSend) return false
        afterInput()
        if (text.isNotEmpty()) em.paste(text)
        if (enter) {
            // Enter goes to the tab the text went to, even if you switch tabs meanwhile;
            // the short pause lets the program finish taking the paste
            val g = t.generation
            postDelayed({ if (t.generation == g && !t.closed) t.write("\r") }, if (text.contains('\n')) 120 else 30)
        }
        return true
    }

    fun paste() {
        val text = TabManager.clipboardText() ?: return
        afterInput()
        emulator?.paste(text)
    }

    fun linesInView() = emulator?.mRows ?: 24
    fun scrollBy(rows: Int) = scrollRows(rows, width / 2f, height / 2f)
}
