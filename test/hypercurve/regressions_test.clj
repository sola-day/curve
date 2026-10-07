(ns hypercurve.regressions-test
  "Issues found building Hypercanvas."
  (:require [clojure.test :refer [deftest is testing]]
            [hypercurve.core :as r]
            [hypercurve.runtime :as rt]
            [hypercurve.test :as ct]))

(r/defn Greeting [user]
  [:p (or (r/server user) "nobody")])

(deftest server-of-an-argument-moves-it
  ;; the client root has no arguments (args-fn gives them to the server only)
  (let [p (ct/pair)
        client (rt/mount-root! (:client p) Greeting)
        server (rt/mount-root! (:server p) Greeting "ada")
        p (assoc p :client-root client :server-root server)]
    (ct/flush! p)
    (is (some #(= "ada" %) (seq (:vals client))) "the value crossed sites")))

(r/defn Panel [items]
  (let [!open (atom true)
        open (r/watch !open)]
    [:div (when open (r/for [x items] [:i x]))]))

(r/defn Page [n]
  (let [items (r/server (vec (range n)))]
    [:section (Panel items)]))

(deftest client-only-subtrees-stay-on-the-client
  (let [p (-> (ct/pair) (ct/mount! Page 3) ct/flush!)
        server-frames (count (rt/frames (:server p)))]
    (is (= 1 server-frames) "the server mounts only the root, not the panel")
    (is (empty? (filter #(= :c->s (:dir %)) (filter #(seq (:vals (:msg %))) (ct/wire-log p))))
        "nothing is uploaded")))
