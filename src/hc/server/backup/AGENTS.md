# Backup and Restore

Local specification for `hc.server.backup`. Root `AGENTS.md` §7.1, §7.2,
§7.3, §7.4 旅程 7（导出、备份与迁移） and §10 are authoritative. This package owns backup policy and
the backup file format; it does not open a database.

## Responsibility

The module turns one coherent domain snapshot into an encrypted file, verifies
and previews it, and applies a merge or replace through explicit ports. It
also owns device-local recovery points and their 7+4+3 retention.

It does not:

- choose a storage engine or write domain records directly;
- upload anything without an explicit target;
- revive execution authority from an imported file;
- treat a local recovery point as an off-device backup.

## Public entry points

`hc.server.backup`:

| Function | Meaning |
|---|---|
| `backup!` | Sample, package, encrypt and write one backup. |
| `open-backup` | Read, verify and decrypt one backup. |
| `preview-restore` | Show merge/replace effects without writing. |
| `preview-fingerprint` | Stable SHA-256 of the confirmed plan projection. |
| `restore!` | Create a recovery point, apply a previewed plan, then fence the app command epoch. |
| `list-backups` | Public metadata from a target; never hides a listing failure. |
| `verify-backup` | Re-open and verify a backup. |
| `list-recovery-points`, `read-recovery-point`, `delete-recovery-point!` | Local recovery-point management. |
| `prune-recovery-points!` | Apply 7+4+3 retention. |
| `restore-recovery-point!` | Roll back to a local recovery point. |

The file implementations are `hc.server.backup.file-target/store` and
`hc.server.backup.file-recovery/store`.

## Ports

`hc.server.backup.port` defines three protocols.

### `DomainPort`

`read-snapshot` returns one coherent neutral state at `:sampled-at`:

```clojure
{:sampled-at "2026-11-03T00:00:00Z"
 :categories {:conversations {:items [...]}
              :assistants    {:items [...]}
              ...}
 :attachments {id {:id ... :size ... :sha256 ... :bytes-base64 ... :ready? true}}
 :imports {backup-id {:digest ... :imported-at ...}}
 :permissions {...}
 :storage {...}}
```

The adapter must not merge writes made after `:sampled-at`. A storage adapter
backed by `hc.server.storage/export-state` maps each exported document's
`:kind` to a category and wraps its `:doc` as the item. It must keep one export
snapshot for the whole call.

`apply-restore!` receives the final category vectors for changed categories,
the attachment map, the import ledger and safe permission defaults. It must
validate the whole plan and apply it in one atomic transaction. A failure must
leave the previous state intact. The adapter may translate the plan to
`hc.server.storage/restore-state!` with a stable `:migration-id`.

`storage-info` is optional and read-only.

### `TargetPort`

`put-object!` writes one complete container. Identical bytes under an existing
ID deduplicate; different bytes under the same ID are
`:BACKUP_OBJECT_CONFLICT`. `list-objects` reads public headers only and reports
a corrupt object as `:integrity :invalid` instead of hiding it.
`verify-object` verifies the whole stored container.

### `RecoveryPointPort`

A local-only store. `write-recovery-point!` must be atomic and must fail rather
than skip protection when space or permissions are insufficient.

## Categories

The ordered category set follows §7.1-2:

`:conversations`, `:assistants`, `:connections`, `:tools`, `:skills`,
`:workspaces`, `:worldbooks`, `:memories`, `:appearance`, `:schedules`.

Defaults:

| Option | Default |
|---|---|
| `:encrypt?` | `true` |
| `:include-secrets?` | `false` |
| `:include-deleted?` | `false` |
| `:include-temp?` | `false` |
| `:include-cache?` | `false` |
| `:include-external-files?` | `false` |
| `:confirm-plaintext-secrets?` | `false` |
| `:confirm-memory-reactivation?` | `false` |
| `:include-expired?` | `false` |
| `:retention-days` | `30` |

Temporary sessions, deleted sessions and rebuildable caches are excluded by
default and counted in the manifest. A linked external workspace contributes
its configuration and display label by default; its files are included only
when explicitly requested. Login sessions and system authorizations are never
exported, even when API keys are requested.

## Password and encryption

- A new encrypted backup needs a password of at least 12 Unicode code points.
- Chinese characters and spaces are accepted. The password is not trimmed.
- An all-whitespace password is refused.
- `:password-confirm` must match exactly when supplied.
- Encryption defaults to on. Turning it off is an explicit choice.
- Including secrets in an unencrypted backup needs
  `:confirm-plaintext-secrets? true`.
- The encryption switch may be remembered; the password is not.

Crypto format:

- PBKDF2-HMAC-SHA256, 64 derived bytes, 16-byte random salt, default 210000
  iterations. The header records the iteration count, so old files keep
  decrypting after the default changes.
- First 32 bytes: AES-256-GCM key. Last 32 bytes: key-check HMAC key.
- A key-check failure is `:BACKUP_WRONG_PASSWORD`. Once the key-check passes,
  a GCM tag failure is `:BACKUP_INTEGRITY`.
- Header and payload digests are checked before key derivation. A newer format
  is `:BACKUP_VERSION_UNSUPPORTED`; an unknown cipher is
  `:BACKUP_ENCRYPTION_UNSUPPORTED`.

## Container format

```text
"HCBK" | container version (1 byte) | header length (u32)
canonical EDN header (UTF-8) | payload bytes
```

