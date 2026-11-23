// نظام مضيف — التحكم عن بعد. عميل سطح المكتب في المتصفح.
// يستعمل مكتبة RFB (core/rfb.js) فقط؛ الواجهة الجاهزة مخفية تماما.
import RFB from './core/rfb.js';
import KeyTable from './core/input/keysym.js';

const $ = (id) => document.getElementById(id);
const screen = $('screen');
const loginEl = $('login');
const loginCard = $('loginCard');
const passwordInput = $('password');
const loginStatus = $('loginStatus');
const statusDot = $('statusDot');
const addrEl = $('addr');
const goneEl = $('gone');
const goneReason = $('goneReason');
const clipbar = $('clipbar');
const clipText = $('clipText');
const hiddenInput = $('hiddenInput');

let rfb = null;
let savedPassword = '';

addrEl.textContent = window.location.host;

function setDot(state) {
    statusDot.className = 'dot ' + state;
}

function connect(password) {
    disconnectRfb();
    savedPassword = password;
    loginStatus.textContent = '';
    setDot('connecting');
    const url = 'ws://' + window.location.host + '/websockify';
    try {
        rfb = new RFB(screen, url, { credentials: { password } });
    } catch (e) {
        loginStatus.textContent = 'تعذر الاتصال';
        setDot('disconnected');
        return;
    }
    rfb.scaleViewport = true;
    rfb.resizeSession = false;
    rfb.addEventListener('connect', onConnect);
    rfb.addEventListener('disconnect', onDisconnect);
    rfb.addEventListener('credentialsrequired', onCredentialsRequired);
    rfb.addEventListener('desktopname', onDesktopName);
    rfb.addEventListener('clipboard', onClipboard);
}

function disconnectRfb() {
    if (rfb) {
        try { rfb.disconnect(); } catch (e) { /* already gone */ }
        rfb = null;
    }
}

function onConnect() {
    loginEl.style.display = 'none';
    goneEl.hidden = true;
    setDot('connected');
    screen.focus();
}

function onCredentialsRequired() {
    disconnectRfb();
    loginEl.style.display = 'flex';
    loginCard.classList.remove('shake');
    void loginCard.offsetWidth; // restart the animation
    loginCard.classList.add('shake');
    loginStatus.textContent = 'كلمة المرور غير صحيحة، أعد المحاولة';
    setDot('disconnected');
    passwordInput.select();
}

function onDisconnect(e) {
    disconnectRfb();
    setDot('disconnected');
    loginEl.style.display = 'none';
    const clean = e && e.detail && e.detail.clean;
    goneReason.textContent = clean ? 'أغلق الاتصال من الهاتف.' : 'تعذر الوصول إلى سطح المكتب. تأكد من أن التحكم عن بعد ما زال يعمل.';
    goneEl.hidden = false;
}

function onDesktopName(e) {
    if (e && e.detail && e.detail.name) {
        document.title = e.detail.name;
    }
}

function onClipboard(e) {
    if (e && e.detail && typeof e.detail.text === 'string') {
        clipText.value = e.detail.text;
        clipbar.hidden = false;
        $('btnClip').classList.add('active');
    }
}

// ---- login ----
function doConnect() {
    const pw = passwordInput.value;
    if (!pw) {
        loginStatus.textContent = 'أدخل كلمة المرور';
        return;
    }
    connect(pw);
}
$('btnConnect').addEventListener('click', doConnect);
passwordInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') doConnect();
});
$('btnShowPass').addEventListener('click', () => {
    passwordInput.type = passwordInput.type === 'password' ? 'text' : 'password';
});
$('btnReconnect').addEventListener('click', () => {
    goneEl.hidden = true;
    if (savedPassword) {
        connect(savedPassword);
    } else {
        loginEl.style.display = 'flex';
    }
});

