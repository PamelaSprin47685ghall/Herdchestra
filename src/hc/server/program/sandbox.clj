(ns hc.server.program.sandbox
  "UFSS/EDN input, `defprogram` parsing, macro expansion, sandbox validation,
  and cooperative checkpoint injection.

  The sandbox is a capability sandbox, not a separate process.  It creates a
  namespace that refers only an explicit safe subset of `clojure.core` and
  `clojure.string`, then interns the runtime's capability functions.  A
  program cannot reach `eval`, `read-string`, reflection, threads, files, or
  the JVM because those names are not referred, qualified symbols are
  checked, and the dangerous special forms are refused.

  Authority: root `AGENTS.md` §4.1 and §4.3."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [hc.core.ufss :as ufss]
            [hc.server.program.capability :as capability]))

(set! *warn-on-reflection* true)

(def default-max-source-bytes (* 1024 1024))
(def sandbox-ns-name 'hc.server.program.sandbox.env)

;; ---------------------------------------------------------------------------
;; Safe core surface

(def allowed-core
  "Unqualified names referred from `clojure.core` into the sandbox namespace.
  This is deliberately a whitelist: adding a name is a security decision."
  '[;; arithmetic and comparison
    + - * / inc dec max min quot rem mod abs
    = == not= < <= > >= compare
    min-key max-key
    ;; logic, functions, composition
    not identity constantly complement comp partial juxt every-pred some-fn
    fnil apply
    ;; collections
    list vector vec hash-map array-map sorted-map sorted-map-by
    hash-set set sorted-set sorted-set-by
    seq first second next nnext ffirst fnext rest last butlast nth nthnext nthrest
    count empty? not-empty empty conj cons concat into merge merge-with
    select-keys keys vals get get-in assoc assoc-in update update-in
    update-keys update-vals dissoc disj contains? find
    some every? not-every? not-any?
    map mapv mapcat filter filterv remove reduce reduce-kv reductions
    take take-while take-last drop drop-while drop-last
    split-at split-with partition partition-all partition-by
    group-by frequencies distinct dedupe flatten interleave interpose
    reverse rseq sort sort-by shuffle rand rand-int rand-nth
    range repeat repeatedly iterate cycle zipmap
    keep keep-indexed map-indexed subvec peek pop
    sequence eduction completing transduce reduced reduced?
    ;; strings, symbols, keywords
    str subs string? char? name keyword symbol
    ;; predicates and conversions
    nil? some? true? false? boolean? number? integer? int? pos-int? neg-int? nat-int?
    zero? pos? neg? even? odd?
    float? double? decimal? ratio? rational? string? keyword? symbol?
    map? vector? list? set? coll? sequential? associative? counted? indexed?
    seqable? sorted? reversible? fn? ifn?
    int long double float bigint biginteger bigdec num byte short char boolean
    parse-long parse-double parse-uuid parse-boolean random-uuid
    ;; metadata and errors
    meta with-meta vary-meta
    ex-info ex-data ex-message ex-cause
    pr-str prn-str print-str println-str
    ;; macros
    let loop fn
    when when-not if-not if-let when-let if-some when-some
    cond condp case and or
    -> ->> as-> some-> some->> cond-> cond->>
    doseq dotimes for while lazy-seq letfn comment assert
    ;; chunked sequence support used by `for` and `doseq`
    chunk-buffer chunk-append chunk-cons chunk chunk-first chunk-rest chunked-seq?
    unchecked-inc unchecked-dec unchecked-add unchecked-subtract unchecked-multiply])

(def allowed-string
  '[blank? capitalize ends-with? escape includes? index-of join last-index-of
    lower-case replace replace-first split-lines starts-with?
    trim trim-newline triml trimr upper-case re-quote-replacement])

(def allowed-catch
  "Catch classes a program may name.  `Throwable` and `Error` are excluded so
  runtime control signals cannot be swallowed.  The runtime throws its
  cancellation/abort signals as private `Error` subclasses for the same
  reason; do not add `Error` or `Throwable` here."
  '#{Exception RuntimeException IllegalArgumentException ArithmeticException
     ClassCastException IndexOutOfBoundsException NumberFormatException
     clojure.lang.ExceptionInfo})

(def allowed-new
  "Constructors the compiler's own macro expansions need, plus ordinary
  exception values a program may throw.  No other class can be constructed."
  '#{java.lang.IllegalArgumentException java.lang.AssertionError
     clojure.lang.LazySeq})

