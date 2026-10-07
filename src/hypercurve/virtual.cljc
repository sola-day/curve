(ns hypercurve.virtual
  "Virtual scrolling (design §16.1). Only the rows in view (plus overscan)
  are rendered, and when the rows come from the server only those cross the
  wire. Row frames are recycled by position (r/for ... :recycle true).

  Fixed row height:
    (virtual/Window {:total n :row-height 24 :height 480} Rows)
  Variable row height: give an :estimate instead; rows are measured after
  they render and their heights kept in a Fenwick tree, so finding the row
  at a scroll offset stays O(log n) for any n:
    (virtual/Window {:total n :estimate 40 :height 480} Rows)
  Rows is a reactive fn of [start end] rendering one element per row:
    (r/defn Rows [start end]
      (r/for [row (r/server (subvec all start end)) :recycle true] [:div.row (:name row)]))"
  (:require [hypercurve.core :as r]))

;; ---------------------------------------------------------------- fixed

(defn viewport
  "Rows [start, end) to render, and the spacer heights around them."
  [scroll-top row-height height total overscan]
  (let [total (or total 0)
        start (max 0 (- (quot (long scroll-top) row-height) overscan))
        n (+ (quot height row-height) (* 2 overscan) 1)
        end (min total (+ start n))]
    {:start (min start end) :end end
     :before (* (min start end) row-height)
     :after (* (- total end) row-height)}))

;; ---------------------------------------------------------------- heights

(defn heights
  "n row heights, all estimated at first; prefix sums in a Fenwick tree."
  ([n estimate] (heights n estimate nil))
  ([n estimate known]
   (let [vals (double-array n)
         tree (double-array (inc n))]
     (dotimes [i n] (aset vals i (double (get known i estimate))))
     ;; O(n) build
     (dotimes [i n]
       (let [j (inc i)]
         (aset tree j (+ (aget tree j) (aget vals i)))
         (let [p (+ j (bit-and j (- j)))]
           (when (<= p n) (aset tree p (+ (aget tree p) (aget tree j)))))))
     {:n n :vals vals :tree tree :estimate estimate})))

(defn set-height!
  "Record row i's measured height. True when it changed."
  [{:keys [n ^doubles vals ^doubles tree]} i h]
  (let [d (- (double h) (aget vals i))]
    (if (< (if (neg? d) (- d) d) 0.5)
      false
      (do (aset vals i (double h))
          (loop [j (inc i)]
            (when (<= j n)
              (aset tree j (+ (aget tree j) d))
              (recur (+ j (bit-and j (- j))))))
          true))))

(defn prefix
  "Total height of rows [0, i)."
  [{:keys [^doubles tree]} i]
  (loop [j i acc 0.0]
    (if (pos? j) (recur (- j (bit-and j (- j))) (+ acc (aget tree j))) acc)))

(defn total-height [h] (prefix h (:n h)))

(defn index-at
  "The row at vertical offset y (the largest i with prefix(i) <= y)."
  [{:keys [n ^doubles tree]} y]
  (let [top (loop [b 1] (if (<= (* 2 b) n) (recur (* 2 b)) b))]
    (loop [pos 0 rem (double y) step top]
      (if (zero? step)
        (min pos (max 0 (dec n)))
        (let [nxt (+ pos step)]
          (if (and (<= nxt n) (<= (aget tree nxt) rem))
            (recur nxt (- rem (aget tree nxt)) (quot step 2))
            (recur pos rem (quot step 2))))))))

(defn variable-viewport
  "Like viewport, for measured heights."
  [h scroll-top height overscan]
  (if (zero? (:n h))
    {:start 0 :end 0 :before 0 :after 0}
    (let [n (:n h)
          first-row (index-at h scroll-top)
          last-row (index-at h (+ scroll-top height))
          start (max 0 (- first-row overscan))
          end (min n (+ last-row 1 overscan))]
      {:start start :end end :first-row first-row
       :before (prefix h start)
       :after (- (total-height h) (prefix h end))})))

