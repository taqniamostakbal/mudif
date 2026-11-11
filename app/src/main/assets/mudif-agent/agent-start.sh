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
if ! pgrep -x ollama >/dev/null 2>&1; then
    (OLLAMA_HOST=127.0.0.1:11434 ollama serve >"$MUDIF_HOME/ollama.log" 2>&1 &)
    echo "ollama: started"
else
    echo "ollama: already running (pid $(pgrep -x ollama | head -1))"
fi

# Wait for Ollama to answer before starting OpenCode, so the model
# is reachable when the first prompt arrives. 20 s cap: if Ollama
# never answers, OpenCode still starts and reports the real error
# on the first prompt instead of hanging the chat.
i=1
while [ "$i" -le 20 ]; do
    if curl -fsS http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
        echo "ollama: answering on 11434"
        break
    fi
    sleep 1
    i=$((i + 1))
done
if [ "$i" -gt 20 ]; then
    echo "ollama: NOT answering after 20 s (see $MUDIF_HOME/ollama.log)"
fi

# OpenCode: the agent server. Flags proven on the rig
# (opencode serve listening on http://127.0.0.1:4096).
if ! pgrep -f "opencode serve" >/dev/null 2>&1; then
    (cd /root && opencode serve --port 4096 --hostname 127.0.0.1 >"$MUDIF_HOME/opencode.log" 2>&1 &)
    echo "opencode: started"
else
    echo "opencode: already running (pid $(pgrep -f 'opencode serve' | head -1))"
fi

echo "agent-start done"
