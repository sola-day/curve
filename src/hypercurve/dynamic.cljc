(ns hypercurve.dynamic
  "Dynamic program tables (design §4.6): reactive code compiled at run time
  (notebooks, plugins, user dashboards) and shipped as data.

  The server compiles source to a table (compile-table, JVM only). The table
  is plain data - code inside it stays as forms - so it crosses the wire like
  any value. Each peer turns it back into a ctor with load: forms are run by
  a small interpreter that only calls functions from an allow-list. Nothing
  is evaluated with eval; untrusted snippets cannot reach anything else.
  (A build that wants full Clojure in snippets can pass its own :eval, e.g.
  SCI; it is not bundled.)"
  (:refer-clojure :exclude [load])
  (:require [clojure.string :as str]
            [hypercurve.runtime :as rt]))

(def default-allow
  "Pure functions a dynamic snippet may call."
  {'str str 'pr-str pr-str 'name name 'count count 'inc inc 'dec dec '+ + '- - '* * '/ /
   '= = 'not= not= '< < '> > '<= <= '>= >= 'not not 'get get 'get-in get-in 'assoc assoc
   'dissoc dissoc 'first first 'second second 'rest rest 'last last 'nth nth 'vec vec 'vector vector
   'hash-map hash-map 'hash-set hash-set 'map map 'mapv mapv 'filter filter 'filterv filterv 'remove remove
   'reduce reduce 'sort sort 'sort-by sort-by 'take take 'drop drop 'range range 'keys keys 'vals vals
   'conj conj 'into into 'empty? empty? 'some? some? 'nil? nil? 'identity identity 'max max 'min min
   'keyword keyword 'subs subs 'format #?(:clj format :cljs (fn [& _] (throw (js/Error. "format is not available"))))
   'str/join str/join 'str/upper-case str/upper-case 'clojure.string/join str/join})

(defn- strip [sym]
  (if (contains? #{"clojure.core" "cljs.core"} (namespace sym)) (symbol (name sym)) sym))

(declare interpret)

(defn- bind-params [env params args]
  (loop [env env [p & ps :as params] params args args]
    (cond (empty? params) env
          (= '& p) (assoc env (first ps) (seq args))
          :else (recur (assoc env p (first args)) ps (rest args)))))

(defn interpret
  "Evaluate form with locals env, calling only allow-listed functions."
  [allow env form]
  (let [ev #(interpret allow env %)]
    (cond
      (symbol? form)
      (let [s (strip form)]
        (cond (contains? env form) (get env form)
              (contains? allow s) (get allow s)
              (rt/ctor-by-name form) (rt/ctor-by-name form)
              :else (throw (ex-info (str "hypercurve.dynamic: not allowed: " form) {:symbol form}))))
      (vector? form) (mapv ev form)
      (map? form) (into {} (map (fn [[k v]] [(ev k) (ev v)])) form)
      (set? form) (set (map ev form))
      (seq? form)
      (let [[h & args] form
            hs (when (symbol? h) (strip h))]
        (case hs
          quote (first args)
          if (if (ev (first args)) (ev (second args)) (ev (nth args 2 nil)))
          when (when (ev (first args)) (last (map ev (rest args))))
          when-not (when-not (ev (first args)) (last (map ev (rest args))))
          do (last (map ev args))
          and (reduce (fn [_ x] (let [v (ev x)] (if v v (reduced v)))) true args)
          or (reduce (fn [_ x] (let [v (ev x)] (if v (reduced v) v))) nil args)
          cond (some (fn [[t e]] (when (ev t) [(ev e)])) (partition 2 args))
          (let let*) (let [[bs & body] args
                           env' (reduce (fn [e [k v]] (assoc e k (interpret allow e v))) env (partition 2 bs))]
                       (last (map #(interpret allow env' %) body)))
          -> (ev (reduce (fn [x f] (if (seq? f) (apply list (first f) x (rest f)) (list f x))) (first args) (rest args)))
          ->> (ev (reduce (fn [x f] (if (seq? f) (concat f [x]) (list f x))) (first args) (rest args)))
          (fn fn*) (let [[params & body] (if (symbol? (first args)) (rest args) args)]
                     (fn [& xs] (let [env' (bind-params env params xs)]
                                  (last (map #(interpret allow env' %) body)))))
          (apply (ev h) (map ev args))))
      :else form)))

(defn- ctor-form? [x] (and (seq? x) (contains? #{'hypercurve.runtime/ctor 'ctor} (first x))))

(defn- code->data [code]
  (cond
    (ctor-form? code) {::ctor (code->data (second code))}
    (map? code) (into {} (map (fn [[k v]] [k (if (contains? #{:f :sel :key :default :validate :ctor-fn} k)
                                               (if (ctor-form? v) (code->data v) v)
                                               (code->data v))]))
                      code)
    (vector? code) (mapv code->data code)
    :else code))

#?(:clj
   (defn compile-table
     "Compile (params body...) as a reactive fn named qname into table data.
     ns: the namespace symbols resolve in (default hypercurve.dynamic.user)."
     [qname params body & {:keys [ns]}]
     (require 'hypercurve.core)
     (let [ns (or ns (do (binding [*ns* (create-ns 'hypercurve.dynamic.user)]
                           (refer-clojure)
                           (alias 'r 'hypercurve.core))
                         'hypercurve.dynamic.user))
           code (binding [*ns* (the-ns ns)]
                  ((requiring-resolve 'hypercurve.compiler/compile-defn*) nil qname params body {}))]
       (code->data code))))

(defn load
  "Turn table data back into a ctor (registered under its name, so it can
  be mounted with r/call on both peers). opts: :allow (fn allow-list),
  :eval (fn [form] -> value) to replace the interpreter."
  ([data] (load data {}))
  ([data {:keys [allow eval]}]
   (let [allow (or allow default-allow)
         run (or eval #(interpret allow {} %))
         build (fn build [x]
                 (cond
                   (and (map? x) (contains? x ::ctor))
                   (let [m (::ctor x)]
                     (rt/ctor (-> m
                                  (update :name #(if (and (seq? %) (= 'quote (first %))) (second %) %))
                                  (update :nodes (fn [ns] (mapv (fn [nd]
                                                                  (into {} (map (fn [[k v]]
                                                                                  [k (cond
                                                                                       (contains? #{:f :sel :key :default :validate :ctor-fn} k)
                                                                                       (when (some? v) (run v))
                                                                                       (= k :v) (if (and (seq? v) (= 'quote (first v))) (second v) v)
                                                                                       (contains? #{:readers :bind :var} k) (if (and (seq? v) (= 'quote (first v))) (second v) v)
                                                                                       (= k :ctor) (build v)
                                                                                       (= k :ctors) (mapv build v)
                                                                                       :else v)])
                                                                                nd)))
                                                                ns)))
                                  (update :render (fn [r] (into {} (map (fn [[k v]] [k (if (and (seq? v) (= 'quote (first v))) (second v) v)]) r)))))))
                   :else x))
         c (build data)]
     (rt/register-ctor! c))))
