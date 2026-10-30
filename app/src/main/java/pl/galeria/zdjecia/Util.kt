package pl.galeria.zdjecia

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 42 000 ms → "0:42", 3 723 000 ms → "1:02:03". */
fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

/** Typ do udostępniania: same filmy, same zdjęcia albo mieszanka. */
fun shareType(videos: List<Boolean>) = when {
    videos.all { it } -> "video/*"
    videos.none { it } -> "image/*"
    else -> "*/*"
}

/** Wymiary pomniejszone tak, żeby dłuższy bok miał najwyżej [max]; mniejszych nie powiększa. */
fun fitSize(w: Int, h: Int, max: Int): Pair<Int, Int> {
    val k = maxOf(w, h).toFloat() / max
    if (k <= 1f) return w to h
    return (w / k).roundToInt().coerceAtLeast(1) to (h / k).roundToInt().coerceAtLeast(1)
}

/** O ile przesunąć obraz (początek, rozmiar) w oknie [view]: mniejszy — na środek, większy — bez pustego pasa przy krawędzi. */
fun fixOffset(start: Float, size: Float, view: Float): Float = when {
    size <= view -> (view - size) / 2 - start
    start > 0 -> -start
    start + size < view -> view - (start + size)
    else -> 0f
}

/** Końce dwóch kresek grotu strzałki z (x0, y0) do (x1, y1): [xa, ya, xb, yb]. */
fun arrowHead(x0: Float, y0: Float, x1: Float, y1: Float, len: Float): FloatArray {
    val a = atan2(y1 - y0, x1 - x0)
    return floatArrayOf(
        x1 - len * cos(a - 0.5f), y1 - len * sin(a - 0.5f),
        x1 - len * cos(a + 0.5f), y1 - len * sin(a + 0.5f),
    )
}
