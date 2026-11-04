package com.bolm.mudif.ui.apps

data class AppInfo(
    val name: String,
    val desc: String,
    val category: String,
    val color: String
)

// Catalog is static for now; a real backend comes with P6 (stores).
val APPS = listOf(
    AppInfo("فايرفوكس", "متصفح سريع يحترم خصوصيتك", "إنترنت", "#FF7139"),
    AppInfo("في إل سي", "يشغل أي فيديو وأي صوت", "وسائط", "#FF8800"),
    AppInfo("ليبر أوفيس", "مستندات وجداول وعروض", "مكتب", "#18A303"),
    AppInfo("جيمب", "تحرير الصور والتصميم", "تصميم", "#5C5543"),
    AppInfo("فيجوال كود", "محرر البرمجة الأشهر", "برمجة", "#007ACC"),
    AppInfo("تيليجرام", "مراسلة سريعة وآمنة", "تواصل", "#229ED9"),
    AppInfo("أوداسيتي", "تسجيل وتحرير الصوت", "صوت", "#0000CC"),
    AppInfo("بلندر", "تصميم ثلاثي الأبعاد", "تصميم", "#E87D0D")
)
