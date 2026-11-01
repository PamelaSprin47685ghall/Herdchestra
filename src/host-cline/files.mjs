// Filesystem helpers for the adapter's own state (spec §1: persistence lives in
// `.Herdchestra/` and stays light).
//
// `writeJson` follows the core rule of spec §3.2 for these files: a temp file in
// the same directory, flushed, then renamed onto the target. The journal is one
// JSON record per line and is only appended to.

import fs from "node:fs";
import path from "node:path";
import { randomBytes } from "node:crypto";

const tempNameFor = (target) => {
  const dir = path.dirname(target);
  const base = path.basename(target);
  return path.join(dir, `.${base}.${process.pid}.${randomBytes(6).toString("hex")}.tmp`);
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

export const writeJson = (target, value) => {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const temp = tempNameFor(target);
  const text = `${JSON.stringify(value, null, 2)}\n`;
  let fd = null;
  try {
    fd = fs.openSync(temp, "w");
    fs.writeFileSync(fd, text, "utf8");
    fs.fsyncSync(fd);
    fs.closeSync(fd);
    fd = null;
    fs.renameSync(temp, target);
    return { ok: true, path: target };
  } catch (error) {
    if (fd !== null) {
      try {
        fs.closeSync(fd);
      } catch {
        // The close failure is not the reason the write failed.
      }
    }
    try {
      fs.unlinkSync(temp);
    } catch {
      // Nothing to remove.
    }
    return { ok: false, path: target, code: error?.code ?? "WRITE_FAILED", message: String(error?.message ?? error) };
  }
};

export const appendJsonLine = (target, record) => {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.appendFileSync(target, `${JSON.stringify(record)}\n`, { encoding: "utf8", mode: 0o644 });
  return record;
};

// Read a journal into records. A torn last line is a crash during append and
// carries no record; any other bad line is an error the caller must see.
export const readJsonLines = (target) => {
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
    try {
      records.push(JSON.parse(line));
    } catch {
      if (index === lines.length - 1) break;
      throw new Error(`journal ${target} line ${index + 1} is not JSON`);
    }
  }
  return records;
};
