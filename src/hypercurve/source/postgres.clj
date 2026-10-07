(ns hypercurve.source.postgres
  "Postgres change data capture (design §8.5) with wal2json (format v2).

  One consumer per server instance reads the logical replication stream and
  turns each change into a table event on a hypercurve.source/table-source, so
  live queries reading that table re-run once. Behind several instances,
  run one consumer and broadcast the table names (e.g. via Redis) to the
  others' changed!.

  parse-wal2json is pure and tested. stream! uses the pgjdbc replication API
  (org.postgresql) looked up at runtime; it has not been run against a live
  database in this repository."
  (:require [clojure.string :as str]
            [hypercurve.source :as src]))

(defn parse-wal2json
  "Table name of a wal2json v2 message (a JSON string), or nil for
  begin/commit and messages without a table."
  [^String json]
  (when-let [[_ action] (re-find #"\"action\"\s*:\s*\"([A-Z])\"" json)]
    (when (contains? #{"I" "U" "D" "T"} action)
      (let [[_ schema] (re-find #"\"schema\"\s*:\s*\"([^\"]+)\"" json)
            [_ table] (re-find #"\"table\"\s*:\s*\"([^\"]+)\"" json)]
        (when table
          {:action ({"I" :insert "U" :update "D" :delete "T" :truncate} action)
           :table (str/lower-case table)
           :schema schema})))))

(defn apply-change!
  "Feed one wal2json message to a table-source."
  [table-source json]
  (when-let [{:keys [table]} (parse-wal2json json)]
    (src/changed! table-source #{table})))

(defn stream!
  "Consume slot (wal2json, format-version 2) on a replication connection and
  notify table-source. Returns a stop fn."
  [^java.sql.Connection replication-conn slot table-source]
  (let [pg (.unwrap replication-conn (Class/forName "org.postgresql.PGConnection"))
        api (.getReplicationAPI pg)
        stream (-> api .replicationStream .logical
                   (.withSlotName slot)
                   (.withSlotOption "format-version" 2)
                   .start)
        running (atom true)]
    (doto (Thread. ^Runnable
                   (fn []
                     (while @running
                       (if-let [^java.nio.ByteBuffer buf (.readPending stream)]
                         (let [arr (byte-array (.remaining buf))]
                           (.get buf arr)
                           (apply-change! table-source (String. arr "UTF-8"))
                           (.setAppliedLSN stream (.getLastReceiveLSN stream))
                           (.setFlushedLSN stream (.getLastReceiveLSN stream)))
                         (Thread/sleep 10))))
                   "hypercurve-pg-cdc")
      (.setDaemon true) (.start))
    (fn [] (reset! running false) (.close stream))))
