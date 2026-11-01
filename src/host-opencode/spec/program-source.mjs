// The program source a receipt carries (§3.3).
//
// The model sends one `code` string per call; that string is the program. The
// receipt's `source` field is that same text with line numbers, so the model
// reads back exactly what it wrote — no re-indentation, no added wrapper.
//
// The source is not stored: it travels with the call. A program that is resumed
// in a later call still shows the source of the call that parked it, which is
// the call the model must reason about.

export const programSource = (code) => (typeof code === "string" ? code : null);

// `sourceWithLineNumbers` in core does the numbering; this module only decides
// which text is the source, so the rule is one line and testable.
export const sourceFor = (input) => programSource(input?.code);
