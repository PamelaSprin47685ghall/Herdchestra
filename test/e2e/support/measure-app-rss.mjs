// Measure the peak RSS of one Herdchestra app JVM (the process tree rooted
// at the clojure launcher).  Usage:
//   node test/e2e/support/measure-app-rss.mjs [seconds]
// Prints VmHWM/VmRSS samples and the peak in MiB; exits non-zero above 512.
import { execFileSync } from 'node:child_process';
import { startApp } from './app.mjs';
import fs from 'node:fs';

const WINDOW_MS = Number(process.argv[2] ?? 20) * 1000;
const LIMIT_KIB = 512 * 1024;

function vmField(pid, field) {
  try {
    const status = fs.readFileSync(`/proc/${pid}/status`, 'utf8');
    const m = status.match(new RegExp(`^${field}:\\s+(\\d+) kB`, 'm'));
    return m ? Number(m[1]) : 0;
  } catch {
    try {
      const out = execFileSync('ps', ['-o', 'rss=', '-p', String(pid)], { encoding: 'utf8' });
      const n = Number(out.trim());
      return Number.isFinite(n) && n > 0 ? n : 0;
    } catch {
      return 0;
    }
  }
}

// Sum VmHWM over the launcher pid and every java descendant (clojure -M
// spawns the JVM as a child of the launcher shell script).
function treePids(rootPid) {
  const pids = [rootPid];
  const children = new Map();
  if (fs.existsSync('/proc')) {
    for (const entry of fs.readdirSync('/proc')) {
      if (!/^\d+$/.test(entry)) continue;
      try {
        const stat = fs.readFileSync(`/proc/${entry}/stat`, 'utf8');
        const ppid = Number(stat.slice(stat.lastIndexOf(')') + 2).split(' ')[1]);
        if (!children.has(ppid)) children.set(ppid, []);
        children.get(ppid).push(Number(entry));
      } catch { /* process exited */ }
    }
  } else {
    try {
      const out = execFileSync('ps', ['-axo', 'pid=,ppid='], { encoding: 'utf8' });
      for (const line of out.split('\n')) {
        const m = line.trim().match(/^(\d+)\s+(\d+)$/);
        if (!m) continue;
        const pid = Number(m[1]), ppid = Number(m[2]);
        if (!children.has(ppid)) children.set(ppid, []);
        children.get(ppid).push(pid);
      }
    } catch { /* ignore */ }
  }
  const queue = [rootPid];
  while (queue.length) {
    const pid = queue.shift();
    for (const child of children.get(pid) || []) {
      pids.push(child);
      queue.push(child);
    }
  }
  return pids;
}

const app = await startApp({ healthTimeoutMs: 120000 });
let peakKib = 0;
let peakRssKib = 0;
const deadline = Date.now() + WINDOW_MS;
while (Date.now() < deadline) {
  let hwm = 0;
  let rss = 0;
  for (const pid of treePids(app.pid)) {
    hwm += vmField(pid, 'VmHWM');
    rss += vmField(pid, 'VmRSS');
  }
  if (hwm > peakKib) peakKib = hwm;
  if (rss > peakRssKib) peakRssKib = rss;
  await new Promise((r) => setTimeout(r, 500));
}
await app.close();

const mib = (kib) => (kib / 1024).toFixed(1);
console.log(JSON.stringify({
  pid: app.pid,
  peakVmHWM_MiB: mib(peakKib),
  peakVmRSS_MiB: mib(peakRssKib),
  limit_MiB: 512,
  ok: peakKib <= LIMIT_KIB,
}));
process.exit(peakKib <= LIMIT_KIB ? 0 : 1);
