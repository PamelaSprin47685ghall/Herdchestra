(ns hc.server.error-taxonomy-test
  "Regression guard for structured error cause preservation.

  The static half is a source-text guard, not a Clojure reader.  It finds
  every `catch Throwable`/`catch Exception` form, then requires each result
  that introduces a new error code to preserve the lower cause through
  `:details {:cause ...}`, a `cause-*` field, or a declared cause-preserving
  helper.  The catch binding symbol alone is never evidence.  Existing unsafe
  catches are a per-file count ratchet: counts may only go down; a decrease
  demands that the baseline be lowered.  The separate known-defect anchors
  fail once the owner adds `:details :cause`, so they cannot become permanent
  exemptions.

  The runtime half injects one lower `:code` into the five structured-result
  entry points and asserts the returned/thrown error exposes it under
  `[:details :cause]`.  A replaced top-level code with empty details fails.
  The `:outcome :partial` registry asserts every producer has a consumer
  regression test."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hc.server.artifacts :as artifacts]
            [hc.server.backup :as backup]
            [hc.server.backup.error :as backup-error]
            [hc.server.backup.port :as backup-port]
            [hc.server.blob-store :as blob]
            [hc.server.diagnostics.service :as diagnostics]
            [hc.server.diagnostics.store :as diagnostics-store]
            [hc.server.recovery :as recovery]
            [hc.server.storage :as storage]
            [hc.server.storage.support :as support])
  (:import (java.io File)
           (java.nio.charset StandardCharsets)
           (java.util.regex Pattern)))

(def ^:private source-root "src/hc/server")

;; ---------------------------------------------------------------------------
;; Static source guard

(defn- clj-files
  [root]
  (->> (file-seq (io/file root))
       (filter (fn [^File file]
                 (and (.isFile file)
                      (str/ends-with? (.getName file) ".clj"))))
       (map (fn [^File file]
              (str/replace (.getPath file) "\\" "/")))
       sort))

