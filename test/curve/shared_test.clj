(ns curve.shared-test
  (:require [clojure.test :refer [deftest is testing]]
            [curve.core :as r]
            [curve.runtime :as rt]
            [curve.session :as session]
            [curve.shared :as shared]
            [curve.source :as src]
            [curve.test :as ct]))

(def !table (atom (vec (for [i (range 100)] {:id i :name (str "p" i)}))))
(def computations (atom 0))
(defn counted [rs] (swap! computations inc) rs)

(r/defn Table []
  (let [rows (r/shared ::table (counted (r/watch !table)))]
    [:ul (r/for [{:keys [name]} rows :by :id] [:li name])]))

(defn- settle! [p] (shared/await-idle) (ct/flush! p) (shared/await-idle) (ct/flush! p))

(defn- wait-until [pred]
  (loop [n 0] (when (and (not (pred)) (< n 200)) (Thread/sleep 25) (recur (inc n)))))

(deftest shared-value-computed-once
  (wait-until #(zero? (shared/instance-count)))
  (reset! computations 0)
  (let [a (-> (ct/pair) (ct/mount! Table)) b (-> (ct/pair) (ct/mount! Table))]
    (settle! a) (settle! b)
    (is (= 1 @computations))
    (swap! !table assoc-in [3 :name] "three")
    (settle! a) (settle! b)
    (is (= 2 @computations) "one recompute for both sessions")
    (rt/unmount-frame! (:server-root a)) (rt/unmount-frame! (:server-root b))
    (shared/await-idle)
    (is (zero? (shared/instance-count)) "released when the last follower leaves")))

(deftest encode-once-across-sessions
  (reset! !table (vec (for [i (range 1000)] {:id i :name (str "p" i)})))
  (let [n 50
        sent (atom 0)
        sessions (doall (for [_ (range n)]
                          (session/start! Table [] {:send! (fn [_] (swap! sent inc))})))
        shared-node (fn [f] (first (keep (fn [[i nd]] (when (= :shared (:op nd)) i))
                                           (map-indexed vector (:nodes (:ctor f))))))
        cursor (fn [s] (session/call s (fn [] (when-let [f @(:root s)]
                                                (let [x (aget ^objects (:sent f) (shared-node f))]
                                                  (when (vector? x) (second x)))))))
        synced? (fn [v] (every? #(= v (cursor %)) sessions))]
    (wait-until #(synced? 1))
    (let [encodes-before (:encodes @shared/stats)]
      (swap! !table assoc-in [500 :name] "changed")
      (wait-until #(synced? 2))
      (is (= 1 (- (:encodes @shared/stats) encodes-before))
          "one delta encoding serves every session"))
    (testing "each session holds only a cursor, not a copy"
      (let [vals (for [s sessions]
                   (session/call s (fn [] (let [f @(:root s) i (shared-node f)]
                                            [(rt/value f i) (aget ^objects (:sent f) i)]))))]
        (is (every? #(identical? (ffirst vals) (first %)) vals) "same persistent value in every session")
        (is (every? #(= ::rt/version (first (second %))) vals))))
    (run! session/close! sessions)
    (wait-until #(zero? (shared/instance-count)))))

(deftest jdbc-polling-source
  (let [f (java.io.File/createTempFile "curve" ".db")
        db (src/jdbc-source (str "jdbc:sqlite:" (.getPath f)))]
    (src/write! db ["create table products (id integer primary key, name text)"])
    (src/write! db ["insert into products (id, name) values (1, 'apple')"])
    (let [ref (src/rows db ["select id, name from products order by id"])
          seen (atom [])]
      (add-watch ref ::t (fn [_ _ _ v] (swap! seen conj v)))
      (is (= [{:id 1 :name "apple"}] @ref))
      (src/write! db ["insert into products (id, name) values (?, ?)" 2 "pear"])
      (is (= [{:id 1 :name "apple"} {:id 2 :name "pear"}] (last @seen)))
      (remove-watch ref ::t)
      (src/write! db ["delete from products"])
      (is (= 1 (count @seen)) "no work after the last watcher left"))))
