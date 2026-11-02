// Host state one session's adapter holds (spec §7.3).
//
// This module is deliberately small. It keeps the host objects a session needs
// — the extension API, the SDK namespace, the event's own context, and the UI
// dialog — and refreshes the context on every emit. It is NOT a dispatcher:
// OMP passes only `api` to an extension factory (`loader.ts:454`), `api` has no
// dispatcher, and `ctx.invokeTool` delegates only to a built-in of the SAME
// name as the calling tool (`extensions/types.ts:877`). So there is no
// `invoke`, no `runAgent`, no `spawn`, no `steer` and no `progressOf` here.
//
// The two capabilities that used to live here have real homes:
//   - `fork`/`join` create and drive a worker session through the SDK the host
//     injects (`api.pi.createAgentSession`, `lane.mjs`);
//   - Plan's `devops(verboseIntent)` reuses one dedicated lane in the session's
//     own roster (`ports.mjs`), so it needs no host tool either.
//
// A host call the adapter cannot make is reported as `{ ok: false, reason }`
// with the reason named, never as a silent success.

export const CAPABILITY = Object.freeze({
  // A steer was attempted on a lane the roster no longer holds. The lane's own
  // session is the only delivery channel, and the roster is the only authority
  // on whether it still exists.
  STEER_UNDELIVERED: "STEER_UNDELIVERED",
});

// One host binding for one session. `dispatch` is accepted for a harness that
// injects one (tests of the old route), but nothing in this adapter calls it:
// the real host supplies none, and keeping a call path that only a test can
// reach would be a second, non-dedicated route.
export class HostBinding {
  #pi;
  #api;
  #ctx;

  constructor({ pi = null, api = null, ctx = null } = {}) {
    this.#pi = pi;
    // The host's SDK namespace (`api.pi`), which is where a session factory
    // lives. Kept beside the extension API so `fork` never guesses.
    this.#api = api ?? pi ?? null;
    this.#ctx = ctx;
  }

  // Adopt the context of the event now being handled. The host builds a new
  // `ExtensionContext` per emit, so `ui` (the `ask` dialog) and every other
  // live member must be read from the current one, not from `session_start`'s.
  refresh(ctx) {
    if (ctx === null || ctx === undefined || typeof ctx !== "object") return this;
    this.#ctx = ctx;
    return this;
  }

  get api() {
    return this.#api;
  }

  get ctx() {
    return this.#ctx;
  }

  get pi() {
    return this.#pi;
  }

  get ui() {
    return this.#ctx?.ui ?? null;
  }
}
