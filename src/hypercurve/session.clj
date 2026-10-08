(ns hypercurve.session
  "Server sessions. A session is a task, not a thread: it owns a server peer
  and a serial task queue drained by a shared pool with one worker per core.
  Everything that touches the peer (incoming messages, watch notifications
  from other threads, timers) is posted to the queue, so the peer is only
  ever used by one thread at a time. After a batch of tasks the session
  propagates once, encodes once and sends once."
  (:require [hypercurve.codec :as codec]
            [hypercurve.runtime :as rt]
            [hypercurve.transport :as transport])
  (:import [java.util.concurrent ConcurrentLinkedQueue Executors ExecutorService ThreadFactory]
           [java.util.concurrent.atomic AtomicBoolean AtomicLong]))

(defonce ^ExecutorService pool
  (Executors/newFixedThreadPool
    (.availableProcessors (Runtime/getRuntime))
    (reify ThreadFactory
      (newThread [_ r] (doto (Thread. ^Runnable r "hypercurve-worker") (.setDaemon true))))))

(defonce ^java.util.concurrent.ScheduledExecutorService timer
  (Executors/newSingleThreadScheduledExecutor
    (reify ThreadFactory
      (newThread [_ r] (doto (Thread. ^Runnable r "hypercurve-session-timer") (.setDaemon true))))))

(defonce metrics
  {:sessions (AtomicLong.) :messages-in (AtomicLong.) :messages-out (AtomicLong.)
   :bytes-in (AtomicLong.) :bytes-out (AtomicLong.) :errors (AtomicLong.)
   :budget-trips (AtomicLong.) :degraded (AtomicLong.) :hibernations (AtomicLong.)})

(defn metric [k] (.get ^AtomicLong (metrics k)))

