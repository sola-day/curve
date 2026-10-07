(ns hypercurve.incseq
  "Ordered-sequence diffs.

  A diff transforms a vector of size n into a vector of size n':
    1. grow:        append `grow` empty slots          (size becomes `degree`)
    2. permutation: move items, a sparse map {from to} over [0, degree)
    3. shrink:      drop the last `shrink` slots         (size n' = degree - shrink)
    4. change:      set values at positions {i v} in [0, n')
  so n = degree - grow. Diffs compose (`combine`) when the output size of
  the first equals the input size of the second; `combine` is associative
  and `(empty-diff n)` is a two-sided identity on size n.")

(defn empty-diff [n] {:degree n :grow 0 :shrink 0 :permutation {} :change {}})

(defn in-size [d] (- (:degree d) (:grow d)))
(defn out-size [d] (- (:degree d) (:shrink d)))

(defn empty-diff? [d]
  (and (zero? (:grow d)) (zero? (:shrink d)) (empty? (:permutation d)) (empty? (:change d))))

(defn- move [p i] (get p i i))

(defn inverse [p] (reduce-kv (fn [m k v] (assoc m v k)) {} p))

(defn compose
  "Sparse permutation equal to applying f then g."
  [f g]
  (let [finv (inverse f)
        ks (into (set (keys f)) (map #(move finv %)) (keys g))]
    (reduce (fn [m i] (let [j (move g (move f i))] (if (= i j) m (assoc m i j)))) {} ks)))

(defn patch
  "Apply diff d to vector v."
  [v d]
  (let [{:keys [degree grow shrink permutation change]} d
        v (vec v)]
    (when-not (= (count v) (- degree grow))
      (throw (ex-info "incseq/patch: size mismatch" {:size (count v) :diff d})))
    (let [grown (into v (repeat grow nil))
          moved (if (empty? permutation)
                  grown
                  (persistent! (reduce-kv (fn [acc from to] (assoc! acc to (nth grown from)))
                                          (transient grown) permutation)))
          shrunk (if (pos? shrink) (into [] (subvec moved 0 (- degree shrink))) moved)]
      (if (empty? change)
        shrunk
        (persistent! (reduce-kv assoc! (transient shrunk) change))))))

(defn combine
  "Diff equal to applying d1 then d2."
  [d1 d2]
  (when-not (= (out-size d1) (in-size d2))
    (throw (ex-info "incseq/combine: size mismatch" {:d1 d1 :d2 d2})))
  (let [{deg1 :degree g1 :grow s1 :shrink p1 :permutation c1 :change} d1
        {g2 :grow s2 :shrink p2 :permutation c2 :change} d2
        n1 (- deg1 s1)
        D (+ deg1 g2)
        ;; move d1's dead tail after d2's ys slots
        swap (if (or (zero? s1) (zero? g2))
               {}
               (merge (into {} (for [i (range n1 deg1)] [i (+ i g2)]))
                      (into {} (for [i (range deg1 D)] [i (- i s1)]))))
        p (compose (compose p1 swap) p2)
        n2 (- (+ n1 g2) s2)
        c (reduce-kv (fn [m i v] (let [j (move p2 i)] (if (< j n2) (assoc m j v) m))) {} c1)]
    {:degree D :grow (+ g1 g2) :shrink (+ s1 s2) :permutation p :change (merge c c2)}))

(defn- unique-keys
  "Keys made unique by occurrence count, so duplicate keys still diff correctly."
  [kf xs]
  (let [seen (volatile! {})]
    (mapv (fn [x] (let [k (kf x) n (get @seen k 0)]
                    (vswap! seen assoc k (inc n))
                    (if (zero? n) k [::dup k n])))
          xs)))

(defn diff-by
  "Diff turning vector `xs` into vector `ys`, matching items by (kf item).
  Changed items keep their slot; `value-diff`, when given, produces a nested
  patch for an item instead of a full replacement: it returns nil for equal,
  [:patch p] or [:set v]."
  ([kf xs ys] (diff-by kf xs ys nil))
  ([kf xs ys value-diff]
   (let [xs (vec xs) ys (vec ys)
         ko (unique-keys kf xs) kn (unique-keys kf ys)
         pos-xs (zipmap ko (range))
         pos-ys (zipmap kn (range))
         n (count xs)
         added (filterv #(not (contains? pos-xs %)) kn)
         removed (filterv #(not (contains? pos-ys %)) ko)
         grow (count added) shrink (count removed)
         degree (+ n grow)
         n' (count ys)
         ;; current position of each key in the grown space
         cur (merge pos-xs (zipmap added (range n degree)))
         ;; target: survivors and additions to their ys index, removed to the tail
         target (merge pos-ys (zipmap removed (range n' degree)))
         perm (reduce-kv (fn [m k from] (let [to (target k)] (if (= from to) m (assoc m from to))))
                         {} cur)
         added? (set added)
         [change patches]
         (reduce (fn [[c ps] i]
                   (let [k (kn i) v (ys i)]
                     (if (added? k)
                       [(assoc c i v) ps]
                       (let [ov (xs (pos-xs k))]
                         (cond
                           (identical? ov v) [c ps]
                           value-diff (let [[t x] (value-diff ov v)]
                                        (case t
                                          nil [c ps]
                                          :patch [c (assoc ps i x)]
                                          :set [(assoc c i x) ps]))
                           (= ov v) [c ps]
                           :else [(assoc c i v) ps])))))
                 [{} {}] (range n'))]
     (cond-> {:degree degree :grow grow :shrink shrink :permutation perm :change change}
       (seq patches) (assoc :patch patches)))))

(defn diff-positional
  "Diff by position: changes in place, growth/shrink at the tail."
  ([xs ys] (diff-positional xs ys nil))
  ([xs ys value-diff]
   (let [xs (vec xs) ys (vec ys)
         n (count xs) n' (count ys)
         grow (max 0 (- n' n)) shrink (max 0 (- n n'))
         [change patches]
         (reduce (fn [[c ps] i]
                   (if (< i n)
                     (let [ov (xs i) v (ys i)]
                       (cond
                         (identical? ov v) [c ps]
                         value-diff (let [[t x] (value-diff ov v)]
                                      (case t nil [c ps] :patch [c (assoc ps i x)] :set [(assoc c i x) ps]))
                         (= ov v) [c ps]
                         :else [(assoc c i v) ps]))
                     [(assoc c i (ys i)) ps]))
                 [{} {}] (range n'))]
     (cond-> {:degree (+ n grow) :grow grow :shrink shrink :permutation {} :change change}
       (seq patches) (assoc :patch patches)))))
