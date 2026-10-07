(ns hypercurve.ops-test
  (:require [clojure.test :refer [deftest is testing]]
            [hypercurve.core :as r]
            [hypercurve.session :as session]
            [hypercurve.source :as src]
            [hypercurve.source.sqlite :as sqlite]))

(defn tmp-db [] (.getPath (java.io.File/createTempFile "hypercurve-ops" ".db")))

(deftest sqlite-update-hook
  (let [db (sqlite/source (tmp-db))]
    (sqlite/write! db ["create table products (id integer primary key, name text)"])
    (sqlite/write! db ["create table users (id integer primary key, name text)"])
    (sqlite/write! db ["insert into products (name) values ('apple'), ('pear')"])
    (let [products (src/rows db {:sql "select id, name from products order by id" :table "products" :key :id})
          users (src/rows db ["select id, name from users"])
          seen (atom [])]
      (add-watch products ::p (fn [_ _ _ v] (swap! seen conj v)))
      (add-watch users ::u (fn [_ _ _ _]))
      (let [base (sqlite/stats db)]
        (testing "update re-reads one row"
          (sqlite/write! db ["update products set name = 'APPLE' where id = 1"])
          (is (= [{:id 1 :name "APPLE"} {:id 2 :name "pear"}] (last @seen)))
          (is (= 1 (- (:row-queries (sqlite/stats db)) (:row-queries base))))
          (is (= (:full-queries base) (:full-queries (sqlite/stats db))) "no full query, users query untouched"))
        (testing "delete drops the row without a query"
          (sqlite/write! db ["delete from products where id = 2"])
          (is (= [{:id 1 :name "APPLE"}] (last @seen)))
          (is (= (:full-queries base) (:full-queries (sqlite/stats db)))))
        (testing "insert re-runs the affected query only"
          (sqlite/write! db ["insert into products (name) values ('fig')"])
          (is (= ["APPLE" "fig"] (map :name (last @seen))))
          (is (= 1 (- (:full-queries (sqlite/stats db)) (:full-queries base)))))))))

(def !big (atom (vec (range 10))))

(r/defn Big [] [:ul (r/for [x (r/server (r/watch !big))] [:li x])])
(r/defn BigServer [] [:ul (r/for [x (r/server (r/watch !big))] [:li (r/server (str "#" x))])])

(defn wait-until [pred] (loop [n 0] (when (and (not (pred)) (< n 100)) (Thread/sleep 20) (recur (inc n)))))

(deftest node-budget-closes-only-that-session
  (reset! !big (vec (range 10)))
  (let [errors (atom [])
        small (session/start! BigServer [] {:send! (fn [_]) :budget {:max-nodes 50} :on-error #(swap! errors conj %)})
        roomy (session/start! BigServer [] {:send! (fn [_])})]
    (Thread/sleep 100)
    (reset! !big (vec (range 1000)))
    (wait-until #(not @(:open small)))
    (is (false? @(:open small)))
    (is (= :nodes (:budget (ex-data (first @errors)))))
    (is @(:open roomy))
    (is (pos? (:budget-trips (session/metrics-snapshot))))
    (session/close! roomy)))

(deftest bandwidth-budget-degrades-first
  (reset! !big (vec (range 10)))
  (let [sent (atom 0)
        s (session/start! Big [] {:send! (fn [bs] (swap! sent + (alength ^bytes bs)))
                                   :budget {:max-bytes-per-sec 200 :over-limit-secs 60}})]
    (Thread/sleep 100)
    (dotimes [i 20] (reset! !big (vec (range (* 10 i) (+ 50 (* 10 i))))) (Thread/sleep 5))
    (Thread/sleep 100)
    (is @(:open s) "over budget: delayed, not closed")
    (is (pos? (:degraded (session/metrics-snapshot))))
    (session/close! s)))
