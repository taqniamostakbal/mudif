package com.bolm.mudif.ui.store

import android.content.Context
import com.bolm.mudif.R
import com.bolm.mudif.ui.settings.AppLanguage
import org.json.JSONObject

/**
 * One program in the Mudif store catalog (P10).
 * Data comes from assets/store-catalog.json; the JSON is the single
 * source of truth, so adding a program never needs a code change.
 */
data class StoreProgram(
    val id: String,
    val name: String,
    val nameEn: String,
    /**
     * Defect 4: Darija name/description and English description,
     * from the catalog JSON (nameDarija / descDarija / descEn).
     * Null when the catalog has no value for the program; the
     * display helpers below fall back to the Fusha name/desc.
     */
    val nameDarija: String? = null,
    val desc: String,
    val descDarija: String? = null,
    val descEn: String? = null,
    val category: String,
    val method: String,
    val apt: String?,
    val binary: String?,
    val deb: String?,
    val debArm64: String?,
    val debPackage: String?,
    val script: String?,
    val font: String?,
    val icon: String,
    /**
     * Extra launch flags appended to the binary when the program
     * is run (e.g. Electron apps that crash without them).
     * Null = no extra flags. VSCodium needs
     * "--no-sandbox --disable-gpu --disable-gpu-sandbox
     * --no-zygote-sandbox --disable-dev-shm-usage":
     * it crashed on launch (code 5) without the first three in
     * proot, and died ~4-5 min later (SIGTRAP) without the
     * --disable-gpu-sandbox / --no-zygote-sandbox pair (Batch 3).
     */
    val flags: String?,
    /**
     * User to run the program as (via runuser). Null = run as root
     * (the guest default). VLC refuses to run as root ("VLC is not
     * supposed to be run as root"), so it runs as "nobody", rig
     * Batch 3 proved `runuser -u nobody` initializes its GUI.
     */
    val runAs: String?,
    /**
     * The process name to check when verifying the launch.
     * Defaults to [binary]. Needed when the binary is a wrapper
     * that execs another program (LibreOffice Writer's binary is
     * `lowriter` but its process is `soffice.bin`).
     */
    val process: String?,
    /**
     * Rig-verified install, e.g. "x86_64 · 2026-11-10". Null = never
     * verified on the rig. Only programs whose install actually
     * passed a rig test carry a value, never set it from a guess.
     * The value names the arch the rig tested (x86_64); arm64 is
     * added only after a phone verification. pdfcraft is
     * deliberately UNMARKED: it installs, but its window never
     * paints (P19v2), so a "tested" badge on it would be a lie.
     */
    val tested: String?
) {
    /** Name in the current app language. English uses the Latin
     *  nameEn; Darija uses nameDarija; both fall back to the
     *  Fusha name when the localized value is absent. */
    fun displayName(context: Context): String = when (AppLanguage.currentTag(context)) {
        AppLanguage.TAG_DARIJA -> nameDarija?.takeIf { it.isNotBlank() } ?: name
        AppLanguage.TAG_ENGLISH -> nameEn
        else -> name
    }

    /** Description in the current app language, falling back to
     *  the Fusha desc when the Darija value is absent. English
     *  stays on the Fusha desc for now: the descEn values in the
     *  catalog are a rule-based draft (930 of 1226 still carry
     *  Arabic tokens), so showing them would put word salad in
     *  the English store. They stay in the JSON for the human
     *  review pass, which is still owed. */
    fun displayDesc(context: Context): String = when (AppLanguage.currentTag(context)) {
        AppLanguage.TAG_DARIJA -> descDarija?.takeIf { it.isNotBlank() } ?: desc
        AppLanguage.TAG_ENGLISH -> desc
        else -> desc
    }
}

/** The 7 catalog categories plus the "all" filter, with Fusha labels.
 *  P19 Phase 1: each category also carries its gradient card background
 *  and its white icon (from the user's style refs). */
