(ns curve.agg
  "Incremental aggregation for visualisation (design §16.3).

  (bucket-by agg :t 60000 :v points) groups points into time buckets of
  width ms and aggregates :v per bucket. Called again with the same series
  grown by appends (the usual case for streaming data), only the new points
  are aggregated: O(new points), not O(series).")

(def aggregators
  {:count {:init 0 :add (fn [a _] (inc a))}
   :sum {:init 0 :add +}
   :min {:init nil :add (fn [a v] (if (nil? a) v (min a v)))}
   :max {:init nil :add (fn [a v] (if (nil? a) v (max a v)))}
   :last {:init nil :add (fn [_ v] v)}})

(defn- add-points [result {:keys [init add]} tk width vk points]
  (reduce (fn [m p]
            (let [b (* width (quot (tk p) width))]
              (assoc m b (add (get m b init) (vk p)))))
          result points))

(defn bucketer
  "A stateful bucket-by: call it with the whole series each time."
  [agg tk width vk]
  (let [a (if (map? agg) agg (aggregators agg))
        state (volatile! {:series [] :result (sorted-map) :seen 0})]
    (fn [series]
      (let [{prev :series result :result seen :seen} @state
            series (vec series)
            n (count series)
            ;; appended? same prefix (by identity of the last seen point)
            appended (and (pos? seen) (>= n seen)
                          (identical? (nth series (dec seen)) (nth prev (dec seen))))
            result (if appended
                     (add-points result a tk width vk (subvec series seen))
                     (add-points (sorted-map) a tk width vk series))]
        (vreset! state {:series series :result result :seen n})
        result))))
