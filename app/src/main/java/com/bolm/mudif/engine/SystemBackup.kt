package com.bolm.mudif.engine

import android.content.Context
import com.bolm.mudif.R
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real-computer #2, backup / restore of the whole Debian system.
 *
 * Backup: a host-side walk of the debian-rootfs, written as
 * .tar.xz (commons-compress + XZ level 1: fast on a phone, bigger
 * file, a full backup is ~2.3 GB extracted, ~500-700 MB packed).
 * The bind-mount contents are excluded (tmp, proc, sys, dev, run
 * hold runtime state, not the system); symlinks stay symlinks and
 * file modes are preserved. Backups land in
 * <external-files>/mudif-backups (browsable from the Files app).
 *
 * Restore: the file is verified to be a Mudif system backup (it
 * must contain etc/debian_version or root/.mudif_image_version),
 * then the current rootfs is wiped and the backup extracted with
 * the same path-safety checks as DebianSystem.extract. The
 * .mudif_ready marker is written fresh at the end.
 *
 * CALLER'S JOB: stop the desktop first (GuestSession.stop()).
 * A live guest changes files mid-tar, and restoring over a running
 * container corrupts it. The engine refuses when the session is
 * alive, so a forgotten stop can never silently corrupt a backup.
 * A restored backup whose image version differs from this build's
 * IMAGE_VERSION is correctly treated as "not installed" by
 * DebianSystem.isReady(), the version check protects the user
 * from booting an outdated system by accident.
 */
object SystemBackup {

    private const val READY_MARKER = ".mudif_ready"

    fun backupDir(context: Context): File =
        (context.getExternalFilesDir("mudif-backups")
            ?: File(context.filesDir, "mudif-backups")).apply { mkdirs() }

    /** Top-level rootfs entries that are runtime state, not the system. */
    private val EXCLUDED_TOP = setOf("tmp", "proc", "sys", "dev", "run")

