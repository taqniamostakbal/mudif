package com.bolm.mudif.ui.store

import android.content.Context
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

/** The 7 catalog categories plus the "all" filter, with Fusha labels. */
enum class StoreCategory(val key: String, val label: String) {
    ALL("all", "الكل"),
    INTERNET("internet", "الإنترنت"),
    OFFICE("office", "المكتب"),
    MEDIA("media", "الوسائط"),
    DEV("dev", "التطوير"),
    AI("ai", "الذكاء الاصطناعي"),
    SYSTEM("system", "النظام"),
    FONTS("fonts", "الخطوط");

    companion object {
        fun labelFor(key: String): String =
            entries.firstOrNull { it.key == key }?.label ?: key
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
