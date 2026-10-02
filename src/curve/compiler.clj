(ns curve.compiler
  "Compiles reactive function bodies into program tables (see curve.runtime).
  Runs at macro-expansion time on the JVM, for both the clj and the cljs
  build; never part of a client bundle.

  Strategy: any subform that contains no reactive construct is lifted whole
  into one host fn node whose inputs are the reactive locals it mentions.
  Only forms that do contain reactive constructs are taken apart.")

(defonce ^{:doc "Qualified symbols of every reactive fn seen so far."} registry (atom #{}))

;; ------------------------------------------------------------------ host

(defn cljs? [menv] (boolean (:ns menv)))

(defn- cljs-resolve [menv sym]
  (let [expander ((requiring-resolve 'cljs.analyzer/get-expander) sym menv)]
    (if expander
      {:name (symbol expander) :macro true}
      (when-let [v (binding [*err* (java.io.StringWriter.)]
                     ((requiring-resolve 'cljs.analyzer.api/resolve) menv sym))]
        {:name (:name v)}))))

(defn- host-resolve
  "{:name qualified-sym :macro bool} for a global symbol, nil for host locals
  and unresolvable symbols."
  [menv sym]
  (if (cljs? menv)
    (when-not (contains? (:locals menv) sym) (cljs-resolve menv sym))
    (when-not (contains? menv sym)
      (let [v (try (resolve sym) (catch Exception _ nil))]
        (when (var? v) {:name (symbol v) :macro (boolean (:macro (meta v)))})))))

(defn- host-macroexpand-1 [menv form]
  (if (cljs? menv)
    ((requiring-resolve 'cljs.analyzer/macroexpand-1) menv form)
    (macroexpand-1 form)))

(def special-forms
  '#{def if do let* loop* letfn* quote var fn* recur throw try catch finally
     monitor-enter monitor-exit new set! . case* deftype* reify* js*})

;; ------------------------------------------------------------------ builder

(defn- new-builder [] {:nodes (atom []) :arg-ids (atom []) :captures (atom []) :capture-map (atom {})})

(defn- add-node! [b nd]
  (let [id (count @(:nodes b))]
    (swap! (:nodes b) conj nd)
    id))

(defn- local-name? [env sym]
  (and (symbol? sym) (nil? (namespace sym))
       (or (contains? (:locals env) sym)
           (contains? @(:capture-map (:b env)) sym)
           (boolean (when-let [o (:outer env)] (local-name? o sym))))))

(defn- resolve-local
  "Node id of a reactive local; captures it from enclosing ctors as needed."
  [env sym]
  (or (get (:locals env) sym)
      (get @(:capture-map (:b env)) sym)
      (when-let [o (:outer env)]
        (when-let [outer-id (resolve-local o sym)]
          (let [b (:b env)
                id (add-node! b {:op :arg})]
            (swap! (:arg-ids b) conj id)
            (swap! (:captures b) conj outer-id)
            (swap! (:capture-map b) assoc sym id)
            id)))))

(defn- error! [env form msg]
  (throw (ex-info (str "curve: " msg
                       (when-let [{:keys [line column]} (meta form)] (str " at line " line ":" column))
                       "\n  in: " (pr-str form))
                  {:form form :line (:line (meta form))})))

;; ------------------------------------------------------------------ classification

(def reactive-specials
  '#{curve.core/server curve.core/client curve.core/for curve.core/watch curve.core/call
     curve.core/mutation curve.core/effect curve.core/suspense curve.core/boundary
     curve.core/binding curve.core/shared curve.core/offload curve.core/flow})

(defn- head-name [env h]
  (when (and (symbol? h) (not (local-name? env h)) (not (special-forms h)))
    (:name (host-resolve (:menv env) h))))

(defn- reactive-head? [env h]
  (when-let [q (head-name env h)]
    (or (contains? reactive-specials q) (contains? @registry q))))

(defn- fn-form? [env form]
  (and (seq? form)
       (or (= 'fn* (first form))
           (contains? '#{clojure.core/fn cljs.core/fn} (head-name env (first form))))))

(defn reactive-free?
  "True when form contains no reactive construct and so can run as plain host code."
  [env form]
  (cond
    (seq? form) (cond
                  (= 'quote (first form)) true
                  (fn-form? env form) true
                  (reactive-head? env (first form)) false
                  :else (every? #(reactive-free? env %) form))
    (map? form) (every? #(reactive-free? env %) (mapcat identity form))
    (coll? form) (every? #(reactive-free? env %) form)
    :else true))

(defn- free-locals [env form]
  (let [acc (volatile! [])]
    ((fn walk [x]
       (cond
         (and (seq? x) (= 'quote (first x))) nil
         (symbol? x) (when (local-name? env x) (vswap! acc conj x))
         (map? x) (run! walk (mapcat identity x))
         (coll? x) (run! walk x)))
     form)
    (vec (distinct @acc))))

(defn- literal? [x]
  (or (nil? x) (boolean? x) (number? x) (string? x) (keyword? x) (char? x)))

;; ------------------------------------------------------------------ compile

(declare compile-form compile-body)

(defn- const! [env v] (add-node! (:b env) {:op :const :v v}))

(defn- lift
  "One host fn node computing form from the reactive locals it mentions."
  [env form]
  (if (literal? form)
    (const! env form)
    (let [syms (free-locals env form)
          ids (mapv #(resolve-local env %) syms)]
      (add-node! (:b env) {:op :call :site (:site env)
                           :code `(fn [~@syms] ~form) :in ids :form form}))))

(defn- value-env [env] (assoc env :render? false))

(defn- call-node [env code ids form]
  (add-node! (:b env) {:op :call :site (:site env) :code code :in ids :form form}))

(defn- compile-call
  "Function call whose args contain reactive constructs."
  [env [h & args :as form]]
  (let [venv (value-env env)
        ids (mapv #(compile-form venv %) args)
        gs (vec (repeatedly (count args) #(gensym "a")))]
    (if (and (symbol? h) (local-name? env h))
      (let [hid (resolve-local env h) g (gensym "f")]
        (call-node env `(fn [~g ~@gs] (~g ~@gs)) (into [hid] ids) form))
      (if (reactive-free? env h)
        (call-node env `(fn [~@gs] (~h ~@gs)) ids form)
        (error! env form "reactive code in function position is not supported")))))

(defn- compile-coll [env form]
  (let [venv (value-env env)
        xs (if (map? form) (mapcat identity form) (seq form))
        ids (mapv #(compile-form venv %) xs)
        gs (vec (repeatedly (count ids) #(gensym "x")))
        code (cond (map? form) `(fn [~@gs] (hash-map ~@gs))
                   (set? form) `(fn [~@gs] (hash-set ~@gs))
                   :else `(fn [~@gs] (vector ~@gs)))]
    (call-node env code ids form)))

(defn- compile-child
  "Compile body as a child ctor (branch arm, for body). Returns
  {:ctor ctor-map :captures outer-ids}."
  [env params body]
  (let [b (new-builder)
        env' (assoc env :b b :locals {} :outer env)
        env' (reduce (fn [e p]
                       (let [id (add-node! b {:op :arg})]
                         (swap! (:arg-ids b) conj id)
                         (assoc-in e [:locals p] id)))
                     env' params)
        ret (compile-form env' body)]
    {:ctor {:b b :ret ret :name (gensym (str (:name env) "-"))}
     :captures @(:captures b)}))

(defn- simple? [env x] (or (literal? x) (and (symbol? x) (local-name? env x))))

(defn- compile-if [env [_ t a b :as form]]
  (let [tid (compile-form (value-env env) t)]
    (if (and (simple? env a) (simple? env b) (not (:render? env)))
      (let [aid (compile-form env a) bid (compile-form env b)]
        (call-node env `(fn [t# a# b#] (if t# a# b#)) [tid aid bid] form))
      (let [arms [(compile-child env [] a) (compile-child env [] b)]]
        (add-node! (:b env) {:op :branch :in [tid] :ctx-site (:site env)
                             :ctors (mapv :ctor arms) :args (mapv :captures arms)})))))

(defn- compile-case [env [_ x & clauses :as form]]
  (let [xid (compile-form (value-env env) x)
        pairs (partition 2 clauses)
        default (when (odd? (count clauses)) (last clauses))
        n (count pairs)
        sel `(fn [v#] (case v# ~@(mapcat (fn [[k _] i] [k i]) pairs (range)) ~n))
        arms (mapv #(compile-child env [] %) (cond-> (mapv second pairs)
                                               (odd? (count clauses)) (conj default)))]
    (add-node! (:b env) {:op :branch :in [xid] :ctx-site (:site env) :sel sel
                         :ctors (mapv :ctor arms) :args (mapv :captures arms)})))

(defn- compile-let [env [_ bindings & body]]
  (let [env (reduce (fn [e [sym init]]
                      (let [id (compile-form (value-env e) init)]
                        (assoc-in e [:locals sym] id)))
                    env (partition 2 bindings))]
    (compile-body env body)))

(defn compile-body [env forms]
  (if (empty? forms)
    (const! env nil)
    (do (doseq [f (butlast forms)] (compile-form (value-env env) f))
        (compile-form env (last forms)))))

(defn- param-binding
  "Destructuring params become gensyms re-bound by a let around the body."
  [params body]
  (let [[syms binds] (reduce (fn [[ss bs] p]
                               (if (symbol? p) [(conj ss p) bs]
                                   (let [g (gensym "p")] [(conj ss g) (into bs [p g])])))
                             [[] []] params)]
    [syms (if (seq binds) `(let ~binds ~@body) `(do ~@body))]))

(defn- compile-for [env [_ bindings & body :as form]]
  (let [[pat coll & opts] bindings
        {kf :by} (apply hash-map opts)
        cid (compile-form (value-env env) coll)
        [[p] body] (param-binding [pat] body)
        {:keys [ctor captures]} (compile-child env [p] body)]
    (add-node! (:b env) {:op :for :in [cid] :key (or kf `identity) :ctx-site (:site env)
                         :ctor ctor :args captures})))

(defn- compile-seq [env form]
  (let [[h & args] form]
    (cond
      (not (symbol? h))
      (if (reactive-free? env form) (lift env form) (compile-call env form))

      (local-name? env h)
      (if (reactive-free? env form) (lift env form) (compile-call env form))

      (special-forms h)
      (case h
        quote (const! env (second form))
        do (compile-body env args)
        let* (compile-let env form)
        if (compile-if env form)
        (if (reactive-free? env form)
          (lift env form)
          (error! env form (str "reactive code inside `" h "` is not supported"))))

      :else
      (let [{q :name macro? :macro} (host-resolve (:menv env) h)]
        (cond
          (= q 'curve.core/server) (compile-body (assoc env :site :server :render? false) args)
          (= q 'curve.core/client) (compile-body (assoc env :site :client) args)
          (= q 'curve.core/for) (compile-for env form)
          (= q 'curve.core/watch)
          (let [rid (compile-form (value-env env) (first args))]
            (add-node! (:b env) {:op :watch :site (:site env) :in [rid]}))
          (= q 'curve.core/call)
          (let [ids (mapv #(compile-form (value-env env) %) args)]
            (add-node! (:b env) {:op :mount :in ids :ctx-site (:site env)}))
          (contains? @registry q)
          (let [ids (mapv #(compile-form (value-env env) %) args)]
            (add-node! (:b env) {:op :mount :ctor-sym h :in ids :ctx-site (:site env)}))
          (contains? '#{clojure.core/case cljs.core/case} q)
          (if (reactive-free? env form) (lift env form) (compile-case env form))
          (reactive-free? env form) (lift env form)
          macro? (compile-form env (host-macroexpand-1 (:menv env) form))
          :else (compile-call env form))))))

(defn compile-form [env form]
  (cond
    (and (:render? env) (:compile-element env) (vector? form) (keyword? (first form)))
    ((:compile-element env) env form)
    (symbol? form) (if (local-name? env form) (resolve-local env form) (lift env form))
    (literal? form) (const! env form)
    (seq? form) (if (empty? form) (const! env ()) (compile-seq env form))
    (coll? form) (if (reactive-free? env form) (lift env form) (compile-coll env form))
    :else (lift env form)))

;; ------------------------------------------------------------------ readers

(defn- reader-inputs [nd]
  (case (:op nd)
    (:call :watch :effect) (:in nd)
    (:branch :for) (:in nd)
    :mount (if (:ctor-sym nd) [] (take 1 (:in nd)))
    []))

(defn- reader-sites [nd]
  (case (:op nd)
    (:call :watch :effect) #{(:site nd)}
    (:branch :for :mount) #{:client :server}
    #{}))

(defn with-readers [nodes]
  (let [rs (reduce (fn [acc nd]
                     (let [sites (reader-sites nd)]
                       (reduce (fn [acc i] (update acc i (fnil into #{}) sites)) acc (reader-inputs nd))))
                   {} nodes)]
    (vec (map-indexed (fn [i nd] (if-let [r (rs i)] (assoc nd :readers r) nd)) nodes))))

;; ------------------------------------------------------------------ emit

(declare emit-ctor)

(defn- emit-code? [target site]
  ;; the JVM build carries client code too (tests, SSR); the browser build
  ;; never carries server code
  (or (= target :clj) (not= site :server)))

(defn- emit-node [target nd]
  (let [base (select-keys nd [:op :site :in :readers :ctx-site])]
    (case (:op nd)
      :arg base
      :const (assoc base :v (list 'quote (:v nd)))
      (:call :effect) (assoc base :f (when (emit-code? target (:site nd)) (:code nd)))
      :watch base
      :branch (cond-> (assoc base :ctors (mapv #(emit-ctor target %) (:ctors nd)) :args (:args nd))
                (:sel nd) (assoc :sel (:sel nd)))
      :for (assoc base :key (:key nd) :ctor (emit-ctor target (:ctor nd)) :args (:args nd))
      :mount (cond-> base (:ctor-sym nd) (assoc :ctor-fn `(fn [] ~(:ctor-sym nd))))
      (merge base (dissoc nd :b)))))

(defn emit-ctor
  "Emit code that builds the runtime ctor for a compiled builder."
  [target {:keys [b ret name site extra]}]
  (let [nodes (with-readers @(:nodes b))]
    `(curve.runtime/ctor
       ~(merge {:name (list 'quote name)
                :ret ret
                :arg-ids @(:arg-ids b)
                :nodes (mapv #(emit-node target %) nodes)}
               (when site {:site site})
               extra))))

;; ------------------------------------------------------------------ entry

(defn compile-defn
  "Compile (r/defn name [params] body...) to ctor code."
  [menv qname params body opts]
  (let [b (new-builder)
        target (if (cljs? menv) :cljs :clj)
        site (or (:site opts) :inherit)
        [syms body] (param-binding params body)
        env {:b b :locals {} :outer nil :site site :menv menv :name (symbol (name qname))
             :render? true :compile-element (:compile-element opts)}
        env (reduce (fn [e p]
                      (let [id (add-node! b {:op :arg})]
                        (swap! (:arg-ids b) conj id)
                        (assoc-in e [:locals p] id)))
                    env syms)
        ret (compile-form env body)]
    (emit-ctor target {:b b :ret ret :name qname
                       :site (when (not= site :inherit) site)})))

(defn analyze
  "Compile a form for inspection (tests, tooling): returns the emitted code."
  [qname params body & {:as opts}]
  (compile-defn nil qname params body opts))
