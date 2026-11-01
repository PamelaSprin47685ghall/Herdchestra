// Pair Hint: the session language, its pair resources, and one synthetic
// call/result pair injected into every outbound request (spec §6.1).
//
// Public surface:
//   LANGUAGES, RESOURCE_NAMES, DEFAULT_LANGUAGE
//   resolveLanguage, bindSession, LanguageBinding
//   RESOURCE_IDS, ResourceLibrary, loadPair
//   renderPresentation, renderHandles, parseHint, factFor, PresentationFact,
//   syntheticMessages, digestOf, assertIndex
//   canonicalMessage, canonicalMessages, canonicalKey, CANONICAL_PART
//   findInjection, findAllInjections, callIdParts
//   PairHintInjector, INJECTION_MODES
//   PairHint, MemoryJournal, JournalStore, REQUEST_KINDS, REBASE_REASONS
//   PairHintError, PAIR_HINT_CODES

export * from "./errors.mjs";
export * from "./language.mjs";
export * from "./resources.mjs";
export * from "./presentation.mjs";
export * from "./canonical.mjs";
export * from "./injection.mjs";
export * from "./facts.mjs";
export * from "./pair-hint.mjs";
