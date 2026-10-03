(ns showcase.client
  (:require [curve.client :as client]
            [showcase.app :as app]))

(defn ^:export init [] (client/start! app/App :router? false))
