package com.bolm.mudif.ui.store

import com.bolm.mudif.R

data class Distro(
    val name: String,
    val descRes: Int,
    val size: String,
    val color: String
)

// Catalog is static for now; a real backend comes with P6 (stores).
// P6.4, نظام مضيف is the ONLY system in this app (the user's
// order 2026-11-07). The store lists نظام مضيف only.
// Phase 1b: the card is branded as the user's own system (name +
// penguin logo), never "Debian".
val DISTROS = listOf(
    Distro("نظام مضيف", R.string.ui_distro_mudif_desc, "35MB", "#593BFE")
)
