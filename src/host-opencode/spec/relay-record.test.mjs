// The relay record layer (根 §2.4 第 3 步). These cases exercise the record, not the
// host: writing it once, reading it back, classifying what a restart must do,
// and deciding what each session may do under it.
//
// Run with: `node --test "src/host-opencode/**/*.test.mjs"`.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import {
  RELAY_GATE,
  RELAY_PHASES,
  SUCCESSION_NOTICE,
  applyBarrier,
  briefIdFor,
  readRelay,
  recoverRelay,
  relayFile,
  relayGate,
  textOfContent,
  relayRecord,
  writeRelay,
} from "./relay-record.mjs";

const tempRoot = () => fs.mkdtempSync(path.join(os.tmpdir(), "herd-relay-"));

const recordFor = (overrides = {}) =>
  relayRecord({
    sessionID: "ses_a",
    incumbent: "ses_a",
    successor: "ses_b",
    tenure: 1,
    milestone: "execute",
    // The host facts this adapter has: both channels refusable.
    requestGate: RELAY_GATE.HOST,
    toolGate: RELAY_GATE.HOST,
    ...overrides,
  });

test("the successor brief id is deterministic and shaped like a host message id", () => {
  const first = briefIdFor({ incumbent: "ses_a", successor: "ses_b", tenure: 1 });
  const second = briefIdFor({ incumbent: "ses_a", successor: "ses_b", tenure: 1 });
  assert.equal(first, second, "the same seed must derive the same id after a crash");
  assert.match(first, /^msg_[0-9A-Za-z]{26}$/);
  // A different pair of identities or a different tenure is a different brief.
  assert.notEqual(first, briefIdFor({ incumbent: "ses_a", successor: "ses_c", tenure: 1 }));
  assert.notEqual(first, briefIdFor({ incumbent: "ses_a", successor: "ses_b", tenure: 2 }));
});

test("a record carries both identities, the barrier and the phase", () => {
  const record = recordFor();
  assert.equal(record.v, 1);
  assert.equal(record.incumbent, "ses_a");
  assert.equal(record.successor, "ses_b");
  assert.equal(record.inheritFrom, "ses_a");
  assert.equal(record.retired, true);
  assert.equal(record.barrier, true);
  assert.equal(record.phase, RELAY_PHASES.COMMITTED);
  assert.equal(record.briefId, briefIdFor({ incumbent: "ses_a", successor: "ses_b", tenure: 1 }));
  assert.equal(record.notice, SUCCESSION_NOTICE);
  assert.equal(record.fenceThrough, 0);
  assert.equal(record.requestGate, RELAY_GATE.HOST);
  assert.equal(record.toolGate, RELAY_GATE.HOST);
  // A host with no refusable request channel is recorded as such, not silently
  // upgraded: the default is the conservative value.
  const bare = relayRecord({ sessionID: "ses_a", incumbent: "ses_a", successor: "ses_b", tenure: 1, milestone: "execute" });
  assert.equal(bare.requestGate, RELAY_GATE.NONE);
  assert.equal(bare.toolGate, RELAY_GATE.HOST);
});

test("the record survives one atomic write and a read back", () => {
  const root = tempRoot();
  const file = relayFile(root, "ses_a");
  const record = recordFor();
  const written = writeRelay(file, record);
  assert.equal(written.ok, true);
  assert.deepEqual(readRelay(file), record);
  // A second write replaces it whole; there is never a half-written record.
  const sending = { ...record, phase: RELAY_PHASES.SENDING };
  assert.equal(writeRelay(file, sending).ok, true);
  assert.equal(readRelay(file).phase, RELAY_PHASES.SENDING);
});

