package com.bolm.mudif.ui.store

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.fragment.app.Fragment
import com.bolm.mudif.engine.DebianSystem
import com.bolm.mudif.ui.apps.AppLauncher
import java.io.File

/**
 * Take 3 (droiddesk-takes): ONE search over Android apps, Linux
 * .desktop files, and settings shortcuts. A fresh Mudif
 * reimplementation of the IDEA (one list, three sources). No
 * DroidDesk code was copied.
 *
 * Sources, in this order of trust:
 * - Android apps: the real launcher activities from the
 *   PackageManager (this app itself excluded).
 * - Linux programs: .desktop files read host-side from the
 *   rootfs (<rootfs>/usr/share/applications and
 *   <rootfs>/root/.local/share/applications). No guest call.
 *   NoDisplay=true entries are hidden, like a menu would hide
 *   them. The user's dir wins over the system dir on a name
 *   clash.
 * - Settings shortcuts: a fixed Arabic list. The Android ones
 *   open the real Android settings screens; the folder ones open
 *   the Linux file manager at that folder in the guest.
 *
 * Matching is a plain case-insensitive substring over label +
 * subtitle + keywords, in Arabic or Latin. An empty query
 * returns everything, capped at [DEFAULT_LIMIT].
 *
 * Launching a Linux result goes through [AppLauncher.launch],
 * the one launch path: a catalog match (by .desktop file name,
 * or by the Exec binary) launches the catalog program, anything
 * else launches from its Exec line as a synthetic catalog entry.
 * That keeps win32 programs on their launchDirect path too
 * (their .desktop file matches the catalog win32 entry by name).
 */
object UnifiedSearch {

    const val DEFAULT_LIMIT = 80

    enum class Kind { ANDROID, LINUX, SETTING }

    data class UnifiedResult(
        val kind: Kind,
        val label: String,
        val subtitle: String,
        /** Arabic source chip, e.g. "أندرويد" / "لينكس" / "الإعدادات". */
        val source: String,
        val id: String,
        /** Android package, or null. */
        val packageName: String? = null,
        /** Linux .desktop base name (no extension), or null. */
        val desktopId: String? = null,
        /** Raw Exec line of the .desktop file, or null. */
        val execLine: String? = null,
        /** Settings key (wifi, bluetooth, display, sound, hotspot), or null. */
        val settingKey: String? = null,
        /** Guest folder path for folder shortcuts, or null. */
        val folderPath: String? = null
    )

    fun search(context: Context, query: String, limit: Int = DEFAULT_LIMIT): List<UnifiedResult> {
        val needle = query.trim().lowercase()
        val out = ArrayList<UnifiedResult>()
        out += androidApps(context, needle)
        out += linuxDesktops(context, needle)
        out += settingShortcuts(needle)
        val distinct = out.distinctBy { "${it.kind}:${it.id}" }
        return distinct
            .sortedWith(compareBy<UnifiedResult> { it.label.lowercase() }.thenBy { it.source })
            .take(limit)
    }

    private fun matches(needle: String, vararg haystacks: String): Boolean {
        if (needle.isEmpty()) return true
        val hay = haystacks.joinToString(" ").lowercase()
        return hay.contains(needle)
    }

