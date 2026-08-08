#!/system/bin/sh
# shellcheck disable=SC2034
DEBUG=@DEBUG@

MODDIR=${0%/*}

(
retry_delay=2
max_retry_delay=60
stable_runtime=120

while true; do
  chcon u:object_r:system_file:s0 "$MODDIR/daemon" 2>/dev/null
  chcon u:object_r:system_file:s0 "$MODDIR/inject" 2>/dev/null
  find "$MODDIR" -maxdepth 1 -type f \( -name '*.apk' -o -name '*.so' \) \
    -exec chcon u:object_r:system_file:s0 {} + 2>/dev/null

  started_at=$(date +%s)
  "$MODDIR/daemon"
  exit_code=$?
  stopped_at=$(date +%s)
  runtime=$((stopped_at - started_at))

  if [ "$runtime" -ge "$stable_runtime" ]; then
    retry_delay=2
  fi

  log -t CleveresTricky \
    "Daemon exited with code $exit_code after ${runtime}s; retrying in ${retry_delay}s"
  sleep "$retry_delay"

  if [ "$runtime" -lt "$stable_runtime" ] && [ "$retry_delay" -lt "$max_retry_delay" ]; then
    retry_delay=$((retry_delay * 2))
    [ "$retry_delay" -gt "$max_retry_delay" ] && retry_delay=$max_retry_delay
  fi
done
) &
