(ns hc.server.backup.port
  "Explicit ports between backup policy and the outside world.

  The backup module owns selection, packaging, encryption, preview, conflict
  resolution and recovery-point retention.  It never opens a database and
  never guesses a storage engine.

  `DomainPort`
  ------------
  `read-snapshot` returns one coherent neutral state.  The state shape is
  documented in `hc.server.backup.model`:

      {:sampled-at iso-8601
       :categories {:conversations {:items [...]} ...}
       :attachments {id {...}}
       :imports {backup-id {...}}
       :permissions {...}
       :storage {...}}

  A storage adapter backed by `hc.server.storage/export-state` maps each
  exported document's `:kind` to a category and wraps its `:doc` as the item.
  The adapter must keep one export snapshot for the whole call, so changes
  after `:sampled-at` cannot leak into the payload.

  `apply-restore!` receives the final category vectors for the categories the
  plan changes, plus the import ledger and safe permission map.  It MUST
  validate the whole plan and apply it in one atomic storage transaction.  A
  failure must leave the previous state intact.  The adapter may translate the
  plan to `hc.server.storage/restore-state!` with a stable `:migration-id`.

  `TargetPort`
  ------------
  A backup file target (local directory, WebDAV, S3).  `put-object!` is
  idempotent for identical bytes and never exposes a partially written object.
  `list-objects` reads only public headers and reports corrupt entries instead
  of hiding them.

  `RecoveryPointPort`
  -------------------
  A local, device-only recovery point store.  It is not an off-device backup
  and must not be counted as one.  The default file implementation writes
  restrictive-permission files and keeps the 7+4+3 representative set."
  (:refer-clojure :exclude [read-string]))

(defprotocol DomainPort
  (read-snapshot [port request]
    "Return one coherent neutral state at `:sampled-at`.

    Request keys:
      :sampled-at            ISO-8601 instant chosen by the caller
      :categories            collection of category keywords
      :include-deleted?      include records in the deleted area
      :include-temp?         include temporary sessions
      :include-cache?        include rebuildable cache items
      :include-secrets?      include API keys (never login sessions)
      :include-external-files? include linked external file bytes
      :purpose               :backup, :preview or :recovery-point

    The returned `:sampled-at` is the instant the coherent read completed and
    is authoritative; the request value is only the start hint.  The adapter
    must not merge later writes into the result.")

  (apply-restore! [port plan]
    "Atomically apply a plan produced by `hc.server.backup.merge`.

    The plan contains `:apply-categories` with final vectors for every changed
    category, `:attachments` when conversation attachments change, `:imports`
    for the import ledger and `:permissions` for safe defaults.  The call must
    either apply all of it or leave the prior state untouched.  It returns
    {:status :applied :version n ...} on success and may throw an expected
    `:error` exception on failure.")

  (storage-info [port]
    "Optional read-only description: {:path ... :free-bytes ... :used-bytes ...}
    or nil when the adapter cannot answer."))

(defprotocol TargetPort
  (put-object! [port object]
    "Write one complete backup object.

    `object` is {:id string :bytes byte[] :metadata map}.  The target returns
    metadata including :id, :size, :sha256, :integrity and :deduplicated?.
    Identical bytes under an existing ID return the existing object.  Different
    bytes under an existing ID fail with :BACKUP_OBJECT_CONFLICT.")

  (get-object [port id]
    "Return the complete bytes for `id`, or nil when it does not exist.")

  (delete-object! [port id]
    "Delete one object.  Deleting a missing object is a no-op.")

  (list-objects [port]
    "Return public object metadata without decrypting payloads.  A corrupt
    object is listed with :integrity :invalid; a listing failure must not be
    reported as an empty list.")

  (verify-object [port id expected-sha256]
    "Verify the stored bytes against `expected-sha256` and return metadata.
    A mismatch is :BACKUP_INTEGRITY, not a missing object."))

(defprotocol RecoveryPointPort
  (write-recovery-point! [port point]
    "Atomically write one local recovery point.

    `point` contains :id, :created-at, :reason, :scope, :state and :bytes.
    Insufficient space or a read-only location must fail with
    :BACKUP_STORAGE_FULL or :BACKUP_RECOVERY_POINT_FAILED; never skip the
    protection and continue a replace.")

  (list-recovery-points [port]
    "Return metadata for every readable recovery point, newest first.  Metadata
    includes :id, :created-at, :bytes, :reason, :scope, :kind, :status and
    :integrity.  A corrupt point is listed as :integrity :invalid.")

  (read-recovery-point [port id]
    "Return the stored recovery point map, or nil when it does not exist.")

  (delete-recovery-point! [port id]
    "Delete one recovery point.  Deleting a missing point is a no-op.")

  (recovery-storage-info [port]
    "Return {:path ... :free-bytes ... :used-bytes ...} when known."))
