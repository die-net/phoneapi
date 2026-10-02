#!/usr/bin/env bash
# Mints a PhoneAPI token over ADB and prints the pairing JSON (host, port, certSha256,
# spkiSha256, token, name) on stdout. Needs only adb; the phone must have USB debugging on.
# No approval on the phone is needed: ADB access already proves you control it.
#
#   scripts/pair-adb.sh [--name NAME] [--forward] > pairing.json
#
# The printed JSON points at http://127.0.0.1 after `adb forward` onto the app's abstract socket.
# --forward also sets the optional HTTPS listener to all interfaces. The adb client still uses HTTP.
# Defaults to the debug application id (net.die.phoneapi.dev). For a release install:
#   PHONEAPI_PKG=net.die.phoneapi scripts/pair-adb.sh
set -euo pipefail

PKG="${PHONEAPI_PKG:-net.die.phoneapi.dev}"
RECEIVER="$PKG/net.die.phoneapi.ShellCommandReceiver"
name="$(hostname -s 2>/dev/null || hostname)"
forward=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --name) name="${2:?--name needs a value}"; shift 2 ;;
    --forward) forward=1; shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
# adb shell joins arguments into one remote command line, so keep the name shell-safe.
name="${name//[^A-Za-z0-9._-]/-}"

broadcast() {
  adb shell am broadcast -n "$RECEIVER" "$@" | tr -d '\r'
}

if [[ $forward == 1 ]]; then
  broadcast -a net.die.phoneapi.SET_BIND --es mode ALL >/dev/null
  sleep 1
fi

out="$(broadcast -a net.die.phoneapi.CREATE_TOKEN --es name "$name")"
json="$(sed -n 's/.*result=0, data="\(.*\)"$/\1/p' <<<"$out")"
if [[ -z "$json" ]]; then
  echo "CREATE_TOKEN failed. Is PhoneAPI installed as $PKG? adb said: $out" >&2
  exit 1
fi
port="$(sed -n 's/.*"port":\([0-9]*\).*/\1/p' <<<"$json")"
if [[ -z "$port" ]]; then
  echo "CREATE_TOKEN returned no port: $json" >&2
  exit 1
fi

adb forward "tcp:$port" "localabstract:$PKG" >/dev/null
json="$(sed -e 's/"host":"[^"]*"/"host":"127.0.0.1"/' -e 's/^{/{"scheme":"http",/' <<<"$json")"

printf '%s\n' "$json"
echo "PhoneAPI at http://127.0.0.1:$port (MCP: /mcp). The token is in the JSON above." >&2
