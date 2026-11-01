// Members of a JS program (spec §3.1, §3.3).
//
// A member is a plain method on the program object the model sees. Every member
// is one real call: to core (files, text, lifecycle) or to a host port
// (fork/join/ask/fission/review/devops). Nothing here invents an outcome.
//
// Files. `cat`, `stat`, `ls`, `write`, `mkdir`, `rm`, `mv` are core's
// `FileMembers`. Core reports expected conditions as receipts; a failed receipt
// becomes a thrown `CoreError` with the same code, so a program stops instead
// of carrying a failure value onward. `stat` of a missing path is a fact
// (`{exists:false}`), never an error.
//
// Text. `where` and `whereAll` are core's `TextMembers`. `where` parks the
// program when the needle has zero or several matches and returns the park
// receipt; `whereAll` never parks.
//
// Commands. `call` drains the program's pending operations, starts the command
// through the host command port, and returns `{stdout, stderr, exitCode}`.

import { CoreError, CODES, invalidArgument } from "../../core/errors.mjs";
import { withDeadline } from "../deadline.mjs";
import { FileMembers } from "../../core/fs.mjs";
import { TextMembers } from "../../core/where.mjs";
import { CommandView, PARK_REASONS } from "../../core/receipt.mjs";
import { STATES } from "../../core/program.mjs";
import { expectNonEmptyString } from "../../core/util.mjs";

const failed = (receipt) =>
  receipt !== null && typeof receipt === "object" && receipt.ok === false;

const raise = (receipt, member) => {
  throw new CoreError(receipt.code, `${member} failed: ${receipt.code}`, {
    member,
    path: receipt.path,
  });
};

// What a member answers once the request's deadline ran out. The program is
// `parked` by then (the surface's expiry did that), so the answer is the park
// receipt, not a command value: the model reads the suspension and stops, and
// no invented `exitCode` is handed to code that would treat it as a real run.
const expiredAnswer = () => ({
  ok: false,
  code: "REQUEST_DEADLINE_EXPIRED",
  state: STATES.PARKED,
  message: "the request's deadline expired; this call was suspended before it produced a result",
});