    /**
     * Creates a backup. Returns the backup file. [onProgress] gets
     * (bytes written, total bytes of regular files). Throws on any
     * failure; a failed backup leaves no partial file behind.
     */
    fun backup(
        context: Context,
        onProgress: (done: Long, total: Long) -> Unit
    ): File {
        if (GuestSession.alive) {
            throw RuntimeException(context.getString(R.string.ui_backup_desktop_running_err))
        }
        val rootfs = DebianSystem.debianRootfsDir(context)
        if (!DebianSystem.isReady(context)) {
            throw RuntimeException(context.getString(R.string.ui_backup_no_system))
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val outFile = File(backupDir(context), "mudif-backup-$stamp.tar.xz")
        if (outFile.exists()) outFile.delete()

        // Pass 1: count regular files + total bytes (length reads only).
        // Only TOP-LEVEL tmp/proc/sys/dev/run are pruned (bind mounts
        // and runtime state); a nested dir that happens to share one
        // of those names is a real system dir and must be kept.
        var totalBytes = 0L
        val entries = ArrayList<File>()
        rootfs.walkTopDown()
            .onEnter { dir ->
                if (dir == rootfs) true
                else !(dir.parentFile == rootfs && dir.name in EXCLUDED_TOP)
            }
            .forEach { f ->
                if (f == rootfs) return@forEach
                entries.add(f)
                if (f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
                    totalBytes += f.length()
                }
            }

        var doneBytes = 0L
        try {
            XZCompressorOutputStream(
                BufferedOutputStream(outFile.outputStream()), 1
            ).use { xz ->
                TarArchiveOutputStream(xz).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    for (f in entries) {
                        val rel = f.relativeTo(rootfs).path.replace(File.separatorChar, '/')
                        val isLink = try {
                            java.nio.file.Files.isSymbolicLink(f.toPath())
                        } catch (_: Exception) { false }
                        val entry = when {
                            isLink -> {
                                val target = try {
                                    java.nio.file.Files.readSymbolicLink(f.toPath()).toString()
                                } catch (_: Exception) { "" }
                                TarArchiveEntry(rel, TarArchiveEntry.LF_SYMLINK).apply {
                                    linkName = target
                                }
                            }
                            f.isDirectory -> TarArchiveEntry(rel + "/")
                            f.isFile -> TarArchiveEntry(f, rel)
                            else -> continue
                        }
                        try {
                            val mode = android.system.Os.stat(f.absolutePath).st_mode
                            entry.mode = mode and 4095
                        } catch (_: Exception) { /* keep the default mode */ }
                        tar.putArchiveEntry(entry)
                        if (f.isFile && !isLink) {
                            f.inputStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    tar.write(buf, 0, n)
                                    doneBytes += n
                                    onProgress(doneBytes, totalBytes)
                                }
                            }
                        }
                        tar.closeArchiveEntry()
                    }
                    tar.finish()
                }
            }
        } catch (e: Exception) {
            try { outFile.delete() } catch (_: Exception) { }
            throw e
        }
        if (outFile.length() < 1024 * 1024) {
            try { outFile.delete() } catch (_: Exception) { }
            throw RuntimeException(context.getString(R.string.ui_backup_too_small, outFile.length()))
        }
        return outFile
    }

    /**
     * Restores [backupFile] over the current system. Returns true on
     * success. The file must be a Mudif system backup (verified by
     * content, not by name). Throws on any failure; a failed restore
     * leaves no half-system behind.
     */
    fun restore(
        context: Context,
        backupFile: File,
        onProgress: (done: Long, total: Long) -> Unit
    ): Boolean {
        if (GuestSession.alive) {
            throw RuntimeException(context.getString(R.string.ui_backup_desktop_running_restore))
        }
        if (!backupFile.isFile || backupFile.length() < 1024 * 1024) {
            throw RuntimeException(context.getString(R.string.ui_backup_invalid_file))
        }
        // Verify: it must be a Mudif system backup, not any tar.xz.
        var looksLikeBackup = false
        var scanned = 0
        TarArchiveInputStream(
            XZCompressorInputStream(BufferedInputStream(backupFile.inputStream()))
        ).use { tar ->
            var entry = tar.nextEntry
            while (entry != null && scanned < 5000) {
                scanned++
                if (entry.name == "etc/debian_version" ||
                    entry.name == "root/.mudif_image_version"
                ) {
                    looksLikeBackup = true
                    break
                }
                entry = tar.nextEntry
            }
        }
        if (!looksLikeBackup) {
            throw RuntimeException(context.getString(R.string.ui_backup_not_mudif))
        }

        val dest = DebianSystem.debianRootfsDir(context)
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()
        val destPath = dest.canonicalPath + File.separator
        var count = 0
        var doneBytes = 0L
        val totalBytes = backupFile.length()
        try {
            TarArchiveInputStream(
                XZCompressorInputStream(BufferedInputStream(backupFile.inputStream()))
            ).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val f = File(dest, entry.name)
                    val canon = f.canonicalPath
                    if (canon != dest.canonicalPath && !canon.startsWith(destPath)) {
                        throw RuntimeException(context.getString(R.string.ui_backup_bad_entry, entry.name))
                    }
                    when {
                        entry.isDirectory -> f.mkdirs()
                        entry.isSymbolicLink -> {
                            f.parentFile?.mkdirs()
                            try { android.system.Os.remove(f.absolutePath) } catch (_: Exception) { }
                            try {
                                android.system.Os.symlink(entry.linkName, f.absolutePath)
                            } catch (e: Exception) {
                                throw RuntimeException(context.getString(R.string.ui_dl_link_failed, entry.name, e.message))
                            }
                        }
                        entry.isLink -> {
                            val target = File(dest, entry.linkName)
                            f.parentFile?.mkdirs()
                            try { android.system.Os.remove(f.absolutePath) } catch (_: Exception) { }
                            try {
                                android.system.Os.link(target.absolutePath, f.absolutePath)
                            } catch (_: Exception) {
                                // Same fallback as DebianSystem.extract:
                                // Huawei/EMUI refuses link(), copy.
                                if (!target.isFile) {
                                    throw RuntimeException(context.getString(R.string.ui_backup_hardlink_missing, entry.name))
                                }
                                target.inputStream().use { input ->
                                    f.outputStream().use { out -> input.copyTo(out) }
                                }
                                try {
                                    android.system.Os.chmod(f.absolutePath, entry.mode and 4095)
                                } catch (_: Exception) { }
                            }
                        }
                        entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> {
                            // No device nodes in a backup; skip.
                        }
                        else -> {
                            f.parentFile?.mkdirs()
                            tar.copyTo(f.outputStream())
                            try {
                                android.system.Os.chmod(f.absolutePath, entry.mode and 4095)
                            } catch (_: Exception) { }
                            doneBytes += entry.size
                            onProgress(doneBytes, totalBytes)
                        }
                    }
                    count++
                    entry = tar.nextEntry
                }
            }
        } catch (e: Exception) {
            try { dest.deleteRecursively() } catch (_: Exception) { }
            throw e
        }
        if (count < 1000) {
            try { dest.deleteRecursively() } catch (_: Exception) { }
            throw RuntimeException(context.getString(R.string.ui_backup_archive_short, count))
        }
        File(dest, READY_MARKER).writeText(DebianSystem.DEBIAN_VERSION)
        return true
    }
}
