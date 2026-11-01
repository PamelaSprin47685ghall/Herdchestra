// Small, dependency-free helpers shared by the core modules.
//
// Every helper is total: it returns a value or throws a TypeError that names
// the offending argument. No helper reads global state.

const fatal = (message) => {
  throw new TypeError(message);
};

export const isObject = (value) =>
  value !== null && typeof value === "object" && !Array.isArray(value);

export const isNonEmptyString = (value) =>
  typeof value === "string" && value.length > 0;

export const isNonBlankString = (value) =>
  typeof value === "string" && value.trim().length > 0;

export const isPositiveInteger = (value) => Number.isInteger(value) && value > 0;

export const isNonNegativeInteger = (value) =>
  Number.isInteger(value) && value >= 0;

export const isUtf16Span = (value) =>
  Array.isArray(value) &&
  value.length === 2 &&
  Number.isInteger(value[0]) &&
  Number.isInteger(value[1]) &&
  value[0] >= 0 &&
  value[0] <= value[1];

export const expectString = (name, value) => {
  if (typeof value !== "string") fatal(`${name} must be a string`);
  return value;
};

export const expectNonBlankString = (name, value) => {
  if (!isNonBlankString(value)) fatal(`${name} must be a non-blank string`);
  return value;
};

export const expectNonEmptyString = (name, value) => {
  if (!isNonEmptyString(value)) fatal(`${name} must be a non-empty string`);
  return value;
};

export const expectArray = (name, value) => {
  if (!Array.isArray(value)) fatal(`${name} must be an array`);
  return value;
};

export const expectPositiveInteger = (name, value) => {
  if (!isPositiveInteger(value)) fatal(`${name} must be a positive integer`);
  return value;
};

export const expectFunction = (name, value) => {
  if (typeof value !== "function") fatal(`${name} must be a function`);
  return value;
};

// Lexical path normalization. `.` and `..` are resolved without touching the
// disk, so a guard can compare two paths before any access.
export const normalizePath = (path) => {
  const absolute = path.startsWith("/");
  const parts = [];
  for (const segment of path.split("/")) {
    if (segment === "" || segment === ".") continue;
    if (segment === "..") {
      if (parts.length > 0 && parts[parts.length - 1] !== "..") parts.pop();
      else if (!absolute) parts.push("..");
      continue;
    }
    parts.push(segment);
  }
  const joined = parts.join("/");
  if (absolute) return `/${joined}`;
  return joined === "" ? "." : joined;
};

// True when `path` resolves inside `root`, root included.
export const isInsideRoot = (root, path) => {
  const base = normalizePath(root);
  const target = normalizePath(path);
  if (base === "" || base === ".") return true;
  return target === base || target.startsWith(`${base}/`);
};

export const byteLength = (text) => Buffer.byteLength(text, "utf8");

// Deterministic JSON: object keys are sorted, so byte equality means value
// equality. Used for callId replay comparison and for persisted records.
export const stableJson = (value) => {
  if (value === undefined) return "undefined";
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(stableJson).join(",")}]`;
  const keys = Object.keys(value).sort();
  return `{${keys.map((key) => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(",")}}`;
};

export const sameJson = (left, right) => stableJson(left) === stableJson(right);

// A plain JSON copy. Throws on cycles or non-JSON values so a record never
// silently loses a field.
export const toJsonValue = (name, value) => {
  let text;
  try {
    text = JSON.stringify(value);
  } catch (error) {
    fatal(`${name} must be JSON-serializable: ${error.message}`);
  }
  if (text === undefined) fatal(`${name} must be JSON-serializable`);
  return JSON.parse(text);
};
