# Current-State Store

Local specification for `hc.server.storage`. Root `AGENTS.md` §7.1 and §5.3
and `src/hc/server/AGENTS.md` are authoritative. This package owns the domain
current state, its atomic commit, its read barrier and its non-destructive
open rules. It does not own blobs, backup files, the Owner, or the board
protocol.

## Responsibility

Root §7.1 names five responsibilities. They map to the public facade:

| Responsibility | Public call |
|---|---|
| `read-session-view` | `(read-session-view store opts)` |
| `commit-migration!` | `(commit-migration! store migration)` |
| `list-open-obligations` | `(list-open-obligations store opts)`; `:all-partitions? true` scans every initialized partition; a session scan includes partition-scoped obligations unless `:include-partition-scope? false` |
| `manage-blob-refs!` | `(manage-blob-refs! store opts)` |
| `open-close-store!` | `(open-close-store! store opts)` |

Lifecycle and diagnostics: `open-store`, `close-store!`, `store-open?`,
`store-closed?`, `store-info`, `store-path`, `verify-integrity`.

Backup support: `export-state` and `restore-state!` produce and consume a
self-describing, format-versioned package.

Expected failures are `ExceptionInfo` values with an `:error` keyword.
`expected-error?`, `error-code` and `error-describe` classify them. The code
set lives in `hc.server.storage.error/codes`.

## Engine decision

Datalevin `1.2.10`, KV API (durable LMDB). The dependency belongs in the
`:server` alias as exactly one candidate:

```clojure
datalevin/datalevin {:mvn/version "1.2.10"}
```

Measured on this host (`HACK.md` records the details):

- a missing directory is created silently by `open-kv`; a foreign non-empty
  directory is adopted and polluted with LMDB files. Both are why
  `layout/classify` runs before the native open;
- a second open of the same environment in one process throws;
- `transact-kv` applies a multi-op vector atomically and `[:del key]` works;
- `d/sync` with a non-zero force flushes to disk;
- the read-only LMDB flag is not usable for this store because Datalevin
  needs a writable environment to open its metadata DBI. `:read-only?` is a
  logical mode: the adapter refuses writes, the directory must still be
  writable;
- LMDB keys are limited to 511 bytes; this adapter rejects keys over 500 bytes.

## One table

One sub-database named `state` holds every record. One `transact-kv` call is
one LMDB write transaction, so a migration can commit documents, obligations,
blob references, metadata and its receipt together. No cross-database
coordination is needed.

Key grammar (`hc.server.storage.keys`):

```text
d/<partition>/<session>/<kind>/<id>   domain document
o/<partition>/<session>/<id>          obligation
b/<partition>/<id>                    blob reference
m/...                                 store and partition metadata
r/<version>/<migration-id>            commit receipt
i/<migration-id>                      receipt index
```

`<session>` is `_` for partition-scoped records. Segments are UTF-8
percent-encoded; only `[A-Za-z0-9.:@-]` stays literal. A literal underscore
is encoded, so the sentinel cannot collide.

Values are envelopes:

```clojure
{:storage/kind :task
 :storage/id "t1"
 :storage/partition "wt-main"
 :storage/session-id "s1"
 :storage/version 42
 :storage/updated-at "2026-11-03T..."
 :doc <caller document>}
```

Views return the caller document plus the reserved `:storage/id`,
`:storage/kind`, `:storage/partition`, `:storage/session-id`,
`:storage/version` and `:storage/updated-at` keys. Domain code must not use
the `:storage/` namespace for its own fields.

Kinds are free-form keywords or strings, with two exceptions: `:obligation`
and `:blob-ref` are rejected in `:put` because they have dedicated channels.
`read-session-view` groups known kinds:

- `:session` -> `:session`
- `:owner-session`, `:owner-state` -> `:state`
- `:message` -> `:messages`, ascending by `:seq` then `:message-id`
- `:task`, `:action`, `:actor`, `:token-slot` -> `:board`
- `:tenure`, `:ledger`, `:run` -> `:relay`
- every other kind -> `:records {kind [docs]}`

