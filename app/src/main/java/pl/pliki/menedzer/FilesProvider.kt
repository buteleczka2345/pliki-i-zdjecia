package pl.pliki.menedzer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Udostępnia pliki innym aplikacjom (Otwórz / Udostępnij / instalator APK) przez content://.
 * Nie jest eksportowany: inna aplikacja widzi tylko ten plik, do którego dostała jednorazową zgodę.
 */
class FilesProvider : ContentProvider() {
    companion object {
        private const val AUTH = "pl.pliki.zdjecia.files"
        fun uriFor(file: File): Uri = Uri.Builder().scheme("content").authority(AUTH).appendPath(file.path).build()
        private fun fileOf(uri: Uri) = File(uri.lastPathSegment!!)
    }

    override fun onCreate() = true

    override fun getType(uri: Uri) = Files.mime(fileOf(uri))

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): MatrixCursor {
        val f = fileOf(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else if (it == OpenableColumns.DISPLAY_NAME) f.name else null })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
}
