package pl.pliki.menedzer

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

enum class Kind(val icon: String, val label: String) {
    IMAGE("🖼", "Obrazy"), VIDEO("🎬", "Wideo"), DOC("📄", "Dokumenty"), APK("🤖", "APK"),
    ARCHIVE("🗜", "Archiwa"), AUDIO("🎵", "Muzyka"), OTHER("📎", "Inne"), DIR("📁", "Foldery")
}

private val EXT = mapOf(
    Kind.IMAGE to "jpg jpeg png gif webp heic heif bmp",
    Kind.VIDEO to "mp4 mkv avi mov webm 3gp m4v",
    Kind.DOC to "pdf doc docx xls xlsx ppt pptx txt odt ods odp csv rtf epub",
    Kind.APK to "apk",
    Kind.ARCHIVE to "zip rar 7z tar gz tgz bz2 xz apks xapk",
    Kind.AUDIO to "mp3 m4a aac ogg opus wav flac amr",
).flatMap { (k, v) -> v.split(' ').map { it to k } }.toMap()

fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

fun kindOf(name: String) = EXT[ext(name)] ?: Kind.OTHER

// Pliki tymczasowe przeglądarek w trakcie pobierania — nie zgłaszamy ich jako "pobrano".
fun isTemp(name: String) = name.startsWith(".") || ext(name) in setOf("tmp", "crdownload", "part", "download", "partial")

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale("pl"), if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
}

fun dayGroup(ms: Long, today: LocalDate, zone: ZoneId): String {
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    return when {
        !d.isBefore(today) -> "Dziś"
        d == today.minusDays(1) -> "Wczoraj"
        d.isAfter(today.minusDays(7)) -> "Ten tydzień"
        else -> "Wcześniej"
    }
}

/** "a.pdf" zajęte → "a (1).pdf", "a (2).pdf"… */
fun uniqueName(name: String, exists: (String) -> Boolean): String {
    if (!exists(name)) return name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    val base = name.substring(0, dot)
    val tail = name.substring(dot)
    var n = 1
    while (exists("$base ($n)$tail")) n++
    return "$base ($n)$tail"
}
