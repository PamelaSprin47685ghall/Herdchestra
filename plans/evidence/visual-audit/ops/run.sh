#!/usr/bin/env bash
set -u
cd /home/kunweiz/Desktop/vibe/Herdchestra
rm -rf /tmp/hc-visual-ops/data /tmp/hc-visual-ops/work
mkdir -p /tmp/hc-visual-ops/data /tmp/hc-visual-ops/work /tmp/hc-visual-ops/tmp
LOG=/tmp/hc-visual-ops/app.log
: > "$LOG"
JAVA_TOOL_OPTIONS="-Xmx768m -XX:MaxDirectMemorySize=512m -Djava.io.tmpdir=/tmp/hc-visual-ops/tmp" /tmp/pixi run clojure -M:server -m hc.server.app --host 127.0.0.1 --port 0 \
  --data-dir /tmp/hc-visual-ops/data --workspace-root /tmp/hc-visual-ops/work \
  --static-root resources/public >"$LOG" 2>&1 &
APP_PID=$!
for i in $(seq 1 180); do
  if grep -q "listening on" "$LOG"; then break; fi
  if ! kill -0 $APP_PID 2>/dev/null; then echo "APP DIED"; cat "$LOG"; exit 1; fi
  sleep 1
done
URL=$(grep -o "http://127.0.0.1:[0-9]*" "$LOG" | head -1)
echo "URL=$URL"
APP_JAVA_PID=$(pgrep -f "hc.server.app.*hc-visual-ops" | head -1)
echo "APP_JAVA_PID=$APP_JAVA_PID"
{
  echo "start $(date -Is) app_pid=$APP_JAVA_PID"
  ps -o pid=,rss=,vsz=,cmd= -p "$APP_JAVA_PID" 2>/dev/null | cut -c1-220
  jcmd "$APP_JAVA_PID" VM.flags 2>/dev/null | head -3
  jcmd "$APP_JAVA_PID" GC.heap_info 2>/dev/null | head -12
} | tee /tmp/hc-visual-ops/memory-start.txt
if [ -z "$URL" ]; then echo "NO URL"; cat "$LOG"; kill $APP_PID 2>/dev/null; exit 1; fi
export BASE_URL="$URL"
export NODE_PATH=/tmp/hc-pw/node_modules
timeout 480 node "$1" "${@:2}"
STATUS=$?
{
  echo "end $(date -Is)"
  ps -o pid=,rss=,vsz= -p "$APP_JAVA_PID" 2>/dev/null
  jcmd "$APP_JAVA_PID" GC.heap_info 2>/dev/null | head -12
} | tee /tmp/hc-visual-ops/memory-end.txt
if [ -n "${APP_JAVA_PID:-}" ]; then
  kill "$APP_JAVA_PID" 2>/dev/null
  for i in $(seq 1 20); do kill -0 "$APP_JAVA_PID" 2>/dev/null || break; sleep 1; done
  kill -9 "$APP_JAVA_PID" 2>/dev/null
fi
kill $APP_PID 2>/dev/null
wait $APP_PID 2>/dev/null
sleep 2
echo "leftover_ppid1:" | tee -a /tmp/hc-visual-ops/memory-end.txt
ps -eo pid=,ppid=,rss=,cmd= | awk '$2==1 && /hc.server.app/ {print}' | cut -c1-220 | tee -a /tmp/hc-visual-ops/memory-end.txt
exit $STATUS