## Commit and read barrier

A migration is data:

```clojure
{:migration-id "uuid" :partition "p" :session-id "s"
 :expected-version 41
 :at "2026-11-03T..." :reason :accept-user-message
 :put    [{:kind :task :id "t1" :doc {..}}]
 :delete [{:kind :task :id "t1"}]
 :obligations {:put [..] :remove ["id" ..]}
 :blob-refs   {:add [..] :release ["sha256-.." ..]}
 :meta {..}}
```

`migration/normalize` validates the whole shape before any read or write:
unknown keys, duplicate `(kind, id)` pairs, reserved kinds, a non-boolean
`:open?`, a bad `:expected-version` and an oversized document all fail with
`:STORAGE_INVALID_MIGRATION`.

The digest is SHA-256 over a canonical form. `:at` is excluded so a retry
without the timestamp still replays; everything else, including
`:expected-version`, is included. Same `:migration-id` with the same digest
returns the original receipt and the current view; a different digest is
`:STORAGE_MIGRATION_CONFLICT`.

Commit sequence, under a non-interruptible lock:

1. read the receipt index; replay or conflict;
2. read the partition version and compare `:expected-version`;
3. plan all ops with `tx-version = global + 1`;
4. one `transact-kv` call;
5. `sync` in `:sync` durability mode;
6. build the view with the version-stability check.

A view is returned only when the global store version is unchanged before and
after its reads. A concurrent commit makes the check fail; the read retries a
bounded number of times and then reports `:STORAGE_READ_UNSTABLE` rather than
returning a mixed snapshot. `commit-migration!` also returns the post-commit
view, so the Owner never publishes a state the store has not committed.

The commit lock is a `ReentrantLock` acquired with `.lock`, never
`lockInterruptibly`. `engine/transact!` and `engine/sync!` clear the thread
interrupt flag for the native call and restore it afterwards. Callers must
still commit from the Owner thread, not from a Missionary cancellation domain
(§5.3).

Receipts are bounded by `:receipt-window` (default 1024). The oldest receipt
and its index entry are pruned in the same transaction that adds the new one.

## Integrity proof

`open-store` proves the marker and metadata agree, and that the state table is
readable. `verify-integrity` adds the remaining §7.1 proof: it scans every
document, obligation, blob reference, metadata key, receipt and receipt index,
and checks that

- each key decodes and matches its envelope (`:storage/kind`, `:storage/id`,
  `:storage/partition`, `:storage/session-id`, `:storage/version`);
- each obligation document agrees with its key and has a boolean `:open?`
  when present;
- each blob reference agrees with its key;
- every metadata key is known and every partition version is valid;
- every receipt key is canonical for its `:version` and `:migration-id`,
  every receipt has its index entry, and every index entry points to a
  receipt.

Adapters whose partition owns a required state document (the command gate
and the session Owner) additionally reject a positive partition version with
a missing state document as `:COMMAND_STORE_CORRUPT` or
`:OWNER_STORE_CORRUPT`.  They also let storage errors escape instead of
converting a missing partition or document into an empty state.  A generic
integrity scan cannot infer that requirement from the envelope alone.

A failure raises `:STORAGE_CORRUPT` with at most 20 examples. Nothing is
repaired or deleted. The scan is linear in the current state. Direct opens
skip it unless `:verify-integrity? true` is passed; `recovery-entry` enables
it by default because it is the startup path. A failed integrity scan during
`open-store` closes the engine and releases the lock before the error
escapes, so a subsequent inspection or restore is not blocked by a stale
lock.

## Non-destructive open

`layout/classify` runs before the native open:

