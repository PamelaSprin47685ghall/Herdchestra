// Host modules the adapter needs (spec §7.2: load only, never fork the host).
//
// Everything is resolved at run time from the host's own module tree. Inside a
// plugin worker the plugin does not sit next to `@cline/core`: the worker's
// `argv[1]` is the sandbox bootstrap, its `cwd` is the session's workspace, and
// `module.paths` of a plugin file points at the plugin's own directory. A plain
// `createRequire(import.meta.url)` therefore cannot see the SDK, and before this
// ladder existed `fork` answered `HostCapabilityMissing` on every real Cline
// Desktop install.
//
// The ladder is ordered by how much the candidate PROVES. Each step is host
// evidence, not a guess:
//
//   1. `@cline/core` / `@cline/sdk` as bare specifiers — an in-process host
//      (tests, `../cline` run from source) resolves them directly.
//   2. `CLINE_CORE_MODULE` — an explicit path or specifier an embedder sets.
//   3. The host's OWN module tree, found from the places a Cline process is
//      guaranteed to leave a trace of itself:
//        - `process.argv[1]` and `process.execPath` ancestors (`node_modules`),
//        - `process.cwd()` ancestors,
//        - the plugin's own ancestors (a checkout next to `../cline`),
//        - the known installation layouts for Cline Desktop on Linux, macOS and
//          Windows, plus `../cline` run from source.
//      Ancestor walking is bounded and deduplicated; every candidate is a real
//      filesystem path that is checked before it is imported.
//
// A candidate that resolves is cached for the process: the SDK does not move
// while the host runs, and a second answer would let two parts of the adapter
// disagree. `hostModuleSource()` reports WHICH step answered, so a receipt can
// explain itself instead of saying "missing".

import { createRequire } from "node:module";

