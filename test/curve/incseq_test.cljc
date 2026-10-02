(ns curve.incseq-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [curve.incseq :as s]
            [curve.delta :as d]))

(def small-vec (gen/vector (gen/choose 0 6) 0 8))

(defspec diff-by-patches-to-target 300
  (prop/for-all [a small-vec b small-vec]
    (= b (s/patch a (s/diff-by identity a b)))))

(defspec positional-patches-to-target 200
  (prop/for-all [a small-vec b small-vec]
    (= b (s/patch a (s/diff-positional a b)))))

(defspec combine-equals-sequential 300
  (prop/for-all [a small-vec b small-vec c small-vec]
    (let [d1 (s/diff-by identity a b) d2 (s/diff-by identity b c)]
      (= c (s/patch a (s/combine d1 d2))))))

(defspec combine-associative 300
  (prop/for-all [a small-vec b small-vec c small-vec e small-vec]
    (let [d1 (s/diff-by identity a b) d2 (s/diff-by identity b c) d3 (s/diff-by identity c e)]
      (= (s/combine (s/combine d1 d2) d3)
         (s/combine d1 (s/combine d2 d3))))))

(defspec combine-identity 200
  (prop/for-all [a small-vec b small-vec]
    (let [d1 (s/diff-by identity a b)]
      (and (= d1 (s/combine (s/empty-diff (count a)) d1))
           (= d1 (s/combine d1 (s/empty-diff (count b))))))))

(deftest permutation-only
  (let [dd (s/diff-by identity [:a :b :c] [:c :a :b])]
    (is (= 0 (:grow dd) (:shrink dd)))
    (is (empty? (:change dd)))
    (is (= [:c :a :b] (s/patch [:a :b :c] dd)))))

;; ---- generic deltas ----

(def gen-row
  (gen/hash-map :name (gen/elements ["a" "b" "c"]) :price (gen/choose 0 3)))

(def gen-rows
  (gen/fmap (fn [rows] (vec (map-indexed (fn [i r] (assoc r :id (* 10 i))) rows)))
            (gen/vector gen-row 0 6)))

(def gen-value
  (gen/recursive-gen
    (fn [inner] (gen/one-of [(gen/vector inner 0 4)
                             (gen/map gen/keyword inner {:max-elements 4})
                             (gen/set gen/small-integer {:max-elements 4})]))
    (gen/one-of [gen/small-integer gen/string-alphanumeric gen/keyword (gen/return nil)])))

(defspec delta-roundtrip 500
  (prop/for-all [a gen-value b gen-value]
    (= b (if-let [x (d/diff a b)] (d/patch a x) a))))

(defspec delta-rows-roundtrip 300
  (prop/for-all [a gen-rows b gen-rows]
    (= b (if-let [x (d/diff a b)] (d/patch a x) a))))

(deftest field-level-row-update
  (let [rows (vec (for [i (range 100)] {:id i :name (str "p" i) :price i}))
        rows' (assoc-in rows [42 :name] "changed")
        x (d/diff rows rows')]
    (is (= [:s {:degree 100 :grow 0 :shrink 0 :permutation {} :change {}
                :patch {42 [:m {:set {:name "changed"}}]}}]
           x))
    (is (= rows' (d/patch rows x)))))
