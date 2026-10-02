(ns todomvc.client
  (:require [curve.client :as client]
            [todomvc.app :as app]))

(defn ^:export init [] (client/start! app/App))