The header exposes only what a remote list needs without the password: backup
ID, sampling time, device, application version, format version, encryption
state, payload size and integrity. Category counts and conflicts are inside the
encrypted payload. Canonical EDN sorts map keys and set members, so the same
logical header always produces the same bytes.

The header digest covers the header. AES-GCM associated data covers the format
version, backup ID, sampling/creation time, device, application version,
encryption mode and plaintext descriptors. Key-derivation parameters are
covered by the header digest and verified by the key-check/tag.

## Snapshot rules

- `:sampled-at` is the instant the adapter's coherent read completed. The
  request value is only a start hint; the adapter returns the actual instant
  and the payload/header use it. A missing instant is
  `:BACKUP_SNAPSHOT_INCOHERENT`. This keeps writes after the displayed
  sampling time out of the backup's label.
- Every attachment referenced by an included conversation is either packaged
  with verified bytes or removed from the conversation and listed in
  `:incomplete-attachments` and `:unavailable-attachment-ids`. A successful
  backup never contains a dangling attachment reference. The reference scan
  covers top-level conversation attachments, drafts, messages and message
  versions. A restored ref whose bytes were not packaged loses `:blob-id`,
  keeps `:source-blob-id`, and gains `:restore-status {:status :missing}`.
- An attachment size or digest mismatch is `:BACKUP_INTEGRITY`, not a silent
  incomplete item.
- Secrets are stripped recursively. Key matching normalizes case and removes
  `-`/`_`, so `:api-key`, `:apiKey` and `:api_key` are one name. Login-session
  tokens are never exported. The manifest records whether secrets were
  included and how many fields were removed.
- The manifest records category counts, byte estimates, exclusions, missing
  references and incomplete attachments.

## Preview, merge and replace

`preview-restore` verifies the file first. A wrong password, corrupt container
or unsupported format never reaches the preview. Nothing is written.

The preview result includes `:mode`, `:summary`, `:conflicts`,
`:missing-relations`, `:placeholders`, `:relink-required`, `:needs-review`,
`:incomplete-attachments`, `:memory-reactivation`, `:expired-skipped`,
`:replace-all?`, `:memory-reactivation-required?` and the recovery-point plan.
`preview` is the same operation with expected failures returned as
`{:ok? false :error ...}`. Both `:categories` and the alias `:selection` are
accepted for the category choice.

Every preview returns a `:fingerprint` over the side-effect-free plan
projection (mode, selected categories, summary, conflicts, placeholders,
missing relations, relink/review lists, incomplete attachments, memory
reactivation, expired skips, replace-all and memory-reactivation flags).
`restore!` accepts `:expected-fingerprint`; a mismatch is
`:BACKUP_PREVIEW_STALE` and fails before creating a recovery point or writing
anything. This binds a supervised maintenance confirmation to the exact plan
the operator saw, even across the stop/restart window.

`restore!` supports exactly two names:

- `:merge` keeps local content not involved in the import.
- `:replace` removes the local version of each selected category and uses the
  backup version. Unselected categories are unchanged.

`replace` of all categories is a separate explicit selection; the code never
silently turns a partial selection into a full overwrite.

Conflict rules:

- Identical source content is skipped and counted.
- The same ID with different content is a conflict. Default: keep both with a
  deterministic copy ID.
- For name-identified categories, the same display name with different content
  is a conflict even when the IDs differ. Default: keep both with a
  `（来自备份 yyyy-MM-dd）` suffix.
- Resolutions are `:keep-local`, `:use-backup` and `:keep-both`. The default is
  `:keep-both`; backup time never silently wins.
- `:use-backup` keeps the local ID when the conflict is name-based, so local
  references stay valid.
- Memories are the exception: a local archived/deleted decision is preserved.
  An imported active version becomes a disabled pending-review copy. New
  imported memories are disabled by default. Replacing the whole memory
  category can reactivate entries only with
  `:confirm-memory-reactivation? true`, and the preview lists those entries.
- Deleted records imported from a backup stay in the deleted area. A deleted
  record older than the local retention window is skipped by default and
  listed in `:expired-skipped`; `:include-expired? true` restores it without
  extending its original expiry.

Relations:

- A conversation whose assistant is absent gets a placeholder assistant with
  the original assistant ID, display name `待配置的导入助手` and
  `:needs-configuration? true`. No local assistant is silently chosen.
- A missing workspace marks the conversation
  `:workspace-relink-required? true`. No directory is created and no external
  location is authorized.
- A missing model/tool configuration leaves history readable and requires a
  new selection before a request.

Idempotence:

- Deterministic copy IDs (`<source-id>@backup-<digest>`) and
  `:imported-from {:backup-id ... :source-id ...}` markers make a repeated
  merge skip content it already created.
- The import ledger records the payload digest. A second merge of the same
  unmodified backup returns `:already-imported`.
- Even without the ledger, a second merge returns `:no-changes` because source
  equality and provenance skip every item.

## No implicit permission restore

Imported entities always receive safe defaults:

| Category | Restored state |
|---|---|
| schedules | disabled, paused, no next run |
| auto-backup plans | disabled |
| tools/MCP | disabled, permissions denied, needs review |
| skills | installed, disabled, needs review |
| connections | unverified, needs verification; no test request |
| external workspaces | not authorized, relink required, write disabled |
| memories | disabled pending review unless explicitly confirmed |
| device control, full access, external write, background run | disabled |

