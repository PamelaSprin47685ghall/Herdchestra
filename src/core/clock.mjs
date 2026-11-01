// Deadlines and clocks (spec §3.3).
//
// A tool request gets one deadline at admission, fixed once from the intent's
// UTF-8 byte count: 50 ms per byte. The program owns that deadline; a park
// cancels it, and a resume hands the target the controlling request's deadline.
// An old deadline is never extended.
//
// A deadline that runs out is an ABORT, not a value a waiter polls: whatever a
// program is blocked on (a command, a host tool, a resume) is racing the
// `signal`, so expiry reaches every wait site at once. The deadline therefore
// arms an unref'd timer at `expiresAt`; the signal fires even when nothing reads
// the `expired` getter. The park that follows is the program's own
// (`core/program.mjs` `park`), so the cancellation that parks cancels the
// signal that announced it.

import { byteLength } from "./util.mjs";

export const MS_PER_BYTE = 50;

export const deadlineFor = (intent) =>
  byteLength(typeof intent === "string" ? intent : "") * MS_PER_BYTE;

export const systemClock = {
  now: () => Date.now(),
};

// A fixed clock for tests. `advance(ms)` moves time forward.
export const fakeClock = (start = 0) => {
  let current = start;
  return {
    now: () => current,
    advance: (ms) => {
      current += ms;
      return current;
    },
  };
};

export class Deadline {
  #clock;
  #expiresAt;
  #cancelled;
  #controller;
  #timer;

  constructor(clock, ms) {
    this.#clock = clock;
    this.#expiresAt = clock.now() + Math.max(0, ms);
    this.#cancelled = false;
    this.#controller = new AbortController();
    this.#timer = null;
    this.#arm();
  }

  // One unref'd wake-up for the remaining budget. The timer is a wake-up, not
  // the clock: it re-checks `expiresAt` when it fires, so a fake clock that has
  // not advanced is never treated as expired, and a wake-up that lands early
  // re-arms instead of firing. `unref` keeps a deadline from holding the
  // process open after its run is over.
  #arm() {
    if (this.#cancelled) return;
    const remaining = this.#expiresAt - this.#clock.now();
    this.#timer = setTimeout(() => {
      this.#timer = null;
      if (this.#cancelled) return;
      if (this.#clock.now() >= this.#expiresAt) {
        this.#controller.abort();
        return;
      }
      this.#arm();
    }, Math.max(0, remaining));
    if (typeof this.#timer.unref === "function") this.#timer.unref();
  }

  // Abort once and drop the pending wake-up. A cancelled deadline describes a
  // park that already happened, and a signal firing afterwards would park the
  // program a second time.
  #fire() {
    if (this.#cancelled) return;
    if (this.#timer !== null) {
      clearTimeout(this.#timer);
      this.#timer = null;
    }
    this.#controller.abort();
  }

  // Deadline for an intent: the budget is computed once, here.
  static forIntent(clock, intent) {
    return new Deadline(clock, deadlineFor(intent));
  }

  get expiresAt() {
    return this.#expiresAt;
  }

  get expired() {
    return !this.#cancelled && this.#clock.now() >= this.#expiresAt;
  }

  get cancelled() {
    return this.#cancelled;
  }

  // The expiry signal (spec §3.3: 一旦到期). The armed timer aborts it at
  // `expiresAt`; a read at or after `expiresAt` also aborts it immediately, so
  // a spent budget answers the first reader. It never aborts after a cancel.
  //
  // A deadline created from a budget that is already spent (`expiresAt` at or
  // before now) aborts here, at the first read, because the run had no time to
  // begin with.
  get signal() {
    if (this.expired) this.#fire();
    return this.#controller.signal;
  }

  // What is left, or 0 once the deadline expired or was cancelled.
  remainingMs() {
    if (this.#cancelled) return 0;
    return Math.max(0, this.#expiresAt - this.#clock.now());
  }

  cancel() {
    this.#cancelled = true;
    if (this.#timer !== null) {
      clearTimeout(this.#timer);
      this.#timer = null;
    }
    return this;
  }
}
