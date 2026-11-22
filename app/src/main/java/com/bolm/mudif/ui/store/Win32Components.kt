package com.bolm.mudif.ui.store

/**
 * F6: per-program Windows components (runtimes a Windows program
 * needs). A "component" here is a set of (DLL, function)
 * implementations compiled INTO the AOT binary at build time
 * (win32.c g_imports); there is no runtime DLL file loading in the
 * win32 layer, so a component is never a staged file. The manifest
 * declares what a program needs, the asset build guarantees it, the
 * store card displays it, and the install gate refuses a program
 * whose components are unavailable. Components leave with the
 * binary on uninstall: nothing extra is staged, nothing extra is
 * removed.
 *
 * Available in v1: msvcrt (C library) and vcruntime140 (minimal
 * C++ runtime: import resolution + the frame-handler stub).
 * NOT available in v1, and never claimed: msvcp140 (the whole C++
 * standard library) and concrt140 (the concurrency runtime) are
 * whole libraries, not a handful of functions. DirectX is out of
 * v1 entirely: there is no D3D graphics backend in win32.c.
 */
data class Win32Component(
    /** Catalog id, e.g. "msvcrt". */
    val id: String,
    /** The DLL name the program imports, e.g. "msvcrt.dll". */
    val dll: String,
    /** Fusha label shown on the store card and in messages. */
    val label: String,
    /** True when the v1 build provides this component. */
    val available: Boolean,
    /** Why it is unavailable, shown nowhere yet; kept so the reason
     *  is recorded next to the flag instead of in a chat log. */
    val note: String? = null
)

object Win32Components {

    private val ALL: Map<String, Win32Component> = listOf(
        Win32Component(
            id = "msvcrt",
            dll = "msvcrt.dll",
            label = "مكتبة سي (msvcrt.dll)",
            available = true
        ),
        Win32Component(
            id = "vcruntime140",
            dll = "vcruntime140.dll",
            label = "وقت تشغيل سي++ (vcruntime140.dll)",
            available = true
        ),
        Win32Component(
            id = "msvcp140",
            dll = "msvcp140.dll",
            label = "مكتبة سي++ القياسية (msvcp140.dll)",
            available = false,
            note = "مكتبة كاملة، غير متوفرة في هذه النسخة"
        ),
        Win32Component(
            id = "concrt140",
            dll = "concrt140.dll",
            label = "وقت تشغيل التزامن (concrt140.dll)",
            available = false,
            note = "مكتبة كاملة، غير متوفرة في هذه النسخة"
        ),
        Win32Component(
            id = "directx",
            dll = "d3d11.dll",
            label = "دايركت إكس (d3d11.dll)",
            available = false,
            note = "يحتاج واجهة رسوميات غير موجودة بعد"
        )
    ).associateBy { it.id }

    /** The registered component for an id, or null when unknown. */
    fun forId(id: String): Win32Component? = ALL[id]

    /**
     * The components in [ids] that are NOT available. Null or empty
     * means the program declares no components, so nothing blocks
     * it. An id the registry does not know counts as missing: the
     * install refuses rather than silently installing a program
     * whose needs are unmet.
     */
    fun missingFor(ids: List<String>?): List<Win32Component> {
        if (ids.isNullOrEmpty()) return emptyList()
        val out = ArrayList<Win32Component>()
        for (id in ids) {
            val c = ALL[id]
            if (c == null) {
                out.add(
                    Win32Component(
                        id = id,
                        dll = id,
                        label = "مكون غير معروف ($id)",
                        available = false
                    )
                )
            } else if (!c.available) {
                out.add(c)
            }
        }
        return out
    }

    /** Fusha labels of the declared components, joined for the card.
     *  Empty when the program declares none. */
    fun labelsFor(ids: List<String>?): String {
        if (ids.isNullOrEmpty()) return ""
        return ids.joinToString("، ") { id ->
            ALL[id]?.label ?: "مكون غير معروف ($id)"
        }
    }
}
