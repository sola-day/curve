(ns curve.scenarios-test
  "The four hard scenarios of design §16, end to end on the JVM."
  (:require [clojure.test :refer [deftest is testing]]
            [curve.agg :as agg]
            [curve.canvas :as canvas]
            [curve.core :as r]
            [curve.dynamic :as dynamic]
            [curve.headless :as h]
            [curve.mount :as mount]
            [curve.runtime :as rt]
            [curve.shared :as shared]
            [curve.test :as ct]))

(defn render-with [dom container ctor & args]
  (let [hooks (mount/renderer dom container)
        p (apply ct/mount! (ct/pair :client-opts (dissoc hooks :mounter)) ctor args)]
    (ct/flush! p)
    p))

;; ---------------------------------------------------------------- whiteboard

(defrecord Recorder [log]
  canvas/Ctx
  (save! [_]) (restore! [_]) (translate! [_ _ _])
  (fill-rect! [_ x y w h c] (swap! log conj [:rect x y w h c]))
  (fill-circle! [_ x y r c] (swap! log conj [:circle x y r c]))
  (stroke-line! [_ _ _ _ _ _]) (fill-text! [_ s _ _ _] (swap! log conj [:text s]))
  (clear! [_ _ _] (reset! log [])))

(def board-key (keyword (str "board-" (System/nanoTime))))

(r/defn Board []
  (let [shapes (r/server (r/watch (shared/shared-atom board-key [{:id 1 :x 10 :y 10 :color "red"}])))
        move! (r/server (fn [id dx] (swap! (shared/shared-atom board-key []) (fn [ss] (mapv #(if (= id (:id %)) (update % :x + dx) %) ss)))))
        add! (r/server (fn [] (swap! (shared/shared-atom board-key []) #(conj % {:id (inc (count %)) :x 100 :y 10 :color "blue"}))))]
    [:g
     (r/for [{:keys [id x y color]} shapes :by :id]
       [:rect {:x x :y y :w 20 :h 20 :fill color :on-click (fn [_] (move! id 5))}])
     [:circle {:cx 200 :cy 200 :r 10 :fill "green" :on-click (fn [_] (add!))}]]))

(deftest collaborative-whiteboard
  (let [paints (atom 0)
        sa (canvas/scene #(swap! paints inc)) sb (canvas/scene (fn []))
        a (render-with (:dom sa) (:root sa) Board)
        b (render-with (:dom sb) (:root sb) Board)
        log (atom [])
        paint! (fn [s] (canvas/paint! (->Recorder log) (:root s) 300 300) @log)]
    (is (pos? @paints) "changes schedule a repaint")
    (is (= [[:rect 10.0 10.0 20.0 20.0 "red"] [:circle 200.0 200.0 10.0 "green"]] (paint! sb)))
    (testing "hit test dispatches to the shape; the other session sees it"
      (canvas/dispatch! (:root sa) "click" 15 15)
      (ct/flush! a) (ct/flush! b)
      (is (= [:rect 15.0 10.0 20.0 20.0 "red"] (first (paint! sb))))
      (canvas/dispatch! (:root sb) "click" 200 205)
      (ct/flush! b) (ct/flush! a)
      (is (= 3 (count (paint! sa))) "a new shape appeared everywhere"))))

;; ---------------------------------------------------------------- dataviz

(deftest incremental-bucketing
  (let [calls (atom 0)
        b (agg/bucketer {:init 0 :add (fn [a v] (swap! calls inc) (+ a v))} :t 1000 :v)
        s1 (vec (for [i (range 1000)] {:t (* i 10) :v 1}))
        r1 (b s1)
        s2 (into s1 (for [i (range 1000 1010)] {:t (* i 10) :v 1}))
        _ (reset! calls 0)
        r2 (b s2)]
    (is (= 10 (count r1)))
    (is (= 100 (get r1 0)))
    (is (= 10 @calls) "an append aggregates only the new points")
    (is (= 11 (count r2)))))

(def chart-calls (atom []))
(defn chart [_el props]
  (swap! chart-calls conj [:mount (count (:series props))])
  {:patch (fn [props d] (swap! chart-calls conj [:patch (first d)]))})

(def !series (atom []))

(r/defn Dashboard []
  (let [series (r/server (vec (sort-by key ((agg/bucketer :sum :t 1000 :v) (r/watch !series)))))]
    [:div (r/foreign chart {:series series})]))

(deftest streaming-chart-gets-deltas
  (reset! chart-calls [])
  (reset! !series (vec (for [i (range 100)] {:t (* i 100) :v i})))
  (let [p (render-with (h/dom) (h/root) Dashboard)]
    (is (= [[:mount 10]] @chart-calls))
    (swap! !series conj {:t 10000 :v 5})
    (ct/flush! p)
    (is (= [:patch :m] (last @chart-calls)) "the chart receives a structural delta, not a new series")))

;; ---------------------------------------------------------------- notebook

(def table (atom nil))

(r/defn Notebook []
  (let [cell (r/server (deref table))
        Cell (dynamic/load cell)]
    [:section (r/call Cell 21)]))

(deftest notebook-cells-compiled-at-run-time
  (reset! table (dynamic/compile-table 'nb/cell-1 '[x] '([:pre.out (str "x*2 = " (* 2 x))])))
  (dynamic/load @table)
  (let [root (h/root) p (render-with (h/dom) root Notebook)]
    (is (= "x*2 = 42" (h/text-content (h/query root "pre.out"))))
    (testing "a snippet cannot call anything outside the allow-list"
      (is (thrown-with-msg? Exception #"not allowed"
                            (let [t (dynamic/compile-table 'nb/evil '[] '((slurp "/etc/passwd")))]
                              (rt/run! (rt/peer :client))
                              (let [peer (rt/peer :client)]
                                (rt/mount-root! peer (dynamic/load t))
                                (rt/run! peer)
                                (let [v (some #(let [x (rt/value (first (rt/frames peer)) %)] (when (rt/failure? x) x)) (range 5))]
                                  (when v (throw (:error v)))))))))))