The preview lists paused items and missing relations. A restore never opens a
network connection, creates a scheduled result or grants a device permission.

## Recovery points and 7+4+3

`restore!` always creates a local recovery point before applying a mutation.
If the point cannot be created, the restore stops and current data is
untouched. The same protection applies to `:merge` and to
`restore-recovery-point!`.

Default retention:

- the 7 most recent successful local points;
- the newest point in each of the last 4 ISO weeks;
- the newest point in each of the last 3 months.

The union is kept. Pinned points, points with unknown timestamps, points from
another device/kind, failed points and the current restore's point are never
deleted. A recovery point is not an off-device backup.


## Real storage and targets

`hc.server.backup.storage-adapter/domain` implements `DomainPort` over an open
`hc.server.storage` handle.  It reads one
`storage/export-state` package and maps:

| Storage partition / kind | Backup category |
|---|---|
| `sessions` / `:session-record`, `session/<id>` / `:owner-state` | `:conversations` |
| `config` / `:app-config` | `:assistants`, `:connections`, `:appearance` |
| `materials` / `:materials-memory` | `:memories` |
| `materials` / `:materials-worldbook-*` | `:worldbooks` |
| `materials` / `:materials-instruction-*` | `:appearance` |
| `backup` / `:backup-import` | `:imports` |
| `scheduler` / `:scheduler-root`, `:scheduler-schedule`, `:scheduler-run` | `:schedules` |
| extension catalog `:skills` | `:skills` (record + file bytes) |
| extension catalog `:mcp :servers` | `:tools` |
| blob objects referenced by conversation attachments | `:attachments` |

The adapter reads the extension catalog version before and after the storage
export; a changed version is retried and then reported as
`:BACKUP_SNAPSHOT_INCOHERENT`.  Skill file bytes are checked against the
catalog's size/SHA-256 metadata.  Attachment blobs are content-addressed and
verified against their IDs.  A missing skill directory, missing catalog, or
referenced blob without a blob port is reported, not silently omitted.

On replace, scheduler entries are written paused and scheduler obligations are
cleared.  On merge, imported schedules/runs are paused/interrupted while local
scheduler entries keep their state; local scheduler obligations are preserved.  Skills and MCP servers
are written disabled for review.  Skill directories are replaced from the
backup, attachment bytes are written back to the blob store, and the extension
catalog is saved after the atomic storage restore.  A failure after the storage
transaction is reported as a failed restore with the recovery point, never as
success.
`apply-restore!` rebuilds a complete export from the current one, replaces only
the selected categories, and calls `storage/restore-state!` in one atomic
transaction.  It refuses unless the plan or the domain carries
`:quiescent? true`.

Real target implementations:

- `hc.server.backup.file-target/store` — local directory.
- `hc.server.backup.webdav/target` — PUT/GET/DELETE/PROPFIND/MKCOL over HTTP(S),
  Basic auth, no redirect following.
- `hc.server.backup.s3/target` — path-style or virtual-hosted S3-compatible
  endpoint, SigV4, GET/PUT/DELETE/ListObjectsV2.

No target substitutes a local path or reports a fake remote success.

`hc.server.backup.service/service` wraps a storage handle, a target map and a
local recovery store.  `handle!` accepts `:backup/status`, `:backup/create`,
`:backup/list`, `:backup/preview`, `:backup/verify`, `:backup/delete`,
`:backup/recovery-points`, `:backup/prune`; restore commands require the
offline path.  Target configs are `:file`, `:webdav`, `:s3`; commands accept
`:target-id`.  `:backup/status` reports target availability without secrets and
`:restore {:online? false :offline? true :requires-quiescence? true}`.  Target
configs are `{:kind :file :root ...}`, `{:kind :webdav :base-url ...
:directory ... :username ... :password ...}` and `{:kind :s3 :endpoint ...
:bucket ... :region ... :access-key ... :secret-key ... :prefix ...
:path-style? true}`.  A bad target stays listed with `:available? false` and a
reason; a command against it fails `:BACKUP_TARGET_UNAVAILABLE`.

`hc.server.backup.offline/backup!`, `preview!`, `restore!`,
`restore-recovery-point!`, `list-backups` and `list-recovery-points` are the
maintenance entry points.  They open the store with `:create? false`, read the
extension catalog/skill files, blob root and workspace store from the data
directory (or explicit `:extensions-root`/`:blob-root`/`:workspace-store-root`),
run with quiescence, and close it.  `hc.server.app.backup` delegates live
create/list/preview/verify/delete/recovery-point commands to
`hc.server.backup.service/handle!` and refuses live restore; the offline entry
point is the only restore path.

## Command epoch after restore

A whole-store restore does not map the `commands/app` gate document to a
backup category, so the local gate and its receipts can survive the restore.
Starting on that window would let a pre-restore client replay old commands, or
would roll the epoch backward relative to the live high-water mark.  The
restore supervisor therefore captures the live app command window before stop,
and `hc.server.backup.command-epoch` fences it before the restarted system
opens its router:

- the retired high-water is raised to `max(pre-restore high-water, restored
  high-water)`;
- receipts and the command-id index are cleared by the gate transitions;
- a fresh epoch is issued strictly above that high-water;
- the journal records `:command-window`, `:command-epoch-rotated?`,
  `:command-epoch` and `:command-retired-through` before start;
