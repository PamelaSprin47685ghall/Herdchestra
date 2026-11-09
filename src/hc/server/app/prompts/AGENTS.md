# `hc.server.app.prompts` — role decoration contract

Authority: root `AGENTS.md` §6.1; `src/hc/server/app/AGENTS.md`.

## Marker constant

`hc.server.app.prompts/role-prompt-marker` is the single source of the
`[[hc-role-prompt]]` value. Writers such as
`hc.server.app.steward-model` must reference this constant; copying the
literal string into another namespace is forbidden.

A system message is marked only when its text equals the marker or starts
with the marker followed by a newline (`\n` or `\r\n`). A near miss such as
`[[hc-role-prompt]]X` is not a marker and must not suppress injection.

## Prompt idempotence

`decorate-request` may run more than once for the same frozen request
(gateway retry, re-entry) and may receive a request whose system messages
already carry a role prompt from a caller such as
`hc.server.app.steward_model`. The role prompt must never appear twice.

Before injecting a role prompt, decoration checks the existing system
messages:

1. preferred: any system message that is exactly the marker or starts with
   the marker followed by a newline.
   The marker normally means the caller already owns prompt decoration,
   including a caller-supplied custom prompt, so injection is skipped. The
   exception is a marked message that contains another known role's
   fingerprint: that is proof of a different role, not of this role's
   decoration, so this role's prompt is still injected;
2. fallback when no marker exists: any system message that contains the role
   fingerprint.

The role fingerprint is the first 120 characters of the role-specific prompt
with all whitespace removed. It deliberately excludes `COMMON`: the shared
prefix is identical for every role, so using the concatenated prompt would
let a Reviewer prompt suppress a Worker prompt.

A holder is fingerprinted by `holder-prompt`, not by `swarm-worker-prompt`,
so a plain worker prompt cannot hide the holder addendum.

When the fingerprint is present, the prompt is not injected again. The
`[[hc-role-prompt]]` marker stays in the text: it is the durable
idempotence handoff, not decoration noise. Tool injection runs independently
and still adds the role tool when the prompt was already present. A marker is
authoritative for the caller's own decoration unless it proves another known
role. Without a marker, the fingerprint disambiguates the role.

## Missing program role

A request without `:snapshot :program-role` — the raw-model-port path where
the caller removed the role marker — is returned unchanged: no role prompt,
no program tool, no error. The caller owns decoration in that path.

## Chat turns

`:snapshot :chat? true` still adds no role prompt and no tool, and strips any
stale `:tools`/`:tool-choice`.
