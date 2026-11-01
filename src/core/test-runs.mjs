// Property-test run counts (dev loop only).
//
// Each property test states its own run count. That count is the evidence the
// test gives, and it must not change by accident. This module lets a developer
// lower the count for one local loop with one environment variable. It never
// raises a count above the number the test states.
//
// `HC_FC_RUNS` (optional): a positive integer. Every property test runs
// `min(its own count, HC_FC_RUNS)` times.
// `HC_SEED` (optional): an integer. Every property test uses it as the
// fast-check seed, so a failing case can be replayed.
//
// No variable set: `fcRuns(N)` returns `{ numRuns: N }` and `fcSeed()` returns
// `{}`. In that case the tests run exactly as written.

const readPositiveInt = (name) => {
  const raw = process.env[name];
  if (raw === undefined || raw === "") return undefined;
  const value = Number(raw);
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive integer; received ${JSON.stringify(raw)}`);
  }
  return value;
};

// The fast-check options for one property test.
//
// Spread the result into the options object:
//   { ...fcRuns(200), ...fcSeed() }
// The result holds `numRuns` only, and never `seed`.
export const fcRuns = (defaultRuns) => {
  const cap = readPositiveInt("HC_FC_RUNS");
  return { numRuns: cap === undefined ? defaultRuns : Math.min(defaultRuns, cap) };
};

// The fast-check seed options for one property test. An empty object when
// `HC_SEED` is not set, so the spread changes nothing.
export const fcSeed = () => {
  const seed = readPositiveInt("HC_SEED");
  return seed === undefined ? {} : { seed };
};