| Path state | `:create? false` | `:create? true` |
|---|---|---|
| missing | `:STORAGE_NOT_FOUND`, nothing created | create directories, initialize |
| empty directory | `:STORAGE_NOT_INITIALIZED` | initialize |
| marker + environment | open and verify | open and verify |
| environment without marker | `:STORAGE_NOT_INITIALIZED` | initialize only if zero entries |
| marker without environment | `:STORAGE_CORRUPT` | `:STORAGE_CORRUPT` |
| foreign non-empty directory | `:STORAGE_NOT_A_STORE` | `:STORAGE_NOT_A_STORE` |
| file | `:STORAGE_NOT_A_STORE` | `:STORAGE_NOT_A_STORE` |

Initialization sequence:

1. create the directory if allowed;
2. open the environment and the `state` DBI;
3. refuse if the environment has any sub-database other than `state`, any key
   outside the initialization metadata, or any user/obligation/blob/receipt
   entry (an unknown environment is never adopted). This catches a foreign
   LMDB environment that happens to contain a `data.mdb`;
4. commit `m/format-version`, `m/store-id`, `m/created-at`, `m/store-version`
   and sync;
5. write `store.edn` atomically: temp file, fsync, atomic rename, directory
   fsync.

A crash between 2 and 4 leaves an environment with no marker. The next open
with `:create? true` finishes initialization only if the table is empty; a
`:create? false` open refuses it. A crash after 4 but before 5 leaves a valid
metadata table; the same rule applies. No user data can exist in either
window.

On an existing store, the marker and the metadata must agree on format
version and store ID, and `m/store-version` must be a non-negative integer.
Any disagreement is `:STORAGE_CORRUPT`; nothing is rewritten.

A corrupt environment, a format mismatch and a lock conflict never delete,
truncate or replace anything. They raise and leave the directory intact for
inspection or restore.

## Locking and shutdown

`store.lock` is held for the store lifetime through an OS file lock. A second
process (or a second `Store` in this JVM) gets `:STORAGE_LOCKED`. `:lock?
false` exists for diagnostics; normal operation leaves it true.

`open-close-store!`:

1. set `:accepting? false`, so new commits get `:STORAGE_CLOSING`;
2. call `:drain-fn` with a deadline; timeout, a `false`/`nil` result, or a
   throwable re-enables intake and keeps the store open
   (`:STORAGE_DRAIN_TIMEOUT`, `:STORAGE_DRAIN_INCOMPLETE`, or the original
   throwable). A timeout also cancels the drain future;
3. call `:handoff-fn` with the store; failure also re-enables intake;
4. close the engine and release the lock.

`close-store!` is idempotent. It stops intake, waits for an in-flight commit
under the commit lock, then takes the store's read-write write lock. Every
public read (`read-session-view`, `list-open-obligations`, `store-info`,
`verify-integrity`, `export-state`, and the `manage-blob-refs!` `:list`
branch) holds the read lock and re-checks `:closed?` inside it. A read that
passed `ensure-open!` before the close therefore finishes before the engine
closes, and a read that arrives after the write lock is refused with
`:STORAGE_CLOSED` instead of touching a freed native environment. Commits are
already serialized by the commit lock, so the close order is commit lock then
write lock; readers never take the commit lock. `restore-state!` refuses while
the store is closing. After close, every operation raises `:STORAGE_CLOSED`.

### Datalevin global runtime

`hc.server.storage.engine.datalevin` is the only product namespace that opens
Datalevin. Datalevin keeps process-global non-daemon pools: a scheduler
(`datalevin.util/scheduler-atom`) and an async executor with its workers
(`datalevin.async/executor-atom`). Datalevin owns their shutdown. In 1.2.10,
`datalevin.binding.cpp/close-kv` removes the environment from
`datalevin.lmdb/lmdb-dirs`; when that set becomes empty it calls
`shutdown-last-lmdb-executors!`, which stops the scheduler, the async
executor and the other Datalevin pools.

The engine therefore does not claim or own those pools. Its only lifecycle
duty is to call `d/close-kv` exactly once after `open-kv` returns a handle:
on every successful open, every later open failure and every ordered
shutdown. The last successful close releases the global runtime. A later
open recreates the pools lazily.

