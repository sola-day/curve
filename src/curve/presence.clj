(ns curve.presence
  "Who is here (design §10.1). In a reactive fn:
    (r/server (r/effect (presence/join! [::room id] {:name user})))  ; leaves on unmount
    (r/server (r/watch (presence/members [::room id])))              ; everyone's info
  Members are keyed by a per-join id, so one user in two tabs is two entries.")

(defonce ^:private rooms (atom {}))

(defn members
  "A reference to {join-id info} for room."
  [room]
  (or (get @rooms room)
      (get (swap! rooms (fn [m] (if (contains? m room) m (assoc m room (atom {}))))) room)))

(defn join!
  "Add info to room; returns the leave fn (r/effect calls it on unmount)."
  [room info]
  (let [id (str (random-uuid)) a (members room)]
    (swap! a assoc id info)
    (fn [] (swap! a dissoc id))))

(defn update! [room id f & args] (apply swap! (members room) update id f args))