(def allowed-special
  "Special forms that are legal after macro expansion."
  '#{if do let* loop* recur fn* letfn* case* try throw new . quote})

(def rejected-special
  "Special forms that would escape the capability sandbox."
  '#{def set! var monitor-enter monitor-exit deftype* reify* import*
     catch finally &})

;; ---------------------------------------------------------------------------
;; Sandbox namespace

(defonce ^:private sandbox-ns* (atom nil))
(defonce ^:private capabilities-installed? (atom false))

(defn- new-sandbox-ns
  []
  (let [ns (create-ns sandbox-ns-name)]
    (binding [*ns* ns]
      ;; `refer-clojure` is a macro and needs a literal list; `refer` is the
      ;; runtime function with the same `:only` filter.
      (refer 'clojure.string :only allowed-string)
      ;; Refer core last so `reverse` and other shared names keep their
      ;; clojure.core meaning; clojure.string remains reachable qualified.
      (refer 'clojure.core :only allowed-core))
    ns))

(defn sandbox-ns
  "The one sandbox namespace.  Capability vars are interned here by
  `install-capabilities!`."
  []
  (or @sandbox-ns*
      (locking sandbox-ns*
        (or @sandbox-ns*
            (reset! sandbox-ns* (new-sandbox-ns))))))

(defn install-capabilities!
  "Intern the runtime's generic capability functions into the sandbox
  namespace.  Idempotent; the functions read dynamic `*runtime*` and
  `*program*`, so one installation serves every runtime instance."
  [functions]
  (let [ns (sandbox-ns)]
    (locking ns
      (when-not @capabilities-installed?
        (doseq [[sym f] functions]
          ;; Remove a same-named referred core/string var first; the
          ;; capability name wins without a replacement warning.  Qualified
          ;; `clojure.core/...` and `clojure.string/...` remain available.
          (ns-unmap ns sym)
          (intern ns sym f))
        (reset! capabilities-installed? true))))
  (sandbox-ns))

;; ---------------------------------------------------------------------------
;; Parsing

(defn- error
  ([code message] (error code message {}))
  ([code message details]
   {:ok? false
    :error (merge {:status code :error code :message message} details)}))

(defn parse-defprogram
  "Read one UFSS/EDN `(defprogram <name> [] body...)` form from SOURCE.

  Returns `{:ok? true :name string :symbol sym :body [forms] :form form}` or
  `{:ok? false :error {...}}`."
  ([source] (parse-defprogram source {}))
  ([source {:keys [max-source-bytes] :or {max-source-bytes default-max-source-bytes}}]
   (cond
     (not (string? source))
     (error :INVALID_ARGUMENT "Program source must be a string"
            {:actual (some-> source class .getName)})

     (> (alength (.getBytes ^String source java.nio.charset.StandardCharsets/UTF_8))
        (long max-source-bytes))
     (error :LIMIT_EXCEEDED "Program source exceeds the configured byte limit"
            {:limit max-source-bytes})

     :else
     (try
       (let [form (ufss/read-input source)]
         (cond
           (not (seq? form))
           (error :INVALID_ARGUMENT
                  "Program source must be one (defprogram <name> [] ...) form"
                  {:found form})

           (not= 'defprogram (first form))
           (error :INVALID_ARGUMENT
                  "Program source must start with (defprogram <name> [] ...)"
                  {:found (first form)})

           (< (count form) 3)
           (error :INVALID_ARGUMENT
                  "defprogram requires a name, an empty argument vector, and a body"
                  {:form form})

           (not (symbol? (second form)))
           (error :INVALID_ARGUMENT "defprogram name must be a symbol"
                  {:found (second form)})

           (namespace (second form))
           (error :INVALID_ARGUMENT "defprogram name must be unqualified"
                  {:found (second form)})

           (not= [] (nth form 2))
           (error :INVALID_ARGUMENT "defprogram takes no arguments; use []"
                  {:found (nth form 2)})

           :else
           (let [name-sym (second form)]
             {:ok? true
              :name (str name-sym)
              :symbol name-sym
              :body (vec (drop 3 form))
              :form form})))
       (catch clojure.lang.ExceptionInfo e
         (let [data (ex-data e)]
           (if (= :ufss/read-error (:type data))
             (error :UFSS_READ_ERROR
                    (or (:message data) "UFSS/EDN read error")
                    (select-keys data [:error :offset :line :column :expected :found]))
             (error :INVALID_ARGUMENT (.getMessage ^Throwable e)
                    {:exception (some-> e class .getName)}))))
       (catch Throwable t
         (error :INVALID_ARGUMENT (.getMessage ^Throwable t)
                {:exception (some-> t class .getName)}))))))

;; ---------------------------------------------------------------------------
;; Validation

(defn- deny
  [sym reason]
  (throw (ex-info (str "Sandbox refused symbol " sym ": " (name reason))
                  {:status :CAPABILITY_DENIED
                   :error :CAPABILITY_DENIED
                   :reason reason
                   :symbol sym})))

(defn- check-symbol
  [sym locals]
  (cond
    (contains? locals sym) nil

    (namespace sym)
    (let [ns (namespace sym)
          n (name sym)]
      (when-not (or (and (= ns "clojure.core") (contains? (set allowed-core) (symbol n)))
                    (and (= ns "clojure.string") (contains? (set allowed-string) (symbol n))))
        (deny sym :qualified-symbol)))

    (str/ends-with? (name sym) ".")
    (deny sym :constructor)

    :else
    (let [resolved (ns-resolve (sandbox-ns) sym)]
      (cond
        (class? resolved) (deny sym :class)
        (some? resolved) nil
        (contains? (set (keys capability/program-functions)) sym) nil
        (contains? #{'checkpoint! 'force-result} sym) nil
        :else (deny sym :unknown-symbol)))))

(declare validate-form)

(defn- validate-symbols
  [syms locals]
  (doseq [s syms]
    (validate-form s locals)))

(defn- binding-symbols
  [bindings]
  (when-not (and (vector? bindings) (even? (count bindings)))
    (throw (ex-info "Sandbox expected an even binding vector"
                    {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT
                     :found bindings})))
  (vec (take-nth 2 bindings)))

(defn- validate-let
  [form locals]
  (let [bindings (second form)
        syms (binding-symbols bindings)
        values (take-nth 2 (rest bindings))
        inner (reduce (fn [scope [sym value]]
                        (validate-form value scope)
                        (conj scope sym))
                      locals
                      (map vector syms values))]
    (doseq [s syms]
      (when-not (symbol? s)
        (throw (ex-info "Sandbox expected a symbol binding"
                        {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT
                         :found s}))))
    (validate-symbols (drop 2 form) inner)))

(defn- validate-params
  [params locals]
  (when-not (vector? params)
    (throw (ex-info "Sandbox expected a parameter vector"
                    {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT
                     :found params})))
  (reduce (fn [locals p]
            (cond
              (= '& p) locals
              (symbol? p) (conj locals p)
              :else (throw (ex-info "Sandbox expected a symbol parameter"
                                    {:status :INVALID_ARGUMENT
                                     :error :INVALID_ARGUMENT
                                     :found p}))))
          locals
          params))

(defn- validate-fn
  [form locals]
  (let [tail (rest form)
        [self tail] (if (symbol? (first tail))
                      [(first tail) (rest tail)]
                      [nil tail])
        locals (if self (conj locals self) locals)
        arities (cond
                  (vector? (first tail)) [tail]
                  (seq? (first tail)) (vec tail)
                  :else (throw (ex-info "Sandbox expected a function body"
                                        {:status :INVALID_ARGUMENT
                                         :error :INVALID_ARGUMENT
                                         :found form})))]
    (doseq [[params & body] arities]
      (let [inner (validate-params params locals)]
        (validate-symbols body inner)))))

(defn- validate-letfn
  [form locals]
  (let [bindings (second form)]
    (when-not (and (vector? bindings) (even? (count bindings)))
      (throw (ex-info "Sandbox expected an even letfn binding vector"
                      {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT
                       :found bindings})))
    (let [names (vec (take-nth 2 bindings))
          fns (take-nth 2 (rest bindings))
          inner (into locals names)]
      (doseq [n names]
        (when-not (symbol? n)
          (throw (ex-info "Sandbox expected a letfn name"
                          {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT
                           :found n}))))
      (doseq [f fns] (validate-form f inner))
      (validate-symbols (drop 2 form) inner))))

(defn- validate-try
  [form locals]
  (loop [forms (rest form)
         seen-catch? false
         seen-finally? false]
    (if (empty? forms)
      nil
      (let [f (first forms)]
        (cond
          (and (seq? f) (= 'catch (first f)))
          (let [[_ cls sym & body] f]
            (when seen-finally?
              (throw (ex-info "Sandbox refused catch after finally"
                              {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT})))
            (when-not (contains? allowed-catch cls)
              (deny cls :catch-class))
            (when-not (symbol? sym)
              (throw (ex-info "Sandbox expected a catch binding symbol"
                              {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT
                               :found sym})))
            (validate-symbols body (conj locals sym))
            (recur (rest forms) true seen-finally?))

          (and (seq? f) (= 'finally (first f)))
          (do
            (when seen-finally?
              (throw (ex-info "Sandbox refused a second finally"
                              {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT})))
            (validate-symbols (rest f) locals)
            (recur (rest forms) seen-catch? true))

          seen-catch?
          (throw (ex-info "Sandbox expected only catch/finally after try body"
                          {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT
                           :found f}))

          :else
          (do (validate-form f locals)
              (recur (rest forms) seen-catch? seen-finally?)))))))

(defn- validate-case
  [form locals]
  ;; (case* expr shift mask default case-map switch-type test-type)
  (when (< (count form) 6)
    (throw (ex-info "Sandbox received a malformed case* form"
                    {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT
                     :found form})))
  (validate-form (nth form 1) locals)
  (validate-form (nth form 4) locals)
  (let [case-map (nth form 5)]
    (when (map? case-map)
      (doseq [[_ expr] case-map]
        (validate-form expr locals)))))

(defn- validate-new
  [form locals]
  (let [[_ cls & args] form]
    (when-not (contains? allowed-new cls)
      (deny cls :constructor-class))
    (validate-symbols args locals)))

(defn- validate-dot
  [form locals]
  (let [[_ target method & args] form]
    (when-not (= 'nth method)
      (deny (or method '.) :interop-method))
    (when-not (and (symbol? target) (contains? locals target))
      (deny (or target '.) :interop-target))
    (validate-symbols args locals)))

(defn- validate-seq
  [form locals]
  (let [op (first form)]
    (cond
      (not (symbol? op))
      (do (validate-form op locals)
          (validate-symbols (rest form) locals))

      (contains? rejected-special op)
      (deny op :special-form)

      (= 'quote op)
      nil

      (= 'if op)
      (do (validate-form (second form) locals)
          (validate-form (nth form 2 nil) locals)
          (when (> (count form) 3)
            (validate-form (nth form 3) locals)))

      (= 'do op)
      (validate-symbols (rest form) locals)

      (or (= 'let* op) (= 'loop* op))
      (validate-let form locals)

      (= 'recur op)
      (validate-symbols (rest form) locals)

      (= 'fn* op)
      (validate-fn form locals)

      (= 'letfn* op)
      (validate-letfn form locals)

      (= 'case* op)
      (validate-case form locals)

      (= 'try op)
      (validate-try form locals)

      (= 'throw op)
      (do (when-not (= 2 (count form))
            (throw (ex-info "Sandbox expected one throw argument"
                            {:status :INVALID_ARGUMENT :error :INVALID_ARGUMENT
                             :found form})))
          (validate-form (second form) locals))

      (= 'new op)
      (validate-new form locals)

      (= '. op)
      (validate-dot form locals)

      (contains? allowed-special op)
      (deny op :unexpected-special)

      :else
      (do (check-symbol op locals)
          (validate-symbols (rest form) locals)))))

(defn validate-form
  "Throw a structured `:CAPABILITY_DENIED` / `:INVALID_ARGUMENT` exception
  when FORM escapes the sandbox."
  [form locals]
  (cond
    (symbol? form) (check-symbol form locals)
    (seq? form) (validate-seq form locals)
    (map? form) (doseq [[k v] form]
                  (validate-form k locals)
                  (validate-form v locals))
    (vector? form) (doseq [x form] (validate-form x locals))
    (set? form) (doseq [x form] (validate-form x locals))
    :else nil))

;; ---------------------------------------------------------------------------
;; Checkpoint injection

(def ^:private checkpoint-form '(checkpoint!))

(declare inject-checkpoints)

(defn- inject-body
  [body]
  (list* 'do checkpoint-form (map inject-checkpoints body)))

(defn- inject-let-like
  [form]
  (let [[op bindings & body] form]
    (list op (inject-checkpoints bindings) (inject-body body))))

(defn- inject-fn
  [form]
  (let [[op & tail] form
        [self tail] (if (symbol? (first tail))
                      [(first tail) (rest tail)]
                      [nil tail])
        prefix (if self (list op self) (list op))]
    (if (vector? (first tail))
      (let [[params & body] tail]
        (concat prefix (list params (inject-body body))))
      (concat prefix
              (map (fn [[params & body]]
                     (list params (inject-body body)))
                   tail)))))

(defn- inject-case
  [form]
  ;; Preserve case* constants; inject only into expression, default, and
  ;; case-map values.
  (let [v (vec form)
        v (assoc v 1 (inject-checkpoints (nth v 1)))
        v (assoc v 4 (inject-checkpoints (nth v 4)))
        v (if (map? (nth v 5))
            (assoc v 5 (into (empty (nth v 5))
                             (map (fn [[k expr]] [k (inject-checkpoints expr)]))
                             (nth v 5)))
            v)]
    (apply list v)))

(defn inject-checkpoints
  "Insert `(checkpoint!)` at function, loop, let, and do entry points.

  This is the cooperative half of the deadline contract: a deadline can park
  only where the program reaches a checkpoint.  A single long-running library
  call or a tight non-recurring computation cannot be preempted without
  losing its continuation; see the package AGENTS.md."
  [form]
  (cond
    (seq? form)
    (let [op (first form)]
      (cond
        (= 'quote op) form
        (= 'fn* op) (inject-fn form)
        (or (= 'let* op) (= 'loop* op)) (inject-let-like form)
        (= 'do op) (list* 'do checkpoint-form (map inject-checkpoints (rest form)))
        (= 'case* op) (inject-case form)
        (= 'letfn* op)
        (let [[op bindings & body] form
              bindings (into []
                             (map-indexed (fn [i x]
                                            (if (odd? i)
                                              (inject-checkpoints x)
                                              x)))
                             bindings)]
          (list op bindings (inject-body body)))
        :else (cons (inject-checkpoints op)
                    (map inject-checkpoints (rest form)))))
    (vector? form) (mapv inject-checkpoints form)
    (map? form) (into (empty form)
                      (map (fn [[k v]] [(inject-checkpoints k)
                                        (inject-checkpoints v)]))
                      form)
    (set? form) (into #{} (map inject-checkpoints) form)
    :else form))

;; ---------------------------------------------------------------------------
;; Compilation

(defn- numbered-source
  [source]
  (->> (str/split source #"\n" -1)
       (map-indexed (fn [i line] (str (inc i) " | " line)))
       (str/join "\n")))

(defn compile-program
  "Parse, macro-expand, validate, and inject checkpoints into SOURCE.

  Returns `{:ok? true :name :source :numbered-source :body-form :fn-form}`
  or `{:ok? false :error {...}}`.  `:fn-form` is a `(fn* [] ...)` form ready
  for `eval` inside the sandbox namespace."
  ([source] (compile-program source {}))
  ([source opts]
   (let [parsed (parse-defprogram source opts)]
     (if-not (:ok? parsed)
       parsed
       (try
         (let [body-form (binding [*ns* (sandbox-ns)]
                           (walk/macroexpand-all (list* 'do (:body parsed))))
               _ (validate-form body-form #{})
               body-form (inject-checkpoints body-form)
               result-sym (gensym "result__")
               fn-form (list 'fn* []
                             (list 'let* [result-sym body-form]
                                   checkpoint-form
                                   (list 'force-result result-sym)))
               _ (validate-form fn-form #{})]
           {:ok? true
            :name (:name parsed)
            :source source
            :numbered-source (numbered-source source)
            :body-form body-form
            :fn-form fn-form})
         (catch clojure.lang.ExceptionInfo e
           (let [data (ex-data e)]
             (if (contains? #{:CAPABILITY_DENIED :INVALID_ARGUMENT} (:status data))
               (error (:status data)
                      (or (:message data) (.getMessage ^Throwable e))
                      (dissoc data :status :error :message))
               (error :SANDBOX_ERROR
                      (or (.getMessage ^Throwable e) "Sandbox compilation failed")
                      {:exception (some-> e class .getName)
                       :data data}))))
         (catch Throwable t
           (error :SANDBOX_ERROR
                  (or (.getMessage ^Throwable t) "Sandbox compilation failed")
                  {:exception (some-> t class .getName)})))))))
