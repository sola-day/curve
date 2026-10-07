(ns hypercurve.clock-test
  (:require [clojure.test :refer [deftest is]]
            [hypercurve.clock :as c]))

(deftest virtual-clock-fires-in-order
  (let [clk (c/virtual-clock)
        log (atom [])]
    (c/schedule! clk 20 #(swap! log conj :b))
    (c/schedule! clk 10 #(do (swap! log conj :a)
                             (c/schedule! clk 5 (fn [] (swap! log conj :a2)))))
    (let [cancel (c/schedule! clk 15 #(swap! log conj :never))]
      (cancel))
    (c/advance! clk 12)
    (is (= [:a] @log))
    (is (= 12 (c/now clk)))
    (c/advance! clk 100)
    (is (= [:a :a2 :b] @log))
    (is (= 0 (c/pending-timers clk)))))
