package com.bolm.mudif.ui.store

data class Distro(
    val name: String,
    val desc: String,
    val size: String,
    val color: String
)

// Catalog is static for now; a real backend comes with P6 (stores).
// P6.4 — نظام مضيف is the ONLY system in this app (the user's
// order 2026-11-07). The store lists نظام مضيف only.
// Phase 1b: the card is branded as the user's own system (name +
// penguin logo), never "Debian".
val DISTROS = listOf(
    Distro("نظام مضيف", "نظامك الكامل — جاهز بضغطة واحدة", "35MB", "#593BFE")
)
