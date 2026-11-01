// Which tools a request may admit (spec §6.2, §6.2, §6.2).
//
// Three facts decide admission, in this order:
//   1. an absent annotation refuses every non-exempt tool, and changes nothing;
//   2. a spent budget refuses every non-exempt tool that does not reset;
//   3. otherwise the annotation is parsed and settled by the book.
//
// Any tool may carry `resetScope` (spec §6.2: every tool call carries a
// `scope` annotation). The annotation, not the tool's name, decides what the
// beat means; the projection decides later which reset actually opened the
// current Scope.
//
// Exempt tools skip rules 1 and 2. Their Scope questions (`negotiate`,
// `askPreviousScope`) answer from the newest snapshot synchronously, so they
// never park and never need a reset to be admitted.

import { DEFAULT_EXEMPT_MEMBERS, SCOPE_CODES, SCOPE_KINDS, refuse } from "./errors.mjs";
import { parseAnnotation } from "./annotation.mjs";

const isBlank = (value) => typeof value !== "string" || value.trim().length === 0;

// The admission rule is a value: `members` is the exempt set of this host.
export class Admission {
  constructor({ exempt = DEFAULT_EXEMPT_MEMBERS } = {}) {
    this.exempt = new Set(exempt);
  }

  get exemptMembers() {
    return Object.freeze([...this.exempt]);
  }

  isExempt(member) {
    return this.exempt.has(member);
  }

  // Returns a refusal or `{ ok: true, exempt, annotation }`. The annotation is
  // the raw value, unparsed; `Scope` settles it. Exempt members do not need
  // one, and an exempt member's annotation is not settled by the book.
  check({ member, annotation, retention }) {
    if (isBlank(member)) {
      return refuse(SCOPE_CODES.SCOPE_ANNOTATION_MISSING, "the tool call has no member name");
    }
    const exempt = this.isExempt(member);
    if (exempt) return { ok: true, exempt: true, annotation: annotation ?? null };

    if (annotation === undefined || annotation === null) {
      return refuse(
        SCOPE_CODES.SCOPE_ANNOTATION_MISSING,
        `tool ${member} needs a scope annotation`,
        { member },
      );
    }
    const parsed = parseAnnotation(annotation);
    if (parsed.ok === false) return parsed;

    // No budget exists in this process yet: only a reset can establish one
    // (spec §6.2). Admitting a keep or a remove here would settle a turn
    // and then refuse it, which is a refusal the caller cannot retry.
    if (!retention.established && parsed.kind !== SCOPE_KINDS.RESET) {
      return refuse(
        SCOPE_CODES.SCOPE_UNINITIALIZED,
        `the budget is not established; tool ${member} must resetScope`,
        { member, kind: parsed.kind },
      );
    }

    if (retention.established && retention.spent && parsed.kind !== "resetScope") {
      return refuse(
        SCOPE_CODES.SCOPE_BUDGET_SPENT,
        `the budget is spent; tool ${member} must resetScope`,
        { member, kind: parsed.kind },
      );
    }
    return { ok: true, exempt: false, annotation: parsed };
  }
}
