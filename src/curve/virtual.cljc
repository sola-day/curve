(ns curve.virtual
  "Virtual scrolling (design §16.1). Only the rows in view (plus overscan)
  are rendered, and when the rows come from the server only those cross the
  wire. Row frames are recycled by position (r/for ... :recycle true).

    (r/defn Rows [start end]
      (r/for [row (r/server (subvec all start end)) :recycle true] [:div.row (:name row)]))
    (virtual/Window {:total (r/server (count all)) :row-height 24 :height 480} Rows)"
  (:require [curve.core :as r]))

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

(r/defn Window [opts Rows]
  (let [{:keys [total row-height height overscan] :or {overscan 3}} opts
        !scroll (atom 0)
        scroll (r/watch !scroll)
        {:keys [start end before after]} (viewport scroll row-height height total overscan)]
    [:div.curve-window {:style {:height (str height "px") :overflow-y "auto"}
                        :on-scroll (fn [e] (reset! !scroll (or (r/event-scroll-top e) 0)))}
     [:div {:style {:height (str before "px")}}]
     (r/call Rows start end)
     [:div {:style {:height (str after "px")}}]]))
