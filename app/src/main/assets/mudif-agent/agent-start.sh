#!/bin/sh
# Mudif agent brain starter (guest side).
#
# Idempotent: it NEVER restarts a server that is already running
# (pgrep guards), so starting the agent from the chat cannot kill a
# brain that is mid-test or mid-task. Binds loopback only
# (127.0.0.1), never 0.0.0.0: the agent server is reachable only
# from inside this guest and from the Mudif app, which shares the
# same loopback.
#
# Logs:
#   /root/.config/mudif/ollama.log
#   /root/.config/mudif/opencode.log
#   /root/.config/mudif/agent-start.log  (this script's own output)
#
# Started by the app (AgentServer) with:
#   sh /root/.config/mudif/agent-start.sh
# The script backgrounds both servers and returns at once; the app
# polls 127.0.0.1:4096 until the server answers.
set -u

MUDIF_HOME=/root/.config/mudif
mkdir -p "$MUDIF_HOME"

# Ollama: the local model host. OLLAMA_HOST pins it to loopback.
# p78: a STRAY ollama can own 11434 from OUTSIDE the guest
# (rig-proven: the server ran in another mount namespace with
# HOME=/root = the Android system root, "mkdir /root: permission
# denied"). pgrep alone cannot tell the stray from the good
# server, and the stray answers /api/tags with an error, so a
# blind "already running" leaves the brain permanently broken.
# Probe first: a valid server answers /api/tags with a "models"
# list within 20 s. Only a process that never answers validly
# is killed and replaced by the real in-guest server
# (OLLAMA_HOME pins the models dir to the guest /root, so the
# server can never land on the Android /root again). A
# good-but-slow server is never killed: it answers inside the
# 20 s wait and the kill path is never reached.
ollama_ok() {
    curl -fsS http://127.0.0.1:11434/api/tags 2>/dev/null | grep -q '"models"'
}
wait_ollama() {
    i=1
    while [ "$i" -le 20 ]; do
        if ollama_ok; then
            echo "ollama: answering on 11434"
            return 0
        fi
        sleep 1
        i=$((i + 1))
    done
    return 1
}
if ! pgrep -x ollama >/dev/null 2>&1; then
    (OLLAMA_HOME=/root/.ollama OLLAMA_HOST=127.0.0.1:11434 ollama serve >"$MUDIF_HOME/ollama.log" 2>&1 &)
    echo "ollama: started"
else
    echo "ollama: already running (pid $(pgrep -x ollama | head -1))"
fi

# Wait for Ollama to answer before starting OpenCode, so the model
# is reachable when the first prompt arrives. 20 s cap: if Ollama
# never answers, the stray is killed and the real in-guest server
# is started in its place, then waited on again.
if ! wait_ollama; then
    echo "ollama: NOT answering after 20 s, killing stray"
    pkill -x ollama 2>/dev/null
    i=1
    while [ "$i" -le 10 ] && pgrep -x ollama >/dev/null 2>&1; do sleep 1; i=$((i + 1)); done
    (OLLAMA_HOME=/root/.ollama OLLAMA_HOST=127.0.0.1:11434 ollama serve >"$MUDIF_HOME/ollama.log" 2>&1 &)
    echo "ollama: restarted in-guest"
    if ! wait_ollama; then
        echo "ollama: STILL not answering after restart (see $MUDIF_HOME/ollama.log)"
    fi
fi

# API keys for the cloud providers (written by the app from the
# settings screen into keys.env). Sourced so opencode inherits
# them; absent = local brain only.
if [ -f "$MUDIF_HOME/keys.env" ]; then
    set -a
    . "$MUDIF_HOME/keys.env"
    set +a
    echo "keys.env: loaded"
fi

# وكيل مضيف: طرفية الوكيل غير تفاعلية (لا إدخال فيها) وجملة
# المستخدم في الدردشة هي الموافقة، فأوامر التثبيت تقبل تلقائيا
# في صدفة الوكيل فقط. الطرفية البشرية (qterminal) لا ترى هذا
# المتغير أبدا، فتبقى تسأل. يسري بعد إعادة تشغيل opencode.
export MUDIF_AGENT=1

# OpenCode: the agent server. Flags proven on the rig
# (opencode serve listening on http://127.0.0.1:4096).
# p80: a STRAY opencode can own 4096 from OUTSIDE the guest
# (rig-proven p78: the server ran as uid 10087 with HOME=/root
# = the Android system root, "EACCES: permission denied, mkdir
# '/root'"; its instance store never booted, so every /session
# route returned UnknownError while the UI route still answered).
# pgrep alone cannot tell the stray from the good server, and
# the stray answers TCP and GET /, so a blind "already running"
# leaves the assistant permanently broken. Probe first: a valid
# server answers GET /session with HTTP 200 and no UnknownError
# within 20 s. Only a server that never answers validly is
# killed and replaced by the real in-guest server (cd /root pins
# the home to the guest /root, so the server can never land on
# the Android /root again). A good-but-slow server is never
# killed: it answers inside the 20 s wait and the kill path is
# never reached. The restart runs INSIDE this script, which the
# app runs through its persistent PTY session: a server started
# from a one-shot proot dies with the session (rig-proven p79:
# pid 4356 gone in <30 s), so the restart must never be a
# one-shot exec.
opencode_ok() {
    code=$(curl -s -o /tmp/opencode-session.json -w "%{http_code}" -m 5 http://127.0.0.1:4096/session 2>/dev/null)
    [ "$code" = "200" ] && ! grep -q "UnknownError" /tmp/opencode-session.json
}
wait_opencode() {
    i=1
    while [ "$i" -le 20 ]; do
        if opencode_ok; then
            echo "opencode: answering on 4096"
            return 0
        fi
        sleep 1
        i=$((i + 1))
    done
    return 1
}
if ! pgrep -f "opencode serve" >/dev/null 2>&1; then
    (cd /root && opencode serve --port 4096 --hostname 127.0.0.1 >"$MUDIF_HOME/opencode.log" 2>&1 &)
    echo "opencode: started"
else
    echo "opencode: already running (pid $(pgrep -f 'opencode serve' | head -1))"
fi
if ! wait_opencode; then
    echo "opencode: NOT answering /session after 20 s, killing stray"
    pkill -f "opencode serve" 2>/dev/null
    i=1
    while [ "$i" -le 10 ] && pgrep -f "opencode serve" >/dev/null 2>&1; do sleep 1; i=$((i + 1)); done
    (cd /root && opencode serve --port 4096 --hostname 127.0.0.1 >"$MUDIF_HOME/opencode.log" 2>&1 &)
    echo "opencode: restarted in-guest"
    if ! wait_opencode; then echo "opencode: STILL not answering after restart (see $MUDIF_HOME/opencode.log)"; fi
fi

echo "agent-start done"
