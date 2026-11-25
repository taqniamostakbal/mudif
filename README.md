<div align="center">

<img src="docs/images/badges/lang-ar.png" height="28" alt="العربية"> <a href="README_en.md"><img src="https://img.shields.io/static/v1?label=&message=English&color=593BFE&style=for-the-badge&logo=googletranslate&logoColor=white" alt="English"></a>

</div>

<p align="center">
  <img src="docs/images/hero.png" alt="مضيف (Mudif): هاتفك هو حاسوبك الكامل" width="100%">
</p>

<p align="center">
  <img src="docs/images/badges/version.svg" alt="version 0.2.2">
  <img src="docs/images/badges/build.svg" alt="build p128">
  <img src="docs/images/badges/android.svg" alt="android 9+">
  <img src="docs/images/badges/kotlin.svg" alt="kotlin 2.0.20">
  <img src="docs/images/badges/store.svg" alt="store 1228 programs">
  <img src="docs/images/badges/license.svg" alt="license MIT">
</p>

<p align="center">
  <strong>مضيف يحوّل هاتفك إلى حاسوب كامل: نظام دبيان مع واجهة LXQt، بالعربية، وتشغيل برامج ويندوز من المتجر.</strong>
</p>

<p align="center">
  مشروع شخصي من تقنية المستقبل | Exploring Tech — بناء taqniamostakbal.
</p>

<p align="center">
  <a href="https://github.com/taqniamostakbal/mudif/releases"><img src="docs/images/badges/nav-releases-ar.png" height="28" alt="الإصدارات"></a>
  <a href="#start-here"><img src="docs/images/badges/nav-start-ar.png" height="28" alt="ابدأ هنا"></a>
  <a href="#features"><img src="docs/images/badges/nav-features-ar.png" height="28" alt="الميزات"></a>
  <a href="#screenshots"><img src="docs/images/badges/nav-screenshots-ar.png" height="28" alt="لقطات الشاشة"></a>
  <a href="#install"><img src="docs/images/badges/nav-install-ar.png" height="28" alt="التثبيت"></a>
  <a href="#status"><img src="docs/images/badges/nav-status-ar.png" height="28" alt="حالة المشروع"></a>
  <a href="#contributing"><img src="docs/images/badges/nav-contributing-ar.png" height="28" alt="المساهمة"></a>
  <a href="https://www.youtube.com/@taqniaMostakbal"><img src="docs/images/badges/nav-youtube-ar.png" height="28" alt="يوتيوب"></a>
  <a href="https://x.com/yts5zqp"><img src="docs/images/badges/nav-x-ar.png" height="28" alt="إكس"></a>
</p>

<a id="start-here"></a>
## ابدأ هنا

اختر صفا واحدا. الوقت المعروض هو للخطوة الأولى فقط.

