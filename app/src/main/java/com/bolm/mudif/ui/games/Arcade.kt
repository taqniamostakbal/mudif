package com.bolm.mudif.ui.games

import com.bolm.mudif.R

/**
 * P19 Phase 3: Mudif Arcade — the curated, verified, one-click games
 * and emulators the user ordered (ARM64 only; x86-only emulators such
 * as PCSX2 are excluded). These are NOT installed yet: the entries are
 * the honest catalogue, and tapping one says the real install is coming
 * soon (the installs themselves land with the P18.4 store expansion).
 * No ROMs are distributed here — emulators need the user's own ROMs.
 */
data class ArcadeEntry(
    val name: String,
    val desc: String,
    val gradientRes: Int
)

val ARCADE_ENTRIES = listOf(
    ArcadeEntry("RetroArch", "محاكي متعدد الأنظمة", R.drawable.cat_dev),
    ArcadeEntry("PPSSPP", "محاكي PSP", R.drawable.cat_media),
    ArcadeEntry("mGBA", "محاكي Game Boy Advance", R.drawable.cat_internet),
    ArcadeEntry("Dolphin", "محاكي GameCube و Wii", R.drawable.cat_ai),
    ArcadeEntry("SuperTuxKart", "سباق كارت ممتع", R.drawable.cat_fonts),
    ArcadeEntry("0 A.D.", "استراتيجية تاريخية", R.drawable.cat_system),
    ArcadeEntry("Battle for Wesnoth", "استراتيجية خيالية", R.drawable.cat_office),
    ArcadeEntry("OpenTTD", "محاكاة النقل والسكك", R.drawable.cat_all),
    ArcadeEntry("Minetest", "عالم مكعبات مفتوح", R.drawable.cat_dev),
    ArcadeEntry("SuperTux", "لعبة منصات كلاسيكية", R.drawable.cat_media),
    ArcadeEntry("Warzone 2100", "استراتيجية وقت حقيقي", R.drawable.cat_internet)
)