- a rotation failure blocks the restart and is reported as restart failure;
- `prepare!` repeats the fence before a cold start when the journal shows a
  restore may have changed storage.

The rotation uses only the public `hc.server.commands.durable` API; the
pre-window capture is a read-only storage view.  No command core file is
edited or duplicated.

The offline restore path applies the fence in the same storage transaction as
the restored data.  `hc.server.backup.storage-adapter/fence-app-export`
replaces the `commands/app` gate document in the export before
`storage/restore-state!`: the new epoch is above the local gate high-water and
every app-scope creation/reuse identity in the restored sessions, and the
receipt window is cleared.  A crash therefore leaves either old data with the
old epoch or new data with the new epoch, never new data with an old epoch.
A present but malformed gate fails the restore before any write; it is not
silently treated as an empty window.  A missing gate also blocks the restore
with `:BACKUP_COMMAND_EPOCH_WINDOW_MISSING`, because the old high-water cannot
be proven.  Only a caller that can assert the store never issued app commands
may pass `:no-prior-app-commands? true` (CLI:
`--no-prior-app-commands true`); then the fence starts from the restored
session identities or epoch 1.  `offline/restore!` and
`offline/restore-recovery-point!` lift the applied fence epoch to the result.
The supervisor may rotate again before restart; that is a safe extra fence.

The CLI maps only `:complete`, `:no-changes`, `:already-imported`, or
`:applied` without `:needs-attention?`/`:needs-recovery?` to `:ok? true`.
Every other restore receipt is `:ok? false` and exits 1.  No-op restores do not
rotate the epoch because they did not change storage.

`hc.server.backup.maintenance/run!` is the in-process quiescence coordinator.
It requires `:stop!` and `:restore!`; `:stop!` must return `true`,
`{:stopped? true}` or `{:quiescent? true}` (a bare `{:ok? true}` is refused).
A missing, throwing, contradictory or unconfirmed stop returns `:stop-failed`
and never runs restore.  Restore success requires an explicit success status
(`:complete`, `:no-changes`, `:already-imported`, `:applied`) or plain
`{:ok? true}`; `{:status :failed}`, `:cancelled`, `:ok? false`, an error,
`:needs-attention? true` or `:needs-recovery? true` are failures.  A failed
restore still attempts restart; a failed or contradictory restart is never
reported `:complete` (`:restart-failed`, `:failed-needs-attention`).

`hc.server.app.restore-supervisor` is the process-level supervisor built on
this coordinator.  It previews while the application is live, stages the
selected backup from its real target into a local file target, writes a
durable journal, closes the command gate, drains handlers, stops every writer,
runs `offline/restore!` with the preview fingerprint, starts a fresh system,
and publishes the terminal status for a reconnecting client.  The live Sente
router still refuses `:backup/restore`; only the supervisor's
stop -> offline -> start path can restore.

The offline CLI uses the same durable maintenance journal, not a second
recovery protocol.  It records an active `:restoring` entry with
`:request {:source :offline-cli}` before the storage transaction and finishes
it only after every external side effect returns.  A process crash in that
window leaves the active entry; `system/start!` checks the journal before
opening the store or a router and returns a blocked operator result
(`:BACKUP_OFFLINE_RESTORE_INCOMPLETE`).  It never re-runs the restore.  The
same gate covers the supervisor: an active supervisor record blocks with
`:BACKUP_MAINTENANCE_RESTORE_FAILED`, and the supervisor's own in-process
restart passes its maintenance ID so only its matching `:restarting` record is
allowed through.  A cold start never passes an ID.

`journal/blocking-state` is the core predicate behind the gate, the CLI
`status` `:blocks-start?` and the refusal to start a second restore.  It
blocks any active record and any terminal failure that storage may have
reached (`:failed`, `:failed-needs-attention`, `:failed-needs-recovery`,
`:failed-commit-unproven`) until `acknowledge!` records the operator's
comparison.  It does not block `:failed-before-commit` (the restore was
observed not to reach a commit), `:failed` with `:restore-attempted? false`
(the restore thunk never ran), `:stop-failed`, `:restart-failed`, `:complete`,
`:no-changes`, `:already-imported` or `:cancelled`.  `startup-blocking-state`
adds the supervisor-restart ID exception; every other caller is subject to
`blocking-state`.

Every offline journal transition runs inside `journal/locked`: one OS file
lock on `<data-dir>/maintenance/state.lock`, then the in-process monitor. The
begin check, the refusal and the write therefore share one critical section,
so two CLI processes, or a CLI and the supervisor, cannot both decide from a
stale read that they may start. `journal/update!` re-reads inside the same
section; `:expected-digest` refuses a state another process replaced. The lock
file is permanent: unlinking an advisory-locked file would let two processes
lock different inodes under one path.

A supervisor restore reuses its own record only with
`:offline-restore-id` plus the `:request-digest` the supervisor wrote, and
only while the record's `:phase` is `:restoring`. The status alone cannot
separate it, because the supervisor sets `:status :restoring` before it calls
the offline entry point. An offline record is never converted into a
supervisor record.  The supervisor also re-runs its same-ID decision inside
the journal lock: a same-ID/different-digest record is a conflict, and a
same-ID/same-digest record returns the existing receipt instead of starting a
second worker.  A worker crash record only labels the operation whose
maintenance ID and request digest it still holds; a record another operation
replaced is left untouched.

