package pl.pliki.menedzer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private const val RECENT = 0
private val TABS = listOf("Ostatnie", "Pobrane", "Telefon", "Karta SD")

class MainActivity : Activity() {
    companion object { const val OPEN = "open" }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val thumbs = Executors.newFixedThreadPool(2)
    private val thumbCache = LruCache<String, Bitmap>(150)

    private val prefs by lazy { getSharedPreferences("pliki", MODE_PRIVATE) }
    private var tab = RECENT
    private var root: File? = null
    private var current: File? = null
    private var filter: Kind? = null
    private var all: List<Item> = emptyList()
    private var rows: List<Any> = emptyList() // String = nagłówek dnia, Item = plik
    private var clip: Pair<File, Boolean>? = null // plik + czy przenieść (false = kopiuj)

    private lateinit var tabButtons: List<Button>
    private lateinit var search: EditText
    private lateinit var chips: View
    private lateinit var chipButtons: List<Pair<Kind?, Button>>
    private lateinit var pathBar: TextView
    private lateinit var listView: ListView
    private lateinit var empty: TextView
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteText: TextView
    private lateinit var body: LinearLayout
    private lateinit var noAccess: LinearLayout

    private val refresh = Runnable { load() }
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            main.removeCallbacks(refresh)
            main.postDelayed(refresh, 700)
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootView.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabsRow = LinearLayout(this)
        tabButtons = TABS.mapIndexed { i, name ->
            Button(this).apply {
                text = name
                isAllCaps = false
                setOnClickListener { selectTab(i) }
                tabsRow.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        // Galeria z dawnej aplikacji „Zdjęcia”; Wstecz wraca tutaj
        tabsRow.addView(Button(this).apply {
            text = "📷 Zdjęcia"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@MainActivity, pl.galeria.zdjecia.MainActivity::class.java)) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(tabsRow)

        search = EditText(this).apply {
            hint = "🔍 Szukaj po nazwie"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = applyFilter()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        body.addView(search)

        val chipRow = LinearLayout(this)
        chipButtons = (listOf<Kind?>(null) + listOf(Kind.IMAGE, Kind.VIDEO, Kind.DOC, Kind.APK, Kind.ARCHIVE, Kind.AUDIO)).map { k ->
            k to Button(this).apply {
                text = k?.let { "${it.icon} ${it.label}" } ?: "Wszystko"
                isAllCaps = false
                setOnClickListener { filter = k; applyFilter() }
                chipRow.addView(this)
            }
        }
        chips = HorizontalScrollView(this).apply { addView(chipRow); isHorizontalScrollBarEnabled = false }
        body.addView(chips)

        pathBar = TextView(this).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 14f
            setOnClickListener { goUp() }
        }
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(pathBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply { text = "＋ Folder"; isAllCaps = false; setOnClickListener { newFolderDialog() } })
        })

