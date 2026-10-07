(ns hypercurve.compiler-test
  (:require [clojure.test :refer [deftest is testing]]
            [hypercurve.core :as r]
            [hypercurve.runtime :as rt]
            [hypercurve.test :as ct]))

(def !db (atom {:users {1 "ada" 2 "bob"} :online #{1}}))

(r/defn Greeting [id]
  (let [nm (r/server (get-in (r/watch !db) [:users id]))]
    (str "hi " nm)))

(r/defn App []
  (let [n (r/server (count (:online (r/watch !db))))]
    (if (pos? n)
      (Greeting (r/server (first (:online (r/watch !db)))))
      "nobody online")))

(defn ret-value [f] (rt/value f (:ret (:ctor f))))

(deftest compiled-app-runs-across-peers
  (reset! !db {:users {1 "ada" 2 "bob"} :online #{1}})
  (let [p (-> (ct/pair) (ct/mount! App) ct/flush!)
        c (:client-root p)]
    (is (= "hi ada" (ret-value c)))
    (swap! !db assoc :online #{2})
    (ct/flush! p)
    (is (= "hi bob" (ret-value c)))
    (swap! !db assoc :online #{})
    (ct/flush! p)
    (is (= "nobody online" (ret-value c)))
    (testing "server never computes client-sited string building"
      (swap! !db assoc :online #{1})
      (ct/flush! p)
      (let [g (first (filter #(= 'hypercurve.compiler-test/Greeting (:name (:ctor %))) (rt/frames (:server p))))]
        (is (some? g))
        (is (rt/pending? (ret-value g)))))))

(r/defn Rows []
  (r/for [{:keys [id name]} (r/server (:rows (r/watch !db))) :by :id]
    (str id ":" name)))

(deftest compiled-for
  (reset! !db {:rows [{:id 1 :name "a"} {:id 2 :name "b"}]})
  (let [p (-> (ct/pair) (ct/mount! Rows) ct/flush!)]
    (is (= ["1:a" "2:b"] (ret-value (:client-root p))))
    (swap! !db update :rows conj {:id 3 :name "c"})
    (ct/flush! p)
    (is (= ["1:a" "2:b" "3:c"] (ret-value (:client-root p))))))

(r/defn Cased [k]
  (case k
    :a (r/server (str "server-a"))
    :b "plain-b"
    "other"))

(def !counter (atom 0))

(r/defn ^:server Counter []
  (let [n (r/watch !counter)
        double (* 2 n)]
    [n double]))

(deftest case-and-server-default
  (let [p (-> (ct/pair) (ct/mount! Cased :a) ct/flush!)]
    (is (= "server-a" (ret-value (:client-root p)))))
  (let [p (-> (ct/pair) (ct/mount! Cased :zz) ct/flush!)]
    (is (= "other" (ret-value (:client-root p)))))
  (testing "^:server root: unannotated code runs on the server"
    (reset! !counter 0)
    (let [p (-> (ct/pair) (ct/mount! Counter) ct/flush!)]
      (is (= [0 0] (ret-value (:server-root p))))
      (swap! !counter inc)
      (ct/flush! p)
      (is (= [1 2] (ret-value (:client-root p)))))))

(deftest lifting-keeps-tables-small
  (testing "a plain expression over reactive locals is one node"
    (let [nodes (:nodes Greeting)]
      ;; arg, server lookup (watch + get-in), str
      (is (<= (count nodes) 6) (pr-str (map :op nodes))))))

(deftest compile-errors-carry-location
  (let [e (try (binding [*ns* (the-ns 'hypercurve.compiler-test)]
                 (eval (read-string "(r/defn Bad [] (loop [i 0] (r/server i)))")))
               nil
               (catch Exception e (or (ex-cause e) e)))]
    (is (re-find #"reactive code inside `loop\*` is not supported" (ex-message e)))))

(deftest deterministic-tables
  (let [a (hypercurve.compiler/analyze 'x/Y '[a] '((str a (r/server (inc a)))))
        b (hypercurve.compiler/analyze 'x/Y '[a] '((str a (r/server (inc a)))))]
    (is (= (pr-str a) (pr-str b)))))