Reconciliation before a cold start never reinterprets an active record that a
live worker or a live recorded PID still owns.  The start gate blocks the
second start instead.

A failure is recorded from `journal/persistence-evidence`: the result's
`:command-epoch` or `:storage-restored?`, the physical `:store-committed?`
version bump from `offline/run-observed-restore`, or a writer's doubt mark.
An exception alone proves nothing.

| Evidence | Terminal status | Next start |
|---|---|---|
| supervisor restore thunk never ran (`:restore-attempted? false`) | `:failed` | open; history |
| `:storage-restored?` / `:command-epoch` / `:store-committed?` | `:failed-needs-recovery` | blocked until acknowledged |
| throw inside the store region with no version evidence (for example an unreadable store) | `:failed-commit-unproven` | blocked until acknowledged |
| store version observed before and after unchanged (`:store-unchanged? true`) | `:failed-before-commit` | open; history |
| success | `:complete` | open |

The same storage evidence decides the terminal status when the restart also
failed.  A proven commit is `:failed-needs-recovery`, an unprovable one is
`:failed-commit-unproven`, and an observed unchanged store is
`:failed-before-commit`; the `:restart` field keeps the restart failure.
`:failed-needs-attention` remains only when the storage evidence is absent or
the restore outcome is unknown.

An exception is never read as "nothing happened": without proof the record is
`:failed-commit-unproven`, never `:failed`. `offline/run-observed-restore`
stamps `:store-observed?` inside the store region, `:store-unchanged?` when the
version did not move, and `:store-committed?` when it did; it reads
`storage/store-info` before and after the restore in the same
`storage/run-uninterrupted` scope, so a commit the adapter could not describe
is still visible as a version bump. A throw with no observation key happened
before the store opened and is `:failed-before-commit`. `:failed` remains only
for records written before this evidence existed (and direct journal writes
with no evidence) and for a pre-restore failure, which carries
`:restore-attempted? false` and is history.  An old `:failed` record without
that flag still blocks, because it cannot prove anything.
`:needs-recovery? true` means a commit was proven; it is not a generic warning
flag. The journal records `:storage-identity` (the resolved store path),
`:restore-plan-digest` over the plan-bearing request fields (never the
password), `:storage-touched?`, `:store-unchanged?`, `:commit-unproven?`, and
the terminal `:restore` evidence.  The supervisor records
`:storage-identity` when it accepts the request, so acknowledging a supervised
failure binds the same journal state, plan and store as an offline failure.

`journal/acknowledge!` is the only unblocking path for a terminal failure. It
keeps `:status`, `:error` and the restore evidence unchanged and adds
`:acknowledged? true` plus an `:acknowledgment` record (`:code`, `:operator`,
`:at`, `:maintenance-id`, `:status`, `:restore-digest`, `:storage`,
`:storage-drift`, optional `:note`, and a `:digest` binding the journal state,
the plan and the storage identity). It refuses a stale `:expected-state-digest`
or `:expected-restore-digest`, a missing operator, a non-blocking status, and a
second acknowledgment. The operator passes an empty `:expected-storage-drift`
only after the store changed (a restore from another backup, or manual
repair); an unchanged store is refused with
`:BACKUP_MAINTENANCE_STORE_UNCHANGED`. The acknowledgement is a recorded human
comparison, not a claim that the data is consistent. A new `restore` refuses
while an unacknowledged terminal failure is recorded.

The acknowledgment code follows the storage evidence, not only the status: a
proven commit (`:needs-recovery? true` or `:failed-needs-recovery`) requires
`:inspect-and-accept-partial`; an unprovable commit (`:commit-unproven? true`
or `:failed-commit-unproven`) and a generic `:failed-needs-attention` without
evidence require `:inspect-accept-unknown-commit`. `:restore-another-backup`
applies to both. A plain legacy `:failed` with no evidence flags accepts
either code, but the start hint suggests the unknown-commit code. The record
keeps its blocking status; only `:acknowledged?` changes.
`:failed-before-commit` is not acknowledgeable: it does not block, so there is
nothing to unblock.

Every journal writer, the supervisor included, writes inside
`journal/locked`; the supervisor's finish also passes
`:planned-request-digest` so it refuses a record another process replaced.
A finish that runs after a CLI `acknowledge` never erases the operator's
decision: a record with a proven commit stays as acknowledged, and a record
without one keeps the finish evidence under `:previous` with a
`journal/supervised-notice`.

The CLI exposes that path as `status` and `acknowledge`
(`--maintenance-id`, `--operator`, `--acknowledge <code>`, `--state-digest`,
`--restore-digest`, optional `--note`). `status` prints the digests to copy
and `:blocks-start?`; its receipt also carries `:restore-attempted?`, so a
supervisor pre-restore `:failed` record is distinguishable from a restore
failure.
Neither command changes the restore outcome.

## Exact category support and restore limits

Implemented in the real storage adapter:

