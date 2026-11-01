// Session language (spec §6.1, §6.1 step 1).
//
// Two languages only. A session binds once, at creation; after that the binding
// is a stored fact and never follows a changed global preference. Retries,
// failures, compaction and wake-up do not touch it. A `fork`ed child copies the
// starter's binding and is independent from then on.
//
// The ladder is: explicit environment variable -> host preference -> IDE /
// system locale -> English. A present but unusable value stops the ladder; it
// never falls through to the next rung, so a typo cannot quietly become
// English.

import { invalidArgument, refuse, PAIR_HINT_CODES } from "./errors.mjs";
import { isNonEmptyString } from "../util.mjs";

export const LANGUAGES = Object.freeze({
  ENGLISH: "English",
  SIMPLIFIED_CHINESE: "SimplifiedChinese",
});

export const LANGUAGE_VALUES = Object.freeze(Object.values(LANGUAGES));

export const DEFAULT_LANGUAGE = LANGUAGES.ENGLISH;

// The resource file name of a language: `en.md` / `zh-CN.md`.
export const RESOURCE_NAMES = Object.freeze({
  [LANGUAGES.ENGLISH]: "en.md",
  [LANGUAGES.SIMPLIFIED_CHINESE]: "zh-CN.md",
});

export const RESOURCE_NAME_VALUES = Object.freeze(Object.values(RESOURCE_NAMES));

export const isLanguage = (value) => LANGUAGE_VALUES.includes(value);

export const assertLanguage = (value, name = "language") => {
  if (!isLanguage(value)) {
    throw refuse(PAIR_HINT_CODES.INVALID_LANGUAGE, `${name} must be English or SimplifiedChinese`, {
      value,
    });
  }
  return value;
};

// Explicit configuration wins over everything; it must name a language.
export const parseExplicitLanguage = (value) => {
  if (value === undefined || value === null) return null;
  if (!isLanguage(value)) {
    throw refuse(PAIR_HINT_CODES.INVALID_LANGUAGE, "explicit language is not a known language", {
      value,
    });
  }
  return value;
};

// A BCP 47 locale tag from the IDE or the system. The first matching segment
// wins; a tag with no known segment is ignored. The language subtag decides:
// `en-GB` is English, `zh-Hans-CN` is SimplifiedChinese. A `zh` tag that names
// a non-Hans script or a region whose convention is Traditional is not a
// known tag here and is ignored, so the ladder moves on instead of pretending
// Traditional is Simplified (§6.1 has exactly two languages).
const TRADITIONAL_SCRIPT = new Set(["hant", "tw", "hk", "mo"]);

const languageFromTag = (tag) => {
  // A POSIX locale carries a codeset and a modifier that are not part of the
  // BCP 47 tag: `zh_TW.UTF-8` names the same region as `zh-TW`, and
  // `zh.UTF-8@euro` the same language as `zh`. Both are stripped before the
  // subtags are read, or the codeset would hide the script/region the tag
  // names — `zh_TW.UTF-8` would pass as Simplified Chinese.
  const core = tag.split("@")[0].split(".")[0].toLowerCase();
  const parts = core.split(/[-_]/);
  const language = parts[0];
  if (language === "en") return LANGUAGES.ENGLISH;
  if (language !== "zh") return null;
  if (parts.slice(1).some((part) => TRADITIONAL_SCRIPT.has(part))) return null;
  return LANGUAGES.SIMPLIFIED_CHINESE;
};

export const languageFromLocale = (locale) => {
  if (typeof locale !== "string" || locale.trim() === "") return null;
  const tags = locale.trim().split(/[,\s]+/).filter((tag) => tag !== "");
  for (const tag of tags) {
    const language = languageFromTag(tag);
    if (language !== null) return language;
  }
  return null;
};

// A host preference is a hint: unusable values are ignored (null) and the
// ladder moves on. It may name the language itself or give a locale tag.
export const parseHostPreference = (value) => {
  if (!isNonEmptyString(value)) return null;
  if (isLanguage(value)) return value;
  const tag = value.trim().toLowerCase();
  if (tag === "english") return LANGUAGES.ENGLISH;
  if (tag === "simplifiedchinese" || tag === "simplified-chinese" || tag === "simplified_chinese") {
    return LANGUAGES.SIMPLIFIED_CHINESE;
  }
  return languageFromLocale(tag);
};