(defn ensure-heights!
  "The heights for total rows, kept in !h; rebuilt (keeping what was
  measured) when the row count changes."
  [!h total estimate]
  (let [{:keys [h]} @!h]
    (if (and h (= total (:n h)))
      h
      (let [known (when h (into {} (for [i (range (min total (:n h)))] [i (aget ^doubles (:vals h) i)])))
            h' (heights total estimate known)]
        (swap! !h assoc :h h')
        h'))))

;; ---------------------------------------------------------------- measuring

(defonce ^{:doc "(fn [el]) -> height in px, or nil. Browser: layout height; JVM: a
  data-height attribute (headless DOM has no layout)."}
  measure
  (atom #?(:clj (fn [el] (some-> (get @(:attrs el) "data-height") parse-double))
           :cljs (fn [el] (.-height (.getBoundingClientRect el))))))

(defonce ^{:doc "How measuring waits for rendering to finish. Browser: the next
  animation frame; JVM: at once (tests replace it)."}
  after-render
  (atom #?(:clj (fn [f] (f)) :cljs (fn [f] (js/requestAnimationFrame f)))))

(defn- row-elements [el]
  #?(:clj (filterv #(= :element (:kind %)) @(:children el))
     :cljs (vec (array-seq (.-children el)))))

(defn- measure-rows!
  [el {:keys [start report!]}]
  (@after-render
    (fn []
      (let [changes (keep-indexed (fn [k row] (when-let [px (@measure row)] [(+ start k) px]))
                                  (row-elements el))]
        (when (seq changes)
          (let [shift (report! changes)]
            ;; scroll anchoring: rows above the first visible one changed size
            #?(:cljs (when-not (zero? shift)
                       (when-let [w (.-parentNode el)]
                         (set! (.-scrollTop w) (+ (.-scrollTop w) shift))))
               :clj shift)))))))

(defn rows-mount
  "r/foreign mount fn for the rows container: measure rows after render."
  [el props]
  (measure-rows! el props)
  {:update (fn [props] (measure-rows! el props))})

;; ---------------------------------------------------------------- component

(r/defn FixedWindow [opts Rows]
  (let [{:keys [total row-height height overscan] :or {overscan 3}} opts
        !scroll (atom 0)
        scroll (r/watch !scroll)
        {:keys [start end before after]} (viewport scroll row-height height total overscan)]
    [:div.hypercurve-window {:style {:height (str height "px") :overflow-y "auto"}
                        :on-scroll (fn [e] (reset! !scroll (or (r/event-scroll-top e) 0)))}
     [:div {:style {:height (str before "px")}}]
     (r/call Rows start end)
     [:div {:style {:height (str after "px")}}]]))

(r/defn VariableWindow [opts Rows]
  (let [{:keys [total estimate height overscan] :or {overscan 3}} opts
        !scroll (atom 0)
        scroll (r/watch !scroll)
        !h (atom {:h nil :v 0})
        version (:v (r/watch !h))
        h (ensure-heights! !h (or total 0) estimate)
        {:keys [start end before after first-row]} (do version (variable-viewport h scroll height overscan))
        ;; record measured heights; returns how much the rows above the first
        ;; visible row grew, so the view can stay anchored on what was seen
        report! (fn [changes]
                  (let [shift (reduce (fn [acc [i px]]
                                        (let [old (aget ^doubles (:vals h) i)]
                                          (if (set-height! h i px)
                                            (+ acc (if (< i first-row) (- px old) 0))
                                            acc)))
                                      0 changes)]
                    (swap! !h update :v inc)
                    shift))]
    [:div.hypercurve-window {:style {:height (str height "px") :overflow-y "auto"}
                        :on-scroll (fn [e] (reset! !scroll (or (r/event-scroll-top e) 0)))}
     [:div {:style {:height (str before "px")}}]
     [:div.hypercurve-rows {:hypercurve/foreign [rows-mount {:start start :end end :report! report!}]}
      (r/call Rows start end)]
     [:div {:style {:height (str after "px")}}]]))

(r/defn Window [opts Rows]
  (if (:row-height opts)
    (FixedWindow opts Rows)
    (VariableWindow opts Rows)))
