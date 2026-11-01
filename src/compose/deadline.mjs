// The request deadline, enforced (spec §3.3).
//
// 根 §3.3: 「按意图字节数核定期限：每次工具请求在准入时，按请求参数 `intent` 的
// UTF-8 字节数 × 50ms 一次性核定整次请求的期限。程序内的所有操作与恢复共用这段
// 剩余时间；一旦到期，控制者与仍在活动的目标程序一同转入 `parked`，随后只恢复控制者。」
//
// Core computes the budget (`core/clock.mjs` `Deadline`), and core keeps the two
// facts a suspension needs: a program can suspend on expiry (`expire`) and a
// waiter can race the expiry signal (`expirySignal`). This module is the one
// place the two are put together, so every host — omp, Cline, OpenCode — reaches
// the same park receipt by the same steps:
//
//   1. the controller expires FIRST, and it is the one that looks: a controller
//      whose own deadline is spent cannot be resumed into a new request, so the
//      request is over and only it is the one a later `unpark` brings back;
//   2. every program of the same request that is still active is expired with
//      it. "Still active" is `running` or `waiting`: a program that already
//      parked is suspended on its own site and must not have it replaced, and a
//      terminal one is over;
//   3. the controller is resumed, and nothing else is.
//
// `settleExpiry` is what makes step 2 observable to a body that is blocked: the
// host's park channel resolves the suspended expression with the receipt, so
// `await this.call(...)` answers a park receipt instead of hanging on a command
// the request no longer has time for.
//
// One fact decides membership in a request: the programs the host hands in. The
// controller's request is the only one live at that moment on these hosts (one
// tool call at a time per session), and a host with a wider notion of "the same
// request" hands a wider list.

// Every program of this request that expiry must suspend, controller first.
//
// `others` is the host's list of live programs. A program is skipped when it is
// already parked (it has a site of its own), terminal, or absent.
export const expireRequest = (controller, others = []) => {
  const expired = [];
  const targets = [];
  for (const program of others ?? []) {
    if (program === null || program === undefined) continue;
    if (program === controller) continue;
    if (program.isTerminal() || program.state === "parked") continue;
    if (program.expire()) targets.push(program.name);
  }
  // The controller is suspended even if it had already parked: a controller
  // whose request ran out is the program a later request resumes, and leaving
  // it on an older site would let it continue on a request that is over.
  if (!controller.isTerminal() && controller.expire()) expired.push(controller.name);
  return Object.freeze({
    expired: Object.freeze(expired),
    targets: Object.freeze(targets),
    // The controller is the ONLY program a later `unpark` resumes (根 §3.3:
    // 随后只恢复控制者).
    resumed: Object.freeze([controller.name]),
  });
};

// The park receipt an expired run answers with. It is the surface's own view, so
// it carries the nine fields and the line-numbered source like every other
// receipt; the state it reports is the one the expiry left (`parked`).
export const expiredReceipt = (surface) => surface.receipt({ reason: "explicit", payload: null });

// Wait for `work`, but not past the run's deadline. Returns the work's outcome,
// or `{ kind: "expired", receipt }` the moment the deadline is spent.
//
// The signal is read through a getter (`program.expirySignal`) so a run with no
// deadline is the same wait it always was, and a run whose deadline a park
// cancelled never answers "expired" for a suspension the model asked for.
//
// `onExpire` is the host's one chance to make the suspension real: it runs
// synchronously after the receipt is built, so a blocked body is released before
// the caller answers.
export const withDeadline = async (program, work, { onExpire = null } = {}) => {
  // A program that suspended on its own site is not expired: a park cancels the
  // deadline, and a wait begun afterwards is the suspended body's own wait. The
  // deadline is only read while the program is still running or waiting, which
  // is the only state in which "expired" can mean anything.
  const signal = program.state === "parked" ? null : program.expirySignal;
  if (signal === null) return { kind: "settled", value: await work };
  if (signal.aborted) {
    // The budget was spent before the wait began.
    const receipt = typeof onExpire === "function" ? onExpire() : null;
    return { kind: "expired", receipt };
  }
  const workPromise = Promise.resolve(work);
  // Both outcomes of the work are observed here, so a rejection that arrives
  // after the race was lost is never an unhandled rejection.
  const settled = workPromise.then(
    (value) => ({ kind: "settled", value }),
    (error) => ({ kind: "failed", error }),
  );
  const expired = new Promise((resolve) => {
    signal.addEventListener("abort", () => resolve({ kind: "expired" }), { once: true });
  });
  const first = await Promise.race([settled, expired]);
  if (first.kind === "settled") return first;
  if (first.kind === "failed") throw first.error;
  const receipt = typeof onExpire === "function" ? onExpire() : null;
  // The work may still settle later; that outcome belongs to the suspended
  // program, not to this caller. It is already observed by `settled`.
  return { kind: "expired", receipt };
};
