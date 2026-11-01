// The operator's original input as a durable fact (根 §2.4 第 3 步).
//
// These cases pin the three properties the fact exists for:
//
//   - provenance: only a genuine first operator receipt records it, decided
//     from the call's identity (parent, delivery, brief id), never from the
//     words, so no operator sentence can be mistaken for the adapter's own;
//   - durability: the record is published atomically and the first writer wins,
//     so a reader never sees half a document and a later prompt never rewrites
//     the origin;
//   - fail-closed: a session that predates the fact, or a record that cannot be
//     read, refuses instead of substituting a newer message.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import {
  ORIGINAL_REFUSALS,
  captureOriginal,
  markUnrecorded,
  originalFile,
  readOriginal,
  receiptVerdict,
  requireOriginal,
  unrecordedFile,
} from "./original-input.mjs";

const tempRoot = () => fs.mkdtempSync(path.join(os.tmpdir(), "herd-original-"));

test("the first receipt is the original and a later one never replaces it", () => {
  const root = tempRoot();
  const first = captureOriginal(root, "ses_a", "ship the parser fix");
  assert.equal(first.ok, true);
  assert.equal(first.written, true);

  const second = captureOriginal(root, "ses_a", "no, do something else");
  assert.equal(second.ok, true);
  assert.equal(second.written, false, "a later prompt is not a new origin");
  assert.equal(second.record.text, "ship the parser fix");

  assert.equal(requireOriginal(root, "ses_a").text, "ship the parser fix");
  fs.rmSync(root, { recursive: true, force: true });
});

test("an operator sentence that reads like our own notice is still the operator's", () => {
  // Identity comes from the call, never from the text: an operator may type
  // anything, including a sentence that looks like the succession notice.
  const words = "A relay just happened in this work: you are the successor.";
  assert.deepEqual(receiptVerdict({ text: words, parentID: null, delivery: "steer" }), {
    capture: true,
    reason: "TOP_LEVEL",
  });
  const root = tempRoot();
  assert.equal(captureOriginal(root, "ses_a", words).ok, true);
  assert.equal(requireOriginal(root, "ses_a").text, words);
  fs.rmSync(root, { recursive: true, force: true });
});

test("the adapter's own deliveries are recognised by id and delivery mode", () => {
  assert.equal(receiptVerdict({ text: "x", parentID: null, briefID: "msg_abc" }).reason, "OWN_DELIVERY");
  assert.equal(receiptVerdict({ text: "x", parentID: null, delivery: "queue" }).reason, "OWN_DELIVERY");
  assert.equal(receiptVerdict({ text: "x", parentID: null, delivery: "steer" }).capture, true);
  assert.equal(receiptVerdict({ text: "x", parentID: "ses_parent" }).reason, "CHILD_SESSION");
  assert.equal(receiptVerdict({ text: "x", parentID: undefined }).reason, "PARENT_UNKNOWN");
  assert.equal(receiptVerdict({ text: "   ", parentID: null }).reason, "NO_TEXT");
});

test("a child session never records the original", () => {
  // A fork's first prompt is its brief (§4.1); a lane's is its lane input
  // (§4.2). Neither is the operator's words.
  assert.equal(receiptVerdict({ text: "do the slice", parentID: "ses_manager" }).capture, false);
});

test("a session that predates the fact is marked and never captures", () => {
  const root = tempRoot();
  const marked = markUnrecorded(root, "ses_old");
  assert.equal(marked.ok, true);
  assert.equal(marked.marked, true);
  assert.ok(fs.existsSync(unrecordedFile(root, "ses_old")));

  const late = captureOriginal(root, "ses_old", "a later prompt");
  assert.equal(late.ok, false);
  assert.equal(late.refusal, ORIGINAL_REFUSALS.MISSING);
  assert.equal(fs.existsSync(originalFile(root, "ses_old")), false, "nothing was published");

  const read = readOriginal(root, "ses_old");
  assert.equal(read.ok, false);
  assert.equal(read.refusal, ORIGINAL_REFUSALS.MISSING);
  assert.match(read.detail, /before the original was recorded/);
  fs.rmSync(root, { recursive: true, force: true });
});

test("marking never overrides an existing original", () => {
  const root = tempRoot();
  captureOriginal(root, "ses_a", "the real request");
  const marked = markUnrecorded(root, "ses_a");
  assert.equal(marked.marked, false);
  assert.equal(requireOriginal(root, "ses_a").text, "the real request");
  fs.rmSync(root, { recursive: true, force: true });
});

test("publication is atomic: a reader never sees a partial document", () => {
  const root = tempRoot();
  captureOriginal(root, "ses_a", "complete text");
  // The published file parses whole. A temporary file from a crash carries no
  // fact and is ignored, because readers only open the published name.
  const published = fs.readFileSync(originalFile(root, "ses_a"), "utf8");
  assert.doesNotThrow(() => JSON.parse(published));
  fs.writeFileSync(path.join(root, ".Herdchestra", "ses_a.original.json.123.456.tmp"), '{"v":1,"sess');
  assert.equal(requireOriginal(root, "ses_a").text, "complete text");
  fs.rmSync(root, { recursive: true, force: true });
});

test("a concurrent first writer loses and keeps the winner's fact", () => {
  const root = tempRoot();
  const first = captureOriginal(root, "ses_a", "writer one");
  const second = captureOriginal(root, "ses_a", "writer two");
  assert.equal(first.written, true);
  assert.equal(second.written, false);
  assert.equal(second.record.text, "writer one");
  fs.rmSync(root, { recursive: true, force: true });
});

test("an unreadable record refuses and is never overwritten", () => {
  const root = tempRoot();
  fs.mkdirSync(path.join(root, ".Herdchestra"), { recursive: true });
  fs.writeFileSync(originalFile(root, "ses_a"), "{ not json");

  const read = readOriginal(root, "ses_a");
  assert.equal(read.ok, false);
  assert.equal(read.refusal, ORIGINAL_REFUSALS.UNREADABLE);

  const recapture = captureOriginal(root, "ses_a", "replacement");
  assert.equal(recapture.ok, false);
  assert.equal(recapture.refusal, ORIGINAL_REFUSALS.UNREADABLE);
  assert.equal(fs.readFileSync(originalFile(root, "ses_a"), "utf8"), "{ not json", "the only copy survives");
  fs.rmSync(root, { recursive: true, force: true });
});

test("a record for another session is refused, not trusted", () => {
  const root = tempRoot();
  captureOriginal(root, "ses_a", "mine");
  fs.copyFileSync(originalFile(root, "ses_a"), originalFile(root, "ses_b"));
  const read = readOriginal(root, "ses_b");
  assert.equal(read.ok, false);
  assert.equal(read.refusal, ORIGINAL_REFUSALS.UNREADABLE);
  fs.rmSync(root, { recursive: true, force: true });
});

test("a missing fact is MISSING, not an empty original", () => {
  const root = tempRoot();
  const read = readOriginal(root, "ses_none");
  assert.equal(read.ok, false);
  assert.equal(read.refusal, ORIGINAL_REFUSALS.MISSING);
  assert.equal(requireOriginal(root, "ses_none").ok, false);
  fs.rmSync(root, { recursive: true, force: true });
});
