#!/usr/bin/env bash
# Repeated start/stop regression for the Datalevin use-after-close fix.
#
# Runs the real application twenty times under Pixi JDK 21, keeps a direct
# publisher reader busy at each stop, and fails when the test fails or when a
# new hs_err_pid*.log appears.  The test log stays at $LOG for inspection.
set -u

cd "$(dirname "$0")/.."

PIXI_BIN="${PIXI_BIN:-/tmp/pixi}"
if [ -x .pixi/envs/default/bin/clojure ]; then
  PATH="$PWD/.pixi/envs/default/bin:$PATH"
  export PATH
  run() { "$@"; }
elif [ -x "$PIXI_BIN" ]; then
  run() { "$PIXI_BIN" run "$@"; }
elif command -v pixi >/dev/null 2>&1; then
  run() { pixi run "$@"; }
else
  echo "no Pixi environment found (set PIXI_BIN)" >&2
  exit 1
fi
LOG="${LOG:-/tmp/hc-store-shutdown-stress.log}"

list_hs_err() {
  ls hs_err_pid*.log 2>/dev/null | sort
}

before="$(list_hs_err)"
before_count="$(printf '%s\n' "$before" | grep -c . || true)"

{
  echo "== store shutdown stress =="
  echo "started: $(date -Is)"
  echo "runner:  ${PIXI_BIN:-repository .pixi}"
  run java -version
  echo "hs_err before: $before_count"
  echo
} > "$LOG"

run clojure -J-Xmx1g -J-XX:MaxDirectMemorySize=512m \
  -M:test:compose:server:client \
  -n hc.server.app.store-shutdown-stress-test >> "$LOG" 2>&1
status=$?

after="$(list_hs_err)"
after_count="$(printf '%s\n' "$after" | grep -c . || true)"
new_logs="$(comm -13 <(printf '%s\n' "$before") <(printf '%s\n' "$after"))"

{
  echo
  echo "test exit:    $status"
  echo "hs_err before: $before_count"
  echo "hs_err after:  $after_count"
  if [ -n "$new_logs" ]; then
    echo "new hs_err:"
    printf '%s\n' "$new_logs"
  else
    echo "new hs_err:    none"
  fi
} | tee -a "$LOG"

if [ "$status" -ne 0 ]; then
  echo "FAIL: the stress test exited $status (log: $LOG)" >&2
  exit "$status"
fi

if [ -n "$new_logs" ]; then
  echo "FAIL: new JVM crash log appeared during the stress run (log: $LOG)" >&2
  exit 1
fi

echo "PASS: 20 start/stop iterations, no new hs_err (log: $LOG)"
