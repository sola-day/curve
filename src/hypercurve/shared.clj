(ns hypercurve.shared
  "Process-wide shared reactive values (r/shared).

  An instance is identified by [ctor key captures]. It is computed once, on
  a dedicated serial runner, however many sessions follow it. Each instance
  keeps a bounded log of recent versions; sessions remember only the version
  they last sent (a cursor) and point at the same persistent value, so N
  sessions cost one value plus N integers. The delta from version a to b is
  encoded once into a self-contained blob and the same bytes go to every
  session at cursor a."
  (:refer-clojure :exclude [reset-meta!])
  (:require [hypercurve.clock :as clock]
            [hypercurve.codec :as codec]
            [hypercurve.delta :as delta]
            [hypercurve.timing :as timing]
            [hypercurve.runtime :as rt])
  (:import [java.util.concurrent Executors ExecutorService ThreadFactory]))

(def log-size 64)

(defonce ^ExecutorService runner
  (Executors/newSingleThreadExecutor
    (reify ThreadFactory
      (newThread [_ r] (doto (Thread. ^Runnable r "hypercurve-shared") (.setDaemon true))))))

(declare peer)

(defn- run-task! [f]
  (.execute runner (fn []
                     (try (f) (rt/run! peer)
                          (catch Throwable e (.printStackTrace e))))))

(defonce peer (rt/peer :server :render-root? false :post! (fn [g] (run-task! g))))

(defonce instances (atom {}))

(defonce stats (atom {:instances-created 0 :encodes 0}))

(defn- record! [inst v]
  (swap! (:state inst)
         (fn [{:keys [version log] :as s}]
           (let [ver (inc version)]
             (assoc s :value v :version ver
                      :log (let [l (assoc log ver v)]
                             (if (> (count l) log-size) (dissoc l (apply min (keys l))) l))))))
  (reset! (:blobs inst) {})
  (doseq [cb @(:subs inst)] (cb)))

