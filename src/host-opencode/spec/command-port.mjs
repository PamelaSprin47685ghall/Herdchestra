// The `command` port of a program surface (§3.3 `call`, §7.1).
//
// `call(command)` starts one shell command through the host process and
// streams its output into core's `CommandView`. The port is the only place the
// adapter runs a command, so the Manager never reaches it (its surface has no
// `call` target that resolves to a command it may run — §2.1 keeps the Manager
// out of the tree; the tool still exists in its face because `call` is a member,
// and the member refuses for the Manager role).
//
// The runner is injected, not imported: `nodeCommandRunner` from core is the
// default, a test passes a fake. Nothing here decides policy.

import { nodeCommandRunner } from "../../core/command.mjs";

// `makeCommandPort({ run, cwd })` fixes the runner and the default working
// directory; a call may still override either through its options.
export const makeCommandPort = (options = {}) => {
  const { run = nodeCommandRunner, cwd } = typeof options === "function" ? { run: options } : options;
  if (typeof run !== "function") throw new TypeError("command runner must be a function");
  return Object.freeze({
    run: (command, options = {}) =>
      run(command, {
        cwd: options.cwd ?? cwd,
        env: options.env,
        onData: options.onData,
        // A cancelled tool call kills its command: the host aborts the tool's
        // signal, and a command that outlived the call would keep running.
        signal: options.signal,
      }),
  });
};
