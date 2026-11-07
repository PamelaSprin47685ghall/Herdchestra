(ns hc.server.extensions.skill-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hc.server.extensions :as ext]
            [hc.server.extensions.support :as support]))

(defn- preview-text
  [text & {:as opts}]
  (ext/preview-skill (merge {:kind :text :text text} opts)))

(deftest preview-extracts-frontmatter-and-conditions
  (let [result (preview-text
                "---\nname: weekly-report\ndescription: Format a weekly report.\nrequires-workspace: true\nrequires-tools: calendar, files\nrequires-network: true\n---\n# Weekly report\nUse the template.")
        candidate (:candidate result)]
    (is (:ok? result))
    (is (= "weekly-report" (:name candidate)))
    (is (= "Format a weekly report." (:description candidate)))
    (is (= {:requires-workspace? true
            :requires-tools ["calendar" "files"]
            :requires-network? true
            :requires-user-input []}
           (:conditions candidate)))
    (is (= ["SKILL.md"] (keys (:files candidate))))))

(deftest preview-refuses-empty-text
  (is (= :SKILL_TEXT_EMPTY
         (get-in (preview-text "  ") [:error :code]))))

(deftest preview-refuses-traversal-and-duplicate-entry
  (testing "archive traversal"
    (let [archive (support/zip-bytes [["SKILL.md" "# hi"]
                                      ["../escape.txt" "x"]])
          result (ext/preview-skill {:kind :archive :bytes archive})]
      (is (false? (:ok? result)))
      (is (= :EXTENSION_PATH_TRAVERSAL (get-in result [:error :code])))))
  (testing "absolute archive path"
    (let [archive (support/zip-bytes [["/SKILL.md" "# hi"]])
          result (ext/preview-skill {:kind :archive :bytes archive})]
      (is (= :EXTENSION_PATH_ABSOLUTE (get-in result [:error :code])))))
  (testing "two entry points"
    (let [archive (support/zip-bytes [["SKILL.md" "# one"]
                                      ["nested/SKILL.md" "# two"]])
          result (ext/preview-skill {:kind :archive :bytes archive})]
      (is (= :SKILL_DUPLICATE_ENTRY (get-in result [:error :code])))))
  (testing "missing entry point"
    (let [archive (support/zip-bytes [["README.md" "# hi"]])
          result (ext/preview-skill {:kind :archive :bytes archive})]
      (is (= :SKILL_ENTRY_MISSING (get-in result [:error :code]))))))