(defn- create [ctor ident caps]
  (let [inst {:ident ident :refs (atom 0) :subs (atom #{}) :frame (atom nil)
              :state (atom {:value rt/pending :version 0 :log {}})
              :blobs (atom {})}]
    (swap! stats update :instances-created inc)
    (run-task!
      (fn []
        (let [f (apply rt/mount-root! peer ctor caps)
              ret (:ret ctor)]
          (reset! (:frame inst) f)
          (when ret
            (rt/subscribe! f ret #(record! inst (rt/value f ret)))))))
    inst))

(defn- retire! [inst]
  (run-task!
    (fn []
      (when (zero? @(:refs inst))
        (swap! instances (fn [m] (if (identical? inst (get m (:ident inst))) (dissoc m (:ident inst)) m)))
        (some-> @(:frame inst) rt/unmount-frame!)))))

(defn- blob [inst from to]
  ;; sessions on different workers ask at the same moment: encode under a
  ;; lock so exactly one of them does the work
  (locking (:blobs inst)
   (let [k [from to]
         cache @(:blobs inst)]
    (if (contains? cache k)
      (get cache k)
      (let [log (:log @(:state inst))
            nv (get log to)
            b (if (and from (contains? log from))
                (when-let [d (delta/diff (get log from) nv)] (codec/encode-delta-blob d))
                (codec/encode-delta-blob [:v nv]))]
        (swap! stats update :encodes inc)
        (swap! (:blobs inst) assoc k b)
        b)))))

(defn acquire!
  "Follow the shared instance for ident. on-change is called (on the shared
  runner thread) whenever its value changes. Returns a handle."
  [ctor ident caps on-change]
  (let [inst (locking instances
               (or (get @instances ident)
                   (let [i (create ctor ident caps)] (swap! instances assoc ident i) i)))]
    (swap! (:refs inst) inc)
    (swap! (:subs inst) conj on-change)
    {:current (fn [] (let [{:keys [value version]} @(:state inst)] [value version]))
     :blob (fn [from to] (blob inst from to))
     :release (fn []
                (swap! (:subs inst) disj on-change)
                (when (zero? (swap! (:refs inst) dec)) (retire! inst)))}))

(defn instance-count [] (count @instances))

(defn await-idle
  "Block until the shared runner has processed everything queued so far."
  []
  @(.submit runner ^Callable (fn [] true)))

;; ---------------------------------------------------------------- shared atoms
;;
;; A SharedAtom is collaborative state: an atom that every session can watch
;; and write. Beyond a plain atom it keeps
;;   - a version log of recent values with the delta and metadata of each
;;     change, so r/watch sends every session the same pre-encoded delta
;;     (one diff, one encode per change, however many sessions)
;;   - write metadata (swap-meta!): who and which operation; an :op-id seen
;;     recently is ignored, so a client may safely retry
;;   - listeners (listen!, or a family's :on-change) that see every change in
;;     version order: persistence, history, audit
;;   - a lifecycle when it belongs to a family (atom-family): loaded on first
;;     use, written back in batches, unloaded once nobody has watched it for
;;     :idle-ms.

(def ^:private seen-limit 4096)

(defonce ^:private flush-pool
  ;; flushes do I/O: keep them off the timer and session threads
  (Executors/newCachedThreadPool
    (reify ThreadFactory
      (newThread [_ r] (doto (Thread. ^Runnable r "hypercurve-flush") (.setDaemon true))))))

(declare commit! share! retire-atom! flush-now!)

(deftype SharedAtom [key family opts ^clojure.lang.Atom a state flush-lock]
  clojure.lang.IDeref
  (deref [_] (:value @state))
  clojure.lang.IRef
  (getValidator [_] nil)
  (setValidator [_ _] (throw (UnsupportedOperationException. "use the :validate option")))
  (getWatches [_] (.getWatches a))
  (addWatch [this k f] (.addWatch a k (fn [k _ o n] (f k this o n))) this)
  (removeWatch [this k] (.removeWatch a k) this)
  clojure.lang.IAtom
  (swap [this f] (commit! this nil f []))
  (swap [this f x] (commit! this nil f [x]))
  (swap [this f x y] (commit! this nil f [x y]))
  (swap [this f x y more] (commit! this nil f (into [x y] more)))
  (compareAndSet [this o n]
    (locking this
      (if (identical? o (:value @state)) (do (commit! this nil (constantly n) []) true) false)))
  (reset [this v] (commit! this nil (constantly v) []))
  clojure.lang.IAtom2
  (swapVals [this f] (let [o @this] [o (commit! this nil f [])]))
  (swapVals [this f x] (let [o @this] [o (commit! this nil f [x])]))
  (swapVals [this f x y] (let [o @this] [o (commit! this nil f [x y])]))
  (swapVals [this f x y more] (let [o @this] [o (commit! this nil f (into [x y] more))]))
  (resetVals [this v] (let [o @this] [o (commit! this nil (constantly v) [])]))
  rt/Shareable
  (-share! [this on-change] (share! this on-change))
  Object
  (toString [_] (str "#shared-atom " (pr-str key))))

(defmethod print-method SharedAtom [^SharedAtom sa ^java.io.Writer w]
  (.write w (str "#shared-atom " (pr-str (.-key sa)))))

(defn- now [opts] (clock/now (:clock opts clock/host)))

(defn- new-shared-atom [key family opts v]
  (SharedAtom. key family opts (clojure.core/atom v)
               (clojure.core/atom {:value v :version 0 :log (sorted-map 0 {:value v})
                                   :refs 0 :seen #{} :seen-q clojure.lang.PersistentQueue/EMPTY
                                   :listeners {} :pending [] :blobs {}})
               (Object.)))

(defn- trim-log [log n]
  (if (> (count log) n) (dissoc log (ffirst log)) log))

(defn- remember-op [st op]
  (if-not op
    st
    (let [q (conj (:seen-q st) op) s (conj (:seen st) op)]
      (if (> (count q) seen-limit)
        (assoc st :seen (disj s (peek q)) :seen-q (pop q))
        (assoc st :seen s :seen-q q)))))

(defn- schedule-idle!
  "Nobody watches: unload after :idle-ms (restarted by every write)."
  [^SharedAtom sa]
  (let [opts (.-opts sa)]
    (when (and (.-family sa) (:idle-ms opts))
      (let [st (.-state sa)]
        (some-> (:idle-timer @st) (apply []))
        (swap! st assoc :idle-timer
               (clock/schedule! (:clock opts clock/host) (:idle-ms opts)
                                (fn [] (retire-atom! sa))))))))

(defn- commit!
  "Apply f to the value under the atom's lock; record the change with meta.
  Returns the new value (the unchanged value for a duplicate :op-id)."
  [^SharedAtom sa meta f args]
  (let [opts (.-opts sa)
        st (.-state sa)
        out (locking sa
              (let [{:keys [value version seen retired]} @st
                    op (:op-id meta)]
                (cond
                  retired ::retired
                  (and op (contains? seen op)) {:value value}
                  :else
                  (let [new (apply f value args)
                        d (delta/diff value new)]
                    (when-let [v (:validate opts)]
                      (when-not (v (.-key sa) value new meta)
                        (throw (ex-info "shared-atom: change rejected" {:key (.-key sa) :meta meta}))))
                    (if (nil? d)
                      (do (swap! st remember-op op) {:value value})
                      (let [ver (inc version)
                            change {:key (.-key sa) :version ver :old value :new new :delta d
                                    :meta meta :at (now opts)}]
                        (swap! st (fn [s]
                                    (-> s
                                        (assoc :value new :version ver :blobs {})
                                        (update :log #(trim-log (assoc % ver {:value new :delta d})
                                                                (:log-size opts log-size)))
                                        (remember-op op)
                                        (cond-> (:flush opts) (update :pending conj change)))))
                        (.reset ^clojure.lang.Atom (.-a sa) new)
                        ;; listeners run under the lock: they see changes in version order
                        (doseq [g (vals (:listeners @st))] (g change))
                        (when-let [g (:on-change opts)] (g change))
                        {:value new :change change}))))))]
    (if (= out ::retired)
      ;; someone kept a reference past unload: write to the live instance
      (commit! ((.-family sa) (.-key sa)) meta f args)
      (do
        (when (:change out)
          (doseq [cb (:subs @st)] (cb))
          (when-let [fl (:flusher @st)] (fl))
          (when (zero? (:refs @st)) (schedule-idle! sa)))
        (:value out)))))

(defn- share! [^SharedAtom sa on-change]
  (let [st (.-state sa)]
    (locking sa
      (when (:retired @st)
        (throw (ex-info "shared-atom was unloaded; look it up again" {:key (.-key sa)})))
      (some-> (:idle-timer @st) (apply []))
      (swap! st #(-> % (update :refs inc) (update :subs (fnil conj #{}) on-change) (dissoc :idle-timer))))
    {:current (fn [] (let [{:keys [value version]} @st] [value version]))
     :blob (fn [from to]
             (locking (.-flush-lock sa)
               (let [{:keys [log blobs]} @st k [from to]]
                 (if (contains? blobs k)
                   (get blobs k)
                   (let [nv (get-in log [to :value])
                         b (cond
                             (and from (= to (inc from)) (contains? log to) (contains? log from))
                             (codec/encode-delta-blob (get-in log [to :delta]))
                             (and from (contains? log from))
                             (when-let [d (delta/diff (get-in log [from :value]) nv)] (codec/encode-delta-blob d))
                             :else (codec/encode-delta-blob [:v nv]))]
                     (swap! stats update :encodes inc)
                     (swap! st assoc-in [:blobs k] b)
                     b)))))
     :release (fn []
                (let [left (locking sa
                             (:refs (swap! st #(-> % (update :refs dec) (update :subs disj on-change)))))]
                  (when (zero? left) (schedule-idle! sa))))}))

(defn swap-meta!
  "swap! with write metadata, e.g. {:op-id \"…\" :user 7}. Listeners and the
  family's :flush see it; an :op-id seen recently is a no-op."
  [sa meta f & args]
  (commit! sa meta f args))

(defn reset-meta! [sa meta v] (commit! sa meta (constantly v) []))

(defn version "The atom's current version (0 before the first change)." [^SharedAtom sa]
  (:version @(.-state sa)))

(defn listen!
  "Call (f change) after every change of sa, in version order, under its
  lock (keep f fast). change: {:key :version :old :new :delta :meta :at}."
  [^SharedAtom sa k f]
  (swap! (.-state sa) assoc-in [:listeners k] f) sa)

(defn unlisten! [^SharedAtom sa k] (swap! (.-state sa) update :listeners dissoc k) sa)

(defn watchers "How many sessions follow sa." [^SharedAtom sa] (:refs @(.-state sa)))

(defn- flush-now!
  "Hand pending changes to the family's :flush fn, one batch at a time. A
  failed batch is put back in front and retried with the next one."
  [^SharedAtom sa]
  (when-let [flush (:flush (.-opts sa))]
    (locking (.-flush-lock sa)
      (let [st (.-state sa)
            [batch value] (locking sa
                            (let [{:keys [pending value]} @st]
                              (swap! st assoc :pending [])
                              [pending value]))]
        (when (seq batch)
          (try (flush (.-key sa) batch value)
               (catch Throwable e
                 (locking sa (swap! st update :pending #(into batch %)))
                 (throw e))))))))

(defn flush!
  "Write sa's pending changes now (blocking)."
  [sa]
  (flush-now! sa))

(defn- retire-atom!
  "Unload an idle family member: final flush, then drop it from the family.
  Holds the family lock throughout, so a new lookup of the same key waits and
  then loads what this flush wrote."
  [^SharedAtom sa]
  (let [members (::members (.-opts sa))
        st (.-state sa)]
    (locking members
      (when (locking sa
              (when (and (zero? (:refs @st)) (not (:retired @st)))
                (swap! st assoc :retired true)
                true))
        (some-> (:flusher @st) timing/cancel!)
        (try (flush-now! sa) (catch Throwable e (.printStackTrace e)))
        (swap! members (fn [m] (if (identical? sa (get m (.-key sa))) (dissoc m (.-key sa)) m)))))))

(defn- live? [sa] (and sa (not (:retired @(.-state ^SharedAtom sa)))))

(defn atom-family
  "A family of SharedAtoms keyed by key, created on first use. Returns a fn
  key -> SharedAtom. opts:
    :init        (fn [key] value) when nothing is stored
    :load        (fn [key] value-or-nil) read from storage on creation
    :flush       (fn [key changes value]) write back a batch of changes
    :flush-ms    write after this much quiet (debounce, default 50)
    :max-wait-ms but at least this often while writes continue (default 1000)
    :idle-ms     unload this long after the last watcher left (default
                 60000; nil keeps members forever)
    :validate    (fn [key old new meta]) falsey rejects the change
    :on-change   (fn [change]) every change of every member, in order
    :log-size    versions kept for cursors (default 64)
    :clock       a hypercurve.clock Clock (tests)"
  [opts]
  (let [opts (merge {:flush-ms 50 :max-wait-ms 1000 :idle-ms 60000} opts)
        members (clojure.core/atom {})
        family (fn family [key]
                 (or (let [sa (get @members key)] (when (live? sa) sa))
                     (locking members
                       (or (let [sa (get @members key)] (when (live? sa) sa))
                           (let [v (if-let [load (:load opts)] (load key) nil)
                                 v (if (and (nil? v) (:init opts)) ((:init opts) key) v)
                                 ^SharedAtom sa (new-shared-atom key family (assoc opts ::members members) v)]
                             (when (:flush opts)
                               (swap! (.-state sa) assoc :flusher
                                      (timing/debounce (:flush-ms opts)
                                                       {:max-wait (:max-wait-ms opts) :clock (:clock opts)}
                                                       (fn [] (.execute ^ExecutorService flush-pool
                                                                        ^Runnable #(try (flush-now! sa)
                                                                                        (catch Throwable e (.printStackTrace e))))))))
                             (swap! members assoc key sa)
                             (schedule-idle! sa)
                             sa)))))]
    (with-meta family {::members members})))

(defn members "The loaded members of a family: {key SharedAtom}." [family]
  @(::members (meta family)))

(defonce ^:private default-family
  (atom-family {:idle-ms nil}))

(defonce ^:private inits (clojure.core/atom {}))

(defn shared-atom
  "The process-wide SharedAtom for key, created with init the first time.
  Collaborative state: every session that watches it sees every change.
  Kept for the life of the process; use atom-family for loading, writing
  back and unloading."
  [key init]
  (or (get (members default-family) key)
      (locking inits
        (or (get (members default-family) key)
            (let [^SharedAtom sa (default-family key)]
              (when (and (nil? @sa) (some? init))
                ;; the first value is the init, not a change
                (let [st (.-state ^SharedAtom sa)]
                  (swap! st assoc :value init :log (sorted-map 0 {:value init}))
                  (.reset ^clojure.lang.Atom (.-a ^SharedAtom sa) init)))
              sa)))))