        val listFrame = FrameLayout(this)
        listView = ListView(this).apply {
            adapter = Adapter()
            setOnItemClickListener { _, _, pos, _ -> (rows[pos] as? Item)?.let { tap(it.file) } }
            setOnItemLongClickListener { _, _, pos, _ -> (rows[pos] as? Item)?.let { menu(it.file) }; true }
        }
        empty = TextView(this).apply { gravity = Gravity.CENTER; textSize = 16f; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        listFrame.addView(listView)
        listFrame.addView(empty)
        body.addView(listFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        pasteBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(4), 0) }
        pasteText = TextView(this)
        pasteBar.addView(pasteText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pasteBar.addView(Button(this).apply { text = "Wklej tutaj"; isAllCaps = false; setOnClickListener { paste() } })
        pasteBar.addView(Button(this).apply { text = "✕"; setOnClickListener { clip = null; updateBars() } })
        body.addView(pasteBar)

        noAccess = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            addView(TextView(context).apply {
                text = "Żeby pokazać pobrane pliki, telefon i kartę SD, aplikacja potrzebuje zgody „Dostęp do wszystkich plików”."
                textSize = 18f
                gravity = Gravity.CENTER
            })
            addView(Button(context).apply {
                text = "Daj dostęp"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                }
            })
        }

        rootView.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rootView.addView(noAccess, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(rootView)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        tab = prefs.getInt("tab", RECENT)
        handleOpen(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    private fun handleOpen(i: Intent?) {
        i?.getStringExtra(OPEN)?.let { Files.open(this, File(it)) }
        i?.removeExtra(OPEN)
    }

    override fun onResume() {
        super.onResume()
        val ok = Files.hasAccess()
        body.visibility = if (ok) View.VISIBLE else View.GONE
        noAccess.visibility = if (ok) View.GONE else View.VISIBLE
        if (!ok) return
        DownloadWatchService.start(this)
        contentResolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer)
        selectTab(tab)
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(observer)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            tab != RECENT && current != root -> goUp()
            tab != RECENT -> selectTab(RECENT)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun selectTab(i: Int) {
        val r = when (i) {
            1 -> Files.downloads
            2 -> Files.phoneRoot
            3 -> Files.sdRoot(this) ?: run { toast("Nie wykryto karty SD"); if (tab == 3) selectTab(RECENT); return }
            else -> null
        }
        // każda zakładka wraca do folderu, w którym była ostatnio (także po zamknięciu aplikacji)
        current = prefs.getString("dir$i", null)?.let(::File)
            ?.takeIf { r != null && it.isDirectory && (it == r || it.path.startsWith(r.path + "/")) } ?: r
        tab = i
        root = r
        load()
    }

    private fun goUp() {
        val c = current ?: return
        if (c != root) { current = c.parentFile; load() }
    }

    private fun load() {
        val t = tab
        val dir = current
        prefs.edit().putInt("tab", t).apply { if (dir != null) putString("dir$t", dir.path) }.apply()
        io.execute {
            val items = try {
                if (t == RECENT) Files.recentDownloads(this, 30, 500) else Files.list(dir!!)
            } catch (e: Exception) { emptyList() }
            main.post { if (t == tab && dir == current) { all = items; applyFilter() } }
        }
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        var items = all.filter { q.isEmpty() || it.file.name.lowercase().contains(q) }
        if (tab == RECENT && filter != null) items = items.filter { kindOf(it.file.name) == filter }
        rows = if (tab == RECENT) {
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            val out = ArrayList<Any>()
            var last: String? = null
            for (it in items) {
                val g = dayGroup(it.date, today, zone)
                if (g != last) { out += g; last = g }
                out += it
            }
            out
        } else items
        (listView.adapter as BaseAdapter).notifyDataSetChanged()
        empty.text = when {
            rows.isNotEmpty() -> ""
            tab == RECENT -> "Brak pobranych plików z ostatnich 30 dni"
            else -> "Pusty folder"
        }
        updateBars()
    }

    private fun updateBars() {
        tabButtons.forEachIndexed { i, b -> b.alpha = if (i == tab) 1f else 0.55f; b.setTypeface(null, if (i == tab) Typeface.BOLD else Typeface.NORMAL) }
        chipButtons.forEach { (k, b) -> b.alpha = if (k == filter) 1f else 0.55f }
        chips.visibility = if (tab == RECENT) View.VISIBLE else View.GONE
        (pathBar.parent as View).visibility = if (tab == RECENT) View.GONE else View.VISIBLE
        val c = current
        if (c != null && root != null) {
            val rel = c.path.removePrefix(root!!.path)
            pathBar.text = (if (c != root) "⬆  " else "📂  ") + TABS[tab] + rel
        }
        val cl = clip
        pasteBar.visibility = if (cl != null && tab != RECENT) View.VISIBLE else View.GONE
        if (cl != null) pasteText.text = (if (cl.second) "Przenieś: " else "Kopiuj: ") + cl.first.name
    }

    private fun tap(f: File) {
        if (f.isDirectory) {
            if (tab == RECENT) showInFolder(f) else { current = f; load() }
        } else if (Files.canUnpack(f)) {
            AlertDialog.Builder(this).setTitle(f.name)
                .setItems(arrayOf(unzipLabel(f), "Otwórz w innej aplikacji")) { _, w -> if (w == 0) unzip(f) else Files.open(this, f) }
                .show()
        } else Files.open(this, f)
    }

    private fun menu(f: File) {
        val sd = Files.sdRoot(this)
        val onSd = sd != null && f.path.startsWith(sd.path + "/")
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (f.isFile) actions += (if (kindOf(f.name) == Kind.APK) "Zainstaluj" else "Otwórz") to { Files.open(this, f) }
        if (Files.canUnpack(f)) actions += unzipLabel(f) to { unzip(f) }
        if (f.isFile) actions += "Udostępnij" to { Files.share(this, f) }
        if (tab == RECENT) actions += "Pokaż w folderze" to { showInFolder(f.parentFile!!) }
        actions += "Zmień nazwę" to { renameDialog(f) }
        actions += "Kopiuj…" to { clip = f to false; hintPaste() }
        actions += "Przenieś…" to { clip = f to true; hintPaste() }
        if (sd != null && !onSd) actions += "Przenieś na kartę SD (Download)" to { doOp("Przeniesiono na kartę SD") { Files.transfer(this, f, File(sd, "Download"), true) } }
        if (onSd) actions += "Przenieś do Pobranych w telefonie" to { doOp("Przeniesiono do Pobranych") { Files.transfer(this, f, Files.downloads, true) } }
        actions += "Usuń" to {
            AlertDialog.Builder(this).setTitle("Usunąć na zawsze?").setMessage(f.name)
                .setPositiveButton("Usuń") { _, _ -> doOp("Usunięto") { Files.delete(this, f) } }
                .setNegativeButton("Anuluj", null).show()
        }
        AlertDialog.Builder(this).setTitle(f.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, w -> actions[w].second() }
            .show()
    }

    private fun showInFolder(dir: File) {
        val sd = Files.sdRoot(this)
        tab = if (sd != null && dir.path.startsWith(sd.path)) 3 else 2
        root = if (tab == 3) sd else Files.phoneRoot
        current = dir
        search.setText("")
        load()
    }

    private fun hintPaste() {
        toast("Wejdź do folderu docelowego i dotknij „Wklej tutaj”")
        if (tab == RECENT) showInFolder(Files.downloads) else updateBars()
    }

    private fun paste() {
        val (f, move) = clip ?: return
        val dst = current ?: return
        clip = null
        doOp(if (move) "Przeniesiono" else "Skopiowano") { Files.transfer(this, f, dst, move) }
    }

    private fun unzipLabel(f: File) = "Rozpakuj do folderu „${f.name.substringBeforeLast('.')}”"

    /** Rozpakowuje do nowego folderu obok archiwum i od razu go otwiera. */
    private fun unzip(f: File) {
        toast("Rozpakowuję…")
        io.execute {
            val dir = try { Files.unzip(this, f) } catch (e: Exception) { main.post { toast(e.message ?: "Błąd") }; return@execute }
            main.post { toast("Rozpakowano do folderu ${dir.name}"); showInFolder(dir) }
        }
    }

    private fun renameDialog(f: File) {
        val input = EditText(this).apply { setText(f.name); setSelection(0, f.name.substringBeforeLast('.').length.coerceAtLeast(1)) }
        AlertDialog.Builder(this).setTitle("Zmień nazwę").setView(input)
            .setPositiveButton("OK") { _, _ -> doOp("Zmieniono nazwę") { Files.rename(this, f, input.text.toString().trim()) } }
            .setNegativeButton("Anuluj", null).show()
    }

    private fun newFolderDialog() {
        val dir = current ?: return
        val input = EditText(this).apply { hint = "Nazwa folderu"; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("Nowy folder").setView(input)
            .setPositiveButton("Utwórz") { _, _ -> doOp("Utworzono folder") { Files.mkdir(dir, input.text.toString().trim()) } }
            .setNegativeButton("Anuluj", null).show()
    }

    /** Operacja na plikach w tle; po niej komunikat i odświeżenie listy. */
    private fun doOp(done: String, op: () -> Any) {
        toast("Pracuję…")
        io.execute {
            val msg = try { op(); done } catch (e: Exception) { e.message ?: "Błąd" }
            main.post { toast(msg); load() }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private inner class Adapter : BaseAdapter() {
        private val time = SimpleDateFormat("d MMM, HH:mm", Locale("pl"))
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p] is String) 0 else 1
        override fun isEnabled(p: Int) = rows[p] is Item

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val r = rows[p]
            if (r is String) return (convert as? TextView ?: TextView(this@MainActivity).apply {
                setPadding(dp(12), dp(14), dp(12), dp(4)); textSize = 13f; setTypeface(null, Typeface.BOLD); alpha = 0.7f
            }).apply { text = r }
            val item = r as Item
            val row = convert as? LinearLayout ?: makeRow()
            val icon = row.getChildAt(0) as FrameLayout
            val emoji = icon.getChildAt(0) as TextView
            val thumb = icon.getChildAt(1) as ImageView
            val texts = row.getChildAt(1) as LinearLayout
            val f = item.file
            val kind = if (f.isDirectory) Kind.DIR else kindOf(f.name)
            emoji.text = kind.icon
            (texts.getChildAt(0) as TextView).text = f.name
            (texts.getChildAt(1) as TextView).text = listOfNotNull(
                if (item.size >= 0) formatSize(item.size) else null,
                if (tab == RECENT) f.parentFile?.name else null,
                time.format(Date(item.date)),
            ).joinToString(" · ")
            (row.getChildAt(2) as TextView).apply {
                visibility = if (kind == Kind.DIR) View.GONE else View.VISIBLE
                setOnClickListener { Files.share(this@MainActivity, f) }
            }
            thumb.tag = f.path
            thumb.setImageBitmap(thumbCache.get(f.path))
            if (thumbCache.get(f.path) == null && (kind == Kind.IMAGE || kind == Kind.VIDEO)) thumbs.execute {
                val bmp = try {
                    if (kind == Kind.IMAGE) ThumbnailUtils.createImageThumbnail(f, Size(dp(48), dp(48)), null)
                    else ThumbnailUtils.createVideoThumbnail(f, Size(dp(48), dp(48)), null)
                } catch (e: Exception) { null } ?: return@execute
                thumbCache.put(f.path, bmp)
                main.post { if (thumb.tag == f.path) thumb.setImageBitmap(bmp) }
            }
            return row
        }

        private fun makeRow() = LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(FrameLayout(context).apply {
                addView(TextView(context).apply { textSize = 26f; gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(context).apply { textSize = 16f; maxLines = 2 })
                addView(TextView(context).apply { textSize = 13f; alpha = 0.7f })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            // Udostępnij jednym dotknięciem; nie przejmuje fokusu, więc dotyk reszty wiersza dalej otwiera plik
            addView(TextView(context).apply {
                text = "📤"
                textSize = 22f
                gravity = Gravity.CENTER
                contentDescription = "Udostępnij"
                isFocusable = false
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
    }
}
