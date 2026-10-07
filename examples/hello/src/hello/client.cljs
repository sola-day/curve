(ns hello.client
  (:require [hypercurve.client :as client]
            [hello.app :as app]))

(defn ^:export init [] (client/start! app/App))
