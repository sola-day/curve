(ns hypercurve.source
  "Data sources (design §8.5). A source turns a query into a live reference:
  watchable, and subscribed to its source only while something watches it,
  so r/watch inside r/shared gives one subscription per distinct query."
  (:require [clojure.string])
  (:import [java.util.concurrent Executors ScheduledExecutorService TimeUnit ThreadFactory]))

(defprotocol Source
  (rows [src query] "A live reference whose value is the current query result."))

(deftype LiveRef [compute subscribe state]
  clojure.lang.IDeref
  (deref [_]
    (if (seq (:watches @state)) (:value @state) (compute)))
  clojure.lang.IRef
  (addWatch [this k f]
    (let [start? (empty? (:watches @state))]
      (swap! state update :watches assoc k f)
      (when start?
        (swap! state assoc :value (compute))
        (swap! state assoc :unsub
               (subscribe (fn refresh []
                            (let [old (:value @state) new (compute)]
                              (when (not= old new)
                                (swap! state assoc :value new)
                                (doseq [[k f] (:watches @state)] (f k this old new)))))))))
    this)
  (removeWatch [this k]
    (swap! state update :watches dissoc k)
    (when (empty? (:watches @state))
      (when-let [u (:unsub @state)] (u))
      (swap! state assoc :unsub nil))
    this)
  (getWatches [_] (:watches @state))
  (setValidator [_ _] (throw (UnsupportedOperationException.)))
  (getValidator [_] nil))

(defn live-ref
  "compute: () -> value. subscribe: (refresh-fn) -> unsubscribe-fn."
  [compute subscribe]
  (LiveRef. compute subscribe (atom {:watches {} :value nil :unsub nil})))

(defonce ^ScheduledExecutorService timer
  (Executors/newSingleThreadScheduledExecutor
    (reify ThreadFactory (newThread [_ r] (doto (Thread. ^Runnable r "hypercurve-source-timer") (.setDaemon true))))))

(defn- watch-ref [r refresh]
  (let [k (gensym "hypercurve-source")]
    (add-watch r k (fn [_ _ _ _] (refresh)))
    #(remove-watch r k)))

(defn- every [ms refresh]
  (let [f (.scheduleWithFixedDelay timer ^Runnable refresh (long ms) (long ms) TimeUnit/MILLISECONDS)]
    #(.cancel f false)))

;; ---------------------------------------------------------------- memory

(defrecord MemSource [!data]
  Source
  (rows [_ query]
    (live-ref #(if (fn? query) (query @!data) (get @!data query))
              #(watch-ref !data %))))

(defn mem-source "A source over an atom; query is a key or a fn of the data." [!data] (->MemSource !data))

;; ---------------------------------------------------------------- polling

(defn poll-ref
  "Re-run query when version (an IRef) changes and, optionally, every
  :interval ms. Cost per change: one query, shared by every subscriber."
  [query-fn & {:keys [version interval]}]
  (live-ref query-fn
            (fn [refresh]
              (let [us (cond-> []
                         version (conj (watch-ref version refresh))
                         interval (conj (every interval refresh)))]
                #(run! (fn [u] (u)) us)))))

;; ---------------------------------------------------------------- jdbc

(defn- result-rows [^java.sql.ResultSet rs]
  (let [md (.getMetaData rs)
        n (.getColumnCount md)
        ks (mapv #(keyword (.toLowerCase (.getColumnLabel md (int %)))) (range 1 (inc n)))]
    (loop [acc (transient [])]
      (if (.next rs)
        (recur (conj! acc (persistent!
                            (reduce (fn [m i] (assoc! m (ks i) (.getObject rs (int (inc i)))))
                                    (transient {}) (range n)))))
        (persistent! acc)))))

(defn query
  "Run [sql & params] on a connection getter, returning rows as maps."
  [get-conn [sql & params]]
  (with-open [^java.sql.Connection c (get-conn)
              ps (.prepareStatement c sql)]
    (doseq [[i p] (map-indexed vector params)] (.setObject ps (int (inc i)) p))
    (if (.execute ps)
      (with-open [rs (.getResultSet ps)] (result-rows rs))
      (.getUpdateCount ps))))

(defrecord JdbcSource [get-conn !version interval]
  Source
  (rows [_ q]
    (poll-ref #(query get-conn q) :version !version :interval interval)))

(defn jdbc-source
  "A polling source over JDBC. Writes through `write!` bump its version, so
  every live query re-runs once; :interval polls for outside writes."
  [url & {:keys [interval]}]
  (->JdbcSource #(java.sql.DriverManager/getConnection url) (atom 0) interval))

(defn write!
  "Execute a write statement and notify live queries."
  [^JdbcSource src stmt]
  (let [r (query (:get-conn src) stmt)]
    (swap! (:!version src) inc)
    r))

;; ---------------------------------------------------------------- table events

(defn tables-of
  "Table names a SQL query reads (from/join), lower case."
  [sql]
  (set (map (comp clojure.string/lower-case second)
            (re-seq #"(?i)\b(?:from|join)\s+([a-z_][a-z0-9_.]*)" sql))))

(defrecord TableSource [query-fn subs]
  Source
  (rows [_ q]
    (let [tables (if (map? q) (set (:tables q)) (tables-of (first q)))
          q (if (map? q) (:query q) q)]
      (live-ref #(query-fn q)
                (fn [refresh]
                  (let [e {:tables tables :refresh refresh}]
                    (swap! subs conj e)
                    #(swap! subs disj e)))))))

(defn table-source
  "A source refreshed by table-change events from anywhere (CDC, Redis,
  Kafka, application code): (changed! src #{\"orders\"}) re-runs only the
  live queries that read those tables. query-fn: (fn [query]) -> rows."
  [query-fn]
  (->TableSource query-fn (atom #{})))

(defn changed!
  "Tables changed: refresh the live queries reading them."
  [^TableSource src tables]
  (let [tables (set (map clojure.string/lower-case tables))]
    (doseq [{t :tables refresh :refresh} @(:subs src)
            :when (some tables t)]
      (refresh))))
