#!/system/bin/sh

PORT_FILE="/data/adb/cleverestricky/web_port"
HOST="127.0.0.1"
# Wait up to 10 seconds so slower boots have time to finish binding the
# loopback WebUI socket before the browser intent is fired.
MAX_WAIT_SECONDS=10
# FLAG_ACTIVITY_NEW_TASK (0x10000000) is required because this intent is
# launched from the module shell action, not from an Activity context.

if [ ! -f "$PORT_FILE" ]; then
  echo "! Web server port file not found. Is the module running?"
  exit 1
fi

IFS= read -r CONTENT < "$PORT_FILE"
PORT=${CONTENT%|*}
TOKEN=${CONTENT#*|}

if [ -z "$PORT" ] || [ -z "$TOKEN" ]; then
    echo "! Invalid port file content."
    exit 1
fi

case "$PORT" in
  ''|*[!0-9]*)
    echo "! Invalid WebUI port: $PORT"
    exit 1
    ;;
esac

if [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then
  echo "! WebUI port out of range: $PORT"
  exit 1
fi

case "$TOKEN" in
  *[!A-Za-z0-9_-]*)
    echo "! Invalid WebUI token."
    exit 1
    ;;
esac
if [ "${#TOKEN}" -lt 32 ] || [ "${#TOKEN}" -gt 128 ]; then
  echo "! Invalid WebUI token length."
  exit 1
fi

URL="http://$HOST:$PORT/?token=$TOKEN"

echo "- Waiting for WebUI to listen on $HOST:$PORT"
READY=0
ATTEMPT=0
while [ "$ATTEMPT" -lt "$MAX_WAIT_SECONDS" ]; do
  if [ -f "/proc/net/tcp" ] && grep -q -i ":$(printf '%04X' "$PORT") " /proc/net/tcp; then
    READY=1
    break
  elif [ -f "/proc/net/tcp6" ] && grep -q -i ":$(printf '%04X' "$PORT") " /proc/net/tcp6; then
    READY=1
    break
  elif command -v nc >/dev/null 2>&1 && nc -z -w 1 "$HOST" "$PORT" >/dev/null 2>&1; then
    READY=1
    break
  fi
  ATTEMPT=$((ATTEMPT + 1))
  sleep 1
done

if [ "$READY" -ne 1 ]; then
  echo "! WebUI did not report ready within ${MAX_WAIT_SECONDS}s; launching browser anyway for debugging"
  log -t CleveresTricky "WebUI readiness probe timed out on $HOST:$PORT"
fi

echo "- Opening WebUI"
START_OUTPUT=$(am start -W -f 0x10000000 -a android.intent.action.VIEW -d "$URL" 2>&1)
START_EXIT=$?

case "$START_OUTPUT" in
  *ActivityNotFoundException*|*unable\ to\ resolve\ Intent*)
    BROWSER_ERROR=1
    ;;
  *)
    BROWSER_ERROR=0
    ;;
esac

# Activity manager can also report "unable to resolve Intent" when no browser
# is available to handle the ACTION_VIEW launch.

if [ "$START_EXIT" -ne 0 ]; then
  echo "! Failed to launch WebUI intent (exit $START_EXIT)"
  log -t CleveresTricky "WebUI launch failed with exit $START_EXIT"
  if [ "$BROWSER_ERROR" -eq 1 ]; then
    echo "! No browser is installed to handle the WebUI link."
  fi
  exit "$START_EXIT"
fi

if [ "$BROWSER_ERROR" -eq 1 ]; then
  echo "! No browser is installed to handle the WebUI link."
  log -t CleveresTricky "WebUI launch failed: no browser handler"
  exit 1
fi

log -t CleveresTricky "WebUI launch intent started"