enum class StoreCategory(
    val key: String,
    val labelRes: Int,
    val gradientRes: Int,
    val iconRes: Int
) {
    ALL("all", R.string.ui_cat_all, R.drawable.cat_all, R.drawable.ic_cat_all),
    INTERNET("internet", R.string.ui_cat_internet, R.drawable.cat_internet, R.drawable.ic_cat_internet),
    OFFICE("office", R.string.ui_cat_office, R.drawable.cat_office, R.drawable.ic_cat_office),
    MEDIA("media", R.string.ui_cat_media, R.drawable.cat_media, R.drawable.ic_cat_media),
    DEV("dev", R.string.ui_cat_dev, R.drawable.cat_dev, R.drawable.ic_cat_dev),
    GAMES("games", R.string.ui_cat_games, R.drawable.cat_games, R.drawable.ic_cat_games),
    AI("ai", R.string.ui_cat_ai, R.drawable.cat_ai, R.drawable.ic_cat_ai),
    SYSTEM("system", R.string.ui_cat_system, R.drawable.cat_system, R.drawable.ic_cat_system),
    FONTS("fonts", R.string.ui_cat_fonts, R.drawable.cat_fonts, R.drawable.ic_cat_fonts);

    companion object {
        /** Localized label; the key itself is the fallback for unknown keys. */
        fun labelFor(context: Context, key: String): String =
            entries.firstOrNull { it.key == key }?.let { context.getString(it.labelRes) } ?: key

        fun forKey(key: String): StoreCategory? =
            entries.firstOrNull { it.key == key }
    }
}

/** Loads the catalog JSON from assets. Throws on a malformed file.
 *  p70: parsed once per process and cached. Every list open
 *  (store, category, apps, games, home stats, agent bridge) used
 *  to re-parse the 745 KB JSON from scratch before showing
 *  anything, which is a visible part of "any list takes very long
 *  to show the programs". The asset never changes at runtime, so
 *  the parsed list is immutable and safe to share; callers only
 *  read or filter it, never mutate it. */
object StoreCatalog {
    @Volatile
    private var cached: List<StoreProgram>? = null

    fun load(context: Context): List<StoreProgram> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val parsed = parse(context)
            cached = parsed
            return parsed
        }
    }

    private fun parse(context: Context): List<StoreProgram> {
        val text = context.assets.open("store-catalog.json")
            .bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("programs")
        val out = ArrayList<StoreProgram>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                StoreProgram(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    nameEn = o.getString("nameEn"),
                    nameDarija = o.optString("nameDarija", null)?.takeIf { it != "null" },
                    desc = o.getString("desc"),
                    descDarija = o.optString("descDarija", null)?.takeIf { it != "null" },
                    descEn = o.optString("descEn", null)?.takeIf { it != "null" },
                    category = o.getString("category"),
                    method = o.getString("method"),
                    apt = o.optString("apt", null)?.takeIf { it != "null" },
                    binary = o.optString("binary", null)?.takeIf { it != "null" },
                    deb = o.optString("deb", null)?.takeIf { it != "null" },
                    debArm64 = o.optString("debArm64", null)?.takeIf { it != "null" },
                    debPackage = o.optString("debPackage", null)?.takeIf { it != "null" },
                    script = o.optString("script", null)?.takeIf { it != "null" },
                    font = o.optString("font", null)?.takeIf { it != "null" },
                    icon = o.getString("icon"),
                    flags = o.optString("flags", null)?.takeIf { it != "null" },
                    runAs = o.optString("runAs", null)?.takeIf { it != "null" },
                    process = o.optString("process", null)?.takeIf { it != "null" },
                    // org.json returns the STRING "null" for a JSON
                    // null, so normalize it away (same trap as D3).
                    tested = o.optString("tested", null)?.takeIf { it != "null" }
                )
            )
        }
        return out
    }
}
