package pl.galeria.zdjecia

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

private const val TRASH_REQUEST = 1
private val TOOLS = listOf(
    PhotoView.Tool.PEN to "✏️ Kredka", PhotoView.Tool.ARROW to "↗ Strzałka",
    PhotoView.Tool.CIRCLE to "◯ Kółko", PhotoView.Tool.TEXT to "T Tekst",
)
private val COLORS = listOf(
    0xFFF44336 to "czerwony", 0xFFFFEB3B to "żółty", 0xFF4CAF50 to "zielony",
    0xFF2196F3 to "niebieski", 0xFFFFFFFF to "biały", 0xFF000000 to "czarny",
).map { (c, name) -> c.toInt() to name }
private val SIZES = listOf(3f to "•", 6f to "●", 12f to "⬤") // grubość w dp

/** Pełny ekran: przesuwanie między zdjęciami, filmy, rysowanie, udostępnianie i kosz. Otwiera też pliki z innych aplikacji. */
class ViewerActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val time = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("pl"))

    private var items = ArrayList<Media>()
    private var index = 0
    @Volatile private var gen = 0 // numer pokazywanego zdjęcia: spóźnione wczytanie starego nie nadpisze nowego
    private var trash = false
    private var drawing = false
    private var barsShown = true
    private var feed = false // czy bieżący dotyk idzie do wykrywania przesunięć

    private lateinit var photo: PhotoView
    private lateinit var video: VideoView
    private lateinit var videoBox: FrameLayout
    private lateinit var top: LinearLayout
    private lateinit var title: TextView
    private lateinit var drawBtn: Button
    private lateinit var shareBtn: Button
    private lateinit var deleteBtn: Button
    private lateinit var drawBar: LinearLayout
    private lateinit var toolButtons: List<Button>
    private lateinit var colorDots: List<View>
    private lateinit var sizeButtons: List<Button>

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(6), 0, dp(6), 0)
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") // Android 15 robi to sam, ale 11–14 potrzebują tego do zdjęcia na cały ekran
        window.setDecorFitsSystemWindows(false)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        photo = PhotoView(this).apply { onText = ::askText; color = COLORS[0].first }
        root.addView(photo)
        videoBox = FrameLayout(this)
        video = VideoView(this).apply { visibility = View.GONE }
        videoBox.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        root.addView(videoBox)
        video.setMediaController(MediaController(this)) // przyczepia się do videoBox

        top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x99000000.toInt())
            isClickable = true // dotyk paska nie przechodzi do zdjęcia
            visibility = View.INVISIBLE // do pierwszego show(): przyciski potrzebują listy
        }
        title = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(12), dp(8), dp(12), 0) }
        top.addView(title)
        drawBtn = button("✏️ Rysuj") { startDrawing() }
        shareBtn = button("📤 Udostępnij") { Gallery.share(this, listOf(items[index])) }
        deleteBtn = button("🗑 Usuń") { trashRequest() }
        top.addView(LinearLayout(this).apply {
            listOf(drawBtn, shareBtn, deleteBtn).forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        })
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        drawBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true
            visibility = View.GONE
        }
        val toolRow = LinearLayout(this)
        toolButtons = TOOLS.map { (t, label) ->
            button(label) { photo.tool = t; updateDrawBar() }
                .also { toolRow.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        drawBar.addView(toolRow)
        val colorRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        colorDots = COLORS.map { (c, name) ->
            View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(2), Color.GRAY) }
                contentDescription = "Kolor $name"
                setOnClickListener { photo.color = c; updateDrawBar() }
                colorRow.addView(this, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) })
            }
        }
        sizeButtons = SIZES.map { (s, label) -> button(label) { photo.size = s; updateDrawBar() }.also { colorRow.addView(it) } }
        drawBar.addView(HorizontalScrollView(this).apply { addView(colorRow) })
        drawBar.addView(LinearLayout(this).apply {
            listOf(button("↶ Cofnij") { photo.undo() }, button("✕ Anuluj") { stopDrawing(ask = true) }, button("✓ Zapisz") { save() })
                .forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        })
        root.addView(drawBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            top.setPadding(b.left, b.top, b.right, 0)
            drawBar.setPadding(b.left, 0, b.right, b.bottom)
            // film i jego suwak nad paskiem nawigacji, nie pod nim
            (videoBox.layoutParams as FrameLayout.LayoutParams).setMargins(b.left, b.top, b.right, b.bottom)
            videoBox.requestLayout()
            insets
        }
        setContentView(root)
        window.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null) { // „Otwórz za pomocą” z innej aplikacji
            val type = intent.type ?: runCatching { contentResolver.getType(data) }.getOrNull() ?: ""
            items = arrayListOf(Media(-1, type.startsWith("video"), -1, "", 0, 0, displayName(data), ext = data))
            show(0)
            return
        }
        trash = intent.getBooleanExtra("trash", false)
        val bucket = if (intent.hasExtra("bucket")) intent.getLongExtra("bucket", 0) else null
        val id = intent.getLongExtra("id", -1)
        val isVideo = intent.getBooleanExtra("video", false)
        io.execute {
            val list = try { Gallery.query(this, trash).filter { bucket == null || it.bucketId == bucket } } catch (e: Exception) { emptyList() }
            main.post {
                if (list.isEmpty()) { finish(); return@post }
                items = ArrayList(list)
                show(list.indexOfFirst { it.id == id && it.video == isVideo }.coerceAtLeast(0))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        video.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        video.stopPlayback()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawing) stopDrawing(ask = true) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun displayName(uri: Uri): String = (try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) { null }) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "zdjecie"

    private val swipe by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { setBars(!barsShown); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || photo.zoomed || abs(vx) < 2 * abs(vy) || abs(e2.x - e1.x) < dp(60)) return false
                go(if (vx < 0) index + 1 else index - 1)
                return true
            }
        })
    }

    // Przesuwanie wykrywamy tu, bo film (VideoView) sam nie oddaje dotyku dalej.
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) feed = !drawing && !(top.isShown && e.y < top.bottom)
        if (feed && e.pointerCount > 1) { // dwa palce = powiększanie, nie przewijanie
            feed = false
            val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
            swipe.onTouchEvent(cancel)
            cancel.recycle()
        }
        if (feed) swipe.onTouchEvent(e)
        return super.dispatchTouchEvent(e)
    }

    private fun setBars(show: Boolean) {
        barsShown = show
        top.visibility = if (show && !drawing) View.VISIBLE else View.GONE
        window.insetsController?.let { if (show) it.show(WindowInsets.Type.systemBars()) else it.hide(WindowInsets.Type.systemBars()) }
    }

    private fun go(i: Int) {
        if (i !in items.indices) return
        val dir = if (i > index) 1 else -1
        show(i)
        if (!items[i].video) photo.apply {
            translationX = dir * width / 3f
            alpha = 0f
            animate().translationX(0f).alpha(1f).setDuration(150)
        }
    }

    private fun show(i: Int) {
        index = i
        val g = ++gen
        val m = items[i]
        title.text = if (m.external) m.name else "${i + 1} / ${items.size} · ${time.format(Date(m.date))}"
        drawBtn.visibility = if (m.video || trash) View.GONE else View.VISIBLE
        drawBtn.isEnabled = false // dopiero gdy wczyta się pełne zdjęcie
        shareBtn.visibility = if (trash) View.GONE else View.VISIBLE
        deleteBtn.visibility = if (m.external) View.GONE else View.VISIBLE
        deleteBtn.text = if (trash) "♻ Przywróć" else "🗑 Usuń"
        top.visibility = if (barsShown) View.VISIBLE else View.GONE
        video.stopPlayback()
        video.visibility = if (m.video) View.VISIBLE else View.GONE
        photo.visibility = if (m.video) View.GONE else View.VISIBLE
        photo.setImage(null)
        if (m.video) { video.setVideoURI(m.uri); video.start(); return }
        val px = dp(256)
        io.execute {
            if (g != gen) return@execute
            // najpierw szybka miniatura, potem pełne zdjęcie
            val small = try { contentResolver.loadThumbnail(m.uri, Size(px, px), null) } catch (e: Exception) { null }
            if (small != null) main.post { if (g == gen) photo.setImage(small) }
            val full = try { Gallery.decode(this, m.uri) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
            main.post {
                if (g != gen) return@post
                if (full == null) { toast("Nie można otworzyć zdjęcia"); return@post }
                photo.setImage(full)
                drawBtn.isEnabled = true
            }
        }
    }

    private fun startDrawing() {
        drawing = true
        photo.drawMode = true
        top.visibility = View.GONE
        drawBar.visibility = View.VISIBLE
        updateDrawBar()
    }

    private fun stopDrawing(ask: Boolean) {
        if (ask && photo.marks.isNotEmpty()) {
            AlertDialog.Builder(this).setTitle("Odrzucić zaznaczenia?")
                .setPositiveButton("Odrzuć") { _, _ -> stopDrawing(false) }
                .setNegativeButton("Wróć", null).show()
            return
        }
        drawing = false
        photo.drawMode = false
        photo.clearMarks()
        drawBar.visibility = View.GONE
        setBars(true)
    }

    private fun updateDrawBar() {
        TOOLS.zip(toolButtons).forEach { (t, b) -> b.alpha = if (t.first == photo.tool) 1f else 0.5f }
        COLORS.zip(colorDots).forEach { (c, v) -> v.scaleX = if (c.first == photo.color) 1.3f else 1f; v.scaleY = v.scaleX }
        SIZES.zip(sizeButtons).forEach { (s, b) -> b.alpha = if (s.first == photo.size) 1f else 0.5f }
    }

    private fun askText(mark: PhotoView.Mark) {
        val input = EditText(this).apply { hint = "Wpisz tekst"; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("Tekst na zdjęciu").setView(input)
            .setPositiveButton("OK") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { photo.addText(mark, it) } }
            .setNegativeButton("Anuluj", null)
            .show().window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun save() {
        if (photo.marks.isEmpty()) { toast("Najpierw coś zaznacz"); return }
        val bmp = photo.render() ?: run { toast("Za mało pamięci, żeby zapisać"); return }
        val name = items[index].name.substringBeforeLast('.') + "_zaznaczone.jpg"
        // na czas zapisu bez rysowania: dopisane teraz kreski nie trafiłyby do pliku
        photo.drawMode = false
        drawBar.visibility = View.GONE
        toast("Zapisuję…")
        io.execute {
            val r: Any = try { Gallery.save(this, bmp, name) } catch (e: Exception) { e }
            main.post {
                if (r !is Uri) { // rysunek zostaje, można spróbować jeszcze raz
                    toast((r as Exception).message ?: "Błąd")
                    photo.drawMode = true
                    drawBar.visibility = View.VISIBLE
                    return@post
                }
                photo.setImage(bmp) // na ekranie zostaje zapisana kopia
                stopDrawing(false)
                AlertDialog.Builder(this).setTitle("Zapisano")
                    .setMessage("Kopia jest w folderze „Zaznaczone”. Oryginał został bez zmian.")
                    .setPositiveButton("Udostępnij") { _, _ -> Gallery.share(this, listOf(r), "image/jpeg") }
                    .setNegativeButton("OK", null).show()
            }
        }
    }

    /** Usuń = do kosza na 30 dni, w koszu = przywróć; Android sam pyta o potwierdzenie. */
    private fun trashRequest() {
        try {
            val pi = MediaStore.createTrashRequest(contentResolver, listOf(items[index].uri), !trash)
            startIntentSenderForResult(pi.intentSender, TRASH_REQUEST, null, 0, 0, 0)
        } catch (e: Exception) { toast("Nie udało się: ${e.message}") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != TRASH_REQUEST || resultCode != RESULT_OK) return
        toast(if (trash) "Przywrócono" else "Przeniesiono do kosza")
        items.removeAt(index)
        if (items.isEmpty()) finish() else show(index.coerceAtMost(items.lastIndex))
    }
}
