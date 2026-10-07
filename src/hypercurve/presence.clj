(ns hypercurve.presence
  "Who is here (design §10.1). In a reactive fn:
    (r/server (r/effect (presence/join! [::room id] {:name user})))  ; leaves on unmount
    (r/server (r/watch (presence/members [::room id])))              ; everyone's info
  Members are keyed by a per-join id, so one user in two tabs is two entries.

  High-frequency info (cursors, a shape being dragged) goes through a
  publisher, which merges what you give it and applies it at most :rate
  times a second, so 120 pointer events a second become 30 updates:
    (let [me (r/server (str (random-uuid)))]
      (r/server (r/effect (presence/join! [::room id] me {:name user})))
      (let [pub (r/server (presence/publisher [::room id] me {:rate 30}))
            move! (r/server (fn [xy] (pub {:cursor xy})))]
        ...))"
  (:require [hypercurve.timing :as timing]))

(defonce ^:private rooms (atom {}))

(defn members
  "A reference to {join-id info} for room."
  [room]
  (or (get @rooms room)
      (get (swap! rooms (fn [m] (if (contains? m room) m (assoc m room (atom {}))))) room)))

(defn join!
  "Add info to room under a fresh id, or under id when given; returns the
  leave fn (r/effect calls it on unmount)."
  ([room info] (join! room (str (random-uuid)) info))
  ([room id info]
   (let [a (members room)]
     (swap! a assoc id info)
     (fn [] (swap! a dissoc id)))))

(defn update! [room id f & args]
  (apply swap! (members room)
         (fn [m] (if (contains? m id) (apply update m id f args) m)) []))

(defn publisher
  "A fn (pub m) merging m into member id's info at most :rate times a second
  (default 30). Keys given between two updates are merged, the latest value
  per key wins; nil values remove the key. (timing/flush! pub) applies the
  pending merge now; (timing/cancel! pub) drops it."
  [room id {:keys [rate clock] :or {rate 30}}]
  (let [pending (atom {})
        apply! (fn [] (let [m (first (reset-vals! pending {}))]
                        (when (seq m)
                          (update! room id (fn [info]
                                             (reduce-kv (fn [i k v] (if (nil? v) (dissoc i k) (assoc i k v)))
                                                        info m))))))
        t (timing/throttle (/ 1000.0 rate) (cond-> {} clock (assoc :clock clock)) apply!)]
    (reify
      clojure.lang.IFn
      (invoke [_ m] (swap! pending merge m) (t))
      timing/Pending
      (flush! [_] (timing/flush! t))
      (cancel! [_] (timing/cancel! t) (reset! pending {}) nil)
      (pending? [_] (boolean (seq @pending))))))
