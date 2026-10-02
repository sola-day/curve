(ns hello.app
  (:require [curve.core :as r]))

#?(:clj (defonce !clicks (atom 0)))
#?(:clj (defonce !messages (atom [])))

(r/defn App []
  (let [!draft (atom "")
        draft (r/watch !draft)
        post! (r/server (fn [text] (swap! !messages conj text)))]
    [:main
     [:h1 "Hello from Curve"]
     [:p "Server clicks: " [:b.count (r/server (r/watch !clicks))]]
     [:button#inc {:on-click (r/server (fn [_] (swap! !clicks inc)))} "click"]
     [:p "You typed: " [:i draft]]
     [:input#draft {:value draft :on-input (fn [e] (reset! !draft (r/event-value e)))}]
     [:button#post {:on-click (fn [_] (post! draft) (reset! !draft ""))} "post"]
     [:ul (r/for [m (r/server (r/watch !messages))] [:li m])]]))
