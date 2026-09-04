#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SERVICE="$REPO_ROOT/module/template/service.sh"
POST_FS="$REPO_ROOT/module/template/post-fs-data.sh"
TEST_ROOT=$(mktemp -d)
trap 'if [[ -n "${victim_job:-}" ]]; then kill "$victim_job" 2>/dev/null || true; wait "$victim_job" 2>/dev/null || true; fi; if [[ -n "${legacy_job:-}" ]]; then kill "$legacy_job" 2>/dev/null || true; wait "$legacy_job" 2>/dev/null || true; fi; rm -rf "$TEST_ROOT"' EXIT

sed -n '/^# BEGIN PID SAFETY HELPERS$/,/^# END PID SAFETY HELPERS$/p' "$SERVICE" > "$TEST_ROOT/helpers.sh"
sed -n '/^# BEGIN BOOT EPOCH HELPERS$/,/^# END BOOT EPOCH HELPERS$/p' "$POST_FS" > "$TEST_ROOT/boot-epoch-helpers.sh"
# shellcheck source=/dev/null
source "$TEST_ROOT/helpers.sh"
# shellcheck source=/dev/null
source "$TEST_ROOT/boot-epoch-helpers.sh"
log() { :; }
chown() { :; }
chcon() { :; }

# Persistent PID records are meaningful only within the boot that created them.
# A mismatched boot marker must discard every runtime PID record without signaling.
CONFIG_DIR="$TEST_ROOT/boot-config"
MODDIR="$TEST_ROOT/module"
CONFIG_ROOT_SAFE=true
mkdir -p "$CONFIG_DIR" "$MODDIR"
TEST_BOOT_ID=11111111-2222-3333-4444-555555555555
current_boot_id() { printf '%s' "$TEST_BOOT_ID"; }
printf '%s\n' 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee' > "$CONFIG_DIR/runtime.boot_id"
for record in supervisor.pid daemon.pid adapter.pid backend.pid; do
  printf '123 456\n' > "$CONFIG_DIR/$record"
done
printf '123 456\n' > "$MODDIR/supervisor.pid"
prepare_runtime_boot_epoch
for record in supervisor.pid daemon.pid adapter.pid backend.pid; do
  [[ ! -e "$CONFIG_DIR/$record" ]]
done
[[ ! -e "$MODDIR/supervisor.pid" ]]
[[ $(tr -d '\r\n' < "$CONFIG_DIR/runtime.boot_id") == "$TEST_BOOT_ID" ]]

# Same-boot restarts retain PID records so the pidfd cleanup below can stop owned processes.
printf '123 456\n' > "$CONFIG_DIR/daemon.pid"
prepare_runtime_boot_epoch
[[ -f "$CONFIG_DIR/daemon.pid" ]]
rm -f "$CONFIG_DIR/daemon.pid"

# Host-side shim for the production pidfd helper. It exercises shell control flow and
# identity policy; Rust unit/CI coverage validates the real pidfd syscalls.
signal_owned_process_original() {
  local target_pid=$1 expected_start=$2 signal_number=$3 expected_executable=$4 expected_comm=$5 expected_argument=$6
  local signal_pid=$target_pid
  if [[ "${FORCE_HELPER_FAILURE:-0}" == 1 ]]; then
    return 2
  fi
  if [[ "$expected_start" != "-" ]]; then
    local current_start
    current_start=$(process_start_ticks "$target_pid") || return 3
    [[ "$current_start" == "$expected_start" ]] || return 3
  fi
  if [[ -n "$expected_executable" ]]; then
    local actual_executable
    actual_executable=$(readlink "/proc/$target_pid/exe" 2>/dev/null) || return 3
    [[ "$actual_executable" == "$expected_executable" || "$actual_executable" == "$expected_executable (deleted)" ]] || return 3
  fi
  if [[ -n "$expected_comm" ]]; then
    local actual_comm
    actual_comm=$(tr -d '\r\n' < "/proc/$target_pid/comm" 2>/dev/null) || return 3
    [[ "$actual_comm" == "$expected_comm" ]] || return 3
  fi
  if [[ -n "$expected_argument" ]]; then
    tr '\000' '\n' < "/proc/$target_pid/cmdline" 2>/dev/null | grep -F -x -- "$expected_argument" >/dev/null || return 3
  fi
  # Some test sandboxes expose host PIDs through procfs while shell signals use a nested PID namespace.
  if [[ "${victim_pid:-}" == "$target_pid" && -n "${victim_namespace_pid:-}" ]]; then
    signal_pid=$victim_namespace_pid
  elif [[ "${legacy_pid:-}" == "$target_pid" && -n "${legacy_namespace_pid:-}" ]]; then
    signal_pid=$legacy_namespace_pid
  fi
  if [[ "$signal_number" == 0 ]]; then
    [[ -r "/proc/$target_pid/stat" ]] || return 3
  else
    kill -"$signal_number" "$signal_pid" 2>/dev/null || return 3
  fi
}

SIGNAL_CALLS=0
signal_owned_process() {
  SIGNAL_CALLS=$((SIGNAL_CALLS + 1))
  signal_owned_process_original "$@"
}

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

