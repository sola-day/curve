(ns curve.tooling-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [curve.core :as r]
            [curve.dev :as dev]
            [curve.headless :as h]
            [curve.mount :as mount]
            [curve.runtime :as rt]
            [curve.test :as ct]))

(def !n (atom 0))
(defn check [n] (if (= n 3) (throw (ex-info "three is not allowed" {})) n))

(r/defn Counter []
  [:p (r/server (check (r/watch !n)))])

(defn render []
  (let [root (h/root) hooks (mount/renderer (h/dom) root)
        p (ct/mount! (ct/pair :client-opts (dissoc hooks :mounter)) Counter)]
    (ct/flush! p)
    (assoc p :dom root)))

(deftest cross-site-errors-name-the-server-location
  (reset! !n 3)
  (let [p (render)
        v (some #(let [x (rt/value (:client-root p) %)] (when (rt/failure? x) x)) (range 3))]
    (is (= "three is not allowed" (ex-message (:error v))))
    (is (re-find #"^server curve.tooling-test/Counter:\d+$" (:at (ex-data (:error v)))))))

(deftest wire-stats-replay-and-generated-tests
  (reset! !n 0)
  (let [p (render)]
    (doseq [i [1 2 4]] (reset! !n i) (ct/flush! p))
    (let [log (ct/wire-log p)
          stats (dev/wire-stats log)]
      (is (= 4 (:count (val (first (filter #(= :s->c (first (key %))) stats))))))
      (testing "replay to any point"
        (is (= "<p>0</p>" (:html (dev/replay Counter log 1))))
        (is (= "<p>2</p>" (:html (dev/replay Counter log 3))))
        (is (= (h/html (:dom p)) (:html (dev/replay Counter log)))))
      (testing "a generated test passes"
        (let [src (dev/log->test 'replayed-counter 'curve.tooling-test/Counter log)]
          (is (re-find #"deftest\s+replayed-counter" src))
          (binding [*ns* (the-ns 'curve.tooling-test)]
            (let [t (eval (read-string src))]
              (is (= :ok (do (t) :ok))))))))))
