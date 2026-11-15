# arabic-cli.sh: اوامر نظام مضيف بالعربية الفصحى
# النسخة: 2.0
MUDIF_CLI_VERSION="2.0"

# --- حل اسم برنامج ---
# ملف البرامج programs.tsv بجانب هذا الملف (اسم، اسم دارجة، حزمة apt، امر التشغيل، الطريقة، المعرف).
# _حل-الاسم يطبع كل الصفوف المطابقة للاسم العربي (الاسم الفصيح او الدارج) او المعرف، ولا شيء ان لم يوجد.
# يعيد 2 ان كان الملف غائبا.
_حل-الاسم() {
  local map="${MUDIF_PROGRAMS:-/root/.config/mudif/programs.tsv}"
  [ -f "$map" ] || return 2
  awk -F'\t' -v n="$1" '$1==n || $2==n || $6==n' "$map"
}

# _تأكيد يسال (ن/لا) ويعيد 0 عند نعم فقط.
# وكيل مضيف (MUDIF_AGENT=1): طرفية الوكيل غير تفاعلية ولا
# إدخال فيها، وجملة المستخدم هي الموافقة، فيقبل تلقائيا.
# الطرفية البشرية التفاعلية تسأل دائما.
_تأكيد() {
  if [ "$MUDIF_AGENT" = "1" ] && [ ! -t 0 ]; then return 0; fi
  local answer
  read -r -p "$1؟ (ن/لا) " answer
  case "$answer" in
    ن|نعم) return 0;;
    *) echo "تم الالغاء."; return 1;;
  esac
}

# _عدد-الاسطر يعد اسطر نص (0 للنص الفارغ).
_عدد-الاسطر() {
  printf '%s\n' "$1" | grep -c .
}

# --- الملفات ---
اعرض() { ls -lah "$@"; }

ادخل() {
  if [ "$#" -eq 0 ]; then echo "الاستعمال: ادخل <مجلد>"; return 1; fi
  cd "$1" || return 1
}

انسخ() {
  if [ "$#" -lt 2 ]; then echo "الاستعمال: انسخ <مصدر> <هدف>"; return 1; fi
  cp -ri "$@" && echo "تم النسخ."
}

انقل() {
  if [ "$#" -lt 2 ]; then echo "الاستعمال: انقل <مصدر> <هدف>"; return 1; fi
  mv -i "$@" && echo "تم النقل."
}

احذف() {
  if [ "$#" -eq 0 ]; then echo "الاستعمال: احذف <مسار...>"; return 1; fi
  for target in "$@"; do
    if [ -d "$target" ]; then
      read -r -p "هل انت متاكد من حذف المجلد $target؟ (ن/لا) " answer
      case "$answer" in
        ن|نعم) rm -rf "$target" && echo "تم الحذف.";;
        *) echo "تم الالغاء.";;
      esac
    else
      rm -i "$target"
    fi
  done
}

انشئ() {
  if [ "$#" -eq 0 ]; then echo "الاستعمال: انشئ <ملف>"; return 1; fi
  touch "$@" && echo "تم الانشاء."
}

انشئ-مجلد() {
  if [ "$#" -eq 0 ]; then echo "الاستعمال: انشئ-مجلد <اسم>"; return 1; fi
  mkdir -p "$@" && echo "تم انشاء المجلد."
}

اقرأ() {
  if [ "$#" -eq 0 ]; then echo "الاستعمال: اقرأ <ملف>"; return 1; fi
  cat "$@"
}

اقرأ-صفحة() {
  if [ "$#" -eq 0 ]; then echo "الاستعمال: اقرأ-صفحة <ملف>"; return 1; fi
  if command -v less >/dev/null 2>&1; then less "$@"; else cat "$@"; fi
}

ابحث-عن-ملف() {
  if [ "$#" -eq 0 ]; then echo "الاستعمال: ابحث-عن-ملف <اسم> [مجلد]"; return 1; fi
  local name="$1"; local dir="${2:-.}"
  find "$dir" -iname "*$name*" 2>/dev/null
}

