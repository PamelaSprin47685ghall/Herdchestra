#!/usr/bin/env bash
set -euo pipefail
cd /home/kunweiz/Desktop/vibe/Herdchestra
mkdir -p /tmp/hc-visual-board/shots
cp resources/public/css/*.css /tmp/hc-visual-board/fixture-public/css/
cp resources/public/index.html /tmp/hc-visual-board/fixture-public/index.html
sed -i 's/<title>Herdchestra 工作台<\/title>/<title>Herdchestra visual fixture<\/title>/' /tmp/hc-visual-board/fixture-public/index.html
python3 -m http.server 7412 --bind 127.0.0.1 --directory /tmp/hc-visual-board/fixture-public > /tmp/hc-visual-board/static.log 2>&1 &
STATIC=$!
rm -rf /tmp/hc-visual-board/data /tmp/hc-visual-board/work /tmp/hc-visual-board/tmp
mkdir -p /tmp/hc-visual-board/data /tmp/hc-visual-board/work /tmp/hc-visual-board/tmp
export JAVA_TOOL_OPTIONS="-Xmx768m -XX:MaxDirectMemorySize=512m -Djava.io.tmpdir=/tmp/hc-visual-board/tmp"
.pixi/envs/default/bin/clojure -M:server -m hc.server.app --host 127.0.0.1 --port 7411 --data-dir /tmp/hc-visual-board/data --workspace-root /tmp/hc-visual-board/work --static-root resources/public > /tmp/hc-visual-board/app.log 2>&1 &
APP=$!
cleanup() { kill "$APP" "$STATIC" 2>/dev/null || true; wait "$APP" "$STATIC" 2>/dev/null || true; }
trap cleanup EXIT
for i in $(seq 1 120); do curl -sf http://127.0.0.1:7411/healthz >/dev/null && break; sleep 1; done
curl -sf http://127.0.0.1:7411/healthz || { tail -80 /tmp/hc-visual-board/app.log; exit 1; }
curl -sf http://127.0.0.1:7412/index.html >/dev/null
echo "APP_PID=$APP STATIC_PID=$STATIC"
ps -o pid,ppid,rss,etime,args -p "$APP" | tail -1
jcmd "$APP" VM.flags | head -6
jcmd "$APP" GC.heap_info | head -6
NODE_PATH=/tmp/hc-pw/node_modules node /tmp/hc-visual-board/verify-all.cjs
CHROME=$(cat /tmp/hc-visual-board/chrome.pid 2>/dev/null || true)
if [ -n "$CHROME" ]; then echo "CHROME_RSS"; ps -o pid,ppid,rss,etime,args -p "$CHROME" | tail -1 || true; fi
