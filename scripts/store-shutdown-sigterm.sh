#!/usr/bin/env bash
# SIGTERM shutdown regression for the Datalevin shutdown-hook race.
#
# Starts a real app JVM in its own java.io.tmpdir, waits for the publisher's
# 500 ms tick, sends SIGTERM, and fails on a JVM crash or a new hs_err log.
# This is the path that JVM shutdown hooks exercise; an in-process
# `system/stop!` test does not run Datalevin's own hook concurrently.
set -u

cd "$(dirname "$0")/.."

PIXI_BIN="${PIXI_BIN:-/tmp/pixi}"
if [ -x .pixi/envs/default/bin/clojure ]; then
  # The repository environment is the real Pixi JDK 21.  A /tmp/pixi wrapper
  # may not support `which` or `-Spath`, so prefer the checkout.
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
ROUNDS="${ROUNDS:-5}"
LOG="${LOG:-/tmp/hc-store-shutdown-sigterm.log}"
JAVA="$(run which java)"
CP="$(run clojure -Spath -M:server)"

list_hs_err() {
  ls hs_err_pid*.log 2>/dev/null | sort
}

before="$(list_hs_err)"
before_count="$(printf '%s\n' "$before" | grep -c . || true)"

{
  echo "== SIGTERM shutdown regression =="
  echo "started: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  "$JAVA" -version
  echo "rounds:  $ROUNDS"
  echo "hs_err before: $before_count"
  echo
} > "$LOG"

failed=0
for i in $(seq 1 "$ROUNDS"); do
  data="$(mktemp -d /tmp/hc-sigterm-data-XXXXXX)"
  work="$(mktemp -d /tmp/hc-sigterm-work-XXXXXX)"
  tmp="$(mktemp -d /tmp/hc-sigterm-tmp-XXXXXX)"
  applog="$data/app.log"
  "$JAVA" -Xmx384m -XX:MaxDirectMemorySize=192m \
    -XX:MaxMetaspaceSize=160m -Xss512k \
    -XX:-OmitStackTraceInFastThrow -Djava.io.tmpdir="$tmp" \
    -cp "$CP" clojure.main -m hc.server.app \
    --host 127.0.0.1 --port 0 --data-dir "$data" \
    --workspace-root "$work" --static-root resources/public \
    --log-level warn > "$applog" 2>&1 &
  pid=$!
  ready=0
  # A cold Clojure start compiles the full server namespace tree; under a
  # loaded machine this can exceed twenty seconds.  Wait up to ninety.
  for _ in $(seq 1 450); do
    if grep -q "Herdchestra listening on" "$applog" 2>/dev/null; then
      ready=1
      break
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      break
    fi
    sleep 0.2
  done
  if [ "$ready" -ne 1 ]; then
    echo "round $i: app did not start (pid $pid)" | tee -a "$LOG"
    sed -n '1,40p' "$applog" | tee -a "$LOG"
    kill -TERM "$pid" 2>/dev/null
    wait "$pid" 2>/dev/null
    failed=1
    continue
  fi
  # Several publisher ticks read the baseline; send SIGTERM while the store
  # is still being read.
  sleep 1.2
  kill -TERM "$pid" 2>/dev/null
  wait "$pid"
  status=$?
  echo "round $i: pid=$pid exit=$status data=$data" | tee -a "$LOG"
  case "$status" in
    0|143) ;;
    *)
      echo "round $i: unexpected exit $status" | tee -a "$LOG"
      failed=1
      ;;
  esac
  rm -rf "$work" "$tmp"
done

after="$(list_hs_err)"
after_count="$(printf '%s\n' "$after" | grep -c . || true)"
new_logs="$(comm -13 <(printf '%s\n' "$before") <(printf '%s\n' "$after"))"

{
  echo
  echo "hs_err before: $before_count"
  echo "hs_err after:  $after_count"
  if [ -n "$new_logs" ]; then
    echo "new hs_err:"
    printf '%s\n' "$new_logs"
  else
    echo "new hs_err:    none"
  fi
} | tee -a "$LOG"

if [ -n "$new_logs" ]; then
  failed=1
fi

if [ "$failed" -ne 0 ]; then
  echo "FAIL: SIGTERM shutdown regression failed (log: $LOG)" >&2
  exit 1
fi

echo "PASS: $ROUNDS SIGTERM stops, no new hs_err (log: $LOG)"