ابحث-في-الملف() {
  if [ "$#" -lt 2 ]; then echo "الاستعمال: ابحث-في-الملف <كلمة> <ملف>"; return 1; fi
  grep -n --color=auto "$1" "$2"
}

احص() {
  if [ "$#" -eq 0 ]; then echo "الاستعمال: احص <ملف>"; return 1; fi
  local lines words chars
  read -r lines words chars _ < <(wc -l -w -c "$1")
  echo "الملف: $1"
  echo "الاسطر: $lines"
  echo "الكلمات: $words"
  echo "الحروف: $chars"
}

اضغط() {
  if [ "$#" -eq 0 ]; then echo "الاستعمال: اضغط <مسار>"; return 1; fi
  local path="$1"
  local base
  base=$(basename "$path")
  tar -czf "$base.tar.gz" "$path" && echo "تم الضغط: $base.tar.gz"
}

فك-الضغط() {
  if [ "$#" -eq 0 ]; then echo "الاستعمال: فك-الضغط <ملف>"; return 1; fi
  case "$1" in
    *.tar.gz|*.tgz) tar -xzf "$1" && echo "تم فك الضغط.";;
    *.zip)
      if command -v unzip >/dev/null 2>&1; then unzip "$1" && echo "تم فك الضغط."; else echo "unzip غير مثبت."; return 1; fi;;
    *) echo "الصيغة غير مدعومة: $1"; return 1;;
  esac
}

افتح() {
  if [ "$#" -eq 0 ]; then echo "الاستعمال: افتح <برنامج أو ملف>"; return 1; fi
  if [ "$#" -eq 1 ]; then
    local m n bin
    if m=$(_حل-الاسم "$1"); then
      n=$(_عدد-الاسطر "$m")
      if [ "$n" -eq 1 ]; then
        bin=$(printf '%s' "$m" | cut -f4)
        if [ -z "$bin" ]; then echo "البرنامج $1 ليس له امر تشغيل."; return 1; fi
        if ! command -v "$bin" >/dev/null 2>&1; then echo "البرنامج $1 غير مثبت. ثبته أولا: ثبت $1"; return 1; fi
        "$bin" >/dev/null 2>&1 & disown
        echo "تم فتح $1."
        return 0
      elif [ "$n" -gt 1 ]; then
        echo "يوجد $n برامج بهذا الاسم:"
        printf '%s\n' "$m" | cut -f1
        return 1
      fi
    fi
  fi
  xdg-open "$1" >/dev/null 2>&1 &
  disown
}

اعرض-الشجرة() {
  find "${1:-.}" -print 2>/dev/null | sort
}

# --- النظام ---
حدث() {
  apt update
}

ثبت() {
  if [ "$#" -eq 0 ]; then echo "الاستعمال: ثبت <برنامج>"; return 1; fi
  if [ "$#" -eq 1 ]; then
    local m n apt method
    if ! m=$(_حل-الاسم "$1"); then echo "ملف البرامج غير موجود."; return 1; fi
    n=$(_عدد-الاسطر "$m")
    if [ "$n" -eq 1 ]; then
      apt=$(printf '%s' "$m" | cut -f3); method=$(printf '%s' "$m" | cut -f5)
      if [ "$method" != "apt" ] || [ -z "$apt" ]; then echo "البرنامج $1 لا يثبت عبر apt."; return 1; fi
      _تأكيد "تثبيت $1 ($apt)" || return 1
      apt install -y "$apt" && echo "تم تثبيت $1."
      return 0
    elif [ "$n" -gt 1 ]; then
      echo "يوجد $n برامج بهذا الاسم:"
      printf '%s\n' "$m" | cut -f1
      return 1
    fi
  fi
  apt install -y "$@"
}

ازل() {
  if [ "$#" -eq 0 ]; then echo "الاستعمال: ازل <حزمة...>"; return 1; fi
  apt remove "$@"
}

