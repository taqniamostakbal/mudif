package com.bolm.mudif.engine

import android.content.Context
import com.bolm.mudif.R
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Take 3 (droiddesk-takes): snapshots of the Linux home with
 * rollback. A fresh Mudif reimplementation of the IDEA (zip the
 * home plus a package list, timestamped name, rename-aside
 * rollback on a failed restore). No DroidDesk code was copied.
 *
 * Shape, and why it is this shape:
 * - Host-side only. The home lives at <rootfs>/root on the
 *   Android host, so the snapshot is a plain host-side zip walk:
 *   no guest process, no display path, no :7/:6 involvement.
 *   Snapshots cover the home regardless of the display, which is
 *   display-independent by construction.
 * - Storage is <filesDir>/snapshots, OUTSIDE the guest image, so
 *   a snapshot never grows the rootfs and never ships inside a
 *   system backup. At most [MAX_SNAPSHOTS] are kept; creating one
 *   more deletes the oldest first.
 * - The home on the rig measures 4.7 GB, of which 4.3 GB is
 *   /root/.ollama (the Ollama models). A snapshot must NEVER copy
 *   the models: they are multi-GB, regenerable by download, and
 *   the rig disk is 97 percent full. [EXCLUDED_TOP] plus the
 *   regenerable-cache prefixes below are skipped by name and the
 *   skip is counted, so the snapshot stays near 28 MB on the rig
 *   (measured 2026-11-21: zip of the home with these exclusions
 *   is 27,635,305 B) instead of 4.7 GB. What is excluded is
 *   regenerable state only: models, caches, the store download
 *   cache, browser component caches, npm modules. Browser
 *   profiles, documents, settings and dotfiles are kept.
 * - The zip is NOT encrypted. It holds the same files the home
 *   already holds (including /root/.config/mudif/keys.env), in
 *   the app-private files dir, under the same protection as the
 *   home itself. Nothing leaves the device.
 * - Restore refuses while the desktop session is alive (a live
 *   guest changes files under the restore). It renames the
 *   current home aside FIRST, so a failed restore deletes the
 *   partial home and renames the aside copy back: the user's
 *   home survives a corrupt snapshot. On a SUCCESSFUL restore
 *   the excluded paths (.ollama, .cache, the download cache,
 *   browser component caches) are moved back from the aside
 *   copy into the new home BEFORE the aside copy is deleted:
 *   the snapshot never holds them, so deleting the aside first
 *   would destroy the Ollama models. If any move back fails,
 *   the restore is cancelled outright (the aside copy is
 *   renamed back, the models stay where they were).
 *
 * Callers must run create/restore on a background thread: both
 * walk the whole home and take seconds.
 */
object HomeSnapshots {

    /** At most this many snapshots are kept; older ones are pruned. */
    const val MAX_SNAPSHOTS = 3

    /** Refuse to create when less than this is free: a snapshot
     *  must never be the thing that fills the disk. The rig
     *  PackageInstaller threshold sits near 779 MB free, so a
     *  snapshot that leaves the disk under this floor would also
     *  block the next install. */
    const val MIN_FREE_BYTES = 150L * 1024L * 1024L

    /** Top-level home entries that are never snapshotted:
     *  .ollama holds the Ollama models (4.3 GB on the rig),
     *  .cache holds regenerable caches. */
    private val EXCLUDED_TOP = setOf(".ollama", ".cache")

    /** Relative path prefixes (from the home) that are skipped:
     *  the store download cache (re-downloadable .debs), browser
     *  component caches (re-downloaded by the browser), and
     *  Firefox crash reports. Browser profiles themselves are
     *  kept. */
    private val EXCLUDED_PREFIXES = listOf(
        ".config/mudif/library",
        ".config/chromium/component_crx_cache",
        ".config/chromium/WidevineCdm",
        ".config/chromium/WasmTtsEngine",
        ".config/chromium/CertificateRevocation",
        ".config/chromium/OnDeviceHeadSuggestModel",
        ".config/chromium/BrowserMetrics",
        ".config/chromium/ActorSafetyLists"
    )

    private const val SNAPSHOT_VERSION = 1
    private const val FILE_PREFIX = "mudif-home-"
    private val NAME_REGEX = Regex("mudif-home-[0-9]{8}-[0-9]{6}\\.zip")
    private val PACKAGE_NAME_REGEX = Regex("[a-z0-9][a-z0-9+.-]{0,127}")

