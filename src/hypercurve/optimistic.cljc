(ns hypercurve.optimistic
  "Optimistic projection (design §9): a client view of a server value with
  pending transforms applied on top. A mutation pushes its transform, the
  view updates at once, and the transform is dropped when the server
  answers: on success the server's value already includes the change, on
  failure the view simply rolls back. Transforms stack in call order, so
  there is no undo/redo flicker.")

(defn- project [{:keys [base pending]}]
  (reduce (fn [v [_ f]] (f v)) base pending))

(deftype Projection [state]
  #?@(:clj [clojure.lang.IDeref
            (deref [_] (project @state))
            clojure.lang.IRef
            (addWatch [this k f]
                      (add-watch state k (fn [_ _ o n] (f k this (project o) (project n))))
                      this)
            (removeWatch [this k] (remove-watch state k) this)
            (getWatches [_] (.getWatches ^clojure.lang.IRef state))
            (setValidator [_ _] (throw (UnsupportedOperationException.)))
            (getValidator [_] nil)]
      :cljs [IDeref
             (-deref [_] (project @state))
             IWatchable
             (-add-watch [this k f]
                         (add-watch state k (fn [_ _ o n] (f k this (project o) (project n))))
                         this)
             (-remove-watch [this k] (remove-watch state k) this)
             (-notify-watches [_ _ _] nil)]))

(defn projection [] (Projection. (atom {:base nil :pending (sorted-map) :next 0})))

(defn rebase!
  "The server value changed."
  [^Projection p v]
  (swap! (.-state p) assoc :base v)
  nil)

(defn push!
  "Apply transform f on top of the server value until drop! is called."
  [^Projection p f]
  (let [id (:next (swap! (.-state p) update :next inc))]
    (swap! (.-state p) update :pending assoc id f)
    id))

(defn drop! [^Projection p id]
  (swap! (.-state p) update :pending dissoc id)
  nil)

(defn pending-count [^Projection p] (count (:pending @(.-state p))))
