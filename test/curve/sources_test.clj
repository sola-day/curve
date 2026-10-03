(ns curve.sources-test
  (:require [clojure.test :refer [deftest is testing]]
            [curve.batch :as batch]
            [curve.source :as src]
            [curve.source.datomic :as datomic]
            [curve.source.postgres :as pg]))

(deftest dataloader-batches-concurrent-loads
  (let [calls (atom [])
        l (batch/loader (fn [ids] (swap! calls conj ids) (into {} (for [i ids] [i (* 10 i)]))) :window-ms 20)
        results (mapv deref (mapv (fn [i] (future @(batch/load l (mod i 5)))) (range 50)))]
    (is (= (map #(* 10 (mod % 5)) (range 50)) results))
    (is (= 1 (batch/batch-count l)) "50 loads from many threads, one batch")
    (is (= #{0 1 2 3 4} (first @calls)))))

(deftest table-events
  (let [queries (atom 0)
        s (src/table-source (fn [_] (swap! queries inc) [@queries]))
        orders (src/rows s ["select * from orders o join items i on i.order_id = o.id"])
        users (src/rows s ["select * from users"])]
    (add-watch orders ::o (fn [_ _ _ _])) (add-watch users ::u (fn [_ _ _ _]))
    (is (= 2 @queries))
    (src/changed! s #{"items"})
    (is (= 3 @queries) "only the query reading items re-ran")
    (src/changed! s #{"payments"})
    (is (= 3 @queries))))

(deftest wal2json
  (is (= {:action :insert :table "orders" :schema "public"}
         (pg/parse-wal2json "{\"action\":\"I\",\"schema\":\"public\",\"table\":\"orders\",\"columns\":[]}")))
  (is (nil? (pg/parse-wal2json "{\"action\":\"B\"}")))
  (let [hits (atom 0)
        s (src/table-source (fn [_] (swap! hits inc)))
        r (src/rows s ["select * from orders"])]
    (add-watch r ::w (fn [_ _ _ _]))
    (pg/apply-change! s "{\"action\":\"U\",\"schema\":\"public\",\"table\":\"Orders\"}")
    (is (= 2 @hits))))

(deftest datomic-adapter-with-stand-in
  (let [db (atom {:n 0})
        q (java.util.concurrent.LinkedBlockingQueue.)
        idents {1 :order/total 2 :user/name}
        api {:q (fn [_ dbv] [[(:n dbv)]])
             :db (fn [_] @db)
             :tx-report-queue (fn [_] q)
             :ident (fn [_ id] (idents id))}
        s (datomic/source :conn api)
        r (src/rows s '[:find ?t :where [?o :order/total ?t]])
        seen (atom [])]
    (is (= #{:order/total} (datomic/query-attrs '[:find ?t :where [?o :order/total ?t]])))
    (add-watch r ::w (fn [_ _ _ v] (swap! seen conj v)))
    (swap! db update :n inc)
    (.put q {:db-after @db :tx-data [{:a 2}]})
    (Thread/sleep 50)
    (is (empty? @seen) "a transaction on other attributes does nothing")
    (.put q {:db-after @db :tx-data [{:a 1}]})
    (Thread/sleep 50)
    (is (= [[[1]]] @seen))))
