(ns hypercurve.delta
  "Structural deltas between two values. Used wherever a value crosses a
  boundary that remembers what the other side already has (the wire, the
  shared-value log, the DOM list reconciler).

  A delta is one of
    [:v x]                                  replace with x
    [:m {:set {k v} :dissoc #{k} :patch {k delta}}]   incmap
    [:s incseq-diff]                        incseq; diff may carry :patch {i delta}
    [:t {:add #{x} :remove #{x}}]           incset
  `(diff a b)` returns nil when a = b."
  (:require [hypercurve.incseq :as s]))

(defn- plain-map? [x] (and (map? x) (not (record? x))))
(defn- plain-set? [x] (set? x))

(defn- keyed?
  "Vectors of entity maps diff by :id so a changed row becomes a field patch."
  [v]
  (and (seq v) (every? #(and (map? %) (contains? % :id)) v)))

(declare diff)

(defn- item-diff [a b]
  (when-let [d (diff a b)]
    (if (= :v (first d)) [:set (second d)] [:patch d])))

(defn diff [a b]
  (cond
    (identical? a b) nil
    (and (plain-map? a) (plain-map? b))
    (let [dissoc (into #{} (remove #(contains? b %)) (keys a))
          [set patch] (reduce-kv
                        (fn [[st pt] k v]
                          (if (contains? a k)
                            (let [ov (get a k)]
                              (if-let [d (and (not (identical? ov v)) (diff ov v))]
                                (if (= :v (first d)) [(assoc st k v) pt] [st (assoc pt k d)])
                                [st pt]))
                            [(assoc st k v) pt]))
                        [{} {}] b)]
      (cond
        (and (empty? dissoc) (empty? set) (empty? patch)) nil
        ;; a delta touching most keys is no smaller than the value
        (and (empty? patch) (> (+ (count set) (count dissoc)) (max 1 (quot (count b) 2))) (pos? (count b)))
        [:v b]
        :else [:m (cond-> {}
                    (seq set) (assoc :set set)
                    (seq dissoc) (assoc :dissoc dissoc)
                    (seq patch) (assoc :patch patch))]))

    (and (vector? a) (vector? b))
    (let [d (if (and (or (keyed? a) (empty? a)) (or (keyed? b) (empty? b)))
              (s/diff-by :id a b item-diff)
              (s/diff-positional a b item-diff))]
      (cond
        (and (s/empty-diff? d) (empty? (:patch d))) nil
        (empty? a) [:v b]
        (empty? b) [:v b]
        :else [:s d]))

    (and (plain-set? a) (plain-set? b))
    (let [add (into #{} (remove a) b) rm (into #{} (remove b) a)]
      (cond
        (and (empty? add) (empty? rm)) nil
        (> (+ (count add) (count rm)) (count b)) [:v b]
        :else [:t (cond-> {} (seq add) (assoc :add add) (seq rm) (assoc :remove rm))]))

    (= a b) nil
    :else [:v b]))

(defn patch [a [t x]]
  (case t
    :v x
    :m (let [{:keys [set dissoc patch]} x
             m (reduce clojure.core/dissoc a dissoc)
             m (reduce-kv assoc m set)]
         (reduce-kv (fn [m k d] (assoc m k (hypercurve.delta/patch (get m k) d))) m patch))
    :s (let [v (s/patch a (-> x (clojure.core/dissoc :patch) (update :degree #(or % (count a)))))]
         (reduce-kv (fn [v i d] (assoc v i (hypercurve.delta/patch (nth v i) d))) v (:patch x)))
    :t (-> (reduce disj a (:remove x)) (into (:add x)))))
