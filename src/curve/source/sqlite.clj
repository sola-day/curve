(ns curve.source.sqlite
  "SQLite source driven by the update hook (design §8.5): cost per write is
  O(affected queries), and for keyed single-table queries O(affected rows).

  All writes go through write! on the source's connection, whose update
  listener reports (type, table, rowid). After each statement:
    - queries that do not touch the changed table do nothing;
    - a query declared with :table and :key re-reads only the changed rows
      (update -> replace or drop the row, delete -> drop it);
    - inserts, and undeclared queries, re-run the query.
  Requires org.xerial/sqlite-jdbc on the classpath."
  (:require [clojure.string :as str]
            [curve.source :as src])
  (:import [org.sqlite SQLiteConnection SQLiteUpdateListener SQLiteUpdateListener$Type]
           [java.sql DriverManager]))

(defn- tables-of [sql]
  (set (map (comp str/lower-case second) (re-seq #"(?i)\b(?:from|join)\s+([a-z_][a-z0-9_]*)" sql))))

(defn- run [{:keys [conn lock stats]} stmt]
  (locking lock
    (swap! stats update :statements inc)
    (src/query (fn [] (proxy [java.sql.Connection] [] (close [])
                        (prepareStatement [sql] (.prepareStatement ^java.sql.Connection conn sql))))
               stmt)))

(defn- run-direct [{:keys [conn lock stats]} [sql & params]]
  (locking lock
    (swap! stats update :statements inc)
    (with-open [ps (.prepareStatement ^java.sql.Connection conn sql)]
      (doseq [[i p] (map-indexed vector params)] (.setObject ps (int (inc i)) p))
      (if (.execute ps)
        (with-open [rs (.getResultSet ps)] (#'src/result-rows rs))
        (.getUpdateCount ps)))))

(defn- full [s {:keys [sql params]}]
  (swap! (:stats s) update :full-queries inc)
  (run-direct s (into [sql] params)))

(defn- row [s {:keys [sql params key]} id]
  (swap! (:stats s) update :row-queries inc)
  (first (run-direct s (into [(str "select * from (" sql ") where " (name key) " = ?")] (concat params [id])))))

(defn- refresh-entry! [s {:keys [q cache refresh]} changes]
  (let [{:keys [key table]} q
        mine (filter #(= (str/lower-case (:table %)) table) changes)]
    (if (and key table (seq mine) (not-any? #(= :insert (:type %)) mine) (vector? @cache))
      (do (swap! cache (fn [rows]
                         (reduce (fn [rows {:keys [type rowid]}]
                                   (let [i (first (keep-indexed (fn [i r] (when (= rowid (get r key)) i)) rows))
                                         fresh (when (= :update type) (row s q rowid))]
                                     (cond (nil? i) rows
                                           fresh (assoc rows i fresh)
                                           :else (into (subvec rows 0 i) (subvec rows (inc i))))))
                                 rows mine)))
          (refresh))
      (do (reset! cache (full s q)) (refresh)))))

(defn- dispatch! [s]
  (let [changes (locking (:lock s) (let [c @(:pending s)] (reset! (:pending s) []) c))]
    (when (seq changes)
      (let [tables (set (map #(str/lower-case (:table %)) changes))]
        (doseq [e @(:subs s)
                :when (some tables (:tables e))]
          (refresh-entry! s e changes))))))

(defrecord SqliteSource [conn lock subs pending stats]
  src/Source
  (rows [s q]
    (let [q (if (map? q) q {:sql (first q) :params (vec (rest q))})
          q (cond-> q (:table q) (update :table str/lower-case))
          cache (atom ::none)]
      (src/live-ref
        (fn [] (let [v @cache] (if (= v ::none) (let [x (full s q)] (reset! cache x) x) v)))
        (fn [refresh]
          (let [e {:q q :tables (or (some-> (:table q) hash-set) (tables-of (:sql q))) :cache cache :refresh refresh}]
            (swap! subs conj e)
            (fn [] (swap! subs disj e) (reset! cache ::none))))))))

(defn source
  "Open a SQLite database at path with an update hook."
  [path]
  (let [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))
        s (->SqliteSource conn (Object.) (atom #{}) (atom []) (atom {:statements 0 :full-queries 0 :row-queries 0}))]
    (.addUpdateListener ^SQLiteConnection (.unwrap conn SQLiteConnection)
                        (reify SQLiteUpdateListener
                          (onUpdate [_ type _db table rowid]
                            (swap! (:pending s) conj {:type (condp = type
                                                              SQLiteUpdateListener$Type/INSERT :insert
                                                              SQLiteUpdateListener$Type/UPDATE :update
                                                              SQLiteUpdateListener$Type/DELETE :delete)
                                                      :table table :rowid rowid}))))
    s))

(defn write!
  "Execute a statement and notify affected live queries."
  [s stmt]
  (let [r (run-direct s stmt)]
    (dispatch! s)
    r))

(defn query "Run a read statement once (no subscription)." [s stmt] (run-direct s stmt))

(defn stats [s] @(:stats s))
