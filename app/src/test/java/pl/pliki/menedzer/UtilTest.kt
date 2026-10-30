package pl.pliki.menedzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class UtilTest {
    @Test fun kinds() {
        assertEquals(Kind.APK, kindOf("gra.APK"))
        assertEquals(Kind.DOC, kindOf("faktura.pdf"))
        assertEquals(Kind.ARCHIVE, kindOf("a.tar.gz"))
        assertEquals(Kind.OTHER, kindOf("bez_rozszerzenia"))
        assertTrue(isTemp("film.mp4.crdownload"))
        assertTrue(isTemp(".pending-123-x.pdf"))
    }

    @Test fun sizes() {
        assertEquals("512 B", formatSize(512))
        assertEquals("1,5 KB", formatSize(1536))
        assertEquals("25 MB", formatSize(25L * 1024 * 1024))
    }

    @Test fun days() {
        val z: ZoneId = ZoneOffset.UTC
        val today = LocalDate.of(2026, 10, 29)
        fun ms(d: LocalDate) = d.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("Dziś", dayGroup(ms(today), today, z))
        assertEquals("Wczoraj", dayGroup(ms(today.minusDays(1)), today, z))
        assertEquals("Ten tydzień", dayGroup(ms(today.minusDays(6)), today, z))
        assertEquals("Wcześniej", dayGroup(ms(today.minusDays(7)), today, z))
    }

    @Test fun unique() {
        val taken = setOf("a.pdf", "a (1).pdf", "folder")
        assertEquals("b.pdf", uniqueName("b.pdf") { it in taken })
        assertEquals("a (2).pdf", uniqueName("a.pdf") { it in taken })
        assertEquals("folder (1)", uniqueName("folder") { it in taken })
    }
}
