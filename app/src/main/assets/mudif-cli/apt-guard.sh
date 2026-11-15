# mudif-apt-guard v1
# Guard wrapper for /usr/bin/apt, /usr/bin/apt-get and /usr/bin/dpkg
# (p51). The real binary lives at /usr/bin/<name>.mudif-real.
# Installed by AgentServer.installAptGuard; the first line must stay
# this marker comment (the installer and the rig check read head -1),
# so there is no shebang line: shells and glibc execvp run a
# shebang-less script with /bin/sh, and every apt caller in this
# system (store, agent bash tool, human terminal) goes through a
# shell. POSIX sh only, no bashisms.
#
# When MUDIF_AGENT is not "1", every call passes straight through
# (store installs, system update, human terminal: never gated).
# Under the agent (MUDIF_AGENT=1) only read-only subcommands pass
# without a token; everything else needs a fresh one-shot token
# from the Arabic CLI verb (see arabic-cli.sh _apt-allow-issue).
# The refusal happens before the real binary runs, so no partial
# install can happen.

name=$(basename "$0")
real="/usr/bin/$name.mudif-real"

# Not the agent: pass everything through unchanged.
if [ "$MUDIF_AGENT" != "1" ]; then
  exec "$real" "$@"
fi

# Agent: find the subcommand. --version/--help are actions even
# though they start with -. Other leading options (-y, -q, --yes)
# are skipped. dpkg subcommands start with -, so the first argument
# is the subcommand.
sub=""
for arg in "$@"; do
  case "$arg" in
    --version|--help) sub="$arg"; break ;;
    -*) if [ "$name" = "dpkg" ]; then sub="$arg"; break; fi ;;
    *) sub="$arg"; break ;;
  esac
done

# Read-only subcommands pass through without a token. clean and
# autoclean are here on purpose: they only clear the package cache
# and touch no installed package (design 2.4: apt clean is not in
# the mutating set, and نظف-الكاش is unchanged, so it must pass).
case "$name:$sub" in
  apt:update|apt:list|apt:show|apt:search|apt:policy|apt:check|apt:clean|apt:autoclean|apt:--version|apt:--help|\
  apt-get:update|apt-get:list|apt-get:show|apt-get:search|apt-get:policy|apt-get:check|apt-get:clean|apt-get:autoclean|apt-get:--version|apt-get:--help|\
  dpkg:-l|dpkg:-s|dpkg:-L|dpkg:--print-architecture|dpkg:--configure)
    exec "$real" "$@"
    ;;
esac

# Everything else under the agent needs a fresh token: the file
# /root/.config/mudif/.apt-token holds "TOKEN EPOCH", and the
# environment variable must match it with an epoch under 300 s old.
token_file="/root/.config/mudif/.apt-token"
tok=""
epoch=""
if [ -f "$token_file" ]; then
  read -r tok epoch < "$token_file"
fi
now=$(date +%s)
case "$epoch" in
  ''|*[!0-9]*) epoch="" ;;
esac
if [ -n "$MUDIF_APT_TOKEN" ] && [ -n "$tok" ] && [ "$MUDIF_APT_TOKEN" = "$tok" ] && [ -n "$epoch" ]; then
  age=$((now - epoch))
  if [ "$age" -ge 0 ] && [ "$age" -lt 300 ]; then
    exec "$real" "$@"
  fi
fi
printf '%s\n' "التثبيت المباشر ممنوع. استعمل ثبت <اسم البرنامج>." >&2
exit 1
