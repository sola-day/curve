(ns curve.analysis-test
  (:require [clojure.test :refer [deftest is testing]]
            [curve.core :as r]
            [curve.runtime :as rt]
            [curve.test :as ct]))

(def calls (atom 0))
(defn expensive [x] (swap! calls inc) (* x x))

(r/defn Unused [a]
  (let [_ (inc a)
        used (r/server (str "a=" a))]
    used))

(deftest dead-pure-nodes-are-not-computed
  (let [inc-node (first (filter #(and (= :call (:op %)) (:dead %)) (:nodes Unused)))]
    (is (some? inc-node) "the unused (inc a) is marked dead")
    (let [p (-> (ct/pair) (ct/mount! Unused 2) ct/flush!)]
      (is (= "a=2" (rt/value (:client-root p) (:ret Unused)))))))

(r/defn KeepsImpure [a]
  (let [_ (expensive a)] "ok"))

(deftest unknown-calls-are-assumed-impure
  (reset! calls 0)
  (is (not-any? :dead (:nodes KeepsImpure)))
  (-> (ct/pair) (ct/mount! KeepsImpure 3) ct/flush!)
  (is (pos? @calls)))

(r/defn Fresh [a] [(inc a) a])

(deftest fresh-collections-compare-by-identity
  (is (some #(= :identical (:eq %)) (:nodes Fresh))))

(deftest mutation-in-a-value-is-a-compile-error
  (let [e (try (binding [*ns* (the-ns 'curve.analysis-test)]
                 (eval '(r/defn Bad [] (let [!a (atom 0)] (swap! !a inc) [:p "x"]))))
               nil
               (catch Exception e (or (ex-cause e) e)))]
    (is (re-find #"`swap!` inside a reactive value" (str (ex-message e))))))

(deftest mutation-in-handlers-and-effects-is-fine
  (is (some? (binding [*ns* (the-ns 'curve.analysis-test)]
               (eval '(r/defn Good [] (let [!a (atom 0)]
                                        (r/effect (reset! !a 1))
                                        [:button {:on-click (fn [_] (swap! !a inc))} "+"])))))))
