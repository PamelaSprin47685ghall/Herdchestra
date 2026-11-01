// Filesystem members of a JS program surface (spec §3.1, §3.2).
//
// `FileMembers` performs `cat`, `stat`, `ls`, `write`, `mkdir`, `rm`, `mv`.
// Expected conditions are receipts, never throws:
// - `stat` of a missing path is `{ exists: false }`.
// - `cat` of a missing path is `{ ok: false, code: "ENOENT", path }`.
// - `write` is an atomic replace (see `atomicWriteFile`).
//
// Paths: an absolute path is used as is; a relative path joins `root`; an empty
// path is the root. Access is decided by the OS; `guard(path)` is the only
// policy hook.

import fs from "node:fs/promises";
import path from "node:path";
import { randomBytes } from "node:crypto";
import { decodeUtf8, encodeUtf8 } from "./text.mjs";

export const CORE_PATH_MEMBERS = Object.freeze([
  "cat",
  "stat",
  "ls",
  "write",
  "mkdir",
  "rm",
  "mv",
]);

const codeOf = (error) => (error && typeof error.code === "string" ? error.code : "EIO");

const failure = (code, target, extra = {}) => ({ ok: false, code, path: target, ...extra });

const tempNameFor = (target) => {
  const dir = path.dirname(target);
  const base = path.basename(target);
  return path.join(dir, `.${base}.${process.pid}.${randomBytes(6).toString("hex")}.tmp`);
};

// Write `text` to `target` atomically (spec §3.2).
//
// The temp file lives in the target's own directory, so the rename stays inside
// one filesystem. It is created exclusively (`wx`), written, flushed and closed,
// then renamed onto the target, which replaces it whether or not it existed.
// Exclusive creation guards the temp name, not the target: a stale temp file
// from an earlier crash is never reused, and an `EEXIST` on the temp name fails
// the write as it is, leaving that file untouched because this call did not
// create it. After this call created its temp file, any failure removes it and
// the target keeps its old bytes. There is no lock: two writers of one path each
// rename their own temp file and the last rename wins.
//
// `rename` is atomic only within one filesystem, so the same-directory rule is
// what makes the guarantee hold; it comes from `tempNameFor`, which derives the
// temp path from `path.dirname(target)` and never from a temp directory. A
// caller that passes a target whose directory is a symlink keeps that property:
// the rename happens inside the directory the caller named, which is the only
// directory the caller can observe.
export const atomicWriteFile = async (target, text) => {
  if (typeof text !== "string") throw new TypeError("content must be a string");
  const bytes = encodeUtf8(text);
  const temp = tempNameFor(target);
  let handle;
  try {
    handle = await fs.open(temp, "wx");
  } catch (error) {
    return failure(codeOf(error), target);
  }
  try {
    await handle.writeFile(bytes);
    await handle.sync();
    await handle.close();
    handle = null;
    await fs.rename(temp, target);
    return { ok: true, path: target, bytes: bytes.length };
  } catch (error) {
    if (handle !== null) await handle.close().catch(() => {});
    await fs.rm(temp, { force: true }).catch(() => {});
    return failure(codeOf(error), target);
  }
};

export class FileMembers {
  #root;
  #guard;
  #onWrite;

  // `root` is the session root. `guard(path)` returns a refusal receipt or null
  // and runs before every access. `onWrite(path)` runs after a successful write.
  constructor({ root, guard = () => null, onWrite = () => {} } = {}) {
    if (typeof root !== "string" || root.length === 0) {
      throw new TypeError("root must be a non-empty path");
    }
    this.#root = root;
    this.#guard = guard;
    this.#onWrite = onWrite;
  }

  get root() {
    return this.#root;
  }

  resolve(target) {
    if (typeof target !== "string") throw new TypeError("path must be a string");
    if (target === "") return this.#root;
    return path.isAbsolute(target) ? target : path.join(this.#root, target);
  }

  #blocked(target) {
    const receipt = this.#guard(target);
    return receipt === null || receipt === undefined ? null : receipt;
  }

  async cat(target) {
    const blocked = this.#blocked(target);
    if (blocked) return blocked;
    try {
      const bytes = await fs.readFile(this.resolve(target));
      return { ok: true, path: target, text: decodeUtf8(bytes) };
    } catch (error) {
      return failure(codeOf(error), target);
    }
  }

  async stat(target) {
    const blocked = this.#blocked(target);
    if (blocked) return blocked;
    try {
      const info = await fs.stat(this.resolve(target));
      // Spec §3.1: `stat` answers `{ exists, size, mtime, isDir }` and nothing
      // else; a missing file is a fact, not an error, so the same four keys
      // come back with `exists: false`.
      return { exists: true, size: info.size, mtime: info.mtimeMs, isDir: info.isDirectory() };
    } catch (error) {
      const code = codeOf(error);
      if (code === "ENOENT" || code === "ENOTDIR") return { exists: false };
      return failure(code, target);
    }
  }

  // Not recursive; directory entries end with "/".
  async ls(target) {
    const blocked = this.#blocked(target);
    if (blocked) return blocked;
    try {
      const entries = await fs.readdir(this.resolve(target), { withFileTypes: true });
      const names = entries
        .map((entry) => (entry.isDirectory() ? `${entry.name}/` : entry.name))
        .sort();
      return { ok: true, path: target, entries: names };
    } catch (error) {
      return failure(codeOf(error), target);
    }
  }

  async write(target, content) {
    const blocked = this.#blocked(target);
    if (blocked) return blocked;
    const receipt = await atomicWriteFile(this.resolve(target), content);
    if (receipt.ok) this.#onWrite(target);
    return receipt;
  }

  async mkdir(target, { recursive = false } = {}) {
    const blocked = this.#blocked(target);
    if (blocked) return blocked;
    try {
      await fs.mkdir(this.resolve(target), { recursive });
      return { ok: true, path: target };
    } catch (error) {
      return failure(codeOf(error), target);
    }
  }

  async rm(target, { recursive = false, force = false } = {}) {
    const blocked = this.#blocked(target);
    if (blocked) return blocked;
    try {
      await fs.rm(this.resolve(target), { recursive, force });
      return { ok: true, path: target };
    } catch (error) {
      return failure(codeOf(error), target);
    }
  }

  async mv(from, to) {
    const blocked = this.#blocked(from);
    if (blocked) return blocked;
    // The destination is an access too: a guard that denies it must refuse the
    // move before the OS touches either path.
    const blockedTarget = this.#blocked(to);
    if (blockedTarget) return blockedTarget;
    try {
      await fs.rename(this.resolve(from), this.resolve(to));
      return { ok: true, from, to };
    } catch (error) {
      return failure(codeOf(error), from, { to });
    }
  }
}
