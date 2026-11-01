// The composition layer's one door to CUE (spec §6.3, §8.2 step 7).
//
// Core owns the renderer (`core/cue.mjs`). This module owns the two shapes the
// composition layer hands to a model, so every surface and every adapter spells
// them the same way:
//
// - `renderReceipt(value)`: a tool result — a park receipt, a review receipt, a
//   fork/join answer. The value as CUE, bare at the root.
// - `renderNotice(value)`: a fact injected into a request as context (the Scope
//   cold-start notice, a refusal detail). The same rendering, or `null` when
//   there is nothing to inject — the caller decides whether to emit anything,
//   and a host must not send the four letters "null" as context.
//
// Neither invents a field. A receipt that gains a field renders that field in
// its own order, and a key the object does not hold enumerably does not appear:
// core attaches `value`/`result` non-enumerably, so the nine-field shape of
// §3.3 stays nine.
//
// `refusalText(error)` is the third shape: a thrown refusal's one-line message
// with its details as CUE on the following lines. The message stays prose (it
// is a sentence, not data); the details are structure, and they keep their shape
// instead of collapsing into a JSON fragment.
//
// Only a refusal this project throws carries `details`: a `CoreError` (core's
// code and details) or a `ComposeError` (a wiring refusal). Any other thrown
// value — a host error, a `TypeError` — is rendered by its message alone, and
// `code` is taken only when the object declares it as an own property, so an
// error that merely inherits `code` does not pass a host constant off as ours.

import { toCue } from "../core/cue.mjs";
import { isComposeError } from "./errors.mjs";
import { isCoreError } from "../core/errors.mjs";

export const renderReceipt = (value) => toCue(value);

export const renderNotice = (value) => (value === null || value === undefined ? null : toCue(value));

export const refusalText = (error) => {
  const message =
    typeof error?.message === "string" && error.message.length > 0 ? error.message : String(error);
  if (!isCoreError(error) && !isComposeError(error)) return message;
  const code = typeof error.code === "string" ? error.code : null;
  const details = error.details;
  const head = code === null ? message : `${code}: ${message}`;
  if (details === null || details === undefined) return head;
  if (typeof details !== "object" || Object.keys(details).length === 0) return head;
  return `${head}\n${toCue(details)}`;
};