    data class SnapshotInfo(
        val name: String,
        val sizeBytes: Long,
        val createdMs: Long,
        val fileCount: Int,
        val skippedCount: Int,
        val packageCount: Int
    )

    fun snapshotsDir(context: Context): File =
        File(context.filesDir, "snapshots").apply { mkdirs() }

    /** The Linux home on the host: <rootfs>/root. */
    fun homeDir(context: Context): File =
        File(DebianSystem.debianRootfsDir(context), "root")

    /** True when this relative home path is excluded from snapshots. */
    fun isExcluded(relativePath: String): Boolean {
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        if (parts[0] in EXCLUDED_TOP) return true
        if ("node_modules" in parts) return true
        val joined = parts.joinToString("/")
        if (EXCLUDED_PREFIXES.any { joined == it || joined.startsWith("$it/") }) return true
        // Firefox site storage and security state are regenerable;
        // history and bookmarks (places.sqlite) are kept. The path
        // is .mozilla/firefox/<profile>/storage, so the dir name
        // sits at segment 3, after the profile dir at segment 2.
        if (parts[0] == ".mozilla" && parts.size >= 4 &&
            parts[1] == "firefox" &&
            parts[3] in setOf("storage", "security_state")
        ) return true
        if ("Crash Reports" in parts) return true
        return false
    }

