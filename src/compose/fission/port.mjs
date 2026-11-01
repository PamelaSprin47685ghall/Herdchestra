// Fission port (spec §4.2, step 2 to step 6).
//
// The host supplies every function. One contract, two host shapes:
//
//   TWO-STEP (a host that can build a lane without running it)
//     ownerView -> buildLane* -> persistGroup -> startLane* -> replacePresent
//
//   ONE-STEP (a host whose lane IS its first turn)
//     ownerView -> createLane* -> persistGroup -> startLane* -> replacePresent
//
// A two-step host's `startLane` is called only after `persistGroup` answered
// `ok`, which is spec §4.2 step 4: the group record is durable before any lane
// runs. A one-step host cannot honour that literally, so its `startLane` is a
// CONFIRMATION: the lane already ran, and the step answers `{ ok: true }` for a
// lane that exists.
//
// Any failure after the first lane was built runs `abortLanes({ groupId,
// laneIds })` for the lanes that exist; a failure after `persistGroup` also
// runs `discardGroup({ groupId, owner, lanes })` first, because the group is
// about to not exist.
//
// Facts after the build, all addressed by the SESSION that owns the group:
//   settle(owner, laneIndex, result)   a lane's turn ended: its delivery, and
//                                      the takeover lane's announcement when
//                                      ConvergenceReady holds
//   deliver(owner, laneIndex)          a lane handed its work in (the same
//                                      delivery, without the announcement)
//   claim(owner, laneIndex, key)       a lane took one broadcast completion
//   converge(owner, laneIndex, result) the takeover lane (n-1) announces
//   stateOf(owner) / groupOf(owner)    the group's phase / the group itself
//
// A step that returns `{ ok: false, reason }` counts as a failure, exactly like
// a thrown error: the group is aborted. `abortLanes` answers
// `{ ok, released, orphaned }`; a lane the host could not stop is reported as
// orphaned in that receipt and must not be silently dropped.

export const FISSION_PORT_METHODS = Object.freeze([
  "ownerView",
  "buildLane",
  "createLane",
  "persistGroup",
  "discardGroup",
  "startLane",
  "replacePresent",
  "abortLanes",
]);

// `lane` passed to `buildLane`/`createLane` is `{ index, laneId, prompt, laneCount,
// groupId, owner, view }`. `view` is the owner's last delivered view, or null.
//
// `laneCount` is the group's lane count (spec §4.2 step 3), the same number in
// every lane of one group. It is a lane fact, not something the host derives:
// the lane's `index` is its position, never a count. `persistGroup` receives the
// same lanes, so the count travels with the record too.
