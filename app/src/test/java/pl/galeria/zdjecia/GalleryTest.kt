package pl.galeria.zdjecia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class GalleryTest {
    private fun media(id: Long, bucket: Long, date: Long) = Media(id, false, bucket, "f$bucket", date, 0, "$id.jpg")

    @Test fun folders() {
        val f = folders(listOf(media(1, 10, 900), media(2, 20, 800), media(3, 10, 700), media(4, 30, 600)))
        assertEquals(listOf(10L, 20L, 30L), f.map { it.id }) // od folderu z najnowszym zdjęciem
        assertEquals(listOf(2, 1, 1), f.map { it.count })
        assertEquals(1L, f[0].cover.id)
    }

    @Test fun durations() {
        assertEquals("0:42", duration(42_000))
        assertEquals("1:02", duration(62_000))
        assertEquals("1:02:03", duration(3_723_000))
    }

    @Test fun shareTypes() {
        assertEquals("image/*", shareType(listOf(false, false)))
        assertEquals("video/*", shareType(listOf(true)))
        assertEquals("*/*", shareType(listOf(false, true)))
    }

    @Test fun fit() {
        assertEquals(4096 to 3072, fitSize(8000, 6000, 4096))
        assertEquals(1000 to 800, fitSize(1000, 800, 4096))
        assertEquals(1 to 4096, fitSize(10, 100_000, 4096)) // skrajne proporcje: bok nie spada do zera
    }

    @Test fun offsets() {
        assertEquals(50f, fixOffset(0f, 100f, 200f), 0f)    // mniejszy od ekranu → na środek
        assertEquals(-10f, fixOffset(10f, 400f, 200f), 0f)  // pusty pas z lewej → dosuń
        assertEquals(20f, fixOffset(-220f, 400f, 200f), 0f) // pusty pas z prawej → dosuń
        assertEquals(0f, fixOffset(-100f, 400f, 200f), 0f)  // w granicach → bez zmian
    }

    @Test fun arrow() {
        val h = arrowHead(0f, 0f, 100f, 0f, 10f) // strzałka w prawo
        assertEquals(10f, hypot(h[0] - 100f, h[1]), 0.001f)
        assertEquals(10f, hypot(h[2] - 100f, h[3]), 0.001f)
        assertTrue(h[0] < 100f && h[2] < 100f) // grot cofa się od czubka, nie wystaje przed
        assertEquals(h[1], -h[3], 0.001f)        // symetrycznie
    }
}
