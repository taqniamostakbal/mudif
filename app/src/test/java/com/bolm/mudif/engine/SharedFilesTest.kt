package com.bolm.mudif.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * F3: the pure pieces of the file transfer. Runs with
 * testDebugUnitTest (and directly with JUnitCore, like the
 * other engine tests, because the Gradle test executor times
 * out on this VM).
 */
class SharedFilesTest {

    @Test
    fun `uniqueName keeps a free name`() {
        val dir = Files.createTempDirectory("f3test").toFile()
        try {
            assertEquals("photo.jpg", SharedFiles.uniqueName(dir, "photo.jpg"))
            assertEquals("file", SharedFiles.uniqueName(dir, ""))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `uniqueName numbers a clash and never overwrites`() {
        val dir = Files.createTempDirectory("f3test").toFile()
        try {
            File(dir, "photo.jpg").writeText("original")
            assertEquals("photo (2).jpg", SharedFiles.uniqueName(dir, "photo.jpg"))
            File(dir, "photo (2).jpg").writeText("second")
            assertEquals("photo (3).jpg", SharedFiles.uniqueName(dir, "photo.jpg"))
            File(dir, "notes").writeText("x")
            assertEquals("notes (2)", SharedFiles.uniqueName(dir, "notes"))
            assertEquals("original", File(dir, "photo.jpg").readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `formatSize uses Latin digits`() {
        assertEquals("0 B", SharedFiles.formatSize(0))
        assertEquals("42 B", SharedFiles.formatSize(42))
        assertEquals("1023 B", SharedFiles.formatSize(1023))
        assertEquals("1 KB", SharedFiles.formatSize(1024))
        assertEquals("2 KB", SharedFiles.formatSize(2048))
        assertEquals("12.5 MB", SharedFiles.formatSize(13_107_200))
        assertEquals("1.0 GB", SharedFiles.formatSize(1024L * 1024 * 1024))
        assertEquals("0 B", SharedFiles.formatSize(-5))
    }

    @Test
    fun `mimeTypeFor maps the common types`() {
        assertEquals("image/jpeg", SharedFiles.mimeTypeFor("photo.jpg"))
        assertEquals("image/jpeg", SharedFiles.mimeTypeFor("photo.JPEG"))
        assertEquals("image/png", SharedFiles.mimeTypeFor("shot.png"))
        assertEquals("application/pdf", SharedFiles.mimeTypeFor("doc.pdf"))
        assertEquals("text/plain", SharedFiles.mimeTypeFor("notes.txt"))
        assertEquals("video/mp4", SharedFiles.mimeTypeFor("clip.mp4"))
        assertEquals("*/*", SharedFiles.mimeTypeFor("noext"))
        assertEquals("*/*", SharedFiles.mimeTypeFor("file.unknownext"))
    }

    @Test
    fun `shortcut command guards on the missing file and never touches tmp`() {
        val cmd = SharedFiles.shortcutCommand()
        assertTrue(cmd.contains("[ -f /root/Desktop/mudif-shared.desktop ] ||"))
        assertTrue(cmd.contains("Exec=pcmanfm-qt /mnt/mudif-shared"))
        assertTrue(cmd.contains("Icon=folder"))
        assertFalse(cmd.contains("/tmp"))
    }
}