| Category | Backed by |
|---|---|
| `:conversations` | session records, owner state, nested message/draft attachment references |
| `:assistants` | `config` app-config `:assistants` |
| `:connections` | `config` app-config `:connections`, connection order, role bindings |
| `:tools` | extension catalog MCP servers and nested tool records |
| `:skills` | extension catalog skill records plus managed skill files |
| `:schedules` | scheduler partition root, schedules and runs |
| `:worldbooks` | materials books, entries and worldbook session counters |
| `:memories` | materials memory records |
| `:appearance` | app-config remainder, materials root/settings, instruction cards and per-session card selections |
| `:workspaces` | `WorkspacePort` registry; managed files under the workspace store |
| `:attachments` | blob bytes referenced by conversation attachment `:blob-id` |
| `:imports` | backup import ledger |

Workspace rules:

- A managed workspace's files are copied when `:workspaces` is selected.
- A linked external workspace contributes its label and original path only.
  Its files are copied only with `:include-external-files? true`; restored
  bytes go to an app-owned `external-imports/<id>` directory, never to the
  original path.
- Restored external entries are `:relink-required? true :authorized? false`;
  the original path is not created and no filesystem permission is granted.
- A selected `:workspaces` category without a workspace port fails
  `:BACKUP_WORKSPACES_UNAVAILABLE`; it is not reported as an empty category.

Not implemented by the storage adapter:

- `:appearance` is a catch-all for the server-side values above; it is not a
  complete copy of every browser-only visual preference.
- Attachment references with no app-owned `:blob-id` (external/workspace files)
  are listed as incomplete.  Their bytes are not fabricated from a path.
- Skill files are limited to the managed extension root.  Skill execution
  never occurs during backup or restore.

Restore limits:

- Storage restore is one atomic `storage/restore-state!` transaction.
  Extension catalog/skill files, workspace files and blob objects live outside
  that transaction; they are applied around it.  A failure after the storage commit returns a
  failed result with the recovery point and `:storage-restored?` evidence; it is
  never reported as success.
- Imported schedules are paused and imported active runs become
  `:interrupted`; MCP servers/skills are disabled for review; MCP connection
  and auth state are reset; attachment blob references are rewritten to the
  restored content IDs.
- Live restore is refused.  The operator stops the application and uses the
  offline CLI; the app does not auto-stop/restart around it.
- WebDAV/S3 were exercised against local HTTP servers and the SigV4 vector, not
  a live cloud account.
- The whole backup is read into memory; streaming large payloads is not
  implemented.

## Review and recovery rules

- Memory activation is `:status`, not `:enabled?`.  `memory-disabled?`
  recognizes `:status :archived`/`:deleted`; an imported active memory becomes
  `:candidate` unless a replace explicitly confirms reactivation.  A local
  archived/deleted decision is preserved and the imported version is a
  disabled candidate, never a revived active record.  A tombstone stays
  `:deleted`.
- `restore!` and `restore-recovery-point!` return only recovery-point
  metadata (`:id`, `:created-at`, `:reason`, `:scope`, `:bytes`, `:sha256`,
  `:state-bytes`).  The snapshot `:state` is never returned or printed.
- A storage restore writes the import ledger with
  `:external-applied? false`; the adapter marks it true only after skill
  files, workspaces and the extension catalog succeed.  A retry with the
  false flag is `:retry-external?`, not `:already-imported`, and re-runs the
  external steps.
- The same transaction writes a `:restore-safety` document in the `config`
  partition with the safe permission map.  Startup must read it
  (`storage-adapter/restore-safety`) before enabling imported schedules,
  auto-backup, device control, full access or external writes.
- A temporarily missing managed workspace stays in the local state with
  `:missing? true`; a merge preserves its registry entry and does not create
  or delete files.  Only a backup refuses to package it as complete.
- A whole-store restore closes every open `:call` obligation as
  `:outcome-unknown` and every open `:report`/`:retry-call` as `:cancelled`
  with `:restore-cancelled? true`; no pre-restore action remains dispatchable.
- `:appearance` in `:replace` mode removes keys the backup does not contain;
  `:merge` keeps the local remainder.
- A status listing failure is `:ok? false` with `:backups nil` and `:errors`,
  never an empty list.  `:last-backup` comes from a verified-success marker,
  not from an unverified newest object.
- Recovery-point retention selects recent/weekly/monthly slots only from
  successful local points; failed/foreign points are protected but do not
  consume a success slot.
- `acknowledge!` accepts `:restore-another-backup` for both a proven partial
  restore and an unknown-commit record.
- Review markers (`:needs-review?`, `:needs-verification?`, `:installed?`) are
  persisted, so a restart still shows which imported items await review.
- A missing-assistant placeholder keeps the original display name from the
  backup and records a source note.
- `hc.server.backup.error/codes` is the documented failure vocabulary;
  `expected?` remains permissive for caller-defined test codes.

## CLI defaults and restart receipts

`hc.server.backup.cli` is the offline maintenance surface.  Its defaults are
part of the contract:

- a new backup is encrypted (`:encrypt? true`, `:encryption :aes-gcm`);
  `--encrypt true|false` overrides it explicitly;
- `--include-deleted true|false` controls deleted sessions and defaults to
  `false`;
- without `HC_BACKUP_PASSWORD` and without an explicit `--encrypt false`, the
  command fails with `:BACKUP_PASSWORD_REQUIRED`; it never writes a plaintext
  backup by accident;
- a fresh-directory `restore` is allowed because the CLI supplies
  `:no-prior-app-commands? true`; the library path still refuses a missing
  gate unless the caller makes that assertion;
- repeating the same `--mode replace` returns `:already-imported` and does
  not add a recovery point; the same merge is also idempotent;
