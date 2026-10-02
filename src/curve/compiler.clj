(ns curve.compiler
  "Compiles reactive function bodies into program tables (see curve.runtime).
  Runs at macro-expansion time on the JVM, for both the clj and the cljs
  build; never part of a client bundle.

  Strategy: any subform that contains no reactive construct is lifted whole
  into one host fn node whose inputs are the reactive locals it mentions.
  Only forms that do contain reactive constructs are taken apart."
  (:require [clojure.pprint]
            [clojure.string]
            [clojure.walk]
            [curve.taint]))

(defonce ^{:doc "Qualified symbols of every reactive fn seen so far."} registry (atom #{}))

;; ------------------------------------------------------------------ host

(defn cljs? [menv] (boolean (:ns menv)))

(defn- cljs-resolve
  "Resolve in the cljs analyzer without warnings: server-only namespaces are
  legitimately absent from the browser build (their code is not emitted)."
  [menv sym]
  (let [warnings-var (requiring-resolve 'cljs.analyzer/*cljs-warnings*)
        quiet (zipmap (keys @warnings-var) (repeat false))]
    (with-bindings {warnings-var quiet}
      (let [expander ((requiring-resolve 'cljs.analyzer/get-expander) sym menv)]
        (if expander
          {:name (symbol expander) :macro true}
          (when-let [v ((requiring-resolve 'cljs.analyzer.api/resolve) menv sym)]
            {:name (:name v) :dynamic (boolean (:dynamic v))
             :secret (boolean (or (:secret v) (:secret (:meta v))))}))))))

(defn- host-resolve
  "{:name qualified-sym :macro bool} for a global symbol, nil for host locals
  and unresolvable symbols."
  [menv sym]
  (if (cljs? menv)
    (when-not (contains? (:locals menv) sym) (cljs-resolve menv sym))
    (when-not (contains? menv sym)
      (let [v (try (resolve sym) (catch Exception _ nil))]
        (when (var? v) {:name (symbol v) :macro (boolean (:macro (meta v)))
                        :dynamic (boolean (:dynamic (meta v)))
                        :secret (boolean (:secret (meta v)))})))))

(defn- host-macroexpand-1 [menv form]
  (if (cljs? menv)
    ((requiring-resolve 'cljs.analyzer/macroexpand-1) menv form)
    (macroexpand-1 form)))

(def special-forms
  '#{def if do let* loop* letfn* quote var fn* recur throw try catch finally
     monitor-enter monitor-exit new set! . case* deftype* reify* js*})

;; ------------------------------------------------------------------ builder

(defn- new-builder [] {:nodes (atom []) :arg-ids (atom []) :captures (atom []) :capture-map (atom {})
                        :render (atom nil) :dyn-map (atom {})})

(defn- add-node! [b nd]
  (let [id (count @(:nodes b))]
    (swap! (:nodes b) conj nd)
    id))

(defn- lexical? [env sym]
  (and (symbol? sym) (nil? (namespace sym))
       (or (contains? (:locals env) sym)
           (contains? @(:capture-map (:b env)) sym)
           (boolean (when-let [o (:outer env)] (lexical? o sym))))))

(defn- dyn-var
  "Qualified name when sym is a dynamic var (not shadowed by a local)."
  [env sym]
  (when (and (symbol? sym) (not (lexical? env sym)) (not (contains? (:menv env) sym))
             (re-find #"^\*.+\*$" (name sym)))
    (let [{:keys [name dynamic]} (host-resolve (:menv env) sym)]
      (when dynamic name))))

(defn- local-name?
  "Reactive locals, plus dynamic vars: reading one inside reactive code
  follows the nearest enclosing r/binding."
  [env sym]
  (or (lexical? env sym) (boolean (dyn-var env sym))))

(defn- resolve-local
  "Node id of a reactive local; captures it from enclosing ctors as needed."
  [env sym]
  (or (when-not (lexical? env sym)
        (when-let [q (dyn-var env sym)]
          (let [b (:b env)]
            (or (get @(:dyn-map b) q)
                (let [id (add-node! b {:op :dyn :var q :default `(fn [] ~sym)})]
                  (swap! (:dyn-map b) assoc q id)
                  id)))))
      (get (:locals env) sym)
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
     curve.core/binding curve.core/shared curve.core/offload curve.core/flow
     curve.core/-with-env curve.core/route curve.core/defer curve.core/declassify curve.core/foreign})

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

(defn- hiccup-tail?
  "Does form (in render position) produce hiccup in a tail position?"
  [form]
  (cond
    (vector? form) (keyword? (first form))
    (seq? form) (and (not= 'quote (first form))
                     (not (contains? '#{fn fn* clojure.core/fn} (first form)))
                     (some hiccup-tail? (rest form)))
    :else false))

(defn reactive-free?
  "True when form contains no reactive construct and so can run as plain host code."
  [env form]
  (cond
    (and (:render? env) (hiccup-tail? form)) false
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

(declare check-no-mutation! pure-form? fresh-collection?)

(defn- lift
  "One host fn node computing form from the reactive locals it mentions."
  [env form]
  (if (literal? form)
    (const! env form)
    (let [_ (check-no-mutation! env form)
          syms (free-locals env form)
          ids (mapv #(resolve-local env %) syms)
          ;; a qualified dynamic var cannot be a param name: substitute
          renames (into {} (keep (fn [s] (when (namespace s) [s (gensym (str (name s) "_"))]))) syms)
          params (mapv #(get renames % %) syms)
          body (if (seq renames) (clojure.walk/postwalk-replace renames form) form)]
      (add-node! (:b env) {:op :call :site (:site env)
                           :code `(fn [~@params] ~body) :in ids :form form
                           :params params :pseudo body
                           :pure (pure-form? env form)
                           :eq (when (fresh-collection? form) :identical)}))))

(defn- value-env [env] (assoc env :render? false))

(defn- call-node
  ([env code ids form] (call-node env code ids form nil nil))
  ([env code ids form params pseudo]
   (add-node! (:b env) {:op :call :site (:site env) :code code :in ids :form form
                        :params params :pseudo pseudo})))

(defn- compile-call
  "Function call whose args contain reactive constructs."
  [env [h & args :as form]]
  (let [venv (value-env env)
        ids (mapv #(compile-form venv %) args)
        gs (vec (repeatedly (count args) #(gensym "a")))]
    (if (and (symbol? h) (local-name? env h))
      (let [hid (resolve-local env h) g (gensym "f")]
        (call-node env `(fn [~g ~@gs] (~g ~@gs)) (into [hid] ids) form (into [g] gs) `(~g ~@gs)))
      (if (reactive-free? env h)
        (call-node env `(fn [~@gs] (~h ~@gs)) ids form gs `(~h ~@gs))
        (error! env form "reactive code in function position is not supported")))))

(defn- compile-coll [env form]
  (let [venv (value-env env)
        xs (if (map? form) (mapcat identity form) (seq form))
        ids (mapv #(compile-form venv %) xs)
        gs (vec (repeatedly (count ids) #(gensym "x")))
        pseudo (cond (map? form) (apply hash-map gs)
                     (set? form) (vec gs)
                     :else (vec gs))
        code (cond (map? form) `(fn [~@gs] (hash-map ~@gs))
                   (set? form) `(fn [~@gs] (hash-set ~@gs))
                   :else `(fn [~@gs] (vector ~@gs)))]
    (call-node env code ids form gs pseudo)))

(defn- compile-child
  "Compile body as a child ctor (branch arm, for body). Returns
  {:ctor ctor-map :captures outer-ids}."
  [env params body]
  (let [b (new-builder)
        env' (assoc env :b b :locals {} :outer env :in-element false)
        env' (reduce (fn [e p]
                       (let [id (add-node! b {:op :arg})]
                         (swap! (:arg-ids b) conj id)
                         (assoc-in e [:locals p] id)))
                     env' params)
        ret (compile-form env' body)]
    ;; deterministic names: a child keeps its identity across recompiles
    {:ctor {:b b :ret ret :name (symbol (str (:qname env) "-" (swap! (:child-counter env) inc)))}
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
                        (when (:secret (meta sym))
                          (swap! (:nodes (:b e)) update id assoc :secret true))
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
        {kf :by recycle :recycle} (apply hash-map opts)
        cid (compile-form (value-env env) coll)
        [[p] body] (param-binding [pat] body)
        {:keys [ctor captures]} (compile-child env [p] body)]
    (add-node! (:b env) (cond-> {:op :for :in [cid] :key (or kf `identity) :ctx-site (:site env)
                                 :ctor ctor :args captures}
                          recycle (assoc :recycle true)))))

(defn- compile-with-bind
  "Compile body as an inline child whose env gains {key node-id} entries."
  [env bind body]
  (let [{:keys [ctor captures]} (compile-child env [] (cons 'do body))
        t (const! env true)]
    (add-node! (:b env) {:op :branch :in [t] :ctx-site (:site env) :ctors [ctor] :args [captures]
                         :bind bind})))

(defn- rewrite
  "Reactive forms that are compositions of other reactive forms."
  [q [_ & args :as form]]
  (case q
    curve.core/boundary
    (let [[[_ [err retry] & fallback] & body] args]
      `(let [!err# (curve.core/client (atom nil))
             err# (curve.core/watch !err#)]
         (if (some? err#)
           (let [~err err# ~retry (fn [] (reset! !err# nil))] ~@fallback)
           (curve.core/-with-env {:curve.runtime/boundary !err#} ~@body))))

    curve.core/suspense
    (let [[fallback & body] args]
      `(let [!n# (curve.core/client (atom 0))
             n# (curve.core/watch !n#)]
         [:div.curve-suspense {:style {:display "contents"}}
          (when (pos? n#) ~fallback)
          [:div {:style {:display (if (pos? n#) "none" "contents")}}
           (curve.core/-with-env {:curve.runtime/suspense !n#} ~@body)]]))

    curve.core/flow
    (let [[subscribe] args]
      `(let [!v# (atom curve.runtime/pending)]
         (curve.core/effect (~subscribe (fn [x#] (reset! !v# x#))))
         (curve.core/watch !v#)))

    curve.core/offload
    `(curve.core/server
       (curve.core/flow (fn [emit#] (curve.runtime/offload! (fn [] ~@args) emit#))))

    curve.core/route
    (let [[routes] args]
      `(curve.core/client (curve.router/match ~routes (curve.core/watch curve.router/*location*))))

    curve.core/defer
    (let [[opts & body] (if (map? (first args)) args (cons {} args))]
      `(let [!ready# (curve.core/client (atom false))
             ready# (curve.core/watch !ready#)]
         (curve.core/client (curve.core/effect (curve.runtime/defer! ~opts (fn [] (reset! !ready# true)))))
         (if ready# (do ~@body) ~(:placeholder opts))))

    curve.core/foreign
    (let [[mf props] args]
      [:div.curve-foreign {:curve/foreign [mf props]}])

    curve.core/mutation
    (let [[f] args]
      `(let [g# (curve.core/server ~f)]
         (fn [& args#] (curve.core/-mutate g# args#))))))

(defn- compile-seq [env form]
  (let [[h & args] form]
    (cond
      (not (symbol? h))
      (if (reactive-free? env form) (lift env form) (compile-call env form))

      (local-name? env h)
      (if (reactive-free? env form) (lift env form) (compile-call env form))

      (and (special-forms h) (not= h 'quote) (reactive-free? env form))
      (lift env form)

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
          (contains? '#{curve.core/boundary curve.core/suspense curve.core/flow
                        curve.core/offload curve.core/mutation curve.core/route curve.core/defer
                        curve.core/foreign} q)
          (compile-form env (rewrite q form))
          (= q 'curve.core/declassify)
          (let [[x reason] args]
            (when-not (and (string? reason) (seq reason))
              (error! env form "r/declassify needs a reason string"))
            (let [id (compile-form env x)]
              (swap! (:nodes (:b env)) update id assoc :declassified reason)
              id))
          (= q 'curve.core/binding)
          (let [[bindings & body] args
                venv (value-env env)
                bind (into {} (for [[sym e] (partition 2 bindings)]
                                [(or (:name (host-resolve (:menv env) sym))
                                     (error! env form (str "r/binding: cannot resolve " sym)))
                                 (compile-form venv e)]))]
            (compile-with-bind env bind body))
          (= q 'curve.core/-with-env)
          (let [[m & body] args
                venv (value-env env)
                bind (into {} (for [[k e] m] [k (compile-form venv e)]))]
            (compile-with-bind env bind body))
          (= q 'curve.core/effect)
          (let [form' (cons 'do args)
                syms (free-locals env form')
                ids (mapv #(resolve-local env %) syms)
                renames (into {} (keep (fn [s] (when (namespace s) [s (gensym)]))) syms)]
            (add-node! (:b env) {:op :effect :site (:site env)
                                 :code `(fn [~@(mapv #(get renames % %) syms)]
                                          ~(clojure.walk/postwalk-replace renames form'))
                                 :in ids}))
          (= q 'curve.core/shared)
          (let [[k & body] args
                senv (assoc env :site :server :render? false)
                kid (compile-form senv k)
                {:keys [ctor captures]} (compile-child senv [] (cons 'do body))]
            (add-node! (:b env) {:op :shared :site :server :in (into [kid] captures) :ctor ctor}))
          (= q 'curve.core/call)
          (let [ids (mapv #(compile-form (value-env env) %) args)]
            (add-node! (:b env) {:op :mount :in ids :ctx-site (:site env)}))
          (contains? @registry q)
          (let [ids (mapv #(compile-form (value-env env) %) args)]
            (add-node! (:b env) {:op :mount :ctor-sym h :ctor-sym-q q :in ids :ctx-site (:site env)}))
          (contains? '#{clojure.core/case cljs.core/case} q)
          (if (reactive-free? env form) (lift env form) (compile-case env form))
          (reactive-free? env form) (lift env form)
          macro? (compile-form env (host-macroexpand-1 (:menv env) form))
          :else (compile-call env form))))))

(declare compile-element)

(declare compile-form*)

(defn compile-form
  "Compile form; ^{:rate n} on a form becomes a send-rate hint on its node."
  [env form]
  (let [id (compile-form* env form)
        m (meta form)
        tag! (fn [k v] (swap! (:nodes (:b env)) update id assoc k v))]
    (when-let [r (:rate m)] (tag! :rate r))
    (when-let [v (:validate m)] (tag! :validate v))
    (when-let [sch (:schema m)]
      (tag! :schema-keys (let [q (if (symbol? sch) (:name (host-resolve (:menv env) sch)) nil)
                               value (cond (symbol? sch) (some-> q requiring-resolve var-get)
                                           (cljs? (:menv env)) nil
                                           :else (try (eval sch) (catch Exception _ nil)))]
                           (if value (curve.taint/schema-secret-keys value) :all))))
    id))

(defn compile-form* [env form]
  (cond
    (and (:render? env) (vector? form) (keyword? (first form)))
    (compile-element env form)
    (symbol? form) (if (local-name? env form) (resolve-local env form) (lift env form))
    (literal? form) (const! env form)
    (seq? form) (if (empty? form) (const! env ()) (compile-seq env form))
    (coll? form) (if (reactive-free? env form) (lift env form) (compile-coll env form))
    :else (lift env form)))

;; ------------------------------------------------------------------ templates
;; A rendered ctor has one static template. Holes are addressed by a path of
;; child indices from the template roots:
;;   [:text path id] [:attr path name id static-prefix] [:event path type id]
;;   [:child path id]   ; comment anchor; child frames render before it

(defn- parse-tag [kw]
  (let [[_ tag rest] (re-matches #"([^.#]*)(.*)" (name kw))
        parts (re-seq #"([.#])([^.#]+)" rest)
        id (some (fn [[_ t v]] (when (= t "#") v)) parts)
        classes (keep (fn [[_ t v]] (when (= t ".") v)) parts)]
    [(if (= "" tag) "div" tag)
     (cond-> {} id (assoc "id" id) (seq classes) (assoc "class" (clojure.string/join " " classes)))]))

(defn- static-value? [v] (or (string? v) (number? v) (keyword? v) (boolean? v) (nil? v)))

(defn- link-node? [env id] (contains? #{:branch :for :mount} (:op (nth @(:nodes (:b env)) id))))

(defn- event-key? [k] (clojure.string/starts-with? (name k) "on-"))

(defn- block
  "Hiccup nested under let/do inside a template: compile as an inline child ctor."
  [env form]
  (let [{:keys [ctor captures]} (compile-child (assoc env :render? true) [] form)
        t (const! env true)]
    (add-node! (:b env) {:op :branch :in [t] :ctx-site (:site env) :ctors [ctor] :args [captures]})))

(defn- element-tree [env holes [tag & more] path]
  (let [[tagname attrs0] (parse-tag tag)
        [attrs children] (if (map? (first more)) [(first more) (rest more)] [{} more])
        venv (value-env env)
        static (reduce-kv
                 (fn [acc k v]
                   (let [an (name k)]
                     (cond
                       ;; {:& m} spreads a map of attributes and handlers
                       (= k :&)
                       (do (swap! holes conj [:spread path (compile-form venv v)]) acc)
                       ;; {:curve/foreign [mount props]} hands this element to a JS component
                       (= k :curve/foreign)
                       (let [[mf props] v]
                         (swap! holes conj [:foreign path (compile-form venv mf) (compile-form venv props)])
                         acc)
                       (event-key? k)
                       (do (swap! holes conj [:event path (subs an 3) (compile-form venv v)]) acc)
                       (static-value? v)
                       (cond (or (nil? v) (false? v)) acc
                             (= "class" an) (update acc "class" #(if % (str % " " (name v)) (name v)))
                             (true? v) (assoc acc an "")
                             :else (assoc acc an (if (keyword? v) (name v) (str v))))
                       :else
                       (do (swap! holes conj [:attr path an (compile-form venv v)
                                              (when (= "class" an) (get attrs0 "class"))])
                           (if (= "class" an) (dissoc acc "class") acc)))))
                 attrs0 attrs)
        ;; merge adjacent static text; drop nils
        kids (reduce (fn [acc c]
                       (cond
                         (nil? c) acc
                         (or (string? c) (number? c))
                         (if (string? (peek acc)) (conj (pop acc) (str (peek acc) c)) (conj acc (str c)))
                         :else (conj acc c)))
                     [] children)
        out (vec (map-indexed
                   (fn [i c]
                     (let [p (conj path i)]
                       (cond
                         (string? c) c
                         (and (vector? c) (keyword? (first c))) (element-tree env holes c p)
                         :else (let [id (compile-form env c)]
                                 (swap! holes conj [(if (link-node? env id) :child :text) p id])
                                 :hole))))
                   kids))]
    {:tag tagname :attrs static :children out}))

(defn compile-element [env form]
  (if (:in-element env)
    (block env form)
    (let [holes (atom [])
          tree (element-tree (assoc env :in-element true :render? true) holes form [0])]
      (when @(:render (:b env))
        (error! env form "a reactive fn renders one template"))
      (reset! (:render (:b env)) {:tree [tree] :holes @holes})
      (const! env nil))))

(def ^:private void-tags #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta" "source" "track" "wbr"})

(defn escape-html [s]
  (-> (str s) (clojure.string/replace "&" "&amp;") (clojure.string/replace "<" "&lt;")
      (clojure.string/replace ">" "&gt;") (clojure.string/replace "\"" "&quot;")))

(defn tree->html [t]
  (cond
    (= :hole t) "<!>"
    (string? t) (escape-html t)
    :else (let [{:keys [tag attrs children]} t]
            (str "<" tag
                 (apply str (for [[k v] (sort attrs)] (if (= "" v) (str " " k) (str " " k "=\"" (escape-html v) "\""))))
                 ">"
                 (when-not (void-tags tag)
                   (str (apply str (map tree->html children)) "</" tag ">"))))))

(defn- derived-render [b ret]
  (let [nd (nth @(:nodes b) ret)]
    {:tree [:hole]
     :holes [[(if (contains? #{:branch :for :mount} (:op nd)) :child :text) [0] ret]]}))

;; ------------------------------------------------------------------ effects
;; Effect signatures (design §17.4): known-pure core fns; anything unknown is
;; assumed impure, so analysis stays cheap and errs on the safe side.

(def pure-fns
  '#{+ - * / inc dec quot rem mod max min abs = not= < > <= >= == zero? pos? neg? even? odd?
     not and or str subs name namespace keyword symbol format pr-str
     get get-in assoc assoc-in dissoc update update-in merge select-keys keys vals find contains?
     first second rest next last butlast nth count empty? seq vec vector list hash-map hash-set set
     conj cons concat into map mapv filter filterv remove keep reduce sort sort-by group-by frequencies
     take drop take-while drop-while partition distinct reverse range repeat interleave interpose zipmap
     some every? identity constantly comp partial juxt boolean int long double nil? some? true? false?
     number? string? keyword? map? vector? coll? fn? clojure.string/join clojure.string/upper-case
     clojure.string/lower-case clojure.string/trim clojure.string/blank? clojure.string/includes?
     clojure.string/split clojure.string/replace clojure.string/starts-with?})

(def mutating-fns
  '#{swap! reset! vswap! vreset! compare-and-set! swap-vals! reset-vals! set! aset}
  )

(defn- core-name [env sym]
  (when (symbol? sym)
    (when-let [q (:name (host-resolve (:menv env) sym))]
      (when (contains? #{"clojure.core" "cljs.core" "clojure.string"} (namespace q))
        (if (= "clojure.string" (namespace q)) q (symbol (name q)))))))

(defn- walk-calls
  "Call heads in form, not descending into fn bodies (they run later)."
  [env form]
  (let [acc (volatile! [])]
    ((fn walk [x]
       (cond
         (and (seq? x) (= 'quote (first x))) nil
         (and (seq? x) (fn-form? env x)) nil
         (seq? x) (do (vswap! acc conj (first x)) (run! walk (rest x)))
         (map? x) (run! walk (mapcat identity x))
         (coll? x) (run! walk x)))
     form)
    @acc))

(defn- pure-form?
  [env form]
  (every? (fn [h] (or (keyword? h)
                      (and (symbol? h) (local-name? env h))
                      (contains? pure-fns (core-name env h))
                      (contains? '#{if let let* do when when-not cond case fn fn* quote} h)))
          (walk-calls env form)))

(defn- check-no-mutation!
  "State changes belong in event handlers or r/effect, not in a value."
  [env form]
  (doseq [h (walk-calls env form)]
    (when (contains? mutating-fns (core-name env h))
      (error! env form (str "`" h "` inside a reactive value; move it into an event handler or r/effect")))))

(defn- fresh-collection?
  "Does form build a new collection every time (looking through do/let tails)?"
  [form]
  (cond
    (or (vector? form) (map? form) (set? form)) true
    (and (seq? form) (contains? '#{do let let*} (first form))) (fresh-collection? (last form))
    (seq? form) (contains? '#{vector hash-map hash-set list vec mapv filterv into} (first form))
    :else false))

;; ------------------------------------------------------------------ readers

(defn- reader-inputs [nd]
  (case (:op nd)
    (:call :watch :effect :shared) (:in nd)
    (:branch :for) (:in nd)
    :mount (if (:ctor-sym nd) [] (take 1 (:in nd)))
    []))

(defn- reader-sites [nd]
  (case (:op nd)
    (:call :watch :effect :shared) #{(:site nd)}
    (:branch :for :mount) #{:client :server}
    #{}))

(defn hole-reads
  "Node ids a template hole reads (same rule as curve.runtime/hole-nodes)."
  [[kind _ a b]]
  (case kind (:attr :event) [b] :foreign [a b] [a]))

(defn- with-dead
  "Pure nodes whose value nobody reads, renders or returns are not computed."
  [nodes ret holes]
  (let [used (into #{ret} (concat (mapcat :in nodes) (mapcat #(apply concat (:args %)) (filter #(= :branch (:op %)) nodes))
                                  (mapcat :args (filter #(contains? #{:for} (:op %)) nodes))
                                  (vals (apply merge (keep :bind nodes)))
                                  (mapcat hole-reads holes)))]
    (vec (map-indexed (fn [i nd] (if (and (:pure nd) (not (contains? used i)) (empty? (:readers nd)))
                                   (assoc nd :dead true) nd))
                      nodes))))

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
  (let [base (select-keys nd [:op :site :in :readers :ctx-site :rate :sid :dead :eq])]
    (case (:op nd)
      :arg base
      :const (assoc base :v (list 'quote (:v nd)))
      (:call :effect) (cond-> (assoc base :f (when (emit-code? target (:site nd)) (:code nd)))
                        (and (:validate nd) (= target :clj)) (assoc :validate (:validate nd)))
      :watch (cond-> base (and (:validate nd) (= target :clj)) (assoc :validate (:validate nd)))
      :shared (assoc base :ctor (emit-ctor target (:ctor nd)))
      :branch (cond-> (assoc base :ctors (mapv #(emit-ctor target %) (:ctors nd)) :args (:args nd))
                (:sel nd) (assoc :sel (:sel nd))
                (:bind nd) (assoc :bind (list 'quote (:bind nd))))
      :dyn (assoc base :var (list 'quote (:var nd)) :default (:default nd))
      :for (cond-> (assoc base :key (:key nd) :ctor (emit-ctor target (:ctor nd)) :args (:args nd))
             (:recycle nd) (assoc :recycle true))
      :mount (cond-> base (:ctor-sym nd) (assoc :ctor-fn `(fn [] ~(:ctor-sym nd))))
      (merge base (dissoc nd :b)))))

(defn- with-sids
  "Stable node ids: a hash of what the node computes and of its inputs' ids,
  so an unchanged subexpression keeps its id when the function around it is
  edited. Hot reload carries local state across by these ids."
  [ctor-name nodes]
  (let [seen (volatile! {})]
    (reduce (fn [acc nd]
              (let [base (hash [(:op nd) (pr-str (or (:form nd) (:v nd) (:var nd) (:code nd)))
                                (mapv #(:sid (nth acc %)) (:in nd))
                                (when (= :arg (:op nd)) (count acc))])
                    n (get @seen base 0)
                    sid (if (zero? n) base (hash [base n]))]
                (vswap! seen assoc base (inc n))
                (conj acc (assoc nd :sid sid))))
            [] nodes)))

(defn emit-ctor
  "Emit code that builds the runtime ctor for a compiled builder."
  [target {:keys [b ret name site extra]}]
  (let [render (or @(:render b) (derived-render b ret))
        nodes (with-sids name (with-dead (with-readers @(:nodes b)) ret (:holes render)))]
    `(curve.runtime/ctor
       ~(merge {:name (list 'quote name)
                :ret ret
                :arg-ids @(:arg-ids b)
                :nodes (mapv #(emit-node target %) nodes)
                :render (merge {:holes (list 'quote (:holes render))}
                               (if (= target :cljs)
                                 {:html (apply str (map tree->html (:tree render)))}
                                 {:tree (list 'quote (:tree render))}))}
               (when site {:site site})
               extra))))

;; ------------------------------------------------------------------ entry

;; ------------------------------------------------------------------ security

(defonce ^{:doc "qname -> set of arg positions whose value can reach the client."} reach (atom {}))
(defonce ^{:doc "qname -> boundary entries (values crossing the network)."} boundary (atom {}))

(defn- builder-info [b ret]
  (let [render (or @(:render b) (derived-render b ret))]
    {:ret ret :arg-ids @(:arg-ids b) :holes (:holes render)}))

(defn- taint-ctx [menv qname]
  (let [self (volatile! nil)
        child-taint (fn [{cb :b cret :ret} arg-taints]
                      (curve.taint/analyze @self @(:nodes cb) (builder-info cb cret) arg-taints))]
    (vreset! self {:qname qname
                   :resolve (fn [sym] (host-resolve menv sym))
                   :reach #(get @reach % #{})
                   :child-taint child-taint})
    @self))

(defn- check-taint!
  "Fail compilation on a secret leak; then record which args reach the client."
  [menv qname b ret nargs]
  (let [ctx (taint-ctx menv qname)
        nodes @(:nodes b)
        info (builder-info b ret)]
    (curve.taint/analyze ctx nodes info [])
    (swap! reach assoc qname
           (set (filter (fn [k]
                          (try (curve.taint/analyze ctx nodes info (assoc (vec (repeat nargs nil)) k :all))
                               false
                               (catch clojure.lang.ExceptionInfo e
                                 (if (= :curve.taint/leak (:type (ex-data e))) true (throw e)))))
                        (range nargs))))))

(defn- record-boundary!
  "Every value that crosses the network, for review (design §11.3)."
  [qname b ret]
  (let [entries (volatile! [])]
    ((fn walk [b ret path]
       (let [nodes @(:nodes b)
             holes (:holes (builder-info b ret))
             hole-ids (set (mapcat hole-reads (remove #(= :child (first %)) holes)))
             readers (with-readers nodes)]
         (doseq [[i nd] (map-indexed vector readers)]
           (let [from (:site nd)
                 to (cond-> (set (:readers nd)) (contains? hole-ids i) (conj :client))
                 crosses (and (contains? #{:server :client} from)
                              (some #(and (not= % from) (not= % :inherit)) to))]
             (when (or crosses (and (= from :server) (contains? to :inherit)))
               (vswap! entries conj (cond-> {:fn qname :path path :op (:op nd)
                                             :form (pr-str (:form nd)) :from from
                                             :to (vec (sort (disj to from)))}
                                      (:line (meta (:form nd))) (assoc :line (:line (meta (:form nd))))
                                      (:declassified nd) (assoc :declassified (:declassified nd))
                                      (:validate nd) (assoc :validated true))))))
         (doseq [[i nd] (map-indexed vector nodes)]
           (doseq [c (concat (:ctors nd) (when (:ctor nd) [(:ctor nd)]))]
             (walk (:b c) (:ret c) (conj path i))))))
     b ret [])
    (swap! boundary assoc qname @entries)))

(defn boundary-report
  "All recorded network crossings, sorted by fn."
  []
  (vec (mapcat val (sort-by key @boundary))))

(defn write-info!
  "Write the boundary report (and taint reach) to path, e.g. curve-info.edn."
  [path]
  (spit path (with-out-str
               (clojure.pprint/pprint {:boundary (boundary-report)
                                       :args-reaching-client (into (sorted-map) @reach)}))))

(defonce ^:private cache (atom {}))

(declare compile-defn*)

(defn compile-defn
  "Compile (r/defn name [params] body...) to ctor code. Incremental: an
  unchanged definition (same source, same set of known reactive fns) reuses
  its previous output."
  [menv qname params body opts]
  (let [k [qname (cljs? menv) (pr-str params body) (dissoc opts :menv) (hash @registry)]]
    (or (get @cache k)
        (let [code (compile-defn* menv qname params body opts)]
          (swap! cache assoc k code)
          code))))

(defn cache-size [] (count @cache))

(defn compile-defn* [menv qname params body opts]
  (let [b (new-builder)
        target (if (cljs? menv) :cljs :clj)
        site (or (:site opts) :inherit)
        [syms body] (param-binding params body)
        env {:b b :locals {} :outer nil :site site :menv menv :name (symbol (name qname))
             :qname qname :child-counter (atom 0) :render? true}
        env (reduce (fn [e p]
                      (let [id (add-node! b {:op :arg})]
                        (swap! (:arg-ids b) conj id)
                        (assoc-in e [:locals p] id)))
                    env syms)
        ret (compile-form env body)]
    (when (= target :clj)
      (check-taint! menv qname b ret (count syms))
      (record-boundary! qname b ret))
    (emit-ctor target {:b b :ret ret :name qname
                       :site (when (not= site :inherit) site)})))

(defn analyze
  "Compile a form for inspection (tests, tooling): returns the emitted code."
  [qname params body & {:as opts}]
  (compile-defn nil qname params body opts))
