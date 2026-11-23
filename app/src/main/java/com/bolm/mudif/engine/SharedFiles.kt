package com.bolm.mudif.engine

import java.io.File
import java.util.Locale

/**
 * F3, file transfer phone <> desktop. The bridge already exists
 * (RootfsManager.sharedDir, Android side, bound to guest
 * /mnt/mudif-shared at boot), so this file holds only the pure
 * pieces the activity needs: a clash-free name (never overwrite,
 * never delete), a human size, a MIME type from the extension,
 * and the one idempotent guest command that puts a desktop
 * shortcut to the shared folder (only when it is missing).
 * The copy itself streams SAF -> sharedDir in chunks; it never
 * touches /tmp (VM-wipe rule) and never deletes anything.
 */
object SharedFiles {

    /** File name of the desktop shortcut inside the guest. */
    const val SHORTCUT_FILE = "mudif-shared.desktop"

    /**
     * Pure: a name that does not clash with an existing file in
     * [dir]. "photo.jpg" -> "photo (2).jpg" -> "photo (3).jpg".
     * The original file is never overwritten or deleted.
     */
    fun uniqueName(dir: File, name: String): String {
        val clean = name.ifBlank { "file" }
        if (!File(dir, clean).exists()) return clean
        val dot = clean.lastIndexOf('.')
        val base = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "$base ($n)$ext"
            if (!File(dir, candidate).exists()) return candidate
            n++
        }
    }

    /** Pure: bytes as "12.5 MB" / "800 KB" / "42 B", Latin digits. */
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.1f GB", gb)
    }

    /**
     * Pure: MIME type from the file extension, or the wildcard
     * type when unknown. A small built-in map (not the framework
     * MimeTypeMap, which varies by device and is untestable on
     * the JVM): the types a user realistically opens from the
     * shared folder. Anything else opens with the wildcard type
     * and the app chooser.
     */
    fun mimeTypeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/x-wav"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
    }

    /**
     * Pure: the one guest command that ensures the desktop
     * shortcut exists. It writes /root/Desktop/mudif-shared.desktop
     * ONLY when the file is missing ([ -f ] guard), then marks it
     * executable like the other desktop entries. Runs through
     * GuestSession.sendCommand on a background thread, only when
     * the session is alive; a dead session means "skip", never a
     * failure the user sees.
     */
    fun shortcutCommand(): String {
        val body = "[Desktop Entry]\\n" +
            "Type=Application\\n" +
            "Name=الملفات المشتركة\\n" +
            "Comment=الملفات المرسلة من الهاتف\\n" +
            "Exec=pcmanfm-qt /mnt/mudif-shared\\n" +
            "Icon=folder\\n" +
            "Terminal=false\\n"
        return "[ -f /root/Desktop/$SHORTCUT_FILE ] || " +
            "printf '$body' > /root/Desktop/$SHORTCUT_FILE && " +
            "chmod +x /root/Desktop/$SHORTCUT_FILE"
    }
}
