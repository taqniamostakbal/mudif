package com.bolm.mudif.ui.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * P18.3 — Viewer settings (Termux:X11) in Fusha Arabic, Mudif style.
 *
 * Single source of truth for the viewer preferences:
 * - The viewer (fork) reads PreferenceManager.getDefaultSharedPreferences,
 *   which is exactly getSharedPreferences("<package>_preferences"). This
 *   object reads/writes that EXACT file with the EXACT keys and value
 *   formats from the fork's preferences.xml, so the settings screen and
 *   the viewer never disagree. (The app has no androidx.preference
 *   dependency, so the file is opened directly by name.)
 * - Mudif defaults are applied ONLY when a pref is absent (first launch).
 *   The user's choice always wins afterwards (openViewer used to overwrite
 *   them on every launch — that silently erased user choices).
 */
object ViewerPrefs {

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    /** Mudif defaults (only written when the key is absent). */
    fun applyMudifDefaults(context: Context, exactResolution: String) {
        val p = prefs(context)
        val e = p.edit()
        fun putStringIfAbsent(key: String, value: String) {
            if (!p.contains(key)) e.putString(key, value)
        }
        fun putBooleanIfAbsent(key: String, value: Boolean) {
            if (!p.contains(key)) e.putBoolean(key, value)
        }
        putStringIfAbsent("touchMode", "2")              // Simulated touchscreen
        putStringIfAbsent("displayResolutionMode", "exact")
        putStringIfAbsent("displayResolutionExact", exactResolution)
        putStringIfAbsent("forceOrientation", "landscape")
        putBooleanIfAbsent("adjustResolution", false)
        putBooleanIfAbsent("displayStretch", true)
        putBooleanIfAbsent("fullscreen", true)
        putBooleanIfAbsent("tapToMove", false)
        putBooleanIfAbsent("additionalKbdVisible", false)
        e.apply()
    }

    sealed class Setting {
        abstract val key: String
        abstract val title: String
        abstract val desc: String
    }

    data class SwitchSetting(
        override val key: String,
        override val title: String,
        override val desc: String,
        val default: Boolean
    ) : Setting()

    data class ListSetting(
        override val key: String,
        override val title: String,
        override val desc: String,
        val options: List<Pair<String, String>>, // value -> Fusha label
        val default: String
    ) : Setting()

    data class SliderSetting(
        override val key: String,
        override val title: String,
        override val desc: String,
        val min: Int,
        val max: Int,
        val default: Int
    ) : Setting()

    data class TextSetting(
        override val key: String,
        override val title: String,
        override val desc: String,
        val default: String
    ) : Setting()

    data class ActionSetting(
        override val key: String,
        override val title: String,
        override val desc: String
    ) : Setting()

    data class Section(val title: String, val settings: List<Setting>)

    private fun list(
        key: String, title: String, desc: String,
        options: List<Pair<String, String>>, default: String
    ) = ListSetting(key, title, desc, options, default)

    private fun switch(key: String, title: String, desc: String, default: Boolean) =
        SwitchSetting(key, title, desc, default)

    private fun slider(key: String, title: String, desc: String, min: Int, max: Int, default: Int) =
        SliderSetting(key, title, desc, min, max, default)

    private fun text(key: String, title: String, desc: String, default: String) =
        TextSetting(key, title, desc, default)

    val RESOLUTIONS = listOf(
        "320x200", "320x240", "352x288", "400x240", "640x350", "640x480",
        "800x480", "800x600", "1024x768", "1280x720", "1280x960", "1280x1024",
        "1400x1050", "1600x1024", "1600x1200", "1366x768", "1680x1050",
        "1920x1080", "1920x1200", "2048x1536", "2560x1600", "2560x2048",
        "3840x2160", "7680x4320"
    )

    private val USER_ACTIONS = listOf(
        "no action" to "بلا إجراء",
        "toggle soft keyboard" to "تبديل لوحة المفاتيح البرمجية",
        "toggle additional key bar" to "تبديل شريط المفاتيح الإضافية",
        "open preferences" to "فتح الإعدادات",
        "release pointer and keyboard capture" to "تحرير المؤشر ولوحة المفاتيح",
        "toggle fullscreen" to "تبديل ملء الشاشة",
        "restart activity" to "إعادة تشغيل النشاط",
        "exit" to "الخروج"
    )
    private val USER_ACTIONS_VOLUME = USER_ACTIONS + listOf(
        "send volume up" to "إرسال رفع الصوت",
        "send volume down" to "إرسال خفض الصوت"
    )
    private val USER_ACTIONS_MEDIA = USER_ACTIONS + listOf(
        "send media action" to "إرسال إجراء الوسائط"
    )