- a merge/restart receipt is successful only for `:complete`, `:no-changes`,
  `:already-imported`, or `:applied` without `:needs-attention?`/
  `:needs-recovery?`.  A successful restore with a failed restart is
  `:restart-failed`, never `:complete`.

Recheck:

```bash
# default encryption
HC_BACKUP_PASSWORD='…' clojure -M:server -m hc.server.backup.cli backup ...

# explicit off / on
... --encrypt false
... --encrypt true

# missing password
env -u HC_BACKUP_PASSWORD clojure -M:server -m hc.server.backup.cli backup ...
# => exit 1, :BACKUP_PASSWORD_REQUIRED

# fresh-directory restore and repeated replace
... restore --backup-id <id> --mode replace
... restore --backup-id <id> --mode replace
# second run => :already-imported
```

Failure modes: a missing password silently writing plaintext, a fresh restore
failing because the CLI forgot `:no-prior-app-commands? true`, a repeated
replace adding a second recovery point, or a restart failure mapped to
`:complete`.  None is acceptable.

## Failure codes

Expected failures are `ExceptionInfo` values with `:error`:

`:BACKUP_PASSWORD_REQUIRED`, `:BACKUP_PASSWORD_INVALID`,
`:BACKUP_PASSWORD_TOO_SHORT`, `:BACKUP_PASSWORD_BLANK`,
`:BACKUP_PASSWORD_MISMATCH`, `:BACKUP_WRONG_PASSWORD`,
`:BACKUP_FORMAT_INVALID`, `:BACKUP_VERSION_UNSUPPORTED`,
`:BACKUP_ENCRYPTION_UNSUPPORTED`, `:BACKUP_INTEGRITY`,
`:BACKUP_SNAPSHOT_INCOHERENT`, `:BACKUP_EXTENSIONS_UNAVAILABLE`,
`:BACKUP_EXTENSIONS_INCOMPLETE`, `:BACKUP_EXTENSIONS_RESTORE_FAILED`,
`:BACKUP_BLOBS_UNAVAILABLE`, `:BACKUP_CATEGORY_UNKNOWN`,
`:BACKUP_CATEGORY_REQUIRED`, `:BACKUP_CATEGORY_MISSING`,
`:BACKUP_MODE_INVALID`, `:BACKUP_CONFLICT_RESOLUTION_INVALID`,
`:BACKUP_PLAINTEXT_SECRETS_UNCONFIRMED`, `:BACKUP_NOT_FOUND`,
`:BACKUP_OBJECT_CONFLICT`, `:BACKUP_ID_INVALID`, `:BACKUP_IO`,
`:BACKUP_ATOMIC_MOVE_UNSUPPORTED`,
`:BACKUP_STORAGE_CLOSED`, `:BACKUP_STORAGE_DUPLICATE_ID`,
`:BACKUP_TARGET_INVALID`, `:BACKUP_TARGET_UNKNOWN`,
`:BACKUP_TARGET_UNAVAILABLE`, `:BACKUP_TARGET_IO`, `:BACKUP_TARGET_HTTP`,
`:BACKUP_DELETE_UNCONFIRMED`, `:BACKUP_COMMAND_UNKNOWN`,
`:BACKUP_RESTORE_REQUIRES_QUIESCENCE`,
`:BACKUP_QUIESCE_REQUIRED`, `:BACKUP_QUIESCE_NOT_CONFIRMED`,
`:BACKUP_QUIESCE_STOP_FAILED`, `:BACKUP_MAINTENANCE_RESTORE_FAILED`,
`:COMMAND_EPOCH_WINDOW_UNAVAILABLE`, `:COMMAND_EPOCH_WINDOW_MISSING`,
`:COMMAND_EPOCH_GATE_MISSING`, `:COMMAND_EPOCH_WINDOW_INVALID`,
`:COMMAND_EPOCH_SYNC_FAILED`,
`:COMMAND_EPOCH_ISSUE_FAILED`, `:COMMAND_EPOCH_STORE_MISSING`,
`:COMMAND_EPOCH_ROTATE_FAILED`,
`:BACKUP_COMMAND_EPOCH_WINDOW_MISSING`,
`:BACKUP_MAINTENANCE_RESTART_FAILED`, `:BACKUP_PREVIEW_STALE`,
`:BACKUP_MAINTENANCE_ID_INVALID`, `:BACKUP_MAINTENANCE_ID_CONFLICT`,
`:BACKUP_MAINTENANCE_ACTIVE`, `:BACKUP_MAINTENANCE_NOT_FOUND`,
`:BACKUP_MAINTENANCE_UNAVAILABLE`, `:BACKUP_MAINTENANCE_JOURNAL_INVALID`,
`:BACKUP_MAINTENANCE_JOURNAL_FAILED`, `:BACKUP_MAINTENANCE_PREVIEW_INVALID`,
`:BACKUP_MAINTENANCE_INTERRUPTED`, `:BACKUP_MAINTENANCE_OUTCOME_UNKNOWN`,
`:BACKUP_RECOVERY_POINT_MISSING`, `:BACKUP_WORKSPACES_UNAVAILABLE`,
`:BACKUP_WORKSPACES_INCOMPLETE`, `:BACKUP_WORKSPACE_INVALID`,
`:BACKUP_WORKSPACE_INCOMPLETE`, `:BACKUP_WORKSPACE_RESTORE_FAILED`,
`:BACKUP_STORAGE_FULL`, `:BACKUP_RECOVERY_POINT_FAILED`,
`:BACKUP_RECOVERY_POINT_CONFLICT`, `:BACKUP_RECOVERY_POINT_INVALID`,
`:BACKUP_CANCELLED`, `:BACKUP_RESTORE_FAILED`.