// Run the ladder. Returns `{ language, source }`; `source` is `env`, `host`,
// `locale` or `default`, which is the fact worth recording in a diagnostic.
// A present-but-unusable explicit value throws; everything below it is a hint.
export const resolveLanguage = ({ explicit = null, host = null, locale = null } = {}) => {
  const fromEnv = parseExplicitLanguage(explicit);
  if (fromEnv !== null) return { language: fromEnv, source: "env" };
  const fromHost = parseHostPreference(host);
  if (fromHost !== null) return { language: fromHost, source: "host" };
  const fromLocale = languageFromLocale(locale);
  if (fromLocale !== null) return { language: fromLocale, source: "locale" };
  return { language: DEFAULT_LANGUAGE, source: "default" };
};

// The binding of one session. `ownerSessionId` is the session that was forked
// from, or null for a top-level session. A binding is immutable: changing a
// session's language after it is bound is impossible here, because spec §6.1
// forbids it (retries, failures, compaction and wake-up never change it).
export class LanguageBinding {
  #ownerSessionId;
  #source;
  #language;
  #sessionId;

  constructor({ sessionId, language, source, ownerSessionId = null }) {
    if (!isNonEmptyString(sessionId)) throw invalidArgument("sessionId must be a non-empty string");
    this.#sessionId = sessionId;
    this.#language = assertLanguage(language);
    this.#source = source;
    this.#ownerSessionId = ownerSessionId;
    // No setter and no freeze of private state: the record can only be made
    // once, in the constructor, and read afterwards.
    Object.freeze(this);
  }

  get sessionId() {
    return this.#sessionId;
  }

  get language() {
    return this.#language;
  }

  get source() {
    return this.#source;
  }

  get ownerSessionId() {
    return this.#ownerSessionId;
  }

  get resourceName() {
    return RESOURCE_NAMES[this.#language];
  }

  // A fork copies the binding under the child's own session id. The child is
  // independent from this point on; the copy keeps the owner for audit only.
  fork(childSessionId) {
    if (!isNonEmptyString(childSessionId)) {
      throw invalidArgument("fork needs the child session id");
    }
    if (childSessionId === this.#sessionId) {
      throw invalidArgument("a fork needs a session id different from its owner", {
        sessionId: childSessionId,
      });
    }
    return new LanguageBinding({
      sessionId: childSessionId,
      language: this.#language,
      source: "fork",
      ownerSessionId: this.#sessionId,
    });
  }

  // A JSON-safe record. `fromRecord` accepts only what this class writes.
  toRecord() {
    return {
      sessionId: this.#sessionId,
      language: this.#language,
      source: this.#source,
      ownerSessionId: this.#ownerSessionId,
    };
  }

  static fromRecord(record) {
    if (record === null || typeof record !== "object") {
      throw refuse(PAIR_HINT_CODES.FACT_CORRUPT, "language record must be an object");
    }
    if (!isNonEmptyString(record.sessionId)) {
      throw refuse(PAIR_HINT_CODES.FACT_CORRUPT, "language record has no session id");
    }
    if (!isLanguage(record.language)) {
      throw refuse(PAIR_HINT_CODES.INVALID_LANGUAGE, "language record names an unknown language", {
        value: record.language,
      });
    }
    return new LanguageBinding({
      sessionId: record.sessionId,
      language: record.language,
      source: record.source,
      ownerSessionId: record.ownerSessionId ?? null,
    });
  }
}

// Bind a session at creation. `forkOf` is a binding or null. A child copies the
// owner's language and ignores the ladder entirely (spec §6.1, §6.1 step 1).
export const bindSession = ({ sessionId, forkOf = null, ladder = {} }) => {
  if (forkOf !== null) {
    if (!(forkOf instanceof LanguageBinding)) {
      throw invalidArgument("forkOf must be a LanguageBinding");
    }
    return forkOf.fork(sessionId);
  }
  const { language, source } = resolveLanguage(ladder);
  return new LanguageBinding({ sessionId, language, source });
};

// §6.1 draws the line: the guard here is formal compliance only — a language
// that cannot be resolved, a resource pair with a missing book, bytes that would
// drift inside an epoch. What the text *says* is the resource's business; this
// package does not check that a resource mentions a subject or keeps a
// sentence, because that would be reviewing content, which §6.1 forbids.