// ---- top bar ----
$('btnFull').addEventListener('click', () => {
    const el = document.getElementById('stage');
    if (document.fullscreenElement) {
        document.exitFullscreen();
    } else if (el.requestFullscreen) {
        el.requestFullscreen();
    }
});
$('btnKeys').addEventListener('click', () => {
    hiddenInput.value = '';
    hiddenInput.focus();
    if (rfb) rfb.focus();
});
$('btnClip').addEventListener('click', () => {
    clipbar.hidden = !clipbar.hidden;
    $('btnClip').classList.toggle('active', !clipbar.hidden);
    if (!clipbar.hidden) clipText.focus();
});
$('btnPaste').addEventListener('click', () => {
    if (rfb && clipText.value) {
        rfb.clipboardPasteFrom(clipText.value);
    }
});
$('btnDisc').addEventListener('click', () => {
    disconnectRfb();
    setDot('disconnected');
    loginEl.style.display = 'flex';
    savedPassword = '';
});

// ---- hidden input: summon the device keyboard on touch devices,
// forward its keys to the desktop. Desktop browsers send keys
// directly to the canvas (RFB's own handlers); this is only the
// touch-keyboard bridge. ----
const NAMED_KEYS = {
    'Enter': 'XK_Return', 'Backspace': 'XK_BackSpace', 'Tab': 'XK_Tab',
    'Escape': 'XK_Escape', 'Delete': 'XK_Delete', 'ArrowLeft': 'XK_Left',
    'ArrowUp': 'XK_Up', 'ArrowRight': 'XK_Right', 'ArrowDown': 'XK_Down',
    'Home': 'XK_Home', 'End': 'XK_End', 'PageUp': 'XK_Page_Up', 'PageDown': 'XK_Page_Down',
};
// Arabic letters: Unicode codepoint -> X keysym (from keysymdef.h).
const ARABIC_KEYSYMS = {
    0x0621: 0x05c1, 0x0627: 0x05c7, 0x0628: 0x05c8, 0x0629: 0x05c9,
    0x062a: 0x05ca, 0x062b: 0x05cb, 0x062c: 0x05cc, 0x062d: 0x05cd,
    0x062e: 0x05ce, 0x062f: 0x05cf, 0x0630: 0x05d0, 0x0632: 0x05d2,
    0x0633: 0x05d3, 0x0634: 0x05d4, 0x0635: 0x05d5, 0x0636: 0x05d6,
    0x0637: 0x05d7, 0x0638: 0x05d8, 0x0639: 0x05d9, 0x063a: 0x05da,
    0x0641: 0x05e1, 0x0642: 0x05e2, 0x0643: 0x05e3, 0x0644: 0x05e4,
    0x0645: 0x05e5, 0x0646: 0x05e6, 0x0648: 0x05e8, 0x0649: 0x05e9,
    0x064a: 0x05ea,
};
function keysymForEvent(e) {
    if (e.key && e.key.length === 1) {
        const cp = e.key.codePointAt(0);
        if (ARABIC_KEYSYMS[cp] !== undefined) return ARABIC_KEYSYMS[cp];
        if (cp < 0x100) return cp; // Latin-1: keysym == code point
        return 0;
    }
    const name = NAMED_KEYS[e.key];
    if (name && KeyTable[name] !== undefined) return KeyTable[name];
    return 0;
}
function forwardKey(e, down) {
    if (!rfb) return;
    const keysym = keysymForEvent(e);
    if (keysym) {
        rfb.sendKey(keysym, e.code || '', down);
        if (down) e.preventDefault();
    }
}
hiddenInput.addEventListener('keydown', (e) => forwardKey(e, true));
hiddenInput.addEventListener('keyup', (e) => forwardKey(e, false));
hiddenInput.addEventListener('blur', () => { hiddenInput.value = ''; });

// Focus the canvas on click so desktop-browser keys flow.
screen.addEventListener('click', () => { if (rfb) rfb.focus(); });
screen.setAttribute('tabindex', '0');
