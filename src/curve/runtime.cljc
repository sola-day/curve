(ns curve.runtime
  "Table-driven evaluator. Both peers (client and server) run this same code
  over the same program table; each computes only the nodes sited on it and
  receives the rest over the wire.

  Program table, per reactive function (a \"ctor\"):
    {:name sym :nargs n :ret node-id :nodes [node ...]}
  Node ids are dense and topologically ordered: a node only reads lower ids
  in its own frame. Args occupy ids 0..nargs-1. Node maps:
    {:op :arg}                                   value supplied by the caller
    {:op :const :v x}
    {:op :call :site s :f host-fn :in [ids]}     host-fn absent on the other peer
    {:op :watch :site s :in [atom-id]}           deref + watch a reference
    {:op :branch :in [test] :sel f :ctors [c..] :args [ids]}  conditional child
    {:op :mount :ctor c :in [ids]}               reactive function call
    {:op :mount :in [ctor-id & ids]}             dynamic (higher-order) call
    {:op :for :in [coll] :key f :ctor c :args [ids]}  keyed child per item
    {:op :effect :site s :f host-fn :in [ids]}   (f args) -> cleanup fn
  Every node also carries :readers, the set of sites whose nodes read it;
  that is what decides which cells cross the wire.

  A frame is one instance of a ctor: a value array plus dirty bits. Frames
  nest (branch, mount, for); a child's identity on both peers is the path
  (parent, node, key), so peers agree without coordination."
  (:refer-clojure :exclude [run!])
  (:require [curve.codec :as codec]
            [curve.delta :as delta])
  #?(:cljs (:require-macros [curve.runtime :refer [guarded]])))

;; ---------------------------------------------------------------- values

(def pending ::pending)
(defn pending? [x] (identical? x pending))

(defrecord Failure [error])
(defn failure? [x] (instance? Failure x))
(defn failure [e] (->Failure e))

(defn other-site [s] (case s :client :server :server :client))

;; ---------------------------------------------------------------- frames

(defrecord Frame [peer ctor site parent node key depth seq-id
                  ^objects vals #?(:clj ^booleans dirty :cljs dirty) ^objects subs ^objects exported ^objects sent
                  children cleanups remote-id alive arg-srcs order watching effects rendered shared
                  ^objects plan queued ^objects deps])

(defn kids-seq
  "Child frames of a node: nil, a single frame, or a map key -> frame."
  [x]
  (cond (nil? x) nil (instance? Frame x) [x] :else (vals x)))

(defn ctor
  "Finish a program-table entry: derive same-frame dependents from :in."
  [m]
  (let [nodes (:nodes m)
        deps (reduce (fn [acc [j nd]] (reduce (fn [acc i] (update acc i (fnil conj []) j)) acc (:in nd)))
                     {} (map-indexed vector nodes))]
    (assoc m :dependents (mapv #(get deps % []) (range (count nodes))))))

(defn- node-at [f i] (nth (:nodes (:ctor f)) i))

(defn- resolve-site
  "Sites in the table are :client, :server, :inherit (the frame's site) or
  nil (computed identically on both peers)."
  [f s]
  (if (= s :inherit) (:site f) s))

(defn node-site [f nd] (resolve-site f (:site nd)))

(defn- arg-id [ctor k] (nth (:arg-ids ctor) k k))
(defn value [f i] (aget ^objects (:vals f) i))

(defn- schedule-frame! [^Frame f]
  (let [q (.-queued f)]
    (when-not @q
      (vreset! q true)
      (let [peer (:peer f)]
        (vswap! (:queue peer) assoc [(:depth f) (:seq-id f)] f)
        (when-let [hook (:on-schedule peer)] (hook))))))

(defn mark-dirty! [f i]
  (when @(:alive f)
    (aset #?(:clj ^booleans (:dirty f) :cljs (:dirty f)) i true)
    (schedule-frame! f)))

(defn subscribe!
  "Call cb whenever cell (f, i) changes. Returns an unsubscribe fn."
  [f i cb]
  (let [^objects subs (:subs f)
        s (or (aget subs i) (let [v (volatile! #{})] (aset subs i v) v))]
    (vswap! s conj cb)
    (fn [] (vswap! s disj cb))))

(defn on-cleanup! [f g] (vswap! (:cleanups f) conj g))

(defn hole-node
  "Node id a template hole reads: [:text p id] [:child p id] [:attr p name id prefix] [:event p type id]."
  [[kind _ a b]]
  (if (contains? #{:attr :event} kind) b a))

(defn child-hole?
  "Is node i of ctor rendered as a child slot in the ctor's template?"
  [ctor i]
  (some #(and (= :child (first %)) (= i (nth % 2))) (:holes (:render ctor))))

(defn ordered-children
  "Child frames of node i in render order."
  [f i]
  (let [k (get @(:children f) i)]
    (cond (nil? k) nil
          (instance? Frame k) [k]
          :else (keep #(get k %) (get @(:order f) i)))))

(declare export! queue-send!)

(defn- changed!
  "Cell (f, i) took a new value: wake local readers and remote ones."
  [^Frame f i]
  (let [i (int i)
        ^ints deps (aget ^objects (.-deps f) i)
        n (alength deps)]
    (when (pos? n)
      (let [dirty (.-dirty f)]
        (dotimes [k n] (aset #?(:clj ^booleans dirty :cljs dirty) (aget deps k) true)))
      ;; while a frame is processed its queued flag stays set: it reaches its
      ;; higher-numbered dependents in this same pass (topological order)
      (schedule-frame! f))
    (when-let [s (aget ^objects (.-subs f) i)]
      (doseq [cb @s] (cb)))
    (when (aget ^objects (.-exported f) i)
      (queue-send! f i))))

(defn- same-value? [a b]
  (or (identical? a b)
      (and (not (fn? a)) (not (fn? b)) (= a b))))

(defn set-cell!
  "Set a cell's value, propagating if it changed."
  [f i v]
  (when (and @(:alive f) (not (same-value? (value f i) v)))
    (aset ^objects (:vals f) i v)
    (changed! f i)))

(defn- set-live!
  "set-cell! for steps, which only run on live frames."
  [^Frame f i v]
  (let [^objects vs (.-vals f)]
    (when-not (same-value? (aget vs i) v)
      (aset vs i v)
      (changed! f i))))

;; ---------------------------------------------------------------- mounting

(declare mount-frame! unmount-frame! bind-child! unbind! apply-val! plan-for deps-of)

(defn- new-frame [peer ctor site parent node key]
  (let [n (count (:nodes ctor))]
    (->Frame peer ctor site parent node key
             (if parent (inc (:depth parent)) 0)
             (vswap! (:seq peer) inc)
             (object-array n) #?(:clj (boolean-array n) :cljs (object-array n)) (object-array n) (object-array n) (object-array n)
             (volatile! {}) (volatile! []) (volatile! nil) (volatile! true)
             (volatile! {}) (volatile! {}) (volatile! {}) (volatile! {})
             (volatile! false) (volatile! {})
             (plan-for peer ctor site) (volatile! false)
             (deps-of peer ctor))))

(defn- arg-source
  "An arg is fed either from a parent cell [frame i] or a pushed value [:value v]."
  [child k src]
  (let [k (arg-id (:ctor child) k)]
    (if (= :value (first src))
      (aset ^objects (:vals child) k (second src))
      (let [[pf pi] src]
        (aset ^objects (:vals child) k (value pf pi))
        (vswap! (:arg-srcs child) assoc k [pf pi])
        (on-cleanup! child (subscribe! pf pi #(mark-dirty! child k)))))))

(defn- children-of [f i] (get @(:children f) i))

(defn mount-frame!
  "Instantiate ctor as a child of (parent, node, key) with arg sources."
  [peer ctor site parent node key arg-srcs]
  (let [f (new-frame peer ctor site parent node key)
        nodes (:nodes ctor)]
    (dotimes [i (count nodes)] (aset ^objects (:vals f) i pending))
    (doseq [[k src] (map-indexed vector arg-srcs)] (arg-source f k src))
    (vreset! (:rendered f) (if parent
                             (boolean (and @(:rendered parent) (child-hole? (:ctor parent) node)))
                             (boolean (:render-root? peer))))
    (on-cleanup! f #(doseq [g (vals @(:effects f))] (g)))
    (on-cleanup! f #(doseq [{:keys [release]} (vals @(:shared f))] (release)))
    (vswap! (:frames peer) assoc (:seq-id f) f)
    ;; everything computable starts dirty; constants are set directly
    (dotimes [i (count nodes)]
      (let [nd (nth nodes i)]
        (case (:op nd)
          :arg nil
          :const (aset ^objects (:vals f) i (:v nd))
          (aset #?(:clj ^booleans (:dirty f) :cljs (:dirty f)) i true))))
    (export! f)
    (when-let [hook (:on-mount peer)] (hook f))
    (when parent (bind-child! peer f))
    (schedule-frame! f)
    f))

(defn unmount-frame! [f]
  (when @(:alive f)
    (doseq [[_ kids] @(:children f)]
      (doseq [c (kids-seq kids)] (unmount-frame! c)))
    (vreset! (:alive f) false)
    (doseq [g (rseq @(:cleanups f))] (g))
    (let [peer (:peer f)]
      (vswap! (:queue peer) dissoc [(:depth f) (:seq-id f)])
      (vswap! (:frames peer) dissoc (:seq-id f))
      (when-let [hook (:on-unmount peer)] (hook f))
      (unbind! peer f))))

;; ---------------------------------------------------------------- exports

(defn- link-op? [op] (contains? #{:branch :mount :for} op))

(defn- remote-read!
  "The other peer reads cell (f, i). Make sure whoever owns it sends it."
  [f i]
  (let [peer (:peer f)
        nd (node-at f i)
        op (:op nd)]
    (cond
      (= op :arg)
      (let [src (get @(:arg-srcs f) i)]
        (when src (let [[pf pi] src] (remote-read! pf pi))))

      (link-op? op)
      (when-not (aget ^objects (:exported f) i)
        ;; the link's own value is computed on both peers from its children;
        ;; exported here means "children rets are read remotely"
        (aset ^objects (:exported f) i :link)
        (doseq [c (kids-seq (children-of f i))]
          (remote-read! c (:ret (:ctor c)))))

      (= (node-site f nd) (:site peer))
      (when-not (aget ^objects (:exported f) i)
        (aset ^objects (:exported f) i true)
        (when-not (pending? (value f i)) (queue-send! f i)))

      :else nil)))

(defn- export! [f]
  (let [me (:site (:peer f))
        other (other-site me)]
    (doseq [[i nd] (map-indexed vector (:nodes (:ctor f)))]
      (when (some #(= other (resolve-site f %)) (:readers nd))
        (remote-read! f i)))
    ;; a rendered frame's template holes are read by the client
    (when (and @(:rendered f) (= me :server))
      (doseq [hole (:holes (:render (:ctor f)))]
        (when (not= :child (first hole)) (remote-read! f (hole-node hole)))))))

;; ---------------------------------------------------------------- compute

(defn- inputs [f ids]
  (loop [ids ids acc []]
    (if-let [[i & more] (seq ids)]
      (let [v (value f i)]
        (cond (pending? v) pending
              (failure? v) v
              :else (recur more (conj acc v))))
      acc)))

(defn- child-ret-value [c]
  (if-let [r (:ret (:ctor c))] (value c r) nil))

(defn- link-child!
  "Make node i of f follow child c's return value."
  [f i c]
  (when-let [r (:ret (:ctor c))]
    (on-cleanup! c (subscribe! c r #(mark-dirty! f i)))
    (when (= :link (aget ^objects (:exported f) i))
      (remote-read! c r))))

(defn server-free?
  "True when no node of ctor (running at site), nor of any ctor it mounts,
  is computed on or read by the server."
  [peer ctor site]
  (let [k [(:name ctor) site]
        memo (:server-free peer)]
    (if (contains? @memo k)
      (get @memo k)
      (do
        (vswap! memo assoc k false) ; recursion: assume the worst
        (let [rs (fn [s] (if (= s :inherit) site s))
              sub (fn [nd c] (or (nil? c) (server-free? peer c (or (rs (:ctx-site nd)) site))))
              r (every? (fn [nd]
                          (and (not= :server (rs (:site nd)))
                               (not-any? #(= :server (rs %)) (:readers nd))
                               (case (:op nd)
                                 :shared false
                                 :branch (every? #(sub nd %) (:ctors nd))
                                 :for (sub nd (:ctor nd))
                                 :mount (if-let [t (:ctor-fn nd)] (sub nd (t)) false)
                                 true)))
                        (:nodes ctor))]
          (vswap! memo assoc k r)
          r)))))

(defn- skip-child?
  "Demand-driven: the server does not instantiate a subtree it neither
  computes nor reads; it only exports what the subtree captures."
  [f i ctor]
  (let [peer (:peer f)
        nd (node-at f i)]
    (and (= :server (:site peer))
         (not-any? #(= :server (resolve-site f %)) (:readers nd))
         (server-free? peer ctor (or (resolve-site f (:ctx-site nd)) (:site f))))))

(defn- mount-child! [f i ctor key arg-srcs]
  (if (skip-child? f i ctor)
    (do (doseq [src arg-srcs]
          (when-not (= :value (first src)) (let [[pf pi] src] (remote-read! pf pi))))
        nil)
    (let [nd (node-at f i)
          site (or (resolve-site f (:ctx-site nd)) (:site f))
          c (mount-frame! (:peer f) ctor site f i key arg-srcs)]
      (link-child! f i c)
      c)))

(defn- arg-srcs [f ids] (mapv (fn [j] [f j]) ids))

(defn- compute-branch [f i nd]
  (let [t (value f (first (:in nd)))
        cur (children-of f i)]
    (cond
      (or (pending? t) (failure? t))
      (do (when cur (unmount-frame! cur) (vswap! (:children f) dissoc i)) t)

      :else
      (let [sel ((or (:sel nd) (fn [x] (if x 0 1))) t)]
        (if (and cur (= sel (:key cur)))
          (child-ret-value cur)
          (do (when cur (unmount-frame! cur))
              (if-let [ctor (nth (:ctors nd) sel nil)]
                (let [srcs (arg-srcs f (nth (:args nd) sel))
                      c (mount-child! f i ctor sel srcs)]
                  (if c
                    (do (vswap! (:children f) assoc i c) (child-ret-value c))
                    (do (vswap! (:children f) dissoc i) pending)))
                (do (vswap! (:children f) dissoc i) nil))))))))

(defn- compute-mount [f i nd]
  (let [[ctor ids key] (if-let [c (or (:ctor nd) (when-let [t (:ctor-fn nd)] (t)))]
                         [c (:in nd) (:name c)]
                         (let [c (value f (first (:in nd)))]
                           [c (rest (:in nd)) (when (map? c) (:name c))]))
        cur (children-of f i)]
    (cond
      (or (pending? ctor) (failure? ctor) (nil? ctor))
      (do (when cur (unmount-frame! cur) (vswap! (:children f) dissoc i))
          (if (nil? ctor) nil ctor))

      (and cur (identical? ctor (:ctor cur))) (child-ret-value cur)

      :else
      (do (when cur (unmount-frame! cur))
          (if-let [c (mount-child! f i ctor key (arg-srcs f ids))]
            (do (vswap! (:children f) assoc i c) (child-ret-value c))
            (do (vswap! (:children f) dissoc i) pending))))))

(defn- compute-for [f i nd]
  (let [coll (value f (first (:in nd)))]
    (cond
      (or (pending? coll) (failure? coll))
      coll

      (skip-child? f i (:ctor nd))
      (do (doseq [[pf pi] (arg-srcs f (:args nd))] (remote-read! pf pi))
          pending)

      :else
      (let [kf (or (:key nd) identity)
            items (vec coll)
            cur (or (children-of f i) {})
            ;; keys made unique per occurrence
            ks (let [seen (volatile! {})]
                 (mapv (fn [x] (let [k (kf x) n (get @seen k 0)]
                                 (vswap! seen assoc k (inc n))
                                 (if (zero? n) k [::dup k n])))
                       items))
            kset (set ks)
            captures (arg-srcs f (:args nd))]
        (doseq [[k c] cur] (when-not (contains? kset k) (unmount-frame! c)))
        (let [kids (reduce (fn [m [k x]]
                             (if-let [c (get cur k)]
                               (do (set-cell! c (arg-id (:ctor c) 0) x) (assoc m k c))
                               (assoc m k (mount-child! f i (:ctor nd) k
                                                        (into [[:value x]] captures)))))
                           {} (map vector ks items))]
          (vswap! (:children f) assoc i kids)
          (vswap! (:order f) assoc i ks)
          (when-let [h (:on-children (:peer f))] (h f i))
          (let [rets (mapv #(child-ret-value (kids %)) ks)]
            (or (some #(when (or (pending? %) (failure? %)) %) rets) rets)))))))

(defn- compute-watch [f i nd]
  (let [r (value f (first (:in nd)))]
    (if (or (pending? r) (failure? r))
      r
      (do
        (when-let [[old-ref unwatch] (get @(:watching f) i)]
          (when-not (identical? old-ref r) (unwatch)))
        (when-not (identical? r (first (get @(:watching f) i)))
          (let [k (gensym "curve-watch")]
            (add-watch r k (fn [_ _ _ _] ((:post! (:peer f)) #(mark-dirty! f i))))
            (let [unwatch #(remove-watch r k)]
              (vswap! (:watching f) assoc i [r unwatch])
              (on-cleanup! f unwatch))))
        @r))))

(defn- compute-effect [f i nd]
  (let [args (inputs f (:in nd))]
    (when-let [g (get @(:effects f) i)] (g) (vswap! (:effects f) dissoc i))
    (if (or (pending? args) (failure? args))
      args
      (let [cleanup (apply (:f nd) args)]
        (when (fn? cleanup)
          (vswap! (:effects f) assoc i cleanup))
        nil))))

(defn- compute-shared
  "Server: follow a process-wide shared value (curve.shared), keyed by the
  ctor, the key value and the captured values."
  [f i nd]
  (let [ins (inputs f (:in nd))]
    (if (or (pending? ins) (failure? ins))
      ins
      (let [[k & caps] ins
            ident [(:name (:ctor nd)) k (vec caps)]
            cur (get @(:shared f) i)]
        (when (and cur (not= ident (:ident cur)))
          ((:release cur))
          (vswap! (:shared f) dissoc i))
        (when-not (get @(:shared f) i)
          (let [peer (:peer f)
                h ((:shared-acquire peer) (:ctor nd) ident (vec caps)
                   (fn [] ((:post! peer) #(mark-dirty! f i))))]
            (vswap! (:shared f) assoc i (assoc h :ident ident))))
        (let [h (get @(:shared f) i)
              [v version] ((:current h))]
          (vswap! (:shared f) assoc-in [i :version] version)
          v)))))

;; ---- plans: the table is data on the wire and in the bundle; on first use a
;; peer turns each ctor into one specialised step fn per node it computes,
;; cached per (ctor, site). This removes per-node dispatch and lookups from
;; the propagation loop.

(defn- settled? [x] (not (or (pending? x) (failure? x))))

#?(:clj
   (defmacro guarded [& body]
     `(try ~@body (catch ~(if (:ns &env) :default 'Throwable) e# (curve.runtime/failure e#)))))

(defn- call-step [nd]
  (let [g (:f nd) in (:in nd)]
    (case (count in)
      0 (fn [f i] (set-live! f i (guarded (g))))
      1 (let [a (int (nth in 0))]
          (fn [^Frame f i] (let [x (aget ^objects (.-vals f) a)]
                      (set-live! f i (if (settled? x) (guarded (g x)) x)))))
      2 (let [a (int (nth in 0)) b (int (nth in 1))]
          (fn [^Frame f i] (let [^objects vs (.-vals f) x (aget vs a) y (aget vs b)]
                      (set-live! f i (cond (not (settled? x)) x (not (settled? y)) y
                                           :else (guarded (g x y)))))))
      3 (let [a (int (nth in 0)) b (int (nth in 1)) c (int (nth in 2))]
          (fn [^Frame f i] (let [^objects vs (.-vals f) x (aget vs a) y (aget vs b) z (aget vs c)]
                      (set-live! f i (cond (not (settled? x)) x (not (settled? y)) y (not (settled? z)) z
                                           :else (guarded (g x y z)))))))
      (fn [f i] (let [args (inputs f in)]
                  (set-live! f i (if (settled? args) (guarded (apply g args)) args)))))))

(defn- step [peer site nd]
  (let [s (let [x (:site nd)] (if (= x :inherit) site x))
        mine? (= s (:site peer))]
    (case (:op nd)
      :arg (fn [f i] (let [[pf pi] (get @(:arg-srcs f) i)]
                       (when pf (set-cell! f i (value pf pi)))))
      :const nil
      :call (when mine? (call-step nd))
      :watch (when mine? (fn [f i] (set-cell! f i (guarded (compute-watch f i nd)))))
      :effect (when mine? (fn [f i] (set-cell! f i (compute-effect f i nd))))
      :shared (when mine? (fn [f i] (set-cell! f i (compute-shared f i nd))))
      :branch (fn [f i] (set-cell! f i (compute-branch f i nd)))
      :mount (fn [f i] (set-cell! f i (compute-mount f i nd)))
      :for (fn [f i] (set-cell! f i (compute-for f i nd))))))

(defn- plan-for [peer ctor site]
  (let [^objects cache (:plans peer)]
    #?(:clj (let [m ^java.util.IdentityHashMap (get (aget cache 0) site)]
              (or (.get m ctor)
                  (let [p (object-array (map #(step peer site %) (:nodes ctor)))]
                    (.put m ctor p)
                    p)))
       :cljs (let [m (get (aget cache 0) site)]
               (or (.get m ctor)
                   (let [p (object-array (map #(step peer site %) (:nodes ctor)))]
                     (.set m ctor p)
                     p))))))

(defn- deps-of
  "Same-frame dependents as int arrays, cached per ctor."
  [peer ctor]
  (let [^objects cache (:plans peer)
        m (get (aget cache 0) :deps)]
    (or (#?(:clj .get :cljs .get) m ctor)
        (let [d (object-array (map #(int-array %) (:dependents ctor)))]
          (#?(:clj .put :cljs .set) m ctor d)
          d))))

(defn- process-frame! [^Frame f]
  (let [dirty #?(:clj ^booleans (.-dirty f) :cljs (.-dirty f))
        ^objects plan (.-plan f)
        n (alength dirty)]
    (when @(.-alive f)
      (loop [i 0]
        (when (< i n)
          (when (aget dirty i)
            (aset dirty i false)
            (when-let [st (aget plan i)] (st f i)))
          (recur (inc i))))
      (vreset! (.-queued f) false)
      ;; something marked a lower index while we were past it
      (when (loop [i 0] (cond (>= i n) false (aget dirty i) true :else (recur (inc i))))
        (schedule-frame! f)))))

(defn run!
  "Propagate until no frame is dirty. Parents before children (by depth)."
  [peer]
  (loop []
    (when-let [[k f] (first @(:queue peer))]
      (vswap! (:queue peer) dissoc k)
      (process-frame! f)
      (recur))))

;; ---------------------------------------------------------------- wire state
;; Each peer numbers its own frames for the wire (root = 0) and declares a
;; frame to the other side by path the first time it sends something for it.

(defn- out-id [f]
  (let [peer (:peer f)]
    (if (nil? (:parent f))
      0
      (or (get @(:out-ids peer) (:seq-id f))
          (let [pid (out-id (:parent f))
                id (vswap! (:next-wire-id peer) inc)]
            (vswap! (:out-ids peer) assoc (:seq-id f) id)
            (vswap! (:out-frames peer) assoc id f)
            (vswap! (:outbox peer) update :decl (fnil conj []) [id pid (:node f) (:key f)])
            (on-cleanup! f (fn []
                             (vswap! (:out-ids peer) dissoc (:seq-id f))
                             (vswap! (:out-frames peer) dissoc id)
                             (vswap! (:outbox peer) update :drop (fnil conj []) id)))
            id)))))

(defn- queue-send! [f i]
  (vswap! (:dirty-out (:peer f)) assoc [(:seq-id f) i] [f i]))

(defn- encode-value [prev v]
  (cond
    (pending? v) (when-not (pending? prev) [:p])
    (failure? v) [:e (let [e (:error v)]
                       #?(:clj (if (instance? Throwable e) (or (ex-message e) (str e)) (str e))
                          :cljs (if (instance? js/Error e) (.-message e) (str e))))]
    (fn? v) (when-not (fn? prev) [:f])
    (or (pending? prev) (failure? prev) (fn? prev) (= prev ::unsent)) [:v v]
    :else (delta/diff prev v)))

(defn- send-shared!
  "Shared cells remember only the version last sent (a cursor) and send the
  pre-encoded delta from that version, which every session shares."
  [f i {:keys [version blob]}]
  (let [^objects sent (:sent f)
        s (aget sent i)
        cursor (when (and (vector? s) (= ::version (first s))) (second s))]
    (when-not (= cursor version)
      (when-let [bs (blob cursor version)]
        ;; out-id may append a frame declaration to the outbox: call it first
        (let [id (out-id f)]
          (vswap! (:outbox (:peer f)) update :vals (fnil conj []) [id i [:raw bs]])))
      (aset sent i [::version version]))))

(defn- collect-out! [peer]
  (doseq [[f i] (vals @(:dirty-out peer))]
    (when @(:alive f)
      (if-let [sh (let [sh (get @(:shared f) i) v (value f i)]
                    (when (and sh (not (pending? v)) (not (failure? v))) sh))]
        (send-shared! f i sh)
      (let [^objects sent (:sent f)
            prev (let [s (aget sent i)] (cond (nil? s) ::unsent (= s ::nil) nil :else s))
            v (value f i)
            d (encode-value prev v)]
        (when d
          (aset sent i (if (nil? v) ::nil v))
          (let [id (out-id f)]
            (vswap! (:outbox peer) update :vals (fnil conj []) [id i d])))))))
  (vreset! (:dirty-out peer) (sorted-map)))

(defn take-message!
  "Collect everything to send since the last call. nil when there is nothing."
  [peer]
  (collect-out! peer)
  (let [m @(:outbox peer)]
    (vreset! (:outbox peer) {})
    (when (seq m) m)))

;; ---- receiving

(defn- frame-by-in-id [peer id] (get @(:in-frames peer) id))

(defn- bind! [peer f id]
  (vswap! (:in-frames peer) assoc id f)
  (vreset! (:remote-id f) id)
  (when-let [vs (get @(:stash peer) id)]
    (vswap! (:stash peer) dissoc id)
    (doseq [[i d] vs] (apply-val! peer f i d)))
  ;; children mounted before we learned our id
  (doseq [[i kids] @(:children f)
          c (kids-seq kids)]
    (when-let [cid (get @(:decls peer) [id i (:key c)])]
      (when-not @(:remote-id c) (bind! peer c cid)))))

(defn- bind-child! [peer c]
  (when-let [pid @(:remote-id (:parent c))]
    (when-let [cid (get @(:decls peer) [pid (:node c) (:key c)])]
      (bind! peer c cid))))

(defn- unbind! [peer f]
  (when-let [id @(:remote-id f)]
    (when (identical? f (frame-by-in-id peer id))
      (vswap! (:in-frames peer) dissoc id))))

(defn remote-fn? [g] (boolean (::remote (meta g))))

(defn- remote-proxy [peer f i]
  (with-meta
   (fn [& args]
    (let [token (vswap! (:next-token peer) inc)
          result (atom pending)
          id (or @(:remote-id f) (throw (ex-info "curve: remote fn frame unknown" {})))]
      (vswap! (:outbox peer) update :call (fnil conj []) [token id i (vec args)])
      (vswap! (:calls peer) assoc token result)
      (when-let [hook (:on-schedule peer)] (hook))
      result))
   {::remote true}))

(defn- authorized-val? [peer f i]
  ;; only accept values for nodes the sender owns
  (let [nd (node-at f i)]
    (and (= (node-site f nd) (other-site (:site peer)))
         (not (link-op? (:op nd))))))

(defn- apply-val! [peer f i d]
  (when (authorized-val? peer f i)
    (let [[t x] d
          prev (value f i)
          v (case t
              :p pending
              :e (failure (ex-info x {:remote true}))
              :f (remote-proxy peer f i)
              :v x
              :raw (let [d' (codec/decode-delta-blob x)]
                     (if (= :v (first d')) (second d') (delta/patch prev d')))
              (delta/patch prev d))]
      (set-cell! f i v))))

(defn- invoke-call! [peer [token id i args]]
  ;; the caller names the frame by the id *we* declared for it
  (let [f (get @(:out-frames peer) id)
        reply (fn [ok v] (vswap! (:outbox peer) update :ret (fnil conj []) [token ok v]))]
    (cond
      (nil? f) (reply false "frame not mounted")
      (not= (node-site f (node-at f i)) (:site peer)) (reply false "not callable")
      :else
      (let [g (value f i)]
        (if-not (fn? g)
          (reply false "not callable")
          (try (let [r (apply g args)]
                 (reply true (if (fn? r) nil r)))
               (catch #?(:clj Throwable :cljs :default) e
                 (reply false (or (ex-message e) (str e))))))))))

(defn receive!
  "Apply a message from the other peer."
  [peer {:keys [decl vals drop call ret]}]
  (doseq [[id pid node key] decl]
    (vswap! (:decls peer) assoc [pid node key] id)
    (when-let [p (frame-by-in-id peer pid)]
      (let [kids (get @(:children p) node)
            c (if (instance? Frame kids) (when (= key (:key kids)) kids) (get kids key))]
        (when c (bind! peer c id)))))
  (doseq [[id i d] vals]
    (if-let [f (frame-by-in-id peer id)]
      (apply-val! peer f i d)
      (vswap! (:stash peer) update id (fnil conj []) [i d])))
  (doseq [id drop]
    (when-let [f (frame-by-in-id peer id)]
      (vreset! (:remote-id f) nil))
    (vswap! (:in-frames peer) dissoc id)
    (vswap! (:stash peer) dissoc id)
    (vswap! (:decls peer) (fn [m] (into {} (remove (fn [[_ v]] (= v id))) m))))
  (doseq [c call] (invoke-call! peer c))
  (doseq [[token ok v] ret]
    (when-let [r (get @(:calls peer) token)]
      (vswap! (:calls peer) dissoc token)
      (reset! r (if ok v (failure (ex-info (str v) {:remote true}))))
      (when-let [cb (get @(:call-callbacks peer) token)] (cb @r)))))

;; ---------------------------------------------------------------- peer

(defn peer
  "Create a peer for :site (:client or :server).
  opts: :on-schedule called when work is pending (to trigger run!);
        :post! how async callbacks (watches) re-enter the peer, default direct."
  [site & {:as opts}]
  (let [p (merge {:site site
                  :queue (volatile! (sorted-map))
                  :seq (volatile! 0)
                  :frames (volatile! {})
                  :dirty-out (volatile! (sorted-map))
                  :outbox (volatile! {})
                  :next-wire-id (volatile! 0)
                  :out-ids (volatile! {})
                  :out-frames (volatile! {})
                  :in-frames (volatile! {})
                  :decls (volatile! {})
                  :stash (volatile! {})
                  :calls (volatile! {})
                  :call-callbacks (volatile! {})
                  :next-token (volatile! 0)
                  :render-root? true
                  :server-free (volatile! {})
                  :plans (doto (object-array 1)
                           (aset 0 #?(:clj {:client (java.util.IdentityHashMap.) :server (java.util.IdentityHashMap.)
                                            :deps (java.util.IdentityHashMap.)}
                                      :cljs {:client (js/Map.) :server (js/Map.) :deps (js/Map.)})))
                  :shared-acquire #?(:clj (fn [& args] (apply (requiring-resolve 'curve.shared/acquire!) args))
                                     :cljs (fn [& _] (throw (js/Error. "r/shared runs on the server"))))
                  :post! (fn [g] (g))}
                 opts)]
    p))

(defn mount-root!
  "Mount ctor as the root frame. args are plain values. The root's default
  site is :client unless the ctor says otherwise."
  [peer ctor & args]
  (let [f (mount-frame! peer ctor (or (:site ctor) :client) nil nil nil (mapv (fn [a] [:value a]) args))]
    (bind! peer f 0)
    (vreset! (:remote-id f) 0)
    (vswap! (:out-frames peer) assoc 0 f)
    f))

(defn frames [peer] (vals @(:frames peer)))
