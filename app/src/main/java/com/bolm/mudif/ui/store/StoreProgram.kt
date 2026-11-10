package com.bolm.mudif.ui.store

import android.content.Context
import com.bolm.mudif.R
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
    val desc: String,
    val category: String,
    val method: String,
    val apt: String?,
    val binary: String?,
    val deb: String?,
    val debArm64: String?,
    val debPackage: String?,
    val script: String?,
    val font: String?,
    val icon: String
)

/** The 7 catalog categories plus the "all" filter, with Fusha labels.
 *  P19 Phase 1: each category also carries its gradient card background
 *  and its white icon (from the user's style refs). */
enum class StoreCategory(
    val key: String,
    val label: String,
    val gradientRes: Int,
    val iconRes: Int
) {
    ALL("all", "الكل", R.drawable.cat_all, R.drawable.ic_cat_all),
    INTERNET("internet", "الإنترنت", R.drawable.cat_internet, R.drawable.ic_cat_internet),
    OFFICE("office", "المكتب", R.drawable.cat_office, R.drawable.ic_cat_office),
    MEDIA("media", "الوسائط", R.drawable.cat_media, R.drawable.ic_cat_media),
    DEV("dev", "التطوير", R.drawable.cat_dev, R.drawable.ic_cat_dev),
    AI("ai", "الذكاء الاصطناعي", R.drawable.cat_ai, R.drawable.ic_cat_ai),
    SYSTEM("system", "النظام", R.drawable.cat_system, R.drawable.ic_cat_system),
    FONTS("fonts", "الخطوط", R.drawable.cat_fonts, R.drawable.ic_cat_fonts);

    companion object {
        fun labelFor(key: String): String =
            entries.firstOrNull { it.key == key }?.label ?: key

        fun forKey(key: String): StoreCategory? =
            entries.firstOrNull { it.key == key }
    }
}

/** Loads the catalog JSON from assets. Throws on a malformed file. */
object StoreCatalog {
    fun load(context: Context): List<StoreProgram> {
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
                    desc = o.getString("desc"),
                    category = o.getString("category"),
                    method = o.getString("method"),
                    apt = o.optString("apt", null),
                    binary = o.optString("binary", null),
                    deb = o.optString("deb", null),
                    debArm64 = o.optString("debArm64", null),
                    debPackage = o.optString("debPackage", null),
                    script = o.optString("script", null),
                    font = o.optString("font", null),
                    icon = o.getString("icon")
                )
            )
        }
        return out
    }
}
