// All three real-host smokes, in parallel.
//
// Each child owns its own isolated root and its own provider session, so the
// three runs share nothing but the machine. The parent prints one line per
// host — wall time and verdict — and exits nonzero when any child did.
//
// `--only omp,opencode` narrows the set; unknown names are an error rather than
// silently dropped, so a typo cannot pass as a green run. `--keep` and
// `--verbose` are forwarded to every child; SIGINT/SIGTERM stop the children
// with their groups instead of leaving hosts and temp roots behind.

import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

import { parseArgs } from "./lib.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const HOSTS = Object.freeze(["omp", "opencode", "cline"]);
const children = new Set();

const runOne = (host, args) =>
  new Promise((resolve) => {
    const started = performance.now();
    const childArgs = [path.join(HERE, `${host}.mjs`)];
    if (args.keep) childArgs.push("--keep");
    if (args.verbose) childArgs.push("--verbose");
    const child = spawn(process.execPath, childArgs, { stdio: ["ignore", "pipe", "pipe"], detached: true });
    children.add(child);
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("close", (code, signal) => {
      children.delete(child);
      resolve({ host, code, signal, stdout, stderr, ms: performance.now() - started });
    });
  });

const stopChildren = (signal) => {
  for (const child of children) {
    try {
      process.kill(-child.pid, signal);
    } catch {
      /* already gone */
    }
  }
};

let stopping = false;
const stop = (code) => {
  if (stopping) return;
  stopping = true;
  if (children.size === 0) process.exit(code);
  stopChildren("SIGTERM");
  // A child that ignores SIGTERM must not keep its host group alive after the
  // parent is gone; the second signal is the backstop.
  setTimeout(() => {
    stopChildren("SIGKILL");
    process.exit(code);
  }, 3_000);
};

process.on("SIGINT", () => stop(130));
process.on("SIGTERM", () => stop(143));

const main = async () => {
  const args = parseArgs(process.argv.slice(2), { flags: ["keep", "verbose"], values: ["only"] });
  const wanted = args.only === undefined ? HOSTS : args.only.split(",").map((name) => name.trim());
  for (const host of wanted) {
    if (!HOSTS.includes(host)) throw new Error(`unknown host ${host}; known: ${HOSTS.join(", ")}`);
  }

  const started = performance.now();
  const results = await Promise.all(wanted.map((host) => runOne(host, args)));
  const wall = performance.now() - started;

  console.log("");
  console.log("host      ms  result");
  console.log("------  ----  ------");
  let failed = 0;
  for (const result of results) {
    const ok = result.code === 0;
    if (!ok) failed += 1;
    console.log(`${result.host.padEnd(6)}  ${String(Math.round(result.ms)).padStart(4)}  ${ok ? "pass" : `FAIL (exit ${result.code ?? result.signal})`}`);
    // A failing host's own table is the evidence; a passing one stays quiet.
    if (!ok || args.verbose) console.log(result.stdout.trimEnd(), result.stderr.trimEnd());
  }
  console.log(`${"all".padEnd(6)}  ${String(Math.round(wall)).padStart(4)}  ${failed === 0 ? "pass" : `${failed} failed`}`);
  console.log("");
  process.exit(failed === 0 ? 0 : 1);
};

await main();
