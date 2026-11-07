# `hc.core.text-location` — exact UTF-16 slices and bounded nearest diagnostic

Authority: root `AGENTS.md` §4.1 (capability table) and §4.3. This package
implements only the pure text-location part. The program runtime owns
`defprogram`, the `:parked` state machine, `unpark`, and the full receipt.

## Independence

The package requires only Clojure and its own namespaces. It does not read
files, call a host, or depend on another `hc/core` module.

## Public API

```clojure
(where {:content "..."
        :needle "..."})
;; => [begin end]
;; or => {:state :parked
;;        :reason :where-ambiguous
;;        :payload {...}}

(whereAll {:content "..."
           :needle "..."})
;; => [[begin end] ...]

(locate request)
;; => {:kind :unique :span [begin end]}
;; or => {:kind :none :payload {...}}
;; or => {:kind :ambiguous :payload {...}}

(nearest request)            ;; diagnostic only
;; => {:nearest map|nil :budget-exhausted? bool}

(park-signal? value)         ;; true for the where park signal
```

`locate` and `nearest` accept an optional second map to override
`:max-distance`, `:max-lines`, and `:dp-budget` for tests or embedding. The
tool surface uses the §4.3 defaults. `:max-distance` must be at most 4096;
`:max-lines` and `:dp-budget` must fit in a `long`. A non-map request, a
non-string `:content` or `:needle`, or an out-of-range bound throws
`:INVALID_ARGUMENT`; the runtime converts that wiring error into a tool
refusal.

## Matching law

- Matching is literal and exact over UTF-16 code units. Offsets are code
  units, so `(subs content begin end)` returns the exact needle substring.
- The scanner never trims, collapses whitespace, normalizes Unicode, or
  interprets regular expressions. Canonically equivalent spellings do not
  match.
- A zero-width needle is not a slice. `whereAll` returns `[]`; `where` takes
  the zero-match path. This prevents an accidental `[0 0]` anchor.
- `whereAll` reports non-overlapping matches in content order. The next
  search starts at the end of the previous match. This is the useful law for
  replacement: spans never overlap.
- `where` counts every literal occurrence, including overlapping ones. Its
  next search starts one code unit after the previous match. This is the
  useful law for anchoring: `"aaa"` contains `"aa"` at 0 and 1, so `where`
  parks instead of silently choosing 0. `whereAll` still returns `[[0 2]]`
  for that content. The asymmetry is deliberate: anchoring asks "is this
  needle unique?", replacement asks "which spans can I rewrite without
  overlapping?".
- `where` requires exactly one occurrence. Zero occurrences and two or more
  occurrences return a park signal. The signal carries `:payload`; the
  runtime adds `:name`, `:source`, `:pending-operations`, and the other
  receipt fields.

## Zero-match payload

```clojure
{:needle needle
 :candidates []
 :nearest {:span [b e] :text "..." :distance d} ; or nil
 :max-distance 64
 :budget-exhausted false}
```

The nearest diagnostic:

- Scans the first 2000 non-blank lines in content order. A line is blank when
  every code unit in it satisfies `Character/isWhitespace`; a whitespace-only
  line consumes no scan budget.
- Line spans exclude `\n`, `\r\n`, and lone `\r`. `:text` is the exact line.
- Skips a line without DP work when
  `|len(needle) - len(line)| > 64`.
- Trims the common prefix and suffix, then runs Ukkonen banded Levenshtein DP
  with bandwidth `K = 64` over the middle. The band is `|i - j| <= 64`.
- Keeps only distances `<= 64`. The earliest line wins a tie.
- Stops after 10M evaluated DP cells. The nearest already found stays; the
  payload sets `:budget-exhausted true`. A partially evaluated line is not a
  candidate.
- `:text` holds at most 200 UTF-16 code units; the root's "200 characters" is
  read as code units. A longer line sets `:truncated true`. A cut that would
  split a surrogate pair moves back one code unit, so the text may hold 199
  units. `:span` still covers the full line.
- An empty needle has no nearest line. The scan returns immediately without
  reading line spans.
- `banded-distance` reports the number of evaluated DP cells in `:cells`. A
  row-0 budget rejection reports `0`, because no DP cell is evaluated.

## Multiple-match payload

```clojure
{:needle needle
 :candidates [{:span [b1 e1] :context "..."} ...]}
```

- At most eight candidates, in content order. For `where`, candidates include
  overlapping occurrences, matching its uniqueness law.
- `:context` is the match plus up to 80 UTF-16 code units before `b` and 80
  after `e`. Boundary clipping shortens it. If a boundary would split a
  surrogate pair, it moves inward by one code unit, so a side may hold 79
  units.
- `whereAll` returns every span and never parks. An empty result is `[]`.

## Runtime integration

`hc.server.program.runtime` owns the continuation state machine:

- `do-where` calls `text/where`. A unique vector returns to the program. A
  `park-signal?` value becomes `(park! ... :where-ambiguous (:payload ...))`.
- `do-where-all` returns the span vector directly.
- The runtime checkpoints before the scan and after a unique result. A
  park-signal result goes straight to `park!`; an ambiguity that wins before
  the deadline timer is a legitimate park point, not a second one.
- `unpark` injects the value verbatim. The runtime does not re-check the
  span; `subs` and the program own that consequence. A resumed program may
  hit another `where` park; `unpark!` then returns the new park receipt, and
  the next unpark resumes it.
- The compose workspace bridge preserves the full park receipt in `:value`
  when `:parked?` is true, so the Owner projects `:payload` candidates and
  `:nearest` to the model.
- UTF-16 spans survive the runtime unchanged. The runtime test suite covers
  an emoji ambiguous resume and a zero-match nearest resume.

An invalid `:content` or `:needle` throws `:INVALID_ARGUMENT` with both
`:status` and `:error`, so the runtime emits an `:INVALID_ARGUMENT` receipt
rather than a generic `:FAILED`.

## Tests

Run only this package:

```bash
clojure -M:test -n hc.core.text-location-test
```

The `:dev` alias also works:
`clojure -M:dev -e "(require 'hc.core.text-location-test) (clojure.test/run-tests 'hc.core.text-location-test)"`.

The suite covers exact UTF-16 offsets, no trim or normalization, overlapping
uniqueness for `where`, the non-overlapping `whereAll` law, context bounds
and surrogate-safe boundaries, CRLF, CJK and combining characters,
2000-line scanning, text truncation, surrogate-pair cuts, the empty-needle
fast path, option bounds, DP cell accounting, DP budget exhaustion, band
boundaries, and property checks against a full Levenshtein DP and
brute-force scanners (overlapping for uniqueness, non-overlapping for
`whereAll`).﻿