رقي() {
  apt update && apt full-upgrade
}

حدّث-النظام() {
  _تأكيد "تحديث النظام كاملا" || return 1
  apt update && apt upgrade -y && echo "تم تحديث النظام."
}

نظف() {
  apt autoremove && apt autoclean && echo "تم التنظيف."
}

نظف-الكاش() {
  _تأكيد "تنظيف كاش الحزم" || return 1
  apt clean && echo "تم تنظيف الكاش."
}

معلومات() {
  echo "النظام: $(uname -s) $(uname -r)"
  echo "المعالج: $(nproc) نواة"
  echo "الذاكرة:"
  free -h
  echo "القرص:"
  df -h /
  echo "مدة التشغيل: $(uptime -p 2>/dev/null || uptime)"
  echo "اصدار دبيان: $(grep PRETTY_NAME /etc/os-release | cut -d= -f2 | tr -d '"')"
}

القرص() { df -h; }

الذاكرة() { free -h; }

العمليات() { ps aux --sort=-%mem | head -20; }

اقتل() {
  if [ "$#" -eq 0 ]; then echo "الاستعمال: اقتل <اسم/رقم>"; return 1; fi
  case "$1" in
    ''|*[!0-9]*) pkill -f "$1" && echo "تم ايقاف $1.";;
    *) kill "$1" && echo "تم ايقاف العملية $1.";;
  esac
}

التاريخ() { date "+%A %d %B %Y"; }

الوقت() { date "+%H:%M:%S"; }

من-انا() { whoami; id; }

الصلاحيات() {
  if [ "$#" -eq 0 ]; then echo "الاستعمال: الصلاحيات <ملف>"; return 1; fi
  ls -l "$1"
  stat "$1"
}

غير-الصلاحيات() {
  if [ "$#" -lt 2 ]; then echo "الاستعمال: غير-الصلاحيات <نمط> <ملف>"; return 1; fi
  chmod "$1" "$2" && echo "تم تغيير الصلاحيات."
}

النسخة() {
  echo "نسخة الاوامر العربية: $MUDIF_CLI_VERSION"
  echo "نظام مضيف: $(grep PRETTY_NAME /etc/os-release | cut -d= -f2 | tr -d '"')"
}

# --- الشبكة ---
الشبكة() {
  ip -brief address
  ip route
  ping -c 2 8.8.8.8
}

عنواني() { ip -brief address; }

اختبر-الاتصال() {
  ping -c 4 "${1:-8.8.8.8}"
}

# ابحث: بحث ويب يعيد استعمال web_search من خادم مضيف
# (نفس المسار: Exa ان وجد مفتاح، ثم Tavily، ثم DuckDuckGo بلا مفتاح).
# لا مزود جديد ولا مفتاح جديد.
ابحث() {
  if [ "$#" -eq 0 ]; then echo "الاستعمال: ابحث <كلمة>"; return 1; fi
  local server="/root/.config/mudif/mudif-mcp-server.py"
  [ -f "$server" ] || { echo "خادم البحث غير موجود. افتح المساعد أولا."; return 1; }
  python3 - "$*" <<'PY'
import importlib.util, sys
try:
    spec = importlib.util.spec_from_file_location("mudif_mcp", "/root/.config/mudif/mudif-mcp-server.py")
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    r = m.web_search({"query": sys.argv[1], "count": 5})
except Exception as e:
    print("فشل البحث: " + str(e)); sys.exit(1)
if r.get("error"):
    print("فشل البحث: " + r["error"]); sys.exit(1)
print("المصدر: " + r.get("source", ""))
for x in r.get("results", []):
    print("- " + x.get("title", "") + "\n  " + x.get("url", ""))
PY
}

وايفاي() {
  if command -v nmcli >/dev/null 2>&1; then
    nmcli device status
    nmcli radio wifi
  else
    echo "nmcli غير متوفر، واجهات الشبكة:"
    ip link show
  fi
}

