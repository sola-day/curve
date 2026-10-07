(ns todomvc.client
  (:require [hypercurve.client :as client]
            [todomvc.app :as app]))

(defn ^:export init [] (client/start! app/App))
