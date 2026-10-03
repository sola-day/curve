(ns curve.collab-test
  (:require [clojure.test :refer [deftest is testing]]
            [curve.core :as r]
            [curve.headless :as h]
            [curve.mount :as mount]
            [curve.presence :as presence]
            [curve.runtime :as rt]
            [curve.shared :as shared]
            [curve.test :as ct]))

(defn render [ctor & args]
  (let [root (h/root)
        hooks (mount/renderer (h/dom) root)
        p (apply ct/mount! (ct/pair :client-opts (dissoc hooks :mounter)) ctor args)]
    (ct/flush! p)
    (assoc p :dom root)))

(r/defn Room [user]
  (r/server (r/effect (presence/join! ::lobby {:name user})))
  (let [doc (r/server (r/watch (shared/shared-atom ::doc "")))
        edit! (r/server (fn [s] (reset! (shared/shared-atom ::doc "") s)))]
    [:div
     [:ul (r/for [n (r/server (sort (map :name (vals (r/watch (presence/members ::lobby))))))] [:li n])]
     [:input {:value doc :on-input (fn [e] (edit! (r/event-value e)))}]]))

(deftest presence-and-shared-state
  (let [a (render Room "ada") b (render Room "bob")
        names #(mapv h/text-content (h/query-all (:dom %) "li"))]
    (ct/flush! a)
    (is (= ["ada" "bob"] (names a)))
    (h/input! (h/query (:dom a) "input") "hello") (ct/flush! a) (ct/flush! b)
    (is (= "hello" (h/value (h/query (:dom b) "input"))))
    (rt/unmount-frame! (:server-root b))
    (ct/flush! a)
    (is (= ["ada"] (names a)) "leaving removes the member")))

(def !todos (atom ["a"]))
(def gate (atom nil))

(r/defn Todos []
  (let [!view (r/projection (r/server (r/watch !todos)))
        view (r/watch !view)
        add! (r/mutation (fn [t] (when (= t "boom") (throw (ex-info "rejected" {})))
                           (swap! !todos conj t))
                         {:optimistic [!view #(conj % "…")]})]
    [:div
     [:ul (r/for [t view] [:li t])]
     [:button.add {:on-click (fn [_] (add! "b"))} "add"]
     [:button.bad {:on-click (fn [_] (add! "boom"))} "bad"]]))

(deftest optimistic-updates
  (reset! !todos ["a"])
  (let [{:keys [dom client server] :as p} (render Todos)
        items #(mapv h/text-content (h/query-all dom "li"))]
    (is (= ["a"] (items)))
    (testing "applied at once, replaced by the server's value"
      (h/fire! (h/query dom "button.add") "click" {})
      (ct/run-client! p)
      (is (= ["a" "…"] (items)) "before the server answered")
      (ct/flush! p)
      (is (= ["a" "b"] (items))))
    (testing "rolled back on failure"
      (h/fire! (h/query dom "button.bad") "click" {})
      (ct/run-client! p)
      (is (= ["a" "b" "…"] (items)))
      (ct/flush! p)
      (is (= ["a" "b"] (items))))))