# --- التطبيقات ---
المتصفح() { firefox-esr >/dev/null 2>&1 & disown; }
الملفات() { pcmanfm-qt >/dev/null 2>&1 & disown; }
الاعدادات() { lxqt-config >/dev/null 2>&1 & disown; }
المتجر() { echo "المتجر قيد البناء."; }
الطرفية() { qterminal >/dev/null 2>&1 & disown; }

عدل() {
  if [ "$#" -eq 0 ]; then echo "الاستعمال: عدل <ملف>"; return 1; fi
  if command -v nano >/dev/null 2>&1; then nano "$1";
  elif command -v vi >/dev/null 2>&1; then vi "$1";
  else echo "لا يوجد محرر نصي مثبت."; return 1; fi
}

# --- الطاقة ---
اقفل() {
  if command -v lxqt-leave >/dev/null 2>&1; then lxqt-leave --lock;
  else echo "قفل الشاشة غير متوفر."; return 1; fi
}

اخرج() {
  if command -v lxqt-leave >/dev/null 2>&1; then lxqt-leave;
  else echo "تسجيل الخروج غير متوفر."; return 1; fi
}

اطفئ() {
  echo "يتم ايقاف الجلسة. اغلق التطبيق من اندرويد لايقاف النظام تماما."
  if command -v lxqt-leave >/dev/null 2>&1; then lxqt-leave --logout; fi
}

اعد-التشغيل() {
  echo "يتم ايقاف الجلسة. اعد فتح سطح المكتب من التطبيق لاعادة التشغيل."
  if command -v lxqt-leave >/dev/null 2>&1; then lxqt-leave --logout; fi
}

# --- المساعدة والتحديث ---
مساعدة() {
  echo "اوامر نظام مضيف (اكتب الامر ثم الوسائط):"
  echo "الملفات: اعرض، ادخل، انسخ، انقل، احذف، انشئ، انشئ-مجلد، اقرأ، اقرأ-صفحة، ابحث-عن-ملف، ابحث-في-الملف، احص، اضغط، فك-الضغط، افتح، اعرض-الشجرة"
  echo "النظام: حدث، ثبت، ازل، رقي، حدّث-النظام، نظف، نظف-الكاش، معلومات، القرص، الذاكرة، العمليات، اقتل، التاريخ، الوقت، من-انا، الصلاحيات، غير-الصلاحيات، النسخة"
  echo "الشبكة: الشبكة، عنواني، اختبر-الاتصال، ابحث، وايفاي"
  echo "التطبيقات: المتصفح، الملفات، الاعدادات، المتجر، الطرفية، عدل"
  echo "الطاقة: اقفل، اخرج، اطفئ، اعد-التشغيل"
  echo "للتفصيل عن امر اكتب: type الامر (مثال: type اعرض)"
}

حدث-الاوامر() {
  local tmp="/root/.config/mudif/arabic-cli.sh.new"
  local url="https://raw.githubusercontent.com/taqniamostakbal/mudif/main/app/src/main/assets/mudif-cli/arabic-cli.sh"
  if command -v curl >/dev/null 2>&1 && curl -fsSL "$url" -o "$tmp" 2>/dev/null; then
    mv "$tmp" /root/.config/mudif/arabic-cli.sh
    . /root/.config/mudif/arabic-cli.sh
    echo "تم تحديث الاوامر الى النسخة $MUDIF_CLI_VERSION."
  else
    echo "التحديثات تأتي مع نسخة التطبيق."
  fi
}

command_not_found_handle() {
  echo "امر غير معروف: $1"
  echo "اكتب مساعدة لعرض كل الاوامر بالعربية."
  return 127
}

if [[ $- == *i* ]]; then
  printf '\e[35mنظام مضيف: الطرفية العربية\e[0m\n'
  printf 'اكتب «مساعدة» لعرض كل الاوامر بالعربية\n'
fi