    private fun androidApps(context: Context, needle: String): List<UnifiedResult> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        } catch (_: Exception) {
            return emptyList()
        }
        return resolved.mapNotNull { info ->
            val pkg = info.activityInfo.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
            if (label.isEmpty()) return@mapNotNull null
            if (!matches(needle, label, pkg)) return@mapNotNull null
            UnifiedResult(
                kind = Kind.ANDROID,
                label = label,
                subtitle = pkg,
                source = "أندرويد",
                id = pkg,
                packageName = pkg
            )
        }
    }

    private fun linuxDesktops(context: Context, needle: String): List<UnifiedResult> {
        val rootfs = DebianSystem.debianRootfsDir(context)
        val dirs = listOf(
            File(rootfs, "root/.local/share/applications"),
            File(rootfs, "usr/share/applications")
        )
        val seen = HashSet<String>()
        val out = ArrayList<UnifiedResult>()
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val files = dir.listFiles { f -> f.isFile && f.extension == "desktop" }
                ?.sortedBy { it.name } ?: continue
            for (file in files) {
                if (!seen.add(file.name)) continue
                val fields = desktopFields(file)
                if (fields["NoDisplay"]?.equals("true", ignoreCase = true) == true) continue
                val name = fields["Name"]?.trim().orEmpty()
                if (name.isEmpty()) continue
                val comment = fields["Comment"].orEmpty()
                val keywords = fields["Keywords"].orEmpty()
                if (!matches(needle, name, comment, keywords)) continue
                out.add(
                    UnifiedResult(
                        kind = Kind.LINUX,
                        label = name,
                        subtitle = comment,
                        source = "لينكس",
                        id = file.nameWithoutExtension,
                        desktopId = file.nameWithoutExtension,
                        execLine = fields["Exec"]
                    )
                )
            }
        }
        return out
    }

    private fun desktopFields(file: File): Map<String, String> {
        return try {
            file.useLines { lines ->
                lines.takeWhile { !it.startsWith("[Desktop Action") }
                    .mapNotNull { line ->
                        val split = line.indexOf('=')
                        if (split <= 0) null
                        else line.substring(0, split).trim() to line.substring(split + 1).trim()
                    }.toMap()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Fixed settings shortcuts, in Fusha Arabic. Folder shortcuts
     * carry the guest path; the Android settings ones carry the
     * settings key that [actionIntent] resolves.
     */
    fun settingShortcuts(needle: String): List<UnifiedResult> {
        val all = listOf(
            UnifiedResult(Kind.SETTING, "المجلد الرئيسي", "ملفات لينكس الخاصة بك", "ملفات", "home", folderPath = "/root"),
            UnifiedResult(Kind.SETTING, "التنزيلات", "الملفات المنزلة", "ملفات", "Downloads", folderPath = "/root/Downloads"),
            UnifiedResult(Kind.SETTING, "المستندات", "مجلد المستندات", "ملفات", "Documents", folderPath = "/root/Documents"),
            UnifiedResult(Kind.SETTING, "الصور", "مجلد الصور", "ملفات", "Pictures", folderPath = "/root/Pictures"),
            UnifiedResult(Kind.SETTING, "إعدادات الواي فاي", "إدارة شبكات أندرويد", "الإعدادات", "wifi", settingKey = "wifi"),
            UnifiedResult(Kind.SETTING, "إعدادات البلوتوث", "إدارة أجهزة أندرويد", "الإعدادات", "bluetooth", settingKey = "bluetooth"),
            UnifiedResult(Kind.SETTING, "إعدادات العرض", "السطوع والشاشة", "الإعدادات", "display", settingKey = "display"),
            UnifiedResult(Kind.SETTING, "إعدادات الصوت", "مستوى الصوت", "الإعدادات", "sound", settingKey = "sound"),
            UnifiedResult(Kind.SETTING, "إعدادات نقطة الاتصال", "مشاركة الإنترنت", "الإعدادات", "hotspot", settingKey = "hotspot")
        )
        return all.filter { matches(needle, it.label, it.subtitle, it.source) }
    }

    /** Android settings intent for a settings shortcut, or null
     *  (folder shortcuts have no Android intent). */
    fun actionIntent(result: UnifiedResult): Intent? {
        val action = when (result.settingKey) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "hotspot" -> Settings.ACTION_WIRELESS_SETTINGS
            else -> return null
        }
        return Intent(action)
    }

    /**
     * Opens one unified result. Android apps and settings open
     * their Android intent; Linux results and folders launch in
     * the guest through [AppLauncher.launch], the one launch path.
     * Returns false only when there is nothing to open (no
     * launch intent and no usable Exec line).
     */
    fun launch(fragment: Fragment, result: UnifiedResult, catalog: List<StoreProgram>): Boolean {
        val ctx = fragment.requireContext()
        when (result.kind) {
            Kind.ANDROID -> {
                val intent = result.packageName?.let {
                    ctx.packageManager.getLaunchIntentForPackage(it)
                } ?: return false
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                return true
            }
            Kind.SETTING -> {
                val intent = actionIntent(result)
                if (intent != null) {
                    ctx.startActivity(intent)
                    return true
                }
                val folder = result.folderPath ?: return false
                AppLauncher.launch(fragment, folderProgram(folder))
                return true
            }
            Kind.LINUX -> {
                val program = resolveCatalog(result, catalog) ?: syntheticProgram(result)
                if (program == null) return false
                AppLauncher.launch(fragment, program)
                return true
            }
        }
    }

    /** Catalog match for a .desktop result: same file name as the
     *  program id first (this is how the win32 Notepad desktop
     *  file finds its catalog entry), then the Exec binary. */
    private fun resolveCatalog(result: UnifiedResult, catalog: List<StoreProgram>): StoreProgram? {
        val base = result.desktopId ?: return null
        catalog.firstOrNull { it.id == base }?.let { return it }
        val binary = execBinary(result.execLine) ?: return null
        val bare = binary.substringAfterLast('/')
        return catalog.firstOrNull { it.binary == bare || it.binary == binary }
    }

    /** First token of the Exec line, field codes (%f, %u, ...) removed. */
    fun execBinary(execLine: String?): String? {
        val line = execLine?.trim().orEmpty()
        if (line.isEmpty()) return null
        val cleaned = line.split(Regex("\\s+")).filter { !it.startsWith("%") }
        if (cleaned.isEmpty()) return null
        val first = cleaned.first()
        val token = if (first.startsWith("\"")) {
            first.trim('"')
        } else {
            first
        }
        return token.ifBlank { null }
    }

    /** A catalog-shaped entry for a .desktop file the catalog does
     *  not know. Only the fields [AppLauncher.launch] reads are
     *  meaningful here (binary, flags, runAs, win32, method);
     *  the rest are honest placeholders. */
    private fun syntheticProgram(result: UnifiedResult): StoreProgram? {
        val binary = execBinary(result.execLine) ?: return null
        return StoreProgram(
            id = "desktop:${result.desktopId ?: binary}",
            name = result.label,
            nameEn = result.label,
            desc = result.subtitle,
            category = "system",
            method = "apt",
            apt = null,
            binary = binary,
            deb = null,
            debArm64 = null,
            debPackage = null,
            script = null,
            font = null,
            win32 = null,
            icon = "",
            flags = null,
            runAs = null,
            process = binary.substringAfterLast('/'),
            tested = null
        )
    }

    /** The file manager opened at one guest folder. */
    private fun folderProgram(folder: String): StoreProgram {
        return StoreProgram(
            id = "desktop:folder",
            name = folder,
            nameEn = folder,
            desc = "",
            category = "system",
            method = "apt",
            apt = null,
            binary = "pcmanfm-qt",
            deb = null,
            debArm64 = null,
            debPackage = null,
            script = null,
            font = null,
            win32 = null,
            icon = "",
            flags = " $folder",
            runAs = null,
            process = "pcmanfm-qt",
            tested = null
        )
    }
}
