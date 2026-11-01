// Which members a role may reach, checked again inside `execute` (§7.1 two
// layers, §2.1 role table).
//
// Visibility (narrowing.mjs) is not authorisation. A model can name a member the
// catalog no longer advertises, and the host has already captured the executor.
// So every program member is looked up here before it runs: the table is
// compose's `SURFACE_MEMBERS`, and a member outside it is refused by name.
//
// There is no per-role exception list: the Manager surface simply has no `call`
// and no file members, so nothing extra needs refusing.

import { SURFACE_MEMBERS, surfaceNameFor } from "../../compose/program/surface.mjs";

export const membersOf = (role) => SURFACE_MEMBERS[surfaceNameFor(role)];

export const memberAvailable = (role, member) => membersOf(role).includes(member);

export const refusalText = (role, member) => `member ${member} is not on the ${surfaceNameFor(role)} surface`;
