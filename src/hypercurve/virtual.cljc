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
      (r/for [row (r/server (subvec all start end)) :recycle true] [:div.row (:name row)]))

  Two dimensions (a canvas): boxes are {:x :y :w :h} in world units.
    (let [area (virtual/quantize viewport 400 200)]        ; same value for small pans
      (r/for [it (virtual/in-rect area box items) :by :id] ...))
  For many items keep a grid-index and update it from each change with
  index-sync; a query then touches only the cells under the viewport."
  (:require [hypercurve.core :as r]
            [hypercurve.delta :as delta]))

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

;; ---------------------------------------------------------------- 2D

(defn quantize
  "rect grown by margin on every side, then snapped outward to multiples of
  step. Small pans and zooms give an equal rect, so whatever is computed from
  it does not run again until the view crosses a step."
  [{:keys [x y w h]} margin step]
  (let [lo #(* step (Math/floor (/ % step)))
        hi #(* step (Math/ceil (/ % step)))
        x0 (lo (- x margin)) y0 (lo (- y margin))
        x1 (hi (+ x w margin)) y1 (hi (+ y h margin))]
    {:x x0 :y y0 :w (- x1 x0) :h (- y1 y0)}))

(defn intersects?
  "Do two {:x :y :w :h} boxes overlap (touching edges count)?"
  [a b]
  (and (<= (:x a) (+ (:x b) (:w b))) (<= (:x b) (+ (:x a) (:w a)))
       (<= (:y a) (+ (:y b) (:h b))) (<= (:y b) (+ (:y a) (:h a)))))

(defn in-rect
  "The items (a seq, or a map's values) whose (box item) meets rect, in order."
  [rect box items]
  (let [xs (if (map? items) (vals items) items)]
    (filterv #(when-let [b (box %)] (intersects? rect b)) xs)))

;; A uniform grid: cell [i j] holds the ids whose box overlaps it. Items much
;; larger than a cell sit in many cells; choose cell near the typical size.

(defn grid-index [cell] {:cell cell :cells {} :boxes {}})

(defn- cells-of [cell {:keys [x y w h]}]
  (let [f #(long (Math/floor (/ % cell)))]
    (for [i (range (f x) (inc (f (+ x w))))
          j (range (f y) (inc (f (+ y h))))]
      [i j])))

(defn index-remove [{:keys [cell boxes] :as idx} id]
  (if-let [b (get boxes id)]
    (-> (reduce (fn [idx c]
                  (let [s (disj (get-in idx [:cells c]) id)]
                    (if (empty? s) (update idx :cells dissoc c) (assoc-in idx [:cells c] s))))
                idx (cells-of cell b))
        (update :boxes dissoc id))
    idx))

(defn index-put [idx id b]
  (if (or (nil? b) (= b (get-in idx [:boxes id])))
    (if (nil? b) (index-remove idx id) idx)
    (let [idx (index-remove idx id)]
      (-> (reduce (fn [idx c] (update-in idx [:cells c] (fnil conj #{}) id)) idx (cells-of (:cell idx) b))
          (assoc-in [:boxes id] b)))))

(defn index-query
  "Ids whose box meets rect."
  [{:keys [cell cells boxes]} rect]
  (let [hits (into #{} (mapcat #(get cells %)) (cells-of cell rect))]
    (into #{} (filter #(intersects? rect (get boxes %))) hits)))

(declare index-patch)

(defn index-sync
  "Bring idx from the items map old to new ({id item}), box giving each
  item's box. Only the changed entries are touched when new was made from
  old (the usual case for successive values of a shared atom)."
  [idx box old new]
  (index-patch idx box new (delta/diff old new)))

(defn index-patch
  "Like index-sync when the delta from the old map to new is already known
  (a SharedAtom change carries it): no diff, work proportional to the change."
  [idx box new d]
  (let []
    (cond
      (nil? d) idx
      (= :m (first d))
      (let [{:keys [set dissoc patch]} (second d)
            idx (reduce index-remove idx dissoc)]
        (reduce (fn [idx id] (index-put idx id (box (get new id))))
                idx (concat (keys set) (keys patch))))
      :else
      (reduce-kv (fn [idx id it] (index-put idx id (box it)))
                 (grid-index (:cell idx)) new))))
