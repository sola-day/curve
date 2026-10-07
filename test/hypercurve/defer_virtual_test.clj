(ns hypercurve.defer-virtual-test
  (:require [clojure.test :refer [deftest is testing]]
            [hypercurve.core :as r]
            [hypercurve.headless :as h]
            [hypercurve.mount :as mount]
            [hypercurve.runtime :as rt]
            [hypercurve.test :as ct]
            [hypercurve.virtual :as v]))

(defn render [ctor & args]
  (let [root (h/root) hooks (mount/renderer (h/dom) root)
        p (apply ct/mount! (ct/pair :client-opts (dissoc hooks :mounter)) ctor args)]
    (ct/flush! p)
    (assoc p :dom root)))

;; ---------------------------------------------------------------- r/defer :visible

(def queries (atom 0))
(defn load-comments [] (swap! queries inc) ["nice" "great"])

(r/defn Page []
  [:main
   [:h1 "product"]
   (r/defer {:when :visible :margin 200 :placeholder [:p.wait "comments…"]}
     [:ul.comments (r/for [c (r/server (load-comments))] [:li c])])])

(deftest defer-visible
  (let [observed (atom [])
        old @rt/visible-observer]
    (reset! queries 0)
    (reset! rt/visible-observer (fn [el opts] (swap! observed conj [el opts]) (fn [] (swap! observed conj :stopped))))
    (try
      (let [{:keys [dom] :as p} (render Page)]
        (testing "not visible yet: placeholder only, nothing queried"
          (is (some? (h/query dom "p.wait")))
          (is (nil? (h/query dom "ul.comments")))
          (is (zero? @queries))
          (is (= 1 (count @observed)))
          (is (= "hypercurve-defer" (get @(:attrs (ffirst @observed)) "class")) "the placeholder element is watched")
          (is (= 200 (:margin (second (first @observed))))))
        (testing "scrolled into view: mounted, queried once, watching stopped"
          ((:on-visible (second (first @observed))))
          (ct/flush! p)
          (is (= ["nice" "great"] (mapv h/text-content (h/query-all dom "li"))))
          (is (nil? (h/query dom "p.wait")))
          (is (= 1 @queries))
          (is (= :stopped (last @observed)))))
      (finally (reset! rt/visible-observer old)))))

(deftest defer-visible-on-the-server-renders-everything
  (reset! queries 0)
  (let [{:keys [dom]} (render Page)]
    (is (= 2 (count (h/query-all dom "li"))) "JVM default: visible at once (SSR shows the content)")))

;; ---------------------------------------------------------------- variable heights

(deftest fenwick-heights
  (let [hs (v/heights 1000 10)]
    (is (= 10000.0 (v/total-height hs)))
    (is (= 25 (v/index-at hs 250)))
    (v/set-height! hs 3 110)
    (is (= 10100.0 (v/total-height hs)))
    (is (= 140.0 (v/prefix hs 4)))
    (is (= 3 (v/index-at hs 100)))
    (is (= 4 (v/index-at hs 140)))
    (testing "matches a linear scan on random heights"
      (let [rnd (java.util.Random. 7) n 500 hs (v/heights n 20)
            hts (vec (for [i (range n)] (+ 5 (.nextInt rnd 80))))]
        (doseq [[i x] (map-indexed vector hts)] (v/set-height! hs i x))
        (doseq [y (range 0 (reduce + hts) 37)]
          (let [expected (dec (count (take-while #(<= % y) (reductions + 0 hts))))]
            (is (= (min expected (dec n)) (v/index-at hs y)))))))))

(def all-rows (vec (for [i (range 10000)] {:id i :h (if (zero? (mod i 3)) 60 20)})))

(r/defn TallRows [start end]
  (r/for [row (r/server (subvec all-rows start end)) :recycle true]
    [:div.row {:data-height (str (:h row))} (str "row " (:id row))]))

(r/defn VarList []
  (v/Window {:total (r/server (count all-rows)) :estimate 20 :height 200} TallRows))

(def frame-queue (atom []))

(defn settle!
  "Like a browser: propagate, then measure after rendering, until quiet."
  [p]
  (loop [n 0]
    (ct/flush! p)
    (let [[q _] (reset-vals! frame-queue [])]
      (when (and (seq q) (< n 20))
        (run! #(%) q)
        (recur (inc n))))))

(deftest variable-height-window
  (reset! v/after-render #(swap! frame-queue conj %))
  (let [{:keys [dom] :as p} (render VarList)
        rows #(mapv h/text-content (h/query-all dom ".row"))
        spacer #(get @(:attrs (first (h/query-all dom ".hypercurve-window div"))) "style")]
    (settle! p)
    (testing "measured heights shrink the window to what fits"
      (is (= "row 0" (first (rows))))
      ;; 200px of rows that average ~33px, plus 3 overscan: fewer than the 17 an estimate of 20 gives
      (is (< (count (rows)) 17)))
    (testing "within measured rows, offsets are exact"
      ;; rows 0..8 measured: 60+20+20 per three rows, so 300px is row 9
      ;; (an estimate of 20px would say row 15); 3 rows of overscan before it
      (h/fire! (h/query dom ".hypercurve-window") "scroll" {:scroll-top 300})
      (settle! p)
      (is (= "row 6" (first (rows)))))
    (testing "a far jump lands where the estimate says, then those rows are measured"
      (h/fire! (h/query dom ".hypercurve-window") "scroll" {:scroll-top 10000})
      (settle! p)
      (let [first-shown (parse-long (subs (first (rows)) 4))]
        (is (< 480 first-shown 500) (str "first row " first-shown))))
    (reset! v/after-render (fn [f] (f)))))
