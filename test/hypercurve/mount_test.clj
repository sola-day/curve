(ns hypercurve.mount-test
  (:require [clojure.test :refer [deftest is testing]]
            [hypercurve.core :as r]
            [hypercurve.headless :as h]
            [hypercurve.mount :as mount]
            [hypercurve.runtime :as rt]
            [hypercurve.test :as ct]))

(def !state (atom nil))

(r/defn Item [{:keys [title id]}]
  [:li {:class (when (= id 2) "two")} title])

(r/defn TodoApp []
  (let [{:keys [items user]} (r/server (r/watch !state))]
    [:div.app
     [:h1 "Hello " user "!"]
     (if user [:p "logged in"] [:p.anon "anonymous"])
     [:ul (r/for [it items :by :id] (Item it))]
     [:button {:on-click (r/server (fn [_] (swap! !state update :items conj {:id 3 :title "c"})))} "add"]]))

(defn render-pair [ctor & args]
  (let [root (h/root)
        hooks (mount/renderer (h/dom) root)
        p (apply ct/mount! (ct/pair :client-opts (dissoc hooks :mounter)) ctor args)]
    (ct/flush! p)
    (assoc p :dom-root root :mounter (:mounter hooks))))

(deftest render-update-and-events
  (reset! !state {:items [{:id 1 :title "a"} {:id 2 :title "b"}] :user "ada"})
  (let [p (render-pair TodoApp)
        root (:dom-root p)]
    (is (= "<div class=\"app\"><h1>Hello ada!</h1><p>logged in</p><ul><li>a</li><li class=\"two\">b</li></ul><button>add</button></div>"
           (h/html root)))
    (testing "branch switch"
      (swap! !state assoc :user nil)
      (ct/flush! p)
      (is (= "anonymous" (h/text-content (h/query root "p.anon"))))
      (is (= "Hello !" (h/text-content (h/query root "h1")))))
    (testing "event calls a server closure; list grows"
      (h/fire! (h/query root "button") "click" {})
      (ct/flush! p)
      (is (= ["a" "b" "c"] (map h/text-content (h/query-all root "li")))))
    (testing "reorder and remove keep DOM nodes"
      (let [li-a (h/query root "li")]
        (swap! !state update :items (fn [xs] (vec (reverse (remove #(= 2 (:id %)) xs)))))
        (ct/flush! p)
        (is (= ["c" "a"] (map h/text-content (h/query-all root "li"))))
        (is (identical? li-a (second (h/query-all root "li"))))))
    (testing "unmount removes everything"
      (rt/unmount-frame! (:client-root p))
      (is (= "" (h/html root))))))

(r/defn Nested [xs]
  [:div
   (let [n (count xs)]
     [:span "count " n])
   (r/for [x xs] (if (odd? x) [:b x] (str x)))])

(deftest blocks-and-root-level-anchors
  (let [p (render-pair Nested [1 2 3])]
    (is (= "<div><span>count 3</span><b>1</b>2<b>3</b></div>" (h/html (:dom-root p))))))

(r/defn Plain [x] (str "x=" x))

(deftest non-hiccup-ctor-renders-text
  (let [p (render-pair Plain 5)]
    (is (= "x=5" (h/html (:dom-root p))))))

(deftest server-skips-client-only-subtrees
  (reset! !state {:items (vec (for [i (range 200)] {:id i :title (str i)})) :user "ada"})
  (let [p (render-pair TodoApp)]
    (is (= 200 (count (h/query-all (:dom-root p) "li"))))
    (is (< (count (rt/frames (:server p))) 10)
        "rows have no server code, so the server does not mirror them")
    (testing "a server closure in a skipped subtree's parent still works"
      (h/fire! (h/query (:dom-root p) "button") "click" {})
      (ct/flush! p)
      (is (= 201 (count (h/query-all (:dom-root p) "li")))))))

(def !perm (atom []))
(r/defn Perm [] [:ul (r/for [x (r/watch !perm) :by identity] [:li x])])

(deftest random-permutations-keep-dom-order
  (reset! !perm (vec (range 30)))
  (let [p (render-pair Perm)
        lis #(mapv (comp parse-long h/text-content) (h/query-all (:dom-root p) "li"))
        rnd (java.util.Random. 42)]
    (dotimes [_ 200]
      (let [v @!perm
            v (if (zero? (.nextInt rnd 3))
                (let [i (.nextInt rnd 30) j (.nextInt rnd 30)] (assoc v i (v j) j (v i)))
                (vec (sort-by (fn [_] (.nextInt rnd 1000)) v)))]
        (reset! !perm v)
        (ct/flush! p)
        (is (= v (lis)))))))