Another open non-spill LMDB, including one opened by a host, keeps
`lmdb-dirs` non-empty, so this product's close is not the last close and
Datalevin does not run the last-close teardown. That is Datalevin's own
directory-set rule, not a protection contract for a co-resident host. A host
that relies on the global pools without an open LMDB still loses them at this
product's last close. Such sharing is unsupported unless the host keeps its
own LMDB open as an anchor or patches Datalevin; the storage facade offers no
opt-out. The test runner's suite-boundary `shutdown-last-lmdb-executors!`
remains a fallback for a leaked store or a pool created without an open KV;
its diagnostic prints `datalevin.lmdb/lmdb-dirs` before teardown.

## Blob references

`manage-blob-refs!` supports `:add`, `:release` and `:list`. Add and release
go through `commit-migration!`, so a reference can be established in the same
transaction as the document that uses it. Callers that coordinate with
`hc.server.blob-store` hold `with-store-lock` for the whole operation, as the
blob store's own specification requires. Cleanup must read the reference set
under that lock; this store does not scan blobs.

## Export and restore

`export-state` returns:

```clojure
{:format-version 1
 :engine :datalevin-kv
 :store-id ".."
 :store-format-version 1
 :exported-at ".."
 :complete? true
 :partitions {"p" {:version 3 :meta {..} :last-commit {..}
                   :docs [{:kind .. :session-id .. :id .. :doc ..}]
                   :obligations [{:session-id .. :id .. :doc ..}]
                   :blob-refs [{:id .. :doc ..}]}}}
```

A single-partition export is marked `:complete? false` and cannot be
restored. `restore-state!` requires `:quiescent? true` from the caller,
validates the whole package before touching the disk, then replaces all
documents, obligations, blob references, partition metadata and receipts in
one atomic transaction. Validation failure changes nothing.

## Temporary-session erasure

`erase-session-derived!` removes the local footprint that `SessionPersistence`
does not own:

- every document, obligation and blob reference in `session/<id>`;
- diagnostics records carrying that session ID;
- materials memory candidates whose `:source :session-id` is that session,
  plus instruction/worldbook session-scoped records;
- a defensive `search` partition when one exists.

Call it **before** `SessionPersistence.delete-session!`. That order leaves the
session record as the retryable authority: the persistence can still compute
the blob-reference diff and remove the record, command records and index
entry. The erasure also removes the ID from the session index.

This build has no persisted full-text or vector index; global search is
derived from session records, so removing the record and owner partition is
sufficient. A future index must add its partition here. Temporary sessions
never enter the deleted area; a tombstone is removed by the persistence
delete.

External services already contacted (model provider, web search, speech) are
not revoked by a local discard. The result carries
`:external-effects [{:scope :external-services :revocable? false ...}]` so the
UI can say so.

## Tests

```text
clojure -X:test:server
clojure -M:test:server -n hc.server.storage-test
clojure -M:test:server -n hc.server.storage.session-erasure-test
clojure -M:test:server -n hc.server.storage.engine-lifecycle-test
clojure -M:test:server -n hc.server.recovery-test
clojure -M:test:server -n hc.server.storage.disk-smoke-test
```

The tests use real Datalevin environments in temporary directories. They
cover atomic commit, replay, version conflict, deletes, obligations, blob
references, export/restore, ordered close, read-only mode, every
non-destructive open branch, a randomized put/delete model, all-partition and
partition-scope obligation scans, integrity failures, and a cross-process
smoke that reopens a store in a second JVM and proves the single-writer lock
across processes. `hc.server.storage.session-erasure-test` runs the real
persistence delete followed by `erase-session-derived!` and proves the owner
partition, diagnostics, memory candidates and index entry are gone while the
external-effect disclosure remains. `hc.server.storage.engine-lifecycle-test` proves the
process-global Datalevin runtime is stopped only after the last open store
closes, stays alive while another store is open, is released by a failed last
open, and is also stopped when only a pool-only user pre-existed; an open
non-spill LMDB makes the close non-final, so Datalevin leaves the pools
running.
`hc.server.recovery.restart-smoke-test` adds a three-JVM restart sequence.
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      