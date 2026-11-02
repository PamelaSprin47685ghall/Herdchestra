// The `fork` and `join` receipts (§4.1).
//
//   fork -> { name, steered: false, started: true }   a new worker session
//           { name, steered: true, started: false }   the task went to a live worker
//           { name, steered: false, started: false, reused: true }
//                                                     the task went to a finished
//                                                     worker's own session again
//
// Both mean "delivered", never "done". `steered` is stated by the receipt so the
// caller never infers the meaning from a reused name (§5 step 4).
//
//   join -> { ok: true, record, rejection: null }     the first worker to come back
//           { ok: false, record: null, rejection }    nothing to collect

// The worker table reports the delivered name. A receipt without one is a
// wiring error in the table, not a reason to invent a name here.
//
// `started` is stated by the table rather than derived from `steered`: a reused
// finished worker is neither steered nor a new session, and calling it "started"
// would report a session creation that did not happen.
export const forkReceipt = ({ name, steered, started, reused, cancelled, modelSource = null }) => ({
  name,
  steered: steered === true,
  started: started === true,
  ...(reused === true ? { reused: true } : {}),
  ...(cancelled === true ? { cancelled: true } : {}),
  ...(modelSource === null ? {} : { modelSource }),
});

export const joinReceipt = ({ record = null, rejection = null } = {}) => ({
  ok: record !== null,
  // A worker record always carries the name the Manager gave it; the session
  // id and the host title are additional facts, never substitutes for it.
  record: record === null ? null : { name: record.name, ...record },
  rejection,
});

// The `devops` member of a Plan is synchronous by contract (§6): it waits for
// the one answer and returns the whole report. Its receipt names the worker and
// carries the report bytes unchanged; a missing report is reported, not filled.
export const devopsReceipt = ({ name, report, modelSource = null }) => ({
  ok: true,
  name,
  report: typeof report === "string" ? report : null,
  ...(modelSource === null ? {} : { modelSource }),
});
