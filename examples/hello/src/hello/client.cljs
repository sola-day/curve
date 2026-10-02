(ns hello.client
  (:require [curve.client :as client]
            [hello.app :as app]))

(defn ^:export init [] (client/start! app/App))
