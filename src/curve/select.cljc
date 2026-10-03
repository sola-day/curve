(ns curve.select
  "O(1) selection (design §17.5): many rows ask \"am I selected?\" and only
  the rows whose answer changes are woken.

    (def sel (select/selector))                 ; or (select/selector !current-id)
    (r/watch (select/is sel id))                ; per row: true/false
    (select/select! sel 7)")

(defprotocol ISelector
  (-current [s])
  (-select! [s k])
  (-watch-key [s k f])
  (-unwatch-key [s k f]))

(deftype Selector [cur watchers]
  ISelector
  (-current [_] @cur)
  (-select! [_ k]
    (let [old @cur]
      (when (not= old k)
        (reset! cur k)
        (doseq [x [old k] f (vals (get @watchers x))] (f (= x k))))))
  (-watch-key [_ k f] (swap! watchers assoc-in [k f] f))
  (-unwatch-key [_ k f] (swap! watchers update k dissoc f)))

(defn selector
  ([] (selector nil))
  ([init] (Selector. (atom init) (atom {}))))

(defn select! [s k] (-select! s k))
(defn current [s] (-current s))

(deftype KeyRef [s k watches]
  #?@(:clj [clojure.lang.IDeref
            (deref [_] (= k (-current s)))
            clojure.lang.IRef
            (addWatch [this wk f]
                      (let [cb (fn [v] (f wk this (not v) v))]
                        (swap! watches assoc wk cb)
                        (-watch-key s k cb))
                      this)
            (removeWatch [this wk]
                         (when-let [cb (get @watches wk)] (-unwatch-key s k cb) (swap! watches dissoc wk))
                         this)
            (getWatches [_] @watches)
            (setValidator [_ _] (throw (UnsupportedOperationException.)))
            (getValidator [_] nil)]
      :cljs [IDeref
             (-deref [_] (= k (-current s)))
             IWatchable
             (-add-watch [this wk f]
                         (let [cb (fn [v] (f wk this (not v) v))]
                           (swap! watches assoc wk cb)
                           (-watch-key s k cb))
                         this)
             (-remove-watch [this wk]
                            (when-let [cb (get @watches wk)] (-unwatch-key s k cb) (swap! watches dissoc wk))
                            this)
             (-notify-watches [_ _ _] nil)]))

(defn is
  "A watchable that is true while k is selected; only notified when that changes."
  [s k]
  (KeyRef. s k (atom {})))
