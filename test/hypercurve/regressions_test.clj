(ns hypercurve.regressions-test
  "Issues found building Hypercanvas."
  (:require [clojure.test :refer [deftest is testing]]
            [hypercurve.core :as r]
            [hypercurve.headless :as h]
            [hypercurve.mount :as mount]
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

(def !rows (atom [1 2 3]))

(r/defn ServerBranch [on]
  (let [v (r/server (if on (mapv inc (r/watch !rows)) []))]
    [:p (str (count v))]))

(deftest a-server-branch-value-reaches-the-client
  (let [p (-> (ct/pair) (ct/mount! ServerBranch true) ct/flush!)]
    (is (some #(= [2 3 4] %) (seq (:vals (:client-root p)))) "the branch's value crossed")))

(r/defn Count [xs] [:b (str (count xs))])

(r/defn PassedOn [on]
  ;; the branch's value is only passed to another component
  (let [v (r/server (if on (mapv inc (r/watch !rows)) []))]
    [:p (if on (Count v) "off")]))

(deftest a-branch-value-only-passed-on
  (let [root (h/root)
        hooks (mount/renderer (h/dom) root)
        p (-> (ct/pair :client-opts (dissoc hooks :mounter)) (ct/mount! PassedOn true) ct/flush!)]
    (is (= "3" (some-> (h/query root "b") h/text-content)) "Count received the server branch's value")))

(r/defn ManyLocals []
  (let [a1 1 a2 2 a3 3 a4 4 a5 5 a6 6 a7 7 a8 8 a9 9 a10 10 a11 11 a12 12
        a13 13 a14 14 a15 15 a16 16 a17 17 a18 18 a19 19 a20 20 a21 21 a22 22]
    [:p (str (+ a1 a2 a3 a4 a5 a6 a7 a8 a9 a10 a11 a12 a13 a14 a15 a16 a17 a18 a19 a20 a21 a22))]))

(deftest more-than-twenty-locals-in-one-expression
  (let [root (h/root)
        hooks (mount/renderer (h/dom) root)
        _ (-> (ct/pair :client-opts (dissoc hooks :mounter)) (ct/mount! ManyLocals) ct/flush!)]
    (is (= "253" (h/text-content (h/query root "p"))))))

(def !calls (atom []))

(r/defn Popup []
  (let [save! (r/server (fn [x] (swap! !calls conj x) nil))]
    [:button {:on-click (fn [_] (save! "clicked"))} "save"]))

(r/defn Opens []
  (let [!open (atom false) open (r/watch !open)]
    [:div
     [:a.open {:on-click (fn [_] (reset! !open true))} "open"]
     (when open (Popup))]))

(deftest calling-a-server-fn-before-its-frame-exists-on-the-server
  (reset! !calls [])
  (let [root (h/root)
        hooks (mount/renderer (h/dom) root)
        p (-> (ct/pair :client-opts (dissoc hooks :mounter)) (ct/mount! Opens) ct/flush!)]
    (h/fire! (h/query root "a.open") "click" {})
    (ct/run-client! p)
    ;; the popup is on screen, the server has not heard of it yet: click now
    (h/fire! (h/query root "button") "click" {})
    (ct/flush! p)
    (is (= ["clicked"] @!calls) "the call waited for the frame instead of being lost")))