`backup!` and `restore!` accept `:on-progress` and `:cancelled?`. Cancellation
before a write or before `apply-restore!` leaves local data unchanged. After a
recovery point is created but before apply, a cancellation returns
`:status :cancelled` with that point.

## Limits and tests

- The package reads a whole backup into memory; the target port owns streaming
  and remote storage. A future streaming container can keep the same header
  and AAD contract.
- Local file writes use a temporary file, fsync and one atomic rename. A
  partially written object or recovery point is never published.
- Tests use temporary directories and temporary embedded stores. They never
  touch a user project or a user database.

```bash
clojure -M:test -n hc.server.backup-test \
  -n hc.server.backup.crypto-test \
  -n hc.server.backup.codec-test \
  -n hc.server.backup.recovery-test \
  -n hc.server.backup.file-target-test \
  -n hc.server.backup.webdav-test \
  -n hc.server.backup.s3-test \
  -n hc.server.backup.workspace-test

clojure -M:test:server -n hc.server.backup.storage-adapter-test
clojure -M:test:server -n hc.server.backup.storage-adapter-regressions-test
clojure -M:test:server -n hc.server.backup.maintenance-test
clojure -M:test:server -n hc.server.backup.command-epoch-test
clojure -M:test:server -n hc.server.backup.fingerprint-test
```

`command-epoch-test` also covers the same-transaction restore fence: the
export replaces the app gate above the local high-water and session
identities, a malformed or unprovable missing gate fails before write, the
explicit no-prior-commands assertion permits a fresh window, the offline
result lifts the applied epoch, and the CLI maps failed or attention-needing
restore receipts to `:ok? false`.

`storage-adapter-regressions-test` exercises the rules that the neutral
in-memory fixtures cannot see: real memory `:status` preservation, a
version-only attachment, the actual export sampling instant, a replace that
removes absent appearance keys, recovery-point metadata without `:state`, the
durable restore-safety record, and closing pre-restore action obligations.

`restore-supervisor-test` covers the offline and supervised journal. An
unfinished `:restoring` entry blocks `startup-blocking-state`. Two concurrent
begins yield exactly one success and one `:BACKUP_MAINTENANCE_ACTIVE`. A
supervisor reuse needs the exact request digest and phase, and never converts
an offline record. A store observed before and after unchanged is
`:failed-before-commit` and does not block the next `system/start!`; a version
bump or adapter fence is `:failed-needs-recovery`; an interrupted throw or an
unreadable store is `:failed-commit-unproven` only when the store version did
not prove an unchanged store; otherwise it is `:failed-before-commit`. The
blocking statuses keep the next start blocked until `acknowledge!` records the
operator's digests; the acknowledged record keeps its failure status and its
error. The tests also refuse a stale state or plan digest, a missing operator,
an unchanged store, a second acknowledgment, a mismatched acknowledgment
code, a finish that would overwrite an acknowledgment, and a new restore over
an unacknowledged failure, and they check that `reconcile-state` keeps the
commit evidence in the status it derives.

The supervisor cold-start regressions prove that an unacknowledged
`:failed-needs-recovery` / `:failed-commit-unproven` supervisor record blocks
`system/start!` with `:BACKUP_MAINTENANCE_RESTORE_FAILED`, while
`:failed-before-commit`, an explicit `:restore-attempted? false` pre-restore
failure and an acknowledged record do not.  They also prove that an active
record is allowed only for the supervisor's own `:restarting` maintenance ID,
that `prepare!` reconciles an interrupted `:restoring` record to
`:failed-needs-attention` before the gate, and that a blocked record is not
rotated first.  They also cover a same-ID request that races into the journal
lock, a crash record that must not relabel another operation, a nil guarded
update that must leave the journal unchanged, an active record owned by a live
worker that reconciliation must leave alone, the storage-evidence status when
the restart also failed, and acknowledgment codes that follow the evidence
rather than the status alone.

The suite covers password policy, wrong-password versus corruption, header and
payload tampering, coherent sampling, secret removal, incomplete attachments,
merge conflicts, memory no-revival, replace isolation, no implicit authority,
idempotent repeated imports, recovery-point failure, 7+4+3 retention, a real
file-target round trip, a real Datalevin store round trip through the storage
adapter and offline entry point, real extension-catalog/skill-file and
scheduler-partition restoration, blob-backed attachment restoration, managed
and linked-workspace filesystem restoration, and real WebDAV/S3 HTTP round
trips against local servers.  Maintenance tests cover absent/unconfirmed stop,
result-valued restore failures, restart failures and no-side-effect refusal.
Command-epoch tests cover high-water fencing, receipt clearing, fresh-epoch
issuance, epoch-above-floor enforcement and structured corrupt-window refusal.

Linked external workspace files remain configuration-only unless the caller
passes `:include-external-files? true`; even then restore writes an app-owned
copy and marks the original path relink-required, without authorization.  An
attachment reference with no app-owned blob ID is listed as incomplete; it is
not fabricated from a path.
