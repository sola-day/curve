(ns curve.codec-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [curve.codec :as c]
            [curve.delta :as d]))

(def gen-value
  (gen/recursive-gen
    (fn [inner] (gen/one-of [(gen/vector inner 0 4)
                             (gen/map gen/keyword-ns inner {:max-elements 4})
                             (gen/set gen/large-integer {:max-elements 4})]))
    (gen/one-of [gen/large-integer gen/double gen/string gen/keyword-ns gen/boolean
                 (gen/return nil) gen/uuid gen/symbol])))

(defn- roundtrip [m]
  (let [{:keys [encode decode]} (c/link)] (decode (encode m))))

(defn- same? [a b] (= (pr-str a) (pr-str b))) ; NaN-safe

(defn- delta-ok?
  "A delta survives the wire when it patches to the same value."
  [a x]
  (let [y (nth (first (:vals (roundtrip {:vals [[3 4 x]]}))) 2)]
    (same? (d/patch a x) (d/patch a y))))

(defspec values-roundtrip 500
  (prop/for-all [v gen-value]
    (same? [[0 1 [:v v]]] (:vals (roundtrip {:vals [[0 1 [:v v]]]})))))

(defspec deltas-roundtrip 300
  (prop/for-all [a gen-value b gen-value]
    (let [x (d/diff a b)]
      (or (nil? x) (delta-ok? a x)))))

(deftest keyword-interning-across-messages
  (let [{:keys [encode decode]} (c/link)
        m {:vals [[0 1 [:v {:name "a"}]]]}
        b1 (encode m) b2 (encode m)]
    (is (< (c/byte-count b2) (c/byte-count b1)))
    (is (= m (decode b1) (decode b2)))))

(deftest full-message-roundtrip
  (let [m {:decl [[1 0 2 :k] [2 1 0 "row-7"]]
           :vals [[1 2 [:p]] [2 0 [:e "boom"]] [2 1 [:f]]]
           :drop [5 6]
           :call [[1 2 3 ["x" 1]]]
           :ret [[1 true {:ok 1}] [2 false "nope"]]}]
    (is (= m (roundtrip m)))
    (is (= {:vals [1 2] :drop [3]} (c/combine {:vals [1] :drop [3]} {:vals [2]})))))

(deftest rejects-unknown-types-and-garbage
  (is (thrown? Exception (c/encode (c/state) {:vals [[0 0 [:v (Object.)]]]})))
  (is (thrown? Exception (c/decode (c/decoder-state) (byte-array [9 1 2 3])))))
