(ns local.app
  "Notes whose 'server' runs in a Web Worker."
  (:require [curve.core :as r]))

(defonce !notes (atom []))

(r/defn App []
  [:main
   [:h1 "Local notes"]
   [:ul (r/for [n (r/server (r/watch !notes))] [:li n])]
   [:button#add {:on-click (r/server (fn [_] (swap! !notes conj (str "note " (inc (count @!notes))))))} "add"]
   [:p.where (r/server (str "server site runs in " #?(:cljs (if (exists? js/document) "the page" "a worker") :clj "the JVM")))]])
