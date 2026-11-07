(ns hc.server.attachments
  "Public attachment ingestion and validation facade.

  Authority: root AGENTS.md §3.4, §6.7 and §7.4 旅程 6.6（多模态附件）.

  The facade keeps three concerns explicit:

  - `model`: pure product limits, descriptor validation, model compatibility
    and send preparation;
  - `media`: real byte sniffing, image metadata/downscaling, strict text
    preview, and honest PDF/media facts;
  - `ingest`: application-owned local copies through the managed blob store,
    optional real extraction, workspace snapshot/association, and verification.

  The HTTP route is separate (`hc.server.attachments.http`) so the application
  can mount bytes on an independent channel without teaching Sente about
  attachments."
  (:require [hc.server.attachments.http :as http]
            [hc.server.attachments.ingest :as ingest]
            [hc.server.attachments.media :as media]
            [hc.server.attachments.model :as model]))

;; Product limits
(def max-attachments model/max-attachments)
(def max-attachment-bytes model/max-attachment-bytes)
(def default-limits model/default-limits)

;; Pure policy
(def normalize-name model/normalize-name)
(def normalize-mime model/normalize-mime)
(def mime->kind model/mime->kind)
(def service-limits model/service-limits)
(def model-capabilities model/model-capabilities)
(def compatibility model/compatibility)
(def compatible? model/compatible?)
(def validate-draft model/validate-draft)
(def prepare-send model/prepare-send)
(def summary-message model/summary-message)
(def descriptor-issues model/descriptor-issues)
(def canonical-blob-id? model/canonical-blob-id?)
(def valid-workspace? model/valid-workspace?)

;; Media inspection
(def sniff-mime media/sniff-mime)
(def resolve-mime media/resolve-mime)
(def image-info media/image-info)
(def prepare-image media/prepare-image)
(def read-text media/read-text)
(def decode-utf8 media/decode-utf8)
(def jpeg-orientation media/jpeg-orientation)

;; Ingestion and workspace
(def service ingest/service)
(def ingest-stream! ingest/ingest-stream!)
(def ingest-file! ingest/ingest-file!)
(def ingest-bytes! ingest/ingest-bytes!)
(def extract-attachment! ingest/extract-attachment!)
(def workspace-attachment! ingest/workspace-attachment!)
(def refresh-workspace! ingest/refresh-workspace!)
(def snapshot-workspace! ingest/snapshot-workspace!)
(def resolve-workspace-path ingest/resolve-workspace-path)
(def verify! ingest/verify!)
(def open-attachment ingest/open-attachment)

;; HTTP
(def handle-http http/handle)
