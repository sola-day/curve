(ns curve.taint
  "Compile-time taint analysis (design §11.4).

  Sources: vars marked ^:secret, let bindings marked ^:secret, and forms
  marked ^{:schema S} where S is a malli-style schema whose keys carry
  {:secret true}. A taint is nil (clean), :all (the whole value) or a set of
  keys (a map, or the maps inside a collection, has secret values under
  those keys). Literal-key access, select-keys and dissoc refine key sets;
  anything the analysis cannot follow taints the whole value. It is better
  to report a false leak than to miss one.

  A tainted value read on the client (a client node, a template hole, a
  loop or branch, or an argument of a reactive fn that reaches the client)
  is a compile error. (r/declassify expr \"reason\") is the only way out and
  shows up in the boundary report."
  (:require [clojure.set :as set]))

;; ------------------------------------------------------------------ lattice

(defn join [a b]
  (cond (nil? a) b (nil? b) a
        (or (= :all a) (= :all b)) :all
        :else (set/union a b)))

(defn- key-taint [t k]
  (cond (nil? t) nil
        (= :all t) :all
        (contains? t k) :all
        :else nil))

(defn schema-secret-keys
  "Keys marked {:secret true} in a malli-style map schema."
  [schema]
  (cond
    (and (vector? schema) (= :map (first schema)))
    (set (keep (fn [entry]
                 (when (and (vector? entry) (map? (second entry)) (:secret (second entry)))
                   (first entry)))
               (rest schema)))
    (and (vector? schema) (contains? #{:vector :sequential :set} (first schema)))
    (schema-secret-keys (second schema))
    :else #{}))

;; ------------------------------------------------------------------ forms

(def ^:private element-fns
  '#{first second last nth rest next take drop filter filterv remove sort sort-by reverse vec seq
     distinct take-while drop-while butlast})

(def ^:private size-fns '#{count empty? seq? some? nil? coll? sequential?})

(defn form-taint
  "Taint of evaluating form when symbols have the taints in tmap.
  resolve: sym -> {:name q :secret bool}."
  [resolve tmap form]
  (letfn [(t [tm x]
            (cond
              (symbol? x) (or (get tm x)
                              (when-not (contains? tm x)
                                (when (:secret (resolve x)) :all)))
              (map? x) (reduce-kv (fn [acc k v] (let [vt (t tm v)]
                                                  (cond (nil? vt) acc
                                                        (keyword? k) (join acc #{k})
                                                        :else :all)))
                                  nil x)
              (or (vector? x) (set? x)) (reduce join nil (map #(t tm %) x))
              (seq? x) (call tm x)
              :else nil))
          (bind [tm bindings]
            (reduce (fn [tm [sym init]]
                      (let [it (t tm init)]
                        (if (symbol? sym)
                          (assoc tm sym (if (:secret (meta sym)) :all it))
                          ;; destructuring: be conservative
                          (reduce #(assoc %1 %2 it) tm (filter symbol? (flatten (seq [sym])))))))
                    tm (partition 2 bindings)))
          (call [tm [h & args :as x]]
            (let [q (when (symbol? h) (:name (resolve h)))
                  nm (when (symbol? h) (if q (symbol (name q)) h))]
              (cond
                (= 'quote h) nil
                (= q 'curve.core/declassify) nil
                (contains? '#{fn fn*} nm)
                ;; a closure that captures a secret carries it
                (reduce join nil (map #(t tm %) (filter symbol? (flatten (seq (drop 1 x))))))
                (contains? '#{let let* loop loop*} nm) (t (bind tm (first args)) (last args))
                (= 'do nm) (t tm (last args))
                (contains? '#{if when when-not if-not cond case if-let when-let} nm)
                (reduce join nil (map #(t tm %) (rest args)))
                (keyword? h) (key-taint (t tm (first args)) h)
                (and (= 'get nm) (keyword? (second args))) (key-taint (t tm (first args)) (second args))
                (and (= 'get-in nm) (vector? (second args)) (keyword? (first (second args))))
                (key-taint (t tm (first args)) (first (second args)))
                (and (= 'select-keys nm) (vector? (second args)) (every? keyword? (second args)))
                (let [mt (t tm (first args))]
                  (cond (nil? mt) nil (= :all mt) :all
                        :else (not-empty (set/intersection mt (set (second args))))))
                (and (= 'dissoc nm) (every? keyword? (rest args)))
                (let [mt (t tm (first args))]
                  (cond (nil? mt) nil (= :all mt) :all :else (not-empty (set/difference mt (set (rest args))))))
                (and (= 'assoc nm) (even? (count (rest args))))
                (reduce (fn [acc [k v]] (if (t tm v) (join acc (if (keyword? k) #{k} :all)) acc))
                        (t tm (first args)) (partition 2 (rest args)))
                ;; (map #(select-keys % [...]) coll): follow a literal fn over the elements
                (and (contains? '#{map mapv filter filterv remove keep} nm)
                     (seq? (first args)) (contains? '#{fn fn*} (first (first args)))
                     (vector? (second (first args))) (= 1 (count (second (first args)))))
                (let [[_ [p] & body] (first args)
                      et (t tm (second args))
                      bt (t (assoc tm p et) (last body))]
                  (if (contains? '#{filter filterv remove} nm) et bt))
                (contains? size-fns nm) nil
                (contains? element-fns nm) (reduce join nil (map #(t tm %) args))
                (= 'merge nm) (reduce join nil (map #(t tm %) args))
                :else (when (some #(t tm %) (cons h args)) :all))))]
    (t tmap form)))

;; ------------------------------------------------------------------ graph

(defn- error! [qname nd msg]
  (throw (ex-info (str "curve: " msg " in " qname
                       (when-let [l (:line (meta (:form nd)))] (str " at line " l))
                       (when (:form nd) (str "\n  in: " (pr-str (:form nd))))
                       "\n  Remove the secret on the server (select-keys/dissoc), or (r/declassify expr \"reason\").")
                  {:type ::leak :fn qname :form (:form nd)})))

(defn- client-reader? [reader-site source-site]
  (or (= :client reader-site) (= :both reader-site)
      (and (= :inherit reader-site) (= :server source-site))))

(defn- link? [nd] (contains? #{:branch :for :mount} (:op nd)))

(declare analyze)

(defn analyze
  "Analyze a compiled builder. arg-taints: taint per arg position.
  ctx: {:qname :resolve :reach (fn [callee-qname] #{arg positions reaching the client})
        :report (fn [entry])}. Returns the taint of the ret node."
  [ctx nodes {:keys [ret arg-ids holes]} arg-taints]
  (let [{:keys [qname resolve reach]} ctx
        arg-pos (zipmap arg-ids (range))
        taints (volatile! [])
        site-of (fn [nd] (or (:site nd) :both))]
    (doseq [[i nd] (map-indexed vector nodes)]
      (let [in-t (fn [j] (nth @taints j))
            tn (cond
                 (:declassified nd) nil
                 (:secret nd) :all
                 (:schema-keys nd) (not-empty (:schema-keys nd))
                 :else
                 (case (:op nd)
                   :arg (nth arg-taints (arg-pos i) nil)
                   (:const :dyn :effect) nil
                   :call (let [tm (zipmap (:params nd) (map in-t (:in nd)))
                               t (form-taint resolve tm (:pseudo nd))]
                           (when (and (not= :server (:site nd))
                                      (some #(:secret (resolve %)) (filter symbol? (flatten (seq [(:pseudo nd)])))))
                             (error! qname nd "a ^:secret var is read outside server code"))
                           t)
                   :watch (in-t (first (:in nd)))
                   :shared ((:child-taint ctx) (:ctor nd) (mapv in-t (rest (:in nd))))
                   :branch (reduce join nil (map-indexed (fn [k c] ((:child-taint ctx) c (mapv in-t (nth (:args nd) k))))
                                                         (:ctors nd)))
                   :for ((:child-taint ctx) (:ctor nd) (into [(in-t (first (:in nd)))] (map in-t (:args nd))))
                   :mount nil
                   nil))]
        (vswap! taints conj tn)
        ;; sinks: this node reading a tainted input
        (let [reads (case (:op nd)
                      (:call :watch :effect :shared) (:in nd)
                      (:branch :for) (take 1 (:in nd))
                      :mount (if (:ctor-sym nd) [] (take 1 (:in nd)))
                      [])]
          (doseq [j reads]
            (let [src (nth nodes j)]
              (when (and (nth @taints j) (not (:declassified src))
                         (client-reader? (if (link? nd) :both (site-of nd)) (site-of src)))
                (error! qname src "a secret value flows to the client"))))
          ;; arguments of a reactive fn call that reach the client
          (when-let [callee (:ctor-sym-q nd)]
            (let [r (reach callee)]
              (doseq [[k j] (map-indexed vector (:in nd))]
                (when (and (nth @taints j) (contains? r k))
                  (error! qname (nth nodes j) (str "a secret value is passed to " callee ", which shows it on the client")))))))))
    ;; template holes are client reads
    (doseq [[kind _ a b] holes
            :when (not= :child kind)
            j (case kind (:attr :event) [b] :foreign [a b] [a])]
      (when (nth @taints j)
        (error! qname (nth nodes j) "a secret value is rendered")))
    (when ret (nth @taints ret))))