(defn- matching-close
  "Index of the paren that closes the form starting at START, or nil."
  [^String text start]
  (let [n (.length text)]
    (loop [i start
           depth 0
           in-string? false
           escaped? false
           in-comment? false]
      (when (< i n)
        (let [c (.charAt text i)]
          (cond
            (= c \newline)
            (recur (inc i) depth in-string? escaped? false)

            in-comment?
            (recur (inc i) depth in-string? escaped? true)

            in-string?
            (cond
              escaped? (recur (inc i) depth true false false)
              (= c \\) (recur (inc i) depth true true false)
              (= c \") (recur (inc i) depth false false false)
              :else (recur (inc i) depth true false false))

            (= c \;) (recur (inc i) depth false false true)
            (= c \") (recur (inc i) depth true false false)

            ;; A char literal outside a string cannot open a form.
            (= c \\) (recur (+ i 2) depth false false false)

            (= c \() (recur (inc i) (inc depth) false false false)
            (= c \)) (if (= depth 1)
                       i
                       (recur (inc i) (dec depth) false false false))
            :else (recur (inc i) depth false false false)))))))

(defn- catch-spans
  [^String text]
  (let [matcher (re-matcher #"\(catch\s+(?:Throwable|Exception)\b" text)]
    (loop [acc []]
      (if (.find matcher)
        (let [start (.start matcher)
              end (matching-close text start)]
          (recur (if end (conj acc [start end]) acc)))
        acc))))

(defn- line-of
  [^String text index]
  (inc (count (filter #(= \newline %) (subs text 0 index)))))

(def ^:private cause-helpers
  "Helpers whose result already carries a lower cause into an error map.

  Each entry is a call marker; the catch block must call it (a bare binding
  symbol is never evidence).  The helper itself is responsible for building
  `:details`/`cause-*`; this is an explicit, reviewable allowlist."
  {"throwable-error"
   "builds an error map with :details from ex-data"
   "err/summary"
   "builds code/message/:details with :cause"
   "error-data"
   "extracts ex-data"
   "error-describe"
   "builds a structured storage error"
   "storage/error-describe"
   "builds a structured storage error"
   "expected-error"
   "wraps a lower expected error"
   "error-result"
   "wraps an expected error with details"
   "error-of"
   "preserves lower ex-data"
   "storage-failure"
   "wraps storage ex-data"
   "blob-failure"
   "wraps blob ex-data"
   "failure-from-throwable"
   "wraps a lower throwable"
   "throwable-cause-details"
   "builds :details with :cause from a lower throwable"
   "failure"
   "maintenance wrapper adds cause-code/cause-details"})

(def ^:private cause-pattern
  #":cause-code\b|:cause-details\b|:details\s*\{[^{}]*:(?:cause|cause-code|cause-details)\b|:details\s*\(assoc[^)]*:cause")

(defn- helper-used?
  [block]
  (boolean
   (some (fn [helper]
           (re-find (re-pattern
                     (str "\\(\\s*" (Pattern/quote helper) "(?:\\s|\\))"))
                    block))
         (keys cause-helpers))))

(defn- cause-preserving?
  "True only for a literal cause construction or a declared cause helper.

  The catch binding symbol alone is deliberately not evidence: a catch can
  mention `t` in a log call or in `.getMessage` and still replace the lower
  code."
  [block]
  (boolean
   (or (re-find cause-pattern block)
       (helper-used? block))))

(def ^:private new-code-pattern
  #":code\s+:[A-Za-z]|:code\s+\(|:error\s+:[A-Za-z]|\b(?:error|err/fail!|fail!|failure|error-result|service-error|command-error|error/fail!)\s+:[A-Za-z]")

(defn- finding
  [file ^String text [start end]]
  (let [block (subs text start end)]
    (when (and (not (cause-preserving? block))
               (re-find new-code-pattern block)
               (not (re-find #"(?s)\(throw\s+" block)))
      (let [flat (str/replace block #"\s+" " ")]
        {:file file
         :line (line-of text start)
         :snippet (subs flat 0 (min 120 (count flat)))}))))

(defn- source-findings
  []
  (vec
   (mapcat (fn [file]
             (let [text (slurp file)]
               (keep #(finding file text %) (catch-spans text))))
           (clj-files source-root))))

(def ^:private baseline-unsafe-catches
  "Ratchet baseline of existing catch blocks that replace a code without a
  literal cause.  Counts may only go down.  Each file carries its reason;
  this is not a blanket exemption for new catches in that file."
  {"src/hc/server/app.clj" {:reason "pre-existing app entry boundary; ratchet only down" :count 5}
   "src/hc/server/app/background_loops.clj" {:reason "pre-existing background loop boundary; ratchet only down" :count 6}
   "src/hc/server/app/backup.clj" {:reason "pre-existing backup adapter boundary; ratchet only down" :count 1}
   "src/hc/server/app/commands.clj" {:reason "pre-existing command router boundary; ratchet only down" :count 15}
   "src/hc/server/app/executors.clj" {:reason "pre-existing executor boundary; ratchet only down" :count 3}
   "src/hc/server/app/restore_supervisor.clj" {:reason "pre-existing maintenance boundary; ratchet only down" :count 1}
   "src/hc/server/app/settings.clj" {:reason "pre-existing settings boundary; ratchet only down" :count 3}
   "src/hc/server/app/singleton.clj" {:reason "pre-existing singleton boundary; ratchet only down" :count 2}
   "src/hc/server/app/steward_git.clj" {:reason "pre-existing Steward Git boundary; ratchet only down" :count 2}
   "src/hc/server/app/steward_model.clj" {:reason "pre-existing Steward model boundary; ratchet only down" :count 2}
   "src/hc/server/app/steward_runtime.clj" {:reason "pre-existing Steward runtime boundary; ratchet only down" :count 5}
   "src/hc/server/app/system.clj" {:reason "pre-existing startup/health boundary; known history-index defects tracked separately" :count 27}
   "src/hc/server/attachments/http.clj" {:reason "pre-existing attachment HTTP boundary; ratchet only down" :count 2}
   "src/hc/server/attachments/ingest.clj" {:reason "pre-existing attachment ingest boundary; ratchet only down" :count 8}
   "src/hc/server/backup/cli.clj" {:reason "pre-existing backup CLI boundary; ratchet only down" :count 1}
   "src/hc/server/backup/codec.clj" {:reason "pre-existing backup codec boundary; ratchet only down" :count 1}
   "src/hc/server/backup/core.clj" {:reason "pre-existing backup core boundary; ratchet only down" :count 2}
   "src/hc/server/backup/file_recovery.clj" {:reason "pre-existing recovery file boundary; ratchet only down" :count 1}
   "src/hc/server/backup/file_target.clj" {:reason "pre-existing backup target boundary; ratchet only down" :count 1}
   "src/hc/server/backup/http_common.clj" {:reason "pre-existing HTTP target boundary; ratchet only down" :count 1}
   "src/hc/server/backup/offline.clj" {:reason "pre-existing offline restore boundary; ratchet only down" :count 1}
   "src/hc/server/backup/package.clj" {:reason "pre-existing package boundary; ratchet only down" :count 2}
   "src/hc/server/backup/s3.clj" {:reason "pre-existing S3 target boundary; ratchet only down" :count 1}
   "src/hc/server/backup/service.clj" {:reason "pre-existing backup service boundary; ratchet only down" :count 1}
   "src/hc/server/backup/webdav.clj" {:reason "pre-existing WebDAV target boundary; ratchet only down" :count 1}
   "src/hc/server/backup/workspace.clj" {:reason "pre-existing workspace backup boundary; ratchet only down" :count 2}
   "src/hc/server/blob_store/cleanup.clj" {:reason "pre-existing blob cleanup boundary; ratchet only down" :count 1}
   "src/hc/server/browser.clj" {:reason "pre-existing browser boundary; ratchet only down" :count 1}
   "src/hc/server/browser/cdp.clj" {:reason "pre-existing CDP boundary; ratchet only down" :count 7}
   "src/hc/server/browser/executor.clj" {:reason "pre-existing browser executor boundary; ratchet only down" :count 1}
   "src/hc/server/browser/service.clj" {:reason "pre-existing browser service boundary; ratchet only down" :count 6}
   "src/hc/server/commands/dispatch.clj" {:reason "pre-existing command dispatch boundary; ratchet only down" :count 3}
   "src/hc/server/commands/durable.clj" {:reason "pre-existing durable command boundary; ratchet only down" :count 1}
   "src/hc/server/documents/extract.clj" {:reason "pre-existing document extraction boundary; ratchet only down" :count 1}
   "src/hc/server/documents/ocr.clj" {:reason "pre-existing OCR boundary; ratchet only down" :count 1}
   "src/hc/server/documents/ocr/tesseract.clj" {:reason "pre-existing Tesseract boundary; ratchet only down" :count 1}
   "src/hc/server/documents/pdf.clj" {:reason "pre-existing PDF boundary; ratchet only down" :count 2}
   "src/hc/server/extensions/durable.clj" {:reason "pre-existing extension catalog boundary; ratchet only down" :count 1}
   "src/hc/server/extensions/mcp/connection.clj" {:reason "pre-existing MCP connection boundary; ratchet only down" :count 1}
   "src/hc/server/extensions/mcp/http.clj" {:reason "pre-existing MCP HTTP boundary; ratchet only down" :count 4}
   "src/hc/server/extensions/mcp/import.clj" {:reason "pre-existing MCP import boundary; ratchet only down" :count 1}
   "src/hc/server/extensions/mcp/stdio.clj" {:reason "pre-existing MCP stdio boundary; ratchet only down" :count 2}
   "src/hc/server/extensions/skill/store.clj" {:reason "pre-existing skill store boundary; ratchet only down" :count 5}
   "src/hc/server/gateway.clj" {:reason "pre-existing gateway boundary; ratchet only down" :count 3}
   "src/hc/server/gateway/sync_object.clj" {:reason "pre-existing gateway object boundary; ratchet only down" :count 1}
   "src/hc/server/materials/memory_suggest.clj" {:reason "pre-existing memory suggestion boundary; ratchet only down" :count 2}
   "src/hc/server/materials/session.clj" {:reason "pre-existing materials session boundary; ratchet only down" :count 2}
   "src/hc/server/miniapps.clj" {:reason "pre-existing miniapp boundary; ratchet only down" :count 1}
   "src/hc/server/model_adapter/port.clj" {:reason "pre-existing model port boundary; ratchet only down" :count 2}
   "src/hc/server/model_adapter/request.clj" {:reason "pre-existing model request boundary; ratchet only down" :count 1}
   "src/hc/server/model_auth/oauth.clj" {:reason "pre-existing model-auth OAuth boundary; ratchet only down" :count 1}
   "src/hc/server/model_auth/service.clj" {:reason "pre-existing model-auth service boundary; ratchet only down" :count 5}
   "src/hc/server/program/receipt.clj" {:reason "pre-existing program receipt boundary; ratchet only down" :count 1}
   "src/hc/server/program/runtime.clj" {:reason "pre-existing program runtime boundary; ratchet only down" :count 2}
   "src/hc/server/program/sandbox.clj" {:reason "pre-existing program sandbox boundary; ratchet only down" :count 2}
   "src/hc/server/scheduler.clj" {:reason "pre-existing scheduler boundary; ratchet only down" :count 9}
   "src/hc/server/sessions.clj" {:reason "pre-existing session domain boundary; ratchet only down" :count 2}
   "src/hc/server/sessions/registry.clj" {:reason "pre-existing registry boundary; ratchet only down" :count 2}
   "src/hc/server/sessions/runs.clj" {:reason "pre-existing run boundary; ratchet only down" :count 1}
   "src/hc/server/sessions/secrets.clj" {:reason "pre-existing secret store boundary; ratchet only down" :count 3}
   "src/hc/server/shell/session.clj" {:reason "pre-existing shell boundary; ratchet only down" :count 1}
   "src/hc/server/speech.clj" {:reason "pre-existing speech boundary; ratchet only down" :count 2}
   "src/hc/server/speech/config.clj" {:reason "pre-existing speech config boundary; ratchet only down" :count 1}
   "src/hc/server/speech/files.clj" {:reason "pre-existing speech file boundary; ratchet only down" :count 1}
   "src/hc/server/speech/json.clj" {:reason "pre-existing speech JSON boundary; ratchet only down" :count 1}
   "src/hc/server/speech/store.clj" {:reason "pre-existing speech store boundary; ratchet only down" :count 1}
   "src/hc/server/speech/translation.clj" {:reason "pre-existing speech translation boundary; ratchet only down" :count 1}})

(def ^:private known-defect-codes
  "Real cause-loss defects owned by history/index and app/system.  Each entry
  fails this test once the owner adds `:details :cause`, so the marker cannot
  rot into a permanent exemption.  Empty means no current known defect."
  {})

(defn- occurrences
  [^String text ^String needle]
  (loop [from 0 acc []]
    (if-let [index (str/index-of text needle from)]
      (recur (+ index (count needle)) (conj acc index))
      acc)))

(defn- cause-near?
  [^String text index]
  (let [window (subs text (max 0 (- index 260))
                      (min (count text) (+ index 700)))]
    (boolean (or (re-find cause-pattern window)
                 (helper-used? window)))))

(deftest cause-detector-does-not-trust-binding-presence
  (is (false? (cause-preserving? "(catch Throwable t (log/info t))"))
      "a bare binding use is not evidence")
  (is (true?
       (cause-preserving?
        "(catch Throwable t (throw (ex-info \"x\" {:details {:cause (:code (ex-data t))}})))"))
      "literal :details :cause is evidence")
  (is (false?
       (cause-preserving?
        "(catch Throwable t (throw (ex-info \"x\" {:message (.getMessage t)})))"))
      ".getMessage only is not evidence")
  (is (false?
       (cause-preserving?
        "(catch Throwable t {:error {:code :X :message (.getMessage t)}})"))
      "a new code plus message is not evidence")
  (is (true? (cause-preserving? "(catch Throwable t (throw (throwable-error :X t)))"))
      "declared cause helper is evidence"))

(deftest catch-blocks-preserve-lower-error-context
  (let [findings (vec (sort-by (juxt :file :line) (source-findings)))
        counts (frequencies (map :file findings))
        baseline-total (reduce + 0 (map :count (vals baseline-unsafe-catches)))
        current-total (count findings)
        unknown (for [file (keys counts)
                      :when (not (contains? baseline-unsafe-catches file))]
                  [file (get counts file 0)])
        increased (for [[file {:keys [count]}] baseline-unsafe-catches
                        :let [current (get counts file 0)]
                        :when (> current count)]
                    [file current count])
        decreased (for [[file {:keys [count]}] baseline-unsafe-catches
                        :let [current (get counts file 0)]
                        :when (< current count)]
                    [file current count])]
    (is (<= current-total baseline-total)
        (str "unsafe catch total increased: " current-total
             " > baseline " baseline-total))
    (is (empty? unknown)
        (str "new unsafe catch files need a baseline entry: " (pr-str unknown)))
    (is (empty? increased)
        (str "unsafe catches increased above baseline: " (pr-str increased)))
    (is (empty? decreased)
        (str "unsafe catches decreased; please lower the baseline: "
             (pr-str decreased)))
    (doseq [[file {:keys [reason]}] baseline-unsafe-catches]
      (is (not (str/blank? reason)) (str file " baseline needs a reason")))
    (println (str "UNSAFE-CATCH-TOTAL " current-total
                  " BASELINE " baseline-total))
    (println "UNSAFE-CATCH-FIRST-10")
    (doseq [finding (take 10 findings)]
      (println (str "  " (:file finding) ":" (:line finding)
                    " " (:snippet finding))))
    (println "KNOWN-DEFECTS")
    (doseq [finding findings
            code (keys known-defect-codes)
            :when (str/includes? (:snippet finding) (str code))]
      (println (str "  " code " at " (:file finding) ":" (:line finding))))))

(deftest known-index-defects-are-still-tracked
  (doseq [[code {:keys [file owner reason]}] known-defect-codes]
    (let [text (slurp file)
          indexes (occurrences text (str ":code " code))]
      (is (seq indexes)
          (str "KNOWN-DEFECT " code " not found in " file))
      (is (some #(not (cause-near? text %)) indexes)
          (str "KNOWN-DEFECT " code " now preserves cause; remove it from "
               "known-defect-codes (" owner ")"))
      (println (str "KNOWN-DEFECT " code " " file " " owner " " reason)))))

;; ---------------------------------------------------------------------------
;; Partial-result producer/consumer registry

(def ^:private partial-producer-consumers
  {"src/hc/server/blob_store/cleanup.clj"
   {:consumer-tests ["partial-blob-cleanup-is-not-reported-as-a-fatal-sweep"]}

   "src/hc/server/app/retention_gc.clj"
   {:consumer-tests ["purge-failure-does-not-stop-other-sessions-and-is-retried"
                     "fatal-sweep-errors-do-not-look-like-partial-purge-failures"]}})

(def ^:private partial-producer-pattern
  #":outcome\s+(?:\(if[\s\S]{0,180}?:partial|:partial)")

(defn- source-files-matching
  [pattern]
  (->> (clj-files source-root)
       (filter #(re-find pattern (slurp %)))
       set))

(defn- test-defined?
  [test-name]
  (some #(str/includes? (slurp %) (str "(deftest " test-name))
        (clj-files "test")))

(deftest partial-outcome-producers-have-consumer-tests
  (let [producers (source-files-matching partial-producer-pattern)
        expected (set (keys partial-producer-consumers))
        new-producers (sort (remove expected producers))
        lost-producers (sort (remove producers expected))]
    (is (empty? new-producers)
        (str "new :outcome :partial producer(s) need a registered consumer test: "
             (pr-str new-producers)))
    (is (empty? lost-producers)
        (str "registered producer(s) no longer contain :outcome :partial: "
             (pr-str lost-producers)))
    (doseq [[file {:keys [consumer-tests]}] partial-producer-consumers]
      (is (re-find partial-producer-pattern (slurp file))
          (str file " must still be a known producer"))
      (doseq [test-name consumer-tests]
        (is (test-defined? test-name)
            (str "missing consumer regression test " test-name
                 " for " file))))))

;; ---------------------------------------------------------------------------
;; Runtime cause preservation

(defn- assert-cause
  [label error code]
  (is (= code (get-in error [:details :cause]))
      (str label " must expose the lower code under [:details :cause]"))
  (is (not (and (some? (get-in error [:details :cause]))
                (empty? (:details error))))
      (str label " must not replace the code with empty details")))

(deftest lower-error-cause-is-preserved
  (testing "artifacts registration wraps a storage failure"
    (let [root (support/temp-dir "hc-error-taxonomy-artifacts-")]
      (try
        (support/with-temp-store [store {}]
          (let [file (io/file root "artifact.txt")
                _ (io/make-parents file)
                _ (spit file "artifact")
                svc (artifacts/service {:store store
                                        :blob-root (str root "/blobs")})
                error (with-redefs [storage/commit-migration!
                                    (fn [& _]
                                      (throw (ex-info
                                              "lower storage failure"
                                              {:error :STORAGE_TX_FAILED
                                               :storage/expected? true
                                               :path "/db"})))]
                        (try
                          (artifacts/register! svc
                                               {:session-id "s1"
                                                :file (str file)
                                                :name "artifact.txt"})
                          nil
                          (catch clojure.lang.ExceptionInfo e e)))]
            (is (some? error))
            (assert-cause "artifacts registration" (ex-data error)
                          :STORAGE_TX_FAILED)))
        (finally
          (support/delete-tree! root)))))

  (testing "diagnostics statistics keeps the storage code"
    (let [svc (diagnostics/service {:store ::store
                                    :now-fn (constantly 0)})
          response (with-redefs [diagnostics-store/snapshot
                                 (fn [_ _]
                                   (throw (ex-info
                                           "lower diagnostics failure"
                                           {:error :STORAGE_READ_FAILED
                                            :storage/expected? true
                                            :path "/db"})))]
                     (diagnostics/handle! svc
                                          {:kind :diagnostics/statistics}))
          error (get-in response [:receipt :statistics :error])]
      (is (= :read-failed (get-in response [:receipt :statistics :status])))
      (assert-cause "diagnostics statistics" error :STORAGE_READ_FAILED)))

  (testing "backup prune keeps a per-point failure code"
    (let [store (reify backup-port/RecoveryPointPort
                  (write-recovery-point! [_ _] nil)
                  (list-recovery-points [_]
                    [{:id "keep"
                      :created-at "2026-11-03T00:00:00Z"
                      :kind :local :status :success :bytes 1}
                     {:id "old"
                      :created-at "2020-01-01T00:00:00Z"
                      :kind :local :status :success :bytes 1}])
                  (read-recovery-point [_ _] nil)
                  (delete-recovery-point! [_ _]
                    (backup-error/fail! :BACKUP_STORAGE_FULL
                                        "no space"
                                        {:id "old" :path "/recovery/old"}))
                  (recovery-storage-info [_] {:path "memory"}))
          result (backup/prune-recovery-points!
                  store {:now "2026-11-03T00:00:00Z"
                         :recent-limit 1
                         :weeks 0
                         :months 0})
          failed (first (filter #(= :failed (:status %)) (:deleted result)))]
      (assert-cause "backup prune" (:error failed) :BACKUP_STORAGE_FULL)
      (is (= "/recovery/old" (get-in failed [:error :details :path])))))

  (testing "recovery startup keeps the storage code"
    (support/with-temp-store [store {}]
      (let [result (with-redefs [recovery/recover!
                                 (fn [_ _]
                                   (throw (ex-info
                                           "lower recovery failure"
                                           {:error :STORAGE_TX_FAILED
                                            :storage/expected? true
                                            :path "/db"})))]
                     (recovery/recover-all! store {}))]
        (is (= :error (:status result)))
        (assert-cause "recovery startup" (:error result) :STORAGE_TX_FAILED))))

  (testing "blob cleanup keeps a reference lookup code"
    (let [root (support/temp-dir "hc-error-taxonomy-blob-")]
      (try
        (blob/put-bytes! root (.getBytes "x" StandardCharsets/UTF_8))
        (let [error (try
                      (blob/cleanup! root
                                     {:referenced?
                                      (fn [_]
                                        (throw (ex-info
                                                "lower reference failure"
                                                {:code :LOWER_REF})))
                                      :safety-window-ms 0
                                      :now (+ (System/currentTimeMillis) 1000)})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? error))
          (is (= :BLOB_REFERENCE_ERROR (:error (ex-data error))))
          (assert-cause "blob cleanup" (ex-data error) :LOWER_REF))
        (finally
          (support/delete-tree! root))))))
