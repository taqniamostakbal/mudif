package com.bolm.mudif.ui.store

data class Distro(
    val name: String,
    val desc: String,
    val size: String,
    val color: String
)

// Catalog is static for now; a real backend comes with P6 (stores).
val DISTROS = listOf(
    Distro("أوبونتو", "الأنسب للبداية — كل شيء يعمل مباشرة", "180MB", "#E95420"),
    Distro("دبيان", "مستقر وخفيف — خيار آمن وموثوق", "120MB", "#D70A53"),
    Distro("ألباين", "الأخف والأسرع — يثبت في ثوانٍ", "15MB", "#0D597F"),
    Distro("فيدورا", "حديث وسريع مع أحدث البرامج", "170MB", "#51A2DA"),
    Distro("آرتش", "دائماً الأحدث — لعشاق التجربة", "150MB", "#1793D1"),
    Distro("كالي", "أدوات الحماية واختبار الاختراق", "200MB", "#367BF0")
)