test("a missing, torn or incomplete record is classified, never guessed", () => {
  const root = tempRoot();
  const file = relayFile(root, "ses_a");
  assert.equal(readRelay(file), null);
  assert.equal(recoverRelay(file).kind, "none");

  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, '{"v":1,"sessionID":"ses_a"', "utf8");
  const torn = readRelay(file);
  assert.equal(torn.corrupt, true);
  assert.equal(torn.reason, "RELAY_RECORD_TORN");
  assert.equal(recoverRelay(file).kind, "corrupt");

  fs.writeFileSync(file, JSON.stringify({ v: 1, sessionID: "ses_a", incumbent: "ses_a" }), "utf8");
  const incomplete = readRelay(file);
  assert.equal(incomplete.corrupt, true);
  assert.equal(incomplete.reason, "RELAY_RECORD_INCOMPLETE");
});

test("recovery says what a restart must do", () => {
  const root = tempRoot();
  const file = relayFile(root, "ses_a");
  writeRelay(file, recordFor({ phase: RELAY_PHASES.COMMITTED }));
  assert.equal(recoverRelay(file).kind, "resume");
  writeRelay(file, recordFor({ phase: RELAY_PHASES.SENDING }));
  assert.equal(recoverRelay(file).kind, "resume");
  writeRelay(file, recordFor({ phase: RELAY_PHASES.SENT }));
  assert.equal(recoverRelay(file).kind, "done");
  // A host with no idempotent delivery: re-sending could duplicate, so the
  // record is honest about being undeliverable rather than retried blindly.
  writeRelay(file, recordFor({ phase: RELAY_PHASES.COMMITTED, briefId: null }));
  const undeliverable = recoverRelay(file);
  assert.equal(undeliverable.kind, "undeliverable");
  assert.equal(undeliverable.record.successor, "ses_b");
});

test("a record the host cannot enforce is never resumed", () => {
  const root = tempRoot();
  const file = relayFile(root, "ses_a");
  // A host whose request channel cannot refuse: the retired tenure keeps
  // sending requests, so starting the successor would leave two participants.
  writeRelay(file, recordFor({ phase: RELAY_PHASES.COMMITTED, requestGate: RELAY_GATE.NONE }));
  const requestSide = recoverRelay(file);
  assert.equal(requestSide.kind, "unenforceable");
  assert.equal(requestSide.record.requestGate, RELAY_GATE.NONE);
  // The tool channel alone is not retirement either.
  writeRelay(file, recordFor({ phase: RELAY_PHASES.COMMITTED, toolGate: RELAY_GATE.NONE }));
  assert.equal(recoverRelay(file).kind, "unenforceable");
  // Both refusable, and the same record resumes.
  writeRelay(file, recordFor({ phase: RELAY_PHASES.COMMITTED }));
  assert.equal(recoverRelay(file).kind, "resume");
});

test("the gate retires the incumbent, passes the successor, and blocks on a corrupt record", () => {
  const record = recordFor();
  assert.equal(relayGate(null, "ses_a"), null, "no record: nobody was retired");
  assert.equal(relayGate(record, "ses_a").code, "RELAY_RETIRED");
  assert.equal(relayGate(record, "ses_b"), null, "the successor is the new tenure");
  assert.equal(relayGate(record, "ses_other"), null, "an unrelated session is untouched");
  // A corrupt record cannot say who was retired, so nobody may act.
  const corrupt = { corrupt: true, reason: "RELAY_RECORD_TORN" };
  assert.equal(relayGate(corrupt, "ses_a").code, "RELAY_CORRUPT");
  assert.equal(relayGate(corrupt, "ses_b").code, "RELAY_CORRUPT");
});

