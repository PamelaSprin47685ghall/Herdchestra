// Filesystem helpers for the adapter: JSON state, atomic writes, and one small
// append-only journal. Everything here is synchronous on purpose. OMP extension
// hooks may run while the agent loop holds its lock; a hook that awaits a disk
// write it could have done inline only widens that window.
//
// `writeJson` follows the core rule of spec §3.2 for our own state files: a temp
// file in the same directory, flushed, then renamed onto the target.

import fs from "node:fs";
import path from "node:path";
import { randomBytes } from "node:crypto";

const tempNameFor = (target) => {
  const dir = path.dirname(target);
  const base = path.basename(target);
  return path.join(dir, `.${base}.${process.pid}.${randomBytes(6).toString("hex")}.tmp`);
};

// The bytes of a text file, or `null` when it does not exist. A missing file is
// an answer ("there is none yet"), never an error; any other read failure is
// real and is thrown, because inventing empty content would turn a broken disk
// into an empty draft that a hand-off would then ship.
export const readText = (target) => {
  try {
    return fs.readFileSync(target, "utf8");
  } catch (error) {
    if (error?.code === "ENOENT") return null;
    throw error;
  }
};

export const readJson = (target, fallback = null) => {
  let raw;
  try {
    raw = fs.readFileSync(target, "utf8");
  } catch (error) {
    if (error?.code === "ENOENT") return fallback;
    throw error;
  }
  try {
    return JSON.parse(raw);
  } catch {
    // A torn or hand-edited state file must not crash the host. The caller gets
    // the fallback and the next write replaces the file atomically.
    return fallback;
  }
};

// The same read, but a file that exists and cannot be read is an ANSWER
// (`{ok: false, code}`), not the fallback.
//
// `readJson` cannot tell "absent" from "unparsable": both come back as the
// fallback. For a state file that is a convenience, but for a SAFETY fact it is
// the wrong direction — a torn verdict would read as "no verdict yet". Callers
// whose answer must fail closed use this instead.
export const readJsonOrError = (target) => {
  let raw;
  try {
    raw = fs.readFileSync(target, "utf8");
  } catch (error) {
    if (error?.code === "ENOENT") return { ok: true, value: null };
    return { ok: false, code: typeof error?.code === "string" ? error.code : "EIO" };
  }
  try {
    return { ok: true, value: JSON.parse(raw) };
  } catch {
    return { ok: false, code: "EJSONPARSE" };
  }
};

// The result is always an answer, never a throw: every caller in this adapter
// treats `{ ok: false }` as "the file could not be written" and decides from
// there (`session-state.mjs` fails the request gate CLOSED on it). A throw
// escaping here reached a hook with no catch of its own, and the answer the
// caller needed — which code — was replaced by a stack trace.
//
// The directory is created INSIDE the try for the same reason: a root that
// cannot hold `.Herdchestra` (a read-only parent, a file where the directory
// belongs) is exactly the failure this answer exists for. It was outside, so
// `ENOTDIR`/`EACCES`/`EROFS` threw past the answer and the first request of
// such a workspace reported a crash instead of `REQUEST_GATE_UNWRITABLE`.
export const writeJson = (target, value) => {
  const text = `${JSON.stringify(value, null, 2)}\n`;
  let temp = null;
  let fd = null;
  try {
    fs.mkdirSync(path.dirname(target), { recursive: true });
    temp = tempNameFor(target);
    fd = fs.openSync(temp, "w");
    fs.writeFileSync(fd, text, "utf8");
    fs.fsyncSync(fd);
    fs.closeSync(fd);
    fd = null;
    fs.renameSync(temp, target);
    return { ok: true, path: target };
  } catch (error) {
    // ENOENT here is the FIRST WRITE into a directory that did not exist yet:
    // something removed it between the `mkdir` above and this open (a host that
    // cleans the state directory, a racing session). The name is chosen in that
    // directory, so the write is retried once after recreating it — the one
    // failure that a second attempt can honestly fix.
    if (error?.code === "ENOENT") {
      try {
        if (fd !== null) fs.closeSync(fd);
      } catch {}
      try {
        fs.mkdirSync(path.dirname(target), { recursive: true });
        const retryTemp = tempNameFor(target);
        const retryFd = fs.openSync(retryTemp, "w");
        try {
          fs.writeFileSync(retryFd, text, "utf8");
          fs.fsyncSync(retryFd);
        } finally {
          fs.closeSync(retryFd);
        }
        fs.renameSync(retryTemp, target);
        return { ok: true, path: target };
      } catch (second) {
        return { ok: false, code: typeof second?.code === "string" ? second.code : "EIO", path: target };
      }
    }
    if (fd !== null) {
      try {
        fs.closeSync(fd);
      } catch {}
    }
    if (temp !== null) {
      try {
        fs.rmSync(temp, { force: true });
      } catch {}
    }
    return { ok: false, code: typeof error?.code === "string" ? error.code : "EIO", path: target };
  }
};

export const ensureDir = (target) => {
  fs.mkdirSync(target, { recursive: true });
  return target;
};

export const exists = (target) => {
  try {
    fs.statSync(target);
    return true;
  } catch {
    return false;
  }
};

// Append one JSON line with O_APPEND, so concurrent appenders never interleave
// inside a line. A crash can leave one torn last line; readers skip it.
export const appendJsonLine = (target, record) => {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.appendFileSync(target, `${JSON.stringify(record)}\n`, { encoding: "utf8", mode: 0o644 });
  return record;
};

// Read a journal into records. `parse` turns one decoded line into a record or
// throws; the caller decides what a bad line means.
export const readJsonLines = (target, parse) => {
  let raw;
  try {
    raw = fs.readFileSync(target, "utf8");
  } catch (error) {
    if (error?.code === "ENOENT") return [];
    throw error;
  }
  const records = [];
  const lines = raw.split("\n");
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    if (line.trim() === "") continue;
    let decoded;
    try {
      decoded = JSON.parse(line);
    } catch {
      if (index === lines.length - 1) break;
      throw new Error(`journal ${target} line ${index + 1} is not JSON`);
    }
    records.push(parse(decoded, index));
  }
  return records;
};

export const fileSize = (target) => {
  try {
    return fs.statSync(target).size;
  } catch {
    return 0;
  }
};
