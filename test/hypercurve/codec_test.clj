(ns hypercurve.codec-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.walk]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hypercurve.codec :as c]
            [hypercurve.delta :as d]))

(def gen-value
  (gen/recursive-gen
    (fn [inner] (gen/one-of [(gen/vector inner 0 4)
                             (gen/map gen/keyword-ns inner {:max-elements 4})
                             (gen/set gen/large-integer {:max-elements 4})]))
    (gen/one-of [gen/large-integer gen/double gen/string gen/keyword-ns gen/boolean
                 (gen/return nil) gen/uuid gen/symbol])))

(defn- roundtrip [m]
  (let [{:keys [encode decode]} (c/link)] (decode (encode m))))

(defn- no-nan [x]
  (clojure.walk/postwalk #(if (and (double? %) (Double/isNaN %)) ::nan %) x))

(defn- same? [a b] (= (no-nan a) (no-nan b)))

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

(deftest columnar-records
  (let [rows (vec (for [i (range 100)] {:id i :name (str "p" i) :price (* 1.5 i)}))
        {:keys [encode decode]} (c/link)
        bs (encode {:vals [[0 1 [:v rows]]]})]
    (is (= rows (second (nth (first (:vals (decode bs))) 2))))
    ;; id ~2, name ~5, price 9 bytes + 2 for the shape reference
    (is (< (/ (c/byte-count bs) 100) 20) "keys are sent once per shape")))

(deftest typed-arrays
  (let [{:keys [encode decode]} (c/link)
        xs (double-array [1.5 -2.25 3e10])
        ys (int-array [1 -2 3])
        m (decode (encode {:vals [[0 1 [:v xs]] [0 2 [:v ys]]]}))
        [[_ _ [_ xs']] [_ _ [_ ys']]] (:vals m)]
    (is (= (vec xs) (vec xs')))
    (is (= (vec ys) (vec ys')))
    (is (= "[D" (.getName (class xs'))))))

(deftest acks
  (let [{:keys [encode decode]} (c/link)]
    (is (= {:ack 3 :vals [[0 1 [:v 1]]]} (decode (encode {:ack 3 :vals [[0 1 [:v 1]]]}))))
    (is (= {:ack 5} (c/combine {:ack 2} {:ack 3})))))
