(ns curve.dev-test
  (:require [clojure.test :refer [deftest is]]
            [curve.core :as r]
            [curve.dev :as dev]
            [curve.runtime :as rt]
            [curve.test :as ct]))

(def !price (atom 10))

(r/defn Total [qty]
  (let [price (r/server (r/watch !price))
        total (* qty price)]
    [:p (str "total " total)]))

(deftest inspect-and-why
  (let [p (-> (ct/pair) (ct/mount! Total 3) ct/flush!)
        c (:client-root p)
        tree (dev/inspect (:client p))]
    (is (= 'curve.dev-test/Total (:ctor tree)))
    (is (some #(= 30 (:value %)) (:nodes tree)))
    (dev/trace!)
    (try
      (reset! !price 11)
      (ct/flush! p)
      (let [total-node (some #(when (= 33 (rt/value c %)) %) (range (count (:nodes Total))))
            chain (dev/why c total-node)]
        (is (some? total-node))
        (is (= [:remote :server] (last chain)) "the total changed because the server sent a new price")
        (is (seq (dev/log))))
      (finally (dev/untrace!)))))

(deftest diagnostics
  (is (vector? (:boundary (dev/diagnostics)))))
