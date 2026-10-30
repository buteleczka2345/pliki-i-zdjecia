package pl.pliki.menedzer

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files as NioFiles
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UnpackTest {
    private val tmp: File = NioFiles.createTempDirectory("pliki").toFile()

    @Test fun sevenZipRoundTrip() {
        val archive = File(tmp, "a.7z")
        SevenZOutputFile(archive).use { out ->
            val src = File(tmp, "zażółć.txt").apply { writeText("cześć") }
            out.putArchiveEntry(out.createArchiveEntry(src, "folder/zażółć.txt"))
            out.write(src.readBytes())
            out.closeArchiveEntry()
        }
        val dir = File(tmp, "out")
        Files.extract7z(archive, dir)
        assertEquals("cześć", File(dir, "folder/zażółć.txt").readText())
    }

    @Test(expected = IOException::class) fun zipSlipBlocked() {
        val archive = File(tmp, "zly.zip")
        ZipOutputStream(archive.outputStream()).use { it.putNextEntry(ZipEntry("../poza.txt")); it.write(1) }
        try { Files.extractZip(archive, File(tmp, "out2"), Charsets.UTF_8) }
        finally { assertFalse(File(tmp, "poza.txt").exists()) }
    }
}
