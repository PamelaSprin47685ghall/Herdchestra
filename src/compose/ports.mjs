// The ports a program surface needs from the outside world.
//
// A port is a plain object of async functions. Composition never invents a
// port: the host adapter supplies one. A missing port is a wiring error
// (`MISSING_PORT`), reported when the surface is built, not when a member is
// first called. Core needs to know nothing about any of them.

import { refuse } from "./errors.mjs";

// Every port function is named here so a surface can be checked at build time.
export const PORT_METHODS = Object.freeze({
  // Worker control (spec §4.1). Both return receipts; neither waits for work.
  // `fork(name, brief, role)`: compose resolves the role from the brief's
  // explicit declaration and passes it along, so no host re-derives it.
  fork: ["fork"],
  join: ["join"],
  // User channel (spec §3.1, §3.3 example 7): the question parks the program;
  // the answer is the reply text. Only a top-level program has `ask`, so only a
  // top-level surface receives this port.
  ask: ["ask"],
  // Lane creation for fission (spec §4.2). `createLane(lane)` builds one
  // present; `replacePresent(group)` silences the owner's old present;
  // `abortLanes(groupId)` undoes a group that failed to start.
  fission: ["createLane", "replacePresent", "abortLanes"],
  // Ledger relay (根 §2.3). `handOver(decision)` performs retirement, barrier
  // and successor start as one record; `startManager(draft)` opens a Manager
  // session from draft bytes; `askOperator(question)` asks for the first
  // incumbent.
  relay: ["handOver", "startManager", "askOperator"],
  // Plan-only: one synchronous devops investigation (spec §6).
  devops: ["investigate"],
  // The saved draft of a Plan (spec §6). `read()` returns its bytes or "" when
  // it is missing; the review ledger refuses an empty draft before a Manager
  // session is created.
  draft: ["read"],
  // Command execution for `call` (spec §3.3). Receives the command and an
  // `onData` callback; resolves to `{ stdout, stderr, exitCode }`.
  command: ["run"],
  // The Scope snapshot of the calling session (spec §6.2). `ask(member)` is the
  // only method and it is SYNCHRONOUS by contract: the two exempt members
  // (`negotiate`, `askPreviousScope`) answer from the newest committed facts and
  // never park, so a port that answered a promise would break the one property
  // §6.2 states about them. A surface without this port refuses those members
  // at the call (`MISSING_PORT`), not at build time: a host that never wired
  // Scope still gets every other member.
  scope: ["ask"],
});

const missing = (portName, names, ports) => {
  const port = ports[portName];
  if (port === null || typeof port !== "object") {
    throw refuse("MISSING_PORT", `port ${portName} is required`, { port: portName });
  }
  for (const name of names) {
    if (typeof port[name] !== "function") {
      throw refuse("MISSING_PORT", `port ${portName}.${name} is required`, {
        port: portName,
        method: name,
      });
    }
  }
  return port;
};

// Check the ports a surface uses. `names` is a list of port names.
export const requirePorts = (ports, names) => {
  if (ports === null || typeof ports !== "object") {
    throw refuse("MISSING_PORT", "ports object is required");
  }
  for (const portName of names) {
    if (PORT_METHODS[portName] === undefined) {
      throw refuse("MISSING_PORT", `unknown port ${portName}`, { port: portName });
    }
    missing(portName, PORT_METHODS[portName], ports);
  }
  return ports;
};

export const optionalPort = (ports, portName) => {
  const port = ports?.[portName];
  if (port === null || port === undefined || typeof port !== "object") return null;
  return missing(portName, PORT_METHODS[portName], ports);
};
