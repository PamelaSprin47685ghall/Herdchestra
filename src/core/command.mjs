// A portable command runner. The `call(command)` member needs a runner supplied
// by the host; this module gives a node implementation and a fake for tests.
//
// A runner is `async (command, options) => { stdout, stderr, exitCode }`.
// options: { cwd, env, shell, signal, onData(chunk) }.
// `stdout`/`stderr` are UTF-8 text. A killed command or a failed spawn reports a
// non-zero code and appends the failure to `stderr`.

import { spawn } from "node:child_process";

export const nodeCommandRunner = async (command, options = {}) => {
  const { cwd, env = process.env, shell = true, signal, onData = () => {} } = options;
  return await new Promise((resolve) => {
    let child;
    try {
      child = spawn(command, { cwd, env, shell, stdio: ["ignore", "pipe", "pipe"] });
    } catch (error) {
      resolve({ stdout: "", stderr: `spawn failed: ${error.message}\n`, exitCode: -1 });
      return;
    }
    let stdout = "";
    let stderr = "";
    let settled = false;
    const finish = (exitCode, failure) => {
      if (settled) return;
      settled = true;
      resolve({ stdout, stderr: failure === null ? stderr : `${stderr}${failure}`, exitCode });
    };
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
      onData({ stream: "stdout", chunk });
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
      onData({ stream: "stderr", chunk });
    });
    child.on("error", (error) => finish(-1, `\nspawn failed: ${error.message}\n`));
    child.on("close", (code) => finish(typeof code === "number" ? code : -1, null));
    const abort = () => {
      if (!settled) child.kill("SIGKILL");
    };
    if (signal) {
      if (signal.aborted) abort();
      else signal.addEventListener("abort", abort, { once: true });
    }
  });
};

// Deterministic runner for tests: `respond(command, index)` returns the receipt.
export const fakeCommandRunner = (respond) => {
  const calls = [];
  const run = async (command, options = {}) => {
    calls.push({ command, options });
    const receipt = await respond(command, calls.length - 1);
    const stdout = receipt?.stdout ?? "";
    const stderr = receipt?.stderr ?? "";
    if (stdout !== "") options.onData?.({ stream: "stdout", chunk: stdout });
    if (stderr !== "") options.onData?.({ stream: "stderr", chunk: stderr });
    return { stdout, stderr, exitCode: receipt?.exitCode ?? 0 };
  };
  run.calls = calls;
  return run;
};