// Core-backed members, bound to one program.
//
// `park(site)` suspends the program and returns the park receipt; it is the
// same function the `park` member uses. `views` is the shared command-view map.
// `commandPort` is the host command port (`{ run }`).
export const coreMembers = ({
  root,
  guard = () => null,
  program,
  clock,
  views,
  park,
  commandPort,
}) => {
  const files = new FileMembers({ root, guard });
  const text = new TextMembers({ park: (payload) => park({ reason: PARK_REASONS.EXPLICIT, payload }) });

  const call = async (command, options = {}) => {
    expectNonEmptyString("command", command);
    // Admission first, in the order spec §3.3 gives for a program that takes no
    // new expression. A parked or finished program is refused for THAT reason:
    // answering "no command port" or "running another command" would hide the
    // fact the caller must act on (it has to resume, not retry).
    //
    // The drain check comes before this call registers its own operation,
    // because `draining` reads the waiter set, not the operation count: a call
    // that registered first would wait for the earlier work, and a SECOND call
    // arriving mid-drain would then register too and wait for that same earlier
    // work — which only the first caller can settle. Refusing here is what keeps
    // the second caller from becoming a phantom operation every later drain
    // would wait on.
    if (!program.acceptsExpressions) {
      // An expired request is answered with its suspension, not with a state the
      // model would read as "parked by me": §3.3's deadline is the request's
      // own, and the program is parked because of it.
      if (program.expired === true) return expiredAnswer();
      const reason = views.has(program.name)
        ? "running another command"
        : program.draining
          ? "draining; it takes no new expression"
          : program.state;
      throw invalidArgument(`program ${program.name} is ${reason}`);
    }
    // A program runs one command at a time (spec §3.3). A second `call` while a
    // command is live is refused rather than queued behind a drain: the live
    // command is not this call's predecessor — it would run AFTER it — so no
    // drain level can order them, and admitting the second one would let two
    // commands of one program interleave. A live command means the program is
    // `waiting`, so the admission check above already refuses it; this guard
    // stays as the explicit statement of the rule and as the check for a host
    // that left a command view behind.
    if (views.has(program.name)) {
      throw invalidArgument(`program ${program.name} is running another command`);
    }
    // A role without a command port (`js-review` is read-only, and a host may
    // omit the port for a surface that never runs) must say so at the call,
    // not fail later on `undefined.run`. Checked last: a surface that can never
    // run a command should still tell the truth about why THIS call was refused.
    if (commandPort === null || commandPort === undefined || typeof commandPort.run !== "function") {
      throw invalidArgument("this surface has no command port");
    }
    // Draining (spec §3.3): every SDK operation this program started EARLIER
    // settles before the command runs. This call registers as an operation
    // first — that is what makes a later `call` wait for it — and then waits by
    // IDENTITY: `drainedPromise(self)` names the operations with smaller ids,
    // so the wait covers every predecessor and excludes this call itself. A
    // count-based level would have to guess which open operations are earlier,
    // and a wrong guess either skips a predecessor or waits forever.
    const self = program.beginOperation();
    const drained = program.drainedPromise(self);
    // A program waiting on its own earlier work is waiting, not running
    // (spec §3.3: `waiting` covers waiting for a command or an SDK operation).
    // Moving before the await makes that true for the whole drain and makes
    // `waiting` agree with `draining` instead of contradicting it.
    const resumedState = program.state;
    program.moveTo(STATES.WAITING);
    //
    // The drain is raced against the request's deadline (spec §3.3): a
    // predecessor that never settles must not outlive the request. An expiry
    // answers the park receipt, which is the honest outcome — the command never
    // started — and the program's own state change is the surface's business.
    const drainedOutcome = await withDeadline(program, drained);
    if (drainedOutcome.kind === "expired") {
      program.endOperation(self);
      return expiredAnswer();
    }
    // A program that suspended on its own site while this call drained has no
    // command to start, and none was started: the suspension is the answer the
    // model is waiting for, so this call answers it instead of throwing at a
    // body that already left the statement it was on. The test below checks
    // BOTH states because a park and a resumed run can interleave with the
    // drain: `acceptsExpressions` alone leaves a resumed program looking
    // blocked, and an error here is what a body parked mid-drain received.
    if (program.state === STATES.PARKED) {
      program.endOperation(self);
      return expiredAnswer();
    }
    // The program may have parked or finished while this call drained. A program
    // that no longer accepts expressions must not start a command (spec §3.3:
    // a program that stops taking new expressions takes none of them).
    if (!program.acceptsExpressions) {
      program.endOperation(self);
      // A live command is the more specific fact and wins: nothing is draining
      // there, a command is running. Otherwise name the transition, which the
      // model can act on; "running" would describe the opposite of what it sees.
      const reason = views.has(program.name)
        ? "running another command"
        : program.draining
          ? "draining; it takes no new expression"
          : program.state;
      throw invalidArgument(`program ${program.name} is ${reason}`);
    }
    // A command that started while this call drained is not this call's
    // predecessor: it would run AFTER it. The pre-drain check catches the common
    // case; this one catches a call admitted before the other command began.
    if (views.has(program.name)) {
      program.endOperation(self);
      throw invalidArgument(`program ${program.name} is running another command`);
    }
    // `ended` is this operation's own last step, taken on EVERY path: the
    // command's receipt, the request's expiry, or the command failing. A path
    // that skips it leaves the operation open, and every later drain then waits
    // for work that is already over.
    let ended = false;
    const end = () => {
      if (ended) return;
      ended = true;
      program.endOperation(self);
    };
    try {
      const view = new CommandView({ clock });
      views.set(program.name, view);
      let receipt;
      const started = commandPort.run(command, {
        cwd: options.cwd,
        env: options.env,
        onData: ({ stream, chunk }) => view.append(stream, chunk),
      });
      try {
        // The command is raced against the deadline too: a long command is
        // exactly what a request runs out of time on, and answering its receipt
        // late would describe a request that no longer exists. The command keeps
        // running (根 §3.3: 挂起绝不冻结外部世界) — only this waiter stops.
        //
        // The expiry is read HERE, not before: the race is registered after the
        // command has started, so a deadline that runs out at any point during
        // the command is still seen, and the wait is one the model can stop.
        const outcome = await withDeadline(program, started);
        if (outcome.kind === "expired") {
          started.catch?.(() => {});
          return expiredAnswer();
        }
        receipt = outcome.value;
      } finally {
        views.delete(program.name);
      }
      const exitCode = typeof receipt?.exitCode === "number" ? receipt.exitCode : -1;
      view.end(exitCode);
      return { stdout: receipt?.stdout ?? "", stderr: receipt?.stderr ?? "", exitCode };
    } finally {
      end();
      // Back to the state the command interrupted — unless the body parked or
      // finished meanwhile, which own the state from that point on. A program
      // parked while its command ran stays `parked`: the resume is the only
      // path out (spec §3.3, single-shot).
      if (program.state === STATES.WAITING) program.moveTo(resumedState);
    }
  };

  return {
    async cat(path) {
      const receipt = await files.cat(path);
      if (failed(receipt)) raise(receipt, "cat");
      return receipt.text;
    },
    // A missing path is a fact (§3.1 `{ exists, size, mtime, isDir }`), never a
    // thrown error: `if (!(await this.stat(p)).exists) continue` is a documented
    // example (§3.4 example 5), and a throw would turn that branch into a crash.
    async stat(path) {
      const receipt = await files.stat(path);
      if (failed(receipt)) raise(receipt, "stat");
      // Exactly the four documented fields (§3.1). A missing path answers
      // `{ exists: false }` and no invented size or mtime.
      if (receipt.exists !== true) return { exists: false };
      return { exists: true, size: receipt.size, mtime: receipt.mtime, isDir: receipt.isDir === true };
    },
    async ls(path) {
      const receipt = await files.ls(path);
      if (failed(receipt)) raise(receipt, "ls");
      return receipt.entries;
    },
    // Every write replaces the target atomically; there is no transaction and
    // no lock across writes (spec §3.2), so a second write to the same path in
    // one program simply wins. Writing each path once is the program's own
    // discipline (§3.4), not a rule this layer enforces.
    async write(path, content) {
      const receipt = await files.write(path, content);
      if (failed(receipt)) raise(receipt, "write");
      return { path: receipt.path, bytes: receipt.bytes };
    },
    async mkdir(path, options = {}) {
      const receipt = await files.mkdir(path, options);
      if (failed(receipt)) raise(receipt, "mkdir");
      return { path: receipt.path };
    },
    async rm(path, options = {}) {
      const receipt = await files.rm(path, options);
      if (failed(receipt)) raise(receipt, "rm");
      return { path: receipt.path };
    },
    async mv(from, to) {
      const receipt = await files.mv(from, to);
      if (failed(receipt)) raise(receipt, "mv");
      return { from: receipt.from, to: receipt.to };
    },
    where(content, needle) {
      return text.where(content, needle);
    },
    whereAll(content, needle) {
      return text.whereAll(content, needle);
    },
    call,
  };
};

