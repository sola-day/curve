(ns curve.source.datomic
  "Datomic adapter (design §8.5): live queries re-run when a transaction
  touches an attribute they read. Cost per transaction: O(changed datoms)
  to classify, then one query per affected live query.

  Needs com.datomic/peer (or a compatible API) on the classpath; the API is
  looked up at runtime, so this namespace loads without it. Verified here
  against an in-memory stand-in of the API, not a real Datomic."
  (:require [curve.source :as src]))

(defn- api [sym] (requiring-resolve (symbol "datomic.api" (name sym))))

(def default-api
  {:q #((api 'q) %1 %2)
   :db #((api 'db) %)
   :tx-report-queue #((api 'tx-report-queue) %)
   :ident (fn [db id] (:db/ident ((api 'entity) db id)))})

(defn query-attrs
  "Attributes a datalog query mentions in its :where clauses (keywords in
  attribute position)."
  [query]
  (let [where (second (drop-while #(not= :where %) query))
        clauses (take-while #(not (keyword? %)) (rest (drop-while #(not= :where %) query)))]
    (set (keep (fn [c] (when (and (vector? c) (keyword? (second c))) (second c))) clauses))))

(defrecord DatomicSource [conn api subs]
  src/Source
  (rows [_ query]
    (let [attrs (query-attrs query)]
      (src/live-ref #((:q api) query ((:db api) conn))
                    (fn [refresh]
                      (let [e {:attrs attrs :refresh refresh}]
                        (swap! subs conj e)
                        #(swap! subs disj e)))))))

(defn source
  "A source over a Datomic connection. Starts a thread reading the
  transaction report queue."
  ([conn] (source conn default-api))
  ([conn api]
   (let [s (->DatomicSource conn api (atom #{}))
         ^java.util.concurrent.BlockingQueue q ((:tx-report-queue api) conn)]
     (doto (Thread. ^Runnable
                    (fn []
                      (loop []
                        (let [{:keys [db-after tx-data]} (.take q)
                              touched (set (keep (fn [d] ((:ident api) db-after (:a d))) tx-data))]
                          (doseq [{:keys [attrs refresh]} @(:subs s)
                                  :when (some touched attrs)]
                            (refresh)))
                        (recur)))
                    "curve-datomic-tx")
       (.setDaemon true)
       (.start))
     s)))