(defn metrics-snapshot
  "All counters, plus live shared instances (design §8.2 metrics)."
  []
  (merge (into (sorted-map) (for [[k v] metrics] [k (.get ^AtomicLong v)]))
         {:shared-instances (count @@(requiring-resolve 'hypercurve.shared/instances))}))

(def default-budget
  "Per-session limits (design §8.2). Exceeding bandwidth first degrades
  (sends are delayed and coalesced); sustained or other excess closes the
  session, never the process."
  {:max-nodes 200000
   :max-bytes-per-sec 2000000
   :max-turn-ms 2000
   :max-strikes 3
   :over-limit-secs 10})

(defn- trip! [s kind detail]
  (.incrementAndGet ^AtomicLong (metrics :budget-trips))
  (throw (ex-info (str "hypercurve: session budget exceeded: " (name kind)) {:budget kind :detail detail})))

(declare drain! close! hibernated? hibernate!)

(defn- post!
  "Queue f to run on the session's turn."
  [s f]
  (.add ^ConcurrentLinkedQueue (:tasks s) f)
  (when (.compareAndSet ^AtomicBoolean (:scheduled s) false true)
    (.execute ^ExecutorService (:executor s) #(drain! s))))

(defn- bandwidth-ok?
  "Within this second's byte budget? If not, retry when the second ends."
  [s]
  (let [{:keys [max-bytes-per-sec over-limit-secs]} (:budget s)
        now (System/currentTimeMillis)
        st (:bw s)
        {:keys [start bytes over-since]} @st]
    (when (>= (- now start) 1000) (swap! st assoc :start now :bytes 0))
    (if (< (:bytes @st) max-bytes-per-sec)
      (do (swap! st assoc :over-since nil) true)
      (let [since (or over-since now)]
        (swap! st assoc :over-since since)
        (.incrementAndGet ^AtomicLong (metrics :degraded))
        (when (> (- now since) (* 1000 over-limit-secs))
          (trip! s :bandwidth {:bytes-per-sec (:bytes @st)}))
        (let [wait (max 1 (- 1000 (- now (:start @st))))]
          (.schedule ^java.util.concurrent.ScheduledExecutorService (:timer s)
                     ^Runnable (fn [] (post! s (fn []))) (long wait) java.util.concurrent.TimeUnit/MILLISECONDS))
        false))))

(defn- send-pending! [s]
  (when (bandwidth-ok? s)
    (when-let [m (rt/take-message! (:peer s))]
      (let [bs ((:encode s) m)
            n (codec/byte-count bs)]
        (.incrementAndGet ^AtomicLong (metrics :messages-out))
        (.addAndGet ^AtomicLong (metrics :bytes-out) n)
        (swap! (:bw s) update :bytes + n)
        (swap! (:stats s) update :bytes-out + n)
        ((:send! s) (transport/data-frame (:transport s) bs))))))

(defn detach!
  "Keep the session running but hold outgoing messages (between a server
  render and the browser's connection)."
  [s]
  (reset! (:sink s) {:buffer []}))

(defn attach!
  "Give the session a live connection; held messages go first."
  [s send!]
  (let [{:keys [buffer]} (first (swap-vals! (:sink s) (constantly {:send! send!})))]
    (doseq [bs buffer] (send! bs))
    (post! s (fn []))))

;; ---- reconnection (design §8.3): a dropped connection detaches the session
;; for a grace period; the client comes back with its token and the last
;; frame it saw, and both sides resend what the other missed.

(defonce ^:private by-token (atom {}))

(defn connection-lost!
  "The socket closed. Keep the session for grace-ms, then close it."
  [s grace-ms]
  (when @(:open s)
    (detach! s)
    (swap! by-token assoc (:token s) s)
    (.schedule timer ^Runnable (fn [] (when (and (= s (get @by-token (:token s))) (:buffer @(:sink s)))
                                        (swap! by-token dissoc (:token s))
                                        (close! s)))
               (long grace-ms) java.util.concurrent.TimeUnit/MILLISECONDS)))

(defn socket-closed!
  "The socket closed with a WebSocket close code. The client closing on
  purpose (1000; 1001 when its page goes away) ends the session now: it
  will not come back for it, and a session left for the grace period stays
  visible to others (presence). Anything else may be a dropped connection."
  [s code grace-ms]
  (if (contains? #{1000 1001} code)
    (close! s)
    (connection-lost! s grace-ms)))

(defn reconnect!
  "A client came back: attach its new connection and resend the frames it
  has not seen. nil when the session is gone (the client starts over)."
  [token seen send!]
  (when-let [s (get @by-token token)]
    (swap! by-token dissoc token)
    (reset! (:sink s) {:send! send!})
    (doseq [f (transport/resend (:transport s) seen)] (send! f))
    (send! (transport/control-frame (:transport s) {:session (:token s) :version (:version s)}))
    (post! s (fn []))
    s))

(defn- check-budget! [s turn-ms]
  (let [{:keys [max-nodes max-turn-ms max-strikes]} (:budget s)
        nodes (:nodes @(:stats s))]
    (swap! (:stats s) update :turns inc)
    (when (> nodes max-nodes) (trip! s :nodes {:nodes nodes}))
    (when (> turn-ms max-turn-ms)
      (let [strikes (:strikes (swap! (:stats s) update :strikes inc))]
        (when (>= strikes max-strikes) (trip! s :cpu {:turn-ms turn-ms}))))))

(defn stats "Per-session counters: nodes, bytes-out, turns, strikes." [s] @(:stats s))

(defn close!
  "Tear the session down: unmount (runs every cleanup) and stop."
  [s]
  (when (compare-and-set! (:open s) true false)
    (post! s (fn [] (when-let [r @(:root s)] (rt/unmount-frame! r))))
    (.decrementAndGet ^AtomicLong (metrics :sessions))
    (when-let [f (:on-close s)] (f))))

(defn- drain! [s]
  (try
    (loop []
      (when-let [f (.poll ^ConcurrentLinkedQueue (:tasks s))]
        (f)
        (recur)))
    (when @(:open s)
      (let [t0 (System/nanoTime)]
        (rt/run! (:peer s))
        (check-budget! s (/ (- (System/nanoTime) t0) 1e6)))
      (send-pending! s))
    (catch Throwable e
      ;; a failing session is cut off; others keep running
      (.incrementAndGet ^AtomicLong (metrics :errors))
      (when-let [h (:on-error s)] (h e))
      (close! s))
    (finally
      (.set ^AtomicBoolean (:scheduled s) false)
      (when (and (not (.isEmpty ^ConcurrentLinkedQueue (:tasks s)))
                 (.compareAndSet ^AtomicBoolean (:scheduled s) false true))
        (.execute ^ExecutorService (:executor s) #(drain! s))))))

(defn start!
  "Start a session running ctor (with args) as the root.
  opts: :send! (fn [bytes]) required; :executor; :on-error; :on-close;
  :window, unacknowledged messages allowed in flight (default 16)."
  [ctor args {:keys [send! executor on-error on-close window budget hibernate-ms]
              :or {executor pool window 16}}]
  (let [s-ref (volatile! nil)
        sink (atom {:send! send!})
        stats (atom {:nodes 0 :bytes-out 0 :turns 0 :strikes 0})
        peer (rt/peer :server
                      :window window
                      :on-mount (fn [f] (swap! stats update :nodes + (count (:nodes (:ctor f)))))
                      :on-unmount (fn [f] (swap! stats update :nodes - (count (:nodes (:ctor f)))))
                      :post! (fn [g] (post! @s-ref g))
                      :on-schedule (fn [] (when-let [s @s-ref]
                                            (when-not (.get ^AtomicBoolean (:scheduled s))
                                              (post! s (fn []))))))
        link-out (codec/link)
        link-in (codec/link)
        s {:peer peer :tasks (ConcurrentLinkedQueue.) :scheduled (AtomicBoolean. false)
           :executor executor :on-error on-error :on-close on-close
           :sink sink
           :send! (fn [bs] (let [{f :send!} @sink]
                             (if f (f bs) (swap! sink update :buffer (fnil conj []) bs))))
           :encode (:encode link-out) :decode (:decode link-in)
           :codec-out (:enc link-out) :codec-in (:dec link-in)
           :root (volatile! nil) :open (atom true)
           :stats stats :budget (merge default-budget budget)
           :bw (atom {:start (System/currentTimeMillis) :bytes 0 :over-since nil})
           :timer timer
           :transport (transport/state)
           :token (str (java.util.UUID/randomUUID))
           :version (rt/tree-version ctor)
           :ctor ctor :args args
           :hibernated (atom nil)
           :last-activity (atom (System/currentTimeMillis))}]
    (vreset! s-ref s)
    (.incrementAndGet ^AtomicLong (metrics :sessions))
    ((:send! s) (transport/control-frame (:transport s) {:session (:token s) :version (:version s)}))
    (when hibernate-ms
      (let [check (fn check []
                    (when @(:open s)
                      (when (and (not (hibernated? s))
                                 (> (- (System/currentTimeMillis) @(:last-activity s)) hibernate-ms))
                        (post! s #(hibernate! s)))
                      (.schedule timer ^Runnable check (long (max 50 (quot hibernate-ms 2)))
                                 java.util.concurrent.TimeUnit/MILLISECONDS)))]
        (check)))
    (post! s (fn [] (vreset! (:root s) (apply rt/mount-root! peer ctor args))))
    s))

(declare wake!)

(defn receive!
  "A frame arrived from this session's client."
  [s frame]
  (.incrementAndGet ^AtomicLong (metrics :messages-in))
  (.addAndGet ^AtomicLong (metrics :bytes-in) (codec/byte-count frame))
  (post! s (fn []
             (reset! (:last-activity s) (System/currentTimeMillis))
             (wake! s)
             (let [{:keys [data]} (transport/receive (:transport s) frame)]
               (when data (rt/receive! (:peer s) ((:decode s) data)))))))

;; ---- hibernation (design §8.6 item 6)

(defn hibernate!
  "Snapshot the server peer and unmount it (watches, shared values and
  frames released) without telling the client. Called on the session's turn."
  [s]
  (let [peer (:peer s)]
    (when-let [root @(:root s)]
      (when-not (rt/has-pending-output? peer)
        (reset! (:hibernated s) (rt/resume-snapshot peer))
        (rt/unmount-silently! peer root)
        (vreset! (:root s) nil)
        (.incrementAndGet ^AtomicLong (metrics :hibernations))))))

(defn wake!
  "Restore a hibernated session from its snapshot (on its turn)."
  [s]
  (when-let [snap @(:hibernated s)]
    (reset! (:hibernated s) nil)
    (vreset! (:root s) (apply rt/resume! (:peer s) (:ctor s) snap (:args s)))
    ;; rebuild closures and subscriptions before handling what woke us
    (rt/run! (:peer s))))

(defn hibernated? [s] (some? @(:hibernated s)))

(defn migrate!
  "This node is draining: ask the client to reconnect (to another node),
  keeping its local state, then close."
  [s]
  ((:send! s) (transport/control-frame (:transport s) {:drain true}))
  (.schedule timer ^Runnable (fn [] (close! s)) 1000 java.util.concurrent.TimeUnit/MILLISECONDS))

(defn call
  "Run f on the session's turn and wait for its result (tests, tooling)."
  [s f]
  (let [p (promise)]
    (post! s (fn [] (deliver p (try (f) (catch Throwable e e)))))
    (deref p 5000 ::timeout)))

(defn frame-count [s] (call s #(count (rt/frames (:peer s)))))
