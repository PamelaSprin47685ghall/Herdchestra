# Managed Blob Store

Local specification for `hc.server.blob-store`. Root `AGENTS.md` §7.1 and
§8.6 are authoritative. This package owns bytes; it does not own the domain
reference table.

## Responsibility

Long tool output, large attachments and binary artifacts enter this store
before the domain transaction that makes them visible. The store provides:

- a stable content ID built from SHA-256;
- one atomic transition from a staging file to a finalized object;
- deduplication by content;
- observation and verification of finalized objects;
- reference-aware reclamation that never guesses.

It does not choose a database, open a domain transaction, or decide which
artifact is user-visible. Those responsibilities stay with the storage
adapter and `manage-blob-refs!`.

## Layout

```text
<root>/objects/sha256/<first-two-hex>/<full-hex>   finalized object
<root>/tmp/blob-*.part                             staging file
<root>/.blob-store.lock                            reference/cleanup lock
```

Staging and objects share one root, so the publish step is a same-filesystem
atomic rename. A finalized object is immutable and is never overwritten.

## Stable content ID

The canonical ID is:

```text
sha256-<64 lowercase hexadecimal characters>
```

The ID is a function of the bytes only. The algorithm is part of the ID, so a
future digest change cannot reinterpret old objects. Uppercase hex, alternate
prefixes and whitespace are rejected: one object must have exactly one name.
The object metadata records `:id`, `:algorithm`, `:sha256`, `:size`, `:path`
and `:mtime-ms`. Size is verified, not folded into the ID.

## Write and crash windows

1. `stage` creates a unique file under `tmp/`.
2. `write!` or `write-stream!` appends bytes while updating the digest and the
   size counter in the same step.
3. `commit!` flushes, fsyncs, closes, derives the ID, then atomically renames
   the file to its content-addressed path. A directory fsync follows on a
   best-effort basis.

| Crash point | On-disk result | Recovery |
|---|---|---|
| During staging | incomplete `tmp/blob-*.part` | cleaner removes it after the safety window |
| After fsync, before rename | complete staging file | cleaner removes it after the safety window |
| After rename, before reference commit | complete unreferenced object | cleaner removes it after the safety window |
| After reference commit | referenced object | normal operation |

A reader never sees a partial object, because the final path appears only
after the rename. A failed `commit!` leaves the staging value terminal; call
`abort!` before retrying with a new staging value.

## Reference and cleanup contract

Cleanup requires `:refs` (a collection or map of IDs) or `:referenced?` (a
predicate). Without one it fails with `:BLOB_REFERENCE_SOURCE_REQUIRED`.

The database adapter must hold `with-store-lock` while it commits or removes
references. Cleanup holds the same lock for its whole decision. The lock is a
per-root JVM lock plus an OS file lock, so separate processes using one root
also serialize.

A `:refs` collection is a snapshot. Read it under `with-store-lock`, or pass
`:referenced?` so cleanup queries the current reference state while the lock
is held. The predicate must not call `with-store-lock` again.

Cleanup deletes only:

- objects older than `:safety-window-ms` (default seven days);
- staging files older than the same window, when `:include-temp?` is true;
- entries that the reference source says are unreferenced.

Young objects, referenced objects and unrecognized filesystem entries are
retained. Every reference is resolved before the first deletion; a lookup
failure in that first pass aborts with nothing removed. Each delete rechecks
the reference first; a recheck failure retains that object and stops the pass.
`:dry-run? true` returns the plan without deleting.
`:temp-retained` reports staging files kept because they are young or because
temporary-file cleanup was disabled.

The safety window must exceed the longest interval between finalization and
the reference commit. A reference to a missing object is a storage
inconsistency; callers must fail with `:BLOB_NOT_FOUND` or `:BLOB_INTEGRITY`,
not recreate a blank object.

## Public operations

- `id-of-bytes`, `id-of-stream`, `id-of-hex`, `id->hex`, `valid-id?`
- `stage`, `write!`, `write-stream!`, `commit!`, `abort!`
- `put-bytes!`, `put-stream!`, `put-file!`
- `metadata`, `exists?`, `open-stream`, `read-bytes`, `verify!`, `scan`
- `delete!` (low level; only after the last reference is durably gone)
- `cleanup!`, `with-store-lock`
- `root-path`, `objects-dir`, `temp-dir`, `object-path`, `object-relative-path`

All expected failures are `ExceptionInfo` values with an `:error` keyword.
`expected-error?` and `error-code` classify them.

## Tests

```text
clojure -X:test
```

The tests use temporary roots and cover atomic publish, deduplication,
integrity failure, staging abort, stale-temp reclamation, reference retention,
dry-run behavior, reference lookup failure and random-content round trips.
