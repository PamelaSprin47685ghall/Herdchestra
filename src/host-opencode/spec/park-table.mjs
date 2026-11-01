// The park channel of one program call (§3.3).
//
// compose's surface parks a program by calling core's `ProgramState.park` and
// returning the receipt. The parked member call must not resolve to that
// receipt: it must stay pending until another program resumes it. Core's
// registry has no place for that promise, so this table holds it, keyed by the
// program name.
//
// The evaluation never blocks on it either: the surface's `park` throws
// `ProgramParked` once the promise is registered, so the call answers with the
// receipt at once. On resume the table settles the promise, and a parked
// program's body is started again from its source with the injected value
// recorded, because the unwound body cannot be continued in place.

import { CoreError, CODES } from "../../core/errors.mjs";

// Raised when a member parks. The program tool catches it and answers with the
// receipt; the promise in this table is what a resume settles.
export class ProgramParked extends Error {
  constructor(name, site) {
    super(`program ${name} parked: ${site?.reason ?? "explicit"}`);
    this.name = "ProgramParked";
    this.site = site;
  }
}

export class ParkTable {
  #entries = new Map();

  get size() {
    return this.#entries.size;
  }

  has(name) {
    return this.#entries.has(name);
  }

  // Register the park of a program and hand back the promise a resume settles.
  park(name, site) {
    const existing = this.#entries.get(name);
    if (existing?.resolve != null) {
      throw new CoreError(CODES.STALE_CONTROL, `program ${name} already has a parked expression`, { name });
    }
    const entry = { name, site, resolve: null, promise: null, value: undefined, resumed: false };
    entry.promise = new Promise((resolve) => {
      entry.resolve = resolve;
    });
    this.#entries.set(name, entry);
    return entry.promise;
  }

  // The value a resume injects, or undefined when the program is not parked.
  take(name) {
    const entry = this.#entries.get(name);
    if (entry === undefined || entry.resolve === null) return undefined;
    return entry;
  }

  // Resume: settle the waiting promise with `value` and record that it happened
  // exactly once. Returns false when nothing was parked under this name.
  settle(name, value) {
    const entry = this.#entries.get(name);
    if (entry === undefined || entry.resolve === null) return false;
    const resolve = entry.resolve;
    entry.resolve = null;
    entry.promise = null;
    entry.value = value;
    entry.resumed = true;
    resolve(value);
    return true;
  }

  // A finished program drops its entry; a parked one keeps it for the resume.
  close(name) {
    const entry = this.#entries.get(name);
    if (entry !== undefined && entry.resolve !== null) {
      entry.resolve(undefined);
      entry.resolve = null;
      entry.promise = null;
    }
    return this.#entries.delete(name);
  }
}
