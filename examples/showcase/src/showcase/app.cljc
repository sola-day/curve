(ns showcase.app
  "A long page: a list of 100,000 rows of varying height in a virtual
  window, and a comments section far below that mounts (and queries the
  server) only when scrolled into view."
  (:require [curve.core :as r]
            [curve.virtual :as v]))

#?(:clj (defonce queries (atom 0)))
#?(:clj (defn load-comments! [] (str "queried " (swap! queries inc) " time(s)")))
#?(:clj (def rows (vec (for [i (range 100000)]
                         {:id i :text (apply str "row " i (repeat (mod (* i 7) 5) " — more text that wraps"))}))))

(r/defn Rows [start end]
  (r/for [row (r/server (subvec rows start end)) :recycle true]
    [:div.row {:style {:padding "4px" :border-bottom "1px solid #ddd" :width "200px"}} (:text row)]))

(r/defn App []
  [:main
   [:h1 "Showcase"]
   (v/Window {:total (r/server (count rows)) :estimate 30 :height 300} Rows)
   [:div.spacer {:style {:height "3000px"}}]
   (r/defer {:when :visible :margin 100 :placeholder [:p.wait {:style {:height "40px"}} "comments…"]}
     [:section.comments
      [:p.count (r/server (load-comments!))]])])