(deftest archive-common-root-is-stripped
  (let [archive (support/zip-bytes [["demo/SKILL.md" "---\nname: demo\n---\n# Demo"]
                                    ["demo/templates/report.md" "template"]])
        candidate (:candidate (ext/preview-skill {:kind :archive :bytes archive}))]
    (is (= #{"SKILL.md" "templates/report.md"} (set (keys (:files candidate)))))
    (is (= "demo" (:name candidate)))))

(deftest archive-candidates-are-listed-for-selection
  (let [archive (support/zip-bytes [["one/SKILL.md" "---\nname: one\n---\n# One"]
                                    ["two/SKILL.md" "---\nname: two\n---\n# Two"]])
        result (ext/preview-skill-candidates {:kind :archive :bytes archive})]
    (is (:ok? result))
    (is (= ["one" "two"] (mapv :name (:candidates result))))
    (is (every? #(= "SKILL.md" (:entry %)) (:candidates result)))))

(deftest directory-preview-reads-a-safe-tree
  (support/with-temp-dir [dir]
    (let [skill-dir (str dir "/skill")
          _ (io/make-parents (io/file skill-dir "templates/report.md"))
          _ (spit (str skill-dir "/SKILL.md") "---\nname: dir-demo\n---\n# Dir")
          _ (spit (str skill-dir "/templates/report.md") "template")
          result (ext/preview-skill {:kind :directory :directory skill-dir})
          candidate (:candidate result)]
      (is (:ok? result))
      (is (= "dir-demo" (:name candidate)))
      (is (= #{"SKILL.md" "templates/report.md"} (set (keys (:files candidate))))))))

(deftest install-is-disabled-unbound-and-uses-a-unique-name
  (support/with-temp-dir [dir]
    (let [candidate (:candidate (preview-text "---\nname: demo\n---\n# Demo"))
          first (ext/install-skill! (ext/empty-catalog) dir candidate)
          second (ext/install-skill! (:catalog first) dir candidate)
          first-skill (:skill first)
          second-skill (:skill second)]
      (is (:ok? first))
      (is (false? (:enabled? first-skill)))
      (is (= #{} (:bindings first-skill)))
      (is (:renamed? second))
      (is (= "demo-2" (:name second-skill)))
      (is (not= (:id first-skill) (:id second-skill)))
      (is (re-find #"# Demo" (slurp (str (:root first-skill) "/SKILL.md")))))))

(deftest global-disable-wins-over-binding-and-session
  (support/with-temp-dir [dir]
    (let [installed (ext/install-skill! (ext/empty-catalog) dir
                                        (:candidate (preview-text "---\nname: demo\n---\n# Demo")))
          id (get-in installed [:skill :id])
          bound (ext/bind-skill! (:catalog installed) id "assistant-1")
          disabled (ext/enable-skill! (:catalog bound) id false)
          custom (ext/resolve-skills (:catalog disabled)
                                     {:assistant-id "assistant-1"
                                      :session {:mode :custom :skills #{id}}
                                      :available {}})
          enabled (ext/enable-skill! (:catalog disabled) id true)
          follow (ext/resolve-skills (:catalog enabled)
                                     {:assistant-id "assistant-1"
                                      :session {:mode :follow}
                                      :available {}})]
      (is (= :globally-disabled (:reason (first (:excluded custom)))))
      (is (= [id] (mapv :id (:active follow)))))))

(deftest unbound-skill-is-not-active
  (support/with-temp-dir [dir]
    (let [installed (ext/install-skill! (ext/empty-catalog) dir
                                        (:candidate (preview-text "---\nname: demo\n---\n# Demo")))
          id (get-in installed [:skill :id])
          enabled (ext/enable-skill! (:catalog installed) id true)
          result (ext/resolve-skills (:catalog enabled)
                                     {:assistant-id "assistant-1"
                                      :session {:mode :follow}
                                      :available {}})]
      (is (= [] (:active result)))
      (is (= [] (:excluded result))))))

(deftest unmet-conditions-block-a-selected-skill
  (support/with-temp-dir [dir]
    (let [installed (ext/install-skill! (ext/empty-catalog) dir
                                        (:candidate (preview-text
                                                     "---\nname: demo\nrequires-workspace: true\n---\n# Demo")))
          id (get-in installed [:skill :id])
          enabled (ext/enable-skill! (:catalog installed) id true)
          blocked (ext/resolve-skills (:catalog enabled)
                                      {:assistant-id "assistant-1"
                                       :session {:mode :custom :skills #{id}}
                                       :available {:workspace-bound? false}})
          ready (ext/resolve-skills (:catalog enabled)
                                    {:assistant-id "assistant-1"
                                     :session {:mode :custom :skills #{id}}
                                     :available {:workspace-bound? true}})]
      (is (= [] (:active blocked)))
      (is (= :SKILL_REQUIRES_WORKSPACE
             (get-in blocked [:blocked 0 :blockers 0 :code])))
      (is (= [id] (mapv :id (:active ready)))))))

(deftest update-conflict-keeps-local-version
  (support/with-temp-dir [dir]
    (let [v1 (:candidate (preview-text "---\nname: demo\n---\n# Demo\nversion one"))
          installed (ext/install-skill! (ext/empty-catalog) dir v1)
          id (get-in installed [:skill :id])
          root (get-in installed [:skill :root])
          _ (spit (str root "/SKILL.md") "---\nname: demo\n---\n# Demo\nlocal edit")
          v2 (:candidate (preview-text "---\nname: demo\n---\n# Demo\nversion two"))
          compared (ext/compare-skill-update (:catalog installed) id v2)
          updated (ext/apply-skill-update! (:catalog installed) id v2)]
      (is (= #{"SKILL.md"} (get-in compared [:diff :conflicts])))
      (is (:ok? updated))
      (is (re-find #"local edit" (slurp (str root "/SKILL.md"))))
      (is (true? (get-in updated [:skill :local-modified?])))
      (is (true? (get-in updated [:skill :review-required?]))))))

(deftest update-without-local-change-writes-upstream
  (support/with-temp-dir [dir]
    (let [v1 (:candidate (preview-text "---\nname: demo\n---\n# Demo\nversion one"))
          installed (ext/install-skill! (ext/empty-catalog) dir v1)
          id (get-in installed [:skill :id])
          v2 (:candidate (preview-text "---\nname: demo\n---\n# Demo\nversion two"))
          updated (ext/apply-skill-update! (:catalog installed) id v2)]
      (is (:ok? updated))
      (is (re-find #"version two" (slurp (str (get-in updated [:skill :root]) "/SKILL.md"))))
      (is (false? (get-in updated [:skill :local-modified?]))))))

(deftest delete-removes-managed-files-only
  (support/with-temp-dir [dir]
    (let [user-file (io/file dir "user-workspace.txt")
          _ (spit user-file "keep me")
          installed (ext/install-skill! (ext/empty-catalog) dir
                                        (:candidate (preview-text "---\nname: demo\n---\n# Demo")))
          id (get-in installed [:skill :id])
          root (get-in installed [:skill :root])
          deleted (ext/delete-skill! (:catalog installed) dir id)]
      (is (:ok? deleted))
      (is (not (.exists (io/file root))))
      (is (.exists user-file))
      (is (= "keep me" (slurp user-file))))))

(deftest copy-creates-independent-disabled-skill
  (support/with-temp-dir [dir]
    (let [installed (ext/install-skill! (ext/empty-catalog) dir
                                        (:candidate (preview-text "---\nname: demo\n---\n# Demo")))
          id (get-in installed [:skill :id])
          copied (ext/copy-skill! (:catalog installed) dir id {})]
      (is (:ok? copied))
      (is (false? (get-in copied [:skill :enabled?])))
      (is (= #{} (get-in copied [:skill :bindings])))
      (is (not= id (get-in copied [:skill :id])))
      (is (re-find #"local" (get-in copied [:skill :name]))))))

(deftest update-refuses-a-symbolic-link-in-the-skill-tree
  (support/with-temp-dir [dir]
    (let [installed (ext/install-skill! (ext/empty-catalog) dir
                                        (:candidate (preview-text "---\nname: demo\n---\n# Demo")))
          id (get-in installed [:skill :id])
          root (get-in installed [:skill :root])
          outside (str dir "/outside")
          _ (io/make-parents (io/file outside "secret.txt"))
          _ (spit (str outside "/secret.txt") "secret")]
      (try
        (java.nio.file.Files/createSymbolicLink
         (java.nio.file.Path/of (str root "/linked") (make-array String 0))
         (java.nio.file.Path/of outside (make-array String 0))
         (make-array java.nio.file.attribute.FileAttribute 0))
        (let [v2 (:candidate (preview-text "---\nname: demo\n---\n# Demo\nv2"))
              compared (ext/compare-skill-update (:catalog installed) id v2)]
          (is (false? (:ok? compared)))
          (is (= :SKILL_SYMLINK (get-in compared [:error :code]))))
        (catch UnsupportedOperationException _
          (is true "platform does not support symbolic links"))))))

(deftest url-preview-uses-fetched-bytes-without-network
  (let [called (atom nil)
        result (ext/preview-skill
                {:kind :url
                 :url "https://example.test/SKILL.md"
                 :fetcher (fn [url timeout]
                            (reset! called [url timeout])
                            {:ok? true
                             :body (.getBytes "---\nname: linked\n---\n# Linked" "UTF-8")
                             :content-type "text/markdown"
                             :final-url url})})]
    (is (:ok? result))
    (is (= "linked" (get-in result [:candidate :name])))
    (is (= ["https://example.test/SKILL.md" 10000] @called))))

(deftest public-resolution-drops-duplicated-file-maps
  (let [large (apply str (repeat 20000 "x"))
        resolved {:active [{:id "s1" :name "demo" :version "1"
                            :files {"SKILL.md" {:size 20000 :text large}}
                            :root "/skills/s1"
                            :description "demo"}]
                  :blocked [{:id "s2" :name "blocked" :version "1"
                             :files {"SKILL.md" {:size 20000 :text large}}
                             :blockers [{:code :SKILL_REQUIRES_NETWORK}]}]
                  :excluded [{:id "s3" :name "off" :reason :globally-disabled
                              :files {"SKILL.md" {:size 20000 :text large}}}]}
        public (ext/public-resolution resolved)]
    (is (= [:id :name :version] (keys (first (:active public)))))
    (is (= [:id :name :version :blockers] (keys (first (:blocked public)))))
    (is (= [:id :name :reason] (keys (first (:excluded public)))))
    (is (< (count (pr-str public)) 500)
        "the projection does not carry file bodies")
    (is (> (count (pr-str resolved)) 20000)
        "the full resolved set still carries the file metadata for file readers")))
