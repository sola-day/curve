(ns curve.runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [curve.runtime :as rt]
            [curve.test :as ct]))

;; Hand-written program tables. The compiler produces the same shape.

(defn simple-ctor [!a]
  (rt/ctor
    {:name 'simple :nargs 0 :ret 2
     :nodes [{:op :const :v !a}
             {:op :watch :site :server :in [0] :readers #{:client}}
             {:op :call :site :client :f inc :in [1]}]}))

(deftest server-value-reaches-client
  (let [!a (atom 1)
        p (-> (ct/pair) (ct/mount! (simple-ctor !a)) ct/flush!)]
    (is (= 2 (rt/value (:client-root p) 2)))
    (is (rt/pending? (rt/value (:server-root p) 2)) "server does not compute client nodes")
    (ct/clear-wire! p)
    (reset! !a 10)
    (ct/flush! p)
    (is (= 11 (rt/value (:client-root p) 2)))
    (is (= #{[:s->c 1]} (ct/slots-changed p)))))

(def child-ctor
  (rt/ctor {:name 'child :nargs 1 :ret 1
            :nodes [{:op :arg :readers #{:client}}
                    {:op :call :site :client :f #(str "hello " %) :in [0]}]}))

(def other-ctor
  (rt/ctor {:name 'other :nargs 0 :ret 0
            :nodes [{:op :const :v "nobody"}]}))

(defn branch-ctor [!user]
  (rt/ctor
    {:name 'branch :nargs 0 :ret 2
     :nodes [{:op :const :v !user}
             {:op :watch :site :server :in [0] :readers #{:client :server}}
             {:op :branch :in [1] :ctors [child-ctor other-ctor] :args [1] :readers #{}}]}))

(deftest branch-switches-child-frames
  (let [!user (atom nil)
        p (-> (ct/pair) (ct/mount! (branch-ctor !user)) ct/flush!)
        c (:client-root p)]
    (is (= "nobody" (rt/value c 2)))
    (reset! !user "ada")
    (ct/flush! p)
    (is (= "hello ada" (rt/value c 2)))
    (is (= 1 (count (rt/kids-seq (get @(:children c) 2)))))
    (reset! !user "bob")
    (ct/flush! p)
    (is (= "hello bob" (rt/value c 2)) "same branch, arg updated")
    (reset! !user false)
    (ct/flush! p)
    (is (= "nobody" (rt/value c 2)))
    (is (= 2 (count (rt/frames (:client p)))) "old child unmounted")))

(def row-ctor
  (rt/ctor {:name 'row :nargs 1 :ret 1
            :nodes [{:op :arg :readers #{:client}}
                    {:op :call :site :client :f :name :in [0]}]}))

(defn table-ctor [!rows]
  (rt/ctor
    {:name 'table :nargs 0 :ret 2
     :nodes [{:op :const :v !rows}
             {:op :watch :site :server :in [0] :readers #{:client :server}}
             {:op :for :in [1] :key :id :ctor row-ctor :args [] :readers #{}}]}))

(deftest keyed-for-and-field-level-wire
  (let [!rows (atom (vec (for [i (range 50)] {:id i :name (str "p" i) :price i})))
        p (-> (ct/pair) (ct/mount! (table-ctor !rows)) ct/flush!)
        c (:client-root p)]
    (is (= (mapv #(str "p" %) (range 50)) (rt/value c 2)))
    (ct/clear-wire! p)
    (swap! !rows assoc-in [7 :name] "seven")
    (ct/flush! p)
    (is (= "seven" (nth (rt/value c 2) 7)))
    (testing "only the changed field crosses the wire"
      (is (= [{:dir :s->c
               :msg {:vals [[0 1 [:s {:degree 50 :grow 0 :shrink 0 :permutation {} :change {}
                                      :patch {7 [:m {:set {:name "seven"}}]}}]]]}
               :bytes nil}]
             (ct/wire-log p))))
    (testing "reorder and delete keep surviving child frames"
      (let [before (get @(:children c) 2)]
        (swap! !rows (fn [rs] (vec (reverse (remove #(= 3 (:id %)) rs)))))
        (ct/flush! p)
        (is (= 49 (count (rt/value c 2))))
        (is (identical? (get before 10) (get (get @(:children c) 2) 10)))))))

(defn call-ctor [!n]
  (rt/ctor
    {:name 'calls :nargs 0 :ret 2
     :nodes [{:op :const :v !n}
             {:op :call :site :server :f (fn [a] (fn [k] (swap! a + k))) :in [0] :readers #{:client}}
             {:op :watch :site :server :in [0] :readers #{:client}}]}))

(deftest remote-fn-call
  (let [!n (atom 0)
        p (-> (ct/pair) (ct/mount! (call-ctor !n)) ct/flush!)
        c (:client-root p)
        f (rt/value c 1)]
    (is (fn? f))
    (let [result (f 5)]
      (ct/flush! p)
      (is (= 5 @!n))
      (is (= 5 @result))
      (is (= 5 (rt/value c 2))))))

(deftest slot-authorization
  (let [!a (atom 1)
        p (-> (ct/pair) (ct/mount! (simple-ctor !a)) ct/flush!)]
    (testing "client cannot overwrite a server-owned cell"
      (rt/receive! (:server p) {:vals [[0 1 [:v 999]]]})
      (rt/run! (:server p))
      (is (= 1 (rt/value (:server-root p) 1))))
    (testing "client cannot call into an unknown frame"
      (rt/receive! (:server p) {:call [[1 42 0 []]]})
      (is (= {:ret [[1 false "frame not mounted"]]} (rt/take-message! (:server p)))))))

(deftest errors-propagate-as-values
  (let [boom (rt/ctor {:name 'boom :nargs 0 :ret 1
                       :nodes [{:op :const :v 0}
                               {:op :call :site :server :f #(/ 1 %) :in [0] :readers #{:client}}]})
        p (-> (ct/pair) (ct/mount! boom) ct/flush!)]
    (is (rt/failure? (rt/value (:server-root p) 1)))
    (is (rt/failure? (rt/value (:client-root p) 1)))))

(deftest propagation-is-glitch-free-and-minimal
  (let [!a (atom 1)
        calls (atom 0)
        diamond (rt/ctor
                  {:name 'diamond :nargs 0 :ret 4
                   :nodes [{:op :const :v !a}
                           {:op :watch :site :server :in [0]}
                           {:op :call :site :server :f inc :in [1]}
                           {:op :call :site :server :f dec :in [1]}
                           {:op :call :site :server :f (fn [x y] (swap! calls inc) [x y]) :in [2 3]}]})
        s (rt/peer :server)
        f (rt/mount-root! s diamond)]
    (rt/run! s)
    (is (= [2 0] (rt/value f 4)))
    (reset! !a 5)
    (rt/run! s)
    (is (= [6 4] (rt/value f 4)))
    (is (= 2 @calls) "one recompute per change, never an inconsistent pair")))
