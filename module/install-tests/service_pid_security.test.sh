#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SERVICE="$REPO_ROOT/module/template/service.sh"
TEST_ROOT=$(mktemp -d)
trap 'if [[ -n "${victim_job:-}" ]]; then kill "$victim_job" 2>/dev/null || true; wait "$victim_job" 2>/dev/null || true; fi; rm -rf "$TEST_ROOT"' EXIT

sed -n '/^# BEGIN PID SAFETY HELPERS$/,/^# END PID SAFETY HELPERS$/p' "$SERVICE" > "$TEST_ROOT/helpers.sh"
# shellcheck source=/dev/null
source "$TEST_ROOT/helpers.sh"
log() { :; }

python3 -c 'import os, pathlib, time; pathlib.Path(__import__("sys").argv[1]).write_text(str(os.getpid())); time.sleep(30)' "$TEST_ROOT/victim.pid" &
victim_job=$!
for _ in {1..50}; do
  [[ -s "$TEST_ROOT/victim.pid" ]] && break
  sleep 0.01
done
victim_namespace_pid=$(< "$TEST_ROOT/victim.pid")
victim_pid=$victim_namespace_pid
if [[ ! -r "/proc/$victim_pid/stat" ]]; then
  for process_dir in /proc/[0-9]*; do
    if tr '\000' '\n' < "$process_dir/cmdline" 2>/dev/null | grep -F -x -- "$TEST_ROOT/victim.pid" >/dev/null; then
      victim_pid=${process_dir##*/}
      break
    fi
  done
fi
victim_start=
for _ in {1..50}; do
  victim_start=$(process_start_ticks "$victim_pid") || victim_start=
  [[ -n "$victim_start" ]] && break
  sleep 0.01
done
[[ -n "$victim_start" ]]
victim_executable=$(readlink "/proc/$victim_pid/exe")
pid_file="$TEST_ROOT/runtime.pid"

printf '%s %s\n' "$victim_pid" "$((victim_start + 1))" > "$pid_file"
terminate_pid "$pid_file" "test" 1 "$victim_executable" "" ""
kill -0 "$victim_job"

printf '%s %s\n' "$victim_pid" "$victim_start" > "$pid_file"
terminate_pid "$pid_file" "test" 1 "$TEST_ROOT/not-the-process" "" ""
kill -0 "$victim_job"

outside="$TEST_ROOT/outside"
printf 'preserve\n' > "$outside"
ln -s "$outside" "$pid_file"
terminate_pid "$pid_file" "test" 1 "$victim_executable" "" ""
[[ $(< "$outside") == preserve ]]
kill -0 "$victim_job"

printf '%s %s\n' "$victim_pid" "$victim_start" > "$pid_file"
terminate_pid "$pid_file" "test" 1 "$victim_executable" "" ""
if [[ "$victim_pid" == "$victim_namespace_pid" ]]; then
  wait "$victim_job" 2>/dev/null || true
else
  kill "$victim_job" 2>/dev/null || true
  wait "$victim_job" 2>/dev/null || true
fi
victim_pid=
victim_job=
[[ ! -e "$pid_file" ]]

config_check=$(grep -n '^if \[ -L "\$CONFIG_DIR" \]; then' "$SERVICE" | head -n1 | cut -d: -f1)
termination=$(grep -n '^terminate_previous_instances$' "$SERVICE" | head -n1 | cut -d: -f1)
[[ -n "$config_check" && -n "$termination" && "$config_check" -lt "$termination" ]]

printf '%s\n' 'service PID identity security tests passed'
