(ns curve.session
  "Server sessions. A session is a task, not a thread: it owns a server peer
  and a serial task queue drained by a shared pool with one worker per core.
  Everything that touches the peer (incoming messages, watch notifications
  from other threads, timers) is posted to the queue, so the peer is only
  ever used by one thread at a time. After a batch of tasks the session
  propagates once, encodes once and sends once."
  (:require [curve.codec :as codec]
            [curve.runtime :as rt])
  (:import [java.util.concurrent ConcurrentLinkedQueue Executors ExecutorService ThreadFactory]
           [java.util.concurrent.atomic AtomicBoolean AtomicLong]))

(defonce ^ExecutorService pool
  (Executors/newFixedThreadPool
    (.availableProcessors (Runtime/getRuntime))
    (reify ThreadFactory
      (newThread [_ r] (doto (Thread. ^Runnable r "curve-worker") (.setDaemon true))))))

(defonce metrics
  {:sessions (AtomicLong.) :messages-in (AtomicLong.) :messages-out (AtomicLong.)
   :bytes-in (AtomicLong.) :bytes-out (AtomicLong.) :errors (AtomicLong.)})

(defn metric [k] (.get ^AtomicLong (metrics k)))

(declare drain!)

(defn- post!
  "Queue f to run on the session's turn."
  [s f]
  (.add ^ConcurrentLinkedQueue (:tasks s) f)
  (when (.compareAndSet ^AtomicBoolean (:scheduled s) false true)
    (.execute ^ExecutorService (:executor s) #(drain! s))))

(defn- send-pending! [s]
  (when-let [m (rt/take-message! (:peer s))]
    (let [bs ((:encode s) m)]
      (.incrementAndGet ^AtomicLong (metrics :messages-out))
      (.addAndGet ^AtomicLong (metrics :bytes-out) (codec/byte-count bs))
      ((:send! s) bs))))

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
      (rt/run! (:peer s))
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
  [ctor args {:keys [send! executor on-error on-close window] :or {executor pool window 16}}]
  (let [s-ref (volatile! nil)
        peer (rt/peer :server
                      :window window
                      :post! (fn [g] (post! @s-ref g))
                      :on-schedule (fn [] (when-let [s @s-ref]
                                            (when-not (.get ^AtomicBoolean (:scheduled s))
                                              (post! s (fn []))))))
        link-out (codec/link)
        link-in (codec/link)
        s {:peer peer :tasks (ConcurrentLinkedQueue.) :scheduled (AtomicBoolean. false)
           :executor executor :send! send! :on-error on-error :on-close on-close
           :encode (:encode link-out) :decode (:decode link-in)
           :root (volatile! nil) :open (atom true)}]
    (vreset! s-ref s)
    (.incrementAndGet ^AtomicLong (metrics :sessions))
    (post! s (fn [] (vreset! (:root s) (apply rt/mount-root! peer ctor args))))
    s))

(defn receive!
  "Bytes arrived from this session's client."
  [s bs]
  (.incrementAndGet ^AtomicLong (metrics :messages-in))
  (.addAndGet ^AtomicLong (metrics :bytes-in) (codec/byte-count bs))
  (post! s (fn [] (rt/receive! (:peer s) ((:decode s) bs)))))

(defn call
  "Run f on the session's turn and wait for its result (tests, tooling)."
  [s f]
  (let [p (promise)]
    (post! s (fn [] (deliver p (try (f) (catch Throwable e e)))))
    (deref p 5000 ::timeout)))

(defn frame-count [s] (call s #(count (rt/frames (:peer s)))))
