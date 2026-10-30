package pl.galeria.zdjecia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.hypot

/**
 * Zdjęcie z powiększaniem (szczypanie, dwukrotne stuknięcie) i rysowaniem po nim.
 * Znaki są we współrzędnych bitmapy: powiększanie ich nie rozjeżdża, a [render] maluje je tak samo jak ekran.
 */
class PhotoView(ctx: Context) : View(ctx) {
    enum class Tool { PEN, ARROW, CIRCLE, TEXT }

    class Mark(val tool: Tool, val color: Int, val width: Float, val x0: Float, val y0: Float) {
        val path = Path().apply { moveTo(x0, y0); lineTo(x0, y0) } // samo stuknięcie kredką = kropka
        var x1 = x0
        var y1 = y0
        var text = ""
    }

    var drawMode = false
    var tool = Tool.PEN
    var color = Color.RED
    var size = 6f // grubość w dp na ekranie, niezależnie od powiększenia
    val marks = ArrayList<Mark>()
    var onText: (Mark) -> Unit = {}

    private var bmp: Bitmap? = null
    private val m = Matrix()
    private var fitScale = 1f
    private var current: Mark? = null
    private var lastFx = 0f
    private var lastFy = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    val zoomed get() = scale() > fitScale * 1.01f

    fun setImage(b: Bitmap?) { bmp = b; fit() }

    fun addText(k: Mark, s: String) { k.text = s; marks += k; invalidate() }

    fun undo() { if (marks.isNotEmpty()) { marks.removeAt(marks.lastIndex); invalidate() } }

    fun clearMarks() { marks.clear(); current = null; invalidate() }

    /** Kopia zdjęcia z narysowanymi znakami (do zapisu); null, gdy nie ma zdjęcia albo pamięci. */
    fun render(): Bitmap? {
        val out = try { bmp?.copy(Bitmap.Config.ARGB_8888, true) } catch (e: OutOfMemoryError) { null } ?: return null
        val c = Canvas(out)
        marks.forEach { paintMark(c, it) }
        return out
    }

    private fun scale(): Float { val v = FloatArray(9); m.getValues(v); return v[Matrix.MSCALE_X] }

    private fun fit() {
        val b = bmp
        if (b != null && width > 0) m.setRectToRect(RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()),
            RectF(0f, 0f, width.toFloat(), height.toFloat()), Matrix.ScaleToFit.CENTER)
        fitScale = scale()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = fit()

    /** Mniejsze od ekranu — na środek, większe — bez pustych pasów przy krawędziach. */
    private fun clamp() {
        val b = bmp ?: return
        val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()).also { m.mapRect(it) }
        m.postTranslate(fixOffset(r.left, r.width(), width.toFloat()), fixOffset(r.top, r.height(), height.toFloat()))
        invalidate()
    }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { lastFx = d.focusX; lastFy = d.focusY; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val s = scale()
            val f = (s * d.scaleFactor).coerceIn(fitScale, fitScale * 8) / s
            m.postScale(f, f, d.focusX, d.focusY)
            m.postTranslate(d.focusX - lastFx, d.focusY - lastFy) // dwa palce też przesuwają
            lastFx = d.focusX
            lastFy = d.focusY
            clamp()
            return true
        }
    }).apply { isQuickScaleEnabled = false } // stuknięcie + przeciągnięcie ma rysować, nie powiększać

    private val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoomed) fit() else { m.postScale(3f, 3f, e.x, e.y); clamp() }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!zoomed || e2.pointerCount > 1) return false
            m.postTranslate(-dx, -dy)
            clamp()
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (drawMode) draw(e) else taps.onTouchEvent(e)
        return true
    }

    private fun draw(e: MotionEvent) {
        if (e.pointerCount > 1) { // dwa palce = powiększanie, nie rysowanie
            current?.let { marks.remove(it) }
            current = null
            invalidate()
            return
        }
        val p = floatArrayOf(e.x, e.y)
        Matrix().also { m.invert(it) }.mapPoints(p)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> current = Mark(tool, color, size * resources.displayMetrics.density / scale(), p[0], p[1])
                .also { if (tool != Tool.TEXT) marks += it }
            MotionEvent.ACTION_MOVE -> current?.let { it.path.lineTo(p[0], p[1]); it.x1 = p[0]; it.y1 = p[1] }
            MotionEvent.ACTION_UP -> current?.let {
                if (it.tool == Tool.TEXT) onText(it)
                // przypadkowe stuknięcie strzałką albo kółkiem
                else if (it.tool != Tool.PEN && hypot(it.x1 - it.x0, it.y1 - it.y0) < it.width * 2) marks.remove(it)
                current = null
            }
            MotionEvent.ACTION_CANCEL -> { current?.let { marks.remove(it) }; current = null }
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        c.drawBitmap(b, m, paint)
        c.save()
        c.concat(m)
        marks.forEach { paintMark(c, it) }
        c.restore()
    }

    /** Ten sam rysunek na ekranie i w zapisanej kopii. */
    private fun paintMark(c: Canvas, k: Mark) {
        pen.style = Paint.Style.STROKE
        pen.color = k.color
        pen.strokeWidth = k.width
        when (k.tool) {
            Tool.PEN -> c.drawPath(k.path, pen)
            Tool.CIRCLE -> c.drawOval(minOf(k.x0, k.x1), minOf(k.y0, k.y1), maxOf(k.x0, k.x1), maxOf(k.y0, k.y1), pen)
            Tool.ARROW -> {
                val h = arrowHead(k.x0, k.y0, k.x1, k.y1, k.width * 5)
                c.drawLine(k.x0, k.y0, k.x1, k.y1, pen)
                c.drawLine(k.x1, k.y1, h[0], h[1], pen)
                c.drawLine(k.x1, k.y1, h[2], h[3], pen)
            }
            Tool.TEXT -> {
                pen.textSize = k.width * 6
                pen.textAlign = Paint.Align.CENTER
                pen.strokeWidth = pen.textSize / 8
                pen.color = if (k.color == Color.BLACK) Color.WHITE else Color.BLACK // obwódka: tekst czytelny na każdym tle
                val y = k.y0 + pen.textSize / 3
                c.drawText(k.text, k.x0, y, pen)
                pen.style = Paint.Style.FILL
                pen.color = k.color
                c.drawText(k.text, k.x0, y, pen)
            }
        }
    }
}