printf '%s \n' "$victim_pid" > "$pid_file"
terminate_pid "$pid_file" "test" 1 "$victim_executable" "" ""
kill -0 "$victim_job"
[[ ! -e "$pid_file" ]]
[[ "$SIGNAL_CALLS" -eq 0 ]]

printf ' %s\n' "$victim_start" > "$pid_file"
terminate_pid "$pid_file" "test" 1 "$victim_executable" "" ""
kill -0 "$victim_job"
[[ ! -e "$pid_file" ]]
[[ "$SIGNAL_CALLS" -eq 0 ]]

invalid_calls=$SIGNAL_CALLS
for invalid_record in '2147483648' "2147483648 $victim_start" '0000000001'; do
  printf '%s\n' "$invalid_record" > "$pid_file"
  terminate_pid "$pid_file" "test" 1 "$victim_executable" "" ""
  kill -0 "$victim_job"
  [[ ! -e "$pid_file" ]]
  [[ "$SIGNAL_CALLS" -eq "$invalid_calls" ]]
done

printf '%s %s\n' "$victim_pid" "$((victim_start + 1))" > "$pid_file"
terminate_pid "$pid_file" "test" 1 "$victim_executable" "" ""
kill -0 "$victim_job"
[[ ! -e "$pid_file" ]]

printf '%s %s\n' "$victim_pid" "$victim_start" > "$pid_file"
terminate_pid "$pid_file" "test" 1 "$TEST_ROOT/not-the-process" "" ""
kill -0 "$victim_job"
[[ ! -e "$pid_file" ]]

outside="$TEST_ROOT/outside"
printf 'preserve\n' > "$outside"
ln -s "$outside" "$pid_file"
terminate_pid "$pid_file" "test" 1 "$victim_executable" "" ""
[[ $(< "$outside") == preserve ]]
kill -0 "$victim_job"

printf '%s %s\n' "$victim_pid" "$victim_start" > "$pid_file"
FORCE_HELPER_FAILURE=1
if terminate_pid "$pid_file" "test" 1 "$victim_executable" "" ""; then
  echo 'terminate_pid unexpectedly ignored helper failure' >&2
  exit 1
fi
unset FORCE_HELPER_FAILURE
kill -0 "$victim_job"
[[ -f "$pid_file" ]]

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

python3 -c 'import os, pathlib, time; pathlib.Path(__import__("sys").argv[1]).write_text(str(os.getpid())); time.sleep(30)' "$TEST_ROOT/legacy-victim.pid" &
legacy_job=$!
for _ in {1..50}; do
  [[ -s "$TEST_ROOT/legacy-victim.pid" ]] && break
  sleep 0.01
done
legacy_namespace_pid=$(< "$TEST_ROOT/legacy-victim.pid")
legacy_pid=$legacy_namespace_pid
if [[ ! -r "/proc/$legacy_pid/stat" ]]; then
  for process_dir in /proc/[0-9]*; do
    if tr '\000' '\n' < "$process_dir/cmdline" 2>/dev/null | grep -F -x -- "$TEST_ROOT/legacy-victim.pid" >/dev/null; then
      legacy_pid=${process_dir##*/}
      break
    fi
  done
fi
legacy_executable=$(readlink "/proc/$legacy_pid/exe")

# A stale/mismatched legacy record can be discarded after a non-destructive pidfd probe.
printf '%s\n' "$legacy_pid" > "$pid_file"
terminate_pid "$pid_file" "legacy-test" 1 "$TEST_ROOT/not-the-process" "" ""
kill -0 "$legacy_job"
[[ ! -e "$pid_file" ]]

# A matching PID-only record is still ambiguous: PID reuse can produce the same process
# shape. Preserve the record and fail closed instead of sending TERM/KILL to that occupant.
printf '%s\n' "$legacy_pid" > "$pid_file"
if terminate_pid "$pid_file" "legacy-test" 1 "$legacy_executable" "" ""; then
  echo 'terminate_pid unexpectedly signaled an ambiguous legacy PID-only record' >&2
  exit 1
fi
kill -0 "$legacy_job"
[[ -f "$pid_file" ]]
rm -f "$pid_file"
kill "$legacy_job" 2>/dev/null || true
wait "$legacy_job" 2>/dev/null || true
legacy_pid=
legacy_job=

grep -q 'CLEVERES_TRICKY_PIDFD_MODE=support' "$SERVICE"
grep -q 'CLEVERES_TRICKY_PIDFD_MODE=signal' "$SERVICE"
awk '
  /^# END BOOT EPOCH HELPERS$/ { after_helpers = 1; next }
  after_helpers && /^[[:space:]]*prepare_runtime_boot_epoch[[:space:]]*$/ { found = 1 }
  END { exit !found }
' "$POST_FS"
if grep -E 'kill[[:space:]].*\$(old_pid|supervisor_pid)' "$SERVICE" >/dev/null; then
  echo 'raw PID kill reintroduced into service supervisor' >&2
  exit 1
fi

config_check=$(grep -n '^if \[ -L "\$CONFIG_DIR" \]; then' "$SERVICE" | head -n1 | cut -d: -f1)
termination=$(grep -n '^if ! terminate_previous_instances; then' "$SERVICE" | head -n1 | cut -d: -f1)
[[ -n "$config_check" && -n "$termination" && "$config_check" -lt "$termination" ]]

printf '%s\n' 'service PID identity security tests passed'
