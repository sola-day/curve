(ns hypercurve.shared
  "Process-wide shared reactive values (r/shared).

  An instance is identified by [ctor key captures]. It is computed once, on
  a dedicated serial runner, however many sessions follow it. Each instance
  keeps a bounded log of recent versions; sessions remember only the version
  they last sent (a cursor) and point at the same persistent value, so N
  sessions cost one value plus N integers. The delta from version a to b is
  encoded once into a self-contained blob and the same bytes go to every
  session at cursor a."
  (:require [hypercurve.codec :as codec]
            [hypercurve.delta :as delta]
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

(defonce ^:private atoms (atom {}))

(defn shared-atom
  "The process-wide atom for key, created with init the first time.
  Collaborative state: every session that watches it sees every change."
  [key init]
  (or (get @atoms key)
      (get (swap! atoms (fn [m] (if (contains? m key) m (assoc m key (clojure.core/atom init))))) key)))
