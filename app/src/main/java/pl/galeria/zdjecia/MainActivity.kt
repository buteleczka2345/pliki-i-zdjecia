package pl.galeria.zdjecia

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

private const val ALL = 0
private const val FOLDERS = 1
private const val TRASH_MANY = 2
private val TABS = listOf("🖼 Wszystkie", "📁 Foldery")

/** Kafelek siatki: zawsze kwadrat o szerokości kolumny. */
private class Square(ctx: Context) : FrameLayout(ctx) {
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
}

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val thumbs = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val prefs by lazy { getSharedPreferences("galeria", MODE_PRIVATE) }
    private val need = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private var tab = ALL
    private var folder: Folder? = null // otwarty folder albo kosz
    private var foldersPos = 0 // miejsce na liście folderów sprzed wejścia do folderu
    private var all: List<Media> = emptyList()
    private var trash: List<Media> = emptyList()
    private var rows: List<Any> = emptyList() // Media albo Folder
    private val selected = LinkedHashSet<Long>() // zaznaczone zdjęcia (id); niepuste = tryb zaznaczania

    private lateinit var tabButtons: List<Button>
    private lateinit var header: TextView
    private lateinit var partial: View
    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private lateinit var body: LinearLayout
    private lateinit var noAccess: LinearLayout
    private lateinit var selBar: LinearLayout
    private lateinit var selText: TextView
    private lateinit var selShare: Button
    private lateinit var selDelete: Button

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun full() = need.all(::granted)
    // Android 14+: „Wybierz zdjęcia” daje tylko tę zgodę, bez READ_MEDIA_IMAGES
    private fun limited() = Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    private fun request() {
        prefs.edit().putBoolean("asked", true).apply()
        val extra = if (Build.VERSION.SDK_INT >= 34) listOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else emptyList()
        requestPermissions((need + extra).toTypedArray(), 1)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootView.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabsRow = LinearLayout(this)
        tabButtons = TABS.mapIndexed { i, name ->
            Button(this).apply {
                text = name
                isAllCaps = false
                setOnClickListener { open(i, null) }
                tabsRow.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        body.addView(tabsRow)

        header = TextView(this).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 16f
            setOnClickListener { open(tab, null) }
        }
        body.addView(header)

        partial = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
            addView(TextView(context).apply { text = "Widzisz tylko wybrane zdjęcia" }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(context).apply { text = "Pokaż wszystkie"; isAllCaps = false; setOnClickListener { request() } })
        }
        body.addView(partial)

        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(2)
            verticalSpacing = dp(2)
            isFastScrollEnabled = true
            adapter = Adapter()
            setOnItemClickListener { _, _, pos, _ -> if (selected.isNotEmpty()) toggle(rows[pos]) else tap(rows[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> toggle(rows[pos]); true }
        }
        empty = TextView(this).apply {
            gravity = Gravity.CENTER; textSize = 16f; setPadding(dp(24), dp(24), dp(24), dp(24)); text = "Wczytuję…"
        }
        val frame = FrameLayout(this)
        frame.addView(grid)
        frame.addView(empty)
        body.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        selText = TextView(this)
        selShare = Button(this).apply { text = "📤 Udostępnij"; isAllCaps = false; setOnClickListener { Gallery.share(this@MainActivity, picked()) } }
        selDelete = Button(this).apply { isAllCaps = false; setOnClickListener { trashPicked() } }
        selBar = LinearLayout(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
            addView(selText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(selShare)
            addView(selDelete)
            addView(Button(context).apply { text = "✕"; contentDescription = "Odznacz"; setOnClickListener { selected.clear(); show() } })
        }
        body.addView(selBar)

        noAccess = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            addView(TextView(context).apply {
                text = "Żeby pokazać zdjęcia i filmy, Galeria potrzebuje do nich dostępu."
                textSize = 18f
                gravity = Gravity.CENTER
            })
            addView(Button(context).apply {
                text = "Daj dostęp"
                setOnClickListener {
                    // po dwóch odmowach Android już nie pokazuje okna zgody — zostają ustawienia aplikacji
                    if (prefs.getBoolean("asked", false) && !shouldShowRequestPermissionRationale(need[0]))
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    else request()
                }
            })
        }

        rootView.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rootView.addView(noAccess, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(rootView)
        if (!full() && !limited()) request()
    }

    override fun onResume() {
        super.onResume()
        val ok = full() || limited()
        body.visibility = if (ok) View.VISIBLE else View.GONE
        noAccess.visibility = if (ok) View.GONE else View.VISIBLE
        partial.visibility = if (full()) View.GONE else View.VISIBLE
        if (ok) load()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            selected.isNotEmpty() -> { selected.clear(); show() }
            folder != null -> open(tab, null)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == TRASH_MANY && resultCode == RESULT_OK) selected.clear() // listę odświeży onResume
    }

    private fun picked() = rows.filterIsInstance<Media>().filter { it.id in selected }

    private fun toggle(r: Any) {
        val m = r as? Media ?: return // foldery się nie zaznaczają
        if (!selected.remove(m.id)) selected += m.id
        show()
    }

    /** Zaznaczone do kosza (albo z kosza z powrotem); Android sam pyta o potwierdzenie. */
    private fun trashPicked() {
        try {
            val pi = MediaStore.createTrashRequest(contentResolver, picked().map { it.uri }, folder?.trash != true)
            startIntentSenderForResult(pi.intentSender, TRASH_MANY, null, 0, 0, 0)
        } catch (e: Exception) { Toast.makeText(this, "Nie udało się: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    /** Wczytuje od nowa przy każdym powrocie: widać nowe zdjęcia z aparatu, usunięte i zapisane kopie. */
    private fun load() = io.execute {
        val a = try { Gallery.query(this) } catch (e: Exception) { emptyList() }
        val t = try { Gallery.query(this, trashed = true) } catch (e: Exception) { emptyList() }
        main.post { all = a; trash = t; show() }
    }

    private fun open(t: Int, f: Folder?) {
        val back = f == null && folder != null
        if (f != null) foldersPos = grid.firstVisiblePosition
        tab = t
        folder = f
        selected.clear()
        show()
        grid.setSelection(if (back && t == FOLDERS) foldersPos else 0)
    }

    private fun show() {
        val f = folder
        rows = when {
            f?.trash == true -> trash
            f != null -> all.filter { it.bucketId == f.id }
            tab == ALL -> all
            else -> folders(all) + listOfNotNull(trash.firstOrNull()?.let { Folder(0, "🗑 Kosz", it, trash.size, trash = true) })
        }
        grid.columnWidth = dp(if (f == null && tab == FOLDERS) 160 else 96)
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        header.visibility = if (f != null) View.VISIBLE else View.GONE
        header.text = "⬅  ${f?.name} · ${rows.size}" + if (f?.trash == true) "\nUsunięte zdjęcia znikną same po 30 dniach" else ""
        empty.text = when {
            rows.isNotEmpty() -> ""
            f != null -> "Pusty folder"
            else -> "Brak zdjęć i filmów"
        }
        selected.retainAll(rows.filterIsInstance<Media>().map { it.id }.toSet()) // usunięte znikają z zaznaczenia
        selBar.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        selText.text = "Zaznaczono: ${selected.size}"
        selShare.visibility = if (f?.trash == true) View.GONE else View.VISIBLE
        selDelete.text = if (f?.trash == true) "♻ Przywróć" else "🗑 Usuń"
        tabButtons.forEachIndexed { i, b -> b.alpha = if (i == tab) 1f else 0.55f; b.setTypeface(null, if (i == tab) Typeface.BOLD else Typeface.NORMAL) }
    }

    private fun tap(r: Any) {
        if (r is Folder) { open(tab, r); return }
        val m = r as Media
        val f = folder
        val i = Intent(this, ViewerActivity::class.java).putExtra("id", m.id).putExtra("video", m.video)
        if (f?.trash == true) i.putExtra("trash", true) else if (f != null) i.putExtra("bucket", f.id)
        startActivity(i)
    }

    /** Miniatura z systemowej pamięci podręcznej; tag pilnuje, żeby przewinięty kafelek nie dostał cudzego zdjęcia. */
    private fun thumb(iv: ImageView, m: Media, sizeDp: Int) {
        val key = "${m.id}/$sizeDp"
        iv.tag = key
        val hit = cache.get(key)
        iv.setImageBitmap(hit)
        if (hit != null) return
        val px = dp(sizeDp)
        thumbs.execute {
            if (iv.tag != key) return@execute // kafelek przewinięty, zanim przyszła kolej
            val b = try { contentResolver.loadThumbnail(m.uri, Size(px, px), null) } catch (e: Exception) { null } ?: return@execute
            cache.put(key, b)
            main.post { if (iv.tag == key) iv.setImageBitmap(b) }
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p] is Folder) 1 else 0

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val r = rows[p]
            if (r is Folder) {
                val v = convert as? LinearLayout ?: folderTile()
                thumb((v.getChildAt(0) as Square).getChildAt(0) as ImageView, r.cover, 180)
                (v.getChildAt(1) as TextView).text = r.name
                (v.getChildAt(2) as TextView).text = r.count.toString()
                return v
            }
            val m = r as Media
            val v = convert as? Square ?: mediaTile()
            thumb(v.getChildAt(0) as ImageView, m, 110)
            (v.getChildAt(1) as TextView).apply {
                visibility = if (m.video) View.VISIBLE else View.GONE
                text = "▶ " + duration(m.duration)
            }
            v.getChildAt(2).visibility = if (m.id in selected) View.VISIBLE else View.GONE
            return v
        }

        private fun mediaTile() = Square(this@MainActivity).apply {
            addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0x22888888) })
            addView(TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
                setPadding(dp(4), dp(2), dp(4), dp(2))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
            addView(TextView(context).apply { // znaczek zaznaczenia
                text = "✓"
                textSize = 32f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(0x882196F3.toInt())
            })
        }

        private fun folderTile() = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(8))
            addView(Square(context).apply {
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0x22888888) })
            })
            addView(TextView(context).apply { textSize = 15f; setTypeface(null, Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(TextView(context).apply { textSize = 13f; alpha = 0.7f })
        }
    }
}