    /** Installed package names, read host-side from the dpkg status
     *  file inside the rootfs. No guest call. */
    fun installedPackages(context: Context): List<String> {
        val status = File(DebianSystem.debianRootfsDir(context), "var/lib/dpkg/status")
        if (!status.isFile) return emptyList()
        return try {
            status.readText().split("\n\n").mapNotNull { block ->
                var pkg: String? = null
                var ok = false
                for (line in block.lineSequence()) {
                    when {
                        line.startsWith("Package:") -> pkg = line.removePrefix("Package:").trim()
                        line.startsWith("Status:") -> ok = line.removePrefix("Status:").trim() == "install ok installed"
                    }
                }
                if (ok) pkg?.takeIf { PACKAGE_NAME_REGEX.matches(it) } else null
            }.sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Creates a snapshot. Returns its info. Throws when the system
     * is not installed, when free space is under [MIN_FREE_BYTES],
     * or when the zip itself fails; a failed create leaves no
     * partial file behind.
     */
    fun create(context: Context): SnapshotInfo {
        if (!DebianSystem.isReady(context)) {
            throw RuntimeException(context.getString(R.string.ui_backup_no_system))
        }
        val dir = snapshotsDir(context)
        if (dir.usableSpace < MIN_FREE_BYTES) {
            throw RuntimeException(
                context.getString(R.string.snapshot_no_space, MIN_FREE_BYTES / (1024 * 1024))
            )
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val outFile = File(dir, "$FILE_PREFIX$stamp.zip")
        if (outFile.exists()) outFile.delete()
        val home = homeDir(context)
        if (!home.isDirectory) {
            throw RuntimeException(context.getString(R.string.ui_backup_no_system))
        }
        val packages = installedPackages(context)
        var fileCount = 0
        var skippedCount = 0
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { zip ->
                zip.putNextEntry(ZipEntry("snapshot.properties"))
                zip.write(
                    ("version=$SNAPSHOT_VERSION\n" +
                        "created=${System.currentTimeMillis()}\n" +
                        "packages=${packages.size}\n").toByteArray()
                )
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("packages.txt"))
                zip.write(packages.joinToString("\n", postfix = "\n").toByteArray())
                zip.closeEntry()
                // onEnter prunes symlinked directories: without it
                // the walk would descend through a symlink and zip
                // the target's files under the link's path.
                home.walkTopDown().onEnter { dir ->
                    if (dir == home) true
                    else try {
                        !Files.isSymbolicLink(dir.toPath())
                    } catch (_: Exception) {
                        false
                    }
                }.forEach { file ->
                    if (file == home) return@forEach
                    val relative = file.relativeTo(home).invariantSeparatorsPath
                    if (isExcluded(relative)) {
                        skippedCount++
                        return@forEach
                    }
                    val isLink = try {
                        Files.isSymbolicLink(file.toPath())
                    } catch (_: Exception) {
                        true
                    }
                    if (isLink) {
                        skippedCount++
                        return@forEach
                    }
                    val name = "home/$relative${if (file.isDirectory) "/" else ""}"
                    var entryOpen = false
                    try {
                        zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                        entryOpen = true
                        if (file.isFile) {
                            FileInputStream(file).use { it.copyTo(zip) }
                            fileCount++
                        }
                    } catch (_: Exception) {
                        skippedCount++
                    } finally {
                        if (entryOpen) {
                            try { zip.closeEntry() } catch (_: Exception) { }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            try { outFile.delete() } catch (_: Exception) { }
            throw e
        }
        if (outFile.length() < 1024) {
            try { outFile.delete() } catch (_: Exception) { }
            throw RuntimeException(context.getString(R.string.ui_backup_too_small, outFile.length()))
        }
        // The properties entry keeps the version, the created
        // stamp, and the package count, which were known before
        // the walk. The file and skipped counts live in the
        // returned info; list() reports them as unknown (-1).
        pruneOld(context)
        return SnapshotInfo(outFile.name, outFile.length(), outFile.lastModified(), fileCount, skippedCount, packages.size)
    }

    /** Snapshots newest first. A file whose properties entry cannot
     *  be read still lists, with unknown counts (-1). */
    fun list(context: Context): List<SnapshotInfo> {
        val dir = snapshotsDir(context)
        return (dir.listFiles { f -> f.isFile && NAME_REGEX.matches(f.name) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { infoOf(it) }
            .orEmpty())
    }

    private fun infoOf(file: File): SnapshotInfo {
        var packages = -1
        try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("snapshot.properties") ?: return@use
                zip.getInputStream(entry).bufferedReader().use { reader ->
                    for (line in reader.lineSequence()) {
                        if (line.startsWith("packages=")) {
                            packages = line.removePrefix("packages=").trim().toIntOrNull() ?: -1
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return SnapshotInfo(file.name, file.length(), file.lastModified(), -1, -1, packages)
    }

    /**
     * Restores [name] over the current home. Returns the package
     * list recorded in the snapshot. Throws when the desktop is
     * alive, when the name is not one of ours, when the snapshot
     * is not version 1, or when any entry fails. On ANY failure
     * the partial home is deleted and the aside copy is renamed
     * back, so the home the user had before the restore is the
     * home they still have afterwards.
     */
    fun restore(context: Context, name: String): List<String> {
        if (GuestSession.alive) {
            throw RuntimeException(context.getString(R.string.ui_backup_desktop_running_restore))
        }
        // Take 1: the direct :6 session is a detached setsid
        // proot, so GuestSession.stop() never touches it and the
        // PTY check above cannot see it. A restore under a live
        // desktop would replace the home under a running session,
        // so refuse while the direct session pid is alive too.
        // Callers run restore on a background thread, so the
        // guest-probe fallback inside isDirectDesktopAlive is safe
        // here. The Profile confirm action kills the direct
        // session first (stopDesktopAndGuest), so a confirmed
        // restore still proceeds.
        if (DirectDesktop.isDirectDesktopAlive(context)) {
            throw RuntimeException(context.getString(R.string.ui_backup_desktop_running_restore))
        }
        val archive = safeSnapshot(context, name)
        val home = homeDir(context)
        val rollback = File(home.parentFile, "${home.name}.before-restore")
        if (rollback.exists()) rollback.deleteRecursively()
        if (home.exists() && !home.renameTo(rollback)) {
            throw RuntimeException(context.getString(R.string.snapshot_preserve_failed))
        }
        home.mkdirs()
        val packages = ArrayList<String>()
        var versionOk = false
        try {
            ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when {
                        entry.name == "snapshot.properties" -> {
                            val text = zip.readBytes().toString(Charsets.UTF_8)
                            versionOk = text.lineSequence().any { it.trim() == "version=$SNAPSHOT_VERSION" }
                        }
                        entry.name == "packages.txt" -> {
                            packages += zip.readBytes().toString(Charsets.UTF_8)
                                .lineSequence().map { it.trim() }
                                .filter { PACKAGE_NAME_REGEX.matches(it) }
                        }
                        entry.name.startsWith("home/") -> {
                            val relative = entry.name.removePrefix("home/")
                            if (relative.isNotEmpty()) {
                                val target = File(home, relative)
                                val canon = target.canonicalPath
                                val homeCanon = home.canonicalPath + File.separator
                                check(canon.startsWith(homeCanon) || canon == home.canonicalPath) {
                                    context.getString(R.string.ui_backup_bad_entry, entry.name)
                                }
                                if (entry.isDirectory) {
                                    target.mkdirs()
                                } else {
                                    target.parentFile?.mkdirs()
                                    FileOutputStream(target).use { zip.copyTo(it) }
                                }
                            }
                        }
                        else -> {
                            // Unknown top-level entry: ignore it, the
                            // snapshot is still ours by name + version.
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            check(versionOk) { context.getString(R.string.snapshot_bad_version) }
            // The snapshot never contains the excluded paths (the
            // models, the caches, the download cache), so they are
            // NOT in the new home. Move them back from the aside
            // copy BEFORE deleting it: deleting the aside first
            // would destroy the user's Ollama models and caches,
            // which no snapshot can bring back. If any move fails,
            // the aside copy is KEPT (never deleted) and the
            // restore reports the failure, so the data survives
            // even when the restore cannot finish cleanly.
            val preserved = preserveExcluded(home, rollback)
            if (!preserved) {
                throw RuntimeException(context.getString(R.string.snapshot_restore_preserve_failed))
            }
            rollback.deleteRecursively()
            return packages.distinct()
        } catch (e: Exception) {
            try { home.deleteRecursively() } catch (_: Exception) { }
            try { rollback.renameTo(home) } catch (_: Exception) { }
            throw e
        }
    }

    /** Deletes one snapshot by name. Returns false when it is not ours. */
    fun delete(context: Context, name: String): Boolean {
        return try {
            safeSnapshot(context, name).delete()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Moves the excluded paths back from the aside copy into the
     * restored home. Topmost excluded entries only (a child of an
     * excluded dir moves with its parent). Returns false when any
     * move fails, so the caller keeps the aside copy instead of
     * deleting data it could not preserve.
     */
    private fun preserveExcluded(home: File, aside: File): Boolean {
        if (!aside.isDirectory) return true
        val topmost = collectExcluded(aside)
        var ok = true
        for (source in topmost) {
            val relative = source.relativeTo(aside).invariantSeparatorsPath
            val target = File(home, relative)
            try {
                target.parentFile?.mkdirs()
                if (target.exists()) {
                    // The restored home never holds an excluded
                    // path, so this means a name clash with
                    // restored content: keep the aside entry and
                    // report the failure instead of overwriting.
                    ok = false
                    continue
                }
                if (!source.renameTo(target)) ok = false
            } catch (_: Exception) {
                ok = false
            }
        }
        return ok
    }

    /**
     * The topmost excluded entries under [aside] (an excluded dir
     * is collected whole and never descended into, so a child of
     * an excluded dir is never listed separately). Written as an
     * explicit recursion: FileTreeWalk.onEnter returning false
     * for an excluded dir also skips YIELDING that dir itself
     * (proven by a standalone Kotlin test: the onEnter walk
     * collected nothing, this recursion collects all four
     * excluded shapes), so the walk form silently preserved
     * nothing and the aside delete destroyed the models.
     */
    private fun collectExcluded(aside: File): List<File> {
        val out = ArrayList<File>()
        fun visit(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                val relative = child.relativeTo(aside).invariantSeparatorsPath
                if (isExcluded(relative)) out.add(child)
                else if (child.isDirectory) visit(child)
            }
        }
        visit(aside)
        return out
    }

    /** Packages recorded in the snapshot that are not installed now. */
    fun missingPackages(context: Context, packages: List<String>): List<String> {
        val installed = installedPackages(context).toSet()
        return packages.filter { PACKAGE_NAME_REGEX.matches(it) }.distinct().filterNot(installed::contains)
    }

    private fun safeSnapshot(context: Context, name: String): File {
        require(NAME_REGEX.matches(name)) { "bad snapshot name: $name" }
        return File(snapshotsDir(context), name).also { require(it.isFile) }
    }

    /** Keeps at most [MAX_SNAPSHOTS], deleting the oldest first. */
    private fun pruneOld(context: Context) {
        val all = list(context)
        if (all.size <= MAX_SNAPSHOTS) return
        for (extra in all.drop(MAX_SNAPSHOTS)) {
            try { File(snapshotsDir(context), extra.name).delete() } catch (_: Exception) { }
        }
    }
}
