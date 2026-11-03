# Mudif UI — Style Spec (extracted from Anas's 8 reference images, 2026-11-03)

Reference = LAW, not inspiration. Every value below was read from the images.

## Non-negotiable brand (Anas's design law)
- Primary: **#593BFE** (electric purple) + white. App identity.
- Display font: **Lifta Swash Fixed Regular** (fonts/Liftaswashfixed-Regular.otf) — headings, hero titles.
- UI font: **Cairo** (fonts/Cairo.ttf) — body, buttons, labels. Full Arabic shaping.
- Language: **Fusha Arabic, RTL everywhere**.
- Copyright: «جميع الحقوق محفوظة © أنس بوعلام — تقنية المستقبل» + https://www.youtube.com/@taqniaMostakbal

## Palette (from refs 80, 81, 84)
- #593BFE primary purple · #977DFF light purple · #0033FF royal blue
- #0600AB deep blue · #00033D darkest navy · #F2E6EE lavender white
- #FFCCF2 pink · #BE74BE / #D481D2 / #703B94 magenta-purple ramp
- Accent gradients: purple→blue (#977DFF→#0033FF), pink→purple (#FFCCF2→#977DFF),
  deep (#0033FF→#0600AB→#00033D). Use on hero cards and the tab indicator.

## Navigation (refs 78, 79, 83)
- **Bottom Tab Bar, 5 tabs**, black/dark pill bar on light theme OR white pill
  on the purple theme — Anas's app: white frosted bar, purple sliding indicator.
- **Sliding pill indicator** (ref 79): the active tab sits inside a rounded pill
  that ANIMATES (slides) between tabs. This motion is the signature — replicate it.
- Patterns available from ref 78/83: Grid, Side Menu, Tab Bar, FAB, Sheet,
  Three Dots, Rectangular, Rudder. Use: Tab Bar (main), Sheet (distro details),
  FAB (quick install), Grid (app catalog).

## Cards (ref 82 — check-in app)
- Big rounded cards (radius ~20-24px), bold solid/gradient fills, playful icon
  doodles: e.g. blue card «Rant», green card «Reflect». Mudif equivalents:
  purple gradient cards for distros, blue for apps, magenta for gaming.
- Greeting header: «مساء الخير،» + subline «جاهز لتشغيل نظامك؟» (Fusha).
- Week-strip pattern → repurpose as «أنظمتي» horizontal strip.

## Color-name labels (ref 80 @BRAZIDY style — Anas's origin reference)
- Big serif/display color name + HEX + RGB values beneath. On distro cards,
  show the distro name big + size/version beneath, same composition.

## Gradient bars (refs 81, 84)
- Vertical gradient bars labeled with HEX at both ends — use as section
  dividers or the «المتجر» category headers.

## Avatar (ref-avatar.jpg)
- Anas's 3D avatar (suit, glasses, purple bg) → profile tab header.

## Screens (5 tabs, Fusha, RTL)
1. **الرئيسية**: greeting + «أنظمتي» cards (installed distros, gradient cards,
   HEX-style labels) + quick actions (FAB).
2. **المتجر**: distro marketplace — Debian / Ubuntu / Arch / Fedora / Kali /
   Alpine cards, size + «تثبيت بنقرة واحدة» button, install progress animation.
3. **التطبيقات**: visual app catalog grid (GIMP, VS Code, Firefox, VLC…) →
   «تثبيت», installed apps note «ستظهر كأيقونة عادية».
4. **الألعاب**: gaming box hero (Box64 + Wine) + game cards.
5. **حسابي**: avatar header, settings rows, about, copyright footer with
   YouTube link.

## Motion
- Tab indicator slides (ref 79). Cards lift on press. Install buttons morph
  to progress then «تم». Sheet slides up for distro details. No flat zooms.

## Anti-slop
- No em dash anywhere. Real Arabic copy, no lorem. Every button works
  (tab switch, install simulation, sheet open/close). Mobile-first, the phone
  is the device. Identity test: swap the logo — still unmistakably Mudif.
