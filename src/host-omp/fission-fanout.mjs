// Lane turn ends reach the fission group (root §4.2 steps 7–10).
//
// The DECISIONS are compose's and only compose's (`compose/fission/fission.mjs`):
//
//   step 7   a completion captured before fission is broadcast to the group and
//            claimed once per lane (`LaneGroup.claimBroadcast`);
//   step 8   a lane's turn end is `Fission.settle(owner, index, result)` — the
//            delivery, and `ConvergenceReady` is compose's conjunction;
//   step 9   the merge order is compose's `mergeOrder()` (0..n−1);
//   step 10  only lane n−1 announces, and `Fission.settle` decides that: it
//            delivers, then asks `LaneGroup.announcement()`, so whichever
//            delivery completes the conjunction announces — always with lane
//            n−1's own stored report. Nothing here re-offers a lane's result.
//
// What this module adds is the one fact compose cannot know: WHICH group a
// settled lane belongs to. A lane id is `<groupId>#<index>` and a group id is
// `<owner>:<n>:<seq>`, but a session's lanes are held by this adapter, so the
// mapping lives here. Nothing in this file writes a group state, a delivery or
// a terminal value; it translates a lane id into `(owner, index)` and hands the
// result to compose.
//
// A lane whose turn ends after its group is terminal is still REPORTED
// (`terminal: true`), because a late report is a fact about the lane, not an
// error of the host's — compose answers it rather than throwing.

// The group a lane id belongs to, read from the lane's own record:
// `{ laneId, index, lane, brief }` (`ports.mjs` `buildLane`).
export const laneAddressOf = (record, groupId) => {
  if (record === null || record === undefined) return null;
  if (!Number.isInteger(record.index) || record.index < 0) return null;
  if (typeof groupId !== "string" || groupId.length === 0) return null;
  return { groupId, index: record.index, laneId: record.laneId ?? null };
};

// The result a lane's turn hands to the group: the lane's own report, in the
// shape a `fork` worker's result has, so a lane and a worker describe their
// work the same way. The TEXT is what compose merges in lane order.
export const laneResult = ({ name, agentId = null, text = null, error = null } = {}) => ({
  name: typeof name === "string" ? name : null,
  agentId: typeof agentId === "string" ? agentId : (typeof name === "string" ? name : null),
  text: typeof text === "string" ? text : null,
  ...(error === null || error === undefined ? {} : { error }),
});

// One turn end, delivered to the group that owns the lane.
//
// `fission` is the live `Fission` of the session that owns the group (compose's
// object). `owner` is that session's identity — the same string the group was
// admitted under, which is what `Fission.settle` keys on.
//
// Answers `{ ok: false, reason }` only when this adapter cannot address the
// group at all (`NO_GROUP`, `LANE_UNKNOWN`); a settle that compose answers with
// a receipt or with `null` (no open group owns that name) is reported as
// `ok: true` with compose's own answer attached, because the turn end itself
// did happen.
export const feedLaneEnd = ({ fission, owner, record, groupId, result = null, onEvent = null }) => {
  const emit = (entry) => {
    if (typeof onEvent !== "function") return;
    try {
      onEvent(entry);
    } catch {}
  };
  if (fission === null || fission === undefined || typeof fission.settle !== "function") {
    return { ok: false, reason: "NO_GROUP" };
  }
  const address = laneAddressOf(record, groupId);
  if (address === null) return { ok: false, reason: "LANE_UNKNOWN" };
  // One call, one delivery, and compose decides everything else: whichever
  // delivery completes the conjunction announces, always with the takeover
  // lane's own report (`LaneGroup.announcement()`). This adapter keeps no
  // result cache and writes no group state.
  const settled = fission.settle(owner, address.index, laneResult(result));
  emit({
    event: settled === null ? "unclaimed" : "settled",
    groupId: address.groupId,
    lane: address.index,
    // `converged` is present only on the announcement compose made — from the
    // takeover lane, and only when `ConvergenceReady` held.
    converged: settled?.converged === undefined ? null : settled.converged,
    terminal: settled?.terminal ?? null,
  });
  return { ok: true, groupId: address.groupId, lane: address.index, settled };
};

// The group id a lane id was built under. A lane id is `<groupId>#<index>` and
// a group id contains no `#`, so the split is unambiguous; a lane id that does
// not carry one is not a lane this adapter built.
export const groupIdOfLane = (laneId) => {
  if (typeof laneId !== "string") return null;
  const at = laneId.lastIndexOf("#");
  if (at <= 0) return null;
  return laneId.slice(0, at);
};