import { enforceFace } from "./runtime.mjs";
import { existsSync, statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const requireFromHost = createRequire(import.meta.url);

const CORE_SPECIFIERS = Object.freeze(["@cline/core", "@cline/sdk"]);

const isFunction = (value) => typeof value === "function";

// How far up an ancestor walk goes. A Cline install never sits deeper than a
// handful of levels below its `node_modules`, and a bound keeps a pathological
// path from turning every `fork` into a filesystem crawl.
const MAX_ANCESTOR_DEPTH = 12;

const isDirectory = (value) => {
  try {
    return statSync(value).isDirectory();
  } catch {
    return false;
  }
};

const isFile = (value) => {
  try {
    return statSync(value).isFile();
  } catch {
    return false;
  }
};

// The ancestors of `start`, nearest first, including `start` itself when it is
// a directory. A file path contributes its directory.
const ancestors = (start) => {
  if (typeof start !== "string" || start.length === 0) return [];
  const out = [];
  let current = path.resolve(start);
  if (!isDirectory(current)) current = path.dirname(current);
  for (let depth = 0; depth < MAX_ANCESTOR_DEPTH; depth += 1) {
    out.push(current);
    const parent = path.dirname(current);
    if (parent === current) break;
    current = parent;
  }
  return out;
};

// The `@cline/core` directories a given base directory could hold, in the order
// a Node resolver would find them.
const coreUnder = (base) => [
  path.join(base, "node_modules", "@cline", "core"),
  path.join(base, "node_modules", "@cline", "core", "dist", "index.js"),
  path.join(base, "plugin-runtime", "node_modules", "@cline", "core"),
  path.join(base, "plugin-runtime", "node_modules", "@cline", "core", "dist", "index.js"),
];

// The layouts a Cline Desktop installation uses. Each is a real location on a
// real platform; a missing one costs one `statSync` and proves nothing.
const INSTALLATION_ROOTS = Object.freeze([
  // Linux, distribution package (this machine: Cline 0.0.49).
  "/usr/lib/Cline/bin/desktop-backend",
  "/opt/Cline/bin/desktop-backend",
  "/opt/cline/bin/desktop-backend",
  // macOS application bundle.
  "/Applications/Cline.app/Contents/Resources/bin/desktop-backend",
  "/Applications/Cline.app/Contents/Resources/app.asar.unpacked/bin/desktop-backend",
  // Windows per-user and machine-wide installs.
  "C:\\Program Files\\Cline\\resources\\bin\\desktop-backend",
  "C:\\Program Files (x86)\\Cline\\resources\\bin\\desktop-backend",
]);

// The `../cline` source checkout: `packages/core` and the workspace root's
// `node_modules`. Both are real layouts of the upstream repository.
const SOURCE_CHECKOUTS = Object.freeze([
  path.join("packages", "core"),
  path.join("packages", "core", "dist", "index.js"),
  path.join("sdk", "packages", "core"),
  path.join("node_modules", "@cline", "core"),
]);

// Every directory that could hold the SDK, in the order the ladder tries them.
const candidateDirectories = () => {
  const directories = [];

  const explicit = process.env.CLINE_CORE_MODULE;
  if (typeof explicit === "string" && explicit.trim().length > 0) directories.push(explicit.trim());

  // The running process. In a worker `argv[1]` is the sandbox bootstrap that
  // lives INSIDE the host's own tree, which is the strongest trace available.
  const processAnchors = [process.argv[1], process.argv[0], process.execPath, process.cwd()];
  for (const anchor of processAnchors) {
    for (const base of ancestors(anchor)) directories.push(...coreUnder(base));
  }

  // The plugin's own location: a checkout checked out beside `../cline`, or a
  // repo that vendors the SDK.
  let pluginDir = null;
  try {
    pluginDir = path.dirname(fileURLToPath(import.meta.url));
  } catch {
    pluginDir = null;
  }
  if (pluginDir !== null) {
    for (const base of ancestors(pluginDir)) {
      directories.push(...coreUnder(base));
      const checkout = path.join(base, "..", "cline");
      for (const inner of SOURCE_CHECKOUTS) directories.push(path.join(checkout, inner));
    }
  }

  // The known installations, plus the same ancestor walk from each of them
  // (a bundle may nest the runtime one level deeper than expected).
  for (const root of INSTALLATION_ROOTS) {
    directories.push(...coreUnder(root));
    for (const base of ancestors(root)) directories.push(...coreUnder(base));
  }

  return directories;
};

// A candidate is either a directory holding a package entry or the entry file
// itself. Both are handed to the loader unchanged; a directory resolves through
// its own `package.json`, which is the host's own rule.
const loadCandidate = async (specifier) => {
  try {
    return requireFromHost(specifier);
  } catch {
    // A worker-thread plugin cannot `require` an ESM-only entry; the dynamic
    // import below is the second chance, not a second source of truth.
  }
  let url;
  try {
    if (specifier.startsWith("file:")) url = specifier;
    else {
      const absolute = path.resolve(specifier);
      // Only a real path is worth importing: an import of a missing path
      // throws, and a directory import needs its package entry to exist.
      if (!isDirectory(absolute) && !isFile(absolute)) return null;
      url = pathToFileURL(absolute).href;
    }
  } catch {
    return null;
  }
  try {
    return await import(url);
  } catch {
    return null;
  }
};

// The exports this adapter needs from the SDK. A module that answers `null` for
// either is not the SDK, whatever its path says.
const usableCore = (mod) =>
  mod !== null &&
  typeof mod === "object" &&
  (isFunction(mod.createDelegatedAgent) ||
    isFunction(mod.createDelegatedAgentConfigProvider) ||
    isFunction(mod.ClineCore?.create) ||
    isFunction(mod.createRuntimeHost));

// One resolution per process.
let resolved = null;

// The walk itself. Called at most once per process, through `inFlight` below:
// two concurrent callers (`fork` and a hand-off in one turn) must not both
// crawl the filesystem and both import the SDK.
const resolveOnce = async () => {
  for (const specifier of CORE_SPECIFIERS) {
    const mod = await loadCandidate(specifier);
    if (usableCore(mod)) {
      resolved = { mod, source: specifier };
      return resolved;
    }
  }
  const seen = new Set();
  for (const directory of candidateDirectories()) {
    const key = path.resolve(directory);
    if (seen.has(key)) continue;
    seen.add(key);
    const mod = await loadCandidate(key);
    if (usableCore(mod)) {
      resolved = { mod, source: key };
      return resolved;
    }
  }
  // A failed walk is NOT cached. `resolved` stays null, so the next caller
  // retries the ladder: one install-time `import` failure (a file an installer
  // was still writing) must not disable fork, fission and devops for the life
  // of the process.
  return { mod: null, source: null };
};

let inFlight = null;

const resolveCore = async () => {
  if (resolved !== null) return resolved;
  inFlight ??= resolveOnce();
  try {
    return await inFlight;
  } finally {
    // A walk that ended with no SDK left `resolved` null (see `resolveOnce`), so
    // the next caller gets its own attempt.
    if (resolved === null) inFlight = null;
  }
};

// The delegated-agent factory pair: what `fork` and a fission lane need. Both
// exports must exist, because a session runtime built from one without the
// other cannot resolve a connection.
export const delegatedAgentApi = async () => {
  const { mod } = await resolveCore();
  if (mod === null) return null;
  if (isFunction(mod?.createDelegatedAgent) && isFunction(mod?.createDelegatedAgentConfigProvider)) return mod;
  return null;
};

// `createDelegatedAgent`, with the worker face enforced on every runtime it
// returns (root §7.2, gap 2).
//
// The host's subagent tool list is decided by the CALLER of
// `createDelegatedAgent` — and a configured agent (one a person declared in
// frontmatter) has its list built by `createSubAgentTools`
// (`extensions/tools/team/configured-agent-tool.ts:160`, wired from
// `runtime/orchestration/runtime-builder.ts:662`). That list reaches the factory
// as `options.tools`, so a frontmatter `tools:` list narrows the child's face
// before any runtime exists, and a narrowed child would lose `js-worker` — and
// with it `this.call`.
//
// A plugin cannot reach `createSubAgentTools`, so the enforcement happens here,
// at the one door every delegated runtime goes through: the factory is wrapped,
// and EVERY runtime it returns is given the worker face through the host's own
// `addTools` (`runtime.mjs` `enforceFace`). The face is the plugin's own tool
// set (`surface.workerTools()`), not whatever the caller passed, so a caller
// that narrowed the list — or added to it — is corrected at the same place.
//
// `workerTools()` is read on every call, not captured: a plugin that registers
// its tools after the first fork still hands the whole face to the next one.
export const guardedCreateDelegatedAgent = (delegated, workerTools) => {
  const create = delegated?.createDelegatedAgent;
  if (typeof create !== "function") return create;
  const face = () => {
    try {
      return typeof workerTools === "function" ? workerTools() : [];
    } catch {
      return [];
    }
  };
  const guarded = (options) => {
    // The requested list still goes in: it is what the host's own prompt and
    // approval logic were built from, and dropping it here would change the
    // host's behaviour rather than restore the face.
    const agent = create(options);
    const enforced = enforceFace(agent, face());
    // A runtime that cannot take the face is reported on the runtime itself: a
    // caller that inspects the receipt sees why `js-worker` may be missing
    // instead of discovering it in a model turn.
    try {
      if (agent !== null && typeof agent === "object") agent.face = enforced;
    } catch {
      // A frozen runtime keeps its own state; the receipt below is enough.
    }
    return agent;
  };
  Object.defineProperty(guarded, "name", { value: "createDelegatedAgent" });
  return guarded;
};

// The host module this adapter actually uses: the resolved SDK with its
// delegated-agent factory replaced by the guarded one. Every caller that needs
// `createDelegatedAgent` goes through `delegatedAgentApi`, so no runtime is
// built on an unguarded door.
export const guardedDelegatedApi = async (workerTools) => {
  const api = await delegatedAgentApi();
  if (api === null) return null;
  return { ...api, createDelegatedAgent: guardedCreateDelegatedAgent(api, workerTools) };
};

// A standalone host for the sessions this adapter starts: a Plan session and the
// Manager a Plan hands off to. `ClineCore.create` is the SDK entry point;
// `createRuntimeHost` is the lower level the SDK itself calls.
export const sessionHostApi = async () => {
  const { mod } = await resolveCore();
  if (mod === null) return null;
  if (isFunction(mod?.ClineCore?.create)) return { kind: "clineCore", mod };
  if (isFunction(mod?.createRuntimeHost)) return { kind: "runtimeHost", mod };
  return null;
};

// Where the host module was found, for a receipt that has to explain a missing
// capability. `null` means nothing was reachable.
export const hostModuleSource = async () => (await resolveCore()).source;

// The credentials for one connection, read from the host's OWN provider
// settings (`~/.cline/data/settings/providers.json`), through the host's own
// `ProviderSettingsManager` and `createProviderConfig`.
//
// Why this exists: `createDelegatedAgentConfigProvider({ providerId, modelId })`
// forwards exactly what it is given. A delegated runtime built with only those
// two fields reaches the provider and dies on "You didn't provide an API key" —
// measured against the live Desktop install. The host already stores the key,
// the base URL and the protocol for the session's provider, and it already knows
// how to turn that entry into a connection config, so the adapter asks it
// instead of inventing a credential path of its own.
//
// The answer is the connection's credential fields ONLY (`apiKey`, `accessToken`,
// `refreshToken`, `accountId`, `baseUrl`, `headers`, `protocol`-derived client
// type). Nothing here is logged, returned to a model, or written anywhere: it
// goes straight into the in-memory config provider.
//
// A host without the manager (an older SDK, a test double) answers `null`, and
// the caller starts the runtime without credentials exactly as before — the
// provider's own error is then the honest report.
export const providerCredentials = async ({ providerId, modelId = null }) => {
  if (typeof providerId !== "string" || providerId.trim().length === 0) return null;
  const { mod } = await resolveCore();
  if (mod === null) return null;
  const Manager = mod.ProviderSettingsManager;
  const createConfig = mod.createProviderConfig;
  if (!isFunction(Manager) || !isFunction(createConfig)) return null;
  let entry;
  try {
    const manager = new Manager();
    const stored = manager.read();
    entry = stored?.providers?.[providerId];
  } catch {
    return null;
  }
  const stored = entry?.settings;
  if (stored === null || typeof stored !== "object") return null;
  let config;
  try {
    // The host's own shape: `createProviderConfig` takes the provider ENTRY's
    // `settings` object (`{ provider, apiKey, baseUrl, protocol, client,
    // model }`). Passing the outer entry answers an all-default connection
    // (`gpt-4o`, the public endpoint, no key), which is worse than none.
    config = createConfig({ ...stored, ...(typeof modelId === "string" && modelId.length > 0 ? { model: modelId } : {}) });
  } catch {
    return null;
  }
  if (config === null || typeof config !== "object") return null;
  return {
    providerId: config.providerId ?? providerId,
    modelId: config.modelId ?? modelId ?? null,
    ...(typeof config.apiKey === "string" && config.apiKey.length > 0 ? { apiKey: config.apiKey } : {}),
    ...(typeof config.accessToken === "string" && config.accessToken.length > 0 ? { accessToken: config.accessToken } : {}),
    ...(typeof config.refreshToken === "string" && config.refreshToken.length > 0 ? { refreshToken: config.refreshToken } : {}),
    ...(typeof config.accountId === "string" && config.accountId.length > 0 ? { accountId: config.accountId } : {}),
    ...(typeof config.baseUrl === "string" && config.baseUrl.length > 0 ? { baseUrl: config.baseUrl } : {}),
    ...(config.headers !== null && typeof config.headers === "object" ? { headers: config.headers } : {}),
    ...(typeof config.clientType === "string" ? { clientType: config.clientType } : {}),
    ...(typeof config.routingProviderId === "string" ? { routingProviderId: config.routingProviderId } : {}),
  };
};

// Whether a credential object carries something a provider will accept: a key,
// an access token, or an explicit auth-free endpoint. Used by the receipts so a
// "started" runtime is never reported without saying what it connected with.
export const hasCredentials = (credentials) =>
  credentials !== null &&
  typeof credentials === "object" &&
  (typeof credentials.apiKey === "string" ||
    typeof credentials.accessToken === "string" ||
    typeof credentials.baseUrl === "string");