| أنت | افعل هذا أولا | الوقت |
|---|---|---|
| على أندرويد 9 أو أحدث | ثبّت الـ APK من [الإصدارات](https://github.com/taqniamostakbal/mudif/releases) فوق النسخة القديمة | دقيقتان |
| تريد تجربة برنامج ويندوز | افتح المتجر وابحث عن `notepad` وثبّت المفكرة | دقيقة واحدة |
| مطور | ابنِ من المصدر بالأمر في قسم [التثبيت](#install) | 5 دقائق |

<a id="about"></a>
## ما هو مضيف

مضيف تطبيق أندرويد (`com.bolm.mudif`) يشغّل نظام دبيان (trixie) كاملا مع واجهة LXQt على الهاتف. واجهة المستخدم عربية بالكامل، والخطان Cairo وLifta، واللون البنفسجي `#593BFE` هو لون الهوية، والبطريق هو الشعار. يعمل على أندرويد 9 فما فوق.

> الرؤية: يضغط على ما يريد فعله، والنظام يتولى الباقي.

<a id="features"></a>
## الميزات

### المترجم: من x86-64 إلى ARM64

مترجم مبني من الصفر بلغة C (5,086 سطرا) يحوّل برامج x86-64 إلى ARM64. لا يستعمل Winlator ولا Box64 ولا FEX. الترجمة تتم مرة واحدة على جهاز البناء، والبرامج المترجمة تصل جاهزة داخل التطبيق، فيعمل الهاتف كودا أصليا بدون مترجم وقت التشغيل.

### طبقة ويندوز

طبقة ويندوز مبنية فوق المترجم تشغّل برامج ويندوز داخل سطح مكتب مضيف. البرنامج المثبت حاليا هو المفكرة (ReactOS Notepad، نسخة مفتوحة المصدر، وليست مفكرة مايكروسوفت)، وقد تحقق من 177 استيرادا، وتفتح نافذتها وتكتب النص وتفتح قائمة ملف (8 عناصر) وتخرج نظيفا.

### المتجر

كتالوج فيه 1,228 برنامج (إنترنت، مكتب، وسائط، تطوير، ذكاء اصطناعي، نظام، خطوط، ألعاب)، وكلها برامج مجانية ومفتوحة المصدر. بطاقة (الكل) في المتجر تعرض 1,139، لأن الخطوط (87) وبرامج ويندوز (2) لها بطاقاتها الخاصة. التثبيت بضغطة واحدة من داخل التطبيق، والبرامج المثبتة تظهر في قائمة التطبيقات. القائمة الكاملة للبرامج في الملف `app/src/main/assets/store-catalog.json` في هذا المستودع: الاسم بالعربية والإنجليزية والوصف والتصنيف وطريقة التثبيت لكل برنامج. أغلب البرامج (1,191) حزم Debian تثبت بـ apt، ومصدرها في مستودعات Debian. باقي البرامج: 28 حزمة deb من مشاريعها (رابط التحميل في الكتالوج)، و6 سكربتات تثبيت في هذا المستودع تحت `app/src/main/assets/`، وبرنامجا ويندوز مترجمان بمترجم مضيف نفسه، وخط واحد. كود مضيف نفسه (التطبيق والمترجم وطبقة ويندوز والمتجر) موجود كاملا في هذا المستودع برخصة MIT.

### ميزات الإصدار p128 (ست ميزات جديدة)

**مؤشر الأداء (HUD):** شريحة صغيرة أعلى الشاشة تعرض FPS والذاكرة والبطارية والحرارة، وتتحدث كل ثانية. الذاكرة الحية مثبتة على جهاز الاختبار (3.2 غيغابايت، وارتفعت إلى 3.7 تحت حمل 500 ميغابايت ثم عادت). رقم FPS الحي وقيم البطارية والحرارة تظهر على هاتف حقيقي.

**مكونات ويندوز لكل برنامج:** المتجر يعرض المكونات التي يحتاجها كل برنامج ويندوز، وبوابة التثبيت ترفض قبل أي تثبيت إن كان المكون غير متوفر. المتوفر في الإصدار الأول: `msvcrt` و`vcruntime140`. DirectX غير متوفر بالتصميم (لا خلفية D3D)، و`msvcp140` و`concrt140` غير متوفرين.

**تقرير الجهاز:** شاشة مخصصة تعرض 12 سطرا: الجهاز، المعالج، نظام أندرويد، الذاكرة، التخزين، الشاشة، كرت الرسوميات، Vulkan، البطارية، الصحة، الحرارة، الجهد. كل سطر طابق مصدره المستقل على جهاز الاختبار. القيم الحية للبطارية واسم كرت Adreno والمعالج الحقيقي تظهر على هاتف حقيقي.

**العرض الخارجي والتحكم عن بعد:** العرض الخارجي ينتقل بالعارض إلى الشاشة الخارجية، ويتبعه الإدخال، ويعود عند الفصل، والجلسة تبقى حية. التحكم عن بعد من المتصفح يعمل على الشبكة المحلية فقط: صفحة دخول، ورمز QR، وكلمة مرور من 8 أحرف تتغير مع كل إعادة تشغيل، وكتابة عربية متصلة من المتصفح. التلفاز الحقيقي وشاشة DisplayPort الحقيقية هما الخطوة التالية.

**مشاركة الحافظة:** النسخ واللصق يتزامنان في الاتجاهين بين أندرويد والضيف، والعربية تصل متصلة. سطر في مركز التحكم يعرض الحالة (مفعّلة أو متوقفة). ملاحظة: نسخة وضعت قبل فتح العارض لا تصل حتى تنسخ من جديد.

**نقل الملفات:** إرسال ملف من الهاتف إلى الضيف باختيار الملف، مع شريط تقدم ورسالة نجاح تسمي المسار في الضيف. الملف المرسل يفتح في أندرويد بالتطبيق المناسب، وملف 50 ميغابايت أرسل بلا خطأ، وتعارض الأسماء يصير `(2)` بلا كتابة فوق الأصل، واختصار على سطح المكتب يفتح المجلد المشترك.

<a id="screenshots"></a>
## لقطات الشاشة

كل اللقطات من جهاز الاختبار (محاكي Redroid)، وليست صورا مصممة.

<p align="center">
  <img src="docs/images/demo.gif" alt="تثبيت برنامج ويندوز من المتجر" width="360">
</p>

<p align="center">تثبيت برنامج ويندوز من المتجر: بحث، ثم تأكيد المكونات، ثم تثبيت.</p>

<table>
  <tr>
    <td align="center"><img src="docs/images/screenshots/desktop.png" width="360" alt="سطح المكتب"><br>سطح المكتب: نظام دبيان مع واجهة LXQt على الهاتف</td>
    <td align="center"><img src="docs/images/screenshots/store.png" width="360" alt="المتجر"><br>المتجر: برامج مجانية تثبت بضغطة واحدة</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/screenshots/control-center.png" width="360" alt="مركز التحكم"><br>مركز التحكم: المظهر والواي فاي والبلوتوث والحافظة</td>
    <td align="center"><img src="docs/images/screenshots/device-report.png" width="360" alt="تقرير الجهاز"><br>تقرير الجهاز: 12 سطرا عن الجهاز والنظام</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/screenshots/remote-control.png" width="360" alt="التحكم عن بعد"><br>التحكم عن بعد: رمز QR وكلمة مرور تتغير مع كل تشغيل</td>
    <td align="center"><img src="docs/images/screenshots/hud.png" width="360" alt="مؤشر الأداء"><br>مؤشر الأداء: FPS والذاكرة والبطارية والحرارة فوق سطح المكتب</td>
  </tr>
</table>

<a id="install"></a>
## التثبيت

### من الإصدارات

نشرات APK الجاهزة في صفحة [الإصدارات](https://github.com/taqniamostakbal/mudif/releases) في هذا المستودع. ثبّت فوق أي نسخة سابقة.

> [!TIP]
> لا تلغ تثبيت التطبيق أبدا. إلغاء التثبيت يمسح نظام دبيان كاملا مع كل ملفاته.

### من المصدر

المتطلبات: JDK 17، وAndroid SDK ‏(API 34)‏، وGradle 8.7.

```sh
git clone https://github.com/taqniamostakbal/mudif.git
cd mudif
gradle :app:assembleDebug
```

الملف الناتج: `app/build/outputs/apk/debug/app-debug.apk`

<a id="status"></a>
## حالة المشروع

الإصدار الحالي 0.2.2 (البناء p128). هذا ما ثبت فعلا على جهاز الاختبار وعلى هاتف حقيقي، وهذه الخطوات التالية:

| الموضوع | الحالة |
|---|---|
| الميزات الست في p128 | مثبتة على جهاز الاختبار (محاكي Redroid ‏x86_64‏) |
| المترجم v0 وv1 | مثبت، والمخرجات مطابقة بايت بايت |
| طبقة ويندوز والمفكرة | مثبتة على جهاز الاختبار (النافذة، الكتابة، القائمة، الخروج)، وNotepad تحل على هاتف |
| التشغيل على هاتف حقيقي (ARM64) | مجرب على هاتف: سطح المكتب خدام، التطبيقات المثبتة كلها باينة، Notepad تحل، وFPS والبطارية والحرارة حيين |
| FPS الحي والبطارية والحرارة وكرت Adreno | مجربة على هاتف |
| تلفاز حقيقي وعرض DisplayPort | العرض الخارجي مثبت على شاشة محاكاة، والتلفاز الحقيقي خطوة تالية |
| البناءات الآلية (CI) | متوقفة حتى 1 ديسمبر (نفاد الدقائق المجانية)، والإصدارات تبنى وتتحقق محليا |

حدود معروفة:

- تنسيق `printf` بالنمط `%08x` يسقط صفرا بادئا (عيب في المترجم، والسبب الجذري لم يوجد بعد).
- الحفظ من داخل المفكرة نفسها ما زال محدودا (نافذة الحفظ تعيد الإلغاء).
- شارة `!` حمراء تظهر على أيقونات سطح المكتب (موجودة من قبل، وسببها لم يثبت بعد).

<a id="layout"></a>
## بنية المشروع

```
mudif/
├── app/                  # Android app (Kotlin, com.bolm.mudif)
├── tv/                   # Android TV receiver: shows the phone desktop on a TV
├── brand/                # BRAND.md + icon master
├── doc/                  # ARCHITECTURE.md and design docs
├── gradle/               # version catalog
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

<a id="contributing"></a>
## المساهمة

مضيف مشروع شخصي من تقنية المستقبل | Exploring Tech. إن وجدت خطأ، افتح Issue مع لقطة شاشة وخطوات الإعادة، أو أرسل Pull Request صغيرا في نطاق ضيق. لا ترسل تغييرات خارج نطاق المشكلة.

---

تقنية المستقبل | Exploring Tech

© 2026 تقنية المستقبل | Exploring Tech: جميع الحقوق محفوظة