test("the barrier keeps the operator's input and the succession notice only", () => {
  const messages = [
    { role: "user", content: "plan the change" },
    { role: "assistant", content: [{ type: "text", text: "working" }] },
    { role: "user", content: "continue" },
    { role: "assistant", content: [{ type: "text", text: "relayed" }] },
  ];
  // `fenceThrough: 0` means the count was not taken, so NOTHING of the retired
  // transcript survives: any pick of "the operator's original input" is a guess
  // that either leaks the conversation the incumbent was answering or drops the
  // real request. The operator's ORIGINAL request travels in `operatorInput`
  // instead, and a relay record carries no draft at all.
  const kept = applyBarrier(messages, recordFor());
  assert.deepEqual(kept, [{ role: "user", content: [{ type: "text", text: SUCCESSION_NOTICE }] }]);
  assert.equal(
    kept.some((message) => message.role === "assistant"),
    false,
    "no assistant turn of the retired tenure survives",
  );
  // The operator's original request rides with the notice.
  const withRequest = applyBarrier(messages, recordFor({ operatorInput: "fix the parser" }));
  assert.match(textOfContent(withRequest[0].content), /fix the parser/);
  assert.equal(withRequest.length, 1, "the operator's request does not re-admit the transcript");
  // A draft on a record is ignored: it is not part of the barrier any more.
  const withDraft = applyBarrier(messages, recordFor({ draft: "a stale draft" }));
  assert.equal(textOfContent(withDraft[0].content).includes("a stale draft"), false);
  // A counted fence drops whole the leading messages that were already on
  // screen when the record was written — here the first three, including the
  // operator message the incumbent had already answered — and keeps the rest.
  const fenced = applyBarrier(messages, recordFor({ fenceThrough: 3 }));
  assert.deepEqual(fenced, [{ role: "user", content: [{ type: "text", text: SUCCESSION_NOTICE }] }, messages[3]]);
  // No operator message at all: only the notice remains.
  const none = applyBarrier([{ role: "assistant", content: [] }], recordFor());
  assert.deepEqual(none, [{ role: "user", content: [{ type: "text", text: SUCCESSION_NOTICE }] }]);
});

test("the operator's original request survives the cut because the record committed it", () => {
  const messages = [
    { role: "user", content: [{ type: "text", text: "ship the parser fix" }] },
    { role: "assistant", content: [{ type: "text", text: "working on it" }] },
    { role: "user", content: [{ type: "text", text: "old work" }] },
    { role: "assistant", content: [{ type: "text", text: "done" }] },
  ];
  const record = recordFor({ fenceThrough: 4, operatorInput: "ship the parser fix" });
  const kept = applyBarrier(messages, record);
  const texts = kept.map((message) =>
    Array.isArray(message.content) ? message.content.map((part) => part.text ?? "").join("") : message.content,
  );
  // 根 §2.4 第 4 步: the notice carries the original request inside it, and nothing the
  // retired tenure answered survives. The request is part of the opening
  // message on purpose — a separate operator turn would be textually identical
  // to the retired tenure's own first user turn.
  assert.equal(kept.length, 1, JSON.stringify(texts));
  assert.match(texts[0], /you are the successor/);
  assert.match(texts[0], /ship the parser fix/);
  assert.equal(texts.includes("old work"), false, JSON.stringify(texts));
  assert.equal(texts.includes("done"), false, JSON.stringify(texts));
  // The committed request is restored even when the fence swallowed the whole
  // copy, which is the case a crash-recovered successor meets.
  const swallowed = applyBarrier([], record);
  // Every synthesized message carries the host's content array, never a bare
  // string: the host's request builder maps over `content`.
  assert.equal(swallowed.length, 1);
  assert.equal(Array.isArray(swallowed[0].content), true);
  assert.match(swallowed[0].content[0].text, /ship the parser fix/);
});

test("every message the barrier synthesizes satisfies the host's content contract", () => {
  // A bare string content crashes the host's request builder
  // (`replaceMedia` maps over `content`), so this is a contract test, not a
  // formatting preference.
  const record = recordFor({ fenceThrough: 2, operatorInput: "ship the parser fix" });
  const kept = applyBarrier(
    [
      { role: "user", content: [{ type: "text", text: "old" }] },
      { role: "assistant", content: [{ type: "text", text: "answer" }] },
    ],
    record,
  );
  for (const message of kept) {
    assert.equal(Array.isArray(message.content), true, JSON.stringify(message));
    for (const part of message.content) assert.equal(part.type, "text");
  }
});
