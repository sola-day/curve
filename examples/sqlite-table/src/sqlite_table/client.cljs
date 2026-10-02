(ns sqlite-table.client
  (:require [curve.client :as client]
            [sqlite-table.app :as app]))

(defn ^:export init [] (client/start! app/App))
