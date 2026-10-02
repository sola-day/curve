(ns curve.clock
  "Time source abstraction. Production uses the host clock and timers;
  tests use a virtual clock that only advances when told to.")

(defprotocol Clock
  (now [c] "Current time in milliseconds.")
  (schedule! [c delay-ms f] "Run f after delay-ms. Returns a cancel fn."))

(deftype HostClock []
  Clock
  (now [_] #?(:clj (System/currentTimeMillis) :cljs (.now js/Date)))
  (schedule! [_ delay-ms f]
    #?(:clj (let [t (doto (Thread. ^Runnable (fn []
                                              (try (Thread/sleep (long delay-ms)) (f)
                                                   (catch InterruptedException _ nil))))
                      (.setDaemon true) (.start))]
              (fn [] (.interrupt t)))
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
