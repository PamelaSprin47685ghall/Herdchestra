(ns hc.client.workbench.settings-bridge-test
  "Persistence regression for the settings bridge draft export.

  The workbench alert `工作台启动失败：TypeError: Converting circular
  structure to JSON … at tick … JSON.stringify` came from
  `persist-drafts!`: `clj->js` preserves JavaScript object identity, so a
  draft value that ever carried a `#js` object with a reference cycle made
  `JSON.stringify` throw inside a `setTimeout` tick.  These tests pin the
  `json-safe` scrub: the export must survive cycles, shared references,
  functions, and opaque host objects, and the persisted payload must still
  round-trip through `import-drafts` without secrets."
  (:require [cljs.test :refer-macros [deftest is testing run-tests]]
            [hc.client.settings.draft :as draft]
            [hc.client.settings.util :as util]
            [hc.client.workbench.settings-bridge :as bridge]))

(defmethod cljs.test/report :end-run-tests
  [m]
  (when (pos? (+ (:fail m) (:error m)))
    (set! (.-exitCode js/process) 1)))

(defn- blank
  []
  (draft/blank-draft :cloud-key {:draft-id "d1"
                                 :key-id "k1"
                                 :auth-edit-id "e1"}))

(deftest json-safe-breaks-js-reference-cycles
  (let [cycle #js {}
        _ (aset cycle "self" cycle)
        payload (util/json-safe {:note cycle})]
    (is (= "[circular]"
           (get-in payload ["note" "self"]))
        "a self-referencing #js value must not recurse forever")))

(deftest json-safe-copies-plain-js-objects
  (let [payload (util/json-safe {:a #js {:x 1 :y "s"}})]
    (is (= 1 (get-in payload ["a" "x"])))
    (is (= "s" (get-in payload ["a" "y"])))
    ;; The copy is a fresh map, not the original identity.
    (is (not (identical? payload {:a #js {:x 1 :y "s"}})))))

(deftest json-safe-replaces-functions-and-opaque-objects
  (let [payload (util/json-safe
                 {:fn-val (fn [] 1)
                  :opaque (js/Date.)})]
    (is (= "[function]" (get payload "fn-val")))
    (is (string? (get payload "opaque")))))

(deftest export-drafts-survives-cyclic-draft-values
  ;; A draft whose field somehow carries a cyclic #js value must still
  ;; produce a payload `JSON.stringify` accepts; `clj->js` would otherwise
  ;; hand the cycle straight to the serializer.
  (let [d (blank)
        cycle #js {}
        _ (aset cycle "self" cycle)
        drafts {"d1" (assoc-in d [:fields :auth] {:nested cycle})}
        payload (draft/export-drafts drafts)
        js-payload (clj->js (util/json-safe payload))
        text (js/JSON.stringify js-payload)]
    (is (string? text) "JSON.stringify must not throw")
    (is (re-find #"circular" text)
        "the cycle survives as a marker, not a crash")))

(deftest export-drafts-still-strips-secrets
  (let [d (draft/edit-key-secret (blank) "k1" "raw-secret"
                                 {:auth-edit-id "e2"})
        payload (draft/export-drafts {"d1" d})]
    (is (not (draft/secret-leak? payload)))
    (let [restored (draft/import-drafts
                    (js->clj (js/JSON.parse
                              (js/JSON.stringify
                               (clj->js (util/json-safe payload))))
                             :keywordize-keys true))]
      (is (= #{:d1} (set (keys restored)))
          "sanitized export must round-trip through import-drafts"))))

(defn -main
  [& _]
  (run-tests 'hc.client.workbench.settings-bridge-test))

(set! *main-cli-fn* -main)
