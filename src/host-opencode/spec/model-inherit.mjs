// The model a session the adapter creates must run (§4.1, 根 §2.4 第 3 步, §6, §7.1).
//
// The host accepts `model` on `Session.create` (`protocol/groups/session.ts:171`,
// `payload: { model: Model.Ref }`; the server forwards it, `handlers/session.ts:110`;
// the core stores it as the new session's own model, `core/src/session.ts:324`).
// It does NOT copy a parent's model into a child: only `Session.fork` inherits
// (`session/projector.ts:167`), and the adapter never uses `Session.fork`.
//
// So a child created without `model` has none, and the runner falls back to the
// agent's default and then to the resolver's own default (`session/runner/model.ts:79-88`).
// On the live host that default is a free model that answers 429. A relay
// successor, a Plan's Manager, a forked worker and a fission lane must run what
// the session they continue was running, so every create carries the parent's
// `model` explicitly.
//
// Where the parent's model comes from: the host's own `session.get` answer. That
// is the only place the adapter can read the fact without guessing — the plugin
// context does not carry a model, and a stored copy in `.Herdchestra/` would go
// stale the moment the operator switches model in the UI.

// A `Model.Ref` as the host stores it: `{ providerID, id, variant? }`. The
// adapter passes the host's own value through unchanged, so this module only
// decides whether a value is usable and where to read it from.
export const isModelRef = (value) =>
  value !== null &&
  typeof value === "object" &&
  typeof value.providerID === "string" &&
  value.providerID.length > 0 &&
  typeof value.id === "string" &&
  value.id.length > 0;

// The model one session runs, asked of the host.
//
//   `{ ok: true, model }`            the session's model
//   `{ ok: false, reason: "NO_SESSION_GET" }`   the host cannot answer
//   `{ ok: false, reason: "SESSION_UNREADABLE", detail }`  the host answered badly
//   `{ ok: false, reason: "NO_MODEL" }`         the session has no model of its own
//
// `NO_MODEL` is a real answer: a session created before this rule existed, or
// one whose operator never picked a model, has none. The caller then refuses to
// create the child rather than letting it fall to the free default.
export const modelOfSession = async (sessions, sessionID) => {
  if (typeof sessionID !== "string" || sessionID.length === 0) {
    return { ok: false, reason: "NO_SESSION" };
  }
  if (sessions === null || sessions === undefined || typeof sessions.get !== "function") {
    return { ok: false, reason: "NO_SESSION_GET" };
  }
  let answer;
  try {
    answer = await sessions.get({ sessionID });
  } catch (error) {
    return { ok: false, reason: "SESSION_UNREADABLE", detail: String(error?.message ?? error) };
  }
  // The host's answer is `{ data: info }` over the HTTP bridge and the info
  // itself in-process; both shapes carry `model` when the session has one.
  const info = answer?.data ?? answer ?? null;
  const model = info?.model ?? null;
  if (isModelRef(model)) {
    return {
      ok: true,
      model: {
        providerID: model.providerID,
        id: model.id,
        ...(typeof model.variant === "string" && model.variant.length > 0 ? { variant: model.variant } : {}),
      },
    };
  }
  return { ok: false, reason: "NO_MODEL" };
};

// The same fact, or a refusal that names why the child would be wrong. Used by
// every create path so none of them can quietly omit the model.
export const requireModelOf = async (sessions, sessionID) => {
  const found = await modelOfSession(sessions, sessionID);
  if (found.ok === true) return found;
  // The host's own words travel with the refusal when it gave any. A caller that
  // reads only `HOST_SESSION_UNREADABLE` learns that the read failed, not why,
  // and the reason is the one thing an operator can act on.
  return { ...found, refusal: modelRefusal(found.reason, found.detail) };
};

// The refusal the program surfaces answer with. A child created without a model
// is a session that will answer 429 under a default nobody chose, which is a
// failure the caller must see, not a slow first token.
export const MODEL_INHERIT_REFUSALS = Object.freeze({
  NO_SESSION_GET: "HOST_HAS_NO_SESSION_GET",
  SESSION_UNREADABLE: "HOST_SESSION_UNREADABLE",
  NO_MODEL: "HOST_SESSION_HAS_NO_MODEL",
  // A caller that named no parent at all: the hand-off has nothing to inherit
  // from, which is a wiring mistake, not a host answer.
  NO_SESSION: "NO_PARENT_SESSION",
});

export const modelRefusal = (reason, detail = null) => ({
  ok: false,
  reason: MODEL_INHERIT_REFUSALS[reason] ?? "HOST_SESSION_MODEL_UNKNOWN",
  detail: typeof detail === "string" && detail.length > 0 ? detail : reason,
});

// The model a SUBAGENT runs (root §1.1 item 6): the operator's task model, not
// the main model. Manager and Plan are the top-level agents and keep the parent
// model; a worker, a fission lane and Plan's dedicated devops are subagents and
// run the task model when one is configured.
//
// Both variables are required. A half-configured pair is a refusal, not a silent
// fallback: the operator named one half of a model, and using the parent's model
// while pretending the task model was configured is the kind of quiet
// substitution that makes a measurement lie.
export const TASK_MODEL_ENV = Object.freeze({
  PROVIDER: "HERDCHESTRA_TASK_PROVIDER_ID",
  MODEL: "HERDCHESTRA_TASK_MODEL_ID",
});

export const taskModelOf = (env = process.env) => {
  const read = (name) => (typeof env?.[name] === "string" ? env[name].trim() : "");
  const providerID = read(TASK_MODEL_ENV.PROVIDER);
  const id = read(TASK_MODEL_ENV.MODEL);
  if (providerID === "" && id === "") return { ok: false, reason: "UNSET" };
  if (providerID === "" || id === "") {
    return {
      ok: false,
      reason: "INCOMPLETE",
      missing: [
        ...(providerID === "" ? [TASK_MODEL_ENV.PROVIDER] : []),
        ...(id === "" ? [TASK_MODEL_ENV.MODEL] : []),
      ],
    };
  }
  return { ok: true, model: { providerID, id } };
};

// The model a child session the adapter creates must run, and where that choice
// came from. `modelSource` is reported in the receipt so a caller can tell the
// task model from the parent fallback without guessing from the values.
//
//   task   the operator configured both variables
//   parent the task model is unset; the parent's own model is used
export const resolveChildModel = async ({ sessions, parentID, env = process.env }) => {
  const task = taskModelOf(env);
  if (task.ok === true) return { ok: true, model: task.model, modelSource: "task" };
  if (task.reason === "INCOMPLETE") {
    return {
      ok: false,
      reason: "TASK_MODEL_INCOMPLETE",
      detail: `${task.missing.join(" and ")} must be set together with ${
        task.missing[0] === TASK_MODEL_ENV.PROVIDER ? TASK_MODEL_ENV.MODEL : TASK_MODEL_ENV.PROVIDER
      }`,
    };
  }
  const found = await requireModelOf(sessions, parentID);
  if (found.ok !== true) return found;
  return { ...found, modelSource: "parent" };
};

