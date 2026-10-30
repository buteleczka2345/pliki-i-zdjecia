package pl.pliki.menedzer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.util.Collections

class Item(val file: File, val date: Long, val size: Long, val id: Long = -1)

object Files {
    // shortcut: znane foldery, do których aplikacje zapisują pobrane pliki; dopisz tu kolejne, jeśli czegoś brakuje.
    private val DOWNLOAD_DIRS = listOf("Download", "Bluetooth", "Telegram", "WhatsApp/Media", "Messenger")

    /** Ścieżki plików, które sami skopiowaliśmy/przenieśliśmy — usługa nie zgłasza ich jako nowe pobrania. */
    val ours: MutableSet<String> = Collections.synchronizedSet(HashSet())

    val phoneRoot: File get() = Environment.getExternalStorageDirectory()
    val downloads: File get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    fun sdRoot(ctx: Context): File? = ctx.getSystemService(StorageManager::class.java).storageVolumes
        .firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }?.directory

    fun hasAccess() = Environment.isExternalStorageManager()

    /** Ostatnie pobrania z całej pamięci (telefon + karta SD), najnowsze pierwsze. */
    fun recentDownloads(ctx: Context, days: Int, limit: Int): List<Item> {
        val cutoff = System.currentTimeMillis() / 1000 - days * 86400L
        val sel = "${MediaStore.MediaColumns.DATE_ADDED} >= ? AND ${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL AND (" +
            DOWNLOAD_DIRS.joinToString(" OR ") { "${MediaStore.MediaColumns.DATA} LIKE ?" } + ")"
        val args = arrayOf(cutoff.toString()) + DOWNLOAD_DIRS.map { "%/$it/%" }
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED)
        val out = ArrayList<Item>()
        ctx.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), cols, sel, args,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val path = c.getString(1) ?: continue
                val file = File(path)
                if (isTemp(file.name)) continue
                out += Item(file, c.getLong(3) * 1000, c.getLong(2), c.getLong(0))
            }
        }
        return out
    }

    fun list(dir: File): List<Item> = (dir.listFiles() ?: emptyArray())
        .filter { !it.name.startsWith(".") }
        .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        .map { Item(it, it.lastModified(), if (it.isDirectory) -1 else it.length()) }

    fun mime(file: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(file.name)) ?: "*/*"

    fun uri(file: File): Uri = FilesProvider.uriFor(file)

    fun viewIntent(file: File) = Intent(Intent.ACTION_VIEW).setDataAndType(uri(file), mime(file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun open(ctx: Context, file: File) {
        if (kindOf(file.name) == Kind.APK && !ctx.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(ctx, "Zezwól na instalowanie aplikacji z „Pliki” i wróć", Toast.LENGTH_LONG).show()
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        try { ctx.startActivity(viewIntent(file)) }
        catch (e: ActivityNotFoundException) { Toast.makeText(ctx, "Brak aplikacji, która otworzy ten plik", Toast.LENGTH_SHORT).show() }
    }

    fun share(ctx: Context, file: File) {
        val send = Intent(Intent.ACTION_SEND).setType(mime(file)).putExtra(Intent.EXTRA_STREAM, uri(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, file.name))
    }

    /** Kopiuje albo przenosi plik/folder do [dstDir]; przy błędzie kopiowania usuwa niedokończoną kopię, oryginał zostaje. */
    fun transfer(ctx: Context, src: File, dstDir: File, move: Boolean): File {
        if (dstDir.canonicalPath == src.canonicalPath || dstDir.canonicalPath.startsWith(src.canonicalPath + "/"))
            throw IOException("Nie można wkleić folderu do niego samego")
        dstDir.mkdirs()
        val dst = File(dstDir, uniqueName(src.name) { File(dstDir, it).exists() })
        ours += dst.path
        if (!(move && src.renameTo(dst))) {
            try {
                src.copyRecursively(dst)
            } catch (e: Exception) {
                dst.deleteRecursively()
                throw IOException("Nie udało się skopiować: ${e.message}", e)
            }
            if (move && !src.deleteRecursively()) throw IOException("Skopiowano, ale nie udało się usunąć oryginału")
        }
        scan(ctx, dst, if (move) src else null)
        return dst
    }

    fun rename(ctx: Context, file: File, name: String): File {
        val dst = File(file.parentFile, name)
        if (name.isBlank() || '/' in name) throw IOException("Niepoprawna nazwa")
        if (dst.exists()) throw IOException("Taki plik już istnieje")
        if (!file.renameTo(dst)) throw IOException("Nie udało się zmienić nazwy")
        ours += dst.path
        scan(ctx, dst, file)
        return dst
    }

    fun mkdir(parent: File, name: String): File {
        if (name.isBlank() || '/' in name || name == "." || name == "..") throw IOException("Niepoprawna nazwa")
        val dir = File(parent, name)
        if (dir.exists()) throw IOException("Taki folder już istnieje")
        if (!dir.mkdir()) throw IOException("Nie udało się utworzyć folderu")
        return dir
    }

    fun delete(ctx: Context, file: File) {
        if (!file.deleteRecursively()) throw IOException("Nie udało się usunąć")
        scan(ctx, file)
    }

    fun canUnpack(f: File) = f.isFile && ext(f.name) in setOf("zip", "7z")

    /** Rozpakowuje ZIP/7z do nowego folderu obok (nazwa jak archiwum). Przy błędzie folder jest usuwany. */
    fun unzip(ctx: Context, archive: File): File {
        val parent = archive.parentFile!!
        val dir = File(parent, uniqueName(archive.name.substringBeforeLast('.')) { File(parent, it).exists() })
        ours += dir.path
        // ZIP-y z Windows często mają polskie nazwy w CP852 zamiast UTF-8; 7z zawsze zapisuje nazwy poprawnie
        val tries: List<() -> Unit> = if (ext(archive.name) == "7z") listOf { extract7z(archive, dir) }
        else listOf(Charsets.UTF_8, charset("IBM852")).map { cs -> { extractZip(archive, dir, cs) } }
        for (t in tries) {
            try {
                t()
                scan(ctx, dir)
                return dir
            } catch (e: IllegalArgumentException) {
                dir.deleteRecursively() // zła strona kodowa nazw — próbujemy następnej
            } catch (e: org.apache.commons.compress.PasswordRequiredException) {
                dir.deleteRecursively()
                throw IOException("Archiwum jest zabezpieczone hasłem")
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw IOException("Nie udało się rozpakować: ${e.message}", e)
            }
        }
        throw IOException("Nie udało się odczytać nazw plików w archiwum")
    }

    internal fun extractZip(zip: File, dir: File, cs: java.nio.charset.Charset) = java.util.zip.ZipFile(zip, cs).use { z ->
        for (e in z.entries()) write(dir, e.name, e.isDirectory) { z.getInputStream(e) }
        dir.mkdirs() // pusty ZIP → pusty folder
    }

    internal fun extract7z(file: File, dir: File) = org.apache.commons.compress.archivers.sevenz.SevenZFile.builder().setFile(file).get().use { z ->
        for (e in z.entries) write(dir, e.name, e.isDirectory) { z.getInputStream(e) }
        dir.mkdirs()
    }

    private fun write(dir: File, name: String, isDir: Boolean, open: () -> java.io.InputStream) {
        val out = File(dir, name)
        if (!out.canonicalPath.startsWith(dir.canonicalPath + File.separator)) throw IOException("Podejrzana ścieżka w archiwum: $name")
        if (isDir) { out.mkdirs(); return }
        out.parentFile!!.mkdirs()
        open().use { i -> out.outputStream().use { i.copyTo(it) } }
    }

    /** Odświeża systemową bazę plików, żeby lista ostatnich i inne aplikacje widziały zmianę. */
    private fun scan(ctx: Context, vararg files: File?) {
        val paths = files.filterNotNull().flatMap { f -> if (f.isDirectory) f.walk().map { it.path }.toList() else listOf(f.path) }
        MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
    }
}
