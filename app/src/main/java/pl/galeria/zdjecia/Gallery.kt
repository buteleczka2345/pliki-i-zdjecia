package pl.galeria.zdjecia

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.IOException

class Media(
    val id: Long, val video: Boolean, val bucketId: Long, val bucket: String,
    val date: Long, val duration: Long, val name: String, private val ext: Uri? = null,
) {
    /** Plik z innej aplikacji („Otwórz za pomocą”) — nie ma go w bazie zdjęć, więc nie da się go usunąć. */
    val external get() = ext != null
    val uri: Uri get() = ext ?: ContentUris.withAppendedId(
        if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
}

class Folder(val id: Long, val name: String, val cover: Media, val count: Int, val trash: Boolean = false)

/** Foldery od tego z najnowszym zdjęciem; [all] musi być od najnowszych, więc okładka = najnowsze zdjęcie. */
fun folders(all: List<Media>) = all.groupBy { it.bucketId }.map { (id, l) -> Folder(id, l[0].bucket, l[0], l.size) }

object Gallery {
    // shortcut: tłumaczymy tylko najczęstsze nazwy folderów; dopisz kolejne, jeśli trzeba.
    private val NAMES = mapOf("Camera" to "Aparat", "Screenshots" to "Zrzuty ekranu", "Download" to "Pobrane")
    // shortcut: zdjęcie ładujemy najwyżej 4096 px na dłuższym boku (limit tekstury GPU), więc kopia z kredką
    // ze zdjęć 50 MP+ jest pomniejszona; pełna rozdzielczość wymagałaby rysowania po kawałkach oryginału.
    private const val MAX_SIDE = 4096

    /** Wszystkie zdjęcia i filmy z telefonu i karty SD (albo tylko te z kosza), od najnowszych. */
    fun query(ctx: Context, trashed: Boolean = false): List<Media> {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
        }
        val out = ArrayList<Media>()
        for (video in listOf(false, true)) {
            val coll = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val cols = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DISPLAY_NAME,
            ) + if (video) arrayOf(MediaStore.MediaColumns.DURATION) else emptyArray()
            ctx.contentResolver.query(coll, cols, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val dir = c.getString(2) ?: "Pamięć telefonu"
                    val sd = if (c.getString(3) != MediaStore.VOLUME_EXTERNAL_PRIMARY) " (karta SD)" else ""
                    // zrzuty ekranu i pobrane obrazki często nie mają daty zrobienia — wtedy data pliku
                    val date = c.getLong(4).takeIf { it > 0 } ?: (c.getLong(5) * 1000)
                    out += Media(c.getLong(0), video, c.getLong(1), (NAMES[dir] ?: dir) + sd, date,
                        if (video) c.getLong(7) else 0, c.getString(6) ?: "")
                }
            }
        }
        return out.sortedByDescending { it.date }
    }

    /** Zdjęcie obrócone według EXIF (także HEIC), dłuższy bok najwyżej [MAX_SIDE]. */
    fun decode(ctx: Context, uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            val (w, h) = fitSize(info.size.width, info.size.height, MAX_SIDE)
            if (w != info.size.width) d.setTargetSize(w, h)
        }

    /** Zapisuje kopię jako nowe zdjęcie w Pictures/Zaznaczone; przy błędzie usuwa niedokończony wpis. */
    fun save(ctx: Context, bmp: Bitmap, name: String): Uri {
        val r = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Zaznaczone")
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Nie udało się zapisać")
        try {
            r.openOutputStream(uri)!!.use { if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)) throw IOException("błąd JPEG") }
            r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            r.delete(uri, null, null)
            throw IOException("Nie udało się zapisać: ${e.message}", e)
        }
        return uri
    }

    /** Jedno albo kilka zdjęć i filmów naraz (WhatsApp, Messenger, e-mail…). */
    fun share(ctx: Context, items: List<Media>) = share(ctx, items.map { it.uri }, shareType(items.map { it.video }))

    fun share(ctx: Context, uris: List<Uri>, type: String) {
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { ctx.startActivity(Intent.createChooser(send, null)) }
        catch (e: Exception) { Toast.makeText(ctx, "Nie udało się udostępnić", Toast.LENGTH_SHORT).show() }
    }
}
