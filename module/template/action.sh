#!/system/bin/sh

PORT_FILE="/data/adb/cleverestricky/web_port"

if [ ! -f "$PORT_FILE" ]; then
  echo "! Web server port file not found. Is the module running?"
  exit 1
fi

CONTENT=$(cat "$PORT_FILE")
PORT=${CONTENT%|*}
TOKEN=${CONTENT#*|}

if [ -z "$PORT" ] || [ -z "$TOKEN" ]; then
    echo "! Invalid port file content."
    exit 1
fi

URL="http://localhost:$PORT/?token=$TOKEN"

echo "- Opening WebUI at $URL"
am start -a android.intent.action.VIEW -d "$URL"
