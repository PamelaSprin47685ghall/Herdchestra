// Pair resources (spec §6.1).
//
// Every localisable resource is a pair of books, `en.md` and `zh-CN.md`, under
// `resources/`. A pair that is missing one book fails: the loader never
// substitutes the other language. A book that exists but cannot be read is also
// a failure, not an empty string.
//
// Names are validated before they touch the filesystem, so a resource id can
// never escape the resources directory.

import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { refuse, PAIR_HINT_CODES } from "./errors.mjs";
import { RESOURCE_NAME_VALUES, RESOURCE_NAMES } from "./language.mjs";
import { isNonEmptyString } from "../util.mjs";

// The six resources §6.1 requires, in presentation order.
export const RESOURCE_IDS = Object.freeze([
  "repo-aware-rules",
  "pair-programming",
  "token-economy",
  "wait-cost",
  "context-limit",
  "budget",
]);

const ID_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

export const assertResourceId = (id) => {
  if (!isNonEmptyString(id) || !ID_PATTERN.test(id)) {
    throw refuse(PAIR_HINT_CODES.RESOURCE_UNKNOWN, "resource id must be lower-case kebab-case", {
      id,
    });
  }
  return id;
};

export const RESOURCE_DIR = new URL("./resources/", import.meta.url);

export const resourcePath = (id, language) => {
  assertResourceId(id);
  const name = RESOURCE_NAMES[language];
  if (name === undefined) {
    throw refuse(PAIR_HINT_CODES.INVALID_LANGUAGE, "unknown language", { language });
  }
  return fileURLToPath(new URL(`${id}/${name}`, RESOURCE_DIR));
};

const decodeBook = (bytes, id, name) => {
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw refuse(PAIR_HINT_CODES.RESOURCE_MISSING, `resource ${id}/${name} is not valid UTF-8`, {
      id,
      file: name,
    });
  }
};

const readBook = async (id, language) => {
  const name = RESOURCE_NAMES[language];
  const target = resourcePath(id, language);
  try {
    const bytes = await fs.readFile(target);
    return decodeBook(bytes, id, name);
  } catch (error) {
    if (error instanceof Error && error.code === "ENOENT") {
      throw refuse(
        PAIR_HINT_CODES.RESOURCE_PAIR_INCOMPLETE,
        `resource ${id} is missing ${name}; a pair is required`,
        { id, file: name },
      );
    }
    if (typeof error?.code === "string") {
      throw refuse(PAIR_HINT_CODES.RESOURCE_MISSING, `resource ${id}/${name} cannot be read`, {
        id,
        file: name,
        code: error.code,
      });
    }
    throw error;
  }
};

// One resource pair, loaded whole. `books` is frozen and keyed by file name:
// `{ "en.md": text, "zh-CN.md": text }`. `book(language)` is the read a renderer
// uses; the file name is an implementation detail of the directory layout.
export class ResourcePair {
  #id;

  constructor(id, books = {}) {
    this.#id = id;
    this.books = Object.freeze({ ...books });
  }

  get id() {
    return this.#id;
  }

  // Text of one book. An absent book is a failure, never null.
  book(language) {
    const text = this.books[RESOURCE_NAMES[language]];
    if (text === undefined) {
      throw refuse(PAIR_HINT_CODES.RESOURCE_PAIR_INCOMPLETE, `resource ${this.#id} has no ${language} book`, {
        id: this.#id,
        language,
      });
    }
    return text;
  }

  get complete() {
    return RESOURCE_NAME_VALUES.every((name) => this.books[name] !== undefined);
  }
}

// Load a pair: both books, or a failure. There is no partial result.
export const loadPair = async (id) => {
  assertResourceId(id);
  const books = {};
  for (const language of Object.keys(RESOURCE_NAMES)) {
    books[RESOURCE_NAMES[language]] = await readBook(id, language);
  }
  return new ResourcePair(id, books);
};

// A loader for one run. It remembers pairs per id, so a request renders from
// already-validated text; a failed pair is remembered as the failure and stays
// failed.
export class ResourceLibrary {
  #ids;
  #pairs;

  constructor({ ids = RESOURCE_IDS } = {}) {
    this.#pairs = new Map();
    this.#ids = Object.freeze(ids.map(assertResourceId));
  }

  get ids() {
    return this.#ids;
  }

  async load(id) {
    assertResourceId(id);
    if (!this.#ids.includes(id)) {
      throw refuse(PAIR_HINT_CODES.RESOURCE_UNKNOWN, `resource ${id} is not part of this library`, {
        id,
      });
    }
    if (!this.#pairs.has(id)) {
      this.#pairs.set(id, loadPair(id));
    }
    return await this.#pairs.get(id);
  }

  // Every pair in presentation order. The first failure wins.
  async loadAll() {
    const pairs = [];
    for (const id of this.#ids) pairs.push(await this.load(id));
    return pairs;
  }
}
