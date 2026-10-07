(ns hypercurve.dev-test
  (:require [clojure.test :refer [deftest is]]
            [hypercurve.core :as r]
            [hypercurve.dev :as dev]
            [hypercurve.runtime :as rt]
            [hypercurve.test :as ct]))

(def !price (atom 10))

(r/defn Total [qty]
  (let [price (r/server (r/watch !price))
        total (* qty price)]
    [:p (str "total " total)]))

(deftest inspect-and-why
  (let [p (-> (ct/pair) (ct/mount! Total 3) ct/flush!)
        c (:client-root p)
        tree (dev/inspect (:client p))]
    (is (= 'hypercurve.dev-test/Total (:ctor tree)))
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