// Read-only members of `js-review` (spec §8): no write, delete, move or run.
//
// `park(site)` takes a site (`{reason, payload}`), exactly like `coreMembers`.
// Core's `TextMembers` hands `where`'s raw payload, so this module wraps it:
// passing the payload straight through would reach the surface as a site with
// no `payload` field and the `where` diagnostics (`needle`, `candidates`,
// `nearest`) would be lost.
export const reviewMembers = ({ root, guard = () => null, park }) => {
  const files = new FileMembers({ root, guard });
  const text = new TextMembers({ park: (payload) => park({ reason: PARK_REASONS.EXPLICIT, payload }) });
  return {
    async cat(path) {
      const receipt = await files.cat(path);
      if (failed(receipt)) raise(receipt, "cat");
      return receipt.text;
    },
    // Same shape and same promise as the worker's `stat` (§3.1): a missing path
    // is `{ exists: false }`, and the four documented fields are the whole
    // answer, so a caller reads one shape on both faces.
    async stat(path) {
      const receipt = await files.stat(path);
      if (failed(receipt)) raise(receipt, "stat");
      // Exactly the four documented fields (§3.1). A missing path answers
      // `{ exists: false }` and no invented size or mtime.
      if (receipt.exists !== true) return { exists: false };
      return { exists: true, size: receipt.size, mtime: receipt.mtime, isDir: receipt.isDir === true };
    },
    async ls(path) {
      const receipt = await files.ls(path);
      if (failed(receipt)) raise(receipt, "ls");
      return receipt.entries;
    },
    where: (content, needle) => text.where(content, needle),
    whereAll: (content, needle) => text.whereAll(content, needle),
  };
};
