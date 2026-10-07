(ns sqlite-table.client
  (:require [hypercurve.client :as client]
            [sqlite-table.app :as app]))

(defn ^:export init [] (client/start! app/App))