    val SECTIONS: List<Section> = listOf(
        Section("العرض", listOf(
            list("displayResolutionMode", "وضع دقة العرض",
                "كيف يحدد القارئ دقة سطح المكتب",
                listOf("native" to "أصلية", "scaled" to "مقيّسة",
                    "exact" to "دقيقة", "custom" to "مخصصة"), "scaled"),
            slider("displayScale", "مقياس العرض",
                "تكبير سطح المكتب أو تصغيره (٪)", 30, 300, 200),
            list("displayResolutionExact", "الدقة الدقيقة",
                "الدقة المستعملة عند اختيار الوضع الدقيق",
                RESOLUTIONS.map { it to it }, "1280x1024"),
            text("displayResolutionCustom", "دقة مخصصة",
                "اكتب الدقة بصيغة العرض×الارتفاع، مثل 1560x720", "1280x1024"),
            list("displayFilteringMode", "طريقة تنعيم العرض",
                "كيف تُصقل الصورة عند تغيير حجمها",
                listOf("bilinear" to "ثنائي الخط", "nearest" to "الأقرب"),
                "nearest"),
            switch("adjustResolution", "ضبط الدقة حسب الاتجاه",
                "تغيير الدقة تلقائيا عند تدوير الهاتف", false),
            switch("displayStretch", "تمديد العرض لملء الشاشة",
                "تمديد سطح المكتب ليملأ الشاشة بلا أشرطة سوداء", false),
            switch("Reseed", "إعادة ضبط الشاشة عند فتح لوحة المفاتيح",
                "تعديل حجم الشاشة أثناء فتح لوحة المفاتيح البرمجية", true),
            switch("PIP", "وضع الصورة داخل الصورة",
                "عرض التطبيق في نافذة صغيرة عند الضغط على زر الرئيسية", true),
            switch("fullscreen", "ملء الشاشة",
                "تفعيل الوضع الغامر في شاشة الهاتف", true),
            list("forceOrientation", "اتجاه الشاشة",
                "تثبيت اتجاه القارئ أو تركه تلقائيا",
                listOf("auto" to "تلقائي", "portrait" to "عمودي",
                    "landscape" to "أفقي", "reverse portrait" to "عمودي معكوس",
                    "reverse landscape" to "أفقي معكوس"), "auto"),
            switch("hideCutout", "إخفاء نتوء الشاشة",
                "إخفاء النتوء (إن وجد) في أعلى الشاشة", true),
            switch("keepScreenOn", "إبقاء الشاشة مضاءة",
                "منع الشاشة من الانطفاء أثناء استعمال سطح المكتب", true)
        )),
        Section("المؤشر", listOf(
            list("touchMode", "وضع إدخال شاشة اللمس",
                "لمس محاكى: المؤشر يتبع إصبعك. لوحة لمس: المؤشر يتحرك نسبيا. لمس مباشر: النقر فقط.",
                listOf("1" to "لوحة لمس", "2" to "لمس محاكى",
                    "3" to "لمس مباشر"), "1"),
            switch("scaleTouchpad", "تطبيق مقياس العرض على لوحة اللمس",
                "ضبط سرعة لوحة اللمس حسب مقياس العرض", true),
            switch("showStylusClickOverride", "إظهار تجاوز نقرة القلم",
                "إظهار زر لتجاوز نقرة القلم", false),
            switch("stylusIsMouse", "القلم يعمل كفأرة",
                "معاملة القلم كفأرة عوض أداة لمس", false),
            switch("stylusButtonContactModifierMode",
                "زر القلم كمعدّل عند اللمس",
                "استعمال زر القلم كمعدّل (Ctrl/Alt) عند ملامسة الشاشة", false),
            switch("showMouseHelper", "أزرار الماوس على الشاشة",
                "أزرار النقر الأيسر والأوسط والأيمن للاستعمال مع لوحة اللمس", false),
            switch("pointerCapture", "التقاط أجهزة المؤشر الخارجية",
                "اعتراض كل أحداث أجهزة المؤشر المادية عند الإمكان", true),
            list("transformCapturedPointer", "تحويل حركة المؤشر الملتقط",
                "تدوير حركة المؤشر الملتقط حسب اتجاه الشاشة",
                listOf("no" to "بدون تحويل", "c" to "باتجاه عقارب الساعة",
                    "cc" to "عكس عقارب الساعة", "ud" to "مقلوب",
                    "at" to "تلقائي"), "no"),
            slider("capturedPointerSpeedFactor", "سرعة المؤشر الملتقط",
                "عامل سرعة المؤشر الملتقط (٪)", 1, 300, 100),
            switch("tapToMove", "النقر للتحريك في لوحة اللمس",
                "النقر مرة يحرك المؤشر، والنقر مرتين ينقر", false),
            switch("ignoreGamepadEvents", "تجاهل أحداث لوحة الألعاب",
                "منع أحداث لوحة الألعاب وعصا التحكم من الوصول إلى الخادم", false)
        )),
        Section("لوحة المفاتيح", listOf(
            switch("showAdditionalKbd", "إظهار لوحة المفاتيح الإضافية",
                "إظهار شريط المفاتيح الإضافية (ESC وCtrl وغيرها)", true),
            switch("showIMEWhileExternalConnected",
                "إظهار لوحة المفاتيح البرمجية مع لوحة خارجية",
                "إظهار لوحة المفاتيح البرمجية أثناء اتصال لوحة مفاتيح مادية", true),
            switch("preferScancodes", "تفضيل رموز المفاتيح عند الإمكان",
                "ترك خادم X يعالج تخطيط لوحة المفاتيح المادية", false),
            switch("hardwareKbdScancodesWorkaround",
                "حل بديل لرموز لوحة المفاتيح المادية",
                "يصلح الرموز في بعض الأجهزة، وقد يسبب مشاكل مع تخطيطات متعددة", true),
            switch("dexMetaKeyCapture", "التقاط مفتاح Meta في وضع Dex",
                "التقاط مفتاح Meta (Win) في وضع سامسونج Dex", false),
            ActionSetting("enableAccessibilityService",
                "تفعيل خدمة الوصول يدويا",
                "فتح إعدادات الوصول في النظام لاعتراض اختصارات النظام"),
            switch("enableAccessibilityServiceAutomatically",
                "تفعيل خدمة الوصول تلقائيا",
                "يتطلب إذن WRITE_SECURE_SETTINGS", false),
            switch("pauseKeyInterceptingWithEsc",
                "إيقاف اعتراض المفاتيح مؤقتا بمفتاح Esc",
                "يتطلب اعتراض اختصارات النظام", false),
            switch("filterOutWinkey", "تصفية مفتاح Win المعترض",
                "السماح باستعمال اختصارات Dex أثناء الاعتراض", false),
            switch("enforceCharBasedInput", "فرض الإدخال المعتمد على الأحرف",
                "إيقاف الاقتراحات والكتابة التنبؤية ولغات CJK", false)
        )),
        Section("شريط المفاتيح الإضافية", listOf(
            switch("adjustHeightForEK", "منع شريط المفاتيح من تغطية العرض",
                "تصغير العرض حتى لا يغطيه شريط المفاتيح", true),
            switch("useTermuxEKBarBehaviour",
                "إلغاء تفعيل المفاتيح الخاصة بعد كل ضغطة",
                "استعمل الضغطة المطولة لتثبيت مفتاح خاص", true),
            slider("opacityEKBar", "شفافية شريط المفاتيح",
                "شفافية شريط المفاتيح الإضافية (٪)", 1, 100, 100),
            text("extra_keys_config", "تكوين المفاتيح الإضافية",
                "تخطيط مفاتيح الشريط بصيغة JSON", "")
        )),
        Section("إجراءات المستخدم", listOf(
            list("swipeUpAction", "إجراء السحب للأعلى",
                "ما يحدث عند السحب بثلاثة أصابع للأعلى",
                USER_ACTIONS, "no action"),
            list("swipeDownAction", "إجراء السحب للأسفل",
                "ما يحدث عند السحب بثلاثة أصابع للأسفل",
                USER_ACTIONS, "toggle additional key bar"),
            list("volumeUpAction", "إجراء زر رفع الصوت",
                "ما يحدث عند الضغط على زر رفع الصوت",
                USER_ACTIONS_VOLUME, "no action"),
            list("volumeDownAction", "إجراء زر خفض الصوت",
                "ما يحدث عند الضغط على زر خفض الصوت",
                USER_ACTIONS_VOLUME, "no action"),
            list("backButtonAction", "إجراء زر الرجوع",
                "ما يحدث عند الضغط على زر الرجوع",
                USER_ACTIONS, "toggle additional key bar"),
            list("notificationTapAction", "إجراء النقر على الإشعار",
                "ما يحدث عند النقر على إشعار القارئ",
                USER_ACTIONS, "open preferences"),
            list("notificationButton0Action", "إجراء زر الإشعار الأول",
                "ما يحدث عند الضغط على زر الإشعار الأول",
                USER_ACTIONS, "open preferences"),
            list("notificationButton1Action", "إجراء زر الإشعار الثاني",
                "ما يحدث عند الضغط على زر الإشعار الثاني",
                USER_ACTIONS, "exit"),
            list("mediaKeysAction", "إجراء مفاتيح الوسائط",
                "ما يحدث عند الضغط على مفاتيح الوسائط",
                USER_ACTIONS_MEDIA, "no action")
        )),
        Section("أخرى", listOf(
            switch("clipboardEnable", "تفعيل الحافظة المشتركة",
                "مزامنة الحافظة بين أندرويد وسطح المكتب", true),
            switch("storeSecondaryDisplayPreferencesSeparately",
                "حفظ إعدادات الشاشة الثانوية بشكل منفصل",
                "حفظ إعدادات الشاشة الخارجية في ملف مستقل", false),
            ActionSetting("configureResponseToUserActions",
                "ضبط إجراءات المستخدم",
                "الانتقال إلى قسم إجراءات المستخدم في الأسفل"),
            ActionSetting("requestNotificationPermission",
                "طلب إذن الإشعارات",
                "طلب إذن عرض إشعارات القارئ من النظام")
        ))
    )
}
