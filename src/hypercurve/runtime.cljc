(ns hypercurve.runtime
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
      :keyed true: coll is a map {k item}; one child per entry, keyed by k.
      When the map arrived as a delta (wire or shared atom), only the
      entries the delta touches are reconciled.
    {:op :effect :site s :f host-fn :in [ids]}   (f args) -> cleanup fn
  Every node also carries :readers, the set of sites whose nodes read it;
  that is what decides which cells cross the wire.

  A frame is one instance of a ctor: a value array plus dirty bits. Frames
  nest (branch, mount, for); a child's identity on both peers is the path
  (parent, node, key), so peers agree without coordination."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string]
            [hypercurve.clock :as clock]
            [hypercurve.codec :as codec]
            [hypercurve.delta :as delta])
  #?(:cljs (:require-macros [hypercurve.runtime :refer [guarded]])))

;; ---------------------------------------------------------------- values

(def pending ::pending)
(defn pending? [x] (identical? x pending))

(defprotocol Shareable
  "A reference that many sessions follow at once (hypercurve.shared). r/watch
  on one acquires a handle instead of an add-watch, and the cell is sent the
  way r/shared cells are: a version cursor and a delta encoded once."
  (-share! [r on-change]
    "Follow r; on-change is called after every change. Returns
    {:current (fn [] [value version]) :blob (fn [from to] bytes) :release (fn [])
     :delta (fn [from to] delta-or-nil)}  ; optional, lets keyed loops skip a diff"))

(defrecord Failure [error])
(defn failure? [x] (instance? Failure x))
(defn failure [e] (->Failure e))

#?(:clj
   (defmacro guarded [& body]
     `(try ~@body (catch ~(if (:ns &env) :default 'Throwable) e# (hypercurve.runtime/failure e#)))))

(defn other-site [s] (case s :client :server :server :client))

;; ---------------------------------------------------------------- frames

(defrecord Frame [peer ctor site parent node key depth seq-id
                  ^objects vals #?(:clj ^booleans dirty :cljs dirty) ^objects subs ^objects exported ^objects sent
                  children cleanups remote-id alive arg-srcs order watching effects rendered shared
                  ^objects plan queued ^objects deps env ^objects holes seed])

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

#?(:cljs
   (defn decode-ctor
     "A ctor from the browser build's compact table (hypercurve.compiler/
     compact-ctor): JSON data, code taken from fns by index."
     [json fns]
     (letfn [(d [x]
               (cond
                 (string? x) (case (.charAt x 0)
                               ":" (keyword (subs x 1))
                               "'" (symbol (subs x 1))
                               "~" (subs x 1)
                               x)
                 (array? x) (case (when (pos? (alength x)) (aget x 0))
                              "~m" (loop [i 1 m (transient {})]
                                     (if (< i (alength x))
                                       (recur (+ i 2) (assoc! m (d (aget x i)) (d (aget x (inc i)))))
                                       (persistent! m)))
                              "~s" (into #{} (map d) (.slice x 1))
                              "~l" (apply list (map d (.slice x 1)))
                              "~f" (aget fns (aget x 1))
                              "~c" (ctor (d (aget x 1)))
                              (into [] (map d) x))
                 :else x))]
       (d (js/JSON.parse json)))))

;; ---- reactive fns by name: they cross the wire as references, so a ctor
;; held in a value (e.g. loaded lazily from a code-split module) can be
;; mounted with r/call on both peers

(defonce ^:private ctors (atom {}))

(defn register-ctor! [c] (swap! ctors assoc (:name c) c) c)
(defn ctor-by-name [n] (get @ctors n))
(defn ctor? [x] (and (map? x) (contains? x :nodes) (contains? x :dependents)))

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

(defonce ^{:doc "Development tracing (hypercurve.dev): nil when off."} trace (volatile! nil))

(defn- trace-cause! [f i]
  (when-let [t @trace]
    (when-let [c (:current @t)]
      (vswap! t assoc-in [:causes [(:seq-id f) i]] c))))

(defn mark-dirty! [f i]
  (trace-cause! f i)
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

(defn hole-nodes
  "Node ids a template hole reads: [:text p id] [:child p id] [:spread p id]
  [:attr p name id prefix] [:event p type id] [:foreign p mount-id props-id]."
  [[kind _ a b]]
  (case kind
    (:attr :event) [b]
    :foreign [a b]
    [a]))

(defn hole-node [hole] (first (hole-nodes hole)))

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

(defn- trace-change! [t ^Frame f i ^ints deps]
  (let [k [(.-seq-id f) i]]
    (vswap! t (fn [s]
                (-> s
                    (update :log (fn [l] (let [l (conj l {:cell k :ctor (:name (.-ctor f))
                                                          :value (aget ^objects (.-vals f) i)})]
                                           (if (> (count l) 1000) (subvec l (- (count l) 1000)) l))))
                    (update :causes (fn [c] (reduce #(assoc %1 [(.-seq-id f) %2] k) c deps)))
                    (assoc :current k))))))

(defn- changed!
  "Cell (f, i) took a new value: wake local readers and remote ones."
  [^Frame f i]
  (let [i (int i)
        ^ints deps (aget ^objects (.-deps f) i)
        n (alength deps)
        t @trace]
    (when t (trace-change! t f i deps))
    (when (pos? n)
      (let [dirty (.-dirty f)]
        (dotimes [k n] (aset #?(:clj ^booleans dirty :cljs dirty) (aget deps k) true)))
      ;; while a frame is processed its queued flag stays set: it reaches its
      ;; higher-numbered dependents in this same pass (topological order)
      (schedule-frame! f))
    (when-let [s (aget ^objects (.-subs f) i)]
      (doseq [cb @s] (cb)))
    (when (aget ^objects (.-exported f) i)
      (queue-send! f i))
    (when t (vswap! t dissoc :current))))

(defn- same-value? [a b]
  (or (identical? a b)
      (and (not (fn? a)) (not (fn? b)) (= a b))))

(defn- report!
  "A rendered hole changed state: tell the nearest error boundary (failures)
  and suspense boundary (pending count). Client only; atoms in the env."
  [^Frame f old v]
  (let [env (.-env f)]
    (when (and (failure? v) (not (failure? old)))
      (when-let [[bf bi] (get env ::boundary)]
        (reset! (value bf bi) (:error v))))
    (when-let [[sf si] (get env ::suspense)]
      (let [d (cond (and (pending? v) (not (pending? old))) 1
                    (and (pending? old) (not (pending? v))) -1
                    :else 0)]
        (when-not (zero? d) (swap! (value sf si) + d))))))

(defn- track! [^Frame f i old v]
  (when (and (< i (alength ^objects (.-holes f)))
             (aget ^objects (.-holes f) i)
             (or (failure? v) (pending? v) (pending? old)))
    (report! f old v)))

(defn set-cell!
  "Set a cell's value, propagating if it changed."
  [f i v]
  (let [old (value f i)]
    (when (and @(:alive f) (not (same-value? old v)))
      (aset ^objects (:vals f) i v)
      (track! f i old v)
      (changed! f i))))

(defn- set-live!
  "set-cell! for steps, which only run on live frames."
  [^Frame f i v]
  (let [^objects vs (.-vals f)
        old (aget vs i)]
    (when-not (same-value? old v)
      (aset vs i v)
      (track! f i old v)
      (changed! f i))))

;; ---------------------------------------------------------------- mounting

(declare mount-frame! unmount-frame! bind-child! unbind! apply-val! plan-for deps-of revive resume-wire! note-delta! slot-unbind! remote-proxy)

(def ^:private no-holes (object-array 0))

(defn- frame-env
  "A child inherits its parent's env; a link node's :bind adds entries
  pointing at the parent's cells (r/binding, boundaries, suspense)."
  [parent node]
  (if (nil? parent)
    {}
    (let [env (.-env ^Frame parent)
          bind (:bind (nth (:nodes (:ctor parent)) node))]
      (if bind
        (reduce-kv (fn [m k id] (assoc m k [parent id])) env bind)
        env))))

(defn- rendered? [peer ctor parent node]
  (if parent
    (boolean (and @(:rendered parent) (child-hole? (:ctor parent) node)))
    (boolean (:render-root? peer))))

(defn- hole-flags
  "Which nodes are rendered holes, when someone (boundary, suspense) on the
  client needs to hear about their state."
  [peer ctor rendered env]
  (if (and rendered (= :client (:site peer))
           (or (contains? env ::boundary) (contains? env ::suspense)))
    (let [hs (object-array (count (:nodes ctor)))]
      (doseq [hole (:holes (:render ctor))]
        (when (not= :child (first hole)) (doseq [i (hole-nodes hole)] (aset hs i true))))
      hs)
    no-holes))

(defn- new-frame [peer ctor site parent node key]
  (let [n (count (:nodes ctor))
        r (rendered? peer ctor parent node)
        env (frame-env parent node)]
    (->Frame peer ctor site parent node key
             (if parent (inc (:depth parent)) 0)
             (vswap! (:seq peer) inc)
             (object-array n) #?(:clj (boolean-array n) :cljs (object-array n)) (object-array n) (object-array n) (object-array n)
             (volatile! {}) (volatile! []) (volatile! nil) (volatile! true)
             (volatile! {}) (volatile! {}) (volatile! {}) (volatile! {})
             (volatile! r) (volatile! {})
             (plan-for peer ctor site) (volatile! false)
             (deps-of peer ctor)
             env
             (hole-flags peer ctor r env)
             (volatile! (if parent
                          (get-in @(:seed parent) [:kids [(:sid (nth (:nodes (:ctor parent)) node)) key]])
                          @(:root-seed peer))))))

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
    ;; dynamic vars bound by an enclosing r/binding behave like args
    (dotimes [i (count nodes)]
      (let [nd (nth nodes i)]
        (when (= :dyn (:op nd))
          (when-let [[pf pi] (get (:env f) (:var nd))]
            (aset ^objects (:vals f) i (value pf pi))
            (vswap! (:arg-srcs f) assoc i [pf pi])
            (on-cleanup! f (subscribe! pf pi #(mark-dirty! f i)))))))
    (on-cleanup! f #(doseq [g (vals @(:effects f))] (g)))
    (on-cleanup! f #(doseq [{:keys [release]} (vals @(:shared f))] (release)))
    (vswap! (:frames peer) assoc (:seq-id f) f)
    ;; everything computable starts dirty; constants are set directly; local
    ;; state carried over by a hot reload is seeded by stable id
    (let [seed @(:seed f)
          state (:state seed)
          resumed (:vals seed)]
      (dotimes [i (count nodes)]
        (let [nd (nth nodes i)
              sid (:sid nd)]
          (case (:op nd)
            :arg nil
            :const (aset ^objects (:vals f) i (:v nd))
            :call (if (and (:remote-fn nd) (= (node-site f nd) (other-site (:site peer))))
                    ;; callable at once; calls wait for the frame on the other side
                    (aset ^objects (:vals f) i (remote-proxy peer f i))
                    (cond
                      (and state (contains? state sid))
                      (aset ^objects (:vals f) i (revive peer f i (get state sid)))
                      (and resumed (contains? resumed sid)
                           (or (= (node-site f nd) (other-site (:site peer)))
                               (not (contains? #{:branch :for :mount :watch :effect :shared :dyn} (:op nd)))))
                      (aset ^objects (:vals f) i (revive peer f i (get resumed sid)))
                      :else
                      (aset #?(:clj ^booleans (:dirty f) :cljs (:dirty f)) i true)))
            (cond
              (and state (contains? state sid))
              (aset ^objects (:vals f) i (revive peer f i (get state sid)))
              ;; resuming a server render: take the value, unless the node
              ;; must run to (re)build children, subscriptions or effects
              (and resumed (contains? resumed sid)
                   (or (= (node-site f nd) (other-site (:site peer))) ; computed by the other peer
                       (not (contains? #{:branch :for :mount :watch :effect :shared :dyn} (:op nd)))))
              (aset ^objects (:vals f) i (revive peer f i (get resumed sid)))
              :else
              (aset #?(:clj ^booleans (:dirty f) :cljs (:dirty f)) i true)))))
      (when resumed (resume-wire! peer f seed)))
    (export! f)
    (when-let [hook (:on-mount peer)] (hook f))
    (when parent (bind-child! peer f))
    ;; holes start pending: count them toward an enclosing suspense, and
    ;; give them back when the frame goes away
    (let [^objects hs (:holes f)]
      (when (pos? (alength hs))
        (let [pending-holes (fn [] (count (filter #(and (aget hs %) (pending? (value f %))) (range (alength hs)))))]
          (when-let [[sf si] (get (:env f) ::suspense)]
            (swap! (value sf si) + (pending-holes))
            (on-cleanup! f #(swap! (value sf si) - (pending-holes)))))))
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
      (or (= op :arg) (= op :dyn))
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

(declare server-free?)

(defn- server-skipped?
  "Client: does the server leave this frame out (skip-child?)? Then nothing
  in it is sent to the server: its :server readers were only the server's
  mirror of branches and loops, which the server does not build."
  [f]
  (let [peer (:peer f) parent (:parent f)]
    (boolean
      (and (= :client (:site peer)) parent
           (or (server-skipped? parent)
               (and (not-any? #(= :server (resolve-site parent %)) (:readers (node-at parent (:node f))))
                    (server-free? peer (:ctor f) (:site f))))))))

(defn- export! [f]
  (let [me (:site (:peer f))
        other (other-site me)]
    (when-not (server-skipped? f)
      (doseq [[i nd] (map-indexed vector (:nodes (:ctor f)))]
        (when (some #(= other (resolve-site f %)) (:readers nd))
          (remote-read! f i))))
    ;; a rendered frame's template holes are read by the client
    (when (and @(:rendered f) (= me :server))
      (doseq [hole (:holes (:render (:ctor f)))]
        (when (not= :child (first hole))
          (doseq [i (hole-nodes hole)] (remote-read! f i)))))))

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

(defn- link-used?
  "Is the value of link node i read by anyone? A loop or branch that is only
  rendered never needs its children's return values."
  [f i]
  (let [nd (node-at f i)]
    (boolean (or (seq (:readers nd)) (:captured nd) (= i (:ret (:ctor f))) (aget ^objects (:exported f) i)))))

(defn- link-child!
  "Make node i of f follow child c's return value, when that value is used."
  [f i c]
  (when-let [r (:ret (:ctor c))]
    (when (link-used? f i)
      (on-cleanup! c (subscribe! c r #(mark-dirty! f i))))
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
              ;; readers are not checked: in a ctor with no server node, a
              ;; :server reader can only be a branch, loop or mount mirroring
              ;; its input, and those children are checked below. Whether the
              ;; server reads the ctor's own result is the caller's question
              ;; (skip-child? looks at the link node's readers).
              r (every? (fn [nd]
                          (and (not= :server (rs (:site nd)))
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

(defn- count! [peer k n]
  (vswap! (:counters peer) update k (fnil + 0) n))

(defn counters
  "Work counters of a peer (tests and benchmarks): {:for-visits n ...}."
  [peer] @(:counters peer))

(defn- coll-delta
  "The delta that turned the previous collection of keyed loop i into coll,
  when it is known (received or read from a shared atom in this run)."
  [f i nd coll]
  (let [j (first (:in nd))
        [sf si] (or (get @(:arg-srcs f) j) [f j])
        [prev v d] (get @(:deltas (:peer f)) [(:seq-id sf) si])]
    (when (and d (identical? v coll) (identical? prev (get @(:order f) [::coll i])))
      d)))

(defn- keyed-for
  "r/for ... :keyed true over a map. With a known delta only the touched
  entries are visited: removed ones unmount, changed ones get their new
  value, new ones mount at the end."
  [f i nd coll]
  (let [coll (if (map? coll) coll (into {} (map (juxt (or (:key nd) :id) identity)) coll))
        prev-ks (get @(:order f) i)
        cur (or (children-of f i) {})
        captures (arg-srcs f (:args nd))
        mount! (fn [k] (mount-child! f i (:ctor nd) k (into [[:value (get coll k)]] captures)))
        update! (fn [c k] (set-cell! c (arg-id (:ctor c) 0) (get coll k)))
        d (when prev-ks (coll-delta f i nd coll))
        [kids ks]
        (if d
          (let [{:keys [set dissoc patch]} (second d)
                kids (reduce (fn [m k] (if-let [c (get m k)] (do (unmount-frame! c) (clojure.core/dissoc m k)) m))
                             cur dissoc)
                ks (if (seq dissoc) (filterv #(not (contains? dissoc %)) prev-ks) prev-ks)
                touched (concat (keys set) (keys patch))]
            (count! (:peer f) :for-visits (+ (count dissoc) (count touched)))
            (reduce (fn [[m ks] k]
                      (if-let [c (get m k)]
                        (do (update! c k) [m ks])
                        [(assoc m k (mount! k)) (conj ks k)]))
                    [kids ks] touched))
          (let [ks (vec (keys coll))]
            (count! (:peer f) :for-visits (+ (count cur) (count ks)))
            (doseq [[k c] cur] (when-not (contains? coll k) (unmount-frame! c)))
            [(reduce (fn [m k] (assoc m k (if-let [c (get cur k)] (do (update! c k) c) (mount! k)))) {} ks)
             ks]))]
    (vswap! (:children f) assoc i kids)
    (vswap! (:order f) assoc i ks [::coll i] coll [::prev i] prev-ks)
    (when (and (not (identical? ks prev-ks)) (not= ks prev-ks))
      (when-let [h (:on-children (:peer f))] (h f i)))
    (when (link-used? f i)
      (let [rets (mapv #(child-ret-value (kids %)) ks)]
        (or (some #(when (or (pending? %) (failure? %)) %) rets) rets)))))

(defn- compute-for [f i nd]
  (let [coll (value f (first (:in nd)))]
    (cond
      (or (pending? coll) (failure? coll))
      coll

      (skip-child? f i (:ctor nd))
      (do (doseq [[pf pi] (arg-srcs f (:args nd))] (remote-read! pf pi))
          pending)

      ;; woken by a child's return value, not by the collection: no
      ;; reconciliation needed
      (identical? coll (get @(:order f) [::coll i]))
      (let [kids (children-of f i)
            rets (mapv #(child-ret-value (kids %)) (get @(:order f) i))]
        (or (some #(when (or (pending? %) (failure? %)) %) rets) rets))

      (:keyed nd)
      (keyed-for f i nd coll)

      :else
      (do (count! (:peer f) :for-visits (count coll))
      (let [kf (or (:key nd) identity)
            items (vec coll)
            prev-ks (get @(:order f) i)
            cur (or (children-of f i) {})
            ;; keys made unique per occurrence
            ks (if (:recycle nd)
                 ;; keyed by position: scrolling reuses the same child frames
                 (vec (range (count items)))
                 (let [seen (volatile! {})]
                   (mapv (fn [x] (let [k (kf x) n (get @seen k 0)]
                                   (vswap! seen assoc k (inc n))
                                   (if (zero? n) k [::dup k n])))
                         items)))
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
          (vswap! (:order f) assoc i ks [::coll i] coll [::prev i] prev-ks)
          (when (not= ks prev-ks)
            (when-let [h (:on-children (:peer f))] (h f i)))
          (when (link-used? f i)
            (let [rets (mapv #(child-ret-value (kids %)) ks)]
              (or (some #(when (or (pending? %) (failure? %)) %) rets) rets)))))))))

(defn- follow-shareable
  "Server: r/watch on a Shareable. Holds one handle per cell (released when
  the reference changes or the frame goes) and records the version read, so
  the send path ships the shared pre-encoded delta."
  [f i r]
  (when-let [[_ unwatch] (get @(:watching f) i)]
    (unwatch) (vswap! (:watching f) dissoc i))
  (let [cur (get @(:shared f) i)]
    (when (and cur (not (identical? r (:ident cur))))
      ((:release cur))
      (vswap! (:shared f) dissoc i)
      ;; versions belong to an instance: start the new one from scratch
      (aset ^objects (:sent f) i nil))
    (when-not (get @(:shared f) i)
      (let [peer (:peer f)
            h (-share! r (fn [] ((:post! peer) #(mark-dirty! f i))))
            release (:release h)
            k [(:seq-id f) i]]
        (vswap! (:shared f) assoc i
                (assoc h :ident r
                         :release (fn []
                                    (when-let [slot (get-in @(:shared f) [i :slot])]
                                      (slot-unbind! peer slot k))
                                    (release))))))
    (let [h (get @(:shared f) i)
          from (:version h)
          prev (value f i)
          [v version] ((:current h))]
      (when (and from (:delta h) (= version (inc from)))
        (when-let [d ((:delta h) from version)]
          (note-delta! (:peer f) f i prev v d)))
      (vswap! (:shared f) assoc-in [i :version] version)
      v)))

(defn- compute-watch [f i nd]
  (let [r (value f (first (:in nd)))]
    (cond
      (or (pending? r) (failure? r))
      r

      (satisfies? Shareable r)
      (follow-shareable f i r)

      :else
      (do
        (when-let [cur (get @(:shared f) i)]
          ((:release cur)) (vswap! (:shared f) dissoc i))
        (when-let [[old-ref unwatch] (get @(:watching f) i)]
          (when-not (identical? old-ref r) (unwatch)))
        (when-not (identical? r (first (get @(:watching f) i)))
          (let [k (gensym "hypercurve-watch")]
            (add-watch r k (fn [_ _ _ _]
                             ((:post! (:peer f))
                              #(do (when-let [t @trace] (vswap! t assoc :current [:watch (str r)]))
                                   (mark-dirty! f i)
                                   (when-let [t @trace] (vswap! t dissoc :current))))))
            (let [unwatch #(remove-watch r k)]
              (vswap! (:watching f) assoc i [r unwatch])
              (on-cleanup! f unwatch))))
        @r))))

(defn- compute-effect [f i nd]
  (let [args (inputs f (:in nd))]
    (when-let [g (get @(:effects f) i)] (g) (vswap! (:effects f) dissoc i))
    (if (or (pending? args) (failure? args))
      args
      (let [cleanup (guarded (apply (:f nd) args))]
        (cond
          (failure? cleanup) cleanup
          (fn? cleanup) (do (vswap! (:effects f) assoc i cleanup) nil)
          :else nil)))))

(defn- compute-shared
  "Server: follow a process-wide shared value (hypercurve.shared), keyed by the
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
          (vswap! (:shared f) dissoc i)
          (aset ^objects (:sent f) i nil))
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


(defn- set-identical!
  "Memo cost model: a node that builds a fresh collection every time is
  compared by identity only; a deep = would cost more than it saves."
  [^Frame f i v]
  (let [^objects vs (.-vals f)
        old (aget vs i)]
    (when-not (identical? old v)
      (aset vs i v)
      (track! f i old v)
      (changed! f i))))

(defn- call-step [nd]
  (let [g (:f nd) in (:in nd)
        set-live! (if (= :identical (:eq nd)) set-identical! set-live!)]
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
      :dyn (let [dflt (:default nd)]
             (fn [f i] (if-let [[pf pi] (get @(:arg-srcs f) i)]
                         (set-cell! f i (value pf pi))
                         (set-cell! f i (if dflt (guarded (dflt)) nil)))))
      :call (when (and mine? (not (:dead nd))) (call-step nd))
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
      (recur)))
  (when (seq @(:deltas peer)) (vreset! (:deltas peer) {})))

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
                             (when-not @(:silent peer)
                               (vswap! (:outbox peer) update :drop (fnil conj []) id))))
            id)))))

(defn- queue-send! [f i]
  (let [peer (:peer f) k [(:seq-id f) i]]
    (when (:debounce (node-at f i))
      (vswap! (:last-change peer) assoc k (clock/now (:clock peer))))
    (vswap! (:dirty-out peer) assoc k [f i])))

(defn- encode-value [prev v]
  (cond
    (pending? v) (when-not (pending? prev) [:p])
    (failure? v) [:e (let [e (:error v)]
                       #?(:clj (if (instance? Throwable e) (or (ex-message e) (str e)) (str e))
                          :cljs (if (instance? js/Error e) (.-message e) (str e))))]
    (fn? v) (when-not (fn? prev) [:f])
    (ctor? v) [:v {::ctor (:name v)}]
    (or (pending? prev) (failure? prev) (fn? prev) (= prev ::unsent)) [:v v]
    :else (delta/diff prev v)))

;; ---- client cache slots (milestone M34)
;; The client keeps the last value of up to :cache-slots shared atoms it no
;; longer watches (plus those it does), in numbered slots. The server mirrors
;; that cache exactly: it decides every slot assignment and eviction and
;; tracks the version each slot holds, from what it sent (the wire is ordered
;; and reliable, so the mirror cannot drift). Watching an atom again then
;; costs a delta from the cached version, or nothing at all.

(defn- slot-bind!
  "Bind cell k to the slot of epoch (an atom instance); returns [slot
  cached-version-or-nil]."
  [peer epoch k]
  (let [st @(:slots peer)
        tick (inc (:tick st 0))]
    (if-let [s (get-in st [:of-epoch epoch])]
      (do (vreset! (:slots peer)
                   (-> st (assoc :tick tick)
                       (update-in [:entries s] #(-> % (update :bound conj k) (assoc :used tick)))))
          [s (get-in st [:entries s :version])])
      (let [[s st] (if-let [s (peek (:free st))] [s (update st :free pop)] [(:next st 0) (update st :next (fnil inc 0))])]
        (vreset! (:slots peer)
                 (-> st (assoc :tick tick)
                     (assoc-in [:of-epoch epoch] s)
                     (assoc-in [:entries s] {:epoch epoch :version nil :bound #{k} :used tick})))
        [s nil]))))

(defn- slot-sent! [peer s version]
  (vswap! (:slots peer) (fn [st] (if (get-in st [:entries s]) (assoc-in st [:entries s :version] version) st))))

(defn- slot-evict! [st s]
  (-> st
      (update :of-epoch dissoc (get-in st [:entries s :epoch]))
      (update :entries dissoc s)
      (update :free (fnil conj []) s)))

(defn- slot-unbind!
  "Cell k stops following slot s; keep at most :cache-slots unwatched slots,
  evicting the least recently used."
  [peer s k]
  (vswap! (:slots peer)
          (fn [st]
            (let [st (update-in st [:entries s :bound] disj k)
                  idle (sort-by #(get-in st [:entries % :used])
                                (for [[s e] (:entries st) :when (empty? (:bound e))] s))
                  over (- (count idle) (:cache-slots peer 8))]
              (reduce slot-evict! st (take (max 0 over) idle))))))

(defn- send-shared!
  "Shared cells remember only the version last sent (a cursor) and send the
  pre-encoded delta from that version, which every session shares. The
  first send of a cell following a SharedAtom goes through a cache slot:
  only the delta from what the client already holds."
  [f i {:keys [version blob epoch slot]}]
  (let [^objects sent (:sent f)
        s (aget sent i)
        peer (:peer f)
        cursor (when (and (vector? s) (= ::version (first s))) (second s))]
    (when-not (= cursor version)
      (if (and (nil? cursor) epoch (pos? (:cache-slots peer 8)))
        (let [[slot base] (slot-bind! peer epoch [(:seq-id f) i])
              ;; nil when the client's copy is current: send nothing but the slot
              bs (when-not (= base version) (blob base version))
              id (out-id f)]
          (vswap! (:shared f) assoc-in [i :slot] slot)
          (vswap! (:outbox peer) update :vals (fnil conj []) [id i [:cache [slot bs]]])
          (slot-sent! peer slot version))
        (do
          (when-let [bs (blob cursor version)]
            ;; out-id may append a frame declaration to the outbox: call it first
            (let [id (out-id f)]
              (vswap! (:outbox peer) update :vals (fnil conj []) [id i [:raw bs]])))
          (when slot (slot-sent! peer slot version))))
      (aset sent i [::version version]))))

(defn- resync!
  "The client could not use a cache slot for (frame id, i): forget the slot
  and send the full value."
  [peer [id i]]
  (when-let [f (get @(:out-frames peer) id)]
    (when-let [slot (get-in @(:shared f) [i :slot])]
      (vswap! (:slots peer) slot-evict! slot)
      (vswap! (:shared f) update i dissoc :slot))
    (aset ^objects (:sent f) i nil)
    (queue-send! f i)))

(defn- rate-deferred?
  "A cell with a :rate hint is sent at most rate times a second; a change
  that comes too soon waits (and is coalesced with later ones)."
  [peer f i]
  (when-let [rate (:rate (node-at f i))]
    (let [k [(:seq-id f) i]
          now (clock/now (:clock peer))
          interval (/ 1000 rate)
          last (get @(:last-sent peer) k)]
      (if (and last (< (- now last) interval))
        (do (when-not (contains? @(:rate-wakeups peer) k)
              (vswap! (:rate-wakeups peer) conj k)
              (clock/schedule! (:clock peer) (- interval (- now last))
                               (fn [] ((:post! peer)
                                       (fn [] (vswap! (:rate-wakeups peer) disj k)
                                         (when-let [h (:on-schedule peer)] (h)))))))
            true)
        (do (vswap! (:last-sent peer) assoc k now) false)))))

(defn- wake-later! [peer k delay-ms]
  (when-not (contains? @(:rate-wakeups peer) k)
    (vswap! (:rate-wakeups peer) conj k)
    (clock/schedule! (:clock peer) delay-ms
                     (fn [] ((:post! peer)
                             (fn [] (vswap! (:rate-wakeups peer) disj k)
                               (when-let [h (:on-schedule peer)] (h))))))))

(defn- debounce-deferred?
  "A cell with a :debounce hint is sent only once it has stopped changing
  for that many ms; each change restarts the wait."
  [peer f i]
  (when-let [ms (:debounce (node-at f i))]
    (let [k [(:seq-id f) i]
          quiet (- (clock/now (:clock peer)) (get @(:last-change peer) k 0))]
      (when (< quiet ms)
        ;; a wakeup already pending may be early; it re-checks and re-arms
        (wake-later! peer k (- ms quiet))
        true))))

(defn- collect-out! [peer]
  (let [later (volatile! (sorted-map))]
  (doseq [[f i :as e] (vals @(:dirty-out peer))]
    (when (and @(:alive f)
               (if (or (debounce-deferred? peer f i) (rate-deferred? peer f i))
                 (do (vswap! later assoc [(:seq-id f) i] e) false)
                 true))
      (if-let [sh (let [sh (get @(:shared f) i) v (value f i)]
                    (when (and sh (not (pending? v)) (not (failure? v))) sh))]
        (send-shared! f i sh)
      (let [^objects sent (:sent f)
            prev (let [s (aget sent i)] (cond (nil? s) ::unsent (= s ::nil) nil :else s))
            v (value f i)
            d (encode-value prev v)
            ;; cross-site stack: say where on this side the error happened
            d (if (and d (= :e (first d)))
                [:e (str (second d) "\u0000" (name (:site peer)) " " (:name (:ctor f))
                         (when-let [l (:line (node-at f i))] (str ":" l)))]
                d)]
        (when d
          (aset sent i (if (nil? v) ::nil v))
          (let [id (out-id f)]
            (vswap! (:outbox peer) update :vals (fnil conj []) [id i d])))))))
  (vreset! (:dirty-out peer) @later)))

(defn has-pending-output? [peer]
  (boolean (or (seq @(:dirty-out peer)) (seq @(:outbox peer)) (pos? @(:to-ack peer)))))

(defn take-message!
  "Collect everything to send since the last call. nil when there is
  nothing, or when :window messages are already unacknowledged (backpressure:
  changes keep accumulating and are coalesced until acks arrive)."
  [peer]
  (let [w (:window peer)
        blocked? (and w (>= @(:in-flight peer) w))]
    (when-not blocked? (collect-out! peer))
    (let [m (if blocked? {} @(:outbox peer))
          acks @(:to-ack peer)
          m (if (pos? acks) (assoc m :ack acks) m)]
      (when-not blocked? (vreset! (:outbox peer) {}))
      (vreset! (:to-ack peer) 0)
      (when (seq m)
        (when (seq (dissoc m :ack)) (vswap! (:in-flight peer) inc))
        m))))

;; ---- receiving

(defn- frame-by-in-id [peer id] (get @(:in-frames peer) id))

(defn- bind! [peer f id]
  (vswap! (:in-frames peer) assoc id f)
  (vreset! (:remote-id f) id)
  (when-let [calls (get @(:deferred-calls peer) (:seq-id f))]
    (vswap! (:deferred-calls peer) dissoc (:seq-id f))
    (doseq [[token i args] calls]
      (vswap! (:outbox peer) update :call (fnil conj []) [token id i args]))
    (when-let [hook (:on-schedule peer)] (hook)))
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
          result (atom pending)]
      (vswap! (:calls peer) assoc token result)
      (if-let [id @(:remote-id f)]
        (vswap! (:outbox peer) update :call (fnil conj []) [token id i (vec args)])
        ;; the other peer has not mounted this frame yet: send once it has
        (vswap! (:deferred-calls peer) update (:seq-id f) (fnil conj []) [token i (vec args)]))
      (when-let [hook (:on-schedule peer)] (hook))
      result))
   {::remote true}))

(defn- authorized-val? [peer f i]
  ;; only accept values for nodes the sender owns
  (let [nd (node-at f i)]
    (and (= (node-site f nd) (other-site (:site peer)))
         (not (link-op? (:op nd))))))

(defn- note-delta!
  "Remember how cell (f, i) went from prev to v, for a keyed r/for reading
  it in this same run. Forgotten when the run ends."
  [peer f i prev v d]
  (when (and (= :m (first d)) (map? v))
    (vswap! (:deltas peer) assoc [(:seq-id f) i] [prev v d]))
  v)

(defn- apply-val! [peer f i d]
  (when (authorized-val? peer f i)
   (let [validate (when (= :server (:site peer)) (:validate (node-at f i)))]
    (let [[t x] d
          prev (value f i)
          v (case t
              :p pending
              ;; message, then where it happened on the other side (ex-data :at)
              :e (let [[msg at] (clojure.string/split x #"\u0000" 2)]
                   (failure (ex-info msg (cond-> {:remote true} at (assoc :at at)))))
              :f (remote-proxy peer f i)
              :v (if (and (map? x) (contains? x ::ctor)) (ctor-by-name (::ctor x)) x)
              :cache (let [[slot bs] x
                           d' (if (or (nil? bs) (vector? bs)) bs (codec/decode-delta-blob bs))
                           base (get @(:slot-vals peer) slot ::none)
                           k [(:seq-id f) i]]
                       (vswap! (:cell-slot peer) assoc k slot)
                       (when-not (contains? @(:slot-cleanups peer) k)
                         (vswap! (:slot-cleanups peer) conj k)
                         (on-cleanup! f #(do (vswap! (:cell-slot peer) dissoc k)
                                             (vswap! (:slot-cleanups peer) disj k))))
                       (cond
                         (and d' (= :v (first d'))) (second d')
                         (= base ::none) (do (vswap! (:outbox peer) update :resync (fnil conj []) [@(:remote-id f) i])
                                             ::resync)
                         (nil? d') base
                         :else (delta/patch base d')))
              :raw (let [d' (codec/decode-delta-blob x)]
                     (if (= :v (first d')) (second d') (note-delta! peer f i prev (delta/patch prev d') d')))
              (note-delta! peer f i prev (delta/patch prev d) d))]
      (when-let [slot (and (not= v ::resync) (get @(:cell-slot peer) [(:seq-id f) i]))]
        (vswap! (:slot-vals peer) assoc slot v))
      ;; boundary schema: the server validates what the client sends
      (when (and (not= v ::resync) (or (nil? validate) (pending? v) (validate v)))
        (when-let [t @trace] (vswap! t assoc-in [:causes [(:seq-id f) i]] [:remote (other-site (:site peer))]))
        (set-cell! f i v))))))

(defn- invoke-call! [peer [token id i args]]
  ;; the caller names the frame by the id *we* declared for it
  (let [f (get @(:out-frames peer) id)
        reply (fn [ok v] (vswap! (:outbox peer) update :ret (fnil conj []) [token ok v]))]
    (cond
      (nil? f) (reply false "frame not mounted")
      (not= (node-site f (node-at f i)) (:site peer)) (reply false "not callable")
      :else
      (let [g (value f i)
            valid? (or (nil? (:validate (node-at f i))) (apply (:validate (node-at f i)) args))]
        (cond
          (not (fn? g)) (reply false "not callable")
          (not valid?) (reply false "invalid arguments")
          :else
          (try (let [r (apply g args)]
                 (reply true (if (fn? r) nil r)))
               (catch #?(:clj Throwable :cljs :default) e
                 (reply false (or (ex-message e) (str e))))))))))

(defn receive!
  "Apply a message from the other peer."
  [peer {:keys [decl vals drop call ret ack resync] :as msg}]
  ;; every message with content is acknowledged (piggybacked on the next one)
  (when (seq (dissoc msg :ack)) (vswap! (:to-ack peer) inc))
  (when ack
    (vswap! (:in-flight peer) #(max 0 (- % ack)))
    (when (and (seq @(:dirty-out peer)) (:on-schedule peer)) ((:on-schedule peer))))
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
  (when (= :server (:site peer)) (doseq [r resync] (resync! peer r)))
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
                  :to-ack (volatile! 0)
                  :root-seed (volatile! nil)
                  :silent (volatile! false)
                  :in-flight (volatile! 0)
                  :window nil
                  :clock clock/host
                  :last-sent (volatile! {})
                  :rate-wakeups (volatile! #{})
                  :last-change (volatile! {})
                  :deltas (volatile! {})
                  :deferred-calls (volatile! {})
                  :slots (volatile! {})
                  :slot-vals (volatile! {})
                  :cell-slot (volatile! {})
                  :slot-cleanups (volatile! #{})
                  :counters (volatile! {})
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
                  :shared-acquire #?(:clj (fn [& args] (apply (requiring-resolve 'hypercurve.shared/acquire!) args))
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

;; ---- resuming a server render (design §7.3, §8.3)
;; A server render runs a headless client peer next to a real session. Its
;; state (values, wire ids, codec tables) is exported here and restored in the
;; browser, which then continues the same session: no query runs twice.

(defn- portable
  "v as snapshot data, or ::skip when it cannot travel (it will be recomputed)."
  [v]
  (cond
    (or (pending? v) (failure? v)) ::skip
    (remote-fn? v) {::proxy true}
    (fn? v) ::skip
    (ctor? v) {::ctor (:name v)}
    (instance? #?(:clj clojure.lang.Atom :cljs cljs.core/Atom) v)
    (let [x (portable @v)] (if (= x ::skip) ::skip {::atom x}))
    :else (if (try (codec/encode-delta-blob [:v v]) true (catch #?(:clj Exception :cljs :default) _ false))
            v
            ::skip)))

(defn- revive [peer f i x]
  (cond
    (and (map? x) (contains? x ::proxy)) (remote-proxy peer f i)
    (and (map? x) (contains? x ::ctor)) (ctor-by-name (::ctor x))
    (and (map? x) (contains? x ::atom)) (atom (::atom x))
    :else x))

(defn- frame-resume-snapshot [f]
  (let [peer (:peer f)
        nodes (:nodes (:ctor f))
        portable-map (fn [arr]
                       (into {} (keep (fn [i] (let [nd (nth nodes i)
                                                    v (aget ^objects arr i)
                                                    ;; an atom travels only when this node created it;
                                                    ;; a reference to a shared atom is looked up again
                                                    x (if (and (instance? #?(:clj clojure.lang.Atom :cljs cljs.core/Atom) v)
                                                               (not (:state nd)))
                                                        ::skip
                                                        (portable v))]
                                                (when-not (= x ::skip) [(:sid nd) x])))
                                      (range (count nodes)))))]
    {:vals (portable-map (:vals f))
     :sent (into {} (keep (fn [i] (let [s (aget ^objects (:sent f) i)]
                                    (when (some? s)
                                      (let [x (if (= s ::nil) nil (portable s))]
                                        (when-not (= x ::skip) [(:sid (nth nodes i)) x])))))
                          (range (count nodes))))
     :remote-id @(:remote-id f)
     :out-id (get @(:out-ids peer) (:seq-id f))
     :kids (into {} (for [[i kids] @(:children f)
                          c (kids-seq kids)]
                      [[(:sid (nth nodes i)) (:key c)] (frame-resume-snapshot c)]))}))

(defn resume-snapshot
  "Everything a browser needs to continue this (client) peer's session."
  [peer]
  (let [root (first (filter #(nil? (:parent %)) (frames peer)))]
    {:root (frame-resume-snapshot root)
     :next-wire-id @(:next-wire-id peer)
     :decls @(:decls peer)}))

(defn- resume-wire!
  "Re-establish a resumed frame's wire identity on both directions."
  [peer f {:keys [remote-id out-id sent]}]
  (let [nodes (:nodes (:ctor f))
        by-sid (into {} (map-indexed (fn [i nd] [(:sid nd) i]) nodes))]
    (doseq [[sid x] sent]
      (when-let [i (by-sid sid)]
        (aset ^objects (:sent f) i (if (nil? x) ::nil (revive peer f i x)))))
    (when (and remote-id (:parent f)) (bind! peer f remote-id))
    (when (and out-id (:parent f))
      (vswap! (:out-ids peer) assoc (:seq-id f) out-id)
      (vswap! (:out-frames peer) assoc out-id f)
      (on-cleanup! f (fn []
                       (vswap! (:out-ids peer) dissoc (:seq-id f))
                       (vswap! (:out-frames peer) dissoc out-id)
                       (when-not @(:silent peer)
                         (vswap! (:outbox peer) update :drop (fnil conj []) out-id)))))))

(defn resume!
  "Restore a peer from resume-snapshot and mount ctor (with args) with it."
  [peer ctor {:keys [root next-wire-id decls]} & args]
  (vreset! (:next-wire-id peer) next-wire-id)
  (vreset! (:decls peer) decls)
  (vreset! (:root-seed peer) root)
  (let [f (apply mount-root! peer ctor args)]
    (vreset! (:root-seed peer) nil)
    f))

(defn unmount-silently!
  "Tear a frame tree down without telling the other peer (hibernation:
  the other side keeps its frames and we come back with the same ids)."
  [peer f]
  (vreset! (:silent peer) true)
  (try (unmount-frame! f) (finally (vreset! (:silent peer) false))))

(defn fnv32
  "FNV-1a over a string: the same number on the JVM and in JS (unlike hash)."
  [^String s]
  #?(:clj (loop [i 0 h 0x811c9dc5]
            (if (< i (.length s))
              (recur (inc i) (bit-and 0xffffffff (* (bit-xor h (long (.charAt s i))) 0x01000193)))
              h))
     :cljs (loop [i 0 h 0x811c9dc5]
             (if (< i (.-length s))
               (recur (inc i) (unsigned-bit-shift-right (js/Math.imul (bit-xor h (.charCodeAt s i)) 0x01000193) 0))
               h))))

(defn tree-structure
  "What tree-version hashes (for diagnosing version mismatches)."
  [ctor]
  (let [seen (volatile! #{})
        acc (volatile! [])]
    ((fn walk [c]
       (when (and c (not (contains? @seen (:name c))))
         (vswap! seen conj (:name c))
         ;; structure, not source text: platform-specific code (#?) differs
         ;; between builds but the protocol is the same
         (vswap! acc conj [(str (:name c)) (:ret c)
                           ;; inputs are left out: platform code (#?) may read different
                           ;; locals on each side without changing what crosses the wire
                           (mapv (fn [nd] [(:op nd) (:site nd) (sort (:readers nd))
                                           (:ctx-site nd) (when (:ctor-fn nd) (str (:name ((:ctor-fn nd)))))])
                                 (:nodes c))
                           (:holes (:render c))])
         (doseq [nd (:nodes c)]
           (doseq [c (:ctors nd)] (walk c))
           (walk (:ctor nd))
           (when-let [t (:ctor-fn nd)] (walk (t))))))
     ctor)
    (sort-by first @acc)))

(defn tree-version
  "Hash of the structure of every program table reachable from ctor: the
  JVM and browser builds of the same app agree, and any change that would
  break the protocol (nodes, edges, sites, holes) changes it."
  [ctor]
  (fnv32 (pr-str (tree-structure ctor))))


;; ---- hot reload

(defn- local-state? [nd] (and (= :call (:op nd)) (:state nd) (:sid nd)))

(defn snapshot
  "Local state of a frame tree by stable ids: the values of input-less call
  nodes (atoms and other per-frame state), recursively through children."
  [f]
  (let [nodes (:nodes (:ctor f))]
    {:state (into {} (keep (fn [i] (let [nd (nth nodes i) v (value f i)]
                                     (when (and (local-state? nd) (not (pending? v)) (not (failure? v)))
                                       [(:sid nd) v])))
                           (range (count nodes))))
     :kids (into {} (for [[i kids] @(:children f)
                          c (kids-seq kids)]
                      [[(:sid (nth nodes i)) (:key c)] (snapshot c)]))}))

(defn- stale? [f]
  (let [cur (ctor-by-name (:name (:ctor f)))]
    (and cur (not (identical? cur (:ctor f))))))

(defn reload!
  "Swap in newly registered versions of reactive fns. Frames running a stale
  version are remounted; local state is carried over by stable id. Returns
  the new root when the root itself was swapped."
  [peer]
  (let [root (first (filter #(nil? (:parent %)) (frames peer)))]
    (if (and root (stale? root))
      (let [seed (snapshot root)
            args (mapv #(value root %) (:arg-ids (:ctor root)))]
        (unmount-frame! root)
        (vreset! (:root-seed peer) seed)
        (let [r (apply mount-root! peer (ctor-by-name (:name (:ctor root))) args)]
          (vreset! (:root-seed peer) nil)
          r))
      (do (doseq [f (frames peer)
                  :when (and @(:alive f) (:parent f) (stale? f))]
            (let [p (:parent f)]
              (vswap! (:seed p) assoc-in [:kids [(:sid (nth (:nodes (:ctor p)) (:node f))) (:key f)]] (snapshot f))
              (mark-dirty! p (:node f))))
          root))))

(defn defer!
  "Call ready! when :when happens: :idle (default) or :interaction (first
  pointer or key event). (:visible is handled by visible-mount.)
  Immediately on the JVM. Returns a cleanup fn."
  [{:keys [when] :or {when :idle}} ready!]
  #?(:clj (do (ready!) nil)
     :cljs (case when
             :interaction (let [f (fn [] (ready!))
                                evs ["pointerdown" "keydown" "touchstart"]]
                            (doseq [e evs] (.addEventListener js/document e f #js {:once true}))
                            #(doseq [e evs] (.removeEventListener js/document e f)))
             (if (exists? js/requestIdleCallback)
               (let [id (js/requestIdleCallback ready!)] #(js/cancelIdleCallback id))
               (let [id (js/setTimeout ready! 1)] #(js/clearTimeout id))))))

(defonce ^{:doc "(fn [el {:keys [margin on-visible]}]) -> stop fn. Replaceable (tests,
  custom scroll roots). Browser: IntersectionObserver; JVM: visible at once
  (server rendering shows the content)."}
  visible-observer
  (atom #?(:clj (fn [_ {:keys [on-visible]}] (on-visible) nil)
           :cljs (fn [el {:keys [on-visible margin]}]
                   (if (exists? js/IntersectionObserver)
                     (let [o (js/IntersectionObserver.
                               (fn [entries obs]
                                 (when (some #(.-isIntersecting %) (array-seq entries))
                                   (.disconnect obs)
                                   (on-visible)))
                               #js {:rootMargin (str (or margin 0) "px")})]
                       (.observe o el)
                       #(.disconnect o))
                     (do (on-visible) nil))))))

(defn visible-mount
  "r/foreign mount fn for (r/defer {:when :visible} ...): watch the
  placeholder element; stop watching when it goes away."
  [el props]
  (let [stop (@visible-observer el props)]
    {:unmount (fn [] (when stop (stop)))}))

(defn offload!
  "Run thunk off the calling thread (a virtual thread on the JVM) and emit
  its result, or a failure. Returns a cancel fn."
  [thunk emit!]
  #?(:clj (let [t (Thread/startVirtualThread
                    (fn [] (let [r (try (thunk) (catch InterruptedException _ ::cancelled)
                                        (catch Throwable e (failure e)))]
                             (when-not (= r ::cancelled) (emit! r)))))]
            (fn [] (.interrupt t)))
     :cljs (do (emit! (failure (js/Error. "r/offload runs on the server"))) (fn []))))
