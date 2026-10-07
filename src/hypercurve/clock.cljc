(ns hypercurve.clock
  "Time source abstraction. Production uses the host clock and timers;
  tests use a virtual clock that only advances when told to.")

(defprotocol Clock
  (now [c] "Current time in milliseconds.")
  (schedule! [c delay-ms f] "Run f after delay-ms. Returns a cancel fn."))

#?(:clj
   (defonce ^:private timer-pool
     ;; one shared timer thread; timers only hand work off, they never block
     (delay (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
              (reify java.util.concurrent.ThreadFactory
                (newThread [_ r] (doto (Thread. ^Runnable r "hypercurve-timer") (.setDaemon true))))))))

(deftype HostClock []
  Clock
  (now [_] #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))
  (schedule! [_ delay-ms f]
    #?(:clj (let [fut (.schedule ^java.util.concurrent.ScheduledExecutorService @timer-pool
                                 ^Runnable (fn [] (try (f) (catch Throwable e (.printStackTrace e))))
                                 (long (max 0 delay-ms)) java.util.concurrent.TimeUnit/MILLISECONDS)]
              (fn [] (.cancel ^java.util.concurrent.ScheduledFuture fut false)))
       :cljs (let [id (js/setTimeout f delay-ms)]
               (fn [] (js/clearTimeout id))))))

(def host (HostClock.))

(deftype VirtualClock [state]
  Clock
  (now [_] (:t @state))
  (schedule! [_ delay-ms f]
    (let [id (:next-id @state)]
      (swap! state (fn [s] (-> s
                               (update :next-id inc)
                               (assoc-in [:timers id] [(+ (:t s) delay-ms) id f]))))
      (fn [] (swap! state update :timers dissoc id)))))

(defn virtual-clock
  ([] (virtual-clock 0))
  ([t0] (VirtualClock. (atom {:t t0 :next-id 0 :timers {}}))))

(defn- next-timer [state]
  (first (sort (map (fn [[at id _]] [at id]) (vals (:timers @state))))))

(defn advance!
  "Advance a virtual clock by ms, firing due timers in time order.
  Timers scheduled by fired timers also fire if they fall within the window."
  [^VirtualClock c ms]
  (let [state (.-state c)
        target (+ (:t @state) ms)]
    (loop []
      (let [[at id] (next-timer state)]
        (if (and at (<= at target))
          (let [[_ _ f] (get-in @state [:timers id])]
            (swap! state #(-> % (assoc :t at) (update :timers dissoc id)))
            (f)
            (recur))
          (swap! state assoc :t target))))))

(defn pending-timers [^VirtualClock c] (count (:timers @(.-state c))))
