;; E2E 08 boundary probe: exercise the real program runtime entry point
;; (`hc.server.program.runtime/evaluate-program!`) for the two role-boundary
;; refusals that cannot be reached through a single UI session cheaply:
;;   - `(ask ...)` outside Plan S1 -> :ASK_FORBIDDEN_OUTSIDE_S1
;;   - `(call ...)` by a non-holder clj-worker -> :TOKEN_REQUIRED
;; The spec reads the last stdout line as JSON, so the probe prints one
;; JSON object on a single line.  `:dev` has no JSON library on its
;; classpath, so the encoder below is deliberately small and total.
(require 'clojure.string)
(require 'hc.server.program)
(require 'hc.server.program.runtime)

(defn- json-escape
  [^String s]
  (let [sb (StringBuilder.)]
    (.append sb "\"")
    (doseq [^Character ch s]
      (case ch
        \" (.append sb "\\\"")
        \\ (.append sb "\\\\")
        \newline (.append sb "\\n")
        \return (.append sb "\\r")
        \tab (.append sb "\\t")
        (let [cp (int ch)]
          (if (or (< cp 0x20))
            (.append sb (format "\\u%04x" cp))
            (.append sb ch)))))
    (.append sb "\"")
    (str sb)))

(defn- json-value
  "Encode one EDN value as JSON.  Keywords become their name strings so the
  spec can compare plain strings; nil becomes null."
  [x]
  (cond
    (nil? x) "null"
    (string? x) (json-escape x)
    (keyword? x) (json-escape (name x))
    (number? x) (str x)
    (true? x) "true"
    (false? x) "false"
    (map? x)
    (str "{" (clojure.string/join ","
                  (map (fn [[k v]]
                         (str (json-escape (name k)) ":" (json-value v)))
                       x))
         "}")
    (or (sequential? x) (set? x))
    (str "[" (clojure.string/join "," (map json-value x)) "]")
    :else (json-escape (str x))))

(let [rt (hc.server.program.runtime/runtime {:max-source-bytes 100000})]
  (try
    (let [ask-s1 (hc.server.program.runtime/evaluate-program!
                  rt {:call-id "probe-ask-s1" :role :clj-plan :s1? true
                      :intent "probe"
                      :source "(defprogram p [] (ask {:question \"q\"}))"})
          ask-s2 (hc.server.program.runtime/evaluate-program!
                  rt {:call-id "probe-ask-s2" :role :clj-plan :s1? false
                      :intent "probe"
                      :source "(defprogram p2 [] (ask {:question \"q\"}))"})
          call-w (hc.server.program.runtime/evaluate-program!
                  rt {:call-id "probe-call-w" :role :clj-worker :holder? false
                      :intent "probe"
                      :source "(defprogram p3 [] (call {:command \"x\"}))"})]
      (println
       (json-value
        {:ask-s1 (:status ask-s1)
         :ask-s2 (:status ask-s2)
         :ask-s2-code (or (get-in ask-s2 [:error :code])
                          (get-in ask-s2 [:error :status])
                          (get-in ask-s2 [:error :error]))
         :call-w (:status call-w)
         :call-w-code (or (get-in call-w [:error :code])
                          (get-in call-w [:error :status])
                          (get-in call-w [:error :error]))
         :ask-s2-raw (:error ask-s2)
         :call-w-raw (:error call-w)})))
    (finally (hc.server.program/stop! rt))))
